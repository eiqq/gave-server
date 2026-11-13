"""G195 어둠 장막 게이브(4막, 방패, 지원: 폭 14, 높이 6 블록 검은 장막을 연 얼굴 여덟이 물고 나름).

새까만 장막이 땅까지 드리운다. 가까이서 보면 눈을 감은 _Gave 얼굴 무늬가 검정 위에 검정으로 찍혀 있고, 지나가는 순간(실눈 뜸)에만 무늬의 눈이
한꺼번에 떠 검붉게 비친다. 윗단을 작은 연 얼굴 여덟이 입으로 물고 날며(연마다 엔드 막대기 둘을 열십자로 엮은 뼈대에 0.6 블록 얼굴), 아랫단은
땅에 끌리며 펄럭인다. 연이 떨어진 자리는 윗단이 처져 틈이 생긴다.

- gave:gabe/g195(shrink 0.19): 몸 가운데(0, 0, 0) = 엔진 pos = 장막 가운데, 바닥 y -3(Java HOVER 3.0). 앞 = +Z(나아가는 쪽).
  기둥 여덟(가로 x = -7 + 1.75 x (i + 0.5)). 상태 ""(드리움, 아랫단 2 자세), "fallen"(앞으로 무너져 땅에 깔림, 바닥에서 z 0..6).
  부위 s1..s8(그 기둥 윗단이 처짐: 연이 떨어짐).
- gave:gabe/g195_kites(shrink 0.2): 연 여덟(장막 윗단 위 y 3.45, 같은 x). 부위 k1..k8(그 연이 떨어져 없음). 날갯짓 2 자세.
소리 dread(낮게 깔리는 울림, 64), pass(장막이 덮고 지나감, 24), kite(연이 떨어짐, 32), collapse(장막이 무너짐, 48).
"""
import numpy as np

from gabe import faces, kit

S = 0.19
COLS = 8
W = 1.75
HALF_W = 7.0
TOP, BOT = 3.0, -3.0
SPLIT = 1.0     # 윗단과 아랫단 경계(처지면 윗단이 사라지고 이 아래만 남음)
KITE_Y = 3.45


def dark_tile(open_eyes, seed):
    """64 x 64: 2 x 2 얼굴 무늬를 검정 위 검정으로(감은 눈) 또는 검붉게 뜬 눈으로"""
    rng = np.random.RandomState(seed)
    out = np.zeros((64, 64, 4), np.uint8)
    names = ("smile", "smile_a", "smile_c", "half")
    for r in range(2):
        for c in range(2):
            f = faces.preset(names[rng.randint(len(names))])
            if open_eyes:
                f = f.with_(eyes="bloodshot" if rng.rand() < 0.4 else "open", blush="none")
            else:
                f = f.with_(blush="none")
            img = faces.far(f).astype(float)
            lum = (0.3 * img[..., 0] + 0.59 * img[..., 1] + 0.11 * img[..., 2]) / 255.0
            # 살(밝음)은 아주 짙은 검정, 눈 줄과 입(어두움)은 그보다 더 검게, 뜬 눈은 검붉게 비침
            base = np.full(lum.shape + (3,), 0.0)
            for k, v in enumerate((16, 14, 18)):
                base[..., k] = v + (lum - 0.85) * 26
            if open_eyes:
                red = (img[..., 0] > 150) & (img[..., 1] < 120)
                white = (img[..., 0] > 200) & (img[..., 1] > 200) & (lum < 0.98) & (lum > 0.8)
                base[red] = (88, 14, 16)
                base[white & ~red] = (40, 30, 30)
            out[r * 32:(r + 1) * 32, c * 32:(c + 1) * 32, :3] = np.clip(base, 2, 255).astype(np.uint8)
    out[..., 3] = 255
    return out


