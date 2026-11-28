package org.mihomet.app.core

import org.mihomet.app.R
import org.mihomet.app.core.config.ConversionReport
import org.mihomet.app.core.config.ConversionWarning

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
    IMPORT_NO_COMPATIBLE_CONFIG("profiles_invalid_result"),
    IMPORT_FAILED("conversion_report_failed"),
    SUBSCRIPTION_TOO_LARGE("profiles_too_large"),
    PROFILE_YAML_EMPTY("profiles_err_yaml_empty"),
    PROFILE_YAML_INVALID("profiles_err_yaml_invalid"),
    SUBSCRIPTION_UNDECRYPTABLE("profiles_subscription_undecryptable"),
    SUBSCRIPTION_ENCRYPT_FAILED("profiles_encrypt_failed"),
    REFRESH_REJECTED_INVALID_CONFIG("profiles_err_refresh_rejected"),
    REFRESH_PROFILE_REMOVED("profiles_err_refresh_removed"),
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
    cause: Throwable? = null,
    /** Sensitive source retained for an explicit diagnostic export only. */
    val sourcePayload: String? = null,
    /** Safe converter context retained for a diagnostic report after a failed import. */
    val conversionReport: ConversionReport? = null,
    val conversionWarnings: List<ConversionWarning> = emptyList()
) : IllegalStateException(
    (listOf(code.name) + args).joinToString(": "),
    cause
)

/** Finds the most recent sensitive source carried by a typed failure chain. */
fun Throwable.sourcePayloadOrNull(): String? {
    var current: Throwable? = this
    while (current != null) {
        if (current is AppException && current.sourcePayload != null) {
            return current.sourcePayload
        }
        current = current.cause
    }
    return null
}

/** Finds the first typed application error in a wrapped failure chain. */
fun Throwable.appExceptionOrNull(): AppException? {
    var current: Throwable? = this
    while (current != null) {
        if (current is AppException) return current
        current = current.cause
    }
    return null
}

/** Fills missing conversion context without putting it into the exception message. */
fun AppException.withConversionContext(
    sourcePayload: String?,
    conversionReport: ConversionReport? = null,
    conversionWarnings: List<ConversionWarning> = emptyList()
): AppException {
    val source = this.sourcePayload ?: sourcePayload
    val report = this.conversionReport ?: conversionReport
    val warnings = this.conversionWarnings.ifEmpty { conversionWarnings }
    if (source == this.sourcePayload && report == this.conversionReport && warnings == this.conversionWarnings) return this
    return AppException(
        code,
        *args,
        cause = this,
        sourcePayload = source,
        conversionReport = report,
        conversionWarnings = warnings
    )
}

/** Resource id for a code; args are formatted by the caller via Context.getString(res, *args). */
fun AppErrorCode.stringRes(): Int = when (this) {
    AppErrorCode.IMPORT_NO_COMPATIBLE_CONFIG -> R.string.profiles_invalid_result
    AppErrorCode.IMPORT_FAILED -> R.string.conversion_report_failed
    AppErrorCode.SUBSCRIPTION_TOO_LARGE -> R.string.profiles_too_large
    AppErrorCode.PROFILE_YAML_EMPTY -> R.string.profiles_err_yaml_empty
    AppErrorCode.PROFILE_YAML_INVALID -> R.string.profiles_err_yaml_invalid
    AppErrorCode.SUBSCRIPTION_UNDECRYPTABLE -> R.string.profiles_subscription_undecryptable
    AppErrorCode.SUBSCRIPTION_ENCRYPT_FAILED -> R.string.profiles_encrypt_failed
    AppErrorCode.REFRESH_REJECTED_INVALID_CONFIG -> R.string.profiles_err_refresh_rejected
    AppErrorCode.REFRESH_PROFILE_REMOVED -> R.string.profiles_err_refresh_removed
    AppErrorCode.ENC_SUB_NO_KEY -> R.string.profiles_err_enc_sub_no_key
    AppErrorCode.ENC_SUB_UNKNOWN_KEY -> R.string.profiles_err_enc_sub_unknown_key
    AppErrorCode.ENC_SUB_DECRYPT_FAILED -> R.string.profiles_err_enc_sub_decrypt_failed
}
