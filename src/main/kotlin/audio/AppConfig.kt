/*
 * Reads and validates startup settings from the PORT and DATA_DIR environment variables.
 */
package audio

import java.nio.file.Path

/**
 * HTTP port and data directory used by the application.
 *
 * @property port Port the HTTP server listens on inside the container.
 * @property dataDir Absolute base path for the database and audio files.
 */
data class AppConfig(val port: Int, val dataDir: Path) {
    companion object {
        /**
         * Creates application settings from environment variables.
         *
         * Uses the process environment by default. Tests can supply their own map.
         *
         * @param environment Variable names and values. Defaults to port 8080 and directory `data` when unset.
         * @return Settings with a valid port and a normalized absolute data path.
         * @throws IllegalArgumentException If PORT is not an integer between 1 and 65535.
         * @throws java.nio.file.InvalidPathException If DATA_DIR is not a valid path.
         */
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): AppConfig {
            val port = (environment["PORT"] ?: "8080").toIntOrNull()
            // Stop startup if the port setting is invalid.
            require(port != null && port in 1..65535) { "PORT must be between 1 and 65535." }
            // Resolve relative paths against the working directory and remove redundant path segments.
            return AppConfig(port, Path.of(environment["DATA_DIR"] ?: "data").toAbsolutePath().normalize())
        }
    }
}
