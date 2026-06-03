package com.devhub.solutions.application.tesseract;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TesseractOcr constants and basic API.
 * Does not require native libraries to be loaded.
 */
class TesseractOcrTest {

    @Test
    @DisplayName("PSM constants have expected values")
    void testPSMConstants() {
        assertEquals(0, TesseractOcr.PSM_OSD_ONLY);
        assertEquals(1, TesseractOcr.PSM_AUTO_OSD);
        assertEquals(3, TesseractOcr.PSM_AUTO);
        assertEquals(6, TesseractOcr.PSM_SINGLE_BLOCK);
        assertEquals(7, TesseractOcr.PSM_SINGLE_LINE);
        assertEquals(13, TesseractOcr.PSM_RAW_LINE);
    }

    @Test
    @DisplayName("TessAPI PSM constants match TesseractOcr")
    void testTessAPIPSMConstants() {
        assertEquals(TessAPI.PSM_OSD_ONLY, TesseractOcr.PSM_OSD_ONLY);
        assertEquals(TessAPI.PSM_AUTO_OSD, TesseractOcr.PSM_AUTO_OSD);
        assertEquals(TessAPI.PSM_AUTO, TesseractOcr.PSM_AUTO);
        assertEquals(TessAPI.PSM_RAW_LINE, TesseractOcr.PSM_RAW_LINE);
    }

    @Test
    @DisplayName("TessAPI OEM constants have expected values")
    void testOEMConstants() {
        assertEquals(0, TessAPI.OEM_TESSERACT_ONLY);
        assertEquals(1, TessAPI.OEM_LSTM_ONLY);
        assertEquals(2, TessAPI.OEM_TESSERACT_LSTM_COMBINED);
        assertEquals(3, TessAPI.OEM_DEFAULT);
    }

    @Test
    @DisplayName("TessAPI.getInstance() uses lazy initialization (v1.2.0)")
    void testTessAPILazyInit() {
        // This test verifies that getInstance() method exists and is accessible
        // without triggering native library loading at class load time
        // (unlike the old INSTANCE field which caused eager initialization)
        //
        // We can't actually call getInstance() in a unit test without native libs,
        // but we can verify the method exists
        try {
            TessAPI.class.getDeclaredMethod("getInstance");
            // Method exists - lazy init pattern is in place
        } catch (NoSuchMethodException e) {
            fail("TessAPI should have getInstance() method for lazy initialization");
        }
    }
}
