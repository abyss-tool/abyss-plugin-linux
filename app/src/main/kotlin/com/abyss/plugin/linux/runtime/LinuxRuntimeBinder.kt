package com.abyss.plugin.linux.runtime

import com.abyss.plugin.linux.runtime.api.CapabilityId
import com.abyss.plugin.linux.runtime.api.Diagnostic
import com.abyss.plugin.linux.runtime.api.DomainInfo
import com.abyss.plugin.linux.runtime.api.QEMU_SUPPORTED_FEATURES
import com.abyss.plugin.linux.runtime.api.PROOT_SUPPORTED_FEATURES
import com.abyss.plugin.linux.runtime.api.RuntimeInfo
import com.abyss.plugin.linux.runtime.api.RuntimeDomain
import com.abyss.plugin.linux.runtime.api.ShizukuAuthState

/**
 * ILinuxRuntime 服务端实现（M0 骨架 —— T-012 完整实现落于 M1）。
 *
 * M0 语义（诚实骨架，绝不假装 READY）：
 *  - `ping()` / `getRuntimeInfo()` 可应答 —— 运行时第一帧快照如实
 *    标注 `assetsReady=false`（骨架期无资产）；
 *  - `openSession()` 一律抛 `E_RUNTIME_UNAVAILABLE` —— 资产管线、
 *    SessionGrant 校验与会话状态机未接入前 fail-closed；
 *  - 调用方四重校验（包名白名单 / 签名指纹 / depends 匹配 / minHost）
 *    由宿主 PluginBroker 侧把门（ARC-102 FR-001）。
 *
 * **G-1 服务端硬门禁（ARC-104 §6，v0.3 预填）**：exported=true 意味着任意 App
 * 都能 bindService —— 每个入口先过 [HostCallerGuard.requireTrustedCaller]：
 * `Binder.getCallingUid()` 反查包集合（必须恰为宿主包）与签名证书 SHA-256
 * （必须命中宿主发布者指纹），否则 `E_DIRECT_BIND_DENIED`。M1 的 grant
 * 五元组校验叠加在此之后。
 */
class LinuxRuntimeBinder(
    private val pluginVersion: String,
    private val callerGuard: HostCallerGuard? = null,
) : ILinuxRuntime.Stub() {

    /** 入口护栏：guard 未接线（测试 / 旧调用）时 fail-closed 拒绝。 */
    private fun requireCaller() {
        val guard = callerGuard ?: throw IllegalStateException(
            Diagnostic.of(
                code = "E_DIRECT_BIND_DENIED",
                action = "服务未接线调用方校验（fail-closed）—— 拒绝本次调用",
            ).humanHint,
        )
        guard.requireTrustedCaller()
    }

    override fun getRuntimeInfo(): RuntimeInfoData {
        requireCaller()
        return runtimeSnapshot(pluginVersion).toData()
    }

    override fun openSession(
        spec: SessionSpecData?,
        grant: SessionGrantData?,
    ): ILinuxSession? {
        requireCaller()
        // M0 fail-closed：宁可「不可用」，不可「假装可用」（ARC-101 §2 纪律③）。
        // M1（T-012/T-013）：grant 五元组校验 + 资产 READY + 探测通过后
        // 经 SessionManager 返回 ILinuxSession。
        throw IllegalStateException(
            Diagnostic.of(
                code = "E_RUNTIME_UNAVAILABLE",
                domain = spec?.preferredDomain?.let { RuntimeDomain.fromId(it) },
                action = "运行环境尚未就绪（M0 契约骨架期），请等待 Linux 插件首个功能版本。",
            ).humanHint,
        )
    }

    override fun ping(): Int {
        requireCaller()
        return PONG
    }

    companion object {
        const val PONG = 0x4C58_3032 // "LX02" —— HOST_API v2

        /** 骨架期运行时快照（诚实状态：资产未就绪 + 授权未知）。 */
        fun runtimeSnapshot(pluginVersion: String): RuntimeInfo = RuntimeInfo(
            pluginVersion = pluginVersion,
            hostApi = 2,
            capability = CapabilityId.LINUX_RUNTIME_V1,
            domains = listOf(
                DomainInfo(
                    id = RuntimeDomain.PROOT_USERLAND,
                    kernel = RuntimeDomain.PROOT_USERLAND.kernel,
                    coldStartMs = 8000,
                    features = PROOT_SUPPORTED_FEATURES,
                    assets = setOf("rootfs", "toolchain"),
                ),
                DomainInfo(
                    id = RuntimeDomain.QEMU_VM,
                    kernel = RuntimeDomain.QEMU_VM.kernel,
                    coldStartMs = 180000,
                    features = QEMU_SUPPORTED_FEATURES,
                    assets = setOf("image"),
                ),
            ),
            assetsReady = false,
            shizukuState = ShizukuAuthState.NOT_INSTALLED,
            probeSummary = "",
            engineVersions = mapOf("skeleton" to pluginVersion),
        )
    }
}
