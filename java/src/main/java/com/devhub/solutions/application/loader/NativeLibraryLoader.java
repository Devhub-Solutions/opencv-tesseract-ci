package com.devhub.solutions.application.loader;

import java.io.*;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NativeLibraryLoader - Utility class for loading OpenCV &amp; Tesseract native libraries
 * across Linux, Windows, and macOS platforms.
 *
 * <p>Automatically detects the current OS and architecture, configures both
 * {@code java.library.path} and {@code jna.library.path} for proper library resolution,
 * extracts native libraries from the classpath (JAR-bundled) or loads from a filesystem
 * path, and manages the library loading lifecycle.</p>
 *
 * <h3>Key Fix (v1.2.0):</h3>
 * <p>Previous versions loaded libraries via {@code System.load()} but did not configure
 * JNA's library search path. This caused {@code UnsatisfiedLinkError} when JNA tried
 * to load libraries independently via {@code Native.load()}. Now, the resolved native
 * library directory is also set as {@code jna.library.path} so both JNI and JNA can
 * find the libraries.</p>
 *
 * <h3>Key Fix (v1.3.0):</h3>
 * <p>Windows support: MinGW-built DLLs use {@code lib} prefix (e.g., {@code libleptonica.dll}
 * instead of {@code leptonica.dll}). Windows also lacks RPATH, so all transitive dependencies
 * must be loaded explicitly in the correct order. The loader now handles both cases.</p>
 *
 * <p>Compatible with native-libs v0.0.3+ from opencv-tesseract-ci releases where:
 * <ul>
 *   <li>WITH_FFMPEG=OFF (no libdc1394, libavcodec, etc.)</li>
 *   <li>Image format libs (libjpeg, libpng, libtiff, libwebp, libopenjp2) are bundled</li>
 *   <li>RPATH=$ORIGIN is set on Linux .so files</li>
 *   <li>install_name_tool @rpath is set on macOS .dylib files</li>
 *   <li>libarchive/libcurl are disabled in Tesseract build</li>
 *   <li>Windows DLLs are MinGW-built with lib prefix (e.g., libleptonica.dll)</li>
 * </ul>
 * </p>
 *
 * <p>Usage:</p>
 * <pre>
 *   NativeLibraryLoader.loadAll();
 *   // or individually:
 *   NativeLibraryLoader.loadOpenCV();
 *   NativeLibraryLoader.loadTesseract();
 * </pre>
 */
public final class NativeLibraryLoader {

    private static final AtomicBoolean opencvLoaded = new AtomicBoolean(false);
    private static final AtomicBoolean tesseractLoaded = new AtomicBoolean(false);

    /** Default path where native libraries are searched on the filesystem */
    private static String nativeLibraryPath = System.getProperty("native.lib.path", "");

    /** Base path inside classpath/JAR where native libs are stored */
    private static final String CLASSPATH_NATIVE_PREFIX = "native-lib";

    /** Temp directory for extracting native libs from classpath */
    private static final Path TEMP_DIR;

    /** Resolved absolute path to the platform-specific native-lib directory */
    private static volatile String resolvedNativeLibDir;

