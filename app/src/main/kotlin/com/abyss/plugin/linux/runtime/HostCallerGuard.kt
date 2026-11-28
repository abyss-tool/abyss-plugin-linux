package com.abyss.plugin.linux.runtime

import android.content.pm.PackageManager
import android.content.pm.Signature
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
 * 签名读取：minSdk 26 起统一走 [PackageManager.GET_SIGNING_CERTIFICATES]
 * （API 28+）并保留 [PackageManager.GET_SIGNATURES] 兜底（API 26/27 的
 * PackageManager 仍只有旧字段）—— 双形态任取其一，非空即用。
 */
class HostCallerGuard(
    private val packageManager: PackageManager,
) {

    /** 当前 AIDL 调用方可信：uid → 包集合恰为宿主 + 签名命中发布者指纹。 */
    fun currentCallerTrusted(): Boolean =
        callerTrusted(Binder.getCallingUid())

    /** uid → 签名反查（可单测注入 uid；探测失败一律不可信，绝不猜）。 */
    fun callerTrusted(uid: Int): Boolean = runCatching {
        val packages = packageManager.getPackagesForUid(uid)?.toList() ?: return false
        val digests = packages.flatMap { pkg -> signaturesOf(pkg) }
            .map { HostCallerTrust.sha256Hex(it.toByteArray()) }
        HostCallerTrust.isTrustedHost(packages, digests)
    }.getOrDefault(false)

    /** 可信放行；不可信抛 AIDL 会传播的 [IllegalStateException]（与 openSession 同通道）。 */
    fun requireTrustedCaller() {
        if (!currentCallerTrusted()) {
            throw IllegalStateException(
                com.abyss.plugin.linux.runtime.api.Diagnostic.of(
                    code = "E_DIRECT_BIND_DENIED",
                    action = "仅深渊工具主壳（同签名）可调用 Linux 运行时 —— 已拒绝并记审计",
                ).humanHint,
            )
        }
    }

    @Suppress("DEPRECATION") // GET_SIGNATURES 全版本可用；SigningInfo 形态 API 28+ 才存在
    private fun signaturesOf(pkg: String): List<Signature> = runCatching {
        val info = packageManager.getPackageInfo(
                pkg,
                // 双标志并取：新版本读 SigningInfo，API 26/27 只有 signatures 字段可用
                PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES,
            )
        val signing = info.signingInfo
        if (signing != null) {
            buildList {
                signing.apkContentsSigners?.let { addAll(it) }
                if (signing.hasMultipleSigners()) {
                    signing.signingCertificateHistory?.let { addAll(it) }
                }
            }
        } else {
            // API 26/27：getPackageInfo(pkg, GET_SIGNING_CERTIFICATES) 等价回落到 signatures 字段
            info.signatures?.toList() ?: emptyList()
        }
    }.getOrDefault(emptyList())
}
