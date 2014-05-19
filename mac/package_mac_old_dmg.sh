#!/bin/sh
#http://developer.apple.com/library/mac/#documentation/Java/Conceptual/Java14Development/03-JavaDeployment/JavaDeployment.html
#http://stackoverflow.com/questions/96882/how-do-i-create-a-nice-looking-dmg-for-mac-os-x-using-command-line-tools

set -o verbose #echo onset +o verbose #echo off

# Note: this must run on a Mac

myDir="`dirname "$0"`"
cd $myDir

APP_NAME="Android Design Preview"
OUT_MAC=../out/mac
DMG_PATH=${OUT_MAC}/AndroidDesignPreview.dmg
DMG_CONTENT_PATH=${OUT_MAC}/contents
BUNDLE_PATH="${DMG_CONTENT_PATH}/${APP_NAME}.app"

if [ ! -f ../desktop/out/ProoferDesktop.jar ]; then
  echo "Desktop JAR doesn't exist. Did you compile with ant yet?" >&1
  exit
fi

rm -rf ${OUT_MAC}
mkdir -p ${DMG_CONTENT_PATH}
mkdir -p "${BUNDLE_PATH}"
SetFile -a B "${BUNDLE_PATH}"
mkdir -p "${BUNDLE_PATH}/Contents/MacOS/"
mkdir -p "${BUNDLE_PATH}/Contents/Resources/Java/"
cp /System/Library/Frameworks/JavaVM.framework/Versions/Current/Resources/MacOS/JavaApplicationStub "${BUNDLE_PATH}/Contents/MacOS/JavaApplicationStub"
cp ../art/icon.icns "${BUNDLE_PATH}/Contents/Resources/Icon.icns"
cp ../desktop/out/ProoferDesktop.jar "${BUNDLE_PATH}/Contents/Resources/Java/ProoferDesktop.jar"
cp Info.plist "${BUNDLE_PATH}/Contents/"
cp PkgInfo "${BUNDLE_PATH}/Contents/"

hdiutil create -srcfolder ${DMG_CONTENT_PATH} -volname "${APP_NAME}" -fs HFS+ \
    -fsargs "-c c=64,a=16,e=16" -format UDRW -size 10m ${DMG_PATH}.temp.dmg

device=$(hdiutil attach -readwrite -noverify -noautoopen ${DMG_PATH}.temp.dmg | \
    egrep '^/dev/' | sed 1q | awk '{print $1}')

osascript <<EOT
    tell application "Finder"
      tell disk "${APP_NAME}"
        open
        set current view of container window to icon view
        set toolbar visible of container window to false
        set statusbar visible of container window to false
        set the bounds of container window to {400, 100, 885, 430}
        set theViewOptions to the icon view options of container window
        set arrangement of theViewOptions to snap to grid
        set icon size of theViewOptions to 96
        -- set background picture of theViewOptions to file ".background:${backgroundPictureName}"
        make new alias file at container window to POSIX file "/Applications" with properties {name:"Applications"}
        delay 1
        set position of item "${APP_NAME}" of container window to {100, 100}
        set position of item "Applications" of container window to {375, 100}
        -- update without registering applications
        delay 5
        -- eject
      end tell
    end tell
EOT

chmod -Rf go-w "/Volumes/${APP_NAME}"
sync
sync
hdiutil detach ${device}
sync
hdiutil convert ${DMG_PATH}.temp.dmg -format UDZO -imagekey zlib-level=9 -o ${DMG_PATH}
rm -rf ${DMG_PATH}.temp.dmg