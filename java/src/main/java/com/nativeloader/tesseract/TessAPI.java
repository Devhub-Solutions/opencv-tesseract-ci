package com.nativeloader.tesseract;

import com.nativeloader.NativeLibraryLoader;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

/**
 * JNA interface for Tesseract OCR native library.
 * Requires Tesseract native library to be loaded first via {@link NativeLibraryLoader#loadTesseract()}.
 *
 * <h3>Usage example:</h3>
 * <pre>
 *   NativeLibraryLoader.loadTesseract();
 *
 *   TessAPI api = TessAPI.INSTANCE;
 *   Pointer handle = api.TessBaseAPICreate();
 *
 *   int result = api.TessBaseAPIInit3(handle, "/path/to/tessdata", "eng");
 *   if (result != 0) {
 *       throw new RuntimeException("Failed to initialize Tesseract");
 *   }
 *
 *   api.TessBaseAPISetImage(handle, imageData, width, height, bytesPerPixel, bytesPerLine);
 *   Pointer textPtr = api.TessBaseAPIGetUTF8Text(handle);
 *   String text = textPtr.getString(0);
 *   api.TessDeleteText(textPtr);
 *
 *   api.TessBaseAPIDelete(handle);
 * </pre>
 */
public interface TessAPI extends Library {

    /** JNA instance - auto-loaded after native lib is on the system path */
    TessAPI INSTANCE = Native.load("tesseract", TessAPI.class);

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
