"""G139 떠넘기는 게이브(4막, 방해): 두께 얇은 1.4 블록 네모 판. 앞면은 지직거리는 잡음 무늬 속에 _Gave 얼굴이 떠올랐다 흩어지기를 되풀이한다
(자세 4: 잡음 → 흐릿한 얼굴 → 또렷한 얼굴 → 반쯤 흩어짐). 엔드 막대기 둘이 판 위에 V 자로 곧게 섰고, 판 아래 모서리에서 분홍 물이 한 방울씩 듣는다.
뒷면은 살빛 뒤판에 바늘땀 이음선(약점: 피해 두 배).

모델 gave:gabe/g139 상태:
  ""      떠다님: 얼굴 떠오름 4자세(40틱 한 바퀴)
  "show"  한 사람에게 판을 보여 줌: 같은 4자세를 두 배 빠르게(cycles 2), 얼굴 단계가 더 또렷(눈 뜸)
가운데(몸 자리) = 판 가운데, 바닥 위 2.0(Java HOVER 2.0, 머리 높이).
덮개 g139_static: 시계가 0 이 되면 화면을 덮는 잡음과 얼굴.
소리 static(지직, 24), tick(똑딱, 16, 시계 든 사람에게만), pass(떠넘김, 16), burst(시계 터짐, 24).
"""
import numpy as np
from PIL import Image

from gabe import faces, kit

HALF = 0.7


def noise_img(n, seed, face=None, mix=0.0):
    """흑백 잡음(가로 줄무늬 섞임) + 얼굴을 mix 만큼 비침. RGBA uint8"""
    rng = np.random.default_rng(seed)
    g = rng.integers(40, 230, (n, n)).astype(np.float32)
    rows = rng.random(n) < 0.18
    g[rows, :] = np.clip(g[rows, :] * 0.4 + 150, 0, 255)
    out = np.zeros((n, n, 4), np.float32)
    out[..., 0] = g * 0.95
    out[..., 1] = g * 0.9
    out[..., 2] = g * 0.95
    if face is not None and mix > 0:
        fi = np.asarray(Image.fromarray(face).resize((n, n), Image.NEAREST), np.float32)
        m = (fi[..., 3:4] > 0) * mix
        jitter = rng.random((n, n, 1)) < 0.25
        m = np.where(jitter, m * 0.35, m)
        out[..., :3] = out[..., :3] * (1 - m) + fi[..., :3] * m
    out[..., 3] = 255
    return np.clip(np.round(out), 0, 255).astype(np.uint8)


def panel(s, k, stage_mix, eyes):
    fl = k.part_tex("flesh")
    smile = faces.far("smile")
    opened = faces.far(faces.Face(eyes=eyes, mouth="torn" if eyes == "open" else "dot"))
    # 판: 옆과 위아래 테는 살, 뒷면은 바늘땀 얼굴 없는 뒤판(살)
    s.box((-HALF, -HALF, -0.06), (HALF, HALF, 0.06), fl, faces=("east", "west", "up", "down", "north"), face=True)
    s.quad((0, 0, -0.065), (1.0, 1.0), "north", k.face_tex(faces.Face(eyes="none", mouth="none", extras=("stitch",), tufts=False)), face=True)
    for f in s.frames(poses=4):
        with f:
            mix = stage_mix[f.k]
            src = opened if f.k == 2 else smile
            tid = k.texture(f"static_{eyes}_{f.k}", noise_img(32, 139 + f.k * 7 + (100 if eyes == "open" else 0), src, mix))
            s.quad((0, 0, 0.065), (2 * HALF - 0.02, 2 * HALF - 0.02), "south", tid, face=True)
            # 물방울: 아래 모서리에서 떨어짐
            y = -HALF - 0.05 - 0.25 * f.t
            s.box((0.18 - 0.04, y - 0.06, -0.04), (0.18 + 0.04, y + 0.02, 0.04), fl)
    # V 자 막대기 둘(판 위)
    for side in (1, -1):
        s.rod((side * 0.22, HALF, 0), (side * 0.55, 1, 0), length=0.95, segs=1, thick=0.09, base=False, rag=False, toward=(0, 0, 1))
    s.front = (0, 0, 1)


def build(k: kit.Kit):
    m = k.model(poses=4, period=40, mut2=False, torn=False)
    panel(m.state(""), k, (0.0, 0.45, 0.85, 0.3), "half")
    panel(m.state("show"), k, (0.2, 0.7, 1.0, 0.5), "open")
    # 덮개: 화면 가득 잡음과 그 속에 들이민 얼굴
    W, H = 480, 270
    rng = np.random.default_rng(1390)
    g = rng.integers(20, 235, (H, W)).astype(np.float32)
    rows = rng.random(H) < 0.2
    g[rows, :] = g[rows, :] * 0.3 + 170
    base = np.stack([g, g * 0.95, g, np.full_like(g, 255)], -1)
    face = Image.fromarray(faces.near(faces.Face(eyes="open", mouth="torn"))).resize((300, 300), Image.BICUBIC)
    fa = np.asarray(face, np.float32)
    x0, y0 = (W - 300) // 2, (H - 300) // 2
    ys, ye = max(0, y0), min(H, y0 + 300)
    reg = base[ys:ye, x0:x0 + 300]
    fr = fa[ys - y0:ye - y0]
    m = (fr[..., 3:4] / 255.0) * 0.7
    reg[..., :3] = reg[..., :3] * (1 - m) + fr[..., :3] * m
    k.cover("g139_static", Image.fromarray(np.clip(base, 0, 255).astype(np.uint8)))


def sounds(k: kit.Kit):
    k.sample("static", [{"id": "kenney_scifi/computernoise_001", "end": 1.2}, {"id": "kenney_scifi/computernoise_003", "start": 2.0, "end": 3.0},
                        "oga_sfx100/noise_02"], "지직", attenuation=24)
    k.sample("tick", "kenney_interface/click_00*", "똑딱", pitch=0.8, attenuation=16)
    k.sample("pass", "kenney_interface/switch_00*", "떠넘김", pitch=0.7, attenuation=16)
    k.sample("burst", "tag:glitch", "잡음이 터짐", pitch=0.6, attenuation=24)


def phrases():
    return {"cause_g139": "떠넘겨 받은 시계가 다 됐다"}
