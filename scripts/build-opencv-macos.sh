#!/bin/bash
# ============================================================
# Build OpenCV with Java support on macOS
# FIX: Disable WITH_FFMPEG, WITH_TBB, WITH_OPENEXR for portability
# FIX: Use install_name_tool to fix dylib paths for relocation
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
export PKG_CONFIG_PATH="${INSTALL_PREFIX}/lib/pkgconfig:${PKG_CONFIG_PATH:-}"
export CMAKE_PREFIX_PATH="${INSTALL_PREFIX}:${CMAKE_PREFIX_PATH:-}"

# FIX: Same as Linux - disable FFMPEG, TBB, OpenEXR for portability
cmake "${SOURCE_DIR}/opencv-${OPENCV_VERSION}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="${INSTALL_PREFIX}" \
    -DCMAKE_PREFIX_PATH="${INSTALL_PREFIX}" \
    -DCMAKE_OSX_ARCHITECTURES="x86_64" \
    \
    -DBUILD_SHARED_LIBS=OFF \
    \
    -DBUILD_opencv_java=ON \
    -DBUILD_FAT_JAVA_LIBS=ON \
    -DJAVA_HOME="${JAVA_HOME}" \
    -DJAVA_AWT_INCLUDE_PATH="${JAVA_HOME}/include" \
    -DJAVA_INCLUDE_PATH="${JAVA_HOME}/include" \
    -DJAVA_INCLUDE_PATH2="${JAVA_HOME}/include/darwin" \
    -DJAVA_JVM_LIBRARY="${JAVA_HOME}/lib/server/libjvm.dylib" \
    \
    -DOPENCV_EXTRA_MODULES_PATH="${SOURCE_DIR}/opencv_contrib-${OPENCV_VERSION}/modules" \
    -DBUILD_opencv_python3=OFF \
    -DBUILD_opencv_python2=OFF \
    \
    -DBUILD_TESTS=OFF \
    -DBUILD_PERF_TESTS=OFF \
    -DBUILD_EXAMPLES=OFF \
    -DBUILD_DOCS=OFF \
    -DBUILD_opencv_apps=OFF \
    \
    -DBUILD_opencv_core=ON \
    -DBUILD_opencv_imgproc=ON \
    -DBUILD_opencv_imgcodecs=ON \
    -DBUILD_opencv_videoio=OFF \
    -DBUILD_opencv_highgui=OFF \
    -DBUILD_opencv_objdetect=ON \
    -DBUILD_opencv_dnn=ON \
    -DBUILD_opencv_features2d=ON \
    -DBUILD_opencv_calib3d=ON \
    -DBUILD_opencv_text=ON \
    \
    -DWITH_TESSERACT=ON \
    -DTesseract_FOUND=TRUE \
    -DTesseract_INCLUDE_DIR="${INSTALL_PREFIX}/include" \
    -DTesseract_INCLUDE_DIRS="${INSTALL_PREFIX}/include" \
    -DTesseract_LIBRARY="${INSTALL_PREFIX}/lib/libtesseract.dylib" \
    -DLept_LIBRARY="${INSTALL_PREFIX}/lib/libleptonica.dylib" \
    -DTesseract_LIBRARIES="${INSTALL_PREFIX}/lib/libtesseract.dylib;${INSTALL_PREFIX}/lib/libleptonica.dylib" \
    \
    -DWITH_FFMPEG=OFF \
    -DWITH_GTK=OFF \
    -DWITH_V4L=OFF \
    -DWITH_DC1394=OFF \
    -DWITH_OPENEXR=OFF \
    -DWITH_EIGEN=ON \
    -DWITH_TBB=OFF \
    -DWITH_OPENCL=ON \
    -DWITH_JPEG=ON \
    -DWITH_PNG=ON \
    -DWITH_TIFF=ON \
    -DWITH_WEBP=ON \
    -DWITH_OPENJPEG=ON \
    \
    -DCMAKE_INSTALL_RPATH="@executable_path;@loader_path" \
    -DCMAKE_BUILD_WITH_INSTALL_RPATH=ON

# Build
cmake --build . -j$(sysctl -n hw.ncpu)

# Install
cmake --install .

# Verify fat JNI library
echo "--- Verifying fat JNI dylib ---"
JNI_DYLIB_CHECK=$(find "${BUILD_DIR}" -name "libopencv_java*.dylib" -type f | head -1)
if [ -n "${JNI_DYLIB_CHECK}" ]; then
    echo "JNI dylib: ${JNI_DYLIB_CHECK}"
    echo "File size: $(du -h "${JNI_DYLIB_CHECK}" | cut -f1)"
    echo "Dynamic dependencies:"
    otool -L "${JNI_DYLIB_CHECK}" 2>/dev/null || true
fi

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

    # FIX: Use install_name_tool to change absolute dylib paths to @rpath
    # This allows the dylib to be relocated (moved to any directory)
    echo "--- Fixing dylib install names for portability ---"

    # Change the library's own ID
    install_name_tool -id "@rpath/libopencv_java4100.dylib" "${ARTIFACT_DIR}/libopencv_java4100.dylib" 2>/dev/null || true

    # Change references to Tesseract/Leptonica from absolute to @rpath
    for dep in $(otool -L "${ARTIFACT_DIR}/libopencv_java4100.dylib" 2>/dev/null | grep -E "/usr/local/lib/lib(tesseract|leptonica)" | awk '{print $1}'); do
        dep_name=$(basename "$dep")
        echo "  Changing $dep → @rpath/$dep_name"
        install_name_tool -change "$dep" "@rpath/$dep_name" "${ARTIFACT_DIR}/libopencv_java4100.dylib" 2>/dev/null || true
    done

    echo "Updated dependencies:"
    otool -L "${ARTIFACT_DIR}/libopencv_java4100.dylib" 2>/dev/null || true
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
