#!/bin/bash
# ============================================================
# Build OpenCV with Java support on Linux (Ubuntu)
# FIX: Disable WITH_FFMPEG to avoid 50+ transitive deps (libdc1394, libvpx, etc.)
# FIX: Disable WITH_DC1394 to avoid FireWire camera dependency
# FIX: Disable WITH_OPENEXR to avoid libIlmImf dependency
# FIX: Set RPATH=$ORIGIN so bundled .so files can find each other
# FIX: Collect ALL transitive dependencies that OpenCV JNI library needs
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

# Prefer the freshly built Tesseract/Leptonica under /usr/local over any
# distro-provided pkg-config files or libraries that may exist on the runner.
export PKG_CONFIG_PATH="/usr/local/lib/pkgconfig:${PKG_CONFIG_PATH:-}"
export CMAKE_PREFIX_PATH="/usr/local:${CMAKE_PREFIX_PATH:-}"

# Configure with Java support
# FIX: BUILD_SHARED_LIBS=OFF so OpenCV modules are built as static
# archives and linked into the fat JNI library.
#
# FIX: WITH_FFMPEG=OFF - FFMPEG pulls in libavcodec, libavformat, etc.
# which themselves depend on libdc1394, libvpx, libx264, libx265, libIlmImf,
# libtbb, etc. This creates a massive transitive dependency chain.
# If video codec support is needed, use a separate pipeline that bundles ALL deps.
#
# FIX: WITH_DC1394=OFF - FireWire camera support, not needed for OCR,
# and libdc1394 is not available on most user systems.
#
# FIX: WITH_OPENEXR=OFF - OpenEXR pulls in libIlmImf which is rarely
# available on user systems and not needed for OCR.
#
# FIX: WITH_TBB=OFF for building, but link TBB statically if needed.
# TBB's libtbb.so.12 is not available on all systems. Disabling to avoid
# the dependency. Performance impact is minimal for OCR workloads.
#
# FIX: CMAKE_INSTALL_RPATH=$ORIGIN so the JNI library can find bundled
# dependencies (libtesseract, libleptonica) in the same directory.
cmake "${SOURCE_DIR}/opencv-${OPENCV_VERSION}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="/usr/local" \
    -DCMAKE_PREFIX_PATH="/usr/local" \
    \
    -DBUILD_SHARED_LIBS=OFF \
    \
    -DBUILD_opencv_java=ON \
    -DBUILD_FAT_JAVA_LIBS=ON \
    \
    -DOPENCV_EXTRA_MODULES_PATH="${SOURCE_DIR}/opencv_contrib-${OPENCV_VERSION}/modules" \
    -DBUILD_opencv_python3=OFF \
    -DBUILD_opencv_python2=OFF \
    -DBUILD_opencv_js=OFF \
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
    -DTesseract_INCLUDE_DIR="/usr/local/include" \
    -DTesseract_INCLUDE_DIRS="/usr/local/include" \
    -DTesseract_LIBRARY="/usr/local/lib/libtesseract.so" \
    -DLept_LIBRARY="/usr/local/lib/libleptonica.so" \
    -DTesseract_LIBRARIES="/usr/local/lib/libtesseract.so;/usr/local/lib/libleptonica.so" \
    \
    -DWITH_FFMPEG=OFF \
    -DWITH_GSTREAMER=OFF \
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
    -DCMAKE_INSTALL_RPATH="\$ORIGIN" \
    -DCMAKE_BUILD_WITH_INSTALL_RPATH=ON

# Build
cmake --build . -j$(nproc)

# Verify Java build output
echo "--- Checking Java build output ---"
ls -la "${BUILD_DIR}/bin/"*.jar 2>/dev/null || echo "JAR not found in bin/"
ls -la "${BUILD_DIR}/lib/"*java* 2>/dev/null || echo "Java lib not found in lib/"
find "${BUILD_DIR}" -name "*.jar" -o -name "*java*" 2>/dev/null || true

