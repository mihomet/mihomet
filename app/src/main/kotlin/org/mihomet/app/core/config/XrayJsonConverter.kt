package org.mihomet.app.core.config

import kotlinx.serialization.json.*
import org.mihomet.app.core.CoreConstants
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

/** Each Happ configuration has its own tag namespace; display names are never identifiers. */
internal object XrayJsonConverter {
    private val serviceProtocols = setOf("freedom", "blackhole", "dns", "dokodemo-door", "api")
    private val empty = JsonObject(emptyMap())

    fun isXrayJson(str: String): Boolean = runCatching {
        fun matches(obj: JsonObject): Boolean = obj["protocol"] is JsonPrimitive ||
            obj.array("outbounds").any { (it as? JsonObject)?.get("protocol") is JsonPrimitive } ||
            obj.keys.any { it in setOf("routing", "observatory", "burstObservatory") }
        when (val root = ConverterSupport.json.parseToJsonElement(str)) {
            is JsonObject -> matches(root)
            is JsonArray -> root.filterIsInstance<JsonObject>().any(::matches)
            else -> false
        }
    }.getOrDefault(false)

    fun convertXrayJsonToMihomoYaml(jsonStr: String, warnings: MutableList<ConversionWarning>? = null): String {
        val outcome = convert(jsonStr)
        warnings?.addAll(outcome.warnings)
        return outcome.yaml
    }

    fun convert(jsonStr: String): ConversionOutcome = try {
        val root = ConverterSupport.json.parseToJsonElement(jsonStr)
        val elements = when (root) {
            is JsonObject -> listOf(root)
            is JsonArray -> root.toList()
            else -> emptyList()
        }
        val objects = elements.filterIsInstance<JsonObject>()
        val outboundArray = objects.isNotEmpty() && objects.all { it["protocol"] != null }
        val scopes = if (outboundArray) {
            listOf(Scope(0, JsonObject(mapOf("outbounds" to JsonArray(elements)))))
        } else elements.mapIndexedNotNull { index, item -> (item as? JsonObject)?.let { Scope(index, it) } }
        val invalidConfigs = if (outboundArray) emptyList()
            else elements.mapIndexedNotNull { index, item -> index.takeIf { item !is JsonObject } }
        Graph(scopes, invalidConfigs).convert()
    } catch (_: Exception) {
        // Parser exceptions can contain the original JSON, including passwords.
        ConversionOutcome(
            yaml = "",
            warnings = listOf(ConversionWarning.XrayError(null)),
            report = ConversionReport(format = "xray"),
            format = "xray"
        )
    }

    private class Scope(val index: Int, val item: JsonObject) {
        val targets = linkedMapOf<String, String>()
        val members = linkedMapOf<String, List<String>>()
        val outbounds = item.array("outbounds")
        val balancers = item.obj("routing").array("balancers").filterIsInstance<JsonObject>()
        var defaultTarget: String? = null

        fun registerTarget(index: Int, tag: String, target: String) {
            if (index == 0) defaultTarget = target
            if (tag.isNotBlank()) targets[tag] = target
        }
    }

    private class Graph(private val scopes: List<Scope>, invalidConfigs: List<Int>) {
        private val nodes = linkedMapOf<String, MutableMap<String, Any>>()
        private val groups = linkedMapOf<String, MutableMap<String, Any>>()
        private val usedNames = mutableSetOf("DIRECT", "REJECT", "GLOBAL", "COMPATIBLE", "dns-out")
        private val equivalent = hashMapOf<Map<String, Any>, String>()
        private val chains = mutableListOf<Triple<Scope, String, String>>()
        private val issues = invalidConfigs.map { ConversionIssue(ConversionIssueCode.INVALID_NODE, "configs[$it]") }.toMutableList()
        private var sourceNodes = invalidConfigs.size
        private var duplicates = 0
        private var sourceRules = 0
        private var importedRules = 0
        private var alternativeRules = 0

        private fun issue(code: ConversionIssueCode, path: String) {
            issues.add(ConversionIssue(code, path))
        }

        private fun name(raw: String): String {
            // Native rule strings use commas to separate the target from rule parameters.
            val base = raw.filterNot { it.isISOControl() }.replace(',', '，').trim()
                .take(160).trimEnd { it.isHighSurrogate() }.ifBlank { "Node" }
            var result = base
            var index = 2
            while (!usedNames.add(result)) result = "$base (${index++})"
            return result
        }

