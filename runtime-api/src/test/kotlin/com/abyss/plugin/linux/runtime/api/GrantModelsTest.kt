package com.abyss.plugin.linux.runtime.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** SessionGrant / Diagnostic / RuntimeSession 契约（ARC-102 AC-003 / DC-101）。 */

/** 主仓 PublisherSignatures.PINNED_SHA256 的十六进制形态（32 字节 → 64 hex）。 */
private val PUBLISHER_SHA256 = "76037821" + "4488D620" + "7978BA1E" + "37E6DC82" +
    "ABC2DE72" + "C9F6C67D" + "9B449515" + "8A99B769"

class GrantModelsTest {

    private fun grant(
        ttlSec: Long = 300,
        issuedAt: Long = 1_000_000L,
        nonce: String = "0123456789abcdef",
    ) = SessionGrant(
        callerPackage = "com.abyss.plugin.appbuilder",
        callerSignerSha256 = PUBLISHER_SHA256,
        sessionId = "sess-001",
        nonce = nonce,
        issuedAtEpochMs = issuedAt,
        expiryEpochMs = issuedAt + ttlSec * 1000,
        hostSignature = "host-sig",
    )

    @Test
    fun `grant TTL 上限 300 秒`() {
        assertEquals(300, SessionGrant.TTL_MAX_SEC)
        grant(ttlSec = 300) // 边界值合法
    }

