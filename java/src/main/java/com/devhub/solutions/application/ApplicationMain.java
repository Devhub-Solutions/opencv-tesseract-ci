package com.devhub.solutions.application;

import com.devhub.solutions.application.loader.NativeLibraryLoader;
import com.devhub.solutions.application.tesseract.TessAPI;
import com.devhub.solutions.application.tesseract.TesseractOcr;

import net.sourceforge.tess4j.Tesseract;

/**
 * Application entry point demonstrating OpenCV + Tesseract native library
 * loading.
 *
 * <p>
 * This class demonstrates the correct loading order:
 * </p>
 * <ol>
 * <li>Call {@link NativeLibraryLoader#loadTesseract()} which loads native
 * .so/.dll/.dylib
 * files via {@code System.load()} AND configures {@code jna.library.path}</li>
 * <li>Call {@link NativeLibraryLoader#loadOpenCV()} for OpenCV native
 * library</li>
 * <li>Access {@link TessAPI#getInstance()} which lazily initializes JNA using
 * the
 * configured {@code jna.library.path}</li>
 * </ol>
 *
 * <p>
 * Compatible with native-libs v0.0.3+ where:
 * <ul>
 * <li>WITH_FFMPEG=OFF (no libdc1394, libavcodec, etc.)</li>
 * <li>Image format libs are bundled</li>
 * <li>RPATH=$ORIGIN is set on Linux .so files</li>
 * <li>libarchive/libcurl are disabled in Tesseract build</li>
 * </ul>
 * </p>
 *
 * <p>
 * Usage:
 * </p>
 * 
 * <pre>
 *   # Recommended: Set LD_LIBRARY_PATH for Linux dependency resolution
 *   LD_LIBRARY_PATH=./native-lib/linux/x86_64 java -Dnative.lib.path=./native-lib -cp target/opencv-tesseract-ci-1.2.0.jar com.devhub.solutions.application.ApplicationMain
 *
 *   # Or use run.sh script
 *   bash run.sh
 * </pre>
 */
public class ApplicationMain {

