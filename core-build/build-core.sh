#!/usr/bin/env bash
# core-build/build-core.sh — build the Mihomet patched mihomo core for Android.
#
# Builds upstream mihomo v1.19.33 + core-patch/mihomo-android-nonroot.patch
# (non-root TUN fix, see the patch header) as three GOOS=android binaries:
#
#   arm64-v8a   -> GOARCH=arm64              -> dist/libmihomo-arm64-v8a.so
#   armeabi-v7a -> GOARCH=arm GOARM=7        -> dist/libmihomo-armeabi-v7a.so
#   x86_64      -> GOARCH=amd64              -> dist/libmihomo-x86_64.so
#
# Build configuration mirrors the official MetaCubeX release workflow
# (.github/workflows/build.yml @ v1.19.33): android targets are cgo builds
# linked with the Android NDK clang (android/amd64 has no internal-linker
# support, so CGO_ENABLED=0 cannot produce it), with -tags with_gvisor (the
# gVisor TUN stack, same tag as the official Makefile GOBUILD), -trimpath and
# -w -s -buildid=. The version is stamped as constant.Version=<CORE_VERSION>.
# The go toolchain is the one shipped inside the golang:1.23 docker image
# (GOTOOLCHAIN=local — no toolchain download).
#
# Host requirements: docker, git, file(1), sha256sum and the Android NDK
# (auto-discovered under ~/Android/ndk or $ANDROID_HOME/ndk, or set NDK_HOME).
#
# mihomo is GPL-3.0; the patched core's complete corresponding source is
# this repository (core-patch/ + this script).
#
# Usage: [NDK_HOME=/path/to/ndk] ./core-build/build-core.sh
# Output: core-build/dist/libmihomo-<abi>.so + SHA256SUMS
set -euo pipefail

UPSTREAM_REPO="https://github.com/MetaCubeX/mihomo.git"
UPSTREAM_VERSION="v1.19.33"                # upstream tag to build from
# Version stamp baked into constant.Version; overridable via env. The stamp
# must match the GitHub release tag the built artifacts are published under.
CORE_VERSION="${CORE_VERSION:-v1.19.33-mihomet.3}"
GO_IMAGE="golang:1.23"
ANDROID_API="${ANDROID_API:-26}"           # minSdk of the app: binaries must run on Android 8+
NDK_HOST_TAG="linux-x86_64"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
PATCH_FILE="$REPO_ROOT/core-patch/mihomo-android-nonroot.patch"
OUT_DIR="$SCRIPT_DIR/dist"
WORK_DIR="$(mktemp -d /tmp/mihomet-core-build.XXXXXX)"
cleanup() { rm -rf "$WORK_DIR"; }
trap cleanup EXIT

