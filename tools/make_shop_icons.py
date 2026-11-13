"""상점 아이콘(총 담당): 상자 상점 칸, 핫바 3번 소모품, 던진 소모품 디스플레이, 증강 카드 그림을 그려 pack_src 에 쓴다.

python -X utf8 tools/make_shop_icons.py [--sheet build/guns/shop_icons.png]

쓰는 곳(모두 pack_src/assets/gave, 이 스크립트 몫):
- textures/item/shop/*.png, textures/item/shop/aug/*.png  (32x32, 128 에서 줄여 가장자리가 부드럽게, 투명 칸 색은 둘레 색으로 메움)
- models/item/shop/*.json  (item/generated 2D 아이콘, 총 뒤판 frame_*)
- items/shop/*.json        (아이템 정의 gave:shop/<키>)
  - gave:shop/gun: custom_model_data strings[0] = 등급(common, rare, epic, legendary, none) 뒤판, strings[1] = 총 종류(pistol, smg,
    shotgun, rifle, sniper) 소품 총 모델 gave:item/<종류>(소품 층, gui 표시 변환 그대로)
  - gave:shop/roll_<종류>: 뽑기 뒤판 + 그 총 모델
  - gave:shop/box: 갈래 무기 상자 칸(뽑기 뒤판 + strings[0] 대표 총 모델, BOXES). --box-only 면 이 정의만 씀
  - gave:shop/aug/<키>: 증강 카드(등급 테두리 + 그림), Augments.Aug 키와 같게
- 뒤판(frame_*)은 모델 z=1 평면(총 뒤)에 남쪽 면 하나, gui 밖 자리에서는 크기 0
그림은 모두 프로그램으로 그린 것(외부 그림 없음). 게이브 얼굴 무늬(연분홍 살, 적갈색 실눈)는 스킨 픽셀을 쓰지 않고 색만 맞춰 새로 그린다.
"""
import argparse
import json
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
PACK = ROOT / "pack_src/assets/gave"
TEX = PACK / "textures/item/shop"
MOD = PACK / "models/item/shop"
ITM = PACK / "items/shop"
S = 128  # 그리는 크기
OUT = 32  # 텍스처 크기
K = S / 32  # 32 칸 한 칸 = K 화소

INK = (42, 28, 30, 255)
SKIN = (255, 226, 219, 255)
EYE = (89, 27, 27, 255)
BLUSH = (255, 190, 175, 255)
GOLD = (255, 197, 61, 255)
TIER = {"common": (180, 180, 180), "rare": (79, 163, 255), "epic": (184, 107, 255), "legendary": (255, 197, 61)}


def new():
    return Image.new("RGBA", (S, S), (0, 0, 0, 0))


def P(*v):
    """32 칸 좌표 → 그림 좌표"""
    return [x * K for x in v]


