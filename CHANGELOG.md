# CHANGELOG · abyss-plugin-linux

> 遵循「一版一条」纪律：每个发布版本一个 `## v*` 条目。

## v0.2.0（2026-11-22）

**M0 契约与骨架首版落地**（HOST_API v2）。

- **新建仓库**：按 `ADDING_PLUGINS.md` 7 步接入插件中心；`plugin.json` v2 形态 —— `minHost=2`、`provides=[linux-runtime-v1]`（since 0.2.0 / stable）、`capabilities` 能力描述（双执行域 / arm64 / 限额 / 授权中介）、`assetChannel` 指向 `abyss-asset-v1` 大资产通道。
- **Shizuku 统一授权**：本插件为全生态唯一 `shizuku.ownAuth` 持有方（原应用构建器与虚拟机插件的两次授权收敛为一次）。
- **资产契约**：`assets/index.json`（`abyss-asset-v1` schema 定稿，首个资产随 M1 发布管线入库）；`tools/validate_assets.py` CI 门禁（字段完整性 / 64 位 sha256 / 分卷连续性 / URL 域白名单 / minPlugin 语义 / 模板许可纪律）。
- **Gradle 多模块骨架**：`:app`（插件 APK · `LinuxRuntimeService` 骨架 + AIDL 契约编译）与 `:runtime-api`（纯 JVM 契约模型：RuntimeDomain / SessionState / Feature / SessionSpec / ExecCommand / SessionGrant / RuntimeInfo / 统一错误码表 / 能力描述 JSON 解析，全部可 `./gradlew test` 纯 JVM 单测）。AIDL 单一事实源位于 `:runtime-api/src/main/aidl`，`:app` 经 sourceSet 引用编译，禁止拷贝（DC-101 AC-004）。
- **CI**：`plugin-ci.yml`（validate_plugin + validate_assets + Gradle assembleDebug/Release + 单测 + apksigner 指纹门禁可选步）。
- **架构文档**：`docs/ARCHITECTURE.md` 承接自应用构建器仓迁入的「底座层」章节并扩写双执行域与模块地图（ADR-100 D-1/D-2）。

对齐规格：`ADR-100` / `SRS-102` / `ARC-100` / `ARC-101` / `DC-100` / `DC-101` / `DC-102` / `ENG-100` / `LIN-003`（契约部分）。

## v0.3.0（2026-11-28）

> 深挖第三轮加固（G-1 门禁修正 + 工具链门禁三源钉一 + M1 纯 JVM 模块批次）。

**M0 阻塞项 G-1 收口（ARC-104 §6，2026-11-28）：exported 服务的调用方签名硬门禁**

- `:runtime-api` 新增 `HostCallerTrust`（纯 JVM）：调用方可信 = uid 包集合**恰为**宿主包 `com.abyss.tools` ∧ 签名证书 SHA-256 命中宿主发布者指纹（与主仓 `PublisherSignatures.PINNED_SHA256` 同一常量，冒号 / 大小写形态归一化）；共享 uid 多包、第三方包、空签名、非法形态一律不可信（fail-closed，不读 Intent extras、不接受自报包名）
- `:app` 新增 `HostCallerGuard`：`Binder.getCallingUid()` → `getPackagesForUid` → `SigningInfo`（API 28+，`GET_SIGNATURES` 双标志兜底 26/27）→ 摘要比对；`LinuxRuntimeBinder` 的 `ping` / `getRuntimeInfo` / `openSession` 每个入口先 `requireTrustedCaller()`，第三方调用抛 `E_DIRECT_BIND_DENIED`（人话提示 + AILD 传播通道与 openSession 一致）；guard 未接线时 fail-closed 拒绝
- 校验点在 AIDL 方法体而非 `onBind`：onBind 由 system_server 转发，`Binder.getCallingUid()` 取到的是系统 uid；onBind 保留 action 校验
- 不采用 Manifest `android:permission` 指向宿主声明的 signature 级权限作为主门禁（自定义权限名无归属保证，存在 permission squatting 风险）；进程内签名校验不依赖安装顺序，后续可与该层叠加
- 单测：`HostCallerTrustTest` 7 例（宿主指纹命中 / 冒号形态等价 / 共享 uid / 第三方直连 / 指纹不符 / 空与非法形态 / 证书摘要互逆）

**深挖第三轮 G-1 修正（NG-1~NG-9，2026-11-28）**

