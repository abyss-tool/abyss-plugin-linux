# Linux 运行环境 · 架构（docs/ARCHITECTURE.md）

> 本文档是 abyss-plugin-linux 的技术方案单一事实源。第 1 章「底座层」
> 自 `abyss-plugin-app-builder` 仓迁入（ADR-100 D-1 / ARC-103 M-1~M-7
> 职责迁移）并扩写为**双执行域**形态。
> 规格依据：主仓 `docs/abyss-specs/11-Linux插件解耦/`（ADR-100 /
> ARC-100~103 / LIN-001~005 / DC-100~102 / SEC-100 / ENG-100）。

## 总览（三进程架构 · ARC-100 §2）

```
进程 A：主壳 Abyss-Tools（PluginBroker 中介 · HOST_API v2）
    │ ① 索引/安装/补丁（既有链路）        ② IAbyssPluginHost.getCapability
    ▼                                    ▼
进程 B：abyss-plugin-linux（本仓库）      进程 C：应用构建器（瘦客户端）
    LinuxRuntimeService（exported）        depends（永不直连）
    ├─ AssetManager    资产下载/校验/配额   预检 / 三路径 / 产物闭环
    ├─ DomainProbe     ptrace/memlock/探测  无 rootfs / 无 PRoot
    ├─ SessionManager  状态机/引用计数      无 QEMU / 无 Shizuku
    ├─ Domain:PROOT    PRoot + Alpine
    ├─ Domain:QEMU     TCG + qcow2 + VNC
    ├─ ShizukuExecutor uid2000 唯一授权
    └─ WorkspaceProvider 会话内文件进出
    ▲
    │ Shizuku（全生态唯一一次 per-app 授权）
    ▼
ADB shell 上下文（uid 2000）/ 或 App 沙箱纯 PRoot 降级
```

关键单向性：**构建器 → 宿主 → Linux 插件**。构建器永不直接持有 Linux
插件的 Binder 全权，也不持有 Shizuku 句柄（ARC-100 §2）。

## 一、底座层（自 app-builder 迁入 + 双执行域扩写）

### 1.1 双执行域（ADR-100 D-2 / SRS-101）

| 维度 | PROOT_USERLAND | QEMU_VM |
| --- | --- | --- |
| 内核 | 复用 Android 内核（PRoot ptrace 用户态 chroot 模拟） | 自带 Linux 客户机内核（QEMU TCG arm64） |
| 冷启动 | ≤8s（目标） | ≤180s（上限 5min 拒绝） |
| 能力 | EXEC / PTY / FILE_IO / PKG_INSTALL（PORT_FORWARD 受限） | 公共子集 + VNC / SNAPSHOT / PORT_FORWARD |
| 资产 | rootfs + toolchain | image（qcow2） |
| 降级角色 | 默认域 | ptrace 被拦时的显式切换域（用户确认 + 耗时/存储/性能预期） |

会话不可原地换域：切域 = close 旧会话 + openSession 新会话（产物由
构建器显式搬运，LIN-002 边界）。

### 1.2 自包含，告别 Termux（迁入原 1.1）

- proot-distro-nolib 类方案把 PRoot 引擎、Alpine 解压与管理逻辑封装为
  可嵌入 AAR / 原生库（约 4MB），打包进本插件 APK（M1 :domain-proot）；
- PRoot 经 ptrace 在用户态模拟 chroot，免 Root，仅用 App 私有目录；
- QEMU TCG 引擎（qemu-system-aarch64 用户态，免 KVM）随 M3 :domain-qemu
  嵌入 —— 客机 ARM64 on ARM64 优先（跨架构需二次性能确认）；
- 结论：用户只需安装「深渊工具 + Linux 插件（+ 构建器）」三个 APK，
  Shizuku 只授权**一次**（本插件）。

### 1.3 PRoot 可用性探测与降级链（迁入原 1.2 + LIN-004 §5.4 扩写）

PRoot 依赖 `ptrace()`，在部分国产定制系统上被平台级策略否决：

| 系统 | 现象 | 根因 |
| --- | --- | --- |
| ColorOS 15/16（OPPO / OnePlus / Realme） | 运行约 1 分钟后安全警告并杀进程 | ptrace 被安全策略限制 |
| 荣耀 MagicOS | 内核拒绝 | 严格 SELinux 策略 |

这不是能靠代码「优化」解决的问题（E_PROBE_PTRACE_BLOCKED 属平台限制，
如实上报 + 已知 ROM 清单，不无限重试）。探测决策树：

