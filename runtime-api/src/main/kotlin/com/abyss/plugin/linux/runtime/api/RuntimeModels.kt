package com.abyss.plugin.linux.runtime.api

import kotlinx.serialization.Serializable

/**
 * 诊断（DC-101 §3 / DC-102 §4）。
 *
 * 纪律：
 *  - [code] 必须来自 [AbyssErrorCodes] 注册表（禁止自由文本当错误码）；
 *  - UI 一律展示 [hint] / [suggestedAction] 人话提示，错误码只进日志与诊断导出；
 *  - [romHint] 承载平台限制的已知 ROM 清单（ColorOS / MagicOS 等，
 *    平台限制如实上报，不包装成代码缺陷）。
 */
@Serializable
data class Diagnostic(
    val code: String,
    val domain: RuntimeDomain? = null,
    /** 人话提示（空 = 取 AbyssErrorCodes.hintFor(code)）。 */
    val hint: String = "",
    /** 平台限制类附已知 ROM 清单与人话建议（E_PROBE_PTRACE_BLOCKED 等）。 */
    val romHint: String = "",
    val recoverable: Boolean = false,
    val suggestedAction: String = "",
) {
    init {
        require(code.isNotEmpty()) { "Diagnostic.code 不得为空（禁止自由文本当错误码）" }
    }

    /** 表内校验（构造期 fail-closed；DC-102 FR-004）。 */
    fun requireRegistered(): Diagnostic {
        AbyssErrorCodes.requireRegistered(code)
        return this
    }

    /** 人话提示出口（表内值优先，兜底表内 hint）。 */
    val humanHint: String
        get() = hint.ifBlank { AbyssErrorCodes.hintFor(code) }

    companion object {
        /** 已知 ptrace 受限 ROM 清单（LIN-001 C-8 / LIN-004 §5.4）。 */
        const val ROM_HINT_PTRACE: String =
            "ColorOS 15/16（OPPO/OnePlus/Realme）约 1 分钟后安全警告并终止 ptrace；荣耀 MagicOS 内核拒绝 ptrace。属平台限制，非本插件缺陷。"

        fun of(code: String, domain: RuntimeDomain? = null, action: String = ""): Diagnostic =
            Diagnostic(
                code = code,
                domain = domain,
                recoverable = AbyssErrorCodes.isRecoverable(code),
                suggestedAction = action,
            )
    }
}

/**
 * 宿主签发的会话授权令牌（ARC-102 §4 —— 五元组 + TTL）。
 *
 * 不变式（SEC-100 L4 / ARC-102 FR-005）：
 *  - TTL ≤ 300s（能力描述 auth.grantTtlSec 事实源）；
 *  - nonce 每次 openSession 新签发，不可跨会话复用、不可持久化；
 *  - 会话心跳续期需宿主重新签发（客户端不缓存 grant）。
 */
@Serializable
data class SessionGrant(
    val callerPackage: String,
    /** 调用方 APK 签名集 SHA-256（十六进制大写；同发布者指纹校验）。 */
    val callerSignerSha256: String,
    val capability: CapabilityId = CapabilityId.LINUX_RUNTIME_V1,
    val sessionId: String,
    /** 一次性随机数（重放即 E_GRANT_REPLAY）。 */
    val nonce: String,
    val issuedAtEpochMs: Long,
    val expiryEpochMs: Long,
    /** 宿主签名摘要（消费方可验证签发方身份，SEC-001 三层信任链）。 */
    val hostSignature: String,
) {
    init {
        require(callerPackage.isNotBlank()) { "callerPackage 不得为空" }
        require(SIGNER_PATTERN.matches(callerSignerSha256)) {
            "callerSignerSha256 应为 64 位 hex → $callerSignerSha256"
        }
        require(sessionId.isNotBlank()) { "sessionId 不得为空" }
        require(nonce.length in 16..128) { "nonce 长度应 16..128 → ${nonce.length}" }
        require(expiryEpochMs > issuedAtEpochMs) { "expiry 必须晚于 issuedAt" }
        require(expiryEpochMs - issuedAtEpochMs <= TTL_MAX_SEC * 1000) {
            "grant TTL 不得超过 $TTL_MAX_SEC 秒（ARC-102 FR-005）"
        }
        require(hostSignature.isNotEmpty()) { "hostSignature 不得为空（宿主签发凭证）" }
    }

    fun isExpired(nowEpochMs: Long): Boolean = nowEpochMs >= expiryEpochMs

    companion object {
        const val TTL_MAX_SEC: Long = 300

        private val SIGNER_PATTERN = Regex("^[0-9a-fA-F]{64}$")
    }
}

/** PTY 句柄（DC-101 §3：窗口大小同步 TIOCSWINSZ；Ctrl-C/D 透传）。 */
@Serializable
data class PtyHandle(
    val ptyId: String,
    val cols: Int,
    val rows: Int,
    /** 主侧 fd（ParcelFileDescriptor 跨进程传递，运行时形态）。 */
    val fd: Int = -1,
) {
    init {
        require(cols in 2..1024) { "cols 应在 2..1024 → $cols" }
        require(rows in 2..1024) { "rows 应在 2..1024 → $rows" }
    }
}

/** 运行时会话快照（DC-101 §3 RuntimeSession —— getState() 返回）。 */
@Serializable
data class RuntimeSession(
    /** UUID，跨进程唯一（失败切换路径复用同一 sessionId，LIN-004 FR-009）。 */
    val id: String,
    val domain: RuntimeDomain,
    val state: SessionState,
    val spec: SessionSpec = SessionSpec(),
    val createdAt: Long = 0,
    /** 持有 ILinuxSession 的消费方数量（宿主代理 + 构建器）。 */
    val refCount: Int = 0,
    val grantedFeatures: Set<Feature> = emptySet(),
    val diagnostic: Diagnostic? = null,
) {
    init {
        require(id.isNotBlank()) { "sessionId 不得为空" }
        require(refCount >= 0) { "refCount 不得为负 → $refCount" }
        // 不假装 READY（ARC-101 §2 纪律③：资产/探测/授权任一未绿，不得返回 READY）
        if (state == SessionState.READY) {
            require(grantedFeatures.isNotEmpty()) { "READY 会话必须携带 grantedFeatures" }
        }
    }
}
