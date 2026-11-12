/*
 * Tests consistency between database metadata and audio files during storage and retrieval.
 * Covers conversion failures, database failures, unsafe paths, and concurrent requests.
 * Uses real SQLite, FFmpeg, and symbolic links in the Linux Docker environment.
 */
package audio

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Calls the service directly to verify storage behavior without HTTP. */
class AudioServiceTest {
    // Keep the test database and audio in separate locations under JUnit temporary storage.
    @TempDir
    lateinit var directory: Path
    private val database get() = directory.resolve("app.sqlite")
    private val storage get() = directory.resolve("audio")

    /** Creates an initialized test repository. */
    private fun repository() = AudioRepository(database).also { it.initialize() }

    /** Checks creation, retrieval, replacement, old-WAV deletion, and the latest audio. */
    @Test
    fun `store and retrieve return latest audio and remove replaced file`() {
        val repository = repository()
        val service = AudioService(repository, AudioConverter(), storage)
        val input = tone("first.m4a", 440)
        assertEquals(StoreOperation.Created, service.store(1, 1, input))
        val oldPath = storage.resolve(repository.find(1, 1)!!.path)
        assertTrue(Files.isRegularFile(oldPath))
        assertTrue(oldPath.fileName.toString().endsWith(".wav"))
        val timestamps = timestamps()
        // Retrieval must not update database timestamps.
        val firstResponse = service.retrieve(1, 1)
        assertEquals("aac", codec(firstResponse))
        assertEquals(timestamps, timestamps())
        assertEquals(StoreOperation.Replaced, service.store(1, 1, tone("next.m4a", 880)))
        assertFalse(Files.exists(oldPath))
        val latestResponse = service.retrieve(1, 1)
        assertEquals("aac", codec(latestResponse))
        assertFalse(Files.readAllBytes(firstResponse).contentEquals(Files.readAllBytes(latestResponse)))
        assertEquals(timestamps.first(), timestamps().first())
        // The service transfers ownership of temporary m4a output to its caller, so the test deletes it.
        Files.delete(firstResponse)
        Files.delete(latestResponse)
        assertTrue(Files.exists(input))
    }

    /** Preserves the previous recording, timestamps, and WAV after invalid-input conversion fails. */
    @Test
    fun `conversion failure keeps old recording and timestamps`() {
        val repository = repository()
        val service = AudioService(repository, AudioConverter(), storage)
        service.store(1, 1, tone("valid.m4a"))
        val old = repository.find(1, 1)
        val times = timestamps()
        val invalid = directory.resolve("bad.m4a")
        Files.writeString(invalid, "broken")
        assertThrows(InvalidAudioException::class.java) { service.store(1, 1, invalid) }
        assertEquals(old, repository.find(1, 1))
        assertEquals(times, timestamps())
        assertEquals(1, wavCount())
        assertTrue(Files.exists(service.retrieve(1, 1)))
    }