```
PROBING
 ├─ ptrace 可用（App 沙箱）          → READY [via=app_sandbox]
 ├─ ptrace 受限 + Shizuku 已授权     → READY [via=uid2000]
 ├─ ptrace 受限 + Shizuku 未授权     → DEGRADED [NEED_SHIZUKU] → 引导授权 → 重探测（最多 1 次）
 ├─ ptrace 受限 + 授权后仍失败        → DEGRADED [PLATFORM_BLOCKED]
 │     ├─ allowDomainFallback=true  → 用户确认切域 → CLOSED → openSession(QEMU_VM)
 │     └─ 否 / 无镜像 / 存储不足      → UNAVAILABLE（+ ROM 清单）
 └─ QEMU 探测不满足（内存/memlock）    → UNAVAILABLE [E_VM_RESOURCE]（附降档建议）
```

纪律四条（LIN-004 §5.4）：不静默重试（同原因最多重探测 1 次）；不静默
切域（弹层确认）；不假装 READY（资产/探测/授权任一未绿不得 READY）；
平台限制如实上报。

### 1.4 Shizuku 统一执行层（迁入原 1.3 + LIN-005 收敛）

- 本插件是全生态**唯一** Shizuku per-app 授权持有方：PRoot 绕 ptrace
  限制 + QEMU 绕 `RLIMIT_MEMLOCK` / `oom_adj`，都经 uid 2000 上下文；
- 消费方（应用构建器等）不再申请授权 —— 授权语义收敛为一次；
- Shizuku 不是 root：uid 2000（shell 级），不能 `su`、不能任意突破
  SELinux；ColorOS 16 的 Shizuku「权限监控」拦截应对（切英语 → 关闭
  系统优化 → 重启 Shizuku）在 UI 提示层如实告知权衡。

### 1.5 底座状态隔离（迁入原 1.4）

- 工具链隔离：三条构建路径的工具链（Anpyra / aapt2+壳 DEX /
  JDK+SDK+Gradle）各自独立子目录，互不覆盖；
- 工作区隔离：每次会话独立 `/workspace/build-<timestamp>`，
  close 后清理（**失败切换路径不清理**，复用同一 sessionId）；
- 缓存共享只读：Maven / pip 缓存跨会话复用；
- 并发控制：同一会话串行（maxConcurrentExec=1）；引用归零 60s 延迟回收。

### 1.6 工程约束（LIN-001 C-1~C-8，落地即 CI 门禁）

- C-1 aapt2 libc：Android/Bionic 或 musl 明确编译的 ARM64 版；底座内
  `ldd` 全量验证，不过**拒绝就绪**（E_TOOLCHAIN_LIBC）；
- C-2 compileSdk 暂锁 34（android-35.jar 兼容问题，导入提示告知）；
- C-3 官方工具 x86_64 错位 → `android.aapt2FromMavenOverride` ARM64 覆盖
  + 社区 ARM64 NDK；build-tools ≥ 33.0.0；
- C-4 Gradle 约束：`-Xmx2048m` / `vfs.watch=false` / `workers.max=2` /
  `sdkDownload=false`；
- C-5 本地 Maven 缓存纳入 App 持久化管理；
- C-6 targetSdk 28 W^X：原生库与可执行物放 App 私有可加载路径；
- C-7 16KB 页对齐：`.so` 全部 16KB 对齐，CI zipalign/readelf 门禁；
- C-8 ptrace 平台限制属平台特性，不靠代码优化解决（见 1.3）。

## 二、资产与分发（LIN-003 / DC-102）

大资产通道 `abyss-asset-v1` 与数据补丁 `abyss-patch-v1` 严格分离
（ADR-011）：分卷二进制 + `assets/index.json` 清单、每卷独立 sha256 +
整像二次 sha256（双层）、断点续传（offset 账本）、原子安装（临时目录 →
校验 → rename）、8GB 默认配额 + 90% 阈值 LRU 清理（保留当前会话引用资产
与至少 1 个可用 rootfs）、清理写审计。

存储布局：`filesDir/runtime/assets/<assetId>/<version>/`（资产）、
`domains/<rootfsId>/`（解包 rootfs）、`sessions/<sessionId>/workspace/`、
`ledger.json`（账本）。

## 三、会话契约（DC-101 / ARC-101）

- `RuntimeSession` 状态机 10 状态（UNINITIALIZED / ASSET_CHECKING /
  ASSET_DOWNLOADING / PROBING / DEGRADED / READY / BUSY / UNAVAILABLE /
  CLOSING / CLOSED），状态×操作门禁表由 :session 模块实现（M1）；
