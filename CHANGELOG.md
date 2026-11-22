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

## v0.3.0（2026-11-22 · 未发布——工作树预填）

> M1 模块批次（随下次发版滚动；CHANGELOG 先行登记工作进展）。

**M1 核心模块落地（PLAN-100 T-012~T-018 的纯 JVM 面）。**

- `:session`：RuntimeSession 状态机（10 状态 + 迁移表 + 状态×操作门禁，ARC-101 §2）、工作区 canonical 断言器（穿越/绝对路径/软链逃逸拒收）、引用计数引擎（60s 延迟回收 + 复用取消回收）
- `:asset`：abyss-asset-v1 契约模型（整像 + 分卷双层 sha256 / 分卷连续性 / 许可必填 / URL 域白名单）、账本（9 状态）、配额 LRU 清理计划（90% 水位 / 引用保护 / ≥1 rootfs 保留 / 审计记录）、断点续传计划
- `:probe`：探测降级决策树（uid2000 / app_sandbox / NEED_SHIZUKU / PLATFORM_BLOCKED 标签，LIN-004 §5.4）+ 已知受限 ROM 清单 + 探测输出 fail-closed 解析（承接自应用构建器 m1-scaffold 的 DegradeChain/BaseProbe 设计，ADR-100 D-3 / ARC-103 M-2 迁出署名）
- `:shizuku`：授权状态机（5 状态转移纪律）+ `LinuxCommands` 指令编排（argv 数组纪律 / `requireSafeToken` 白名单 `[A-Za-z0-9._:-]` 就地拒绝 / `shellQuote` 单引号转义）
- `:domain-proot`：PRoot 启动 argv 组装（-R/-0/-w/-b + argv 命令）+ 会话环境白名单
- `:domain-qemu`：三档位（LOW/MID/HIGH）+ 60% 内存预算 + HIGH 4GB 门槛 + VNC 回环策略 + 快照元数据 + qemu argv 组装（TCG/virt/9p/slirp）

对齐规格：`ARC-101` / `LIN-001~005` / `DC-102` / `PLAN-100 T-012~T-018`。
