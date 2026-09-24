package com.rushtify.app.data.music.potoken

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Regression tests for the 4.1.1 playback outage: YouTube's BotGuard
 * `Create` endpoint stopped returning a bare top-level array, so the
 * parser threw on every song, PO-token minting never succeeded, and all
 * InnerTube player clients failed over to the slow NewPipe fallback.
 */
class ChallengeParserTest {

    private val challengeArray =
        """["p0",["https://js"],["https://url"],"x","PROGRAM","GLOBAL"]"""

    private fun assertChallengePayload(output: String) {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(output).jsonObject
        assertEquals("PROGRAM", root["program"]!!.jsonPrimitive.content)
        assertEquals("GLOBAL", root["globalName"]!!.jsonPrimitive.content)
        val interpreter = root["interpreterJavascript"]!!.jsonObject
        assertEquals(
            "https://js",
            interpreter["privateDoNotAccessOrElseSafeScriptWrappedValue"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "https://url",
            interpreter["privateDoNotAccessOrElseTrustedResourceUrlWrappedValue"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun legacyCreateArrayParses() {
        assertChallengePayload(parseCreateChallenge("[$challengeArray]"))
    }

    @Test
    fun legacyScrambledCreateParses() {
        val scrambled = java.util.Base64.getEncoder().encodeToString(
            challengeArray.toByteArray(Charsets.UTF_8)
                .map { (it - 97).toByte() }
                .toByteArray(),
        )
        assertChallengePayload(parseCreateChallenge("""["ignore","$scrambled"]"""))
    }

    @Test
    fun objectWrappedCreateParses() {
        val raw = """{"status":"ok","data":{"challenge":[$challengeArray]}}"""
        assertChallengePayload(parseCreateChallenge(raw))
    }

    @Test
    fun bareStringInterpreterEntriesParse() {
        val raw = """[["p0","inline-js","inline-url","x","PROGRAM","GLOBAL"]]"""
        val root = kotlinx.serialization.json.Json.parseToJsonElement(parseCreateChallenge(raw)).jsonObject
        val interpreter = root["interpreterJavascript"]!!.jsonObject
        assertEquals(
            "inline-js",
            interpreter["privateDoNotAccessOrElseSafeScriptWrappedValue"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "inline-url",
            interpreter["privateDoNotAccessOrElseTrustedResourceUrlWrappedValue"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun errorObjectThrowsWithShapeOnly() {
        val canary = "SECRET_CANARY_123"
        val raw = """{"error":{"code":403,"message":"$canary"}}"""
        try {
            parseCreateChallenge(raw)
            fail("expected ChallengeFormatException")
        } catch (error: ChallengeFormatException) {
            assertTrue(error.message.orEmpty().contains("keys"))
            assertFalse(error.message.orEmpty().contains(canary))
        }
    }

    @Test
    fun emptyAndGarbageCreateThrows() {
        for (raw in listOf("", "   ")) {
            try {
                parseCreateChallenge(raw)
                fail("expected ChallengeFormatException for blank input")
            } catch (error: ChallengeFormatException) {
                assertTrue(error.message.orEmpty().contains("empty"))
            }
        }
        for (raw in listOf("not json {", "[]", "[[]]", "{}")) {
            try {
                parseCreateChallenge(raw)
                fail("expected ChallengeFormatException for $raw")
            } catch (error: ChallengeFormatException) {
                // Expected — message must never echo response content.
                assertFalse(error.message.orEmpty().contains(raw))
            }
        }
    }

    @Test
    fun legacyGenerateITParses() {
        val (tokenU8, lifetime) = parseIntegrityToken("""["aGVsbG8td29ybGQ=",21600]""")
        assertEquals("new Uint8Array([104,101,108,108,111,45,119,111,114,108,100])", tokenU8)
        assertEquals(21600L, lifetime)
    }

    // 48-char base64 ("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"); the
    // structural token matcher ignores short strings by design.
    private val longToken = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU2Nzg5"
    private val longTokenU8 =
        "new Uint8Array([65,66,67,68,69,70,71,72,73,74,75,76,77,78,79,80,81,82,83,84,85,86,87,88,89,90," +
            "48,49,50,51,52,53,54,55,56,57])"

    @Test
    fun objectGenerateITParses() {
        val raw = """{"integrityToken":"$longToken","ttlSeconds":7200}"""
        val (tokenU8, lifetime) = parseIntegrityToken(raw)
        assertEquals(longTokenU8, tokenU8)
        assertEquals(7200L, lifetime)
    }

    @Test
    fun nestedGenerateITPairParses() {
        val raw = """{"result":["aGVsbG8td29ybGQ=",3600]}"""
        val (_, lifetime) = parseIntegrityToken(raw)
        assertEquals(3600L, lifetime)
    }

    @Test
    fun generateITWithoutLifetimeThrows() {
        try {
            parseIntegrityToken("""{"integrityToken":"$longToken"}""")
            fail("expected ChallengeFormatException")
        } catch (error: ChallengeFormatException) {
            assertFalse(error.message.orEmpty().contains(longToken))
        }
    }

    @Test
    fun commaSeparatedBytesEncodeUrlSafeWithoutPadding() {
        assertEquals("aGU", commaSeparatedBytesToBase64("104, 101"))
    }
}
