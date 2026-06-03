#!/bin/bash
# ============================================================
# Package Linux artifacts into Java-importable structure
# FIX: Merge ALL dependencies from OpenCV and Tesseract into a single
#      flat directory with proper symlinks and RPATH=$ORIGIN
# FIX: Ensure soname symlinks are correct (libleptonica.so.6 → liblept.so)
# FIX: Use patchelf to set RPATH=$ORIGIN on all bundled .so files
# ============================================================
set -euo pipefail

ARTIFACT_DIR="${GITHUB_WORKSPACE}/artifacts/linux"

echo "========================================"
echo "Packaging Linux artifacts for Java"
echo "========================================"

# Install patchelf if not available (for setting RPATH)
if ! command -v patchelf &>/dev/null; then
    sudo apt-get install -y patchelf 2>/dev/null || true
fi

# Create final structure
mkdir -p "${ARTIFACT_DIR}/final/native/linux/x86_64"
mkdir -p "${ARTIFACT_DIR}/final/jars"
mkdir -p "${ARTIFACT_DIR}/final/headers"

# Target directory for all native libraries (flat, all in one dir)
NATIVE_DIR="${ARTIFACT_DIR}/final/native/linux/x86_64"

# Copy OpenCV artifacts
echo "--- OpenCV ---"
if [ -d "${ARTIFACT_DIR}/opencv" ]; then
    ls -la "${ARTIFACT_DIR}/opencv/"
    cp "${ARTIFACT_DIR}/opencv/"*.jar "${ARTIFACT_DIR}/final/jars/" 2>/dev/null || true
    cp "${ARTIFACT_DIR}/opencv/"*.so "${NATIVE_DIR}/" 2>/dev/null || true
    cp "${ARTIFACT_DIR}/opencv/include/"*.h "${ARTIFACT_DIR}/final/headers/" 2>/dev/null || true
    # FIX: Copy transitive deps collected by build-opencv-linux.sh
    if [ -d "${ARTIFACT_DIR}/opencv/deps" ]; then
        echo "Copying OpenCV transitive dependencies..."
        cp "${ARTIFACT_DIR}/opencv/deps/"*.so* "${NATIVE_DIR}/" 2>/dev/null || true
    fi
fi

# Copy Tesseract artifacts
echo "--- Tesseract ---"
if [ -d "${ARTIFACT_DIR}/tesseract" ]; then
    ls -la "${ARTIFACT_DIR}/tesseract/lib/"
    # Copy ALL .so files including versioned ones
    cp "${ARTIFACT_DIR}/tesseract/lib/"*.so* "${NATIVE_DIR}/" 2>/dev/null || true
    cp "${ARTIFACT_DIR}/tesseract/lib/"*.a "${NATIVE_DIR}/" 2>/dev/null || true
    cp "${ARTIFACT_DIR}/tesseract/include/"*.h "${ARTIFACT_DIR}/final/headers/" 2>/dev/null || true
    cp -r "${ARTIFACT_DIR}/tesseract/include/tesseract" "${ARTIFACT_DIR}/final/headers/" 2>/dev/null || true
fi

# FIX: Ensure liblept.so symlink exists (Leptonica installs as libleptonica.so.6)
# Our Java loader looks for liblept.so, but the soname is libleptonica.so.6
echo "--- Fixing library symlinks ---"
cd "${NATIVE_DIR}"

# Create liblept.so → libleptonica.so.6 if it doesn't exist
if [ ! -e "liblept.so" ] && [ -e "libleptonica.so.6" ]; then
    ln -sf libleptonica.so.6 liblept.so
    echo "Created symlink: liblept.so → libleptonica.so.6"
fi

# Create liblept.so.5 for backward compatibility
if [ ! -e "liblept.so.5" ] && [ -e "libleptonica.so.6" ]; then
    ln -sf libleptonica.so.6 liblept.so.5
    echo "Created symlink: liblept.so.5 → libleptonica.so.6"
fi

# Create libtesseract.so.5 symlink if it doesn't exist
if [ ! -e "libtesseract.so.5" ] && [ -e "libtesseract.so.5.5" ]; then
    ln -sf libtesseract.so.5.5 libtesseract.so.5
    echo "Created symlink: libtesseract.so.5 → libtesseract.so.5.5"
fi

# Create libtesseract.so → libtesseract.so.5 for Java System.loadLibrary
if [ ! -e "libtesseract.so" ] && [ -e "libtesseract.so.5" ]; then
    ln -sf libtesseract.so.5 libtesseract.so
    echo "Created symlink: libtesseract.so → libtesseract.so.5"
fi

# FIX: Set RPATH=$ORIGIN on ALL .so files so they can find each other
# This is the critical fix - without RPATH, System.load() won't find
# bundled dependencies unless LD_LIBRARY_PATH is set.
echo "--- Setting RPATH=\$ORIGIN on all .so files ---"
PATCHED_COUNT=0
for sofile in *.so*; do
    if [ -f "$sofile" ] && [ ! -L "$sofile" ]; then
        if command -v patchelf &>/dev/null; then
            patchelf --set-rpath '$ORIGIN' "$sofile" 2>/dev/null && PATCHED_COUNT=$((PATCHED_COUNT + 1)) || true
        fi
    fi
