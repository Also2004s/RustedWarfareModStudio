#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成本地翻译库资源文件：
- 原生表：D:\ALSO2004\人机的玩笑\scripts\翻译库.txt -> app/src/main/assets/data/translation.txt
- 附件表：tools/data/extra_translation_supplement.txt -> app/src/main/assets/data/extra_translation_supplement.txt

附件表会：去重（保留后出现的）、排序、跳过空 key/value、跳过 en == zh 的条目。
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "data")
NATIVE_SRC = r"D:\ALSO2004\人机的玩笑\scripts\翻译库.txt"
SUPPLEMENT_SRC = os.path.join(ROOT, "tools", "data", "extra_translation_supplement.txt")


def normalize_line(line: str) -> str:
    """去掉行尾换行符、首尾空格。"""
    return line.rstrip("\r\n").strip()


def copy_native():
    if not os.path.exists(NATIVE_SRC):
        print(f"[SKIP] 原生表源文件不存在: {NATIVE_SRC}")
        return
    os.makedirs(ASSETS_DIR, exist_ok=True)
    with open(NATIVE_SRC, "r", encoding="utf-8") as f:
        content = f.read()
    out = os.path.join(ASSETS_DIR, "translation.txt")
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(content)
    print(f"[OK] 原生表已生成: {out} ({os.path.getsize(out)} bytes)")


def generate_supplement():
    if not os.path.exists(SUPPLEMENT_SRC):
        print(f"[SKIP] 附件表源文件不存在: {SUPPLEMENT_SRC}")
        return

    seen = {}
    skipped = []
    with open(SUPPLEMENT_SRC, "r", encoding="utf-8") as f:
        for raw in f:
            line = normalize_line(raw)
            if not line or line.startswith("#"):
                continue
            if "=" not in line:
                skipped.append((line, "no '='"))
                continue
            en, _, zh = line.partition("=")
            en = en.strip()
            zh = zh.strip()
            if not en or not zh:
                skipped.append((line, "empty key/value"))
                continue
            if en == zh:
                skipped.append((line, "en == zh"))
                continue
            seen[en] = zh

    # 按英文 key 排序，保持输出稳定
    lines = [f"{en} = {zh}" for en, zh in sorted(seen.items())]
    out = os.path.join(ASSETS_DIR, "extra_translation_supplement.txt")
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        if lines:
            f.write("\n".join(lines) + "\n")
    print(f"[OK] 附件表已生成: {out} ({len(lines)} entries, {os.path.getsize(out)} bytes)")
    if skipped:
        print(f"[INFO] 跳过 {len(skipped)} 条无效/重复条目")


if __name__ == "__main__":
    copy_native()
    generate_supplement()
