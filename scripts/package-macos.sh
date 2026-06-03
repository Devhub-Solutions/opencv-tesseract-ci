#!/bin/bash
# ============================================================
# Package macOS artifacts into Java-importable structure
# ============================================================
set -euo pipefail

ARTIFACT_DIR="${GITHUB_WORKSPACE}/artifacts/macos"

echo "========================================"
echo "Packaging macOS artifacts for Java"
echo "========================================"

# Create upload-ready directories
mkdir -p "${ARTIFACT_DIR}/opencv-upload"
mkdir -p "${ARTIFACT_DIR}/tesseract-upload"

# Copy OpenCV artifacts
echo "--- OpenCV ---"
if [ -d "${ARTIFACT_DIR}/opencv" ]; then
    ls -la "${ARTIFACT_DIR}/opencv/"
    cp "${ARTIFACT_DIR}/opencv/"*.jar "${ARTIFACT_DIR}/opencv-upload/" 2>/dev/null || true
    cp "${ARTIFACT_DIR}/opencv/"*.dylib "${ARTIFACT_DIR}/opencv-upload/" 2>/dev/null || true
    cp "${ARTIFACT_DIR}/opencv/include/"*.h "${ARTIFACT_DIR}/opencv-upload/" 2>/dev/null || true
fi

# Copy Tesseract artifacts
echo "--- Tesseract ---"
if [ -d "${ARTIFACT_DIR}/tesseract" ]; then
    ls -laR "${ARTIFACT_DIR}/tesseract/"
    # FIX: Copy ALL dylib files including Leptonica (liblept*.dylib)
    cp "${ARTIFACT_DIR}/tesseract/lib/"*.dylib "${ARTIFACT_DIR}/tesseract-upload/" 2>/dev/null || true
    cp -r "${ARTIFACT_DIR}/tesseract/include/tesseract" "${ARTIFACT_DIR}/tesseract-upload/" 2>/dev/null || true
fi

# Replace dirs
rm -rf "${ARTIFACT_DIR}/opencv"
mv "${ARTIFACT_DIR}/opencv-upload" "${ARTIFACT_DIR}/opencv"
rm -rf "${ARTIFACT_DIR}/tesseract"
mv "${ARTIFACT_DIR}/tesseract-upload" "${ARTIFACT_DIR}/tesseract"

echo "Packaging complete!"
echo "=== OpenCV ==="
ls -la "${ARTIFACT_DIR}/opencv/"
echo "=== Tesseract ==="
ls -la "${ARTIFACT_DIR}/tesseract/"