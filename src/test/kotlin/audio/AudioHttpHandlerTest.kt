/*
 * Tests HTTP statuses, JSON, audio, headers, and logs through POST and GET requests.
 * Uses a temporary local server and database to reproduce invalid inputs and failures.
 * Does not access the application's localhost:80 endpoint or persistent volume.
 */
package audio

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/** Integration tests combining API components over real HTTP. */
class AudioHttpHandlerTest {
    @TempDir lateinit var directory: Path
    private lateinit var server: HttpServer
    private val executor = Executors.newFixedThreadPool(3)
    private val client = HttpClient.newHttpClient()
    // Use a thread-safe queue shared by the HTTP and test threads.
    private val logs = ConcurrentLinkedQueue<String>()
    private val root = "/audio/user/1/phrase/1"
    // Make failure flags changed by the test visible to HTTP worker threads.
    @Volatile private var failWrite = false

    /** Creates an independent database, storage directory, and HTTP server for each test. */
    @BeforeEach
    fun start() {
        val repository = AudioRepository(directory.resolve("app.sqlite"))
        repository.initialize()
        val logger = RequestLogger(sink = { logs.add(it) })
        val service = AudioService(repository, AudioConverter(), directory.resolve("audio"), logger = logger)
        val reader = MultipartAudioReader(logger)
        // Replace only audio writing to simulate a transmission failure.
        val handler = AudioHttpHandler(service, directory.resolve("uploads"), logger, reader) { path, output ->
            if (failWrite) { throw IOException("simulated disconnect") }
            Files.newInputStream(path).use { it.copyTo(output) }
        }
        // Let the OS choose a free port to avoid conflicts with other applications.
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/", handler)
        server.start()
    }

    /** Stops the server and worker threads even if the test fails. */
    @AfterEach
    fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    /** Checks create 201, replace 200, retrieve 200, audio format, and request-ID correlation. */
    @Test
    fun `POST creates and replaces then GET returns m4a and correlated logs`() {
        val audio = tone()
        // A traversal-like client filename must not become the storage path.
        val created = post(audio, filename = "../../client.m4a")
        assertEquals(201, created.statusCode())
        val body = json(created)
        assertEquals(setOf("error_code", "message", "data"), body.keys)
        assertEquals(0, body["error_code"]!!.jsonPrimitive.int)
        assertEquals("created", body["data"]!!.jsonObject["operation"]!!.jsonPrimitive.content)
        assertEquals(1, body["data"]!!.jsonObject["user_id"]!!.jsonPrimitive.int)
        assertEquals("$root/m4a", created.headers().firstValue("Location").orElseThrow())
        val replaced = post(audio)
        assertEquals(200, replaced.statusCode())
        assertEquals("replaced", json(replaced)["data"]!!.jsonObject["operation"]!!.jsonPrimitive.content)
        val retrieved = request("GET", "$root/m4a")
        assertEquals(200, retrieved.statusCode())
        assertEquals("audio/mp4", retrieved.headers().firstValue("Content-Type").orElseThrow())
        assertEquals(retrieved.body().size.toLong(), retrieved.headers().firstValueAsLong("Content-Length").orElseThrow())
        val responseFile = directory.resolve("response.m4a")
        Files.write(responseFile, retrieved.body())
        assertEquals(0, ProcessCommandRunner().run(listOf("ffprobe", "-v", "error", responseFile.toString()), Duration.ofSeconds(5)).exitCode)
        awaitLogs(3)
        val firstLog = Json.parseToJsonElement(logs.first()).jsonObject
        assertEquals(created.headers().firstValue("X-Request-ID").orElseThrow(), firstLog["request_id"]!!.jsonPrimitive.content)
        assertEquals("created", firstLog["operation"]!!.jsonPrimitive.content)
        assertNoTemps()
    }

    /** Checks consistent error JSON and distinct unknown-ID and missing-recording errors. */
    @Test
    fun `GET errors share POST error JSON and missing IDs are distinct`() {
        assertError(request("GET", "/audio/user/999/phrase/1/m4a"), 404, 2001)
        assertError(request("GET", "/audio/user/1/phrase/999/m4a"), 404, 2002)
        assertError(request("GET", "$root/m4a"), 404, 2003)
        assertError(post(byteArrayOf(), path = "/audio/user/999/phrase/1"), 404, 2001)
        assertError(post(byteArrayOf(), path = "/audio/user/1/phrase/999"), 404, 2002)
    }

