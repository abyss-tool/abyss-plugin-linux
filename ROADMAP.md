# 🐧 Linux 运行环境 · 路线图

> 对齐主仓规格 `PLAN-100`（M0–M4 里程碑）与 `ROADMAP` 演进纪律：
> 先 PRoot 域、再跨插件能力绑定、再 QEMU 域、最后资产差量收口。

- [ ] v0.2 PRoot 域（M1 · T-012~T-018）：`:session` 状态机/引用计数/工作区断言 ✅、`:asset` 契约/账本/配额/续传 ✅、`:probe` 降级决策树 ✅、`:shizuku` 授权状态机+指令编排 ✅、`:domain-proot` argv 组装 ✅、`:domain-qemu` 档位/argv ✅（纯 JVM 面）；待接线：引擎原生库 / Android 侧 Binder / 下载实现
- [ ] v0.3 跨插件能力绑定（M1 出口）：宿主 `PluginBroker` 握手打通、`SessionGrant` 签发 / 校验 / 撤销、受限 `ILinuxSession` 代理经宿主中介下发、E2E-01（`echo ok` / `apk --version`）跑通
- [ ] v0.4 QEMU 域（M3 · T-029~T-035）：TCG arm64 引擎集成与档位、qcow2 镜像资产、9p/virtiofs 工作区挂载、VNC 回环 + 随机口令、快照与 `ResourceLimits` 打通、切域确认弹层数据
- [ ] v0.5 资产差量与收口（M4）：vcdiff 差量更新（可选）、配额 LRU 与清理审计收口、性能基线实测达标、`abyss-plugin-virtual-machine` 能力承接公告的最终验证

## 已知风险清单（落地时逐项验证 · PLAN-101）

- [ ] ColorOS 15/16 ptrace 约 1 分钟被杀 → Shizuku uid2000 上下文 + 探测降级链（LIN-001 C-8 / LIN-005）
- [ ] 荣耀 MagicOS 严格 SELinux 拒绝 ptrace → 同上；`allowDomainFallback` + 用户确认切 QEMU_VM
- [ ] aapt2 glibc / musl / Bionic 混用 `undefined symbol` → ARM64 专用编译 + 底座内 `ldd` 全量验证门禁（LIN-001 C-1）
- [ ] 手机端 Gradle OOM → JVM 堆 2048m / workers 2 / vfs.watch=false / OOM 降档（LIN-001 C-4）
- [ ] QEMU TCG 构建耗时 3–8 倍于 PRoot → 默认不切域、MID 档 `workers.max=1`、如实提示（LIN-002）
- [ ] 数 GB 镜像下载中断 → 分卷断点续传 + 配额 + 存储预检（LIN-003）
- [ ] Binder 死亡 / grant 竞态 → DeathRecipient 双向注册 + TTL + 撤销 + E2E-12（ARC-102 FR-006/007）

## 工程接入清单

- [x] 插件仓骨架 + HOST_API v2 manifest（M0 ✅ v0.2.0）
- [x] `abyss-asset-v1` 资产清单与 CI 门禁（M0 契约部分 ✅ v0.2.0）
- [x] Gradle 多模块骨架与纯 JVM 契约单测（M0 ✅ v0.2.0）
- [ ] APK 构建流水线（GitHub Actions 固定签名 + apksigner 指纹门禁，M1 接入 secrets）
- [ ] 首个 rootfs 资产入库（alpine-rootfs arm64 分卷，M1 发布管线）
- [ ] E2E-01 留痕（宿主 + 本插件开会话执行 `echo ok`，M1 出口判据）
