package com.devhub.solutions.application.tesseract;

import com.devhub.solutions.application.loader.NativeLibraryLoader;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

/**
 * JNA interface for Tesseract OCR native library.
 *
 * <h3>Key Design (v1.2.0):</h3>
 * <p>Uses lazy initialization via the {@code LazyHolder} pattern instead of eager
 * static initialization. This ensures that {@code jna.library.path} is configured
 * by {@link NativeLibraryLoader} <b>before</b> JNA attempts to load the native library.</p>
 *
 * <h3>Why this fix is needed:</h3>
 * <p>JNA's {@code Native.load()} uses its own library search mechanism ({@code dlopen()}).
 * It searches paths listed in {@code jna.library.path}, not {@code java.library.path}.
 * Previously, even though {@code System.load()} successfully loaded the native library,
 * JNA couldn't find it because {@code jna.library.path} was never set. The lazy
 * initialization ensures the path is configured first.</p>
 *
 * <h3>Usage example:</h3>
 * <pre>
 *   // Must load native libraries first via NativeLibraryLoader
 *   NativeLibraryLoader.loadTesseract();
 *
 *   // Then access the JNA instance
 *   String version = TessAPI.getInstance().TessVersion();
 *   Pointer handle = TessAPI.getInstance().TessBaseAPICreate();
 * </pre>
 */
public interface TessAPI extends Library {

    // ================================================================
    // Lazy Initialization (KEY FIX for JNA loading)
    // ================================================================

    /**
     * Thread-safe lazy holder for the JNA TessAPI instance.
     *
     * <p>The JVM guarantees that {@code LazyHolder.INSTANCE} is initialized
     * only when {@code LazyHolder} class is first accessed, which happens
     * only when {@code TessAPI.getInstance()} is first called. By that point,
     * {@link NativeLibraryLoader#loadTesseract()} should have already been called,
     * which sets {@code jna.library.path} to include the native library directory.</p>
     */
    final class LazyHolder {
        static final TessAPI INSTANCE = createInstance();

        private static TessAPI createInstance() {
            // Ensure jna.library.path is configured before JNA tries to load
            ensureJnaLibraryPath();
            return Native.load("tesseract", TessAPI.class);
        }

