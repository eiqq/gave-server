"""G186 양탄자 게이브: 가로세로 3 블록의 납작한 얼굴 양탄자. 손바닥만 한 _Gave 얼굴 서른여섯이 빈틈없이 깔려 위를 보고, 네 귀퉁이에
짧은 엔드 막대기 못이 하나씩 박혀 땅을 긁으며 끌려온다. 깔린 얼굴들은 실눈이다가 위에 선 사람의 발목을 물 때 눈을 뜬다(look.open).

모델 gave:gabe/g186(shrink 0.5, 몸 중심 = 판 = 땅 위 0.06: 그리기 높이 약속 API 16.7, 예고 0.02 와 쓰러짐 고리 0.04 위):
- ""(꿈틀 4자세 16틱: 칸마다 조금씩 들썩임).
소리 murmur(웅성거림, 20), bite(발목 묾, 16), scrape(못이 땅을 긁음, 16). 글귀 cause_g186.
"""
import numpy as np

from gabe import faces, kit


def tile(face):
    """64x64 칸: 32 얼굴 넷(2x2), 부류 비트 그대로(팔레트, 실눈 뜸 색이 먹게)"""
    a = faces.far(face)
    out = np.zeros((64, 64, 4), np.uint8)
    out[:32, :32] = a
    out[:32, 32:] = a[:, ::-1]
    out[32:, :32] = a[:, ::-1]
    out[32:, 32:] = a
    return out


LIFT = [[0, 1, 0], [1, 0, 1], [0, 1, 0]]


def build(k: kit.Kit):
    m = k.model(poses=4, period=16, shrink=0.5, mut2=False, torn=False)
    s = m.state("")
    shut = k.texture("rug", tile("smile"), classes=True)
    wide = k.texture("rug_open", tile(faces.Face(eyes="open")), classes=True)
    for f in s.frames():
        with f:
            for i in range(3):
                for j in range(3):
                    up = 0.03 * ((LIFT[i][j] + f.k) % 2) + 0.01 * ((i + j + f.k) % 3)
                    c = (i - 1.0, up, j - 1.0)
                    with s.when("!open"):
                        s.quad(c, (1, 1), "up", shut, face=True)
                    with s.when("open"):
                        s.quad(c, (1, 1), "up", wide, face=True)
    # 네 귀퉁이 못(구조 막대기): 뒤로 비스듬히 땅을 긁음
    for x, z in ((1.42, 1.42), (-1.42, 1.42), (1.42, -1.42), (-1.42, -1.42)):
        s.rod((x, 0.12, z), (x * 0.15, -1, -0.35), length=0.36, segs=1, thick=0.08, base=False, rag=False)
    s.front = (0, 1, 0)


def sounds(k: kit.Kit):
    # 이 종 전용 신호: 수십 장이 발밑에서 웅성거림
    k.sample("murmur", "tag:whisper", "웅성거림", pitch=0.8, attenuation=20)
    k.sample("bite", "tag:flesh", "발목을 묾", pitch=1.5, attenuation=16)
    k.sample("scrape", "tag:scratch", "못이 땅을 긁음", pitch=0.7, attenuation=16)


def phrases():
    return {"cause_g186": "양탄자 얼굴들에 물어뜯겼다"}
