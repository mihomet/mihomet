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
 * - the object-level facade resolves an AndroidKeyStore key (reuse-first, gated
 *   generation, see [decideKeyResolution]): a readable key is reused without
 *   consulting the data-loss gate (audit N07-3), generation is refused while
 *   `enc1:` data exists under a missing/unreadable key — [encrypt] then throws
 *   [SecretEncryptException] instead of silently stripping at-rest protection
 *   (audit N07-2) — and a Keystore unusable wholesale degrades to the documented
 *   passthrough, so a missing hardware-backed key can never make the app fail
 *   to read its own data.
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
     * Thrown by [encrypt] when a value could not be encrypted because the
     * Keystore key is missing or unreadable while encrypted data already exists
     * (the data-loss gate refused to (re)generate the key — audit N07-2).
     * Persisting the value would silently strip at-rest protection from a
     * secret the user believes is encrypted, so the write is refused; callers
     * must surface the failure instead of storing plaintext. The default
     * message is user-displayable (Russian, like the other data-layer errors)
     * because UI catch-alls surface it via `e.message`.
     */
    class SecretEncryptException(
        message: String = "Не удалось зашифровать данные — ключ шифрования недоступен. " +
            "Сохранение в открытом виде отклонено, чтобы не потерять секрет.",
        cause: Throwable? = null
    ) : IllegalStateException(message, cause)

    /**
     * Outcome of the AndroidKeyStore key resolution (audit N07). Distinguishes
     * the two `null`-key situations so [encrypt] can fail closed on one (data
     * exists — no plaintext writes) while keeping the documented passthrough
     * on the other (Keystore unusable wholesale).
     */
    internal sealed class KeyResolution {
        /** A usable key was loaded (or generated on a confirmed fresh install). */
        data class Available(val key: javax.crypto.SecretKey) : KeyResolution()

        /**
         * Generation was refused by the data-loss gate: an existing alias is
         * unreadable, or `enc1:` data exists while the alias is gone. The key
         * (and with it the encrypted data) is currently unusable — [encrypt]
         * throws [SecretEncryptException], [decrypt] throws
         * [SecretDecryptException].
         */
        data object RefusedByGate : KeyResolution()

        /**
         * The Keystore itself is unusable (load/get/containsAlias/generate
         * threw) — the documented passthrough degradation.
         */
        data class Unavailable(val error: Throwable) : KeyResolution()
    }

    /**
     * Data-loss gate (external audit F12-а, extended by N07-1): consulted by the
     * facade before it ever (re)generates the AndroidKeyStore key over a missing
     * or unreadable alias. Returns `true` when at least one `enc1:` value exists
     * on disk — i.e. secrets were encrypted under the (possibly missing) key and
     * the alias must never be overwritten. The production gate (see
     * [MihometApplication.onCreate]) is CONSERVATIVE on purpose and probes every
     * place an `enc1:` value can live:
     * - the controller secret (DataStore);
     * - every profile `url` / `customHeaders` (Room);
     * - every stored Happ crypto keyring value (DataStore JSON).
     *
     * A read error at ANY source counts as "data exists" — an error must never
     * read as absence of data, or a lost key would be regenerated over it. The
     * same policy is enforced defensively by [gateHasEncryptedData] even if the
     * wired gate throws.
     *
     * `null` (the default until the Application wires it up) is treated as
     * `false` → generation is allowed, which is the correct fresh-install
     * behavior. The gate must be cheap: it is only consulted when no readable
     * key exists under the alias, never on a healthy startup (a readable key is
     * reused without consulting the gate, see [decideKeyResolution]).
     */
    @Volatile
    var keyGate: (() -> Boolean)? = null

    /**
     * Pure decision table behind the gated generation (JVM-testable, external
     * audit F12-а): `true` → generating a key is safe, `false` → fail closed.
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
     *
     * Only consulted when no readable key exists (audit N07-3) — see
     * [decideKeyResolution].
     */
    internal fun decideGenerate(hasAlias: Boolean, keyReadable: Boolean, hasEncryptedData: Boolean): Boolean =
        if (hasAlias) keyReadable else !hasEncryptedData

    /**
     * Pure resolution flow behind [acquireKey] (JVM-testable, audit N07):
     * - a readable key is reused immediately and the data-loss gate is NOT
     *   consulted — a failing/slow gate must never disable a working key
     *   (N07-3);
     * - an existing-but-unreadable alias, or a missing alias while
     *   [hasEncryptedData] reports existing `enc1:` data, refuses generation
     *   ([KeyResolution.RefusedByGate], the [decideGenerate] table, fail
     *   closed);
     * - only a confirmed fresh install generates ([KeyResolution.Available]).
     *
     * Any exception thrown by the Keystore operations themselves ([readKey],
     * [hasAlias], [generateKey]) degrades to [KeyResolution.Unavailable] — the
     * documented passthrough mode. The [hasEncryptedData] gate is expected to
     * be total (never throws); the production facade wraps it conservatively
     * via [gateHasEncryptedData].
     */
    internal fun decideKeyResolution(
        readKey: () -> javax.crypto.SecretKey?,
        hasAlias: () -> Boolean,
        generateKey: () -> javax.crypto.SecretKey,
        hasEncryptedData: () -> Boolean
    ): KeyResolution = try {
        val existing = readKey()
        if (existing != null) {
            KeyResolution.Available(existing)
        } else if (decideGenerate(hasAlias(), keyReadable = false, hasEncryptedData = hasEncryptedData())) {
            KeyResolution.Available(generateKey())
        } else {
            KeyResolution.RefusedByGate
        }
    } catch (error: Exception) {
        KeyResolution.Unavailable(error)
    }

    /** Successfully resolved Keystore key; `null` while unresolved. */
    @Volatile
    private var cachedKey: javax.crypto.SecretKey? = null

    /** Guards the double-checked [resolveKeyState] slow path. */
    private val keyLock = Any()

    /**
     * Evaluates [keyGate] defensively: a gate that fails (DataStore/Room/keyring
     * read error) must never read as "no encrypted data" (audit N07-1) — the
     * error is treated as "data exists" and generation is refused. The
     * production gate ([org.mihomet.app.data.preferences.SettingsRepository.hasEncryptedSecrets])
     * already applies this policy internally; this wrapper is the last line of
     * defense. Internal so the conservative contract stays JVM-testable.
     */
    internal fun gateHasEncryptedData(): Boolean =
        try {
            keyGate?.invoke() ?: false
        } catch (error: Exception) {
            Log.e(TAG, "SecretsCipher: keyGate failed; conservatively assuming encrypted data exists", error)
            true
        }

    /**
     * Resolves the AndroidKeyStore key state: reuses the key under [KEY_ALIAS],
     * or generates one — but only when [decideKeyResolution] confirms that
     * generation cannot destroy data. Unlike a `by lazy`, a refused/unavailable
     * outcome is deliberately NOT memoized: a transiently unavailable Keystore
     * or a fail-closed gate verdict retries on the next call instead of pinning
     * the degraded mode for the whole process. Only a successful resolution is
     * cached.
     *
     * Threading: safe from any thread. The slow path holds [keyLock] while it
     * consults [keyGate], which production wires to a conservative probe
     * (DataStore + Room + keyring) via `runBlocking` — quick in the common
     * case (DataStore in-memory after the first read), and never reached on a
     * healthy install where the alias already exists. Callers run on
     * Dispatchers.IO contexts; do not call this on the main thread with a
     * blocking gate wired.
     */
    private fun resolveKeyState(): KeyResolution {
        cachedKey?.let { return KeyResolution.Available(it) }
        return synchronized(keyLock) {
            cachedKey?.let { return KeyResolution.Available(it) }
            val resolved = acquireKey(::gateHasEncryptedData)
            (resolved as? KeyResolution.Available)?.let { cachedKey = it.key }
            resolved
        }
    }

    /**
     * Runs the Keystore I/O behind [decideKeyResolution]: loads the key store,
     * then reuses / refuses / generates per the decision table. Keystore-wide
     * failures (instance/load throwing) degrade to [KeyResolution.Unavailable]
     * — the passthrough mode; the resolution itself is logged there.
     */
    private fun acquireKey(hasEncryptedData: () -> Boolean): KeyResolution {
        val resolution = runCatching {
            val keyStore = java.security.KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            decideKeyResolution(
                readKey = { keyStore.getKey(KEY_ALIAS, null) as? javax.crypto.SecretKey },
                hasAlias = { keyStore.containsAlias(KEY_ALIAS) },
                generateKey = { generateKeystoreKey() },
                hasEncryptedData = hasEncryptedData
            )
        }.getOrElse { error ->
            KeyResolution.Unavailable(error)
        }
        if (resolution is KeyResolution.Unavailable) {
            Log.e(
                TAG,
                "SecretsCipher: AndroidKeyStore unavailable; secrets degrade to unencrypted passthrough (documented)",
                resolution.error
            )
        }
        return resolution
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

    /**
     * Encrypts [plain] with the Keystore key:
     * - key available → `MARKER + base64(IV ‖ ciphertext+tag)`;
     * - generation refused by the data-loss gate (encrypted data exists but the
     *   key is missing/unreadable) → [SecretEncryptException] — storing
     *   plaintext here would silently strip at-rest protection from a secret
     *   the user believes is encrypted (audit N07-2); callers must surface the
     *   failure instead of writing it;
     * - Keystore unusable wholesale → passthrough [plain] plus an ERROR log:
     *   the documented degradation that keeps the app functional on devices
     *   without a working Keystore.
     */
    fun encrypt(plain: String): String = when (val resolution = resolveKeyState()) {
        is KeyResolution.Available -> withKey(resolution.key).encrypt(plain)
        KeyResolution.RefusedByGate -> throw SecretEncryptException()
        is KeyResolution.Unavailable -> {
            Log.e(
                TAG,
                "SecretsCipher: storing the value unencrypted (Keystore unavailable, documented degradation)",
                resolution.error
            )
            plain
        }
    }

    /**
     * Decrypts a value produced by [encrypt].
     *
     * - Values without the [MARKER] (legacy plaintext, not a secret) are returned as-is.
     * - Marker present and decryption succeeds → plaintext.
     * - Marker present but the key is missing/unreadable or the Keystore failed →
     *   [SecretDecryptException]: the payload exists but is unusable, and callers
     *   must surface it instead of treating the ciphertext as a working secret.
     * - Marker present but decryption fails → [SecretDecryptException].
     */
    fun decrypt(stored: String): String {
        if (!stored.startsWith(MARKER)) return stored
        val key = when (val resolution = resolveKeyState()) {
            is KeyResolution.Available -> resolution.key
            KeyResolution.RefusedByGate -> throw SecretDecryptException(
                "Keystore key is missing while encrypted data exists: cannot decrypt the stored secret"
            )
            is KeyResolution.Unavailable -> throw SecretDecryptException(
                "Keystore unavailable: cannot decrypt the stored secret", resolution.error
            )
        }
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

    /**
     * Encrypts [value] with the Keystore key unless it is blank / already
     * encrypted. Throws [SecretEncryptException] when the value must be
     * encrypted but the Keystore key is missing while encrypted data exists
     * (audit N07-2) — callers must surface the failure instead of persisting
     * the value in plaintext.
     */
    fun encryptIfSecret(value: String): String = encryptIfSecret(value, ::encrypt)
}