def finish(img):
    """128 → 32 로 줄이고 투명 칸 RGB 를 둘레 색으로 메움(가장자리 검은 테두리 없게)"""
    small = img.resize((OUT, OUT), Image.LANCZOS)
    px = small.load()
    # 거의 투명한 것은 지움
    for y in range(OUT):
        for x in range(OUT):
            r, g, b, a = px[x, y]
            if a < 12:
                px[x, y] = (r, g, b, 0)
    # 색 메우기: 투명 칸에 이웃 불투명 칸 평균 색(알파 0 그대로)
    for _ in range(3):
        src = small.copy().load()
        for y in range(OUT):
            for x in range(OUT):
                if src[x, y][3] > 0:
                    continue
                acc, n = [0, 0, 0], 0
                for dy in (-1, 0, 1):
                    for dx in (-1, 0, 1):
                        xx, yy = x + dx, y + dy
                        if 0 <= xx < OUT and 0 <= yy < OUT and src[xx, yy][3] > 0:
                            c = src[xx, yy]
                            acc[0] += c[0]
                            acc[1] += c[1]
                            acc[2] += c[2]
                            n += 1
                if n:
                    px[x, y] = (acc[0] // n, acc[1] // n, acc[2] // n, 0)
    return small


def outline(img, width=1.2, color=INK):
    """그림 둘레에 어두운 테두리(알파를 넓혀 아래에 깔기)"""
    a = img.split()[3]
    grow = a.filter(ImageFilter.MaxFilter(int(width * K) * 2 + 1))
    base = Image.new("RGBA", img.size, color)
    base.putalpha(grow)
    base.alpha_composite(img)
    return base


def shade_circle(d, cx, cy, r, fill, light=None):
    d.ellipse(P(cx - r, cy - r, cx + r, cy + r), fill=fill)
    if light:
        d.ellipse(P(cx - r * 0.55, cy - r * 0.7, cx - r * 0.05, cy - r * 0.2), fill=light)


def slit_eyes(d, cx, cy, w, gap, thick=1.4, color=EYE):
    """게이브 실눈 웃음(위로 볼록한 짧은 호 둘)"""
    for sx in (-1, 1):
        x0 = cx + sx * gap - w / 2
        d.arc(P(x0, cy - w * 0.45, x0 + w, cy + w * 0.55), 200, 340, fill=color, width=int(thick * K))


# ------------------------------------------------------------------ 소모품, 상점 칸 그림

def flare():
    img = new()
    d = ImageDraw.Draw(img)
    # 기운 막대(빨강) + 캡 + 불꽃
    stick = [(9, 27), (6, 24), (19, 11), (22, 14)]
    d.polygon([tuple(P(*p)) for p in stick], fill=(214, 46, 46, 255))
    d.polygon([tuple(P(*p)) for p in [(8, 26), (7, 25), (19, 13), (20, 14)]], fill=(250, 110, 100, 255))
    d.polygon([tuple(P(*p)) for p in [(9, 27), (6, 24), (8, 22), (11, 25)]], fill=(60, 60, 64, 255))
    img = outline(img)
    d = ImageDraw.Draw(img)
    # 불꽃(바깥 주황, 안 노랑, 심 흰)
    for r, c in ((7.5, (255, 120, 40, 230)), (5, (255, 210, 70, 255)), (2.6, (255, 255, 235, 255))):
        d.ellipse(P(22 - r, 9 - r, 22 + r, 9 + r), fill=c)
    glow = img.filter(ImageFilter.GaussianBlur(2))
    glow.alpha_composite(img)
    return glow


def molotov():
    img = new()
    d = ImageDraw.Draw(img)
    # 병 몸통(초록 갈색 유리), 목, 천 조각
    d.rounded_rectangle(P(9, 13, 23, 29), radius=4 * K, fill=(96, 120, 60, 235))
    d.rectangle(P(13.5, 7, 18.5, 14), fill=(96, 120, 60, 235))
    d.rounded_rectangle(P(10.5, 15, 13, 26), radius=1 * K, fill=(170, 200, 120, 200))
    d.rectangle(P(9, 19, 23, 23), fill=(200, 170, 120, 230))  # 붙인 종이
    d.line(P(11, 21, 21, 21), fill=(120, 80, 60, 255), width=int(0.8 * K))
    d.polygon([tuple(P(*p)) for p in [(13, 7), (19, 7), (21, 3), (17, 5), (15, 2), (14, 5)]], fill=(230, 220, 200, 255))  # 천
    img = outline(img)
    d = ImageDraw.Draw(img)
    for r, c in ((4.5, (255, 120, 40, 230)), (2.8, (255, 210, 70, 255)), (1.3, (255, 255, 235, 255))):
        d.ellipse(P(17 - r, 3.5 - r, 17 + r, 3.5 + r), fill=c)
    return img


def rattle():
    img = new()
    d = ImageDraw.Draw(img)
    # 손잡이(분홍 띠 둘) + 둥근 머리(연분홍 살, 실눈, 점 입) + 방울 점
    d.rounded_rectangle(P(14.5, 17, 17.5, 30), radius=1.5 * K, fill=(250, 240, 230, 255))
    d.rectangle(P(14.5, 21, 17.5, 23), fill=(255, 170, 190, 255))
    d.rectangle(P(14.5, 26, 17.5, 28), fill=(255, 170, 190, 255))
    shade_circle(d, 16, 11, 9, SKIN, (255, 245, 240, 255))
    img = outline(img)
    d = ImageDraw.Draw(img)
    slit_eyes(d, 16, 10.5, 4.5, 3.2)
    d.ellipse(P(15.2, 14, 16.8, 15.6), fill=(66, 20, 20, 255))
    for x, y in ((9.5, 9), (22.5, 9), (11, 16), (21, 16)):
        d.ellipse(P(x - 0.9, y - 0.9, x + 0.9, y + 0.9), fill=BLUSH)
    return img


def cleanse():
    img = new()
    d = ImageDraw.Draw(img)
    d.rounded_rectangle(P(10, 12, 22, 29), radius=4 * K, fill=(200, 235, 255, 170))
    d.rounded_rectangle(P(11, 18, 21, 28), radius=3.5 * K, fill=(90, 200, 255, 245))
    d.rectangle(P(13.5, 6, 18.5, 12.5), fill=(200, 235, 255, 190))
    d.rectangle(P(13, 4, 19, 7), fill=(170, 130, 90, 255))
    d.rounded_rectangle(P(12, 19, 14, 26), radius=1 * K, fill=(220, 250, 255, 220))
    img = outline(img)
    d = ImageDraw.Draw(img)
    # 반짝임
    for cx, cy, r in ((25, 9, 2.6), (6, 15, 1.8)):
        d.polygon([tuple(P(*p)) for p in [(cx, cy - r), (cx + r * 0.3, cy - r * 0.3), (cx + r, cy), (cx + r * 0.3, cy + r * 0.3), (cx, cy + r),
                                          (cx - r * 0.3, cy + r * 0.3), (cx - r, cy), (cx - r * 0.3, cy - r * 0.3)]], fill=(255, 255, 255, 255))
    return img


def raincoat():
    img = new()
    d = ImageDraw.Draw(img)
    y = (242, 196, 58, 255)
    d.polygon([tuple(P(*p)) for p in [(16, 3), (22, 6), (24, 10), (28, 14), (27, 18), (24, 16), (24, 29), (8, 29), (8, 16), (5, 18), (4, 14),
                                      (8, 10), (10, 6)]], fill=y)
    d.ellipse(P(12, 4.5, 20, 11), fill=(190, 140, 30, 255))  # 모자 안
    d.line(P(16, 11, 16, 29), fill=(200, 150, 40, 255), width=int(1 * K))
    img = outline(img)
    d = ImageDraw.Draw(img)
    # 적어 넣은 글귀(삐뚤삐뚤 줄)
    for i, yy in enumerate((15, 18.5, 22, 25.5)):
        x0 = 9.5 + (i % 2) * 0.8
        pts = [P(x0 + t * 1.6, yy + math.sin(t * 1.7 + i) * 0.5) for t in range(0, 4)]
        d.line([tuple(p) for p in pts], fill=(70, 40, 40, 255), width=int(0.8 * K))
        pts = [P(17.5 + t * 1.5, yy + 0.4 + math.sin(t * 1.3 + i) * 0.5) for t in range(0, 4)]
        d.line([tuple(p) for p in pts], fill=(70, 40, 40, 255), width=int(0.8 * K))
    return img


def bandage():
    img = new()
    d = ImageDraw.Draw(img)
    # 감긴 붕대 두루마리(베이지) + 풀린 꼬리
    d.polygon([tuple(P(*p)) for p in [(18, 21), (29, 25), (28, 29), (16, 25)]], fill=(240, 232, 214, 255))
    d.ellipse(P(5, 7, 23, 25), fill=(246, 240, 226, 255))
    d.ellipse(P(10, 12, 18, 20), fill=(214, 200, 176, 255))
    d.ellipse(P(12.5, 14.5, 15.5, 17.5), fill=(150, 130, 110, 255))
    for r in (7.5, 6):
        d.arc(P(14 - r, 16 - r, 14 + r, 16 + r), 200, 320, fill=(220, 210, 190, 255), width=int(0.7 * K))
    img = outline(img)
    return img


def upgrade():
    img = new()
    d = ImageDraw.Draw(img)
    for i, (y, c) in enumerate(((4, (255, 225, 120, 255)), (12, GOLD), (20, (230, 160, 40, 255)))):
        d.polygon([tuple(P(*p)) for p in [(16, y), (27, y + 9), (22, y + 9), (16, y + 4.5), (10, y + 9), (5, y + 9)]], fill=c)
    return outline(img)


def coin():
    img = new()
    d = ImageDraw.Draw(img)
    d.ellipse(P(4, 4, 28, 28), fill=(214, 150, 30, 255))
    d.ellipse(P(5.5, 5, 26.5, 26), fill=GOLD)
    d.ellipse(P(8, 8, 24, 24), outline=(214, 150, 30, 255), width=int(1 * K))
    img = outline(img)
    d = ImageDraw.Draw(img)
    slit_eyes(d, 16, 14.5, 4, 3, 1.3, (150, 90, 20, 255))
    d.ellipse(P(15.2, 18.5, 16.8, 20.1), fill=(150, 90, 20, 255))
    d.ellipse(P(8, 7, 13, 10), fill=(255, 245, 200, 200))
    return img


def heart(d, cx, cy, s, fill):
    pts = []
    for i in range(64):
        t = i / 64 * 2 * math.pi
        x = 16 * math.sin(t) ** 3
        y = -(13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t))
        pts.append(tuple(P(cx + x * s / 17, cy + y * s / 17)))
    d.polygon(pts, fill=fill)


