# OpenCV + Tesseract CI/CD Multi-OS Build Pipeline

Pipeline tự động build OpenCV (với Java JNI) và Tesseract OCR trên **Linux**, **Windows**, **macOS**, export các native library files (.so, .dll, .dylib) và JAR để import trực tiếp vào project Java.

## Kiến trúc Tổng quan

```
┌──────────────────────────────────────────────────────────┐
│                  GitHub Actions CI/CD                     │
│                                                          │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐     │
│  │  Linux X64  │  │ Windows X64 │  │  macOS X64  │     │
│  │  (Ubuntu)   │  │ (MSYS2/MingW│  │  (Xcode)    │     │
│  │             │  │             │  │             │     │
│  │ Leptonica   │  │ Leptonica   │  │ Leptonica   │     │
│  │ Tesseract   │  │ Tesseract   │  │ Tesseract   │     │
│  │ OpenCV+JNI  │  │ OpenCV+JNI  │  │ OpenCV+JNI  │     │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘     │
│         │                │                │             │
│         ▼                ▼                ▼             │
│  ┌──────────────────────────────────────────────┐       │
│  │          Package & Release Job                │       │
│  │  native-libs.zip                             │       │
│  │  ├── native-lib/linux/x86_64/*.so            │       │
│  │  ├── native-lib/windows/x86_64/*.dll         │       │
│  │  ├── native-lib/macos/x86_64/*.dylib         │       │
│  │  ├── jars/opencv-4100.jar                    │       │
│  │  └── headers/                                │       │
│  └──────────────────────────────────────────────┘       │
└──────────────────────────────────────────────────────────┘
```

## Cấu trúc Thư mục

```
opencv-tesseract-ci/
├── .github/
│   └── workflows/
│       └── build-native-libs.yml    # Main CI/CD workflow
├── scripts/
│   ├── build-leptonica-linux.sh
│   ├── build-tesseract-linux.sh
│   ├── build-opencv-linux.sh
│   ├── package-linux.sh
│   ├── build-leptonica-windows.sh
│   ├── build-tesseract-windows.sh
│   ├── build-opencv-windows.sh
│   ├── package-windows.sh
│   ├── build-leptonica-macos.sh
│   ├── build-tesseract-macos.sh
│   ├── build-opencv-macos.sh
│   └── package-macos.sh
└── java/
    ├── pom.xml
    ├── build.gradle
    └── src/main/java/com/nativeloader/
        ├── NativeLibraryLoader.java      # Multi-OS native lib loader
        └── tesseract/
            ├── TessAPI.java              # JNA interface cho Tesseract
            └── TesseractOcr.java         # High-level wrapper
```

## Artifacts Output

Sau khi pipeline chạy xong, các file được export:

### Linux (x86_64)
| File | Mô tả |
|------|--------|
| `libopencv_java410.so` | OpenCV JNI library (fat, chứa tất cả modules) |
| `opencv-4100.jar` | OpenCV Java class files |
| `libtesseract.so.5` | Tesseract OCR shared library |
| `liblept.so.5` | Leptonica image processing library |
| `libtesseract.a` | Tesseract static library (tuỳ chọn) |

### Windows (x86_64)
| File | Mô tả |
|------|--------|
| `opencv_java410.dll` | OpenCV JNI library |
| `opencv-4100.jar` | OpenCV Java class files |
| `libtesseract-5.dll` | Tesseract OCR DLL |
| `liblept-5.dll` | Leptonica DLL |
| `libgcc_s_seh-1.dll` | MinGW runtime |
| `libstdc++-6.dll` | C++ runtime |
| `libwinpthread-1.dll` | pthread runtime |

### macOS (x86_64)
| File | Mô tả |
|------|--------|
| `libopencv_java410.dylib` | OpenCV JNI library |
| `opencv-4100.jar` | OpenCV Java class files |
| `libtesseract.5.dylib` | Tesseract OCR dylib |
| `liblept.5.dylib` | Leptonica dylib |

## Cách sử dụng

### 1. Cài đặt vào Java Project (Maven)

Bước 1: Download `native-libs.zip` từ GitHub Release.

Bước 2: Giải nén vào thư mục project:

```
your-project/
├── native-lib/
│   ├── linux/x86_64/*.so
│   ├── windows/x86_64/*.dll
│   └── macos/x86_64/*.dylib
├── libs/
│   └── opencv-4100.jar
└── pom.xml
```

