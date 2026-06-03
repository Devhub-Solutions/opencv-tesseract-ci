package com.devhub.solutions.application.loader;

import java.io.*;
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
 * <p>Compatible with native-libs v0.0.3+ from opencv-tesseract-ci releases where:
 * <ul>
 *   <li>WITH_FFMPEG=OFF (no libdc1394, libavcodec, etc.)</li>
 *   <li>Image format libs (libjpeg, libpng, libtiff, libwebp, libopenjp2) are bundled</li>
 *   <li>RPATH=$ORIGIN is set on Linux .so files</li>
 *   <li>install_name_tool @rpath is set on macOS .dylib files</li>
 *   <li>libarchive/libcurl are disabled in Tesseract build</li>
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
     * Linux/macOS: "lib", Windows: ""
     */
    public static String getLibPrefix() {
        return detectOS() == OS.WINDOWS ? "" : "lib";
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
     */
    public static synchronized void loadOpenCV() {
        if (opencvLoaded.get()) {
            return;
        }

        OS os = detectOS();
        String libName = buildOpenCVLibName();

        // Ensure library paths are configured for dependency resolution
        ensureLibraryPaths();

        loadLibrary(libName, "opencv");
        opencvLoaded.set(true);
        System.out.println("[NativeLoader] OpenCV loaded successfully");
    }

    /**
     * Loads the Tesseract OCR native library and its dependency (Leptonica).
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

        // Load leptonica first (tesseract depends on it)
        String leptName = buildLeptonicaLibName();
        loadLibrary(leptName, "leptonica");

        // Load tesseract
        String tessName = buildTesseractLibName();
        loadLibrary(tessName, "tesseract");

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
     * <p>The method also sets {@code LD_LIBRARY_PATH} hint on Linux for transitive
     * dependency resolution by the system dynamic linker.</p>
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

        // Fix 3: On Linux, hint about LD_LIBRARY_PATH for transitive deps
        OS os = detectOS();
        if (os == OS.LINUX) {
            String existingLdPath = System.getenv("LD_LIBRARY_PATH");
            if (existingLdPath == null || !existingLdPath.contains(nativeDir)) {
                System.out.println("[NativeLoader] HINT: For full dependency resolution, run with:");
                System.out.println("[NativeLoader]   LD_LIBRARY_PATH=" + nativeDir + " java ...");
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
    // Library Name Builders
    // ================================================================

    private static String buildOpenCVLibName() {
        String prefix = getLibPrefix();
        return prefix + "opencv_java4100";
    }

    private static String buildTesseractLibName() {
        OS os = detectOS();
        String prefix = getLibPrefix();
        switch (os) {
            case WINDOWS: return prefix + "tesseract55";
            default:      return prefix + "tesseract";
        }
    }

    private static String buildLeptonicaLibName() {
        OS os = detectOS();
        String prefix = getLibPrefix();
        switch (os) {
            case WINDOWS: return prefix + "leptonica";
            default:      return prefix + "lept";
        }
    }

    // ================================================================
    // Alternative Library Names
    // ================================================================

    /**
     * Returns alternative library names to try when the primary name fails.
     */
    private static List<String> getAlternativeLibNames(String libName, String category) {
        List<String> names = new ArrayList<>();
        names.add(libName); // Primary name

        OS os = detectOS();
        if (os == OS.LINUX) {
            switch (category) {
                case "tesseract":
                    names.add("tesseract.so.5");
                    names.add("tesseract.so.5.5");
                    names.add("tesseract.so.5.5.0");
                    break;
                case "leptonica":
                    names.add("leptonica.so.6");
                    names.add("leptonica.so.6.0.0");
                    names.add("lept.so.5");
                    break;
                case "opencv":
                    names.add("opencv_java410");
                    break;
            }
        } else if (os == OS.MACOS) {
            switch (category) {
                case "tesseract":
                    names.add("tesseract.5");
                    names.add("tesseract.5.5");
                    names.add("tesseract.5.5.0");
                    break;
                case "leptonica":
                    names.add("leptonica.6");
                    names.add("leptonica.6.0.0");
                    names.add("lept.5");
                    break;
            }
        }
        return names;
    }

    /**
     * Returns alternative filenames to search for in the filesystem.
     */
    private static List<String> getAlternativeFileNames(String libName, String category) {
        OS os = detectOS();
        String ext = os.getLibExtension();
        String prefix = getLibPrefix();
        List<String> fileNames = new ArrayList<>();
        fileNames.add(libName + "." + ext); // Primary filename

        if (os == OS.LINUX) {
            switch (category) {
                case "tesseract":
                    fileNames.add(prefix + "tesseract.so.5");
                    fileNames.add(prefix + "tesseract.so.5.5");
                    fileNames.add(prefix + "tesseract.so.5.5.0");
                    break;
                case "leptonica":
                    fileNames.add(prefix + "leptonica.so.6");
                    fileNames.add(prefix + "leptonica.so.6.0.0");
                    fileNames.add(prefix + "lept.so.5");
                    break;
                case "opencv":
                    fileNames.add(prefix + "opencv_java410.so");
                    break;
            }
        } else if (os == OS.MACOS) {
            switch (category) {
                case "tesseract":
                    fileNames.add(prefix + "tesseract.5.dylib");
                    fileNames.add(prefix + "tesseract.5.5.dylib");
                    fileNames.add(prefix + "tesseract.5.5.0.dylib");
                    break;
                case "leptonica":
                    fileNames.add(prefix + "leptonica.6.dylib");
                    fileNames.add(prefix + "leptonica.6.0.0.dylib");
                    fileNames.add(prefix + "lept.5.dylib");
                    break;
            }
        }
        return fileNames;
    }

    // ================================================================
    // Core Loading Logic - Multi-strategy
    // ================================================================

    private static void loadLibrary(String libName, String category) {
        OS os = detectOS();
        Arch arch = detectArch();
        String ext = os.getLibExtension();
        String platformDir = os.getDirName() + "/" + arch.getDirName();
        String fullLibFileName = libName + "." + ext;

        System.out.println("[NativeLoader] Attempting to load: " + fullLibFileName +
                           " (OS=" + os + ", Arch=" + arch + ")");

        List<String> alternativeFileNames = getAlternativeFileNames(libName, category);

        // Strategy 1: Load from custom filesystem path
        if (!nativeLibraryPath.isEmpty()) {
            for (String fileName : alternativeFileNames) {
                Path libPath = Paths.get(nativeLibraryPath, platformDir, fileName);
                if (Files.exists(libPath)) {
                    System.load(libPath.toAbsolutePath().toString());
                    System.out.println("[NativeLoader] Loaded from custom path: " + libPath);
                    return;
                }
                // Also try flat directory (without platform subdirectory)
                libPath = Paths.get(nativeLibraryPath, fileName);
                if (Files.exists(libPath)) {
                    System.load(libPath.toAbsolutePath().toString());
                    System.out.println("[NativeLoader] Loaded from custom path (flat): " + libPath);
                    return;
                }
            }
        }

        // Strategy 1b: Auto-detect project-relative native-lib directory
        Path projectNativeLib = Paths.get("native-lib", platformDir);
        if (Files.isDirectory(projectNativeLib)) {
            for (String fileName : alternativeFileNames) {
                Path libPath = projectNativeLib.resolve(fileName);
                if (Files.exists(libPath)) {
                    System.load(libPath.toAbsolutePath().toString());
                    System.out.println("[NativeLoader] Loaded from project native-lib: " + libPath);
                    return;
                }
            }
        }

        // Strategy 2: Load from java.library.path
        String javaLibPath = System.getProperty("java.library.path", "");
        if (!javaLibPath.isEmpty()) {
            for (String pathEntry : javaLibPath.split(File.pathSeparator)) {
                for (String fileName : alternativeFileNames) {
                    Path libPath = Paths.get(pathEntry, fileName);
                    if (Files.exists(libPath)) {
                        System.load(libPath.toAbsolutePath().toString());
                        System.out.println("[NativeLoader] Loaded from java.library.path: " + libPath);
                        return;
                    }
                }
            }
        }

        // Strategy 3: Extract from classpath to temp dir and load
        for (String fileName : alternativeFileNames) {
            String classpathPath = CLASSPATH_NATIVE_PREFIX + "/" + platformDir + "/" + fileName;
            try {
                if (extractAndLoadFromClasspath(classpathPath, fileName)) {
                    System.out.println("[NativeLoader] Loaded from classpath: " + classpathPath);
                    return;
                }
            } catch (IOException e) {
                System.err.println("[NativeLoader] Failed to extract from classpath: " + e.getMessage());
            }
        }

        // Strategy 4: System.loadLibrary with alternative names
        List<String> alternativeLibNames = getAlternativeLibNames(libName, category);
        for (String name : alternativeLibNames) {
            try {
                System.loadLibrary(name);
                System.out.println("[NativeLoader] Loaded via System.loadLibrary: " + name);
                return;
            } catch (UnsatisfiedLinkError e) {
                // Try next alternative
            }
        }

        // All strategies failed
        String missingDepsHint = buildMissingDepsHint(category, os);
        String errorMsg = String.format(
            "Failed to load native library '%s' (%s).%n" +
            "Attempted:%n" +
            "  1. Custom path: %s%n" +
            "  2. Project native-lib directory%n" +
            "  3. java.library.path%n" +
            "  4. Classpath extraction%n" +
            "  5. System.loadLibrary (with alternatives)%n%n" +
            "%s%n" +
            "Download native-libs from: https://github.com/Devhub-Solutions/opencv-tesseract-ci/releases",
            fullLibFileName, category, nativeLibraryPath, missingDepsHint
        );
        throw new UnsatisfiedLinkError(errorMsg);
    }

    /**
     * Builds a hint about missing system dependencies based on the category and OS.
     */
    private static String buildMissingDepsHint(String category, OS os) {
        if (os != OS.LINUX) return "";

        StringBuilder hint = new StringBuilder("Required system packages on Debian/Ubuntu:\n");
        switch (category) {
            case "opencv":
                hint.append("  sudo apt install libarchive13 libcurl4\n");
                hint.append("  (only needed if libarchive/libcurl were not disabled in CI build)\n");
                hint.append("\nOr run with LD_LIBRARY_PATH:\n");
                hint.append("  LD_LIBRARY_PATH=./native-lib/linux/x86_64 java ...\n");
                break;
            case "tesseract":
                hint.append("  sudo apt install libarchive13 libcurl4\n");
                hint.append("  (only needed if libarchive/libcurl were not disabled in CI build)\n");
                break;
            case "leptonica":
                hint.append("  All Leptonica dependencies should be bundled.\n");
                hint.append("  If still failing, run with:\n");
                hint.append("  LD_LIBRARY_PATH=./native-lib/linux/x86_64 java ...\n");
                break;
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
        StringBuilder sb = new StringBuilder();
        sb.append("=== Native Library Loader Diagnostics ===\n");
        sb.append("OS: ").append(detectOS()).append("\n");
        sb.append("Architecture: ").append(detectArch()).append("\n");
        sb.append("Lib Prefix: ").append(getLibPrefix()).append("\n");
        sb.append("Lib Extension: ").append(detectOS().getLibExtension()).append("\n");
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

        // Show expected library file names
        sb.append("\nExpected Library Files:\n");
        sb.append("  OpenCV: ").append(buildOpenCVLibName()).append(".").append(detectOS().getLibExtension()).append("\n");
        sb.append("  Tesseract: ").append(buildTesseractLibName()).append(".").append(detectOS().getLibExtension()).append("\n");
        sb.append("  Leptonica: ").append(buildLeptonicaLibName()).append(".").append(detectOS().getLibExtension()).append("\n");

        // Show LD_LIBRARY_PATH hint for Linux
        if (detectOS() == OS.LINUX) {
            sb.append("\nLD_LIBRARY_PATH: ").append(System.getenv("LD_LIBRARY_PATH") != null ?
                System.getenv("LD_LIBRARY_PATH") : "(not set)").append("\n");
            if (resolvedDir != null) {
                sb.append("\nRecommended run command:\n");
                sb.append("  LD_LIBRARY_PATH=").append(resolvedDir).append(" java -Dnative.lib.path=./native-lib -cp target/opencv-tesseract-ci-1.2.0.jar com.devhub.solutions.application.ApplicationMain\n");
            }
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
