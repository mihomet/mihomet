package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.data.crypto.SecretsCipher
import java.security.Key
import java.util.Base64
import javax.crypto.KeyGenerator

/**
 * Tests for the pure [SecretsCipher.Cipher] core (AES-256-GCM, fresh IV per
 * call, `enc1:` marker format). The key here is a plain JVM-generated
 * `javax.crypto.SecretKey` — no AndroidKeyStore involved.
 */
class SecretsCipherTest {

    private fun freshKey(): Key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun cipher(key: Key = freshKey()) = SecretsCipher.withKey(key)

    private fun ivOf(encrypted: String): ByteArray =
        Base64.getDecoder().decode(encrypted.removePrefix(SecretsCipher.MARKER)).copyOfRange(0, 12)

    @Test
    fun `round trip - encrypt then decrypt returns the plaintext`() {
        val secret = "https://panel.example.com/sub?token=s3cr3t&t=1"
        val c = cipher()
        assertEquals(secret, c.decrypt(c.encrypt(secret)))
    }

    @Test
    fun `round trip - non-ascii plaintext survives`() {
        val secret = "Подписка ✈ ключ: пароль"
        val c = cipher()
        assertEquals(secret, c.decrypt(c.encrypt(secret)))
    }

    @Test
    fun `encrypted value carries the marker and base64 payload with a 12-byte IV`() {
        val encrypted = cipher().encrypt("plain")
        assertTrue(encrypted.startsWith(SecretsCipher.MARKER))
        assertEquals("enc1:", SecretsCipher.MARKER)
        val payload = Base64.getDecoder().decode(encrypted.removePrefix(SecretsCipher.MARKER))
        // IV (12) + ciphertext + GCM tag (16)
        assertTrue(payload.size > 12 + 16)
        assertEquals(12, ivOf(encrypted).size)
    }

    @Test
    fun `legacy plaintext without the marker passes through decrypt unchanged`() {
        val c = cipher()
        val legacy = "https://panel.example.com/sub?token=x"
        assertEquals(legacy, c.decrypt(legacy))
        assertEquals("{}", c.decrypt("{}"))
        assertEquals("", c.decrypt(""))
    }

    @Test
    fun `every call uses a unique IV - two encryptions of the same plaintext differ`() {
        val c = cipher()
        val first = c.encrypt("same-plain")
        val second = c.encrypt("same-plain")
        assertNotEquals(first, second)
        assertFalse(ivOf(first).contentEquals(ivOf(second)))
        assertEquals("same-plain", c.decrypt(first))
        assertEquals("same-plain", c.decrypt(second))
    }

    @Test
    fun `corrupted ciphertext degrades to passthrough instead of throwing`() {
        val c = cipher()
        val encrypted = c.encrypt("secret")
        val corrupted = encrypted.dropLast(4) + "AAAA" // flip the tail (tag) bits
        assertEquals(corrupted, c.decrypt(corrupted))
    }

    @Test
    fun `malformed marker value degrades to passthrough instead of throwing`() {
        val c = cipher()
        assertEquals("enc1:not-base64!!!", c.decrypt("enc1:not-base64!!!"))
        assertEquals("enc1:", c.decrypt("enc1:"))
    }

    @Test
    fun `wrong key degrades to passthrough instead of throwing`() {
        val encrypted = cipher().encrypt("secret")
        assertEquals(encrypted, cipher().decrypt(encrypted))
    }

    @Test
    fun `encryptIfSecret - blanks and already-marked values stay unchanged`() {
        val c = cipher()
        val encrypted = c.encrypt("real-secret")
        assertEquals("", SecretsCipher.encryptIfSecret("") { c.encrypt(it) })
        assertEquals("   ", SecretsCipher.encryptIfSecret("   ") { c.encrypt(it) })
        assertEquals(encrypted, SecretsCipher.encryptIfSecret(encrypted) { c.encrypt(it) })
    }

    @Test
    fun `encryptIfSecret - plaintext values are marked and decrypt back`() {
        val c = cipher()
        val stored = SecretsCipher.encryptIfSecret("https://panel.example.com/sub") { c.encrypt(it) }
        assertTrue(stored.startsWith(SecretsCipher.MARKER))
        assertEquals("https://panel.example.com/sub", c.decrypt(stored))
    }
}
