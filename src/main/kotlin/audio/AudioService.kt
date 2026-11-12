/*
 * Coordinates recording storage, replacement, and retrieval.
 * Combines ID validation, conversion, database updates, and file cleanup.
 * Reports failures as exceptions without handling HTTP statuses or JSON.
 */
package audio

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID

/** Storage outcome: [StoreOperation.Created] for a new recording, [StoreOperation.Replaced] for an existing pair. */
enum class StoreOperation { Created, Replaced }

// Keep domain failures distinct; HttpFailure.kt maps them to HTTP statuses and error codes.
/** A user or phrase ID is not a positive integer. */
class InvalidIdException : RuntimeException("IDs must be positive integers.")
/** The user ID is absent from the master table. */
class UserNotFoundException : RuntimeException("User not found.")
/** The phrase ID is absent from the master table. */
class PhraseNotFoundException : RuntimeException("Phrase not found.")
/** No recording metadata exists for the valid ID pair. */
class RecordingNotFoundException : RuntimeException("Recording not found.")
/**
 * An internal storage failure, such as missing audio or an invalid path.
 *
 * @param message Description for internal logs.
 * @param cause Underlying exception, or null when there is none.
 */
class AudioStorageException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Entry point for storage and retrieval, with dependencies supplied by Main.kt.
 *
 * Deletes files directly and reports cleanup failures as separate warnings.
 *
 * @property repository Checks master IDs and stores recording metadata.
 * @property converter Inspects and converts m4a and WAV files.
 * @param storageDir Persistent WAV directory, created and resolved to a real path during construction.
 * @property logger Logger for file cleanup failures.
 * @throws AudioStorageException If the temporary directory is a symbolic link or resolves outside storage.
 * @throws java.io.IOException If the storage directories cannot be prepared.
 */
