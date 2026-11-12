/*
 * Runs external programs such as FFmpeg with a time limit.
 * Drains stdout and stderr concurrently, bounds captured output, and terminates child processes.
 */
package audio

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Result of an external command.
 *
 * @property exitCode Process exit code, not an HTTP status; usually zero means success.
 * @property stdout Captured standard output; its size limit depends on the implementation.
 * @property stderr Captured standard error; its size limit depends on the implementation.
 */
data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Contract for external command execution.
 */
fun interface CommandRunner {
    /**
     * Executes an external command and returns its result.
     *
     * @param arguments Command name followed by its arguments.
     * @param timeout Limit for execution and output collection; extra termination time depends on the implementation.
     * @return Exit code, stdout, and stderr; nonzero exit codes are returned as results.
     * @throws AudioConversionTimeoutException If execution does not finish within the limit.
     */
    fun run(arguments: List<String>, timeout: Duration): CommandResult
}

/**
 * [CommandRunner] implementation using ProcessBuilder, output collection, and termination checks.
 *
 * @property start Creates a Process from the arguments; replaceable in tests.
 */
class ProcessCommandRunner(
    private val start: (List<String>) -> Process = { ProcessBuilder(it).start() },
) : CommandRunner {
    /**
     * Uses one execution deadline and always cleans up processes and output readers.
     *
     * @param arguments Command name and arguments; no shell is started automatically.
     * @param timeout Positive execution limit, with up to five additional seconds for termination checks.
     * @return Exit result with the first 64 KiB of each output stream decoded as UTF-8.
     * @throws IllegalArgumentException If timeout is not positive.
     * @throws java.io.IOException If process startup or stream operations fail.
     * @throws AudioConversionTimeoutException If execution or output collection exceeds the deadline.
     * @throws AudioProcessTerminationException If process termination cannot be confirmed.
     * @throws AudioConversionException If the thread is interrupted; its interrupt flag is restored.
     */
    override fun run(arguments: List<String>, timeout: Duration): CommandResult {
        require(!timeout.isNegative && !timeout.isZero)
        val started = System.nanoTime()
        val process = start(arguments)
        // Read both streams concurrently to avoid a full pipe blocking the process.
        val readers = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "audio-process-output").apply { isDaemon = true }
        }
        try {
            // No stdin is sent; close the stream to signal EOF.
            process.outputStream.close()
            val stdout = readers.submit(Callable { capture(process.inputStream) })
            val stderr = readers.submit(Callable { capture(process.errorStream) })
            fun remaining(): Long = (timeout.toNanos() - (System.nanoTime() - started)).coerceAtLeast(0)
            // Apply the same deadline to process completion and output collection.
            if (!process.waitFor(remaining(), TimeUnit.NANOSECONDS)) {
                throw AudioConversionTimeoutException()
            }
            return CommandResult(
                process.exitValue(),
                stdout.get(remaining(), TimeUnit.NANOSECONDS),
                stderr.get(remaining(), TimeUnit.NANOSECONDS),
            )
        } catch (exception: TimeoutException) {
            throw AudioConversionTimeoutException()
        } catch (exception: InterruptedException) {
            // Restore the interrupt flag so callers can detect cancellation.
            Thread.currentThread().interrupt()
            throw AudioConversionException("Audio process interrupted.", exception)
        } finally {
            // Check termination on all paths and release streams even if termination fails.
            try {
                terminate(process)
            } finally {
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                readers.shutdownNow()
            }
        }
    }

    /**
     * Stops the process and its descendants within a separate five-second cleanup deadline.
     *
     * @param process Process to terminate.
     * @throws AudioProcessTerminationException If termination cannot be confirmed before the cleanup deadline.
     */
    private fun terminate(process: Process) {
        val handles = process.descendants().use { it.toList() } + process.toHandle()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        // Clear the interrupt flag while waiting for cleanup, then restore it.
        var interrupted = Thread.interrupted()
        try {
            handles.filter { it.isAlive }.forEach { it.destroyForcibly() }
            for (handle in handles) {
                while (handle.isAlive) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) {
                        throw AudioProcessTerminationException()
                    }
                    try {
                        handle.onExit().get(remaining, TimeUnit.NANOSECONDS)
                    } catch (exception: InterruptedException) {
                        interrupted = true
                    } catch (exception: TimeoutException) {
                        throw AudioProcessTerminationException()
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /**
     * Drains output to EOF while retaining at most the first 64 KiB in memory.
     *
     * @param stream Input stream, closed when this function finishes.
     * @return Captured bytes decoded as UTF-8.
     * @throws java.io.IOException If reading fails.
     */
    private fun capture(stream: InputStream): String = stream.use {
        val captured = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) {
                break
            }
            // Keep draining after reaching the capture limit to avoid blocking the child process.
            val remaining = (65536 - captured.size()).coerceAtLeast(0)
            captured.write(buffer, 0, minOf(count, remaining))
        }
        captured.toString(Charsets.UTF_8)
    }
}
