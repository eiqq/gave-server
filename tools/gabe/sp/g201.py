"""G201 안아줘요 게이브(3막, 정예, 방해): 키 2.9 블록의 머리채 기둥. _Gave 의 적갈색 머리채가 땅까지 자라 온몸을 치마처럼 덮었고 꼭대기에는
올림머리 둘로 솟았다. 본 얼굴은 꼭대기가 아니라 올림머리 밑 사람 머리 높이에 박혀 있고, 팔 둘은 어깨가 아니라 두 볼 옆에서 나와 손끝이 땅에 끌린다.
머리채 여기저기에는 까닭 모를 작은 머리 열둘이 열매처럼 박히고 매달려 있다(평소엔 다들 눈을 감았거나 눈코입이 없고, 품을 때만 한꺼번에 눈을 뜬다).
등에서 길고 마디진 엔드 막대기 다리 넷이 부채꼴로 뻗는데 걷는 데는 쓰지 않는다(걸음은 치맛자락 밑 잔발): 사람을 감싸 안을 때만 쓴다.
안아 달라고 조르듯 두 팔을 활짝 벌렸다가 제가 뻗어 사람을 품으로 데려오고, 다리 넷으로 이불처럼 감싼 채 등을 토닥이며 2초 동안 뽀뽀를 퍼붓고는
아무렇게나 내던진다.

모델(블록 좌표, 몸 가운데 = 바닥에서 1.5: Java HOVER, 바닥 y = -1.5, +Z 앞, +X 는 게이브 자신의 왼쪽)
- gave:gabe/g201(shrink 0.5): 본 얼굴, 머리채 몸, 몸통에 달린 머리 열둘, 등 다리 넷.
  ""(걷기, 서기: 다리 부채꼴이 느리게 흔들리고 치맛자락 밑 잔발이 종종거림), "aim"(예고: 다리 넷을 만세하듯 치켜들고 잘게 떪),
  "hug"(품음: 다리 넷이 앞으로 오므라들어 감쌈). 얼굴은 세 상태 모두 같은 자리의 보통 면(탈색, 짙은 홍조, 맞은 번쩍임을 받게).
  달린 머리는 본 얼굴(0.8)보다 훨씬 작고(0.28~0.5) 눈 뜸 오버레이가 없다: 눈 뜸 신호는 본 얼굴만. "hug" 에서만 눈을 뜬 얼굴로 바뀐다.
- gave:gabe/g201_arm(부품 하나에 두 팔, shrink 0.15): Java 가 몸 기준 자세로 둔다.
  ""(늘어뜨림), "aim"(활짝 벌림: 이리 온), "hug"(끌어안고 번갈아 토닥임: 10틱, 자세 2), "throw"(내던지기 몸짓: 8틱, 자세 4, Java 가 자세 0 에 맞춰 켬),
  "bye"(한 손을 들어 흔듦: 6틱, 자세 2), "r3", "r6", "r9"(곧게 뻗음: 볼 사이 가운데가 (0, 0, 0), +Z 로 그 길이. Java 가 길이에 맞춰 +Z 로 줄이고
  팔 뿌리 자리로 옮겨 겨눈 쪽으로 숙임). 부위 "grip": 손가락을 오므림(움켜쥠).
소리 crack(손마디 꺾는 소리, 예고, 32), lunge(팔이 뻗어 옴, 24), clutch(꼬옥 끌어안음, 16), kiss(뽀뽀 소리, 16), pat(토닥이는 소리, 12),
purr(가르랑거림, 16), fling(휙 내던짐, 24), fit(경련, 12). 글귀 cause_g201, hint_g201.
"""
import math

import numpy as np

from gabe import faces, kit

S = 0.5                       # 몸 모델 줄임(등 다리가 가운데에서 2.8 블록까지)
ARM_S = 0.15                  # 팔 부품 줄임(뻗은 팔 9 블록)
GROUND = -1.5                 # 바닥(Java HOVER 1.5)
FACE_AT, FACE, FACE_D = (0.0, 0.42, 0.06), 0.8, 0.62   # 얼굴 가운데(바닥에서 1.92: 사람 눈높이), 크기, 깊이
SH = (0.47, 0.42, 0.0)        # 왼팔 뿌리(+X 볼 옆). 오른쪽은 x 부호만 바꿈. Java SH_Y, SH_Z 와 같게
UP, LO = 0.92, 0.86           # 위팔, 아래팔 길이
ARM_T, LEG_T = 0.15, 0.13
REACH = (3, 6, 9)             # 뻗은 팔 상태 길이(Java LENGTHS 와 같게)


