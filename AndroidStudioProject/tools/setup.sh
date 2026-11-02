#!/bin/bash
set -e
cd "$(dirname "$0")/.."
sdk_home="$ANDROID_HOME"
if [[ -z "$sdk_home" ]] && [[ -e "$HOME/Library/Android/sdk" ]]; then
	sdk_home="$HOME/Library/Android/sdk"
fi
if [[ -z "$sdk_home" ]] && [[ -e "$HOME/Android/sdk" ]]; then
	sdk_home="$HOME/Android/sdk"
fi
if [[ -z "$sdk_home" ]] && [[ -e "$HOME/Android/Sdk" ]]; then
	sdk_home="$HOME/Android/Sdk"
fi
if [[ -z "$sdk_home" ]]; then
	echo "Can't find Android SDK: set ANDROID_HOME"
	exit 1
fi
# <!-- UPDATE --> Update SDK version and the digest with it.
android_jar_sha256=1ced3bcb90990d5b9e7c91a8aa4ff3a9224976d582a66e60810f841ddcd5abb1
if [[ ! -e "tools/android-37-custom.jar" ]]; then
	curl -fL --retry 5 --retry-all-errors --progress-bar \
		-o "tools/android-37-custom.jar" \
		"https://github.com/outlawsanzhang/android-hidden-api/releases/download/37.0/android-37-custom.jar"
fi
echo "$android_jar_sha256  tools/android-37-custom.jar" | sha256sum -c -
./tools/build_fake_sdk.sh "$sdk_home" "modified_sdk" "tools/android-37-custom.jar"
echo "sdk.dir=$(readlink -f "$PWD/modified_sdk")" > local.properties
