package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mihomet.app.data.crypto.SecretsCipher
import org.mihomet.app.data.preferences.SettingsRepository
import java.io.IOException
import javax.crypto.KeyGenerator

/**
 * Decision table for the SecretsCipher key gate (external audit F12-а, extended
 * by N07).
 *
 * The AndroidKeyStore alias must never be overwritten speculatively:
 * generating a new key destroys all data encrypted under the previous one.
 * [SecretsCipher.decideGenerate] therefore fails closed in the two loss
 * scenarios and allows generation only on a confirmed fresh install (or when
 * an existing readable key is simply reused).
 *
 * The N07 extensions covered here:
 * - a readable key is reused WITHOUT consulting the gate (N07-3) — a failing
 *   gate probe must never disable a working key;
 * - the gate wrapper ([SecretsCipher.gateHasEncryptedData]) is conservative:
 *   a gate error counts as "encrypted data exists", never as absence (N07-1);
 * - the wide gate probe core
 *   [SettingsRepository.hasEncryptedSecrets] (controller secret + Room
 *   url/customHeaders + Happ keyring, N07-1);
 * - the facade contracts that keep the documented degradation intact on a
 *   Keystore-less JVM: encrypt passthrough, decrypt throws.
 */
class SecretsCipherGateTest {

    private fun freshKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    // ---------------------------------------------------------------
    // decideGenerate — the pure fail-closed table (F12-а)
    // ---------------------------------------------------------------

    @Test
    fun `existing readable alias is reused - safe to proceed`() {
        // Data may or may not exist — either way the readable key is reused,
        // nothing is generated over it.
        assertTrue(SecretsCipher.decideGenerate(hasAlias = true, keyReadable = true, hasEncryptedData = true))
        assertTrue(SecretsCipher.decideGenerate(hasAlias = true, keyReadable = true, hasEncryptedData = false))
    }

    @Test
    fun `existing unreadable alias fails closed - transient keystore failure`() {
        // The alias points at a key that cannot be loaded right now; regenerating
        // would overwrite it (AOSP replaces the alias) and destroy the data.
        assertFalse(SecretsCipher.decideGenerate(hasAlias = true, keyReadable = false, hasEncryptedData = true))
        assertFalse(SecretsCipher.decideGenerate(hasAlias = true, keyReadable = false, hasEncryptedData = false))
    }

    @Test
    fun `no alias but encrypted data exists fails closed - key already lost`() {
        // Generating a fresh key could never decrypt the old data and would
        // silently cement the loss; callers must surface the failure instead.
        assertFalse(SecretsCipher.decideGenerate(hasAlias = false, keyReadable = false, hasEncryptedData = true))
    }

    @Test
    fun `no alias and no encrypted data - confirmed fresh install may generate`() {
        assertTrue(SecretsCipher.decideGenerate(hasAlias = false, keyReadable = false, hasEncryptedData = false))
    }

    // ---------------------------------------------------------------
    // decideKeyResolution — the pure resolution flow (N07-3)
    // ---------------------------------------------------------------

    @Test
    fun `readable key is reused and the gate is never consulted`() {
        val key = freshKey()
        var gateCalls = 0
        val resolution = SecretsCipher.decideKeyResolution(
            readKey = { key },
            hasAlias = { true },
            generateKey = { throw AssertionError("generation must not run when the key is readable") },
            hasEncryptedData = { gateCalls++; true }
        )
        assertTrue(resolution is SecretsCipher.KeyResolution.Available)
        assertSame(key, (resolution as SecretsCipher.KeyResolution.Available).key)
        assertEquals(
            "The data-loss gate must not be consulted when the existing key is readable (audit N07-3)",
            0,
            gateCalls
        )
    }

    @Test
    fun `alias exists but key unreadable - refuses to generate`() {
        val resolution = SecretsCipher.decideKeyResolution(
            readKey = { null },
            hasAlias = { true },
            generateKey = { throw AssertionError("generation must never overwrite an existing alias") },
            hasEncryptedData = { false }
        )
        assertTrue(resolution is SecretsCipher.KeyResolution.RefusedByGate)
    }

    @Test
    fun `no alias but encrypted data exists - refuses to generate`() {
        val resolution = SecretsCipher.decideKeyResolution(
            readKey = { null },
            hasAlias = { false },
            generateKey = { throw AssertionError("generation must not run over existing encrypted data") },
            hasEncryptedData = { true }
        )
        assertTrue(resolution is SecretsCipher.KeyResolution.RefusedByGate)
    }

    @Test
    fun `no alias and no encrypted data - confirmed fresh install generates`() {
        val key = freshKey()
        val resolution = SecretsCipher.decideKeyResolution(
            readKey = { null },
            hasAlias = { false },
            generateKey = { key },
            hasEncryptedData = { false }
        )
        assertTrue(resolution is SecretsCipher.KeyResolution.Available)
        assertSame(key, (resolution as SecretsCipher.KeyResolution.Available).key)
    }

