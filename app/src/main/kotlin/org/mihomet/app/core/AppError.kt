package org.mihomet.app.core

import org.mihomet.app.R

/**
 * Locale-neutral codes for fail-closed failures that used to carry free-form
 * Russian messages (external audit P2.9 tail): producers throw [AppException]
 * with data-only parameters, the display layer localizes via [stringRes] at
 * render time. The exception message itself stays ASCII (`CODE: detail`) so
 * logcat/report output never leaks UI-language text.
 */
enum class AppErrorCode(
    /** strings.xml key backing this code — asserted to exist in BOTH locales by AppErrorResourcesTest. */
    val resKeyName: String
) {
    PROFILE_YAML_EMPTY("profiles_err_yaml_empty"),
    PROFILE_YAML_INVALID("profiles_err_yaml_invalid"),
    SUBSCRIPTION_UNDECRYPTABLE("profiles_subscription_undecryptable"),
    SUBSCRIPTION_ENCRYPT_FAILED("profiles_encrypt_failed"),
    REFRESH_REJECTED_INVALID_CONFIG("profiles_err_refresh_rejected"),
    ENC_SUB_NO_KEY("profiles_err_enc_sub_no_key"),
    ENC_SUB_UNKNOWN_KEY("profiles_err_enc_sub_unknown_key"),
    ENC_SUB_DECRYPT_FAILED("profiles_err_enc_sub_decrypt_failed")
}

/**
 * Fail-closed error carrying a typed [code] plus optional positional [args]
 * (e.g. the failing key id, the YAML parser detail) consumed by the localized
 * format string. Not user-visible text — the display layer formats it.
 */
class AppException(
    val code: AppErrorCode,
    vararg val args: String,
    cause: Throwable? = null
) : IllegalStateException(
    (listOf(code.name) + args).joinToString(": "),
    cause
)

/** Resource id for a code; args are formatted by the caller via Context.getString(res, *args). */
fun AppErrorCode.stringRes(): Int = when (this) {
    AppErrorCode.PROFILE_YAML_EMPTY -> R.string.profiles_err_yaml_empty
    AppErrorCode.PROFILE_YAML_INVALID -> R.string.profiles_err_yaml_invalid
    AppErrorCode.SUBSCRIPTION_UNDECRYPTABLE -> R.string.profiles_subscription_undecryptable
    AppErrorCode.SUBSCRIPTION_ENCRYPT_FAILED -> R.string.profiles_encrypt_failed
    AppErrorCode.REFRESH_REJECTED_INVALID_CONFIG -> R.string.profiles_err_refresh_rejected
    AppErrorCode.ENC_SUB_NO_KEY -> R.string.profiles_err_enc_sub_no_key
    AppErrorCode.ENC_SUB_UNKNOWN_KEY -> R.string.profiles_err_enc_sub_unknown_key
    AppErrorCode.ENC_SUB_DECRYPT_FAILED -> R.string.profiles_err_enc_sub_decrypt_failed
}
