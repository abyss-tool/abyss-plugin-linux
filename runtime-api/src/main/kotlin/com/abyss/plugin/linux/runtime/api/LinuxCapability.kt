package com.abyss.plugin.linux.runtime.api

import kotlinx.serialization.Serializable

/**
 * 运行时第一帧快照（DC-101 §3 RuntimeInfo —— getCapability 后首帧，
 * 消费方握手分流的数据源：版本 / 域 / 资产 / 授权 / 探测摘要）。
 */
@Serializable
data class RuntimeInfo(
    val pluginVersion: String,
    val hostApi: Int,
    val capability: CapabilityId = CapabilityId.LINUX_RUNTIME_V1,
    val domains: List<DomainInfo> = emptyList(),
    /** 资产是否就绪（false → 消费方展示资产下载入口，不启动构建）。 */
    val assetsReady: Boolean = false,
    val shizukuState: ShizukuAuthState = ShizukuAuthState.NOT_INSTALLED,
    /** 探测摘要（PROOT_BLOCKED → 消费方给切域选项；via 标签见 ProbeSummary）。 */
    val probeSummary: String = "",
    /** 引擎版本表（proot / qemu / rootfs 等）。 */
    val engineVersions: Map<String, String> = emptyMap(),
) {
    init {
        require(SemVerPattern.matches(pluginVersion)) { "pluginVersion 非法 SemVer → $pluginVersion" }
        require(hostApi >= 2) { "linux-runtime-v1 能力要求 HOST_API ≥ 2（依赖解析与能力绑定）" }
    }

    companion object {
        val SemVerPattern: Regex = Regex("""^\d+\.\d+\.\d+$""")
    }
}

/** 域信息（DC-101 §2 domains[] —— 切域确认弹层的耗时预期数据源）。 */
@Serializable
data class DomainInfo(
    val id: RuntimeDomain,
    val kernel: String,
    /** 冷启动毫秒目标（PROOT ≤8000 / QEMU ≤180000，ENG-100 §7）。 */
    val coldStartMs: Int,
    val features: Set<Feature> = emptySet(),
    /** 依赖的资产类别（rootfs / toolchain / image）。 */
    val assets: Set<String> = emptySet(),
) {
    init {
        require(kernel == id.kernel) { "kernel 必须与域能力描述一致 → $kernel vs ${id.kernel}" }
        require(coldStartMs > 0) { "coldStartMs 应为正整数 → $coldStartMs" }
    }
}

/**
 * 能力描述（DC-101 §2 capabilities["linux-runtime-v1"] —— 索引侧机器可读
 * 描述，纯 JVM 解析并单测，零 Android 依赖，FR-001）。
 */
@Serializable
data class LinuxCapability(
    val capability: CapabilityId = CapabilityId.LINUX_RUNTIME_V1,
    val provider: String = "abyss-plugin-linux",
    val since: String = "0.2.0",
    val hostApi: Int = 2,
    val domains: List<DomainInfo> = emptyList(),
    val arch: Set<String> = setOf("arm64"),
    val limits: CapabilityLimits = CapabilityLimits(),
    val auth: CapabilityAuth = CapabilityAuth(),
) {
    init {
        require(hostApi >= 2) { "hostApi 必须 ≥ 2（HOST_API v2 能力绑定）" }
        require("arm64" in arch) { "arch 必须含 arm64（当前基线）" }
        require(domains.map { it.id }.containsAll(listOf(RuntimeDomain.PROOT_USERLAND, RuntimeDomain.QEMU_VM))) {
            "双执行域必须同时声明（A-1 假设 / ADR-100 D-2）"
        }
        require(since.isNotBlank()) { "since 必填（能力起始版本）" }
    }

    /** 域的能力矩阵查询（请求前先查，不满足返回明确错误码，禁止隐藏失败）。 */
    fun featuresOf(domain: RuntimeDomain): Set<Feature> =
        domains.firstOrNull { it.id == domain }?.features ?: emptySet()

    fun supports(domain: RuntimeDomain, feature: Feature): Boolean =
        feature in featuresOf(domain)
}

/** 能力限额（DC-101 §2 limits —— 消费方不得越权提高的事实源）。 */
@Serializable
data class CapabilityLimits(
    val maxMemoryMb: Int = ResourceLimits.DEFAULT_MAX_MEMORY_MB,
    val maxExecTimeoutSec: Int = ResourceLimits.DEFAULT_EXEC_TIMEOUT_SEC,
    val maxOutputBytes: Long = SessionSpec.MAX_OUTPUT_BYTES,
    val maxConcurrentExec: Int = 1,
    val defaultQuotaGb: Int = DEFAULT_QUOTA_GB,
) {
    init {
        require(maxMemoryMb > 0 && maxExecTimeoutSec > 0 && maxOutputBytes > 0) {
            "能力限额必须为正数（$maxMemoryMb / $maxExecTimeoutSec / $maxOutputBytes）"
        }
        require(maxConcurrentExec == 1) { "maxConcurrentExec 恒为 1（会话内串行）" }
        require(defaultQuotaGb in 1..64) { "defaultQuotaGb 应在 1..64 → $defaultQuotaGb" }
    }

    companion object {
        /** 资产配额默认 8GB（LIN-003 §5；用户可调，上限 = 剩余空间 70%）。 */
        const val DEFAULT_QUOTA_GB: Int = 8
    }
}

/** 授权描述（DC-101 §2 auth —— 声明必须经宿主中介，禁止直连）。 */
@Serializable
data class CapabilityAuth(
    val shizuku: String = "ownAuth",
    /** grant TTL（秒；≤300）。 */
    val grantTtlSec: Int = SessionGrant.TTL_MAX_SEC.toInt(),
    val mediator: String = "PluginBroker",
) {
    init {
        require(shizuku == "ownAuth") { "auth.shizuku 必须为 ownAuth（唯一授权持有方）" }
        require(grantTtlSec in 1..SessionGrant.TTL_MAX_SEC.toInt()) {
            "grantTtlSec 应 ≤ ${SessionGrant.TTL_MAX_SEC} → $grantTtlSec（ARC-102 FR-005）"
        }
        require(mediator == "PluginBroker") { "auth.mediator 必须为 PluginBroker（插件间永不直连）" }
    }
}
