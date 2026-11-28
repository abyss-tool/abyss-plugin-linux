#!/usr/bin/env python3
"""插件仓 CI 门禁：plugin.json / CHANGELOG / 补丁通道三处一致性校验。

Linux 插件（引擎级 · HOST_API v2）新增校验项：
  - category = tools；minHost = 2（依赖解析与能力绑定需要 v2 宿主）
  - shizuku 独立授权声明 —— 本插件是全生态**唯一**授权持有方
    （ownAuth + reason ≥ 10 字符，LIN-005 FR-001）
  - provides 至少含 linux-runtime-v1（DC-100 §3：capability 形态
    ^[a-z0-9-]+-v[0-9]+$，主版本变化必须新开 id）
  - capabilities 能力描述（DC-101 §2：双执行域 / arm64 / 限额 /
    grantTtlSec ≤ 300）
  - assetChannel 指向本仓 assets/index.json（abyss-asset-v1 通道）
  - depends（若声明）逐条形态校验（本插件当前不声明任何依赖）
"""
import json
import re
import sys
from pathlib import Path

SEMVER = re.compile(r"^\d+\.\d+\.\d+$")
REQUIRED = ("id", "name", "repo", "category", "version", "minApp", "summary")
CATEGORIES = {"terminal", "system", "sandbox", "disguise", "media",
              "automation", "desktop", "network", "data", "tools", "dev"}
CAPABILITY_ID = re.compile(r"^[a-z0-9-]+-v[0-9]+$")
ASSET_CHANNEL = re.compile(
    r"^https://raw\.githubusercontent\.com/abyss-tool/abyss-plugin-linux/main/assets/index\.json$"
)
PATCH_CHANNEL = re.compile(
    r"^https://raw\.githubusercontent\.com/abyss-tool/abyss-plugin-linux/main/patches/index\.json$"
)
DOMAINS = {"PROOT_USERLAND", "QEMU_VM"}

errors = []


def fail(msg):
    errors.append(msg)


manifest = json.loads(Path("plugin.json").read_text(encoding="utf-8"))
repo_full = Path(".git/config").read_text(encoding="utf-8")
expected_repo = None
for line in repo_full.splitlines():
    if "url = https://github.com/" in line:
        expected_repo = line.split("github.com/")[1].removesuffix(".git").strip()
        break
if expected_repo is None:
    expected_repo = __import__("os").environ.get("GITHUB_REPOSITORY", "")

for field in REQUIRED:
    if not manifest.get(field):
        fail(f"plugin.json 缺少必填字段 {field}")

pid = manifest.get("id", "")
if not re.match(r"^abyss-plugin-[a-z0-9]+(-[a-z0-9]+)*$", pid):
    fail(f"插件 id 形态非法 → {pid!r}")
if expected_repo and manifest.get("repo") != expected_repo:
    fail(f"plugin.json.repo({manifest.get('repo')}) 与仓库({expected_repo})不一致")
if expected_repo and not expected_repo.endswith(pid):
    fail("仓库名末段应等于插件 id")
if manifest.get("category") not in CATEGORIES:
    fail(f"category 非法 → {manifest.get('category')!r}")
for key in ("version", "minApp"):
    if not SEMVER.match(manifest.get(key, "")):
        fail(f"plugin.json.{key} 非法 SemVer → {manifest.get(key)!r}")
if manifest.get("minHost") != 2:
    fail("plugin.json.minHost 必须为 2（linux-runtime-v1 能力依赖 HOST_API v2 的依赖解析与能力绑定）")

# ── Shizuku 统一授权声明（本插件必填；全生态唯一持有方）──────────────
shizuku = manifest.get("shizuku")
if not isinstance(shizuku, dict):
    fail("plugin.json.shizuku 声明缺失 —— Linux 引擎（PRoot/QEMU）经 Shizuku uid2000 上下文拉起，必须向用户明示唯一授权")
else:
    if shizuku.get("ownAuth") is not True:
        fail("plugin.json.shizuku.ownAuth 必须为 true（本插件为独立 APK，且是唯一授权持有方）")
    if not shizuku.get("reason") or len(shizuku.get("reason", "")) < 10:
        fail("plugin.json.shizuku.reason 缺失或过短（<10 字符）—— 需向用户解释为什么单独授权一次")

