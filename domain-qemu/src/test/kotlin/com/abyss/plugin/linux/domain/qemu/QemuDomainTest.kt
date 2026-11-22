package com.abyss.plugin.linux.domain.qemu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** QEMU 域档位 / VNC / 快照 / argv（LIN-002 §3.2 / AC-005）。 */
class QemuDomainTest {

    @Test
    fun `三档位常量（LOW 1×512、MID 2×1024、HIGH 4×2048）`() {
        assertEquals(1, QemuDomain.Tier.LOW.vcpu)
        assertEquals(512, QemuDomain.Tier.LOW.memoryMb)
        assertEquals(2, QemuDomain.Tier.MID.vcpu)
        assertEquals(1024, QemuDomain.Tier.MID.memoryMb)
        assertEquals(4, QemuDomain.Tier.HIGH.vcpu)
        assertEquals(2048, QemuDomain.Tier.HIGH.memoryMb)
    }

    @Test
    fun `60% 内存预算规则`() {
        // 8GB 可用 → 预算 4915MB：MID(1024) 过、HIGH(2048) 过
        assertNull(QemuDomain.validateTier(QemuDomain.Tier.MID, 8192))
        assertNull(QemuDomain.validateTier(QemuDomain.Tier.HIGH, 8192))
        // 2GB 可用 → 预算 1228MB：LOW(512) 过、MID(1024) 过、HIGH(2048) 拒
        assertNull(QemuDomain.validateTier(QemuDomain.Tier.LOW, 2048))
        assertNull(QemuDomain.validateTier(QemuDomain.Tier.MID, 2048))
        assertTrue(QemuDomain.validateTier(QemuDomain.Tier.HIGH, 2048)!!.contains("E_VM_RESOURCE"))
    }

    @Test
    fun `HIGH 档 4GB 空闲门槛`() {
        // 4000MB 可用：60% 预算 = 2400MB > 2048 过，但空闲 < 4096 → HIGH 拒
        assertTrue(QemuDomain.validateTier(QemuDomain.Tier.HIGH, 4000)!!.contains("HIGH"))
        assertNull(QemuDomain.validateTier(QemuDomain.Tier.HIGH, 4096))
    }

    @Test
    fun `VNC 仅回环绑定（AC-005 - 非回环被拒）`() {
        assertNull(QemuDomain.VncPolicy.checkBinding("127.0.0.1"))
        assertTrue(QemuDomain.VncPolicy.checkBinding("0.0.0.0")!!.contains("回环"))
        assertTrue(QemuDomain.VncPolicy.checkBinding("192.168.1.5")!!.contains("回环"))
    }

    @Test
    fun `快照元数据（含镜像 id、内存、时间、sha256）`() {
        QemuDomain.SnapshotMeta("alpine-vm", 1024, 1L, "a".repeat(64))
        try {
            QemuDomain.SnapshotMeta("", 1024, 1L, "a".repeat(64))
            fail("快照必须绑定镜像 id")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            QemuDomain.SnapshotMeta("alpine-vm", 1024, 1L, "short")
            fail("快照 sha256 必须为 64 位")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `启动 argv 组装（TCG + virt + 9p + slirp）`() {
        val argv = QemuDomain.launchArgv(
            imageId = "alpine-vm",
            imagePath = "/data/.../alpine-vm.qcow2",
            workspacePath = "/data/.../sessions/s1/workspace",
            tier = QemuDomain.Tier.MID,
        )
        assertEquals("qemu-system-aarch64", argv.first())
        assertTrue(argv.contains("tcg"))
        assertTrue(argv.contains("virt"))
        // 9p 工作区挂载进客机
        assertTrue(argv.any { it.startsWith("local,id=ws,path=") })
        // slirp 用户态 NAT
        assertTrue(argv.contains("user,id=net0"))
        // 默认串口（无 VNC display 参数）
        assertTrue(argv.contains("-nographic"))
    }

    @Test
    fun `VNC display 号参数与越界拒绝`() {
        val argv = QemuDomain.launchArgv(
            imageId = "alpine-vm",
            imagePath = "/x/a.qcow2",
            workspacePath = "/w",
            tier = QemuDomain.Tier.MID,
            vncDisplay = 3,
        )
        assertTrue(argv.contains("127.0.0.1:3"))
        try {
            QemuDomain.launchArgv("alpine-vm", "/x/a.qcow2", "/w", QemuDomain.Tier.MID, vncDisplay = 99)
            fail("VNC display 越界必须被拒绝")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `快照恢复参数（-loadvm）`() {
        val argv = QemuDomain.launchArgv(
            imageId = "alpine-vm",
            imagePath = "/x/a.qcow2",
            workspacePath = "/w",
            tier = QemuDomain.Tier.MID,
            snapshot = "pre-build",
        )
        assertEquals(listOf("-loadvm", "pre-build"), argv.takeLast(2))
    }

    @Test
    fun `客机内核探测命令（E2E-09）`() {
        assertEquals(listOf("uname", "-r"), QemuDomain.GUEST_KERNEL_CHECK_ARGV)
    }
}