    /** Deletes the new WAV after a database update failure and keeps old audio retrievable. */
    @Test
    fun `database update failure removes new wav and keeps old recording`() {
        val repository = repository()
        val service = AudioService(repository, AudioConverter(), storage)
        val input = tone("valid.m4a")
        service.store(1, 1, input)
        val old = repository.find(1, 1)
        val times = timestamps()
        sql("CREATE TRIGGER reject_save BEFORE UPDATE ON recordings BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertThrows(SQLException::class.java) { service.store(1, 1, input) }
        assertEquals(old, repository.find(1, 1))
        assertEquals(times, timestamps())
        assertEquals(1, wavCount())
        assertEquals("aac", codec(service.retrieve(1, 1)))
    }

    /** Leaves no recording row or orphan WAV after the initial INSERT fails. */
    @Test
    fun `database insert failure leaves no recording or wav`() {
        val repository = repository()
        val service = AudioService(repository, AudioConverter(), storage)
        sql("CREATE TRIGGER reject_save BEFORE INSERT ON recordings BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertThrows(SQLException::class.java) { service.store(1, 1, tone("valid.m4a")) }
        assertNull(repository.find(1, 1))
        assertEquals(0, wavCount())
    }

    /** Distinguishes invalid IDs, unknown users, unknown phrases, and missing recordings. */
    @Test
    fun `unknown IDs and missing recordings are distinct`() {
        val service = AudioService(repository(), AudioConverter(), storage)
        val missingInput = directory.resolve("unused.m4a")
        assertThrows(InvalidIdException::class.java) { service.store(0, 1, missingInput) }
        assertThrows(UserNotFoundException::class.java) { service.store(999, 1, missingInput) }
        assertThrows(PhraseNotFoundException::class.java) { service.store(1, 999, missingInput) }
        assertThrows(UserNotFoundException::class.java) { service.retrieve(999, 1) }
        assertThrows(PhraseNotFoundException::class.java) { service.retrieve(1, 999) }
        assertThrows(RecordingNotFoundException::class.java) { service.retrieve(1, 1) }
        assertEquals(0, wavCount())
    }

    /** Rejects paths outside storage and symbolic links without touching external files. */
    @Test
    fun `database paths cannot escape storage or follow symlinks`() {
        val repository = repository()
        val service = AudioService(repository, AudioConverter(), storage)
        val outside = directory.resolve("outside.wav")
        Files.writeString(outside, "keep")
        repository.save(Recording(1, 1, "../outside.wav"))
        assertThrows(AudioStorageException::class.java) { service.retrieve(1, 1) }
        assertThrows(AudioStorageException::class.java) { service.store(1, 1, tone("valid.m4a")) }
        Files.createSymbolicLink(storage.resolve("linked.wav"), outside)
        repository.save(Recording(1, 1, "linked.wav"))
        assertThrows(AudioStorageException::class.java) { service.retrieve(1, 1) }
        assertEquals("keep", Files.readString(outside))
    }

    /** Treats missing or corrupt stored files as internal failures without leaving temporary output. */
    @Test
    fun `missing or corrupt stored files are internal failures`() {
        val repository = repository()
        val service = AudioService(repository, AudioConverter(), storage)
        repository.save(Recording(1, 1, "missing.wav"))
        assertThrows(AudioStorageException::class.java) { service.retrieve(1, 1) }
        Files.writeString(storage.resolve("bad.wav"), "broken")
        repository.save(Recording(1, 1, "bad.wav"))
        assertThrows(AudioConversionException::class.java) { service.retrieve(1, 1) }
        Files.list(storage.resolve(".tmp")).use { assertEquals(0, it.count()) }
    }

    /** Checks that concurrent replacement and retrieval on one service do not lose files. */
    @Test
    fun `concurrent replacement and retrieval do not lose files`() {
        val repository = repository()
        val service = AudioService(repository, AudioConverter(), storage)
        val input = tone("valid.m4a")
        service.store(1, 1, input)
        val executor = Executors.newFixedThreadPool(3)
        try {
            // Collect task results so exceptions within tasks also fail the test.
            val tasks = (1..6).map { index -> Callable {
                if (index % 2 == 0) {
                    service.store(1, 1, input)
                } else {
                    val response = service.retrieve(1, 1)
                    assertEquals("aac", codec(response))
                    Files.delete(response)
                }
            } }
            executor.invokeAll(tasks, 30, TimeUnit.SECONDS).forEach { it.get() }
            assertEquals(1, wavCount())
        } finally {
            executor.shutdownNow()
        }
    }

    /** Runs fixed test SQL, such as triggers used to simulate database failures. */
    private fun sql(command: String) = DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
        connection.createStatement().use { it.execute(command) }
    }

    /** Reads creation and update timestamps for the test recording directly. */
    private fun timestamps(): List<String> = DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT created_at, updated_at FROM recordings WHERE user_id=1 AND phrase_id=1").use {
                assertTrue(it.next())
                listOf(it.getString(1), it.getString(2))
            }
        }
    }

    /** Counts only WAV files directly in storage to detect output left after failed updates. */
    private fun wavCount(): Long = Files.list(storage).use { paths -> paths.filter { it.toString().endsWith(".wav") }.count() }

    /** Generates tones at different frequencies to distinguish audio before and after replacement. */
    private fun tone(name: String, frequency: Int = 440): Path {
        val path = directory.resolve(name)
        val process = ProcessBuilder("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=$frequency:duration=0.15", "-f", "ipod", path.toString())
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
        } finally { process.destroyForcibly() }
        return path
    }

    /** Checks that retrieved audio is actually encoded as AAC. */
    private fun codec(path: Path): String {
        val result = ProcessCommandRunner().run(
            listOf("ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=codec_name", "-of", "default=noprint_wrappers=1:nokey=1", path.toString()),
            java.time.Duration.ofSeconds(5),
        )
        assertEquals(0, result.exitCode)
        return result.stdout.trim()
    }
}
