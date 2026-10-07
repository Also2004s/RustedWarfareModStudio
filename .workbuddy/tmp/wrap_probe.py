# -*- coding: utf-8 -*-
"""复现 SmartWrapBreaks.computePriorityBreaks 的分组与断点选择（等宽近似模型）。

等宽近似：汉字/全角 = 2 单位，其余 = 1 单位。仅用于判断"断点落在哪、行尾留多少白"，
不代表真实渲染宽度。
"""
import re

AND_OR = re.compile(r"\band\b|\bor\b", re.IGNORECASE)
FALLBACK_SYMBOLS = set(",*+-%/: (".replace(" ", " "))


def is_han(c):
    cp = ord(c)
    return 0x4E00 <= cp <= 0x9FFF or 0x3400 <= cp <= 0x4DBF or 0xF900 <= cp <= 0xFAFF


def priority_breaks(line):
    n = len(line)
    paren, andor, ops, commas = set(), set(), set(), set()
    if n <= 1:
        return paren, andor, ops, commas
    # 括号匹配
    match = {}
    stack = []
    for i, c in enumerate(line):
        if c == '(':
            stack.append(i)
        elif c == ')' and stack:
            match[stack.pop()] = i
    # R2 and/or
    for m in AND_OR.finditer(line):
        andor.add(m.start())
    # R3 含 and/or 的括号组
    for o, c in match.items():
        if c > o + 1 and AND_OR.search(line[o + 1:c]):
            paren.add(o)
    # R4 +/- 后跟字母或下划线
    for i, c in enumerate(line):
        if c not in '+-':
            continue
        if i == 0 or i == n - 1:
            continue
        prev, nxt = line[i - 1], line[i + 1]
        if prev.isspace() or nxt.isspace() or nxt == ')' or nxt == '\n':
            continue
        if nxt.isalpha() or nxt == '_':
            ops.add(i)
    # R5 逗号
    for i in range(1, n):
        if line[i] == ',':
            commas.add(i)
    # R6 * /
    for i in range(1, n - 1):
        c = line[i]
        if c in '*/' and not line[i - 1].isspace() and not line[i + 1].isspace():
            ops.add(i)

    def clean(s):
        return sorted(x for x in s if 0 < x < n)

    return clean(paren), clean(andor), clean(ops), clean(commas)


def w(s):
    return sum(2 if is_han(c) else 1 for c in s)


def simulate(line, width, groups):
    """复现 breakLine 的优先级选择：parenGroups > andOr > operators > commas，
    同组取"最远放得下"；全放不下 → 报兜底。"""
    n = len(line)
    rows = []
    row_start = 0
    while row_start < n:
        if w(line[row_start:]) <= width:
            rows.append((row_start, n, '整段放得下'))
            break
        chosen = -1
        chosen_kind = None
        for kind, arr in groups:
            last_fit = -1
            for p in arr:
                if p <= row_start:
                    continue
                if w(line[row_start:p]) <= width:
                    last_fit = p
                else:
                    break
            if last_fit >= 0:
                chosen, chosen_kind = last_fit, kind
                break
        if chosen < 0:
            chosen_kind = '兜底(空格/标点)'
            # 兜底：找最远能放下的空格或标点
            best = -1
            for p in range(row_start + 1, n):
                c = line[p]
                if c == '=' or line[p - 1] == '=':
                    continue
                if c not in FALLBACK_SYMBOLS:
                    continue
                if is_han(line[p - 1]) and is_han(c):
                    continue
                if w(line[row_start:p]) <= width:
                    best = p
                else:
                    break
            if best < 0:
                best = row_start + 1
            chosen = best
        rows.append((row_start, chosen, chosen_kind))
        row_start = chosen
    return rows


def report(line, widths):
    print("=" * 78)
    print("原文:", line)
    print("总宽(等宽近似 汉字=2):", w(line), " 字符数:", len(line))
    paren, andor, ops, commas = priority_breaks(line)
    def show(name, arr):
        print(f"  {name}({len(arr)}):", [(p, line[p:p+1], line[max(0,p-12):p], line[p:p+14]) for p in arr])
    show("括号组", paren)
    show("and/or", andor)
    show("运算符", ops)
    show("逗号", commas)
    groups = [('括号组', paren), ('and/or', andor), ('运算符', ops), ('逗号', commas)]
    for width in widths:
        print(f"  --- 假定行宽 {width} 单位 ---")
        for (s, e, kind) in simulate(line, width, groups):
            seg = line[s:e]
            print(f"    行: 列[{s:>4},{e:>4})  宽{w(seg):>3}  断因={kind:<14} 留白{width - w(seg):>3}  |{seg}|")


SAMPLE = (",资源名=敌人=(全局检查第一个单位(需标签='I').敌人有此单位数量(需标签=\"I\")), "
          "队友=(全局检查第一个单位(需标签='I').队伍中此单位数量(需标签=\"I\")+全局检查第一个单位(需标签='I').盟友有此单位数量(需标签=\"I\")), "
          "队伍=max(自身资源.敌人/自身资源.队友, 1)")

report(SAMPLE, [40, 46, 60])

print()
print("#" * 78)
S2 = "requireConditional:if numberOfUnitsInEnemyTeam(withTag=\"作战区块\", greaterThan=0) and (numberOfUnitsInTeam(withTag=\"陆军作战中控\", withinRange=250, greaterThan=1) or self.hasResources(冷却=30) and self.hasResources(时间=240))"
report(S2, [46, 60])
