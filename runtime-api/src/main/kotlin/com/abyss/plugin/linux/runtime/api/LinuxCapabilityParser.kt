package com.abyss.plugin.linux.runtime.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 能力描述 JSON 解析器（DC-101 §2 —— 索引侧 capabilities 描述的
 * 纯 JVM 解析，FR-001：零 Android 依赖，fail-closed）。
 *
 * 两种输入形态（字段全为可选输入，输出校验后补全事实源默认值）：
 *  - 插件仓 `plugin.json` 的 `capabilities["linux-runtime-v1"]`
 *    （capability / provider / since 来自 provides[] 侧信息，可缺省）；
 *  - 插件中心 `plugins.json` 条目透传后的完整描述（DC-101 §2 全字段）。
 *
 * 解析纪律：坏数据一律抛 [IllegalArgumentException]（调用方负责
 * fail-closed —— 坏索引整页 Error，不展示部分数据，PLG-003 FR-001）。
 */
object LinuxCapabilityParser {

    // 严格 JSON：ignoreUnknownKeys 保留做 schema 前向兼容（与主仓
    // PluginParsers 同款纪律），但不 isLenient —— 畸形值绝不猜成合法态。
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    @Serializable
    private data class DomainDto(
        val id: String? = null,
        val kernel: String? = null,
        val coldStartMs: Int? = null,
        val features: List<String> = emptyList(),
        val assets: List<String> = emptyList(),
    )

    @Serializable
    private data class LimitsDto(
        val maxMemoryMb: Int? = null,
        val maxExecTimeoutSec: Int? = null,
        val maxOutputBytes: Long? = null,
        val maxConcurrentExec: Int? = null,
        val defaultQuotaGb: Int? = null,
    )

    @Serializable
    private data class AuthDto(
        val shizuku: String? = null,
        val grantTtlSec: Int? = null,
        val mediator: String? = null,
    )

    @Serializable
    private data class CapabilityDto(
        val capability: String? = null,
        val provider: String? = null,
        val since: String? = null,
        val hostApi: Int? = null,
        val domains: List<DomainDto> = emptyList(),
        val arch: List<String> = emptyList(),
        val limits: LimitsDto? = null,
        val auth: AuthDto? = null,
    )

    /** 解析能力描述 JSON（capabilities 对象内的单能力形态）。 */
    fun parse(raw: String): LinuxCapability {
        val dto = json.decodeFromString(CapabilityDto.serializer(), raw)
        return fromDto(dto)
    }

    fun parseCapabilityId(raw: String): CapabilityId {
        val trimmed = raw.trim()
        // 形态先行校验（CapabilityId 构造期 require），统一以 E_CAPABILITY_MISMATCH 语义抛出
        val id = runCatching { CapabilityId(trimmed) }.getOrElse {
            throw IllegalArgumentException(
                "能力 id 形态非法 → $trimmed（E_CAPABILITY_MISMATCH，应为 ^[a-z0-9-]+-v[0-9]+\$）",
            )
        }
        require(id.value == CapabilityId.LINUX_RUNTIME_V1.value) {
            "当前唯一注册能力为 linux-runtime-v1 → ${id.value}（E_CAPABILITY_MISMATCH）"
        }
        return id
    }

    private fun fromDto(dto: CapabilityDto): LinuxCapability {
        val domainIds = dto.domains.map { d ->
            val id = RuntimeDomain.fromId(d.id ?: "")
            requireNotNull(id) { "domains[].id 非法 → ${d.id}（应为 PROOT_USERLAND / QEMU_VM）" }
            val kernel = d.kernel ?: id.kernel
            val coldStartMs = requireNotNull(d.coldStartMs) { "domains[${id.id}].coldStartMs 缺失（切域弹层耗时预期数据源）" }
            val features = d.features.map { f ->
                requireNotNull(Feature.fromId(f)) { "domains[${id.id}].features 非法 → $f" }
            }.toSet()
            DomainInfo(
                id = id,
                kernel = kernel,
                coldStartMs = coldStartMs,
                features = features,
                assets = d.assets.toSet(),
            )
        }
        return LinuxCapability(
            capability = dto.capability?.let { parseCapabilityId(it) } ?: CapabilityId.LINUX_RUNTIME_V1,
            provider = dto.provider ?: "abyss-plugin-linux",
            since = dto.since ?: "0.2.0",
            hostApi = dto.hostApi ?: 2,
            domains = domainIds,
            arch = dto.arch.toSet().ifEmpty { setOf("arm64") },
            limits = CapabilityLimits(
                maxMemoryMb = dto.limits?.maxMemoryMb ?: ResourceLimits.DEFAULT_MAX_MEMORY_MB,
                maxExecTimeoutSec = dto.limits?.maxExecTimeoutSec ?: ResourceLimits.DEFAULT_EXEC_TIMEOUT_SEC,
                maxOutputBytes = dto.limits?.maxOutputBytes ?: SessionSpec.MAX_OUTPUT_BYTES,
                maxConcurrentExec = dto.limits?.maxConcurrentExec ?: 1,
                defaultQuotaGb = dto.limits?.defaultQuotaGb ?: CapabilityLimits.DEFAULT_QUOTA_GB,
            ),
            auth = CapabilityAuth(
                shizuku = dto.auth?.shizuku ?: "ownAuth",
                grantTtlSec = dto.auth?.grantTtlSec ?: SessionGrant.TTL_MAX_SEC.toInt(),
                mediator = dto.auth?.mediator ?: "PluginBroker",
            ),
        )
    }
}