def sheet(s, k, closed, opened, hem):
    for i in range(COLS):
        x = -HALF_W + W * (i + 0.5)
        lo = (x - W / 2, BOT + 0.7, -0.04)
        hi = (x + W / 2, SPLIT, 0.04)
        for cond, tex in (("!open", closed[i % len(closed)]), ("open", opened[i % len(opened)])):
            with s.when(cond):
                s.box(lo, hi, tex, uv=[0, 0, 16, 16], faces=("south", "north"), face=True)
                with s.when(f"!on:s{i + 1}"):
                    s.box((x - W / 2, SPLIT, -0.04), (x + W / 2, TOP, 0.04), tex, uv=[0, 0, 16, 16], faces=("south", "north"), face=True)
        # 처진 윗단: 경계 위로 조금 늘어진 자락
        with s.when(f"on:s{i + 1}"):
            s.box((x - W / 2 + 0.1, SPLIT - 0.2, -0.04), (x + W / 2 - 0.1, SPLIT + 0.55, 0.04), closed[(i + 1) % len(closed)], uv=[0, 0, 16, 5],
                  faces=("south", "north"), rot=(28, 0, 6 if i % 2 else -6), origin=(x, SPLIT, 0), face=True)
    # 아랫단: 땅에 끌리며 펄럭임(2 자세)
    for f in s.frames(poses=2):
        with f:
            for i in range(COLS):
                x = -HALF_W + W * (i + 0.5)
                a = (14 if (i + f.k) % 2 else -10)
                s.box((x - W / 2, BOT, -0.04), (x + W / 2, BOT + 0.75, 0.04), hem, uv=[0, 0, 16, 6], faces=("south", "north"), rot=(a, 0, 0),
                      origin=(x, BOT + 0.75, 0), face=True)


def fallen(s, k, closed):
    for i in range(COLS):
        x = -HALF_W + W * (i + 0.5)
        s.box((x - W / 2, BOT, 0.0), (x + W / 2, BOT + 0.08, 6.0), closed[i % len(closed)], uv=[0, 0, 16, 16], faces=("up", "down"), face=True)
        s.box((x - W / 2, BOT, 5.8), (x + W / 2, BOT + 0.35, 6.2), closed[(i + 2) % len(closed)], uv=[0, 0, 16, 4], faces=("up", "south", "north"),
              rot=(0, 0, 4 if i % 2 else -4), origin=(x, BOT, 6.0), face=True)


def kites(k):
    m = k.model("g195_kites", poses=2, period=12, shrink=0.2, mut2=False, torn=False, rag=False)
    s = m.state("")
    for f in s.frames():
        with f:
            for i in range(COLS):
                x = -HALF_W + W * (i + 0.5)
                bob = 0.18 if (i + f.k) % 2 else -0.12
                tilt = 10 if (i + f.k) % 2 else -10
                y = KITE_Y + bob
                with s.when(f"!on:k{i + 1}"):
                    for d in ((1, 1), (-1, 1)):
                        s.rod((x - d[0] * 0.55, y - 0.55, 0.05), (d[0], d[1], 0), length=1.55, segs=1, thick=0.09, base=False, toward=(0, 0, 1),
                              rag=False)
                    s.head(size=0.62, depth=0.45, at=(x, y, 0.12), face="smile", near=False, rot=(0, 0, tilt))
    s.front = (0, 0, 1)


def build(k: kit.Kit):
    closed = [k.texture(f"veil{i}", dark_tile(False, 1950 + i)) for i in range(3)]
    opened = [k.texture(f"veil{i}_open", dark_tile(True, 1950 + i)) for i in range(3)]
    hem = k.texture("veil_hem", dark_tile(False, 1959))
    m = k.model(poses=2, period=16, shrink=S, mut2=False, open=False, torn=False, rag=False)
    sheet(m.state(""), k, closed, opened, hem)
    m.state("").front = (0, 0, 0)
    fallen(m.state("fallen", poses=1), k, closed)
    m.state("fallen").front = (0, 0, 0)
    kites(k)
    k.icon(model="g195_kites", state="")


def sounds(k: kit.Kit):
    k.sample("dread", "tag:low+drone", "낮게 깔리는 울림", pitch=0.55, attenuation=64)
    k.sample("pass", ["kenney_rpg/cloth1", "kenney_rpg/cloth3", "kenney_rpg/dropleather"], "장막이 덮고 지나감", pitch=0.55, attenuation=24)
    k.sample("kite", "tag:flap", "연이 떨어짐", pitch=0.8, attenuation=32)
    k.sample("collapse", ["kenney_rpg/cloth1", "kenney_impact/impactpunch_heavy_000"], "장막이 무너짐", pitch=0.45, attenuation=48)


def phrases():
    return {"cause_g195": "어둠 장막에 휩쓸렸다", "cause_g195_fall": "무너지는 장막에 깔렸다"}
