#!/bin/bash
# ============================================================
# Package Linux artifacts into Java-importable structure
# ============================================================
set -euo pipefail

ARTIFACT_DIR="${GITHUB_WORKSPACE}/artifacts/linux"

echo "========================================"
echo "Packaging Linux artifacts for Java"
echo "========================================"

# Create final structure
mkdir -p "${ARTIFACT_DIR}/final/native/linux/x86_64"
mkdir -p "${ARTIFACT_DIR}/final/jars"
mkdir -p "${ARTIFACT_DIR}/final/headers"

# Copy OpenCV artifacts
echo "--- OpenCV ---"
if [ -d "${ARTIFACT_DIR}/opencv" ]; then
    ls -la "${ARTIFACT_DIR}/opencv/"
    cp "${ARTIFACT_DIR}/opencv/"*.jar "${ARTIFACT_DIR}/final/jars/" 2>/dev/null || true
    cp "${ARTIFACT_DIR}/opencv/"*.so "${ARTIFACT_DIR}/final/native/linux/x86_64/" 2>/dev/null || true
    cp "${ARTIFACT_DIR}/opencv/include/"*.h "${ARTIFACT_DIR}/final/headers/" 2>/dev/null || true
fi

# Copy Tesseract artifacts
echo "--- Tesseract ---"
if [ -d "${ARTIFACT_DIR}/tesseract" ]; then
    ls -la "${ARTIFACT_DIR}/tesseract/lib/"
    cp "${ARTIFACT_DIR}/tesseract/lib/"*.so* "${ARTIFACT_DIR}/final/native/linux/x86_64/" 2>/dev/null || true
    cp "${ARTIFACT_DIR}/tesseract/include/"*.h "${ARTIFACT_DIR}/final/headers/" 2>/dev/null || true
    cp -r "${ARTIFACT_DIR}/tesseract/include/tesseract" "${ARTIFACT_DIR}/final/headers/" 2>/dev/null || true
fi

# Create OS-specific manifest
cat > "${ARTIFACT_DIR}/final/native/linux/x86_64/MANIFEST.txt" << EOF
platform=linux
arch=x86_64
opencv_version=${OPENCV_VERSION:-4.10.0}
tesseract_version=${TESSERACT_VERSION:-5.5.0}
files=$(ls "${ARTIFACT_DIR}/final/native/linux/x86_64/" | grep -v MANIFEST | tr '\n' ',')
EOF

# Replace artifact dirs with final
rm -rf "${ARTIFACT_DIR}/opencv" "${ARTIFACT_DIR}/tesseract"
mv "${ARTIFACT_DIR}/final/"* "${ARTIFACT_DIR}/"
rm -rf "${ARTIFACT_DIR}/final"

# Flatten for GitHub Actions upload artifact
# (artifact action needs files directly in the upload path)
mkdir -p "${ARTIFACT_DIR}/opencv-upload"
mkdir -p "${ARTIFACT_DIR}/tesseract-upload"

cp "${ARTIFACT_DIR}/jars/"*.jar "${ARTIFACT_DIR}/opencv-upload/" 2>/dev/null || true
cp "${ARTIFACT_DIR}/native/linux/x86_64/"libopencv_java*.so "${ARTIFACT_DIR}/opencv-upload/" 2>/dev/null || true
cp "${ARTIFACT_DIR}/headers/"*.h "${ARTIFACT_DIR}/opencv-upload/" 2>/dev/null || true

cp "${ARTIFACT_DIR}/native/linux/x86_64/"libtesseract*.so* "${ARTIFACT_DIR}/tesseract-upload/" 2>/dev/null || true
cp "${ARTIFACT_DIR}/native/linux/x86_64/"liblept*.so* "${ARTIFACT_DIR}/tesseract-upload/" 2>/dev/null || true
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
