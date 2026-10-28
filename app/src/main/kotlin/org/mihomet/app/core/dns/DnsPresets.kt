package org.mihomet.app.core.dns

import org.mihomet.app.R

/**
 * Reachability classification of DNS servers from Russia.
 *
 * TSPU (Russian DPI equipment) intercepts and blocks encrypted DNS of foreign
 * providers (Cloudflare, Google, Quad9, and intermittently AdGuard) and plain
 * UDP/53 queries to their IPs. Russian providers (Yandex, Comss.one) remain
 * reachable both in plain and encrypted form.
 */
enum class DnsCategory(val labelRes: Int) {
    /** Plain UDP/53 servers that work directly from Russia. */
    PLAINTEXT_RU(R.string.dns_category_plaintext_ru),

    /** Encrypted (DoH/DoT) servers hosted so that they work directly from Russia. */
    ENCRYPTED_RU(R.string.dns_category_encrypted_ru),

    /** Encrypted servers that are NOT guaranteed to work from Russia without a proxy. */
    GLOBAL(R.string.dns_category_global)
}

/** A selectable DNS preset shown in the Custom DNS dialog. */
data class DnsPreset(
    val id: String,
    val uri: String,
    val category: DnsCategory
)

/**
 * Single source of truth for DNS server classification across the app.
 * ConfigGenerator consumes [BOOTSTRAP_RU_PLAIN] / category lists; the
 * CustomDnsDialog renders [ALL] grouped by category with color badges.
 */
object DnsPresets {

    /** Yandex plain DNS — reachable from Russia over UDP/53. */
    const val YANDEX_PLAIN_1 = "77.88.8.8"
    const val YANDEX_PLAIN_2 = "77.88.8.1"

    /**
     * Bootstrap servers for mihomo `default-nameserver`: plain direct IPs used to
     * resolve the hostnames of encrypted (DoH/DoT) upstream endpoints.
     * Foreign plain DNS (1.1.1.1 / 8.8.8.8) is blocked by TSPU and must never be here.
     */
    val BOOTSTRAP_RU_PLAIN = listOf(YANDEX_PLAIN_1, YANDEX_PLAIN_2)

    /** Default main `nameserver` when the user has not chosen a custom DNS. */
    val DEFAULT_NAMESERVER = listOf(YANDEX_PLAIN_1)

    val ALL = listOf(
        // PLAINTEXT_RU — reachable from Russia directly (plain UDP 53)
        DnsPreset("yandex_plain_1", YANDEX_PLAIN_1, DnsCategory.PLAINTEXT_RU),
        DnsPreset("yandex_plain_2", YANDEX_PLAIN_2, DnsCategory.PLAINTEXT_RU),

        // ENCRYPTED_RU — encrypted, reachable from Russia directly.
        // Yandex DoH endpoint per official docs (dns.yandex.com):
        // https://common.doh.dns.yandex.net/dns-query
        DnsPreset("yandex_doh", "https://common.doh.dns.yandex.net/dns-query", DnsCategory.ENCRYPTED_RU),
        // Comss.one — popular Russian-accessible DoH service.
        DnsPreset("comss_doh", "https://dns.comss.one/dns-query", DnsCategory.ENCRYPTED_RU),

        // GLOBAL — encrypted, NOT guaranteed to work from Russia directly
        // (usable only when resolution goes through the proxy).
        // AdGuard is classified GLOBAL deliberately: its DoH suffers intermittent
        // TSPU disruptions depending on the ISP (since 2023).
        DnsPreset("adguard_doh", "https://dns.adguard-dns.com/dns-query", DnsCategory.GLOBAL),
        DnsPreset("cloudflare_doh_ip", "https://1.1.1.1/dns-query", DnsCategory.GLOBAL),
        DnsPreset("cloudflare_doh", "https://cloudflare-dns.com/dns-query", DnsCategory.GLOBAL),
        DnsPreset("google_doh", "https://dns.google/dns-query", DnsCategory.GLOBAL),
        DnsPreset("quad9_doh", "https://dns.quad9.net/dns-query", DnsCategory.GLOBAL)
    )

    /** Category of a user-saved DNS URI, or null for blank/custom values. */
    fun categoryOf(uri: String): DnsCategory? =
        uri.trim().takeIf { it.isNotBlank() }
            ?.let { trimmed -> ALL.firstOrNull { it.uri == trimmed }?.category }

    /** All presets of a single category, in [ALL] order. */
    fun byCategory(category: DnsCategory): List<DnsPreset> = ALL.filter { it.category == category }
}
