"""G182 요새 게이브(3막, 방패, 원거리, 보상: 한 변 4.6 블록 닫힌 얼굴 상자).

여섯 면이 모두 두꺼운 _Gave 얼굴 판(면마다 2 x 2 판)이고 모서리 여덟에서 엔드 막대기 창이 바깥으로 비죽 나왔다(아래 넷은 다리).
앞면 위쪽에 빗장처럼 가로로 걸린 굵은 엔드 막대기 하나와 그 위 작은 얼굴 등불 다섯. 네 옆면 가운데 가로 틈(총안)이 열리면 안쪽 눈들이 보인다.

- gave:gabe/g182(shrink 0.38): 몸 가운데(0, 0, 0) = 엔진 pos = 상자 가운데. 상태 ""(아래 창 다리 2 자세로 기어감), "sit"(창을 땅에 박음).
  부위 slit(총안 열림), broken(빗장 부러짐), l1..l5(등불 켜짐). 빗장 가운데 (0, BAR_Y, BAR_Z), 등불 (x, LAMP_Y, BAR_Z).
- gave:gabe/g182_spike: 쏘는 가시 하나(+Z 로 길쭉, 가운데가 (0, 0, 0)).
소리 horn(요새 뿔나팔, 48), shoot(가시 쏨, 32), stab(모서리 창, 24), crack(빗장 부러짐, 48), giggle(안에서 킥킥, 24), thud(주저앉음, 48).
"""
import numpy as np

from gabe import faces, kit

S = 0.38
H = 2.3            # 상자 반 변
PLATE = 0.16       # 얼굴 판 두께
BAR_Y, BAR_Z = 1.25, H + 0.42
LAMP_Y = 1.86
LAMPS = (-1.4, -0.7, 0.0, 0.7, 1.4)
SLIT_Y0, SLIT_Y1 = 0.02, 0.42
CORNERS = [(sx, sy, sz) for sx in (1, -1) for sy in (1, -1) for sz in (1, -1)]
SIDE_FACES = ("smile", "half", "smile_a", "tears", "smile_c", "stitch", "smile", "deep")


def dark(img, k=0.45):
    a = np.asarray(img).astype(float)
    a[..., :3] *= k
    a[..., 3] = 255
    return a.astype(np.uint8)


def side(s, k, axis, sign, idx, slit_open_eyes):
    """한 면: 2 x 2 두꺼운 얼굴 판(가운데 가로 틈 위아래), 틈 덮개(!on:slit)와 안쪽 눈(on:slit)"""
    facing = {("z", 1): "south", ("z", -1): "north", ("x", 1): "east", ("x", -1): "west", ("y", 1): "up", ("y", -1): "down"}[(axis, sign)]
    flesh = k.part_tex("flesh")

    def place(u, v, w):
        # 면 틀(u 가로, v 세로, w 바깥) → 블록
        if axis == "z":
            return (u * sign, v, w * sign)
        if axis == "x":
            return (w * sign, v, -u * sign)
        return (u, w * sign, v * sign)

    vertical = axis != "y"
    for i, (u, v) in enumerate(((-1.13, 1.2), (1.13, 1.2), (-1.13, -0.95), (1.13, -0.95))):
        h = 1.95 if vertical and v > 0 else 2.2
        if vertical and v > 0:
            v = SLIT_Y1 + h / 2
        elif vertical:
            v = SLIT_Y0 - h / 2 + 0.0
            h = H + SLIT_Y0
            v = -H + h / 2
        else:
            h = 2.2
            v = 1.13 if v > 0 else -1.13
        c = np.array(place(u, v, H + PLATE / 2))
        half = np.abs(np.array(place(1.1, h / 2, PLATE / 2)))
        f = SIDE_FACES[(idx * 4 + i) % len(SIDE_FACES)]
        opp = {"south": "north", "north": "south", "east": "west", "west": "east", "up": "down", "down": "up"}[facing]
        edges = tuple(n for n in ("north", "south", "east", "west", "up", "down") if n not in (facing, opp))
        s.box(tuple(c - half), tuple(c + half), flesh, faces=edges, face=True)
        with s.when("!open"):
            s.box(tuple(c - half), tuple(c + half), k.face_tex(f), uv=[0, 0, 16, 16], faces=(facing,), face=True)
        with s.when("open"):
            s.box(tuple(c - half), tuple(c + half), k.face_tex(faces.preset(f).with_(eyes="open")), uv=[0, 0, 16, 16], faces=(facing,), face=True)
    if not vertical:
        return
    # 총안: 닫혔을 때 살 덮개, 열리면 안쪽(0.35 들어간 곳)에 눈 줄
    mid = (SLIT_Y0 + SLIT_Y1) / 2
    c = np.array(place(0, mid, H + PLATE / 2 - 0.02))
    half = np.abs(np.array(place(2.2, (SLIT_Y1 - SLIT_Y0) / 2, PLATE / 2 - 0.01)))
    with s.when("!on:slit"):
        s.box(tuple(c - half), tuple(c + half), flesh, faces=(facing,), face=True)
    with s.when("on:slit"):
        for j, u in enumerate((-1.5, -0.5, 0.5, 1.5)):
            q = place(u, mid, H - 0.35)
            s.quad(q, (0.9, 0.9), facing, slit_open_eyes[(idx + j) % len(slit_open_eyes)], face=True,
                   uv=(0, 4, 16, 12))


