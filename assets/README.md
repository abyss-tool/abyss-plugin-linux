# 大资产通道（abyss-asset-v1）

本目录是 Linux 插件的**大资产发布通道**（rootfs / VM 镜像 / 工具链 /
引擎原生库），与 `patches/`（abyss-patch-v1 小数据补丁）严格分离
（ADR-011 / ADR-100 D-6）。

- `index.json`：资产清单单一事实源（`abyss-asset-v1` schema，见
  主仓规格 `DC-102`）。CI 用 `tools/validate_assets.py` 校验。
- 首个资产（`alpine-rootfs` arm64 分卷）随 M1 发布管线入库：
  CI 生成分卷 → 逐卷 + 整像双层 sha256 → 上传 GitHub Release →
  回填本清单（`generatedAt` / `minPlugin` 同步滚动）。
- **许可纪律**：所有可分发资产必须在条目 `license` 字段列明许可，
  Windows 类客户机镜像 `distributable=false`（仅用户自备，ADR-008
  「镜像只做通路不做信任源」）。
- **URL 白名单**：分卷与整像 URL 仅允许
  `raw.githubusercontent.com` / `github.com` /
  `objects.githubusercontent.com` / `release-assets.githubusercontent.com`
  四域，重定向后复检 host（SEC-002 / LIN-003 F-2）。
