"""生成 App 图标：深色底 + 气泡「查包裹」+ 简笔女仆头。输出各密度 mipmap。"""
import os
from PIL import Image, ImageDraw, ImageFont

OUT = r"D:\Ruan Jian\DeepSeek\workspace\parcel\android\app\src\main\res"
S = 1024  # 先大图再缩

BG = (20, 23, 31)
HAIR = (61, 79, 168)
HAIR_D = (43, 57, 128)
SKIN = (255, 232, 219)
WHITE = (255, 255, 255)
INK = (26, 38, 92)
EYE = (46, 63, 140)
BLUSH = (255, 176, 176)


def rounded_bg(d):
    d.rectangle([0, 0, S, S], fill=BG)


def bubble(d, fnt):
    # 气泡
    box = [70, 120, 700, 430]
    d.ellipse(box, fill=WHITE, outline=INK, width=16)
    # 小尾巴
    d.polygon([(180, 415), (150, 520), (280, 430)], fill=WHITE)
    d.line([(180, 415), (150, 520)], fill=INK, width=16)
    d.line([(150, 520), (280, 432)], fill=INK, width=16)
    # 文字
    text = "查包裹"
    tw = d.textlength(text, font=fnt)
    d.text((box[0] + (box[2] - box[0] - tw) / 2, 205), text, font=fnt, fill=INK)


def character(d):
    # 头发（大圆）
    d.ellipse([520, 430, 1000, 930], fill=HAIR)
    # 脸
    d.ellipse([590, 540, 950, 910], fill=SKIN)
    # 刘海：几团圆弧压在额头上
    for cx, cy, r in ((610, 560, 90), (720, 535, 95), (830, 535, 95), (930, 560, 85)):
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=HAIR)
    # 两侧鬓角
    d.ellipse([520, 560, 640, 800], fill=HAIR)
    d.ellipse([880, 560, 1000, 800], fill=HAIR)
    # 女仆头饰：一排白色泡泡
    for i in range(5):
        x = 590 + i * 76
        y = 470 - abs(2 - i) * 16
        d.ellipse([x, y, x + 92, y + 64], fill=WHITE, outline=HAIR_D, width=6)
    # 眼睛
    for cx in (700, 850):
        d.ellipse([cx - 46, 660, cx + 46, 780], fill=WHITE)
        d.ellipse([cx - 30, 686, cx + 30, 772], fill=EYE)
        d.ellipse([cx - 18, 700, cx - 4, 726], fill=WHITE)
        d.ellipse([cx + 6, 748, cx + 16, 762], fill=WHITE)
    # 腮红
    d.ellipse([622, 780, 692, 820], fill=BLUSH)
    d.ellipse([858, 780, 928, 820], fill=BLUSH)
    # 嘴
    d.arc([748, 786, 806, 834], 20, 160, fill=INK, width=8)


def build():
    font_path = r"C:\Windows\Fonts\msyhbd.ttc"
    fnt = ImageFont.truetype(font_path, 150)
    img = Image.new("RGBA", (S, S), BG)
    d = ImageDraw.Draw(img)
    rounded_bg(d)
    character(d)
    bubble(d, fnt)
    return img


def emit(img):
    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for dpi, px in sizes.items():
        path = os.path.join(OUT, f"mipmap-{dpi}")
        os.makedirs(path, exist_ok=True)
        small = img.resize((px, px), Image.LANCZOS)
        small.save(os.path.join(path, "ic_launcher.png"))
        # 圆形版：圆角遮罩
        mask = Image.new("L", (px, px), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, px - 1, px - 1], fill=255)
        round_img = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        round_img.paste(small, (0, 0), mask)
        round_img.save(os.path.join(path, "ic_launcher_round.png"))
    big = img.resize((512, 512), Image.LANCZOS)
    big.save(r"D:\Ruan Jian\DeepSeek\workspace\parcel\icon-512.png")
    print("icons written")


if __name__ == "__main__":
    emit(build())
