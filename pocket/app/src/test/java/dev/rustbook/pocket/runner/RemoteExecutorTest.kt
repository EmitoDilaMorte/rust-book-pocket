package dev.rustbook.pocket.runner

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class RemoteExecutorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun executor(): RemoteExecutor {
        val client = OkHttpClient.Builder()
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .callTimeout(1, TimeUnit.SECONDS)
            .build()
        return RemoteExecutor(client) { server.url("/").toString() }
    }

    @Test
    fun `posts the snippet to the execute endpoint`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"stdout":"hello\n","stderr":"","exitDetail":"Exited with status 0"}""",
            ),
        )

        val result = executor().execute(PlaygroundRequest(code = "fn main() { println!(\"hello\"); }"))

        assertTrue(result.success)
        assertEquals("hello\n", result.stdout)

        val recorded = server.takeRequest()
        assertEquals("/execute", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"edition\":\"2024\""))
        assertTrue(body.contains("\"channel\":\"stable\""))
        assertTrue(body.contains("println!"))
    }

    @Test
    fun `includes a non-default playground path`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        val executor = RemoteExecutor(OkHttpClient()) { server.url("/rust/").toString() }

        executor.execute(PlaygroundRequest(code = "fn main() {}"))

        assertEquals("/rust/execute", server.takeRequest().path)
    }

    @Test
    fun `maps rate limiting`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))

        val result = executor().execute(PlaygroundRequest(code = "fn main() {}"))

        assertFalse(result.success)
        assertEquals(ExecFailure.RATE_LIMITED, result.failure)
    }

    @Test
    fun `maps server errors`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))

        val result = executor().execute(PlaygroundRequest(code = "fn main() {}"))

        assertFalse(result.success)
        assertEquals(ExecFailure.SERVER_ERROR, result.failure)
    }

    @Test
    fun `maps unexpected bodies`() = runBlocking {
        server.enqueue(MockResponse().setBody("not json"))

        val result = executor().execute(PlaygroundRequest(code = "fn main() {}"))

        assertFalse(result.success)
        assertEquals(ExecFailure.INVALID_RESPONSE, result.failure)
    }

    @Test
    fun `maps timeouts`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val result = executor().execute(PlaygroundRequest(code = "fn main() {}"))

        assertFalse(result.success)
        assertEquals(ExecFailure.TIMEOUT, result.failure)
    }

    @Test
    fun `rejects oversized snippets before using the network`() = runBlocking {
        val code = "a".repeat(PlaygroundRequest.MAX_CODE_BYTES + 1)

        val result = executor().execute(PlaygroundRequest(code = code))

        assertFalse(result.success)
        assertEquals(ExecFailure.TOO_LARGE, result.failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `falls back to the public playground when the url is blank`() {
        // Only checks the constant plumbing: no request is made.
        assertEquals("https://play.rust-lang.org", RemoteExecutor.DEFAULT_BASE_URL)
    }
}
