"""G64 등 뒤에 앉는 게이브: 손바닥만 한 0.4블록 얼굴 양옆에 엔드 막대기 둘을 뼈대(위 가장자리)로 한 얼굴 판 날개(작은 방패연 꼴, 날개 앞면에 얼굴이 하나씩).
날개 끝까지 폭 1.6 이라 멀리서 '얼굴 셋 달린 연'으로 읽힌다.

상태: "fly"(날개 퍼덕 4자세, 기본), "sit"(앉음: 날개를 접어 얼굴을 감싼 작은 분홍 돌, 위에 막대기 둘이 가위표), "dive"(덮침: 날개를 뒤로 70도 젖힘).
떼 종이라 변이 부위(입 찢어짐, 막대기 둘 더)는 끈다.
"""
import math

from gabe import kit

HEAD = 0.4
WING_W, WING_H = 0.62, 0.5   # 날개 판 폭(옆으로), 높이


def wing(s, k, side, ang):
    """날개 하나: 머리 옆(뿌리)에서 옆으로 선 얼굴 판(앞면 얼굴, 뒷면 뒤통수) + 위 가장자리의 엔드 막대기 뼈대.
    ang 은 뿌리를 세로축으로 날개 끝을 뒤로 젖힌 각(도): 나비처럼 앞뒤로 친다"""
    root = (side * HEAD / 2, 0.0, 0.0)
    a = math.radians(ang)
    c = (side * (HEAD / 2 + WING_W / 2), 0.0, 0.0)
    rot = (0.0, side * ang, 0.0)
    s.quad(c, (WING_W, WING_H), "south", k.face_tex("half"), face=True, rot=rot, origin=root)
    s.quad((c[0], c[1], c[2] - 0.004), (WING_W, WING_H), "north", k.skin_tex("back"), face=True, rot=rot, origin=root)
    d = (side * math.cos(a), 0.0, -math.sin(a))
    s.rod((root[0], WING_H / 2, 0.0), d, length=WING_W + 0.05, segs=1, thick=0.05, base=False, rag=False)


def build(k: kit.Kit):
    m = k.model(poses=4, period=8, mut2=False, torn=False)

    # ---------------------------------------------------------------- 날기: 날개 퍼덕(뿌리를 축으로 위아래 4자세)
    f = m.state("fly")
    f.head(size=HEAD, depth=0.3, face="smile")
    for fr in f.frames():
        with fr:
            ang = 10 + 35 * math.sin(2 * math.pi * fr.t)
            for side in (1, -1):
                wing(f, k, side, ang)
    m.default = "fly"

    # ---------------------------------------------------------------- 앉음: 날개를 접어 얼굴을 감싼 작은 분홍 돌
    s = m.state("sit")
    s.head(size=HEAD * 0.95, depth=0.3, at=(0, -0.08, 0), face="smile", near=False)
    s.box((-0.25, -0.26, -0.22), (0.25, 0.1, 0.22), "flesh", face=True)
    s.box((-0.2, 0.1, -0.18), (0.2, 0.17, 0.18), "flesh", face=True)
    for side in (1, -1):
        s.rod((side * 0.26, 0.12, -0.2), (-side * 1.0, 0.05, 1.0), length=0.62, segs=1, thick=0.05, base=False, rag=False)
    s.front = (0, 1, 0)

    # ---------------------------------------------------------------- 덮침: 날개를 뒤로 젖히고 얼굴로 곧장
    d = m.state("dive")
    d.head(size=HEAD, depth=0.3, face="open")
    for side in (1, -1):
        wing(d, k, side, 70)

    k.icon(model="g64", state="fly", view="front")


def sounds(k: kit.Kit):
    # 내려앉을 때 아주 작은 '사뿐'(이 종 전용, 반경 12)
    k.sample("land", "tag:cloth", "사뿐 내려앉음", pitch=1.25, volume=0.55, attenuation=12)
    # 한꺼번에 날개를 펴고 덮침
    k.sample("flap", "tag:cloth+flap", "퍼덕", pitch=1.4, volume=0.8, attenuation=16)


def phrases():
    return {"cause_g64": "등 뒤에 앉은 연들에 덮였다"}
