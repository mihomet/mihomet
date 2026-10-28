package org.mihomet.app.core

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import java.util.Locale

/**
 * Stable per-device identifier for subscription panels that require device binding.
 * The Happ/Incy ecosystem sends it as the `x-hwid` request header on every
 * subscription fetch; the value must be alphanumeric and 10..64 characters long.
 *
 * Derived as SHA-256 over [Settings.Secure.ANDROID_ID] + [Build.MANUFACTURER] +
 * [Build.MODEL], truncated to the first 32 lowercase hex characters — deterministic
 * per device, never contains a raw hardware identifier.
 */
object HwidProvider {

    /** Response/request header name used by Happ/Incy panels. */
    const val HWID_HEADER: String = "x-hwid"

    private const val HWID_LENGTH = 32

    /**
     * Pure hashing core (JVM-testable): SHA-256 over the parts joined with NUL
     * separators, rendered as lowercase hex and truncated to [HWID_LENGTH] chars.
     */
    fun compute(vararg parts: String): String {
        val joined = parts.joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(joined.toByteArray(Charsets.UTF_8))
        return buildString(capacity = HWID_LENGTH) {
            for (byte in digest) {
                append(String.format(Locale.ROOT, "%02x", byte))
            }
        }.take(HWID_LENGTH)
    }

    /** Device-bound hwid for the given context (ANDROID_ID is per-app-signing stable). */
    fun get(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
        return compute(androidId, Build.MANUFACTURER, Build.MODEL)
    }
}
