"""G104 딱지 게이브(2막, 떼, 방해): 손바닥만 한 종이쪽 얼굴(0.5블록, 두께 없음)이 나뭇잎처럼 팔랑이며 난다.
위 두 귀퉁이에 짧은 엔드 막대기 둘이 시침핀처럼 꽂혔다. 사람 등에 붙으면 핀이 박히고 검은 깨알 얼굴 떼가 연기처럼 따라붙는다.

- gave:gabe/g104 상태 ""(팔랑임: 종이 위아래 반쪽이 엇갈려 휘고 통째로 기울어짐, 자세 4, 12틱), "stuck"(납작 붙음, 핀이 박힘).
  떼 역할이라 변이 막대기, 입 찢어짐 끔. 종이 뒤는 누런 종이.
- gave:gabe/g104_dots: 붙은 뒤에만 켜는 점 떼(검은 깨알 얼굴 덩이 + 뒤로 흘러드는 가는 줄, 자세 4).
소리 flutter(종이 소리, 16), slap(등에 찰싹, 16), gnaw(점 떼 사각사각, 12).
"""
import math

import numpy as np
from PIL import Image

from gabe import faces, kit

W = 0.5
PAPER = (226, 214, 188)


def back_img():
    """종이 뒤: 누런 종이결"""
    n = 32
    rng = np.random.RandomState(104)
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    v = 0.9 + 0.05 * np.sin(yy * 0.9 + xx * 0.13) + rng.rand(n, n) * 0.06
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = np.clip(np.array(PAPER, np.float32) * v[..., None], 0, 255)
    out[..., 3] = 255
    return Image.fromarray(out, "RGBA")


def speck_img():
    """검은 깨알 얼굴: _Gave 얼굴을 숯빛으로(눈 줄, 입만 붉게 남음)"""
    f = faces.far("smile").astype(np.float32)
    lum = f[..., :3].mean(axis=2, keepdims=True)
    dark = np.where(lum > 150, np.array((24, 18, 20), np.float32), np.array((120, 22, 26), np.float32))
    out = np.zeros_like(f, dtype=np.uint8)
    out[..., :3] = np.clip(dark, 0, 255)
    out[..., 3] = np.where(f[..., 3] > 0, 255, 0)
    return Image.fromarray(out, "RGBA")


def paper(s, k, back, bend, tilt, roll, pin_in=0.0):
    """종이 한 장: 위 반쪽과 아래 반쪽이 가운데 가로선에서 bend 도씩 엇갈려 휨, 통째로 tilt(앞뒤), roll(옆) 기울어짐"""
    ft = k.face_tex("smile")
    h = W / 2
    o = (0.0, 0.0, 0.0)
    for half, (v0, v1), sign in (("top", (0, 8), 1), ("bot", (8, 16), -1)):
        cy = sign * h / 2
        rot = (tilt + sign * bend, 0.0, roll)
        s.quad((0, cy, 0.0), (W, h), "south", ft, uv=(0, v0, 16, v1), rot=rot, origin=o, face=True)
        s.quad((0, cy, 0.0), (W, h), "north", back, uv=(0, v0, 16, v1), rot=rot, origin=o)
    # 시침핀: 위 두 귀퉁이에서 뒤로 비스듬히(붙으면 깊이 박혀 짧게 보임)
    R = kit.rot_axis((1, 0, 0), tilt + bend) if abs(roll) < 1e-6 else kit.rot_axis((0, 0, 1), roll) @ kit.rot_axis((1, 0, 0), tilt + bend)
    for side in (1, -1):
        p = R @ np.array((side * (W / 2 - 0.05), h - 0.05, 0.0))
        d = R @ np.array((side * 0.25, 0.35, -1.0))
        s.rod(tuple(p + d / np.linalg.norm(d) * pin_in), tuple(d), length=0.2 - pin_in * 0.5, segs=1, thick=0.035, base=False, rag=False)


def build(k: kit.Kit):
    back = k.texture("back", back_img())
    m = k.model(poses=4, period=12, mut2=False, torn=False, rag=False)
    s = m.state("")
    flutter = ((22, 10, 8), (-8, -6, 18), (-20, 12, -6), (6, 4, -16))
    for f in s.frames():
        with f:
            bend, tilt, roll = flutter[f.k]
            paper(s, k, back, bend, tilt, roll)
    s.front = (0, 0, 1)
    st = m.state("stuck", poses=1)
    paper(st, k, back, 4, 0, 0, pin_in=0.08)
    st.front = (0, 0, 1)

    # 점 떼: 붙은 딱지 둘레 덩이 + 뒤(+Z, 사람 등에서 바깥)로 길게 흘러드는 줄
    sp = k.texture("speck", speck_img())
    d = k.model("g104_dots", poses=4, period=8, shrink=0.4, mut2=False, open=False, torn=False, rag=False)
    ds = d.state("")
    rng = np.random.RandomState(1040)
    cloud = [rng.uniform(-1, 1, 3) for _ in range(40)]
    cloud = [c for c in cloud if c @ c <= 1][:12]
    for f in ds.frames():
        with f:
            for i, c in enumerate(cloud):
                a = 2 * math.pi * (f.t + i * 0.37)
                p = (c[0] * 0.42 + 0.05 * math.cos(a), c[1] * 0.42 + 0.05 * math.sin(a), 0.1 + abs(c[2]) * 0.3)
                sz = 0.07 + 0.02 * (i % 3)
                ds.box(tuple(np.array(p) - sz / 2), tuple(np.array(p) + sz / 2), sp, uv=[0, 0, 16, 16],
                       faces=("north", "south", "east", "west"))
            # 줄: 바깥에서 등 쪽으로 흘러드는 깨알(자세마다 한 칸씩 앞으로)
            for j in range(10):
                u = (j + f.t) / 10.0
                z = 0.5 + 2.8 * (1 - u)
                y = 0.1 * math.sin(u * 9 + j)
                x = 0.12 * math.cos(u * 7 + j * 2)
                sz = 0.06
                ds.box((x - sz / 2, y - sz / 2, z - sz / 2), (x + sz / 2, y + sz / 2, z + sz / 2), sp, uv=[0, 0, 16, 16],
                       faces=("north", "south", "east", "west"))
    ds.front = (0, 0, 0)
    k.icon(model="g104", state="stuck")


def sounds(k: kit.Kit):
    k.sample("flutter", "tag:paper", "종이 소리", pitch=1.1, volume=0.9, attenuation=16)
    k.sample("slap", "kenney_impact/impactsoft_heavy_00*", "등에 찰싹", pitch=1.5, attenuation=16)
    k.sample("gnaw", "tag:chitter", "사각사각", pitch=1.4, volume=0.7, attenuation=12)


def phrases():
    return {"cause_g104": "딱지 따라온 점 떼에 뜯겼다"}
