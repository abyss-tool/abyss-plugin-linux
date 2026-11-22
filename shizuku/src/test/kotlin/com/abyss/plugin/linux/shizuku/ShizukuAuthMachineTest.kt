package com.abyss.plugin.linux.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 授权状态机（LIN-005 §4：5 状态 + 转移纪律）。 */
class ShizukuAuthMachineTest {

    @Test
    fun `安装→运行→授权→撤销→再授权 全链`() {
        var s = ShizukuGateState.NOT_INSTALLED
        s = ShizukuAuthMachine.transition(s, ShizukuAuthMachine.Event.DETECT_RUNNING)
        assertEquals(ShizukuGateState.NOT_GRANTED, s)
        s = ShizukuAuthMachine.transition(s, ShizukuAuthMachine.Event.GRANT)
        assertEquals(ShizukuGateState.GRANTED, s)
        s = ShizukuAuthMachine.transition(s, ShizukuAuthMachine.Event.REVOKE)
        assertEquals(ShizukuGateState.REVOKED, s)
        s = ShizukuAuthMachine.transition(s, ShizukuAuthMachine.Event.GRANT)
        assertEquals(ShizukuGateState.GRANTED, s)
    }

    @Test
    fun `Shizuku 重启 - STALE 后重检`() {
        var s = ShizukuAuthMachine.transition(ShizukuGateState.GRANTED, ShizukuAuthMachine.Event.SHELL_RESTART)
        assertEquals(ShizukuGateState.STALE, s)
        s = ShizukuAuthMachine.transition(s, ShizukuAuthMachine.Event.RECHECK)
        assertEquals(ShizukuGateState.NOT_GRANTED, s)
    }

    @Test
    fun `非法迁移 fail-closed`() {
        try {
            ShizukuAuthMachine.transition(ShizukuGateState.NOT_INSTALLED, ShizukuAuthMachine.Event.GRANT)
            fail("未安装态直接授权必须被拒绝")
        } catch (expected: IllegalStateException) {
        }
    }

    @Test
    fun `展示语义人话`() {
        assertTrue(ShizukuAuthMachine.guidance(ShizukuGateState.NOT_GRANTED).contains("单独授权一次"))
    }
}

/** 指令编排与注入防护（LIN-005 §3 / SEC-100 AC-003：非法 token 全拒）。 */
class LinuxCommandsTest {

    @Test
    fun `免引用参数白名单`() {
        assertEquals("alpine-rootfs", LinuxCommands.requireSafeToken("alpine-rootfs"))
        assertEquals("aapt2.arm64.v34", LinuxCommands.requireSafeToken("aapt2.arm64.v34"))
        assertEquals("path:ok-1.0", LinuxCommands.requireSafeToken("path:ok-1.0"))
        // 斜杠不属于免引用 token 白名单（路径形态走 requireSafeRelativePath）
        try {
            LinuxCommands.requireSafeToken("a/b")
            fail("斜杠 token 应就地拒绝")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `会话内相对路径校验（argv 元素形态）`() {
        assertEquals("toolchains/aapt2", LinuxCommands.requireSafeRelativePath("toolchains/aapt2"))
        assertEquals("a/b/c-1.0.0", LinuxCommands.requireSafeRelativePath("a/b/c-1.0.0"))
        listOf("../escape", "/abs/path", "", "back\\slash", "a b", "a;b", "a|b", "a&b", "a\$b", "a`b`").forEach { bad ->
            try {
                LinuxCommands.requireSafeRelativePath(bad)
                fail("非法相对路径应就地拒绝 → ${bad.replace("\n", "⏎")}")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `非法 token 就地拒绝（零 shell 执行）`() {
        listOf(
            "a;b", "a|b", "a&b", "a\$b", "a`b`", "a b", "", "a\nb",
        ).forEach { bad ->
            try {
                LinuxCommands.requireSafeToken(bad)
                fail("非法 token 必须就地拒绝 → ${bad.replace("\n", "⏎")}")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `自由文本单引号转义`() {
        assertEquals("''", LinuxCommands.shellQuote(""))
        assertEquals("'plain text'", LinuxCommands.shellQuote("plain text"))
        assertEquals("'it'\\''s'", LinuxCommands.shellQuote("it's"))
    }

    @Test
    fun `探测指令组为 argv 数组形态`() {
        LinuxCommands.PROBE_ARGV.forEach { argv ->
            assertTrue("探测指令必须是 argv 数组（非 shell 字符串）", argv.isNotEmpty())
        }
        assertEquals(listOf("getenforce"), LinuxCommands.PROBE_ARGV.first())
        assertEquals(listOf("grep", "TracerPid", "/proc/self/status"), LinuxCommands.PROBE_ARGV[1])
    }

    @Test
    fun `冒烟与 ldd 模板（E2E-01 与 C-1 门禁）`() {
        assertEquals(listOf(listOf("echo", "ok"), listOf("apk", "--version")), LinuxCommands.smokeTestArgv())
        assertEquals(
            listOf("ldd", "toolchains/aapt2"),
            LinuxCommands.toolchainLddCheckArgv("toolchains/aapt2"),
        )
        try {
            LinuxCommands.toolchainLddCheckArgv("bad;path")
            fail("ldd 路径含非法字符必须就地拒绝")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            LinuxCommands.toolchainLddCheckArgv("../escape")
            fail("ldd 路径穿越必须就地拒绝")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `看门狗常量 30s`() {
        assertEquals(30, LinuxCommands.WATCHDOG_TIMEOUT_SECONDS)
    }
}
