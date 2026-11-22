// ILinuxSession.aidl —— 消费方唯一可持有的会话句柄（DC-101 §4）。
//
// 不变式：
//  - 所有方法携带 SessionGrant；服务端每次校验签名/包名/nonce/
//    expiry/sessionId 五元组（DC-101 §4 不变式）；
//  - exec 在会话内串行；并发第二个返回 E_CONCURRENT_LIMIT；
//  - 输出单流上限 1MB，truncated 如实回传；
//  - 任何方法失败返回带 code 的 Diagnostic（经 RuntimeSessionData/
//    ExecResultData 携带），不抛异常穿透 UI。
package com.abyss.plugin.linux.runtime;

import android.os.ParcelFileDescriptor;
import com.abyss.plugin.linux.runtime.RuntimeSessionData;
import com.abyss.plugin.linux.runtime.ExecCommandData;
import com.abyss.plugin.linux.runtime.ExecResultData;
import com.abyss.plugin.linux.runtime.SessionGrantData;
import com.abyss.plugin.linux.runtime.PtyHandleData;
import com.abyss.plugin.linux.runtime.ILinuxSessionCallback;
import com.abyss.plugin.linux.runtime.IOutputSink;

interface ILinuxSession {
    RuntimeSessionData getState();

    /** argv 数组命令（禁止 shell 字符串拼接 —— 从根上消除注入面）。 */
    ExecResultData exec(in ExecCommandData cmd, in SessionGrantData grant, in IOutputSink sink);

    PtyHandleData openPty(int cols, int rows, in SessionGrantData grant);

    /** 工作区文件写入（relativePath 白名单 + canonical 断言）。 */
    void writeFile(String relativePath, in ParcelFileDescriptor pfd, in SessionGrantData grant);

    /** 工作区文件读取（ParcelFileDescriptor 回传消费方进程）。 */
    ParcelFileDescriptor readFile(String relativePath, in SessionGrantData grant);

    void registerCallback(ILinuxSessionCallback cb);

    /** CLOSING → CLOSED：终止进程树、回收 PTY/VNC、清理工作区、撤销 grant。 */
    void close(String reason, in SessionGrantData grant);
}