log() { printf '\n[mihomet-core] %s\n' "$*"; }
die() { printf '[mihomet-core] ERROR: %s\n' "$*" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || die "docker is required"
command -v git    >/dev/null 2>&1 || die "git is required"
command -v file   >/dev/null 2>&1 || die "file(1) is required (ELF sanity checks)"
[ -f "$PATCH_FILE" ] || die "patch not found: $PATCH_FILE"
docker info >/dev/null 2>&1 || die "docker daemon is not reachable"

# Locate the Android NDK (needed for cgo linking of all android targets).
NDK_HOME="${NDK_HOME:-}"
if [ -z "$NDK_HOME" ]; then
  for d in "$HOME"/Android/ndk/android-ndk-* "${ANDROID_HOME:-/nonexistent}"/ndk/*; do
    [ -d "$d" ] && NDK_HOME="$d"
  done
fi
[ -n "$NDK_HOME" ] || die "Android NDK not found — install it under ~/Android/ndk or set NDK_HOME"
NDK_TOOLCHAIN="/ndk/toolchains/llvm/prebuilt/$NDK_HOST_TAG"
[ -x "$NDK_HOME/toolchains/llvm/prebuilt/$NDK_HOST_TAG/bin/clang" ] \
  || die "invalid NDK layout: $NDK_HOME/toolchains/llvm/prebuilt/$NDK_HOST_TAG"
log "Using NDK: $NDK_HOME (API $ANDROID_API)"

log "Cloning mihomo $UPSTREAM_VERSION into $WORK_DIR/mihomo"
git clone --depth 1 --branch "$UPSTREAM_VERSION" "$UPSTREAM_REPO" "$WORK_DIR/mihomo" \
  || die "failed to clone $UPSTREAM_REPO ($UPSTREAM_VERSION)"

# Deterministic build time: the upstream commit date of the built tag.
BUILDTIME="$(git -C "$WORK_DIR/mihomo" log -1 --format=%cI)"

log "Verifying patch applies to $UPSTREAM_VERSION"
git -C "$WORK_DIR/mihomo" apply --check "$PATCH_FILE" \
  || die "patch does not apply cleanly to $UPSTREAM_VERSION — aborting"
git -C "$WORK_DIR/mihomo" apply "$PATCH_FILE"
log "Patch applied: $(git -C "$WORK_DIR/mihomo" diff --stat | tail -1)"

mkdir -p "$OUT_DIR" "$WORK_DIR/out"

# abi name -> goarch [goarm] [ndk-clang-target]; matches the Android ABIs and
# the app's jniLibs layout (arm64-v8a / armeabi-v7a / x86_64).
build_abi() {
  local abi="$1"
  local goarch="$2"
  local goarm="${3:-}"
  local cc_target="$4"
  local out="$OUT_DIR/libmihomo-$abi.so"
  local goarm_env=()
  [ -n "$goarm" ] && goarm_env=(-e "GOARM=$goarm")
  log "Building $abi (GOARCH=$goarch${goarm:+ GOARM=$goarm}) with $cc_target -> $out"
  docker run --rm \
    -v "$WORK_DIR:/work" \
    -v "$NDK_HOME:/ndk:ro" \
    -v mihomet-core-gomodcache:/gomodcache \
    -v mihomet-core-gocache:/gocache \
    -e GOMODCACHE=/gomodcache \
    -e GOCACHE=/gocache \
    -e GOTOOLCHAIN=local \
    -e CGO_ENABLED=1 \
    -e GOOS=android \
    -e GOARCH="$goarch" \
    "${goarm_env[@]}" \
    -e CC="$NDK_TOOLCHAIN/bin/$cc_target" \
    -e HOME=/tmp \
    -w /work/mihomo \
    "$GO_IMAGE" \
    go build -tags with_gvisor -trimpath -buildvcs=false \
      -ldflags "-X \"github.com/metacubex/mihomo/constant.Version=$CORE_VERSION\" \
        -X \"github.com/metacubex/mihomo/constant.BuildTime=$BUILDTIME\" \
        -w -s -buildid=" \
      -o "/work/out/libmihomo-$abi.so" .
  mv "$WORK_DIR/out/libmihomo-$abi.so" "$out"
  chmod 0755 "$out"
}

build_abi "arm64-v8a"   "arm64" ""  "aarch64-linux-android$ANDROID_API-clang"
build_abi "armeabi-v7a" "arm"   "7" "armv7a-linux-androideabi$ANDROID_API-clang"
build_abi "x86_64"      "amd64" ""  "x86_64-linux-android$ANDROID_API-clang"

log "Sanity checks (ELF headers, version stamp)"
for abi in arm64-v8a armeabi-v7a x86_64; do
  out="$OUT_DIR/libmihomo-$abi.so"
  [ -s "$out" ] || die "missing artifact: $out"
  file "$out" | sed 's/^/[mihomet-core]   /'
  grep -aq "$CORE_VERSION" "$out" || die "version string $CORE_VERSION not found in $out"
done

log "SHA256"
cd "$OUT_DIR"
sha256sum libmihomo-arm64-v8a.so libmihomo-armeabi-v7a.so libmihomo-x86_64.so | tee SHA256SUMS
ls -l libmihomo-*.so | sed 's/^/[mihomet-core]   /'

log "Done. Artifacts in $OUT_DIR (upload these to the GitHub release $CORE_VERSION)"
