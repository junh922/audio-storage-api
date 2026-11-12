/*
 * Connects to SQLite, initializes tables, and reads and writes recording metadata.
 * Stores IDs and filenames in the database while keeping audio content in separate files.
 */
package audio

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.format.DateTimeFormatterBuilder

/**
 * Recording metadata passed between the repository and service.
 *
 * Use Long IDs to match SQLite's signed 64-bit integer range.
 *
 * @property userId ID of the user who owns the recording.
 * @property phraseId ID of the recorded phrase.
 * @property path Relative filename; the service generates a UUID-based WAV name.
 */
data class Recording(val userId: Long, val phraseId: Long, val path: String)

/**
 * Accesses SQLite through JDBC without an ORM.
 *
 * Accepts a clock so tests can use a fixed time.
 * [AudioService] handles audio placement and filename validation.
 *
 * @param databasePath SQLite file path, normalized without opening the database in the constructor.
 * @property clock Source of creation and update times; defaults to the system clock.
 */
class AudioRepository(
    databasePath: Path,
    private val clock: Clock = Clock.systemUTC(),
) {
    // Normalize the database location to an absolute path.
    private val databasePath = databasePath.toAbsolutePath().normalize()
    // Use UTC ISO 8601 with exactly three fractional digits, such as 2026-11-08T01:23:45.123Z.
    private val timestampFormat = DateTimeFormatterBuilder().appendInstant(3).toFormatter()

    /**
     * Creates required tables and seeds master IDs 1 through 3.
     *
     * Preserves existing recordings on repeated calls and rolls back failed database operations.
     *
     * @throws java.io.IOException If the database's parent directory cannot be created.
     * @throws java.sql.SQLException If connection, table creation, or seeding fails.
     */
    fun initialize() {
        Files.createDirectories(databasePath.parent)
        // Roll back schema and seed changes together if initialization fails.
        transaction { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY CHECK (id > 0))")
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS phrases (id INTEGER PRIMARY KEY CHECK (id > 0))")
                // Allow only one recording per user/phrase pair through the composite primary key.
                // Foreign keys reject unknown master IDs; timestamps belong only to recordings.
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS recordings (
                        user_id INTEGER NOT NULL,
                        phrase_id INTEGER NOT NULL,
                        file_path TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY (user_id, phrase_id),
                        FOREIGN KEY (user_id) REFERENCES users (id),
                        FOREIGN KEY (phrase_id) REFERENCES phrases (id)
                    )
                    """.trimIndent(),
                )
            }
            for (table in listOf("users", "phrases")) {
                // Use only fixed table names and bind ID values as parameters.
                // Keep existing seed rows unchanged on conflict.
                connection.prepareStatement("INSERT INTO $table (id) VALUES (?) ON CONFLICT(id) DO NOTHING").use { statement ->
                    for (id in 1L..3L) {
                        statement.setLong(1, id)
                        statement.executeUpdate()
                    }
                }
            }
        }
    }

    /**
     * Checks whether a user exists.
     *
     * @param id User ID to look up.
     * @return True if the user is registered.
     * @throws java.sql.SQLException If database connection or lookup fails.
     */
    fun userExists(id: Long): Boolean = exists("SELECT 1 FROM users WHERE id = ?", id)

    /**
     * Checks whether a phrase exists.
     *
     * @param id Phrase ID to look up.
     * @return True if the phrase is registered.
     * @throws java.sql.SQLException If database connection or lookup fails.
     */
    fun phraseExists(id: Long): Boolean = exists("SELECT 1 FROM phrases WHERE id = ?", id)

    /**
     * Finds recording metadata for a user/phrase pair.
     *
     * @param userId User ID.
     * @param phraseId Phrase ID.
     * @return Recording metadata, or null when absent; does not check the audio file.
     * @throws java.sql.SQLException If database connection or lookup fails.
     */
    fun find(userId: Long, phraseId: Long): Recording? = connect().use { connection ->
        connection.prepareStatement("SELECT file_path FROM recordings WHERE user_id = ? AND phrase_id = ?").use { statement ->
            statement.setLong(1, userId)
            statement.setLong(2, phraseId)
            statement.executeQuery().use { result ->
                if (result.next()) { Recording(userId, phraseId, result.getString("file_path")) } else { null }
            }
        }
    }

    /**
     * Inserts recording metadata or replaces the entry for an existing ID pair.
     *
     * Replacement updates file_path and updated_at while preserving created_at.
     *
     * @param recording IDs and filename to save; the caller must place the audio file first.
     * @throws java.sql.SQLException If saving fails, including foreign key violations for unknown master IDs.
     */
    fun save(recording: Recording) {
        val timestamp = timestampFormat.format(clock.instant())
        transaction { connection ->
            // Insert or update atomically with one UPSERT statement.
            // SQLite's excluded row contains the values proposed by the INSERT.
            connection.prepareStatement(
                """
                INSERT INTO recordings (user_id, phrase_id, file_path, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(user_id, phrase_id) DO UPDATE SET
                    file_path = excluded.file_path,
                    updated_at = excluded.updated_at
                """.trimIndent(),
            ).use { statement ->
                // Bind values instead of concatenating them into SQL.
                statement.setLong(1, recording.userId)
                statement.setLong(2, recording.phraseId)
                statement.setString(3, recording.path)
                statement.setString(4, timestamp)
                statement.setString(5, timestamp)
                statement.executeUpdate()
            }
        }
    }

    /**
     * Runs a shared master-record existence check.
     *
     * @param sql Fixed SELECT statement with one ID placeholder.
     * @param id ID to bind to the search condition.
     * @return True if the query returns at least one row.
     */
    private fun exists(sql: String, id: Long): Boolean = connect().use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().use { it.next() }
        }
    }

    /**
     * Opens a database connection with foreign keys and a lock wait timeout enabled.
     *
     * @return Connection that the caller must close, for example with use.
     * @throws java.sql.SQLException If connection or connection setup fails.
     */
    private fun connect(): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite:$databasePath")
        try {
            connection.createStatement().use { statement ->
                // SQLite foreign key enforcement must be enabled for every connection.
                statement.execute("PRAGMA foreign_keys = ON")
                // Wait up to five seconds for another writer before reporting a lock error.
                statement.execute("PRAGMA busy_timeout = 5000")
            }
            return connection
        } catch (exception: Exception) {
            connection.close()
            throw exception
        }
    }

    /**
     * Executes the supplied database action in a transaction.
     *
     * @param action Operations to commit on success; exceptions trigger a rollback attempt and are rethrown.
     */
    private fun transaction(action: (Connection) -> Unit) {
        connect().use { connection ->
            // Disable per-statement commits and commit only after the whole action succeeds.
            connection.autoCommit = false
            try {
                action(connection)
                connection.commit()
            } catch (exception: Exception) {
                try {
                    connection.rollback()
                } catch (rollbackException: Exception) {
                    // Keep the original database error and attach the rollback failure as suppressed.
                    exception.addSuppressed(rollbackException)
                }
                throw exception
            }
        }
    }
}
