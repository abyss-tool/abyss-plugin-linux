package com.abyss.plugin.linux.session

import com.abyss.plugin.linux.runtime.api.Feature
import com.abyss.plugin.linux.runtime.api.RuntimeDomain
import com.abyss.plugin.linux.runtime.api.SessionState
import java.io.File
import java.util.UUID

/**
 * RuntimeSession 状态机（ARC-101 §2 / LIN-004 §5 —— 纯 JVM，零 Android 依赖）。
 *
 * 10 状态 + 事件驱动的迁移表；`SessionGate` 承载「状态 × 操作」合法性
 * 门禁（ARC-101 §2 表）。任何非法事件 / 非法操作一律 fail-closed
 * （抛 [IllegalSessionTransition] / [IllegalSessionOperation]，由调用方
 * 翻译为 DC-102 错误码回执）。
 */
object SessionStateMachine {

    /** 会话事件（迁移触发器）。 */
    enum class Event(val id: String) {
        OPEN_SESSION("openSession"),
        ASSET_MISSING("asset.missing"),
        ASSET_READY("asset.ready"),
        ASSET_FAILED("asset.failed"),
        PROBE_PASSED("probe.passed"),
        PROBE_BLOCKED("probe.blocked"),
        DEGRADE_DECLINED("degrade.declined"),
        DOMAIN_SWITCH_CONFIRMED("domain.switch.confirmed"),
        EXEC_STARTED("exec.started"),
        EXEC_COMPLETED("exec.completed"),
        CLOSE_REQUESTED("close.requested"),
        RECLAIMED("reclaimed"),
    }

    /** 迁移表（ARC-101 §2；缺省 = 非法迁移）。 */
    private val transitions: Map<Pair<SessionState, Event>, SessionState> = buildMap {
        put(SessionState.UNINITIALIZED to Event.OPEN_SESSION, SessionState.ASSET_CHECKING)
        put(SessionState.ASSET_CHECKING to Event.ASSET_MISSING, SessionState.ASSET_DOWNLOADING)
        put(SessionState.ASSET_CHECKING to Event.ASSET_READY, SessionState.PROBING)
        put(SessionState.ASSET_DOWNLOADING to Event.ASSET_READY, SessionState.PROBING)
        put(SessionState.ASSET_DOWNLOADING to Event.ASSET_FAILED, SessionState.UNAVAILABLE)
        put(SessionState.PROBING to Event.PROBE_PASSED, SessionState.READY)
        put(SessionState.PROBING to Event.PROBE_BLOCKED, SessionState.DEGRADED)
        put(SessionState.DEGRADED to Event.DEGRADE_DECLINED, SessionState.UNAVAILABLE)
        // 用户确认切域：重建会话（close 旧 + open 新）；本事件语义为
        // 「新域会话建立成功」→ READY（非原地换域，ARC-101 §2）。
        put(SessionState.DEGRADED to Event.DOMAIN_SWITCH_CONFIRMED, SessionState.READY)
        put(SessionState.READY to Event.EXEC_STARTED, SessionState.BUSY)
        put(SessionState.BUSY to Event.EXEC_COMPLETED, SessionState.READY)
        put(SessionState.READY to Event.CLOSE_REQUESTED, SessionState.CLOSING)
        put(SessionState.CLOSING to Event.RECLAIMED, SessionState.CLOSED)
    }

    /** 当前状态下的合法事件集（非法事件 → [IllegalSessionTransition]）。 */
    fun allowedEvents(state: SessionState): Set<Event> =
        transitions.keys.filter { it.first == state }.map { it.second }.toSet()

    /** 迁移（fail-closed）。 */
    fun transition(state: SessionState, event: Event): SessionState {
        val next = transitions[state to event]
            ?: throw IllegalSessionTransition(
                "非法状态迁移：${state.id} --${event.id}--> ∅（LIN-004 §5.1 生命周期表外）",
            )
        return next
    }

    /** 终态判定（CLOSED 后任何调用 → E_SESSION_CLOSED）。 */
    fun isTerminal(state: SessionState): Boolean = state == SessionState.CLOSED
}