    @Test
    fun `grant 超过 TTL 必须被拒`() {
        try {
            grant(ttlSec = 301)
            fail("TTL > 300s 必须被拒绝（ARC-102 FR-005）")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `grant 过期判定`() {
        val g = grant(ttlSec = 60, issuedAt = 1000)
        assertFalse(g.isExpired(nowEpochMs = 1000 + 59_999))
        assertTrue(g.isExpired(nowEpochMs = 1000 + 60_000))
    }

    @Test
    fun `grant 签名指纹形态必须为 64 位 hex`() {
        try {
            grant().copy(callerSignerSha256 = "not-hex")
            fail("签名指纹形态校验必须生效")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `Diagnostic 必须携带表内错误码`() {
        val d = Diagnostic(code = "E_DEP_MISSING").requireRegistered()
        assertEquals("请先安装 Linux 插件（提供运行环境）", d.humanHint)
        assertTrue(d.recoverable)
        try {
            Diagnostic(code = "E_FREE_TEXT").requireRegistered()
            fail("自由文本错误码必须被拒绝")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `Diagnostic 人话提示兜底链`() {
        val d = Diagnostic(code = "E_GRANT_EXPIRED", hint = "")
        assertEquals(AbyssErrorCodes.hintFor("E_GRANT_EXPIRED"), d.humanHint)
        val withHint = Diagnostic(code = "E_GRANT_EXPIRED", hint = "已自动重试")
        assertEquals("已自动重试", withHint.humanHint)
    }

    @Test
    fun `平台限制 romHint 常量可用`() {
        assertTrue(Diagnostic.ROM_HINT_PTRACE.contains("ColorOS"))
        assertTrue(Diagnostic.ROM_HINT_PTRACE.contains("MagicOS"))
        assertTrue(Diagnostic.ROM_HINT_PTRACE.contains("平台限制"))
    }

    @Test
    fun `READY 会话必须携带 grantedFeatures（不假装 READY）`() {
        try {
            RuntimeSession(
                id = "s1", domain = RuntimeDomain.PROOT_USERLAND, state = SessionState.READY,
                refCount = 1, grantedFeatures = emptySet(),
            )
            fail("READY 且零能力必须被拒绝（ARC-101 §2 纪律③）")
        } catch (expected: IllegalArgumentException) {
        }
        RuntimeSession(
            id = "s1", domain = RuntimeDomain.PROOT_USERLAND, state = SessionState.READY,
            refCount = 1, grantedFeatures = setOf(Feature.EXEC),
        )
    }

    @Test
    fun `PtyHandle 窗口尺寸边界`() {
        PtyHandle(ptyId = "p1", cols = 80, rows = 24)
        try {
            PtyHandle(ptyId = "p1", cols = 1, rows = 24)
            fail("cols 边界非法")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            PtyHandle(ptyId = "p1", cols = 80, rows = 2000)
            fail("rows 边界非法")
        } catch (expected: IllegalArgumentException) {
        }
    }
}

/** 能力描述 JSON 解析（DC-101 FR-001 / AC-001：字段逐项一致）。 */
class LinuxCapabilityParserTest {

    private val fullJson = """
        {
          "capability": "linux-runtime-v1",
          "provider": "abyss-plugin-linux",
          "since": "0.2.0",
          "hostApi": 2,
          "domains": [
            {"id": "PROOT_USERLAND", "kernel": "shared-android", "coldStartMs": 8000,
             "features": ["EXEC", "PTY", "FILE_IO", "PKG_INSTALL"], "assets": ["rootfs", "toolchain"]},
            {"id": "QEMU_VM", "kernel": "guest-linux", "coldStartMs": 180000,
             "features": ["EXEC", "PTY", "FILE_IO", "PKG_INSTALL", "VNC", "SNAPSHOT", "PORT_FORWARD"],
             "assets": ["image"]}
          ],
          "arch": ["arm64"],
          "limits": {"maxMemoryMb": 2048, "maxExecTimeoutSec": 1800, "maxOutputBytes": 1048576,
                     "maxConcurrentExec": 1, "defaultQuotaGb": 8},
          "auth": {"shizuku": "ownAuth", "grantTtlSec": 300, "mediator": "PluginBroker"},
          "futureOptionalField": {"ignored": true}
        }
    """.trimIndent()

    private val pluginJsonShape = """
        {
          "hostApi": 2,
          "domains": [
            {"id": "PROOT_USERLAND", "coldStartMs": 8000,
             "features": ["EXEC", "PTY", "FILE_IO", "PKG_INSTALL"], "assets": ["rootfs", "toolchain"]},
            {"id": "QEMU_VM", "coldStartMs": 180000,
             "features": ["EXEC", "PTY", "FILE_IO", "PKG_INSTALL", "VNC", "SNAPSHOT", "PORT_FORWARD"],
             "assets": ["image"]}
          ],
          "arch": ["arm64"],
          "limits": {"maxMemoryMb": 2048, "maxExecTimeoutSec": 1800, "maxOutputBytes": 1048576,
                     "maxConcurrentExec": 1, "defaultQuotaGb": 8},
          "auth": {"shizuku": "ownAuth", "grantTtlSec": 300, "mediator": "PluginBroker"}
        }
    """.trimIndent()

    @Test
    fun `完整形态解析 - 字段逐项一致（DC-101 AC-001）`() {
        val cap = LinuxCapabilityParser.parse(fullJson)
        assertEquals(CapabilityId.LINUX_RUNTIME_V1, cap.capability)
        assertEquals("abyss-plugin-linux", cap.provider)
        assertEquals("0.2.0", cap.since)
        assertEquals(2, cap.hostApi)
        assertEquals(2, cap.domains.size)
        assertEquals(8000, cap.domains.first { it.id == RuntimeDomain.PROOT_USERLAND }.coldStartMs)
        assertEquals(180000, cap.domains.first { it.id == RuntimeDomain.QEMU_VM }.coldStartMs)
        assertEquals(setOf("arm64"), cap.arch)
        assertEquals(2048, cap.limits.maxMemoryMb)
        assertEquals(1800, cap.limits.maxExecTimeoutSec)
        assertEquals(1_048_576L, cap.limits.maxOutputBytes)
        assertEquals(1, cap.limits.maxConcurrentExec)
        assertEquals(8, cap.limits.defaultQuotaGb)
        assertEquals(300, cap.auth.grantTtlSec)
        assertEquals("PluginBroker", cap.auth.mediator)
    }

    @Test
    fun `plugin json 形态解析 - 缺省字段补全事实源默认值`() {
        val cap = LinuxCapabilityParser.parse(pluginJsonShape)
        assertEquals(CapabilityId.LINUX_RUNTIME_V1, cap.capability)
        assertEquals("abyss-plugin-linux", cap.provider)
        assertEquals(2, cap.hostApi)
        assertEquals(2, cap.domains.size)
        // kernel 缺省 → 与域声明一致
        cap.domains.forEach { assertEquals(it.id.kernel, it.kernel) }
    }

    @Test
    fun `未知字段忽略（ignoreUnknownKeys 前向兼容）`() {
        LinuxCapabilityParser.parse(fullJson) // futureOptionalField 不炸
    }

    @Test
    fun `域查询与特性矩阵`() {
        val cap = LinuxCapabilityParser.parse(fullJson)
        assertTrue(cap.supports(RuntimeDomain.PROOT_USERLAND, Feature.EXEC))
        assertFalse(cap.supports(RuntimeDomain.PROOT_USERLAND, Feature.VNC))
        assertTrue(cap.supports(RuntimeDomain.QEMU_VM, Feature.VNC))
        assertTrue(cap.supports(RuntimeDomain.QEMU_VM, Feature.SNAPSHOT))
    }

    @Test
    fun `单域能力描述必须被拒（A-1 双执行域假设）`() {
        val bad = """
            {"hostApi": 2, "domains": [{"id": "PROOT_USERLAND", "coldStartMs": 8000, "features": ["EXEC"]}],
             "arch": ["arm64"], "auth": {"shizuku": "ownAuth", "grantTtlSec": 300, "mediator": "PluginBroker"}}
        """.trimIndent()
        try {
            LinuxCapabilityParser.parse(bad)
            fail("缺失 QEMU_VM 域必须被拒绝（A-1）")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("双执行域"))
        }
    }

    @Test
    fun `非 arm64 架构必须被拒`() {
        val bad = """
            {"hostApi": 2, "domains": [], "arch": ["x86_64"]}
        """.trimIndent()
        try {
            LinuxCapabilityParser.parse(bad)
            fail("arch 缺 arm64 必须被拒绝")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `grantTtlSec 超 300 必须被拒（ARC-102 FR-005）`() {
        val bad = """
            {"hostApi": 2, "domains": [], "arch": ["arm64"],
             "auth": {"shizuku": "ownAuth", "grantTtlSec": 600, "mediator": "PluginBroker"}}
        """.trimIndent()
        try {
            LinuxCapabilityParser.parse(bad)
            fail("grantTtlSec > 300 必须被拒绝")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `mediator 直连形态必须被拒（插件间永不直连）`() {
        val bad = """
            {"hostApi": 2, "domains": [], "arch": ["arm64"],
             "auth": {"shizuku": "ownAuth", "grantTtlSec": 300, "mediator": "direct"}}
        """.trimIndent()
        try {
            LinuxCapabilityParser.parse(bad)
            fail("非 PluginBroker 中介必须被拒绝")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `非法 capability id 必须被拒（E_CAPABILITY_MISMATCH）`() {
        try {
            LinuxCapabilityParser.parseCapabilityId("linux-runtime")
            fail("能力 id 形态非法必须被拒绝")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("E_CAPABILITY_MISMATCH"))
        }
    }

    @Test
    fun `坏 JSON 必须抛出（fail-closed 不猜合法态）`() {
        try {
            LinuxCapabilityParser.parse("{ not json")
            fail("坏 JSON 必须抛出")
        } catch (expected: Exception) {
        }
    }

    @Test
    fun `RuntimeInfo 契约与域信息`() {
        val info = RuntimeInfo(
            pluginVersion = "0.2.0",
            hostApi = 2,
            domains = listOf(
                DomainInfo(
                    id = RuntimeDomain.PROOT_USERLAND,
                    kernel = "shared-android",
                    coldStartMs = 8000,
                    features = setOf(Feature.EXEC, Feature.FILE_IO),
                    assets = setOf("rootfs", "toolchain"),
                ),
                DomainInfo(
                    id = RuntimeDomain.QEMU_VM,
                    kernel = "guest-linux",
                    coldStartMs = 180000,
                    features = QEMU_SUPPORTED_FEATURES,
                    assets = setOf("image"),
                ),
            ),
            assetsReady = false,
            shizukuState = ShizukuAuthState.NOT_GRANTED,
            probeSummary = ProbeSummary.PROOT_BLOCKED,
        )
        assertEquals(2, info.domains.size)
        assertEquals(ProbeSummary.PROOT_BLOCKED, info.probeSummary)
        try {
            info.copy(hostApi = 1)
            fail("hostApi < 2 必须被拒绝")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            info.copy(pluginVersion = "0.2")
            fail("非法 SemVer 必须被拒绝")
        } catch (expected: IllegalArgumentException) {
        }
    }
}
