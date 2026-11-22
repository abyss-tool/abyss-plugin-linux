package com.abyss.plugin.linux.runtime.api

import kotlinx.serialization.Serializable

/**
 * 会话规格（ARC-101 §1 / DC-101 §3）。
 *
 * 纪律：
 *  - [mounts] 仅白名单路径 + canonical 断言（执行期校验，见 :session）；
 *  - [limits] 消费方只能**约束**不能越权提高（FR-007：宿主事实源比对）；
 *  - [requestedFeatures] 请求前先比对能力矩阵，不满足返回
 *    E_FEATURE_UNSUPPORTED，禁止隐藏失败。
 */
@Serializable
data class SessionSpec(
    val preferredDomain: RuntimeDomain = RuntimeDomain.PROOT_USERLAND,
    /** 仅用户确认后才允许切 QEMU_VM（DEGRADED 弹层产物）。 */
    val allowDomainFallback: Boolean = true,
    /** PROOT_USERLAND 用。 */
    val rootfsId: String? = null,
    /** QEMU_VM 用。 */
    val imageId: String? = null,
    val workspaceSizeMb: Int = DEFAULT_WORKSPACE_MB,
    val env: Map<String, String> = emptyMap(),
    val mounts: List<MountSpec> = emptyList(),
    val limits: ResourceLimits = ResourceLimits(),
    val requestedFeatures: Set<Feature> = setOf(Feature.EXEC, Feature.FILE_IO),
    /** 调用方标识，用于审计关联（禁记命令正文与项目内容，SEC-009）。 */
    val clientTag: String = "",
) {
    init {
        require(workspaceSizeMb in 1..MAX_WORKSPACE_MB) {
            "workspaceSizeMb 应在 1..$MAX_WORKSPACE_MB → $workspaceSizeMb"
        }
        require(requestedFeatures.isNotEmpty()) { "requestedFeatures 不得为空（至少 EXEC）" }
        require(Feature.EXEC in requestedFeatures) { "requestedFeatures 必须包含 EXEC（公共子集）" }
        requestedFeatures.forEach { f ->
            val supported = when (preferredDomain) {
                RuntimeDomain.PROOT_USERLAND -> PROOT_SUPPORTED_FEATURES
                RuntimeDomain.QEMU_VM -> QEMU_SUPPORTED_FEATURES
            }
            require(f in supported) {
                "域 ${preferredDomain.id} 不支持特性 ${f.id}（E_FEATURE_UNSUPPORTED，禁止隐藏失败）"
            }
        }
        require(clientTag.length <= 64) { "clientTag ≤64 字符（审计标签）" }
        when (preferredDomain) {
            RuntimeDomain.QEMU_VM -> requireNotNull(imageId) {
                "QEMU_VM 会话必须声明 imageId（执行域资产）"
            }
            RuntimeDomain.PROOT_USERLAND -> Unit
        }
    }

    companion object {
        const val DEFAULT_WORKSPACE_MB: Int = 512
        const val MAX_WORKSPACE_MB: Int = 4096

        /** exec 默认输出单流上限（1MB，SHL-001/003）。 */
        const val MAX_OUTPUT_BYTES: Long = 1_048_576
    }
}

/** 挂载规格（ARC-101 §1 MountSpec）：source 白名单 + 目标 canonical 断言。 */
@Serializable
data class MountSpec(
    /** 白名单源（workspace / 缓存只读等；服务端 canonical 断言）。 */
    val source: MountSource,
    /** 工作区内相对路径（禁绝对路径 / `..` 穿越）。 */
    val targetRelative: String,
    val readOnly: Boolean = true,
) {
    init {
        require(targetRelative.isNotBlank()) { "targetRelative 不得为空" }
        require(!targetRelative.startsWith("/")) { "targetRelative 必须为相对路径 → $targetRelative" }
        require(".." !in targetRelative.split('/', '\\')) {
            "targetRelative 禁止路径穿越 → $targetRelative（E_PATH_TRAVERSAL）"
        }
    }
}

/** 挂载白名单源（ARC-101 §3 mount 原语）。 */
enum class MountSource(val id: String) {
    /** 会话工作区（可写）。 */
    WORKSPACE("workspace"),

    /** 工具链 / 依赖缓存（只读共享，跨会话保留）。 */
    CACHE_RO("cache-readonly"),

    /** 已校验 rootfs（只读）。 */
    ROOTFS_RO("rootfs-readonly");

    companion object {
        fun fromId(raw: String): MountSource? = entries.firstOrNull { it.id == raw }
    }
}

/**
 * 资源限额（ARC-101 §1 / DC-101 §3）。
 *
 * 默认值即能力描述声明的事实源上限 —— 消费方请求只允许**收紧**，
 * 不允许越权提高（SessionSpec FR-007，宿主侧 clamp 校验）。
 */
