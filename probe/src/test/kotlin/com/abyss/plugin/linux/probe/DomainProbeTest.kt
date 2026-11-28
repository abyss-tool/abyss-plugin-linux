package com.abyss.plugin.linux.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 域探测降级决策（LIN-004 §5.4 / LIN-005 —— 承接 m1-scaffold 决策表）。 */
class DomainProbeTest {

    private fun env(
        shizukuRunning: Boolean = false,
        shizukuAuthorized: Boolean = false,
        probe: DomainProbe.ProbeOutcome = DomainProbe.ProbeOutcome.NOT_RUN,
    ) = DomainProbe.Environment(shizukuRunning, shizukuAuthorized, probe)

    @Test
    fun `推荐档 - Shizuku 已授权`() {
        val d = DomainProbe.resolve(env(true, true, DomainProbe.ProbeOutcome.OK))
        assertEquals(DomainProbe.Level.READY, d.level)
        assertEquals("uid2000", d.via)
        assertTrue(d.humanMessage.contains("uid 2000"))
    }

    @Test
    fun `沙箱降级档 - Shizuku 未运行`() {
        val d = DomainProbe.resolve(env(false, false, DomainProbe.ProbeOutcome.OK))
        assertEquals(DomainProbe.Level.DEGRADED_SANDBOX, d.level)
        assertEquals("app_sandbox", d.via)
        assertTrue(d.humanMessage.contains("ColorOS"))
    }

    @Test
    fun `需授权档 - 运行中未授权`() {
        val d = DomainProbe.resolve(env(true, false, DomainProbe.ProbeOutcome.OK))
        assertEquals(DomainProbe.Level.NEEDS_AUTH, d.level)
        assertEquals("NEED_SHIZUKU", d.reason)
        assertTrue(d.humanMessage.contains("单独授权一次"))
    }

    @Test
    fun `不可用档 - 探测被拒或超时`() {
        listOf(DomainProbe.ProbeOutcome.DENIED, DomainProbe.ProbeOutcome.TIMEOUT).forEach { outcome ->
            val d = DomainProbe.resolve(env(true, true, outcome))
            assertEquals(DomainProbe.Level.UNAVAILABLE, d.level)
            assertTrue(d.reason.contains("E_PROBE_PTRACE_BLOCKED"))
        }
    }

    @Test
    fun `探测超时 5s 常量`() {
        assertEquals(5, DomainProbe.PROBE_TIMEOUT_SECONDS)
    }

    @Test
    fun `已知受限 ROM 清单匹配`() {
        val coloros = DomainProbe.restrictedRomOf("OnePlus")
        assertNotNull(coloros)
        assertEquals("ColorOS 15/16", coloros!!.romName)
        assertEquals("荣耀 MagicOS", DomainProbe.restrictedRomOf("HONOR")!!.romName)
        assertNull(DomainProbe.restrictedRomOf("Google"))
        assertNull(DomainProbe.restrictedRomOf(""))
        // 人话清单（UNAVAILABLE 档 romHint）
        assertTrue(DomainProbe.restrictedRomSummary("OPPO")!!.contains("ColorOS"))
    }

    @Test
    fun `探测输出解析 - 正常环境`() {
        val output = "--- getenforce\nPermissive\n--- TracerPid:\n0"
        assertEquals(DomainProbe.ProbeOutcome.OK, DomainProbe.parseOutcome(output, "Google"))
    }

    @Test
    fun `探测输出解析 - 已知清单厂商以输出为准（NG-6 前缀先验否决已移除）`() {
        // ColorOS < 15 的 OnePlus / 未受限 Realme：探测输出干净 → OK
        // （旧实现按 manufacturer 前缀整套 DENIED，把可用设备误杀）
        val clean = "--- getenforce\nPermissive\n--- TracerPid:\n0"
        assertEquals(DomainProbe.ProbeOutcome.OK, DomainProbe.parseOutcome(clean, "OnePlus"))
        assertEquals(DomainProbe.ProbeOutcome.OK, DomainProbe.parseOutcome(clean, "Realme"))
        // 真正受限设备由探测输出如实上报（TracerPid 异常 / 空 / 缺失）
        assertEquals(DomainProbe.ProbeOutcome.DENIED, DomainProbe.parseOutcome("--- TracerPid:\n1234", "OnePlus"))
        // 清单仍作 romHint / 预警用途（厂商匹配不变）
        assertEquals("ColorOS 15/16", DomainProbe.restrictedRomOf("OnePlus")!!.romName)
    }

    @Test
    fun `探测输出解析 fail-closed`() {
        // 空输出 → DENIED
        assertEquals(DomainProbe.ProbeOutcome.DENIED, DomainProbe.parseOutcome("", "Google"))
        // TracerPid 非零（已有 tracer = 异常环境）
        assertEquals(
            DomainProbe.ProbeOutcome.DENIED,
            DomainProbe.parseOutcome("--- TracerPid:\n1234", "Google"),
        )
    }

    @Test
    fun `探测输出缺失关键节 - DENIED 不猜测`() {
        assertEquals(
            DomainProbe.ProbeOutcome.DENIED,
            DomainProbe.parseOutcome("--- getenforce\nPermissive", "Google"),
        )
    }
}
