# -*- coding: utf-8 -*-
"""复现三种实现下老板样例的断行结果（等宽近似 汉字=2 其它=1）。

A 改造前   : 四档 paren > andOr > ops > commas(逗号位置本身，断在逗号之前)
B 当前回退 : 四档 paren > andOr > ops > commas(逗号之后)
C 上一轮改造: 块界(顶层逗号之后/and-or/括号组) > 块内填满
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
    clean = lambda s: sorted(x for x in s if 0 < x < n)
    return clean(paren), clean(andor), clean(ops), clean(commas)


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


def four_arch(line, width, groups):
    """四档（A/B 共用的选择逻辑）：按档顺序取最远放得下。"""
    n = len(line)
    rows, rs, trace = [], 0, []
    while rs < n:
        if w(line[rs:]) <= width:
            rows.append((rs, n, '整段放得下'))
            trace.append((rs, '-', '整段放得下'))
            break
        chosen, kind = -1, None
        for name, arr in groups:
            f = farthest(line, arr, rs, width)
            if f >= 0:
                chosen, kind = f, name
                break
        if chosen < 0:
            cands = sorted(set(fallback_positions(line)) | set(line_comma_after(line)))
            f = farthest(line, cands, rs, width)
            chosen, kind = (f, '兜底(标点/空格)') if f >= 0 else (rs + 1, '硬切一格')
        rows.append((rs, chosen, kind))
        trace.append((rs, chosen, kind))
        rs = chosen
    return rows


def line_comma_after(line):
    return [comma_after(line, i) for i in range(1, len(line)) if line[i] == ',']


def blocks_arch(line, width, paren, andor, commas):
    n = len(line)
    blocks = sorted(set(paren) | {p for p in andor if depth_at(line, p) == 0}
                    | {p for p in commas if depth_at(line, p) == 0})
    inner = sorted(set(analyze(line)[2]) | set(commas) | set(fallback_positions(line)))
    rows, rs = [], 0
    while rs < n:
        if w(line[rs:]) <= width:
            rows.append((rs, n, '整段放得下'))
            break
        b = farthest(line, blocks, rs, width)
        if b >= 0:
            chosen, kind = b, '块界'
        else:
            f = farthest(line, inner, rs, width)
            chosen, kind = (f, '块内填满') if f >= 0 else (rs + 1, '硬切一格')
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

paren, andor, ops, commas = analyze(SAMPLE)
print("总宽(单位):", w(SAMPLE))
print("顶层逗号位置:", [p for p in commas if depth_at(SAMPLE, p) == 0])
print("每个逗号的宽度位置(逗号后):", [(p, w(SAMPLE[:comma_after(SAMPLE, p)])) for p in commas])
print("运算符位置:", ops)

for width in (60, 46, 40):
    dump("A 改造前（逗号断在逗号之前）", SAMPLE, width,
         four_arch(SAMPLE, width, [('括号组', paren), ('and/or', andor), ('运算符', ops), ('逗号', commas)]))
    dump("B 当前回退版（逗号断在逗号之后）", SAMPLE, width,
         four_arch(SAMPLE, width, [('括号组', paren), ('and/or', andor), ('运算符', ops), ('逗号(后置)', line_comma_after(SAMPLE))]))
    dump("C 上一轮改造版（块界优先）", SAMPLE, width, blocks_arch(SAMPLE, width, paren, andor, commas))
