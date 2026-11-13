"""G63 집게다리 게이브: 땅에 바짝 엎드린 0.9 블록 얼굴 위에 얼굴보다 넓은 등딱지가 얹히고, 둘레에서 엔드 막대기 다리 여섯이 무릎을 높이 세워
게처럼 뻗는다(폭 2.3). 앞 다리 둘은 앞으로 뻗어 끝이 집게로 갈라졌다. 낮고 넓은 게 실루엣.

모델 gave:gabe/g63 상태 ""(걸음: 뒤 다리 넷이 엇갈려 땅을 두드림), "strike"(앞 집게 둘을 높이 쳐듦: 찍기 예고), "down"(주저앉음: 남은 다리가 바닥에 퍼짐).
부위 "cut1".."cut6"(1 앞 왼쪽, 2 가운데 왼쪽, 3 뒤 왼쪽, 4 앞 오른쪽, 5 가운데 오른쪽, 6 뒤 오른쪽. 왼쪽 = 게이브 자신의 +X): 그 다리의 정강이가 떨어지고
허벅지만 그루터기로 남는다. 다리 자리 숫자는 Java G63 의 LEG 표와 같다.
소리 skitter(집게 걸음), clack(껍질에 튕김), snap(다리 끊김), snip(집게 찍기), 글귀 cause_g63.
"""
import numpy as np

from gabe import kit

# 다리: (부위 번호, 쪽, 뿌리 z, 무릎 dz, 정강이 방향, 길이, 위상)
ROOT_X, ROOT_Y = 0.5, 0.12
KNEE = (0.35, 0.5)
LEGS = (
    (1, 1, 0.3, 0.3, None, 1.0, 0),
    (2, 1, -0.15, 0.0, (0.28, -1.0, 0.0), 1.25, 180),
    (3, 1, -0.55, -0.25, (0.28, -1.0, -0.15), 1.25, 0),
    (4, -1, 0.3, 0.3, None, 1.0, 180),
    (5, -1, -0.15, 0.0, (0.28, -1.0, 0.0), 1.25, 0),
    (6, -1, -0.55, -0.25, (0.28, -1.0, -0.15), 1.25, 180),
)
# 앞 집게 팔의 방향: 걸음(앞 아래), 쳐듦(앞 위), 주저앉음(앞 바닥)
CLAW_DIR = {"": (0.05, -0.8, 0.6), "strike": (0.05, 0.8, 0.6), "down": (0.25, -0.35, 0.9)}


def shell_tex(seed=63):
    """등딱지: 윤나는 분홍 껍질, 가로 마디 골, 흰 반짝임"""
    rng = np.random.RandomState(seed)
    yy, xx = np.mgrid[0:16, 0:16].astype(float)
    col = np.array([236, 164, 156], float) * (0.88 + 0.12 * np.cos(yy * 0.8))[..., None] + rng.randn(16, 16, 1) * 2
    col[yy.astype(int) % 5 == 4] = (178, 96, 100)
    col[(np.abs(xx - 4) < 2) & (np.abs(yy - 2) < 1)] = (255, 236, 232)
    col[(np.abs(xx - 11) < 1) & (np.abs(yy - 7) < 1)] = (255, 226, 222)
    out = np.zeros((16, 16, 4), np.uint8)
    out[..., :3] = np.clip(np.round(col), 0, 255)
    out[..., 3] = 255
    return out


def crab(k, s, name):
    down = name == "down"
    s.head(size=0.9, height=0.72, depth=0.8, at=(0, -0.08, 0.1), face="smile")
    shell = k.texture("shell", shell_tex())
    s.box((-0.64, 0.1, -0.86), (0.64, 0.44, 0.32), {"up": shell, "north": shell, "east": shell, "west": shell, "south": shell},
          faces=("up", "north", "east", "west", "south"), uv=[0, 0, 16, 16], face=True)
    for (no, side, rz, kdz, shin, length, phase) in LEGS:
        root = (side * ROOT_X, ROOT_Y, rz)
        if down:
            # 주저앉음: 허벅지가 옆으로 눕고 정강이는 바닥에 퍼져 움찔
            knee = (side * (ROOT_X + 0.5), ROOT_Y + 0.12, rz + kdz)
        else:
            knee = (side * (ROOT_X + KNEE[0]), ROOT_Y + KNEE[1], rz + kdz)
        thigh = tuple(np.subtract(knee, root))
        # 허벅지: 늘 남음(끊기면 이것이 그루터기)
        s.rod(root, thigh, length=float(np.linalg.norm(thigh)), segs=1, thick=0.11, toward=(0, 0, 1), rag=False)
        with s.when(f"!on:cut{no}"):
            if shin is None:
                # 앞 집게 팔: 자세 없이, 끝에 갈라진 집게 둘
                d = CLAW_DIR[name]
                d = (side * d[0], d[1], d[2])
                arm = s.rod(knee, d, length=length, segs=1, thick=0.11, toward=(1, 0, 0), base=False, rag=False)
                tip = arm.tip()
                u = np.array(d) / np.linalg.norm(d)
                for sgn in (1, -1):
                    c = u + np.array([sgn * 0.5, 0.0, 0.0])
                    s.rod(tuple(tip), tuple(c), length=0.3, segs=1, thick=0.08, toward=(0, 1, 0.3), base=False, tex="bone", rag=False)
            elif down:
                d = (side * 1.0, -0.3, shin[2] * 2)
                s.rod(knee, d, length=length * 0.8, segs=1, thick=0.1, toward=(0, 0, 1), base=False, poses=4,
                      wave=kit.Wave(amp=5, phase=phase, shape="twitch"))
            else:
                d = (side * shin[0], shin[1], shin[2])
                s.rod(knee, d, length=length, segs=1, thick=0.1, toward=(0, 0, 1), base=False, poses=4,
                      wave=kit.Wave(amp=15, phase=phase))


def build(k: kit.Kit):
    m = k.model(poses=4, period=8, shrink=0.75, mut2=dict(root=(0.3, 0.42, -0.5), dir=(0.5, 1.0, -0.4), length=0.6, segs=1, poses=2))
    for name in ("", "strike", "down"):
        crab(k, m.state(name), name)


def sounds(k: kit.Kit):
    k.sample("skitter", "tag:chitter", "집게 걸음", pitch=1.1, volume=0.7, attenuation=20)
    k.sample("clack", "tag:pot", "껍질에 튕김", pitch=1.6, volume=0.6, attenuation=16)
    k.sample("snap", "tag:bone", "다리 끊김", pitch=0.9, attenuation=16)
    k.sample("snip", ["kenney_rpg/chop", "kenney_rpg/knifeslice", "kenney_rpg/knifeslice2"], "집게 찍기", pitch=0.8, attenuation=16)


def phrases():
    return {"cause_g63": "집게에 찍혔다"}
