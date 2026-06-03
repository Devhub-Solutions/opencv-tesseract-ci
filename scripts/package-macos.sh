#!/bin/bash
# ============================================================
# Package macOS artifacts into Java-importable structure
# FIX: Fix install_name_tool for all dylibs (relocatable)
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
    # Copy ALL dylib files including Leptonica
    cp "${ARTIFACT_DIR}/tesseract/lib/"*.dylib "${ARTIFACT_DIR}/tesseract-upload/" 2>/dev/null || true
    cp -r "${ARTIFACT_DIR}/tesseract/include/tesseract" "${ARTIFACT_DIR}/tesseract-upload/" 2>/dev/null || true
fi

# FIX: Fix install names on all dylibs for portability
# Change absolute paths (/usr/local/lib/...) to @rpath/... so they
# can be loaded from any directory when bundled together
echo "--- Fixing install names on Tesseract/Leptonica dylibs ---"
for dylib in "${ARTIFACT_DIR}/tesseract-upload/"*.dylib; do
    if [ -f "$dylib" ] && [ ! -L "$dylib" ]; then
        libname=$(basename "$dylib")
        echo "Processing: $libname"

        # Set the library's own ID to @rpath/libname
        install_name_tool -id "@rpath/$libname" "$dylib" 2>/dev/null || true

        # Change all absolute dependency paths to @rpath
        for dep in $(otool -L "$dylib" 2>/dev/null | grep -E "/usr/local/lib/" | awk '{print $1}'); do
            dep_name=$(basename "$dep")
            echo "  Changing $dep → @rpath/$dep_name"
            install_name_tool -change "$dep" "@rpath/$dep_name" "$dylib" 2>/dev/null || true
        done

        # Also change Homebrew paths
        for dep in $(otool -L "$dylib" 2>/dev/null | grep -E "/opt/homebrew/lib/|/usr/local/Cellar/" | awk '{print $1}'); do
            dep_name=$(basename "$dep")
            echo "  Changing $dep → @rpath/$dep_name"
            install_name_tool -change "$dep" "@rpath/$dep_name" "$dylib" 2>/dev/null || true
        done
    fi
done

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
