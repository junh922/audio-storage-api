/*
 * Tests format inspection, m4a/WAV conversion, deadlines, and failed-output cleanup.
 * Combines real FFmpeg checks with replaceable command runners for failure scenarios.
 * Some tests use sleep and sh and run in the Linux Docker development stage.
 */
package audio

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Generates short synthetic tones as real input files. */
class AudioConverterTest {
    // Use JUnit temporary storage rather than the application's /data.
    @TempDir
    lateinit var directory: Path

    /** Checks PCM WAV storage and AAC m4a output through a round-trip conversion. */
    @Test
    fun `m4a converts to PCM wav and back to AAC m4a`() {
        val input = tone("input.m4a")
        val wav = directory.resolve("stored.wav")
        val output = directory.resolve("response.m4a")
        val converter = AudioConverter()
        converter.convert(input, wav, "wav")
        converter.convert(wav, output, "m4a")
        assertTrue(Files.size(wav) > 0)
        assertTrue(Files.size(output) > 0)
        assertEquals("pcm_s16le", codec(wav))
        assertEquals("aac", codec(output))
    }

    /** Rejects a WAV renamed with an m4a extension based on its actual format. */
    @Test
    fun `renamed wav is rejected as an m4a upload`() {
        val input = tone("renamed.m4a", "wav")
        val output = directory.resolve("output.wav")
        assertThrows(UnsupportedAudioFormatException::class.java) {
            AudioConverter().convert(input, output, "wav")
        }
        assertFalse(Files.exists(output))
    }

    /** Rejects empty and non-audio input without leaving output. */
    @Test
    fun `broken and empty inputs are rejected without output`() {
        for (content in listOf(byteArrayOf(), "not audio".toByteArray())) {
            val input = directory.resolve("broken.m4a")
            Files.write(input, content)
            val output = directory.resolve("output.wav")
            assertThrows(InvalidAudioException::class.java) { AudioConverter().convert(input, output, "wav") }
            assertFalse(Files.exists(output))
        }
    }

    /** Rejects a container containing video but no audio stream. */
    @Test
    fun `container without audio is rejected`() {
        val input = directory.resolve("video.m4a")
        command("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "color=size=16x16:rate=1", "-t", "1", "-an", "-c:v", "mpeg4", "-f", "mp4", input.toString())
        assertThrows(InvalidAudioException::class.java) {
            AudioConverter().convert(input, directory.resolve("out.wav"), "wav")
        }
    }

    /** Rejects unsupported formats and identical paths before conversion. */
    @Test
    fun `unsupported output format and identical paths are rejected before execution`() {
        val input = tone("input.m4a")
        assertThrows(IllegalArgumentException::class.java) { AudioConverter().convert(input, directory.resolve("out.mp3"), "mp3") }
        assertThrows(IllegalArgumentException::class.java) { AudioConverter().convert(input, input, "wav") }
        assertTrue(Files.size(input) > 0)
    }

    /** Preserves an existing destination file instead of overwriting it. */
    @Test
    fun `preexisting output is preserved`() {
        val input = tone("input.m4a")
        val output = directory.resolve("old.wav")
        Files.writeString(output, "keep")
        assertThrows(AudioConversionException::class.java) { AudioConverter().convert(input, output, "wav") }
        assertEquals("keep", Files.readString(output))
    }

    /** Uses a failing runner to verify removal of partial output. */
    @Test
    fun `failed conversion removes partial output`() {
        val input = tone("input.m4a")
        val output = directory.resolve("partial.wav")
        val runner = CommandRunner { args, _ ->
            // Let ffprobe succeed, then fail the simulated ffmpeg conversion.
            if (args.first() == "ffprobe") {
                CommandResult(0, "codec_type=audio\nformat_name=mov,mp4,m4a,3gp,3g2,mj2\nTAG:major_brand=M4A \n", "")
            } else {
                Files.writeString(output, "partial")
                CommandResult(1, "", "decode failed")
            }
        }
        assertThrows(InvalidAudioException::class.java) { AudioConverter(runner = runner).convert(input, output, "wav") }
        assertFalse(Files.exists(output))
    }

    /** Checks timeout cleanup and that the supplied time budget stays within its limit. */
    @Test
    fun `timeout removes partial output and uses a shared deadline`() {
        val input = tone("input.m4a")
        val output = directory.resolve("partial.wav")
        val runner = CommandRunner { _, remaining ->
            assertTrue(remaining <= Duration.ofSeconds(30))
            Files.writeString(output, "partial")
            throw AudioConversionTimeoutException()
        }
        assertThrows(AudioConversionTimeoutException::class.java) { AudioConverter(runner = runner).convert(input, output, "wav") }
        assertFalse(Files.exists(output))
    }

    /** Starts a real waiting process and verifies it is no longer alive after timeout. */
    @Test
    fun `process timeout terminates the child`() {
        var child: Process? = null
        val runner = ProcessCommandRunner { args -> ProcessBuilder(args).start().also { child = it } }
        assertThrows(AudioConversionTimeoutException::class.java) {
            runner.run(listOf("sleep", "5"), Duration.ofMillis(100))
        }
        assertFalse(child!!.isAlive)
    }

    /** Checks that inspection reduces the time available for conversion. */
    @Test
    fun `probing reduces the remaining conversion deadline`() {
        val input = tone("input.m4a")
        val output = directory.resolve("out.wav")
        val budgets = mutableListOf<Duration>()
        val runner = CommandRunner { args, remaining ->
            budgets.add(remaining)
            if (args.first() == "ffprobe") {
                CommandResult(0, "codec_type=audio\nformat_name=mov,mp4,m4a,3gp,3g2,mj2\nTAG:major_brand=M4A \n", "")
            } else {
                Files.writeString(output, "converted")
                CommandResult(0, "", "")
            }
        }
        AudioConverter(runner = runner).convert(input, output, "wav")
        assertEquals(2, budgets.size)
        assertTrue(budgets[1] < budgets[0])
    }

    /** Leaves output for recovery when process termination cannot be confirmed. */
    @Test
    fun `unconfirmed termination leaves output for later recovery`() {
        val input = tone("input.m4a")
        val output = directory.resolve("partial.wav")
        val runner = CommandRunner { _, _ ->
            Files.writeString(output, "partial")
            throw AudioProcessTerminationException()
        }
        assertThrows(AudioProcessTerminationException::class.java) { AudioConverter(runner = runner).convert(input, output, "wav") }
        assertTrue(Files.exists(output))
    }

    /** Drains large stderr output without blocking and retains at most 64 KiB. */
    @Test
    fun `large stderr is drained while captured output is bounded`() {
        val result = ProcessCommandRunner().run(
            listOf("sh", "-c", "head -c 200000 /dev/zero >&2; printf done"),
            Duration.ofSeconds(5),
        )
        assertEquals(0, result.exitCode)
        assertEquals("done", result.stdout)
        assertTrue(result.stderr.length <= 65536)
    }

    /** Creates a 440 Hz, 0.15-second tone without external test assets. */
    private fun tone(name: String, format: String = "ipod"): Path {
        val path = directory.resolve(name)
        command("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=0.15", "-f", format, path.toString())
        return path
    }

    /** Checks the actual codec with ffprobe rather than relying on the extension. */
    private fun codec(path: Path): String {
        val process = ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=codec_name", "-of", "default=noprint_wrappers=1:nokey=1", path.toString()).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
        return output.trim()
    }

    /** Runs a test-asset command and checks successful completion within its time limit. */
    private fun command(vararg args: String) {
        val process = ProcessBuilder(*args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
        } finally {
            process.destroyForcibly()
        }
    }
}