        fun convert(): ConversionOutcome {
            val routingScope = scopes.maxWithOrNull(compareBy<Scope> { it.balancers.isNotEmpty() }
                .thenBy { it.item.obj("routing").array("rules").size })
            sourceRules = routingScope?.item?.obj("routing")?.array("rules")?.size ?: 0
            alternativeRules = scopes.filter { it !== routingScope }
                .sumOf { it.item.obj("routing").array("rules").size }
            scopes.filter { it !== routingScope && it.item.obj("routing").array("rules").isNotEmpty() }
                .forEach { issue(ConversionIssueCode.ALTERNATIVE_RULES_NOT_MERGED, "configs[${it.index}].routing") }
            // Prefer individual Happ remarks when a separate balancer config repeats the same nodes.
            scopes.sortedBy { it.balancers.isNotEmpty() }.forEach(::collectNodes)
            scopes.forEach(::collectBalancers)
            resolveChains()
            removeBrokenDependencies()
            if (nodes.isEmpty()) return result("")

            val mainName = name("PROXY")
            val balancers = groups.values.filter { it["type"] != "select" }.map { it["name"].toString() }
            val firstScope = scopes.firstOrNull()
            val firstTarget = firstScope?.defaultTarget
            val defaultTarget = firstTarget?.takeIf { scopes.size == 1 && exists(it) }
            val mainMembers = (listOfNotNull(defaultTarget) + balancers + nodes.keys).distinct()
            groups[mainName] = linkedMapOf("name" to mainName, "type" to "select", "proxies" to mainMembers)

            val rules = linkedSetOf<String>()
            for (scope in scopes) {
                if (scope !== routingScope && scope.item.obj("routing").array("rules").isNotEmpty()) {
                    continue
                }
                val source = scope.item.obj("routing").array("rules")
                source.forEachIndexed { index, element ->
                    val path = "configs[${scope.index}].routing.rules[$index]"
                    val rule = element as? JsonObject
                    val target = rule?.text("balancerTag")?.takeIf { it.isNotBlank() }
                        ?.let { scope.targets["balancer:$it"] }
                        ?: rule?.text("outboundTag")?.takeIf { it.isNotBlank() }?.let { scope.targets[it] }
                    if (rule == null || target == null || !exists(target)) {
                        issue(ConversionIssueCode.UNRESOLVED_REFERENCE, path)
                    } else {
                        val translated = XrayRouting.convert(rule, target)
                        if (translated == null) issue(ConversionIssueCode.UNSUPPORTED_RULE, path)
                        else { rules.add(translated); importedRules++ }
                    }
                }
                val routing = scope.item.obj("routing")
                if (routing.text("domainStrategy").let { it.isNotEmpty() && it != "AsIs" }) {
                    issue(ConversionIssueCode.UNSUPPORTED_FIELD, "configs[${scope.index}].routing.domainStrategy")
                }
                if (scope.item["dns"] != null) issue(ConversionIssueCode.UNSUPPORTED_FIELD, "configs[${scope.index}].dns")
            }
            if (rules.none { it.startsWith("MATCH,") }) rules.add("MATCH,$mainName")
            val options = DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK; indent = 2 }
            val yaml = Yaml(options).dump(linkedMapOf(
                "proxies" to nodes.values.toList(), "proxy-groups" to (listOf(groups.getValue(mainName)) + groups.filterKeys { it != mainName }.values), "rules" to rules.toList()
            ))
            return result(yaml)
        }

        private fun result(yaml: String): ConversionOutcome {
            val report = ConversionReport(
                format = "xray",
                sourceNodes = sourceNodes,
                importedNodes = nodes.size,
                duplicateNodes = duplicates,
                sourceRules = sourceRules,
                importedRules = importedRules,
                issues = issues.distinct(),
                alternativeRules = alternativeRules
            )
            val warnings = report.issues.map { ConversionWarning.XrayLoss(it) }.toMutableList<ConversionWarning>()
            if (nodes.isEmpty()) warnings.add(ConversionWarning.NoNodesXray)
            return ConversionOutcome(yaml, warnings, report, format = "xray")
        }

