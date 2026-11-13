"""G101 나만 보이는 게이브: 엔드 막대기 넷이 네모 틀(액자, 폭 1.2, 높이 1.6 블록)을 이루고 그 안에 납작하게 눌린 _Gave 얼굴이 초상화처럼
끼워져 있다. 틀의 아래 두 모서리가 뾰족하게 내려와 다리처럼 땅을 짚는다(또각또각). 얼굴 볼살이 틀 밖으로 조금 삐져나왔다. 빛바랜 색은 Java 가
팔레트(탈색)로 준다. 한 사람(주인)에게만 보인다(Java 가 다른 사람에게 숨김).

- gave:gabe/g101: 상태 ""(틀 다리 걸음, 자세 4). Java hover 1.15 면 다리 끝이 바닥.
- gave:gabe/g101_ring: 머리에 끼운 액자를 남들이 보는 희미한 네모 테(머리 칸 모델, 모델 -Z 가 사람 얼굴 쪽).
- 덮개 g101_frame: 주인 화면 가장자리에 낀 액자 틀(가운데는 비어 시야가 좁아짐).
소리 clack(나만 들리는 덜컹, 12), strike(틀 모서리 찍기, 12), fit(머리에 끼움, 12). 글귀 cause_g101.
"""
import numpy as np
from PIL import Image

from gabe import kit

W, H = 1.2, 1.6
FRONT = (0, 0, 1)


def cover_img():
    """480x270: 화면 가장자리에 낀 막대기 액자 틀(밝은 막대기, 어두운 모서리 이음), 볼살이 양옆에서 비어져 들어옴. 가운데는 투명"""
    w, h = 480, 270
    a = np.zeros((h, w, 4), np.uint8)
    t_top, t_side = 34, 46
    rng = np.random.RandomState(101)

    def bar(x0, y0, x1, y1, horiz):
        n = (y1 - y0) if horiz else (x1 - x0)
        for i in range(n):
            k = i / max(1, n - 1)
            v = int(150 + 90 * (1 - abs(k - 0.35) * 1.6))
            v = max(110, min(240, v))
            if horiz:
                a[y0 + i, x0:x1, :3] = (v, v, v + 6)
            else:
                a[y0:y1, x0 + i, :3] = (v, v, v + 6)
        a[y0:y1, x0:x1, 3] = 255

    bar(0, 0, w, t_top, True)
    bar(0, h - t_top, w, h, True)
    bar(0, 0, t_side, h, False)
    bar(w - t_side, 0, w, h, False)
    # 모서리 이음(어두운 받침)
    for cx, cy in ((0, 0), (w - t_side - 8, 0), (0, h - t_top - 8), (w - t_side - 8, h - t_top - 8)):
        a[cy:cy + t_top + 8, cx:cx + t_side + 8, :3] = (92, 84, 96)
        a[cy:cy + t_top + 8, cx:cx + t_side + 8, 3] = 255
    # 양옆에서 비어져 들어온 빛바랜 볼살
    yy, xx = np.mgrid[0:h, 0:w]
    for cx in (t_side - 6, w - t_side + 6):
        m = ((xx - cx) / 34.0) ** 2 + ((yy - h * 0.62) / 46.0) ** 2 <= 1
        a[m, :3] = (236, 206, 198)
        a[m, 3] = 255
    a[..., :3] = np.clip(a[..., :3].astype(int) + rng.randint(-4, 5, (h, w, 1)), 0, 255).astype(np.uint8)
    return a


def build(k: kit.Kit):
    m = k.model(poses=4, period=10, mut2=False)
    s = m.state("")
    # 납작하게 눌린 초상화 얼굴(틀 안)
    s.head(size=1.04, height=1.42, depth=0.14, face="smile")
    # 틀 밖으로 삐져나온 볼살
    for side in (1, -1):
        s.box((side * 0.56 - 0.07, -0.42, -0.06), (side * 0.56 + 0.07, -0.1, 0.07), "flesh", face=True)
    # 네모 틀: 엔드 막대기 넷(구조 막대기)
    s.rod((-W / 2 - 0.05, H / 2, 0.02), (1, 0, 0), length=W + 0.1, segs=1, thick=0.1, base=False, rag=False)
    s.rod((-W / 2 - 0.05, -H / 2, 0.02), (1, 0, 0), length=W + 0.1, segs=1, thick=0.1, base=False, rag=False)
    for side in (1, -1):
        s.rod((side * W / 2, -H / 2 - 0.05, 0.02), (0, 1, 0), length=H + 0.1, segs=1, thick=0.1, toward=FRONT, base=False, rag=False)
        # 아래 모서리가 뾰족하게 내려와 다리처럼(또각또각)
        s.rod((side * W / 2, -H / 2, 0.02), (side * 0.15, -1, 0), length=0.36, segs=1, thick=0.07,
              wave=kit.Wave(amp=22, phase=0 if side > 0 else 180), toward=FRONT, poses=4, base=False, rag=False)
    s.front = (0, 0, 1)

    # 남들이 보는 머리 둘레 희미한 네모 테(머리 칸: -Z 가 얼굴 쪽)
    r = k.model("g101_ring", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    rs = r.state("")
    q, t, z = 0.56, 0.05, -0.5
    for (cx, cy, w, hh) in ((0, q, 2 * q + t, t), (0, -q, 2 * q + t, t), (q, 0, t, 2 * q), (-q, 0, t, 2 * q)):
        rs.box((cx - w / 2, cy - hh / 2, z - t / 2), (cx + w / 2, cy + hh / 2, z + t / 2), "rod", uv=[2, 2, 6, 6])
    rs.front = (0, 0, 0)

    k.cover("g101_frame", Image.fromarray(cover_img(), "RGBA"))
    k.icon(model="g101", state="")


def sounds(k: kit.Kit):
    k.sample("clack", "tag:door", "나만 들리는 덜컹", pitch=0.9, attenuation=12)
    k.sample("strike", "kenney_impact/impactwood_light_00*", "액자 모서리 찍기", pitch=0.8, attenuation=12)
    k.sample("fit", ["kenney_rpg/doorclose_1", "kenney_rpg/doorclose_3"], "머리에 액자가 끼임", pitch=1.2, attenuation=12)


def phrases():
    return {"cause_g101": "나만 보이는 액자에 끼였다"}