def stat_hp():
    img = new()
    d = ImageDraw.Draw(img)
    heart(d, 15, 16, 12, (230, 70, 80, 255))
    heart(d, 13, 13, 4, (255, 160, 160, 255))
    img = outline(img)
    d = ImageDraw.Draw(img)
    d.rectangle(P(22, 19, 30, 22), fill=(124, 240, 124, 255), outline=INK, width=int(0.6 * K))
    d.rectangle(P(24.5, 16.5, 27.5, 24.5), fill=(124, 240, 124, 255), outline=INK, width=int(0.6 * K))
    d.rectangle(P(22.6, 19.6, 29.4, 21.4), fill=(124, 240, 124, 255))
    return img


def shield(d, cx, cy, s, fill, inner=None):
    pts = [(cx - s, cy - s * 0.9), (cx, cy - s * 1.15), (cx + s, cy - s * 0.9), (cx + s * 0.95, cy + s * 0.1), (cx, cy + s * 1.2),
           (cx - s * 0.95, cy + s * 0.1)]
    d.polygon([tuple(P(*p)) for p in pts], fill=fill)
    if inner:
        k = 0.62
        d.polygon([tuple(P(cx + (x - cx) * k, cy + (y - cy) * k)) for x, y in pts], fill=inner)


def stat_armor():
    img = new()
    d = ImageDraw.Draw(img)
    shield(d, 16, 15, 11, (120, 140, 160, 255), (170, 190, 210, 255))
    d.line(P(16, 6, 16, 26), fill=(120, 140, 160, 255), width=int(1.4 * K))
    return outline(img)


