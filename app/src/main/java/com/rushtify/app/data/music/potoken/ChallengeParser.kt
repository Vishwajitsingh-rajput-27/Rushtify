package com.rushtify.app.data.music.potoken

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

private val json = Json { ignoreUnknownKeys = true }

/**
 * Thrown when a BotGuard endpoint answers in a shape no known parser handles.
 * The message carries only structural info (kinds, keys, sizes) — never
 * response content, which may embed session material.
 */
class ChallengeFormatException(message: String) : IllegalArgumentException(message)

/**
 * Parses raw JSON responses from YouTube's `api/jnn/v1/Create` endpoint.
 *
 * Legacy shape: a top-level array (optionally with a scrambled payload in
 * slot [1]). Newer server rollouts wrap the same challenge array inside an
 * object — the parser finds it structurally (an array carrying the program /
 * global-name string slots) instead of assuming a fixed envelope.
 */
fun parseCreateChallenge(rawResponse: String): String {
    val root = parseJsonElement("Create", rawResponse)
    val challenge = when (root) {
        is JsonArray -> extractLegacyChallenge(root) ?: findChallengeArray(root)
        is JsonObject -> findChallengeArray(root)
        else -> null
    } ?: throw ChallengeFormatException(
        "Create response was ${describeElement(root)}; expected a top-level " +
            "array or an object holding a challenge array with program/globalName string slots [4]/[5]",
    )
    return buildChallengePayload(challenge)
}

/**
 * Parses raw response from YouTube's `api/jnn/v1/GenerateIT` endpoint.
 *
 * Legacy shape: `[tokenBase64, lifetimeSeconds]`. Object-wrapped rollouts
 * are read the same way: a nested legacy pair first, then named token +
 * lifetime fields matched structurally.
 */
fun parseIntegrityToken(rawResponse: String): Pair<String, Long> {
    val root = parseJsonElement("GenerateIT", rawResponse)
    val pair = when (root) {
        is JsonArray -> readTokenPair(root)
        is JsonObject -> findLegacyTokenArray(root)?.let(::readTokenPair) ?: findIntegrityFields(root)
        else -> null
    } ?: throw ChallengeFormatException(
        "GenerateIT response was ${describeElement(root)}; expected " +
            "[tokenBase64, lifetimeSeconds] or an object holding them",
    )
    return pair
}

/**
 * Converts a plain-string identifier to a JavaScript `Uint8Array(...)` literal.
 */
fun stringToJsUint8Array(identifier: String): String {
    val bytes = identifier.toByteArray(Charsets.UTF_8)
    return "new Uint8Array([${bytes.joinToString(",") { (it.toInt() and 0xFF).toString() }}])"
}

/**
 * Converts a comma-separated byte list (output of `Uint8Array.toString()` in JS)
 * to URL-safe Base64 encoding.
 */
