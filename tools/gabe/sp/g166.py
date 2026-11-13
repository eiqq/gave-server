"""G166 까치발 게이브: 땅에 까치발로 선 엔드 막대기 발 넷만 보이는 투명한 벽.

발(모델 gave:gabe/g166, shrink 0.6, 닻): 3.6 블록 폭에 늘어선 한 뼘 길이 막대기 발 넷이 발끝으로 종종걸음(2자세, 8틱 한 바퀴).
부위 f0..f3 이 켜지면 그 발이 꺾여 사라진다(남은 그루터기만).

벽(모델 gave:gabe/g166_wall, shrink 0.6, 부품): 3.6 x 3 블록 판. 상태 ""(거의 안 보임: 빛이 일그러진 듯한 성긴 점묘 반짝임),
"hit"(탄이 닿은 자리에 눌린 얼굴 자국이 물결처럼, 실눈), "touch"(사람에게 닿은 순간 눈 뜬 자국), "fall"(발을 다 잃음: 한 장의 넓적한
불투명 얼굴 판). 반투명 화소는 쓰지 않는다(점묘로 흐림).
"""
import numpy as np

from gabe import faces, kit

WALL_W, WALL_H = 3.6, 3.0
FEET_X = (-1.35, -0.45, 0.45, 1.35)


def stipple_tex(seed=166):
    """64x64 성긴 점묘: 대부분 투명, 빛이 휜 듯한 흰 푸른 점과 짧은 가로 줄 몇(유리 일그러짐)"""
    n = 64
    rng = np.random.RandomState(seed)
    out = np.zeros((n, n, 4), np.uint8)
    on = rng.rand(n, n) < 0.018
    for _ in range(10):
        y, x = rng.randint(0, n), rng.randint(0, n - 6)
        on[y, x:x + rng.randint(2, 6)] = True
    out[on, :3] = (214, 228, 236)
    out[on, 3] = 255
    return out


def imprint_tex(face, seed):
    """64x64 눌린 얼굴 자국: 먼 얼굴 32 를 두 배로 키워 체크 무늬로 성기게 찍고(반투명 대신), 물결처럼 줄마다 옆으로 밀림"""
    f = faces.far(faces.preset(face))
    big = np.kron(f, np.ones((2, 2, 1), np.uint8))
    n = 64
    out = np.zeros((n, n, 4), np.uint8)
    rng = np.random.RandomState(seed)
    yy, xx = np.mgrid[0:n, 0:n]
    keep = ((yy + xx) % 2 == 0) | (rng.rand(n, n) < 0.25)
    for y in range(n):
        sh = int(round(2.0 * np.sin(y / 5.0)))
        row = np.roll(big[y], sh, axis=0)
        m = keep[y] & (row[:, 3] > 127)
        out[y, m, :3] = row[m, :3]
        out[y, m, 3] = 255
    return out


def feet(k, s):
    for i, x in enumerate(FEET_X):
        with s.when(f"!on:f{i}"):
            # 까치발: 발목에서 땅까지 기운 막대기 하나(한 뼘), 번갈아 들림
            w = kit.Wave(amp=14, bias=0, phase=0 if i % 2 == 0 else 180)
            s.rod((x, 0.14, 0.0), (0, -1, 0.22), length=0.36, segs=1, thick=0.1, wave=w, toward=(0, 0, 1), base=False)
        with s.when(f"on:f{i}"):
            s.rod((x, -0.2, 0.0), (0.3, 1, 0.5), length=0.12, segs=1, thick=0.1, base=False, toward=(0, 0, 1), rag=False)
    # 발목 위 맨 처음 얼굴 하나는 없음: 벽 판이 부품으로 위에 놓인다(얼굴 방향은 앞)
    s.front = (0, 0, 1)


def wall(k, s, pose):
    z = 0.0
    if pose == "fall":
        # 불투명한 넓적한 얼굴 판(앞뒤 모두 얼굴, 옆은 스킨)
        s.head(size=WALL_W, height=WALL_H, depth=0.16, at=(0, 0, 0), face="smile", near=False, torn=False)
        return
    if pose == "":
        tex = k.texture("stipple", stipple_tex())
    elif pose == "hit":
        tex = k.texture("imprint", imprint_tex("smile", 1))
    else:
        tex = k.texture("imprint_open", imprint_tex("open", 2))
    s.quad((0, 0, z + 0.01), (WALL_W, WALL_H), "south", tex, face=True)
    s.quad((0, 0, z - 0.01), (WALL_W, WALL_H), "north", tex, face=True)
    s.front = (0, 0, 1)


def build(k: kit.Kit):
    m = k.model(poses=2, period=8, shrink=0.6, mut2=False, torn=False, open=False)
    feet(k, m.state(""))
    w = k.model("g166_wall", poses=1, shrink=0.6, mut2=False, torn=False, open=False, rag=False)
    for pose in ("", "hit", "touch", "fall"):
        wall(k, w.state(pose), pose)
    k.icon(model="g166_wall", state="fall")


def sounds(k: kit.Kit):
    # 이 종 전용 신호: 발끝으로 걷는 소리
    k.sample("tiptoe", "kenney_impact/footstep_wood_00*", "살금살금 발소리", pitch=1.45, volume=0.6, attenuation=20)
    # 위를 쏜 탄이 튕김
    k.sample("ricochet", "tag:glass", "탄이 튕김", pitch=1.3, volume=0.8, attenuation=24)
    # 발이 꺾임, 벽이 쓰러짐
    k.sample("snap", "oga_metalwood100/wood_hammer_0*", "발이 꺾임", pitch=0.8, attenuation=20)
    k.sample("fall", "kenney_impact/impactsoft_heavy_00*", "벽이 쓰러짐", pitch=0.6, attenuation=32)


def phrases():
    return {"cause_g166": "보이지 않는 벽에 짓눌렸다"}
