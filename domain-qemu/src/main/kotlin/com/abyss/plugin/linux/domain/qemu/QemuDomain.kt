package com.abyss.plugin.linux.domain.qemu

/**
 * QEMU 完整系统执行域编排（LIN-002 —— 纯 JVM 决策与 argv 组装）。
 *
 * 关键规则：
 *  - TCG 用户态（免 KVM），客机 ARM64 on ARM64 优先（跨架构需二次确认）；
 *  - 机器类型 virt（virtio-blk/net），无图形设备时串口 + VNC；
 *  - 镜像 qcow2（快照/差量）；
 *  - 显示：VNC 仅绑 **127.0.0.1**，端口与口令随会话随机，关闭即失效；
 *  - 工作区：9p / virtiofs 挂载进客机 `/workspace`（canonical 断言）；
 *  - 网络：slirp 用户态 NAT，仅客机主动出站；
 *  - 档位：LOW/MID/HIGH（客机内存 + QEMU 开销 ≤ 宿主可用 60%；HIGH
 *    需 ≥4GB 空闲内存否则拒绝）。
 */
object QemuDomain {

    /** 性能档位（LIN-002 §3.2 —— 构建器默认 MID；用户可在切域弹层降档）。 */
    enum class Tier(val vcpu: Int, val memoryMb: Int, val note: String) {
        LOW(1, 512, "登录 / 轻量命令（不跑构建）"),
        MID(2, 1024, "路径三兜底单模块构建（workers.max=1）"),
        HIGH(4, 2048, "多模块构建（谨慎）：需 ≥4GB 空闲内存，否则拒绝"),
    }

    /** HIGH 档空闲内存门槛（LIN-002 §3.2）。 */
    const val HIGH_TIER_MIN_FREE_MB: Int = 4096

    /** 客机内存 + QEMU 开销占宿主可用内存上限比例（60%）。 */
    const val HOST_MEMORY_BUDGET_RATIO: Double = 0.6

    /**
     * 档位校验（探测不足直接拒绝 —— 不启动注定失败的实例）。
     *
     * @param tier 目标档位
     * @param availableMemoryMb 宿主当前可用内存（Android ActivityManager 口径）
     * @return null = 通过；否则人话拒收（E_VM_RESOURCE）
     */
    fun validateTier(tier: Tier, availableMemoryMb: Int): String? {
        val budget = (availableMemoryMb * HOST_MEMORY_BUDGET_RATIO).toInt()
        if (tier.memoryMb > budget) {
            return "内存不足：$tier 需 ${tier.memoryMb}MB，宿主预算上限 ${budget}MB（可用 60% 规则）—— 建议降档或释放内存（E_VM_RESOURCE）"
        }
        if (tier == Tier.HIGH && availableMemoryMb < HIGH_TIER_MIN_FREE_MB) {
            return "HIGH 档需 ≥${HIGH_TIER_MIN_FREE_MB}MB 空闲内存（当前 $availableMemoryMb MB）—— 拒绝启动（E_VM_RESOURCE）"
        }
        return null
    }

    /** 快照元数据（DC-102 FR-004：含镜像 id / 内存大小 / 创建时间 / sha256）。 */
    data class SnapshotMeta(
        val imageId: String,
        val memoryMb: Int,
        val createdAtEpochMs: Long,
        val sha256: String,
    ) {
        init {
            require(imageId.isNotBlank()) { "快照必须绑定镜像 id" }
            require(memoryMb > 0) { "快照内存大小非法 → $memoryMb" }
            require(sha256.length == 64) { "快照 sha256 应为 64 位 hex" }
        }
    }

    /** VNC 绑定策略（FR-008：仅 127.0.0.1；口令随会话随机）。 */
    object VncPolicy {
        const val LOOPBACK_HOST: String = "127.0.0.1"

        /** null = 合法；否则人话拒收（非回环绑定被拒，LIN-002 AC-005）。 */
        fun checkBinding(host: String): String? {
            if (host.trim() != LOOPBACK_HOST) {
                return "VNC 仅允许绑定回环地址（$LOOPBACK_HOST）—— 绑定 $host 被拒（会话级随机口令 + 关闭即失效）"
            }
            return null
        }

        /** 口令长度基线（随机生成；会话关闭即失效，不持久化）。 */
        const val PASSWORD_LENGTH: Int = 16
    }

    /**
     * QEMU argv 组装（argv 数组纪律；引擎 `qemu-system-aarch64` 用户态 TCG）。
     *
     * 形态：
     * ```
     * qemu-system-aarch64 -machine virt -cpu cortex-a72 -m <MB> -smp <vcpu>
     *   -nographic -serial stdio（或 VNC :0） -drive file=<qcow2>,if=virtio,format=qcow2
     *   -fsdev local,id=ws,path=<workspace>,security_model=none -device virtio-9p-pci,fsdev=ws,mount_tag=workspace
     *   -netdev user,id=net0 -device virtio-net-pci,netdev=net0（slirp 仅主动出站）
     *   -loadvm <snapshot>（恢复时）
     * ```
     */
    fun launchArgv(
        imageId: String,
        imagePath: String,
        workspacePath: String,
        tier: Tier,
        vncDisplay: Int? = null,
        snapshot: String? = null,
    ): List<String> {
        require(imageId.isNotBlank()) { "imageId 不得为空" }
        require(imagePath.isNotBlank()) { "imagePath 不得为空" }
        require(workspacePath.isNotBlank()) { "workspacePath 不得为空" }
        val argv = mutableListOf(
            "qemu-system-aarch64",
            "-machine", "virt",
            "-cpu", "cortex-a72",
            "-accel", "tcg",
            "-m", tier.memoryMb.toString(),
            "-smp", tier.vcpu.toString(),
            "-drive", "file=$imagePath,if=virtio,format=qcow2",
            // 9p 工作区挂载（LIN-002 FR-007：客机内 /workspace）
            "-fsdev", "local,id=ws,path=$workspacePath,security_model=none",
            "-device", "virtio-9p-pci,fsdev=ws,mount_tag=workspace",
            // slirp 用户态 NAT：仅客机主动出站
            "-netdev", "user,id=net0",
            "-device", "virtio-net-pci,netdev=net0",
        )
        if (vncDisplay != null) {
            require(vncDisplay in 0..63) { "VNC display 号应为 0..63 → $vncDisplay" }
            // 127.0.0.1 绑定由 VncPolicy 保证（口令经 monitor 通道随会话注入）
            argv += listOf("-vnc", "127.0.0.1:$vncDisplay")
        } else {
            argv += listOf("-nographic", "-serial", "stdio")
        }
        if (snapshot != null) {
            require(snapshot.isNotBlank()) { "快照名不得为空" }
            argv += listOf("-loadvm", snapshot)
        }
        return argv
    }

    /** 客机内核版本探测（E2E-09：`uname -r` 为客机内核 ≠ Android 内核）。 */
    val GUEST_KERNEL_CHECK_ARGV: List<String> = listOf("uname", "-r")
}
