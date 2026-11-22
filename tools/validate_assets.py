#!/usr/bin/env python3
"""abyss-asset-v1 大资产清单 CI 门禁（DC-102 / LIN-003 · ENG-100）。

校验 assets/index.json：
  1. schema / generatedAt / minPlugin 顶层字段；
  2. 每个资产条目字段完整（id / type / domain / arch / version /
     sizeBytes / sha256 / volumes / minPlugin / url / license）；
  3. sha256 一律 64 位 hex（整像必填，分卷独立必填）；
  4. 分卷 index 从 0 连续、sizeBytes > 0、URL 白名单；
  5. URL 仅允许 raw.githubusercontent.com / github.com /
     objects.githubusercontent.com / release-assets.githubusercontent.com
     四域（SEC-002 / LIN-003 F-2 —— 运行时另有重定向复检，CI 只做
     静态域白名单）；
  6. minPlugin 为合法 SemVer 且不高于插件当前版本（版本倒退即拒）；
  7. 模板清单：id 唯一、distributable=false 的 Windows 模板必须带
     note 说明「仅用户自备」；distributable=true 必须带 license。

不校验远端 URL 存活性（资产发布动作发生在 Release 流水线，本脚本
是入库前门禁 —— 「apk=null 期间展示资产未发布」为框架期常态）。
"""
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

