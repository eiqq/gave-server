"""G66 바구니 진 덩치 게이브(1막, 거인 인형, 키 5.5 블록).

등이 활처럼 굽은 사람꼴 덩치. 분홍 살 몸통에 _Gave 얼굴 판 여러 장을 덧댔고 머리는 2 블록 _Gave 얼굴(어깨 앞으로 숙임).
등에 엔드 막대기로 엮은 바구니(지름 2.5)를 졌고 그 안에서 작은 얼굴들이 들썩이며 웃는다. 오른팔(-X)은 엔드 막대기를 묶은 몽둥이,
왼팔(+X)은 살 팔. 다리 둘은 굵은 살 다리(걸음 물결: 죽으면 래그돌에서 흐물거림).

- gave:gabe/g66(shrink 0.37, 블록 좌표 그대로 커짐): 몸 가운데가 (0, 0, 0), 발바닥 y = -2.75(Java hover 2.75).
  상태 ""(걷기: 다리 걸음, 바구니 얼굴 들썩, 24틱 한 걸음 주기), "stand"(멈춰 섬: 다리 가만, 바구니 얼굴 들썩),
  "throw"(던지기: 살 팔이 바구니에 들어갔다 머리 위로 앞으로 내던짐. 20틱, 자세 8. Java 가 자세 0 에 맞춰 켜고 16틱째에 놓음),
  "swing"(몽둥이 휘두르기: 오른쪽 뒤에서 앞을 가로질러 왼쪽으로. 12틱, 자세 4. 자세 2 가 앞을 지나는 때).
  부위 "empty": 바구니가 부서짐(테가 빠지고 얼굴들이 사라짐).
- gave:gabe/g66_kid: 던져지는 새끼(0.55 블록 얼굴, 버둥대는 팔다리 막대기 넷). 상태 ""(버둥).
소리 toss(끙 던짐, 40), swing(몽둥이 휘두름, 24), crack(바구니 부서짐, 32), kid(새끼 킥킥, 16). 걸음은 Java 가 공용 sfx.stomp.
"""
import math

import numpy as np

from gabe import kit

S = 0.37            # 모델 줄임(블록 좌표 ±4 까지)
FOOT = -2.75        # 발바닥 높이
HIP = (0.72, -0.45, -0.1)
LEG_L, LEG_T = 2.45, 0.7
SH_L, SH_R = (1.55, 1.05, 0.65), (-1.55, 1.05, 0.65)   # 왼 어깨(+X, 살 팔), 오른 어깨(-X, 몽둥이)
BASKET_C, BASKET_R, BASKET_Y0, BASKET_Y1 = (0.0, 0.0, -1.2), 1.35, 0.15, 3.05
STAVES = 7
LEAN = 28           # 몸통이 앞으로 숙인 각(도)
PIVOT = np.array((0.0, -0.75, 0.0))
TW, TH, TD = 1.45, 2.35, 0.85   # 몸통 반 폭, 높이(골반에서), 반 두께


def _u(v):
    v = np.array(v, float)
    return v / np.linalg.norm(v)


def limb(s, a, b, thick, tex, segs=1):
    """a 에서 b 까지 곧은 막대기(자세 없음, 받침 없음)"""
    a, b = np.array(a, float), np.array(b, float)
    d = b - a
    up = abs(d[1]) > 0.97 * np.linalg.norm(d)
    return s.rod(tuple(a), tuple(d), length=float(np.linalg.norm(d)), segs=segs, thick=thick, base=False, tex=tex, rag=False,
                 toward=(0, 0, 1) if up else (0, 1, 0))


def lean(p):
    """몸통 틀(숙이기 전) 점 → 모델 점"""
    return tuple(kit.rot_axis((1, 0, 0), LEAN) @ (np.array(p, float) - PIVOT) + PIVOT)


