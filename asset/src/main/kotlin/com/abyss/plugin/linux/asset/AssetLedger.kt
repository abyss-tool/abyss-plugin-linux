package com.abyss.plugin.linux.asset

import kotlinx.serialization.Serializable

/**
 * 本地资产账本（DC-102 §3 ledger.json —— abyss-asset-ledger-v1）。
 *
 * 状态机（LIN-003 §4.3，9 状态）：IDLE → CHECKING →（NEED_UPDATE）→
 * DOWNLOADING → VERIFYING → INSTALLING → READY；任一失败 → FAILED
 * （保留已完成分卷，可续传）；用户取消 → CANCELLED（保留进度）。
 */
enum class AssetLedgerState(val id: String) {
    IDLE("IDLE"),
    CHECKING("CHECKING"),
    DOWNLOADING("DOWNLOADING"),
    VERIFYING("VERIFYING"),
    INSTALLING("INSTALLING"),
    READY("READY"),
    FAILED("FAILED"),
    CANCELLED("CANCELLED");

    companion object {
        fun fromId(raw: String): AssetLedgerState? = entries.firstOrNull { it.id == raw }
    }
}

@Serializable
data class LedgerEntry(
    val id: String,
    val version: String,
    val state: AssetLedgerState = AssetLedgerState.IDLE,
    /**
     * 资产种类（NG-7，深挖第三轮）：LRU 保留策略「至少保留 1 个可用 rootfs」的
     * 判定依据 —— 旧实现无种类概念，混合资产（rootfs + image/toolchain）下
     * 「唯一 rootfs」被当作「唯一 READY 条目」误判，可被 LRU 回收。
     * null = 未标注（旧账本 / 测试），不计入 rootfs 保留（fail-open 仅限
     * 保留策略，不产生越权）；安装器写入时必须携带。
     */
    val type: AssetType? = null,
    val installedAt: String? = null,
    val lastVerifiedAt: String? = null,
    val sha256: String? = null,
    /** 断点续传账本（完成分卷集合；重下只补缺口，E2E-11）。 */
    val completedVolumes: List<Int> = emptyList(),
    val sizeBytes: Long = 0,
    /** 最近引用时间（LRU 清理的「最久未用」依据）。 */
    val lastReferencedAt: Long = 0,
)

@Serializable
data class AssetLedger(
    val schema: String = "abyss-asset-ledger-v1",
    val entries: List<LedgerEntry> = emptyList(),
    /** 配额字节（默认 8GB；上限 = 剩余空间 70%，LIN-003 §5）。 */
    val quotaBytes: Long = DEFAULT_QUOTA_BYTES,
    val lastCleanup: String? = null,
) {
    init {
        require(schema == "abyss-asset-ledger-v1") { "账本 schema 非法 → $schema" }
        require(quotaBytes > 0) { "配额必须为正 → $quotaBytes" }
    }

    fun entryOf(id: String): LedgerEntry? = entries.firstOrNull { it.id == id }

    companion object {
        const val DEFAULT_QUOTA_BYTES: Long = 8L * 1024 * 1024 * 1024 // 8GB
    }
}

/** 清理审计记录（LRU 回收写审计 —— LIN-003 §5）。 */
data class CleanupAudit(
    val assetId: String,
    val version: String,
    val freedBytes: Long,
    val reason: String,
    val atEpochMs: Long,
)

/**
 * 配额与 LRU 清理计算器（LIN-003 §5 —— 纯 JVM 决策，IO 由调用方执行）。
 *
 * 规则：
 *  - 超过配额 90% 触发清理建议；
 *  - 按「未引用 + 最久未用」排序清理至回到 90% 以下；
 *  - 正被会话引用的资产禁止清理；至少保留 1 个可用 rootfs；
 *  - 每次清理写审计（资产 id / 版本 / 释放大小 / 原因）。
 */
object QuotaPolicy {

    const val HIGH_WATERMARK_RATIO: Double = 0.9
    const val LOW_WATERMARK_RATIO: Double = 0.9

    /** 当前占用。 */
    fun usedBytes(ledger: AssetLedger): Long =
        ledger.entries.filter { it.state == AssetLedgerState.READY }.sumOf { it.sizeBytes }

    /** 是否触发清理（占用 ≥ 配额 90%）。 */
    fun shouldCleanup(ledger: AssetLedger): Boolean =
        usedBytes(ledger) >= (ledger.quotaBytes * HIGH_WATERMARK_RATIO).toLong()

    /**
     * 计算清理计划（不执行 IO）：返回按 LRU 顺序待清理条目与审计预览。
     *
     * @param referencedIds 会话当前引用中的资产 id（禁止清理）
     */
    fun cleanupPlan(
        ledger: AssetLedger,
        referencedIds: Set<String>,
        nowEpochMs: Long,
    ): List<CleanupAudit> {
        if (!shouldCleanup(ledger)) return emptyList()
        var used = usedBytes(ledger)
        val target = (ledger.quotaBytes * LOW_WATERMARK_RATIO).toLong()
        // 候选：READY 且未被引用的资产；rootfs 至少保留 1 个可用
        val candidates = ledger.entries
            .filter { it.state == AssetLedgerState.READY && it.id !in referencedIds }
            .sortedBy { it.lastReferencedAt } // LRU：最久未用在前
        val audits = mutableListOf<CleanupAudit>()
        // NG-7：按「种类 = ROOTFS 的 READY 条目」计数 —— 混合资产下
        // image/toolchain 的存在不再稀释保留判定（旧实现数全部 READY）。
        var rootfsKept = ledger.entries.count {
            it.state == AssetLedgerState.READY && it.type == AssetType.ROOTFS
        }
        for (entry in candidates) {
            if (used < target) break
            // 至少保留 1 个可用 rootfs（LIN-003 §5 保留策略 —— 按种类计数）
            if (entry.type == AssetType.ROOTFS) {
                if (rootfsKept <= 1) continue
                rootfsKept -= 1
            }
            used -= entry.sizeBytes
            audits += CleanupAudit(
                assetId = entry.id,
                version = entry.version,
                freedBytes = entry.sizeBytes,
                reason = "配额 LRU：占用超 90% 水位（引用集外最久未用）",
                atEpochMs = nowEpochMs,
            )
        }
        return audits
    }
}

/**
 * 断点续传计划（LIN-003 F-4 / E2E-11 —— 不重复已完成分卷）。
 */
object ResumePlanner {

    /** 待下载分卷序号（账本已完成集的补集，按 index 升序）。 */
    fun pendingVolumes(descriptor: AssetDescriptor, completed: Set<Int>): List<VolumeSpec> =
        descriptor.volumes.filter { it.index !in completed }

    /** 全部完成且整像待校验（VERIFYING 前置）。 */
    fun allVolumesComplete(descriptor: AssetDescriptor, completed: Set<Int>): Boolean =
        pendingVolumes(descriptor, completed).isEmpty()
}
