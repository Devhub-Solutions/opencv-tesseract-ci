package com.nativeloader;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NativeLibraryLoader - Utility class for loading OpenCV & Tesseract native libraries
 * across Linux, Windows, and macOS platforms.
 *
 * <p>Automatically detects the current OS and architecture, extracts native libraries
 * from the classpath (JAR-bundled) or loads from a filesystem path, and manages
 * the library loading lifecycle.</p>
 *
 * <h3>Usage:</h3>
 * <pre>
 *   // Load OpenCV
 *   NativeLibraryLoader.loadOpenCV();
 *
 *   // Load Tesseract
 *   NativeLibraryLoader.loadTesseract();
 *
 *   // Or load all at once
 *   NativeLibraryLoader.loadAll();
 *
 *   // Set custom native library path
 *   NativeLibraryLoader.setNativeLibraryPath("/opt/native-libs");
 * </pre>
 *
 * <h3>Expected directory structure:</h3>
 * <pre>
 *   native-lib/
 *   ├── linux/
 *   │   └── x86_64/
 *   │       ├── libopencv_java410.so
 *   │       ├── libtesseract.so.5
 *   │       └── liblept.so.5
 *   ├── windows/
 *   │   └── x86_64/
 *   │       ├── opencv_java410.dll
 *   │       ├── libtesseract-5.dll
 *   │       └── liblept-5.dll
 *   └── macos/
 *       └── x86_64/
 *           ├── libopencv_java410.dylib
 *           ├── libtesseract.5.dylib
 *           └── liblept.5.dylib
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
     * Loads the OpenCV native library. Thread-safe.
     */
    public static synchronized void loadOpenCV() {
        if (opencvLoaded.get()) {
            return;
        }
        String libName = buildOpenCVLibName();
        loadLibrary(libName, "opencv");
        opencvLoaded.set(true);
        System.out.println("[NativeLoader] OpenCV loaded successfully");
    }

    /**
     * Loads the Tesseract OCR native library and its dependency (Leptonica).
     */
    public static synchronized void loadTesseract() {
        if (tesseractLoaded.get()) {
            return;
        }

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
     * Convenience method to load both OpenCV and Tesseract.
     */
    public static synchronized void loadAll() {
        loadOpenCV();
        loadTesseract();
    }

    public static boolean isOpenCVLoaded() {
        return opencvLoaded.get();
    }

    public static boolean isTesseractLoaded() {
        return tesseractLoaded.get();
    }

    // ================================================================
    // Library Name Builders
    // ================================================================

    private static String buildOpenCVLibName() {
        String prefix = getLibPrefix();
        return prefix + "opencv_java410";
    }

    private static String buildTesseractLibName() {
        OS os = detectOS();
        String prefix = getLibPrefix();
        switch (os) {
            case WINDOWS: return prefix + "tesseract-5";
            default:      return prefix + "tesseract";
        }
    }

    private static String buildLeptonicaLibName() {
        OS os = detectOS();
        String prefix = getLibPrefix();
        switch (os) {
            case WINDOWS: return prefix + "lept-5";
            default:      return prefix + "lept";
        }
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

        // Strategy 1: Load from custom filesystem path
        if (!nativeLibraryPath.isEmpty()) {
            Path libPath = Paths.get(nativeLibraryPath, platformDir, fullLibFileName);
            if (Files.exists(libPath)) {
                System.load(libPath.toAbsolutePath().toString());
                System.out.println("[NativeLoader] Loaded from custom path: " + libPath);
                return;
            }
            libPath = Paths.get(nativeLibraryPath, fullLibFileName);
            if (Files.exists(libPath)) {
                System.load(libPath.toAbsolutePath().toString());
                System.out.println("[NativeLoader] Loaded from custom path (flat): " + libPath);
                return;
            }
        }

        // Strategy 2: Load from java.library.path
        String javaLibPath = System.getProperty("java.library.path", "");
        if (!javaLibPath.isEmpty()) {
            for (String pathEntry : javaLibPath.split(File.pathSeparator)) {
                Path libPath = Paths.get(pathEntry, fullLibFileName);
                if (Files.exists(libPath)) {
                    System.load(libPath.toAbsolutePath().toString());
                    System.out.println("[NativeLoader] Loaded from java.library.path: " + libPath);
                    return;
                }
            }
        }

        // Strategy 3: Extract from classpath to temp dir and load
        String classpathPath = CLASSPATH_NATIVE_PREFIX + "/" + platformDir + "/" + fullLibFileName;
        try {
            if (extractAndLoadFromClasspath(classpathPath, fullLibFileName)) {
                System.out.println("[NativeLoader] Loaded from classpath: " + classpathPath);
                return;
            }
        } catch (IOException e) {
            System.err.println("[NativeLoader] Failed to extract from classpath: " + e.getMessage());
        }

        // Strategy 4: System.loadLibrary as last resort
        try {
            System.loadLibrary(libName);
            System.out.println("[NativeLoader] Loaded via System.loadLibrary: " + libName);
            return;
        } catch (UnsatisfiedLinkError e) {
            // Fall through to error
        }

        String errorMsg = String.format(
            "Failed to load native library '%s' for %s/%s.%n" +
            "Tried:%n  1. Custom path: %s%n  2. java.library.path: %s%n" +
            "  3. Classpath: %s%n  4. System.loadLibrary%n%n" +
            "Solutions:%n  - Set -Dnative.lib.path=/path/to/native-lib%n" +
            "  - Set -Djava.library.path=/path/to/native-lib/linux/x86_64%n" +
            "  - Bundle native-lib/ in your JAR classpath",
            fullLibFileName, os, arch,
            nativeLibraryPath.isEmpty() ? "(not set)" : nativeLibraryPath + "/" + platformDir,
            javaLibPath, classpathPath
        );
        throw new UnsatisfiedLinkError(errorMsg);
    }

    private static boolean extractAndLoadFromClasspath(String classpathPath, String fileName)
            throws IOException {

        ClassLoader[] loaders = {
            Thread.currentThread().getContextClassLoader(),
            NativeLibraryLoader.class.getClassLoader(),
            ClassLoader.getSystemClassLoader()
        };

        URL resourceUrl = null;
        for (ClassLoader loader : loaders) {
            if (loader != null) {
                resourceUrl = loader.getResource(classpathPath);
                if (resourceUrl != null) break;
            }
        }

        if (resourceUrl == null) {
            return false;
        }

        Path tempFile = TEMP_DIR.resolve(fileName);
        if (!Files.exists(tempFile)) {
            try (InputStream is = resourceUrl.openStream()) {
                Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            tempFile.toFile().deleteOnExit();

            if (detectOS() != OS.WINDOWS) {
                try {
                    Files.setPosixFilePermissions(tempFile,
                        Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE,
                            PosixFilePermission.GROUP_READ,
                            PosixFilePermission.GROUP_EXECUTE,
                            PosixFilePermission.OTHERS_READ,
                            PosixFilePermission.OTHERS_EXECUTE
                        ));
                } catch (UnsupportedOperationException e) {
                    // Non-POSIX filesystem, ignore
                }
            }
        }

        System.load(tempFile.toAbsolutePath().toString());
        return true;
    }

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
     * Returns diagnostic information about the current platform and library status.
     */
    public static String getDiagnosticInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Native Library Loader Diagnostics ===\n");
        sb.append("OS: ").append(System.getProperty("os.name")).append("\n");
        sb.append("Arch: ").append(System.getProperty("os.arch")).append("\n");
        sb.append("Detected OS: ").append(detectOS()).append("\n");
        sb.append("Detected Arch: ").append(detectArch()).append("\n");
        sb.append("Custom native path: ").append(nativeLibraryPath.isEmpty() ? "(not set)" : nativeLibraryPath).append("\n");
        sb.append("java.library.path: ").append(System.getProperty("java.library.path")).append("\n");
        sb.append("OpenCV loaded: ").append(opencvLoaded.get()).append("\n");
        sb.append("Tesseract loaded: ").append(tesseractLoaded.get()).append("\n");
        sb.append("Temp dir: ").append(TEMP_DIR).append("\n");
        return sb.toString();
    }

    private NativeLibraryLoader() {}
}
