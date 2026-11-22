package com.abyss.plugin.linux.shizuku

/**
 * Shizuku 授权状态机（LIN-005 §4 —— 5 状态展示语义 + 转移规则）。
 *
 * 状态写入 RuntimeInfo.shizukuState；消费方握手时读取、不自行判断。
 * 授权被撤销时进行中的会话不强行提权：完成当前命令后标 DEGRADED，
 * 下次启动重新探测（LIN-005 §6）。
 */
enum class ShizukuGateState(val id: String) {
    NOT_INSTALLED("NOT_INSTALLED"),
    NOT_RUNNING("NOT_RUNNING"),
    NOT_GRANTED("NOT_GRANTED"),
    GRANTED("GRANTED"),
    REVOKED("REVOKED"),
    STALE("STALE");

    companion object {
        fun fromId(raw: String): ShizukuGateState? = entries.firstOrNull { it.id == raw }
    }
}

/** 授权状态转移（事件驱动；非法转移 fail-closed）。 */
object ShizukuAuthMachine {

    enum class Event(val id: String) {
        DETECT_NOT_INSTALLED("detect.not_installed"),
        DETECT_RUNNING("detect.running"),
        DETECT_STOPPED("detect.stopped"),
        GRANT("grant"),
        REVOKE("revoke"),
        SHELL_RESTART("shizuku.restart"),
        RECHECK("recheck"),
    }

    private val transitions: Map<Pair<ShizukuGateState, Event>, ShizukuGateState> = buildMap {
        put(ShizukuGateState.NOT_INSTALLED to Event.DETECT_RUNNING, ShizukuGateState.NOT_GRANTED)
        put(ShizukuGateState.NOT_INSTALLED to Event.RECHECK, ShizukuGateState.NOT_INSTALLED)
        put(ShizukuGateState.NOT_RUNNING to Event.DETECT_RUNNING, ShizukuGateState.NOT_GRANTED)
        put(ShizukuGateState.NOT_RUNNING to Event.DETECT_NOT_INSTALLED, ShizukuGateState.NOT_INSTALLED)
        put(ShizukuGateState.NOT_RUNNING to Event.RECHECK, ShizukuGateState.NOT_RUNNING)
        put(ShizukuGateState.NOT_GRANTED to Event.GRANT, ShizukuGateState.GRANTED)
        put(ShizukuGateState.NOT_GRANTED to Event.DETECT_STOPPED, ShizukuGateState.NOT_RUNNING)
        put(ShizukuGateState.NOT_GRANTED to Event.DETECT_NOT_INSTALLED, ShizukuGateState.NOT_INSTALLED)
        put(ShizukuGateState.GRANTED to Event.REVOKE, ShizukuGateState.REVOKED)
        put(ShizukuGateState.GRANTED to Event.SHELL_RESTART, ShizukuGateState.STALE)
        put(ShizukuGateState.GRANTED to Event.DETECT_STOPPED, ShizukuGateState.STALE)
        put(ShizukuGateState.REVOKED to Event.GRANT, ShizukuGateState.GRANTED)
        put(ShizukuGateState.REVOKED to Event.DETECT_STOPPED, ShizukuGateState.NOT_RUNNING)
        put(ShizukuGateState.STALE to Event.RECHECK, ShizukuGateState.NOT_GRANTED)
        put(ShizukuGateState.STALE to Event.GRANT, ShizukuGateState.GRANTED)
    }

    /** 合法事件集。 */
    fun allowedEvents(state: ShizukuGateState): Set<Event> =
        transitions.keys.filter { it.first == state }.map { it.second }.toSet()

    /** 迁移（fail-closed）。 */
    fun transition(state: ShizukuGateState, event: Event): ShizukuGateState {
        return transitions[state to event]
            ?: throw IllegalStateException("非法授权状态迁移：${state.id} --${event.id}--> ∅")
    }

    /** 展示语义（详情页 / 授权引导直出人话；执行动作列明）。 */
    fun guidance(state: ShizukuGateState): String = when (state) {
        ShizukuGateState.NOT_INSTALLED -> "未检测到 Shizuku —— 请先安装并启动（无线调试或 ADB）"
        ShizukuGateState.NOT_RUNNING -> "Shizuku 未运行 —— 请启动一次（重启后通常需要重新启动）"
        ShizukuGateState.NOT_GRANTED -> "请为「Linux 运行环境」单独授权一次（全生态唯一授权动作）"
        ShizukuGateState.GRANTED -> "已授权（uid 2000 shell 上下文 · 引擎推荐档）"
        ShizukuGateState.REVOKED -> "授权已被撤销 —— 请重新授权"
        ShizukuGateState.STALE -> "授权已失效（Shizuku 重启）—— 请重新检测并授权"
    }
}