def torso(s, k):
    """굽은 몸통(앞으로 숙임) + 덧댄 얼굴 판 + 숙인 머리. 몸통이 얼굴보다 넓다(멀리서 덩치로 읽히게)"""
    y0, y1 = PIVOT[1], PIVOT[1] + TH
    s.box((-TW, y0, -TD), (TW, y1, TD), "flesh", rot=(LEAN, 0, 0), origin=tuple(PIVOT), face=True)
    # 배: 아래로 처진 살 덩어리
    s.box((-TW * 0.8, y0 - 0.35, -TD * 0.7), (TW * 0.8, y0 + 0.6, TD * 1.1), "flesh", rot=(LEAN * 0.5, 0, 0), origin=tuple(PIVOT),
          face=True)
    # 덧댄 얼굴 판: 몸통 앞(+Z), 옆(+-X) 겉에 기울여 붙임(바늘땀 얼굴, 눈물 얼굴, 반쯤 뜬 얼굴)
    for (u, v), side, tilt, f, sz in (((0.62, 0.36), "south", 7, "stitch", 1.0), ((-0.62, 0.22), "south", -9, "tears", 1.0),
                                      ((0.05, 0.02), "south", 3, "half", 0.9), ((0.05, 0.55), "east", 0, "smile_c", 1.0),
                                      ((-0.1, 0.4), "west", 0, "bloodshot", 1.0)):
        y = y0 + v * TH
        if side == "south":
            c = lean((u, y, TD + 0.02))
            s.quad(c, (sz, sz), "south", k.face_tex(f), rot=(LEAN, 0, tilt), origin=c, face=True)
        else:
            x = TW + 0.02 if side == "east" else -TW - 0.02
            c = lean((x, y, u))
            s.quad(c, (sz, sz), side, k.face_tex(f), rot=(LEAN, 0, 0), origin=c, face=True)
    # 숙인 머리: 어깨 앞 위
    hc = lean((0, y1 - 0.25, TD + 0.35))
    s.head(size=1.85, depth=1.35, at=(0, hc[1] + 0.35, hc[2] + 0.2), face="smile", rot=(14, 0, 0))
    # 어깨 살 덩어리(팔이 붙는 곳)
    for x in (TW, -TW):
        c = lean((x, y1 - 0.45, 0.2))
        s.box((c[0] - 0.42, c[1] - 0.45, c[2] - 0.45), (c[0] + 0.42, c[1] + 0.45, c[2] + 0.45), "flesh", rot=(LEAN, 0, 0), origin=c,
              face=True)


def legs(s, wave):
    for side in (1, -1):
        root = (side * HIP[0], HIP[1], HIP[2])
        if wave is None:
            s.rod(root, (side * 0.08, -1, 0.04), length=LEG_L, segs=2, thick=LEG_T, tex="flesh", rag=True)
        else:
            w = kit.Wave(amp=wave.amp, lag=wave.lag, bias=wave.bias, phase=0 if side > 0 else 180, shape=wave.shape)
            s.rod(root, (side * 0.08, -1, 0.04), length=LEG_L, segs=2, thick=LEG_T, tex="flesh", wave=w, toward=(0, 0, 1))
        # 발: 납작한 살 덩어리(가만히, 걸음 물결에 안 따라감: 발목 아래 끝이 바닥에 깔림)


