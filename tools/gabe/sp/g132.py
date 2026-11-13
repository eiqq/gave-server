"""G132 신호탄 게이브: 0.6 블록 얼굴이 엔드 막대기 다리 둘로 서고, 등에 굵은 엔드 막대기 통 하나를 비스듬히 멨다(신호통, 키 1.5 블록).
통 아가리에서 분홍 불씨가 샌다. 사람을 찍으면 눈을 뜨고 통을 곧추세워 얼굴이 통 밑에 깔린 꼴이 된다.

모델 gave:gabe/g132 상태:
  ""      통을 멘 종종걸음(다리 2자세)
  "aim"   통을 하늘로 세움(얼굴이 통 밑에서 위를 봄, 불씨가 통을 타고 오르는 4자세)
  "spent" 통을 내림(통이 등 뒤로 축 처짐)
가운데(몸 자리) = 얼굴(Java hover 0.8: 다리 끝이 땅).
소리 fuse(심지 타는 소리, 32), flare(신호탄 터짐, 64), swing(통 후려침, 16).
"""
import numpy as np

from gabe import kit


def _rgba(arr):
    return np.clip(np.round(arr), 0, 255).astype(np.uint8)


def ember_tex(n=8):
    out = np.zeros((n, n, 4), np.float32)
    out[..., :3] = (255, 120, 150)
    out[2:6, 2:6, :3] = (255, 220, 200)
    out[0, :, :3] = (220, 70, 110)
    out[..., 3] = 255
    return _rgba(out)


def mouth_tex(n=8):
    out = np.zeros((n, n, 4), np.float32)
    out[..., :3] = (40, 12, 18)
    out[3:5, 3:5, :3] = (255, 110, 140)
    out[..., 3] = 255
    return _rgba(out)


def legs(s, waved):
    for side, ph in ((1, 0), (-1, 180)):
        s.rod((side * 0.16, -0.26, 0.0), (side * 0.12, -1, 0.05), length=0.6, segs=1, thick=0.08, base=False,
              wave=kit.Wave(amp=26, phase=ph) if waved else None, toward=(0, 0, 1), poses=2 if waved else None, rag=False)


def barrel(s, k, root, d, length=1.05, embers=None):
    ch = s.rod(root, d, length=length, segs=1, thick=0.3, base=False, rag=False)
    tip = ch.tip(None)
    s.quad((tip[0], tip[1] + 0.005, tip[2]), (0.26, 0.26), "up", k.texture("mouth", mouth_tex()))
    return ch


def build(k: kit.Kit):
    m = k.model(poses=2, period=8, mut2=False)
    em = k.texture("ember", ember_tex())
    s = m.state("")
    s.head(size=0.6, depth=0.45, face="smile")
    legs(s, True)
    barrel(s, k, (0.05, -0.05, -0.32), (0.08, 0.85, -0.5))
    # 아가리에서 새는 불씨 하나(가만히)
    s.box((0.1, 0.92, -0.86), (0.18, 1.0, -0.78), em, uv=[0, 0, 8, 8])

    a = m.state("aim", poses=4, period=20)
    a.head(size=0.6, depth=0.45, at=(0, -0.05, 0.12), face="smile", rot=(-38, 0, 0))
    legs(a, False)
    barrel(a, k, (0, 0.22, -0.1), (0, 1, -0.02), length=1.2)
    for f in a.frames(4):
        with f:
            # 불씨가 통을 타고 오름(자세마다 한 칸씩)
            for j in range(2):
                y = 0.35 + (f.k + j * 2) % 4 * 0.26
                a.box((0.13, y, -0.16), (0.21, y + 0.08, -0.08), em, uv=[0, 0, 8, 8])
                a.box((-0.2, y + 0.12, -0.14), (-0.12, y + 0.2, -0.06), em, uv=[0, 0, 8, 8])

    sp = m.state("spent", poses=1)
    sp.head(size=0.6, depth=0.45, face="smile", rot=(10, 0, 0))
    legs(sp, False)
    barrel(sp, k, (0.05, -0.05, -0.3), (0.15, -0.35, -0.9), length=0.95)


def sounds(k: kit.Kit):
    k.sample("fuse", "tag:hiss", "심지 타는 소리", pitch=1.4, attenuation=32)
    k.sample("flare", "tag:firework", "신호탄", pitch=0.9, attenuation=64)
    k.sample("swing", "kenney_impact/impactpunch_heavy_00*", "통 후려침", pitch=1.1, attenuation=16)


def phrases():
    return {"cause_g132": "신호통에 맞았다", "cause_g132_pop": "신호통이 터졌다"}
