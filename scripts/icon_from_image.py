"""把用户给的手绘稿做成 App 图标：抹掉截图水印 → 输出各密度 mipmap。

用法：python scripts/icon_from_image.py <源图路径>
"""
import os
import sys

from PIL import Image, ImageDraw

SRC_DEFAULT = (
    r"D:\Ruan Jian\DeepSeek\data\dsh-home\attachments\v1\objects\bd"
    r"\bdde6ed517c213c65038eab7ebe0a45b74acfaaf23d6962f87d31d5770f20b41"
)
RES = r"D:\Ruan Jian\DeepSeek\workspace\parcel\android\app\src\main\res"
OUT_BIG = r"D:\Ruan Jian\DeepSeek\workspace\parcel\icon-512.png"

# 要抹掉的区域（原图坐标）：左下角 "1-Flash 1017 High" 水印
CLEAN_RECTS = [(0, 256, 138, 295)]


def clean(im):
    d = ImageDraw.Draw(im)
    for box in CLEAN_RECTS:
        d.rectangle(box, fill=(255, 255, 255))

    # 截图里还有几条浅灰的 UI 分隔线（R≈G≈B 且偏亮），一并抹成白色
    px = im.load()
    w, h = im.size
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            if abs(r - g) < 6 and abs(g - b) < 6 and 196 <= r <= 246:
                px[x, y] = (255, 255, 255)
    return im


def emit(img):
    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for dpi, px in sizes.items():
        path = os.path.join(RES, f"mipmap-{dpi}")
        os.makedirs(path, exist_ok=True)
        small = img.resize((px, px), Image.LANCZOS)
        small.save(os.path.join(path, "ic_launcher.png"))
        mask = Image.new("L", (px, px), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, px - 1, px - 1], fill=255)
        rnd = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        rnd.paste(small, (0, 0), mask)
        rnd.save(os.path.join(path, "ic_launcher_round.png"))
    img.resize((512, 512), Image.LANCZOS).save(OUT_BIG)
    print("icons written")


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else SRC_DEFAULT
    im = Image.open(src).convert("RGB")
    # 原图原样使用，不做任何清理（用户要求保留截图里的细节）
    im = im.resize((1024, 1024), Image.LANCZOS)
    im.save(r"D:\Ruan Jian\DeepSeek\workspace\parcel\icon-source-1024.png")
    emit(im)


if __name__ == "__main__":
    main()
