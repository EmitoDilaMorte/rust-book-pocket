package dev.rustbook.pocket.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaygroundRequestTest {

    @Test
    fun `parses the payload mdbook sends`() {
        val payload = """
            {
              "code": "fn main() { println!(\"hi\"); }",
              "channel": "stable",
              "mode": "debug",
              "edition": "2024",
              "crateType": "bin",
              "tests": false,
              "backtrace": false
            }
        """.trimIndent()

        val request = PlaygroundRequest.fromBookPayload(payload)

        assertEquals("fn main() { println!(\"hi\"); }", request?.code)
        assertEquals("2024", request?.edition)
        assertEquals("stable", request?.channel)
    }

    @Test
    fun `applies defaults for missing fields`() {
        val request = PlaygroundRequest.fromBookPayload("""{"code":"fn main() {}"}""")

        assertEquals("stable", request?.channel)
        assertEquals("debug", request?.mode)
        assertEquals("2024", request?.edition)
        assertEquals("bin", request?.crateType)
        assertEquals(false, request?.tests)
    }

    @Test
    fun `accepts the legacy evaluate payload from mdbook`() {
        val payload = """
            {
              "version": "nightly",
              "optimize": "1",
              "code": "fn main() {}",
              "edition": "2024"
            }
        """.trimIndent()

        val request = PlaygroundRequest.fromBookPayload(payload)

        assertEquals("nightly", request?.channel)
        assertEquals("release", request?.mode)
        assertEquals("2024", request?.edition)
    }

    @Test
    fun `rejects multi-file payloads it cannot compile`() {
        val payload = """{"code":[{"name":"main.rs","content":"fn main() {}"}]}"""

        assertNull(PlaygroundRequest.fromBookPayload(payload))
    }

    @Test
    fun `rejects empty code`() {
        assertNull(PlaygroundRequest.fromBookPayload("""{"code":"   "}"""))
        assertNull(PlaygroundRequest.fromBookPayload("""{"code":""}"""))
        assertNull(PlaygroundRequest.fromBookPayload("not json"))
    }

    @Test
    fun `serializes back into the playground shape`() {
        val json = PlaygroundRequest(code = "fn main() {}").toJson()

        assertEquals("fn main() {}", json.getString("code"))
        assertEquals("2024", json.getString("edition"))
        assertTrue(json.has("backtrace"))
    }

    @Test
    fun `parses playground results`() {
        val result = ExecResult.parse(
            """{"success":false,"stdout":"","stderr":"error[E0384]","exitDetail":"Exited with status 1"}""",
        )

        assertEquals(false, result?.success)
        assertEquals("error[E0384]", result?.stderr)
        assertEquals("Exited with status 1", result?.exitDetail)
    }

    @Test
    fun `rejects bodies without a success flag`() {
        assertNull(ExecResult.parse("""{"stdout":"hi"}"""))
    }

    @Test
    fun `failure results round-trip through json`() {
        val json = ExecResult.failed(ExecFailure.OFFLINE).toJson()

        assertEquals(false, json.getBoolean("success"))
        assertEquals("OFFLINE", json.getString("exitDetail"))
        assertEquals("", json.getString("stdout"))
    }
}