echo "--- Verifying fat JNI library has minimal external dependencies ---"
JNI_LIB=$(find "${BUILD_DIR}/lib" -name "libopencv_java*.so" -type f | head -1)
if [ -n "${JNI_LIB}" ]; then
    echo "JNI library: ${JNI_LIB}"
    echo "File size: $(du -h "${JNI_LIB}" | cut -f1)"
    echo "Dynamic dependencies:"
    ldd "${JNI_LIB}" 2>/dev/null || true

    # Check for problematic dependencies
    echo ""
    echo "--- Checking for unbundled dependencies ---"
    MISSING_COUNT=0
    while IFS= read -r line; do
        dep_name=$(echo "$line" | awk '{print $1}')
        dep_status=$(echo "$line" | grep -o "not found" || true)
        if [ -n "$dep_status" ]; then
            echo "  MISSING: $dep_name"
            MISSING_COUNT=$((MISSING_COUNT + 1))
        else
            # Check if it's a non-system library that we need to bundle
            case "$dep_name" in
                linux-vdso.so.*|libc.so.*|libm.so.*|libstdc++.so.*|libgcc_s.so.*|libpthread.so.*|libdl.so.*|librt.so.*|ld-linux-*.so.*)
                    # System base libraries - always available
                    ;;
                libz.so.*|libpng16.so.*|libjpeg.so.*|libtiff.so.*|libwebp.so.*|libopenjp2.so.*)
                    echo "  NEEDS BUNDLING: $dep_name (image format lib)"
                    ;;
                libtesseract.*|liblept.*|libleptonica.*)
                    echo "  NEEDS BUNDLING: $dep_name (OCR lib)"
                    ;;
                libdc1394.*|libavcodec.*|libavformat.*|libavutil.*|libswscale.*|libswresample.*|libIlmImf.*|libtbb.*)
                    echo "  PROBLEMATIC: $dep_name (should be disabled or statically linked)"
                    ;;
                libgif.*|libwebpmux.*)
                    echo "  NEEDS BUNDLING: $dep_name (image format lib)"
                    ;;
            esac
        fi
    done < <(ldd "${JNI_LIB}" 2>/dev/null)

    echo ""
    if [ ${MISSING_COUNT} -gt 0 ]; then
        echo "WARNING: ${MISSING_COUNT} missing dependencies detected!"
    else
        echo "All direct dependencies resolved."
    fi

    # Check that no individual OpenCV module .so files are needed
    if ldd "${JNI_LIB}" 2>/dev/null | grep -q "libopencv_"; then
        echo ""
        echo "ERROR: Fat JNI library still depends on individual OpenCV modules!"
        ldd "${JNI_LIB}" 2>/dev/null | grep "libopencv_"
    else
        echo ""
        echo "OK: Fat JNI library is self-contained (no libopencv_*.so dependencies)"
    fi
fi

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
    FAT_LIB=$(find "${BUILD_DIR}" -name "libopencv_java*.so" -type f | head -1)
    if [ -n "${FAT_LIB}" ]; then
        cp "${FAT_LIB}" "${ARTIFACT_DIR}/"
        echo "Copied fat JNI lib: ${FAT_LIB}"
    fi
fi

# FIX: Collect ALL transitive dependencies of the OpenCV JNI library
# This ensures that the release bundle is self-contained
echo "--- Collecting transitive dependencies for OpenCV JNI ---"
NATIVE_LIB_DIR="${ARTIFACT_DIR}/deps"
mkdir -p "${NATIVE_LIB_DIR}"

if [ -n "${JNI_LIB}" ]; then
    while IFS= read -r line; do
        dep_path=$(echo "$line" | sed -n 's/.*=> \(.*\) (.*)/\1/p')
        if [ -n "$dep_path" ] && [ -f "$dep_path" ]; then
            libname=$(basename "$dep_path")
            # Only bundle non-system libraries
            case "$libname" in
                linux-vdso.so.*|libc.so.*|libm.so.*|libstdc++.so.*|libgcc_s.so.*|libpthread.so.*|libdl.so.*|librt.so.*|ld-linux-*.so.*)
                    echo "  Skipping (system base): $dep_path"
                    ;;
                *)
                    echo "  Bundling: $dep_path"
                    cp "$dep_path" "${NATIVE_LIB_DIR}/" 2>/dev/null || true
                    # Follow symlinks
                    real_path=$(readlink -f "$dep_path")
                    if [ -f "$real_path" ] && [ "$real_path" != "$dep_path" ]; then
                        cp "$real_path" "${NATIVE_LIB_DIR}/" 2>/dev/null || true
                    fi
                    ;;
            esac
        fi
    done < <(ldd "${JNI_LIB}" 2>/dev/null)
fi

# Copy JNI header files
mkdir -p "${ARTIFACT_DIR}/include"
HEADER_DIR="${BUILD_DIR}/modules/java/jni"
if [ -d "${HEADER_DIR}" ]; then
    cp -r "${HEADER_DIR}"/*.h "${ARTIFACT_DIR}/include/" 2>/dev/null || true
fi

if [ -d "${BUILD_DIR}/opencv_jni_source" ]; then
    cp "${BUILD_DIR}/opencv_jni_source/"*.h "${ARTIFACT_DIR}/include/" 2>/dev/null || true
fi

echo "OpenCV artifacts collected in ${ARTIFACT_DIR}"
ls -la "${ARTIFACT_DIR}/"
echo "Transitive deps collected:"
ls -la "${NATIVE_LIB_DIR}/"
