package org.mihomet.app

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.data.crypto.SecretsCipher
import org.mihomet.app.data.preferences.SettingsRepository
import java.security.Key
import javax.crypto.KeyGenerator

/**
 * External audit F15b — at-rest encryption of the Happ crypto keyring.
 *
 * [SettingsRepository.setCryptoKey] persists the keyring JSON through
 * [SettingsRepository.encodeCryptoKeys] (every value → `enc1:` ciphertext via
 * [SecretsCipher.encrypt]); [SettingsRepository.getCryptoKeys] /
 * `cryptoKeysFlow` read it back through [SettingsRepository.decodeCryptoKeys]
 * ([SecretsCipher.decrypt], undecryptable entries skipped with a log).
 *
 * NOTE on the Android facade: on the JVM the AndroidKeyStore is unavailable, so
 * `SecretsCipher.encrypt/decrypt` (the object facade) degrade to their documented
 * passthrough — only that passthrough path of the facade is verifiable here
 * (same precedent as the SecretsCipher facade behavior). The REAL Keystore-backed
 * round trip (value stored as `enc1:` by the facade) is covered below with a plain
 * JVM AES key via [SecretsCipher.withKey], which exercises the identical
 * encrypt/decrypt engine the facade delegates to on a device.
 */
class CryptoKeyringCodecTest {

    private val keyring = mapOf(
        "key01" to "key01:abcDEF45gh",
        "key02" to "key02:+]%4ij#P\"/"
    )

    private fun freshKey(): Key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun encryptFn(key: Key): (String) -> String = { plain -> SecretsCipher.withKey(key).encrypt(plain) }

    private fun decryptFn(key: Key): (String) -> String = { stored -> SecretsCipher.withKey(key).decrypt(stored) }

    // ---------------------------------------------------------------
    // Keystore-equivalent round trip (pure engine, JVM AES key)
    // ---------------------------------------------------------------

    @Test
    fun `round trip - write path stores enc1 values, read path restores the plaintext keys`() {
        val key = freshKey()

        // setCryptoKey write path: raw stored map → encode (encrypt) → JSON on disk.
        val storedJson = Json.encodeToString(SettingsRepository.encodeCryptoKeys(keyring, encryptFn(key)))

        // Every at-rest value is ciphertext under the `enc1:` marker.
        val atRest = Json.decodeFromString<Map<String, String>>(storedJson)
        assertEquals("The keyring JSON keeps its {id: value} shape", keyring.keys, atRest.keys)
        assertTrue(
            "Every stored value must carry the enc1: marker",
            atRest.values.all { it.startsWith(SecretsCipher.MARKER) }
        )
        assertFalse(
            "The raw AES key must never appear in the stored JSON",
            atRest.values.any { value -> keyring.values.any { value.contains(it) } }
        )

        // getCryptoKeys read path: JSON → decode (decrypt) → plaintext view.
        val restored = SettingsRepository.decodeCryptoKeys(atRest, decryptFn(key))
        assertEquals(keyring, restored)
    }

    @Test
    fun `encode is idempotent - already marked values are never double-encrypted`() {
        val key = freshKey()
        val c = SecretsCipher.withKey(key)
        val alreadyEncrypted = c.encrypt("key01:abcDEF45gh")
        val encoded = SettingsRepository.encodeCryptoKeys(
            mapOf("key01" to alreadyEncrypted, "key02" to "plain-key"),
            encryptFn(key)
        )
        assertEquals("Marker value must pass through unchanged", alreadyEncrypted, encoded["key01"])
        assertTrue(encoded["key02"]!!.startsWith(SecretsCipher.MARKER))
        assertEquals("key01:abcDEF45gh", c.decrypt(encoded["key01"]!!))
    }

    @Test
    fun `decode skips an undecryptable entry and keeps the rest of the keyring`() {
        val key = freshKey()
        // A payload carrying the marker but encrypted under a DIFFERENT key: the
        // facade's decrypt (unlike the pure engine) throws SecretDecryptException
        // for it — mirror that production seam here.
        val foreignCiphertext = SecretsCipher.withKey(freshKey()).encrypt("lost-key-value")
        val decryptFnThrowing: (String) -> String = { stored ->
            if (stored == foreignCiphertext) {
                throw SecretsCipher.SecretDecryptException("Stored secret is undecryptable (key replaced or lost)")
            }
            decryptFn(key)(stored)
        }

        val stored = mapOf(
            "lost" to foreignCiphertext,
            "alive" to SecretsCipher.withKey(key).encrypt("alive-key-value")
        )

        val decoded = SettingsRepository.decodeCryptoKeys(stored, decryptFnThrowing)
        assertFalse("Undecryptable entry must be skipped, not surfaced as ciphertext", decoded.containsKey("lost"))
        assertEquals("Undamaged entries must still decrypt", "alive-key-value", decoded["alive"])
    }

    @Test
    fun `decode passes legacy plaintext through so existing subscriptions keep working`() {
        val key = freshKey()
        val stored = mapOf(
            "legacy" to "legacy-plain-key",   // pre-F15b plaintext value
            "modern" to SecretsCipher.withKey(key).encrypt("modern-key-value")
        )
        val decoded = SettingsRepository.decodeCryptoKeys(stored, decryptFn(key))
        assertEquals("Legacy plaintext must pass through (logged, re-encrypted on next write)", "legacy-plain-key", decoded["legacy"])
        assertEquals("modern-key-value", decoded["modern"])
    }

    @Test
    fun `corrupted json degrades to an empty keyring`() {
        // parseCryptoKeysJsonRaw's contract, mirrored with the same Json instance.
        val parsed = runCatching {
            Json.decodeFromString<Map<String, String>>("not-json{{{")
        }.getOrElse { emptyMap() }
        assertTrue(parsed.isEmpty())
        assertEquals(emptyMap<String, String>(), SettingsRepository.decodeCryptoKeys(parsed, decryptFn(freshKey())))
    }

    // ---------------------------------------------------------------
    // Facade passthrough path (what actually runs on the JVM / when the
    // Keystore is unavailable): values without the marker pass through both
    // directions, so setCryptoKey → getCryptoKeys still round-trips.
    // ---------------------------------------------------------------

    @Test
    fun `facade passthrough round trip - setCryptoKey then getCryptoKeys mirror on JVM`() {
        // Production wiring is SecretsCipher::encrypt / SecretsCipher::decrypt.
        val storedJson = Json.encodeToString(SettingsRepository.encodeCryptoKeys(keyring, SecretsCipher::encrypt))
        val restored = SettingsRepository.decodeCryptoKeys(
            Json.decodeFromString<Map<String, String>>(storedJson),
            SecretsCipher::decrypt
        )
        assertEquals(
            "Without a Keystore key the facade degrades to passthrough; the keyring must still round-trip",
            keyring,
            restored
        )
        val atRest = Json.decodeFromString<Map<String, String>>(storedJson)
        assertFalse(
            "Passthrough mode never yields the marker (documented degradation)",
            atRest.values.any { it.startsWith(SecretsCipher.MARKER) }
        )
    }
}
