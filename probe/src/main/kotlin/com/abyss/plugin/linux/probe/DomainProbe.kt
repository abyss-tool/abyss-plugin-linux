package com.abyss.plugin.linux.probe

/**
 * 执行域探测决策（LIN-001 §2.4 / LIN-004 §5.4 / LIN-005 —— 纯 JVM）。
 *
 * 探测降级链（设计承接自应用构建器 m1-scaffold 的 DegradeChain/BaseProbe
 * 三件套 —— ADR-100 D-3 / ARC-103 M-2 迁出；决策表与 ROM 清单逻辑
 * 署名保留，标签对齐 LIN-005 双执行域语义）：
 *
 * ```
 * PROBING
 *  ├─ ptrace 可用（App 沙箱）           → READY [via=app_sandbox]
 *  ├─ ptrace 受限 + Shizuku 已授权      → READY [via=uid2000]
 *  ├─ ptrace 受限 + Shizuku 未授权      → DEGRADED [NEED_SHIZUKU]（引导后重探测 ≤1 次）
 *  ├─ ptrace 受限 + 授权后仍失败         → DEGRADED [PLATFORM_BLOCKED]
 *  │     ├─ allowDomainFallback → 用户确认 → 重建会话（QEMU_VM）
 *  │     └─ 否 / 无镜像 / 存储不足       → UNAVAILABLE（+ ROM 清单）
 *  └─ QEMU 探测不满足（内存/memlock）    → UNAVAILABLE [E_VM_RESOURCE]
 * ```
 *
 * 纪律四条（LIN-004 §5.4）：不静默重试（同原因 ≤1 次且用户可见）；不
 * 静默切域（弹层确认 + 耗时/存储/性能预期）；不假装 READY（任一未绿
 * 不得 READY）；平台限制如实上报（已知 ROM 清单进 romHint）。
 */
object DomainProbe {

    /** 探测超时（LIN-001 FR-002：只读、无副作用、5s）。 */
    const val PROBE_TIMEOUT_SECONDS: Int = 5

    /** 已知 ptrace 受限 ROM 清单（LIN-001 C-8 —— 平台限制，非代码缺陷）。 */
    val KNOWN_RESTRICTED_ROMS: List<KnownRom> = listOf(
        KnownRom(
            manufacturerPrefixes = listOf("oppo", "oneplus", "realme"),
            romName = "ColorOS 15/16",
            behavior = "运行约 1 分钟后安全警告并杀进程（ptrace 被安全策略限制）",
            workaround = "Shizuku uid2000 上下文可缓解但不保证 —— ColorOS 16 的「权限监控」也可能拦截 Shizuku 本身（临时切英语系统 → 开发者选项 Disable system optimization → 重启 Shizuku；可能影响 LTPO 刷新率/续航，需权衡）",
        ),
        KnownRom(
            manufacturerPrefixes = listOf("honhor", "honor"),
            romName = "荣耀 MagicOS",
            behavior = "内核拒绝（严格 SELinux 策略）",
            workaround = "无已知缓解路径 —— PRoot 域不可用，可评估切 QEMU_VM 完整系统域",
        ),
    )

    data class KnownRom(
        val manufacturerPrefixes: List<String>,
        val romName: String,
        val behavior: String,
        val workaround: String,
    ) {
        fun summary(): String = "$romName：$behavior"
    }

    /** 探测结果（探测协议：执行器由 Android 侧注入，5s 看门狗）。 */
    enum class ProbeOutcome { OK, DENIED, TIMEOUT, NOT_RUN }

    /** 环境探测输入（Shizuku 状态 + ptrace 探测 + 切域许可）。 */
    data class Environment(
        val shizukuRunning: Boolean,
        val shizukuAuthorized: Boolean,
        val probe: ProbeOutcome,
        /** 用户是否已在切域弹层确认 allowDomainFallback（DEGRADED 出口之一）。 */
        val qemuFallbackConfirmed: Boolean = false,
    ) {
        companion object {
            val INITIAL = Environment(false, false, ProbeOutcome.NOT_RUN)
        }
    }

    /** 降级档位（带 via / reason 标签，LIN-004 §5.4）。 */
    enum class Level {
        /** 探测未执行。 */
        NOT_INITIALIZED,
        /** 就绪（uid2000 上下文，推荐档）。 */
        READY,
        /** Shizuku 运行中但本插件未获授权 —— 引导一次后重探测。 */
        NEEDS_AUTH,
        /** App 沙箱纯 PRoot 降级档（部分设备可行）。 */
        DEGRADED_SANDBOX,
        /** 平台限制且无法绕过（含用户拒绝切域 / 无镜像 / 存储不足）。 */
        UNAVAILABLE,
    }

