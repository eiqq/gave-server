"""G83 장승 게이브: 폭 0.8, 높이 3.2 블록 통나무 기둥에 얼굴이 세로로 길게 늘여 새겨졌다(나뭇결 살빛, 실눈이 세로로 길다).
정수리 밑에 엔드 막대기 하나가 비녀처럼 가로로 꿰였고, 턱 밑에 막대기 둘이 수염처럼 박혔다(셋 다 가만히 있는 구조 막대기). 밑동에 흙이 묻었다.

상태 ""(곧음) 하나. 기울기(멈출 때마다 조금씩 다른 기울기)와 덮침(앞으로 넘어짐)은 Java 가 몸 변환(roll, pitch)으로 준다.
3.2 블록이라 shrink 0.5 로 굽는다(엔진이 Look.scale 배).
"""
import numpy as np

from gabe import kit

H = 3.2
W = 0.8


def log_tex(seed=83):
    """나뭇결 살빛 통나무 옆면: 세로 결, 옹이 하나"""
    rng = np.random.RandomState(seed)
    n = 16
    im = np.zeros((n, n, 4), np.uint8)
    base = np.array((226, 186, 164), float)
    for v in range(n):
        for u in range(n):
            g = 0.9 + 0.1 * np.sin(u * 1.7 + np.sin(v * 0.35) * 1.4)
            c = base * g
            if (u + (v // 6)) % 5 == 0:
                c = base * 0.78
            im[v, u] = (*np.clip(c + rng.randn(3) * 2, 0, 255).astype(np.uint8), 255)
    im[9:11, 4:6] = (150, 112, 96, 255)
    return im


def ring_tex():
    """나이테(위, 아래 면)"""
    n = 16
    im = np.zeros((n, n, 4), np.uint8)
    for v in range(n):
        for u in range(n):
            r = np.hypot(u - 7.5, v - 7.5)
            c = (214, 176, 150) if int(r) % 3 else (176, 136, 116)
            if r > 7.2:
                c = (150, 112, 96)
            im[v, u] = (*c, 255)
    return im


def mud_tex(seed=84):
    rng = np.random.RandomState(seed)
    n = 16
    im = np.zeros((n, n, 4), np.uint8)
    for v in range(n):
        for u in range(n):
            k = 0.85 + 0.15 * rng.rand()
            c = np.array((92, 70, 54)) * k
            if v < 3 and rng.rand() < 0.4:
                c = np.array((226, 186, 164)) * 0.9
            im[v, u] = (*np.clip(c, 0, 255).astype(np.uint8), 255)
    return im


def build(k: kit.Kit):
    m = k.model(poses=1, period=20, shrink=0.5, mut2=False, torn=False)
    s = m.state("")
    wood = k.texture("log", log_tex())
    ring = k.texture("ring", ring_tex())
    mud = k.texture("mud", mud_tex())
    # 기둥(얼굴보다 길게: 얼굴 아래로 1블록 더 내려간 몸통)
    s.box((-W / 2, -H / 2, -W / 2), (W / 2, H / 2, W / 2), {"north": wood, "south": wood, "east": wood, "west": wood, "up": ring,
                                                             "down": ring})
    # 밑동 흙
    s.box((-W / 2 - 0.02, -H / 2, -W / 2 - 0.02), (W / 2 + 0.02, -H / 2 + 0.42, W / 2 + 0.02), mud,
          faces=("north", "south", "east", "west"))
    # 세로로 늘인 얼굴(옆면은 나무)
    s.head(size=W + 0.03, depth=W + 0.05, height=2.1, at=(0, 0.42, 0), face="smile", sides=wood)
    # 비녀: 정수리 밑을 가로로 꿴 막대기
    s.rod((-0.78, 1.22, 0.04), (1, 0, 0), length=1.56, segs=1, thick=0.1, base=False)
    # 수염: 턱 밑에 박힌 막대기 둘
    for side in (1, -1):
        s.rod((side * 0.17, -0.6, W / 2), (side * 0.12, -1.0, 0.3), length=0.6, segs=1, thick=0.08, base=False)


def sounds(k: kit.Kit):
    # 멈추는 순간에만 나무가 '딱' 운다(이 종 신호)
    k.sample("knock", "tag:wood+heavy", "나무 우는 소리", pitch=0.85, attenuation=20)


def phrases():
    return {"cause_g83": "장승에 깔렸다"}