def stat_move():
    img = new()
    d = ImageDraw.Draw(img)
    # 장화 + 바람 줄
    d.polygon([tuple(P(*p)) for p in [(12, 5), (19, 5), (19, 19), (27, 22), (27, 27), (10, 27), (10, 20)]], fill=(150, 100, 70, 255))
    d.rectangle(P(10, 24, 27, 27), fill=(90, 60, 45, 255))
    d.rectangle(P(12, 8, 19, 10), fill=(190, 140, 100, 255))
    img = outline(img)
    d = ImageDraw.Draw(img)
    for y, w in ((9, 6), (14, 8), (19, 5)):
        d.line(P(1, y, 1 + w, y), fill=(220, 240, 255, 255), width=int(1.2 * K))
    return img


def stat_reload():
    img = new()
    d = ImageDraw.Draw(img)
    d.rounded_rectangle(P(11, 8, 21, 29), radius=1.5 * K, fill=(80, 84, 90, 255))
    d.rectangle(P(12.5, 10, 19.5, 27), fill=(110, 116, 124, 255))
    for y in (4, 6.5):
        d.rounded_rectangle(P(13, y, 19, y + 2.4), radius=1 * K, fill=(220, 170, 60, 255))
    img = outline(img)
    d = ImageDraw.Draw(img)
    d.arc(P(3, 3, 29, 29), 300, 600, fill=(124, 240, 124, 255), width=int(1.6 * K))
    d.polygon([tuple(P(*p)) for p in [(26.5, 5), (29.5, 11), (23, 10.5)]], fill=(124, 240, 124, 255))
    return img


def aug_roll():
    img = new()
    d = ImageDraw.Draw(img)
    for i, (tier, ang) in enumerate((("common", -18), ("rare", 0), ("epic", 18))):
        card = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        cd = ImageDraw.Draw(card)
        c = TIER[tier]
        cd.rounded_rectangle(P(10, 5, 22, 25), radius=2 * K, fill=(30, 26, 34, 255), outline=c + (255,), width=int(1.4 * K))
        cd.ellipse(P(13.5, 11, 18.5, 16), fill=c + (255,))
        card = card.rotate(-ang, center=tuple(P(16, 28)), resample=Image.BICUBIC)
        img.alpha_composite(card)
    return outline(img, 0.8)


def eye():
    """상점 오염 실눈 글리프: 연분홍 살 조각에 적갈색 실눈 둘, 볼 홍조"""
    img = new()
    d = ImageDraw.Draw(img)
    d.rounded_rectangle(P(3, 7, 29, 25), radius=7 * K, fill=SKIN)
    slit_eyes(d, 16, 15.5, 7, 6, 2.0)
    d.ellipse(P(4.5, 18, 9.5, 21), fill=BLUSH)
    d.ellipse(P(22.5, 18, 27.5, 21), fill=BLUSH)
    d.ellipse(P(15, 20.5, 17, 22.5), fill=(66, 20, 20, 255))
    return img


def gnawed():
    """갉힌 칸: 회색 판에 이빨 자국으로 뜯긴 모서리"""
    img = new()
    d = ImageDraw.Draw(img)
    d.rounded_rectangle(P(4, 4, 28, 28), radius=3 * K, fill=(96, 92, 92, 255))
    for i in range(5):
        cx = 30 - i * 4.2
        d.ellipse(P(cx - 3.2, 1 + i * 3.6, cx + 3.2, 7.4 + i * 3.6), fill=(0, 0, 0, 0))
    d.polygon([tuple(P(*p)) for p in [(19, 9), (24, 4), (29, 4), (29, 13)]], fill=(0, 0, 0, 0))
    for x, y in ((8, 9), (11, 12), (8, 15), (12, 19), (9, 23)):
        d.line(P(x, y, x + 3, y + 1.5), fill=(60, 56, 56, 255), width=int(0.9 * K))
    return outline(img, 0.8)


def ammo():
    img = new()
    d = ImageDraw.Draw(img)
    for i, x in enumerate((7, 13, 19)):
        d.rounded_rectangle(P(x, 12, x + 5, 28), radius=1 * K, fill=(220, 170, 60, 255))
        d.polygon([tuple(P(*p)) for p in [(x, 12), (x + 2.5, 5), (x + 5, 12)]], fill=(190, 110, 70, 255))
    return outline(img)


def frame(tier):
    """총 뒤판: 등급 색 테두리(둥근), 안은 같은 색 옅게(총이 위에 그려짐)"""
    img = new()
    d = ImageDraw.Draw(img)
    if tier == "none":
        return img
    c = TIER[tier]
    d.rounded_rectangle(P(1, 1, 31, 31), radius=5 * K, fill=c + (46,), outline=c + (255,), width=int(1.4 * K))
    d.rounded_rectangle(P(3, 3, 29, 29), radius=4 * K, outline=c + (90,), width=int(0.8 * K))
    return img


