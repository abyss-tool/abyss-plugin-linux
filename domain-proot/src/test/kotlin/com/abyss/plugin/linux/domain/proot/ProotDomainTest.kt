package com.abyss.plugin.linux.domain.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** PRoot 域启动编排（LIN-001 §2.2 / argv 数组纪律）。 */
class ProotDomainTest {

    @Test
    fun `启动 argv 组装（无 shell 拼接）`() {
        val argv = ProotDomain.launchArgv(
            rootfsPath = "/data/user/0/com.abyss.plugin.linux/files/runtime/domains/alpine-rootfs",
            cwd = "/workspace/build-1000",
            binds = listOf(ProotDomain.Bind("/data/cache", "/cache")),
            command = listOf("apk", "--version"),
        )
        assertEquals("proot", argv.first())
        assertTrue(argv.contains("-R"))
        assertTrue(argv.contains("-0"))
        assertTrue(argv.contains("--"))
        assertEquals("apk", argv[argv.size - 2])
        assertEquals("--version", argv.last())
        // 无 shell 元字符（argv 数组纪律）
        assertTrue(argv.none { it.contains("; ") || it == "|&" })
    }

    @Test
    fun `绑定挂载形态与穿越拒绝`() {
        ProotDomain.Bind(hostPath = "/cache", guestPath = "/cache", readOnly = true)
        try {
            ProotDomain.Bind(hostPath = "/cache", guestPath = "/../escape")
            fail("guest 路径穿越必须被拒绝")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `空参数 fail-closed`() {
        try {
            ProotDomain.launchArgv(rootfsPath = "", cwd = "/w", command = listOf("ls"))
            fail("rootfsPath 为空必须被拒绝")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            ProotDomain.launchArgv(rootfsPath = "/r", cwd = "/w", command = emptyList())
            fail("空命令 argv 必须被拒绝")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `会话环境透传白名单与非法键拒绝`() {
        val env = ProotDomain.sessionEnv(mapOf("GRADLE_OPTS" to "-Xmx2048m"))
        assertEquals("/root", env["HOME"])
        assertEquals("-Xmx2048m", env["GRADLE_OPTS"])
        try {
            ProotDomain.sessionEnv(mapOf("BAD KEY" to "x"))
            fail("env 键含空格必须被拒绝")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            ProotDomain.sessionEnv(mapOf("A=B" to "x"))
            fail("env 键含 '=' 必须被拒绝")
        } catch (expected: IllegalArgumentException) {
        }
    }
}
