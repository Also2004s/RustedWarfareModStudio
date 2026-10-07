# -*- coding: utf-8 -*-
"""验证新五档（括号组 > and/or > 括号外逗号 > 运算符 > 括号内逗号）下的断行效果。

对照 D(新) vs B(刚回退的旧四档，逗号最低)。等宽近似 汉字=2 其它=1。
"""
import re

AND_OR = re.compile(r"\band\b|\bor\b", re.IGNORECASE)
FALLBACK_SYMBOLS = set(',*+-%/: (')


def is_han(c):
    cp = ord(c)
    return 0x4E00 <= cp <= 0x9FFF or 0x3400 <= cp <= 0x4DBF or 0xF900 <= cp <= 0xFAFF


def w(s):
    return sum(2 if is_han(c) else 1 for c in s)


def comma_after(line, i):
    j = i + 1
    while j < len(line) and line[j] in ' \t':
        j += 1
    return j


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
    outer = sorted(comma_after(line, i) for i in commas if depth_at(line, i) == 0)
    inner = sorted(comma_after(line, i) for i in commas if depth_at(line, i) > 0)
    clean = lambda s: sorted(x for x in s if 0 < x < n)
    return clean(paren), clean(andor), clean(outer), clean(ops), clean(inner)


def fallback_positions(line):
    n = len(line)
    out = []
    for p in range(1, n):
        c = line[p]
        if c == '=' or line[p - 1] == '=':
            continue
        if c not in FALLBACK_SYMBOLS:
            continue
        if is_han(line[p - 1]) and is_han(c):
            continue
        out.append(comma_after(line, p) if c == ',' else p)
    return sorted(set(out))


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


def sim(line, width, groups):
    n = len(line)
    rows, rs = [], 0
    while rs < n:
        if w(line[rs:]) <= width:
            rows.append((rs, n, '整段放得下'))
            break
        chosen, kind = -1, None
        for name, arr in groups:
            f = farthest(line, arr, rs, width)
            if f >= 0:
                chosen, kind = f, name
                break
        if chosen < 0:
            f = farthest(line, fallback_positions(line), rs, width)
            chosen, kind = (f, '兜底(标点/空格)') if f >= 0 else (rs + 1, '硬切一格')
        rows.append((rs, chosen, kind))
        rs = chosen
    return rows


def dump(title, line, width, rows):
    print(f"\n--- {title}  (行宽 ≈ {width} 单位) ---")
    for (s, e, kind) in rows:
        seg = line[s:e]
        print(f"  [{s:>3},{e:>3}) 宽{w(seg):>3} 留白{width - w(seg):>3} {kind:<14} |{seg}|")


SAMPLE = (",资源名=敌人=(全局检查第一个单位(需标签='I').敌人有此单位数量(需标签=\"I\")), "
          "队友=(全局检查第一个单位(需标签='I').队伍中此单位数量(需标签=\"I\")+全局检查第一个单位(需标签='I').盟友有此单位数量(需标签=\"I\")), "
          "队伍=max(自身资源.敌人/自身资源.队友, 1)")

paren, andor, outer, ops, inner = analyze(SAMPLE)
print("总宽:", w(SAMPLE), "| 括号外逗号位置:", outer, "| 括号内逗号位置:", inner, "| 运算符位置:", ops)

OLD = [('括号组', paren), ('and/or', andor), ('运算符', ops), ('逗号(旧:最低)', outer + inner)]
NEW = [('括号组', paren), ('and/or', andor), ('括号外逗号', outer), ('运算符', ops), ('括号内逗号', inner)]

for width in (100, 76, 70, 60, 46):
    dump(f"B 旧四档（逗号最低）", SAMPLE, width, sim(SAMPLE, width, OLD))
    dump(f"D 新五档（括号外逗号在运算符前）", SAMPLE, width, sim(SAMPLE, width, NEW))
