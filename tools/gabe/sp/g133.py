"""G133 물마루 게이브(4막, 떼, 돌격): 폭 3 블록, 높이 0.5 블록의 낮은 분홍 물결 띠 하나에 손바닥만 한 _Gave 얼굴 열둘이 비늘처럼 겹쳐 박혔다.
띠 양 끝에 짧은 엔드 막대기가 말목처럼 꽂혀 있고, 물마루가 설 때 두 막대기가 벌떡 서서 띠를 세로로 당겨 올린다.

모델 gave:gabe/g133(shrink 0.5: 선 물마루가 3 블록) 상태:
  ""       낮은 띠: 물결이 뒤에서 앞으로 굴러가는 자세 4(프레임 접기), 얼굴 비늘은 실눈, 말목은 비스듬히 누움
  "crest"  선 물마루: 3 블록 벽이 위에서 앞으로 말림, 얼굴 비늘 열둘이 눈을 뜸, 말목 둘이 기둥처럼 섬
  "ebb"    쓸려 나감: 낮고 납작한 띠, 얼굴은 반쯤 감김, 말목은 뒤로 누움
가운데(몸 자리) = 띠 가운데, 바닥 위 0.3(Java HOVER 0.3). 앞 +Z = 밀려오는 쪽.
소리 surge(밀려오는 물소리, 32), crash(물마루 부서짐, 24), lap(낮게 무는 물소리, 16).
"""
import math

from gabe import kit

W = 1.5          # 띠 반 폭
DEPTH = 0.7      # 띠 반 깊이
FLOOR = -0.3     # 바닥 높이(몸 틀)
SLATS = 4
FACES = ("smile_a", "smile", "smile_c", "half")


def ripple(i, t, amp=0.13):
    """물결 높이(띠 윗면, 바닥 위): 뒤(i=0)에서 앞으로 굴러감"""
    return 0.22 + amp * math.sin(2 * math.pi * (t - i / SLATS))


def low_band(s, k, t, amp, face_list, flat=False):
    """낮은 띠 한 자세: 살 판 넷(앞뒤로) + 얼굴 비늘 열둘(3줄 x 4)"""
    wet = k.part_tex("wetskin")
    dz = 2 * DEPTH / SLATS
    for i in range(SLATS):
        z0 = -DEPTH + i * dz
        h = (0.12 if flat else ripple(i, t, amp))
        s.box((-W, FLOOR, z0), (W, FLOOR + h, z0 + dz + 0.02), wet, faces=("up", "south", "north", "east", "west"), face=True)
    # 비늘: 앞쪽 위로 비스듬히(앞에서 보이게), 줄마다 반 칸씩 어긋남
    for row in range(3):
        z = -DEPTH + 0.3 + row * 0.42
        i = min(SLATS - 1, int((z + DEPTH) / dz))
        h = 0.12 if flat else ripple(i, t, amp)
        for col in range(4):
            x = -1.1 + col * 0.74 + (0.37 if row % 2 else 0) - 0.18
            f = face_list[(row * 4 + col) % len(face_list)]
            s.quad((x, FLOOR + h + 0.03, z), (0.5, 0.5), "south", k.face_tex(f), rot=(-55, 0, 0), origin=(x, FLOOR + h + 0.03, z), face=True)


def posts(s, mode):
    """띠 양 끝 말목 둘(가만히): low 비스듬히 앞으로, stand 기둥, back 뒤로 누움"""
    for side in (1, -1):
        x = side * (W + 0.06)
        if mode == "stand":
            s.rod((x, FLOOR, 0.05), (side * 0.08, 1, 0.12), length=3.0, segs=1, thick=0.13, base=False, rag=False)
        elif mode == "back":
            s.rod((x, FLOOR + 0.05, 0.1), (side * 0.2, 0.35, -1), length=0.75, segs=1, thick=0.11, base=False, rag=False)
        else:
            s.rod((x, FLOOR + 0.05, -0.1), (side * 0.15, 0.55, 1), length=0.8, segs=1, thick=0.11, base=False, rag=False)


def crest(s, k):
    """선 물마루: 아래 벽 + 앞으로 기운 위 벽 + 앞으로 말린 입술, 앞면에 눈 뜬 얼굴 비늘 열둘"""
    wet = k.part_tex("wetskin")
    s.box((-W, FLOOR, -0.35), (W, 1.2, 0.05), wet, face=True)
    # 위 벽: 아래 벽 위 모서리(앞)에서 앞으로 20도
    s.box((-W, 1.15, -0.3), (W, 2.15, 0.05), wet, rot=(20, 0, 0), origin=(0, 1.15, 0.05), face=True)
    # 말린 입술: 꼭대기에서 앞 아래로
    top = (0, 1.15 + math.cos(math.radians(20)), 0.05 + math.sin(math.radians(20)))
    s.box((-W, top[1] - 0.05, top[2] - 0.3), (W, top[1] + 0.25, top[2] + 0.45), wet, rot=(55, 0, 0), origin=top, face=True)
    # 앞면 비늘 열둘(눈 뜸): 아래 벽 두 줄 + 위 벽 한 줄
    opens = ("open", "bloodshot", "open", "half")
    for row in range(3):
        for col in range(4):
            x = -1.1 + col * 0.74 + (0.37 if row % 2 else 0) - 0.18
            f = opens[(row * 4 + col) % len(opens)]
            if row < 2:
                y = 0.2 + row * 0.5
                s.quad((x, y, 0.07), (0.56, 0.56), "south", k.face_tex(f), face=True)
            else:
                y = 1.6
                zz = 0.07 + (y - 1.15) * math.tan(math.radians(20))
                s.quad((x, y, zz), (0.56, 0.56), "south", k.face_tex(f), rot=(20, 0, 0), origin=(x, y, zz), face=True)
    # 마루 끝 큰 얼굴 하나(꼭대기에서 내려다봄)
    s.quad((0, top[1] + 0.05, top[2] + 0.5), (0.9, 0.9), "south", k.face_tex("open"), rot=(55, 0, 0), origin=(0, top[1] + 0.05, top[2] + 0.5),
           face=True)


def build(k: kit.Kit):
    m = k.model(poses=4, period=16, shrink=0.5, mut2=False, torn=False)
    s = m.state("")
    for f in s.frames():
        with f:
            low_band(s, k, f.t, 0.13, FACES)
    posts(s, "low")
    s.front = (0, 1, 0)

    c = m.state("crest", poses=1)
    crest(c, k)
    posts(c, "stand")
    c.front = (0, 0, 1)

    e = m.state("ebb", poses=1)
    low_band(e, k, 0, 0, ("half", "blank", "half", "smile_c"), flat=True)
    posts(e, "back")
    e.front = (0, 1, 0)


def sounds(k: kit.Kit):
    k.sample("surge", "tag:wet+flow", "밀려오는 물소리", pitch=0.85, attenuation=32)
    k.sample("crash", "tag:splash", "물마루 부서짐", pitch=0.7, attenuation=24)
    k.sample("lap", "oga_water40/splash_0*", "물이 발목을 묾", pitch=1.2, volume=0.7, attenuation=16)


def phrases():
    return {"cause_g133": "물마루에 깔렸다"}
