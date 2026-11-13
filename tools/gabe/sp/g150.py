"""G150 모래시계 게이브(4막, 정예, 목표 압박): 키 3.2 블록 모래시계. 엔드 막대기 넷이 틀 기둥이고 위아래 받침은 납작한 얼굴 판.
위 유리알 자리에는 거꾸로 뒤집힌 _Gave 얼굴, 아래에는 바로 선 _Gave 얼굴이 들어 있고, 위 얼굴이 모래처럼 부서져 가는 줄기로 아래 얼굴 위에 쌓인다.
틀 기둥 넷의 아래 끝이 다리다(대각으로 번갈아 걷는다).

모델 gave:gabe/g150(shrink 0.5) 상태:
  ""      걷기: 다리 넷 4자세, 모래 줄기 낟알이 떨어지는 4자세
  "stop"  멈춤(뒤집히는 동안): 다리 가만, 모래 줄기 멈춤. 뒤집힘 자체는 Java body.roll
가운데(몸 자리) = 목(두 유리알 사이), 바닥 위 1.6(Java HOVER 1.6: 다리 끝이 땅).
소리 sand(모래 떨어지는 소리, 24), strike(틀 기둥 찍기, 20), flip(뒤집힘, 24).
"""
import numpy as np

from gabe import kit

FOOT = -1.6
TOP_Y, BOT_Y = 1.45, -0.95     # 위 받침 아랫면, 아래 받침 윗면
POST = 0.62                    # 기둥 자리(가운데에서 x, z)


def sand_tex(n=16, seed=150):
    rng = np.random.default_rng(seed)
    base = np.array([226, 196, 150], np.float32)
    out = np.zeros((n, n, 4), np.float32)
    out[..., :3] = base + rng.normal(0, 12, (n, n, 1))
    dark = rng.random((n, n)) < 0.18
    out[dark, :3] = (190, 150, 112)
    out[..., 3] = 255
    return np.clip(np.round(out), 0, 255).astype(np.uint8)


def plate(s, k, y0, y1):
    """얼굴 판 받침: 살 판, 네 옆면에 얼굴(먼 32), 위아래는 살"""
    fl = k.part_tex("flesh")
    ft = k.face_tex("smile_c")
    s.box((-0.82, y0, -0.82), (0.82, y1, 0.82), {"up": fl, "down": fl, "north": ft, "south": ft, "east": ft, "west": ft},
          uv={"up": None, "down": None, "north": [0, 5, 16, 11], "south": [0, 5, 16, 11], "east": [0, 5, 16, 11], "west": [0, 5, 16, 11]},
          face=True)


def frame(s, k, walk):
    plate(s, k, TOP_Y, TOP_Y + 0.22)
    plate(s, k, BOT_Y - 0.22, BOT_Y)
    # 틀 기둥 넷(가만히): 아래 받침에서 위 받침까지
    for x, z in ((POST, POST), (-POST, POST), (POST, -POST), (-POST, -POST)):
        s.rod((x, BOT_Y - 0.22, z), (0, 1, 0), length=TOP_Y + 0.22 - (BOT_Y - 0.22), segs=1, thick=0.13, base=False, rag=False,
              toward=(0, 0, 1))
    # 다리 넷: 기둥 아래 끝에서 땅까지(대각끼리 같은 위상)
    for i, (x, z) in enumerate(((POST, POST), (-POST, -POST), (-POST, POST), (POST, -POST))):
        w = kit.Wave(amp=22, phase=0 if i < 2 else 180) if walk else None
        s.rod((x, BOT_Y - 0.22, z), (0, -1, 0), length=-FOOT + BOT_Y - 0.22, segs=1, thick=0.13, base=False, wave=w, toward=(0, 0, 1),
              rag=walk)
    # 목: 두 유리알 사이 가는 살 고리(유리알 테두리 대신 엔드 막대기 고리 둘)
    fl = k.part_tex("flesh")
    s.box((-0.16, -0.08, -0.16), (0.16, 0.08, 0.16), fl, face=True)
    for y, r in ((0.95, 0.52), (-0.5, 0.5)):
        for a in range(4):
            dx, dz = ((r, 0), (0, r), (-r, 0), (0, -r))[a]
            ex, ez = ((0, r), (-r, 0), (0, -r), (r, 0))[a]
            s.rod((dx, y, dz), (ex - dx, 0, ez - dz), length=float(np.hypot(ex - dx, ez - dz)), segs=1, thick=0.05, base=False, rag=False)


def faces(s, k, flow):
    # 위: 거꾸로 뒤집힌 얼굴(부서져 가는 아래쪽이 모래빛), 아래: 바로 선 얼굴 + 쌓인 모래 둔덕
    s.head(size=0.78, depth=0.6, at=(0, 0.72, 0), face="flipped", near=False, open=False, torn=False)
    s.head(size=0.82, depth=0.62, at=(0, -0.52, 0), face="smile")
    sand = k.texture("sand", sand_tex())
    s.box((-0.36, -0.13, -0.3), (0.36, -0.09, 0.3), sand)
    s.box((-0.2, 0.3, -0.2), (0.2, 0.34, 0.2), sand)
    if flow:
        # 모래 줄기: 낟알 셋이 목에서 아래 얼굴 위로 떨어지는 자세 4
        for f in s.frames(poses=4):
            with f:
                for j in range(3):
                    y = 0.3 - ((f.t + j / 3) % 1.0) * 0.4
                    s.box((-0.035, y - 0.035, -0.035), (0.035, y + 0.035, 0.035), sand)
    else:
        s.box((-0.03, -0.09, -0.03), (0.03, 0.3, 0.03), sand)


def build(k: kit.Kit):
    m = k.model(poses=4, period=16, shrink=0.5, mut2=False, torn=False)
    s = m.state("")
    frame(s, k, True)
    faces(s, k, True)
    st = m.state("stop", poses=1)
    frame(st, k, False)
    faces(st, k, False)


def sounds(k: kit.Kit):
    k.sample("sand", "tag:paper", "모래 떨어지는 소리", pitch=0.55, volume=0.9, attenuation=24)
    k.sample("strike", "kenney_impact/impactwood_heavy_00*", "틀 기둥에 찍힘", pitch=0.7, attenuation=20)
    k.sample("flip", "kenney_impact/impactglass_heavy_00*", "모래시계가 뒤집힘", pitch=0.55, attenuation=24)


def phrases():
    return {"cause_g150": "모래시계 기둥에 찍혔다", "notice_g150_late": "너무 오래 걸렸다"}
