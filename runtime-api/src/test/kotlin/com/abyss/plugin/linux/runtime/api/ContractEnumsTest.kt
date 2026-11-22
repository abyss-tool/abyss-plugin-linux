package com.abyss.plugin.linux.runtime.api

import com.abyss.plugin.linux.runtime.api.Feature.EXEC
import com.abyss.plugin.linux.runtime.api.Feature.FILE_IO
import com.abyss.plugin.linux.runtime.api.Feature.PTY
import com.abyss.plugin.linux.runtime.api.Feature.SNAPSHOT
import com.abyss.plugin.linux.runtime.api.Feature.VNC
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 契约枚举与标识（SRS-101 AC-003：代码常量与枚举逐项一致）。 */
class ContractEnumsTest {

    @Test
    fun `双执行域枚举完整`() {
        assertEquals(2, RuntimeDomain.entries.size)
        assertEquals("PROOT_USERLAND", RuntimeDomain.PROOT_USERLAND.id)
        assertEquals("QEMU_VM", RuntimeDomain.QEMU_VM.id)
        assertEquals("shared-android", RuntimeDomain.PROOT_USERLAND.kernel)
        assertEquals("guest-linux", RuntimeDomain.QEMU_VM.kernel)
    }

    @Test
    fun `fromId 反查大小写敏感且未知返回 null`() {
        assertEquals(RuntimeDomain.QEMU_VM, RuntimeDomain.fromId("QEMU_VM"))
        assertNull(RuntimeDomain.fromId("qemu_vm"))
        assertNull(RuntimeDomain.fromId(""))
    }

    @Test
    fun `会话状态机 10 状态`() {
        assertEquals(10, SessionState.entries.size)
        assertEquals(
            listOf(
                "UNINITIALIZED", "ASSET_CHECKING", "ASSET_DOWNLOADING", "PROBING",
                "DEGRADED", "READY", "BUSY", "UNAVAILABLE", "CLOSING", "CLOSED",
            ),
            SessionState.entries.map { it.id },
        )
    }