def frame_roll():
    """뽑기 뒤판: 금색 점선 테두리 + 오른쪽 아래 물음표"""
    img = new()
    d = ImageDraw.Draw(img)
    d.rounded_rectangle(P(1, 1, 31, 31), radius=5 * K, fill=(60, 40, 80, 120))
    for i in range(16):
        t0 = i / 16 * 2 * math.pi
        x0, y0 = 16 + 14.3 * math.cos(t0), 16 + 14.3 * math.sin(t0)
        d.ellipse(P(x0 - 1, y0 - 1, x0 + 1, y0 + 1), fill=GOLD)
    q = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    qd = ImageDraw.Draw(q)
    qd.arc(P(21, 19, 28, 26), 180, 90, fill=(255, 235, 160, 255), width=int(1.5 * K))
    qd.line(P(24.5, 26, 24.5, 27.5), fill=(255, 235, 160, 255), width=int(1.5 * K))
    qd.ellipse(P(23.6, 28.4, 25.4, 30.2), fill=(255, 235, 160, 255))
    img.alpha_composite(outline(q, 0.6))
    return img


# ------------------------------------------------------------------ 증강 카드 그림(가운데 기호)

def sym_bullet(d, cx=16, cy=16, s=1.0, n=1):
    for i in range(n):
        x = cx + (i - (n - 1) / 2) * 5 * s
        d.rounded_rectangle(P(x - 2 * s, cy - 2 * s, x + 2 * s, cy + 7 * s), radius=0.6 * K, fill=(230, 180, 70, 255))
        d.polygon([tuple(P(*p)) for p in [(x - 2 * s, cy - 2 * s), (x, cy - 8 * s), (x + 2 * s, cy - 2 * s)]], fill=(205, 120, 80, 255))


def sym_bolt(d, cx=16, cy=16, s=1.0, color=(255, 230, 90, 255)):
    pts = [(cx + 1 * s, cy - 9 * s), (cx - 5 * s, cy + 1 * s), (cx - 0.5 * s, cy + 1 * s), (cx - 2 * s, cy + 9 * s), (cx + 5 * s, cy - 2 * s),
           (cx + 0.5 * s, cy - 2 * s)]
    d.polygon([tuple(P(*p)) for p in pts], fill=color)


def sym_mag(d, empty=False):
    d.rounded_rectangle(P(12, 10, 20, 25), radius=1 * K, fill=(90, 95, 104, 255))
    d.rectangle(P(13.3, 11.5, 18.7, 23.5), fill=(130, 136, 146, 255) if not empty else (50, 50, 56, 255))
    if not empty:
        for y in (6, 8.5):
            d.rounded_rectangle(P(13.5, y, 18.5, y + 2.2), radius=0.8 * K, fill=(230, 180, 70, 255))
    else:
        d.rounded_rectangle(P(13.5, 8.5, 18.5, 10.7), radius=0.8 * K, fill=(230, 180, 70, 255))


def sym_cycle(d, color=(124, 240, 124, 255)):
    d.arc(P(8, 8, 24, 24), 320, 590, fill=color, width=int(1.8 * K))
    d.polygon([tuple(P(*p)) for p in [(22, 7.5), (25.5, 13), (19, 12.5)]], fill=color)


def sym_cross(d, color=(255, 255, 255, 255)):
    d.ellipse(P(9, 9, 23, 23), outline=color, width=int(1.4 * K))
    for a, b, c2, e in ((16, 6, 16, 12), (16, 20, 16, 26), (6, 16, 12, 16), (20, 16, 26, 16)):
        d.line(P(a, b, c2, e), fill=color, width=int(1.4 * K))
    d.ellipse(P(15, 15, 17, 17), fill=color)


def sym_spring(d):
    pts = []
    for i in range(9):
        pts.append(P(11 if i % 2 == 0 else 21, 7 + i * 2.2))
    d.line([tuple(p) for p in pts], fill=(200, 210, 220, 255), width=int(1.4 * K))
    d.rectangle(P(9, 24.5, 23, 26.5), fill=(150, 160, 170, 255))


def sym_eye(d):
    d.chord(P(6, 10, 26, 26), 200, 340, fill=(240, 240, 240, 255))
    d.chord(P(6, 6, 26, 22), 20, 160, fill=(240, 240, 240, 255))
    d.ellipse(P(12.5, 12.5, 19.5, 19.5), fill=(70, 140, 220, 255))
    d.ellipse(P(14.5, 14.5, 17.5, 17.5), fill=(20, 20, 30, 255))


def sym_belt(d):
    d.line(P(5, 22, 27, 12), fill=(110, 80, 50, 255), width=int(3 * K))
    for i in range(5):
        x = 7 + i * 4.4
        y = 21 - i * 2
        d.rounded_rectangle(P(x - 1.2, y - 6, x + 1.2, y), radius=0.5 * K, fill=(230, 180, 70, 255))


def sym_crouch(d):
    sym_cross(d, (200, 220, 255, 255))
    d.polygon([tuple(P(*p)) for p in [(10, 25), (22, 25), (16, 30)]], fill=(124, 240, 124, 255))


def sym_drop(d, color=(220, 50, 60, 255)):
    d.polygon([tuple(P(*p)) for p in [(16, 5), (10.5, 16), (21.5, 16)]], fill=color)
    d.ellipse(P(10, 11, 22, 24), fill=color)
    d.ellipse(P(12.5, 14, 15, 18), fill=(255, 150, 150, 255))


