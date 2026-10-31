package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mihomet.app.core.CoreConstants
import org.mihomet.app.core.update.CoreUpdateVerifier
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/**
 * Strict tests for the runtime core-update verification chain (audit P0 §1.2).
 *
 * The chain is pure JVM (JCA only): manifest signature (RSA-4096 SHA256withRSA
 * against the embedded public key) → manifest sanity → exact size + sha256 of the
 * downloaded binary. The updater must install NOTHING unless every step passes.
 */
class CoreUpdateVerifierStrictTest {

    private lateinit var testPublicKeyBase64: String
    private lateinit var testPrivateKey: java.security.PrivateKey

    @Before
    fun setUp() {
        // Fresh 2048-bit test keypair (verification logic is size-agnostic; the
        // embedded release key is RSA-4096 and is checked separately below).
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val keyPair = generator.generateKeyPair()
        testPrivateKey = keyPair.private
        testPublicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
    }

    private fun sign(bytes: ByteArray, key: java.security.PrivateKey = testPrivateKey): ByteArray =
        Signature.getInstance("SHA256withRSA").apply {
            initSign(key)
            update(bytes)
        }.sign()

    private fun buildManifest(sha: String, size: Long): ByteArray = """
        {
          "schema": 1,
          "version": "core-v1.19.33-mihomet.4",
          "minAppVersion": "1.2.0",
          "artifacts": {
            "arm64-v8a":   { "file": "libmihomo-arm64-v8a.so",   "sha256": "$sha", "size": $size },
            "armeabi-v7a": { "file": "libmihomo-armeabi-v7a.so", "sha256": "$sha", "size": $size },
            "x86_64":      { "file": "libmihomo-x86_64.so",      "sha256": "$sha", "size": $size }
          }
        }
    """.trimIndent().toByteArray(Charsets.UTF_8)

    // ---------------------------------------------------------------------
    // Embedded release public key
    // ---------------------------------------------------------------------

    @Test
    fun `embedded public key parses as RSA-4096`() {
        val key = CoreUpdateVerifier.parsePublicKey(CoreConstants.CORE_UPDATE_PUBLIC_KEY_B64).getOrThrow()
        assertEquals("RSA", key.algorithm)
        val encoded = key.encoded
        // RSA-4096 SPKI DER is ~550 bytes (512-byte modulus + headers); anything
        // smaller means the embedded key was swapped for a weaker one.
        assertTrue("embedded key must be RSA-4096 SPKI", encoded.size > 500)
    }

    @Test
    fun `embedded public key rejects a foreign signature`() {
        // Manifest signed by an attacker key must NOT verify against the embedded key.
        val attacker = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val manifest = "{\"schema\":1,\"version\":\"x\",\"artifacts\":{}}".toByteArray()
        val forged = sign(manifest, attacker.private)

        val result = CoreUpdateVerifier.verifySignature(
            manifest, forged, CoreConstants.CORE_UPDATE_PUBLIC_KEY_B64
        )
        assertTrue("forged signature must be refused", result.isFailure)
    }

    // ---------------------------------------------------------------------
    // Signature verification
    // ---------------------------------------------------------------------

    @Test
    fun `valid signature verifies against the matching public key`() {
        val manifest = "{\"schema\":1,\"version\":\"v1\",\"artifacts\":{}}".toByteArray()
        val signature = sign(manifest)
        assertTrue(CoreUpdateVerifier.verifySignature(manifest, signature, testPublicKeyBase64).isSuccess)
    }

    @Test
    fun `tampered manifest fails signature verification`() {
        val manifest = "{\"schema\":1,\"version\":\"v1\",\"artifacts\":{}}".toByteArray()
        val signature = sign(manifest)
        val tampered = "{\"schema\":1,\"version\":\"v1-evil\",\"artifacts\":{}}".toByteArray()
        assertTrue(CoreUpdateVerifier.verifySignature(tampered, signature, testPublicKeyBase64).isFailure)
    }

