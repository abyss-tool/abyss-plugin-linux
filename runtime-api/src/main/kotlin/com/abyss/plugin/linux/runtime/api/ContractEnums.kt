package com.abyss.plugin.linux.runtime.api

import kotlinx.serialization.Serializable

/**
 * 执行域（SRS-101 术语事实源 / ADR-100 D-2）。
 *
 * - [PROOT_USERLAND]：复用 Android 内核的 Alpine 用户空间（PRoot ptrace
 *   用户态 chroot 模拟）。冷启动目标 ≤8s，默认域。
 * - [QEMU_VM]：自带 Linux 内核的完整系统（QEMU TCG arm64）。冷启动
 *   目标 ≤180s，显式切换的兼容域（非默认）。
 *
 * 纪律（ARC-101 §2）：会话不可原地换域 —— 切域 = close 旧会话 +
 * openSession 新会话，必须经用户确认并展示耗时/存储/性能预期。
 */
enum class RuntimeDomain(val id: String, val kernel: String) {
    PROOT_USERLAND("PROOT_USERLAND", "shared-android"),
    QEMU_VM("QEMU_VM", "guest-linux");

    companion object {
        fun fromId(raw: String): RuntimeDomain? = entries.firstOrNull { it.id == raw }
    }
}

/** 能力标识（SRS-101）：当前唯一注册能力 = `linux-runtime-v1`。 */
@Serializable
data class CapabilityId(val value: String) {
    init {
        require(CAPABILITY_PATTERN.matches(value)) {
            "capability id 形态非法 → $value（应为 ^[a-z0-9-]+-v[0-9]+\$，DC-100 §2）"
        }
    }

    val major: Int get() = value.substringAfterLast("-v").toInt()

    override fun toString(): String = value

    companion object {
        val CAPABILITY_PATTERN = Regex("^[a-z0-9-]+-v[0-9]+$")

        /** 全生态当前唯一注册能力。 */
        val LINUX_RUNTIME_V1 = CapabilityId("linux-runtime-v1")
    }
}

/**
 * 会话状态机（ARC-101 §2，10 状态）。
 *
 * 迁移表（节选关键边，完整门禁见 :session 的 SessionStateMachine）：
 * ```
 * UNINITIALIZED ─openSession→ ASSET_CHECKING
 * ASSET_CHECKING ─资产缺失→ ASSET_DOWNLOADING ─失败→ UNAVAILABLE
 * ASSET_DOWNLOADING ─校验通过→ PROBING
 * PROBING ─探测通过→ READY ─exec/pty→ BUSY ─完成→ READY
 * PROBING ─平台限制→ DEGRADED ─拒绝/无法切域→ UNAVAILABLE
 * READY ─close/引用归零→ CLOSING ─回收完成→ CLOSED
 * ```
 */
enum class SessionState(val id: String) {
    UNINITIALIZED("UNINITIALIZED"),
    ASSET_CHECKING("ASSET_CHECKING"),
    ASSET_DOWNLOADING("ASSET_DOWNLOADING"),
    PROBING("PROBING"),
    DEGRADED("DEGRADED"),
    READY("READY"),
    BUSY("BUSY"),
    UNAVAILABLE("UNAVAILABLE"),
    CLOSING("CLOSING"),
    CLOSED("CLOSED");

    companion object {
        fun fromId(raw: String): SessionState? = entries.firstOrNull { it.id == raw }
    }
}

/** 会话能力特性（ARC-101 §1 能力矩阵）。 */
enum class Feature(val id: String) {
    EXEC("EXEC"),
    PTY("PTY"),
    FILE_IO("FILE_IO"),
    PKG_INSTALL("PKG_INSTALL"),
    PORT_FORWARD("PORT_FORWARD"),
    VNC("VNC"),
    SNAPSHOT("SNAPSHOT");

    companion object {
        fun fromId(raw: String): Feature? = entries.firstOrNull { it.id == raw }
    }
}

/** PRoot 域能力矩阵（ARC-101 §1：VNC/SNAPSHOT 不支持，返回 E_FEATURE_UNSUPPORTED）。 */
val PROOT_SUPPORTED_FEATURES: Set<Feature> = setOf(Feature.EXEC, Feature.PTY, Feature.FILE_IO, Feature.PKG_INSTALL)

/** QEMU 域能力矩阵（公共子集 + VNC / SNAPSHOT / PORT_FORWARD）。 */
val QEMU_SUPPORTED_FEATURES: Set<Feature> = Feature.entries.toSet()

/**
 * Shizuku 授权展示状态（LIN-005 §4，消费方握手时只读 RuntimeInfo.shizukuState，
 * 不自行判断）。
 */
enum class ShizukuAuthState(val id: String) {
    /** 未检测到 Shizuku + 安装引导。 */
    NOT_INSTALLED("NOT_INSTALLED"),

    /** 已安装未运行 + 无线调试/ADB 启动引导。 */
    NOT_RUNNING("NOT_RUNNING"),

    /** 运行中但未授权本插件 + 跳转授权列表引导。 */
    NOT_GRANTED("NOT_GRANTED"),

    /** 已授权（显示授权包名与 uid 2000；允许 uid2000 链路）。 */
    GRANTED("GRANTED"),

    /** 授权被撤销 → 重新授权引导。 */
    REVOKED("REVOKED"),

    /** 授权失效（Shizuku 重启等）→ 重新检测。 */
    STALE("STALE");

    companion object {
        fun fromId(raw: String): ShizukuAuthState? = entries.firstOrNull { it.id == raw }
    }
}

/** 探测摘要原因码（非 E_ 前缀的诊断标签，LIN-001/004）。 */
object ProbeSummary {
    /** ptrace 被平台限制（ColorOS 15/16 / MagicOS；已知 ROM 清单 + 切域建议）。 */
    const val PROOT_BLOCKED: String = "PROOT_BLOCKED"

    /** ptrace 经 App 沙箱可用。 */
    const val VIA_APP_SANDBOX: String = "app_sandbox"

    /** ptrace 经 Shizuku uid2000 上下文可用。 */
    const val VIA_UID2000: String = "uid2000"

    /** Shizuku 授权缺失（降级链第 2 步，最多重探测 1 次）。 */
    const val NEED_SHIZUKU: String = "NEED_SHIZUKU"

    /** 平台限制且无法绕过（降级链第 3 步）。 */
    const val PLATFORM_BLOCKED: String = "PLATFORM_BLOCKED"
}
