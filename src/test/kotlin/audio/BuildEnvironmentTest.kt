/*
 * Checks availability of SQLite JDBC, the JDK HTTP server, FFmpeg, and ffprobe.
 * Verifies required runtime components before testing application behavior.
 */
package audio

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

/** Connection and startup checks for dependencies in the Docker development stage. */
class BuildEnvironmentTest {
    /** Connects to in-memory SQLite and executes SQL. */
    @Test
    fun `SQLite JDBC can connect and execute SQL`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT 1").use { result ->
                    assertTrue(result.next())
                    assertEquals(1, result.getInt(1))
                }
            }
        }
    }

    /** Starts and stops the JDK HTTP server on an OS-selected free port. */
    @Test
    fun `JDK HTTP server is available`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.start()
            assertTrue(server.address.port > 0)
        } finally {
            server.stop(0)
        }
    }

    /** Checks that both commands are on PATH and exit successfully when reporting versions. */
    @Test
    fun `FFmpeg and ffprobe are available`() {
        for (executable in listOf("ffmpeg", "ffprobe")) {
            val process = ProcessBuilder(executable, "-version")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            // Bound the wait as well as checking the exit code to avoid a hung test.
            try {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue())
            } finally {
                process.destroyForcibly()
            }
        }
    }
}