Bước 3: Thêm dependency vào `pom.xml`:

```xml
<dependencies>
    <!-- OpenCV -->
    <dependency>
        <groupId>org.opencv</groupId>
        <artifactId>opencv</artifactId>
        <version>4.10.0</version>
        <scope>system</scope>
        <systemPath>${project.basedir}/libs/opencv-4100.jar</systemPath>
    </dependency>

    <!-- JNA for Tesseract -->
    <dependency>
        <groupId>net.java.dev.jna</groupId>
        <artifactId>jna</artifactId>
        <version>5.14.0</version>
    </dependency>
</dependencies>
```

### 2. Cài đặt vào Java Project (Gradle)

```groovy
dependencies {
    // OpenCV
    implementation files('libs/opencv-4100.jar')

    // JNA for Tesseract
    api 'net.java.dev.jna:jna:5.14.0'
}
```

### 3. Load Native Libraries trong Code

#### Cách 1: Sử dụng NativeLibraryLoader (khuyến nghị)

```java
import com.nativeloader.NativeLibraryLoader;

public class Main {
    public static void main(String[] args) {
        // Cách 1a: Set custom path
        NativeLibraryLoader.setNativeLibraryPath("/path/to/native-lib");
        NativeLibraryLoader.loadAll();

        // Cách 1b: Hoặc dùng JVM argument
        // java -Dnative.lib.path=/path/to/native-lib -cp app.jar Main
        // NativeLibraryLoader.loadAll();

        // Cách 1c: Hoặc dùng java.library.path
        // java -Djava.library.path=/path/to/native-lib/linux/x86_64 -cp app.jar Main
        // NativeLibraryLoader.loadAll();

        // Sau khi load, sử dụng OpenCV bình thường
        System.out.println("OpenCV version: " + org.opencv.core.Core.VERSION);

        // Hoặc chỉ load riêng
        // NativeLibraryLoader.loadOpenCV();
        // NativeLibraryLoader.loadTesseract();

        // Debug info
        System.out.println(NativeLibraryLoader.getDiagnosticInfo());
    }
}
```

#### Cách 2: Manual loading

```java
// Linux
System.load("/path/to/native-lib/linux/x86_64/libopencv_java410.so");
System.load("/path/to/native-lib/linux/x86_64/liblept.so.5");
System.load("/path/to/native-lib/linux/x86_64/libtesseract.so.5");

// Windows
System.load("C:\\path\\to\\native-lib\\windows\\x86_64\\opencv_java410.dll");
System.load("C:\\path\\to\\native-lib\\windows\\x86_64\\liblept-5.dll");
System.load("C:\\path\\to\\native-lib\\windows\\x86_64\\libtesseract-5.dll");

// macOS
System.load("/path/to/native-lib/macos/x86_64/libopencv_java410.dylib");
System.load("/path/to/native-lib/macos/x86_64/liblept.5.dylib");
System.load("/path/to/native-lib/macos/x86_64/libtesseract.5.dylib");
```

### 4. Sử dụng Tesseract OCR qua JNA

```java
import com.nativeloader.NativeLibraryLoader;
import com.nativeloader.tesseract.TesseractOcr;

public class OcrExample {
    public static void main(String[] args) {
        // Load native libs
        NativeLibraryLoader.loadTesseract();

        // Sử dụng high-level API (try-with-resources)
        try (TesseractOcr ocr = new TesseractOcr()) {
            ocr.setDatapath("/usr/share/tesseract-ocr/5/tessdata");
            ocr.setLanguage("vie+eng");  // Tiếng Việt + Anh
            ocr.setPageSegMode(TesseractOcr.PSM_AUTO);

            // Đọc ảnh từ byte array
            String text = ocr.doOCR(imageBytes, width, height, 3);
            System.out.println("OCR Result: " + text);
        }
    }
}
```

### 5. Sử dụng OpenCV + Tesseract cùng nhau

