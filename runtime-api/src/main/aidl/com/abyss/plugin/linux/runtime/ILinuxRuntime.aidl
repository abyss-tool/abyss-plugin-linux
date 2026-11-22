// ILinuxRuntime.aidl —— 宿主可见的运行时入口（DC-101 §4 / ARC-102 §5）。
//
// 纪律：
//  - 消费方（如应用构建器）不得直接持有本接口 —— 只能经宿主
//    PluginBroker 中介拿到受限 ILinuxSession 代理（ARC-102 FR-004）；
//  - LinuxRuntimeService 虽为 exported（跨 APK 必需），但 onBind 必须
//    校验调用方与 SessionGrant，无合法凭证返回 null；
//  - 接口版本化演进：新增方法保持 v1；删除/改语义必须新开
//    linux-runtime-v2 并提升 HOST_API（DC-101 §5）。
package com.abyss.plugin.linux.runtime;

import com.abyss.plugin.linux.runtime.RuntimeInfoData;
import com.abyss.plugin.linux.runtime.SessionSpecData;
import com.abyss.plugin.linux.runtime.SessionGrantData;
import com.abyss.plugin.linux.runtime.ILinuxSession;

interface ILinuxRuntime {
    /** 运行时第一帧快照（版本/域/资产/授权/探测摘要），不启动会话。 */
    RuntimeInfoData getRuntimeInfo();

    /** 开启会话：grant 有效 + 资产 READY + 探测通过 → ILinuxSession（refCount+1）。 */
    ILinuxSession openSession(in SessionSpecData spec, in SessionGrantData grant);

    /** 存活探测（超时 10s；ARC-102 FR-006）。 */
    int ping();
}
