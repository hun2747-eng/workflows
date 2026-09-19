#!/bin/bash
set -e

echo "=== Building NetLokator iOS 17 IPA ==="

if ! command -v xcodegen &> /dev/null; then
    echo "Installing xcodegen..."
    brew install xcodegen
fi

cd ios
echo "Generating Xcode project..."
xcodegen generate

echo "Building Archive..."
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
cp -r build/NetLokator.xcarchive/Products/Applications/NetLokator.app build/Payload/
cd build
zip -r NetLokator-unsigned.ipa Payload
echo "=== NetLokator-unsigned.ipa ready at ios/build/NetLokator-unsigned.ipa ==="
