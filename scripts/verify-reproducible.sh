#!/usr/bin/env bash
# Rebuilds a published release from source and checks that the published
# files are exactly what the source produces.
#
#   git checkout v0.0.1 && scripts/verify-reproducible.sh v0.0.1
#   scripts/verify-reproducible.sh v0.0.1 --from DIR   (already-downloaded files)
#
# Downloads the release's APKs, koi-ctl and SHA256SUMS (or takes them from
# DIR), rebuilds the same tag in the pinned build container, and compares:
#   - each APK with apksigcopier: the rebuilt APK is zipaligned like a release,
#     the published signature is copied onto it, and the result must verify.
#     So the two differ only in the signature.
#   - koi-ctl byte for byte.
# Needs a clean checkout at the tag, podman, and network access for the
# download and the first build.
set -euo pipefail

repo=$(cd "$(dirname "$0")/.." && pwd)
tag=${1:-}
[[ $tag =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] ||
	{ echo "usage: $0 vMAJOR.MINOR.PATCH[-suffix] [--from DIR]" >&2; exit 2; }
from=
if [[ ${2:-} == --from ]]; then
	from=$(cd "${3:?--from needs a directory}" && pwd)
fi
v=${tag#v}
files=("koi-$v.apk" "koi-$v-dev.apk" "koi-devd-$v.apk" koi-ctl SHA256SUMS)

# Inside the repository, so the build container sees it under /src.
work=dist/verify-$tag
rm -rf "${repo:?}/$work"
mkdir -p "$repo/$work"
for f in "${files[@]}"; do
	if [[ -n $from ]]; then
		cp "$from/$f" "$repo/$work/$f"
	else
		curl -fsSL --retry 3 -o "$repo/$work/$f" \
			"https://github.com/franticplant/koiTerminal/releases/download/$tag/$f"
	fi
done
echo "== published checksums"
(cd "$repo/$work" && grep -E " (koi-$v\.apk|koi-$v-dev\.apk|koi-devd-$v\.apk|koi-ctl)\$" SHA256SUMS | sha256sum -c -)

echo "== rebuilding $tag"
KOI_RELEASE_TAG=$tag "$repo/scripts/build" all
"$repo/scripts/ctl"

out=AndroidStudioProject/app/build/outputs/apk
devd_out=AndroidStudioProject/devd/build/outputs/apk/release
fail=0
# Releases are zipaligned and then signed without moving anything (scripts/sign),
# so align the rebuilt APK the same way before comparing.
compare() {
	local published=$1 rebuilt=$2 aligned=$work/aligned-$1
	if "$repo/scripts/run" --offline bash -c 'zipalign -P 16 -f 4 "$1" "$2" && apksigcopier compare --unsigned "$3" "$2"' \
		_ "$rebuilt" "$aligned" "$work/$published" >/dev/null; then
		echo "  same  $published"
	else
		echo "  DIFF  $published"
		fail=1
	fi
}
echo "== comparing"
compare "koi-$v.apk" "$out/prod/release/app-prod-release-unsigned.apk"
compare "koi-$v-dev.apk" "$out/dev/release/app-dev-release-unsigned.apk"
compare "koi-devd-$v.apk" "$devd_out/devd-release-unsigned.apk"
if cmp -s "$repo/$work/koi-ctl" "$repo/dist/koi-ctl"; then
	echo "  same  koi-ctl"
else
	echo "  DIFF  koi-ctl"
	fail=1
fi

if [[ $fail == 0 ]]; then
	echo "$tag reproduces: the published files match this source."
else
	echo "$tag does NOT reproduce here; see the differences above." >&2
fi
exit $fail
