package com.abyss.plugin.linux.runtime.api

/**
 * 宿主契约动作串（镜像主仓 `PluginContract` HOST_API v2 —— ARC-102 §2）。
 *
 * 事实源纪律：动作串本身即契约（宿主 PluginContract 与本常量必须逐字
 * 一致；两侧 CI 以 manifest 声明 + 集成测试锚定）。HOST_API v2 起宿主
 * PluginBroker 经 [ACTION_LINUX_RUNTIME] 发现并绑定本插件服务。
 */
object HostContractActions {

    /** Linux 插件运行时服务动作串（LinuxRuntimeService intent-filter）。 */
    const val ACTION_LINUX_RUNTIME: String = "com.abyss.tools.plugin.action.LINUX_RUNTIME"

    /** 通用插件服务动作串（沿用 N-PLG-002 AIDL 双向桥）。 */
    const val ACTION_PLUGIN: String = "com.abyss.tools.plugin.action.PLUGIN"

    /** manifest 中宿主入口声明键（APK 插件 meta-data）。 */
    const val META_HOST_ENTRY: String = "com.abyss.tools.plugin.HOST_ENTRY"

    /** manifest 中 UI 入口声明键（APK 插件自有 Activity）。 */
    const val META_UI_ENTRIES: String = "com.abyss.tools.plugin.UI_ENTRIES"

    /** manifest 中能力声明键（HOST_API v2：PluginBroker 能力发现）。 */
    const val META_CAPABILITIES: String = "com.abyss.tools.plugin.CAPABILITIES"

    /** 宿主插件 API 版本（v2：依赖解析 + 能力绑定）。 */
    const val HOST_API_VERSION: Int = 2
}
