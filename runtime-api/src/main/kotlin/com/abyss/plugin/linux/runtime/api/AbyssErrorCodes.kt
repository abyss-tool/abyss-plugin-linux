package com.abyss.plugin.linux.runtime.api

/**
 * 统一错误码表（DC-102 §4 —— 全链路唯一事实源）。
 *
 * 使用纪律（DC-102）：
 *  - 错误码只进日志、诊断导出与 [Diagnostic.code]；UI 一律展示
 *    [hint] 人话提示 + [suggestedAction] 建议动作；
 *  - [recoverable]=true 才允许展示「重试」按钮；平台限制类不得无限重试；
 *  - 新增错误码必须同步 DC-102 表、测试夹具与 spec-lint 枚举校验
 *    （本表即代码侧单一事实源）。
 *
 * 覆盖范围 = DC-102 §4 全表 + ARC-101 §5 的域级原语码
 * （E_PTY_UNSUPPORTED / E_FILE_NOT_FOUND / E_MOUNT_DENIED —— 三者由
 * ARC-101 引用、语义与 DC-004 会话原语对齐，纳入本注册表统一治理）。
 */
object AbyssErrorCodes {

    data class Entry(
        val code: String,
        /** 人话提示要点（UI 直出，禁含错误码原文）。 */
        val hint: String,
        /** 可恢复标记：true 才允许「重试」按钮。 */
        val recoverable: Boolean,
    )

    val ALL: List<Entry> = listOf(
        // ── 依赖 / 契约（DC-102 §4）────────────────────────────────────
        Entry("E_DEP_MISSING", "请先安装 Linux 插件（提供运行环境）", true),
        Entry("E_DEP_VERSION", "Linux 插件需升级到指定版本以上", true),
        Entry("E_DEPENDENCY_CYCLE", "插件依赖配置异常（依赖成环）", false),
        Entry("E_CAPABILITY_MISMATCH", "能力版本不兼容", false),
        Entry("E_HOST_API_TOO_LOW", "请升级深渊工具主壳到新版", true),

        // ── 信任 / 令牌（DC-102 §4 / ARC-102 §7）────────────────────────
        Entry("E_SIGNATURE_MISMATCH", "插件来源不可信（签名集/发布者指纹不符）", false),
        Entry("E_GRANT_INVALID", "请重新打开应用（令牌缺失或非法）", true),
        Entry("E_GRANT_EXPIRED", "已自动重试（令牌过期）", true),
        Entry("E_GRANT_REPLAY", "已拒绝并记审计（nonce 重放）", false),
        Entry("E_DIRECT_BIND_DENIED", "请通过插件中心启动（禁止无中介直连）", false),

        // ── 资产（DC-102 §4 / LIN-003）──────────────────────────────────
        Entry("E_ASSET_INDEX_BAD", "组件清单异常", true),
        Entry("E_ASSET_URL_DENIED", "下载地址不被允许", false),
        Entry("E_ASSET_VOLUME_HASH", "已重下该分卷（分卷校验失败）", true),
        Entry("E_ASSET_WHOLE_HASH", "组件校验失败（整像 sha256 不符）", true),
        Entry("E_ASSET_FORMAT", "组件格式不支持（魔数/格式/大小超限）", false),
        Entry("E_STORAGE_INSUFFICIENT", "需释放存储空间（附所需空间）", true),
        Entry("E_ASSET_MIN_PLUGIN", "请升级 Linux 插件（资产 minPlugin 不满足）", true),

        // ── 探测 / 平台（DC-102 §4 / LIN-001/002）──────────────────────
        Entry("E_PROBE_PTRACE_BLOCKED", "当前系统限制 ptrace（已知 ROM 清单 + 切域建议）", false),
        Entry("E_PROBE_TIMEOUT", "环境检测超时", true),
        Entry("E_TOOLCHAIN_LIBC", "工具链不兼容（动态依赖不可解析）", false),
        Entry("E_VM_RESOURCE", "建议降档或释放内存（内存/memlock 不足）", true),
        Entry("E_VM_TCG_UNAVAILABLE", "完整系统域不可用（TCG 不可用）", false),

        // ── 授权（DC-102 §4 / LIN-005）──────────────────────────────────
        Entry("E_SHIZUKU_NOT_RUNNING", "请启动 Shizuku", true),
        Entry("E_SHIZUKU_NOT_GRANTED", "请为 Linux 插件单独授权一次", true),
        Entry("E_SHIZUKU_REVOKED", "请重新授权（授权被撤销）", true),

        // ── 会话（DC-102 §4 / ARC-101 §5）──────────────────────────────
        Entry("E_SESSION_CLOSED", "请重新开始（会话已关闭）", true),
        Entry("E_CONCURRENT_LIMIT", "已有任务在跑（并发 exec 超限）", true),
        Entry("E_EXEC_TIMEOUT", "构建超时，可降档重试", true),
        Entry("E_EXEC_TRUNCATED", "日志不完整（输出被截断，如实标注）", false),
        Entry("E_PATH_TRAVERSAL", "路径不合法（路径穿越被拒）", false),
        Entry("E_FEATURE_UNSUPPORTED", "当前运行环境不支持该特性", false),
        Entry("E_RUNTIME_UNAVAILABLE", "运行环境未就绪（Linux 插件未就绪）", true),
        Entry("E_RUNTIME_DEAD", "运行环境已退出（Binder 死亡）", true),

        // ── 域级原语码（ARC-101 §5，纳入统一治理）──────────────────────
        Entry("E_PTY_UNSUPPORTED", "当前执行域不支持终端（PTY）", false),
        Entry("E_FILE_NOT_FOUND", "文件不存在（工作区内）", true),
        Entry("E_MOUNT_DENIED", "挂载被拒（白名单目录之外）", false),

        // ── 产物（DC-102 §4 / APP-102）──────────────────────────────────
        Entry("E_ARTIFACT_EMPTY", "产物为空文件", false),
        Entry("E_ARTIFACT_FORMAT", "产物格式异常（zip 魔数/中央目录不可解析）", false),
        Entry("E_ARTIFACT_ALIGN", "产物对齐校验失败（zipalign）", false),
        Entry("E_ARTIFACT_SIGN", "产物签名校验失败（apksigner）", false),
        Entry("E_ARTIFACT_DIGEST", "产物 SHA-256 与构建记录不一致", false),
        Entry("E_ARTIFACT_META", "产物元信息非法（包名/versionCode）", false),
        Entry("E_INSTALL_TIMEOUT", "安装超时，可重试", true),
        Entry("E_INSTALL_REJECTED", "安装被拒（签名冲突或系统拦截）", false),
    )

    private val byCode: Map<String, Entry> = ALL.associateBy { it.code }

    /** 表内是否存在该错误码（Diagnostic.code 只允许表内值 —— DC-102 FR-004）。 */
    fun isRegistered(code: String): Boolean = byCode.containsKey(code)

    /** 人话提示（表外码 fail-closed 返回通用提示，绝不自由文本当错误码）。 */
    fun hintFor(code: String): String =
        byCode[code]?.hint ?: "出现未知错误（$code），请导出诊断反馈"

    /** 可恢复标记。 */
    fun isRecoverable(code: String): Boolean = byCode[code]?.recoverable ?: false

    /** 代码中出现的 E_* 全部必须在本表（spec-lint / 单测 AC-002 门禁的运行时形态）。 */
    fun requireRegistered(code: String) {
        require(isRegistered(code)) { "错误码未登记于 DC-102 表 → $code（新增须同步表/夹具/spec-lint）" }
    }
}
