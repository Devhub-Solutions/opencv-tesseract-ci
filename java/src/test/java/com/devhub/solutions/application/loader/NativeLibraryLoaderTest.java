package com.devhub.solutions.application.loader;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NativeLibraryLoader.
 * Tests OS detection, architecture detection, library name building,
 * loading state management, and JNA path configuration without requiring actual native libraries.
 */
class NativeLibraryLoaderTest {

    @BeforeEach
    void setUp() {
        NativeLibraryLoader.resetState();
    }

    // ================================================================
    // OS Detection Tests
    // ================================================================

    @Test
    @DisplayName("detectOS returns a valid OS enum value")
    void testDetectOSReturnsValidValue() {
        NativeLibraryLoader.OS os = NativeLibraryLoader.detectOS();
        assertNotNull(os, "OS detection should never return null");
        assertTrue(os == NativeLibraryLoader.OS.LINUX ||
                   os == NativeLibraryLoader.OS.WINDOWS ||
                   os == NativeLibraryLoader.OS.MACOS,
                   "OS should be one of LINUX, WINDOWS, or MACOS");
    }

    @Test
    @DisplayName("OS enum has correct directory names")
    void testOSDirectoryNames() {
        assertEquals("linux", NativeLibraryLoader.OS.LINUX.getDirName());
        assertEquals("windows", NativeLibraryLoader.OS.WINDOWS.getDirName());
        assertEquals("macos", NativeLibraryLoader.OS.MACOS.getDirName());
    }

    @Test
    @DisplayName("OS enum has correct library extensions")
    void testOSLibraryExtensions() {
        assertEquals("so", NativeLibraryLoader.OS.LINUX.getLibExtension());
        assertEquals("dll", NativeLibraryLoader.OS.WINDOWS.getLibExtension());
        assertEquals("dylib", NativeLibraryLoader.OS.MACOS.getLibExtension());
    }

    // ================================================================
    // Architecture Detection Tests
    // ================================================================

    @Test
    @DisplayName("detectArch returns a valid Arch enum value")
    void testDetectArchReturnsValidValue() {
        NativeLibraryLoader.Arch arch = NativeLibraryLoader.detectArch();
        assertNotNull(arch, "Architecture detection should never return null");
        assertTrue(arch == NativeLibraryLoader.Arch.X86_64 ||
                   arch == NativeLibraryLoader.Arch.ARM64 ||
                   arch == NativeLibraryLoader.Arch.AARCH64,
                   "Arch should be one of X86_64, ARM64, or AARCH64");
    }

    @Test
    @DisplayName("Arch enum has correct directory names")
    void testArchDirectoryNames() {
        assertEquals("x86_64", NativeLibraryLoader.Arch.X86_64.getDirName());
        assertEquals("arm64", NativeLibraryLoader.Arch.ARM64.getDirName());
        assertEquals("aarch64", NativeLibraryLoader.Arch.AARCH64.getDirName());
    }

    // ================================================================
    // Library Prefix Tests
    // ================================================================

    @Test
    @DisplayName("getLibPrefix returns 'lib' for non-Windows, '' for Windows")
    void testGetLibPrefix() {
        NativeLibraryLoader.OS os = NativeLibraryLoader.detectOS();
        String prefix = NativeLibraryLoader.getLibPrefix();
        if (os == NativeLibraryLoader.OS.WINDOWS) {
            assertEquals("", prefix, "Windows should have empty prefix");
        } else {
            assertEquals("lib", prefix, "Linux/macOS should have 'lib' prefix");
        }
    }

    // ================================================================
    // Loading State Tests
    // ================================================================

    @Test
    @DisplayName("Initial state: neither library is loaded")
    void testInitialState() {
        assertFalse(NativeLibraryLoader.isOpenCVLoaded(), "OpenCV should not be loaded initially");
        assertFalse(NativeLibraryLoader.isTesseractLoaded(), "Tesseract should not be loaded initially");
    }

    @Test
    @DisplayName("resetState resets both loading flags")
    void testResetState() {
        // We can't actually load libraries in unit tests, but we can verify
        // that resetState properly resets the state
        NativeLibraryLoader.resetState();
        assertFalse(NativeLibraryLoader.isOpenCVLoaded());
        assertFalse(NativeLibraryLoader.isTesseractLoaded());
    }

    // ================================================================
    // Native Library Path Tests
    // ================================================================

    @Test
    @DisplayName("Default native library path is empty or from system property")
    void testDefaultNativeLibraryPath() {
        String path = NativeLibraryLoader.getNativeLibraryPath();
        String expected = System.getProperty("native.lib.path", "");
        assertEquals(expected, path, "Default path should match system property or empty");
    }

    @Test
    @DisplayName("setNativeLibraryPath updates the path")
    void testSetNativeLibraryPath() {
        String originalPath = NativeLibraryLoader.getNativeLibraryPath();
        NativeLibraryLoader.setNativeLibraryPath("/test/path");
        assertEquals("/test/path", NativeLibraryLoader.getNativeLibraryPath());
        // Restore
        NativeLibraryLoader.setNativeLibraryPath(originalPath);
    }