def basket(s, k, bob_cycles):
    """등 바구니: 세로 엔드 막대기 살 + 위 테 + 바닥 판, 안에서 들썩이는 작은 얼굴들(바구니가 부서지면 테와 얼굴이 사라짐)"""
    cx, _, cz = BASKET_C
    tops = []
    for i in range(STAVES):
        a = 2 * math.pi * (i + 0.5) / STAVES
        x, z = cx + BASKET_R * math.cos(a), cz + BASKET_R * 0.85 * math.sin(a)
        # 살은 위로 갈수록 살짝 벌어짐
        top = (x * 1.08, BASKET_Y1, cz + (z - cz) * 1.08)
        limb(s, (x, BASKET_Y0, z), top, 0.13, "rod")
        tops.append(top)
    with s.when("!on:empty"):
        for i in range(STAVES):
            limb(s, tops[i], tops[(i + 1) % STAVES], 0.11, "rod")
    with s.when("on:empty"):
        # 부서진 바구니: 테 대신 꺾여 늘어진 토막 둘
        limb(s, tops[1], (tops[1][0] + 0.3, BASKET_Y1 - 0.9, tops[1][2] - 0.5), 0.11, "rod")
        limb(s, tops[4], (tops[4][0] - 0.4, BASKET_Y1 - 0.8, tops[4][2] - 0.3), 0.11, "rod")
    s.box((cx - BASKET_R * 0.95, BASKET_Y0 - 0.12, cz - BASKET_R * 0.8), (cx + BASKET_R * 0.95, BASKET_Y0, cz + BASKET_R * 0.8), "rod")
    # 멜빵: 어깨에서 바구니 테로 살 띠 둘(가만히)
    for side in (1, -1):
        limb(s, (side * 0.75, 1.5, -0.15), (side * 0.85, BASKET_Y1 - 0.2, cz + BASKET_R * 0.6), 0.16, "flesh")
    # 작은 얼굴들: 테 위로 머리를 내밀고 손뼉 치듯 들썩(자세 4, 서로 엇갈림)
    kids = ((0.0, -1.0, "smile_a", 0.0), (0.62, -1.45, "smile", 0.5), (-0.6, -1.5, "smile_c", 0.25),
            (0.3, -2.1, "tears", 0.75), (-0.35, -2.05, "smile_a", 0.6))
    with s.when("!on:empty"):
        for f in s.frames(poses=4):
            with f:
                for x, z, face, ph in kids:
                    h = 0.18 * max(0.0, math.sin(2 * math.pi * (bob_cycles * f.t + ph)))
                    # 바깥(몸 뒤쪽 위)을 보고 살짝 젖힘: 뒤에 선 사람에게도 웃는 얼굴이 보이게 몸 뒤(-Z) 쪽을 봄
                    yaw = math.degrees(math.atan2(x - cx, -(z - cz) + 0.6)) * 0.7
                    s.quad((x, BASKET_Y1 - 0.05 + h, z), (0.62, 0.62), "north", k.face_tex(face), rot=(-18, yaw, 0),
                           origin=(x, BASKET_Y1 - 0.05 + h, z), face=True)


def arm_dir(theta, side=1.0, splay=0.18):
    """어깨에서 팔이 뻗는 쪽: theta 0 아래, 90 앞, 180 위, 270 뒤(몸 옆으로 splay 만큼 벌림)"""
    t = math.radians(theta)
    return _u((side * splay, -math.cos(t), math.sin(t)))


def flesh_arm(s, theta, bend=25):
    """왼팔(+X): 위팔 1.35 + 아래팔 1.25 + 손 덩어리"""
    a = np.array(SH_L)
    e = a + arm_dir(theta, 1.0) * 1.35
    h = e + arm_dir(theta + bend, 1.0, 0.05) * 1.25
    limb(s, a, e, 0.42, "flesh")
    limb(s, e, h, 0.36, "flesh")
    s.box(tuple(h - 0.28), tuple(h + 0.28), "flesh", face=True)


def club_arm(s, yaw, pitch):
    """오른팔(-X): 위팔 + 몽둥이(엔드 막대기 다섯을 살 띠로 묶음). yaw: 몸 앞에서 잰 수평 각(+ 는 왼쪽), pitch: 아래로 숙인 각"""
    a = np.array(SH_R)
    y, p = math.radians(yaw), math.radians(pitch)
    d = _u((math.sin(y) * math.cos(p), -math.sin(p), math.cos(y) * math.cos(p)))
    e = a + d * 1.25
    limb(s, a, e, 0.42, "flesh")
    # 몽둥이: 손목에서 같은 쪽으로 2.3, 막대기 다섯을 십자로 모아 묶음
    side = _u(np.cross(d, (0, 1, 0)) if abs(d[1]) < 0.95 else (1, 0, 0))
    up = _u(np.cross(side, d))
    for ox, oy in ((0, 0), (0.15, 0), (-0.15, 0), (0, 0.15), (0, -0.15)):
        o = e + side * ox + up * oy
        limb(s, o, o + d * 2.3, 0.15, "rod")
    c = e + d * 0.35
    s.box(tuple(c - 0.24), tuple(c + 0.24), "flesh", face=True)