        private fun collectNodes(scope: Scope) {
            val real = scope.outbounds.filter { (it as? JsonObject)?.text("protocol")?.lowercase() !in serviceProtocols }
            scope.outbounds.forEachIndexed { index, element ->
                val path = "configs[${scope.index}].outbounds[$index]"
                val ob = element as? JsonObject
                if (ob == null) {
                    sourceNodes++
                    issue(ConversionIssueCode.INVALID_NODE, path)
                    return@forEachIndexed
                }
                val tag = ob.text("tag")
                when (ob.text("protocol").lowercase()) {
                    "freedom" -> {
                        scope.registerTarget(index, tag, "DIRECT")
                        ob.obj("settings").filterNot { (key, value) ->
                            key == "domainStrategy" && (value as? JsonPrimitive)?.contentOrNull == "AsIs" ||
                                key == "userLevel" && (value as? JsonPrimitive)?.intOrNull == 0
                        }.keys.forEach { issue(ConversionIssueCode.UNSUPPORTED_FIELD, "$path.settings.${XraySourceFields.safeName(it)}") }
                        return@forEachIndexed
                    }
                    "blackhole" -> {
                        scope.registerTarget(index, tag, "REJECT")
                        if (ob.obj("settings").obj("response").text("type").let { it.isNotBlank() && it != "none" }) {
                            issue(ConversionIssueCode.UNSUPPORTED_FIELD, "$path.settings.response")
                        }
                        return@forEachIndexed
                    }
                    in serviceProtocols -> return@forEachIndexed
                }
                val names = mutableListOf<String>()
                val label = if (real.size == 1) scope.item.text("remarks").ifBlank { tag } else tag
                val expanded = expand(ob)
                var variants = 0
                expanded.forEachIndexed { variant, settings ->
                    variants++
                    sourceNodes++
                    val variantPath = "$path.settings[$variant]"
                    if (settings == null) {
                        issue(ConversionIssueCode.INVALID_NODE, variantPath)
                        return@forEachIndexed
                    }
                    val map = parseNode(ob, settings, label, variantPath) ?: return@forEachIndexed
                    val chain = map["dialer-proxy"] as? String
                    val key = map.filterKeys { it != "name" } + if (chain != null) mapOf("scope" to scope.index) else emptyMap()
                    val existing = equivalent[key]
                    if (existing != null) { duplicates++; names.add(existing) }
                    else {
                        val nodeName = name(label.ifBlank { "Node ${scope.index + 1}.${index + 1}" })
                        map["name"] = nodeName
                        nodes[nodeName] = map
                        equivalent[key] = nodeName
                        names.add(nodeName)
                        if (chain != null) chains.add(Triple(scope, nodeName, chain))
                    }
                }
                if (variants == 0) {
                    sourceNodes++
                    issue(ConversionIssueCode.INVALID_NODE, "$path.settings")
                }
                if (names.isEmpty()) return@forEachIndexed
                scope.members[tag] = names.distinct()
                val target = if (names.distinct().size == 1) names.first() else {
                    val groupName = name(tag.ifBlank { label })
                    groups[groupName] = linkedMapOf("name" to groupName, "type" to "select", "proxies" to names.distinct())
                    groupName
                }
                scope.registerTarget(index, tag, target)
            }
        }

