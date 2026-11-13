"""압도감 연출(ovw12): 흙먼지 너머 지평선의 그림자 떼. 실제 개체가 아닌 연출용 모델(맞지 않음, 감독 대기열과 무관).

모델 gave:gabe/horizon(키트, _Gave 얼굴 텍스처 + 엔드 막대기). 디스플레이 하나에 게이브 수십 마리를 담는다: 얼굴은 정면 판 하나
(먼 얼굴 32), 가오리 날개 막대기 둘은 판 하나씩. Java kr.gave.game.Horizon 이 세로 빌보드(VERTICAL: 늘 보는 사람 쪽)로 세우므로
앞면(+Z)만 굽는다. 얼굴 판은 자세 없이 한 번, 날개만 자세 3(주기 48틱)으로 굽고 마리마다 위상이 달라 떼가 따로따로 퍼덕인다
(자세 4 는 면이 360 이라 카드 47장이 몹 300 옆에서 프레임을 더 먹어 3 으로 줄임, docs/모듈/압도감.md 8절).

상태(strings[0]):
  ""   땅 떼 + 낮게 떠 오는 것 + 위에 나는 것(가까운 줄, 40마리)
  "b"  다른 배치(가까운 줄, 40마리)
  "c"  큰 것 몇이 우뚝 선 먼 줄(16마리, 얼굴이 큼)
면 = 얼굴 n + 날개 2 x 자세 3 x n(상태마다 400 아래). 래그돌 없음(죽지 않음). 모델 틀은 shrink 0.5(+-3블록), 엔진이 Look.scale(2) 배를 곱한다.
모델 가운데에서 맨 아래(가장 낮은 날개 끝)까지 FOOT 블록(Java Horizon.FOOT 과 같게, 굽기 끝에 찍어 확인).
"""
import math

import numpy as np

from gabe import kit

POSES = 3
PERIOD = 48
# 얼굴 처방 가중치(대부분 실눈 웃음, 드물게 다른 얼굴: 흙먼지 속에서는 거의 같아 보이고 하늘이나 가까운 줄에서만 티가 남)
FACES = [("smile", 10), ("smile_a", 6), ("smile_c", 6), ("half", 3), ("tears", 2), ("deep", 2), ("open", 1), ("bloodshot", 1),
         ("teeth_ring", 1)]
# 띠: (y 아래, y 위, z, 크기 아래, 크기 위, 마리 수, x 반폭). 블록, 모델 가운데 기준. 아래(땅에 붙은 떼)는 빽빽하고 넓게, 위로 갈수록
# 드물게 떠 있는 것(가장자리가 네모로 잘려 보이지 않게 위 띠는 폭도 좁힘). 뒤 띠일수록 높이 뜨고 작다
ROWS_A = [(1.5, 2.6, -1.3, 0.42, 0.58, 4, 1.9), (0.35, 1.5, -1.0, 0.5, 0.68, 8, 2.3), (-0.6, 0.35, -0.65, 0.6, 0.82, 12, 2.6),
          (-1.35, -0.6, -0.3, 0.7, 0.95, 16, 2.7)]
ROWS_B = [(1.2, 2.5, -1.3, 0.42, 0.6, 5, 2.2), (0.2, 1.2, -1.0, 0.5, 0.7, 9, 2.5), (-0.7, 0.2, -0.65, 0.62, 0.84, 12, 2.7),
          (-1.35, -0.7, -0.3, 0.72, 0.95, 14, 2.6)]
ROWS_C = [(0.6, 2.0, -1.2, 0.9, 1.15, 4, 2.0), (-0.5, 0.6, -0.8, 1.05, 1.35, 6, 2.2), (-1.0, -0.3, -0.4, 1.2, 1.5, 6, 2.1)]
FOOT = 2.2


def _pick_faces(rng, n):
    names = [f for f, w in FACES for _ in range(w)]
    return [names[i] for i in rng.randint(0, len(names), n)]


