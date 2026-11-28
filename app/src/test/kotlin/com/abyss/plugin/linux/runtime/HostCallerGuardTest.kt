package com.abyss.plugin.linux.runtime

import com.abyss.plugin.linux.runtime.api.HostCallerTrust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HostCallerGuard 判定链（ARC-104 §6 G-1 / NG-3，深挖第三轮）。
 *
 * 主构造注入「uid→包集合 / 包→签名摘要集」两个查询函数 —— uid 反查 → 摘要
 * 汇集 → [HostCallerTrust.isTrustedHost] 的判定链**全部真实执行**（桩只替换
 * Android 查询，不短路判定）。Binder / SigningInfo 的 SDK 分支属 Android 面
 * （lint + 真机验收覆盖）；signingInfo 字段访问已钉在
 * `Build.VERSION.SDK_INT >= 28` 分支内（NG-1：26/27 上 getfield 会抛
 * NoSuchFieldError，被 runCatching 吞成空签名集 → 合法宿主被全量拒绝）。
 */
class HostCallerGuardTest {

    private val pin: String = HostCallerTrust.HOST_PUBLISHER_SHA256
    private val otherDigest: String = "0".repeat(63) + "1"

    private fun guard(
        packages: Map<Int, List<String>?>,
        digests: Map<String, List<String>>,
    ) = HostCallerGuard(
        packagesForUid = { uid -> packages[uid] },
        signerDigestsOf = { pkg -> digests[pkg] ?: emptyList() },
    )

    @Test
    fun `uid 反查 - 包集合恰为宿主且签名命中 pin - 可信`() {
        val guard = guard(
            packages = mapOf(10_000 to listOf(HostCallerTrust.HOST_PACKAGE)),
            digests = mapOf(HostCallerTrust.HOST_PACKAGE to listOf(pin)),
        )
        assertTrue(guard.callerTrusted(10_000))
    }

    @Test
    fun `uid 反查 - 第三方包 - 不可信`() {
        val guard = guard(
            packages = mapOf(10_001 to listOf("com.evil.thirdparty")),
            digests = mapOf("com.evil.thirdparty" to listOf(pin)),
        )
        // 即使第三方也拿着宿主形态的摘要（现实中不可能，防实现错位），包集合不匹配即不可信
        assertFalse(guard.callerTrusted(10_001))
    }

    @Test
    fun `uid 反查 - 共享 uid 多包 - 不可信（fail-closed）`() {
        val guard = guard(
            packages = mapOf(10_002 to listOf(HostCallerTrust.HOST_PACKAGE, "com.evil.shared")),
            digests = mapOf(HostCallerTrust.HOST_PACKAGE to listOf(pin)),
        )
        assertFalse(guard.callerTrusted(10_002))
    }

    @Test
    fun `uid 反查 - 宿主包但签名不命中 - 不可信`() {
        val guard = guard(
            packages = mapOf(10_003 to listOf(HostCallerTrust.HOST_PACKAGE)),
            digests = mapOf(HostCallerTrust.HOST_PACKAGE to listOf(otherDigest)),
        )
        assertFalse(guard.callerTrusted(10_003))
    }

    @Test
    fun `uid 反查 - 多签名者任一命中即可信（当前签名者集合语义）`() {
        val guard = guard(
            packages = mapOf(10_004 to listOf(HostCallerTrust.HOST_PACKAGE)),
            digests = mapOf(HostCallerTrust.HOST_PACKAGE to listOf(otherDigest, pin)),
        )
        assertTrue(guard.callerTrusted(10_004))
    }

    @Test
    fun `uid 反查 - 空签名集 / 查询抛异常 / 未知 uid - 一律不可信（不猜）`() {
        val emptyDigests = guard(
            packages = mapOf(10_005 to listOf(HostCallerTrust.HOST_PACKAGE)),
            digests = emptyMap(),
        )
        assertFalse(emptyDigests.callerTrusted(10_005))

        val lookupThrows = HostCallerGuard(
            packagesForUid = { throw SecurityException("provider died") },
            signerDigestsOf = { listOf(pin) },
        )
        assertFalse(lookupThrows.callerTrusted(10_006))

        val unknownUid = guard(
            packages = mapOf(10_000 to listOf(HostCallerTrust.HOST_PACKAGE)),
            digests = mapOf(HostCallerTrust.HOST_PACKAGE to listOf(pin)),
        )
        // 未知 uid：packagesForUid 返回 null → 不可信
        assertFalse(unknownUid.callerTrusted(99_999))
    }

    @Test
    fun `sha256Hex 输出恒为 64 位大写 hex（跨进程摘要换算锚）`() {
        val digest = HostCallerTrust.sha256Hex(HostCallerTrust.HOST_PACKAGE.encodeToByteArray())
        assertEquals(64, digest.length)
        assertTrue(digest.all { it in '0'..'9' || it in 'A'..'F' })
        // 幂等：同输入同输出；且与归一化互逆
        assertEquals(digest, HostCallerTrust.sha256Hex(HostCallerTrust.HOST_PACKAGE.encodeToByteArray()))
        assertEquals(digest, HostCallerTrust.normalizeDigest(digest.lowercase()))
    }
}
