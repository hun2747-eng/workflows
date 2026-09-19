#!/bin/bash
set -e

echo "=== Building NetLokator iOS 17 IPA ==="

# Auto-detect latest available Xcode in runner
LATEST_XCODE=$(ls -d /Applications/Xcode*.app 2>/dev/null | sort -V | tail -n 1)
if [ -n "$LATEST_XCODE" ]; then
    export DEVELOPER_DIR="$LATEST_XCODE/Contents/Developer"
fi

echo "Using Xcode at: $DEVELOPER_DIR"
xcodebuild -version

cd ios
echo "Generating Xcode project..."
xcodegen generate

# Ensure objectVersion is 56 (Xcode 14/15/16 compatible)
if [ -f "NetLokator.xcodeproj/project.pbxproj" ]; then
    sed -i '' -E 's/objectVersion = [0-9]+;/objectVersion = 56;/g' NetLokator.xcodeproj/project.pbxproj || true
fi

echo "Building Archive..."
rm -rf build
xcodebuild archive \
  -project NetLokator.xcodeproj \
  -scheme NetLokator \
  -configuration Release \
  -destination "generic/platform=iOS" \
  -archivePath build/NetLokator.xcarchive \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_IDENTITY=""

echo "Creating IPA..."
mkdir -p build/Payload
cp -R build/NetLokator.xcarchive/Products/Applications/NetLokator.app build/Payload/
find build/Payload -name ".DS_Store" -delete 2>/dev/null || true

cd build
# Use zip -qry for compliant iOS packaging preserving symlinks and bundle structure
zip -qry NetLokator-unsigned.ipa Payload
echo "=== NetLokator-unsigned.ipa ready at ios/build/NetLokator-unsigned.ipa ==="
