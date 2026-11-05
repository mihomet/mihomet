package org.mihomet.app.core.config

/**
 * User-defined site rules from the Routing screen ("Правила для сайтов").
 *
 * The user adds domains (or a bare TLD such as `ru`) that must always connect
 * DIRECT, bypassing the proxy — the common "RU domains direct" request from
 * panel users whose subscription routes everything through the tunnel.
 *
 * [ConfigGenerator] prepends the produced rules to the profile's own rules so
 * they win the top-down match. Note: in global ("All sites") mode mihomo
 * bypasses rules entirely, so these only apply in rule ("Smart") mode — the
 * Routing screen documents this.
 */
object UserRules {

    /** Lowercase LDH domain (labels + dots). Bare TLD like `ru` is valid. */
    private val DOMAIN_REGEX =
        Regex("^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*$")

    /**
     * Normalizes a user-typed entry to a bare domain:
     * strips scheme/path/port and trailing dot, lowercases. Returns `null`
     * when nothing domain-shaped remains (the UI shows "invalid domain").
     */
    fun sanitizeDomain(raw: String): String? {
        var d = raw.trim().lowercase()
        d = d.substringAfter("://")   // no scheme → unchanged
        d = d.substringBefore('/')
        d = d.substringBefore('?')
        d = d.substringBefore(':')    // strip port
        d = d.trimEnd('.')
        if (d.isBlank() || d.length > 253 || !DOMAIN_REGEX.matches(d)) return null
        return d
    }

    /** mihomo rules for the stored domains, deterministic order, deduped. */
    fun directRules(domains: Set<String>): List<String> =
        domains.mapNotNull { sanitizeDomain(it) }
            .distinct()
            .sorted()
            .map { "DOMAIN-SUFFIX,$it,DIRECT" }
}
