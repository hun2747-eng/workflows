#!/bin/bash
set -euo pipefail

echo "=== Building signed NetLokator iOS 17 IPA ==="

: "${DEVELOPMENT_TEAM:?DEVELOPMENT_TEAM is required}"
: "${CODE_SIGN_IDENTITY:?CODE_SIGN_IDENTITY is required}"
: "${APP_PROVISIONING_PROFILE_NAME:?APP_PROVISIONING_PROFILE_NAME is required}"
: "${SHARE_PROVISIONING_PROFILE_NAME:?SHARE_PROVISIONING_PROFILE_NAME is required}"

LATEST_XCODE=$(ls -d /Applications/Xcode*.app 2>/dev/null | sort -V | tail -n 1)
if [ -n "$LATEST_XCODE" ]; then
    export DEVELOPER_DIR="$LATEST_XCODE/Contents/Developer"
fi

echo "Using Xcode at: $DEVELOPER_DIR"
xcodebuild -version

cd ios

echo "Generating Xcode project..."
xcodegen generate

if [ -f "NetLokator.xcodeproj/project.pbxproj" ]; then
    sed -i '' -E 's/objectVersion = [0-9]+;/objectVersion = 56;/g' NetLokator.xcodeproj/project.pbxproj || true
fi

rm -rf build

echo "=== Archiving with Apple signing ==="
xcodebuild archive   -project NetLokator.xcodeproj   -scheme NetLokator   -configuration Release   -destination "generic/platform=iOS"   -archivePath build/NetLokator.xcarchive   DEVELOPMENT_TEAM="$DEVELOPMENT_TEAM"   CODE_SIGN_IDENTITY="$CODE_SIGN_IDENTITY"   CODE_SIGN_STYLE=Manual   CODE_SIGNING_ALLOWED=YES   CODE_SIGNING_REQUIRED=YES   "PROVISIONING_PROFILE_SPECIFIER=$APP_PROVISIONING_PROFILE_NAME"

echo "=== Exporting signed IPA ==="

cat > build/ExportOptions.plist <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>method</key>
  <string>ad-hoc</string>
  <key>signingStyle</key>
  <string>manual</string>
  <key>teamID</key>
  <string>$DEVELOPMENT_TEAM</string>
  <key>signingCertificate</key>
  <string>$CODE_SIGN_IDENTITY</string>
  <key>provisioningProfiles</key>
  <dict>
    <key>hu.netlokator.app</key>
    <string>$APP_PROVISIONING_PROFILE_NAME</string>
    <key>hu.netlokator.app.share</key>
    <string>$SHARE_PROVISIONING_PROFILE_NAME</string>
  </dict>
</dict>
</plist>
EOF

xcodebuild -exportArchive   -archivePath build/NetLokator.xcarchive   -exportPath build/export   -exportOptionsPlist build/ExportOptions.plist

test -f build/export/NetLokator.ipa

mv build/export/NetLokator.ipa build/NetLokator.ipa

echo "=== Signed IPA ready: ios/build/NetLokator.ipa ==="
