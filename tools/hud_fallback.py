"""HUD 한국어 글귀에 Galmuri11 에 없는 글자(한자 같은 것)를 쓸 때, 그 글자만 대체 글꼴로 구운 그림을 둔다(2026-11-12 opening).

python -X utf8 tools/hud_fallback.py 故 [더 많은 글자 ...] [--font <otf/ttf>] [--size 11]

- 나온 그림: art/fonts/fallback/u<16진 코드>.png(흰 잉크 + 투명, 잉크 상자만, 높이 11 텍셀 이하 = Galmuri11 한글 잉크 줄 수).
  make_hud.py 의 ko_render 가 글귀 속 그 글자 자리에 이 그림을 끼워 그린다(글자를 바꾸지 않음). 다른 글자는 그대로 Galmuri11.
- 기본 글꼴: Source Han Sans K Medium(Adobe, SIL OFL 1.1, art/fonts/fallback/LICENSE.txt). 큰 글꼴 파일(16MB)은 복사하지 않고 그 글자 그림만 둔다.
  화소 글꼴이 아니라서 11 크기 단색(계조 없음)으로 그린다. 획이 Galmuri Bold 보다 가늘다(1 화소).
"""
import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "art/fonts/fallback"
FONT = Path("C:/Users/PC/Desktop/클로드/witchtrial/ref/manosaba/fonts/SourceHanSansKR.otf")
MAX_ROWS = 11  # make_hud: Galmuri11 한글 잉크 11 줄(KO_TOP 1 .. 11)


def bake(ch, font):
    m = Image.new("L", (font.size * 3, font.size * 3), 0)
    d = ImageDraw.Draw(m)
    d.fontmode = "1"
    d.text((font.size // 2, font.size // 2), ch, font=font, fill=255)
    ink = np.asarray(m) > 127
    ys, xs = np.nonzero(ink)
    if not len(ys):
        sys.exit(f"{ch!r}(U+{ord(ch):04X}): 글꼴에 없음(잉크 0)")
    ink = ink[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    if ink.shape[0] > MAX_ROWS:
        sys.exit(f"{ch!r}: 잉크 {ink.shape[0]} 줄 > {MAX_ROWS}(--size 를 줄일 것)")
    px = np.zeros(ink.shape + (4,), np.uint8)
    px[ink] = (255, 255, 255, 255)
    return Image.fromarray(px, "RGBA")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("chars", nargs="+")
    ap.add_argument("--font", default=str(FONT))
    ap.add_argument("--size", type=int, default=11)
    a = ap.parse_args()
    font = ImageFont.truetype(a.font, a.size)
    OUT.mkdir(parents=True, exist_ok=True)
    for ch in "".join(a.chars):
        img = bake(ch, font)
        p = OUT / f"u{ord(ch):04x}.png"
        img.save(p)
        print(f"{ch} U+{ord(ch):04X} → {p.relative_to(ROOT)} {img.width}x{img.height}")


if __name__ == "__main__":
    main()
