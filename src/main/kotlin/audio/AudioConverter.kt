/*
 * Inspects audio with ffprobe and converts between m4a and WAV with FFmpeg.
 * Uploads are stored as WAV; downloads are converted to AAC in an m4a container.
 * Delegates command execution to CommandRunner without handling HTTP or database access.
 */
package audio

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

// Distinguish invalid input, unsupported formats, timeouts, and internal failures.
/** The input is empty, has no audio, or cannot be decoded. */
class InvalidAudioException : RuntimeException("Audio file could not be decoded.")
/** The input container or stream layout does not meet the upload requirements. */
class UnsupportedAudioFormatException : RuntimeException("Only m4a audio files are supported.")
/** Audio inspection, conversion, or output collection exceeded the time limit. */
class AudioConversionTimeoutException : RuntimeException("Audio conversion timed out.")
/**
 * An internal audio processing failure, distinct from invalid upload data.
 *
 * @param message Description for internal logs.
 * @param cause Underlying exception, or null when there is none.
 */
open class AudioConversionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
/** Process termination could not be confirmed, so its output cannot be safely removed. */
class AudioProcessTerminationException : AudioConversionException("Audio process could not be terminated.")

/**
 * Inspects and converts audio within a shared time limit.
 *
 * @property timeout Shared limit for inspection and conversion; greater than zero and at most 30 seconds.
 * @property runner Command executor, replaceable in tests.
 * @throws IllegalArgumentException If timeout is outside the allowed range.
 */
class AudioConverter(
    private val timeout: Duration = Duration.ofSeconds(30),
    private val runner: CommandRunner = ProcessCommandRunner(),
) {
    init {
        // Reject nonpositive timeouts and values above 30 seconds.
        require(!timeout.isNegative && !timeout.isZero && timeout <= Duration.ofSeconds(30))
    }

    /**
     * Converts audio to the requested format at a new output path.
     *
     * For WAV output, validates an m4a upload; for m4a output, validates stored WAV data.
     * Removes partial output on failure unless process termination cannot be confirmed.
     *
     * @param input Regular source file, which is not deleted after conversion.
     * @param output New destination file; existing files and identical input/output paths are rejected.
     * @param outputFormat Output format: wav or m4a.
     * @throws IllegalArgumentException If the format is unsupported or normalized input/output paths match.
     * @throws InvalidAudioException If the input is empty, has no audio, or cannot be decoded.
     * @throws UnsupportedAudioFormatException If the upload does not meet the m4a requirements for WAV output.
     * @throws AudioConversionTimeoutException If inspection or conversion exceeds the shared deadline.
     * @throws AudioProcessTerminationException If process termination cannot be confirmed.
     * @throws AudioConversionException If output already exists, stored WAV is invalid, or another conversion failure occurs.
     * @throws java.io.IOException If the initial size check or destination file creation fails.
     */
    fun convert(input: Path, output: Path, outputFormat: String) {
        require(outputFormat == "wav" || outputFormat == "m4a")
        val source = input.toAbsolutePath().normalize()
        val destination = output.toAbsolutePath().normalize()
        // Prevent overwriting the input and reject missing, nonregular, or empty files.
        require(source != destination)
        if (!Files.isRegularFile(source) || Files.size(source) == 0L) {
            throw InvalidAudioException()
        }
        try {
            // Reserve the destination atomically so FFmpeg cannot overwrite an existing file.
            Files.createFile(destination)
        } catch (exception: FileAlreadyExistsException) {
            throw AudioConversionException("Output file already exists.", exception)
        }
        // Use elapsed time, unaffected by changes to the wall clock.
        val started = System.nanoTime()
        // Share one deadline between ffprobe and ffmpeg rather than allowing 30 seconds each.
        /**
         * Calculates the time left before the shared deadline.
         *
         * @return Remaining time after subtracting elapsed time since inspection started.
         * @throws AudioConversionTimeoutException If the deadline has been reached.
         */
        fun remaining(): Duration {
            val remaining = timeout.minusNanos(System.nanoTime() - started)
            if (remaining.isNegative || remaining.isZero) {
                throw AudioConversionTimeoutException()
            }
            return remaining
        }
        try {
            // Inspect actual streams and container format rather than trusting the extension.
            // Restrict input protocols to local files.
            val metadata = runner.run(
                listOf(
                    "ffprobe", "-v", "error", "-protocol_whitelist", "file",
                    "-format_whitelist", "mov,wav,mp3,ogg,flac,aac",
                    "-show_entries", "stream=codec_type:format=format_name:format_tags=major_brand",
                    "-of", "default=noprint_wrappers=1", source.toString(),
                ),
                remaining(),
            )
            if (metadata.exitCode != 0) {
                throw InvalidAudioException()
            }
            // Read ffprobe output as lines of key=value pairs.
            val fields = metadata.stdout.lineSequence().toList()
            if (fields.none { it == "codec_type=audio" }) {
                throw InvalidAudioException()
            }
            val formats = fields.firstOrNull { it.startsWith("format_name=") }
                ?.substringAfter('=')?.split(',').orEmpty()
            if (outputFormat == "wav") {
                // Validate the container and brand, and reject files containing video.
                val brand = fields.firstOrNull { it.startsWith("TAG:major_brand=") }?.substringAfter('=')?.trim()
                if ("m4a" !in formats || brand !in setOf("M4A", "isom", "iso2", "mp41", "mp42") || "codec_type=video" in fields) {
                    throw UnsupportedAudioFormatException()
                }
            } else if ("wav" !in formats) {
                throw AudioConversionException("Stored audio is not WAV.")
            }
            // Select the container and codec for the conversion direction.
            // Use mov to read m4a and ipod as the FFmpeg output format for m4a.
            val inputFormat = if (outputFormat == "wav") { "mov" } else { "wav" }
            val codec = if (outputFormat == "wav") { "pcm_s16le" } else { "aac" }
            val muxer = if (outputFormat == "wav") { "wav" } else { "ipod" }
            // Pass arguments directly without shell expansion.
            // Select the first audio stream and remove video and metadata.
            val result = runner.run(
                listOf(
                    "ffmpeg", "-nostdin", "-v", "error", "-xerror", "-y",
                    "-protocol_whitelist", "file", "-f", inputFormat, "-i", source.toString(),
                    "-map", "0:a:0", "-vn", "-map_metadata", "-1", "-c:a", codec,
                    "-f", muxer, destination.toString(),
                ),
                remaining(),
            )
            if (result.exitCode != 0) {
                // Upload decoding failures are input errors; stored WAV conversion failures are internal errors.
                if (outputFormat == "wav") {
                    throw InvalidAudioException()
                }
                throw AudioConversionException("Stored audio conversion failed.")
            }
            if (Files.size(destination) == 0L) {
                throw AudioConversionException("Audio converter produced no output.")
            }
        } catch (exception: Exception) {
            // Remove partial output unless process termination is unconfirmed.
            // The process may still be writing, so leave that output for later recovery.
            if (exception !is AudioProcessTerminationException) {
                try {
                    Files.deleteIfExists(destination)
                } catch (cleanupException: Exception) {
                    exception.addSuppressed(cleanupException)
                }
            }
            when (exception) {
                // Preserve known failures and wrap unexpected exceptions as conversion failures.
                is InvalidAudioException, is UnsupportedAudioFormatException,
                is AudioConversionTimeoutException, is AudioConversionException -> throw exception
                else -> throw AudioConversionException("Audio conversion failed.", exception)
            }
        }
    }
}