    @Test
    fun `keystore operation failure degrades to Unavailable - not to a refusal`() {
        // The distinction matters: RefusedByGate makes encrypt throw (data may
        // exist), Unavailable keeps the documented passthrough alive.
        val resolution = SecretsCipher.decideKeyResolution(
            readKey = { throw IOException("keystore down") },
            hasAlias = { true },
            generateKey = { throw AssertionError() },
            hasEncryptedData = { true }
        )
        assertTrue(resolution is SecretsCipher.KeyResolution.Unavailable)
    }

    // ---------------------------------------------------------------
    // gateHasEncryptedData — the conservative gate wrapper (N07-1)
    // ---------------------------------------------------------------

    @Test
    fun `gate wrapper - a failing gate counts as encrypted data, never as absence`() {
        val original = SecretsCipher.keyGate
        try {
            SecretsCipher.keyGate = { throw IOException("probe failed") }
            assertTrue(
                "A gate error must read as 'data exists' (conservative, audit N07-1)",
                SecretsCipher.gateHasEncryptedData()
            )
            SecretsCipher.keyGate = { true }
            assertTrue(SecretsCipher.gateHasEncryptedData())
            SecretsCipher.keyGate = { false }
            assertFalse(SecretsCipher.gateHasEncryptedData())
            SecretsCipher.keyGate = null
            assertFalse("Unwired gate defaults to fresh-install semantics", SecretsCipher.gateHasEncryptedData())
        } finally {
            SecretsCipher.keyGate = original
        }
    }

    // ---------------------------------------------------------------
    // hasEncryptedSecrets — the wide gate probe core (N07-1)
    // ---------------------------------------------------------------

    private val marker = SecretsCipher.MARKER

    @Test
    fun `hasEncryptedSecrets - controller secret carrying the marker is enough`() {
        assertTrue(
            SettingsRepository.hasEncryptedSecrets(
                datastoreSecret = "${marker}ciphertext",
                roomRows = emptyList(),
                keyringValues = emptyList()
            )
        )
    }

    @Test
    fun `hasEncryptedSecrets - an enc1 profile url counts even when the controller secret is plaintext`() {
        // The regression from the audit probe: Room had enc1:, the controller
        // secret was plaintext, the narrow gate read "no data" and the key was
        // regenerated over the Room data.
        assertTrue(
            SettingsRepository.hasEncryptedSecrets(
                datastoreSecret = "0123456789abcdef",
                roomRows = listOf("https://panel.example.com/sub" to "{}", "${marker}ciphertext" to "{}"),
                keyringValues = emptyList()
            )
        )
    }

    @Test
    fun `hasEncryptedSecrets - an enc1 customHeaders value counts`() {
        assertTrue(
            SettingsRepository.hasEncryptedSecrets(
                datastoreSecret = null,
                roomRows = listOf("https://panel.example.com/sub" to "${marker}ciphertext"),
                keyringValues = emptyList()
            )
        )
    }

    @Test
    fun `hasEncryptedSecrets - an enc1 keyring value counts`() {
        assertTrue(
            SettingsRepository.hasEncryptedSecrets(
                datastoreSecret = "",
                roomRows = emptyList(),
                keyringValues = listOf("legacy-plain-key", "${marker}ciphertext")
            )
        )
    }

    @Test
    fun `hasEncryptedSecrets - clean install without any marker is false`() {
        assertFalse(
            SettingsRepository.hasEncryptedSecrets(
                datastoreSecret = "0123456789abcdef",
                roomRows = listOf("https://panel.example.com/sub" to "{}", "" to ""),
                keyringValues = listOf("legacy-plain-key")
            )
        )
        assertFalse(
            SettingsRepository.hasEncryptedSecrets(
                datastoreSecret = null,
                roomRows = emptyList(),
                keyringValues = emptyList()
            )
        )
    }

    @Test
    fun `hasEncryptedSecrets - the marker must be a prefix, not a substring`() {
        // A URL that merely CONTAINS "enc1:" somewhere is not an encrypted value.
        assertFalse(
            SettingsRepository.hasEncryptedSecrets(
                datastoreSecret = null,
                roomRows = listOf("https://panel.example.com/enc1:mimic" to "{}"),
                keyringValues = emptyList()
            )
        )
        assertNotEquals("enc1: inside a value", true, "x${marker}y".startsWith(marker))
    }

    // ---------------------------------------------------------------
    // Facade contracts on a Keystore-less JVM (documented degradation)
    // ---------------------------------------------------------------

    @Test
    fun `facade on JVM - encrypt with an unavailable keystore degrades to passthrough`() {
        val original = SecretsCipher.keyGate
        SecretsCipher.keyGate = null
        try {
            val stored = SecretsCipher.encrypt("plain-value")
            assertEquals("Keystore unavailable wholesale keeps the documented passthrough", "plain-value", stored)
            assertFalse(stored.startsWith(SecretsCipher.MARKER))
        } finally {
            SecretsCipher.keyGate = original
        }
    }

    @Test
    fun `facade on JVM - decrypt of a marker value without a keystore throws`() {
        // The payload exists but is unusable — never returned as a working secret.
        try {
            SecretsCipher.decrypt("${marker}AAAA")
            fail("Expected SecretDecryptException")
        } catch (expected: SecretsCipher.SecretDecryptException) {
            // expected
        }
    }
}
