#!/bin/bash
# ============================================================
# Build Tesseract OCR on Linux (Ubuntu)
# ============================================================
set -euo pipefail

TESSERACT_VERSION="${TESSERACT_VERSION:-5.5.0}"
INSTALL_PREFIX="/usr/local"
SOURCE_DIR="$HOME/tesseract-source"
BUILD_DIR="$HOME/tesseract-build"
ARTIFACT_DIR="${GITHUB_WORKSPACE}/artifacts/linux/tesseract"

echo "========================================"
echo "Building Tesseract ${TESSERACT_VERSION} on Linux"
echo "========================================"

# Download source if not cached
if [ ! -d "${SOURCE_DIR}/tesseract-${TESSERACT_VERSION}" ]; then
    mkdir -p "${SOURCE_DIR}"
    cd "${SOURCE_DIR}"
    curl -L "https://github.com/tesseract-ocr/tesseract/archive/${TESSERACT_VERSION}.tar.gz" -o tesseract.tar.gz
    tar -xzf tesseract.tar.gz
    rm tesseract.tar.gz
fi

# Build with shared libraries
mkdir -p "${BUILD_DIR}"
cd "${BUILD_DIR}"
cmake "${SOURCE_DIR}/tesseract-${TESSERACT_VERSION}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="${INSTALL_PREFIX}" \
    -DBUILD_SHARED_LIBS=ON \
    -DBUILD_TRAINING_TOOLS=OFF \
    -DENABLE_LTO=ON \
    -DLeptonica_DIR="${INSTALL_PREFIX}/lib/cmake/leptonica"

cmake --build . -j$(nproc)
sudo cmake --install .

sudo ldconfig

echo "Tesseract ${TESSERACT_VERSION} built and installed successfully!"
tesseract --version 2>&1 || true

# Also build static version for JNI bundling
mkdir -p "${BUILD_DIR}-static"
cd "${BUILD_DIR}-static"
cmake "${SOURCE_DIR}/tesseract-${TESSERACT_VERSION}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="${INSTALL_PREFIX}" \
    -DBUILD_SHARED_LIBS=OFF \
    -DBUILD_TRAINING_TOOLS=OFF \
    -DENABLE_LTO=ON \
    -DLeptonica_DIR="${INSTALL_PREFIX}/lib/cmake/leptonica"

cmake --build . -j$(nproc)

# Collect artifacts
mkdir -p "${ARTIFACT_DIR}/lib" "${ARTIFACT_DIR}/include"
cp "${INSTALL_PREFIX}/lib/libtesseract.so"* "${ARTIFACT_DIR}/lib/" 2>/dev/null || true
cp "${INSTALL_PREFIX}/lib/libtesseract.a" "${ARTIFACT_DIR}/lib/" 2>/dev/null || true
cp -r "${INSTALL_PREFIX}/include/tesseract" "${ARTIFACT_DIR}/include/" 2>/dev/null || true

# Copy dependent shared libs needed at runtime
for lib in libtesseract liblept libpng libjpeg libtiff libwebp lzma z; do
    for f in /usr/local/lib/lib${lib}.so* /usr/lib/x86_64-linux-gnu/lib${lib}.so*; do
        if [ -f "$f" ]; then
            cp "$f" "${ARTIFACT_DIR}/lib/" 2>/dev/null || true
        fi
    done
done

echo "Tesseract artifacts collected in ${ARTIFACT_DIR}"
ls -la "${ARTIFACT_DIR}/lib/"
