/*
 * Tests defaults, environment overrides, and port validation in AppConfig.
 * Supplies maps to reproduce settings without changing the process environment.
 */
package audio

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Verifies startup defaults and input validation. */
class AppConfigTest {
    /** Checks default and explicit settings and rejects invalid ports. */
    @Test
    fun `configuration validates port and resolves the data directory`() {
        assertEquals(8080, AppConfig.fromEnvironment(emptyMap()).port)
        val configured = AppConfig.fromEnvironment(mapOf("PORT" to "9090", "DATA_DIR" to "custom-data"))
        assertEquals(9090, configured.port)
        assertTrue(configured.dataDir.isAbsolute)
        assertEquals("custom-data", configured.dataDir.fileName.toString())
        // Cover out-of-range values and values that cannot be parsed as integers.
        for (port in listOf("", "abc", "0", "-1", "65536")) {
            assertThrows(IllegalArgumentException::class.java) { AppConfig.fromEnvironment(mapOf("PORT" to port)) }
        }
    }
}
