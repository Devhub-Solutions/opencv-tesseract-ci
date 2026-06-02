#!/bin/bash
# ============================================================
# Build Leptonica on Linux (Ubuntu)
# ============================================================
set -euo pipefail

LEPTONICA_VERSION="${LEPTONICA_VERSION:-1.85.0}"
INSTALL_PREFIX="/usr/local"
SOURCE_DIR="$HOME/leptonica-source"
BUILD_DIR="$HOME/leptonica-build"

echo "========================================"
echo "Building Leptonica ${LEPTONICA_VERSION} on Linux"
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
sudo cmake --install .

# Update library cache
sudo ldconfig


# Leptonica CMake installs a configuration-specific pkg-config file (for
# example, lept_Release.pc). Tesseract and our validation use the canonical
# package name, so provide lept.pc when upstream did not install it.
PKG_CONFIG_DIR="${INSTALL_PREFIX}/lib/pkgconfig"
export PKG_CONFIG_PATH="${PKG_CONFIG_DIR}:${PKG_CONFIG_PATH:-}"
if [ ! -f "${PKG_CONFIG_DIR}/lept.pc" ]; then
    LEPT_PC=$(find "${PKG_CONFIG_DIR}" -maxdepth 1 -name 'lept*.pc' | head -1 || true)
    if [ -n "${LEPT_PC}" ]; then
        sudo cp "${LEPT_PC}" "${PKG_CONFIG_DIR}/lept.pc"
    fi
fi

echo "Leptonica ${LEPTONICA_VERSION} built and installed successfully!"
pkg-config --modversion lept