    static {
        try {
            TEMP_DIR = Files.createTempDirectory("native-lib-");
            TEMP_DIR.toFile().deleteOnExit();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    deleteRecursively(TEMP_DIR);
                } catch (IOException e) {
                    // Ignore cleanup errors on shutdown
                }
            }));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temp directory for native libs", e);
        }
    }

    // ================================================================
    // OS & Architecture Detection
    // ================================================================

    public enum OS {
        LINUX("linux", "so"),
        WINDOWS("windows", "dll"),
        MACOS("macos", "dylib");

        private final String dirName;
        private final String libExtension;

        OS(String dirName, String libExtension) {
            this.dirName = dirName;
            this.libExtension = libExtension;
        }

        public String getDirName() { return dirName; }
        public String getLibExtension() { return libExtension; }
    }

    public enum Arch {
        X86_64("x86_64"),
        ARM64("arm64"),
        AARCH64("aarch64");

        private final String dirName;

        Arch(String dirName) {
            this.dirName = dirName;
        }

        public String getDirName() { return dirName; }
    }

    /**
     * Detects the current operating system.
     */
    public static OS detectOS() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH);
        if (osName.contains("linux") || osName.contains("nix") || osName.contains("nux")) {
            return OS.LINUX;
        } else if (osName.contains("win")) {
            return OS.WINDOWS;
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            return OS.MACOS;
        }
        throw new UnsupportedOperationException("Unsupported operating system: " + osName);
    }

    /**
     * Detects the current CPU architecture.
     */
    public static Arch detectArch() {
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ENGLISH);
        if (osArch.equals("x86_64") || osArch.equals("amd64")) {
            return Arch.X86_64;
        } else if (osArch.equals("aarch64") || osArch.equals("arm64")) {
            return Arch.ARM64;
        }
        throw new UnsupportedOperationException("Unsupported architecture: " + osArch);
    }

    /**
     * Returns the platform-specific library file name prefix.
     * Linux/macOS: "lib"
     * Windows (MSVC): ""
     * Windows (MinGW): "lib" — our CI builds use MinGW, so DLLs have "lib" prefix
     *
     * <p>Note: Since native-libs v0.0.3+ uses MinGW for Windows builds,
     * all Windows DLLs use the {@code lib} prefix convention (e.g., {@code libleptonica.dll}).</p>
     */
    public static String getLibPrefix() {
        // All platforms use "lib" prefix in our CI/CD builds
        // Linux/macOS: standard convention
        // Windows: MinGW convention (libleptonica.dll, libtesseract55.dll)
        return "lib";
    }

    // ================================================================
    // Public API - Library Loading
    // ================================================================

    /**
     * Sets a custom filesystem path where native libraries should be searched first.
     */
    public static void setNativeLibraryPath(String path) {
        nativeLibraryPath = path;
    }

    /**
     * Gets the current native library path.
     */
    public static String getNativeLibraryPath() {
        return nativeLibraryPath;
    }

    /**
     * Returns the resolved absolute path to the platform-specific native-lib directory,
     * or null if not yet resolved.
     *
     * <p>This is used by {@link com.devhub.solutions.application.tesseract.TessAPI}
     * to configure JNA's library search path.</p>
     */
    public static String getResolvedNativeLibDir() {
        return resolvedNativeLibDir;
    }

    /**
     * Loads the OpenCV native library. Thread-safe.
     *
     * <p>Loading order: Leptonica → Tesseract → OpenCV (if opencv_text module needs Tesseract).
     * This method only loads OpenCV; call loadTesseract() first if you need opencv_text.</p>
     *
     * <p>On Windows, OpenCV Java DLL ({@code libopencv_java4100.dll}) may not be available
     * in all native-libs packages. This method will gracefully fail if the DLL is missing.</p>
     */
    public static synchronized void loadOpenCV() {
        if (opencvLoaded.get()) {
            return;
        }

        OS os = detectOS();

        // Ensure library paths are configured for dependency resolution
        ensureLibraryPaths();

        // On Windows, load transitive dependencies first (no RPATH equivalent)
        if (os == OS.WINDOWS) {
            loadWindowsOpenCVDependencies();
        }

        // Try loading OpenCV with multiple name variations
        List<String> opencvNames = buildOpenCVLibNames();
        boolean loaded = tryLoadWithAlternatives(opencvNames, "opencv");

        if (loaded) {
            opencvLoaded.set(true);
            System.out.println("[NativeLoader] OpenCV loaded successfully");
        } else {
            String ext = os.getLibExtension();
            String missingDepsHint = buildMissingDepsHint("opencv", os);
            String errorMsg = String.format(
                "Failed to load OpenCV native library.%n" +
                "Attempted names: %s%n" +
                "%s%n" +
                "Download native-libs from: https://github.com/Devhub-Solutions/opencv-tesseract-ci/releases",
                opencvNames, missingDepsHint
            );
            throw new UnsatisfiedLinkError(errorMsg);
        }
    }

    /**
     * Loads the Tesseract OCR native library and its dependency (Leptonica).
     *
     * <p>On Windows, all transitive dependencies (image format DLLs, MinGW runtime)
     * are loaded explicitly before leptonica and tesseract, since Windows has no
     * RPATH mechanism for automatic dependency resolution.</p>
     *
     * <p>Note: With CI/CD v0.0.3+, libarchive and libcurl are disabled in the build,
     * so no additional system packages should be needed.</p>
     */
    public static synchronized void loadTesseract() {
        if (tesseractLoaded.get()) {
            return;
        }

        OS os = detectOS();

        // Ensure library paths are configured for dependency resolution
        ensureLibraryPaths();

        if (os == OS.WINDOWS) {
            // On Windows, load ALL transitive dependencies explicitly
            // Windows has no RPATH - DLLs must be findable via PATH or pre-loaded
            loadWindowsTransitiveDependencies();
        }

        // Load leptonica first (tesseract depends on it)
        List<String> leptNames = buildLeptonicaLibNames();
        boolean leptLoaded = tryLoadWithAlternatives(leptNames, "leptonica");
        if (!leptLoaded) {
            String missingDepsHint = buildMissingDepsHint("leptonica", os);
            String errorMsg = String.format(
                "Failed to load Leptonica native library.%n" +
                "Attempted names: %s%n" +
                "%s%n" +
                "Download native-libs from: https://github.com/Devhub-Solutions/opencv-tesseract-ci/releases",
                leptNames, missingDepsHint
            );
            throw new UnsatisfiedLinkError(errorMsg);
        }

        // Load tesseract
        List<String> tessNames = buildTesseractLibNames();
        boolean tessLoaded = tryLoadWithAlternatives(tessNames, "tesseract");
        if (!tessLoaded) {
            String missingDepsHint = buildMissingDepsHint("tesseract", os);
            String errorMsg = String.format(
                "Failed to load Tesseract native library.%n" +
                "Attempted names: %s%n" +
                "%s%n" +
                "Download native-libs from: https://github.com/Devhub-Solutions/opencv-tesseract-ci/releases",
                tessNames, missingDepsHint
            );
            throw new UnsatisfiedLinkError(errorMsg);
        }

        tesseractLoaded.set(true);
        System.out.println("[NativeLoader] Tesseract loaded successfully");
    }

    /**
     * Convenience method to load both OpenCV and Tesseract in the correct order.
     */
    public static synchronized void loadAll() {
        // Load Tesseract first (OpenCV opencv_text depends on it)
        loadTesseract();
        loadOpenCV();
    }

    public static boolean isOpenCVLoaded() {
        return opencvLoaded.get();
    }

    public static boolean isTesseractLoaded() {
        return tesseractLoaded.get();
    }

    // ================================================================
    // Library Path Configuration (KEY FIX for JNA)
    // ================================================================

    /**
     * Ensures that both {@code java.library.path} and {@code jna.library.path}
     * include the resolved native-lib directory.
     *
     * <p>This is the critical fix: JNA's {@code Native.load()} searches for libraries
     * using {@code jna.library.path} (not {@code java.library.path}). Without setting
     * this property, JNA cannot find native libraries loaded by this class via
     * {@code System.load()}, because JNA uses its own {@code dlopen()} mechanism
     * independent of the JVM's native library loading.</p>
     *
     * <p>The method also adds the native-lib directory to the Windows PATH environment
     * variable for DLL dependency resolution, and sets {@code LD_LIBRARY_PATH} hint
     * on Linux for transitive dependency resolution by the system dynamic linker.</p>
     */
    private static void ensureLibraryPaths() {
        String nativeDir = resolveNativeLibDir();
        if (nativeDir == null) {
            return;
        }

        resolvedNativeLibDir = nativeDir;

        // Fix 1: Set jna.library.path so JNA's Native.load() can find the libraries
        String jnaPath = System.getProperty("jna.library.path", "");
        if (!jnaPath.contains(nativeDir)) {
            String newJnaPath = nativeDir + File.pathSeparator + jnaPath;
            System.setProperty("jna.library.path", newJnaPath);
            System.out.println("[NativeLoader] Set jna.library.path to include: " + nativeDir);
        }

        // Fix 2: Set java.library.path for System.loadLibrary() fallback
        String javaLibPath = System.getProperty("java.library.path", "");
        if (!javaLibPath.contains(nativeDir)) {
            String newJavaLibPath = nativeDir + File.pathSeparator + javaLibPath;
            System.setProperty("java.library.path", newJavaLibPath);
        }

        OS os = detectOS();
        if (os == OS.WINDOWS) {
            // Fix 3: Add native-lib dir to Windows PATH for DLL dependency resolution
            // This is critical because Windows has no RPATH mechanism
            addToWindowsPath(nativeDir);
        } else if (os == OS.LINUX) {
            // Fix 3: On Linux, hint about LD_LIBRARY_PATH for transitive deps
            String existingLdPath = System.getenv("LD_LIBRARY_PATH");
            if (existingLdPath == null || !existingLdPath.contains(nativeDir)) {
                System.out.println("[NativeLoader] HINT: For full dependency resolution, run with:");
                System.out.println("[NativeLoader]   LD_LIBRARY_PATH=" + nativeDir + " java ...");
            }
        }
    }

    /**
     * Adds a directory to the Windows PATH environment variable at runtime.
     *
     * <p>This is necessary because Windows has no RPATH mechanism - DLL dependencies
     * are resolved via the PATH environment variable. By adding the native-lib directory
     * to PATH, the Windows DLL loader can find all dependency DLLs.</p>
     *
     * <p>Uses reflection to modify the process environment map since
     * {@code System.getenv()} is normally read-only. Falls back to
     * {@code ProcessBuilder} environment if reflection fails.</p>
     */
    private static void addToWindowsPath(String dir) {
        String currentPath = System.getenv("PATH");
        if (currentPath != null && currentPath.contains(dir)) {
            return; // Already in PATH
        }

        try {
            // Try to modify the process environment via reflection
            Map<String, String> env = System.getenv();
            Class<?> clazz = env.getClass();
            Field field = clazz.getDeclaredField("m");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, String> writableEnv = (Map<String, String>) field.get(env);
            String newPath = dir + ";" + (currentPath != null ? currentPath : "");
            writableEnv.put("PATH", newPath);
            System.out.println("[NativeLoader] Added to Windows PATH: " + dir);
        } catch (Exception e) {
            // Reflection hack may fail on some JVMs; try alternative approach
            try {
                // Alternative: use ProcessBuilder environment (only affects child processes)
                // But at least set the property for our own loading logic
                System.out.println("[NativeLoader] Could not modify Windows PATH directly. " +
                    "Pre-loading all dependency DLLs instead.");
            } catch (Exception e2) {
                // Ignore
            }
        }
    }

    /**
     * Resolves the absolute path to the platform-specific native-lib directory.
     */
    private static String resolveNativeLibDir() {
        OS os = detectOS();
        Arch arch = detectArch();
        String platformDir = os.getDirName() + "/" + arch.getDirName();

        // Check custom path first
        if (!nativeLibraryPath.isEmpty()) {
            Path dir = Paths.get(nativeLibraryPath, platformDir);
            if (Files.isDirectory(dir)) {
                return dir.toAbsolutePath().toString();
            }
            // Try flat directory
            if (Files.isDirectory(Paths.get(nativeLibraryPath))) {
                return Paths.get(nativeLibraryPath).toAbsolutePath().toString();
            }
        }

        // Check project-relative native-lib directory
        Path projectDir = Paths.get("native-lib", platformDir);
        if (Files.isDirectory(projectDir)) {
            return projectDir.toAbsolutePath().toString();
        }

        return null;
    }

    // ================================================================
    // Library Name Builders - Multi-variant for cross-platform
    // ================================================================

    /**
     * Returns the primary OpenCV library name for the current platform.
     */
    private static String buildOpenCVLibName() {
        return getLibPrefix() + "opencv_java4100";
    }

    /**
     * Returns all possible OpenCV library names to try, in order of preference.
     * Handles both MinGW (lib prefix) and MSVC (no prefix) naming on Windows.
     */
    private static List<String> buildOpenCVLibNames() {
        List<String> names = new ArrayList<>();
        OS os = detectOS();

        // Primary name (lib prefix - matches MinGW builds)
        names.add("libopencv_java4100");

        // Non-prefixed variant (matches MSVC builds)
        names.add("opencv_java4100");

        if (os == OS.LINUX) {
            names.add("libopencv_java410");
            names.add("opencv_java410");
        }

        return names;
    }

    /**
     * Returns all possible Tesseract library names to try.
     * Windows MinGW: libtesseract55, tesseract55
     * Linux/macOS: libtesseract + versioned variants
     */
    private static List<String> buildTesseractLibNames() {
        List<String> names = new ArrayList<>();
        OS os = detectOS();

        if (os == OS.WINDOWS) {
            // MinGW build uses lib prefix: libtesseract55.dll
            names.add("libtesseract55");
            // Also try without lib prefix (MSVC convention)
            names.add("tesseract55");
            // Try generic name
            names.add("libtesseract");
            names.add("tesseract");
        } else if (os == OS.LINUX) {
            names.add("libtesseract");
            names.add("tesseract");
            names.add("libtesseract.so.5");
            names.add("tesseract.so.5");
            names.add("libtesseract.so.5.5");
            names.add("libtesseract.so.5.5.0");
        } else { // MACOS
            names.add("libtesseract");
            names.add("tesseract");
            names.add("libtesseract.5");
            names.add("tesseract.5");
            names.add("libtesseract.5.5");
            names.add("libtesseract.5.5.0");
        }

        return names;
    }

    /**
     * Returns all possible Leptonica library names to try.
     * Windows MinGW: libleptonica, leptonica
     * Linux/macOS: liblept + versioned variants
     */
    private static List<String> buildLeptonicaLibNames() {
        List<String> names = new ArrayList<>();
        OS os = detectOS();

        if (os == OS.WINDOWS) {
            // MinGW build uses lib prefix: libleptonica.dll
            names.add("libleptonica");
            // Also try without lib prefix
            names.add("leptonica");
            // Try lept variant
            names.add("liblept");
            names.add("lept");
        } else if (os == OS.LINUX) {
            names.add("liblept");
            names.add("lept");
            names.add("libleptonica");
            names.add("leptonica");
            names.add("liblept.so.5");
            names.add("lept.so.5");
            names.add("libleptonica.so.6");
            names.add("leptonica.so.6");
            names.add("libleptonica.so.6.0.0");
        } else { // MACOS
            names.add("liblept");
            names.add("lept");
            names.add("libleptonica");
            names.add("leptonica");
            names.add("liblept.5");
            names.add("lept.5");
            names.add("libleptonica.6");
            names.add("leptonica.6");
            names.add("libleptonica.6.0.0");
        }

        return names;
    }

    // ================================================================
    // Windows-specific: Transitive Dependency Loading
    // ================================================================

    /**
     * Loads all transitive dependency DLLs on Windows.
     *
     * <p>On Linux, RPATH=$ORIGIN ensures the dynamic linker finds transitive
     * dependencies in the same directory. On macOS, @rpath serves the same purpose.
     * On Windows, there is no equivalent mechanism - DLLs are found via PATH or
     * must be pre-loaded.</p>
     *
     * <p>This method loads all dependency DLLs in the correct order before
     * leptonica and tesseract, ensuring that when the Windows DLL loader needs
     * a dependency, it's already in the process's address space.</p>
     *
     * <p>Loading order:
     * <ol>
     *   <li>MinGW runtime DLLs (libgcc_s_seh-1, libstdc++-6, libwinpthread-1)</li>
     *   <li>zlib1 (fundamental compression library)</li>
     *   <li>Image format DLLs (liblzma-5, libjpeg-8, libpng16-16, libtiff-6, libwebp-7, etc.)</li>
     *   <li>These are loaded silently - failures are logged but don't prevent loading
     *       leptonica/tesseract (they may fail later if a real dependency is missing)</li>
     * </ol>
     * </p>
     */
    private static void loadWindowsTransitiveDependencies() {
        String nativeDir = resolveNativeLibDir();
        if (nativeDir == null) {
            System.err.println("[NativeLoader] Warning: Cannot resolve native-lib dir for Windows dependency loading");
            return;
        }

        // Ordered list of Windows transitive dependencies
        // Each entry: {filename, description}
        String[][] dependencies = {
            // MinGW runtime (required by all C++ DLLs)
            {"libgcc_s_seh-1.dll", "MinGW GCC runtime"},
            {"libstdc++-6.dll", "MinGW C++ runtime"},
            {"libwinpthread-1.dll", "MinGW pthread runtime"},

            // Fundamental compression library
            {"zlib1.dll", "zlib compression"},

            // Image format libraries (leptonica dependencies)
            {"liblzma-5.dll", "LZMA compression"},
            {"libjpeg-8.dll", "JPEG image format"},
            {"libpng16-16.dll", "PNG image format"},
            {"libtiff-6.dll", "TIFF image format"},
            {"libtiffxx-6.dll", "TIFF C++ wrapper"},
            {"libwebpdecoder-3.dll", "WebP decoder"},
            {"libwebp-7.dll", "WebP image format"},
            {"libwebpdemux-2.dll", "WebP demuxer"},
            {"libwebpmux-3.dll", "WebP muxer"},
        };

        System.out.println("[NativeLoader] Loading Windows transitive dependencies...");

        for (String[] dep : dependencies) {
            String fileName = dep[0];
            String description = dep[1];
            Path libPath = Paths.get(nativeDir, fileName);

            if (Files.exists(libPath)) {
                try {
                    System.load(libPath.toAbsolutePath().toString());
                    System.out.println("[NativeLoader]   Loaded: " + fileName + " (" + description + ")");
                } catch (UnsatisfiedLinkError e) {
                    // Log but don't fail - the dependency might not be needed
                    System.err.println("[NativeLoader]   Warning: Could not load " + fileName +
                        " (" + description + "): " + e.getMessage());
                }
            } else {
                // DLL not found in native-lib dir - might be in system PATH
                System.out.println("[NativeLoader]   Not found (will use system PATH if needed): " + fileName);
            }
        }

        System.out.println("[NativeLoader] Windows transitive dependency loading complete.");
    }

    /**
     * Loads Windows dependencies specific to OpenCV.
     * OpenCV Java shares some image format libraries with Tesseract/Leptonica.
     * If Tesseract is already loaded, most dependencies are already in the process.
     */
    private static void loadWindowsOpenCVDependencies() {
        if (tesseractLoaded.get()) {
            // Tesseract already loaded - most transitive deps are already loaded
            System.out.println("[NativeLoader] Tesseract already loaded, OpenCV deps should be satisfied.");
            return;
        }

        // If Tesseract isn't loaded yet, we still need the image format DLLs
        loadWindowsTransitiveDependencies();
    }

    // ================================================================
    // Core Loading Logic - Multi-strategy with alternatives
    // ================================================================

    /**
     * Tries to load a library using multiple name variants, trying each strategy
     * for each name until one succeeds.
     *
     * @param libNames list of library name variants to try (without extension)
     * @param category category for error reporting ("opencv", "tesseract", "leptonica")
     * @return true if loading succeeded, false if all attempts failed
     */
    private static boolean tryLoadWithAlternatives(List<String> libNames, String category) {
        OS os = detectOS();
        Arch arch = detectArch();
        String ext = os.getLibExtension();
        String platformDir = os.getDirName() + "/" + arch.getDirName();

        for (String libName : libNames) {
            String fullLibFileName = libName + "." + ext;

            System.out.println("[NativeLoader] Attempting to load: " + fullLibFileName +
                               " (OS=" + os + ", Arch=" + arch + ")");

            // Strategy 1: Load from custom filesystem path
            if (!nativeLibraryPath.isEmpty()) {
                // Try with platform subdirectory
                Path libPath = Paths.get(nativeLibraryPath, platformDir, fullLibFileName);
                if (Files.exists(libPath)) {
                    try {
                        System.load(libPath.toAbsolutePath().toString());
                        System.out.println("[NativeLoader] Loaded from custom path: " + libPath);
                        return true;
                    } catch (UnsatisfiedLinkError e) {
                        System.err.println("[NativeLoader] Failed to load " + libPath + ": " + e.getMessage());
                    }
                }
                // Try flat directory (without platform subdirectory)
                libPath = Paths.get(nativeLibraryPath, fullLibFileName);
                if (Files.exists(libPath)) {
                    try {
                        System.load(libPath.toAbsolutePath().toString());
                        System.out.println("[NativeLoader] Loaded from custom path (flat): " + libPath);
                        return true;
                    } catch (UnsatisfiedLinkError e) {
                        System.err.println("[NativeLoader] Failed to load " + libPath + ": " + e.getMessage());
                    }
                }
            }

            // Strategy 1b: Auto-detect project-relative native-lib directory
            Path projectNativeLib = Paths.get("native-lib", platformDir);
            if (Files.isDirectory(projectNativeLib)) {
                Path libPath = projectNativeLib.resolve(fullLibFileName);
                if (Files.exists(libPath)) {
                    try {
                        System.load(libPath.toAbsolutePath().toString());
                        System.out.println("[NativeLoader] Loaded from project native-lib: " + libPath);
                        return true;
                    } catch (UnsatisfiedLinkError e) {
                        System.err.println("[NativeLoader] Failed to load " + libPath + ": " + e.getMessage());
                    }
                }
            }

            // Strategy 2: Load from java.library.path
            String javaLibPath = System.getProperty("java.library.path", "");
            if (!javaLibPath.isEmpty()) {
                for (String pathEntry : javaLibPath.split(File.pathSeparator)) {
                    if (pathEntry.trim().isEmpty()) continue;
                    Path libPath = Paths.get(pathEntry, fullLibFileName);
                    if (Files.exists(libPath)) {
                        try {
                            System.load(libPath.toAbsolutePath().toString());
                            System.out.println("[NativeLoader] Loaded from java.library.path: " + libPath);
                            return true;
                        } catch (UnsatisfiedLinkError e) {
                            System.err.println("[NativeLoader] Failed to load " + libPath + ": " + e.getMessage());
                        }
                    }
                }
            }

            // Strategy 3: Extract from classpath to temp dir and load
            String classpathPath = CLASSPATH_NATIVE_PREFIX + "/" + platformDir + "/" + fullLibFileName;
            try {
                if (extractAndLoadFromClasspath(classpathPath, fullLibFileName)) {
                    System.out.println("[NativeLoader] Loaded from classpath: " + classpathPath);
                    return true;
                }
            } catch (IOException e) {
                System.err.println("[NativeLoader] Failed to extract from classpath: " + e.getMessage());
            }

            // Strategy 4: System.loadLibrary with the name (without extension)
            try {
                System.loadLibrary(libName);
                System.out.println("[NativeLoader] Loaded via System.loadLibrary: " + libName);
                return true;
            } catch (UnsatisfiedLinkError e) {
                // Try next alternative name
            }
        }

        return false;
    }

    /**
     * Builds a hint about missing system dependencies based on the category and OS.
     */
    private static String buildMissingDepsHint(String category, OS os) {
        StringBuilder hint = new StringBuilder();

        if (os == OS.WINDOWS) {
            hint.append("Windows troubleshooting:\n");
            switch (category) {
                case "opencv":
                    hint.append("  1. Ensure native-lib/windows/x86_64/ contains libopencv_java4100.dll\n");
                    hint.append("     (Note: OpenCV Java DLL may not be included in all native-libs packages)\n");
                    hint.append("  2. Check that all image format DLLs are in the same directory\n");
                    hint.append("  3. Run with: java -Dnative.lib.path=./native-lib ...\n");
                    hint.append("  4. Or use run.bat which sets PATH automatically\n");
                    break;
                case "tesseract":
                    hint.append("  1. Ensure native-lib/windows/x86_64/ contains libtesseract55.dll and libleptonica.dll\n");
                    hint.append("  2. Check that all dependency DLLs (libjpeg-8.dll, libpng16-16.dll, etc.) are present\n");
                    hint.append("  3. Run with: java -Dnative.lib.path=./native-lib ...\n");
                    hint.append("  4. Or use run.bat which sets PATH automatically\n");
                    break;
                case "leptonica":
                    hint.append("  1. Ensure native-lib/windows/x86_64/ contains libleptonica.dll\n");
                    hint.append("  2. Check that dependency DLLs (libjpeg-8.dll, libpng16-16.dll, etc.) are present\n");
                    hint.append("  3. Run with: java -Dnative.lib.path=./native-lib ...\n");
                    hint.append("  4. Or use run.bat which sets PATH automatically\n");
                    break;
            }
        } else if (os == OS.LINUX) {
            hint.append("Linux troubleshooting:\n");
            switch (category) {
                case "opencv":
                    hint.append("  1. Ensure native-lib/linux/x86_64/ contains libopencv_java4100.so\n");
                    hint.append("  2. Set LD_LIBRARY_PATH to include native-lib directory:\n");
                    hint.append("     LD_LIBRARY_PATH=./native-lib/linux/x86_64 java ...\n");
                    hint.append("  3. On Linux, install system dependencies if needed:\n");
                    hint.append("     sudo apt install libarchive13 libcurl4\n");
                    hint.append("  4. Or use run.sh which sets LD_LIBRARY_PATH automatically\n");
                    break;
                case "tesseract":
                    hint.append("  1. Ensure native-lib/linux/x86_64/ contains libtesseract.so and liblept.so\n");
                    hint.append("  2. Set LD_LIBRARY_PATH to include native-lib directory:\n");
                    hint.append("     LD_LIBRARY_PATH=./native-lib/linux/x86_64 java ...\n");
                    hint.append("  3. Verify jna.library.path includes the native-lib directory:\n");
                    hint.append("     java -Djna.library.path=./native-lib/linux/x86_64 ...\n");
                    hint.append("  4. Or use run.sh which sets paths automatically\n");
                    break;
                case "leptonica":
                    hint.append("  1. Ensure native-lib/linux/x86_64/ contains liblept.so\n");
                    hint.append("  2. All Leptonica dependencies should be bundled.\n");
                    hint.append("  3. If still failing, run with:\n");
                    hint.append("     LD_LIBRARY_PATH=./native-lib/linux/x86_64 java ...\n");
                    break;
            }
        } else if (os == OS.MACOS) {
            hint.append("macOS troubleshooting:\n");
            switch (category) {
                case "opencv":
                    hint.append("  1. Ensure native-lib/macos/x86_64/ contains libopencv_java4100.dylib\n");
                    hint.append("  2. Run with: java -Dnative.lib.path=./native-lib ...\n");
                    hint.append("  3. Or use run.sh which sets paths automatically\n");
                    break;
                case "tesseract":
                    hint.append("  1. Ensure native-lib/macos/x86_64/ contains libtesseract.dylib and libleptonica.dylib\n");
                    hint.append("  2. Verify jna.library.path includes the native-lib directory\n");
                    hint.append("  3. Or use run.sh which sets paths automatically\n");
                    break;
                case "leptonica":
                    hint.append("  1. Ensure native-lib/macos/x86_64/ contains libleptonica.dylib\n");
                    hint.append("  2. Or use run.sh which sets paths automatically\n");
                    break;
            }
        }

        return hint.toString();
    }

    // ================================================================
    // Classpath Extraction
    // ================================================================

    private static boolean extractAndLoadFromClasspath(String classpathPath, String libFileName) throws IOException {
        // Try classloader first (works inside JAR)
        InputStream is = NativeLibraryLoader.class.getClassLoader().getResourceAsStream(classpathPath);
        if (is == null) {
            is = Thread.currentThread().getContextClassLoader().getResourceAsStream(classpathPath);
        }
        if (is == null) {
            is = ClassLoader.getSystemResourceAsStream(classpathPath);
        }
        if (is == null) {
            return false;
        }

        try (InputStream inputStream = is) {
            Path targetFile = TEMP_DIR.resolve(libFileName);
            Files.copy(inputStream, targetFile, StandardCopyOption.REPLACE_EXISTING);

            // Set executable permission on Unix
            if (detectOS() != OS.WINDOWS) {
                try {
                    Set<PosixFilePermission> perms = Files.getPosixFilePermissions(targetFile);
                    perms.add(PosixFilePermission.OWNER_EXECUTE);
                    perms.add(PosixFilePermission.GROUP_EXECUTE);
                    perms.add(PosixFilePermission.OTHERS_EXECUTE);
                    Files.setPosixFilePermissions(targetFile, perms);
                } catch (Exception e) {
                    targetFile.toFile().setExecutable(true);
                }
            }

            System.load(targetFile.toAbsolutePath().toString());
            return true;
        }
    }

    // ================================================================
    // Utility Methods 
    // ================================================================

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path entry : stream) {
                    deleteRecursively(entry);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    /**
     * Returns diagnostic information about the current platform and library loading status.
     */
    public static String getDiagnosticInfo() {
        OS os = detectOS();
        Arch arch = detectArch();
        StringBuilder sb = new StringBuilder();
        sb.append("=== Native Library Loader Diagnostics ===\n");
        sb.append("OS: ").append(os).append("\n");
        sb.append("Architecture: ").append(arch).append("\n");
        sb.append("Lib Prefix: ").append(getLibPrefix()).append("\n");
        sb.append("Lib Extension: ").append(os.getLibExtension()).append("\n");
        sb.append("Custom Native Path: ").append(nativeLibraryPath.isEmpty() ? "(not set)" : nativeLibraryPath).append("\n");
        sb.append("java.library.path: ").append(System.getProperty("java.library.path", "(not set)")).append("\n");
        sb.append("jna.library.path: ").append(System.getProperty("jna.library.path", "(not set)")).append("\n");
        sb.append("native.lib.path: ").append(System.getProperty("native.lib.path", "(not set)")).append("\n");
        sb.append("Temp Dir: ").append(TEMP_DIR).append("\n");
        sb.append("OpenCV Loaded: ").append(opencvLoaded.get()).append("\n");
        sb.append("Tesseract Loaded: ").append(tesseractLoaded.get()).append("\n");

        // Show resolved native-lib dir
        String resolvedDir = resolveNativeLibDir();
        sb.append("Resolved Native Dir: ").append(resolvedDir != null ? resolvedDir : "(not found)").append("\n");

        if (os == OS.WINDOWS) {
            sb.append("Windows PATH: ").append(System.getenv("PATH") != null ? "(set)" : "(not set)").append("\n");
        }

        // Show expected library file names
        sb.append("\nExpected Library Files (in order of preference):\n");
        sb.append("  OpenCV:\n");
        for (String name : buildOpenCVLibNames()) {
            sb.append("    ").append(name).append(".").append(os.getLibExtension()).append("\n");
        }
        sb.append("  Tesseract:\n");
        for (String name : buildTesseractLibNames()) {
            sb.append("    ").append(name).append(".").append(os.getLibExtension()).append("\n");
        }
        sb.append("  Leptonica:\n");
        for (String name : buildLeptonicaLibNames()) {
            sb.append("    ").append(name).append(".").append(os.getLibExtension()).append("\n");
        }

        // Show platform-specific environment info
        if (os == OS.LINUX) {
            sb.append("\nLD_LIBRARY_PATH: ").append(System.getenv("LD_LIBRARY_PATH") != null ?
                System.getenv("LD_LIBRARY_PATH") : "(not set)").append("\n");
            if (resolvedDir != null) {
                sb.append("\nRecommended run command:\n");
                sb.append("  LD_LIBRARY_PATH=").append(resolvedDir).append(" java -Dnative.lib.path=./native-lib -cp target/opencv-tesseract-ci-1.3.0.jar com.devhub.solutions.application.ApplicationMain\n");
            }
        } else if (os == OS.WINDOWS && resolvedDir != null) {
            sb.append("\nRecommended run command:\n");
            sb.append("  run.bat\n");
            sb.append("  OR:\n");
            sb.append("  set PATH=").append(resolvedDir).append(";%PATH%\n");
            sb.append("  java -Dnative.lib.path=./native-lib -cp target/opencv-tesseract-ci-1.3.0.jar com.devhub.solutions.application.ApplicationMain\n");
        }

        return sb.toString();
    }

    /**
     * Resets the loading state (for testing purposes only).
     */
    public static synchronized void resetState() {
        opencvLoaded.set(false);
        tesseractLoaded.set(false);
    }
}
