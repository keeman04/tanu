#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT_DIR"

echo "TANU iOS - Free Apple Personal Team setup"
echo "-------------------------------------------"

if [ "$(uname -s)" != "Darwin" ]; then
  echo "ERROR: This setup must be run on a Mac."
  exit 1
fi

if ! command -v xcodebuild >/dev/null 2>&1; then
  echo "ERROR: Xcode is required. Install Xcode from the Mac App Store, open it once, then run this script again."
  exit 1
fi

if ! xcodebuild -version >/dev/null 2>&1; then
  echo "ERROR: Xcode is installed but not ready. Open Xcode once and accept its license/components, then try again."
  exit 1
fi

if ! command -v xcodegen >/dev/null 2>&1; then
  if command -v brew >/dev/null 2>&1; then
    echo "Installing XcodeGen with Homebrew..."
    brew install xcodegen
  else
    echo "ERROR: XcodeGen is not installed and Homebrew is not available."
    echo "Install Homebrew from https://brew.sh, then run: brew install xcodegen"
    exit 1
  fi
fi

echo "Generating TANU.xcodeproj..."
xcodegen generate

echo
 echo "Project generated successfully."
echo "Opening TANU in Xcode..."
open TANU.xcodeproj

echo
echo "NEXT STEPS IN XCODE:"
echo "1. Xcode > Settings > Accounts > add your free Apple Account."
echo "2. Click TANU in the Project navigator > TARGETS: TANU > Signing & Capabilities."
echo "3. Keep Automatically manage signing ON."
echo "4. Team: choose your '(Personal Team)'."
echo "5. Connect and unlock your iPhone, tap Trust if asked, and enable Developer Mode if iOS asks."
echo "6. Select your iPhone as the run destination at the top of Xcode."
echo "7. Press the Run button (triangle)."
echo
echo "Bundle ID used by this free build: com.keeman04.tanu.personal"
echo "No paid Apple Developer membership is required for this direct personal-device test."
