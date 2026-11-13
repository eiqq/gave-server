"""G79 접시 세는 게이브: 1 블록 얼굴이 머리 높이로 둥둥 떠 있고, 그 위 허공에 접시 꼴 원판 얼굴 아홉 장이 세로로 차곡차곡 떠 있다
(접시마다 동그랗게 눌린 웃는 얼굴). 엔드 막대기 둘은 긴 젓가락 한 벌로 얼굴 옆에서 맨 위 접시를 집는다. 얼굴 위 접시 탑 실루엣(키 2.8).

모델 gave:gabe/g79(원점 = 얼굴 가운데, shrink 0.6) 상태 ""(젓가락을 옆에 세움, 살랑), "pick"(젓가락을 접시 탑 위로 뻗음),
"scream"(아홉을 다 세고 비명: 찢어진 입, 핏발 선 눈, 젓가락을 벌림), "confused"(수가 흩어져 갸웃: 반쯤 뜬 눈).
부위 p1..p9 는 그 접시가 '없음'(던졌거나 깨짐): p1 이 맨 아래, p9 가 맨 위. 모델 gave:gabe/g79_plate(던진 접시 한 장).
소리 plate(접시 소리), crack(접시 깨짐), scream(비명), 글귀 cause_g79, cause_g79_scream.
"""
import numpy as np

from gabe import faces, kit

PLATE_D = 0.6
STACK_Y0, STACK_DY, STACK_Z = 0.62, 0.17, 0.32


def plate_tex():
    """원판 얼굴: 얼굴(부류 비트 그대로)을 동그랗게 오려 흰 테두리를 두름, 바깥 투명"""
    a = faces.far(faces.preset("smile")).copy()
    yy, xx = np.mgrid[0:32, 0:32].astype(float)
    r = np.hypot(xx - 15.5, yy - 15.5)
    rim = (r > 13.2) & (r <= 15.6)
    a[rim, 0], a[rim, 1] = 246, 240
    a[rim, 2] = (236 & 0xF8) | 0
    a[r > 15.6, 3] = 0
    return a


def plate(s, tex, at, tilt=12):
    """접시 한 장: 앞뒤를 보는 판 + 옆을 보는 판(십자로 겹쳐 어느 쪽에서 봐도 동그란 얼굴 접시)"""
    x, y, z = at
    h = PLATE_D / 2
    s.box((x - h, y - h, z - 0.012), (x + h, y + h, z + 0.012), {"south": tex, "north": tex}, faces=("south", "north"), uv=[0, 0, 16, 16],
          rot=(tilt, 0, 0) if tilt else None, origin=(x, y, z), face=True)
    s.box((x - h, y - h, z - 0.012), (x + h, y + h, z + 0.012), {"south": tex, "north": tex}, faces=("south", "north"), uv=[0, 0, 16, 16],
          rot=(0, 90, 0), origin=(x, y, z), face=True)


def body(k, s, name):
    tex = k.texture("plate", plate_tex(), classes=True)
    face = {"scream": faces.Face(eyes="bloodshot", mouth="torn", blush="deep"), "confused": faces.Face(eyes="half", tilt=-12)}.get(name, "smile")
    s.head(size=1.0, depth=0.8, face=face)
    # 접시 탑(앞으로 살짝 내밀고, 아래 접시가 이마를 가림)
    for i in range(9):
        with s.when(f"!on:p{i + 1}"):
            plate(s, tex, (0.04 * ((i % 3) - 1), STACK_Y0 + STACK_DY * i, STACK_Z - 0.02 * i), tilt=10 + 4 * (i % 2))
    # 젓가락 한 벌(얼굴 오른쪽 -X 옆)
    root = (-0.62, -0.2, 0.15)
    if name == "pick":
        dirs = ((0.45, 1.0, 0.25), (0.52, 1.0, 0.18))
        wave = None
    elif name == "scream":
        dirs = ((-0.6, 1.0, 0.2), (-0.1, 1.0, -0.3))
        wave = kit.Wave(amp=(8,), lag=20, cycles=2)
    else:
        dirs = ((0.05, 1.0, 0.2), (0.15, 1.0, 0.15))
        wave = kit.Wave(amp=(6,), lag=20)
    for j, d in enumerate(dirs):
        s.rod((root[0] + 0.05 * j, root[1], root[2] + 0.04 * j), d, length=1.5, segs=1, thick=0.07, toward=(0, 0, 1),
              wave=None if wave is None else kit.Wave(amp=wave.amp, lag=wave.lag, cycles=wave.cycles, phase=60 * j), poses=None if wave is None else 4)


def build(k: kit.Kit):
    m = k.model(poses=4, period=20, shrink=0.6, mut2=dict(root=(0.3, 0.3, -0.35), dir=(0.6, 1.0, -0.5), length=0.6, segs=1, poses=2))
    for name in ("", "pick", "scream", "confused"):
        body(k, m.state(name), name)
    p = k.model("g79_plate", poses=1, period=20, mut2=False, torn=False, open=False, rag=False)
    ps = p.state("")
    plate(ps, k.texture("plate", plate_tex(), classes=True), (0, 0, 0), tilt=0)
    ps.front = (0, 0, 0)


def sounds(k: kit.Kit):
    k.sample("plate", "tag:dishes", "접시 소리", pitch=1.1, volume=0.9, attenuation=24)
    k.sample("crack", "tag:glass", "접시 깨짐", pitch=1.2, attenuation=20)
    k.sample("scream", ["oga_creature80/scream_01", "oga_creature80/scream_02"], "비명", pitch=0.85, attenuation=32)


def phrases():
    return {"cause_g79": "접시에 맞았다", "cause_g79_scream": "아홉째 접시 비명"}
