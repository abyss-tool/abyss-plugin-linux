// ILinuxSessionCallback.aidl —— 会话状态推送（ARC-101 FR-008）。
package com.abyss.plugin.linux.runtime;

import com.abyss.plugin.linux.runtime.RuntimeSessionData;

oneway interface ILinuxSessionCallback {
    /** 状态/诊断变化推送（10 状态机，见 :runtime-api SessionState）。 */
    void onStateChanged(in RuntimeSessionData session);

    /** 会话关闭（close() / 引用归零 / Binder 死亡；10s 内必达，LIN-004 AC-002）。 */
    void onClosed(String reason);
}