def body(s, k, *, wave=None, arm=20, club=(-20, 62), bob=1):
    torso(s, k)
    legs(s, wave)
    basket(s, k, bob)
    if arm is not None:
        flesh_arm(s, arm)
    if club is not None:
        club_arm(s, *club)


def build(k: kit.Kit):
    m = k.model(poses=8, period=24, shrink=S, mut2=False)
    # 걷기: 넓적다리 ±22도, 무릎은 뒤로 굽음(정강이 물결이 늦게 따라옴)
    walk = kit.Wave(amp=(22, 16), lag=70, bias=(6, -14))
    body(m.state(""), k, wave=walk, bob=1)
    body(m.state("stand", poses=4), k, bob=1)
    # 던지기: 살 팔이 앞 아래 → 위 → 뒤 위(바구니 속) → 머리 위 앞으로 내던짐 → 내림. 자세 8, 20틱
    th = m.state("throw", poses=8, period=20)
    body(th, k, arm=None, bob=2)
    thetas = (20, 95, 170, 215, 225, 175, 120, 55)
    bends = (25, 30, 40, 55, 60, 30, 5, 15)
    for f in th.frames():
        with f:
            flesh_arm(th, thetas[f.k], bends[f.k])
    # 휘두르기: 몽둥이가 오른쪽 뒤 위 → 오른쪽 앞 → 앞 → 왼쪽 앞 아래(자세 4, 12틱)
    sw = m.state("swing", poses=4, period=12)
    body(sw, k, club=None, bob=2)
    clubs = ((-125, 5), (-60, 10), (0, 18), (55, 30))
    for f in sw.frames():
        with f:
            club_arm(sw, *clubs[f.k])

    # 던져지는 새끼
    kd = k.model("g66_kid", poses=4, period=8, mut2=False)
    s = kd.state("")
    h = s.head(size=0.55, depth=0.45, face="smile_a")
    for side in (1, -1):
        s.rod(h.point(side * 0.9, 0.1, 0), (side, 0.4, 0.2), length=0.42, segs=2, thick=0.07,
              wave=kit.Wave(amp=(35, 25), lag=60, phase=0 if side > 0 else 90, cycles=2), toward=(0, 1, 0), poses=4)
        s.rod(h.point(side * 0.5, -0.9, 0), (side * 0.3, -1, -0.2), length=0.36, segs=2, thick=0.07,
              wave=kit.Wave(amp=(30, 20), lag=50, phase=90 if side > 0 else 270, cycles=2), toward=(0, 0, 1), poses=4)
    k.icon(model="g66", state="stand")


def sounds(k: kit.Kit):
    k.sample("toss", ["oga_creature80/grunt_01", "oga_creature80/grunt_03", "oga_creature80/troll_02"], "끙 던짐", pitch=0.6,
             attenuation=40)
    k.sample("swing", "tag:flap", "몽둥이 휘두름", pitch=0.55, attenuation=24)
    k.sample("crack", ["oga_breaking75/bfh1_rock_breaking_01", "oga_metalwood100/wood_slam_02"], "바구니 부서짐", pitch=0.7,
             attenuation=32)
    k.sample("kid", "tag:laugh", "새끼 킥킥", pitch=1.2, attenuation=16)


def phrases():
    return {"cause_g66": "덩치의 몽둥이에 맞았다", "cause_g66_kid": "던져진 새끼에 물렸다"}