        private fun parseNode(ob: JsonObject, settings: JsonObject, label: String, path: String): MutableMap<String, Any>? {
            val protocol = ob.text("protocol").lowercase()
            if (!XraySourceFields.validShape(ob)) {
                issue(ConversionIssueCode.INVALID_NODE, path)
                return null
            }
            val stream = ob.obj("streamSettings")
            if (!XrayTransportOptions.validReality(stream)) {
                issue(ConversionIssueCode.INVALID_NODE, "$path.streamSettings.realitySettings")
                return null
            }
            if (settings["port"] != null && (settings["port"] as? JsonPrimitive)?.intOrNull !in 1..65535) {
                issue(ConversionIssueCode.INVALID_NODE, "$path.port")
                return null
            }
            if (!XrayTransportOptions.supported(protocol, stream)) {
                issue(ConversionIssueCode.UNSUPPORTED_TRANSPORT, "$path.streamSettings")
                return null
            }
            val map = runCatching { XrayOutboundParser.parse(label, protocol, settings, stream)?.toMutableMap() }.getOrNull()
            if (map == null || map["server"].toString().isBlank() || (map["port"] as? Int) !in 1..65535) {
                issue(ConversionIssueCode.INVALID_NODE, path)
                return null
            }
            try {
                XraySourceFields.reportUnmappedFields(ob, settings) { field ->
                    issue(ConversionIssueCode.UNSUPPORTED_FIELD, "$path.$field")
                }
                XrayTransportOptions.apply(map, protocol, settings, stream) { field ->
                    issue(ConversionIssueCode.UNSUPPORTED_FIELD, "$path.$field")
                }
            } catch (_: IllegalArgumentException) {
                issue(ConversionIssueCode.UNSUPPORTED_TRANSPORT, path)
                return null
            }
            val mux = ob.obj("mux")["enabled"]
            if (mux != null && (mux as? JsonPrimitive)?.booleanOrNull == null) {
                issue(ConversionIssueCode.UNSUPPORTED_FIELD, "$path.mux.enabled")
            }
            if ((mux as? JsonPrimitive)?.booleanOrNull == true) {
                issue(ConversionIssueCode.MUX_UNSUPPORTED, "$path.mux")
            }
            val dialer = ob.obj("proxySettings").text("tag").ifBlank { stream.obj("sockopt").text("dialerProxy") }
            if (dialer.isNotBlank()) map["dialer-proxy"] = dialer
            if ((ob.obj("proxySettings")["transportLayer"] as? JsonPrimitive)?.booleanOrNull == false) {
                issue(ConversionIssueCode.UNSUPPORTED_FIELD, "$path.proxySettings.transportLayer")
            }
            return map
        }

        private fun collectBalancers(scope: Scope) {
            scope.balancers.forEachIndexed { index, balancer ->
                val path = "configs[${scope.index}].routing.balancers[$index]"
                val selectors = balancer.array("selector").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                val members = scope.members.filterKeys { tag -> selectors.any { tag.startsWith(it) } }.values.flatten().distinct()
                val fallback = scope.targets[balancer.text("fallbackTag")]
                val selected = members.ifEmpty { if (fallback != null && exists(fallback)) listOf(fallback) else emptyList() }
                if (selected.isEmpty()) { issue(ConversionIssueCode.UNRESOLVED_REFERENCE, path); return@forEachIndexed }
                val strategy = balancer.obj("strategy").text("type").ifBlank { "random" }
                if (strategy !in setOf("leastPing", "leastLoad", "random")) {
                    issue(ConversionIssueCode.UNSUPPORTED_FIELD, "$path.strategy"); return@forEachIndexed
                }
                val tag = balancer.text("tag").ifBlank { "balancer-${index + 1}" }
                val groupName = name(scope.item.text("remarks").takeIf { scope.balancers.size == 1 }.orEmpty().ifBlank { tag })
                val observatory = scope.item.obj("burstObservatory").ifEmpty { scope.item.obj("observatory") }
                val ping = observatory.obj("pingConfig")
                val group = linkedMapOf<String, Any>(
                    "name" to groupName, "type" to if (strategy == "random") "load-balance" else "url-test",
                    "proxies" to selected,
                    "url" to ping.text("destination").ifBlank { observatory.text("probeURL").ifBlank { CoreConstants.AUTO_PROBE_URL } },
                    "interval" to ConverterSupport.parseSeconds(ping.text("interval").ifBlank { observatory.text("probeInterval") }, 300).coerceAtLeast(1)
                )
                if (strategy == "random") group["strategy"] = "round-robin" else group["tolerance"] = 50
                if (strategy != "leastPing") issue(ConversionIssueCode.BALANCER_APPROXIMATION, "$path.strategy")
                if (balancer.obj("strategy").obj("settings").isNotEmpty()) issue(ConversionIssueCode.UNSUPPORTED_FIELD, "$path.strategy.settings")
                groups[groupName] = group
                scope.targets["balancer:$tag"] = groupName
            }
        }

        private fun resolveChains() {
            for ((scope, node, tag) in chains) {
                val target = scope.targets[tag]
                when {
                    target == "DIRECT" -> nodes[node]?.remove("dialer-proxy")
                    target != null && exists(target) -> nodes[node]?.set("dialer-proxy", target)
                    else -> {
                        nodes.remove(node)
                        issue(ConversionIssueCode.UNRESOLVED_REFERENCE, "configs[${scope.index}].dialer-proxy")
                    }
                }
            }
        }

        private fun exists(target: String): Boolean = target == "DIRECT" || target == "REJECT" || target in nodes || target in groups