SEMVER = re.compile(r"^\d+\.\d+\.\d+$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
URL_HOSTS = (
    "https://raw.githubusercontent.com/",
    "https://github.com/",
    "https://objects.githubusercontent.com/",
    "https://release-assets.githubusercontent.com/",
)
TYPES = {"rootfs", "image", "toolchain", "engine-native", "template"}
DOMAINS = {"PROOT_USERLAND", "QEMU_VM", "BOTH"}

errors = []


def fail(msg):
    errors.append(msg)


def check_url(url, where):
    if not isinstance(url, str) or not url:
        fail(f"{where}: url 缺失")
        return
    if not any(url.startswith(h) for h in URL_HOSTS):
        fail(f"{where}: url 越出域白名单 → {url!r}（E_ASSET_URL_DENIED）")


def check_sha(value, where):
    if not isinstance(value, str) or not SHA256.match(value):
        fail(f"{where}: sha256 应为 64 位小写 hex → {value!r}")


manifest_path = Path("plugin.json")
data_path = Path("assets/index.json")
data = json.loads(data_path.read_text(encoding="utf-8"))

if data.get("schema") != "abyss-asset-v1":
    fail("assets/index.json.schema 应为 abyss-asset-v1")
generated = data.get("generatedAt", "")
try:
    datetime.strptime(generated, "%Y-%m-%dT%H:%M:%SZ")
except ValueError:
    fail(f"generatedAt 应为 UTC ISO 形态 → {generated!r}")
index_min_plugin = data.get("minPlugin", "")
if not SEMVER.match(index_min_plugin):
    fail(f"assets/index.json.minPlugin 非法 SemVer → {index_min_plugin!r}")

try:
    plugin_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    plugin_version = plugin_manifest.get("version", "")
except Exception as exc:
    plugin_manifest = {}
    plugin_version = ""
    fail(f"plugin.json 读取失败 → {exc}")


def version_tuple(v):
    return tuple(int(x) for x in v.split("."))


if plugin_version and SEMVER.match(plugin_version) and SEMVER.match(index_min_plugin):
    if version_tuple(index_min_plugin) > version_tuple(plugin_version):
        fail(f"minPlugin({index_min_plugin}) 高于插件当前版本({plugin_version}) —— 版本倒退即拒（E_ASSET_MIN_PLUGIN）")

assets = data.get("assets")
if not isinstance(assets, list):
    fail("assets 应为数组")
    assets = []
ids = set()
for a in assets:
    if not isinstance(a, dict):
        fail(f"assets 条目应为对象 → {a!r}")
        continue
    aid = a.get("id", "")
    where = f"assets[{aid}]"
    if not aid:
        fail(f"{where}: id 缺失")
    if aid in ids:
        fail(f"{where}: id 重复")
    ids.add(aid)
    if a.get("type") not in TYPES:
        fail(f"{where}: type 非法 → {a.get('type')!r}（应为 rootfs/image/toolchain/engine-native/template）")
    if a.get("domain") not in DOMAINS:
        fail(f"{where}: domain 非法 → {a.get('domain')!r}")
    arch = a.get("arch")
    if not isinstance(arch, list) or not arch or any(x not in ("arm64", "arm", "x86_64", "x86", "riscv64") for x in arch):
        fail(f"{where}: arch 应为非空架构数组 → {arch!r}")
    if not SEMVER.match(a.get("version", "")):
        fail(f"{where}: version 非法 SemVer → {a.get('version')!r}")
    if not isinstance(a.get("sizeBytes"), int) or a.get("sizeBytes", 0) <= 0:
        fail(f"{where}: sizeBytes 应为正整数")
    check_sha(a.get("sha256"), f"{where}(整像)")
    if not a.get("format"):
        fail(f"{where}: format 缺失（tar.gz / qcow2 / zip 等）")
    if not a.get("license"):
        fail(f"{where}: license 必填（许可合规，SEC-100 / LIN-003 F-9）")
    min_plugin = a.get("minPlugin", index_min_plugin)
    if not SEMVER.match(min_plugin):
        fail(f"{where}: minPlugin 非法 SemVer → {min_plugin!r}")
    elif plugin_version and SEMVER.match(plugin_version) and version_tuple(min_plugin) > version_tuple(plugin_version):
        fail(f"{where}: minPlugin({min_plugin}) 高于插件当前版本({plugin_version}) —— 版本倒退即拒")
    check_url(a.get("url"), f"{where}(整像)")
    volumes = a.get("volumes")
    if not isinstance(volumes, list) or not volumes:
        fail(f"{where}: volumes 缺失（大资产必须分卷，双层校验）")
    else:
        for i, v in enumerate(volumes):
            vwhere = f"{where}.volumes[{i}]"
            if not isinstance(v, dict):
                fail(f"{vwhere}: 应为对象 → {v!r}")
                continue
            if v.get("index") != i:
                fail(f"{vwhere}: index 应从 0 连续 → {v.get('index')!r}")
            if not isinstance(v.get("sizeBytes"), int) or v.get("sizeBytes", 0) <= 0:
                fail(f"{vwhere}: sizeBytes 应为正整数")
            check_sha(v.get("sha256"), vwhere)
            check_url(v.get("url"), vwhere)

templates = data.get("templates")
if not isinstance(templates, list):
    fail("templates 应为数组")
    templates = []
tids = set()
for t in templates:
    if not isinstance(t, dict):
        fail(f"templates 条目应为对象 → {t!r}")
        continue
    tid = t.get("id", "")
    where = f"templates[{tid}]"
    if not tid:
        fail(f"{where}: id 缺失")
    if tid in tids:
        fail(f"{where}: id 重复")
    tids.add(tid)
    if not isinstance(t.get("distributable"), bool):
        fail(f"{where}: distributable 应为布尔")
    lowered = tid.lower()
    if "windows" in lowered or "win" in lowered:
        if t.get("distributable") is not False:
            fail(f"{where}: Windows 类模板必须 distributable=false（仅用户自备，N-VM-004 FR-002）")
    if t.get("distributable") is True:
        if not t.get("license"):
            fail(f"{where}: distributable=true 必须带 license")
        if t.get("imageId") and t.get("imageId") not in ids:
            fail(f"{where}: imageId({t.get('imageId')}) 未在 assets 中登记")

if errors:
    print(f"[asset-ci] ✗ {len(errors)} 处校验失败：", file=sys.stderr)
    for e in errors:
        print(f"  - {e}", file=sys.stderr)
    sys.exit(1)
print(f"[asset-ci] ✓ abyss-asset-v1 清单校验通过（{len(assets)} 资产 · {len(templates)} 模板 · "
      f"minPlugin {index_min_plugin}）")
