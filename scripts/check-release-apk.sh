#!/usr/bin/env bash
# Checks that APKs carry none of the development-only features, so a prod or
# release build cannot ship them by mistake.
#
# Usage: scripts/check-release-apk.sh [--expect-dev] [--unsigned] APK...
#        scripts/check-release-apk.sh --devd [--unsigned] APK...
#   --expect-dev  the opposite for the checks that can tell: each must find its
#                 dev-only piece. CI runs it on dev builds, so a check that stops
#                 seeing what it looks for fails there instead of passing silently.
#   --unsigned    structural checks only; skip the signer check for artifacts
#                 that have not been signed yet. Signed artifacts must not use it.
#   --devd        check the dev agent APK: native listener, signer, no debug.
#
# The app checks: BuildConfig.DEV_TOOLS (kept by keepRules/rules.keep), the
# "-dev" version name, classes under
# io.github.franticplant.koiterminal.dev (the dev source set and the dev
# API; in a prod build they must be absent, not just unreachable), and the
# DevHooks service and its DEV_HOOKS permission in the manifest.
set -euo pipefail

expect_dev=false
devd=false
unsigned=false
while [[ ${1:-} == --* ]]; do
	case $1 in
	--expect-dev) expect_dev=true ;;
	--devd) devd=true ;;
	--unsigned) unsigned=true ;;
	*) echo "unknown option $1" >&2; exit 2 ;;
	esac
	shift
done
[[ $# -gt 0 ]] || { echo "usage: $0 [--expect-dev|--devd] APK..." >&2; exit 2; }

repo=$(cd "$(dirname "$0")/.." && pwd)
pinned=${KOI_CERT_SHA256:-$(tr -d '[:space:]' < "$repo/dev/signing/koi-dev-cert.sha256")}
pinned=$(printf '%s' "$pinned" | tr 'A-F' 'a-f')
dev_permissions=(
	android.permission.REQUEST_INSTALL_PACKAGES
	android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION
)

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

fail=0
ok() { echo "  ok    $1"; }
bad() {
	echo "  FAIL  $1"
	fail=1
}
# found NAME yes|no: NAME is a dev-only piece; whether it is expected depends on the mode.
found() {
	local name=$1 got=$2
	if $expect_dev; then
		if [[ $got == yes ]]; then
			ok "$name (present, as expected in a dev build)"
		else
			bad "$name: not found in a dev build; the check no longer sees it"
		fi
	else
		if [[ $got == no ]]; then
			ok "no $name"
		else
			bad "$name is present"
		fi
	fi
}
yesno() { if "$@"; then echo yes; else echo no; fi; }

for apk in "$@"; do
	echo "$apk"
	badging=$(aapt2 dump badging "$apk")
	manifest=$(aapt2 dump xmltree --file AndroidManifest.xml "$apk")
	if $unsigned; then
		ok "unsigned (signer check skipped)"
	else
		got=$(apksigner verify --print-certs "$apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
		if [[ $got == "$pinned" ]]; then ok "pinned signer"; else bad "signer $got, expected $pinned"; fi
	fi
	if grep -q "application-debuggable" <<< "$badging"; then bad "debuggable"; else ok "not debuggable"; fi

	if $devd; then
		listing=$(unzip -l "$apk")
		if grep -q 'lib/arm64-v8a/libkoi_devd.so' <<< "$listing"; then
			ok "libkoi_devd.so for arm64-v8a"
		else
			bad "libkoi_devd.so for arm64-v8a is missing"
		fi
		if grep -q 'lib/arm64-v8a/libforwarder_host_jni.so' <<< "$listing"; then
			bad "app-only libforwarder_host_jni.so is present"
		else
			ok "no app-only native libraries"
		fi
		rm -rf "${work:?}"/*
		unzip -q -o "$apk" 'classes*.dex' -d "$work"
		dump=$work/dump.txt
		for dex in "$work"/classes*.dex; do
			dexdump -d "$dex" 2> /dev/null >> "$dump"
		done
		embedded=$(grep -A4 "name *: 'PINNED_CERT_SHA256'" "$dump" | grep -m1 'value *:' | sed -E 's/.*"([0-9a-fA-F]{64})".*/\1/' || true)
		if [[ $embedded == "$pinned" ]]; then
			ok "embedded updater pin"
		else
			bad "embedded updater pin ${embedded:-missing}, expected $pinned"
		fi
		notices=$(unzip -p "$apk" assets/third_party_notices.txt 2> /dev/null || true)
		if [[ $notices == *"JAVA AND KOTLIN LIBRARIES"* && $notices == *"RUST CRATES"* ]]; then
			ok "third-party notices are packaged"
		else
			bad "assets/third_party_notices.txt is missing or incomplete"
		fi
		continue
	fi

	found "-dev version name" "$(yesno grep -qE "^package: .* versionName='[^']*-dev'" <<< "$badging")"

	rm -rf "${work:?}"/*
	unzip -q -o "$apk" 'classes*.dex' -d "$work"
	dump=$work/dump.txt
	for dex in "$work"/classes*.dex; do
		dexdump -d "$dex" 2> /dev/null >> "$dump"
	done
	value=$(grep -A4 "name          : 'DEV_TOOLS'" "$dump" | grep -m1 -oE 'value +: (true|false)' | awk '{print $NF}' || true)
	if [[ -z $value ]]; then
		bad "BuildConfig.DEV_TOOLS not found in the dex"
	else
		found "BuildConfig.DEV_TOOLS=true" "$([[ $value == true ]] && echo yes || echo no)"
	fi

	found "classes from the dev source set" \
		"$(yesno grep -qE "Class descriptor *: 'Lio/github/franticplant/koiterminal/dev/" "$dump")"
	found "DevHooks service" \
		"$(yesno grep -q 'io.github.franticplant.koiterminal.dev.DevHooksService' <<< "$manifest")"
	found "DEV_HOOKS permission" \
		"$(yesno grep -q 'io.github.franticplant.koiterminal.permission.DEV_HOOKS' <<< "$manifest")"

	if ! $expect_dev; then
		for p in "${dev_permissions[@]}"; do
			if grep -q "uses-permission: name='$p'" <<< "$badging"; then bad "$p is requested"; else ok "no $p"; fi
		done
	fi

	listing=$(unzip -l "$apk")
	if grep -q 'lib/arm64-v8a/libforwarder_host_jni.so' <<< "$listing"; then
		ok "libforwarder_host_jni.so for arm64-v8a"
	else
		bad "libforwarder_host_jni.so for arm64-v8a is missing"
	fi
done

if [[ $fail != 0 ]]; then
	if $expect_dev; then
		echo "the release check no longer recognises a dev build; update $0" >&2
	else
		echo "development-only features found; releases must be built without them" >&2
	fi
	exit 1
fi