def sym_coin(d):
    """동전 둘 겹침(상점 동전과 같은 실눈 얼굴)"""
    for cx, cy in ((19, 19), (13, 13)):
        d.ellipse(P(cx - 7, cy - 7, cx + 7, cy + 7), fill=GOLD, outline=(200, 140, 30, 255), width=int(1 * K))
    slit_eyes(d, 13, 12, 2.6, 2.2, 1.0, (150, 90, 20, 255))
    d.ellipse(P(12.4, 15, 13.6, 16.2), fill=(150, 90, 20, 255))


def sym_heart(d):
    heart(d, 16, 16, 10, (230, 70, 80, 255))
    heart(d, 14, 13, 3.5, (255, 160, 160, 255))


def sym_shield(d):
    shield(d, 16, 15.5, 8.5, (150, 170, 190, 255), (200, 215, 230, 255))


def sym_wing(d):
    for i in range(2):
        x = 8 + i * 8
        d.polygon([tuple(P(*p)) for p in [(x, 8), (x + 9, 16), (x, 24), (x + 3, 16)]], fill=(220, 240, 255, 255))


def sym_hourglass(d):
    d.rectangle(P(9, 6, 23, 8), fill=(170, 130, 90, 255))
    d.rectangle(P(9, 24, 23, 26), fill=(170, 130, 90, 255))
    d.polygon([tuple(P(*p)) for p in [(11, 8), (21, 8), (16, 16), (21, 24), (11, 24), (16, 16)]], fill=(220, 235, 255, 200))
    d.polygon([tuple(P(*p)) for p in [(13, 22), (19, 22), (21, 24), (11, 24)]], fill=(220, 60, 70, 255))
    d.polygon([tuple(P(*p)) for p in [(12.5, 9), (19.5, 9), (16, 13)]], fill=(220, 60, 70, 255))


def sym_pierce(d):
    d.ellipse(P(10, 10, 22, 22), outline=(240, 240, 240, 255), width=int(1.6 * K))
    d.line(P(4, 24, 26, 8), fill=(255, 230, 120, 255), width=int(1.6 * K))
    d.polygon([tuple(P(*p)) for p in [(28, 6.5), (21.5, 8), (25, 12)]], fill=(255, 230, 120, 255))


def sym_star(d):
    pts = []
    for i in range(16):
        r = 10 if i % 2 == 0 else 4
        a = i / 16 * 2 * math.pi - math.pi / 2
        pts.append(tuple(P(16 + r * math.cos(a), 16 + r * math.sin(a))))
    d.polygon(pts, fill=(255, 90, 80, 255))
    d.ellipse(P(13.5, 13.5, 18.5, 18.5), fill=(255, 230, 200, 255))


def sym_pellets(d):
    for x, y in ((9, 16), (14, 10), (14, 22), (20, 7), (20, 16), (20, 25), (25, 12), (25, 20)):
        d.ellipse(P(x - 1.6, y - 1.6, x + 1.6, y + 1.6), fill=(210, 210, 220, 255))


def sym_band(d, which):
    """네 띠(하늘, 머리, 허리, 바닥) 중 하나 밝게"""
    for i, y in enumerate((7, 13, 19, 25)):
        on = i == which
        d.rounded_rectangle(P(7, y - 1.6, 25, y + 1.6), radius=1.4 * K, fill=(255, 197, 61, 255) if on else (90, 90, 100, 255))
    d.polygon([tuple(P(*p)) for p in [(3, (7, 13, 19, 25)[which] - 3), (6.5, (7, 13, 19, 25)[which]), (3, (7, 13, 19, 25)[which] + 3)]],
              fill=(255, 255, 255, 255))


def sym_swarm(d):
    for i in range(14):
        a = i * 2.4
        r = 2.5 + (i % 4) * 2.3
        x, y = 16 + r * math.cos(a), 16 + r * math.sin(a)
        d.ellipse(P(x - 1.3, y - 1.3, x + 1.3, y + 1.3), fill=SKIN)


def sym_gold(d):
    d.ellipse(P(7, 7, 25, 25), fill=SKIN)
    slit_eyes(d, 16, 14.5, 4, 3.3, 1.3)
    d.ellipse(P(8.5, 17, 12.5, 20), fill=GOLD)
    d.ellipse(P(19.5, 17, 23.5, 20), fill=GOLD)
    d.ellipse(P(15.2, 19.5, 16.8, 21.1), fill=(66, 20, 20, 255))


def sym_revive(d):
    d.ellipse(P(7, 7, 25, 25), fill=(60, 170, 110, 255))
    d.polygon([tuple(P(*p)) for p in [(16, 9), (22, 16), (18.5, 16), (18.5, 22), (13.5, 22), (13.5, 16), (10, 16)]], fill=(240, 255, 240, 255))


def sym_nade(d):
    d.ellipse(P(9, 11, 23, 27), fill=(90, 110, 70, 255))
    for y in (15, 19, 23):
        d.line(P(10, y, 22, y), fill=(60, 80, 50, 255), width=int(0.8 * K))
    d.rectangle(P(13.5, 7, 18.5, 11.5), fill=(120, 120, 128, 255))
    d.line(P(18, 8, 24, 13), fill=(160, 160, 168, 255), width=int(1.2 * K))
    d.ellipse(P(11.5, 3.5, 15.5, 7.5), outline=(200, 200, 208, 255), width=int(0.8 * K))


