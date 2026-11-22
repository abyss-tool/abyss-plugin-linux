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