    @Test
    fun `wrong key or garbage signature fails`() {
        val manifest = "{\"schema\":1}".toByteArray()
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertTrue(
            CoreUpdateVerifier.verifySignature(manifest, sign(manifest), Base64.getEncoder().encodeToString(other.public.encoded)).isFailure
        )
        assertTrue(CoreUpdateVerifier.verifySignature(manifest, "not-a-signature".toByteArray(), testPublicKeyBase64).isFailure)
    }

    // ---------------------------------------------------------------------
    // Manifest parsing
    // ---------------------------------------------------------------------

    @Test
    fun `parseManifest accepts a valid schema-1 manifest`() {
        val bytes = "{\"schema\":1,\"version\":\"core-v1\",\"artifacts\":{\"arm64-v8a\":{\"file\":\"a.so\",\"sha256\":\"${"a".repeat(64)}\",\"size\":10}}}".toByteArray()
        val manifest = CoreUpdateVerifier.parseManifest(bytes).getOrThrow()
        assertEquals("core-v1", manifest.version)
        assertEquals("a.so", manifest.artifacts.getValue("arm64-v8a").file)
    }

    @Test
    fun `parseManifest refuses unknown schema missing version or empty artifacts`() {
        val badSchema = "{\"schema\":2,\"version\":\"x\",\"artifacts\":{\"a\":{\"file\":\"f\",\"sha256\":\"${"a".repeat(64)}\",\"size\":1}}}".toByteArray()
        val noVersion = "{\"schema\":1,\"artifacts\":{\"a\":{\"file\":\"f\",\"sha256\":\"${"a".repeat(64)}\",\"size\":1}}}".toByteArray()
        val noArtifacts = "{\"schema\":1,\"version\":\"x\"}".toByteArray()
        val garbage = "not json at all".toByteArray()

        assertTrue(CoreUpdateVerifier.parseManifest(badSchema).isFailure)
        assertTrue(CoreUpdateVerifier.parseManifest(noVersion).isFailure)
        assertTrue(CoreUpdateVerifier.parseManifest(noArtifacts).isFailure)
        assertTrue(CoreUpdateVerifier.parseManifest(garbage).isFailure)
    }

    @Test
    fun `checkManifestVersion enforces the release tag`() {
        val manifest = CoreUpdateVerifier.CoreManifest(
            schema = 1,
            version = "core-v1.19.33-mihomet.4",
            artifacts = mapOf("arm64-v8a" to CoreUpdateVerifier.CoreArtifact("a.so", "a".repeat(64), 1))
        )
        assertTrue(CoreUpdateVerifier.checkManifestVersion(manifest, "core-v1.19.33-mihomet.4").isSuccess)
        assertTrue(CoreUpdateVerifier.checkManifestVersion(manifest, "core-v1.19.33-mihomet.3").isFailure)
    }

    // ---------------------------------------------------------------------
    // Artifact selection + binary integrity
    // ---------------------------------------------------------------------

    @Test
    fun `deviceArtifactKey maps device ABIs to manifest keys`() {
        assertEquals("arm64-v8a", CoreUpdateVerifier.deviceArtifactKey("arm64-v8a"))
        assertEquals("armeabi-v7a", CoreUpdateVerifier.deviceArtifactKey("armeabi-v7a"))
        assertEquals("x86_64", CoreUpdateVerifier.deviceArtifactKey("x86_64"))
        assertNull(CoreUpdateVerifier.deviceArtifactKey("mips"))
        assertNull(CoreUpdateVerifier.deviceArtifactKey(null))
    }

