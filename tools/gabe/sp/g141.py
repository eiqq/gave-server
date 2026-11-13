"""G141 밀려오는 얼굴 벽 게이브(4막, 방패, 튼튼이: 폭 16, 높이 4 블록 벽).

_Gave 얼굴 수백 장을 벽돌처럼 엇갈려 쌓은 벽. 앞뒤 두 면 모두 얼굴 벽돌(작은 얼굴 판을 이어 그린 텍스처 판 + 도드라진 큰 얼굴 판)이고,
엔드 막대기가 비계처럼 가로세로로 벽을 엮는다. 위 끝에 2 블록 망루 얼굴 셋(앞뒤 두 얼굴), 양 끝 모서리에 큰 얼굴 하나씩(옆을 봄).
가운데 망루 위 막대기 둘은 왕관 가시(원형 DNA, 살짝 흔들림).

- gave:gabe/g141(shrink 0.15, 블록 좌표 그대로): 몸 가운데(0, 0, 0) = 엔진 pos, 벽 밑 y = -2(Java HOVER 2.0). 앞(+Z)과 뒤(-Z) 모양이 같다:
  Java 는 돌아서지 않고 반대로 밀고 오므로 나아가는 쪽 면이 앞이 된다.
  상태 ""(벽 하나뿐). 실눈 뜸(open)이면 벽돌 얼굴 판과 도드라진 얼굴이 모두 눈을 뜬 그림으로 바뀐다(조건 open / !open).
- gave:gabe/g141_lump: 망루가 던지는 얼굴 덩이(1 블록, 얼굴 여섯 뭉치).
소리 grind(벽이 밀려옴, 64), toss(얼굴 덩이 던짐, 32), splat(덩이 터짐, 24), tink(앞면 탄 막음, 24).
"""
import math

import numpy as np

from gabe import faces, kit

S = 0.15
HALF_W, HALF_H, HALF_D = 8.0, 2.0, 0.55
TILE = 4.0            # 텍스처 판 하나(4 x 4 블록, 얼굴 8 x 8 장)
TURRETS = (-5.0, 0.0, 5.0)
TURRET = 2.0          # 망루 얼굴 크기
TURRET_Y = HALF_H + 1.0
CORNER = 2.4          # 끝 얼굴 크기
BRICK_FACES = ("smile", "smile_a", "smile_c", "half", "tears", "stitch", "smile", "deep", "smile_a", "gold")


def brick_texture(seed, opened):
    """작은 얼굴(32 px) 8 x 8 장을 벽돌처럼(줄마다 반 장씩 엇갈림) 이은 256 x 256 그림(화소 부류 그대로)"""
    rng = np.random.RandomState(seed)
    out = np.zeros((256, 256, 4), np.uint8)
    for r in range(8):
        shift = 16 if r % 2 else 0
        for c in range(-1, 8):
            name = BRICK_FACES[rng.randint(len(BRICK_FACES))]
            f = faces.preset(name)
            if opened:
                f = f.with_(eyes="bloodshot" if rng.rand() < 0.25 else "open")
            img = faces.far(f)
            x0 = c * 32 + shift
            xs, xe = max(0, x0), min(256, x0 + 32)
            if xe <= xs:
                continue
            out[r * 32:(r + 1) * 32, xs:xe] = img[:, xs - x0:xe - x0]
    # 줄눈: 얼굴 사이 1 px 적갈색 그늘(벽돌처럼 읽히게, 부류 0)
    seam = np.array((0x61, 0x39, 0x38), np.uint8)
    for r in range(8):
        out[r * 32, :, :3] = seam
        shift = 16 if r % 2 else 0
        for c in range(9):
            x = c * 32 + shift
            if 0 <= x < 256:
                out[r * 32:(r + 1) * 32, x, :3] = seam
    out[..., 3] = 255
    out[..., 2] = out[..., 2]  # 부류 비트는 faces.far 가 새긴 그대로
    return out


def rodline(s, a, b, thick=0.16):
    """a 에서 b 까지 곧은 엔드 막대기(가만히, 받침 없음). 길면 8 블록씩 끊어 이음점이 틀 안에 들게"""
    a, b = np.array(a, float), np.array(b, float)
    d = b - a
    L = float(np.linalg.norm(d))
    n = max(1, int(math.ceil(L / 8.0)))
    for i in range(n):
        p = a + d * (i / n)
        up = abs(d[1]) > 0.97 * L
        s.rod(tuple(p), tuple(d), length=L / n, segs=1, thick=thick, base=False, rag=False, toward=(0, 0, 1) if up else (0, 1, 0))


