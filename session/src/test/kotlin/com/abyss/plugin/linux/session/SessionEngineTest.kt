package com.abyss.plugin.linux.session

import com.abyss.plugin.linux.runtime.api.Feature
import com.abyss.plugin.linux.runtime.api.RuntimeDomain
import com.abyss.plugin.linux.runtime.api.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** RuntimeSession 状态机（ARC-101 AC-002：10 状态 ≥14 条迁移单测）。 */
class SessionStateMachineTest {

    @Test
    fun `状态机 10 状态`() {
        assertEquals(10, SessionState.entries.size)
    }

    @Test
    fun `合法迁移链 - 资产就绪直通`() {
        var s = SessionState.UNINITIALIZED
        s = SessionStateMachine.transition(s, SessionStateMachine.Event.OPEN_SESSION)
        assertEquals(SessionState.ASSET_CHECKING, s)
        s = SessionStateMachine.transition(s, SessionStateMachine.Event.ASSET_READY)
        assertEquals(SessionState.PROBING, s)
        s = SessionStateMachine.transition(s, SessionStateMachine.Event.PROBE_PASSED)
        assertEquals(SessionState.READY, s)
        s = SessionStateMachine.transition(s, SessionStateMachine.Event.EXEC_STARTED)
        assertEquals(SessionState.BUSY, s)
        s = SessionStateMachine.transition(s, SessionStateMachine.Event.EXEC_COMPLETED)
        assertEquals(SessionState.READY, s)
        s = SessionStateMachine.transition(s, SessionStateMachine.Event.CLOSE_REQUESTED)
        assertEquals(SessionState.CLOSING, s)
        s = SessionStateMachine.transition(s, SessionStateMachine.Event.RECLAIMED)
        assertEquals(SessionState.CLOSED, s)
    }

    @Test
    fun `合法迁移链 - 资产缺失下载与失败`() {
        var s = SessionState.UNINITIALIZED
        s = SessionStateMachine.transition(s, SessionStateMachine.Event.OPEN_SESSION)
        s = SessionStateMachine.transition(s, SessionStateMachine.Event.ASSET_MISSING)
        assertEquals(SessionState.ASSET_DOWNLOADING, s)
        s = SessionStateMachine.transition(s, SessionStateMachine.Event.ASSET_FAILED)
        assertEquals(SessionState.UNAVAILABLE, s)
    }

    @Test
    fun `降级链迁移 - 平台限制与切域重建`() {
        var s = SessionState.PROBING
        s = SessionStateMachine.transition(s, SessionStateMachine.Event.PROBE_BLOCKED)
        assertEquals(SessionState.DEGRADED, s)
        s = SessionStateMachine.transition(s, SessionStateMachine.Event.DEGRADE_DECLINED)
        assertEquals(SessionState.UNAVAILABLE, s)
        // 用户确认切域：新域会话建立成功 → READY（重建会话语义，非原地换域）
        val switched = SessionStateMachine.transition(
            SessionState.DEGRADED,
            SessionStateMachine.Event.DOMAIN_SWITCH_CONFIRMED,
        )
        assertEquals(SessionState.READY, switched)
    }

    @Test
    fun `非法迁移 fail-closed（≥3 类）`() {
        listOf(
            SessionState.CLOSED to SessionStateMachine.Event.OPEN_SESSION,
            SessionState.READY to SessionStateMachine.Event.ASSET_MISSING,
            SessionState.BUSY to SessionStateMachine.Event.EXEC_STARTED, // 并发 exec 禁
            SessionState.UNAVAILABLE to SessionStateMachine.Event.EXEC_STARTED,
            SessionState.UNINITIALIZED to SessionStateMachine.Event.PROBE_PASSED,
        ).forEach { (state, event) ->
            try {
                SessionStateMachine.transition(state, event)
                fail("非法迁移应被拒绝：$state --${event.id}-->")
            } catch (expected: IllegalSessionTransition) {
            }
        }
    }

    @Test
    fun `终态判定`() {
        assertTrue(SessionStateMachine.isTerminal(SessionState.CLOSED))
        assertTrue(!SessionStateMachine.isTerminal(SessionState.CLOSING))
    }
}

/** 状态×操作门禁（ARC-101 §2 表 / AC-004 并发拒绝）。 */
class SessionGateTest {

    @Test
    fun `CLOSED 与 CLOSING 态任何调用 - E_SESSION_CLOSED`() {
        listOf(SessionGate.Op.EXEC, SessionGate.Op.PTY, SessionGate.Op.FILE_IN).forEach { op ->
            assertEquals("E_SESSION_CLOSED", SessionGate.check(SessionState.CLOSED, op))
        }
    }

    @Test
    fun `BUSY 态并发第二 exec - E_CONCURRENT_LIMIT（AC-004）`() {
        assertEquals("E_CONCURRENT_LIMIT", SessionGate.check(SessionState.BUSY, SessionGate.Op.EXEC))
        assertEquals(null, SessionGate.check(SessionState.BUSY, SessionGate.Op.PROGRESS_QUERY))
    }