    public static void main(String[] args) {
        System.out.println("=== OpenCV + Tesseract CI/CD Application ===");
        System.out.println("  native-libs v0.0.3+ (FFMPEG disabled, image libs bundled, self-contained)");
        System.out.println("  Java loader v1.2.0 (jna.library.path fix)");
        System.out.println();

        // Print platform info
        System.out.println("Platform Information:");
        System.out.println("  OS: " + NativeLibraryLoader.detectOS());
        System.out.println("  Architecture: " + NativeLibraryLoader.detectArch());
        System.out.println("  Library Extension: " + NativeLibraryLoader.detectOS().getLibExtension());
        System.out.println();

        // Print diagnostic info
        System.out.println(NativeLibraryLoader.getDiagnosticInfo());

        // Attempt to load native libraries
        System.out.println("Attempting to load native libraries...");
        System.out.println();

        // Load Tesseract first (OpenCV opencv_text depends on it)
        try {
            NativeLibraryLoader.loadTesseract();
            //image /home/pc1/Workspace/opencv-tesseract-ci-java/image.png
            try (TesseractOcr ocr = new TesseractOcr()) {
                ocr.setDatapath("/home/pc1/Workspace/opencv-tesseract-ci-java/tessdata");
                ocr.setLanguage("vie");
                ocr.setPageSegMode(TesseractOcr.PSM_AUTO);
               try {
                    byte[] imageBytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/home/pc1/Workspace/opencv-tesseract-ci-java/image.png"));
                    String result = ocr.doOCR(imageBytes, 10, 20, 3); // width, height, channels
                    System.out.println("OCR Result:");
                    System.out.println(result);
                } catch (Exception e) {
                    System.err.println("Error during OCR: " + e.getMessage());
                }
            }
            Tesseract tesseract = new Tesseract();
            tesseract.setDatapath("/home/pc1/Workspace/opencv-tesseract-ci-java/tessdata");
            tesseract.setLanguage("vie");
            try {
                String result = tesseract.doOCR(new java.io.File("/home/pc1/Workspace/opencv-tesseract-ci-java/image.png"));
                System.out.println("Tess4J OCR Result:");
                System.out.println(result);
            } catch (Exception e) {
                System.err.println("Error during Tess4J OCR: " + e.getMessage());
            }   
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Tesseract loading failed: " + e.getMessage());
            printTesseractTroubleshooting();
        }

        // Then load OpenCV
        try {
            NativeLibraryLoader.loadOpenCV();
        } catch (UnsatisfiedLinkError e) {
            System.err.println("OpenCV loading failed: " + e.getMessage());
            printOpenCvTroubleshooting();
        }

        System.out.println();

        // If OpenCV is loaded, try to get version
        if (NativeLibraryLoader.isOpenCVLoaded()) {
            try {
                System.out.println("OpenCV Version: " + org.opencv.core.Core.VERSION);
                System.out.println();
            } catch (Exception e) {
                System.err.println("Could not read OpenCV version: " + e.getMessage());
            }
        }

        // If Tesseract is loaded, try to get version via JNA
        if (NativeLibraryLoader.isTesseractLoaded()) {
            try {
                // Use getInstance() instead of INSTANCE for lazy JNA initialization
                String tessVersion = TessAPI.getInstance().TessVersion();
                System.out.println("Tesseract Version: " + tessVersion);
                System.out.println();
            } catch (Exception e) {
                System.err.println("Could not read Tesseract version: " + e.getMessage());
                System.err.println("  This usually means JNA cannot find libtesseract.so");
                System.err.println("  Ensure jna.library.path includes the native-lib directory");
                System.err
                        .println("  Current jna.library.path: " + System.getProperty("jna.library.path", "(not set)"));
            }
        }

        System.out.println("Loading Status:");
        System.out.println("  OpenCV Loaded: " + NativeLibraryLoader.isOpenCVLoaded());
        System.out.println("  Tesseract Loaded: " + NativeLibraryLoader.isTesseractLoaded());
        System.out.println("  jna.library.path: " + System.getProperty("jna.library.path", "(not set)"));

        if (!NativeLibraryLoader.isOpenCVLoaded() || !NativeLibraryLoader.isTesseractLoaded()) {
            System.out.println();
            System.out.println("=== Troubleshooting ===");
            if (!NativeLibraryLoader.isOpenCVLoaded()) {
                printOpenCvTroubleshooting();
            }
            if (!NativeLibraryLoader.isTesseractLoaded()) {
                printTesseractTroubleshooting();
            }
        }
    }

    private static void printOpenCvTroubleshooting() {
        System.out.println();
        System.out.println("OpenCV troubleshooting:");
        System.out.println("  1. Ensure native-lib/linux/x86_64/ contains libopencv_java4100.so");
        System.out.println("  2. Set LD_LIBRARY_PATH to include native-lib directory:");
        System.out.println("     LD_LIBRARY_PATH=./native-lib/linux/x86_64 java ...");
        System.out.println("  3. On Linux, install system dependencies if needed:");
        System.out.println("     sudo apt install libarchive13 libcurl4");
        System.out.println("  4. Or use run.sh which sets LD_LIBRARY_PATH automatically");
    }

    private static void printTesseractTroubleshooting() {
        System.out.println();
        System.out.println("Tesseract troubleshooting:");
        System.out.println("  1. Ensure native-lib/linux/x86_64/ contains libtesseract.so and liblept.so");
        System.out.println("  2. Set LD_LIBRARY_PATH to include native-lib directory:");
        System.out.println("     LD_LIBRARY_PATH=./native-lib/linux/x86_64 java ...");
        System.out.println("  3. Verify jna.library.path includes the native-lib directory:");
        System.out.println("     java -Djna.library.path=./native-lib/linux/x86_64 ...");
        System.out.println("  4. Or use run.sh which sets paths automatically");
    }
}
