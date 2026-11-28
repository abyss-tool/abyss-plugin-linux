package com.abyss.plugin.linux

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.abyss.plugin.linux.runtime.api.HostContractActions
import com.abyss.plugin.linux.runtime.HostCallerGuard
import com.abyss.plugin.linux.runtime.LinuxRuntimeBinder

/**
 * Linux 运行时服务（ARC-100 §4 / LIN-004 —— 前台服务托管的
 * LinuxRuntimeService）。
 *
 * 绑定纪律（ARC-102 §4 / ARC-104 §6 G-1）：
 *  - exported 为跨 APK 绑定所必需（宿主 PluginBroker 经
 *    [HostContractActions.ACTION_LINUX_RUNTIME] 发现）；
 *  - 消费方**永不直连**：直连尝试在宿主侧即被 `E_DIRECT_BIND_DENIED`
 *    拒绝；**本服务侧每个 AIDL 入口同样以 `Binder.getCallingUid()` 反查
 *    调用方签名（包集合恰为宿主 + 发布者指纹），第三方绑定后调用即拒**（G-1，
 *    [HostCallerGuard] —— onBind 由 system_server 转发，那里查 uid 是系统
 *    uid，故校验点在 AIDL 方法体）；grant 五元组校验随 M1 叠加；
 *  - 非 LINUX_RUNTIME 动作的绑定一律返回 null。
 *
 * 前台服务与进程治理（pid/pgid 记录 / kill(-pgid) 整组回收 /
 * refCount 归零 60s 延迟停止）随 :session / :domain-* 模块在 M1 接入。
 */
class LinuxRuntimeService : Service() {

    private var binder: LinuxRuntimeBinder? = null

    override fun onCreate() {
        super.onCreate()
        // NG-5（深挖第三轮）：版本免猜 —— PM 查询失败时兑底 "0.0.0"（诚实未知），
        // 不再硬编码 "0.2.0" 制造第三事实源（APK versionName 与 plugin.json 版本
        // 由 CI 门禁 validate_plugin.py 钉死一致，这里只负责如实上报）。
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "0.0.0"
        binder = LinuxRuntimeBinder(version, HostCallerGuard(packageManager))
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
