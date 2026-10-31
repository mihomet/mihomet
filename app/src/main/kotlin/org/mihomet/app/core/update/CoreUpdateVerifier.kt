package org.mihomet.app.core.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Pure verification chain for runtime core updates (audit P0 §1.2).
 *
 * The in-app updater may ONLY install a core binary that passes, in order:
 *
 *  1. **Signature** — the release's `manifest.json` must carry a detached
 *     RSA-4096 `SHA256withRSA` (PKCS#1 v1.5) signature produced by
 *     `scripts/sign-core-manifest.sh` and verifiable against the public key
 *     embedded in [org.mihomet.app.core.CoreConstants.CORE_UPDATE_PUBLIC_KEY_B64].
 *  2. **Manifest sanity** — schema understood, `version` matches the GitHub
 *     release tag, and the device ABI has an artifact entry.
 *  3. **Integrity** — the downloaded `.so` must match the manifest's `sha256`
 *     AND its exact byte `size`.
 *
 * Fail-closed: every step returns [Result]; the updater installs nothing unless
 * the whole chain succeeds. The class is free of Android/root dependencies so
 * the chain is unit-testable on the JVM.
 */
object CoreUpdateVerifier {

    /** Manifest schema version understood by this build. */
    const val SUPPORTED_SCHEMA = 1

    /** ABI keys used in [CoreManifest.artifacts] (APK abiFilters). */
    val SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64")

    @Serializable
    data class CoreArtifact(
        val file: String = "",
        val sha256: String = "",
        val size: Long = 0L
    )

    @Serializable
    data class CoreManifest(
        val schema: Int = 0,
        val version: String = "",
        val minAppVersion: String? = null,
        val artifacts: Map<String, CoreArtifact> = emptyMap()
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Standard JCA algorithm — available on every supported API level, no new dependency. */
    private const val SIGNATURE_ALGORITHM = "SHA256withRSA"

    /** Maps the device's primary ABI to the manifest artifact key, or null for unsupported devices. */
    fun deviceArtifactKey(primaryAbi: String?): String? = when {
        primaryAbi == null -> null
        primaryAbi.contains("arm64") -> "arm64-v8a"
        primaryAbi.contains("armeabi") || primaryAbi.contains("v7a") -> "armeabi-v7a"
        primaryAbi.contains("x86_64") || primaryAbi.contains("x86") -> "x86_64"
        else -> null
    }

    /** Parses manifest bytes; refuses unknown/missing schema versions. */
    fun parseManifest(bytes: ByteArray): Result<CoreManifest> = runCatching {
        val manifest = json.decodeFromString<CoreManifest>(bytes.toString(Charsets.UTF_8))
        require(manifest.schema == SUPPORTED_SCHEMA) {
            "Unsupported manifest schema ${manifest.schema} (expected $SUPPORTED_SCHEMA)"
        }
        require(manifest.version.isNotBlank()) { "Manifest version is missing" }
        require(manifest.artifacts.isNotEmpty()) { "Manifest lists no artifacts" }
        manifest
    }

    /**
     * Verifies the detached signature over the exact manifest bytes against the
     * embedded public key (Base64 X.509 SubjectPublicKeyInfo).
     */
    fun verifySignature(
        manifestBytes: ByteArray,
        signatureBytes: ByteArray,
        publicKeyBase64: String
    ): Result<Unit> = runCatching {
        val publicKey = parsePublicKey(publicKeyBase64).getOrThrow()
        val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
        verifier.initVerify(publicKey)
        verifier.update(manifestBytes)
        check(verifier.verify(signatureBytes)) {
            "Manifest signature does not match the embedded public key"
        }
    }

    /** Decodes the embedded Base64 SPKI public key (diagnosed separately for clear errors). */
    fun parsePublicKey(publicKeyBase64: String): Result<PublicKey> = runCatching {
        val decoded = Base64.getMimeDecoder().decode(publicKeyBase64.trim())
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(decoded))
    }

    /**
     * Selects the manifest artifact for [abi] and validates its declared fields
     * (file name present, sha256 well-formed) WITHOUT any binary data.
     */
    fun selectArtifact(manifest: CoreManifest, abi: String): Result<CoreArtifact> = runCatching {
        val artifact = manifest.artifacts[abi]
            ?: throw IllegalStateException("Manifest has no artifact for ABI $abi (have: ${manifest.artifacts.keys.sorted()})")
        require(artifact.file.isNotBlank()) { "Manifest artifact for $abi has no file name" }
        require(artifact.sha256.length == 64 && artifact.sha256.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            "Manifest artifact for $abi has a malformed sha256"
        }
        require(artifact.size > 0L) { "Manifest artifact for $abi has no size" }
        artifact
    }

    /**
     * Verifies the downloaded [binaryBytes] against the manifest entry for [abi]:
     * exact byte size first, then sha256. Returns the verified artifact.
     */
    fun verifyBinary(
        manifest: CoreManifest,
        abi: String,
        binaryBytes: ByteArray
    ): Result<CoreArtifact> = runCatching {
        val artifact = selectArtifact(manifest, abi).getOrThrow()
        if (binaryBytes.size.toLong() != artifact.size) {
            throw IllegalStateException(
                "Downloaded core size ${binaryBytes.size} does not match the manifest (${artifact.size})"
            )
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(binaryBytes)
            .joinToString("") { "%02x".format(it) }
        if (!digest.equals(artifact.sha256, ignoreCase = true)) {
            throw IllegalStateException(
                "Downloaded core sha256 mismatch: expected ${artifact.sha256}, got $digest"
            )
        }
        artifact
    }

    /**
     * Cross-checks the manifest's own version against the GitHub release tag it was
     * downloaded from — a mismatch means the release assets are inconsistent.
     */
    fun checkManifestVersion(manifest: CoreManifest, releaseTag: String): Result<Unit> = runCatching {
        check(manifest.version.trim() == releaseTag.trim()) {
            "Manifest version '${manifest.version}' does not match release tag '$releaseTag'"
        }
    }

    /**
     * Defense in depth: asset URLs taken from the GitHub API response are only
     * followed when they point into the mihomet/core releases space. A tampered
     * API answer cannot redirect the download elsewhere (the signature+sha256
     * chain would still catch wrong bytes, but the fetch itself stays pinned).
     */
    fun isTrustedCoreUrl(url: String): Boolean =
        url.startsWith("${org.mihomet.app.core.CoreConstants.CORE_RELEASES_DOWNLOAD_BASE}/") ||
                url.startsWith("https://api.github.com/repos/mihomet/core/releases/")
}