    /** Rejects invalid ID ranges, characters, formats, methods, and endpoints. */
    @Test
    fun `invalid IDs formats endpoints and methods are rejected`() {
        for (id in listOf("0", "-1", "+1", "1%20OR%201=1", "%EF%BC%91", "9223372036854775808", "..")) {
            assertError(request("GET", "/audio/user/$id/phrase/1/m4a"), 400, 1001)
        }
        assertError(request("GET", "$root/M4A"), 415, 3002)
        assertError(request("GET", "$root/mp3"), 415, 3002)
        val method = request("DELETE", root)
        assertError(method, 405, 2005)
        assertEquals("POST", method.headers().firstValue("Allow").orElseThrow())
        assertError(request("GET", "/unknown"), 404, 2004)
    }

    /** Rejects malformed uploads and duplicate files without leaving temporary files. */
    @Test
    fun `invalid uploads and duplicate files leave no temporary files`() {
        assertError(request("POST", root, byteArrayOf(), "application/octet-stream"), 415, 3003)
        assertError(request("POST", root, "--B--\r\n".toByteArray(), "multipart/form-data; boundary=B"), 400, 1002)
        assertError(request("POST", root, "broken".toByteArray(), "multipart/form-data"), 400, 1004)
        assertError(post(byteArrayOf()), 400, 1004)
        assertError(post("broken".toByteArray()), 400, 1003)
        assertError(post("broken".toByteArray(), filename = "bad.mp3"), 415, 3002)
        val part = "--B\r\nContent-Disposition: form-data; name=\"audio_file\"; filename=\"a.m4a\"\r\n\r\nx\r\n"
        assertError(request("POST", root, (part + part + "--B--\r\n").toByteArray(), "multipart/form-data; boundary=B"), 400, 1004)
        assertNoTemps()
    }

    /** Returns 413 for audio over 10 MiB with both known-length and chunked requests. */
    @Test
    fun `known length and chunked oversized audio return 413`() {
        val oversized = ByteArray(10 * 1024 * 1024 + 1)
        assertError(post(oversized), 413, 3001)
        assertError(post(oversized, chunked = true), 413, 3001)
        assertNoTemps()
    }

    /** Preserves old audio on replacement failure and exposes database details only in logs. */
    @Test
    fun `failed replacement retains old audio and internal errors hide details`() {
        assertEquals(201, post(tone()).statusCode())
        assertError(post("broken".toByteArray()), 400, 1003)
        assertEquals(200, request("GET", "$root/m4a").statusCode())
        DriverManager.getConnection("jdbc:sqlite:${directory.resolve("app.sqlite")}").use { connection ->
            connection.createStatement().use { it.execute("CREATE TRIGGER fail_save BEFORE UPDATE ON recordings BEGIN SELECT RAISE(ABORT, 'private database detail'); END") }
        }
        val failure = post(Files.readAllBytes(directory.resolve("input.m4a")))
        assertError(failure, 500, 9002)
        assertFalse(String(failure.body()).contains("private database detail"))
        awaitLogs(4)
        val log = Json.parseToJsonElement(logs.last()).jsonObject
        assertTrue(log.containsKey("stack_trace"))
        assertEquals("ERROR", log["level"]!!.jsonPrimitive.content)
        assertEquals(200, request("GET", "$root/m4a").statusCode())
        assertNoTemps()
    }

