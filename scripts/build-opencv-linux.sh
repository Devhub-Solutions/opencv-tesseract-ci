#!/bin/bash
# ============================================================
# Build OpenCV with Java support on Linux (Ubuntu)
# ============================================================
set -euo pipefail

OPENCV_VERSION="${OPENCV_VERSION:-4.10.0}"
SOURCE_DIR="$HOME/opencv-source"
BUILD_DIR="$HOME/opencv-build"
ARTIFACT_DIR="${GITHUB_WORKSPACE}/artifacts/linux/opencv"

echo "========================================"
echo "Building OpenCV ${OPENCV_VERSION} on Linux (with Java JNI)"
echo "========================================"

# Download source if not cached
if [ ! -d "${SOURCE_DIR}/opencv-${OPENCV_VERSION}" ]; then
    mkdir -p "${SOURCE_DIR}"
    cd "${SOURCE_DIR}"
    
    # Download OpenCV
    curl -L "https://github.com/opencv/opencv/archive/${OPENCV_VERSION}.tar.gz" -o opencv.tar.gz
    tar -xzf opencv.tar.gz
    rm opencv.tar.gz
    
    # Download OpenCV Contrib
    curl -L "https://github.com/opencv/opencv_contrib/archive/${OPENCV_VERSION}.tar.gz" -o opencv_contrib.tar.gz
    tar -xzf opencv_contrib.tar.gz
    rm opencv_contrib.tar.gz
fi

# Create build directory
mkdir -p "${BUILD_DIR}"
cd "${BUILD_DIR}"

# Configure with Java support
cmake "${SOURCE_DIR}/opencv-${OPENCV_VERSION}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="/usr/local" \
    \
    `# Java JNI support` \
    -DBUILD_opencv_java=ON \
    -DBUILD_FAT_JAVA_LIBS=ON \
    \
    `# Module selection` \
    -DOPENCV_EXTRA_MODULES_PATH="${SOURCE_DIR}/opencv_contrib-${OPENCV_VERSION}/modules" \
    -DBUILD_opencv_python3=OFF \
    -DBUILD_opencv_python2=OFF \
    -DBUILD_opencv_js=OFF \
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
    `# Tesseract integration via opencv_text` \
    -DWITH_TESSERACT=ON \
    -DTESSERACT_INCLUDE_DIR="/usr/local/include" \
    -DTESSERACT_LIBRARY="/usr/local/lib/libtesseract.so" \
    \
    `# Other dependencies` \
    -DWITH_FFMPEG=ON \
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
cmake --build . -j$(nproc)

# Verify Java build output
echo "--- Checking Java build output ---"
ls -la "${BUILD_DIR}/bin/"*.jar 2>/dev/null || echo "JAR not found in bin/"
ls -la "${BUILD_DIR}/lib/"*java* 2>/dev/null || echo "Java lib not found in lib/"
find "${BUILD_DIR}" -name "*.jar" -o -name "*java*" 2>/dev/null || true

# Install
sudo cmake --install .
sudo ldconfig

# Collect artifacts for Java integration
mkdir -p "${ARTIFACT_DIR}"

# Copy JAR file
JAR_FILE=$(find "${BUILD_DIR}" -name "opencv-*.jar" -type f | head -1)
if [ -n "${JAR_FILE}" ]; then
    cp "${JAR_FILE}" "${ARTIFACT_DIR}/"
    echo "Copied JAR: ${JAR_FILE}"
else
    echo "WARNING: OpenCV JAR not found!"
fi

# Copy JNI shared library
JNI_LIB=$(find "${BUILD_DIR}/lib" -name "libopencv_java*.so" -type f | head -1)
if [ -n "${JNI_LIB}" ]; then
    cp "${JNI_LIB}" "${ARTIFACT_DIR}/"
    echo "Copied JNI lib: ${JNI_LIB}"
else
    echo "WARNING: OpenCV JNI .so not found!"
    # Fallback: look for the fat java lib
    FAT_LIB=$(find "${BUILD_DIR}" -name "libopencv_java*.so" -type f | head -1)
    if [ -n "${FAT_LIB}" ]; then
        cp "${FAT_LIB}" "${ARTIFACT_DIR}/"
        echo "Copied fat JNI lib: ${FAT_LIB}"
    fi
fi

# Copy JNI header files
mkdir -p "${ARTIFACT_DIR}/include"
HEADER_DIR="${BUILD_DIR}/modules/java/jni"
if [ -d "${HEADER_DIR}" ]; then
    cp -r "${HEADER_DIR}"/*.h "${ARTIFACT_DIR}/include/" 2>/dev/null || true
fi

# Also copy the generated header from build
if [ -d "${BUILD_DIR}/opencv_jni_source" ]; then
    cp "${BUILD_DIR}/opencv_jni_source/"*.h "${ARTIFACT_DIR}/include/" 2>/dev/null || true
fi

echo "OpenCV artifacts collected in ${ARTIFACT_DIR}"
ls -la "${ARTIFACT_DIR}/"
