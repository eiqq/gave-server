"""G09 멈칫 게이브: 줄 끊긴 꼭두각시. 1.2배 얼굴(0.96 블록)이 인형 머리이고, 아래로 가는 목, 헝겊 살 몸통, 살 막대기 팔다리가 매달리고,
위로 엔드 막대기 둘(원형 DNA)이 인형 줄처럼 솟는다(키 3.2 블록).

모델 gave:gabe/g09: 상태 ""(다가옴: 줄이 위로 까딱, 팔은 늘어져 흔들, 다리는 허공을 걸음), "halt"(공중 정지: 줄 둘이 위로 팽팽하게 곧게, 끝만
파르르, 팔은 T 자로 옆, 다리는 짧게 접음: Java 가 1.4 배로 키워도 발끝이 땅 위), "dazed"(벽이나 동료에 막혀 나타나지 못함: 줄이 옆 아래로
처지고 팔다리가 옆으로 늘어져 주저앉음, hover 1.2 에서 땅에 안 박히게). 커짐(1.0 → 1.4)과 사라짐은 Java 가 디스플레이 크기로.
"""
import numpy as np

from gabe import audio, kit

HEAD, DEPTH = 0.96, 0.72
# 상태마다: 팔 방향, 팔 물결, 다리 방향, 다리 길이, 다리 물결, 줄 방향, 줄 물결
POSE = {
    "": dict(arm=(0.35, -1.0, 0.05), arm_w=kit.Wave(amp=(10, 14), lag=60),
             leg=(0.08, -1.0, 0.0), leg_len=0.66, leg_w=kit.Wave(amp=(14, 18), lag=60),
             string=(0.25, 1.0, -0.05), string_w=kit.Wave(amp=(10, 14), lag=50, shape="twitch", cycles=2)),
    "halt": dict(arm=(1.0, -0.02, 0.0), arm_w=kit.Wave(amp=(1, 2), lag=90, cycles=4),
                 leg=(0.02, -1.0, 0.0), leg_len=0.3, leg_w=kit.Wave(amp=(1, 1), lag=90, cycles=4),
                 string=(0.05, 1.0, -0.03), string_w=kit.Wave(amp=(1, 2), lag=90, cycles=4)),
    "dazed": dict(arm=(0.75, -0.55, 0.3), arm_w=kit.Wave(amp=(3, 4), lag=60, bias=(-6, -14)),
                  leg=(1.0, -0.04, 0.45), leg_len=0.6, leg_w=kit.Wave(amp=(2, 3), lag=60, bias=(-4, -10)),
                  string=(0.6, -0.3, -0.2), string_w=kit.Wave(amp=(6, 8), lag=60, bias=(-10, -20))),
}


def cloth_tex(n=16, seed=9):
    """헝겊 살 몸통: 얼굴 살빛 천의 성긴 결, 가운데 세로 적갈색 바늘땀, 가장자리 짙은 솔기"""
    rng = np.random.RandomState(seed)
    yy, xx = np.mgrid[0:n, 0:n].astype(float)
    col = np.array([236, 200, 192], float) * (0.9 + 0.06 * ((xx.astype(int) + yy.astype(int)) % 2) + 0.04 * np.sin(yy * 2.1))[..., None]
    col += rng.randn(n, n, 1) * 3
    col[:, 0] = col[:, n - 1] = (196, 140, 138)
    col[0, :] = col[n - 1, :] = (196, 140, 138)
    for y in range(1, n - 1):
        col[y, 7 + (y % 2)] = (96, 34, 32) if y % 3 else (120, 46, 42)
        if y % 3 == 1:
            col[y, 6] = col[y, 9] = (110, 40, 38)
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = np.clip(np.round(col), 0, 255)
    out[..., 3] = 255
    return out


def _w(w, phase):
    return kit.Wave(amp=w.amp, lag=w.lag, bias=w.bias, phase=phase, shape=w.shape, cycles=w.cycles)


def puppet(k, s, name):
    """꼭두각시 하나: 머리(얼굴) + 목 + 헝겊 몸통 + 팔 둘, 다리 둘(살 막대기) + 인형 줄 둘(원형 DNA)"""
    p = POSE[name]
    h = s.head(size=HEAD, depth=DEPTH, face="smile")
    cloth = k.texture("cloth", cloth_tex())
    s.box((-0.07, -0.66, -0.07), (0.07, -0.47, 0.07), "flesh", uv=[0, 0, 4, 4])
    s.box((-0.26, -1.12, -0.15), (0.26, -0.64, 0.15), cloth, uv=[0, 0, 16, 16], face=True)
    for side in (1, -1):
        ph = 0 if side > 0 else 180
        ax, ay, az = p["arm"]
        s.rod((side * 0.28, -0.7, 0), (side * ax, ay, az), length=0.62, segs=2, thick=0.1, tex="flesh", wave=_w(p["arm_w"], ph),
              toward=(side, 0, 0), poses=4)
        lx, ly, lz = p["leg"]
        s.rod((side * 0.13, -1.1, 0), (side * lx, ly, lz), length=p["leg_len"], segs=2, thick=0.11, tex="flesh", wave=_w(p["leg_w"], ph),
              toward=(0, 0, 1), poses=4)
        # 인형 줄(원형 DNA): 정수리에서 위로
        sx, sy, sz = p["string"]
        s.rod(h.point(side * 0.55, 0.95, -0.2), (side * sx, sy, sz), length=1.0, segs=2, thick=0.09, wave=_w(p["string_w"], ph),
              toward=(side, 0, 0), poses=4)
    return h


def build(k: kit.Kit):
    m = k.model(poses=8, period=20)
    m.mut2 = dict(root=(0.3, -0.2, -0.3), dir=(1.0, -0.4, -0.6), length=0.6, poses=4)
    for name in POSE:
        puppet(k, m.state(name), name)


def sounds(k: kit.Kit):
    # 흡: 멈추는 순간 숨을 멈춤(이 종 전용 신호)
    k.sound("hup", "흡", vanilla=[("mob/breeze/inhale2", 1.75), ("mob/fox/sniff2", 0.75)], synth=lambda i: hup(seed=900 + i),
            volume=1.0, attenuation=28)
    # 숨소리: 등 뒤에 나타남
    k.sound("breath", "등 뒤 숨소리", vanilla=[("mob/horse/breathe1", 1.45), ("mob/panda/nosebreath2", 0.9)],
            synth=lambda i: breath(seed=910 + i), volume=1.0, attenuation=12)
    # 박치기
    k.sound("ram", "쿵", vanilla=[("mob/slime/attack2", 0.6)], synth=lambda i: audio.thump(seed=920 + i, freq=95), attenuation=16)


def hup(seed=0, dur=0.22):
    n = audio.bandpass(audio.noise(dur, seed), 900, 4200)
    t = np.arange(len(n), dtype=np.float32) / audio.SR
    e = np.where(t < 0.15, (t / 0.15) ** 0.7, 0.0).astype(np.float32)
    return audio.normalize(audio.fade(n * e, 0.002, 0.004), 0.8)


def breath(seed=0, dur=0.9):
    n = audio.noise(dur, seed)
    t = np.arange(len(n), dtype=np.float32) / audio.SR
    out = audio.bandpass(n, 300, 1800)
    e = (np.sin(np.pi * np.clip(t / dur, 0, 1)) ** 1.5).astype(np.float32)
    return audio.normalize(audio.fade(out * e), 0.6)


def phrases():
    return {"cause_g09": "등 뒤에서 박혔다"}
