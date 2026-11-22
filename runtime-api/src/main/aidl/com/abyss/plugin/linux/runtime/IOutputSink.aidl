// IOutputSink.aidl —— exec 输出流式回传（SHL-001/003：stdout/stderr
// 并发读取、单流 1MB 上限、truncated 如实标注）。
package com.abyss.plugin.linux.runtime;

import com.abyss.plugin.linux.runtime.ExecResultData;

oneway interface IOutputSink {
    /** chunk 为字节流片段；isError 区分 stdout / stderr。 */
    void onChunk(in byte[] chunk, boolean isError);

    /** exec 终局结果（exitCode / truncated / durationMs / stage）。 */
    void onResult(in ExecResultData result);
}
