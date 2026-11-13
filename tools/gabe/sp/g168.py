"""G168 꿰매는 게이브: 0.4 블록 얼굴이 입에 길이 1.2 블록의 곧은 엔드 막대기 바늘을 물고 사람 머리 높이에 떠 있다. 바늘귀(뒤쪽)에 분홍 살 실이
꿰여 뒤로 끌리고, 얼굴 둘레에 짧은 막대기 바늘 몇 개가 바늘꽂이처럼 꽂혀 있다. 입가는 제 손으로 꿰맨 바늘땀(stitch 얼굴).

모델 gave:gabe/g168(몸 중심 = 얼굴 가운데):
- ""(날아옴): 바늘이 앞(+Z)으로, 실이 뒤로 끌림(4자세, 12틱).
- "hang"(실 한가운데 매달림): 바늘이 아래로 처지고 끌리던 실은 없음(사람 사이 실은 따로).
모델 gave:gabe/g168_thread: Java 가 부품으로 두 사람 사이에 늘이는 살 실(+Z 로 길이 1 블록). 상태 ""(늘어짐), "taut"(팽팽하게 떪, 2자세 4틱).
소리 stitch(바늘 긋는 소리, 20), pull(실이 당겨지는 소리, 24), snap(실이 끊김, 16). 글귀 cause_g168, cause_g168_pull, cause_g168_share.
"""
from gabe import faces, kit

STITCH = faces.Face(eyes="squint", mouth="dot", extras=("stitch",))
PINS = [((0.6, 0.7, -0.2), 0.26), ((-0.7, 0.6, -0.3), 0.24), ((0.9, -0.1, -0.4), 0.22), ((-0.9, -0.2, -0.3), 0.22),
        ((0.1, 1.0, -0.4), 0.28), ((0.3, -0.8, -0.5), 0.2)]


def needle_head(s, needle_dir, trail):
    h = s.head(size=0.4, depth=0.3, face=STITCH)
    # 바늘: 입에서 곧게(구조 막대기)
    s.rod(h.point(0, -0.25, 0.9), needle_dir, length=1.2, segs=1, thick=0.06, base=False, rag=False)
    # 바늘꽂이: 얼굴 둘레에 꽂힌 짧은 바늘
    for (u, v, w), ln in PINS:
        s.rod(h.point(u * 0.9, v * 0.9, w), (u, v, w - 0.2), length=ln, segs=1, thick=0.035, base=False, rag=False)
    if trail:
        # 바늘귀에서 뒤로 끌리는 살 실(4자세)
        s.band(h.point(0, -0.3, -0.95), (0, -0.25, -1), length=1.0, width=0.07, segs=3, thick=0.02, tex="flesh",
               wave=kit.Wave(amp=(10, 16, 20), lag=55), toward=(0, 1, 0), poses=4)


def build(k: kit.Kit):
    m = k.model(poses=4, period=12, mut2=False)
    needle_head(m.state(""), (0, -0.04, 1), trail=True)
    needle_head(m.state("hang", poses=1), (0, -1, 0.25), trail=False)

    t = k.model("g168_thread", poses=2, period=4, mut2=False, open=False, torn=False, rag=False)
    s = t.state("")
    s.box((-0.035, -0.035, 0), (0.035, 0.035, 1), "flesh", uv=[0, 0, 2, 16], face=True)
    s.front = (0, 0, 0)
    s = t.state("taut")
    for f in s.frames():
        with f:
            dx = 0.012 if f.k == 0 else -0.012
            s.box((-0.03 + dx, -0.03, 0), (0.03 + dx, 0.03, 1), "flesh", uv=[0, 0, 2, 16], face=True)
    s.front = (0, 0, 0)
    k.icon(model="g168", state="")


def sounds(k: kit.Kit):
    # 이 종 전용 신호: 바늘이 드나드는 '칙, 칙'
    k.sample("stitch", "tag:blade", "바늘 긋는 소리", pitch=1.3, attenuation=20)
    k.sample("pull", "tag:cloth", "실이 당겨짐", pitch=0.6, attenuation=24)
    k.sample("snap", "kenney_rpg/knifeslice*", "실이 끊김", pitch=1.6, attenuation=16, n=2)


def phrases():
    return {"cause_g168": "바늘에 꿰였다", "cause_g168_pull": "실에 끌려가 엉켰다", "cause_g168_share": "꿰인 동료의 상처를 나눠 받았다"}
