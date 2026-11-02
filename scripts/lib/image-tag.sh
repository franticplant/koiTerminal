#!/bin/sh
# Prints the local build image's name: localhost/koi-build:<tag>, where the
# tag hashes everything the image is built from (build/Containerfile,
# toolchain.lock, install-cargo-tools.sh, requirements-verify.txt). Used by scripts/podman-run and
# scripts/sign, so all agree on the image.
#   image-tag.sh REPO
set -eu
cd "$1/build"
tag=$(cat Containerfile toolchain.lock install-cargo-tools.sh requirements-verify.txt | sha256sum | cut -c1-16)
echo "localhost/koi-build:$tag"
