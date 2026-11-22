package com.abyss.plugin.linux.runtime.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** SessionSpec / ExecCommand / ResourceLimits 校验矩阵（ARC-101 AC-002~004）。 */
class SessionSpecTest {

    @Test
    fun `默认规格 - PRoot 域 + EXEC FILE_IO`() {
        val spec = SessionSpec()
        assertEquals(RuntimeDomain.PROOT_USERLAND, spec.preferredDomain)
        assertEquals(setOf(Feature.EXEC, Feature.FILE_IO), spec.requestedFeatures)
        assertEquals(512, spec.workspaceSizeMb)
        assertTrue(spec.allowDomainFallback)
    }

    @Test
    fun `PRoot 域请求 VNC 必须被拒（不满足返回明确错误 禁止隐藏失败）`() {
        try {
            SessionSpec(preferredDomain = RuntimeDomain.PROOT_USERLAND, requestedFeatures = setOf(Feature.EXEC, Feature.VNC))
            fail("PRoot 域 VNC 特性请求应被拒绝")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("E_FEATURE_UNSUPPORTED"))
        }
    }

    @Test
    fun `QEMU 域缺 imageId 必须被拒`() {
        try {
            SessionSpec(preferredDomain = RuntimeDomain.QEMU_VM, requestedFeatures = setOf(Feature.EXEC))
            fail("QEMU 会话必须声明 imageId")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `QEMU 域合法规格`() {
        val spec = SessionSpec(
            preferredDomain = RuntimeDomain.QEMU_VM,
            imageId = "alpine-vm",
            requestedFeatures = setOf(Feature.EXEC, Feature.FILE_IO, Feature.VNC),
        )
        assertEquals(RuntimeDomain.QEMU_VM, spec.preferredDomain)
    }

    @Test
    fun `requestedFeatures 缺 EXEC 必须被拒`() {
        try {
            SessionSpec(requestedFeatures = setOf(Feature.FILE_IO))
            fail("公共子集 EXEC 必须包含")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `mount 目标穿越与绝对路径必须被拒`() {
        listOf(
            "/abs/path",
            "../escape",
            "ok/../../escape",
        ).forEach { bad ->
            try {
                MountSpec(source = MountSource.CACHE_RO, targetRelative = bad)
                fail("非法挂载目标应被拒绝 → $bad")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `合法挂载目标通过`() {
        val m = MountSpec(source = MountSource.CACHE_RO, targetRelative = "cache/maven", readOnly = true)
        assertEquals("cache/maven", m.targetRelative)
        assertTrue(m.readOnly)
    }

    @Test
    fun `限额默认即事实源上限 - 越权提高必须被拒（FR-007）`() {
        try {
            ResourceLimits(maxMemoryMb = 4096)
            fail("越权提高内存限额必须被拒绝")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("FR-007"))
        }
        try {
            ResourceLimits(execTimeoutSec = 3600)
            fail("越权提高超时必须被拒绝")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            ResourceLimits(maxConcurrentExec = 2)
            fail("并发 exec 恒为 1")
        } catch (expected: IllegalArgumentException) {
        }
        // 收紧合法
        ResourceLimits(maxMemoryMb = 1024, execTimeoutSec = 600)
    }

    @Test
    fun `ExecCommand 只接受 argv 数组`() {
        val cmd = ExecCommand(argv = listOf("apk", "--version"), stage = "resolve")
        assertEquals(listOf("apk", "--version"), cmd.argv)
        assertEquals("resolve", cmd.stage)
    }

    @Test
    fun `ExecCommand 空 argv 与空元素必须被拒`() {
        try {
            ExecCommand(argv = emptyList())
            fail("空 argv 必须被拒绝（禁字符串命令）")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            ExecCommand(argv = listOf("apk", " "))
            fail("空白 argv 元素必须被拒绝")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `ExecCommand 越权 timeoutSec 与穿越 cwd 必须被拒`() {
        try {
            ExecCommand(argv = listOf("ls"), timeoutSec = 9999)
            fail("越权超时必须被拒绝")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            ExecCommand(argv = listOf("ls"), cwd = "../up")
            fail("cwd 穿越必须被拒绝")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `ExecResult exitCode 零才算成功`() {
        assertTrue(ExecResult(exitCode = 0).succeeded)
        assertEquals(false, ExecResult(exitCode = 1).succeeded)
    }
}