# ── provides：至少含 linux-runtime-v1（DC-100 §3）────────────────────
provides = manifest.get("provides")
if not isinstance(provides, list) or not provides:
    fail("plugin.json.provides 缺失或为空 —— 引擎级插件必须声明对外能力")
else:
    caps = set()
    for item in provides:
        if not isinstance(item, dict):
            fail(f"provides 条目应为对象 → {item!r}")
            continue
        cap = item.get("capability", "")
        if not CAPABILITY_ID.match(cap):
            fail(f"provides.capability 形态非法 → {cap!r}（应为 ^[a-z0-9-]+-v[0-9]+$）")
        if cap in caps:
            fail(f"provides.capability 重复 → {cap!r}")
        caps.add(cap)
        if item.get("since") is not None and not SEMVER.match(str(item.get("since"))):
            fail(f"provides[{cap}].since 非法 SemVer → {item.get('since')!r}")
        if not isinstance(item.get("stable", True), bool):
            fail(f"provides[{cap}].stable 应为布尔")
    if "linux-runtime-v1" not in caps:
        fail("provides 必须包含 linux-runtime-v1（当前唯一注册能力）")

# ── capabilities 能力描述（DC-101 §2）────────────────────────────────
capabilities = manifest.get("capabilities")
if not isinstance(capabilities, dict):
    fail("plugin.json.capabilities 缺失或不是对象 —— 能力描述是消费方预检的数据源")
else:
    desc = capabilities.get("linux-runtime-v1")
    if not isinstance(desc, dict):
        fail("capabilities 缺少 linux-runtime-v1 描述")
    else:
        domains = desc.get("domains")
        if not isinstance(domains, list) or not domains:
            fail("capabilities.linux-runtime-v1.domains 应为非空数组")
        else:
            ids = {d.get("id") for d in domains if isinstance(d, dict)}
            if not DOMAINS.issubset(ids):
                fail(f"domains 必须同时含 PROOT_USERLAND 与 QEMU_VM → {sorted(ids)}")
            for d in domains:
                if not isinstance(d, dict):
                    fail(f"domains 条目应为对象 → {d!r}")
                    continue
                if not d.get("kernel") in ("shared-android", "guest-linux"):
                    fail(f"domains[{d.get('id')}].kernel 非法 → {d.get('kernel')!r}")
                if not isinstance(d.get("coldStartMs"), int) or d.get("coldStartMs", 0) < 0:
                    fail(f"domains[{d.get('id')}].coldStartMs 应为非负整数")
                feats = d.get("features")
                if not isinstance(feats, list) or not feats:
                    fail(f"domains[{d.get('id')}].features 应为非空数组")
        arch = desc.get("arch")
        if not isinstance(arch, list) or "arm64" not in arch:
            fail("capabilities.linux-runtime-v1.arch 必须含 arm64")
        limits = desc.get("limits")
        if not isinstance(limits, dict) or not limits:
            fail("capabilities.linux-runtime-v1.limits 缺失 —— 消费方不得越权提高限额的事实源")
        auth = desc.get("auth")
        if not isinstance(auth, dict):
            fail("capabilities.linux-runtime-v1.auth 缺失")
        elif auth.get("mediator") != "PluginBroker":
            fail("auth.mediator 必须为 PluginBroker（插件间永不直连，ARC-102 §1）")
        elif not isinstance(auth.get("grantTtlSec"), int) or auth.get("grantTtlSec", 0) > 300:
            fail("auth.grantTtlSec 应为 ≤300 的整数（ARC-102 FR-005）")
        elif auth.get("shizuku") != "ownAuth":
            fail("auth.shizuku 必须为 ownAuth")
        if desc.get("hostApi") != 2:
            fail("capabilities.linux-runtime-v1.hostApi 必须为 2")

