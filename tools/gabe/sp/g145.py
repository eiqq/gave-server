"""G145 못 박힌 게이브: 어깨 높이 2.2, 길이 3.2 블록 황소 꼴. 1.3 블록 얼굴을 낮게 숙였고 이마에서 굵은 엔드 막대기 뿔 둘이 앞으로 뻗었다.
살 몸통은 뒤로 갈수록 가늘고 짧은 막대기 다리 넷으로 땅을 찬다. 목덜미 혹 한가운데에 둥근 구멍이 뚫려 속이 캄캄하고, 뽑힌 못(엔드 막대기
하나)이 옆구리 끈에 매달려 덜렁거린다.

모델 gave:gabe/g145(shrink 0.6: 뿔 끝까지 2 블록 넘음) 상태:
  ""       달리기(다리 넷 4자세)
  "scrape" 앞발로 땅 긁기(오른 앞발만 2자세)
  "tame"   못이 박혀 순함(천천히 걷는 다리 2자세, 옆구리의 뽑힌 못이 없음: 목덜미에 박혔으니). 실눈, 옅은 홍조는 Java 팔레트(PAL_PALE)
모델 gave:gabe/g145_nail: 목덜미에 박힌 못(아래 끝이 가운데, +Y 로 솟음). Java 부품이 밀려 나온 길이를 보간으로.
가운데(몸 자리) = 몸통 가운데(Java hover 1.3: 다리 끝이 땅).
소리 nail(못 박히는 소리, 32), bellow(울부짖음, 32), scrape(땅 긁기, 16), ram(들이받음, 24).
"""
import numpy as np

from gabe import kit


def _rgba(arr):
    return np.clip(np.round(arr), 0, 255).astype(np.uint8)


def hole_tex(n=16):
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    c = (n - 1) / 2
    r = np.hypot(xx - c, yy - c) / c
    out = np.zeros((n, n, 4), np.float32)
    out[..., :3] = np.array([226, 160, 156], np.float32)
    out[r < 0.95, :3] = (150, 60, 66)
    out[r < 0.72, :3] = (12, 4, 6)
    out[..., 3] = 255
    return _rgba(out)


def body(s, k, nail_on_side=True):
    fl = k.part_tex("flesh")
    s.box((-0.78, -0.55, -0.1), (0.78, 0.66, 0.95), fl, face=True)
    s.box((-0.64, -0.46, -0.92), (0.64, 0.52, -0.1), fl, face=True)
    s.box((-0.48, -0.36, -1.6), (0.48, 0.36, -0.92), fl, face=True)
    # 목덜미 혹과 둥근 구멍
    s.box((-0.5, 0.64, 0.02), (0.5, 0.96, 0.78), fl, face=True)
    s.quad((0, 0.962, 0.34), (0.36, 0.36), "up", k.texture("hole", hole_tex()))
    # 꼬리 그루터기
    s.rod((0, 0.15, -1.58), (0, -0.6, -0.5), length=0.5, segs=1, thick=0.07, base=False, rag=False)
    if nail_on_side:
        # 옆구리 끈에 매달린 뽑힌 못(덜렁)
        s.chain((0.79, 0.35, 0.25), (0.86, -0.15, 0.3), links=3, sag=0.12, size=0.06)
        s.rod((0.86, -0.15, 0.3), (0.05, -1, 0.1), length=0.48, segs=1, thick=0.07, base=False, rag=False)


def head(s, tilt=22):
    h = s.head(size=1.3, depth=0.8, at=(0, -0.08, 1.2), face="deep", rot=(tilt, 0, 0))
    for side in (1, -1):
        s.rod(h.point(side * 0.8, 0.82, 0.3), (side * 0.45, 0.18, 1), length=1.0, segs=2, thick=0.16, base=False, rag=False)
    return h


LEG_ROOTS = ((0.46, 0.62), (-0.46, 0.62), (0.4, -1.25), (-0.4, -1.25))


def legs(s, mode):
    for i, (x, z) in enumerate(LEG_ROOTS):
        if mode == "run":
            w = kit.Wave(amp=34, phase=0 if i in (0, 3) else 180)
            poses = None
        elif mode == "walk":
            w = kit.Wave(amp=16, phase=0 if i in (0, 3) else 180)
            poses = 2
        elif mode == "scrape" and i == 0:
            w = kit.Wave(amp=40, bias=-20, shape="flap")
            poses = 2
        else:
            w, poses = None, None
        s.rod((x, -0.48, z), (0, -1, 0), length=0.86, segs=1, thick=0.15, base=False, wave=w, toward=(0, 0, 1), poses=poses, rag=w is not None)


def build(k: kit.Kit):
    m = k.model(poses=4, period=8, shrink=0.6, mut2=False)
    s = m.state("")
    body(s, k)
    head(s)
    legs(s, "run")

    sc = m.state("scrape", poses=2, period=12)
    body(sc, k)
    head(sc, 30)
    legs(sc, "scrape")

    t = m.state("tame", poses=2, period=24)
    body(t, k, nail_on_side=False)
    head(t, 12)
    legs(t, "walk")

    n = k.model("g145_nail", poses=1, mut2=False, open=False, torn=False, rag=False)
    ns = n.state("")
    ns.rod((0, -0.2, 0), (0, 1, 0), length=1.0, segs=1, thick=0.1, base=False, rag=False, toward=(0, 0, 1))
    ns.box((-0.11, 0.8, -0.11), (0.11, 0.88, 0.11), "rod", uv=[0, 0, 4, 4])
    ns.front = (0, 0, 0)


def sounds(k: kit.Kit):
    k.sample("nail", "tag:bell", "못 박히는 소리", pitch=1.5, attenuation=32)
    k.sample("bellow", "tag:growl", "울부짖음", pitch=0.6, attenuation=32)
    k.sample("scrape", "kenney_interface/scratch_00*", "땅 긁는 소리", pitch=0.5, attenuation=16)
    k.sample("ram", "kenney_impact/impactpunch_heavy_00*", "들이받음", pitch=0.6, attenuation=24)


def phrases():
    return {"cause_g145": "황소 얼굴에 들이받혔다"}
