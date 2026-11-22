package com.abyss.plugin.linux.asset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** abyss-asset-v1 契约与账本（DC-102 AC-001~003 / LIN-003）。 */
class AssetContractsTest {

    private val sha64: (Int) -> String = { n -> n.toString().repeat(64).take(64) }

    private fun volume(i: Int, url: String = "https://raw.githubusercontent.com/abyss-tool/abyss-plugin-linux/main/assets/v$i") =
        VolumeSpec(index = i, sizeBytes = 1024L * (i + 1), sha256 = sha64(i + 1), url = url)

    private fun descriptor(
        id: String = "alpine-rootfs",
        type: AssetType = AssetType.ROOTFS,
        domain: AssetDomain = AssetDomain.PROOT_USERLAND,
        volumes: List<VolumeSpec> = listOf(volume(0), volume(1)),
    ) = AssetDescriptor(
        id = id, type = type, domain = domain, arch = listOf("arm64"),
        version = "3.19.1", sizeBytes = 1024L * 3, sha256 = sha64(9),
        format = "tar.gz", license = "Alpine Linux（MIT & GPL 组件）",
        volumes = volumes, minPlugin = "0.2.0",
        url = "https://raw.githubusercontent.com/abyss-tool/abyss-plugin-linux/main/assets/alpine-rootfs.tar.gz",
    )

    @Test
    fun `整像契约解析与双层校验结构`() {
        val d = descriptor()
        assertEquals(2, d.volumes.size)
        assertEquals(1024L * 3, d.volumesBytesTotal)
        assertEquals(AssetType.ROOTFS, d.type)
    }

