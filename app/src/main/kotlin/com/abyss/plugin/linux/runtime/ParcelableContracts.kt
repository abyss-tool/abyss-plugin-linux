package com.abyss.plugin.linux.runtime

import android.os.Parcelable
import com.abyss.plugin.linux.runtime.api.CapabilityId
import com.abyss.plugin.linux.runtime.api.Diagnostic
import com.abyss.plugin.linux.runtime.api.DomainInfo
import com.abyss.plugin.linux.runtime.api.ExecCommand
import com.abyss.plugin.linux.runtime.api.ExecResult
import com.abyss.plugin.linux.runtime.api.Feature
import com.abyss.plugin.linux.runtime.api.MountSource
import com.abyss.plugin.linux.runtime.api.MountSpec
import com.abyss.plugin.linux.runtime.api.PtyHandle
import com.abyss.plugin.linux.runtime.api.ResourceLimits
import com.abyss.plugin.linux.runtime.api.RuntimeDomain
import com.abyss.plugin.linux.runtime.api.RuntimeInfo
import com.abyss.plugin.linux.runtime.api.RuntimeSession
import com.abyss.plugin.linux.runtime.api.SessionGrant
import com.abyss.plugin.linux.runtime.api.SessionSpec
import com.abyss.plugin.linux.runtime.api.SessionState
import com.abyss.plugin.linux.runtime.api.ShizukuAuthState
import kotlinx.parcelize.Parcelize

/**
 * AIDL Parcelable 数据类（DC-101 §3 —— 字段语义镜像 :runtime-api 纯 JVM
 * 模型，单一事实源，禁止两处漂移）。
 *
 * 纪律：
 *  - 字段一律使用 parcelable 安全类型（String / Int / Long / Boolean /
 *    List<String> / Map<String,String>），枚举经 id 字符串往返；
 *  - `toModel()` 走模型构造期校验 —— 跨进程边界即不可信输入，坏数据
 *    fail-closed（构造抛 IllegalArgumentException → 调用方收到错误回执）。
 */

@Parcelize
data class MountSpecData(
    val source: String,
    val targetRelative: String,
    val readOnly: Boolean,
) : Parcelable

@Parcelize
data class SessionSpecData(
    val preferredDomain: String,
    val allowDomainFallback: Boolean,
    val rootfsId: String?,
    val imageId: String?,
    val workspaceSizeMb: Int,
    val env: Map<String, String>,
    val mounts: List<MountSpecData>,
    val maxCpuPercent: Int,
    val maxMemoryMb: Int,
    val execTimeoutSec: Int,
    val maxOutputBytes: Long,
    val maxConcurrentExec: Int,
    val requestedFeatures: List<String>,
    val clientTag: String,
) : Parcelable

@Parcelize
data class SessionGrantData(
    val callerPackage: String,
    val callerSignerSha256: String,
    val capability: String,
    val sessionId: String,
    val nonce: String,
    val issuedAtEpochMs: Long,
    val expiryEpochMs: Long,
    val hostSignature: String,
) : Parcelable

@Parcelize
data class DiagnosticData(
    val code: String,
    val domain: String?,
    val hint: String,
    val romHint: String,
    val recoverable: Boolean,
    val suggestedAction: String,
) : Parcelable

@Parcelize
data class DomainInfoData(
    val id: String,
    val kernel: String,
    val coldStartMs: Int,
    val features: List<String>,
    val assets: List<String>,
) : Parcelable

@Parcelize
data class RuntimeInfoData(
    val pluginVersion: String,
    val hostApi: Int,
    val capability: String,
    val domains: List<DomainInfoData>,
    val assetsReady: Boolean,
    val shizukuState: String,
    val probeSummary: String,
    val engineVersions: Map<String, String>,
) : Parcelable

@Parcelize
data class RuntimeSessionData(
    val id: String,
    val domain: String,
    val state: String,
    val spec: SessionSpecData?,
    val createdAt: Long,
    val refCount: Int,
    val grantedFeatures: List<String>,
    val diagnostic: DiagnosticData?,
) : Parcelable

