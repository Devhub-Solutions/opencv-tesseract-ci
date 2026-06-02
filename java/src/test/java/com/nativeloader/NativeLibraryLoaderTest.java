package com.nativeloader;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NativeLibraryLoader.
 * These tests only verify OS/arch detection and loader logic,
 * not actual native library loading (which requires built artifacts).
 */
class NativeLibraryLoaderTest {

    @Test
    void testDetectOS() {
        NativeLibraryLoader.OS os = NativeLibraryLoader.detectOS();
        assertNotNull(os);
        // On CI, the OS should match the runner
        System.out.println("Detected OS: " + os);
    }

    @Test
    void testDetectArch() {
        NativeLibraryLoader.Arch arch = NativeLibraryLoader.detectArch();
        assertNotNull(arch);
        System.out.println("Detected Arch: " + arch);
    }

    @Test
    void testGetLibPrefix() {
        NativeLibraryLoader.OS os = NativeLibraryLoader.detectOS();
        String prefix = NativeLibraryLoader.getLibPrefix();
        if (os == NativeLibraryLoader.OS.WINDOWS) {
            assertEquals("", prefix);
        } else {
            assertEquals("lib", prefix);
        }
    }

    @Test
    void testGetDiagnosticInfo() {
        String info = NativeLibraryLoader.getDiagnosticInfo();
        assertNotNull(info);
        assertTrue(info.contains("OS:"));
        assertTrue(info.contains("Arch:"));
        assertTrue(info.contains("OpenCV loaded:"));
        assertTrue(info.contains("Tesseract loaded:"));
    }

    @Test
    void testInitiallyNotLoaded() {
        assertFalse(NativeLibraryLoader.isOpenCVLoaded());
        assertFalse(NativeLibraryLoader.isTesseractLoaded());
    }

    @Test
    void testSetNativeLibraryPath() {
        NativeLibraryLoader.setNativeLibraryPath("/tmp/test-native");
        String info = NativeLibraryLoader.getDiagnosticInfo();
        assertTrue(info.contains("/tmp/test-native"));
        // Reset
        NativeLibraryLoader.setNativeLibraryPath("");
    }
}
