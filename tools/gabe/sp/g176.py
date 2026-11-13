"""G176 쟁기 게이브: 낮게 엎드린 1 블록 얼굴이 멍에를 멘 소처럼 앞에서 끌고, 뒤에 엔드 막대기 날 셋이 쟁기처럼 비스듬히 땅에 꽂혀
끌려간다(길이 2.4 블록). 지나간 자리에 폭 1 블록의 그을린 고랑이 남고, 고랑 바닥에 손톱만 한 얼굴 이빨이 줄지어 돋는다.

모델 gave:gabe/g176(몸 중심 = 땅 위 0.55, 앞 +Z 가 끄는 얼굴):
- ""(끌기: 짧은 앞발 둘과 날 뒤 흙 튐 2자세, 8틱).
모델 gave:gabe/g176_furrow(고랑 토막, shrink 0.5, 가운데 = 땅 위 0.03, +Z 로 길이 5 블록 폭 1): 상태 "".
소리 plow(땅 갈리는 소리, 40), bite(고랑 이빨이 무는 소리, 16), clang(날이 탄을 튕김, 16). 글귀 cause_g176, cause_g176_furrow.
"""
import numpy as np

from gabe import kit


def soil_tex(seed=176):
    n = 16
    rng = np.random.RandomState(seed)
    col = np.full((n, n, 3), (92, 64, 44), np.float32) + rng.randint(-14, 15, (n, n, 1))
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = np.clip(col, 0, 255).astype(np.uint8)
    out[..., 3] = 255
    return out


def furrow_tex(seed=1760):
    """그을린 고랑 바닥 32x32: 검은 숯빛 가운데, 가장자리 갈색 흙, 불씨 점"""
    n = 32
    rng = np.random.RandomState(seed)
    xs = np.arange(n)[None, :].repeat(n, 0)
    edge = np.minimum(xs, n - 1 - xs)
    col = np.zeros((n, n, 3), np.float32)
    col[:] = (30, 24, 22)
    col[edge < 6] = (66, 46, 34)
    col[edge < 3] = (96, 70, 50)
    col += rng.randint(-8, 9, (n, n, 1))
    ember = (rng.rand(n, n) < 0.035) & (edge >= 5)
    col[ember] = (210, 78, 30)
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = np.clip(col, 0, 255).astype(np.uint8)
    out[..., 3] = 255
    return out


def build(k: kit.Kit):
    m = k.model(poses=2, period=8, mut2=False)
    s = m.state("")
    h = s.head(size=1.0, depth=0.62, at=(0, 0, 0.62), face="smile", rot=(-8, 0, 0))
    # 멍에: 뒤통수 위 가로대 + 양옆으로 내려온 굽은 막대
    s.rod((-0.72, 0.42, 0.28), (1, 0, 0), length=1.44, segs=1, thick=0.1, base=False, rag=False)
    for side in (1, -1):
        s.rod((side * 0.66, 0.42, 0.28), (side * 0.1, -1, 0), length=0.5, segs=1, thick=0.08, base=False, rag=False)
        # 봇줄: 멍에 끝에서 뒤의 쟁기까지
        s.rod((side * 0.62, 0.0, 0.26), (side * -0.25, -0.15, -1), length=1.0, segs=1, thick=0.05, base=False, rag=False)
    # 짧은 앞발 둘(땅을 짚고 끌기, 2자세)
    for side, ph in ((1, 0), (-1, 180)):
        s.rod(h.point(side * 0.6, -0.9, 0.3), (0, -1, 0.5), length=0.36, segs=1, thick=0.09,
              wave=kit.Wave(amp=22, lag=0, phase=ph), toward=(0, 0, 1), base=False)
    # 쟁기: 가로대와 날 셋(비스듬히 땅에 꽂힘)
    s.rod((-0.5, 0.1, -0.68), (1, 0, 0), length=1.0, segs=1, thick=0.08, base=False, rag=False)
    for x in (-0.4, 0.0, 0.4):
        s.rod((x, 0.1, -0.68), (0, -0.85, -0.6), length=0.85, segs=1, thick=0.1, base=False, rag=False)
    # 날 뒤로 튀는 흙(2자세)
    soil = k.texture("soil", soil_tex())
    for f in s.frames():
        with f:
            for i, x in enumerate((-0.4, 0.0, 0.4)):
                up = 0.12 + 0.12 * ((i + f.k) % 2)
                s.box((x - 0.07, -0.5 + up, -1.22 - 0.05 * f.k), (x + 0.07, -0.38 + up, -1.1 - 0.05 * f.k), soil)

    fm = k.model("g176_furrow", poses=1, shrink=0.5, mut2=False, open=False, torn=False, rag=False)
    s = fm.state("")
    tex = k.texture("furrow", furrow_tex())
    for i in range(5):
        s.quad((0, 0.0, -2.0 + i), (1, 1), "up", tex)
    # 고랑 이빨: 손톱만 한 얼굴이 고랑 양쪽 가장자리를 따라 줄지어 하늘을 봄
    for i in range(10):
        z = -2.25 + i * 0.5
        x = 0.32 if i % 2 == 0 else -0.32
        s.head(size=0.14, depth=0.06, at=(x, 0.02, z), face="smile", near=False, open=False, torn=False, rot=(-90, 0, 0))
    s.front = (0, 0, 0)


def sounds(k: kit.Kit):
    # 이 종 전용 신호: 땅이 갈리는 무거운 소리
    k.sample("plow", "tag:stone", "땅 갈리는 소리", pitch=0.6, attenuation=40)
    k.sample("bite", "tag:flesh", "고랑 이빨이 묾", pitch=1.4, attenuation=16)
    k.sample("clang", "tag:metal+impact", "날이 탄을 튕김", pitch=1.2, attenuation=16)


def phrases():
    return {"cause_g176": "쟁기 날에 치였다", "cause_g176_furrow": "고랑 이빨에 물렸다"}