# ── depends（本插件为纯提供方；若未来声明则逐条校验）────────────────
depends = manifest.get("depends")
if depends is not None:
    if not isinstance(depends, list):
        fail("plugin.json.depends 应为数组")
    else:
        for item in depends:
            if not isinstance(item, dict):
                fail(f"depends 条目应为对象 → {item!r}")
                continue
            dep_id = item.get("pluginId", "")
            if not re.match(r"^[a-z0-9]+(-[a-z0-9]+)+$", dep_id):
                fail(f"depends[].pluginId 形态非法 → {dep_id!r}")
            if not CAPABILITY_ID.match(item.get("capability", "")):
                fail(f"depends[{dep_id}].capability 形态非法 → {item.get('capability')!r}")
            if not item.get("versionRange"):
                fail(f"depends[{dep_id}].versionRange 缺失（统一 SemVer 区间比较，禁止 split('.')）")
            if item.get("optional") is False and (not item.get("reason") or len(item.get("reason", "")) < 10):
                fail(f"depends[{dep_id}].optional=false 时 reason 必填且 ≥10 字符")

# ── assetChannel / patchChannel（通道纪律）────────────────────────────
if not ASSET_CHANNEL.match(manifest.get("assetChannel", "")):
    fail(f"assetChannel 应指向本仓 assets/index.json → {manifest.get('assetChannel')!r}")
if not PATCH_CHANNEL.match(manifest.get("patchChannel", "")):
    fail(f"patchChannel 形态非法 → {manifest.get('patchChannel')!r}")

changelog = Path("CHANGELOG.md").read_text(encoding="utf-8")
if f"## v{manifest.get('version')}" not in changelog:
    fail(f"CHANGELOG.md 缺少当前版本条目 ## v{manifest.get('version')}")

# ── NG-8（深挖第三轮）：APK versionName 与 plugin.json 三源钉一 ──────────
# 旧实现从不校验 Gradle 侧版本 —— patch-release 只 bump plugin.json 时
# APK versionName 静默漂移，宿主 getRuntimeInfo() 与插件中心 / 依赖解析
# 读到两个不同版本（E_DEP_VERSION 判定分叉）。make_patch.py 已同步升版，
# 此门禁保证漂移不可能无声发生。
_gradle = Path("app/build.gradle.kts")
if not _gradle.exists():
    fail("app/build.gradle.kts 缺失（APK 模块是插件本体，不可缺席）")
else:
    _m = re.search(r'versionName\s*=\s*"([^"]+)"', _gradle.read_text(encoding="utf-8"))
    if _m is None:
        fail("app/build.gradle.kts 未声明 versionName（模板漂移，请人工核对）")
    elif _m.group(1) != manifest.get("version"):
        fail(
            f"版本三源不一致：app/build.gradle.kts versionName({_m.group(1)}) "
            f"≠ plugin.json.version({manifest.get('version')}) —— APK 与插件中心"
            f"将读到不同版本（运行时版本判定分叉）"
        )

channel = json.loads(Path("patches/index.json").read_text(encoding="utf-8"))
if channel.get("schema") != "abyss-plugin-patches-v1":
    fail("patches/index.json schema 应为 abyss-plugin-patches-v1")
if channel.get("plugin") != pid:
    fail(f"patches/index.json.plugin({channel.get('plugin')}) 与 id 不一致")
if channel.get("latest") != manifest.get("version"):
    fail(f"patches/index.json.latest({channel.get('latest')}) 与 version 不一致")
seen = set()
for p in channel.get("patches", []):
    key = (p.get("from"), p.get("to"))
    if key in seen:
        fail(f"补丁段重复 {key}")
    seen.add(key)
    if not p.get("url"):
        fail(f"补丁 {key} 缺 url")

if errors:
    print(f"[plugin-ci] ✗ {len(errors)} 处校验失败：", file=sys.stderr)
    for e in errors:
        print(f"  - {e}", file=sys.stderr)
    sys.exit(1)
print(f"[plugin-ci] ✓ {pid} v{manifest.get('version')} 校验通过"
      f"（HOST_API v2 · provides linux-runtime-v1 · shizuku 唯一授权 · "
      f"{len(provides) if provides else 0} 能力 · {len(channel.get('patches', []))} 条补丁记录）")