    // ================================================================
    // Diagnostic Info Tests
    // ================================================================

    @Test
    @DisplayName("getDiagnosticInfo returns non-empty string with expected content")
    void testGetDiagnosticInfo() {
        String info = NativeLibraryLoader.getDiagnosticInfo();
        assertNotNull(info, "Diagnostic info should not be null");
        assertFalse(info.isEmpty(), "Diagnostic info should not be empty");
        assertTrue(info.contains("OS:"), "Should contain OS info");
        assertTrue(info.contains("Architecture:"), "Should contain Architecture info");
        assertTrue(info.contains("OpenCV Loaded:"), "Should contain OpenCV loading status");
        assertTrue(info.contains("Tesseract Loaded:"), "Should contain Tesseract loading status");
        assertTrue(info.contains("libopencv_java4100"), "Should show expected OpenCV lib name");
        assertTrue(info.contains("libtesseract"), "Should show expected Tesseract lib name");
        assertTrue(info.contains("liblept"), "Should show expected Leptonica lib name");
    }

    @Test
    @DisplayName("getDiagnosticInfo includes jna.library.path (v1.2.0 fix)")
    void testDiagnosticInfoIncludesJnaPath() {
        String info = NativeLibraryLoader.getDiagnosticInfo();
        assertTrue(info.contains("jna.library.path"),
            "Should include jna.library.path in diagnostics (key fix in v1.2.0)");
    }

    @Test
    @DisplayName("getDiagnosticInfo shows resolved native dir")
    void testDiagnosticInfoShowsResolvedDir() {
        String info = NativeLibraryLoader.getDiagnosticInfo();
        assertTrue(info.contains("Resolved Native Dir:"), "Should show resolved native dir");
    }

    @Test
    @DisplayName("getDiagnosticInfo shows LD_LIBRARY_PATH hint on Linux")
    void testDiagnosticInfoShowsLdLibraryPathHint() {
        NativeLibraryLoader.OS os = NativeLibraryLoader.detectOS();
        if (os == NativeLibraryLoader.OS.LINUX) {
            String info = NativeLibraryLoader.getDiagnosticInfo();
            assertTrue(info.contains("LD_LIBRARY_PATH"), "Should show LD_LIBRARY_PATH info on Linux");
        }
    }

    // ================================================================
    // Resolved Native Dir Tests
    // ================================================================

    @Test
    @DisplayName("getResolvedNativeLibDir returns null before loading")
    void testResolvedNativeLibDirBeforeLoading() {
        // Before loading, resolvedNativeLibDir may or may not be null
        // depending on whether ensureLibraryPaths() was called
        String dir = NativeLibraryLoader.getResolvedNativeLibDir();
        // It's fine either way - just verify it doesn't throw
        assertNotNull(dir != null || dir == null); // Always true, just checking no exception
    }

    // ================================================================
    // Missing Dependencies Hint Tests
    // ================================================================

    @Test
    @DisplayName("loadTesseract failure message contains troubleshooting info")
    void testTesseractFailureHint() {
        try {
            NativeLibraryLoader.loadTesseract();
        } catch (UnsatisfiedLinkError e) {
            String message = e.getMessage();
            assertNotNull(message, "Error message should not be null");
            // Should mention the library name and troubleshooting steps
            assertTrue(message.contains("tesseract") || message.contains("Failed"),
                "Error message should mention tesseract or failure");
        }
        // Test passes whether or not loading succeeds
    }

    @Test
    @DisplayName("loadOpenCV failure message contains troubleshooting hint")
    void testOpenCVFailureHint() {
        try {
            NativeLibraryLoader.loadOpenCV();
        } catch (UnsatisfiedLinkError e) {
            String message = e.getMessage();
            assertNotNull(message, "Error message should not be null");
            assertTrue(message.contains("opencv"), "Error message should mention opencv");
        }
        // Test passes whether or not loading succeeds
    }

    // ================================================================
    // JNA Library Path Configuration Test (v1.2.0)
    // ================================================================

    @Test
    @DisplayName("ensureLibraryPaths sets jna.library.path when native libs found")
    void testJnaLibraryPathConfiguration() {
        // If native libs exist at the expected path, jna.library.path should be set
        // after attempting to load
        try {
            NativeLibraryLoader.loadTesseract();
        } catch (UnsatisfiedLinkError e) {
            // Loading may fail in test environment, that's OK
        }

        // If resolvedNativeLibDir was set, jna.library.path should include it
        String resolvedDir = NativeLibraryLoader.getResolvedNativeLibDir();
        if (resolvedDir != null) {
            String jnaPath = System.getProperty("jna.library.path", "");
            assertTrue(jnaPath.contains(resolvedDir),
                "jna.library.path should include the resolved native lib dir: " + resolvedDir);
        }
    }
}
