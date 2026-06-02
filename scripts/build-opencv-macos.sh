#!/bin/bash
# ============================================================
# Build OpenCV with Java support on macOS
# ============================================================
set -euo pipefail

OPENCV_VERSION="${OPENCV_VERSION:-4.10.0}"
INSTALL_PREFIX="/usr/local"
SOURCE_DIR="$HOME/opencv-source"
BUILD_DIR="$HOME/opencv-build"
ARTIFACT_DIR="${GITHUB_WORKSPACE}/artifacts/macos/opencv"

echo "========================================"
echo "Building OpenCV ${OPENCV_VERSION} on macOS (with Java JNI)"
echo "========================================"

echo "JAVA_HOME: ${JAVA_HOME}"

# Download source if not cached
if [ ! -d "${SOURCE_DIR}/opencv-${OPENCV_VERSION}" ]; then
    mkdir -p "${SOURCE_DIR}"
    cd "${SOURCE_DIR}"
    
    curl -L "https://github.com/opencv/opencv/archive/${OPENCV_VERSION}.tar.gz" -o opencv.tar.gz
    tar -xzf opencv.tar.gz
    rm opencv.tar.gz
    
    curl -L "https://github.com/opencv/opencv_contrib/archive/${OPENCV_VERSION}.tar.gz" -o opencv_contrib.tar.gz
    tar -xzf opencv_contrib.tar.gz
    rm opencv_contrib.tar.gz
fi

# Create build directory
mkdir -p "${BUILD_DIR}"
cd "${BUILD_DIR}"

# Prefer the freshly built Tesseract/Leptonica under /usr/local over Homebrew
# or system metadata, and avoid FFmpeg API drift in OpenCV 4.10 builds.
export PKG_CONFIG_PATH="${INSTALL_PREFIX}/lib/pkgconfig:${PKG_CONFIG_PATH:-}"
export CMAKE_PREFIX_PATH="${INSTALL_PREFIX}:${CMAKE_PREFIX_PATH:-}"

# Configure with Java support
cmake "${SOURCE_DIR}/opencv-${OPENCV_VERSION}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="${INSTALL_PREFIX}" \
    -DCMAKE_PREFIX_PATH="${INSTALL_PREFIX}" \
    -DCMAKE_OSX_ARCHITECTURES="x86_64" \
    \
    `# Java JNI support` \
    -DBUILD_opencv_java=ON \
    -DBUILD_FAT_JAVA_LIBS=ON \
    -DJAVA_HOME="${JAVA_HOME}" \
    -DJAVA_AWT_INCLUDE_PATH="${JAVA_HOME}/include" \
    -DJAVA_INCLUDE_PATH="${JAVA_HOME}/include" \
    -DJAVA_INCLUDE_PATH2="${JAVA_HOME}/include/darwin" \
    -DJAVA_JVM_LIBRARY="${JAVA_HOME}/lib/server/libjvm.dylib" \
    \
    `# Module selection` \
    -DOPENCV_EXTRA_MODULES_PATH="${SOURCE_DIR}/opencv_contrib-${OPENCV_VERSION}/modules" \
    -DBUILD_opencv_python3=OFF \
    -DBUILD_opencv_python2=OFF \
    \
    `# Build optimization` \
    -DBUILD_TESTS=OFF \
    -DBUILD_PERF_TESTS=OFF \
    -DBUILD_EXAMPLES=OFF \
    -DBUILD_DOCS=OFF \
    -DBUILD_opencv_apps=OFF \
    \
    `# Core modules` \
    -DBUILD_opencv_core=ON \
    -DBUILD_opencv_imgproc=ON \
    -DBUILD_opencv_imgcodecs=ON \
    -DBUILD_opencv_videoio=ON \
    -DBUILD_opencv_highgui=OFF \
    -DBUILD_opencv_objdetect=ON \
    -DBUILD_opencv_dnn=ON \
    -DBUILD_opencv_features2d=ON \
    -DBUILD_opencv_calib3d=ON \
    -DBUILD_opencv_text=ON \
    \
    `# Tesseract integration` \
    -DWITH_TESSERACT=ON \
    -DTesseract_FOUND=TRUE \
    -DTesseract_INCLUDE_DIR="${INSTALL_PREFIX}/include" \
    -DTesseract_INCLUDE_DIRS="${INSTALL_PREFIX}/include" \
    -DTesseract_LIBRARY="${INSTALL_PREFIX}/lib/libtesseract.dylib" \
    -DLept_LIBRARY="${INSTALL_PREFIX}/lib/libleptonica.dylib" \
    -DTesseract_LIBRARIES="${INSTALL_PREFIX}/lib/libtesseract.dylib;${INSTALL_PREFIX}/lib/libleptonica.dylib" \
    \
    `# macOS specific` \
    -DWITH_FFMPEG=OFF \
    -DWITH_GTK=OFF \
    -DWITH_V4L=OFF \
    -DWITH_EIGEN=ON \
    -DWITH_TBB=ON \
    -DWITH_OPENCL=ON \
    -DWITH_JPEG=ON \
    -DWITH_PNG=ON \
    -DWITH_TIFF=ON \
    -DWITH_WEBP=ON \
    -DWITH_OPENJPEG=ON

# Build
cmake --build . -j$(sysctl -n hw.ncpu)

# Install
cmake --install .

# Collect artifacts
mkdir -p "${ARTIFACT_DIR}"

# Copy JAR file
JAR_FILE=$(find "${BUILD_DIR}" -name "opencv-*.jar" -type f | head -1)
if [ -n "${JAR_FILE}" ]; then
    cp "${JAR_FILE}" "${ARTIFACT_DIR}/"
    echo "Copied JAR: ${JAR_FILE}"
else
    echo "WARNING: OpenCV JAR not found!"
fi

# Copy JNI dylib
JNI_DYLIB=$(find "${BUILD_DIR}" -name "libopencv_java*.dylib" -type f | head -1)
if [ -n "${JNI_DYLIB}" ]; then
    cp "${JNI_DYLIB}" "${ARTIFACT_DIR}/"
    echo "Copied JNI dylib: ${JNI_DYLIB}"
else
    echo "WARNING: OpenCV JNI dylib not found, searching alternatives..."
    find "${BUILD_DIR}" -name "*.dylib" | head -20
fi

# Copy header files
mkdir -p "${ARTIFACT_DIR}/include"
HEADER_DIR="${BUILD_DIR}/modules/java/jni"
if [ -d "${HEADER_DIR}" ]; then
    cp -r "${HEADER_DIR}"/*.h "${ARTIFACT_DIR}/include/" 2>/dev/null || true
fi

echo "OpenCV artifacts collected in ${ARTIFACT_DIR}"
ls -la "${ARTIFACT_DIR}/"
