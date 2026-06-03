package com.devhub.solutions.application;

import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;

import com.devhub.solutions.application.loader.NativeLibraryLoader;

public class TestOpenCV {
    public static void main(String[] args) {
        System.out.println("=== Test Class ===");
        System.out.println("This is a placeholder test class. Replace with actual tests.");

        NativeLibraryLoader.loadOpenCV();
        // image /home/pc1/Workspace/opencv-tesseract-ci-java/image.png
        Mat image = Imgcodecs.imread("/home/pc1/Workspace/opencv-tesseract-ci-java/image.png");
        if (image.empty()) {
            System.err.println("Failed to load image. Check the file path and permissions.");
        } else {
            System.out.println("Image loaded successfully. Dimensions: " + image.width() + "x" + image.height());
        }
    }
}