@Parcelize
data class ExecCommandData(
    val argv: List<String>,
    val cwd: String,
    val env: Map<String, String>,
    val timeoutSec: Int,
    val stage: String,
    val workdirRelative: String,
) : Parcelable

@Parcelize
data class ExecResultData(
    val exitCode: Int,
    val truncated: Boolean,
    val stdoutDigest: String,
    val stderrSummary: String,
    val durationMs: Long,
    val stage: String,
    val signal: String?,
) : Parcelable

@Parcelize
data class PtyHandleData(
    val ptyId: String,
    val cols: Int,
    val rows: Int,
    val fd: Int,
) : Parcelable

// ── 模型 ↔ AIDL 数据类转换（唯一双向通道）──────────────────────────────

fun MountSpec.toData() = MountSpecData(source.id, targetRelative, readOnly)

fun MountSpecData.toModel(): MountSpec = MountSpec(
    source = MountSource.fromId(source) ?: error("挂载源非法 → $source（E_MOUNT_DENIED）"),
    targetRelative = targetRelative,
    readOnly = readOnly,
)

fun SessionSpec.toData(): SessionSpecData = SessionSpecData(
    preferredDomain = preferredDomain.id,
    allowDomainFallback = allowDomainFallback,
    rootfsId = rootfsId,
    imageId = imageId,
    workspaceSizeMb = workspaceSizeMb,
    env = env,
    mounts = mounts.map { it.toData() },
    maxCpuPercent = limits.maxCpuPercent,
    maxMemoryMb = limits.maxMemoryMb,
    execTimeoutSec = limits.execTimeoutSec,
    maxOutputBytes = limits.maxOutputBytes,
    maxConcurrentExec = limits.maxConcurrentExec,
    requestedFeatures = requestedFeatures.map { it.id },
    clientTag = clientTag,
)

fun SessionSpecData.toModel(): SessionSpec {
    val domain = RuntimeDomain.fromId(preferredDomain)
        ?: error("执行域非法 → $preferredDomain（E_CAPABILITY_MISMATCH）")
    val features = requestedFeatures.map { raw ->
        Feature.fromId(raw) ?: error("特性非法 → $raw（E_FEATURE_UNSUPPORTED）")
    }.toSet()
    return SessionSpec(
        preferredDomain = domain,
        allowDomainFallback = allowDomainFallback,
        rootfsId = rootfsId,
        imageId = imageId,
        workspaceSizeMb = workspaceSizeMb,
        env = env,
        mounts = mounts.map { it.toModel() },
        limits = ResourceLimits(
            maxCpuPercent = maxCpuPercent,
            maxMemoryMb = maxMemoryMb,
            execTimeoutSec = execTimeoutSec,
            maxOutputBytes = maxOutputBytes,
            maxConcurrentExec = maxConcurrentExec,
        ),
        requestedFeatures = features,
        clientTag = clientTag,
    )
}

fun SessionGrant.toData() = SessionGrantData(
    callerPackage = callerPackage,
    callerSignerSha256 = callerSignerSha256,
    capability = capability.value,
    sessionId = sessionId,
    nonce = nonce,
    issuedAtEpochMs = issuedAtEpochMs,
    expiryEpochMs = expiryEpochMs,
    hostSignature = hostSignature,
)

fun SessionGrantData.toModel(): SessionGrant = SessionGrant(
    callerPackage = callerPackage,
    callerSignerSha256 = callerSignerSha256,
    capability = CapabilityId(capability),
    sessionId = sessionId,
    nonce = nonce,
    issuedAtEpochMs = issuedAtEpochMs,
    expiryEpochMs = expiryEpochMs,
    hostSignature = hostSignature,
)

fun Diagnostic.toData() = DiagnosticData(
    code = code,
    domain = domain?.id,
    hint = hint,
    romHint = romHint,
    recoverable = recoverable,
    suggestedAction = suggestedAction,
)

fun DiagnosticData.toModel(): Diagnostic = Diagnostic(
    code = code,
    domain = domain?.let { RuntimeDomain.fromId(it) },
    hint = hint,
    romHint = romHint,
    recoverable = recoverable,
    suggestedAction = suggestedAction,
)

