#!/bin/bash
# ============================================================
# Build Tesseract OCR on Windows (MSYS2/MinGW64)
# FIX: Disable libarchive/libcurl to reduce transitive deps
# FIX: Copy ALL runtime DLL dependencies including image format libs
# ============================================================
set -euo pipefail

TESSERACT_VERSION="${TESSERACT_VERSION:-5.5.0}"
INSTALL_PREFIX="/mingw64"
SOURCE_DIR="$HOME/tesseract-source"
BUILD_DIR="$HOME/tesseract-build"
ARTIFACT_DIR="$(cygpath -u "${GITHUB_WORKSPACE}")/artifacts/windows/tesseract"

echo "========================================"
echo "Building Tesseract ${TESSERACT_VERSION} on Windows (MSYS2)"
echo "========================================"

if command -v mingw32-make >/dev/null 2>&1; then
    CMAKE_GENERATOR_ARGS=(-G "MinGW Makefiles" -DCMAKE_MAKE_PROGRAM="$(command -v mingw32-make)")
elif command -v make >/dev/null 2>&1; then
    CMAKE_GENERATOR_ARGS=(-G "Unix Makefiles" -DCMAKE_MAKE_PROGRAM="$(command -v make)")
else
    echo "ERROR: neither mingw32-make nor make was found on PATH" >&2
    exit 1
fi

# Download source if not cached
if [ ! -d "${SOURCE_DIR}/tesseract-${TESSERACT_VERSION}" ]; then
    mkdir -p "${SOURCE_DIR}"
    cd "${SOURCE_DIR}"
    curl -L "https://github.com/tesseract-ocr/tesseract/archive/${TESSERACT_VERSION}.tar.gz" -o tesseract.tar.gz
    tar -xzf tesseract.tar.gz
    rm tesseract.tar.gz
fi

# Build shared libraries
# FIX: Added -DCMAKE_DISABLE_FIND_PACKAGE_libarchive=ON and -DCMAKE_DISABLE_FIND_PACKAGE_CURL=ON
mkdir -p "${BUILD_DIR}"
cd "${BUILD_DIR}"
cmake "${SOURCE_DIR}/tesseract-${TESSERACT_VERSION}" \
    "${CMAKE_GENERATOR_ARGS[@]}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="${INSTALL_PREFIX}" \
    -DCMAKE_PREFIX_PATH="${INSTALL_PREFIX}" \
    -DSW_BUILD=OFF \
    -DBUILD_SHARED_LIBS=ON \
    -DBUILD_TRAINING_TOOLS=OFF \
    -DENABLE_LTO=ON \
    -DLeptonica_DIR="${INSTALL_PREFIX}/lib/cmake/leptonica" \
    -DCMAKE_DISABLE_FIND_PACKAGE_libarchive=ON \
    -DCMAKE_DISABLE_FIND_PACKAGE_CURL=ON

cmake --build . -j$(nproc)
cmake --install .

# Collect artifacts
mkdir -p "${ARTIFACT_DIR}/bin" "${ARTIFACT_DIR}/lib" "${ARTIFACT_DIR}/include"

# Copy DLL files
cp "${INSTALL_PREFIX}/bin/libtesseract"*.dll "${ARTIFACT_DIR}/bin/" 2>/dev/null || true
cp "${INSTALL_PREFIX}/bin/liblept"*.dll "${ARTIFACT_DIR}/bin/" 2>/dev/null || true

# Copy import libraries
cp "${INSTALL_PREFIX}/lib/libtesseract.dll.a" "${ARTIFACT_DIR}/lib/" 2>/dev/null || true
cp "${INSTALL_PREFIX}/lib/liblept.dll.a" "${ARTIFACT_DIR}/lib/" 2>/dev/null || true

# Copy headers
cp -r "${INSTALL_PREFIX}/include/tesseract" "${ARTIFACT_DIR}/include/" 2>/dev/null || true

# Runtime DLL dependencies (MinGW runtime)
for dll in libgcc_s_seh-1.dll libstdc++-6.dll libwinpthread-1.dll; do
    cp "${INSTALL_PREFIX}/bin/${dll}" "${ARTIFACT_DIR}/bin/" 2>/dev/null || true
done

# FIX: Copy ALL image format DLLs - use wildcard patterns for robustness
# The previous version used hardcoded names like "libjpeg62.dll" which may
# not match the actual MSYS2 package version (libjpeg-8.dll, libjpeg-62.dll, etc.)
for dll_pattern in "libpng*.dll" "libjpeg*.dll" "libtiff*.dll" "libwebp*.dll" "zlib1.dll" "liblzma*.dll"; do
    for f in "${INSTALL_PREFIX}/bin/"${dll_pattern}; do
        if [ -f "$f" ]; then
            cp "$f" "${ARTIFACT_DIR}/bin/"
        fi
    done
done

echo "Tesseract artifacts collected in ${ARTIFACT_DIR}"
ls -laR "${ARTIFACT_DIR}/"
