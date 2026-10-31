package org.mihomet.app.data.subs

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Strict parsing + validation of user-provided custom subscription headers
 * ("Name: Value" lines or a JSON object map).
 *
 * Everything a user pastes here is later handed to the HTTP client as a request
 * header, so the parser enforces hard guards:
 * - **Header-injection guard**: CR / LF (and every other ASCII control
 *   character) inside a name or value is a hard [Violation.FORBIDDEN_CHAR] —
 *   a newline in a value would smuggle extra headers / a second request into
 *   the wire format;
 * - **Size caps**: raw input, header count, name and value lengths are bounded
 *   so a pasted blob can neither blow up the stored row nor the request.
 *
 * Parsing is lenient about FORMAT (JSON object or `Key: Value` / `Key=Value`
 * lines, legacy behavior preserved) and strict about CONTENT (the guards
 * above). Violations raise [HeaderValidationException] carrying a
 * [Violation] that the UI maps to a localized message.
 */
object SubscriptionHeaders {

    /** Maximum accepted raw input length (characters). */
    const val MAX_RAW_LENGTH = 16 * 1024

    /** Maximum number of headers in one profile. */
    const val MAX_HEADERS = 32

    /** Maximum header NAME length (characters). */
    const val MAX_NAME_LENGTH = 128

    /** Maximum header VALUE length (characters). */
    const val MAX_VALUE_LENGTH = 4096

    /**
     * Guard violations ([HeaderValidationException.violation]). Mapped to
     * localized strings by the UI layer — the pure parser never produces
     * user-facing text.
     */
    enum class Violation {
        RAW_TOO_LARGE,
        TOO_MANY_HEADERS,
        NAME_TOO_LONG,
        VALUE_TOO_LONG,
        FORBIDDEN_CHAR,
        EMPTY_NAME
    }

    /** Thrown by [parse]/[validate] when a hard guard fires. */
    class HeaderValidationException(val violation: Violation) :
        IllegalArgumentException("Header validation failed: ${violation.name}")

    /** Lenient format parsing (no content guards): JSON object or `Key: Value` lines. */
    fun parseLenient(raw: String): Map<String, String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyMap()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            // Legacy fallback: a stored JSON-shaped blob that does not decode
            // (hand-edited rows from older versions) is line-parsed like any
            // other input instead of yielding nothing.
            return runCatching {
                Json.decodeFromString<Map<String, String>>(trimmed)
            }.getOrElse { linesToMap(trimmed) }
        }
        return linesToMap(trimmed)
    }

    /**
     * Strict parse: raw-size guard, lenient format parsing followed by
     * [validate]. Throws [HeaderValidationException] on the first violated
     * guard.
     */
    fun parse(raw: String): Map<String, String> {
        if (raw.length > MAX_RAW_LENGTH) throw HeaderValidationException(Violation.RAW_TOO_LARGE)
        return validate(parseLenient(raw))
    }

    /**
     * Content guards over an already-parsed header map (pure, JVM-testable).
     * Returns the map unchanged when everything is legal; throws
     * [HeaderValidationException] otherwise.
     */
    fun validate(headers: Map<String, String>): Map<String, String> {
        if (headers.size > MAX_HEADERS) throw HeaderValidationException(Violation.TOO_MANY_HEADERS)
        for ((name, value) in headers) {
            if (name.isBlank()) throw HeaderValidationException(Violation.EMPTY_NAME)
            if (name.length > MAX_NAME_LENGTH) throw HeaderValidationException(Violation.NAME_TOO_LONG)
            if (value.length > MAX_VALUE_LENGTH) throw HeaderValidationException(Violation.VALUE_TOO_LONG)
            if (containsControlChar(name) || containsControlChar(value)) {
                throw HeaderValidationException(Violation.FORBIDDEN_CHAR)
            }
        }
        return headers
    }

    /**
     * True when [text] contains any ASCII control character (C0 range) or
     * DEL — a CR/LF would split the header line on the wire (request/header
     * injection), the remaining control characters are equally unsafe.
     */
    private fun containsControlChar(text: String): Boolean =
        text.any { it.code < 0x20 || it.code == 0x7F }

    /**
     * Download-path defense in depth: strips every control character from the
     * stored header names and values before they are handed to the HTTP
     * client. Rows written by older versions were validated leniently, so a
     * legacy stored value could theoretically still carry a CR/LF — this makes
     * it impossible to emit a smuggled header line regardless of what is
     * stored. A header whose stripped name becomes empty is dropped.
     */
    fun sanitizeForRequest(headers: Map<String, String>): Map<String, String> =
        headers.mapNotNull { (name, value) ->
            val cleanName = stripControlChars(name)
            if (cleanName.isBlank()) null else cleanName to stripControlChars(value)
        }.toMap()

    private fun stripControlChars(text: String): String =
        buildString(text.length) {
            for (ch in text) if (ch.code >= 0x20 && ch.code != 0x7F) append(ch)
        }

    /** JSON object map for at-rest storage (encrypt-then-store, see SecretsCipher). */
    fun toJson(headers: Map<String, String>): String = Json.encodeToString(headers)

    /** Human-facing `Name: Value` lines view (profile editor dialog). */
    fun toLines(headers: Map<String, String>): String =
        headers.entries.joinToString("\n") { (name, value) -> "$name: $value" }

    private fun linesToMap(trimmed: String): Map<String, String> =
        trimmed.lines()
            .map { it.trim() }
            .filter { it.contains(":") || it.contains("=") }
            .associate { line ->
                val delimiter = if (line.contains(":")) ":" else "="
                val parts = line.split(delimiter, limit = 2)
                parts[0].trim() to parts.getOrElse(1) { "" }.trim()
            }
}