```java
import com.nativeloader.NativeLibraryLoader;
import com.nativeloader.tesseract.TesseractOcr;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

public class OpenCvOcrPipeline {
    public static void main(String[] args) {
        // Load tất cả native libraries
        NativeLibraryLoader.loadAll();

        // Đọc ảnh bằng OpenCV
        Mat image = Imgcodecs.imread("document.png");

        // Preprocessing: chuyển sang grayscale
        Mat gray = new Mat();
        Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);

        // Threshold để tăng chất lượng OCR
        Mat binary = new Mat();
        Imgproc.threshold(gray, binary, 0, 255, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU);

        // OCR bằng Tesseract
        try (TesseractOcr ocr = new TesseractOcr()) {
            ocr.setDatapath("/usr/share/tesseract-ocr/5/tessdata");
            ocr.setLanguage("eng");

            int width = binary.cols();
            int height = binary.rows();
            int channels = binary.channels();
            byte[] imageData = new byte[(int) binary.total() * channels];
            binary.get(0, 0, imageData);

            String text = ocr.doOCR(imageData, width, height, channels);
            System.out.println("Recognized text:\n" + text);
        }
    }
}
```

## Cấu hình Pipeline

### Kích hoạt Build

Pipeline chạy tự động khi:
- Push lên branch `main` hoặc `develop`
- Tạo tag `v*` (ví dụ: `v1.0.0`)
- Pull request vào `main`
- Manual trigger qua GitHub UI (workflow_dispatch)

### Tuỳ chỉnh Version

Khi trigger manual, bạn có thể chỉ định version:

| Parameter | Default | Mô tả |
|-----------|---------|--------|
| `opencv_version` | 4.10.0 | OpenCV version |
| `tesseract_version` | 5.5.0 | Tesseract version |
| `leptonica_version` | 1.85.0 | Leptonica version |

### Chạy local (không cần CI)

Linux:
```bash
export OPENCV_VERSION=4.10.0
export TESSERACT_VERSION=5.5.0
export LEPTONICA_VERSION=1.85.0

# Install deps
sudo apt-get install build-essential cmake git ant default-jdk \
    libgtk-3-dev libavcodec-dev libavformat-dev libswscale-dev \
    libtbb-dev libjpeg-dev libpng-dev libtiff-dev libwebp-dev \
    autoconf automake libtool

# Build
bash scripts/build-leptonica-linux.sh
bash scripts/build-tesseract-linux.sh
bash scripts/build-opencv-linux.sh
```

macOS:
```bash
brew install cmake ant pkg-config eigen nasm jpeg libpng libtiff openjpeg openexr ffmpeg tbb
bash scripts/build-leptonica-macos.sh
bash scripts/build-tesseract-macos.sh
bash scripts/build-opencv-macos.sh
```

### JVM Arguments cho Java Application

```bash
# Linux
java -Dnative.lib.path=./native-lib \
     -Djava.library.path=./native-lib/linux/x86_64 \
     -cp "app.jar:libs/opencv-4100.jar" com.example.Main

# Windows
java -Dnative.lib.path=./native-lib ^
     -Djava.library.path=./native-lib/windows/x86_64 ^
     -cp "app.jar;libs/opencv-4100.jar" com.example.Main

# macOS
java -Dnative.lib.path=./native-lib \
     -Djava.library.path=./native-lib/macos/x86_64 \
     -cp "app.jar:libs/opencv-4100.jar" com.example.Main
```

### Docker Deployment

```dockerfile
FROM eclipse-temurin:17-jre

# Copy native libs
COPY native-lib/linux/x86_64/ /app/native-lib/
COPY libs/opencv-4100.jar /app/libs/

ENV native.lib.path=/app/native-lib

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

## Troubleshooting

### "UnsatisfiedLinkError: no opencv_java in java.library.path"

1. Kiểm tra file native lib tồn tại:
   ```bash
   ls -la native-lib/linux/x86_64/
   ```

2. Kiểm tra diagnostic:
   ```java
   System.out.println(NativeLibraryLoader.getDiagnosticInfo());
   ```

3. Thêm JVM argument:
   ```bash
   -Djava.library.path=/full/path/to/native-lib/linux/x86_64
   ```

### "symbol lookup error" trên Linux

Thiếu shared library dependency. Kiểm tra:
```bash
ldd native-lib/linux/x86_64/libopencv_java410.so
ldd native-lib/linux/x86_64/libtesseract.so.5
```

Cài thêm thiếu:
```bash
sudo apt-get install libtbb2 libgomp1
```

### Windows DLL không load

1. Kiểm tra Visual C++ Redistributable đã cài chưa
2. Đảm bảo tất cả DLL đi kèm (libgcc, libstdc++, libwinpthread) cùng thư mục
3. Add thư mục chứa DLL vào PATH environment variable

### macOS Gatekeeper chặn dylib

```bash
xattr -cr /path/to/native-lib/
```