fun DomainInfo.toData() = DomainInfoData(
    id = id.id,
    kernel = kernel,
    coldStartMs = coldStartMs,
    features = features.map { it.id },
    assets = assets.toList(),
)

fun DomainInfoData.toModel(): DomainInfo {
    val domain = RuntimeDomain.fromId(id)
        ?: error("执行域非法 → $id（E_CAPABILITY_MISMATCH）")
    val features = features.map { raw ->
        Feature.fromId(raw) ?: error("特性非法 → $raw（E_FEATURE_UNSUPPORTED）")
    }.toSet()
    return DomainInfo(
        id = domain,
        kernel = kernel,
        coldStartMs = coldStartMs,
        features = features,
        assets = assets.toSet(),
    )
}

fun RuntimeInfo.toData() = RuntimeInfoData(
    pluginVersion = pluginVersion,
    hostApi = hostApi,
    capability = capability.value,
    domains = domains.map { it.toData() },
    assetsReady = assetsReady,
    shizukuState = shizukuState.id,
    probeSummary = probeSummary,
    engineVersions = engineVersions,
)

fun RuntimeInfoData.toModel(): RuntimeInfo = RuntimeInfo(
    pluginVersion = pluginVersion,
    hostApi = hostApi,
    capability = CapabilityId(capability),
    domains = domains.map { it.toModel() },
    assetsReady = assetsReady,
    shizukuState = ShizukuAuthState.fromId(shizukuState)
        ?: error("授权状态非法 → $shizukuState（E_SHIZUKU_NOT_GRANTED）"),
    probeSummary = probeSummary,
    engineVersions = engineVersions,
)

fun RuntimeSession.toData() = RuntimeSessionData(
    id = id,
    domain = domain.id,
    state = state.id,
    spec = spec.toData(),
    createdAt = createdAt,
    refCount = refCount,
    grantedFeatures = grantedFeatures.map { it.id },
    diagnostic = diagnostic?.toData(),
)

fun RuntimeSessionData.toModel(): RuntimeSession = RuntimeSession(
    id = id,
    domain = RuntimeDomain.fromId(domain) ?: error("执行域非法 → $domain"),
    state = SessionState.fromId(state) ?: error("会话状态非法 → $state"),
    spec = spec?.toModel() ?: SessionSpec(),
    createdAt = createdAt,
    refCount = refCount,
    grantedFeatures = grantedFeatures.map { raw ->
        Feature.fromId(raw) ?: error("特性非法 → $raw（E_FEATURE_UNSUPPORTED）")
    }.toSet(),
    diagnostic = diagnostic?.toModel(),
)

fun ExecCommand.toData() = ExecCommandData(
    argv = argv,
    cwd = cwd,
    env = env,
    timeoutSec = timeoutSec,
    stage = stage,
    workdirRelative = workdirRelative,
)

fun ExecCommandData.toModel(): ExecCommand = ExecCommand(
    argv = argv,
    cwd = cwd,
    env = env,
    timeoutSec = timeoutSec,
    stage = stage,
    workdirRelative = workdirRelative,
)

fun ExecResult.toData() = ExecResultData(
    exitCode = exitCode,
    truncated = truncated,
    stdoutDigest = stdoutDigest,
    stderrSummary = stderrSummary,
    durationMs = durationMs,
    stage = stage,
    signal = signal,
)

fun ExecResultData.toModel(): ExecResult = ExecResult(
    exitCode = exitCode,
    truncated = truncated,
    stdoutDigest = stdoutDigest,
    stderrSummary = stderrSummary,
    durationMs = durationMs,
    stage = stage,
    signal = signal,
)

fun PtyHandle.toData() = PtyHandleData(
    ptyId = ptyId,
    cols = cols,
    rows = rows,
    fd = fd,
)

fun PtyHandleData.toModel(): PtyHandle = PtyHandle(
    ptyId = ptyId,
    cols = cols,
    rows = rows,
    fd = fd,
)
