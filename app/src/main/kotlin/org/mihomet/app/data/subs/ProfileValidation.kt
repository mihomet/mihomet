package org.mihomet.app.data.subs

import org.yaml.snakeyaml.Yaml

/**
 * Semantic, fail-closed validation of a profile YAML before it is persisted
 * (subscription refresh in [SubscriptionRepository], remote add, clipboard /
 * deep-link import in ProfilesViewModel).
 *
 * A profile is valid when it parses into a YAML **mapping** and declares at
 * least one **non-empty** source of routing truth:
 *  - `proxies:` — a list holding at least one **mapping** (a real node
 *    definition; `null` / scalar entries do not count, see below);
 *  - `proxy-providers:` — a non-empty mapping (mihomo's native shape) or list
 *    of providers the core fetches nodes from at runtime;
 *  - `rules:` — a non-empty routing rules list.
 *
 * The earlier presence-only check accepted degenerate payloads such as
 * `proxies: []` + `rules: []` (what a broken subscription / failed conversion
 * can return); with such a file the core starts with no nodes and no rules and
 * routes **everything to DIRECT** while the UI shows "connected". Only a
 * *non-empty* key counts now:
 *  - `proxies: []` / `proxies: null` / absent proxies → valid only when a
 *    non-empty `proxy-providers` (or non-empty `rules`) is present;
 *  - `proxies: [null]` / `proxies: ["scalar"]` → the entries are NOT nodes:
 *    ConfigGenerator filters non-mapping proxies out, so such a list yields an
 *    empty node set and does not count as a routing source;
 *  - `rules` may be empty or absent when inline proxies exist — such a config
 *    is a deliberate "node library" (routing is then app-managed); valid;
 *  - `proxies: []` (or `[null]`) + `rules: []` → **invalid** (broken
 *    subscription).
 *
 * Deliberately valid without inline proxies:
 *  - `proxy-providers`-only configs (nodes are downloaded by the core itself);
 *  - `rules`-only configs — the happ://routing deep-link import produces
 *    exactly such routing profiles (GLOBAL/DIRECT/REJECT targets + a MATCH
 *    rule); routing profiles carry no nodes by design and must keep importing.
 *
 * Pure function over the YAML string (no Android dependencies) so JVM unit
 * tests can drive it directly — see ProfileValidationTest.
 */
object ProfileValidation {

    fun isValid(yaml: String): Boolean {
        if (yaml.isBlank()) return false
        val parsed = runCatching { Yaml().load<Any>(yaml) }.getOrNull() ?: return false
        if (parsed !is Map<*, *>) return false
        return hasProxyEntry(parsed["proxies"]) ||
            hasNonEmptyCollection(parsed["proxy-providers"]) ||
            hasNonEmptyList(parsed["rules"])
    }

    /**
     * `proxies` counts as a routing source only when the list holds at least one
     * mapping (a real node definition). Degenerate entries — `null`
     * (`proxies: [null]`) or bare scalars — do NOT count: the runtime config
     * generator keeps only `Map` proxies, so a list of them yields an empty node
     * set and the core would route everything to DIRECT while the UI shows
     * "connected".
     */
    private fun hasProxyEntry(value: Any?): Boolean =
        value is List<*> && value.any { it is Map<*, *> }

    private fun hasNonEmptyList(value: Any?): Boolean = value is List<*> && value.isNotEmpty()

    /** `proxy-providers` is a mapping in mihomo; a list form is tolerated too. */
    private fun hasNonEmptyCollection(value: Any?): Boolean = when (value) {
        is Map<*, *> -> value.isNotEmpty()
        is List<*> -> value.isNotEmpty()
        else -> false
    }
}
