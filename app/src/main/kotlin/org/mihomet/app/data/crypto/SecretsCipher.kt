package org.mihomet.app.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.Key
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher as PlatformCipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * At-rest encryption for locally persisted secrets (the controller secret in
 * DataStore, subscription URLs and custom headers in Room).
 *
 * Structure:
 * - [Cipher] is the pure AES/GCM/NoPadding engine bound to a caller-provided
 *   [Key] — JVM-testable with a plain `javax.crypto.SecretKey`, no Android
 *   dependencies beyond the `Log.w` on the decrypt-error path;
 * - the object-level facade uses a lazily generated AndroidKeyStore key and
 *   degrades to a passthrough when the Keystore is unavailable, so a missing
 *   hardware-backed key can never make the app fail to read its own data.
 *
 * Wire format: `MARKER + base64(IV(12) ‖ ciphertext ‖ GCM tag)`. Values without
 * [MARKER] are treated as legacy plaintext / not-a-secret and pass through
 * unchanged — [decrypt] never throws.
 */
object SecretsCipher {

    /** Prefix marking a value as encrypted. */
    const val MARKER = "enc1:"

    private const val TAG = "SecretsCipher"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "mihomet_secrets"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_SIZE_BYTES = 12
    private const val KEY_SIZE_BITS = 256

    /**
     * Pure AES/GCM engine over [key]. Each [encrypt] call draws a fresh 12-byte
     * [SecureRandom] IV (GCM forbids IV reuse under the same key).
     */
    class Cipher(private val key: Key) {

        private val random = SecureRandom()

        /**
         * Encrypts [plain]; returns `MARKER + base64(IV ‖ ciphertext+tag)`.
         */
        fun encrypt(plain: String, key: Key = this.key): String {
            val iv = ByteArray(IV_SIZE_BYTES).also(random::nextBytes)
            val cipher = PlatformCipher.getInstance(TRANSFORMATION)
            cipher.init(PlatformCipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            return MARKER + Base64.getEncoder().encodeToString(iv + ciphertext)
        }

        /**
         * Decrypts a value produced by [encrypt]. Values not starting with
         * [MARKER] are returned as-is (legacy plaintext / not a secret). Any
         * failure (corrupted payload, wrong key, ...) is logged and the input
         * is returned unchanged — a read must never crash the app.
         */
        fun decrypt(stored: String, key: Key = this.key): String {
            if (!stored.startsWith(MARKER)) return stored
            return runCatching {
                val payload = Base64.getDecoder().decode(stored.removePrefix(MARKER))
                require(payload.size > IV_SIZE_BYTES) { "Encrypted payload too short" }
                val iv = payload.copyOfRange(0, IV_SIZE_BYTES)
                val ciphertext = payload.copyOfRange(IV_SIZE_BYTES, payload.size)
                val cipher = PlatformCipher.getInstance(TRANSFORMATION)
                cipher.init(PlatformCipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            }.getOrElse { error ->
                Log.w(TAG, "SecretsCipher: decrypt failed; returning the stored value as-is", error)
                stored
            }
        }
    }

    /** Creates a pure [Cipher] over [key] (AES/GCM/NoPadding). */
    fun withKey(key: Key): Cipher = Cipher(key)

    // region Android facade (AndroidKeyStore)

    /**
     * Thrown when a value carries the encrypted marker but cannot be decrypted
     * (the Keystore key is missing, was replaced, or the Keystore failed).
     * Callers must surface this — the raw `enc1:` payload is NOT a usable secret.
     */
    class SecretDecryptException(message: String, cause: Throwable? = null) :
        IllegalStateException(message, cause)

    /**
     * AndroidKeyStore AES-256 key.
     *
     * Lifecycle: an existing key under [KEY_ALIAS] is loaded and REUSED; a new key
     * is generated only on a confirmed first initialization. Generating on every
     * process start would overwrite the alias (AOSP behavior) and permanently
     * destroy previously encrypted data. When the Keystore is temporarily
     * unavailable, the result is `null` and callers degrade to passthrough —
     * the alias is never overwritten speculatively.
     */
    private val keystoreKey: Key? by lazy {
        runCatching {
            val keyStore = java.security.KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            (keyStore.getKey(KEY_ALIAS, null) as? javax.crypto.SecretKey)
                ?: run {
                    val generator = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
                    generator.init(
                        KeyGenParameterSpec.Builder(
                            KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                        )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(KEY_SIZE_BITS)
                            // The engine supplies its own fresh IV per call, so the
                            // Keystore-mandated random IV must be disabled.
                            .setRandomizedEncryptionRequired(false)
                            .build()
                    )
                    generator.generateKey()
                }
        }.getOrElse { error ->
            Log.w(TAG, "AndroidKeyStore unavailable; secrets will be stored unencrypted", error)
            null
        }
    }

    /** Encrypts [plain] with the Keystore key; passthrough when unavailable. */
    fun encrypt(plain: String): String {
        val key = keystoreKey ?: return plain
        return withKey(key).encrypt(plain)
    }

    /**
     * Decrypts a value produced by [encrypt].
     *
     * - Values without the [MARKER] (legacy plaintext, not a secret) are returned as-is.
     * - Marker present and decryption succeeds → plaintext.
     * - Marker present but decryption fails → [SecretDecryptException]: the payload
     *   exists but is unusable, and callers must surface it instead of treating the
     *   ciphertext as a working secret.
     */
    fun decrypt(stored: String): String {
        if (!stored.startsWith(MARKER)) return stored
        val key = keystoreKey
            ?: throw SecretDecryptException("Keystore unavailable: cannot decrypt the stored secret")
        return try {
            val payload = Base64.getDecoder().decode(stored.removePrefix(MARKER))
            require(payload.size > IV_SIZE_BYTES) { "Encrypted payload too short" }
            val iv = payload.copyOfRange(0, IV_SIZE_BYTES)
            val ciphertext = payload.copyOfRange(IV_SIZE_BYTES, payload.size)
            val cipher = PlatformCipher.getInstance(TRANSFORMATION)
            cipher.init(PlatformCipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (error: Exception) {
            throw SecretDecryptException(
                "Stored secret is undecryptable (key replaced or lost)", error
            )
        }
    }

    // endregion

    /**
     * Persistence helper: encrypts [value] for at-rest storage, leaving blank
     * values and values already carrying [MARKER] unchanged (idempotent).
     * The [encryptFn] parameter is the JVM-testable seam; production callers
     * use the Keystore-backed overload below.
     */
    fun encryptIfSecret(value: String, encryptFn: (String) -> String): String =
        if (value.isBlank() || value.startsWith(MARKER)) value else encryptFn(value)

    /** Encrypts [value] with the Keystore key unless it is blank / already encrypted. */
    fun encryptIfSecret(value: String): String = encryptIfSecret(value, ::encrypt)
}
