/*
 * Tests SQLite schema, seeds, recordings, timestamps, and constraints.
 * Uses a temporary database and fixed clocks without relying on real data or current time.
 */
package audio

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Uses real SQLite JDBC and test triggers to verify rollback on failure. */
class AudioRepositoryTest {
    // Create the database inside a per-test temporary directory.
    @TempDir
    lateinit var directory: Path

    private val database get() = directory.resolve("nested/app.sqlite")
    private val first = Instant.parse("2026-11-08T01:23:45.123Z")
    private val later = Instant.parse("2026-11-08T02:34:56.789Z")

    /** Uses a Japan-time-zone clock to verify timestamps are still stored in UTC. */
    private fun repository(at: Instant = first) =
        AudioRepository(database, Clock.fixed(at, ZoneOffset.ofHours(9)))

    /** Checks master IDs, columns, and the initial absence of recordings. */
    @Test
    fun `initialization creates id only masters and seeds valid IDs`() {
        val repository = repository()
        repository.initialize()
        for (id in 1L..3L) {
            assertTrue(repository.userExists(id))
            assertTrue(repository.phraseExists(id))
        }
        assertFalse(repository.userExists(999))
        assertFalse(repository.phraseExists(999))
        assertEquals(listOf("id"), columns("users"))
        assertEquals(listOf("id"), columns("phrases"))
        assertEquals(
            listOf("user_id", "phrase_id", "file_path", "created_at", "updated_at"),
            columns("recordings"),
        )
        assertNull(repository.find(1, 1))
    }

    /** Checks persistence across repository instances and UTC millisecond timestamps. */
    @Test
    fun `saving persists a recording and UTC millisecond timestamps`() {
        val repository = repository()
        repository.initialize()
        val recording = Recording(1, 2, "first.wav")
        repository.save(recording)
        assertEquals(recording, repository().find(1, 2))
        assertEquals(listOf(first.toString(), first.toString()), timestamps(1, 2))
        assertNull(repository.find(2, 1))
    }

    /** Replaces only the target pair while preserving creation time and other users' recordings. */
    @Test
    fun `replacement preserves creation time and changes only the matching pair`() {
        val repository = repository()
        repository.initialize()
        repository.save(Recording(1, 1, "old.wav"))
        repository.save(Recording(2, 1, "other.wav"))
        repository(later).save(Recording(1, 1, "new.wav"))
        assertEquals("new.wav", repository.find(1, 1)?.path)
        assertEquals("other.wav", repository.find(2, 1)?.path)
        assertEquals(listOf(first.toString(), later.toString()), timestamps(1, 1))
        assertEquals(listOf(first.toString(), first.toString()), timestamps(2, 1))
        assertEquals(2, rowCount())
    }

    /** Reinitializes without deleting recordings and restores only missing seed rows. */
    @Test
    fun `reinitialization keeps recordings and restores only missing seed rows`() {
        val repository = repository()
        repository.initialize()
        repository.save(Recording(1, 1, "kept.wav"))
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { it.executeUpdate("DELETE FROM phrases WHERE id = 3") }
        }
        repository(later).initialize()
        assertTrue(repository.phraseExists(3))
        assertEquals("kept.wav", repository.find(1, 1)?.path)
        assertEquals(listOf(first.toString(), first.toString()), timestamps(1, 1))
    }

    /** Checks foreign key enforcement on every save connection. */
    @Test
    fun `foreign keys reject unknown users and phrases on every save connection`() {
        val repository = repository()
        repository.initialize()
        assertThrows(SQLException::class.java) { repository.save(Recording(999, 1, "bad.wav")) }
        assertThrows(SQLException::class.java) { repository.save(Recording(1, 999, "bad.wav")) }
        assertEquals(0, rowCount())
        repository.save(Recording(1, 1, "valid.wav"))
        assertEquals(1, rowCount())
    }

    /** Treats SQL-like strings as parameter values rather than executable SQL. */
    @Test
    fun `path values are bound as SQL parameters`() {
        val repository = repository()
        repository.initialize()
        val path = "audio'; DROP TABLE users; --.wav"
        repository.save(Recording(1, 1, path))
        assertEquals(path, repository.find(1, 1)?.path)
        assertTrue(repository.userExists(1))
    }

    /** Forces an UPDATE failure and verifies rollback of the path and timestamps. */
    @Test
    fun `failed replacement rolls back path and timestamps`() {
        val repository = repository()
        repository.initialize()
        repository.save(Recording(1, 1, "old.wav"))
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use {
                it.execute("CREATE TRIGGER reject_update BEFORE UPDATE ON recordings BEGIN SELECT RAISE(ABORT, 'test failure'); END")
            }
        }
        assertThrows(SQLException::class.java) { repository(later).save(Recording(1, 1, "new.wav")) }
        assertEquals("old.wav", repository.find(1, 1)?.path)
        assertEquals(listOf(first.toString(), first.toString()), timestamps(1, 1))
    }

    /** Checks exactly three fractional digits, including .000 for whole seconds. */
    @Test
    fun `timestamps always contain exactly three fractional digits`() {
        val repository = repository()
        repository.initialize()
        repository(Instant.parse("2026-11-08T03:00:00Z")).save(Recording(1, 1, "whole.wav"))
        assertEquals(listOf("2026-11-08T03:00:00.000Z", "2026-11-08T03:00:00.000Z"), timestamps(1, 1))
        repository(Instant.parse("2026-11-08T04:00:00.123456789Z")).save(Recording(1, 1, "fraction.wav"))
        assertEquals(listOf("2026-11-08T03:00:00.000Z", "2026-11-08T04:00:00.123Z"), timestamps(1, 1))
    }

    /** Checks rollback of earlier schema and seed operations when initialization fails. */
    @Test
    fun `initialization failure rolls back earlier schema and seed changes`() {
        Files.createDirectories(database.parent)
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use {
                it.execute("CREATE TABLE users (id INTEGER PRIMARY KEY CHECK (id > 0))")
                it.execute("CREATE TRIGGER reject_seed BEFORE INSERT ON users WHEN NEW.id = 2 BEGIN SELECT RAISE(ABORT, 'test failure'); END")
            }
        }
        assertThrows(SQLException::class.java) { repository().initialize() }
        assertFalse(repository().userExists(1))
        assertTrue(columns("phrases").isEmpty())
        assertTrue(columns("recordings").isEmpty())
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { it.execute("DROP TRIGGER reject_seed") }
        }
        repository().initialize()
        assertTrue(repository().userExists(1))
        assertTrue(repository().phraseExists(3))
    }

    /** Reads column names from SQLite metadata for assertions. */
    private fun columns(table: String): List<String> =
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info($table)").use { result ->
                    buildList { while (result.next()) { add(result.getString("name")) } }
                }
            }
        }

    /** Reads timestamps directly because repository results do not include them. */
    private fun timestamps(userId: Long, phraseId: Long): List<String> =
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.prepareStatement("SELECT created_at, updated_at FROM recordings WHERE user_id = ? AND phrase_id = ?").use { statement ->
                statement.setLong(1, userId)
                statement.setLong(2, phraseId)
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    listOf(result.getString(1), result.getString(2))
                }
            }
        }

    /** Counts rows to detect records left behind by a failed save. */
    private fun rowCount(): Int = DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM recordings").use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }
}
