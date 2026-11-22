package com.abyss.plugin.linux

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.abyss.plugin.linux.runtime.HostContractActions
import com.abyss.plugin.linux.runtime.LinuxRuntimeBinder

/**
 * Linux 运行时服务（ARC-100 §4 / LIN-004 —— 前台服务托管的
 * LinuxRuntimeService）。
 *
 * 绑定纪律（ARC-102 §4）：
 *  - exported 为跨 APK 绑定所必需（宿主 PluginBroker 经
 *    [HostContractActions.ACTION_LINUX_RUNTIME] 发现）；
 *  - 消费方**永不直连**：直连尝试在宿主侧即被 `E_DIRECT_BIND_DENIED`
 *    拒绝；本服务侧的 grant 五元组校验随 M1 落地（当前 openSession
 *    一律 E_RUNTIME_UNAVAILABLE —— fail-closed）；
 *  - 非 LINUX_RUNTIME 动作的绑定一律返回 null。
 *
 * 前台服务与进程治理（pid/pgid 记录 / kill(-pgid) 整组回收 /
 * refCount 归零 60s 延迟停止）随 :session / :domain-* 模块在 M1 接入。
 */
class LinuxRuntimeService : Service() {

    private var binder: LinuxRuntimeBinder? = null

    override fun onCreate() {
        super.onCreate()
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "0.2.0"
        binder = LinuxRuntimeBinder(version)
    }

    override fun onBind(intent: Intent): IBinder? {
        if (intent.action != HostContractActions.ACTION_LINUX_RUNTIME) {
            return null
        }
        return binder
    }

    override fun onDestroy() {
        binder = null
        super.onDestroy()
    }
}
