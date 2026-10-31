package org.mihomet.app.data.subs

import org.mihomet.app.data.preferences.AppSettings

/**
 * Global subscription identity settings (Settings → Subscriptions), extracted
 * from [AppSettings] so the pure merge logic below stays JVM-testable:
 * - [globalUserAgent] — user-chosen User-Agent for subscription downloads;
 *   blank = the built-in default chain;
 * - [sendHwid] — whether the `x-hwid` device header is sent at all;
 * - [customHwid] — user override of the `x-hwid` value; blank = the
 *   device-derived id (HwidProvider).
 */
data class SubscriptionIdentitySettings(
    val globalUserAgent: String = "",
    val sendHwid: Boolean = true,
    val customHwid: String = ""
) {
    companion object {
        fun of(settings: AppSettings): SubscriptionIdentitySettings = SubscriptionIdentitySettings(
            globalUserAgent = settings.subscriptionUserAgent,
            sendHwid = settings.sendHwid,
            customHwid = settings.customHwid
        )
    }
}

/**
 * Pure header merge for a subscription download (precedence, v2rayNG/Happ
 * conventions: per-subscription override wins over the global setting, the
 * global setting wins over the built-in default):
 *
 * - **User-Agent**: a `User-Agent` header in the profile's custom headers
 *   replaces the chain entirely (explicit per-subscription override); else a
 *   non-blank [SubscriptionIdentitySettings.globalUserAgent] replaces it;
 *   else the honest default chain is used (strict SRR panels such as Remnawave
 *   serve content by UA tier, so "Mihomet" must land in the Mihomo tier).
 * - **x-hwid**: a profile `x-hwid` header wins; else the header is omitted
 *   when [SubscriptionIdentitySettings.sendHwid] is off; else
 *   [SubscriptionIdentitySettings.customHwid] when set; else the device id.
 * - **Everything else** from the profile headers is sent as-is (already
 *   sanitized by [SubscriptionHeaders.sanitizeForRequest]).
 */
data class SubscriptionRequestPlan(
    /** User-Agent chain to try in order (first successful response wins). */
    val userAgentsToTry: List<String>,
    /** `x-hwid` value to send, or null = do not send the header at all. */
    val hwidHeader: String?,
    /** Profile headers sent verbatim (already excludes UA / x-hwid). */
    val extraHeaders: Map<String, String>
) {
    companion object {

        /**
         * Builds the request plan for one subscription download.
         *
         * [profileHeaders] must already be sanitized
         * ([SubscriptionHeaders.sanitizeForRequest]) — this builder performs no
         * content validation of its own. Case-insensitive lookups keep the
         * precedence working for any user-typed header casing.
         */
        fun build(
            profileHeaders: Map<String, String>,
            identity: SubscriptionIdentitySettings,
            deviceHwid: String
        ): SubscriptionRequestPlan {
            val profileUa = profileHeaders.entries
                .firstOrNull { it.key.equals(USER_AGENT_HEADER, ignoreCase = true) }?.value
            val globalUa = identity.globalUserAgent.trim()

            val userAgentsToTry = when {
                !profileUa.isNullOrBlank() -> listOf(profileUa)
                globalUa.isNotBlank() -> listOf(globalUa)
                else -> SubscriptionRepository.UA_FALLBACK_CHAIN
            }

            val profileHwid = profileHeaders.entries
                .firstOrNull { it.key.equals(HWID_HEADER, ignoreCase = true) }?.value
            val hwidHeader: String? = when {
                !profileHwid.isNullOrBlank() -> profileHwid
                !identity.sendHwid -> null
                identity.customHwid.isNotBlank() -> identity.customHwid
                else -> deviceHwid
            }

            val extraHeaders = profileHeaders.filterKeys { key ->
                !key.equals(USER_AGENT_HEADER, ignoreCase = true) &&
                    !key.equals(HWID_HEADER, ignoreCase = true)
            }

            return SubscriptionRequestPlan(userAgentsToTry, hwidHeader, extraHeaders)
        }

        /** Happ/Incy panel device-binding header (see HwidProvider). */
        const val HWID_HEADER: String = "x-hwid"
        const val USER_AGENT_HEADER: String = "User-Agent"
    }
}