    @Test
    fun `selectArtifact refuses missing ABI malformed sha256 missing file or zero size`() {
        val good = CoreUpdateVerifier.CoreArtifact("lib.so", "a".repeat(64), 100)
        val manifest = CoreUpdateVerifier.CoreManifest(
            schema = 1, version = "v", artifacts = mapOf("arm64-v8a" to good)
        )
        assertEquals("lib.so", CoreUpdateVerifier.selectArtifact(manifest, "arm64-v8a").getOrThrow().file)
        assertTrue(CoreUpdateVerifier.selectArtifact(manifest, "x86_64").isFailure)

        val badSha = manifest.copy(artifacts = mapOf("arm64-v8a" to good.copy(sha256 = "zz")))
        assertTrue(CoreUpdateVerifier.selectArtifact(badSha, "arm64-v8a").isFailure)
        val noFile = manifest.copy(artifacts = mapOf("arm64-v8a" to good.copy(file = "")))
        assertTrue(CoreUpdateVerifier.selectArtifact(noFile, "arm64-v8a").isFailure)
        val zeroSize = manifest.copy(artifacts = mapOf("arm64-v8a" to good.copy(size = 0)))
        assertTrue(CoreUpdateVerifier.selectArtifact(zeroSize, "arm64-v8a").isFailure)
    }

    @Test
    fun `verifyBinary enforces exact size and sha256`() {
        val binary = ByteArray(128) { (it % 251).toByte() }
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(binary)
            .joinToString("") { "%02x".format(it) }
        val manifest = CoreUpdateVerifier.CoreManifest(
            schema = 1,
            version = "v",
            artifacts = mapOf("arm64-v8a" to CoreUpdateVerifier.CoreArtifact("a.so", sha, binary.size.toLong()))
        )

        // Exact match passes and returns the artifact
        val artifact = CoreUpdateVerifier.verifyBinary(manifest, "arm64-v8a", binary).getOrThrow()
        assertEquals(sha, artifact.sha256)

        // Truncated binary must NEVER pass (audit: no truncated binary may be installed)
        assertTrue(
            "truncated binary must be refused",
            CoreUpdateVerifier.verifyBinary(manifest, "arm64-v8a", binary.copyOf(binary.size - 1)).isFailure
        )
        // One flipped byte must be caught by sha256
        val flipped = binary.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertTrue(CoreUpdateVerifier.verifyBinary(manifest, "arm64-v8a", flipped).isFailure)
    }

    // ---------------------------------------------------------------------
    // End-to-end: manifest → sign → verify → integrity (the release pipeline shape)
    // ---------------------------------------------------------------------

    @Test
    fun `full chain mirrors the release pipeline and catches any tampering`() {
        val binary = ByteArray(1024) { (it % 256).toByte() }
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(binary)
            .joinToString("") { "%02x".format(it) }
        val manifestBytes = buildManifest(sha, binary.size.toLong())
        val signature = sign(manifestBytes)

        // 1. signature
        CoreUpdateVerifier.verifySignature(manifestBytes, signature, testPublicKeyBase64).getOrThrow()
        // 2. parse
        val manifest = CoreUpdateVerifier.parseManifest(manifestBytes).getOrThrow()
        // 3. tag
        CoreUpdateVerifier.checkManifestVersion(manifest, "core-v1.19.33-mihomet.4").getOrThrow()
        // 4. integrity
        val artifact = CoreUpdateVerifier.verifyBinary(manifest, "arm64-v8a", binary).getOrThrow()
        assertEquals("libmihomo-arm64-v8a.so", artifact.file)

        // Any byte flip in the manifest must break the signature (the sha256 inside
        // it is then untrusted and the update is refused before download).
        val tamperedManifest = manifestBytes.copyOf().also { it[10] = 'x'.code.toByte() }
        assertTrue(
            CoreUpdateVerifier.verifySignature(tamperedManifest, signature, testPublicKeyBase64).isFailure
        )
    }

    @Test
    fun `openssl-style raw signature format is accepted`() {
        // scripts/sign-core-manifest.sh emits RAW PKCS#1 v1.5 signature bytes
        // (openssl dgst -sha256 -sign); java.security.Signature.sign() produces the
        // same encoding — this test documents the contract.
        val manifest = "{\"schema\":1}".toByteArray()
        val raw = sign(manifest)
        assertEquals(256, raw.size) // 2048-bit key → 256-byte raw signature
        assertTrue(CoreUpdateVerifier.verifySignature(manifest, raw, testPublicKeyBase64).isSuccess)
    }
}