def wall(s, k, tex_closed, tex_open):
    W, H, D = HALF_W, HALF_H, HALF_D
    # 속: 살 벽(위, 아래, 양 끝 면만. 앞뒤는 얼굴 판이 덮음)
    s.box((-W, -H, -D), (W, H, D), "flesh", faces=("up", "down", "east", "west"), face=True)
    # 앞뒤 얼굴 벽돌 판(4 x 4 블록 넷씩, 무늬 둘을 번갈아)
    for side, facing in ((1, "south"), (-1, "north")):
        for i in range(4):
            x = -W + TILE * (i + 0.5)
            pick = (i + (0 if side > 0 else 1)) % 2
            with s.when("!open"):
                s.quad((x, 0, side * (D + 0.01)), (TILE, TILE), facing, tex_closed[pick], face=True)
            with s.when("open"):
                s.quad((x, 0, side * (D + 0.01)), (TILE, TILE), facing, tex_open[pick], face=True)
    # 도드라진 큰 얼굴 판: 벽돌 사이에 엇갈려 박혀 튀어나옴(앞뒤 열씩)
    rng = np.random.RandomState(141)
    for side, facing in ((1, "south"), (-1, "north")):
        for j in range(10):
            row = j % 3
            x = -W + 0.9 + (j * 1.57 + (0.55 if row % 2 else 0)) % (2 * W - 1.8)
            y = -H + 0.8 + row * 1.25 + rng.uniform(-0.15, 0.15)
            sz = rng.uniform(0.85, 1.25)
            tilt = rng.uniform(-12, 12)
            z = side * (D + 0.06 + rng.uniform(0, 0.08))
            name = BRICK_FACES[rng.randint(len(BRICK_FACES))]
            f = faces.preset(name)
            with s.when("!open"):
                s.quad((x, y, z), (sz, sz), facing, k.face_tex(f), face=True, rot=(0, 0, tilt), origin=(x, y, z))
            with s.when("open"):
                s.quad((x, y, z), (sz, sz), facing, k.face_tex(f.with_(eyes="open")), face=True, rot=(0, 0, tilt), origin=(x, y, z))
    # 비계: 가로 막대기 둘(앞뒤), 세로 막대기 다섯(앞뒤), 막대기 끝은 벽 밖으로 조금 나감
    for side in (1, -1):
        z = side * (D + 0.22)
        for y in (-1.15, 0.95):
            rodline(s, (-W - 0.4, y, z), (W + 0.4, y, z), 0.15)
        for x in (-W + 0.15, -4.0, 0.0, 4.0, W - 0.15):
            rodline(s, (x, -H - 0.05, z), (x, H + 0.35, z), 0.15)
    # 위 끝 성가퀴: 망루 사이 살 턱
    s.box((-W, H, -D * 0.8), (W, H + 0.35, D * 0.8), "flesh", faces=("up", "north", "south"), face=True)
    # 망루 얼굴 셋: 앞을 보는 머리 + 뒤를 보는 얼굴 판(앞뒤 같은 모습)
    for x in TURRETS:
        s.head(size=TURRET, depth=1.4, at=(x, TURRET_Y, 0), face="smile", near=False)
        with s.when("!open"):
            s.quad((x, TURRET_Y, -0.71), (TURRET, TURRET), "north", k.face_tex("smile"), face=True)
        with s.when("open"):
            s.quad((x, TURRET_Y, -0.71), (TURRET, TURRET), "north", k.face_tex("open"), face=True)
    # 양 끝 모서리 큰 얼굴: 바깥 옆(+X, -X)을 봄(끝으로 돌아온 사람이 마주침)
    for side in (1, -1):
        s.head(size=CORNER, depth=1.3, at=(side * (W + 0.35), -0.2, 0), face="half" if side > 0 else "bloodshot", near=True,
               rot=(0, side * 90, 0))
    # 왕관 가시(원형 DNA): 가운데 망루 위 막대기 둘이 살짝 흔들림
    for side in (1, -1):
        s.rod((side * 0.55, TURRET_Y + TURRET / 2 - 0.1, 0), (side * 0.35, 1, 0), length=1.6, segs=2, thick=0.14, base=False,
              wave=kit.Wave(amp=(10, 8), lag=40, phase=0 if side > 0 else 180), toward=(1, 0, 0), poses=4)
    s.front = (0, 0, 1)


def build(k: kit.Kit):
    tex_closed = [k.texture(f"brick{i}", brick_texture(1410 + i, False), classes=True) for i in range(2)]
    tex_open = [k.texture(f"brick{i}_open", brick_texture(1410 + i, True), classes=True) for i in range(2)]
    m = k.model(poses=4, period=20, shrink=S, mut2=False)
    wall(m.state(""), k, tex_closed, tex_open)

    lm = k.model("g141_lump", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    s = lm.state("")
    s.lump(count=6, radius=(0.3, 0.3, 0.3), size=0.42, faces_list=["smile", "smile_a", "tears", "smile_c", "half", "smile"], poses=1)
    s.front = (0, 0, 0)
    k.icon(model="g141", state="")


def sounds(k: kit.Kit):
    k.sample("grind", "tag:stone", "벽이 밀려옴", pitch=0.55, attenuation=64, level=-10)
    k.sample("toss", "tag:grunt", "얼굴 덩이 던짐", pitch=0.7, attenuation=32)
    k.sample("splat", "tag:wet+slime", "얼굴 덩이 터짐", pitch=0.75, attenuation=24)
    k.sample("tink", "tag:stone", "벽이 탄을 막음", pitch=1.3, attenuation=24, n=2)


def phrases():
    return {"cause_g141": "얼굴 벽에 밀려 깔렸다", "cause_g141_lump": "벽 망루가 던진 얼굴 덩이에 맞았다"}