def _u(v):
    v = np.array(v, float)
    return v / np.linalg.norm(v)


def hair_tex(ragged=False, seed=201):
    """32x32 머리채: _Gave 머리 덩어리 색(적갈색)의 세로 가닥, 갈라진 어두운 골, 젖은 윤기 점. ragged: 아래 끝이 가닥마다 들쭉날쭉(컷아웃)"""
    n = 32
    rng = np.random.RandomState(seed)
    col = np.zeros((n, n, 3), np.float32)
    strands = (1.0, 0.86, 1.1, 0.74, 0.96, 1.18, 0.8, 1.04, 0.9, 0.7)
    for x in range(n):
        col[:, x] = np.array((84, 27, 28), np.float32) * strands[(x * 3 + x // 4) % len(strands)]
    for x in (4, 13, 22, 29):
        col[:, x] = (36, 11, 14)
    col += rng.randint(-4, 5, (n, n, 1))
    for _ in range(12):
        x, y = rng.randint(0, n), rng.randint(0, n - 4)
        col[y:y + 2 + rng.randint(0, 3), x] = (132, 54, 50)
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = np.clip(col, 0, 255).astype(np.uint8)
    out[..., 3] = 255
    if ragged:
        cut = (0, 3, 1, 5, 2, 0, 4, 1, 6, 2, 0, 3, 5, 1, 0, 4)
        for x in range(n):
            c = cut[(x // 2) % len(cut)]
            if c:
                out[n - c:, x, 3] = 0
    return out


# ============================================================================================== 몸


def tier(s, lo, hi, tex, hem=None):
    """머리채 한 단(옆 네 면과 윗면, 탈색과 맞은 번쩍임을 받음). hem: 옆면을 이 텍스처의 아래쪽에 맞춰 붙임(들쭉날쭉한 자락 끝)"""
    if hem is None:
        s.box(lo, hi, tex, faces=("north", "south", "east", "west", "up"), face=True)
        return
    px = 16 * S
    h = (hi[1] - lo[1]) * px
    wx, wz = (hi[0] - lo[0]) * px, (hi[2] - lo[2]) * px
    uv = {"north": [0, 16 - h, wx, 16], "south": [16 - wx, 16 - h, 16, 16], "east": [0, 16 - h, wz, 16], "west": [16 - wz, 16 - h, 16, 16]}
    s.box(lo, hi, hem, faces=("north", "south", "east", "west"), uv=uv, face=True)
    s.box(lo, hi, tex, faces=("up",), face=True)


def cloak(s, k):
    """머리채 몸: 올림머리 둘, 얼굴을 감싼 덩어리, 치마 두 단(아래 단은 자락 끝이 들쭉날쭉)"""
    hair, hem = k.texture("hair", hair_tex()), k.texture("hem", hair_tex(ragged=True))
    for side in (1, -1):
        lo, hi = sorted((side * 0.08, side * 0.46))
        tier(s, (lo, 1.0, -0.34), (hi, 1.42, 0.16), hair)
    tier(s, (-0.47, -0.02, -0.42), (0.47, 1.0, 0.26), hair)
    tier(s, (-0.53, -0.8, -0.47), (0.53, -0.02, 0.31), hair)
    tier(s, (-0.67, GROUND, -0.58), (0.67, -0.8, 0.4), hair, hem)
    return hair


def face(s):
    return s.head(size=FACE, depth=FACE_D, at=FACE_AT, face="smile")


# 몸통에 달린 머리들: (가운데, 크기, 보는 쪽(도: 0 앞, 90 왼쪽, 180 뒤), 숙임, 갸웃, 얼굴). 팔이 늘어지는 옆구리 가운데와 다리 뿌리는 비켜 둔다
HEADS = (
    ((0.26, -0.27, 0.36), 0.4, 8, 0, -18, "smile_a"),       # 본 얼굴 턱 밑 왼쪽
    ((-0.36, -0.19, 0.33), 0.28, -14, 0, 28, "smile_c"),     # 턱 밑 오른쪽 작은 것
    ((-0.26, -0.66, 0.38), 0.5, -8, 6, 12, "smile"),         # 가장 큰 것(그래도 본 얼굴 넓이의 넷 가운데 하나)
    ((0.36, -0.96, 0.46), 0.38, 14, 0, 96, "tears"),         # 옆으로 누운 채 우는 얼굴
    ((0.0, -1.2, 0.48), 0.3, -4, -8, 180, "smile"),          # 거꾸로 붙음
    ((0.72, -0.98, -0.34), 0.44, 90, 0, -12, "smile_c"),
    ((-0.74, -1.16, -0.3), 0.38, -90, 0, 20, "blank"),       # 눈코입 없는 민얼굴
    ((0.0, 0.34, -0.47), 0.5, 180, 0, 0, "smile"),           # 뒤통수
    ((-0.28, -0.56, -0.53), 0.4, 170, 0, -24, "tears"),
    ((0.24, -1.1, -0.64), 0.46, 190, -6, 12, "smile_a"),
)
# 올림머리에서 머리카락 한 가닥으로 늘어진 머리 둘(왼쪽은 짧게, 오른쪽은 길게): (가운데, 크기, 보는 쪽, 갸웃, 얼굴)
DANGLE = (((0.64, 0.64, -0.33), 0.32, 35, 8, "smile"), ((-0.62, 0.34, -0.38), 0.3, -50, -10, "flipped"))


def _rot(yaw, pitch=0.0, roll=0.0):
    """머리 회전(도): 보는 쪽 yaw, 숙임 pitch, 갸웃 roll(머리 제 틀에서) → 요소 회전(오일러)"""
    return kit.euler_of(kit.rot_axis((0, 1, 0), yaw) @ kit.rot_axis((1, 0, 0), pitch) @ kit.rot_axis((0, 0, 1), roll))


def bunch(s, hair, awake=False):
    """몸통 여기저기에 까닭 없이 달린 작은 머리들(다른 종의 덧머리처럼 가까운 얼굴, 눈 뜸, 입 찢어짐 오버레이 없이: 눈 뜸 신호는 본 얼굴만).
    평소엔 다들 눈을 감았거나 눈코입이 없고, 품을 때(awake)만 한꺼번에 눈을 번쩍 뜬다"""
    def one(at, size, rot, face):
        f = faces.preset(face)
        return s.head(size=size, depth=size * 0.8, at=at, face=f.with_(eyes="open") if awake else f, near=False, open=False, torn=False, rot=rot)

    for at, size, yaw, pitch, roll, face in HEADS:
        one(at, size, _rot(yaw, pitch, roll), face)
    for at, size, yaw, roll, face in DANGLE:
        top = np.array(one(at, size, _rot(yaw, 0, roll), face).point(0, 1, 0))
        root = np.array(((0.44 if at[0] > 0 else -0.44), 1.04, -0.28))   # 올림머리 바깥 뒤 모서리
        s.band(tuple(root), tuple(top - root), length=float(np.linalg.norm(top - root)), width=0.07, segs=1, thick=0.02, toward=(0, 0, 1), tex=hair)


# 등 다리: (뿌리, 뻗는 쪽, 휘는 쪽, 마디마다 굽힘(도), 길이). 적힌 값은 왼쪽(+X), 오른쪽은 x 부호만 바꿈
LEGS = {
    # 부채꼴: 위 둘은 올림머리 너머 뒤 위로 솟았다 끝이 뒤로 처지고, 아래 둘은 옆 뒤로 나가 땅에 끌린다
    "": (((0.24, 0.74, -0.42), (0.5, 0.85, -0.3), (0.0, -0.4, -1.0), (6, 30, 40), 2.4),
         ((0.3, -0.12, -0.47), (0.72, 0.16, -0.66), (0.0, -1.0, 0.0), (16, 40, 28), 2.3)),
    # 예고: 넷 다 만세하듯 치켜들고 끝을 앞으로 굽힘
    "aim": (((0.24, 0.74, -0.42), (0.6, 0.78, -0.1), (0.0, 0.15, 1.0), (0, 24, 32), 2.4),
            ((0.3, -0.12, -0.47), (0.95, 0.3, -0.12), (0.0, 0.35, 1.0), (0, 16, 22), 2.3)),
    # 품음: 위 둘은 올림머리 옆으로 넘어와 앞에서 안으로 굽고, 아래 둘은 허리 옆으로 돌아 앞에서 엇갈린다
    "hug": (((0.24, 0.74, -0.42), (0.6, 0.5, 0.6), (-0.8, -0.55, 0.25), (8, 50, 52), 2.4),
            ((0.3, -0.12, -0.47), (0.9, -0.12, 0.42), (-0.85, -0.05, 0.55), (10, 56, 58), 2.3)),
}


def legs(s, kind, *, amp, lag=50, cycles=1, rag=None):
    """등 다리 넷(엔드 막대기 세 마디, 원형 DNA). 좌우는 반 박자 엇갈려 흔들린다"""
    for i, (root, d0, tw, bias, length) in enumerate(LEGS[kind]):
        for side in (1, -1):
            w = kit.Wave(amp=amp[i], lag=lag, bias=bias, phase=(0 if side > 0 else 180) + i * 90, cycles=cycles)
            s.rod((side * root[0], root[1], root[2]), (side * d0[0], d0[1], d0[2]), length=length, segs=3, thick=LEG_T, wave=w,
                  toward=(side * tw[0], tw[1], tw[2]), rag=rag)


def feet(s):
    """치맛자락 밑 잔발: 작은 살 발 둘이 앞뒤로 엇갈려 종종거림(자세 2: 면 20 을 달린 머리에 내줌)"""
    for f in s.frames(2):
        with f:
            for side in (1, -1):
                ahead = (f.k % 2 == 0) == (side > 0)
                dz, lift = (0.1, 0.04) if ahead else (-0.08, 0.0)
                s.box((side * 0.22 - 0.08, GROUND + lift, 0.3 + dz), (side * 0.22 + 0.08, GROUND + 0.13 + lift, 0.54 + dz), "flesh",
                      faces=("north", "south", "east", "west", "up"))


def body(k):
    m = k.model(poses=4, period=16, shrink=S,
                mut2=dict(root=lambda side: (side * 0.27, 1.42, -0.08), dir=(0.45, 1.0, -0.25), length=0.6, segs=1, thick=0.1, poses=2))
    # 걷기, 서기
    s = m.state("")
    bunch(s, cloak(s, k))
    face(s)
    legs(s, "", amp=((5, 7, 9), (6, 4, 2)))
    feet(s)
    # 예고: 다리 넷을 치켜들고 잘게 떪(같은 주기에 두 번)
    s = m.state("aim")
    bunch(s, cloak(s, k))
    face(s)
    legs(s, "aim", amp=((3, 4, 6), (3, 4, 6)), lag=30, cycles=2, rag=False)
    # 품음: 다리 넷이 감쌈(살짝 조였다 풂). 달린 머리들이 한꺼번에 눈을 뜬다
    s = m.state("hug")
    bunch(s, cloak(s, k), awake=True)
    face(s)
    legs(s, "hug", amp=((2, 3, 4), (2, 3, 4)), lag=0, rag=False)
    return m


# ============================================================================================== 팔


def limb(s, a, b, thick=ARM_T, tex="rod", knob=False):
    """a 에서 b 까지 곧은 막대기 하나(자세 없음). knob: 뿌리에 이음 받침"""
    a, b = np.array(a, float), np.array(b, float)
    d = b - a
    n = float(np.linalg.norm(d))
    up = abs(d[1]) > 0.97 * n
    return s.rod(tuple(a), tuple(d), length=n, segs=1, thick=thick, base=knob, tex=tex, rag=False, toward=(0, 0, 1) if up else (0, 1, 0))


def elbow(sh, wr, pole):
    """팔 뿌리 sh, 손목 wr, 팔꿈치가 향할 쪽 pole → (팔꿈치, 손목). 팔이 모자라면 손목을 당겨 곧게 편다"""
    sh, wr = np.array(sh, float), np.array(wr, float)
    d = wr - sh
    dist = float(np.linalg.norm(d))
    u = d / dist
    if dist > UP + LO - 0.01:
        dist = UP + LO - 0.01
        wr = sh + u * dist
    a = (UP * UP - LO * LO + dist * dist) / (2 * dist)
    h = math.sqrt(max(UP * UP - a * a, 0.0))
    p = np.array(pole, float)
    p = p - u * float(p @ u)
    p = _u(p) if np.linalg.norm(p) > 1e-6 else _u(np.cross(u, (1, 0, 0)))
    return sh + u * a + p * h, wr


def hand(s, wr, fwd, curl, grip=None, lean=False):
    """손목 wr 에서 fwd 쪽으로 난 갈퀴 손(손등 뼈 + 손가락 셋). curl: 오므릴 때 손끝이 향할 쪽.
    grip: None 이면 '폄'과 '오므림'을 부위 grip 으로 둘 다 굽고, True/False 면 그 모양만. lean: 손가락을 한 마디로(자세마다 굽는 몸짓용, 면을 아낌)"""
    wr, f, c = np.array(wr, float), _u(fwd), np.array(curl, float)
    c = _u(c - f * float(c @ f)) if np.linalg.norm(c - f * float(c @ f)) > 1e-6 else _u(np.cross(f, (0, 1, 0)))
    w = _u(np.cross(f, c))
    palm = wr + f * 0.07
    s.box(tuple(palm - 0.1), tuple(palm + 0.1), "bone")

    def fingers(closed):
        for j in (-1, 0, 1):
            root = palm + f * 0.06 + w * (j * 0.07)
            if closed:
                mid = root + _u(f * 0.75 + c * 0.55 + w * (j * 0.1)) * 0.2
                tip = mid + _u(f * 0.05 + c * 1.0) * 0.18
            else:
                mid = root + _u(f * 0.9 - c * 0.25 + w * (j * 0.5)) * 0.2
                tip = mid + _u(f * 0.85 + c * 0.35 + w * (j * 0.45)) * 0.18
            if lean:
                limb(s, root, tip, 0.055, "bone")
            else:
                limb(s, root, mid, 0.055, "bone")
                limb(s, mid, tip, 0.045, "bone")

    if grip is None:
        with s.when("!on:grip"):
            fingers(False)
        with s.when("on:grip"):
            fingers(True)
    else:
        fingers(grip)


def arm(s, side, wrist, pole, curl, grip=None, hand_dir=None, lean=False):
    """팔 뿌리에서 손목까지 두 마디 팔 + 손. side +1 왼쪽"""
    sh = (side * SH[0], SH[1], SH[2])
    el, wr = elbow(sh, wrist, pole)
    limb(s, sh, el, ARM_T, knob=not lean)
    limb(s, el, wr, ARM_T * 0.92, knob=True)
    hand(s, wr, hand_dir if hand_dir is not None else wr - el, curl, grip, lean)


def hang(s, side):
    """늘어뜨린 팔: 팔꿈치가 살짝 뒤 바깥으로 굽고 손끝이 땅에 끌린다"""
    arm(s, side, (side * 0.62, GROUND + 0.45, 0.16), (side * 0.6, 0, -1), (-side, 0, 0.3), grip=False, hand_dir=(side * 0.1, -1, 0.25))


def arms(k):
    m = k.model("g201_arm", poses=4, period=8, shrink=ARM_S, mut2=False, open=False, torn=False, rag=False)
    # 늘어뜨림
    s = m.state("", poses=1)
    for side in (1, -1):
        hang(s, side)
    s.front = (0, 0, 0)
    # 예고: 두 팔을 양옆으로 활짝 벌림(이리 온), 손바닥은 앞으로 펴고
    s = m.state("aim", poses=1)
    for side in (1, -1):
        arm(s, side, (side * 1.95, 0.9, 0.4), (0, -1, -0.5), (-side, 0, 0.6), grip=False, hand_dir=(side * 0.55, 0.45, 0.7))
    s.front = (0, 0, 0)
    # 끌어안음: 팔꿈치는 앞 바깥, 손은 품은 사람 등 뒤. 두 손이 번갈아 들렸다 토닥임(자세 2)
    s = m.state("hug", poses=2, period=10)
    for f in s.frames():
        with f:
            for side in (1, -1):
                up = (f.k == 0) == (side > 0)   # 이 자세에서 들린 손
                # 손은 품은 사람의 등(왼손은 어깨뼈, 오른손은 허리): 머리는 건드리지 않는다
                arm(s, side, (-side * 0.1, 0.0 if side > 0 else -0.3, 1.44 if up else 1.12), (side, 0.25, 0.3), (0, 0, -1), grip=False,
                    hand_dir=(-side * 0.8, 0, -0.6), lean=True)
    s.front = (0, 0, 0)
    # 내던지기 몸짓(자세 4, 8틱): 움켜쥐고 들어 올림 → 앞 위로 곧게 내뻗으며 놓음 → 앞 아래로 뻗은 채 → 팔을 거둠
    s = m.state("throw", poses=4, period=8)
    keys = (((0.26, 0.85, 0.95), (1, 0.5, -0.5), True), ((0.3, 1.0, 1.76), (1, 0.6, -0.2), False), ((0.34, 0.1, 1.78), (1, 0.8, -0.2), False),
            ((0.6, -0.55, 0.95), (0.8, 0.2, -1), False))
    for f in s.frames():
        with f:
            wr, pole, grip = keys[f.k]
            for side in (1, -1):
                arm(s, side, (side * wr[0], wr[1], wr[2]), (side * pole[0], pole[1], pole[2]), (0, -1, 0), grip=grip, lean=True)
    s.front = (0, 0, 0)
    # 배웅: 오른손을 들어 흔들고(자세 2, 6틱) 왼팔은 늘어뜨림
    s = m.state("bye", poses=2, period=6)
    hang(s, 1)
    for f in s.frames():
        with f:
            arm(s, -1, (-1.05 + 0.42 * f.k, 1.42, 0.45), (-0.3, -1, -0.2), (1, 0, 0), grip=False, hand_dir=(-0.35 + 0.7 * f.k, 1, 0), lean=True)
    s.front = (0, 0, 0)
    # 곧게 뻗음: 볼 사이 가운데 (0, 0, 0) 에서 +Z 로 L. 1 블록 마디마다 이음 받침, 끝으로 갈수록 두 팔이 모인다(손은 마주 보고 움켜쥠)
    for L in REACH:
        s = m.state(f"r{L}", poses=1)
        for side in (1, -1):
            a, b = np.array((side * SH[0], 0.0, 0.0)), np.array((side * 0.2, 0.0, float(L)))
            for i in range(L):
                limb(s, a + (b - a) * (i / L), a + (b - a) * ((i + 1) / L), ARM_T, knob=True)
            hand(s, b, (0, 0, 1), (-side, 0, 0))
        s.front = (0, 0, 0)
    return m


def build(k: kit.Kit):
    body(k)
    arms(k)
    k.icon(model="g201", state="")


def sounds(k: kit.Kit):
    # 이 종 전용 신호: 팔을 벌리며 손마디를 꺾는 소리(낚아채기 예고)
    k.sound("crack", "손마디 꺾는 소리", vanilla=[("block/bone_block/break1", 0.6), ("block/bone_block/break3", 0.55), ("block/bone_block/break5", 0.65)],
            attenuation=32)
    k.sound("lunge", "팔이 뻗어 옴", vanilla=[("mob/frog/tongue1", 0.55), ("mob/frog/tongue2", 0.5), ("mob/frog/tongue3", 0.6)], attenuation=24)
    k.sound("clutch", "꼬옥 끌어안음", vanilla=[("item/armor/equip_leather1", 0.55), ("item/armor/equip_leather3", 0.5), ("item/armor/equip_leather5", 0.6)],
            attenuation=16)
    k.sound("kiss", "뽀뽀 소리", vanilla=[("random/pop", 1.25), ("mob/chicken/plop", 1.5), ("random/pop", 1.45)], volume=0.9, attenuation=16)
    k.sound("pat", "토닥이는 소리", vanilla=[("dig/cloth1", 0.8), ("dig/cloth2", 0.75), ("dig/cloth3", 0.85)], volume=0.8, attenuation=12)
    k.sound("purr", "가르랑거림", vanilla=[("mob/cat/purr1", 0.6), ("mob/cat/purr2", 0.55), ("mob/cat/purr3", 0.65)], attenuation=16)
    k.sound("fling", "휙 내던짐", vanilla=[("mob/irongolem/throw", 0.85)], attenuation=24)
    k.sound("fit", "경련", vanilla=[("entity/fish/flop1", 0.6), ("entity/fish/flop2", 0.55), ("entity/fish/flop3", 0.65), ("entity/fish/flop4", 0.6)],
            volume=0.8, attenuation=12)


def phrases():
    return {"cause_g201": "뽀뽀 세례를 받았다", "hint_g201": "경련하는 동안엔 금방 깨운다"}
