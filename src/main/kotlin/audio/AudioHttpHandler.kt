/*
 * Validates HTTP paths and methods, then routes requests to storage or retrieval.
 * Returns JSON for POST success and API errors, and audio bytes for GET success.
 * Also handles temporary file cleanup and one result log per request.
 */
package audio

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import kotlinx.serialization.json.*
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * API entry point implementing the JDK HttpHandler interface.
 *
 * Receives the service, logger, multipart reader, and audio writer through its constructor.
 *
 * @property service Stores and retrieves recordings and validates master IDs.
 * @property uploadDir Temporary directory for uploaded files.
 * @property logger Output for request results and cleanup warnings.
 * @property multipart Multipart parser and validator.
 * @property audioWriter Writes temporary audio to the response; replaceable to simulate transmission failures.
 */
class AudioHttpHandler(
    private val service: AudioService,
    private val uploadDir: Path,
    private val logger: RequestLogger = RequestLogger(),
    private val multipart: MultipartAudioReader = MultipartAudioReader(logger),
    private val audioWriter: (Path, OutputStream) -> Unit = { path, output -> Files.newInputStream(path).use { it.copyTo(output) }; Unit },
) : HttpHandler {
    // Capture userId, phraseId, and optional format as groups 1, 2, and 3.
    // Validate IDs separately to distinguish invalid IDs from unknown endpoints.
    private val route = Regex("^/audio/user/([^/]*)/phrase/([^/]*)(?:/([^/]+))?$")

    /**
     * Handles a POST or GET, including response transmission, cleanup, and result logging.
     *
     * Returns JSON for POST success and errors before transmission, and audio for GET success.
     * Once transmission starts, log failures as code 9003 without sending another response.
     *
     * @param exchange Request and response context, closed when handling finishes.
     */
    override fun handle(exchange: HttpExchange) {
        // Measure elapsed time and use a UUID to correlate the response with its log.
        val started = System.nanoTime()
        val requestId = UUID.randomUUID().toString()
        exchange.responseHeaders.set("X-Request-ID", requestId)
        // Keep request state available for logging even if processing fails.
        var userId: Long? = null
        var phraseId: Long? = null
        var status = 500
        var code = 0
        var operation: String? = null
        var failure: Exception? = null
        // Do not attempt a second JSON response once transmission has started.
        var sending = false
        var upload: Path? = null
        var responseFile: Path? = null
        /**
         * Starts a JSON response and updates the enclosing status and transmission state.
         *
         * @param responseStatus HTTP status to send.
         * @param value JSON object encoded as UTF-8; HEAD responses omit the body.
         * @throws java.io.IOException If headers or the body cannot be sent.
         */
        fun sendJson(responseStatus: Int, value: JsonObject) {
            val bytes = value.toString().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            status = responseStatus
            sending = true
            if (exchange.requestMethod == "HEAD") {
                // For HEAD, report the JSON length without sending a body.
                exchange.responseHeaders.set("Content-Length", bytes.size.toString())
                exchange.sendResponseHeaders(status, -1)
            } else {
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.responseBody.close()
            }
        }
        try {
            // Use rawPath so encoded characters cannot turn an invalid ID into an accepted number.
            val match = route.matchEntire(exchange.requestURI.rawPath)
                ?: throw HttpFailure(404, 2004, "Endpoint not found.")
            userId = parseId(match.groupValues[1])
            phraseId = parseId(match.groupValues[2])
            val format = match.groupValues[3]
            // Paths without a format are POST endpoints; paths with a format are GET endpoints.
            val method = if (format.isEmpty()) { "POST" } else { "GET" }
            if (exchange.requestMethod != method) {
                // Include the supported method in the Allow header for a 405 response.
                exchange.responseHeaders.set("Allow", method)
                throw HttpFailure(405, 2005, "Method not allowed.")
            }
            if (method == "POST") {
                // Reject invalid or unknown IDs before receiving the upload into a temporary file.
                service.validateIds(userId, phraseId)
                val input = multipart.read(exchange, uploadDir)
                // After a successful read, this handler owns deletion of the upload.
                upload = input
                val result = service.store(userId, phraseId, input)
                operation = if (result == StoreOperation.Created) { "created" } else { "replaced" }
                // Location is an API path, not an internal storage path.
                exchange.responseHeaders.set("Location", "/audio/user/$userId/phrase/$phraseId/m4a")
                sendJson(if (result == StoreOperation.Created) { 201 } else { 200 }, buildJsonObject {
                    // Use error_code, message, and data for both success and error JSON.
                    put("error_code", 0)
                    put("message", if (result == StoreOperation.Created) { "Audio created." } else { "Audio replaced." })
                    put("data", buildJsonObject {
                        put("user_id", userId)
                        put("phrase_id", phraseId)
                        put("operation", operation)
                    })
                })
            } else {
                if (format != "m4a") { throw HttpFailure(415, 3002, "Only m4a audio files are supported.") }
                val file = service.retrieve(userId, phraseId)
                // Keep the temporary download path so it can be deleted after a transmission failure.
                responseFile = file
                val responseSize = Files.size(file)
                exchange.responseHeaders.set("Content-Type", "audio/mp4")
                // Send audio bytes directly with Content-Length, without wrapping them in JSON.
                status = 200
                sending = true
                exchange.sendResponseHeaders(200, responseSize)
                audioWriter(file, exchange.responseBody)
                exchange.responseBody.close()
                // Record retrieval success only after writing and closing the response body.
                operation = "retrieved"
            }
        } catch (exception: Exception) {
            failure = exception
            operation = null
            if (sending) {
                // Log transmission failures as 9003 and preserve any HTTP status already sent.
                status = exchange.responseCode.takeIf { it >= 100 } ?: 0
                code = 9003
            } else {
                // Before transmission, convert exceptions to the common JSON error response.
                val error = httpFailure(exception)
                code = error.code
                try {
                    sendJson(error.status, buildJsonObject {
                        put("error_code", error.code)
                        put("message", error.message)
                        put("data", JsonNull)
                    })
                } catch (transmission: Exception) {
                    // Preserve the original exception if sending the error response also fails.
                    exception.addSuppressed(transmission)
                    status = exchange.responseCode.takeIf { it >= 100 } ?: 0
                    code = 9003
                }
            }
        } finally {
            // Remove this request's upload and temporary download on both success and failure.
            // Persistent WAV files referenced by the database are not included here.
            for (path in listOfNotNull(upload, responseFile)) {
                try { Files.deleteIfExists(path) } catch (exception: Exception) { logger.cleanupFailure(exception) }
            }
            runCatching { exchange.close() }
            // Write one result log after cleanup, converting nanoseconds to milliseconds.
            logger.log(RequestLog(requestId, exchange.requestMethod, userId, phraseId, status, code, operation, (System.nanoTime() - started) / 1_000_000), failure)
        }
    }

    /**
     * Parses a positive Long ID containing only ASCII digits.
     *
     * @param value Raw ID segment extracted from the URL before decoding.
     * @return Positive ID; master record existence is checked separately.
     * @throws InvalidIdException If the value is empty, nonnumeric, out of Long range, or zero.
     */
    private fun parseId(value: String): Long {
        // Reject invalid input rather than trimming or removing characters.
        if (!value.matches(Regex("[0-9]+"))) { throw InvalidIdException() }
        return value.toLongOrNull()?.takeIf { it > 0 } ?: throw InvalidIdException()
    }
}
