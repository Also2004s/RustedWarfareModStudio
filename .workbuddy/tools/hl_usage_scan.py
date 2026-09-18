# -*- coding: utf-8 -*-
"""统计真实 RW ini 项目里各高亮类型的 token 出现次数（给「自定义高亮」排序提供依据）。

口径（2026-09-18 修正版）：
  1. 规则来源 = app/src/main/assets/textmate/ini/ini.tmLanguage.json；
  2. **分层模拟** TextMate：顶层 patterns 里有一条「键值对」begin/end 块
     （`begin` = `^[ \\t]*([^:=#\\[\\n]+)[ \\t]*(:)`，`end` = `$`），
     它**内部有自己的一套 patterns**；一旦进入该块，块内内容**只用块内规则匹配，不回落到顶层**。
     ⚠️ 旧版把顶层与块内规则**摊平**成一个列表再竞争，导致顶层注释规则 `(#|;).*$` 跑进块内抢走
     `#FF0000`，得出「颜色值 0 命中」的错结论（真实 322 次）。
  3. 每个位置上取「出现位置最早」的匹配，同位置取规则表中靠前的；带 captures 的规则按捕获组区间落 scope；
  4. scope → 配色键的归属取 DarkThemeColors.targetScopes（从源码正则解析）。

用法：
  python hl_usage_scan.py "D:/path/to/rw/mod/project" [更多目录...]
"""
import collections
import io
import json
import os
import re
import sys

sys.stdout.reconfigure(encoding="utf-8")

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
APP = os.path.join(REPO, "app", "src", "main")
GRAMMAR = os.path.join(APP, "assets", "textmate", "ini", "ini.tmLanguage.json")
COLORS_KT = os.path.join(APP, "java", "com", "rwmodstudio", "core", "DarkThemeColors.kt")
TEXT_EXT = (".ini", ".template", ".txt")
KV_BLOCK_INDEX = 5  # 顶层 patterns 里「键值对 begin/end 块」的下标


def _compile(r):
    body = r.get("match") or r.get("begin") or ""
    if not body:
        return None
    try:
        rx = re.compile(body)
    except re.error:
        return None
    caps = {}
    for ck in ("captures", "beginCaptures", "endCaptures"):
        for gid, c in (r.get(ck) or {}).items():
            if isinstance(c, dict) and c.get("name"):
                caps[int(gid)] = c["name"]
    return rx, r, caps


def load_rules():
    g = json.load(io.open(GRAMMAR, encoding="utf-8"))
    top = g.get("patterns") or []
    top_rules = [c for c in (_compile(r) for r in top) if c]
    inner = (top[KV_BLOCK_INDEX].get("patterns") or []) if len(top) > KV_BLOCK_INDEX else []
    inner_rules = [c for c in (_compile(r) for r in inner) if c]
    return top, top_rules, inner_rules


def load_scope_to_category():
    src = io.open(COLORS_KT, encoding="utf-8").read()
    block = src.split("val targetScopes = linkedMapOf(")[1]
    parts = re.split(r'\n\s*"(\w+)"\s+to\s+setOf\(', block)
    mapping = {}
    for key, body in zip(parts[1::2], parts[2::2]):
        for scope in re.findall(r'"([^"]+)"', body.split(")", 1)[0]):
            mapping[scope] = key
    return mapping


def read_text(path):
    data = open(path, "rb").read()
    for enc in ("utf-8-sig", "utf-8", "gbk"):
        try:
            return data.decode(enc)
        except Exception:
            pass
    return data.decode("utf-8", "replace")


def main(roots):
    top, top_rules, inner_rules = load_rules()
    scope2cat = load_scope_to_category()
    cat_hits = collections.Counter()
    scope_hits = collections.Counter()
    files = lines = 0

    def emit(a, b, scope):
        if b > a:
            scope_hits[scope] += 1
            cat = scope2cat.get(scope)
            if cat:
                cat_hits[cat] += 1

    def scan(line, start, end, rules, depth):
        pos, guard = start, 0
        while pos < end and guard < 300:
            guard += 1
            best = None
            for idx, (rx, r, caps) in enumerate(rules):
                m = rx.search(line, pos, end)
                if m and (best is None or (m.start(), idx) < best[0]):
                    best = ((m.start(), idx), r, caps, m)
            if best is None:
                break
            _, r, caps, m = best
            if caps:
                for gid, scope in caps.items():
                    a, b = m.span(gid)
                    if a >= 0:
                        emit(a, b, scope)
                if r.get("name"):
                    emit(m.start(), m.end(), r["name"])
            elif r.get("name"):
                emit(m.start(), m.end(), r["name"])
            if depth == 0 and r is top[KV_BLOCK_INDEX]:
                # 进入键值块：块内用块内规则扫到行尾（end = $），顶层规则不参与
                scan(line, m.end(), len(line), inner_rules, depth + 1)
                pos = len(line)
            else:
                pos = m.end() if m.end() > m.start() else m.start() + 1

    for root in roots:
        for dp, dn, fn in os.walk(root):
            if any(x in dp for x in (os.sep + ".git", os.sep + ".vscode", "node_modules")):
                continue
            for name in fn:
                if not name.lower().endswith(TEXT_EXT):
                    continue
                files += 1
                for line in read_text(os.path.join(dp, name)).splitlines():
                    lines += 1
                    if line.strip():
                        scan(line, 0, len(line), top_rules, 0)

    print(f"文件={files} 行数={lines} token={sum(scope_hits.values())}")
    print("\n=== 各高亮类型出现次数（降序）===")
    for cat, n in cat_hits.most_common():
        print(f"  {n:7d}  {cat}")
    print("\n=== 命中 0 的类型 ===\n  " + ", ".join(sorted(set(scope2cat.values()) - set(cat_hits))))
    print("\n=== 逐 scope（降序）===")
    for s, n in scope_hits.most_common():
        print(f"  {n:7d}  {s:52s} -> {scope2cat.get(s, '(无归属)')}")


if __name__ == "__main__":
    main(sys.argv[1:] or [os.path.join(REPO, "..")])
