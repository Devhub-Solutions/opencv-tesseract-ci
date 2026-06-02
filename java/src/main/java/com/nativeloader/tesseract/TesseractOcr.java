package com.nativeloader.tesseract;

import com.nativeloader.NativeLibraryLoader;

/**
 * High-level Java wrapper for Tesseract OCR that handles lifecycle management.
 * Simplifies the raw JNA interface into a try-with-resources compatible API.
 *
 * <h3>Usage:</h3>
 * <pre>
 *   NativeLibraryLoader.loadTesseract();
 *
 *   try (TesseractOcr ocr = new TesseractOcr()) {
 *       ocr.setDatapath("/usr/share/tesseract-ocr/5/tessdata");
 *       ocr.setLanguage("eng");
 *       ocr.setPageSegMode(TesseractOcr.PSM_AUTO);
 *
 *       String result = ocr.doOCR(imageBytes, width, height, channels);
 *       System.out.println(result);
 *   }
 * </pre>
 */
public class TesseractOcr implements AutoCloseable {

    private long handle = 0;
    private String datapath = "";
    private String language = "eng";
    private int pageSegMode = 3; // PSM_AUTO
    private int ocrEngineMode = 3; // OEM_DEFAULT
    private boolean initialized = false;

    public TesseractOcr() {
        if (!NativeLibraryLoader.isTesseractLoaded()) {
            NativeLibraryLoader.loadTesseract();
        }
        this.handle = TessAPI.INSTANCE.TessBaseAPICreate().getNativePeer();
        if (this.handle == 0) {
            throw new RuntimeException("Failed to create Tesseract base API");
        }
    }

    /**
     * Initializes the Tesseract engine. Called automatically on first OCR if not done manually.
     */
    public synchronized void init() {
        if (initialized) return;
        int result = TessAPI.INSTANCE.TessBaseAPIInit3(
            getHandle(), datapath, language
        );
        if (result != 0) {
            throw new RuntimeException(
                "Tesseract initialization failed (code=" + result + "). " +
                "Check datapath='" + datapath + "' and language='" + language + "'"
            );
        }
        TessAPI.INSTANCE.TessBaseAPISetPageSegMode(getHandle(), pageSegMode);
        initialized = true;
    }

    /**
     * Performs OCR on raw image bytes (RGB/BGR format).
     *
     * @param imageData raw pixel data
     * @param width image width in pixels
     * @param height image height in pixels
     * @param channels number of color channels (1=grayscale, 3=RGB, 4=RGBA)
     * @return recognized text in UTF-8
     */
    public String doOCR(byte[] imageData, int width, int height, int channels) {
        if (!initialized) init();

        int bytesPerLine = width * channels;
        com.sun.jna.Pointer handlePtr = getHandle();

        TessAPI.INSTANCE.TessBaseAPISetImage(
            handlePtr, imageData, width, height, channels, bytesPerLine
        );

        com.sun.jna.Pointer textPtr = TessAPI.INSTANCE.TessBaseAPIGetUTF8Text(handlePtr);
        if (textPtr == null) {
            return "";
        }
        String result = textPtr.getString(0);
        TessAPI.INSTANCE.TessDeleteText(textPtr);
        return result;
    }

    /**
     * Performs OCR with explicit bytes per line (for padded images).
     */
    public String doOCR(byte[] imageData, int width, int height, int channels, int bytesPerLine) {
        if (!initialized) init();

        com.sun.jna.Pointer handlePtr = getHandle();
        TessAPI.INSTANCE.TessBaseAPISetImage(
            handlePtr, imageData, width, height, channels, bytesPerLine
        );

        com.sun.jna.Pointer textPtr = TessAPI.INSTANCE.TessBaseAPIGetUTF8Text(handlePtr);
        if (textPtr == null) return "";
        String result = textPtr.getString(0);
        TessAPI.INSTANCE.TessDeleteText(textPtr);
        return result;
    }

    // ================================================================
    // Getters & Setters
    // ================================================================

    public TesseractOcr setDatapath(String datapath) {
        this.datapath = datapath;
        return this;
    }

    public TesseractOcr setLanguage(String language) {
        this.language = language;
        return this;
    }

    public TesseractOcr setPageSegMode(int mode) {
        this.pageSegMode = mode;
        return this;
    }

    public TesseractOcr setOcrEngineMode(int mode) {
        this.ocrEngineMode = mode;
        return this;
    }

    public String getDatapath() { return datapath; }
    public String getLanguage() { return language; }
    public int getPageSegMode() { return pageSegMode; }
    public boolean isInitialized() { return initialized; }

    // ================================================================
    // Lifecycle
    // ================================================================

    private com.sun.jna.Pointer getHandle() {
        return new com.sun.jna.Pointer(handle);
    }

    @Override
    public void close() {
        if (handle != 0) {
            TessAPI.INSTANCE.TessBaseAPIDelete(getHandle());
            handle = 0;
            initialized = false;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    // PSM constants (mirror TessAPI)
    public static final int PSM_OSD_ONLY = 0;
    public static final int PSM_AUTO_OSD = 1;
    public static final int PSM_AUTO_ONLY = 2;
    public static final int PSM_AUTO = 3;
    public static final int PSM_SINGLE_COLUMN = 4;
    public static final int PSM_SINGLE_BLOCK_VERT_TEXT = 5;
    public static final int PSM_SINGLE_BLOCK = 6;
    public static final int PSM_SINGLE_LINE = 7;
    public static final int PSM_SINGLE_WORD = 8;
    public static final int PSM_CIRCLE_WORD = 9;
    public static final int PSM_SINGLE_CHAR = 10;
    public static final int PSM_SPARSE_TEXT = 11;
    public static final int PSM_SPARSE_TEXT_OSD = 12;
    public static final int PSM_RAW_LINE = 13;
}
