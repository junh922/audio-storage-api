/*
 * Reads a single m4a file named audio_file from a multipart/form-data POST body.
 * Validates size, part count, and filename, then streams the upload to a temporary file.
 * AudioConverter checks the actual audio format later.
 */
package audio

import com.sun.net.httpserver.HttpExchange
import org.apache.commons.fileupload2.core.*
import java.io.InputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.FileSystemException
import java.util.Locale

/**
 * Receives uploads as a stream and removes temporary files if parsing fails.
 *
 * @property logger Output for warnings when temporary file deletion fails.
 */
class MultipartAudioReader(
    private val logger: RequestLogger = RequestLogger(),
) {
    companion object {
        // Allow 10 MiB of audio plus 64 KiB for multipart headers and boundaries.
        /** Maximum size of one audio file in bytes (10 MiB). */
        const val MAX_AUDIO_BYTES = 10L * 1024 * 1024
        /** Maximum body size including multipart headers and boundaries. */
        const val MAX_REQUEST_BYTES = MAX_AUDIO_BYTES + 64 * 1024
    }

    /**
     * Returns a temporary upload path and transfers cleanup ownership to the caller.
     *
     * Validate bytes actually read rather than trusting Content-Length alone.
     *
     * @param exchange HTTP request to parse as multipart/form-data.
     * @param tempDir Temporary upload directory, created if necessary.
     * @return Path to a nonempty m4a upload; [AudioConverter] checks its actual format later.
     * @throws HttpFailure If input validation fails, including missing parts, malformed multipart, unsupported format, or size limits.
     * @throws java.nio.file.FileSystemException If directory or temporary file operations fail.
     */
    fun read(exchange: HttpExchange, tempDir: Path): Path {
        val type = exchange.requestHeaders.getFirst("Content-Type") ?: ""
        // Compare the media type without parameters such as boundary.
        if (!type.substringBefore(';').trim().equals("multipart/form-data", ignoreCase = true)) {
            throw HttpFailure(415, 3003, "Content-Type must be multipart/form-data.")
        }
        val length = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull() ?: -1
        if (length > MAX_REQUEST_BYTES) { throw uploadTooLarge() }
        // Limit actual bytes read even for chunked requests without Content-Length.
        val limited = LimitedInput(exchange.requestBody, MAX_REQUEST_BYTES)
        // Adapt the JDK request to the RequestContext expected by Commons FileUpload.
        val context = object : RequestContext {
            override fun getCharacterEncoding(): String = "UTF-8"
            override fun getContentLength(): Long = length
            override fun getContentType(): String = type
            override fun getInputStream(): InputStream = limited
            override fun isMultipartRelated(): Boolean = false
        }
        // Use the streaming iterator rather than loading all parts through parseRequest.
        val upload = object : AbstractFileUpload<Unit, DiskFileItem, DiskFileItemFactory>() {
            override fun getItemIterator(request: Unit): FileItemInputIterator = super.getItemIterator(context)
            override fun parseRequest(request: Unit): MutableList<DiskFileItem> = super.parseRequest(context)
            override fun parseParameterMap(request: Unit): MutableMap<String, MutableList<DiskFileItem>> = super.parseParameterMap(context)
        }
        // Also enforce size and part-header limits through the library.
        upload.setMaxFileSize(MAX_AUDIO_BYTES)
        upload.setMaxSize(MAX_REQUEST_BYTES)
        upload.setMaxPartHeaderSize(8192)
        upload.setMaxFileCount(1)
        var path: Path? = null
        try {
            val items = upload.getItemIterator(context)
            var count = 0
            while (items.hasNext()) {
                val item = items.next()
                count++
                // Reject text fields, unexpected field names, and multiple files.
                if (count > 1 || item.isFormField || item.fieldName != "audio_file") { throw invalidUpload() }
                val filename = item.name ?: throw invalidUpload()
                if (filename.isBlank()) { throw invalidUpload() }
                // Use Locale.ROOT for consistent extension checks across environments.
                // The extension alone does not prove the format; ffprobe checks the content later.
                if (!filename.lowercase(Locale.ROOT).endsWith(".m4a")) {
                    throw HttpFailure(415, 3002, "Only m4a audio files are supported.")
                }
                Files.createDirectories(tempDir)
                // Generate a temporary filename instead of using the client-provided name.
                val destination = Files.createTempFile(tempDir, "upload-", ".m4a")
                path = destination
                var bytes = 0L
                item.inputStream.use { input ->
                    Files.newOutputStream(destination).use { output ->
                        // Copy in 8 KiB chunks without loading the whole upload into memory.
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            // A read result of -1 marks the end of the stream.
                            if (read < 0) { break }
                            bytes += read
                            if (bytes > MAX_AUDIO_BYTES) { throw uploadTooLarge() }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (bytes == 0L) { throw invalidUpload() }
            }
            // Include any epilogue after the multipart terminator in the total body limit.
            val remainder = ByteArray(8192)
            while (limited.read(remainder) >= 0) {
                // Drain the remaining body while enforcing the size limit.
            }
            return path ?: throw HttpFailure(400, 1002, "Audio file is required.")
        } catch (exception: Exception) {
            // The caller has not received the path yet, so clean up partial uploads here.
            path?.let {
                try {
                    Files.deleteIfExists(it)
                } catch (cleanup: Exception) {
                    exception.addSuppressed(cleanup)
                    logger.cleanupFailure(cleanup)
                }
            }
            // Search wrapped causes to preserve explicit errors such as 413.
            val explicit = generateSequence<Throwable>(exception) { it.cause }.filterIsInstance<HttpFailure>().firstOrNull()
            when {
                explicit != null -> throw explicit
                exception is FileUploadSizeException -> throw uploadTooLarge()
                // Do not turn filesystem failures into client input errors.
                exception is FileSystemException -> throw exception
                exception is FileUploadException || exception is IOException || exception is IllegalArgumentException -> throw invalidUpload()
                else -> throw exception
            }
        }
    }

    /**
     * Wraps a stream and limits total bytes read through both single-byte and buffered reads.
     *
     * @property source Underlying input stream.
     * @property maximum Maximum allowed byte count.
     */
    private class LimitedInput(private val source: InputStream, private val maximum: Long) : InputStream() {
        private var count = 0L
        /**
         * Reads one byte and increments the count only when data is received.
         *
         * @return The next byte as 0 through 255, or -1 at end of stream.
         * @throws HttpFailure If the total exceeds the limit; maps to HTTP 413.
         */
        override fun read(): Int {
            if (count > maximum) { throw uploadTooLarge() }
            val value = source.read()
            // Do not count the -1 end-of-stream result as data.
            if (value >= 0 && ++count > maximum) { throw uploadTooLarge() }
            return value
        }
        /**
         * Reads into a buffer, allowing one extra byte to detect a limit violation.
         *
         * @param bytes Destination buffer.
         * @param offset Starting position in the buffer.
         * @param length Maximum requested byte count.
         * @return Actual byte count, -1 at end of stream, or 0 when length is zero.
         * @throws HttpFailure If the total exceeds the limit; maps to HTTP 413.
         */
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) { return 0 }
            if (count > maximum) { throw uploadTooLarge() }
            // Read at most the remaining allowance plus one byte to detect overflow.
            val read = source.read(bytes, offset, minOf(length.toLong(), maximum - count + 1).toInt())
            if (read > 0) { count += read }
            if (count > maximum) { throw uploadTooLarge() }
            return read
        }
        /** Closes the underlying stream. */
        override fun close() = source.close()
    }
}