class AudioService(
    private val repository: AudioRepository,
    private val converter: AudioConverter,
    storageDir: Path,
    private val logger: RequestLogger = RequestLogger(),
) {
    // Create storage if necessary and resolve its real location.
    private val storage = Files.createDirectories(storageDir.toAbsolutePath().normalize()).toRealPath()
    private val temporary = storage.resolve(".tmp")

    init {
        // Prepare temporary storage for download m4a files.
        Files.createDirectories(temporary)
        // Reject a temporary directory that redirects outside storage.
        if (Files.isSymbolicLink(temporary) || temporary.toRealPath().parent != storage) {
            throw AudioStorageException("Invalid temporary audio directory.")
        }
    }

    /**
     * Converts uploaded m4a to a new WAV, updates the database, then removes the old WAV.
     *
     * The calling HTTP handler owns deletion of the uploaded input.
     * A failed old-file deletion after commit is a warning and does not undo storage success.
     *
     * @param userId User who owns the recording.
     * @param phraseId Phrase being recorded.
     * @param input Temporary input m4a path; this function does not delete it.
     * @return [StoreOperation.Created] if absent before storage, otherwise [StoreOperation.Replaced].
     * @throws InvalidIdException If either ID is not positive.
     * @throws UserNotFoundException If the user is not registered.
     * @throws PhraseNotFoundException If the phrase is not registered.
     * @throws AudioStorageException If the previous recording's path is invalid or its file is missing.
     * @throws InvalidAudioException If the uploaded audio cannot be decoded.
     * @throws UnsupportedAudioFormatException If the input does not meet m4a upload requirements.
     * @throws AudioConversionTimeoutException If inspection or conversion exceeds its time limit.
     * @throws AudioConversionException If command execution, conversion, or termination fails.
     * @throws java.sql.SQLException If database lookup or update fails.
     * @throws java.io.IOException If input/output files cannot be prepared or inspected.
     */
    // Serialize store and retrieve on this instance to prevent files disappearing during replacement.
    // This lock covers one service instance within the JVM, not multiple containers.
    @Synchronized
    fun store(userId: Long, phraseId: Long, input: Path): StoreOperation {
        validateIds(userId, phraseId)
        val previous = repository.find(userId, phraseId)
        val previousPath = previous?.let { storedPath(it.path) }
        // Generate a UUID filename instead of using the client-provided name.
        val filename = "${UUID.randomUUID()}.wav"
        val output = storage.resolve(filename)
        converter.convert(input, output, "wav")
        try {
            // Save metadata only after conversion succeeds, preserving the old recording until then.
            repository.save(Recording(userId, phraseId, filename))
        } catch (exception: Exception) {
            // Delete the new WAV if the database update fails, preserving the old recording.
            try {
                Files.deleteIfExists(output)
            } catch (cleanupException: Exception) {
                exception.addSuppressed(cleanupException)
                logger.cleanupFailure(cleanupException)
            }
            throw exception
        }
        if (previousPath != null) {
            // After commit, report old-file cleanup failures without undoing the replacement.
            try {
                Files.deleteIfExists(previousPath)
            } catch (exception: Exception) {
                logger.cleanupFailure(exception)
            }
        }
        return if (previous == null) { StoreOperation.Created } else { StoreOperation.Replaced }
    }

    /**
     * Converts a stored WAV to a temporary m4a for download.
     *
     * @param userId User who owns the recording.
     * @param phraseId Phrase to retrieve.
     * @return Temporary m4a path; the caller must delete it after transmission.
     * @throws InvalidIdException If either ID is not positive.
     * @throws UserNotFoundException If the user is not registered.
     * @throws PhraseNotFoundException If the phrase is not registered.
     * @throws RecordingNotFoundException If no recording exists for the ID pair.
     * @throws AudioStorageException If stored audio or temporary storage is invalid, or the WAV is missing.
     * @throws AudioConversionTimeoutException If inspection or conversion exceeds its time limit.
     * @throws AudioConversionException If stored WAV is corrupt or execution, conversion, or termination fails.
     * @throws java.sql.SQLException If ID or recording lookup fails.
     * @throws java.io.IOException If input/output files or temporary storage cannot be inspected or prepared.
     */
    @Synchronized
    fun retrieve(userId: Long, phraseId: Long): Path {
        validateIds(userId, phraseId)
        val recording = repository.find(userId, phraseId) ?: throw RecordingNotFoundException()
        val input = storedPath(recording.path)
        // Recheck in case the temporary directory was replaced after startup.
        if (Files.isSymbolicLink(temporary) || temporary.toRealPath().parent != storage) {
            throw AudioStorageException("Invalid temporary audio directory.")
        }
        val output = temporary.resolve("${UUID.randomUUID()}.m4a")
        try {
            converter.convert(input, output, "m4a")
        } catch (exception: InvalidAudioException) {
            // Corrupt stored audio is an internal failure, not an error in the current request's input.
            throw AudioConversionException("Stored audio could not be decoded.", exception)
        }
        return output
    }

    /**
     * Validates positive IDs and master records; also called before receiving uploads.
     *
     * @param userId User ID that must be positive and registered.
     * @param phraseId Phrase ID that must be positive and registered.
     * @throws InvalidIdException If either ID is zero or negative.
     * @throws UserNotFoundException If the user is not registered.
     * @throws PhraseNotFoundException If the phrase is not registered.
     * @throws java.sql.SQLException If master-record lookup fails.
     */
    internal fun validateIds(userId: Long, phraseId: Long) {
        if (userId <= 0 || phraseId <= 0) {
            throw InvalidIdException()
        }
        if (!repository.userExists(userId)) {
            throw UserNotFoundException()
        }
        if (!repository.phraseExists(phraseId)) {
            throw PhraseNotFoundException()
        }
    }

    /**
     * Validates database filenames as regular WAV files directly inside storage.
     *
     * @param value Relative filename read from the database.
     * @return Validated path to the stored WAV.
     * @throws AudioStorageException If the path is invalid, outside storage, a link, or not a regular file.
     */
    private fun storedPath(value: String): Path {
        val relative = try {
            Path.of(value)
        } catch (exception: java.nio.file.InvalidPathException) {
            throw AudioStorageException("Invalid stored audio path.", exception)
        }
        val path = storage.resolve(relative).normalize()
        // Reject absolute paths, subdirectories, traversal outside storage, and symbolic links.
        if (relative.isAbsolute || relative.nameCount != 1 || path.parent != storage ||
            !value.endsWith(".wav") || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw AudioStorageException("Stored audio is missing or its path is invalid.")
        }
        return path
    }

}
