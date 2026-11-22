package com.abyss.plugin.linux.domain.proot

/**
 * PRoot 用户态执行域启动编排（LIN-001 §2 —— 纯 JVM argv 组装）。
 *
 * 启动链路（顺序敏感，LIN-001 §2.2）：
 *  资产检查 → rootfs 解包（canonical 断言）→ ptrace 探测（5s）→
 *  宿主选择（①Shizuku uid2000 推荐 ②App 沙箱纯 PRoot ③双失败→
 *  DEGRADED/UNAVAILABLE + ROM 清单）→ 建立会话 → READY。
 */
object ProotDomain {

    /** 底座本体量级锚点（LIN-001 §2.1：AAR/原生库约 4MB）。 */
    const val ENGINE_NATIVE_SIZE_MB: Int = 4

    /** rootfs 解包目标（App 私有目录内，PLG-005 FR-005 canonical 断言）。 */
    const val ROOTFS_PARENT_DIR: String = "domains"

    /**
     * PRoot 引擎 argv 组装（argv 数组纪律 —— 无 shell 拼接）。
     *
     * 形态（proot-distro 类 CLI）：
     * ```
     * proot -R <rootfs> -0 -w <cwd> [-b <guest>:<host>] [--] <command argv>
     * ```
     *
     * @param rootfsPath rootfs 解包目录（解包后传入；调用方保证 canonical）
     * @param cwd 会话工作区相对路径（`/workspace/build-<ts>`）
     * @param binds 绑定挂载（host 路径 → guest 路径，白名单源）
     * @param command 会话内命令 argv（如 ["apk", "--version"]）
     */
    fun launchArgv(
        rootfsPath: String,
        cwd: String,
        binds: List<Bind> = emptyList(),
        command: List<String>,
    ): List<String> {
        require(rootfsPath.isNotBlank()) { "rootfsPath 不得为空" }
        require(cwd.isNotBlank()) { "cwd 不得为空" }
        require(command.isNotEmpty()) { "command argv 不得为空（argv 数组纪律）" }
        val argv = mutableListOf("proot", "-R", rootfsPath, "-0", "-w", cwd)
        binds.forEach { b ->
            argv += listOf("-b", "${b.hostPath}:${b.guestPath}")
        }
        argv += "--"
        argv += command
        return argv
    }

    /** 绑定挂载（白名单源：工作区 / 缓存只读 / rootfs 只读）。 */
    data class Bind(
        val hostPath: String,
        val guestPath: String,
        val readOnly: Boolean = true,
    ) {
        init {
            require(hostPath.isNotBlank() && guestPath.isNotBlank()) { "绑定路径不得为空" }
            require(!guestPath.contains("..")) { "guest 路径禁止穿越 → $guestPath" }
        }
    }

    /** 会话环境（PRoot 域 env 透传白名单——PATH/HOME/LANG 级）。 */
    fun sessionEnv(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val env = linkedMapOf(
            "HOME" to "/root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG" to "C.UTF-8",
        )
        extra.forEach { (k, v) ->
            require(k.length <= 64 && !k.contains(' ') && !k.contains('=')) { "env 键形态非法 → $k" }
            env[k] = v
        }
        return env
    }
}
