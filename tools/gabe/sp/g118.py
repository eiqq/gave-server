"""G118 빚 놓는 게이브: 얼굴이 그려진 불룩한 복주머니 꼴 1.4 블록 몸. 주머니 아가리를 엔드 막대기 둘이 비녀처럼 가로질러 꿰어 조였고,
아가리 틈으로 금빛 작은 얼굴 동전이 넘친다(누가 동전을 주울 때마다 Java 가 body.scale 로 한 뼘씩 부풀림).

모델 gave:gabe/g118 상태 ""(아가리 동전이 반짝이는 2자세). 부위 "unpin"(받으러 오기 전 비녀가 빠짐: 비녀 숨기고 아가리가 벌어짐).
모델 gave:gabe/g118_coin: 바닥에 흘린 금화(금빛 얼굴 원판, Java 가 연출 디스플레이에 실음).
가운데(몸 자리) = 주머니 가운데(Java hover 0.8).
소리 coins(금화 소리, 24), pin(비녀 빠짐 '탁', 16), ram(박치기, 16).
"""
import numpy as np

from gabe import faces, kit


def _rgba(arr):
    return np.clip(np.round(arr), 0, 255).astype(np.uint8)


def gold_face(face="smile"):
    """금빛 얼굴: 얼굴 그림의 밝기를 금빛으로(이목구비는 짙은 금갈색)"""
    f = faces.far(faces.preset(face)).astype(np.float32)
    lum = f[..., :3].mean(axis=2, keepdims=True) / 255.0
    gold = np.array([250, 204, 70], np.float32)
    dark = np.array([120, 74, 18], np.float32)
    col = dark + (gold - dark) * np.clip((lum - 0.25) / 0.7, 0, 1)
    out = np.zeros_like(f)
    out[..., :3] = col
    out[..., 3] = 255
    return _rgba(out)


def edge_tex(n=8):
    out = np.zeros((n, n, 4), np.float32)
    out[..., :3] = (214, 160, 40)
    out[::2, :, :3] = (240, 196, 72)
    out[..., 3] = 255
    return _rgba(out)


def cloth_tex(n=16):
    """주머니 목(조여 모인 살 천): 세로 주름"""
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    fold = np.sin(xx * 1.6) * 0.5 + 0.5
    base = np.array([238, 196, 186], np.float32)
    col = base[None, None, :] * (0.78 + 0.22 * fold[..., None])
    out = np.zeros((n, n, 4), np.float32)
    out[..., :3] = col
    out[..., 3] = 255
    out[:, ::5, :3] *= 0.85
    return _rgba(out)


def coin(s, k, at, rot, size=0.2):
    gf = k.texture("goldface", gold_face())
    ed = k.texture("goldedge", edge_tex())
    w = size / 2
    t = {"south": gf, "north": gf, "east": ed, "west": ed, "up": ed, "down": ed}
    s.box((at[0] - w, at[1] - w, at[2] - 0.025), (at[0] + w, at[1] + w, at[2] + 0.025), t, uv=[0, 0, 16, 16], rot=rot, origin=at)


def build(k: kit.Kit):
    m = k.model(poses=2, period=20, mut2=False)
    s = m.state("")
    cl = k.texture("cloth", cloth_tex())
    # 불룩한 배: 얼굴 상자 하나 + 옆으로 불룩한 띠(실루엣이 둥근 주머니)
    s.head(size=1.12, depth=1.0, at=(0, -0.12, 0), face="deep")
    s.box((-0.66, -0.5, -0.46), (0.66, 0.2, 0.46), {n: k.skin_tex("left") for n in ("east", "west", "north")}, faces=("east", "west", "north"),
          uv=[0, 0, 16, 16], face=True)
    s.box((-0.48, -0.74, -0.42), (0.48, -0.62, 0.44), {"down": k.skin_tex("bottom")}, faces=("down",), uv=[0, 0, 16, 16], face=True)
    # 조여 모인 목과 주름 깃
    s.box((-0.3, 0.42, -0.28), (0.3, 0.6, 0.28), cl, uv=[0, 0, 16, 8], face=True)
    with s.when("!on:unpin"):
        s.box((-0.42, 0.6, -0.4), (0.42, 0.72, 0.4), cl, uv=[0, 0, 16, 4], face=True)
        # 비녀: 엔드 막대기 둘이 목을 가로질러 꿰임(위에서 보면 X)
        s.rod((-0.62, 0.52, -0.5), (1, 0.05, 0.8), length=1.55, segs=1, thick=0.09, base=False, rag=False)
        s.rod((0.62, 0.55, -0.5), (-1, 0.05, 0.8), length=1.55, segs=1, thick=0.09, base=False, rag=False)
    with s.when("on:unpin"):
        # 비녀가 빠져 아가리가 벌어짐: 깃이 바깥으로 젖혀지고 속이 보임
        for side in (1, -1):
            s.box((side * 0.2 - 0.25, 0.62, -0.42), (side * 0.2 + 0.25, 0.7, 0.42), cl, uv=[0, 0, 16, 4], rot=(0, 0, side * 28),
                  origin=(side * 0.3, 0.62, 0), face=True)
        s.quad((0, 0.605, 0), (0.5, 0.5), "up", k.texture("throat", _rgba(np.dstack([np.full((8, 8), 70), np.full((8, 8), 22),
                                                                                         np.full((8, 8), 30), np.full((8, 8), 255)]))))
    # 아가리에서 넘치는 금화(2자세 반짝: 기울기가 바뀜)
    for f in s.frames(2):
        with f:
            for i, (x, z, ry) in enumerate(((0.0, 0.05, 0), (0.14, -0.08, 40), (-0.15, 0.1, -35), (0.05, 0.2, 80), (-0.08, -0.15, 15))):
                coin(s, k, (x, 0.78 + (0.03 if (i + f.k) % 2 else 0), z), (12 if (i + f.k) % 2 else -14, ry, 8 * i))
    # 옆구리로 흘러내린 금화 둘
    coin(s, k, (0.55, -0.45, 0.35), (0, 30, 60))
    coin(s, k, (-0.5, -0.6, 0.38), (0, -20, -40))

    c = k.model("g118_coin", poses=1, mut2=False, open=False, torn=False, rag=False)
    cs = c.state("")
    coin(cs, k, (0, 0, 0), (0, 0, 0), size=0.42)
    cs.front = (0, 0, 0)


def sounds(k: kit.Kit):
    k.sample("coins", "tag:coin", "금화 소리", pitch=1.0, attenuation=24)
    k.sample("pin", "kenney_impact/impactwood_heavy_00*", "비녀 빠지는 소리", pitch=1.5, attenuation=16)
    k.sample("ram", "kenney_impact/impactpunch_heavy_00*", "박치기", pitch=0.85, attenuation=16)


def phrases():
    return {"cause_g118": "빚을 받아 갔다"}
