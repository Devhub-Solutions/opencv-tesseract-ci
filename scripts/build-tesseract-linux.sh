#!/bin/bash
# ============================================================
# Build Tesseract OCR on Linux (Ubuntu)
# FIX: Disable libarchive/libcurl to reduce transitive deps
# FIX: Set RPATH=$ORIGIN for portability
# FIX: Collect ALL transitive dependencies for bundling
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

# FIX: Added -DCMAKE_DISABLE_FIND_PACKAGE_libarchive=ON and -DCMAKE_DISABLE_FIND_PACKAGE_CURL=ON
# to prevent Tesseract 5.5+ from linking libarchive and libcurl which add
# dozens of transitive dependencies that are not bundled in the release.
# These are only needed for training tools which we already disable.
cmake "${SOURCE_DIR}/tesseract-${TESSERACT_VERSION}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="${INSTALL_PREFIX}" \
    -DBUILD_SHARED_LIBS=ON \
    -DBUILD_TRAINING_TOOLS=OFF \
    -DENABLE_LTO=ON \
    -DLeptonica_DIR="${INSTALL_PREFIX}/lib/cmake/leptonica" \
    -DCMAKE_DISABLE_FIND_PACKAGE_libarchive=ON \
    -DCMAKE_DISABLE_FIND_PACKAGE_CURL=ON \
    -DCMAKE_INSTALL_RPATH="\$ORIGIN" \
    -DCMAKE_BUILD_WITH_INSTALL_RPATH=ON

cmake --build . -j$(nproc)
sudo cmake --install .

sudo ldconfig

echo "Tesseract ${TESSERACT_VERSION} built and installed successfully!"
tesseract --version 2>&1 || true

# Verify: check that libtesseract does NOT depend on libarchive/libcurl
echo "--- Verifying Tesseract has no libarchive/libcurl dependency ---"
if ldd "${INSTALL_PREFIX}/lib/libtesseract.so" 2>/dev/null | grep -qE "libarchive|libcurl"; then
    echo "WARNING: Tesseract still depends on libarchive/libcurl!"
    ldd "${INSTALL_PREFIX}/lib/libtesseract.so" 2>/dev/null | grep -E "libarchive|libcurl"
else
    echo "OK: Tesseract has no libarchive/libcurl dependency."
fi

# Also build static version for JNI bundling (OpenCV fat JNI can use this)
mkdir -p "${BUILD_DIR}-static"
cd "${BUILD_DIR}-static"
cmake "${SOURCE_DIR}/tesseract-${TESSERACT_VERSION}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="${INSTALL_PREFIX}" \
    -DBUILD_SHARED_LIBS=OFF \
    -DBUILD_TRAINING_TOOLS=OFF \
    -DENABLE_LTO=ON \
    -DLeptonica_DIR="${INSTALL_PREFIX}/lib/cmake/leptonica" \
    -DCMAKE_DISABLE_FIND_PACKAGE_libarchive=ON \
    -DCMAKE_DISABLE_FIND_PACKAGE_CURL=ON

cmake --build . -j$(nproc)

# Collect artifacts
mkdir -p "${ARTIFACT_DIR}/lib" "${ARTIFACT_DIR}/include"
cp "${INSTALL_PREFIX}/lib/libtesseract.so"* "${ARTIFACT_DIR}/lib/" 2>/dev/null || true
cp "${INSTALL_PREFIX}/lib/libtesseract.a" "${ARTIFACT_DIR}/lib/" 2>/dev/null || true
cp -r "${INSTALL_PREFIX}/include/tesseract" "${ARTIFACT_DIR}/include/" 2>/dev/null || true

# FIX: Collect ALL transitive shared library dependencies for bundling.
# Use ldd to find every .so that libtesseract and libleptonica need at runtime.
echo "--- Collecting transitive dependencies for bundling ---"
NATIVE_LIB_DIR="${ARTIFACT_DIR}/lib"

# List of our primary libraries to analyze
PRIMARY_LIBS=(
    "${INSTALL_PREFIX}/lib/libtesseract.so"
    "${INSTALL_PREFIX}/lib/libleptonica.so"
)

# Collect dependencies using ldd
for lib in "${PRIMARY_LIBS[@]}"; do
    if [ -f "$lib" ]; then
        echo "Analyzing dependencies of: $lib"
        while IFS= read -r line; do
            # Extract the resolved path from ldd output (after "=>")
            dep_path=$(echo "$line" | sed -n 's/.*=> \(.*\) (.*)/\1/p')
            if [ -n "$dep_path" ] && [ -f "$dep_path" ]; then
                # Only bundle libraries from /usr/local or /usr/lib, skip linux-vdso and ld-linux
                case "$dep_path" in
                    /usr/local/*)
                        echo "  Bundling (local): $dep_path"
                        cp "$dep_path" "${NATIVE_LIB_DIR}/" 2>/dev/null || true
                        # Follow symlinks to get the real file
                        real_path=$(readlink -f "$dep_path")
                        if [ -f "$real_path" ] && [ "$real_path" != "$dep_path" ]; then
                            cp "$real_path" "${NATIVE_LIB_DIR}/" 2>/dev/null || true
                        fi
                        ;;
                    */x86_64-linux-gnu/*)
                        # Skip system libraries like libc, libm, libstdc++, libgcc_s, libz, libpthread etc.
                        # These are guaranteed to exist on any Debian/Ubuntu system
                        libname=$(basename "$dep_path")
                        case "$libname" in
                            libc.so.*|libm.so.*|libstdc++.so.*|libgcc_s.so.*|libpthread.so.*|libdl.so.*|librt.so.*|linux-vdso.so.*|ld-linux-*.so.*)
                                echo "  Skipping (system base): $dep_path"
                                ;;
                            libz.so.*|libpng16.so.*|libjpeg.so.*|libwebp.so.*|libtiff.so.*|libopenjp2.so.*|libgif.so.*|libwebpmux.so.*)
                                echo "  Bundling (image format): $dep_path"
                                cp "$dep_path" "${NATIVE_LIB_DIR}/" 2>/dev/null || true
                                real_path=$(readlink -f "$dep_path")
                                if [ -f "$real_path" ] && [ "$real_path" != "$dep_path" ]; then
                                    cp "$real_path" "${NATIVE_LIB_DIR}/" 2>/dev/null || true
                                fi
                                ;;
                            *)
                                echo "  Skipping (system): $dep_path"
                                ;;
                        esac
                        ;;
                esac
            fi
        done < <(ldd "$lib" 2>/dev/null)
    fi
done

echo "Tesseract artifacts collected in ${ARTIFACT_DIR}"
ls -la "${ARTIFACT_DIR}/lib/"