        private fun dependencies(target: String): List<String> = nodes[target]?.get("dialer-proxy")?.let { listOf(it.toString()) }
            ?: (groups[target]?.get("proxies") as? List<*>)?.map { it.toString() }.orEmpty()

        private fun removeBrokenDependencies() {
            val all = (nodes.keys + groups.keys).toSet()
            val remaining = all.associateWith { dependencies(it).count { dependency -> dependency in all } }.toMutableMap()
            val parents = mutableMapOf<String, MutableList<String>>()
            for (target in all) for (dependency in dependencies(target)) {
                if (dependency in all) parents.getOrPut(dependency) { mutableListOf() }.add(target)
            }
            val ready = ArrayDeque(remaining.filterValues { it == 0 }.keys)
            while (ready.isNotEmpty()) {
                val done = ready.removeFirst()
                for (parent in parents[done].orEmpty()) {
                    val count = remaining.getValue(parent) - 1
                    remaining[parent] = count
                    if (count == 0) ready.addLast(parent)
                }
            }
            val cyclic = remaining.filterValues { it > 0 }.keys
            if (cyclic.isNotEmpty()) {
                cyclic.forEach { nodes.remove(it); groups.remove(it) }
                issue(ConversionIssueCode.REFERENCE_CYCLE, "outbounds.dialer-proxy")
            }
            do {
                var changed = false
                val invalidNodes = nodes.filterValues { proxy -> (proxy["dialer-proxy"] as? String)?.let { !exists(it) } == true }.keys
                invalidNodes.toList().forEach { nodes.remove(it); changed = true }
                for ((_, group) in groups) {
                    val members = (group["proxies"] as List<*>).map { it.toString() }
                    val valid = members.filter(::exists)
                    if (valid != members) { group["proxies"] = valid; changed = true }
                }
                val emptyGroups = groups.filterValues { (it["proxies"] as List<*>).isEmpty() }.keys.toList()
                emptyGroups.forEach { groups.remove(it); changed = true }
                if (changed) issue(ConversionIssueCode.UNRESOLVED_REFERENCE, "outbounds.dependencies")
            } while (changed)
        }
    }

    private fun expand(ob: JsonObject): Sequence<JsonObject?> {
        val settings = ob.obj("settings")
        val servers = settings["vnext"]
        if (servers != null && servers !is JsonNull) {
            if (servers !is JsonArray) return sequenceOf(null)
            return servers.asSequence().flatMap { element ->
                val server = element as? JsonObject ?: return@flatMap sequenceOf(null)
                val users = server["users"] as? JsonArray ?: return@flatMap sequenceOf(null)
                if (users.isEmpty()) return@flatMap sequenceOf(null)
                users.asSequence().map { user ->
                    if (user !is JsonObject) null else JsonObject((settings - "vnext") + (server - "users") + user)
                }
            }
        }
        val generic = settings["servers"]
        if (generic != null && generic !is JsonNull) {
            if (generic !is JsonArray) return sequenceOf(null)
            return generic.asSequence().flatMap { element ->
                val server = element as? JsonObject ?: return@flatMap sequenceOf(null)
                val userField = server["users"]
                if (userField != null && userField !is JsonNull && userField !is JsonArray) return@flatMap sequenceOf(null)
                val users = (userField as? JsonArray).orEmpty().ifEmpty { listOf(empty) }
                users.asSequence().map { user ->
                    if (user !is JsonObject) return@map null
                    val merged = JsonObject(server + if (user.isEmpty()) emptyMap() else mapOf("users" to JsonArray(listOf(user))))
                    if (ob.text("protocol").lowercase() in setOf("trojan", "shadowsocks")) JsonObject(settings + ("servers" to JsonArray(listOf(merged))))
                    else JsonObject((settings - "servers") + merged)
                }
            }
        }
        val users = settings["users"]
        if (ob.text("protocol").lowercase() in setOf("http", "socks") && users != null && users !is JsonNull) {
            if (users !is JsonArray) return sequenceOf(null)
            if (users.isNotEmpty()) return users.asSequence().map { user ->
                if (user !is JsonObject) null else JsonObject(settings + ("users" to JsonArray(listOf(user))))
            }
        }
        return sequenceOf(settings)
    }

    internal fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    internal fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: empty
    internal fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray)?.toList().orEmpty()
}
