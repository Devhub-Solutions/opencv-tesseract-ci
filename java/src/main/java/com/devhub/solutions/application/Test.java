package com.devhub.solutions.application;

import com.devhub.solutions.application.loader.NativeLibraryLoader;
import net.sourceforge.tess4j.Tesseract;

public class Test {
    public static void main(String[] args) {
        System.out.println("=== Test Class ===");
        System.out.println("This is a placeholder test class. Replace with actual tests.");

        NativeLibraryLoader.loadTesseract();
        // image /home/pc1/Workspace/opencv-tesseract-ci-java/image.png

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

    }
}
