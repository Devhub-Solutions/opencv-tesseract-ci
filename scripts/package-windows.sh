#!/bin/bash
# ============================================================
# Package Windows artifacts into Java-importable structure
# FIX: Copy ALL DLLs from deps/ directory for a self-contained bundle
# ============================================================
set -euo pipefail

WORKSPACE="$(cygpath -u "${GITHUB_WORKSPACE}")"
ARTIFACT_DIR="${WORKSPACE}/artifacts/windows"

echo "========================================"
echo "Packaging Windows artifacts for Java"
echo "========================================"

# Create upload-ready directories (flatten structure for artifact upload)
mkdir -p "${ARTIFACT_DIR}/opencv-upload"
mkdir -p "${ARTIFACT_DIR}/tesseract-upload"

# Copy OpenCV artifacts
echo "--- OpenCV ---"
if [ -d "${ARTIFACT_DIR}/opencv" ]; then
    ls -la "${ARTIFACT_DIR}/opencv/"
    cp "${ARTIFACT_DIR}/opencv/"*.jar "${ARTIFACT_DIR}/opencv-upload/" 2>/dev/null || true
    cp "${ARTIFACT_DIR}/opencv/"*.dll "${ARTIFACT_DIR}/opencv-upload/" 2>/dev/null || true
    cp "${ARTIFACT_DIR}/opencv/include/"*.h "${ARTIFACT_DIR}/opencv-upload/" 2>/dev/null || true
    # FIX: Copy DLL dependencies from deps/
    if [ -d "${ARTIFACT_DIR}/opencv/deps" ]; then
        cp "${ARTIFACT_DIR}/opencv/deps/"*.dll "${ARTIFACT_DIR}/opencv-upload/" 2>/dev/null || true
    fi
fi

# Copy Tesseract artifacts
echo "--- Tesseract ---"
if [ -d "${ARTIFACT_DIR}/tesseract" ]; then
    ls -laR "${ARTIFACT_DIR}/tesseract/"
    # Copy ALL DLLs including Leptonica and runtime dependencies
    cp "${ARTIFACT_DIR}/tesseract/bin/"*.dll "${ARTIFACT_DIR}/tesseract-upload/" 2>/dev/null || true
    cp "${ARTIFACT_DIR}/tesseract/lib/"*.dll.a "${ARTIFACT_DIR}/tesseract-upload/" 2>/dev/null || true
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
