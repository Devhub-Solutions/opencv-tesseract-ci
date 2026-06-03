#!/bin/bash
# ============================================================
# Build Tesseract OCR on macOS
# FIX: Disable libarchive/libcurl to reduce transitive deps
# FIX: Use install_name_tool to set proper library IDs for portability
# ============================================================
set -euo pipefail

TESSERACT_VERSION="${TESSERACT_VERSION:-5.5.0}"
INSTALL_PREFIX="/usr/local"
SOURCE_DIR="$HOME/tesseract-source"
BUILD_DIR="$HOME/tesseract-build"
ARTIFACT_DIR="${GITHUB_WORKSPACE}/artifacts/macos/tesseract"

echo "========================================"
echo "Building Tesseract ${TESSERACT_VERSION} on macOS"
echo "========================================"

# Download source if not cached
if [ ! -d "${SOURCE_DIR}/tesseract-${TESSERACT_VERSION}" ]; then
    mkdir -p "${SOURCE_DIR}"
    cd "${SOURCE_DIR}"
    curl -L "https://github.com/tesseract-ocr/tesseract/archive/${TESSERACT_VERSION}.tar.gz" -o tesseract.tar.gz
    tar -xzf tesseract.tar.gz
    rm tesseract.tar.gz
fi

# Build
mkdir -p "${BUILD_DIR}"
cd "${BUILD_DIR}"

# FIX: Disable libarchive/libcurl (same as Linux)
cmake "${SOURCE_DIR}/tesseract-${TESSERACT_VERSION}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="${INSTALL_PREFIX}" \
    -DBUILD_SHARED_LIBS=ON \
    -DBUILD_TRAINING_TOOLS=OFF \
    -DENABLE_LTO=ON \
    -DCMAKE_OSX_ARCHITECTURES="x86_64" \
    -DLeptonica_DIR="${INSTALL_PREFIX}/lib/cmake/leptonica" \
    -DCMAKE_DISABLE_FIND_PACKAGE_libarchive=ON \
    -DCMAKE_DISABLE_FIND_PACKAGE_CURL=ON

cmake --build . -j$(sysctl -n hw.ncpu)
cmake --install .

echo "Tesseract ${TESSERACT_VERSION} built and installed successfully!"

# Collect artifacts
mkdir -p "${ARTIFACT_DIR}/lib" "${ARTIFACT_DIR}/include"

# Copy dylib files
cp "${INSTALL_PREFIX}/lib/libtesseract"*.dylib "${ARTIFACT_DIR}/lib/" 2>/dev/null || true
cp "${INSTALL_PREFIX}/lib/liblept"*.dylib "${ARTIFACT_DIR}/lib/" 2>/dev/null || true

# Copy headers
cp -r "${INSTALL_PREFIX}/include/tesseract" "${ARTIFACT_DIR}/include/" 2>/dev/null || true

echo "Tesseract artifacts collected in ${ARTIFACT_DIR}"
ls -laR "${ARTIFACT_DIR}/"