- `NG-1`：`HostCallerGuard` 签名读取按 `Build.VERSION.SDK_INT >= 28` 分支 —— 旧实现直读 `PackageInfo.signingInfo`（API 28+ 字段），在 minSdk 26/27 的 Android 8.x 上 `getfield` 抛 `NoSuchFieldError` 被 runCatching 吞成空签名集，**合法宿主被全量拒绝**（fail-closed 方向对但把真宿主关在门外）；现 26/27 走 `signatures` 旧字段
- `NG-2`：签名集**只认当前签名者**（apkContentsSigners / signatures）—— 不再以 `hasMultipleSigners()` 为条件并入 `signingCertificateHistory`（语义错位：正确条件是 hasPastSigningCertificates，且多签名者场景 history 恒为 null）；历史 / 轮换证书不参与可信判定，宿主轮换时双方 pin 同步更新
- `NG-3`：Guard 改构造注入（uid→包集合 / 包→摘要集两个查询函数）—— 判定链（uid 反查 → 摘要汇集 → isTrustedHost）可纯 JVM 全量断言；新增 `HostCallerGuardTest` 7 例（第三方 / 共享 uid / 指纹不符 / 多签名者任一命中 / 查询异常 / 未知 uid / 摘要形态锚）
- `NG-4`：异常 message 携 `[E_XXX]` 码前缀（`Diagnostic.machineHint`）—— 跨进程调用方按码路由而非字符串匹配人话；`E_DIRECT_BIND_DENIED` 改抛 `SecurityException`（语义对齐），`E_RUNTIME_UNAVAILABLE` 同步前缀化
- `NG-5`：`LinuxRuntimeService` 版本兜底 `"0.2.0"` 硬编码改诚实 `"0.0.0"` —— 三源漂移面收敛为 CI 钉一
- `NG-6`：`DomainProbe.parseOutcome` 移除 manufacturer 前缀先验否决 —— 旧实现把 ColorOS < 15 的 OPPO/OnePlus/Realme 与未受限荣耀机型整套拒掉（规格语义是「ColorOS 15/16」版本级清单）；探测输出为唯一判定权威，清单降级为 romHint 预警（版本级细化留 M1 T-015 系统属性指纹）；顺手修正 `honhor` 拼写死前缀
- `NG-7`：`LedgerEntry` 增 `type` 字段、`QuotaPolicy` 的「至少保留 1 个可用 rootfs」按种类计数 —— 旧实现数全部 READY 条目，混合资产（rootfs + image/toolchain）下唯一 rootfs 可被 LRU 误回收（数 GB 重下）；新增混合资产回归锚
- `NG-8`：版本三源钉一 —— `make_patch.py` 同步升版 `app/build.gradle.kts versionName`、`validate_plugin.py` 新增 versionName == plugin.json.version 断言（旧实现从不校验 Gradle 侧，APK 与插件中心读到不同版本）
- `NG-9`：`make_patch.py` 对已存在的 `## v<to>` 条目直接 die —— 旧实现静默跳过写入，工作树预填条目让发布 notes 分叉且门禁绿灯

**M1 核心模块落地（PLAN-100 T-012~T-018 的纯 JVM 面）。**

- `:session`：RuntimeSession 状态机（10 状态 + 迁移表 + 状态×操作门禁，ARC-101 §2）、工作区 canonical 断言器（穿越/绝对路径/软链逃逸拒收）、引用计数引擎（60s 延迟回收 + 复用取消回收）
- `:asset`：abyss-asset-v1 契约模型（整像 + 分卷双层 sha256 / 分卷连续性 / 许可必填 / URL 域白名单）、账本（9 状态）、配额 LRU 清理计划（90% 水位 / 引用保护 / ≥1 rootfs 保留 / 审计记录）、断点续传计划
- `:probe`：探测降级决策树（uid2000 / app_sandbox / NEED_SHIZUKU / PLATFORM_BLOCKED 标签，LIN-004 §5.4）+ 已知受限 ROM 清单 + 探测输出 fail-closed 解析（承接自应用构建器 m1-scaffold 的 DegradeChain/BaseProbe 设计，ADR-100 D-3 / ARC-103 M-2 迁出署名）
- `:shizuku`：授权状态机（5 状态转移纪律）+ `LinuxCommands` 指令编排（argv 数组纪律 / `requireSafeToken` 白名单 `[A-Za-z0-9._:-]` 就地拒绝 / `shellQuote` 单引号转义）
- `:domain-proot`：PRoot 启动 argv 组装（-R/-0/-w/-b + argv 命令）+ 会话环境白名单
- `:domain-qemu`：三档位（LOW/MID/HIGH）+ 60% 内存预算 + HIGH 4GB 门槛 + VNC 回环策略 + 快照元数据 + qemu argv 组装（TCG/virt/9p/slirp）

对齐规格：`ARC-101` / `LIN-001~005` / `DC-102` / `PLAN-100 T-012~T-018`。