/** 非法状态迁移（fail-closed；调用方翻译为 DC-102 人话回执）。 */
class IllegalSessionTransition(message: String) : IllegalStateException(message)

/** 非法操作（状态门禁拒绝；E_SESSION_CLOSED / E_CONCURRENT_LIMIT 等）。 */
class IllegalSessionOperation(val code: String, message: String) : IllegalStateException(message)

/**
 * 会话操作门禁（ARC-101 §2 状态×操作合法性表）。
 */
object SessionGate {

    enum class Op(val id: String) {
        EXEC("exec"),
        PTY("pty"),
        FILE_IN("fileIn"),
        FILE_OUT("fileOut"),
        PROGRESS_QUERY("progress.query"),
        CANCEL_DOWNLOAD("download.cancel"),
        PROBE("probe"),
        DEGRADE_GUIDE("degrade.guide"),
        STATE_QUERY("state.query"),
        CLOSE("close"),
    }

    private val gates: Map<SessionState, Set<Op>> = buildMap {
        put(SessionState.UNINITIALIZED, setOf(Op.PROGRESS_QUERY, Op.STATE_QUERY))
        put(SessionState.ASSET_CHECKING, setOf(Op.PROGRESS_QUERY, Op.CANCEL_DOWNLOAD, Op.STATE_QUERY))
        put(SessionState.ASSET_DOWNLOADING, setOf(Op.PROGRESS_QUERY, Op.CANCEL_DOWNLOAD, Op.STATE_QUERY))
        put(SessionState.PROBING, setOf(Op.PROGRESS_QUERY, Op.STATE_QUERY))
        put(
            SessionState.DEGRADED,
            setOf(Op.PROGRESS_QUERY, Op.STATE_QUERY, Op.DEGRADE_GUIDE, Op.CLOSE),
        )
        put(
            SessionState.READY,
            setOf(Op.EXEC, Op.PTY, Op.FILE_IN, Op.FILE_OUT, Op.PROGRESS_QUERY, Op.STATE_QUERY, Op.CLOSE),
        )
        // BUSY：并发第二个 exec → E_CONCURRENT_LIMIT（ARC-101 AC-004）
        put(SessionState.BUSY, setOf(Op.PROGRESS_QUERY, Op.STATE_QUERY, Op.CLOSE))
        put(SessionState.UNAVAILABLE, setOf(Op.PROGRESS_QUERY, Op.STATE_QUERY, Op.CLOSE))
        put(SessionState.CLOSING, setOf(Op.STATE_QUERY))
        put(SessionState.CLOSED, setOf(Op.STATE_QUERY))
    }

    /** 操作合法性（null = 允许；否则为拒绝错误码）。 */
    fun check(state: SessionState, op: Op): String? = when {
        op !in gates.getValue(state) -> when {
            state == SessionState.CLOSED || state == SessionState.CLOSING -> "E_SESSION_CLOSED"
            state == SessionState.BUSY && op == Op.EXEC -> "E_CONCURRENT_LIMIT"
            state == SessionState.UNAVAILABLE -> "E_RUNTIME_UNAVAILABLE"
            else -> "E_SESSION_CLOSED" // 未就绪态的构建动作（ASSET_*/PROBING/DEGRADED）
        }
        else -> null
    }

    fun require(state: SessionState, op: Op) {
        check(state, op)?.let { code -> throw IllegalSessionOperation(code, "状态 ${state.id} 不允许 ${op.id} → $code") }
    }
}

/**
 * 工作区路径断言器（ARC-101 FR-004 / LIN-004 FR-010 —— canonical 断言）。
 *
 * 纪律：`../` 穿越 / 绝对路径 / 软链逃逸一律拒（E_PATH_TRAVERSAL）；
 * 会话工作区位于 App 私有目录之内（PLG-005 FR-005）。
 */
class WorkspaceGuard(private val root: File) {

    init {
        require(root.isDirectory) { "工作区根必须是已存在目录：$root" }
    }

