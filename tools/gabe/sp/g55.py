"""G55 가짜 종소리 게이브(황야판): 종처럼 거꾸로 선 얼굴이 땅에 반쯤 묻혀 뒤로 젖혀져 있다. 정수리(뒤집혀 위로 온 턱 쪽)에 쇠사슬 고리가
솟았다가 땅으로 늘어지고, 양옆 땅에서 엔드 막대기 둘이 종 추처럼 솟아 얼굴 위에서 서로 맞부딪힌다. 둘레에 들뜬 흙덩이.

상태: ""(거의 안 움직임: 추가 아주 느리게 까딱), "ring"(가짜 종: 추가 빠르게 맞부딪힘). 모델 가운데가 땅보다 0.12 위(Java hover).
"""
import numpy as np

from gabe import audio, kit

GROUND = -0.12


def dirt_tex(n=16, seed=5):
    """들뜬 마른 흙덩이: 갈색 알갱이와 갈라진 금"""
    rng = np.random.RandomState(seed)
    out = np.zeros((n, n, 4), np.float32)
    base = np.array([122, 96, 66], np.float32)
    for y in range(n):
        for x in range(n):
            v = 0.85 + 0.3 * rng.rand()
            out[y, x, :3] = base * v
            out[y, x, 3] = 255
    for i in range(n):
        j = (i * 5 + 3) % n
        out[i, j, :3] = (70, 52, 36)
        out[(i * 3 + 1) % n, i, :3] = (84, 64, 44)
    return np.clip(np.round(out), 0, 255).astype(np.uint8)


def bell(s, k, clap, dirt):
    # 뒤집힌 얼굴(종): 28도 뒤로 젖혀 반쯤 묻힘
    h = s.head(size=0.72, depth=0.6, at=(0, 0.02, 0), face="flipped", rot=(-28, 0, 0))
    # 쇠사슬: 정수리 뒤에서 솟았다가 뒤쪽 땅으로 늘어짐
    s.chain((0.0, 0.34, -0.2), (0.02, 0.78, -0.42), links=3, sag=0.0, size=0.1)
    s.chain((0.02, 0.78, -0.42), (0.42, GROUND + 0.03, -0.78), links=4, sag=0.12, size=0.1)
    # 종 추: 양옆 땅에서 솟은 막대기 둘이 얼굴 위로 기울어 안쪽으로 까딱(같은 틱에 서로 맞부딪힘)
    for side in (1, -1):
        s.rod((side * 0.56, GROUND - 0.04, 0.42), (-side * 0.5, 1.0, 0.0), length=0.84, segs=2, thick=0.085, wave=clap, toward=(-side, 0.4, 0))
    # 들뜬 흙덩이 셋
    for (x, z, w, hh, ry) in ((0.46, 0.2, 0.3, 0.1, 20), (-0.44, -0.12, 0.34, 0.08, -35), (0.1, -0.5, 0.26, 0.07, 50)):
        s.box((x - w / 2, GROUND - 0.02, z - w / 2), (x + w / 2, GROUND + hh, z + w / 2), dirt, uv=[0, 0, 8, 8], rot=(0, ry, 0))
    return h


def build(k: kit.Kit):
    dirt = k.texture("dirt", dirt_tex())
    m = k.model(poses=8, period=40, mut2=False, torn=False)
    bell(m.state(""), k, kit.Wave(amp=(3, 2), lag=30, bias=(-4, -2)), dirt)
    # 가짜 종: 40틱에 네 번 맞부딪힘
    bell(m.state("ring"), k, kit.Wave(amp=(17, 9), lag=25, bias=(4, 2), shape="flap", cycles=4), dirt)


def toll(seed=0, dur=2.2):
    """종소리(가짜 웨이브 끝): 맑은 종 배음 셋이 길게 울림. 진짜와 같은 소리지만 Java 가 몹 자리에서 울려 자막 화살표가 뜬다"""
    f = 523.0 * (1 + 0.004 * seed)
    parts = []
    for mul, g, dec in ((1.0, 1.0, 1.6), (2.76, 0.5, 1.0), (5.4, 0.25, 0.6), (8.9, 0.12, 0.35)):
        x = audio.tone(f * mul, dur, "sine", vib=0.002, vib_rate=5)
        t = np.linspace(0, dur, len(x), dtype=np.float32)
        parts.append(x * g * np.exp(-t * (3.0 / dec)))
    strike = audio.env(audio.highpass(audio.noise(0.02, 550 + seed), 3000), 0.0005, 0.004, 0.2, 0.012) * 0.6
    out = audio.mix(*parts, strike)
    return audio.normalize(audio.fade(out, 0.001, 0.2), 0.85)


def titter(seed=0):
    """일제히 터지는 낮은 킥킥(덮칠 때)"""
    return audio.giggle(seed=560 + seed, count=7, base=430 + 20 * seed, rate=10.5)


def sounds(k: kit.Kit):
    # 가짜 종소리: 이 종의 신호(한쪽에서 들림). 멀리까지
    k.sound("toll", "종소리", synth=toll, volume=1.0, attenuation=96)
    # 가짜 상점 여는 소리(진짜는 통 여는 소리)
    k.sound("door", "상점 여는 소리", vanilla=[("block/barrel/open1", 0.9), ("block/barrel/open2", 0.9)], volume=1.0, attenuation=96)
    # 덮칠 때 사방에서 터지는 웃음(물러났던 게이브 자리마다)
    k.sound("titter", "사방에서 킥킥", synth=titter, volume=0.9, attenuation=48)
    # 추가 맞부딪힘(가까이서만)
    k.sound("clack", "딱 맞부딪힘", vanilla=[("random/wood_click", 0.7)], volume=0.5, attenuation=12)


def phrases():
    return {"cause_g55": "가짜 종소리에 속았다", "notice_g55_found": "숨은 게이브가 드러났다"}
