# 🐧 Linux 运行环境（abyss-plugin-linux）

> 深渊工具的引擎级插件：自包含 Linux 运行环境 —— PRoot 用户态（复用
> Android 内核的 Alpine 用户空间）+ QEMU 完整系统（自带 Linux 内核）
> 双执行域，统一 `RuntimeSession` 会话抽象，唯一持有 rootfs / 镜像 /
> 执行引擎与 Shizuku 授权，对外提供 `linux-runtime-v1` 能力。

状态：骨架期（v0.2.0 · HOST_API v2）。规格依据 `Abyss-Tools` 仓
`docs/abyss-specs/11-Linux插件解耦/`（ADR-100 / ARC-100~103 /
LIN-001~005 / DC-100~102 等 23 份子规格）。

## 定位

- **唯一引擎持有方**：应用构建器等消费方经宿主 `PluginBroker` 中介依赖
  本插件（`depends` 声明 + 受限 `ILinuxSession` 代理），永不直连。
- **双执行域**：`PROOT_USERLAND`（冷启动 ≤8s）默认；`QEMU_VM`（冷启动
  ≤180s）为显切换的兼容域。切域 = 关旧会话 + 开新会话（用户确认）。
- **资产通道**：rootfs / 镜像 / 工具链走 `abyss-asset-v1` 大资产通道
  （分卷 sha256 + 整像 sha256 + 断点续传 + 8GB 配额），与
  `abyss-patch-v1` 数据补丁通道严格分离。
- **Shizuku 统一授权**：本插件一次授权（per-app），消费方不再单独申请。

## 仓库结构

```
plugin.json                插件 manifest（v2：provides/depends/capabilities/assetChannel）
assets/index.json          abyss-asset-v1 资产清单（rootfs / 镜像 / 工具链）
patches/index.json         数据补丁通道（仅小数据，abyss-plugin-patches-v1）
tools/                     validate_plugin / validate_assets / make_patch / publish_patch
docs/ARCHITECTURE.md       架构（底座章节自 app-builder 迁入 + 双执行域扩写）
:app / :runtime-api / :session / :asset / :probe / :shizuku /
:domain-proot / :domain-qemu   Gradle 模块（见 docs/ARCHITECTURE.md 模块地图）
```

详细路线见 [ROADMAP.md](ROADMAP.md)；设计规格见主仓规格集。
