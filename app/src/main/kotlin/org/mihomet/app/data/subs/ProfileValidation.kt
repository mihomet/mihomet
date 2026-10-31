package org.mihomet.app.data.subs

import org.yaml.snakeyaml.Yaml

/**
 * Semantic, fail-closed validation of a profile YAML before it is persisted
 * (subscription refresh in [SubscriptionRepository], remote add, clipboard /
 * deep-link import in ProfilesViewModel).
 *
 * A profile is valid when it parses into a YAML **mapping** and declares at
 * least one **non-empty** source of routing truth:
 *  - `proxies:` — a non-empty list of inline nodes;
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
 *  - `rules` may be empty or absent when inline proxies exist — such a config
 *    is a deliberate "node library" (routing is then app-managed); valid;
 *  - `proxies: []` + `rules: []` → **invalid** (broken subscription).
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
        return hasNonEmptyList(parsed["proxies"]) ||
            hasNonEmptyCollection(parsed["proxy-providers"]) ||
            hasNonEmptyList(parsed["rules"])
    }

    private fun hasNonEmptyList(value: Any?): Boolean = value is List<*> && value.isNotEmpty()

    /** `proxy-providers` is a mapping in mihomo; a list form is tolerated too. */
    private fun hasNonEmptyCollection(value: Any?): Boolean = when (value) {
        is Map<*, *> -> value.isNotEmpty()
        is List<*> -> value.isNotEmpty()
        else -> false
    }
}
