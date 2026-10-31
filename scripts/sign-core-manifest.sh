#!/usr/bin/env bash
#
# sign-core-manifest.sh — detached signer for mihomet core update manifests.
#
# Releases published at https://github.com/mihomet/core/releases must ship, next to
# every libmihomo-<abi>.so asset, a signed manifest describing the artifacts:
#
#   manifest.json      — see the schema below (produced by the release pipeline)
#   manifest.json.sig  — detached RSA-4096 "SHA256withRSA" (PKCS#1 v1.5) signature
#                        over the EXACT bytes of manifest.json (raw signature bytes,
#                        NOT base64 — produced by: openssl dgst -sha256 -sign)
#   libmihomo-<abi>.so — one per supported ABI
#
# manifest.json schema (org.mihomet.app.core.update.CoreUpdateVerifier):
# {
#   "schema": 1,
#   "version": "core-v1.19.33-mihomet.4",        // must equal the release tag
#   "minAppVersion": "1.2.0",                    // optional, informational
#   "artifacts": {
#     "arm64-v8a":   { "file": "libmihomo-arm64-v8a.so",   "sha256": "<64 hex>", "size": 64465432 },
#     "armeabi-v7a": { "file": "libmihomo-armeabi-v7a.so", "sha256": "<64 hex>", "size": 58458504 },
#     "x86_64":      { "file": "libmihomo-x86_64.so",      "sha256": "<64 hex>", "size": 67614328 }
#   }
# }
#
# The app embeds the matching PUBLIC key (X.509 SubjectPublicKeyInfo, Base64 DER)
# as CoreConstants.CORE_UPDATE_PUBLIC_KEY_B64. Updates are refused (fail-closed)
# when the manifest is missing, its signature does not verify against the embedded
# key, or the downloaded .so does not match the manifest sha256/size.
#
# Usage:
#   scripts/sign-core-manifest.sh <manifest.json> <private-key.pem> [output.sig]
#
#   manifest.json     path to the manifest to sign
#   private-key.pem   RSA private key (the matching public key is compiled into
#                     the app; repo-external secret, e.g. /secure/path/core-update-priv.pem)
#   output.sig        defaults to <manifest.json>.sig (i.e. manifest.json.sig)
#
# Example (release pipeline):
#   scripts/sign-core-manifest.sh manifest.json /secure/core-update-priv.pem
#   # → writes manifest.json.sig next to manifest.json; upload all three files
#   #   (manifest.json, manifest.json.sig, libmihomo-<abi>.so) to the release.
#
# Verifying a signature locally:
#   openssl pkey -in core-update-priv.pem -pubout -out pub.pem
#   openssl dgst -sha256 -verify pub.pem -signature manifest.json.sig manifest.json

set -euo pipefail

MANIFEST="${1:?usage: sign-core-manifest.sh <manifest.json> <private-key.pem> [output.sig]}"
PRIVATE_KEY="${2:?usage: sign-core-manifest.sh <manifest.json> <private-key.pem> [output.sig]}"
OUTPUT="${3:-${MANIFEST}.sig}"

[ -f "$MANIFEST" ] || { echo "manifest not found: $MANIFEST" >&2; exit 1; }
[ -f "$PRIVATE_KEY" ] || { echo "private key not found: $PRIVATE_KEY" >&2; exit 1; }

openssl dgst -sha256 -sign "$PRIVATE_KEY" -out "$OUTPUT" "$MANIFEST"
echo "signed $MANIFEST -> $OUTPUT ($(stat -c %s "$OUTPUT") raw signature bytes)"