done
echo "Patched RPATH on ${PATCHED_COUNT} .so files"

# Verify: check that all dependencies are resolvable within the bundle
echo "--- Verifying all dependencies are resolvable ---"
FAILED=0
for sofile in *.so*; do
    if [ -f "$sofile" ] && [ ! -L "$sofile" ]; then
        NOT_FOUND=$(LD_LIBRARY_PATH="${NATIVE_DIR}" ldd "$sofile" 2>/dev/null | grep "not found" || true)
        if [ -n "$NOT_FOUND" ]; then
            echo "UNRESOLVED in $sofile:"
            echo "$NOT_FOUND"
            FAILED=$((FAILED + 1))
        fi
    fi
done

if [ ${FAILED} -gt 0 ]; then
    echo ""
    echo "WARNING: ${FAILED} libraries have unresolvable dependencies!"
    echo "Users will need to install additional system packages."
else
    echo "OK: All dependencies resolvable within the bundle."
fi

# Create OS-specific manifest
cat > "${NATIVE_DIR}/MANIFEST.txt" << EOF
platform=linux
arch=x86_64
opencv_version=${OPENCV_VERSION:-4.10.0}
tesseract_version=${TESSERACT_VERSION:-5.5.0}
files=$(ls "${NATIVE_DIR}/" | grep -v MANIFEST | tr '\n' ',')
EOF

# Replace artifact dirs with final
rm -rf "${ARTIFACT_DIR}/opencv" "${ARTIFACT_DIR}/tesseract"
mv "${ARTIFACT_DIR}/final/"* "${ARTIFACT_DIR}/"
rm -rf "${ARTIFACT_DIR}/final"

# Flatten for GitHub Actions upload artifact
mkdir -p "${ARTIFACT_DIR}/opencv-upload"
mkdir -p "${ARTIFACT_DIR}/tesseract-upload"

cp "${ARTIFACT_DIR}/jars/"*.jar "${ARTIFACT_DIR}/opencv-upload/" 2>/dev/null || true
cp "${ARTIFACT_DIR}/native/linux/x86_64/"libopencv_java*.so "${ARTIFACT_DIR}/opencv-upload/" 2>/dev/null || true
cp "${ARTIFACT_DIR}/headers/"*.h "${ARTIFACT_DIR}/opencv-upload/" 2>/dev/null || true

# Copy ALL .so files including versioned ones for Tesseract upload
cp "${ARTIFACT_DIR}/native/linux/x86_64/"libtesseract*.so* "${ARTIFACT_DIR}/tesseract-upload/" 2>/dev/null || true
cp "${ARTIFACT_DIR}/native/linux/x86_64/"liblept*.so* "${ARTIFACT_DIR}/tesseract-upload/" 2>/dev/null || true
cp "${ARTIFACT_DIR}/native/linux/x86_64/"libleptonica*.so* "${ARTIFACT_DIR}/tesseract-upload/" 2>/dev/null || true
# FIX: Also copy image format dependencies
cp "${ARTIFACT_DIR}/native/linux/x86_64/"libjpeg*.so* "${ARTIFACT_DIR}/tesseract-upload/" 2>/dev/null || true
cp "${ARTIFACT_DIR}/native/linux/x86_64/"libpng*.so* "${ARTIFACT_DIR}/tesseract-upload/" 2>/dev/null || true
cp "${ARTIFACT_DIR}/native/linux/x86_64/"libtiff*.so* "${ARTIFACT_DIR}/tesseract-upload/" 2>/dev/null || true
cp "${ARTIFACT_DIR}/native/linux/x86_64/"libwebp*.so* "${ARTIFACT_DIR}/tesseract-upload/" 2>/dev/null || true
cp "${ARTIFACT_DIR}/native/linux/x86_64/"libopenjp2*.so* "${ARTIFACT_DIR}/tesseract-upload/" 2>/dev/null || true
cp "${ARTIFACT_DIR}/native/linux/x86_64/"libgif*.so* "${ARTIFACT_DIR}/tesseract-upload/" 2>/dev/null || true
cp -r "${ARTIFACT_DIR}/headers/tesseract" "${ARTIFACT_DIR}/tesseract-upload/" 2>/dev/null || true

echo "Packaging complete!"
echo "=== OpenCV upload contents ==="
ls -la "${ARTIFACT_DIR}/opencv-upload/"
echo "=== Tesseract upload contents ==="
ls -la "${ARTIFACT_DIR}/tesseract-upload/"

# Move upload dirs to replace artifact dirs
rm -rf "${ARTIFACT_DIR}/opencv"
mv "${ARTIFACT_DIR}/opencv-upload" "${ARTIFACT_DIR}/opencv"
rm -rf "${ARTIFACT_DIR}/tesseract"
mv "${ARTIFACT_DIR}/tesseract-upload" "${ARTIFACT_DIR}/tesseract"