    @Test
    fun `能力 id 形态与主版本`() {
        assertEquals("linux-runtime-v1", CapabilityId.LINUX_RUNTIME_V1.value)
        assertEquals(1, CapabilityId.LINUX_RUNTIME_V1.major)
        try {
            CapabilityId("linux-runtime")
            fail("非法 capability id 应被拒绝")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `域能力矩阵 - PRoot 不支持 VNC 快照`() {
        assertTrue(VNC !in PROOT_SUPPORTED_FEATURES)
        assertTrue(SNAPSHOT !in PROOT_SUPPORTED_FEATURES)
        assertTrue(EXEC in PROOT_SUPPORTED_FEATURES)
        assertTrue(PTY in PROOT_SUPPORTED_FEATURES)
        assertTrue(FILE_IO in PROOT_SUPPORTED_FEATURES)
        assertTrue(VNC in QEMU_SUPPORTED_FEATURES)
        assertTrue(SNAPSHOT in QEMU_SUPPORTED_FEATURES)
    }

    @Test
    fun `Shizuku 授权展示状态完备（含 REVOKED 与 STALE）`() {
        assertEquals(6, ShizukuAuthState.entries.size)
        assertEquals("GRANTED", ShizukuAuthState.GRANTED.id)
        assertNotNull(ShizukuAuthState.fromId("NOT_GRANTED"))
        assertNull(ShizukuAuthState.fromId("granted"))
    }

    @Test
    fun `探测摘要原因码常量`() {
        assertEquals("PROOT_BLOCKED", ProbeSummary.PROOT_BLOCKED)
        assertEquals("app_sandbox", ProbeSummary.VIA_APP_SANDBOX)
        assertEquals("uid2000", ProbeSummary.VIA_UID2000)
        assertEquals("NEED_SHIZUKU", ProbeSummary.NEED_SHIZUKU)
        assertEquals("PLATFORM_BLOCKED", ProbeSummary.PLATFORM_BLOCKED)
    }
}

/** 统一错误码表（DC-102 AC-002/AC-004：表内检索 + 人话 + 可恢复标记）。 */
class AbyssErrorCodesTest {

    @Test
    fun `全表条目唯一且非空人话`() {
        val codes = AbyssErrorCodes.ALL.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        AbyssErrorCodes.ALL.forEach { e ->
            assertTrue("${e.code} hint 为空", e.hint.isNotBlank())
        }
    }

    @Test
    fun `DC-102 表内代表码全部注册`() {
        listOf(
            "E_DEP_MISSING", "E_DEP_VERSION", "E_DEPENDENCY_CYCLE", "E_CAPABILITY_MISMATCH",
            "E_HOST_API_TOO_LOW", "E_SIGNATURE_MISMATCH", "E_GRANT_INVALID", "E_GRANT_EXPIRED",
            "E_GRANT_REPLAY", "E_DIRECT_BIND_DENIED", "E_ASSET_INDEX_BAD", "E_ASSET_URL_DENIED",
            "E_ASSET_VOLUME_HASH", "E_ASSET_WHOLE_HASH", "E_ASSET_FORMAT", "E_STORAGE_INSUFFICIENT",
            "E_ASSET_MIN_PLUGIN", "E_PROBE_PTRACE_BLOCKED", "E_PROBE_TIMEOUT", "E_TOOLCHAIN_LIBC",
            "E_VM_RESOURCE", "E_VM_TCG_UNAVAILABLE", "E_SHIZUKU_NOT_RUNNING", "E_SHIZUKU_NOT_GRANTED",
            "E_SHIZUKU_REVOKED", "E_SESSION_CLOSED", "E_CONCURRENT_LIMIT", "E_EXEC_TIMEOUT",
            "E_EXEC_TRUNCATED", "E_PATH_TRAVERSAL", "E_FEATURE_UNSUPPORTED", "E_RUNTIME_UNAVAILABLE",
            "E_RUNTIME_DEAD", "E_ARTIFACT_EMPTY", "E_ARTIFACT_FORMAT", "E_ARTIFACT_ALIGN",
            "E_ARTIFACT_SIGN", "E_ARTIFACT_DIGEST", "E_ARTIFACT_META", "E_INSTALL_TIMEOUT",
            "E_INSTALL_REJECTED",
        ).forEach { code ->
            assertTrue("错误码未注册 → $code", AbyssErrorCodes.isRegistered(code))
        }
    }

    @Test
    fun `ARC-101 域级原语码已纳入注册表`() {
        assertTrue(AbyssErrorCodes.isRegistered("E_PTY_UNSUPPORTED"))
        assertTrue(AbyssErrorCodes.isRegistered("E_FILE_NOT_FOUND"))
        assertTrue(AbyssErrorCodes.isRegistered("E_MOUNT_DENIED"))
    }

    @Test
    fun `表外码 fail-closed 通用提示`() {
        assertFalse(AbyssErrorCodes.isRegistered("E_NOT_IN_TABLE"))
        assertTrue(AbyssErrorCodes.hintFor("E_NOT_IN_TABLE").contains("未知错误"))
        assertFalse(AbyssErrorCodes.isRecoverable("E_NOT_IN_TABLE"))
    }

    @Test
    fun `可恢复标记逐类抽检`() {
        // 可恢复 → 允许重试按钮
        assertTrue(AbyssErrorCodes.isRecoverable("E_DEP_MISSING"))
        assertTrue(AbyssErrorCodes.isRecoverable("E_GRANT_EXPIRED"))
        // 平台限制 / 信任类 → 禁无限重试
        assertFalse(AbyssErrorCodes.isRecoverable("E_PROBE_PTRACE_BLOCKED"))
        assertFalse(AbyssErrorCodes.isRecoverable("E_SIGNATURE_MISMATCH"))
        assertFalse(AbyssErrorCodes.isRecoverable("E_GRANT_REPLAY"))
    }

    @Test
    fun `requireRegistered 表外码抛出`() {
        try {
            AbyssErrorCodes.requireRegistered("E_FREE_TEXT")
            fail("自由文本错误码必须被拒绝（DC-102 FR-004）")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("DC-102"))
        }
    }
}