    /** 工作区内相对路径 → canonical 绝对路径（越界即拒）。 */
    fun resolve(relativePath: String): File {
        if (relativePath.isBlank() || relativePath.startsWith("/") || relativePath.contains("\\")) {
            throw IllegalSessionOperation("E_PATH_TRAVERSAL", "工作区路径必须为相对路径 → $relativePath")
        }
        if (".." in relativePath.split('/', '\\')) {
            throw IllegalSessionOperation("E_PATH_TRAVERSAL", "工作区路径禁止穿越 → $relativePath")
        }
        val canonicalRoot = root.canonicalFile
        val candidate = File(root, relativePath).canonicalFile
        if (candidate != canonicalRoot && !candidate.path.startsWith(canonicalRoot.path + File.separator)) {
            throw IllegalSessionOperation(
                "E_PATH_TRAVERSAL",
                "canonical 断言失败：${candidate.path} 越出工作区 ${canonicalRoot.path}（软链逃逸或穿越）",
            )
        }
        return candidate
    }

    /** 每次构建独占工作区 `/workspace/build-<timestamp>`（ARC-101 §3）。 */
    fun newBuildDir(timestampMs: Long): File {
        val dir = File(root, "build-$timestampMs")
        require(!dir.exists()) { "工作区目录已存在（时间戳冲突）：${dir.name}" }
        dir.mkdirs()
        return dir
    }
}

/**
 * 引用计数引擎（ARC-101 §5 / LIN-004 §5.3 —— refCount + 60s 延迟回收）。
 *
 * 语义：
 *  - retain（openSession 成功 / 宿主代理接入）→ +1；
 *  - release（消费方 Binder 死亡 / close()）→ -1；
 *  - 归零 → 启动 60s 延迟回收（防短暂重建抖动）；延迟内 retain → 取消回收；
 *  - 延迟到期 → CLOSING（终止进程树、清理工作区、撤销 grant、写审计）。
 */
class RefCountEngine(
    private val clock: () -> Long = System::currentTimeMillis,
    private val reclaimDelayMs: Long = RECLAIM_DELAY_MS,
) {

    data class Entry(
        val sessionId: String,
        val domain: RuntimeDomain,
        val createdAt: Long,
        val grantedFeatures: Set<Feature>,
    ) {
        var refCount: Int = 0
        var zeroSince: Long? = null
    }

    private val sessions = linkedMapOf<String, Entry>()

    /** openSession 成功登记（refCount=1）。 */
    fun retain(sessionId: String, domain: RuntimeDomain, grantedFeatures: Set<Feature>): Entry {
        val existing = sessions[sessionId]
        if (existing != null) {
            existing.refCount += 1
            existing.zeroSince = null // 延迟回收窗口内的复用：取消回收
            return existing
        }
        val entry = Entry(sessionId, domain, clock(), grantedFeatures)
        entry.refCount = 1
        sessions[sessionId] = entry
        return entry
    }

    /** 消费方释放（DeathRecipient / close()）→ 归零返回 true。 */
    fun release(sessionId: String): Boolean {
        val entry = sessions[sessionId] ?: return false
        require(entry.refCount > 0) { "refCount 下溢（重复释放）：$sessionId" }
        entry.refCount -= 1
        if (entry.refCount == 0) {
            entry.zeroSince = clock()
            return true
        }
        return false
    }

    /** 归零后是否已到延迟回收时点（CLOSING 起点）。 */
    fun shouldReclaim(sessionId: String): Boolean {
        val entry = sessions[sessionId] ?: return false
        val since = entry.zeroSince ?: return false
        return clock() - since >= reclaimDelayMs
    }

    /** 回收完成（CLOSED）：移除登记。 */
    fun reclaim(sessionId: String) {
        sessions.remove(sessionId)
    }

    fun entryOf(sessionId: String): Entry? = sessions[sessionId]

    fun newSessionId(): String = UUID.randomUUID().toString()

    companion object {
        /** 引用归零后 60s 延迟回收（ARC-101 §5）。 */
        const val RECLAIM_DELAY_MS: Long = 60_000
    }
}
