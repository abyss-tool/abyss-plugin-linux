#!/usr/bin/env python3
"""pr_guard.py —— PR 纪律守卫:对 PR diff 的新增行做红线扫描(四仓同构副本)。

只扫 PR 新增行(+ 行),存量代码零影响 —— 把 AGENTS.md 的成文纪律从
「评审自觉」升级为「CI 门禁」。本脚本自身可被 PR 修改,属仓库侧门禁的
固有边界(纪律工具,非安全边界);改动本脚本须在评审中显式说明。

规则(编号对应 AGENTS.md):
  R1 死点击族       —— 「收了 onClick 却从未接线」在本仓反复出现(入口卡/
                       插件卡/已装行/AssistChip 信息标签共 4+ 处)。新增行
                       出现空 onClick / 空 clickable 一律拒收:不可交互改
                       StatusChip / 纯展示组件,可交互必须接线真实处理。
  R2 渠道 URL 白名单 —— 渠道 URL 只认 AbyssChannels / MirrorCatalog
                       (AGENTS.md 模块纪律 4,运行时一律过白名单)。
                       allowlist:core/common/**(事实源本体)、
                       */src/test/** 与 */src/androidTest/**(夹具)、
                       vendor/**(第三方库快照)。
  R3 SDK 整组红线    —— targetSdk=28(ADR-003 SELinux 红线)/ minSdk=26 /
                       compileSdk=36;单点改动即红,升级须整组联动
                       (AGENTS.md 工具链节「勿单独升降」)。
  R4 玻璃 API 归口   —— vendored 玻璃库(com.kyant.backdrop)只允许
                       core:designsystem glass 包与 vendor 本体引用
                       (AGENTS.md 模块纪律 1:业务组件零感知底层库)。

用法:
  BASE_SHA=... HEAD_SHA=... python3 tools/pr_guard.py          # CI(PR 事件)
  python3 tools/pr_guard.py --title "feat(core): xx"           # 标题纪律(可选)
  git diff --unified=0 main...HEAD | python3 tools/pr_guard.py --diff   # 本地
"""
from __future__ import annotations

import os
import re
import subprocess
import sys

# ── 规则配置 ─────────────────────────────────────────────────────────────
SDK_PINS = {"targetSdk": 28, "minSdk": 26, "compileSdk": 36}

R2_EXCLUDE_PREFIXES = ("core/common/", "vendor/")
R2_TEST_MARKERS = ("/src/test/", "/src/androidTest/")
R4_EXCLUDE_PREFIXES = ("core/designsystem/", "vendor/")

RE_EMPTY_ONCLICK = re.compile(r"\bonClick\s*=\s*\{\s*\}|\bonClick\s*\(\s*\{\s*\}\s*\)|\bclickable\s*\([^)]*\)\s*\{\s*\}")
RE_RAW_URL = re.compile(r"raw\.githubusercontent\.com")
RE_SDK_PIN = re.compile(r"\b(targetSdk|minSdk|compileSdk)\s*=\s*\"?(\d+)\"?")
RE_BACKDROP_IMPORT = re.compile(r"\bimport\s+com\.kyant\.backdrop\b")

TITLE_RE = re.compile(
    r"^(feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert)"
    r"(\([a-z0-9:._/-]+\))?!?: .+"
)

RULE_MSG = {
    "R1": "R1 死点击族:onClick/clickable 收了空体 —— 点击无任何效果(本缺陷族历史上出现 4+ 次)。不可交互请改 StatusChip/纯展示组件;可交互请接线真实处理",
    "R2": "R2 渠道 URL 硬编码:raw.githubusercontent.com 仅允许 AbyssChannels/MirrorCatalog(core/common)与测试夹具 —— 业务代码一律走白名单(AGENTS.md 模块纪律 4)",
    "R3": "R3 SDK 整组红线:targetSdk=28(ADR-003 SELinux 红线)/minSdk=26/compileSdk=36,禁止单点改动 —— 升级须整组联动并过全量 CI(AGENTS.md 工具链节)",
    "R4": "R4 玻璃 API 越界:vendored 玻璃库(com.kyant.backdrop)仅限 core:designsystem glass 包与 vendor 本体 —— 业务组件经 designsystem glass 包(AGENTS.md 模块纪律 1)",
}


