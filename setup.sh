#!/usr/bin/env bash
# Clone whisper.cpp + download Montserrat Bold font for subtitle rendering.
# Run once before first gradle build.
set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
CPP_DIR="$SCRIPT_DIR/app/src/main/cpp"
FONT_DIR="$SCRIPT_DIR/app/src/main/assets/fonts"
WHISPER_VERSION="v1.7.4"

echo "==> Cloning whisper.cpp ($WHISPER_VERSION) into $CPP_DIR/whisper.cpp"
if [ ! -d "$CPP_DIR/whisper.cpp" ]; then
    git clone --depth 1 --branch "$WHISPER_VERSION" https://github.com/ggml-org/whisper.cpp "$CPP_DIR/whisper.cpp"
else
    echo "    (already present — skipping)"
fi

echo "==> Downloading Montserrat-Bold.ttf"
mkdir -p "$FONT_DIR"
if [ ! -f "$FONT_DIR/Montserrat-Bold.ttf" ]; then
    curl -fSL -o "$FONT_DIR/Montserrat-Bold.ttf" \
        "https://github.com/JulietaUla/Montserrat/raw/master/fonts/ttf/Montserrat-Bold.ttf"
else
    echo "    (already present — skipping)"
fi

echo ""
echo "Done. Next steps:"
echo "  ./gradlew assembleDebug"
echo "  ./gradlew installDebug"
