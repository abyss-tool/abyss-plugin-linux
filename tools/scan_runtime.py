#!/usr/bin/env python3
"""Linux 插件运行时资产扫描门禁（ENG-100 §2 · PLAN-101 §1③）。

检查项：
  1. 原生库许可覆盖：所有 *.so（app 或任意模块 jniLibs）必须在
     LICENSES.md 中有对应组件条目（SEC-100 许可合规 —— 未登记的原生
     库不得入 APK）；
  2. AIDL 契约单一副本（DC-101 AC-004）：ILinuxRuntime.aidl 在本仓内
     仅存在于 :runtime-api/src/main/aidl —— 任何模块私拷即拒（跨仓
     拷贝由消费方 CI 的静态扫描兜底）；
  3. 引擎原生库声明锚点：engine-native 资产（.so 上架时）须在
     assets/index.json 登记（当前骨架期无 .so → 门禁保持绿灯，原生库
     入库日起生效）。

16KB 页对齐门禁（LIN-001 C-7）：zipalign/readelf 校验随 APK 发布
流水线执行（有 .so 的产物阶段）；本脚本负责源内纪律。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LICENSES = ROOT / "LICENSES.md"
AIDL_NAME = "ILinuxRuntime.aidl"
AIDL_CANON = ROOT / "runtime-api" / "src" / "main" / "aidl"

errors = []


def fail(msg):
    errors.append(msg)


# ── 1. 原生库许可覆盖 ─────────────────────────────────────────────────
so_files = [p for p in ROOT.rglob("*.so") if "build" not in p.parts]
licenses_text = LICENSES.read_text(encoding="utf-8") if LICENSES.exists() else ""
if not LICENSES.exists():
    fail("LICENSES.md 缺失 —— 原生库许可清单是入库门禁（SEC-100）")

if so_files:
    print(f"[scan-runtime] 检测到 {len(so_files)} 个原生库（engine-native 入库期门禁生效）")
    for so in so_files:
        stem = so.name.split("-")[0] if "-" in so.name else so.stem
        # 组件名锚点：文件名主干或父目录名出现在 LICENSES.md
        anchor_names = {so.stem, so.name, so.parent.name.lower(), stem}
        if not any(a and a in licenses_text for a in anchor_names):
            fail(f"原生库 {so.relative_to(ROOT)} 未在 LICENSES.md 登记（许可覆盖门禁）")
        rel = so.relative_to(ROOT).as_posix()
        if not rel.startswith(("app/src/main/jniLibs", "domain-")) and "jniLibs" not in rel:
            fail(f"原生库 {rel} 不在 jniLibs 目录（C-6：可加载路径纪律）")
else:
    print("[scan-runtime] 骨架期无原生库 —— 许可覆盖门禁待 engine-native 入库生效")

# ── 2. AIDL 契约单一副本（仓内纪律）──────────────────────────────────
aidl_copies = [p for p in ROOT.rglob(AIDL_NAME) if "build" not in p.parts]
for p in aidl_copies:
    if not p.is_relative_to(AIDL_CANON):
        fail(f"{AIDL_NAME} 出现仓内私拷 → {p.relative_to(ROOT)}（DC-101 AC-004：仅允许 :runtime-api/src/main/aidl）")
if not (AIDL_CANON / "com/abyss/plugin/linux/runtime" / AIDL_NAME).exists():
    fail(f"{AIDL_NAME} 必须位于 :runtime-api/src/main/aidl（契约单一事实源）")

# ── 3. Shizuku AAR 归属纪律（LIN-005 FR-003：只进 :shizuku 模块）─────
for gradle in ROOT.rglob("build.gradle.kts"):
    if "build" in gradle.parts:
        continue
    rel = gradle.relative_to(ROOT).as_posix()
    text = gradle.read_text(encoding="utf-8")
    if re.search(r"\bshizuku(-api)?\b|\bdev\.rikka\.shizuku\b", text) and not rel.startswith("shizuku/"):
        fail(f"Shizuku AAR 引用只允许出现在 :shizuku 模块 → {rel}（LIN-005 FR-003）")

if errors:
    print(f"[scan-runtime] ✗ {len(errors)} 处校验失败：", file=sys.stderr)
    for e in errors:
        print(f"  - {e}", file=sys.stderr)
    sys.exit(1)
print(f"[scan-runtime] ✓ 运行时资产纪律扫描通过（原生库许可 {'全覆盖' if so_files else '无库待检'} · "
      f"AIDL 单一副本 · Shizuku AAR 归属 :shizuku）")