@Serializable
data class ResourceLimits(
    val maxCpuPercent: Int = DEFAULT_MAX_CPU_PERCENT,
    val maxMemoryMb: Int = DEFAULT_MAX_MEMORY_MB,
    val execTimeoutSec: Int = DEFAULT_EXEC_TIMEOUT_SEC,
    val maxOutputBytes: Long = SessionSpec.MAX_OUTPUT_BYTES,
    val maxConcurrentExec: Int = 1,
) {
    init {
        require(maxCpuPercent in 1..100) { "maxCpuPercent 应在 1..100 → $maxCpuPercent" }
        require(maxMemoryMb in 16..DEFAULT_MAX_MEMORY_MB) {
            "maxMemoryMb 不得越权高于 $DEFAULT_MAX_MEMORY_MB → $maxMemoryMb（FR-007）"
        }
        require(execTimeoutSec in 1..DEFAULT_EXEC_TIMEOUT_SEC) {
            "execTimeoutSec 不得越权高于 $DEFAULT_EXEC_TIMEOUT_SEC → $execTimeoutSec（FR-007）"
        }
        require(maxOutputBytes in 1..SessionSpec.MAX_OUTPUT_BYTES) {
            "maxOutputBytes 不得越权高于 ${SessionSpec.MAX_OUTPUT_BYTES} → $maxOutputBytes（FR-007）"
        }
        require(maxConcurrentExec in 1..1) {
            "maxConcurrentExec 当前恒为 1（同一会话串行构建，ARC-101 §1）"
        }
    }

    companion object {
        const val DEFAULT_MAX_CPU_PERCENT: Int = 80
        const val DEFAULT_MAX_MEMORY_MB: Int = 2048
        const val DEFAULT_EXEC_TIMEOUT_SEC: Int = 1800
    }
}

/**
 * 执行命令（DC-101 §3 / ARC-101 §3 —— **argv 数组，禁止字符串命令**）。
 *
 * 契约形态本身消除注入面：不存在 `command: String` 字段；服务端
 * （:session / :shizuku 模板编排）亦拒绝任何含 shell 元字符的单字符串。
 */
@Serializable
data class ExecCommand(
    /** argv 数组（argv[0] 为程序路径；元素不得为空白）。 */
    val argv: List<String>,
    /** 工作区内 cwd（相对；空 = 工作区根）。 */
    val cwd: String = "",
    val env: Map<String, String> = emptyMap(),
    /** 该次 exec 的超时（≤ limits.execTimeoutSec）。 */
    val timeoutSec: Int = ResourceLimits.DEFAULT_EXEC_TIMEOUT_SEC,
    /** 构建阶段标签（进度条一一对应，APP-101 FR-005）。 */
    val stage: String = "",
    /** 工作区内文件相对路径（空 = cwd）。 */
    val workdirRelative: String = "",
) {
    init {
        require(argv.isNotEmpty()) { "argv 不得为空（argv 数组命令，禁字符串拼接）" }
        require(argv.none { it.isBlank() }) { "argv 元素不得为空白 → $argv" }
        require(argv.size <= 256) { "argv 长度上限 256 → ${argv.size}" }
        require(cwd.isBlank() || (!cwd.startsWith("/") && ".." !in cwd.split('/', '\\'))) {
            "cwd 必须为工作区内相对路径 → $cwd（E_PATH_TRAVERSAL）"
        }
        require(workdirRelative.isBlank() ||
            (!workdirRelative.startsWith("/") && ".." !in workdirRelative.split('/', '\\'))) {
            "workdirRelative 必须为相对路径 → $workdirRelative（E_PATH_TRAVERSAL）"
        }
        require(timeoutSec in 1..ResourceLimits.DEFAULT_EXEC_TIMEOUT_SEC) {
            "timeoutSec 不得越权高于 ${ResourceLimits.DEFAULT_EXEC_TIMEOUT_SEC} → $timeoutSec（FR-007）"
        }
        require(stage.length <= 64) { "stage 标签 ≤64 字符" }
        require(env.size <= 64) { "env 数量上限 64 → ${env.size}" }
        require(env.keys.all { it.length <= 64 && !it.contains(' ') && !it.contains('=') }) {
            "env 键形态非法（禁空格与 '='，注入面收敛）"
        }
    }
}

/** exec 结果（DC-101 §3：exitCode==0 才算成功；截断如实标注）。 */
@Serializable
data class ExecResult(
    val exitCode: Int,
    val truncated: Boolean = false,
    /** stdout 摘要 digest（审计与 UI 预览用；全量不跨进程回传）。 */
    val stdoutDigest: String = "",
    /** stderr 摘要（人话浓缩，非全量）。 */
    val stderrSummary: String = "",
    val durationMs: Long = 0,
    val stage: String = "",
    /** 终止信号（被看门狗/超时杀掉时非空，如实回传不吞异常）。 */
    val signal: String? = null,
) {
    val succeeded: Boolean get() = exitCode == 0
}