def parse_added_lines(diff_text: str):
    """解析 unified diff → [(path, new_line_no, code)]。只产出 + 行。"""
    path = None
    new_line = 0
    for line in diff_text.splitlines():
        if line.startswith("diff --git "):
            m = re.match(r'^diff --git "?a/(.*?)"? "?b/(.*?)"?$', line)
            path = m.group(2) if m else None
        elif line.startswith("@@"):
            m = re.match(r"@@ -\d+(?:,\d+)? \+(\d+)", line)
            new_line = int(m.group(1)) if m else 0
        elif line.startswith("\\"):  # \ No newline at end of file
            continue
        elif path is None:
            continue
        elif line.startswith("+") and not line.startswith("+++"):
            yield path, new_line, line[1:]
            new_line += 1
        elif line.startswith("-") and not line.startswith("---"):
            continue  # 删除行:新侧行号不动
        else:
            new_line += 1


def is_comment(code: str) -> bool:
    s = code.lstrip()
    return s.startswith(("//", "/*", "*", "*/"))


def scan(path: str, line_no: int, code: str) -> list[tuple[str, str]]:
    """返回 [(rule_id, detail)]。"""
    if is_comment(code):
        return []
    violations = []
    low = path.lower()

    # R1 死点击族(.kt/.kts)
    if low.endswith((".kt", ".kts")) and RE_EMPTY_ONCLICK.search(code):
        violations.append(("R1", code.strip()[:80]))

    # R2 渠道 URL 白名单(.kt/.kts,allowlist:core/common、测试源集、vendor)
    if (low.endswith((".kt", ".kts"))
            and not path.startswith(R2_EXCLUDE_PREFIXES)
            and not any(m in path for m in R2_TEST_MARKERS)
            and RE_RAW_URL.search(code)):
        violations.append(("R2", f"{path}: {code.strip()[:70]}"))

    # R3 SDK 整组红线(.kts/.gradle/.toml;toml 值可带引号)
    if low.endswith((".kts", ".gradle", ".toml")):
        for m in RE_SDK_PIN.finditer(code):
            key, val = m.group(1), int(m.group(2))
            if key in SDK_PINS and val != SDK_PINS[key]:
                violations.append(("R3", f"{path}: {key} = {val}(钉 {SDK_PINS[key]})"))

    # R4 玻璃 API 归口(.kt,allowlist:core/designsystem、vendor)
    if (low.endswith(".kt")
            and not path.startswith(R4_EXCLUDE_PREFIXES)
            and RE_BACKDROP_IMPORT.search(code)):
        violations.append(("R4", f"{path}: import com.kyant.backdrop"))

    return violations


def main(argv: list[str]) -> int:
    # ── 可选:PR 标题纪律(插件仓无独立 pr-lint,合并入本守卫)──────────
    violations: list[str] = []
    title_checked = "--title" in argv
    if title_checked:
        idx = argv.index("--title")
        title = argv[idx + 1] if idx + 1 < len(argv) else ""
        if not TITLE_RE.match(title.strip()):
            violations.append(
                "::error::PR 标题不符合 Conventional Commits(AGENTS.md 提交纪律):"
                "要求 type(scope): 中文描述,type ∈ feat/fix/docs/style/refactor/perf/"
                f"test/build/ci/chore/revert。实际:{title!r}")
        else:
            print(f"[pr-guard] ✓ PR 标题合规:{title}")

    # ── 取 diff:环境变量(git)或 --diff(stdin);都没有则只做标题检查 ──
    base, head = os.environ.get("BASE_SHA", ""), os.environ.get("HEAD_SHA", "")
    diff_text = None
    if "--diff" in argv:
        diff_text = sys.stdin.read()
    elif base and head:
        r = subprocess.run(["git", "diff", "--unified=0", base, head],
                           capture_output=True, text=True)
        if r.returncode != 0:
            print(f"::error::git diff 失败:{r.stderr[:200]}", file=sys.stderr)
            return 2
        diff_text = r.stdout
    elif title_checked:
        pass  # title-only 模式(无 diff 输入)
    else:
        print(__doc__)
        return 2

    if diff_text is not None:
        counts: dict[str, int] = {}
        for path, line_no, code in parse_added_lines(diff_text):
            for rule, detail in scan(path, line_no, code):
                counts[rule] = counts.get(rule, 0) + 1
                # 行号缺省(二进制等)退化为文件级注解
                loc = f"file={path},line={line_no}" if line_no > 0 else f"file={path}"
                violations.append(f"::error {loc}::[pr-guard {RULE_MSG[rule]}] 样例:{detail}")
    else:
        counts = {}

    if violations:
        print(f"[pr-guard] ✗ {len(violations)} 处红线违规:", file=sys.stderr)
        per_rule = " / ".join(f"{k}:{v}" for k, v in sorted(counts.items())) or "title"
        print(f"[pr-guard]   分布:{per_rule}", file=sys.stderr)
        for v in violations:
            print(v)
        return 1

    print("[pr-guard] ✓ PR 红线扫描通过"
          + ("(diff:死点击族/渠道 URL/SDK 整组/玻璃归口)" if diff_text is not None else "(title-only)"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
