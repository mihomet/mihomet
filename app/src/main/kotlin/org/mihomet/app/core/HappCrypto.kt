package org.mihomet.app.core

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Happ encrypted subscription content (spec: HappDev/happ_su,
 * `dev-docs/encrypting-subscription-content.md`).
 *
 * The panel encrypts the subscription with **AES-128-GCM**:
 * - the response body is `base64(ciphertext)`;
 * - the GCM authentication tag is **not** appended to the ciphertext — it travels
 *   separately in the `encrypt-tag` response header (base64);
 * - the IV is the fixed 12-byte ASCII literal `kkkkkkkkkkkk`;
 * - the URL carries only the key *identifier* (`?key=keyNN`) and the key itself is
 *   distributed out-of-band: the user stores it in the client keyring
 *   ([org.mihomet.app.data.preferences.SettingsRepository]).
 */
object HappCrypto {

    /** Fixed GCM IV from the Happ spec: the ASCII literal "kkkkkkkkkkkk" (12 bytes). */
    const val CRYPTO_IV: String = "kkkkkkkkkkkk"

    /** HTTP response header carrying the Base64 GCM tag, separate from the body. */
    const val ENCRYPT_TAG_HEADER: String = "encrypt-tag"

    /** AES-128: the key must be exactly 16 bytes. */
    const val KEY_BYTES: Int = 16

    private const val GCM_TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** A user-supplied Happ crypto key: panel-assigned id plus the 16-byte ASCII key. */
    data class CryptoKey(val id: String, val key: String)

    /**
     * Decrypts a Happ encrypted subscription body.
     *
     * @param bodyB64 base64-encoded ciphertext (the raw response body)
     * @param tagB64  base64-encoded GCM tag from the `encrypt-tag` header
     * @param key     the AES key, exactly [KEY_BYTES] bytes
     * @return the decrypted plaintext (URI list / JSON), or `null` on any error
     *         (bad base64, wrong key length, authentication failure, corrupted data)
     */
    fun decrypt(bodyB64: String, tagB64: String, key: ByteArray): String? = runCatching {
        require(key.size == KEY_BYTES) { "Happ AES key must be exactly $KEY_BYTES bytes" }
        val ciphertext = decodeBase64(bodyB64)
        val tag = decodeBase64(tagB64)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, CRYPTO_IV.toByteArray(Charsets.US_ASCII))
        )
        // The tag is delivered out-of-band (encrypt-tag header), so it must be
        // appended to the ciphertext before doFinal verifies and strips it.
        String(cipher.doFinal(ciphertext + tag), Charsets.UTF_8)
    }.getOrNull()

    /**
     * Extracts the key identifier from the subscription URL's `key` query parameter.
     * Only values starting with "key" (case-insensitive) are treated as key ids
     * (e.g. `?key=key02` → `"key02"`). Returns `null` when there is no `key`
     * parameter or its value is not a key id.
     */
    fun keyIdFromUrl(url: String): String? {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return null
        val query = url.substring(queryStart + 1).substringBefore('#')
        if (query.isBlank()) return null
        return query.split('&')
            .filter { it.contains('=') }
            .firstOrNull { it.substringBefore('=').equals("key", ignoreCase = true) }
            ?.substringAfter('=')
            ?.takeIf { it.length >= 3 && it.startsWith("key", ignoreCase = true) }
    }

    /** Standard base64 with a URL-safe fallback; ASCII whitespace is stripped first (never part of the alphabet). */
    private fun decodeBase64(value: String): ByteArray {
        val clean = value.filterNot { it.isWhitespace() }
        return runCatching { Base64.getDecoder().decode(clean) }
            .getOrElse { Base64.getUrlDecoder().decode(clean) }
    }
}
