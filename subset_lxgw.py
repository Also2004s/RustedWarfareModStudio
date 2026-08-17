#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from fontTools.ttLib import TTFont

TXT_PATH = r"D:/ALSO2004/人机的玩笑/scripts/翻译库.txt"
JSON_PATH = r"D:/ALSO2004/人机的玩笑/scripts/数据集/代码参考库.json"
FONT_PATH = r"D:/ALSO2004/android-tool/RustedWarfareModStudio/app/src/main/res/font/lxgw_wenkai_regular.ttf"

def collect_strings(obj):
    if isinstance(obj, str):
        yield obj
    elif isinstance(obj, dict):
        for v in obj.values():
            yield from collect_strings(v)
    elif isinstance(obj, list):
        for item in obj:
            yield from collect_strings(item)

def main():
    font_path = Path(FONT_PATH)
    if not font_path.is_file():
        print(f"字体文件不存在: {font_path}", file=sys.stderr)
        sys.exit(1)

    chars = set()

    # 1. 从翻译库.txt 提取字符
    txt_text = Path(TXT_PATH).read_text(encoding="utf-8", errors="ignore")
    chars.update(txt_text)

    # 2. 从代码参考库.json 提取字符（递归所有字符串值）
    with open(JSON_PATH, "r", encoding="utf-8", errors="ignore") as f:
        data = json.load(f)
    for s in collect_strings(data):
        chars.update(s)

    # 3. 基本 ASCII 可打印字符
    chars.update(chr(c) for c in range(0x20, 0x7F))

    # 4. 常用中文标点
    chars.update("。，、；：？！「」『』“”‘’（）《》【】…—～·")

    # 去除控制字符（保留 ASCII 可打印已在范围内，这里只过滤 0x00-0x1F 和 0x7F）
    chars = {c for c in chars if 0x20 <= ord(c) <= 0x7E or ord(c) >= 0x80}

    retained_count = len(chars)

    # 写入临时文本文件
    tmp_dir = Path(tempfile.gettempdir())
    text_file = tmp_dir / "lxgw_retain_chars.txt"
    text_file.write_text("".join(sorted(chars)), encoding="utf-8")

    out_file = font_path.with_name("lxgw_wenkai_regular.subset.ttf")

    # 运行 fontTools subset
    subprocess.run(
        [
            "py", "-m", "fontTools.subset",
            str(font_path),
            f"--text-file={text_file}",
            f"--output-file={out_file}",
        ],
        check=True,
    )

    # 读取 glyph 数量（使用上下文管理器关闭文件句柄，避免 Windows 下替换时文件被占用）
    with TTFont(str(font_path)) as orig_font:
        orig_glyphs = orig_font["maxp"].numGlyphs
    with TTFont(str(out_file)) as subset_font:
        subset_glyphs = subset_font["maxp"].numGlyphs

    orig_size = font_path.stat().st_size
    subset_size = out_file.stat().st_size

    # 备份并替换原文件
    backup_path = font_path.with_suffix(".ttf.bak")
    shutil.copy2(str(font_path), str(backup_path))
    shutil.move(str(out_file), str(font_path))

    print(f"原始文件大小: {orig_size} 字节 ({orig_size / 1024 / 1024:.2f} MB)")
    print(f"子集化后大小: {subset_size} 字节 ({subset_size / 1024 / 1024:.2f} MB)")
    print(f"保留不同字符数: {retained_count}")
    print(f"原始 glyph 数量: {orig_glyphs}")
    print(f"子集 glyph 数量: {subset_glyphs}")
    print(f"备份路径: {backup_path}")

if __name__ == "__main__":
    main()