def sym_hollow(d):
    sym_mag(d, empty=True)
    for r, c in ((4.2, (255, 120, 40, 230)), (2.6, (255, 210, 70, 255))):
        d.ellipse(P(16 - r, 4.5 - r, 16 + r, 4.5 + r), fill=c)


def sym_crown(d):
    d.polygon([tuple(P(*p)) for p in [(6, 23), (6, 10), (11, 16), (16, 7), (21, 16), (26, 10), (26, 23)]], fill=GOLD)
    d.rectangle(P(6, 22, 26, 25), fill=(220, 150, 40, 255))
    for x in (11, 16, 21):
        d.ellipse(P(x - 1.2, 18.5, x + 1.2, 20.9), fill=(220, 60, 90, 255))


def sym_execute(d):
    d.rounded_rectangle(P(5, 13, 27, 19), radius=2 * K, fill=(50, 50, 56, 255))
    d.rounded_rectangle(P(5.8, 13.8, 12, 18.2), radius=1.6 * K, fill=(230, 70, 80, 255))
    d.line(P(9, 27, 24, 5), fill=(240, 240, 250, 255), width=int(1.6 * K))
    d.polygon([tuple(P(*p)) for p in [(25.5, 3), (21.5, 6), (24.5, 8)]], fill=(240, 240, 250, 255))


AUGS = {
    # Augments.Aug 키: (등급, 그리기)
    "dmg": ("common", lambda d: sym_bullet(d)),
    "rate": ("common", lambda d: sym_bolt(d)),
    "mag": ("common", lambda d: sym_mag(d)),
    "reload": ("common", lambda d: sym_cycle(d)),
    "steady": ("common", lambda d: sym_cross(d)),
    "grip": ("common", lambda d: sym_spring(d)),
    "reach": ("common", lambda d: sym_eye(d)),
    "reserve": ("common", lambda d: sym_belt(d)),
    "crouch": ("common", lambda d: sym_crouch(d)),
    "leech": ("common", lambda d: sym_drop(d)),
    "bounty": ("common", lambda d: sym_coin(d)),
    "hp": ("common", lambda d: sym_heart(d)),
    "armor": ("common", lambda d: sym_shield(d)),
    "move": ("common", lambda d: sym_wing(d)),
    "bleed": ("common", lambda d: sym_hourglass(d)),
    "pierce": ("rare", lambda d: sym_pierce(d)),
    "crit": ("rare", lambda d: sym_star(d)),
    "pellets": ("rare", lambda d: sym_pellets(d)),
    "head": ("rare", lambda d: sym_band(d, 1)),
    "waist": ("rare", lambda d: sym_band(d, 2)),
    "ground": ("rare", lambda d: sym_band(d, 3)),
    "sky": ("rare", lambda d: sym_band(d, 0)),
    "swarm": ("rare", lambda d: sym_swarm(d)),
    "gold": ("rare", lambda d: sym_gold(d)),
    "revive": ("rare", lambda d: sym_revive(d)),
    "nade": ("rare", lambda d: sym_nade(d)),
    "dmg2": ("epic", lambda d: sym_bullet(d, 16, 17, 0.9, 2)),
    "rate2": ("epic", lambda d: (sym_bolt(d, 12, 16, 0.8), sym_bolt(d, 20, 16, 0.8))),
    "hollow": ("epic", lambda d: sym_hollow(d)),
    "elite": ("epic", lambda d: sym_crown(d)),
    "execute": ("epic", lambda d: sym_execute(d)),
}


def card(tier, draw):
    img = new()
    d = ImageDraw.Draw(img)
    c = TIER[tier]
    d.rounded_rectangle(P(2, 1, 30, 31), radius=4 * K, fill=(28, 24, 32, 255), outline=c + (255,), width=int(1.6 * K))
    d.rounded_rectangle(P(4, 3, 28, 29), radius=3 * K, outline=c + (80,), width=int(0.8 * K))
    sym = new()
    draw(ImageDraw.Draw(sym))
    img.alpha_composite(outline(sym, 0.7))
    return img


# ------------------------------------------------------------------ 쓰기

ICONS = {
    "flare": flare, "molotov": molotov, "rattle": rattle, "cleanse": cleanse, "raincoat": raincoat, "bandage": bandage, "upgrade": upgrade,
    "coin": coin, "stat_hp": stat_hp, "stat_armor": stat_armor, "stat_move": stat_move, "stat_reload": stat_reload, "aug_roll": aug_roll,
    "eye": eye, "gnawed": gnawed, "ammo": ammo,
}
GUNS = ("pistol", "smg", "shotgun", "rifle", "sniper")
HELD = {"flare", "molotov", "rattle", "cleanse", "raincoat", "bandage"}
# 갈래 무기 상자 칸 그림 gave:shop/box(2026-11-12 boxsplit): 상자 뒤판 frame_roll + strings[0] 대표 총 모델(소품 층 gave:item/<키>, 종류 키 사본).
# 키는 Shop.Crate 의 icon 과 같게(권총, 기관단총, 소총, 기관총, 샷건, 저격, 특수, 폭발)
BOXES = ("revolver", "smg", "rifle", "lmg", "shotgun", "sniper", "flamethrower", "rocket")


