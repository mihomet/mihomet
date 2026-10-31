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
 * - the object-level facade resolves an AndroidKeyStore key once per process
 *   (reuse-first, gated generation, see [decideGenerate]) and degrades to a
 *   passthrough when the Keystore is unavailable, so a missing hardware-backed
 *   key can never make the app fail to read its own data.
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
     * Data-loss gate (external audit F12-а): consulted by [resolveKey] before
     * the facade ever (re)generates the AndroidKeyStore key. Returns `true`
     * when at least one `enc1:` value exists on disk — i.e. secrets were
     * encrypted under the (possibly missing) key and the alias must never be
     * overwritten. The signal is the controller secret; a secret encrypted in
     * Room while the DataStore secret stayed plaintext degrades the gate to
     * "no data" — accepted limitation, the controller secret is the primary
     * at-rest secret in this app.
     *
     * `null` (the default until the Application wires it up) is treated as
     * `false` → generation is allowed, which is the correct fresh-install
     * behavior. The production gate is a single synchronous DataStore read
     * (see [MihometApplication.onCreate]); it must be cheap — it is only
     * consulted when the key alias is absent, never on a healthy startup.
     */
    @Volatile
    var keyGate: (() -> Boolean)? = null

    /**
     * Pure decision table behind [acquireKey] (JVM-testable, external audit
     * F12-а): `true` → generating a key is safe, `false` → fail closed.
     *
     * - alias exists + readable → `true` (the existing key is reused, nothing
     *   is generated);
     * - alias exists + unreadable → `false` (transient Keystore failure —
     *   regenerating would overwrite the alias and permanently destroy all
     *   data encrypted under it);
     * - no alias + encrypted data present → `false` (the key is already lost;
     *   a freshly generated one can never decrypt that data, and regenerating
     *   would silently cement the loss — callers must surface the failure);
     * - no alias + no encrypted data → `true` (confirmed first initialization).
     */
    internal fun decideGenerate(hasAlias: Boolean, keyReadable: Boolean, hasEncryptedData: Boolean): Boolean =
        if (hasAlias) keyReadable else !hasEncryptedData

    /** Successfully resolved Keystore key; `null` while unresolved. */
    @Volatile
    private var cachedKey: Key? = null

    /** Guards the double-checked [resolveKey] slow path. */
    private val keyLock = Any()

    /**
     * Resolves the AndroidKeyStore key: reuses the key under [KEY_ALIAS], or
     * generates one — but only when [decideGenerate] confirms that generation
     * cannot destroy data. Unlike the previous `by lazy` (external audit
     * F12-б), a `null` outcome is deliberately NOT memoized: a transiently
     * unavailable Keystore or a fail-closed gate verdict retries on the next
     * call instead of pinning the degraded passthrough mode for the whole
     * process. Only a successful resolution is cached.
     *
     * Threading: safe from any thread. The slow path holds [keyLock] while it
     * consults [keyGate], which production wires to a one-shot synchronous
     * DataStore read via `runBlocking` — quick (in-memory after the first
     * read), and never reached on a healthy install where the alias already
     * exists. Callers run on Dispatchers.IO contexts; do not call this on the
     * main thread with a blocking gate wired.
     */
    private fun resolveKey(): Key? {
        cachedKey?.let { return it }
        return synchronized(keyLock) {
            cachedKey?.let { return it }
            val resolved = acquireKey { keyGate?.invoke() ?: false }
            if (resolved != null) {
                cachedKey = resolved
            }
            resolved
        }
    }

    /**
     * Loads the key under [KEY_ALIAS], applying the [decideGenerate] table:
     * an existing-but-unreadable alias, or a missing alias while
     * [hasEncryptedData] reports existing `enc1:` data, fails closed (no
     * generation, no data destruction); a confirmed fresh install generates
     * the key. Keystore-wide failures (load/get/containsAlias throwing)
     * degrade to `null` — the passthrough mode.
     */
    private fun acquireKey(hasEncryptedData: () -> Boolean): Key? = runCatching {
        val keyStore = java.security.KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        val existing = keyStore.getKey(KEY_ALIAS, null) as? javax.crypto.SecretKey
        val hasAlias = existing != null || keyStore.containsAlias(KEY_ALIAS)
        if (!decideGenerate(hasAlias, existing != null, hasEncryptedData())) {
            return@runCatching null
        }
        existing ?: generateKeystoreKey()
    }.getOrElse { error ->
        Log.w(TAG, "AndroidKeyStore unavailable; secrets will be stored unencrypted", error)
        null
    }

    /** Generates a fresh AndroidKeyStore AES-256/GCM key under [KEY_ALIAS]. */
    private fun generateKeystoreKey(): javax.crypto.SecretKey {
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
        return generator.generateKey()
    }

    /** Encrypts [plain] with the Keystore key; passthrough when unavailable. */
    fun encrypt(plain: String): String {
        val key = resolveKey() ?: return plain
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
        val key = resolveKey()
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