    /** 决策结果（档位 + via/reason + 人话说明）。 */
    data class Decision(val level: Level, val via: String, val reason: String, val humanMessage: String)

    /** 决策表（唯一实现；首页 / 详情页 / 引导共用）。 */
    fun resolve(env: Environment): Decision = when {
        env.probe == ProbeOutcome.DENIED || env.probe == ProbeOutcome.TIMEOUT ->
            decision(
                Level.UNAVAILABLE, "platform_blocked",
                "E_PROBE_PTRACE_BLOCKED",
                "当前设备系统限制（探测被平台策略否决）。已知受限 ROM 见清单 —— 属系统限制而非本插件缺陷，构建不会启动",
            )
        !env.shizukuRunning && env.probe == ProbeOutcome.OK ->
            decision(
                Level.DEGRADED_SANDBOX, "app_sandbox", "PROOT_SANDBOX",
                "降级（App 沙箱内运行）：Shizuku 未运行。沙箱模式受厂商 ptrace 策略影响，ColorOS / MagicOS 等受限 ROM 可能不可用；建议启动 Shizuku（无线调试或 ADB）获得更稳的 shell 上下文",
            )
        env.shizukuRunning && !env.shizukuAuthorized ->
            decision(
                Level.NEEDS_AUTH, "need_shizuku", "NEED_SHIZUKU",
                "Shizuku 已运行，但本插件尚未获得独立授权。Shizuku 授权严格 per-app：请在本插件内单独授权一次（授权后自动重探测）",
            )
        env.shizukuRunning && env.shizukuAuthorized ->
            decision(
                Level.READY, "uid2000", "OK",
                "就绪（Shizuku shell 上下文 uid 2000，推荐档）—— 引擎经授权上下文拉起，厂商针对「普通应用 ptrace」的策略不直接作用于这一层",
            )
        else -> decision(Level.NOT_INITIALIZED, "not_run", "NOT_RUN", "未初始化：点击「探测环境」开始")
    }

    /** 解析探测输出 → [ProbeOutcome]（fail-closed：语义不明一律 DENIED）。 */
    fun parseOutcome(output: String, manufacturer: String): ProbeOutcome {
        // ① 已知受限 ROM 直接判定（清单权威，先于通用探测）
        if (restrictedRomOf(manufacturer) != null) return ProbeOutcome.DENIED
        // ② SELinux Enforcing + 厂商未知 = MagicOS 型拒绝的常见形态
        val enforcing = output.lineOf("getenforce") == "Enforcing"
        val tracerPid = output.lineOf("TracerPid")?.substringAfterLast(':')?.trim()
        return when {
            output.isBlank() -> ProbeOutcome.DENIED
            enforcing && manufacturer.isBlank() -> ProbeOutcome.DENIED
            // 显式 TracerPid=0 = 无 tracer（干净环境）；缺失 = 语义不明 → 拒绝不猜测
            tracerPid == "0" -> ProbeOutcome.OK
            else -> ProbeOutcome.DENIED
        }
    }

    /** 已知受限 ROM 匹配（manufacturer 小写前缀比对）。 */
    fun restrictedRomOf(manufacturer: String): KnownRom? {
        val m = manufacturer.trim().lowercase()
        if (m.isEmpty()) return null
        return KNOWN_RESTRICTED_ROMS.firstOrNull { rom ->
            rom.manufacturerPrefixes.any { m.startsWith(it) }
        }
    }

    /** 受限 ROM 的人话清单（UNAVAILABLE 档 romHint 展示）。 */
    fun restrictedRomSummary(manufacturer: String): String? =
        restrictedRomOf(manufacturer)?.summary()

    private fun decision(level: Level, via: String, reason: String, human: String) =
        Decision(level, via, reason, human)

    private fun String.lineOf(key: String): String? =
        lineGroups().firstOrNull { it.first.contains(key, ignoreCase = true) }?.second

    /** 探测输出解析：`--- cmd` 分节 → (节名, 首行值)。 */
    private fun String.lineGroups(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var currentKey = ""
        for (raw in split("\n")) {
            val line = raw.trim()
            if (line.startsWith("--- ")) {
                currentKey = line.removePrefix("--- ").trim()
            } else if (line.isNotEmpty() && currentKey.isNotEmpty()) {
                out += currentKey to line
                currentKey = ""
            }
        }
        return out
    }
}