def bar_and_lamps(s, k):
    flesh = k.part_tex("flesh")
    # 빗장 받침 둘
    for x in (-2.0, 2.0):
        s.box((x - 0.18, BAR_Y - 0.22, H + PLATE), (x + 0.18, BAR_Y + 0.22, BAR_Z + 0.2), flesh, face=True)
    with s.when("!on:broken"):
        s.rod((-2.45, BAR_Y, BAR_Z), (1, 0, 0), length=4.9, segs=2, thick=0.3, base=False, toward=(0, 1, 0), rag=False)
    with s.when("on:broken"):
        s.rod((-2.45, BAR_Y, BAR_Z), (1, -0.55, 0.25), length=2.1, segs=1, thick=0.3, base=False, toward=(0, 1, 0), rag=False)
        s.rod((2.45, BAR_Y, BAR_Z), (-1, -0.7, 0.3), length=2.0, segs=1, thick=0.3, base=False, toward=(0, 1, 0), rag=False)
    unlit = k.texture("lamp_off", dark(faces.far(faces.preset("smile")), 0.42))
    lit = k.face_tex(faces.Face(eyes="open", blush="gold"))
    for i, x in enumerate(LAMPS):
        c = (x, LAMP_Y, H + PLATE + 0.06)
        with s.when(f"!on:l{i + 1}"):
            s.box((x - 0.27, LAMP_Y - 0.27, H + PLATE), (x + 0.27, LAMP_Y + 0.27, H + PLATE + 0.12), {"south": unlit, "east": flesh, "west": flesh,
                  "up": flesh, "down": flesh}, uv=[0, 0, 16, 16], faces=("south", "east", "west", "up", "down"), face=True)
        with s.when(f"on:l{i + 1}"):
            s.box((x - 0.31, LAMP_Y - 0.31, H + PLATE), (x + 0.31, LAMP_Y + 0.31, H + PLATE + 0.16), {"south": lit, "east": flesh, "west": flesh,
                  "up": flesh, "down": flesh}, uv=[0, 0, 16, 16], faces=("south", "east", "west", "up", "down"), face=True)


def shell(s, k):
    """몸: 속 상자(조금 안쪽), 여섯 면 얼굴 판, 빗장과 등불, 위 창 넷"""
    eyes = [k.face_tex(n) for n in ("open", "bloodshot", "open", "bloodshot")]
    c = H - 0.4
    s.box((-c, -c, -c), (c, c, c), "flesh", face=True)
    for idx, (axis, sign) in enumerate((("z", 1), ("x", 1), ("z", -1), ("x", -1), ("y", 1), ("y", -1))):
        side(s, k, axis, sign, idx, eyes)
    bar_and_lamps(s, k)
    for (sx, sy, sz) in CORNERS:
        if sy > 0:
            s.rod((sx * (H - 0.15), sy * (H - 0.15), sz * (H - 0.15)), (sx * 0.7, 0.55, sz * 0.7), length=1.7, segs=1, thick=0.2, base=True,
                  toward=(0, 1, 0), rag=False)
    s.front = (0, 0, 1)


def leg(s, sx, sz, d, L):
    s.rod((sx * (H - 0.15), -(H - 0.15), sz * (H - 0.15)), d, length=L, segs=1, thick=0.2, base=False, toward=(1, 0, 0), rag=False)


def build(k: kit.Kit):
    m = k.model(poses=2, period=16, shrink=S, mut2=False, torn=False)
    st = m.state("")
    shell(st, k)
    # 아래 창 다리: 대각선 둘씩 번갈아 듦(2 자세)
    for f in st.frames():
        with f:
            for (sx, sy, sz) in CORNERS:
                if sy > 0:
                    continue
                lift = ((sx * sz > 0) == (f.k == 0))
                leg(st, sx, sz, (sx * 0.55, -0.6 if lift else -1.0, sz * 0.55 + (0.3 if lift else 0)), 1.5)
    sit = m.state("sit", poses=1)
    shell(sit, k)
    for (sx, sy, sz) in CORNERS:
        if sy < 0:
            leg(sit, sx, sz, (sx * 0.5, -1.0, sz * 0.5), 1.25)

    sm = k.model("g182_spike", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    ss = sm.state("")
    ss.rod((0, 0, -0.7), (0, 0, 1), length=1.4, segs=1, thick=0.12, base=False, toward=(0, 1, 0), rag=False)
    ss.rod((0, 0, -0.5), (0.12, 0.1, 1), length=0.9, segs=1, thick=0.08, base=False, toward=(0, 1, 0), rag=False)
    ss.rod((0, 0, -0.5), (-0.12, -0.08, 1), length=0.9, segs=1, thick=0.08, base=False, toward=(0, 1, 0), rag=False)
    ss.front = (0, 0, 0)
    k.icon(model="g182", state="sit")


def sounds(k: kit.Kit):
    k.sample("horn", "tag:horn", "요새 뿔나팔", pitch=0.8, attenuation=48)
    k.sample("shoot", "tag:blade", "가시 쏨", pitch=0.8, attenuation=32)
    k.sample("stab", "tag:impact+flesh", "모서리 창이 찌름", pitch=0.9, attenuation=24)
    k.sample("crack", "tag:breaking", "빗장이 부러짐", pitch=0.6, attenuation=48)
    k.sample("giggle", "tag:laugh", "요새 안에서 킥킥", pitch=1.15, attenuation=24, n=2)
    k.sample("thud", "tag:heavy+impact", "요새가 주저앉음", pitch=0.55, attenuation=48)


def phrases():
    return {"cause_g182": "요새의 가시 다발에 맞았다", "cause_g182_stab": "요새 모서리 창에 찔렸다"}
