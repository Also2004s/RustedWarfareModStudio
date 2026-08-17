from PIL import Image, ImageDraw
import os

# 源图标路径
SRC = "docs/icon_source.jpg"
# 输出目录前缀
OUT_DIR = "app/src/main/res"

# Android 标准图标密度与尺寸
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

# 圆角半径：mdpi 48px 上约 8dp（1dp=1px at mdpi），按比例缩放
# 即圆角半径 = size * (8 / 48) = size / 6
CORNER_RATIO = 8 / 48


def remove_black_bg(img: Image.Image, threshold: int = 30) -> Image.Image:
    """将接近黑色的背景替换为透明。"""
    rgba = img.convert("RGBA")
    datas = rgba.getdata()
    new_data = []
    for r, g, b, a in datas:
        if r < threshold and g < threshold and b < threshold:
            new_data.append((0, 0, 0, 0))
        else:
            new_data.append((r, g, b, a))
    rgba.putdata(new_data)
    return rgba


def make_rounded(src: Image.Image, size: int) -> Image.Image:
    """生成圆角方形图标。"""
    img = remove_black_bg(src).resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    radius = max(1, int(size * CORNER_RATIO))
    draw.rounded_rectangle((0, 0, size, size), radius=radius, fill=255)
    result = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    result.paste(img, (0, 0), mask)
    return result


def make_circle(src: Image.Image, size: int) -> Image.Image:
    """生成圆形图标。"""
    img = remove_black_bg(src).resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse((0, 0, size, size), fill=255)
    result = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    result.paste(img, (0, 0), mask)
    return result


def main():
    src = Image.open(SRC)
    # 源图可能含透明背景？JPEG 无透明，保持原样缩放即可
    for density, size in DENSITIES.items():
        out_dir = os.path.join(OUT_DIR, f"mipmap-{density}")
        os.makedirs(out_dir, exist_ok=True)

        rounded = make_rounded(src, size)
        rounded_path = os.path.join(out_dir, "ic_launcher.png")
        rounded.save(rounded_path, "PNG")
        print(f"Saved {rounded_path}")

        circle = make_circle(src, size)
        circle_path = os.path.join(out_dir, "ic_launcher_round.png")
        circle.save(circle_path, "PNG")
        print(f"Saved {circle_path}")


if __name__ == "__main__":
    main()
