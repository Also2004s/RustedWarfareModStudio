# -*- coding: utf-8 -*-
"""按新实现（块边界优先 + 块内填满 + 参数括号组原子 + 逗号后置）模拟断行结果。
等宽近似：汉字 = 2 单位，其余 = 1 单位。
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
    n = len(line)
    i += 1
    while i < n and line[i] in ' \t':
        i += 1
    return i


def analyze(line):
    n = len(line)
    depth = [0] * n
    d = 0
    for i, c in enumerate(line):
        depth[i] = d
        if c == '(':
            d += 1
        elif c == ')' and d > 0:
            d -= 1
    match, stack = {}, []
    for i, c in enumerate(line):
        if c == '(':
            stack.append(i)
        elif c == ')' and stack:
            match[stack.pop()] = i
    blocks, inner = set(), set()
    for m in AND_OR.finditer(line):
        (blocks if depth[m.start()] == 0 else inner).add(m.start())
    for o, c in match.items():
        if c > o + 1 and AND_OR.search(line[o + 1:c]):
            blocks.add(o)
    for i in range(1, n):
        if line[i] != ',':
            continue
        p = comma_after(line, i)
        if p <= 0 or p >= n:
            continue
        (blocks if depth[i] == 0 else inner).add(p)
    for i, c in enumerate(line):
        if c not in '+-' or i == 0 or i == n - 1:
            continue
        prev, nxt = line[i - 1], line[i + 1]
        if prev.isspace() or nxt.isspace() or nxt == ')' or nxt == '\n':
            continue
        if nxt.isalpha() or nxt == '_':
            inner.add(i)
    for i in range(1, n - 1):
        if line[i] in '*/' and not line[i - 1].isspace() and not line[i + 1].isspace():
            inner.add(i)
    atoms = []
    for o, c in sorted(match.items()):
        if o > 0 and (line[o - 1].isalnum() or line[o - 1] in '_$' or is_han(line[o - 1])):
            atoms += [o, c]
    clean = lambda s: sorted(x for x in s if 0 < x < n)
    return clean(blocks), clean(inner), atoms, match


def fallback(line, start, end):
    n = len(line)
    out = []
    for p in range(start + 1, end):
        c = line[p]
        if c == '=' or line[p - 1] == '=':
            continue
        if c not in FALLBACK_SYMBOLS:
            continue
        if is_han(line[p - 1]) and is_han(c):
            continue
        if c == ',':
            q = comma_after(line, p)
            if q <= 0 or q >= end:
                continue
            if line[q] == '=' or line[q - 1] == '=':
                continue
            out.append(q)
        else:
            out.append(p)
    return out


def dot_breaks(line, start, end):
    out = []
    for p in range(start + 1, end):
        if line[p] != '.':
            continue
        if line[p - 1] == '=':
            continue
        if is_han(line[p - 1]) and is_han(line[p]):
            continue
        out.append(p)
    return out


def protected(p, row_start, atoms):
    for i in range(0, len(atoms) - 1, 2):
        open_, close = atoms[i], atoms[i + 1]
        if open_ > p:
            break
        if open_ <= row_start:
            continue
        if open_ < p <= close:
            return True
    return False


def farthest(line, cands, row_start, width, atoms):
    last = -1
    for p in cands:
        if p <= row_start:
            continue
        if w(line[row_start:p]) <= width:
            if protected(p, row_start, atoms):
                continue
            last = p
        else:
            break
    return last


def next_bs_n(line, frm):
    i = frm
    n = len(line)
    while i < n - 1:
        if i > frm and line[i] == '\\' and line[i + 1] == 'n':
            return i
        i += 1
    return -1


def simulate(line, width, blocks, inner, atoms):
    n = len(line)
    rows, rs = [], 0
    bi = ii = 0
    while rs < n:
        limit = next_bs_n(line, rs)
        if limit < 0:
            limit = n
        if w(line[rs:limit]) <= width:
            rows.append((rs, limit, '整段放得下', width - w(line[rs:limit])))
            rs = limit
        else:
            chosen, kind = farthest(line, blocks, rs, width, atoms), '块界'
            if chosen < 0:
                fill = sorted(set(inner) | set(atoms[0::2]))
                chosen, kind = farthest(line, fill, rs, width, atoms), '块内语义'
            if chosen < 0:
                chosen, kind = farthest(line, fallback(line, rs, limit), rs, width, atoms), '标点填满'
            if chosen < 0:
                chosen, kind = farthest(line, dot_breaks(line, rs, limit), rs, width, atoms), '点兜底'
            if chosen < 0:
                chosen, kind = rs + 1, '硬切'
            rows.append((rs, chosen, kind, width - w(line[rs:chosen])))
            rs = chosen
        while bi < len(blocks) and blocks[bi] <= rs:
            bi += 1
        while ii < len(inner) and inner[ii] <= rs:
            ii += 1
    return rows


SAMPLE = (",资源名=敌人=(全局检查第一个单位(需标签='I').敌人有此单位数量(需标签=\"I\")), "
          "队友=(全局检查第一个单位(需标签='I').队伍中此单位数量(需标签=\"I\")+全局检查第一个单位(需标签='I').盟友有此单位数量(需标签=\"I\")), "
          "队伍=max(自身资源.敌人/自身资源.队友, 1)")
b, i_, atoms, match = analyze(SAMPLE)
print("blocks:", b)
print("inner:", i_)
print("atoms:", atoms)
for width in (40, 46, 60):
    print(f"\n=== 改之后 · 行宽 {width} ===")
    for (s, e, kind, pad) in simulate(SAMPLE, width, b, i_, atoms):
        seg = SAMPLE[s:e]
        print(f"  [{s:>3},{e:>3}) 宽{w(seg):>3} 留白{pad:>3}  {kind:<10} |{seg}|")

print()
S2 = ('requireConditional:if numberOfUnitsInEnemyTeam(withTag="作战区块", greaterThan=0) and '
      '(numberOfUnitsInTeam(withTag="陆军作战中控", withinRange=250, greaterThan=1) or '
      'self.hasResources(冷却=30) and self.hasResources(时间=240))')
b2, i2, a2, _ = analyze(S2)
for width in (46, 60):
    print(f"\n=== 条件行 · 行宽 {width} ===")
    for (s, e, kind, pad) in simulate(S2, width, b2, i2, a2):
        seg = S2[s:e]
        print(f"  [{s:>3},{e:>3}) 宽{w(seg):>3} 留白{pad:>3}  {kind:<10} |{seg}|")
