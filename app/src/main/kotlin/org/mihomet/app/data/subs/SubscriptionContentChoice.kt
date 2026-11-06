package org.mihomet.app.data.subs

import org.yaml.snakeyaml.Yaml

/**
 * Pure accept/continue decision for the UA fallback chain (content-aware).
 *
 * Channel quality tiers (live-panel study 06.11: the same panel serves a bare
 * URI list to unknown UAs, a Mihomo YAML with a single select group to Mihomo
 * UAs, and a FULL Xray config with a balancer + thousands of routing rules to
 * Happ — stopping at the first grouped response silently drops the richest
 * channel):
 *
 * - [ChannelQuality.RICH] — the response carries more than a flat node list:
 *   an Xray JSON with multiple outbounds (converted with balancer + routing
 *   rules) or a Mihomo YAML with any non-select (balancer) group. STOPS the
 *   walk — nothing richer can follow.
 * - [ChannelQuality.GROUPED] — nodes + groups, but select-only. Kept as the
 *   best-so-far; the walk continues hoping for a RICH channel.
 * - [ChannelQuality.PLAIN] — nodes only (bare URI list). Kept as the last
 *   resort fallback.
 *
 * Encrypted (Happ) payloads decrypt to URI lists — accepted immediately.
 */
object SubscriptionContentChoice {

    enum class ChannelQuality { PLAIN, GROUPED, RICH }

    data class Decision(
        /** Store this attempt as the current response. */
        val accept: Boolean,
        /** Stop walking the UA chain. */
        val stop: Boolean,
        /** Quality of the CURRENT stored response. */
        val sawQuality: ChannelQuality
    )

    /** Size of a top-level YAML list ("proxies" / "proxy-groups"), 0 on any parse failure. */
    fun countTopLevelList(yaml: String, key: String): Int = runCatching {
        val parsed = Yaml().load<Any>(yaml)
        val list = (parsed as? Map<*, *>)?.get(key) as? List<*>
        list?.size ?: 0
    }.getOrDefault(0)

    /**
     * Detects a RICH channel: Xray/sing-box JSON with more than the direct
     * outbound (the converter synthesizes balancers + routing from it), or a
     * Mihomo YAML whose groups include a balancer type.
     */
    fun isRich(attemptText: String): Boolean {
        val trimmed = attemptText.trim()
        if (trimmed.startsWith("{")) {
            val outbounds = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(trimmed)
                    .let { it as? kotlinx.serialization.json.JsonObject }
                    ?.get("outbounds")
                    ?.let { it as? kotlinx.serialization.json.JsonArray }
                    ?.size ?: 0
            }.getOrDefault(0)
            return outbounds > 2
        }
        val groups = runCatching {
            @Suppress("UNCHECKED_CAST")
            (Yaml().load<Any>(attemptText) as? Map<*, *>)?.get("proxy-groups")
                as? List<Map<*, *>> ?: emptyList()
        }.getOrDefault(emptyList())
        return groups.any {
            val type = (it["type"] as? String)?.lowercase().orEmpty()
            type == "url-test" || type == "fallback" || type == "load-balance"
        }
    }

    fun evaluate(
        currentHasResponse: Boolean,
        currentSawQuality: ChannelQuality,
        attemptEncrypted: Boolean,
        attemptText: String
    ): Decision {
        val attemptQuality = when {
            attemptEncrypted -> ChannelQuality.RICH
            isRich(attemptText) -> ChannelQuality.RICH
            countTopLevelList(attemptText, "proxies") > 0 &&
                    countTopLevelList(attemptText, "proxy-groups") > 0 -> ChannelQuality.GROUPED
            else -> ChannelQuality.PLAIN
        }
        // Accept when the attempt beats or matches what we hold (a later equal
        // channel is still taken: UA tiers the panel knows better than us).
        val accept = when {
            !currentHasResponse -> true
            attemptQuality > currentSawQuality -> true
            attemptQuality == currentSawQuality && currentSawQuality != ChannelQuality.RICH -> true
            else -> false
        }
        val stop = attemptQuality == ChannelQuality.RICH
        val sawQuality = when {
            accept -> attemptQuality
            else -> currentSawQuality
        }
        return Decision(accept = accept, stop = stop, sawQuality = sawQuality)
    }
}
