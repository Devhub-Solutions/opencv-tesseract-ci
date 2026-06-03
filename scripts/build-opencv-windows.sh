#!/bin/bash
# ============================================================
# Build OpenCV with Java support on Windows (MSYS2/MinGW64)
# ============================================================
set -euo pipefail

OPENCV_VERSION="${OPENCV_VERSION:-4.10.0}"
INSTALL_PREFIX="/mingw64"
SOURCE_DIR="$HOME/opencv-source"
BUILD_DIR="$HOME/opencv-build"
ARTIFACT_DIR="$(cygpath -u "${GITHUB_WORKSPACE}")/artifacts/windows/opencv"

echo "========================================"
echo "Building OpenCV ${OPENCV_VERSION} on Windows (with Java JNI)"
echo "========================================"

if command -v mingw32-make >/dev/null 2>&1; then
    CMAKE_GENERATOR_ARGS=(-G "MinGW Makefiles" -DCMAKE_MAKE_PROGRAM="$(command -v mingw32-make)")
elif command -v make >/dev/null 2>&1; then
    CMAKE_GENERATOR_ARGS=(-G "Unix Makefiles" -DCMAKE_MAKE_PROGRAM="$(command -v make)")
else
    echo "ERROR: neither mingw32-make nor make was found on PATH" >&2
    exit 1
fi

# Find JAVA_HOME
JAVA_HOME_WIN="${JAVA_HOME}"
JAVA_HOME_UNIX="$(cygpath -u "${JAVA_HOME}")"
echo "JAVA_HOME (Windows): ${JAVA_HOME_WIN}"
echo "JAVA_HOME (Unix):    ${JAVA_HOME_UNIX}"

# Find ant
ANT_PATH=$(which ant 2>/dev/null || echo "")
echo "Ant path: ${ANT_PATH}"

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

# Prefer the MSYS2 libraries installed by the previous Leptonica/Tesseract steps.
export PKG_CONFIG_PATH="${INSTALL_PREFIX}/lib/pkgconfig:${PKG_CONFIG_PATH:-}"
export CMAKE_PREFIX_PATH="${INSTALL_PREFIX}:${CMAKE_PREFIX_PATH:-}"

# Configure with Java support
# FIX: Added BUILD_SHARED_LIBS=OFF for fat JNI library
cmake "${SOURCE_DIR}/opencv-${OPENCV_VERSION}" \
    "${CMAKE_GENERATOR_ARGS[@]}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="${INSTALL_PREFIX}" \
    -DCMAKE_PREFIX_PATH="${INSTALL_PREFIX}" \
    \
    -DBUILD_SHARED_LIBS=OFF \
    \
    -DBUILD_opencv_java=ON \
    -DBUILD_FAT_JAVA_LIBS=ON \
    -DJAVA_HOME="${JAVA_HOME_UNIX}" \
    -DJAVA_AWT_INCLUDE_PATH="${JAVA_HOME_UNIX}/include" \
    -DJAVA_INCLUDE_PATH="${JAVA_HOME_UNIX}/include" \
    -DJAVA_INCLUDE_PATH2="${JAVA_HOME_UNIX}/include/win32" \
    -DJAVA_JVM_LIBRARY="${JAVA_HOME_UNIX}/lib/server/jvm.dll" \
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
    -DBUILD_opencv_videoio=ON \
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
    -DTesseract_LIBRARY="${INSTALL_PREFIX}/lib/libtesseract.dll.a" \
    -DLept_LIBRARY="${INSTALL_PREFIX}/lib/libleptonica.dll.a" \
    -DTesseract_LIBRARIES="${INSTALL_PREFIX}/lib/libtesseract.dll.a;${INSTALL_PREFIX}/lib/libleptonica.dll.a" \
    \
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
cmake --build . -j$(nproc)

# Install
cmake --install .

# Verify fat JNI library
echo "--- Verifying fat JNI DLL ---"
JNI_DLL_CHECK=$(find "${BUILD_DIR}" -name "opencv_java*.dll" -type f | head -1)
if [ -n "${JNI_DLL_CHECK}" ]; then
    echo "JNI DLL: ${JNI_DLL_CHECK}"
    echo "File size: $(du -h "${JNI_DLL_CHECK}" | cut -f1)"
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

# Copy JNI DLL
JNI_DLL=$(find "${BUILD_DIR}" -name "opencv_java*.dll" -type f | head -1)
if [ -n "${JNI_DLL}" ]; then
    cp "${JNI_DLL}" "${ARTIFACT_DIR}/"
    echo "Copied JNI DLL: ${JNI_DLL}"
else
    echo "WARNING: OpenCV JNI DLL not found, searching alternatives..."
    find "${BUILD_DIR}" -name "*.dll" | head -20
fi

# Copy header files
mkdir -p "${ARTIFACT_DIR}/include"
HEADER_DIR="${BUILD_DIR}/modules/java/jni"
if [ -d "${HEADER_DIR}" ]; then
    cp -r "${HEADER_DIR}"/*.h "${ARTIFACT_DIR}/include/" 2>/dev/null || true
fi

echo "OpenCV artifacts collected in ${ARTIFACT_DIR}"
ls -la "${ARTIFACT_DIR}/"