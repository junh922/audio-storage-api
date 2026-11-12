/*
 * Maps processing exceptions to HTTP statuses and numeric error codes.
 * Keeps POST and GET errors consistent without exposing internal failure details.
 */
package audio

import java.io.IOException

/**
 * Failure information safe to return as an HTTP response.
 *
 * @property status HTTP status code.
 * @property code Application-specific numeric error code.
 * @param message Error message safe to expose to the client.
 */
class HttpFailure(val status: Int, val code: Int, message: String) : IOException(message)

/**
 * Creates the shared error for audio or request body size violations.
 *
 * @return An HTTP 413, error_code 3001 exception; this function does not throw it.
 */
internal fun uploadTooLarge() = HttpFailure(413, 3001, "Audio file must not exceed 10 MiB.")
/**
 * Creates the shared error for malformed multipart or invalid required file content.
 *
 * @return An HTTP 400, error_code 1004 exception; this function does not throw it.
 */
internal fun invalidUpload() = HttpFailure(400, 1004, "Invalid upload request.")

/**
 * Maps an exception to an API error.
 *
 * Classifies known failures and hides details of unclassified internal errors behind HTTP 500.
 * The exception types using !! always supply a message in their definitions.
 *
 * @param exception Failure raised before response transmission starts.
 * @return The existing HttpFailure or its mapped equivalent; unknown failures become a generic 500.
 */
internal fun httpFailure(exception: Exception): HttpFailure = when (exception) {
    is HttpFailure -> exception
    is InvalidIdException -> HttpFailure(400, 1001, exception.message!!)
    is UserNotFoundException -> HttpFailure(404, 2001, exception.message!!)
    is PhraseNotFoundException -> HttpFailure(404, 2002, exception.message!!)
    is RecordingNotFoundException -> HttpFailure(404, 2003, exception.message!!)
    is InvalidAudioException -> HttpFailure(400, 1003, exception.message!!)
    is UnsupportedAudioFormatException -> HttpFailure(415, 3002, exception.message!!)
    is AudioConversionTimeoutException -> HttpFailure(500, 9001, exception.message!!)
    else -> HttpFailure(500, 9002, "An internal error occurred.")
}
