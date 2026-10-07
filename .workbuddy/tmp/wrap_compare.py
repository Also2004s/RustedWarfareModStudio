# -*- coding: utf-8 -*-
"""对比：现状(按符号类别分级) vs 建议(块界优先 + 块内填满)。等宽近似 汉字=2 其它=1。"""
import re

AND_OR = re.compile(r"\band\b|\bor\b", re.IGNORECASE)
FALLBACK_SYMBOLS = set(',*+-%/: (')


def is_han(c):
    cp = ord(c)
    return 0x4E00 <= cp <= 0x9FFF or 0x3400 <= cp <= 0x4DBF or 0xF900 <= cp <= 0xFAFF


def w(s):
    return sum(2 if is_han(c) else 1 for c in s)


def depth_at(line, p):
    d = 0
    for i in range(min(p, len(line))):
        if line[i] == '(':
            d += 1
        elif line[i] == ')':
            d -= 1
    return d


def analyze(line):
    n = len(line)
    match, stack = {}, []
    for i, c in enumerate(line):
        if c == '(':
            stack.append(i)
        elif c == ')' and stack:
            match[stack.pop()] = i
    andor = sorted(m.start() for m in AND_OR.finditer(line))
    paren = sorted(o for o, c in match.items() if c > o + 1 and AND_OR.search(line[o + 1:c]))
    ops = set()
    for i, c in enumerate(line):
        if c not in '+-' or i == 0 or i == n - 1:
            continue
        prev, nxt = line[i - 1], line[i + 1]
        if prev.isspace() or nxt.isspace() or nxt == ')' or nxt == '\n':
            continue
        if nxt.isalpha() or nxt == '_':
            ops.add(i)
    for i in range(1, n - 1):
        if line[i] in '*/' and not line[i - 1].isspace() and not line[i + 1].isspace():
            ops.add(i)
    commas = [i for i in range(1, n) if line[i] == ',']
    clean = lambda s: sorted(x for x in s if 0 < x < n)
    return clean(paren), clean(andor), clean(ops), clean(commas)


def fallback_positions(line, n):
    out = []
    for p in range(1, n):
        c = line[p]
        if c == '=' or line[p - 1] == '=':
            continue
        if c not in FALLBACK_SYMBOLS:
            continue
        if is_han(line[p - 1]) and is_han(c):
            continue
        out.append(p)
    return out


def farthest(line, cands, row_start, width):
    last = -1
    for p in cands:
        if p <= row_start:
            continue
        if w(line[row_start:p]) <= width:
            last = p
        else:
            break
    return last


def old_sim(line, width, groups):
    n = len(line)
    rows, rs = [], 0
    while rs < n:
        if w(line[rs:]) <= width:
            rows.append((rs, n, '整段放得下', 0))
            break
        chosen, kind = -1, None
        for name, arr in groups:
            f = farthest(line, arr, rs, width)
            if f >= 0:
                chosen, kind = f, name
                break
        if chosen < 0:
            kind = '兜底'
            cands = sorted(set(fallback_positions(line, n)) | {p for p in range(1, n) if line[p] == ','})
            f = farthest(line, cands, rs, width)
            chosen = f if f >= 0 else rs + 1
        rows.append((rs, chosen, kind, width - w(line[rs:chosen])))
        rs = chosen
    return rows


def new_sim(line, width, paren, andor, ops, commas):
    n = len(line)
    blocks = sorted(set(paren) | {p for p in andor if depth_at(line, p) == 0}
                    | {p for p in commas if depth_at(line, p) == 0})
    inner = sorted(set(ops) | set(commas) | set(fallback_positions(line, n)))
    rows, rs = [], 0
    while rs < n:
        if w(line[rs:]) <= width:
            rows.append((rs, n, '整段放得下', 0))
            break
        b = farthest(line, blocks, rs, width)
        if b >= 0:
            chosen, kind = b, '块界(顶层, / and/or / 括号组)'
        else:
            f = farthest(line, inner, rs, width)
            chosen, kind = (f, '块内填满') if f >= 0 else (rs + 1, '硬切')
        rows.append((rs, chosen, kind, width - w(line[rs:chosen])))
        rs = chosen
    return rows


def dump(title, line, width, rows):
    print(f"\n--- {title}  (行宽 ≈ {width} 单位) ---")
    for (s, e, kind, pad) in rows:
        seg = line[s:e]
        print(f"  [{s:>3},{e:>3}) 宽{w(seg):>3} 留白{pad:>3}  {kind:<22} |{seg}|")


SAMPLE = (",资源名=敌人=(全局检查第一个单位(需标签='I').敌人有此单位数量(需标签=\"I\")), "
          "队友=(全局检查第一个单位(需标签='I').队伍中此单位数量(需标签=\"I\")+全局检查第一个单位(需标签='I').盟友有此单位数量(需标签=\"I\")), "
          "队伍=max(自身资源.敌人/自身资源.队友, 1)")
paren, andor, ops, commas = analyze(SAMPLE)
for width in (40, 46, 60):
    dump("现状", SAMPLE, width, old_sim(SAMPLE, width, [('括号组', paren), ('and/or', andor), ('运算符', ops), ('逗号', commas)]))
    dump("建议", SAMPLE, width, new_sim(SAMPLE, width, paren, andor, ops, commas))

print("\n段 2 起点列号:", [p for p in commas], " 总宽:", w(SAMPLE))
