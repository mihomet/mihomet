package org.mihomet.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.data.crypto.SecretsCipher

/**
 * Decision table for the SecretsCipher key gate (external audit F12-а).
 *
 * The AndroidKeyStore alias must never be overwritten speculatively:
 * generating a new key destroys all data encrypted under the previous one.
 * [SecretsCipher.decideGenerate] therefore fails closed in the two loss
 * scenarios and allows generation only on a confirmed fresh install (or when
 * an existing readable key is simply reused).
 */
class SecretsCipherGateTest {

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
}
