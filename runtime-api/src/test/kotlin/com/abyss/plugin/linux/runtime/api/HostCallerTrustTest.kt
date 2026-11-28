package com.abyss.plugin.linux.runtime.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ARC-104 §6 G-1 —— 服务端调用方信任锚契约测试。
 *
 * 覆盖：包集合恰为宿主 + 签名命中 → 可信；共享 uid / 第三方包 / 冒号形态 /
 * 空集合 / 指纹不符 → 一律不可信；证书摘要与归一化互逆。
 */
class HostCallerTrustTest {

    private val hostDigest: String = HostCallerTrust.HOST_PUBLISHER_SHA256

    @Test
    fun `宿主包加发布者指纹 - 可信`() {
        assertTrue(
            HostCallerTrust.isTrustedHost(
                packagesForUid = listOf(HostCallerTrust.HOST_PACKAGE),
                signerDigests = listOf(hostDigest),
            ),
        )
    }

    @Test
    fun `冒号与大小写形态等价归一 - 可信`() {
        val colons = hostDigest.chunked(2).joinToString(":")
        assertTrue(
            HostCallerTrust.isTrustedHost(
                packagesForUid = listOf(HostCallerTrust.HOST_PACKAGE),
                signerDigests = listOf(colons.lowercase()),
            ),
        )
        assertEquals(hostDigest, HostCallerTrust.normalizeDigest(colons))
        assertEquals(hostDigest, HostCallerTrust.normalizeDigest(hostDigest.lowercase()))
    }

    @Test
    fun `共享 uid 的多包 - 不可信`() {
        assertFalse(
            HostCallerTrust.isTrustedHost(
                packagesForUid = listOf(HostCallerTrust.HOST_PACKAGE, "com.evil.shared"),
                signerDigests = listOf(hostDigest),
            ),
        )
    }

    @Test
    fun `第三方包直连 - 不可信（即使持有指纹串）`() {
        assertFalse(
            HostCallerTrust.isTrustedHost(
                packagesForUid = listOf("com.evil.thirdparty"),
                signerDigests = listOf(hostDigest),
            ),
        )
    }

    @Test
    fun `包名对但指纹不符 - 不可信`() {
        assertFalse(
            HostCallerTrust.isTrustedHost(
                packagesForUid = listOf(HostCallerTrust.HOST_PACKAGE),
                signerDigests = listOf("AA" + "BB".repeat(31)),
            ),
        )
    }

    @Test
    fun `空集合与非法形态 - 一律不可信`() {
        assertFalse(HostCallerTrust.isTrustedHost(emptyList(), listOf(hostDigest)))
        assertFalse(HostCallerTrust.isTrustedHost(null, listOf(hostDigest)))
        assertFalse(HostCallerTrust.isTrustedHost(listOf(HostCallerTrust.HOST_PACKAGE), emptyList()))
        assertFalse(HostCallerTrust.isTrustedHost(listOf(HostCallerTrust.HOST_PACKAGE), listOf(null)))
        assertFalse(HostCallerTrust.isTrustedHost(listOf(HostCallerTrust.HOST_PACKAGE), listOf("zz")))
        assertNull(HostCallerTrust.normalizeDigest(null))
        assertNull(HostCallerTrust.normalizeDigest("not-a-sha"))
    }

    @Test
    fun `证书字节与十六进制摘要互逆`() {
        val digest = HostCallerTrust.sha256Hex(ByteArray(64) { it.toByte() })
        assertTrue(Regex("^[0-9A-F]{64}$").matches(digest))
        // 归一化幂等：hex 已是大写无分隔 → 原样返回
        assertEquals(digest, HostCallerTrust.normalizeDigest(digest))
    }
}
