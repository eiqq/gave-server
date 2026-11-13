"""G173 외투 게이브: 멀리서는 긴 외투를 여미고 고개를 숙인 채 걷는 사람 그림자(키 1.9), 가까이서 보면 외투는 접힌 날개 판 둘이고 깃 사이로
엔드 막대기 끝 둘이 옷걸이 어깨처럼 삐죽 나와 있다. 펼치면 날개 판 안쪽 가득 얼굴이 비늘처럼 박혀 있고(폭 3.2) 가슴 한가운데 0.7 얼굴이 눈을 뜬다.

모델 gave:gabe/g173(shrink 0.75), 몸 중심은 땅 위 0.95(서 있을 때, Java):
- "coat"(접힘): 어두운 천 날개 판 둘이 몸을 감싼 사람 윤곽, 숙인 두건, 깃 위 막대기 끝 둘, 외투 자락 밑 발 걸음 4자세.
- ""(펼침): 날개 판 둘을 앞으로 활짝 펼침(안쪽 얼굴 비늘, 눈 뜬 얼굴), 가슴 얼굴(Java 가 눈 뜸), 떨림 4자세(날개 끝이 떪).
- "crawl": 네 발로 엎드림(몸 중심 땅 위 0.5 로 Java 가 내림): 등에 반쯤 편 날개, 앞에 낮은 가슴 얼굴, 막대기 다리 넷이 기는 4자세.
"""
import numpy as np

from gabe import faces, kit


def cloth_tex(seed=173):
    """32x32 뻣뻣한 어두운 천: 세로 결, 접힌 그늘 줄, 해진 점"""
    n = 32
    rng = np.random.RandomState(seed)
    col = np.full((n, n, 3), (46, 40, 44), np.float32)
    col += rng.randint(-5, 6, (n, n, 1))
    for x in range(n):
        if x % 6 in (0, 1):
            col[:, x] *= 0.78
    for x in rng.randint(0, n, 3):
        col[:, x] = (30, 26, 30)
    worn = rng.rand(n, n) < 0.03
    col[worn] = (70, 62, 64)
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = np.clip(col, 0, 255).astype(np.uint8)
    out[..., 3] = 255
    return out


def scales_tex(open_eyes):
    """64x64 날개 안쪽: 얼굴 16x16 이 4x4 로 비늘처럼 엇갈려 박힘(살 바탕)"""
    f = faces.far(faces.preset("open" if open_eyes else "smile"))
    small = f.reshape(16, 2, 16, 2, 4).astype(np.float32).mean(axis=(1, 3)).astype(np.uint8)
    n = 64
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = (196, 120, 120)
    out[..., 3] = 255
    for row in range(4):
        for col in range(4):
            x0 = col * 16 + (8 if row % 2 else 0)
            y0 = row * 16
            for y in range(16):
                for x in range(16):
                    xx = (x0 + x) % n
                    if small[y, x, 3] > 127:
                        out[y0 + y, xx, :3] = small[y, x, :3]
    # 비늘 테두리(짙은 줄)
    for row in range(5):
        out[min(n - 1, row * 16), :, :3] = (120, 60, 64)
    return out


def coat(k, s):
    cloth = k.texture("cloth", cloth_tex())
    # 외투(날개 판 둘이 감쌈): 어깨에서 무릎 아래까지
    for side in (1, -1):
        lo, hi = sorted((side * 0.02, side * 0.36))
        s.box((lo, -0.72, -0.2), (hi, 0.62, 0.2), cloth)
    # 여민 앞자락 그늘 줄
    s.box((-0.03, -0.7, 0.2), (0.03, 0.6, 0.215), k.texture("seam", np.full((4, 4, 4), (24, 20, 24, 255), np.uint8)))
    # 숙인 두건(속은 비어 있음: 앞은 어두운 구멍)
    hood = k.texture("hood", np.full((4, 4, 4), (14, 10, 14, 255), np.uint8))
    s.box((-0.19, 0.6, -0.16), (0.19, 0.98, 0.2), {"north": cloth, "east": cloth, "west": cloth, "up": cloth, "south": hood, "down": hood},
          rot=(24, 0, 0), origin=(0, 0.6, 0))
    # 깃 위 막대기 끝 둘(옷걸이 어깨)
    for side in (1, -1):
        s.rod((side * 0.22, 0.5, 0.0), (side * 0.6, 1.0, -0.1), length=0.32, segs=1, thick=0.07, base=False, rag=False)
    # 외투 자락 밑 발 걸음
    for f in s.frames():
        with f:
            for side in (1, -1):
                ph = f.t * np.pi * 2 + (0 if side > 0 else np.pi)
                dz = 0.12 * np.sin(ph)
                lift = 0.05 * max(0.0, np.cos(ph))
                s.box((side * 0.16 - 0.07, -0.95 + lift, -0.06 + dz), (side * 0.16 + 0.07, -0.72 + lift, 0.1 + dz), cloth)