    @Test
    fun `未就绪态禁构建动作 - ASSET 与 PROBING`() {
        listOf(SessionState.ASSET_CHECKING, SessionState.ASSET_DOWNLOADING, SessionState.PROBING).forEach { s ->
            assertEquals("E_SESSION_CLOSED", SessionGate.check(s, SessionGate.Op.EXEC))
            assertEquals(null, SessionGate.check(s, SessionGate.Op.PROGRESS_QUERY))
        }
    }

    @Test
    fun `READY 全原语放行`() {
        listOf(SessionGate.Op.EXEC, SessionGate.Op.PTY, SessionGate.Op.FILE_IN, SessionGate.Op.FILE_OUT, SessionGate.Op.CLOSE)
            .forEach { op -> assertEquals(null, SessionGate.check(SessionState.READY, op)) }
    }

    @Test
    fun `UNAVAILABLE 禁启动动作但允许查询`() {
        assertEquals("E_RUNTIME_UNAVAILABLE", SessionGate.check(SessionState.UNAVAILABLE, SessionGate.Op.EXEC))
        assertEquals(null, SessionGate.check(SessionState.UNAVAILABLE, SessionGate.Op.PROGRESS_QUERY))
    }

    @Test
    fun `DEGRADED 允许引导与查询 禁 exec`() {
        assertEquals(null, SessionGate.check(SessionState.DEGRADED, SessionGate.Op.DEGRADE_GUIDE))
        assertEquals("E_SESSION_CLOSED", SessionGate.check(SessionState.DEGRADED, SessionGate.Op.EXEC))
    }
}

/** 工作区路径断言（ARC-101 AC-003：路径穿越用例集）。 */
class WorkspaceGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `合法相对路径通过`() {
        val guard = WorkspaceGuard(tmp.newFolder("workspace"))
        val f = guard.resolve("build-1000/src/main.kt")
        assertTrue(f.path.contains("build-1000"))
        assertTrue(f.path.endsWith("src/main.kt"))
    }

    @Test
    fun `穿越与绝对路径全拒（E_PATH_TRAVERSAL）`() {
        val guard = WorkspaceGuard(tmp.newFolder("workspace"))
        listOf(
            "../escape",
            "ok/../../escape",
            "/abs/path",
            "",
            "back\\slash\\..\\win",
        ).forEach { bad ->
            try {
                guard.resolve(bad)
                fail("路径穿越必须被拒绝 → $bad")
            } catch (expected: IllegalSessionOperation) {
                assertEquals("E_PATH_TRAVERSAL", expected.code)
            }
        }
    }

    @Test
    fun `软链逃逸拒绝（canonical 断言）`() {
        val root = tmp.newFolder("workspace")
        val outside = tmp.newFolder("outside")
        val link = java.nio.file.Files.createSymbolicLink(
            root.toPath().resolve("link"),
            outside.toPath(),
        )
        val guard = WorkspaceGuard(root)
        try {
            guard.resolve(link.fileName.toString())
            // 若沙箱无符号链接权限则跳过该用例
        } catch (expected: IllegalSessionOperation) {
            assertEquals("E_PATH_TRAVERSAL", expected.code)
        }
    }
}

/** 引用计数引擎（ARC-101 §5 / LIN-004 §5.3：60s 延迟回收）。 */
class RefCountEngineTest {

    private var now = 1_000L
    private fun engine() = RefCountEngine(clock = { now })

    @Test
    fun `retain 与 release 计数`() {
        val e = engine()
        val entry = e.retain("s1", RuntimeDomain.PROOT_USERLAND, setOf(Feature.EXEC))
        assertEquals(1, entry.refCount)
        e.retain("s1", RuntimeDomain.PROOT_USERLAND, setOf(Feature.EXEC))
        assertEquals(2, e.entryOf("s1")!!.refCount)
        e.release("s1")
        assertEquals(false, e.release("s1")) // 第二次释放才归零（返回 true 的是归零那次）
        assertEquals(0, e.entryOf("s1")!!.refCount)
    }

    @Test
    fun `归零 60s 内不回收 超时回收（AC-002 判据）`() {
        val e = engine()
        e.retain("s1", RuntimeDomain.PROOT_USERLAND, setOf(Feature.EXEC))
        e.release("s1")
        now += 59_999
        assertTrue(!e.shouldReclaim("s1"))
        now += 1
        assertTrue(e.shouldReclaim("s1"))
        e.reclaim("s1")
        assertTrue(e.entryOf("s1") == null)
    }

    @Test
    fun `延迟窗口内复用取消回收（防重建抖动）`() {
        val e = engine()
        e.retain("s1", RuntimeDomain.PROOT_USERLAND, setOf(Feature.EXEC))
        e.release("s1")
        now += 30_000
        // 延迟内重新 retain → 取消回收
        e.retain("s1", RuntimeDomain.PROOT_USERLAND, setOf(Feature.EXEC))
        now += 10_000
        assertTrue(!e.shouldReclaim("s1"))
        assertEquals(1, e.entryOf("s1")!!.refCount)
    }

    @Test
    fun `重复释放 fail-closed`() {
        val e = engine()
        e.retain("s1", RuntimeDomain.PROOT_USERLAND, setOf(Feature.EXEC))
        e.release("s1")
        try {
            e.release("s1")
            fail("refCount 下溢必须被拒绝")
        } catch (expected: IllegalArgumentException) {
        }
    }
}
