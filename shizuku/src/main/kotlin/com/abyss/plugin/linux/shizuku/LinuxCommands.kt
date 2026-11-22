package com.abyss.plugin.linux.shizuku

/**
 * Linux 会话指令编排（LIN-005 §3 / SEC-003 —— 指令模板集中地，纯 JVM）。
 *
 * 纪律（LIN-005 / SEC-100）：
 *  - **argv 数组纪律**：所有模板一律 `List<List<String>>` 形态 —— 消费方
 *    传入的命令在会话内执行，不经过 shell 字符串拼接，从根上消除注入面；
 *  - 免引用参数过 [requireSafeToken]（白名单 `[A-Za-z0-9._:-]`），含
 *    `;` `|` `&` `$` 反引号、空格一律**就地拒绝**，不尝试净化；
 *  - 自由文本过 [shellQuote]（单引号转义）；
 *  - 业务模块禁止拼 shell 字符串（模板集中本文件）；
 *  - 审计只记 sessionId / 结果码，不记命令正文（SEC-009 数据最小化）。
 */
object LinuxCommands {

    /** 免引用参数白名单（LIN-005 §3 / SEC-003）。 */
    val SAFE_TOKEN_PATTERN: Regex = Regex("^[A-Za-z0-9._:-]+$")

    /** 免引用参数校验：非法字符就地拒绝（不净化）。 */
    fun requireSafeToken(token: String): String {
        require(token.isNotEmpty()) { "空参数" }
        require(SAFE_TOKEN_PATTERN.matches(token)) {
            "参数含非法字符（就地拒绝，不尝试净化）：$token"
        }
        return token
    }

    /**
     * 会话内相对路径校验（argv 元素形态 —— 与 ExecCommand.cwd/
     * workdirRelative 同款纪律）：非空、非绝对、禁穿越（..）、禁
     * 反斜杠与空白（路径含 `/` 合法，属 argv 元素而非 shell 拼接）。
     */
    fun requireSafeRelativePath(path: String): String {
        require(path.isNotEmpty()) { "空路径" }
        require(!path.startsWith("/")) { "路径必须为会话内相对形态：$path" }
        require(!path.contains('\\')) { "路径禁用反斜杠：$path" }
        require(".." !in path.split('/')) { "路径禁止穿越：$path" }
        require(path.none { it.isWhitespace() || it in ";|&`\$" }) {
            "路径含非法字符（就地拒绝，不尝试净化）：$path"
        }
        return path
    }

    /** 自由文本单引号转义（`'` → `'\''`）。 */
    fun shellQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    // ── 探测指令组（LIN-001 §2.4：只读、无副作用、5s 超时看门狗）────────
    //     argv 形态：getenforce 为独立命令；TracerPid 经 grep 读取状态。

    /** 环境级探测组（执行顺序即数组顺序；执行器由 Android 侧注入）。 */
    val PROBE_ARGV: List<List<String>> = listOf(
        listOf("getenforce"),
        listOf("grep", "TracerPid", "/proc/self/status"),
    )

    /** rootfs 就绪探测（底座内 ldd 验证 aapt2 动态依赖 —— C-1 门禁）。 */
    fun toolchainLddCheckArgv(aapt2RelativePath: String): List<String> =
        listOf("ldd", requireSafeRelativePath(aapt2RelativePath))

    /** rootfs 内自检命令（E2E-01：`echo ok` / `apk --version`）。 */
    fun smokeTestArgv(): List<List<String>> = listOf(
        listOf("echo", "ok"),
        listOf("apk", "--version"),
    )

    // ── 会话启动模板（引擎拉起由 :domain-proot / :domain-qemu 承载）────

    /** 前台服务启动自检（进程组登记 + pgid 断言的辅助读取）。 */
    val SESSION_GUARD_ARGV: List<List<String>> = listOf(
        listOf("cat", "/proc/self/status"),
    )

    /** 快照元数据读取（QEMU 域维护用，只读）。 */
    val SNAPSHOT_READ_ARGV: List<List<String>> = listOf(
        listOf("ls", "-l", "/workspace"),
    )

    /** 引擎拉起超时（探测 / 短命令 30s 真超时看门狗，ARC-101 §5）。 */
    const val WATCHDOG_TIMEOUT_SECONDS: Int = 30
}
