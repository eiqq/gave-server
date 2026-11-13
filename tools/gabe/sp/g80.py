"""G80 춤추는 굴렁쇠 게이브: 0.5블록 얼굴 여섯이 엔드 막대기 바퀴살 여섯(지름 막대기 셋)으로 가운데 작은 얼굴 하나(양옆 모두 얼굴)에 이어진
굴렁쇠(지름 1.6). 바퀴 테두리 얼굴은 모두 바깥을 보고 짙은 홍조로 신났다. 굴렁쇠 면은 몸의 앞뒤(+Z)를 따라 서 있다(옆에서 보면 바퀴).

상태: "roll"(기본, 구름: 손으로 짠 4자세로 15도씩 돎. 얼굴 여섯이라 60도마다 같은 모양이 되어 4자세가 끝없이 이어짐. 빠르기는 Java 가
body.look.rate 로 맞춤), "bounce"(튕겨 나감: 가만한 바퀴, 눈 뜸 얼굴).
몸 가운데 = 바닥 위 0.8(허리 띠). 떼 종이라 변이 부위는 끈다.
"""
import math

from gabe import faces, kit

RR = 0.62          # 테두리 얼굴 가운데까지 반지름
RIM = 0.5          # 테두리 얼굴 크기
HUB = 0.32         # 가운데 얼굴 크기


def hoop(s, k, ang, face):
    """굴렁쇠 한 자세: ang 만큼 돈(도, 위에서 앞으로) 테두리 얼굴 여섯 + 지름 막대기 셋"""
    for i in range(6):
        phi = ang + i * 60
        a = math.radians(phi)
        at = (0.0, RR * math.cos(a), RR * math.sin(a))
        s.head(size=RIM, depth=0.34, at=at, rot=(phi - 90, 0, 0), face=face, near=False)
    for i in range(3):
        a = math.radians(ang + 30 + i * 60)
        d = (0.0, math.cos(a), math.sin(a))
        r = RR - 0.16
        s.rod((0.0, -d[1] * r, -d[2] * r), d, length=2 * r, segs=1, thick=0.07, base=False, rag=False, toward=(1, 0, 0))


def build(k: kit.Kit):
    m = k.model(poses=4, period=8, mut2=False, torn=False)
    glee = faces.Face(eyes="squint", blush="deep")
    hub = k.face_tex(faces.preset("smile"))

    r = m.state("roll")
    r.head(size=HUB, depth=0.22, rot=(0, 90, 0), face="smile", sides=hub, near=False)
    for fr in r.frames():
        with fr:
            hoop(r, k, 15 * fr.k, glee)
    r.front = (1, 0, 0)
    m.default = "roll"

    b = m.state("bounce")
    b.head(size=HUB, depth=0.22, rot=(0, 90, 0), face="open", sides=k.face_tex(faces.preset("open")), near=False)
    hoop(b, k, 0, faces.Face(eyes="open", blush="deep"))
    b.front = (1, 0, 0)

    k.icon(model="g80", state="roll", view="side")


def sounds(k: kit.Kit):
    # 굴러올 때 달그락달그락(이 종 전용)
    k.sample("roll", "tag:light+impact-glass", "굴렁쇠 굴러옴", pitch=1.15, volume=0.7, attenuation=20)
    # 치고 튕김
    k.sample("bonk", "tag:wood+impact", "쿵", pitch=1.2, attenuation=16)
    # 춤이 걸림, 옮음(작은 웃음)
    k.sample("dance", "tag:cute", "깔깔", pitch=1.2, volume=0.8, attenuation=16)


def phrases():
    return {"cause_g80": "굴렁쇠에 치였다"}
