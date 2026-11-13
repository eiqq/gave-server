"""G165 무른 땅 게이브: 지름 7 블록의 꺼진 젖은 진흙 자리와 거기서 솟는 등딱지 얼굴.

무른 땅(모델 gave:gabe/g165, shrink 0.4): 둘레보다 짙은 진흙 원판, 가운데 1.5 블록 얼굴이 코까지 잠겨 실눈으로 떠 있고(무른 땅의 배꼽),
가장자리 네 곳에 엔드 막대기가 말뚝처럼 반쯤 박혀 경계를 알린다. 상태 ""(가만히), "bulge"(솟기 0.5초 전: 진흙이 다섯 군데 볼록, 얼굴이
조금 떠오름), "dry"(굳어 죽음: 마른 갈라진 흙, 얼굴이 가라앉고 말뚝이 쓰러짐).

등딱지(모델 gave:gabe/g165_crab): 0.6 블록 얼굴을 하늘 보게 눕혀 등딱지 삼고, 그 밑 살 배에 짧은 엔드 막대기 다리 여섯이 게처럼 옆으로 난
낮은 몸(게걸음 4자세, 8틱 한 바퀴).
"""
import numpy as np

from gabe import kit

MUD_R = 3.5


def mud_tex(dry=False, seed=165):
    """64x64 진흙 원판: 둘레보다 짙은 젖은 흙(물기 반짝임 점), 가장자리 고리는 더 짙게. dry 는 연한 마른 흙과 검은 갈라짐"""
    n = 64
    rng = np.random.RandomState(seed)
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    r = np.hypot(xx + 0.5 - n / 2, yy + 0.5 - n / 2) / (n / 2)
    out = np.zeros((n, n, 4), np.uint8)
    if dry:
        base = np.array((132, 108, 82), np.float32)
    else:
        base = np.array((52, 36, 28), np.float32)
    noise = rng.randint(-10, 11, (n, n, 1)).astype(np.float32)
    # 2x2 덩이 얼룩(손맛)
    blot = np.kron(rng.randint(-8, 9, (n // 2, n // 2, 1)), np.ones((2, 2, 1))).astype(np.float32)
    col = base + noise + blot
    edge = (r > 0.86)
    col[edge] = col[edge] * (0.8 if not dry else 0.85)
    if not dry:
        glint = (rng.rand(n, n) < 0.025) & (r < 0.85)
        col[glint] = (150, 140, 128)
    else:
        # 갈라짐: 가운데에서 바깥으로 뻗는 금 몇 줄
        for a in rng.rand(9) * np.pi * 2:
            for t in np.linspace(0.08, 0.95, 60):
                x = int(n / 2 + np.cos(a + 0.4 * np.sin(t * 9)) * t * n / 2)
                y = int(n / 2 + np.sin(a + 0.4 * np.sin(t * 9)) * t * n / 2)
                if 0 <= x < n and 0 <= y < n:
                    col[y, x] = (46, 34, 26)
    out[..., :3] = np.clip(col, 0, 255).astype(np.uint8)
    out[..., 3] = np.where(r <= 1.0, 255, 0)
    return out


def mud(k, s, pose):
    tex = k.texture("mud_dry" if pose == "dry" else "mud", mud_tex(pose == "dry"))
    s.quad((0, 0.03, 0), (MUD_R * 2, MUD_R * 2), "up", tex)
    # 가운데 잠긴 얼굴(코까지 진흙에): 몸 중심이 땅 위 0.02 라 얼굴 아래쪽은 땅속
    y = {"": -0.08, "bulge": 0.12, "dry": -0.42}[pose]
    s.head(size=1.5, depth=1.1, at=(0, y, 0), face="smile", torn=False)
    s.front = (0, 0, 1)
    # 귀퉁이 말뚝 넷(반쯤 박힘, 바깥으로 기욺). 굳으면 쓰러짐
    for i, (x, z) in enumerate(((1, 1), (-1, 1), (1, -1), (-1, -1))):
        cx, cz = x * MUD_R * 0.7, z * MUD_R * 0.7
        if pose == "dry":
            s.rod((cx, 0.06, cz), (x, 0.08, z), length=0.9, segs=1, thick=0.12, base=False, rag=False)
        else:
            s.rod((cx, -0.25, cz), (x * 0.25, 1, z * 0.25), length=1.05, segs=1, thick=0.12, base=False, toward=(0, 0, 1), rag=False)
    if pose == "bulge":
        # 다섯 군데 볼록(진흙 둔덕)
        for j in range(5):
            a = j / 5 * np.pi * 2 + 0.3
            cx, cz = np.cos(a) * 2.2, np.sin(a) * 2.2
            s.box((cx - 0.35, 0.0, cz - 0.35), (cx + 0.35, 0.28, cz + 0.35), tex)


def crab(k, s):
    # 등딱지: 하늘 보는 얼굴(이마가 앞 +Z)
    s.head(size=0.6, depth=0.22, at=(0, 0.06, 0), face="smile", rot=(-90, 180, 0), torn=False)
    s.front = (0, 1, 0)
    # 살 배(등딱지보다 조금 좁고 낮음)
    s.box((-0.24, -0.16, -0.22), (0.24, -0.05, 0.22), "flesh", face=True)
    # 다리 여섯(옆으로 난 게 다리, 앞뒤로 번갈아 디딤)
    for side in (1, -1):
        for j, z in enumerate((-0.17, 0.0, 0.17)):
            w = kit.Wave(amp=26, bias=-26, phase=(0 if side > 0 else 180) + j * 120)
            s.rod((side * 0.26, -0.06, z), (side, 0.0, z * 1.2), length=0.4, segs=1, thick=0.07, wave=w, toward=(0, 1, 0), poses=4,
                  base=False, rag=False)


def build(k: kit.Kit):
    m = k.model(poses=1, shrink=0.4, mut2=False, torn=False, open=True, rag=False)
    for pose in ("", "bulge", "dry"):
        mud(k, m.state(pose), pose)
    c = k.model("g165_crab", poses=4, period=8, mut2=False, torn=False)
    crab(k, c.state(""))
    k.icon(model="g165_crab", state="")


def sounds(k: kit.Kit):
    # 이 종 전용 신호: 발이 빠지는 푹(무른 땅을 밟은 순간)
    k.sample("sink", "tag:soft", "푹 꺼지는 소리", pitch=0.75, attenuation=20)
    # 진흙에서 다섯이 솟음
    k.sample("rise", "tag:wet+slime", "진흙이 갈라짐", pitch=0.8, attenuation=20)
    # 집게 물기
    k.sample("pinch", "kenney_impact/impactpunch_medium_00*", "집게 물기", pitch=1.4, volume=0.7, attenuation=12)


def phrases():
    return {"cause_g165": "무른 땅에서 솟은 것에 물렸다"}