fun commaSeparatedBytesToBase64(commaBytes: String): String {
    val bytes = commaBytes
        .split(",")
        .map { it.trim().toInt().toByte() }
        .toByteArray()
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** The exact legacy envelope, guarded: any shape deviation yields null. */
private fun extractLegacyChallenge(outer: JsonArray): JsonArray? = runCatching {
    if (outer.size > 1 && outer[1].jsonPrimitive.isString) {
        val decoded = descramble(outer[1].jsonPrimitive.content)
        json.parseToJsonElement(decoded).jsonArray
    } else {
        outer[0].jsonArray
    }
}.getOrNull()

private fun buildChallengePayload(challenge: JsonArray): String {
    if (challenge.size < 6) {
        throw ChallengeFormatException(
            "Create challenge array has size ${challenge.size}; need program/globalName string slots [4]/[5]",
        )
    }
    val program = (challenge[4] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw ChallengeFormatException(
            "Create challenge slot [4] is ${describeElement(challenge[4])}; need the program string",
        )
    val globalName = (challenge[5] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw ChallengeFormatException(
            "Create challenge slot [5] is ${describeElement(challenge[5])}; need the global-name string",
        )
    val interpreterJs = firstStringElement(challenge.getOrNull(1))
    val interpreterUrl = firstStringElement(challenge.getOrNull(2))

    return json.encodeToString(
        JsonObject.serializer(),
        JsonObject(
            mapOf(
                "program" to JsonPrimitive(program),
                "globalName" to JsonPrimitive(globalName),
                "interpreterJavascript" to JsonObject(
                    mapOf(
                        "privateDoNotAccessOrElseSafeScriptWrappedValue" to interpreterJs,
                        "privateDoNotAccessOrElseTrustedResourceUrlWrappedValue" to interpreterUrl,
                    ),
                ),
            ),
        ),
    )
}

/** A bare string or an array whose first string item wins; otherwise null. */
private fun firstStringElement(element: JsonElement?): JsonElement {
    if (element is JsonPrimitive && element.isString) return element
    if (element is JsonArray) {
        element.firstOrNull { it is JsonPrimitive && it.jsonPrimitive.isString }?.let { return it }
    }
    return JsonNull
}

/**
 * Deep search for a challenge-shaped array: at least 6 slots with strings
 * at [4] (program) and [5] (global name). Falls back to the legacy
 * scrambled-payload pattern nested anywhere — any string that descrambles
 * into such an array.
 */
private fun findChallengeArray(root: JsonElement): JsonArray? {
    walkJson(root).firstOrNull { it is JsonArray && isChallengeShaped(it) }?.let { return it as JsonArray }
    for (element in walkJson(root)) {
        if (element is JsonPrimitive && element.isString && element.content.length >= 16) {
            val decoded = runCatching {
                json.parseToJsonElement(descramble(element.content))
            }.getOrNull() as? JsonArray ?: continue
            if (isChallengeShaped(decoded)) return decoded
        }
    }
    return null
}

private fun isChallengeShaped(array: JsonArray): Boolean {
    if (array.size < 6) return false
    val program = array[4] as? JsonPrimitive ?: return false
    val globalName = array[5] as? JsonPrimitive ?: return false
    return program.isString && globalName.isString
}

private fun readTokenPair(array: JsonArray): Pair<String, Long>? = runCatching {
    if (array.size < 2) return@runCatching null
    base64ToJsUint8Array(array[0].jsonPrimitive.content) to array[1].jsonPrimitive.long
}.getOrNull()

/** A nested legacy `[token, lifetime]` pair anywhere inside an object. */
private fun findLegacyTokenArray(root: JsonElement): JsonArray? =
    walkJson(root).firstOrNull { element ->
        if (element !is JsonArray || element.size < 2) return@firstOrNull false
        val token = element.getOrNull(0) as? JsonPrimitive ?: return@firstOrNull false
        val lifetime = element.getOrNull(1) as? JsonPrimitive ?: return@firstOrNull false
        token.isString && runCatching { lifetime.long }.getOrNull() != null
    } as? JsonArray

/**
 * Named token + lifetime fields matched structurally: the most token-like
 * string (name hints, then length/charset) paired with a plausible lifetime
 * in seconds. Both are required — a guessed expiry would silently poison
 * the minter cache, so absence fails loudly instead.
 */
private fun findIntegrityFields(root: JsonObject): Pair<String, Long>? {
    var bestToken: Pair<String, Int>? = null
    var bestLifetime: Pair<Long, Int>? = null
    for ((key, value) in walkEntries(root)) {
        if (value is JsonPrimitive && value.isString) {
            val text = value.content
            if (isTokenLike(text)) {
                var score = text.length.coerceAtMost(1_000)
                if (TOKEN_NAME_HINTS.any { key.contains(it, ignoreCase = true) }) score += 10_000
                if (bestToken == null || score > bestToken.second) bestToken = text to score
            }
        } else if (value is JsonPrimitive && !value.isString) {
            val number = runCatching { value.long }.getOrNull() ?: continue
            if (number in MIN_TOKEN_LIFETIME_SEC..MAX_TOKEN_LIFETIME_SEC) {
                var score = 0
                if (LIFETIME_NAME_HINTS.any { key.contains(it, ignoreCase = true) }) score += 100
                if (bestLifetime == null || score > bestLifetime.second) bestLifetime = number to score
            }
        }
    }
    val token = bestToken?.first ?: return null
    val lifetime = bestLifetime?.first ?: return null
    return runCatching { base64ToJsUint8Array(token) to lifetime }.getOrNull()
}

private fun isTokenLike(text: String): Boolean {
    if (text.length < 32) return false
    if (text.contains("http", ignoreCase = true) || text.contains("www.") || text.any { it.isWhitespace() }) return false
    return text.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_' || it == '.' }
}

/** Iterative depth-first walk with a node budget — hostile nesting can't blow the stack. */
private fun walkJson(root: JsonElement, budget: Int = 20_000): Sequence<JsonElement> = sequence {
    val stack = ArrayDeque<JsonElement>()
    stack.addLast(root)
    var remaining = budget
    while (stack.isNotEmpty() && remaining-- > 0) {
        val current = stack.removeLast()
        yield(current)
        when (current) {
            is JsonObject -> current.values.forEach { stack.addLast(it) }
            is JsonArray -> current.forEach { stack.addLast(it) }
            else -> Unit
        }
    }
}

/** Same walk, keeping each value's key for name-hint scoring. */
private fun walkEntries(root: JsonElement, budget: Int = 20_000): Sequence<Pair<String, JsonElement>> = sequence {
    val stack = ArrayDeque<Pair<String, JsonElement>>()
    when (root) {
        is JsonObject -> root.entries.forEach { (key, value) -> stack.addLast(key to value) }
        is JsonArray -> root.forEachIndexed { index, value -> stack.addLast("#$index" to value) }
        else -> Unit
    }
    var remaining = budget
    while (stack.isNotEmpty() && remaining-- > 0) {
        val (key, current) = stack.removeLast()
        yield(key to current)
        when (current) {
            is JsonObject -> current.entries.forEach { (childKey, value) -> stack.addLast(childKey to value) }
            is JsonArray -> current.forEachIndexed { index, value -> stack.addLast("$key#$index" to value) }
            else -> Unit
        }
    }
}

private fun parseJsonElement(kind: String, rawResponse: String): JsonElement {
    if (rawResponse.isBlank()) throw ChallengeFormatException("$kind response was empty")
    return runCatching { json.parseToJsonElement(rawResponse) }.getOrNull()
        ?: throw ChallengeFormatException("$kind response was not JSON (length=${rawResponse.length})")
}

/** Structural summary only — kinds, key names, sizes. Never content. */
private fun describeElement(element: JsonElement): String = when (element) {
    is JsonArray -> "array(size=${element.size})"
    is JsonObject -> {
        val keys = element.keys.sorted().take(8)
        val suffix = if (element.size > keys.size) " +${element.size - keys.size} more" else ""
        "object(keys=[${keys.joinToString(", ")}]$suffix)"
    }
    is JsonNull -> "null"
    is JsonPrimitive -> if (element.isString) "string(len=${element.content.length})" else "primitive"
}

private fun descramble(base64Payload: String): String =
    base64ToByteArray(base64Payload)
        .map { (it + 97).toByte() }
        .toByteArray()
        .decodeToString()

private fun base64ToJsUint8Array(base64: String): String {
    val bytes = base64ToByteArray(base64)
    return "new Uint8Array([${bytes.joinToString(",") { (it.toInt() and 0xFF).toString() }}])"
}

private fun base64ToByteArray(base64: String): ByteArray {
    // java.util.Base64 (API 26+) keeps this file free of android.* imports
    // so the parser stays unit-testable on the JVM.
    val normalized = base64
        .replace('-', '+')
        .replace('_', '/')
        .replace('.', '=')
        .filter { !it.isWhitespace() }
    return java.util.Base64.getDecoder().decode(normalized)
}

private const val MIN_TOKEN_LIFETIME_SEC = 60L
private const val MAX_TOKEN_LIFETIME_SEC = 604_800L

private val TOKEN_NAME_HINTS = listOf("token", "integrity", "po_token", "challenge", "signature")
private val LIFETIME_NAME_HINTS = listOf("ttl", "expir", "lifetime", "timeout")
