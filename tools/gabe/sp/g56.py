"""G56 깡충 줄 게이브: 0.6 블록 얼굴 아래 통나무처럼 곧은 살 몸통(다리 없음), 엔드 막대기 둘을 팔처럼 앞으로 나란히 곧게 뻗었다.
이마에 작은 얼굴이 그려진 세로 종이쪽이 붙어 실눈을 가린다. 줄 맨 앞(인도자)만 2블록 막대기 장대를 세워 들었고 끝에 작은 얼굴 방울 셋.

모델 gave:gabe/g56: 상태 ""(꼿꼿이 선 몸, 막대기 팔은 자세 없음, 종이쪽), "wild"(인도자를 잃음: 종이쪽 없음, 팔을 위아래로 휘젓는 4자세).
부위 "bell"(인도자: 장대와 방울 셋). 뛰는 동안 늘었다 줄어드는 것은 Java body.squash 로 한다(상태를 따로 굽지 않음).
"""
import numpy as np

from gabe import faces, kit

HEAD, DEPTH = 0.6, 0.5
HEAD_Y = 0.36          # 얼굴 가운데 높이(몸 중심 기준)
TRUNK = ((-0.33, -0.76, -0.25), (0.33, 0.07, 0.25))
ARM = dict(length=0.78, segs=1, thick=0.11)


def slip_tex():
    """세로 종이쪽(12x30): 누런 종이, 붉은 갈색 테두리와 붓 자국, 아래에 작은 실눈 얼굴"""
    w, h = 12, 30
    out = np.zeros((h, w, 4), np.uint8)
    rng = np.random.RandomState(56)
    out[..., :3] = (238, 222, 160)
    out[..., :3] = np.clip(out[..., :3].astype(np.int16) + rng.randint(-6, 7, (h, w, 1)), 0, 255)
    out[..., 3] = 255
    ink = (132, 34, 30)
    out[0, :, :3] = ink
    out[h - 1, :, :3] = ink
    out[:, 0, :3] = ink
    out[:, w - 1, :3] = ink
    # 붓 자국 세 줄(위쪽)
    for y, xs in ((3, range(3, 9)), (5, range(2, 10)), (6, (5, 6)), (7, (5, 6)), (8, range(3, 9)), (10, (3, 4, 7, 8)), (11, range(3, 9))):
        for x in xs:
            out[y, x, :3] = ink
    # 작은 얼굴(먼 얼굴 32 를 8 로 줄임)
    face = faces.far(faces.preset("smile"))
    small = face.reshape(8, 4, 8, 4, 4).astype(np.float32).mean(axis=(1, 3))
    y0, x0 = 16, 2
    for y in range(8):
        for x in range(8):
            if small[y, x, 3] > 127:
                out[y0 + y, x0 + x, :3] = np.clip(small[y, x, :3], 0, 255).astype(np.uint8)
    # 눈 줄을 또렷하게(줄이면 흐려짐)
    for x in (3, 4, 7, 8):
        out[y0 + 3, x, :3] = (96, 30, 30)
    return out


def body(k, s, wild):
    h = s.head(size=HEAD, depth=DEPTH, at=(0, HEAD_Y, 0), face="smile")
    # 통나무 몸통(살): 얼굴보다 큰 덩어리가 실루엣을 만든다
    s.box(TRUNK[0], TRUNK[1], "flesh", face=True)
    for side in (1, -1):
        root = (side * 0.22, -0.06, 0.24)
        if wild:
            w = kit.Wave(amp=38, bias=8, phase=0 if side > 0 else 180, shape="flap")
            s.rod(root, (0, 0, 1), wave=w, toward=(0, 1, 0), poses=4, **ARM)
        else:
            s.rod(root, (0, 0, 1), **ARM)
    if not wild:
        # 종이쪽: 이마에서 턱까지, 실눈을 가림
        z = DEPTH / 2 + 0.012
        s.quad((0, HEAD_Y + 0.04, z), (0.2, 0.5), "south", k.texture("slip", slip_tex()), face=True)
        with s.when("on:bell"):
            # 장대(2블록)를 게이브 왼손 끝에 세워 듦, 끝에 작은 얼굴 방울 셋
            px, pz = 0.22, 0.98
            s.rod((px, -0.7, pz), (0, 1, 0), length=2.0, segs=1, thick=0.08, base=False, toward=(0, 0, 1))
            for dx, dy in ((0.0, 1.36), (-0.2, 1.16), (0.2, 1.16)):
                s.head(size=0.2, depth=0.16, at=(px + dx, dy, pz), face="smile", near=False, open=False, torn=False)
    return h


def build(k: kit.Kit):
    m = k.model(poses=4, period=10, mut2=False, torn=False)
    body(k, m.state(""), False)
    body(k, m.state("wild"), True)


def sounds(k: kit.Kit):
    # 이 종의 신호: 뛰기 0.4초 전에 먼저 울리는 방울
    k.sample("bell", ["oga_sfx100/bell_02", "oga_sfx100/bell_01"], "방울", pitch=1.5, attenuation=32)
    # 인도자가 죽어 방울이 땅에 떨어져 구름
    k.sample("drop", "oga_sfx100/bell_03", "방울이 떨어짐", pitch=0.75, attenuation=32)
    # 두 발 모아 내려찍음
    k.sample("stomp", "kenney_impact/impactsoft_medium_00*", "쿵", pitch=1.1, volume=0.7, attenuation=16)


def phrases():
    return {"cause_g56": "깡충 줄에 밟혔다"}
