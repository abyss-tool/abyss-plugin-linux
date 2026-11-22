package com.abyss.plugin.linux.asset

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * abyss-asset-v1 大资产契约模型（DC-102 §2 / LIN-003 —— 纯 JVM，
 * 零 Android 依赖，FR-001）。
 *
 * 纪律（fail-closed）：坏清单 / 非法版本 / sha256 缺失 / 分卷断链 /
 * URL 越域一律拒收 —— 解析失败抛 [IllegalArgumentException]。
 */
@Serializable
data class AssetIndex(
    val schema: String = "abyss-asset-v1",
    val generatedAt: String = "",
    val minPlugin: String = "",
    val assets: List<AssetDescriptor> = emptyList(),
    val templates: List<AssetTemplate> = emptyList(),
) {
    init {
        require(schema == "abyss-asset-v1") { "schema 应为 abyss-asset-v1 → $schema" }
    }

    fun assetOf(id: String): AssetDescriptor? = assets.firstOrNull { it.id == id }
}

enum class AssetType(val id: String) {
    ROOTFS("rootfs"),
    IMAGE("image"),
    TOOLCHAIN("toolchain"),
    ENGINE_NATIVE("engine-native"),
    TEMPLATE("template");

    companion object {
        fun fromId(raw: String): AssetType? = entries.firstOrNull { it.id == raw }
    }
}

enum class AssetDomain(val id: String) {
    PROOT_USERLAND("PROOT_USERLAND"),
    QEMU_VM("QEMU_VM"),
    BOTH("BOTH");

    companion object {
        fun fromId(raw: String): AssetDomain? = entries.firstOrNull { it.id == raw }
    }
}

@Serializable
data class VolumeSpec(
    val index: Int,
    val sizeBytes: Long,
    /** 64 位小写 hex；分卷独立校验（双层校验第一层）。 */
    val sha256: String,
    val url: String,
) {
    init {
        require(index >= 0) { "分卷 index 应 ≥0 → $index" }
        require(sizeBytes > 0) { "分卷 sizeBytes 应 >0 → $sizeBytes" }
        require(SHA256_PATTERN.matches(sha256)) { "分卷 sha256 应为 64 位 hex → $sha256" }
    }

    companion object {
        val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}

@Serializable
data class AssetDescriptor(
    val id: String,
    val type: AssetType,
    val domain: AssetDomain,
    val arch: List<String>,
    val version: String,
    val sizeBytes: Long,
    /** 整像 sha256（双层校验第二层）。 */
    val sha256: String,
    val format: String = "",
    val license: String = "",
    val volumes: List<VolumeSpec> = emptyList(),
    val minPlugin: String = "",
    val url: String = "",
) {
    init {
        require(id.isNotBlank()) { "资产 id 不得为空" }
        require(arch.isNotEmpty()) { "资产 $id arch 不得为空" }
        require(arch.isNotEmpty()) { "资产 $id arch 至少声明一个架构" }
        require(sizeBytes > 0) { "资产 $id sizeBytes 应 >0" }
        require(VolumeSpec.SHA256_PATTERN.matches(sha256)) {
            "资产 $id 整像 sha256 应为 64 位 hex → $sha256"
        }
        require(license.isNotBlank()) { "资产 $id license 必填（SEC-100 许可合规）" }
        require(volumes.isNotEmpty()) { "资产 $id 必须分卷（大资产双层校验）" }
        // 分卷 index 从 0 连续（断链即拒，DC-012 AC-001）
        volumes.forEachIndexed { i, v ->
            require(v.index == i) { "资产 $id 分卷 index 应从 0 连续 → 位置 $i 实为 ${v.index}" }
        }
    }

    /** 双层校验体积一致性（分卷累计 = 整像体积；离散资产可整卷）。 */
    val volumesBytesTotal: Long get() = volumes.sumOf { it.sizeBytes }
}

@Serializable
data class AssetTemplate(
    val id: String,
    val imageId: String? = null,
    val arch: String = "arm64",
    val license: String = "",
    /** false = 仅用户自备（Windows 类镜像不分发，N-VM-004 FR-002）。 */
    val distributable: Boolean = true,
    val note: String = "",
) {
    init {
        require(id.isNotBlank()) { "模板 id 不得为空" }
        if (distributable) {
            require(license.isNotBlank()) { "可分发模板必须声明 license" }
        }
    }
}

/** URL 域白名单（LIN-003 F-2 / SEC-002 —— 重定向后复检在传输层）。 */
object AssetUrlPolicy {
    val ALLOWED_HOSTS: Set<String> = setOf(
        "raw.githubusercontent.com",
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    /** null = 合法；否则为人话拒收理由（E_ASSET_URL_DENIED）。 */
    fun check(url: String): String? {
        val parsed = runCatching { java.net.URI(url) }.getOrNull()
            ?: return "资产 URL 无法解析：$url"
        if (!parsed.scheme.equals("https", ignoreCase = true)) {
            return "资产 URL 非 https：$url"
        }
        val host = parsed.host?.lowercase() ?: ""
        if (host !in ALLOWED_HOSTS) {
            return "资产域不在白名单（$host）：$url —— E_ASSET_URL_DENIED"
        }
        return null
    }
}

/** 大资产清单解析器（严格 JSON：坏数据 fail-closed，绝不猜合法态）。 */
object AssetIndexParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun parse(raw: String): AssetIndex = json.decodeFromString(AssetIndex.serializer(), raw)

    /** 逐资产执行 URL 白名单复核（CI 门禁与运行时共用同一条纪律）。 */
    fun validateUrls(index: AssetIndex): List<String> = buildList {
        index.assets.forEach { a ->
            a.url.takeIf { it.isNotBlank() }?.let { AssetUrlPolicy.check(it)?.let { e -> add(e) } }
            a.volumes.forEach { v -> AssetUrlPolicy.check(v.url)?.let { e -> add(e) } }
        }
    }
}
