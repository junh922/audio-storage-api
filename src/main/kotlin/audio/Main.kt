package audio

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Builds application components and starts the audio API HTTP server.
 *
 * Runs once at startup; requests share the server and component instances.
 *
 * This function only wires startup dependencies.
 * [AudioHttpHandler] handles routing and responses; [AudioService] coordinates storage.
 *
 * @throws IllegalArgumentException If startup settings are invalid.
 * @throws java.sql.SQLException If database initialization fails.
 * @throws java.io.IOException If storage preparation or HTTP server creation fails.
 */
fun main() {
    // Read PORT and DATA_DIR and validate the port.
    // Defaults are port 8080 and directory data; Compose sets the directory to /data.
    val config = AppConfig.fromEnvironment()

    // Compose uses /data/app.sqlite.
    val repository = AudioRepository(config.dataDir.resolve("app.sqlite"))

    // Connect to SQLite, create tables, and seed user and phrase IDs.
    // Repeated initialization preserves recordings and adds only missing seed IDs.
    // Abort startup if initialization fails rather than accepting requests without a usable database.
    repository.initialize()

    // Share one logger across requests, writing UTC timestamps as one-line JSON to stdout.
    // Docker collects stdout, so no application log file is created inside the container.
    val logger = RequestLogger()

    // Supply the dependencies needed for storage, replacement, and retrieval.
    // The repository handles database access; AudioConverter inspects and converts audio.
    // Compose stores audio in /data/audio; requests share this service instance.
    // The service reports cleanup failures directly to the shared logger.
    val service = AudioService(
        repository,
        AudioConverter(),
        config.dataDir.resolve("audio"),
        logger,
    )

    // Bind to all IPv4 interfaces inside the container.
    val server = HttpServer.create(InetSocketAddress("0.0.0.0", config.port), 0)

    // Use up to four worker threads for HTTP requests.
    // AudioService serializes storage and retrieval, so four audio conversions do not run concurrently.
    val executor = Executors.newFixedThreadPool(4)

    // Dispatch requests to the worker pool.
    server.executor = executor

    // Route requests to the audio API handler.
    server.createContext("/", AudioHttpHandler(service, config.dataDir.resolve("uploads"), logger))

    // Register cleanup for JVM shutdown, including normal Docker stops.
    // Cleanup is not guaranteed on forced termination such as SIGKILL.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            // Stop accepting requests and shut down the server.
            server.stop(2)

            // Request interruption of running workers and cancel queued tasks.
            executor.shutdownNow()
        },
    )

    // Start accepting HTTP requests after initialization.
    server.start()
}
