"""G11 시체 뜯는 떼 게이브: 납작하게 눌린 0.35 얼굴에 막대기 꼬리 하나, 바닥을 덮는 군집. 마리마다 디스플레이 하나(G02 닻 방식).

상태: "a", "b"(실눈 기울기 다른 깨끗한 얼굴), "fa", "fb"(시체를 먹은 뒤: 입가에 분홍 즙), "lod"(15블록 밖 무리 덩어리, 바닥에 깔린 얼굴들).
고개를 처박는 것(뒤통수만 보임)과 일제히 드는 것은 Java 가 마리 방향(pitch)으로 한다. 떼라 변함 규칙에서 빠지므로 입 찢어짐, 변이 2 끔.
"""
import numpy as np

from gabe import audio, faces, kit

TAIL = kit.Wave(amp=(30, 38), lag=70)
JUICE = (255, 92, 140)
JUICE_D = (214, 52, 104)


def juice32():
    """먹은 얼굴 입가의 분홍 즙(32, 얼굴 전체 크기 덧그림, 나머지 투명)"""
    out = np.zeros((32, 32, 4), np.uint8)
    rng = np.random.RandomState(11)
    mx, my = faces.MOUTH_XY
    pts = []
    for x in range(mx - 5, mx + 7):
        for y in range(my - 1, my + 4):
            d = abs(x - (mx + 0.5)) / 6.0 + abs(y - (my + 1)) / 3.0
            if d < 1.0 and rng.rand() < 0.75 - 0.4 * d:
                pts.append((x, y))
    # 턱으로 흐르는 줄 셋
    for x0 in (mx - 3, mx + 1, mx + 4):
        for y in range(my + 2, min(31, my + 4 + rng.randint(2, 5))):
            pts.append((x0, y))
    for (x, y) in pts:
        if 0 <= x < 32 and 0 <= y < 32:
            out[y, x, :3] = JUICE if rng.rand() < 0.6 else JUICE_D
            out[y, x, 3] = 255
    # 입 자리는 비워 둠(점 입이 그대로 보임)
    for dy, row in enumerate(faces.MOUTH_DOT):
        for dx, ch in enumerate(row):
            if ch != ".":
                out[my + dy, mx + dx, 3] = 0
    return out


def juice128():
    """가까운 얼굴(128)용: 같은 자리를 4배로, 가장자리에 물방울 몇 개 더"""
    a = juice32()
    big = np.repeat(np.repeat(a, 4, axis=0), 4, axis=1).copy()
    rng = np.random.RandomState(12)
    ys, xs = np.nonzero(big[..., 3])
    for _ in range(40):
        i = rng.randint(len(xs))
        x, y = xs[i] + rng.randint(-3, 4), ys[i] + rng.randint(0, 6)
        if 0 <= x < 128 and 0 <= y < 128:
            big[y, x, :3] = (255, 150, 185)
            big[y, x, 3] = 255
    return big


def member(s, face, fed, j32, j128):
    h = s.head(size=0.35, height=0.24, depth=0.22, face=face)
    if fed:
        ep = 1.0 / 16
        z = 0.11 + 0.32 * ep
        s.quad((0, 0, z), (0.35, 0.24), "south", j32, dist="far")
        s.quad((0, 0, z + 0.04 * ep), (0.35, 0.24), "south", j128, dist="near")
    # 꼬리: 뒤통수 가운데에서 뒤로, 좌우로 흔듦
    s.rod(h.point(0, -0.2, -1), (0, -0.05, -1), length=0.34, segs=2, thick=0.05, wave=TAIL, toward=(1, 0, 0))


def build(k: kit.Kit):
    m = k.model(poses=6, period=12, mut2=False, torn=False)
    j32 = k.texture("juice", juice32())
    j128 = k.texture("juice_n", juice128())
    for name, face, fed in (("a", "smile_a", False), ("b", "smile_c", False), ("fa", "smile_a", True), ("fb", "smile_c", True)):
        member(m.state(name), face, fed, j32, j128)
    m.default = "a"
    lod = m.state("lod")
    lod.lump(count=16, radius=(1.5, 0.12, 1.5), size=0.24, faces_list=["smile_a", "smile", "smile_c"], wobble=0.04, seed=11)
    k.icon(model="g11", state="fa")


def munch(seed=0):
    """쩝쩝 쪽: 젖은 쩝 둘 + 입술 떼는 높은 쪽(이 종 전용)"""
    rng = np.random.RandomState(seed)
    parts = []
    for i in range(2):
        w = audio.env(audio.bandpass(audio.noise(0.09, seed * 7 + i), 500, 2600), 0.002, 0.03, 0.4, 0.04)
        b = audio.env(audio.tone(180 + rng.rand() * 40, 0.09, "tri", glide=110), 0.002, 0.03, 0.3, 0.04) * 0.6
        parts.append(audio.cat(audio.mix(w, b), audio.silence(0.06 + rng.rand() * 0.03)))
    kiss = audio.env(audio.mix(audio.tone(1500, 0.05, "sine", glide=2600), audio.gain(audio.bandpass(audio.noise(0.05, seed + 5), 2500, 8000), 0.5)),
                     0.001, 0.01, 0.3, 0.03)
    return audio.normalize(audio.cat(*parts, audio.silence(0.05), kiss), 0.8)


def skitter(seed=0):
    """다다닥: 바닥을 긁으며 튀어 오는 빠른 발소리"""
    return audio.normalize(audio.lowpass(audio.chitter(seed=seed, dur=0.45, rate=26, base=900), 3500), 0.6)


def sounds(k: kit.Kit):
    k.sound("munch", "쩝쩝 쪽", synth=lambda i: munch(110 + i), attenuation=20)
    k.sound("skitter", "다다닥", synth=lambda i: skitter(120 + i), volume=0.6, attenuation=16)
    k.sound("bite", "발목 물림", vanilla=[("mob/silverfish/hit1", 1.4), ("mob/silverfish/hit2", 1.5)], volume=0.7, attenuation=12)


def phrases():
    return {"cause_g11": "떼에 발목을 뜯겼다"}
