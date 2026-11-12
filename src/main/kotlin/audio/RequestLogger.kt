/*
 * Writes API request results as one-line JSON to standard output.
 * Docker collects stdout, so no application log file is created.
 * Records durations, internal failures, and cleanup warnings separately from HTTP responses.
 */
package audio

import kotlinx.serialization.json.*
import java.time.Clock

/**
 * Result of one request, supplied by the HTTP handler.
 *
 * IDs may be null before parsing, and operation is null on failure.
 * On transmission failure, preserve the committed status or use zero if none was set.
 *
 * @property requestId Unique identifier matching the response's X-Request-ID header.
 * @property method HTTP method received.
 * @property userId Parsed user ID, or null if processing failed before parsing.
 * @property phraseId Parsed phrase ID, or null if processing failed before parsing.
 * @property status HTTP status, or zero if no response code was set before transmission failed.
 * @property errorCode Application result code: zero on success and 9003 for transmission failures.
 * @property operation Successful action: created, replaced, or retrieved; null on failure.
 * @property durationMs Elapsed milliseconds including cleanup but excluding logging itself.
 */
data class RequestLog(
    val requestId: String,
    val method: String,
    val userId: Long?,
    val phraseId: Long?,
    val status: Int,
    val errorCode: Int,
    val operation: String?,
    val durationMs: Long,
)

/**
 * Writes request results and cleanup warnings as one-line JSON.
 *
 * @property clock Source of time for the UTC timestamp.
 * @property sink Output function for the JSON string; defaults to println.
 */
class RequestLogger(
    private val clock: Clock = Clock.systemUTC(),
    private val sink: (String) -> Unit = { println(it) },
) {
    /**
     * Immediately writes a cleanup failure as a separate one-line JSON warning.
     *
     * Does not aggregate warnings per request or include a stack trace.
     * Suppresses sink failures so they do not replace the API result.
     *
     * @param exception Failure encountered while deleting an upload, download, or old recording.
     */
    fun cleanupFailure(exception: Exception) {
        val line = buildJsonObject {
            put("timestamp", clock.instant().toString())
            put("level", "WARN")
            put("event", "cleanup_failed")
            put("message", exception.message ?: "Cleanup failed.")
        }.toString()
        runCatching { sink(line) }
    }

    /**
     * Writes a request result with the appropriate log level.
     *
     * Includes a stack trace only for HTTP 5xx with an exception; suppresses sink failures.
     *
     * @param result Final request outcome recorded by the HTTP handler.
     * @param exception Failure to record, or null for a successful request.
     */
    fun log(result: RequestLog, exception: Exception? = null) {
        // Let the JSON builder escape strings so embedded newlines do not split the log record.
        val line = buildJsonObject {
            put("timestamp", clock.instant().toString())
            // Prioritize 5xx as ERROR, other nonzero error codes as WARN, and success as INFO.
            put("level", when {
                result.status >= 500 -> "ERROR"
                result.errorCode != 0 -> "WARN"
                else -> "INFO"
            })
            put("request_id", result.requestId)
            put("method", result.method)
            put("user_id", result.userId)
            put("phrase_id", result.phraseId)
            put("status", result.status)
            put("error_code", result.errorCode)
            put("operation", result.operation)
            put("duration_ms", result.durationMs)
            if (result.status >= 500 && exception != null) {
                // Keep exception details in internal logs rather than API responses.
                put("exception", exception.javaClass.name)
                put("exception_message", exception.message)
                put("stack_trace", exception.stackTraceToString())
            }
        }.toString()
        // Do not let output failures affect the API result.
        runCatching { sink(line) }
    }
}