- 统一原语：exec（argv 数组，exitCode==0 才算成功）/ pty /
  fileIn / fileOut / mount / state / close —— 全部携带 SessionGrant；
- 限额事实源：maxMemoryMb=2048 / execTimeoutSec=1800 / 单流输出 1MB /
  maxConcurrentExec=1；消费方只能收紧不能越权提高；
- 宿主签发 SessionGrant（五元组 + TTL ≤300s + nonce 一次性），
  经 PluginBroker 中介下发受限 ILinuxSession 代理。

## 四、模块地图（ARC-100 §4）

| 模块 | 形态 | 职责 | 里程碑 |
| --- | --- | --- | --- |
| `:app` | Android | LinuxRuntimeService（exported · grant 校验）/ 前台服务 / 权限声明 / AIDL 编译 | M0 骨架已入 |
| `:runtime-api` | 纯 JVM | 契约模型 + AIDL 单一事实源 + 能力描述 JSON + 统一错误码表 | M0 已入 |
| `:session` | 纯 JVM | RuntimeSession 状态机 / 引用计数 / 限额 / 工作区路径断言 | M1 |
| `:asset` | 纯 JVM + Android 下载 | 清单解析 / 分卷校验 / 断点续传 / 配额 LRU / ledger | M1 |
| `:probe` | 纯 JVM + JNI 探测 | ptrace / memlock / arch / 存储 / libc 探测与降级决策 | M1 |
| `:shizuku` | Android 库 | 唯一授权检测与 shell 上下文（Shizuku AAR 只进此模块） | M1 |
| `:domain-proot` | Android 库 + 原生库 | PRoot 嵌入 / Alpine rootfs 解包 / 启动链路 | M1 |
| `:domain-qemu` | Android 库 + 原生库 | TCG 拉起 / qcow2 / VNC 回环 / 快照 / 9p 挂载 | M3 |

模块纪律沿用主仓 ARC-002：每个 include 必须有 `build.gradle.kts` 与至少
一个源文件，禁止空模块；`repositoriesMode = FAIL_ON_PROJECT_REPOS`；
JDK 17 配置期人话报错。

## 五、与主壳及消费方的契约（APP-100 / ARC-102）

| 事项 | 约定 |
| --- | --- |
| 插件形态 | 独立 APK（`abyss-plugin-linux`，id = 仓库名） |
| HOST_API | v2（minHost=2；v1 宿主加载本插件 fail-closed 并提示升级） |
| 能力 | `provides=[linux-runtime-v1]`（since 0.2.0 · stable） |
| 消费方式 | 消费方 `depends` 声明 → 宿主 PluginBroker 四重校验 → 受限 ILinuxSession 代理 |
| 安装序 | `[abyss-plugin-linux → abyss-plugin-app-builder]`（拓扑序，宿主侧解析） |
| 补丁 | `patches/index.json`（abyss-plugin-patches-v1，仅小数据） |
| 大资产 | `assets/index.json`（abyss-asset-v1，assetChannel 声明） |
| Shizuku | 唯一 per-app 授权持有方（ownAuth=true，详情页引导单独授权一次） |
| 权限 | INTERNET / FOREGROUND_SERVICE / WAKE_LOCK（**不申请** REQUEST_INSTALL_PACKAGES） |
| 直连 | 消费方直连 service 一律 `E_DIRECT_BIND_DENIED`；无 grant bind 返回 null |

## 六、安全边界（SEC-100 摘要）

- 三层信任链：发布签名同发布者指纹（CI apksigner 实测）+ 渠道白名单
  （URL 域白名单 + 重定向复检）+ 运行时令牌（SessionGrant 五元组）；
- `LinuxRuntimeService` exported 但 `onBind` 校验凭证，无凭证返回 null；
- 指令安全：`ExecCommand` argv 数组（结构性消除注入面）；探测/启动模板
  集中 `LinuxCommands.kt`；免引用参数 `requireSafeToken`
  （白名单 `[A-Za-z0-9._:-]`）+ 自由文本 `shellQuote` 单引号转义；
- 工作区 canonical 断言（穿越 / 绝对路径 / 软链逃逸一律拒）；
- VNC 仅绑 127.0.0.1 + 会话级随机口令，关闭即失效；
- 审计数据最小化：只记 sessionId / capability / 错误码 / 阶段 / 耗时，
  禁记命令正文与项目内容（SEC-009）。
