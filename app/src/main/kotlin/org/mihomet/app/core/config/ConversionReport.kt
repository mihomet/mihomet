package org.mihomet.app.core.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class ConversionIssueCode {
    INVALID_NODE, UNSUPPORTED_TRANSPORT, UNSUPPORTED_FIELD, UNSUPPORTED_RULE,
    UNRESOLVED_REFERENCE, REFERENCE_CYCLE, BALANCER_APPROXIMATION, MUX_UNSUPPORTED,
    ALTERNATIVE_RULES_NOT_MERGED
}

/** Paths are generated schema paths, never source values, credentials or parser excerpts. */
@Serializable
data class ConversionIssue(val code: ConversionIssueCode, val path: String)

@Serializable
data class ConversionReport(
    val format: String = "",
    val sourceNodes: Int = 0,
    val importedNodes: Int = 0,
    val duplicateNodes: Int = 0,
    val sourceRules: Int = 0,
    val importedRules: Int = 0,
    val issues: List<ConversionIssue> = emptyList(),
    val alternativeRules: Int = 0
) {
    val skippedNodes: Int get() = (sourceNodes - importedNodes - duplicateNodes).coerceAtLeast(0)
    val skippedRules: Int get() = (sourceRules - importedRules).coerceAtLeast(0)

    fun toJson(): String = codec.encodeToString(this)

    companion object {
        private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromJson(raw: String): ConversionReport? = if (raw.isBlank()) null else
            runCatching { codec.decodeFromString<ConversionReport>(raw) }.getOrNull()
    }
}