def _spots(rng, rows):
    """줄마다 (x, y, z, 크기) 를 서로 너무 겹치지 않게 흩뿌림(앞 줄일수록 z 가 커 앞에 그려짐)"""
    out = []
    for (y0, y1, z, s0, s1, n, xh) in rows:
        row = []
        tries = 0
        while len(row) < n and tries < 4000:
            tries += 1
            s = rng.uniform(s0, s1)
            x = rng.uniform(-xh + s * 0.2, xh - s * 0.2)
            y = rng.uniform(y0, y1)
            if all((x - q[0]) ** 2 + ((y - q[1]) * 1.3) ** 2 > (0.42 * (s + q[2])) ** 2 for q in row):
                row.append((x, y, s))
        for i, (x, y, s) in enumerate(sorted(row, key=lambda q: q[1], reverse=True)):
            # 같은 줄 안에서도 깊이를 조금씩 달리해 겹친 면이 깜빡이지 않게(아래에 있는 것이 앞)
            out.append((x, y, z + i * 0.012, s))
    return out


def _wing(s, k, root, sigma, phi_deg, size, z):
    """날개 막대기 판 하나: 뿌리에서 sigma 쪽(+1 = +X)으로, 수평에서 phi 도(+ 위). 세운 판(길이가 +Y)을 z 축으로 돌려 눕힌다.
    회전 전 판이 모델 틀(+-3블록) 위로 나가면 아래로 세운 판(길이가 -Y)을 반 바퀴 더 돌린다(키트 사슬 마디와 같은 수)"""
    length = 1.5 * size
    thick = max(0.07, 0.14 * size)
    phi = math.radians(phi_deg)
    theta = math.degrees(math.atan2(-sigma * math.cos(phi), math.sin(phi)))
    x, y = root
    tex = k.part_tex("rod")
    if y + length <= 2.95:
        frm, to = (x - thick / 2, y, z), (x + thick / 2, y + length, z)
    else:
        frm, to = (x - thick / 2, y - length, z), (x + thick / 2, y, z)
        theta += 180.0
    return s.box(frm, to, tex, faces=("south",), uv=[0, 0, 2, 16], rot=(0, 0, theta), origin=(x, y, z))


def crowd(k, s, rng, rows, wing_base=-8.0, wing_amp=22.0):
    """상태 s 에 게이브 떼: 얼굴 판(자세 없음) + 날개 판(자세마다)"""
    spots = _spots(rng, rows)
    faces = _pick_faces(rng, len(spots))
    phases = rng.uniform(0, 2 * math.pi, len(spots))
    rolls = rng.uniform(-12, 12, len(spots))
    for (x, y, z, size), face, roll in zip(spots, faces, rolls):
        s.quad((x, y, z), (size, size), "south", k.face_tex(face), face=True, rot=(0, 0, roll), origin=(x, y, z))
    for f in s.frames(POSES):
        with f:
            for (x, y, z, size), ph in zip(spots, phases):
                phi = wing_base + wing_amp * math.sin(2 * math.pi * f.t + ph)
                ry = y - 0.25 * size
                for sigma in (1, -1):
                    _wing(s, k, (x + sigma * 0.44 * size, ry), sigma, phi, size, z - 0.004)
    return spots


def build(k: kit.Kit):
    m = k.model("horizon", poses=POSES, period=PERIOD, shrink=0.5, mut2=False, open=False, torn=False, rag=False)
    low = []
    for name, rows, seed in (("", ROWS_A, 11), ("b", ROWS_B, 23), ("c", ROWS_C, 37)):
        spots = crowd(k, m.state(name), np.random.RandomState(seed), rows)
        # 맨 아래: 얼굴 아래 끝과 가장 처진 날개 끝(-30도)
        low.append(min(min(y - s / 2, y - 0.25 * s - 1.5 * s * math.sin(math.radians(30))) for (x, y, z, s) in spots))
    print(f"horizon: 모델 가운데에서 맨 아래까지 {-min(low):.2f} 블록(Java Horizon.FOOT = {FOOT})")
    k.icon(model="horizon", state="")