    /** Preserves the sent 200 status on disconnect, logs 9003, and removes temporary audio. */
    @Test
    fun `failed response transmission keeps sent status and cleans response file`() {
        assertEquals(201, post(tone()).statusCode())
        failWrite = true
        assertThrows(IOException::class.java) { request("GET", "$root/m4a") }
        awaitLogs(2)
        val log = Json.parseToJsonElement(logs.last()).jsonObject
        assertEquals(200, log["status"]!!.jsonPrimitive.int)
        assertEquals(9003, log["error_code"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, log["operation"])
        assertNoTemps()
    }

    /** Includes data after the multipart terminator in the total chunked-body limit. */
    @Test
    fun `chunked multipart epilogue is included in total body limit`() {
        val part = "--B\r\nContent-Disposition: form-data; name=\"audio_file\"; filename=\"a.m4a\"\r\n\r\nx\r\n--B--\r\n".toByteArray()
        val body = part + ByteArray(MultipartAudioReader.MAX_REQUEST_BYTES.toInt())
        assertError(request("POST", root, body, "multipart/form-data; boundary=B", true), 413, 3001)
        assertNoTemps()
    }

    /** Logs status zero when response header transmission fails before a status is committed. */
    @Test
    fun `header send failure is logged as no committed HTTP status`() {
        val collected = mutableListOf<String>()
        val service = AudioService(AudioRepository(directory.resolve("app.sqlite")), AudioConverter(), directory.resolve("audio"))
        val handler = AudioHttpHandler(service, directory.resolve("uploads"), RequestLogger(sink = { collected.add(it) }))
        handler.handle(HeaderFailureExchange())
        val log = Json.parseToJsonElement(collected.single()).jsonObject
        assertEquals(0, log["status"]!!.jsonPrimitive.int)
        assertEquals(9003, log["error_code"]!!.jsonPrimitive.int)
    }

    /** Generates short audio with FFmpeg and returns its bytes for upload. */
    private fun tone(): ByteArray {
        val path = directory.resolve("input.m4a")
        val result = ProcessCommandRunner().run(listOf("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "sine=duration=0.1", "-f", "ipod", path.toString()), Duration.ofSeconds(5))
        assertEquals(0, result.exitCode)
        return Files.readAllBytes(path)
    }

    /** Builds a multipart request body using boundary B and part headers. */
    private fun post(bytes: ByteArray, path: String = root, filename: String = "input.m4a", chunked: Boolean = false): HttpResponse<ByteArray> {
        val header = "--B\r\nContent-Disposition: form-data; name=\"audio_file\"; filename=\"$filename\"\r\nContent-Type: audio/mp4\r\n\r\n".toByteArray()
        return request("POST", path, header + bytes + "\r\n--B--\r\n".toByteArray(), "multipart/form-data; boundary=B", chunked)
    }

    /** Sends HTTP and receives bytes for both audio and JSON errors. */
    private fun request(method: String, path: String, body: ByteArray = byteArrayOf(), type: String? = null, chunked: Boolean = false): HttpResponse<ByteArray> {
        // Use an unknown-length publisher to reproduce chunked HTTP/1.1 requests.
        val publisher = if (chunked) { HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(body) } } else { HttpRequest.BodyPublishers.ofByteArray(body) }
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.address.port}$path")).timeout(Duration.ofSeconds(15)).method(method, publisher)
        if (type != null) { request.header("Content-Type", type) }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    /** Parses a response body as a JSON object for assertions. */
    private fun json(response: HttpResponse<ByteArray>) = Json.parseToJsonElement(String(response.body())).jsonObject

    /** Checks HTTP status, numeric error_code, and the common JSON fields. */
    private fun assertError(response: HttpResponse<ByteArray>, status: Int, code: Int) {
        assertEquals(status, response.statusCode())
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElseThrow())
        val body = json(response)
        assertEquals(setOf("error_code", "message", "data"), body.keys)
        assertEquals(code, body["error_code"]!!.jsonPrimitive.int)
        assertFalse(body["error_code"]!!.jsonPrimitive.isString)
        assertEquals(JsonNull, body["data"])
    }

    /** Waits with a deadline because the response may arrive before the finally-block log. */
    private fun awaitLogs(count: Int) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (logs.size < count && System.nanoTime() < deadline) { Thread.sleep(5) }
        assertTrue(logs.size >= count)
    }

    /** Checks that upload and download temporary directories are empty. */
    private fun assertNoTemps() {
        for (path in listOf(directory.resolve("uploads"), directory.resolve("audio/.tmp"))) {
            if (Files.exists(path)) { Files.list(path).use { assertEquals(0L, it.count()) } }
        }
    }
}

/**
 * Test HttpExchange that always fails while sending response headers.
 * Provides minimal method implementations to reproduce a failure hard to trigger with real sockets.
 */
private class HeaderFailureExchange : com.sun.net.httpserver.HttpExchange() {
    private val headers = com.sun.net.httpserver.Headers()
    override fun getRequestHeaders() = com.sun.net.httpserver.Headers()
    override fun getResponseHeaders() = headers
    override fun getRequestURI() = URI("/unknown")
    override fun getRequestMethod() = "GET"
    override fun getHttpContext(): com.sun.net.httpserver.HttpContext? = null
    override fun close() { }
    override fun getRequestBody() = ByteArrayInputStream(byteArrayOf())
    override fun getResponseBody() = java.io.ByteArrayOutputStream()
    override fun sendResponseHeaders(status: Int, length: Long) { throw IOException("header failure") }
    override fun getRemoteAddress() = InetSocketAddress(0)
    override fun getResponseCode() = -1
    override fun getLocalAddress() = InetSocketAddress(0)
    override fun getProtocol() = "HTTP/1.1"
    override fun getAttribute(name: String): Any? = null
    override fun setAttribute(name: String, value: Any?) { }
    override fun setStreams(input: java.io.InputStream, output: java.io.OutputStream) { }
    override fun getPrincipal(): com.sun.net.httpserver.HttpPrincipal? = null
}
