package org.mihomet.app.data.subs

import org.yaml.snakeyaml.Yaml

/**
 * Pure accept/continue decision for the UA fallback chain (content-aware):
 * a response that converts WITH proxy-groups beats a group-less one, because
 * panels that gate by User-Agent serve a bare URI list (no groups) to unknown
 * clients and the full template to known ones. A group-less success is kept
 * as a fallback while the chain still has entries to try; the first grouped
 * response stops the walk. Encrypted (Happ) payloads decrypt to URI lists —
 * they are accepted immediately.
 */
object SubscriptionContentChoice {

    data class Decision(
        /** Store this attempt as the current response. */
        val accept: Boolean,
        /** Stop walking the UA chain. */
        val stop: Boolean,
        /** Whether the CURRENT stored response yields nodes + groups. */
        val sawGroups: Boolean
    )

    /** Size of a top-level YAML list ("proxies" / "proxy-groups"), 0 on any parse failure. */
    fun countTopLevelList(yaml: String, key: String): Int = runCatching {
        val parsed = Yaml().load<Any>(yaml)
        val list = (parsed as? Map<*, *>)?.get(key) as? List<*>
        list?.size ?: 0
    }.getOrDefault(0)

    fun evaluate(
        currentHasResponse: Boolean,
        currentSawGroups: Boolean,
        attemptEncrypted: Boolean,
        attemptText: String
    ): Decision {
        val yieldsGroups = if (attemptEncrypted) {
            true
        } else {
            val groups = countTopLevelList(attemptText, "proxy-groups")
            val nodes = countTopLevelList(attemptText, "proxies")
            nodes > 0 && groups > 0
        }
        val accept = !currentHasResponse || (yieldsGroups && !currentSawGroups)
        val stop = yieldsGroups
        return Decision(
            accept = accept,
            stop = stop,
            sawGroups = if (accept) yieldsGroups else currentSawGroups
        )
    }
}
