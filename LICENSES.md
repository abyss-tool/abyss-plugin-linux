# 第三方组件许可清单（LICENSES.md）

> 本插件自包含 Linux 运行环境引擎，按 LIN-003 F-9 / SEC-100 许可合规
> 纪律逐项列明。资产条目的 `license` 字段与本清单同步维护。

| 组件 | 用途 | 许可 | 分发形态 |
| --- | --- | --- | --- |
| PRoot（proot-distro-nolib 衍生） | PROOT_USERLAND 域 ptrace 用户态 chroot 模拟 | GPL-2.0-or-later | 原生库随 APK（engine-native 资产） |
| Alpine Linux minirootfs（arm64 / musl） | PROOT_USERLAND 域用户空间 | Alpine Linux（MIT & GPL 组件，见其 LICENSES） | rootfs 大资产（abyss-asset-v1 分卷） |
| QEMU（qemu-system-aarch64 TCG） | QEMU_VM 域完整系统引擎 | GPL-2.0-only | 原生库随 APK（engine-native 资产） |
| Alpine Linux qcow2 镜像 | QEMU_VM 域客户机盘 | Alpine Linux（MIT & GPL 组件） | image 大资产（用户可自备等价镜像） |
| musl libc | Alpine 用户空间 libc | MIT | 随 rootfs 分发 |
| busybox | Alpine 基础用户工具 | GPL-2.0-or-later | 随 rootfs 分发 |
| Shizuku API（dev.rikka.shizuku:api） | uid 2000 shell 上下文客户端接口 | Apache-2.0 | Maven Central AAR（仅 :shizuku 模块） |
| Windows 客户机镜像 | — | **不分发**（仅用户自备，模板 `distributable=false`） | 无（N-VM-004 FR-002 / ADR-008） |

GPL 组件以独立进程（PRoot/QEMU）形态运行、经进程边界与 IPC 契约交互，
不与本插件闭源部分静态/动态链接。上游许可全文随对应 Release 资产归档。
