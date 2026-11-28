package com.abyss.plugin.linux.runtime.api

/**
 * 宿主调用方信任锚（ARC-104 §6 G-1 —— `exported=true` 服务的服务端硬门禁，纯 JVM 可单测）。
 *
 * 为什么必须做：`LinuxRuntimeService` 为跨 APK 绑定而 exported，没有 `android:permission`
 * 护栏时任意 App 都能 `bindService` 并直呼 AIDL 方法。宿主侧 `PluginBroker` 的四重校验
 * 只管「经宿主中介」的合法路径，拦不住第三方绕开宿主直连 —— 所以服务端每个 AIDL 入口
 * 都要以 `Binder.getCallingUid()` 反查调用方签名，非宿主签名一律 `E_DIRECT_BIND_DENIED`。
 *
 * 为什么不用 Manifest `android:permission` 指向宿主声明的 signature 级权限：自定义权限名
 * 不保证归属（permission squatting），先装者定义生效；进程内签名校验不依赖安装顺序，
 * 更稳。两层可叠加，本轮先落进程内这一层。
 *
 * 纪律：
 *  - 调用方 uid 映射的包集合必须**恰为**宿主包名（共享 uid 的其它包一律不信）；
 *  - 签名证书 SHA-256 必须命中宿主发布者指纹（[HOST_PUBLISHER_SHA256]，64 位 hex）；
 *  - 任一条件不满足即不可信 —— 不读 Intent extras、不接受自报包名。
 */
object HostCallerTrust {

    /** 宿主包名（深渊工具主壳）。 */
    const val HOST_PACKAGE: String = "com.abyss.tools"

    /** 宿主发布者签名指纹 —— 与主仓 `PublisherSignatures.PINNED_SHA256` 同一常量（64 位 hex）。 */
    const val HOST_PUBLISHER_SHA256: String =
        "760378214488D6207978BA1E37E6DC82ABC2DE72C9F6C67D9B4495158A99B769"

    private val SHA256_HEX = Regex("^[0-9A-F]{64}$")

    /** 冒号 / 空白分隔的指纹归一为 64 位大写 hex；非法形态返回 null（fail-closed）。 */
    fun normalizeDigest(raw: String?): String? {
        val compact = raw?.replace(":", "")?.replace(" ", "")?.trim()?.uppercase() ?: return null
        return if (SHA256_HEX.matches(compact)) compact else null
    }

    /** 证书字节 → SHA-256 大写 hex（Android 侧 `Signature.toByteArray()` 直接喂入）。 */
    fun sha256Hex(cert: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(cert)
            .joinToString("") { "%02X".format(it) }

    /**
     * 调用方可信判定：包集合 == {宿主} 且任一签名摘要命中宿主指纹。
     * [signerDigests] 允许传归一化前形态（内部归一化）；空集 / null 一律不可信。
     */
    fun isTrustedHost(packagesForUid: Collection<String>?, signerDigests: Collection<String?>): Boolean {
        if (packagesForUid == null || packagesForUid.size != 1) return false
        if (packagesForUid.first() != HOST_PACKAGE) return false
        return signerDigests.any { normalizeDigest(it) == HOST_PUBLISHER_SHA256 }
    }
}
