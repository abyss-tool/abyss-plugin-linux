package com.abyss.plugin.linux.runtime

import android.content.pm.PackageManager
import android.os.Build
import android.os.Binder
import com.abyss.plugin.linux.runtime.api.HostCallerTrust

/**
 * Android 侧调用方信任护栏（ARC-104 §6 G-1）。
 *
 * `Binder.getCallingUid()` 在 **AIDL 方法体内**才是调用方真实 uid（onBind 由
 * system_server 转发调用，那里查到的是系统 uid）—— 所以校验点放在
 * [LinuxRuntimeBinder] 的每个 AIDL 入口，不在 onBind 里做 uid 判断（onBind
 * 保留 action 校验 + 常量级防护）。
 *
 * 签名读取（NG-1 / NG-2，深挖第三轮修复）：
 *  - **API 分支先行**：`PackageInfo.signingInfo` 字段 API 28 才存在 —— 在 26/27
 *    上读该字段抛 NoSuchFieldError，旧实现被 runCatching 吞成空签名集 → 合法
 *    宿主在 Android 8.x 上被全量拒绝（fail-closed 但把真宿主也关在门外）。
 *    现在先判 `Build.VERSION.SDK_INT >= 28` 再触碰新字段，26/27 直接走
 *    `signatures` 旧字段（两代字段都在 `GET_SIGNING_CERTIFICATES or
 *    GET_SIGNATURES` 双标志下可用）。
 *  - **只认当前签名者**（apkContentsSigners / signatures）：签名历史
 *    （signingCertificateHistory / 轮换 lineage）不参与可信判定 —— pin 必须
 *    命中当前发布者证书；宿主未来轮换签名时主仓 `PublisherSignatures` 与本仓
 *    `HostCallerTrust.HOST_PUBLISHER_SHA256` 同步换 pin。多签名者场景下
 *    AOSP 的 history 本就为 null，旧代码把 `hasMultipleSigners()` 当读取条件
 *    属语义错位（正确条件是 hasPastSigningCertificates，且本策略不采信历史）。
 *
 * 构造注入（NG-3）：主构造收「uid→包集合 / 包→签名摘要集」两个查询函数
 * （纯 JVM 单测注入桩 —— 摘要已算好，判定链全部真实执行）；Android 侧经
 * [PackageManager] 便捷构造接线（摘要在 [currentSignerBytes] 侧换算）。
 */
class HostCallerGuard(
    private val packagesForUid: (Int) -> List<String>?,
    private val signerDigestsOf: (String) -> List<String>,
) {

    /** Android 接线（[LinuxRuntimeService] / 生产路径使用）。 */
    constructor(packageManager: PackageManager) : this(
        packagesForUid = { uid ->
            runCatching { packageManager.getPackagesForUid(uid)?.toList() }.getOrNull()
        },
        signerDigestsOf = { pkg ->
            currentSignerBytes(packageManager, pkg).map { HostCallerTrust.sha256Hex(it) }
        },
    )

    /** 当前 AIDL 调用方可信：uid → 包集合恰为宿主 + 签名命中发布者指纹。 */
    fun currentCallerTrusted(): Boolean =
        callerTrusted(Binder.getCallingUid())

    /** uid → 签名反查（可单测注入 uid；探测失败一律不可信，绝不猜）。 */
    fun callerTrusted(uid: Int): Boolean {
        val packages = packagesForUid(uid) ?: return false
        val digests = packages.flatMap { signerDigestsOf(it) }
        return HostCallerTrust.isTrustedHost(packages, digests)
    }

    /** 可信放行；不可信抛携带错误码的 [SecurityException]（与 openSession 同通道）。 */
    fun requireTrustedCaller() {
        if (!currentCallerTrusted()) {
            throw SecurityException(
                com.abyss.plugin.linux.runtime.api.Diagnostic.of(
                    code = "E_DIRECT_BIND_DENIED",
                    action = "仅深渊工具主壳（同签名）可调用 Linux 运行时 —— 已拒绝并记审计",
                ).machineHint,
            )
        }
    }

    companion object {
        /**
         * 当前签名者字节集（NG-1/NG-2）：API ≥ 28 优先 `SigningInfo.apkContentsSigners`
         * （为空回退旧字段）；API 26/27 只有 `signatures` 旧字段 —— signingInfo
         * 字段访问必须留在 SDK_INT 分支内（26/27 上不存在，直接 getfield 抛
         * NoSuchFieldError）。
         */
        @Suppress("DEPRECATION") // GET_SIGNATURES 全版本可用；signingInfo 字段 API 28+
        fun currentSignerBytes(packageManager: PackageManager, pkg: String): List<ByteArray> =
            runCatching {
                val info = packageManager.getPackageInfo(
                    pkg,
                    // 双标志并取：新版本读 SigningInfo，API 26/27 只有 signatures 字段可用
                    PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES,
                )
                val current: List<ByteArray>? =
                    if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.map { it.toByteArray() } else null
                if (!current.isNullOrEmpty()) current else info.signatures?.map { it.toByteArray() }.orEmpty()
            }.getOrDefault(emptyList())
    }
}
