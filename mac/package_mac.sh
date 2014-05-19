#!/bin/sh
#http://developer.apple.com/library/mac/#documentation/Java/Conceptual/Java14Development/03-JavaDeployment/JavaDeployment.html

set -o verbose #echo onset +o verbose #echo off

# Note: this must run on a Mac

myDir="`dirname "$0"`"
cd $myDir

APP_NAME="Android Design Preview"
OUT_PATH=../out/mac
OUT_ZIP="${APP_NAME}-mac.zip"
BUNDLE_NAME="${APP_NAME}.app"
BUNDLE_PATH="${OUT_PATH}/${BUNDLE_NAME}"

if [ ! -f ../desktop/out/ProoferDesktop.jar ]; then
  echo "Desktop JAR doesn't exist. Did you compile with ant yet?" >&1
  exit
fi

rm -rf ${OUT_PATH}
mkdir -p "${BUNDLE_PATH}"
SetFile -a B "${BUNDLE_PATH}"
mkdir -p "${BUNDLE_PATH}/Contents/MacOS/"
mkdir -p "${BUNDLE_PATH}/Contents/Resources/Java/"
cp /System/Library/Frameworks/JavaVM.framework/Versions/Current/Resources/MacOS/JavaApplicationStub "${BUNDLE_PATH}/Contents/MacOS/JavaApplicationStub"
cp ../art/icon.icns "${BUNDLE_PATH}/Contents/Resources/Icon.icns"
cp ../desktop/out/ProoferDesktop.jar "${BUNDLE_PATH}/Contents/Resources/Java/ProoferDesktop.jar"
cp Info.plist "${BUNDLE_PATH}/Contents/"
cp PkgInfo "${BUNDLE_PATH}/Contents/"

cd "${OUT_PATH}"
zip -r "${OUT_ZIP}" "${BUNDLE_NAME}"