def spread(k, s):
    cloth = k.texture("cloth", cloth_tex())
    sc = k.texture("scales_open", scales_tex(True))
    # 몸통(살), 가슴 얼굴
    s.box((-0.24, -0.7, -0.18), (0.24, 0.55, 0.14), "flesh", face=True)
    s.head(size=0.7, depth=0.4, at=(0, 0.12, 0.18), face="smile")
    # 날개 판 둘: 어깨에서 앞으로 활짝(안쪽 = 얼굴 비늘, 바깥 = 천), 떨림 자세
    for f in s.frames():
        with f:
            tw = 4 * np.sin(f.t * np.pi * 2)
            for side in (1, -1):
                # 상자를 어깨 쪽 경첩에서 돌림: 경첩(옆 0.25)을 원점으로 날개를 옆으로 펼친 뒤 앞으로 접음
                x0, x1 = (0.25, 1.65) if side > 0 else (-1.65, -0.25)
                s.box((x0, -0.92, 0.0), (x1, 0.92, 0.06), {"south": sc, "north": cloth, "east": cloth, "west": cloth, "up": cloth,
                                                         "down": cloth},
                      rot=(0, -side * (38 + tw), 0), origin=(side * 0.25, 0, 0.03))
    # 깃 막대기 끝 둘(어깨에서 위로)
    for side in (1, -1):
        s.rod((side * 0.22, 0.5, 0.0), (side * 0.6, 1.0, -0.1), length=0.32, segs=1, thick=0.07, base=False, rag=False)


def crawl(k, s):
    cloth = k.texture("cloth", cloth_tex())
    sc = k.texture("scales_open", scales_tex(True))
    # 엎드린 몸통(앞뒤로 길게), 앞에 낮은 가슴 얼굴
    s.box((-0.26, -0.22, -0.7), (0.26, 0.12, 0.36), "flesh", face=True)
    s.head(size=0.62, depth=0.36, at=(0, -0.02, 0.5), face="smile")
    # 등에 반쯤 편 날개(안쪽 얼굴이 위를 봄)
    for side in (1, -1):
        x0, x1 = (0.2, 1.3) if side > 0 else (-1.3, -0.2)
        s.box((x0, 0.1, -0.75), (x1, 0.16, 0.25), {"up": sc, "down": cloth, "north": cloth, "south": cloth, "east": cloth, "west": cloth},
              rot=(0, 0, side * 22), origin=(side * 0.2, 0.13, 0))
    # 막대기 다리 넷(기어 옴)
    for i, (x, z) in enumerate(((1, 1), (-1, 1), (1, -1), (-1, -1))):
        w = kit.Wave(amp=(26, 18), lag=50, bias=(10, -40), phase=0 if (i in (0, 3)) else 180)
        s.rod((x * 0.24, -0.12, z * 0.3 - 0.15), (x, -0.5, z * 0.4), length=0.62, segs=2, thick=0.08, wave=w, toward=(0, 0, 1),
              poses=4, base=False)


def build(k: kit.Kit):
    m = k.model(poses=4, period=12, shrink=0.75, mut2=False, torn=False)
    coat(k, m.state("coat"))
    spread(k, m.state(""))
    crawl(k, m.state("crawl"))
    k.icon(model="g173", state="")


def sounds(k: kit.Kit):
    # 이 종 전용 신호: 걸을 때 뻣뻣한 천이 스치는 소리
    k.sample("rustle", "tag:cloth", "외투 스치는 소리", pitch=0.8, volume=0.9, attenuation=12)
    # 외투를 확 펼침
    k.sample("unfold", ["kenney_rpg/cloth1", "kenney_rpg/cloth3"], "외투가 펼쳐짐", pitch=0.55, volume=1.0, attenuation=28, n=2)
    # 감싸 묾
    k.sample("bite", "kenney_impact/impactpunch_heavy_00*", "감싸 묾", pitch=0.85, attenuation=16)
    # 웅크려 들으면 섞여 들리는 웃음(흉내 중)
    k.sample("snicker", "oga_cackle/witch_cackle_1_0", "낮은 웃음", pitch=0.75, volume=0.5, attenuation=8, n=1)


def phrases():
    return {"cause_g173": "곁에 선 외투에 감싸였다"}