    @Test
    fun `sha256 缺失或非 64 位 hex 拒收（AC-001）`() {
        try {
            descriptor().copy(sha256 = "deadbeef")
            fail("整像 sha256 形态非法必须被拒绝")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            volume(0).copy(sha256 = "AB")
            fail("分卷 sha256 形态非法必须被拒绝")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `分卷断链（index 不连续）拒收（AC-001）`() {
        try {
            descriptor(volumes = listOf(volume(0), volume(2)))
            fail("分卷 index 必须从 0 连续")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("连续"))
        }
    }

    @Test
    fun `license 必填（许可合规）`() {
        try {
            descriptor().copy(license = "")
            fail("license 缺失必须被拒绝")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `URL 域白名单（AC-003 - 外域 URL 拒收）`() {
        assertEquals(null, AssetUrlPolicy.check("https://raw.githubusercontent.com/a/b/c"))
        assertEquals(null, AssetUrlPolicy.check("https://github.com/abyss-tool/x/releases/download/v1/y"))
        assertTrue(AssetUrlPolicy.check("https://evil.example.com/x")!!.contains("白名单"))
        assertTrue(AssetUrlPolicy.check("http://raw.githubusercontent.com/a/b")!!.contains("https"))
    }

    @Test
    fun `清单 JSON 解析（严格 fail-closed）`() {
        val json = """
            {
              "schema": "abyss-asset-v1",
              "generatedAt": "2026-11-22T00:00:00Z",
              "minPlugin": "0.2.0",
              "assets": [
                {
                  "id": "alpine-rootfs", "type": "rootfs", "domain": "PROOT_USERLAND",
                  "arch": ["arm64"], "version": "3.19.1", "sizeBytes": 3145728,
                  "sha256": "${sha64(1)}", "format": "tar.gz", "license": "Alpine",
                  "volumes": [
                    {"index": 0, "sizeBytes": 1048576, "sha256": "${sha64(2)}",
                     "url": "https://raw.githubusercontent.com/abyss-tool/abyss-plugin-linux/main/assets/a.vol0"},
                    {"index": 1, "sizeBytes": 2097152, "sha256": "${sha64(3)}",
                     "url": "https://raw.githubusercontent.com/abyss-tool/abyss-plugin-linux/main/assets/a.vol1"}
                  ],
                  "minPlugin": "0.2.0", "url": "https://raw.githubusercontent.com/abyss-tool/abyss-plugin-linux/main/assets/a.tar.gz"
                }
              ],
              "templates": []
            }
        """.trimIndent()
        val index = AssetIndexParser.parse(json)
        assertEquals(1, index.assets.size)
        val d = index.assets.single()
        assertEquals("alpine-rootfs", d.id)
        assertEquals(3_145_728L, d.sizeBytes)
        assertTrue(AssetIndexParser.validateUrls(index).isEmpty())
        // 坏 JSON 拒绝
        try {
            AssetIndexParser.parse("{ not json")
            fail("坏清单必须被拒绝")
        } catch (expected: Exception) {
        }
    }

    @Test
    fun `模板许可纪律（Windows 不分发）`() {
        AssetTemplate(id = "windows-xp", distributable = false, note = "仅用户自备镜像")
        try {
            AssetTemplate(id = "windows-xp", distributable = true, license = "Proprietary")
            fail("Windows 类模板必须 distributable=false")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            AssetTemplate(id = "alpine-min", distributable = true, license = "")
            fail("可分发模板必须声明 license")
        } catch (expected: IllegalArgumentException) {
        }
    }
}

/** 配额与 LRU 清理（LIN-003 §5：8GB / 90% 水位 / 保留策略 / 审计）。 */
class QuotaPolicyTest {

    private fun entry(id: String, sizeBytes: Long, lastRef: Long = 0L) = LedgerEntry(
        id = id, version = "1.0", state = AssetLedgerState.READY,
        sha256 = "a".repeat(64), sizeBytes = sizeBytes, lastReferencedAt = lastRef,
    )

    @Test
    fun `配额默认 8GB 与水位触发`() {
        assertEquals(8L * 1024 * 1024 * 1024, AssetLedger.DEFAULT_QUOTA_BYTES)
        // 未超 90% → 不清理
        val under = AssetLedger(entries = listOf(entry("a", AssetLedger.DEFAULT_QUOTA_BYTES / 2)))
        assertTrue(!QuotaPolicy.shouldCleanup(under))
        // 超 90% → 触发
        val over = AssetLedger(entries = listOf(entry("a", AssetLedger.DEFAULT_QUOTA_BYTES)))
        assertTrue(QuotaPolicy.shouldCleanup(over))
    }

    @Test
    fun `引用中的资产禁止清理（保留策略）`() {
        val ledger = AssetLedger(
            entries = listOf(
                entry("rootfs-a", 6L * 1024 * 1024 * 1024, lastRef = 1),
                entry("rootfs-b", 3L * 1024 * 1024 * 1024, lastRef = 2),
            ),
        )
        // rootfs-a 被会话引用 → 只能清 rootfs-b（LRU 最久未用）
        val plan = QuotaPolicy.cleanupPlan(ledger, referencedIds = setOf("rootfs-a"), nowEpochMs = 99)
        assertTrue(plan.none { it.assetId == "rootfs-a" })
        assertTrue(plan.any { it.assetId == "rootfs-b" })
    }

    @Test
    fun `至少保留 1 个可用 rootfs`() {
        val ledger = AssetLedger(entries = listOf(entry("only-rootfs", 9L * 1024 * 1024 * 1024, lastRef = 1)))
        val plan = QuotaPolicy.cleanupPlan(ledger, referencedIds = emptySet(), nowEpochMs = 99)
        assertTrue("唯一的 rootfs 不得被清理", plan.isEmpty())
    }

    @Test
    fun `清理写审计（id / 版本 / 释放量 / 原因）`() {
        val ledger = AssetLedger(
            entries = listOf(
                entry("rootfs-old", 5L * 1024 * 1024 * 1024, lastRef = 1),
                entry("rootfs-new", 5L * 1024 * 1024 * 1024, lastRef = 99_999),
            ),
        )
        val plan = QuotaPolicy.cleanupPlan(ledger, referencedIds = emptySet(), nowEpochMs = 100)
        val first = plan.first()
        assertEquals("rootfs-old", first.assetId) // LRU：最久未用优先
        assertEquals("1.0", first.version)
        assertEquals(5L * 1024 * 1024 * 1024, first.freedBytes)
        assertTrue(first.reason.contains("配额"))
    }
}

/** 断点续传计划（LIN-003 F-4 / E2E-11：不重复已完成分卷）。 */
class ResumePlannerTest {

    @Test
    fun `只补缺口分卷`() {
        val volumes = listOf(
            VolumeSpec(0, 1L, "a".repeat(64), "https://raw.githubusercontent.com/abyss-tool/x/main/0"),
            VolumeSpec(1, 2L, "b".repeat(64), "https://raw.githubusercontent.com/abyss-tool/x/main/1"),
            VolumeSpec(2, 3L, "c".repeat(64), "https://raw.githubusercontent.com/abyss-tool/x/main/2"),
        )
        val d = AssetDescriptor("alpine-rootfs", AssetType.ROOTFS, AssetDomain.PROOT_USERLAND,
            listOf("arm64"), "3.19.1", 6L, "d".repeat(64), "tar.gz", "Alpine",
            volumes, "0.2.0", "https://raw.githubusercontent.com/abyss-tool/x/main/a")
        val pending = ResumePlanner.pendingVolumes(d, completed = setOf(0, 1))
        assertEquals(listOf(2), pending.map { it.index })
        assertTrue(!ResumePlanner.allVolumesComplete(d, setOf(0, 1)))
        assertTrue(ResumePlanner.allVolumesComplete(d, setOf(0, 1, 2)))
    }
}
