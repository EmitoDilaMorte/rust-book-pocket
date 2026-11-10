package dev.rustbook.pocket.runner

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutorRouterTest {

    private class FakeExecutor(
        override val id: String,
        private val available: Boolean = true,
        private val result: ExecResult = ExecResult.success(stdout = "ok"),
    ) : RustExecutor {
        var calls = 0
            private set

        override fun isAvailable(): Boolean = available

        override suspend fun execute(request: PlaygroundRequest): ExecResult {
            calls++
            return result
        }
    }

    private fun router(
        local: RustExecutor?,
        mode: ExecutionMode,
        online: Boolean = true,
        remote: FakeExecutor = FakeExecutor("remote"),
    ): Pair<ExecutorRouter, FakeExecutor> = ExecutorRouter(
        remote = remote,
        local = local,
        mode = { mode },
        isOnline = { online },
    ) to remote

    @Test
    fun `auto prefers the local toolchain for std-only code`() = runBlocking {
        val local = FakeExecutor("local")
        val (router, remote) = router(local = local, mode = ExecutionMode.AUTO)

        router.execute(PlaygroundRequest(code = "fn main() { println!(\"hi\"); }"))

        assertEquals(1, local.calls)
        assertEquals(0, remote.calls)
    }

    @Test
    fun `auto sends snippets with external crates to the playground`() = runBlocking {
        val local = FakeExecutor("local")
        val (router, remote) = router(local = local, mode = ExecutionMode.AUTO)

        router.execute(PlaygroundRequest(code = "use rand::Rng;\nfn main() {}"))

        assertEquals(0, local.calls)
        assertEquals(1, remote.calls)
    }

    @Test
    fun `remote mode ignores an installed toolchain`() = runBlocking {
        val local = FakeExecutor("local")
        val (router, remote) = router(local = local, mode = ExecutionMode.REMOTE)

        router.execute(PlaygroundRequest(code = "fn main() {}"))

        assertEquals(0, local.calls)
        assertEquals(1, remote.calls)
    }

    @Test
    fun `local mode never uploads silently`() = runBlocking {
        val local = FakeExecutor("local", available = false)
        val (router, remote) = router(local = local, mode = ExecutionMode.LOCAL)

        val result = router.execute(PlaygroundRequest(code = "fn main() {}"))

        assertFalse(result.success)
        assertEquals(ExecFailure.LOCAL_UNAVAILABLE, result.failure)
        assertEquals(0, remote.calls)
    }

    @Test
    fun `auto falls back to remote when the toolchain is missing`() = runBlocking {
        val local = FakeExecutor("local", available = false)
        val (router, remote) = router(local = local, mode = ExecutionMode.AUTO)

        val result = router.execute(PlaygroundRequest(code = "fn main() {}"))

        assertTrue(result.success)
        assertEquals(1, remote.calls)
    }

    @Test
    fun `reports offline when nothing can run the snippet`() = runBlocking {
        val (router, remote) = router(local = null, mode = ExecutionMode.AUTO, online = false)

        val result = router.execute(PlaygroundRequest(code = "fn main() {}"))

        assertFalse(result.success)
        assertEquals(ExecFailure.OFFLINE, result.failure)
        assertEquals(0, remote.calls)
    }

    @Test
    fun `rejects oversized snippets`() = runBlocking {
        val local = FakeExecutor("local")
        val (router, _) = router(local = local, mode = ExecutionMode.AUTO)

        val result = router.execute(
            PlaygroundRequest(code = "a".repeat(PlaygroundRequest.MAX_CODE_BYTES + 1)),
        )

        assertFalse(result.success)
        assertEquals(ExecFailure.TOO_LARGE, result.failure)
        assertEquals(0, local.calls)
    }
}
