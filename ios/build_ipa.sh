#!/bin/bash
set -e

echo "=== Building NetLokator iOS 17 IPA ==="

# If Xcode 16 is available, switch to it, otherwise keep current or find best matching
if [ -d "/Applications/Xcode_16.0.app" ]; then
    export DEVELOPER_DIR="/Applications/Xcode_16.0.app/Contents/Developer"
elif [ -d "/Applications/Xcode_16.app" ]; then
    export DEVELOPER_DIR="/Applications/Xcode_16.app/Contents/Developer"
elif [ -d "/Applications/Xcode_15.4.app" ]; then
    export DEVELOPER_DIR="/Applications/Xcode_15.4.app/Contents/Developer"
fi

echo "Using Xcode at: "
xcodebuild -version

# Install xcodegen if missing
if ! command -v xcodegen &> /dev/null; then
    echo "Installing xcodegen..."
    brew install xcodegen
fi

cd ios
echo "Generating Xcode project..."
xcodegen generate

echo "Building Archive..."
xcodebuild archive   -project NetLokator.xcodeproj   -scheme NetLokator   -configuration Release   -destination "generic/platform=iOS"   -archivePath build/NetLokator.xcarchive   CODE_SIGNING_ALLOWED=NO   CODE_SIGNING_REQUIRED=NO   CODE_SIGN_IDENTITY=""

echo "Creating IPA..."
mkdir -p build/Payload
cp -r build/NetLokator.xcarchive/Products/Applications/NetLokator.app build/Payload/
cd build
zip -r NetLokator-unsigned.ipa Payload
echo "=== NetLokator-unsigned.ipa ready at ios/build/NetLokator-unsigned.ipa ==="
