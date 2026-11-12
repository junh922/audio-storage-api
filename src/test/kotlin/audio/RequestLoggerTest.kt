/*
 * Tests JSON log format, severity, and exception details limited to internal failures.
 * Captures output in a list without reading console or Docker logs.
 * Also checks that cleanup failures produce separate warning records.
 */
package audio

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Checks that quotes and embedded newlines still produce one physical JSON line. */
class RequestLoggerTest {
    /** Logs cleanup failures immediately without carrying warnings into later successful requests. */
    @Test
    fun `cleanup failures are logged immediately without request state`() {
        val lines = mutableListOf<String>()
        val logger = RequestLogger(sink = { lines.add(it) })
        logger.cleanupFailure(IllegalStateException("cleanup failed"))
        assertEquals(1, lines.size)
        val warning = Json.parseToJsonElement(lines.single()).jsonObject
        assertEquals("WARN", warning["level"]!!.jsonPrimitive.content)
        assertEquals("cleanup_failed", warning["event"]!!.jsonPrimitive.content)
        assertEquals("cleanup failed", warning["message"]!!.jsonPrimitive.content)
        assertFalse(warning.containsKey("stack_trace"))
        logger.log(RequestLog("next", "GET", 1, 1, 200, 0, "retrieved", 1))
        assertEquals(2, lines.size)
        val result = Json.parseToJsonElement(lines.last()).jsonObject
        assertEquals("INFO", result["level"]!!.jsonPrimitive.content)
        assertFalse(result.containsKey("cleanup_warnings"))
    }

    /** Compares success, 4xx, and 5xx logs and checks where exception details are included. */
    @Test
    fun `logs are one JSON line and exception details appear only for internal failure`() {
        val lines = mutableListOf<String>()
        // Capture output in a list to inspect the generated JSON.
        val logger = RequestLogger(sink = { lines.add(it) })
        logger.log(RequestLog("one", "POST", 1, 1, 201, 0, "created", 2))
        logger.log(RequestLog("two", "GET", 999, 1, 404, 2001, null, 1))
        logger.log(RequestLog("three", "POST", 1, 1, 500, 9002, null, 4), IllegalStateException("quoted \"value\"\nnext line"))
        assertEquals(3, lines.size)
        // Check both the absence of physical newlines and valid JSON parsing.
        val parsed = lines.map {
            assertFalse(it.contains('\n'))
            Json.parseToJsonElement(it).jsonObject
        }
        assertEquals("INFO", parsed[0]["level"]!!.jsonPrimitive.content)
        assertEquals("WARN", parsed[1]["level"]!!.jsonPrimitive.content)
        assertEquals("ERROR", parsed[2]["level"]!!.jsonPrimitive.content)
        assertFalse(parsed[0].containsKey("stack_trace"))
        assertFalse(parsed[1].containsKey("exception"))
        // The parsed stack trace restores newlines that were escaped in the JSON record.
        assertTrue(parsed[2]["stack_trace"]!!.jsonPrimitive.content.contains('\n'))
        assertTrue(parsed[2]["timestamp"]!!.jsonPrimitive.content.endsWith("Z"))
    }
}
