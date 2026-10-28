package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mihomet.app.core.HappCrypto
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Tests for Happ encrypted subscription decryption (AES-128-GCM with the fixed
 * IV literal and the out-of-band GCM tag), per the Happ spec
 * (HappDev/happ_su dev-docs/encrypting-subscription-content.md).
 */
class HappCryptoTest {

    /** Test key from the Happ spec: key id "key02", exactly 16 ASCII bytes. */
    private val specKey: ByteArray = "key02:+]%4ij#P\"/".toByteArray(Charsets.UTF_8)

    /** Encrypt-side helper: AES-128-GCM, returns (bodyB64, tagB64) with the tag split off. */
    private fun encryptToParts(plain: String, key: ByteArray): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, HappCrypto.CRYPTO_IV.toByteArray(Charsets.US_ASCII))
        )
        val full = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val body = full.copyOfRange(0, full.size - GCM_TAG_BYTES)
        val tag = full.copyOfRange(full.size - GCM_TAG_BYTES, full.size)
        val enc = Base64.getEncoder()
        return enc.encodeToString(body) to enc.encodeToString(tag)
    }

    @Test
    fun `spec constants - IV is the fixed 12-byte literal and test key is 16 bytes`() {
        assertEquals("kkkkkkkkkkkk", HappCrypto.CRYPTO_IV)
        assertEquals(12, HappCrypto.CRYPTO_IV.toByteArray(Charsets.US_ASCII).size)
        assertEquals(16, specKey.size)
        assertEquals(16, HappCrypto.KEY_BYTES)
    }

    @Test
    fun `decrypt round-trips an encrypted subscription with the spec test key`() {
        val plain = "vless://3f6e1f2a-1111-4d3e-9f7b-6c9c1e2d3f4a@example.com:443?" +
            "security=tls&sni=cdn.example.com&type=ws#Node-01\n" +
            "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ@1.2.3.4:8388#Node-02"
        val (bodyB64, tagB64) = encryptToParts(plain, specKey)

        assertEquals(plain, HappCrypto.decrypt(bodyB64, tagB64, specKey))
    }

    @Test
    fun `decrypt tolerates whitespace in base64 body`() {
        val plain = "trojan://password@host:443#node"
        val (bodyB64, tagB64) = encryptToParts(plain, specKey)

        val wrapped = bodyB64.chunked(16).joinToString("\n") + "\n"
        assertEquals(plain, HappCrypto.decrypt(wrapped, tagB64, specKey))
    }

    @Test
    fun `decrypt returns null on a wrong key`() {
        val (bodyB64, tagB64) = encryptToParts("vless://a@b:443#x", specKey)
        val wrongKey = "another-key-16by".toByteArray(Charsets.UTF_8)

        assertNull(HappCrypto.decrypt(bodyB64, tagB64, wrongKey))
    }

    @Test
    fun `decrypt returns null on a corrupted tag or ciphertext`() {
        val (bodyB64, tagB64) = encryptToParts("vless://a@b:443#x", specKey)

        val corruptedTag = if (tagB64[0] == 'A') "B${tagB64.substring(1)}" else "A${tagB64.substring(1)}"
        assertNull(HappCrypto.decrypt(bodyB64, corruptedTag, specKey))

        val corruptedBody = if (bodyB64[0] == 'A') "B${bodyB64.substring(1)}" else "A${bodyB64.substring(1)}"
        assertNull(HappCrypto.decrypt(corruptedBody, tagB64, specKey))
    }

    @Test
    fun `decrypt returns null on invalid key length or empty tag`() {
        val (bodyB64, tagB64) = encryptToParts("vless://a@b:443#x", specKey)

        assertNull(HappCrypto.decrypt(bodyB64, tagB64, ByteArray(15)))
        assertNull(HappCrypto.decrypt(bodyB64, tagB64, ByteArray(32)))
        assertNull(HappCrypto.decrypt(bodyB64, "", specKey))
    }

    @Test
    fun `keyIdFromUrl extracts the key parameter`() {
        assertEquals("key02", HappCrypto.keyIdFromUrl("https://vpn.com/sub/kjdsfkWr5jewk3rjew?key=key02"))
        assertEquals("key03", HappCrypto.keyIdFromUrl("https://vpn.com/sub?foo=1&key=key03&x=2"))
        assertEquals("key42", HappCrypto.keyIdFromUrl("https://vpn.com/sub?key=key42#anchor"))
    }

    @Test
    fun `keyIdFromUrl is case-insensitive on parameter name and key prefix`() {
        assertEquals("Key07", HappCrypto.keyIdFromUrl("https://vpn.com/sub?KEY=Key07"))
        assertEquals("key08", HappCrypto.keyIdFromUrl("https://vpn.com/sub?Key=key08"))
    }

    @Test
    fun `keyIdFromUrl returns null when the parameter is absent or not a key id`() {
        assertNull(HappCrypto.keyIdFromUrl("https://vpn.com/sub"))
        assertNull(HappCrypto.keyIdFromUrl("https://vpn.com/sub?token=abc"))
        assertNull(HappCrypto.keyIdFromUrl("https://vpn.com/sub?foo=key01"))
        assertNull(HappCrypto.keyIdFromUrl("https://vpn.com/sub?key=abc"))
        assertNull(HappCrypto.keyIdFromUrl("https://vpn.com/sub?key="))
    }

    private companion object {
        const val GCM_TAG_BYTES = 16
    }
}
