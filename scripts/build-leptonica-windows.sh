#!/bin/bash
# ============================================================
# Build Leptonica on Windows (MSYS2/MinGW64)
# ============================================================
set -euo pipefail

LEPTONICA_VERSION="${LEPTONICA_VERSION:-1.85.0}"
INSTALL_PREFIX="/mingw64"
SOURCE_DIR="$HOME/leptonica-source"
BUILD_DIR="$HOME/leptonica-build"

echo "========================================"
echo "Building Leptonica ${LEPTONICA_VERSION} on Windows (MSYS2)"
echo "========================================"

# Download source if not cached
if [ ! -d "${SOURCE_DIR}/leptonica-${LEPTONICA_VERSION}" ]; then
    mkdir -p "${SOURCE_DIR}"
    cd "${SOURCE_DIR}"
    curl -L "https://github.com/DanBloomberg/leptonica/releases/download/${LEPTONICA_VERSION}/leptonica-${LEPTONICA_VERSION}.tar.gz" -o leptonica.tar.gz
    tar -xzf leptonica.tar.gz
    rm leptonica.tar.gz
fi

# Build
mkdir -p "${BUILD_DIR}"
cd "${BUILD_DIR}"
cmake "${SOURCE_DIR}/leptonica-${LEPTONICA_VERSION}" \
    -G "MinGW Makefiles" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="${INSTALL_PREFIX}" \
    -DBUILD_SHARED_LIBS=ON \
    -DBUILD_PROG=OFF \
    -DENABLE_ZLIB=ON \
    -DENABLE_PNG=ON \
    -DENABLE_JPEG=ON \
    -DENABLE_TIFF=ON \
    -DENABLE_WEBP=ON

cmake --build . -j$(nproc)
cmake --install .

echo "Leptonica ${LEPTONICA_VERSION} built and installed successfully!"