        /**
         * Ensures that JNA's library search path includes the native lib directory.
         * This is a safety net in case NativeLibraryLoader.loadTesseract() was
         * not called before accessing TessAPI.getInstance().
         */
        private static void ensureJnaLibraryPath() {
            String nativeDir = NativeLibraryLoader.getResolvedNativeLibDir();
            if (nativeDir != null) {
                String jnaPath = System.getProperty("jna.library.path", "");
                if (!jnaPath.contains(nativeDir)) {
                    System.setProperty("jna.library.path",
                        nativeDir + java.io.File.pathSeparator + jnaPath);
                    System.out.println("[TessAPI] Set jna.library.path to include: " + nativeDir);
                }
            } else {
                // Try to resolve and set the path now
                try {
                    String resolved = resolveNativeDirManually();
                    if (resolved != null) {
                        String jnaPath = System.getProperty("jna.library.path", "");
                        if (!jnaPath.contains(resolved)) {
                            System.setProperty("jna.library.path",
                                resolved + java.io.File.pathSeparator + jnaPath);
                            System.out.println("[TessAPI] Set jna.library.path to include: " + resolved);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[TessAPI] Could not resolve native lib dir: " + e.getMessage());
                }
            }
        }

        /**
         * Manual resolution fallback when NativeLibraryLoader hasn't been initialized.
         */
        private static String resolveNativeDirManually() {
            String osName = System.getProperty("os.name", "").toLowerCase();
            String osArch = System.getProperty("os.arch", "").toLowerCase();

            String osDir;
            if (osName.contains("linux")) osDir = "linux";
            else if (osName.contains("mac") || osName.contains("darwin")) osDir = "macos";
            else if (osName.contains("win")) osDir = "windows";
            else return null;

            String archDir;
            if (osArch.equals("x86_64") || osArch.equals("amd64")) archDir = "x86_64";
            else if (osArch.equals("aarch64") || osArch.equals("arm64")) archDir = "arm64";
            else return null;

            String platformDir = osDir + "/" + archDir;

            // Check native.lib.path system property
            String customPath = System.getProperty("native.lib.path", "");
            if (!customPath.isEmpty()) {
                java.nio.file.Path dir = java.nio.file.Paths.get(customPath, platformDir);
                if (java.nio.file.Files.isDirectory(dir)) {
                    return dir.toAbsolutePath().toString();
                }
            }

            // Check project-relative native-lib directory
            java.nio.file.Path projectDir = java.nio.file.Paths.get("native-lib", platformDir);
            if (java.nio.file.Files.isDirectory(projectDir)) {
                return projectDir.toAbsolutePath().toString();
            }

            return null;
        }
    }

    /**
     * Returns the lazily-initialized JNA instance of the Tesseract API.
     *
     * <p>Important: Call {@link NativeLibraryLoader#loadTesseract()} before this method
     * to ensure native libraries are loaded and JNA paths are configured.</p>
     *
     * @return JNA proxy instance for the Tesseract C API
     */
    static TessAPI getInstance() {
        return LazyHolder.INSTANCE;
    }

    // ================================================================
    // API Creation & Deletion
    // ================================================================

    Pointer TessBaseAPICreate();
    void TessBaseAPIDelete(Pointer handle);

    // ================================================================
    // Initialization
    // ================================================================

    int TessBaseAPIInit3(Pointer handle, String datapath, String language);
    int TessBaseAPIInit4(Pointer handle, String datapath, String language,
                         int oem, String[] configs, int configs_size,
                         String[] vars_vec, String[] vars_values,
                         PointerByReference vars_vec_sizes,
                         boolean set_only_non_debug_params);

    int TessBaseAPIInit5(Pointer handle, String datapath, String language,
                         int oem, char[] configs, int configs_size,
                         String[] vars_vec, String[] vars_values,
                         PointerByReference vars_vec_sizes,
                         boolean set_only_non_debug_params,
                         PointerByReference paths);

    void TessBaseAPIEnd(Pointer handle);
    int TessBaseAPIIsValidWord(Pointer handle, String word);

    // ================================================================
    // Image Input
    // ================================================================

    void TessBaseAPISetImage(Pointer handle, byte[] imagedata,
                             int width, int height,
                             int bytes_per_pixel, int bytes_per_line);

    void TessBaseAPISetImage2(Pointer handle, Pointer pix);

    void TessBaseAPISetSourceResolution(Pointer handle, int ppi);
    void TessBaseAPISetRectangle(Pointer handle, int left, int top, int width, int height);

    // ================================================================
    // OCR Results
    // ================================================================

    Pointer TessBaseAPIGetUTF8Text(Pointer handle);
    Pointer TessBaseAPIGetHOCRText(Pointer handle, int page_number);
    Pointer TessBaseAPIGetAltoText(Pointer handle, int page_number);
    Pointer TessBaseAPIGetTsvText(Pointer handle, int page_number);
    Pointer TessBaseAPIGetLSTMBoxText(Pointer handle, int page_number);
    Pointer TessBaseAPIGetWordStrBoxText(Pointer handle, int page_number);
    Pointer TessBaseAPIGetUNLVText(Pointer handle);

    void TessDeleteText(Pointer text);
    void TessDeleteIntArray(int[] arr);

    // ================================================================
    // Result Iterator
    // ================================================================

    Pointer TessBaseAPIGetIterator(Pointer handle);
    Pointer TessBaseAPIGetMutableIterator(Pointer handle);

    // ================================================================
    // Page Segmentation Mode
    // ================================================================

    void TessBaseAPISetPageSegMode(Pointer handle, int mode);
    int TessBaseAPIGetPageSegMode(Pointer handle);

    /** Page Segmentation Modes */
    int PSM_OSD_ONLY = 0;
    int PSM_AUTO_OSD = 1;
    int PSM_AUTO_ONLY = 2;
    int PSM_AUTO = 3;
    int PSM_SINGLE_COLUMN = 4;
    int PSM_SINGLE_BLOCK_VERT_TEXT = 5;
    int PSM_SINGLE_BLOCK = 6;
    int PSM_SINGLE_LINE = 7;
    int PSM_SINGLE_WORD = 8;
    int PSM_CIRCLE_WORD = 9;
    int PSM_SINGLE_CHAR = 10;
    int PSM_SPARSE_TEXT = 11;
    int PSM_SPARSE_TEXT_OSD = 12;
    int PSM_RAW_LINE = 13;
    int PSM_COUNT = 14;

    // ================================================================
    // OCR Engine Mode
    // ================================================================

    /** OCR Engine Modes */
    int OEM_TESSERACT_ONLY = 0;
    int OEM_LSTM_ONLY = 1;
    int OEM_TESSERACT_LSTM_COMBINED = 2;
    int OEM_DEFAULT = 3;

    // ================================================================
    // Version Info
    // ================================================================

    String TessVersion();
}


