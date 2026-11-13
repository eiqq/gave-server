"""G123 벌레 구름 게이브: 손톱만 한 얼굴(0.15 블록)에 엔드 막대기 날개 둘이 달린 벌레 수백. 한 마리씩은 안 보이고 폭 8 블록 검붉은 구름으로
보인다. 구름 속에서 실눈이 점점이 반짝이고, 떨어져 나온 막대기 날개 조각 여섯이 구름 가장자리를 따라 돈다.

모델 gave:gabe/g123(shrink 0.35: 구름 폭 8 블록) 상태:
  ""  닻(점묘 벌레 덩이 + 작은 얼굴 + 가장자리 날개 조각 여섯, 2자세 출렁임). Java 가 닻을 계속 돌려 날개 조각이 가장자리를 돈다
  "b", "c"  부품 덩이(배치가 다른 점묘 덩이 둘: body.attach 로 따로 돈다, 체력이 줄면 하나씩 끔)
점묘 덩이는 벌레 텍스처(검붉은 몸, 흰 날개 점, 분홍 실눈 점: 컷아웃)를 입힌 큰 상자들이라 면 수에 비해 벌레가 수백으로 보인다.
덮개 g123_bugs: 감싸인 사람 화면(가장자리부터 벌레가 기어 들어와 아래 절반을 가림).
소리 buzz(벌레 소리, 48), chew(뜯는 소리, 16).
"""
import math

import numpy as np
from PIL import Image, ImageDraw

from gabe import faces, kit

R = (3.4, 1.5, 3.4)


def _rgba(arr):
    return np.clip(np.round(arr), 0, 255).astype(np.uint8)


def swarm_tex(n=32, seed=123, fill=0.3):
    """점묘 벌레: 검붉은 몸 점(2화소), 그 옆 흰 날개 점, 드문드문 분홍 실눈 점. 사이는 비침(컷아웃)"""
    rng = np.random.RandomState(seed)
    out = np.zeros((n, n, 4), np.float32)
    k = int(n * n * fill / 3)
    for _ in range(k):
        x, y = rng.randint(n - 1), rng.randint(n - 1)
        body = (92, 20, 26) if rng.rand() < 0.6 else (52, 12, 16)
        out[y, x, :3] = body
        out[y, x, 3] = 255
        out[y, x + 1, :3] = body
        out[y, x + 1, 3] = 255
        r = rng.rand()
        if r < 0.35 and y > 0:
            out[y - 1, x, :3] = (226, 214, 214)
            out[y - 1, x, 3] = 255
        elif r < 0.45:
            out[y, x, :3] = (240, 170, 176)  # 실눈 점
    return _rgba(out)


def cloud(s, k, seed, count, tex_seed):
    tex = k.texture(f"swarm{tex_seed}", swarm_tex(seed=tex_seed))
    rng = np.random.RandomState(seed)
    spots = []
    while len(spots) < count:
        p = rng.uniform(-1, 1, 3)
        if p @ p <= 0.85:
            spots.append(p * np.array(R) * 0.8)
    for f in s.frames(2):
        with f:
            for i, p in enumerate(spots):
                sz = 1.7 + 0.5 * ((i * 5) % 3) / 2
                c = p * (1.0 if f.k == 0 else 1.08) + np.array([0, 0.12 if (i + f.k) % 2 else -0.12, 0])
                s.box(tuple(c - sz / 2), tuple(c + sz / 2), tex, uv=[0, 0, 16, 16])


def build(k: kit.Kit):
    m = k.model(poses=2, period=4, shrink=0.35, mut2=False, torn=False, rag=False)
    s = m.state("")
    cloud(s, k, 1, 7, 1)
    s.lump(count=10, radius=(2.4, 1.0, 2.4), size=0.22, faces_list=["smile", "smile_a", "smile_c"], seed=4)
    # 가장자리를 도는 날개 조각 여섯(짧은 막대기, 파르르)
    for i in range(6):
        a = math.radians(i * 60 + 15)
        root = (math.sin(a) * 3.4, (0.4 if i % 2 else -0.4), math.cos(a) * 3.4)
        s.rod(root, (math.cos(a), 0.25, -math.sin(a)), length=0.5, segs=1, thick=0.06, base=False,
              wave=kit.Wave(amp=30, phase=90 * i), toward=(0, 1, 0), poses=2)
    s.front = (0, 0, 0)
    for name, sd, ts in (("b", 2, 2), ("c", 3, 3)):
        p = m.state(name)
        cloud(p, k, sd, 6, ts)
        p.front = (0, 0, 0)
    k.cover("g123_bugs", bugs_cover())


def bugs_cover(seed=21, size=(480, 270)):
    """감싸인 화면: 가장자리에서 기어 드는 벌레(아래쪽이 짙어 시야 절반을 가림), 사이사이 작은 실눈 얼굴"""
    W, H = size
    rng = np.random.RandomState(seed)
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    for i in range(2600):
        y = H - abs(rng.normal(0, H * 0.42))
        x = rng.uniform(-5, W + 5)
        if rng.rand() < 0.35:
            x = rng.choice([rng.uniform(-5, 60), rng.uniform(W - 60, W + 5)])
            y = rng.uniform(0, H)
        if y < 0:
            continue
        a = rng.uniform(0, 2 * math.pi)
        L = rng.uniform(3, 6)
        col = (70 + rng.randint(40), 14 + rng.randint(14), 20 + rng.randint(12), 235)
        d.line((x, y, x + math.cos(a) * L, y + math.sin(a) * L), fill=col, width=3)
        if rng.rand() < 0.3:
            wx, wy = x + math.cos(a + 1.6) * 3, y + math.sin(a + 1.6) * 3
            d.line((x, y, wx, wy), fill=(225, 212, 212, 200), width=1)
    fc = Image.fromarray(faces.far(faces.preset("smile"))).resize((16, 16), Image.NEAREST)
    for i in range(14):
        x, y = int(rng.uniform(10, W - 26)), int(H - abs(rng.normal(0, H * 0.3)) - 16)
        if y > 0:
            im.alpha_composite(fc, (x, y))
    return im


def sounds(k: kit.Kit):
    k.sample("buzz", "tag:bug", "벌레 소리", pitch=0.75, attenuation=48)
    k.sample("chew", "tag:bug", "뜯는 소리", pitch=1.2, volume=0.8, attenuation=16)


def phrases():
    return {"cause_g123": "벌레 구름에 뜯겼다"}