def box_item():
    """gave:shop/box: 뒤판 + 대표 총(새 무기 모델은 모든 면 tintindex 0 이라 상점 총 그림 weapon_icon 처럼 흰색 고정 틴트)"""
    white = [{"type": "minecraft:constant", "value": 0xFFFFFF}]
    guns = {"type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
            "cases": [{"when": k, "model": {"type": "minecraft:model", "model": f"gave:item/{k}", "tints": white}} for k in BOXES],
            "fallback": {"type": "minecraft:model", "model": "gave:item/weapon_crate"}}
    dump(ITM / "box.json", {"model": {"type": "minecraft:composite", "models": [
        {"type": "minecraft:model", "model": "gave:item/shop/frame_roll"}, guns]}})


def dump(path: Path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, indent=1, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")


def generated(name, tex):
    dump(MOD / f"{name}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": f"gave:item/shop/{tex}"}})


def flat_back(name, tex):
    """총 뒤 평면(z=1, 남쪽 면만, 그늘 없음). gui 밖 자리는 크기 0"""
    hide = {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0, 0, 0]}
    disp = {c: hide for c in ("thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand", "firstperson_lefthand", "head", "ground",
                              "fixed", "on_shelf")}
    disp["gui"] = {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}
    dump(MOD / f"{name}.json", {"textures": {"f": f"gave:item/shop/{tex}", "particle": f"gave:item/shop/{tex}"}, "gui_light": "front",
                                "elements": [{"from": [0, 0, 1], "to": [16, 16, 1], "shade": False,
                                              "faces": {"south": {"uv": [0, 0, 16, 16], "texture": "#f"}}}],
                                "display": disp})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sheet", help="모든 그림을 한 장에 모은 미리보기 png")
    ap.add_argument("--box-only", action="store_true", help="갈래 무기 상자 아이템 정의 items/shop/box.json 만 씀(그림은 다시 그리지 않음)")
    args = ap.parse_args()
    if args.box_only:
        box_item()
        print("갈래 무기 상자 그림 정의", (ITM / "box.json").relative_to(ROOT))
        return
    made = {}
    for name, fn in ICONS.items():
        made[name] = finish(fn())
    for tier in ("common", "rare", "epic", "legendary", "none"):
        made[f"frame_{tier}"] = finish(frame(tier))
    made["frame_roll"] = finish(frame_roll())
    for key, (tier, draw) in AUGS.items():
        made[f"aug/{key}"] = finish(card(tier, draw))
    for name, img in made.items():
        p = TEX / f"{name}.png"
        p.parent.mkdir(parents=True, exist_ok=True)
        img.save(p, optimize=True)
    # 모델, 아이템 정의
    for name in ICONS:
        generated(name, name)
        d = {"model": {"type": "minecraft:model", "model": f"gave:item/shop/{name}"}}
        if name in HELD:
            d["hand_animation_on_swap"] = False
        dump(ITM / f"{name}.json", d)
    for key in AUGS:
        generated(f"aug/{key}", f"aug/{key}")
        dump(ITM / f"aug/{key}.json", {"model": {"type": "minecraft:model", "model": f"gave:item/shop/aug/{key}"}})
    for tier in ("common", "rare", "epic", "legendary", "none"):
        flat_back(f"frame_{tier}", f"frame_{tier}")
    flat_back("frame_roll", "frame_roll")
    frames = {"type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
              "cases": [{"when": t, "model": {"type": "minecraft:model", "model": f"gave:item/shop/frame_{t}"}}
                        for t in ("common", "rare", "epic", "legendary")],
              "fallback": {"type": "minecraft:model", "model": "gave:item/shop/frame_none"}}
    guns = {"type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 1,
            "cases": [{"when": g, "model": {"type": "minecraft:model", "model": f"gave:item/{g}"}} for g in GUNS],
            "fallback": {"type": "minecraft:model", "model": "gave:item/pistol"}}
    dump(ITM / "gun.json", {"model": {"type": "minecraft:composite", "models": [frames, guns]}})
    for g in GUNS[1:]:
        dump(ITM / f"roll_{g}.json", {"model": {"type": "minecraft:composite", "models": [
            {"type": "minecraft:model", "model": "gave:item/shop/frame_roll"}, {"type": "minecraft:model", "model": f"gave:item/{g}"}]}})
    box_item()
    n_png = len(made)
    print(f"상점 아이콘: 그림 {n_png}, 증강 카드 {len(AUGS)}, 아이템 정의 {len(list(ITM.rglob('*.json')))} -> {PACK.relative_to(ROOT)}")
    if args.sheet:
        cols = 12
        rows = (n_png + cols - 1) // cols
        sheet = Image.new("RGBA", (cols * 72, rows * 72), (58, 58, 64, 255))
        for i, (name, img) in enumerate(made.items()):
            big = img.resize((64, 64), Image.NEAREST)
            sheet.alpha_composite(big, ((i % cols) * 72 + 4, (i // cols) * 72 + 4))
        out = ROOT / args.sheet
        out.parent.mkdir(parents=True, exist_ok=True)
        sheet.save(out)
        print("미리보기", out)


if __name__ == "__main__":
    main()
