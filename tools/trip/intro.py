"""판 시작 인트로 소리(kr.gave.trip.Story 가 트는 gave:trip.intro_<대본>_full, _short). 받은 소리 없이 numpy 로만 합성한다.

make_trip.py 가 bake 를 불러 원정 층에 넣는다. 대본은 장면표의 하나(2026-11-14 사용자가 a 를 고름).

python -X utf8 tools/trip/intro.py [--out build/intro/sound]     긴 판, 짧은 판을 ogg 로(들어 보기)

장면표(jar 의 trip/intro.json, Story 가 읽는 그 파일)와 글귀(data/phrases/*.json)를 읽어 장면마다 소리를 얹는다. 표나 글을 고치면 다시 굽는다.
- 저음: 60 Hz 전깃줄 웅웅(조금 어긋난 둘이 맥놀이), 형광등 지직. 얼굴이 끼어드는 장면에서는 뚝 끊긴다
- 안내 방송 종: 헌사 줄에 네 음, 넷째 음은 늦고 낮다
- 목소리: 줄의 한글을 자모로 풀어 모음마다 포먼트를 옮기는 웅얼거림(말 같지만 말이 아님). 숫자는 읽는 소리로. 장면의 voices 만큼 여럿이
  같이 말한다(번질수록 늘어남, 높은 목소리가 섞임)
- 젖은 소리: 줄 사이 입맛 다시는 소리, 얼굴이 뜰 때 뽁, 얼굴이 늘 때 뽁뽁, 눈이 뜨일 때 쩝
- 킥킥: tools/gabe/audio.py 의 giggle(게이브 떼 소리와 같은 합성). 멀리서 드문드문, 밝아진 뒤 첫 웨이브까지 몰려온다
make_trip 약속: bake(layer) -> 요약, credits() -> 출처 줄.
"""
import functools
import json
import subprocess
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from gabe import audio as GA  # noqa: E402  킥킥 합성(numpy)

SR = 44100
TICK = 0.05
TAIL = 2.5  # 첫 웨이브가 늦게 열려도 끊기지 않게 남기는 꼬리(초). Story 가 웨이브가 열릴 때 소리를 끊는다
SPEC = [ROOT / "build/wip/intro/plugin/src/main/resources/trip/intro.json", ROOT / "plugin/src/main/resources/trip/intro.json"]
PHRASES = ROOT / "data/phrases"
QUALITY = 3  # libvorbis -q:a (make_bgm 과 같음)
TARGET_RMS_DB, PEAK_DB = -20.0, -1.5  # 소리 나는 곳 RMS(배경 음악과 같은 기준), 피크


# ---------------------------------------------------------------- 장면표

def load():
    """(장면표, 글귀 {키: 글}). 장면표는 고치는 중인 wip 것이 있으면 그것, 없으면 진짜 자리."""
    spec = next(json.loads(p.read_text(encoding="utf-8")) for p in SPEC if p.is_file())
    phrases = {}
    for f in sorted(PHRASES.glob("*.json")):
        d = json.loads(f.read_text(encoding="utf-8"))
        if isinstance(d, dict):
            phrases.update({k: v for k, v in d.items() if isinstance(v, str)})
    return spec, phrases


def schedule(spec, key, kind):
    """장면마다 {t 다 검어진 뒤 시작 틱, stay, gap, line, alt, cover[], say, voices}, 다 검은 동안의 길이(틱). Story.show 와 같은 셈."""
    out, t = [], 0
    for c in spec["scripts"][key][kind]:
        gap = c.get("gap", spec["gap"])
        out.append({"t": t, "stay": c["stay"], "gap": gap, "line": c.get("line"), "alt": c.get("alt"), "cover": c.get("cover", []),
                    "say": c.get("say", "voice"), "voices": c.get("voices", 1)})
        t += c["stay"] + gap
    return out, t


# ---------------------------------------------------------------- 연장

def secs(n):
    return np.arange(int(n * SR), dtype=np.float64) / SR


def band(x, lo, hi):
    """푸리에로 자른 대역(가장자리 부드럽게)."""
    X = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    g = 1 / (1 + (lo / np.maximum(f, 1e-6)) ** 6) / (1 + (f / hi) ** 6)
    return np.fft.irfft(X * g, len(x))


def pan(x, p):
    """mono → (n, 2), p -1 왼쪽 .. 1 오른쪽."""
    a = (p + 1) * np.pi / 4
    return np.stack([x * np.cos(a), x * np.sin(a)], 1)


def put(buf, x, t, gain=1.0, p=0.0):
    """buf(n, 2) 의 t 초 자리에 x(mono 또는 스테레오)를 더한다."""
    if x.ndim == 1:
        x = pan(x, p)
    i = int(t * SR)
    if i >= len(buf) or i + len(x) <= 0:
        return
    x = x[max(0, -i):len(buf) - i]
    buf[max(0, i):max(0, i) + len(x)] += x * gain


def fade(x, a=0.004, b=0.02):
    x = x.copy()
    na, nb = min(len(x), int(a * SR)), min(len(x), int(b * SR))
    x[:na] *= np.linspace(0, 1, na)
    x[len(x) - nb:] *= np.linspace(1, 0, nb)
    return x


def db(v):
    return 10 ** (v / 20)


# ---------------------------------------------------------------- 목소리(말 같지만 말이 아닌 것)

FORMANT = {"a": (760, 1250, 2550), "eo": (600, 1000, 2600), "o": (460, 820, 2500), "u": (350, 850, 2300), "eu": (400, 1400, 2450),
           "i": (290, 2250, 2950), "e": (500, 1850, 2550), "m": (250, 1150, 2400)}
# 중성 21개(ㅏ ㅐ ㅑ ㅒ ㅓ ㅔ ㅕ ㅖ ㅗ ㅘ ㅙ ㅚ ㅛ ㅜ ㅝ ㅞ ㅟ ㅠ ㅡ ㅢ ㅣ) → (앞에 미끄러지는 모음 또는 None, 모음)
JUNG = [(None, "a"), (None, "e"), ("i", "a"), ("i", "e"), (None, "eo"), (None, "e"), ("i", "eo"), ("i", "e"), (None, "o"), ("u", "a"), ("u", "e"),
        ("u", "e"), ("i", "o"), (None, "u"), ("u", "eo"), ("u", "e"), ("u", "i"), ("i", "u"), (None, "eu"), ("eu", "i"), (None, "i")]
STOP, FRIC, NASAL = {0, 1, 3, 4, 7, 8, 15, 16, 17}, {9, 10, 12, 13, 14, 18}, {2, 6}  # 초성 갈래(ㄹ 5, ㅇ 11 은 따로)
CODA_NASAL = {4, 16, 21}
READ = {"故": "고"}


def read_number(s):
    """숫자열을 읽는 소리로: 2031 → 이천삼십일."""
    n, out = int(s), ""
    for unit, name in ((1000, "천"), (100, "백"), (10, "십"), (1, "")):
        d = n // unit % 10
        if d:
            out += ("" if d == 1 and unit > 1 else "영일이삼사오육칠팔구"[d]) + name
    return out or "영"


def syllables(text):
    """글 → [("s", 초성, 중성, 종성) | ("p", 쉬는 초)]"""
    out, i = [], 0
    while i < len(text):
        ch = READ.get(text[i], text[i])
        if ch.isdigit():
            j = i
            while j < len(text) and text[j].isdigit():
                j += 1
            out += syllables(read_number(text[i:j]))
            i = j
            continue
        code = ord(ch) - 0xAC00
        if 0 <= code < 11172:
            out.append(("s", code // 588, code % 588 // 28, code % 28))
        elif ch in ",.":
            out.append(("p", 0.2 if ch == "." else 0.14))
        elif ch == " ":
            out.append(("p", 0.035))
        i += 1
    while out and out[-1][0] == "p":
        out.pop()
    return out


def speak(text, room, seed, f0=106.0, ratio=1.0):
    """한 줄을 room 초 안에 웅얼거린다 → mono. ratio = 목소리 높이(2 면 아이 목소리: 포먼트도 올라감)."""
    rng = np.random.RandomState(seed)
    syl = syllables(text)
    n_s = sum(1 for s in syl if s[0] == "s")
    if not n_s:
        return np.zeros(1)
    pause = sum(s[1] for s in syl if s[0] == "p")
    d = float(np.clip((room - pause - 0.08) / n_s, 0.085, 0.15))
    total = d * n_s + pause + 0.08
    n = int(total * SR)
    t = np.arange(n) / SR
    fscale = 1 + 0.2 * np.log2(ratio)
    amp, noise = np.zeros(n), np.zeros(n)
    F = np.zeros((n, 3))
    pitch = np.ones(n)
    at, k = 0.0, 0
    ends_open = not text.rstrip().endswith(".")
    for s in syl:
        if s[0] == "p":
            at += s[1]
            continue
        _, cho, jung, jong = s
        a, b = int(at * SR), int((at + d) * SR)
        glide, vowel = JUNG[jung]
        f1 = np.array(FORMANT[vowel], float)
        seg = np.tile(f1, (b - a, 1))
        if glide:
            g = np.array(FORMANT[glide], float)
            m = int((b - a) * 0.4)
            seg[:m] = g + (f1 - g) * np.linspace(0, 1, m)[:, None]
        e = np.ones(b - a)
        head = 0
        if cho in STOP:
            head = int(0.032 * SR)
            e[:head] = 0
            click = rng.randn(int(0.012 * SR)) * np.linspace(1, 0, int(0.012 * SR)) ** 2
            noise[a + head - len(click) // 2:a + head - len(click) // 2 + len(click)] += click * 0.5
        elif cho in FRIC:
            head = int((0.05 if cho != 18 else 0.035) * SR)
            e[:head] = 0
            lo, hi = (3800, 9000) if cho in (9, 10) else (2400, 6500) if cho != 18 else (900, 4200)
            h = band(rng.randn(head + 256), lo, hi)[:head] * np.hanning(head)
            noise[a:a + head] += h * (0.75 if cho != 18 else 0.4)
        elif cho in NASAL:
            head = int(0.04 * SR)
            seg[:head] = FORMANT["m"]
            e[:head] = 0.45
        elif cho == 5:
            head = int(0.025 * SR)
            e[:head] = 0.5
        if jong in CODA_NASAL:
            m = int((b - a) * 0.3)
            seg[-m:] = FORMANT["m"]
            e[-m:] *= 0.5
        elif jong:
            e[-int(0.03 * SR):] = 0
        # 낱말 첫 음절은 낮게, 둘째는 높게(서울말 억양), 끝은 내려가거나(마침표) 살짝 올라감(이어지는 말)
        left = n_s - 1 - k
        tone = (0.97 if k % 3 == 0 else 1.03) * (1 + 0.02 * rng.randn())
        if left < 3:
            tone *= (1.0 + 0.035 * (3 - left)) if ends_open else (1.0 - 0.06 * (3 - left))
        pitch[a:b] = tone
        amp[a:b] = e * (1.0 if left else 0.85)
        F[a:b] = seg
        at += d
        k += 1
    # 음절 사이를 부드럽게(혀가 옮겨 가는 시간), 소리 없는 자리의 포먼트는 앞뒤를 이음
    w = int(0.022 * SR)
    ker = np.hanning(w) / np.hanning(w).sum()
    live = F[:, 0] > 0
    idx = np.where(live, np.arange(n), 0)
    np.maximum.accumulate(idx, out=idx)
    F = F[idx]
    F[F[:, 0] == 0] = FORMANT["eo"]
    F = np.stack([np.convolve(F[:, j], ker, "same") for j in range(3)], 1) * fscale
    amp = np.convolve(amp, ker, "same")
    pitch = np.convolve(pitch, np.hanning(int(0.05 * SR)) / np.hanning(int(0.05 * SR)).sum(), "same")
    pitch[pitch < 0.5] = 1
    ph = np.cumsum(f0 * ratio * pitch * (1 + 0.012 * np.sin(2 * np.pi * 5.1 * t + rng.rand() * 6))) / SR
    src = (2 * (ph % 1.0) - 1) * amp + 0.02 * rng.randn(n) * amp
    # 틀마다 포먼트 셋으로 걸러 겹쳐 더함
    win, hop = 1024, 256
    wnd = np.hanning(win)
    out = np.zeros(n + win)
    norm = np.zeros(n + win)
    fr = np.fft.rfftfreq(win, 1 / SR)
    srcp = np.pad(src, (0, win))
    for i in range(0, n, hop):
        fm = F[min(n - 1, i + win // 2)]
        H = sum(g / (1 + ((fr - f) / bw) ** 2) for f, g, bw in zip(fm, (1.0, 0.7, 0.4), (70 * fscale, 95 * fscale, 150 * fscale)))
        H = H / (1 + (fr / (4200 * fscale)) ** 4)
        out[i:i + win] += np.fft.irfft(np.fft.rfft(srcp[i:i + win] * wnd) * H, win) * wnd
        norm[i:i + win] += wnd ** 2
    y = out[:n] / np.maximum(norm[:n], 1e-3) + noise * 0.25
    return y / (np.abs(y).max() + 1e-9)


# 여럿이 같이 말할 때의 목소리: (높이 배율, 크기, 늦는 초, 좌우)
CHORUS = [(1.0, 1.0, 0.0, 0.0), (2.0, 0.5, 0.03, -0.5), (2.12, 0.42, 0.055, 0.55), (0.5, 0.55, 0.02, 0.2), (2.52, 0.3, 0.075, -0.8), (1.94, 0.4, 0.045, 0.85)]


def announce(text, room, seed, voices):
    """안내 방송 목소리 → 스테레오. 스피커를 지난 듯 좁은 대역, 조금 찌그러짐."""
    parts = []
    for v, (ratio, g, late, p) in enumerate(CHORUS[:max(1, voices)]):
        y = speak(text, room - late, seed + 31 * v, ratio=ratio)
        y = np.tanh(band(y, 300, 4000) * 2.2) * g
        parts.append((np.concatenate([np.zeros(int(late * SR)), y]), p))
    n = max(len(y) for y, _ in parts)
    out = np.zeros((n, 2))
    for y, p in parts:
        out[:len(y)] += pan(y, p)
    return out


# ---------------------------------------------------------------- 그 밖의 소리

def chime():
    """안내 방송 종 네 음(솔 시 레 솔). 넷째 음은 늦게, 낮게, 늘어지며 울린다."""
    out = np.zeros(int(3.4 * SR))
    for i, (f, late, cents) in enumerate(((392.0, 0, 0), (493.9, 0, 0), (587.3, 0, 0), (784.0, 0.13, -70))):
        t = secs(2.0)
        wow = 1 + 0.004 * np.sin(2 * np.pi * 0.8 * t + i) - (0.02 * t if cents else 0)
        ph = 2 * np.pi * np.cumsum(f * 2 ** (cents / 1200) * wow) / SR
        y = np.sin(ph) * np.exp(-t / 0.75) + 0.3 * np.sin(3.98 * ph) * np.exp(-t / 0.1) + 0.12 * np.sin(9.9 * ph) * np.exp(-t / 0.025)
        a = int((0.36 * i + late) * SR)
        out[a:a + len(y)] += fade(y, 0.002, 0.1)[:len(out) - a]
    return out / np.abs(out).max()


def hum(n, seed=3):
    """전깃줄 웅웅 + 형광등 지직 → 스테레오."""
    t = np.arange(n) / SR
    rng = np.random.RandomState(seed)

    def one(f, ph):
        return (np.sin(2 * np.pi * f * t + ph) + 0.55 * np.sin(2 * np.pi * 2 * f * t + 1.3 * ph) + 0.3 * np.sin(2 * np.pi * 3 * f * t)
                + 0.16 * np.sin(2 * np.pi * 5 * f * t + ph))
    swell = 1 + 0.18 * np.sin(2 * np.pi * 0.27 * t)
    left = (one(60.0, 0.0) + 0.6 * one(60.55, 1.0)) * swell
    right = (one(60.0, 0.9) + 0.6 * one(60.55, 2.4)) * swell
    # 지직: 120 Hz 톱니의 높은 대역, 가끔 끊김
    gate = np.ones(n)
    for a in np.cumsum(rng.exponential(1.7, int(n / SR / 1.7) + 2)):
        i = int(a * SR)
        gate[i:i + int(rng.uniform(0.04, 0.13) * SR)] = 0
    gate = np.convolve(gate, np.ones(200) / 200, "same")
    buzz = band(2 * ((120 * t) % 1.0) - 1, 1800, 5200) * gate * 0.5
    return np.stack([left + buzz, right + buzz * 0.8], 1) / 3.2


def pop(seed, lo=300.0, hi=1150.0, dur=0.055):
    """젖은 '뽁': 올라가는 짧은 음 + 딸깍."""
    rng = np.random.RandomState(seed)
    t = secs(dur)
    f = lo * (hi / lo) ** (t / dur) * (1 + 0.05 * rng.randn())
    y = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / (dur * 0.35))
    y[:60] += rng.randn(60) * np.linspace(0.5, 0, 60)
    return fade(y, 0.001, 0.01)


def smack(seed):
    """입맛 다시는 '쩝': 짧은 젖은 잡음 둘."""
    rng = np.random.RandomState(seed)
    out = np.zeros(int(0.09 * SR))
    for at, g in ((0.0, 1.0), (0.034 + 0.01 * rng.rand(), 0.6)):
        m = int(0.02 * SR)
        y = band(rng.randn(m + 512), 1300, 5200)[:m] * np.exp(-np.arange(m) / (0.006 * SR))
        out[int(at * SR):int(at * SR) + m] += y * g
    return out / np.abs(out).max()


@functools.lru_cache(None)
def giggle(i):
    """킥킥 열두 가지(게이브 떼 소리와 같은 합성: tools/gabe/audio.py giggle)."""
    rng = np.random.RandomState(100 + i)
    return GA.giggle(seed=200 + i, count=int(rng.randint(3, 8)), base=float(rng.uniform(430, 760)), rate=float(rng.uniform(7.5, 11.5))).astype(np.float64)


def far(x, cut):
    """멀리서 들리게: 높은 소리를 깎음."""
    return band(x, 120, cut)


def reverb(x, wet=0.2, length=1.1, seed=9):
    """방 울림(줄어드는 잡음과 겹쳐 곱함)."""
    rng = np.random.RandomState(seed)
    m = int(length * SR)
    n = len(x) + m
    size = 1 << (n - 1).bit_length()
    out = x.copy()
    for ch in range(2):
        ir = band(rng.randn(m + 512), 200, 4500)[:m] * np.exp(-np.arange(m) / (0.28 * SR))
        ir[:int(0.012 * SR)] = 0
        y = np.fft.irfft(np.fft.rfft(x[:, ch], size) * np.fft.rfft(ir, size), size)[:len(x)]
        out[:, ch] += y * wet / (np.sqrt((ir ** 2).sum()) + 1e-9)
    return out


# ---------------------------------------------------------------- 한 곡

def render(spec, phrases, key, kind):
    """대본 key 의 긴 판(full) 또는 짧은 판(short) → (스테레오 float32 (n, 2), 정보). 0초 = Story 가 인트로를 시작한 틱."""
    cards, length = schedule(spec, key, kind)
    black = spec["fadeIn"] * TICK
    rev = black + length * TICK
    wave = rev + spec["reveal"] * TICK
    n = int((wave + TAIL) * SR)
    dry, wet = np.zeros((n, 2)), np.zeros((n, 2))  # 그대로 나가는 것, 방 울림을 타는 것
    seed = sum(map(ord, key + kind))
    rng = np.random.RandomState(seed)
    # 저음: 검어지며 들어오고, 그림만 뜨는 장면(얼굴)에서는 끊기고, 밝아지기 시작하면 사라진다
    h = hum(n, seed)
    env = np.clip((np.arange(n) / SR - 0.2) / 1.2, 0, 1) * np.clip((rev + 0.05 - np.arange(n) / SR) / 0.05, 0, 1)
    marks = []
    for c in cards:
        t0 = black + c["t"] * TICK
        marks.append((t0, c["line"] or "+".join(dict.fromkeys(c["cover"]))))
        if not c["line"]:
            a, b = int((t0 - 0.01) * SR), int((t0 + (c["stay"] + c["gap"]) * TICK) * SR)
            env[a:b] = 0
    env = np.convolve(env, np.ones(441) / 441, "same")
    dry += h * env[:, None] * db(-21)
    faces, last_voice = 0, black
    for i, c in enumerate(cards):
        t0 = black + c["t"] * TICK
        stay = c["stay"] * TICK
        text = phrases.get(c["line"], "") if c["line"] else ""
        if c["say"] == "chime":
            put(wet, chime(), t0 + 0.15, db(-9))
        elif text:
            if rng.rand() < 0.6 and t0 - last_voice > 0.25:
                put(dry, smack(seed + i), t0 - 0.12, db(-22), rng.uniform(-0.2, 0.2))
            # 말은 줄이 뜨고 0.1초 뒤에 시작해 다음 장면 0.2초 전에는 끝난다(줄 뒤 틈까지 써서 너무 빨라지지 않게)
            room = stay + c["gap"] * TICK - 0.3
            v = announce(text, room, seed + 7 * i, c["voices"])
            put(wet, v, t0 + 0.1, db(-8))
            last_voice = t0 + 0.1 + len(v) / SR
            if c["alt"] in phrases:
                # 깜빡이는 다른 줄은 높은 목소리 하나가 같이 말한다(바뀐 글자만 다르게 들림)
                put(wet, np.tanh(band(speak(phrases[c["alt"]], room, seed + 7 * i, ratio=2.0), 300, 4000) * 2.2), t0 + 0.1, db(-13), 0.35)
        steps = c["cover"]
        if steps and not c["line"]:
            faces += 1
            if len(steps) == 1:
                # 얼굴이 불쑥: 조용한 가운데 뽁 하나
                put(dry, pop(seed + i), t0, db(-7))
            else:
                # 눈이 뜨인다: 그림이 바뀔 때마다 쩝
                for j in range(1, len(steps)):
                    if steps[j] != steps[j - 1]:
                        put(dry, smack(seed + 3 * i + j), t0 + stay * j / len(steps), db(-15), 0.0)
        elif len(steps) > 1:
            # 얼굴이 는다: 그림이 바뀔 때마다 뽁뽁뽁, 킥킥이 가까워진다
            for j in range(len(steps)):
                ts = t0 + stay * j / len(steps)
                for k in range(3 + j):
                    put(dry, pop(seed + 11 * i + 5 * j + k, 260 + 90 * rng.rand(), 900 + 500 * rng.rand()), ts + 0.045 * k + 0.02 * rng.rand(),
                        db(-15), rng.choice((-1, 1)) * rng.uniform(0.5, 1))
                for k in range(1 + j):
                    put(wet, far(giggle(int(rng.randint(12))), 2600), ts + 0.1 + 0.25 * rng.rand(), db(-17), rng.choice((-1, 1)) * rng.uniform(0.6, 1))
        # 얼굴이 한 번 끼어든 뒤로는 멀리서 드문드문 킥킥
        if faces and c["line"] and rng.rand() < 0.75:
            put(wet, far(giggle(int(rng.randint(12))), 1100), t0 + stay * rng.uniform(0.3, 0.8), db(-25), rng.uniform(-1, 1))
    # 밝아진 뒤 첫 웨이브까지: 킥킥이 사방에서 몰려온다(웃음소리가 가까워지고 있습니다), 낮은 울림이 차오른다
    span = wave - rev
    for k in range(34):
        u = (k / 34) ** 0.75
        put(wet, far(giggle(int(rng.randint(12))), 1200 + 5200 * u ** 2), rev + 0.15 + u * (span + 0.6) + 0.1 * rng.rand(), db(-27 + 19 * u), rng.uniform(-1, 1))
    t = secs(span + TAIL)
    swell = np.sin(2 * np.pi * np.cumsum(46 + 20 * np.clip(t / span, 0, 1)) / SR) * np.clip(t / span, 0, 1) ** 2
    put(dry, swell + 0.4 * np.sin(2 * np.pi * np.cumsum(92 + 40 * np.clip(t / span, 0, 1)) / SR) * np.clip(t / span, 0, 1) ** 2, rev, db(-17))
    y = dry + reverb(wet, 0.22)
    # 꼬리는 줄어들며 끝남, 크기 맞춤(소리 나는 곳 RMS, 피크)
    tail = int(TAIL * SR)
    y[-tail:] *= np.linspace(1, 0, tail)[:, None] ** 1.5
    fr = int(0.4 * SR)
    r = np.sqrt((y[:len(y) // fr * fr].reshape(-1, fr, 2) ** 2).mean((1, 2)))
    act = 20 * np.log10(np.sqrt((r[r > r.max() * 0.05] ** 2).mean()) + 1e-12)
    g = min(db(TARGET_RMS_DB - act), db(PEAK_DB) / np.abs(y).max())
    info = {"seconds": round(n / SR, 2), "black": round(black, 2), "reveal": round(rev, 2), "wave": round(wave, 2), "gain_db": round(20 * np.log10(g), 1),
            "marks": [(round(a, 2), b) for a, b in marks]}
    return (y * g).astype(np.float32), info


def ffmpeg():
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except ImportError:
        return "ffmpeg"


def write_ogg(x, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    pcm = (np.clip(x, -1, 1) * 32767).astype("<i2").tobytes()
    r = subprocess.run([ffmpeg(), "-y", "-loglevel", "error", "-f", "s16le", "-ar", str(SR), "-ac", "2", "-i", "-", "-c:a", "libvorbis", "-q:a", str(QUALITY),
                        "-fflags", "+bitexact", "-flags:a", "+bitexact", "-map_metadata", "-1", str(path)], input=pcm, capture_output=True)
    if r.returncode:
        raise RuntimeError("ogg 굽기 실패: " + r.stderr.decode("utf-8", "replace")[-300:])


def tracks():
    """(사건 이름, 소리, 정보) 차례로: trip.intro_<대본>_full, trip.intro_<대본>_short."""
    spec, phrases = load()
    for key in spec["scripts"]:
        for kind in ("full", "short"):
            y, info = render(spec, phrases, key, kind)
            yield f"intro_{key}_{kind}", y, info


# ---------------------------------------------------------------- make_trip 약속

def bake(layer: Path):
    """원정 층에 소리 파일과 sounds.json 키를 넣는다(스테레오, 스트리밍, 자막 없음: 배경 음악처럼 사람 몸에 붙여 틂)."""
    path = layer / "assets/gave/sounds.json"
    sounds = json.loads(path.read_text(encoding="utf-8")) if path.is_file() else {}
    out = {}
    for name, y, info in tracks():
        write_ogg(y, layer / f"assets/gave/sounds/trip/{name}.ogg")
        sounds[f"trip.{name}"] = {"sounds": [{"name": f"gave:trip/{name}", "stream": True}]}
        out[name] = info["seconds"]
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(sounds, ensure_ascii=False, indent=1), encoding="utf-8")
    return {"tracks": len(out), "seconds": out}


def credits():
    return ["[원정 인트로] 판 시작 인트로 소리(assets/gave/sounds/trip/intro_*.ogg)",
            "받은 소리 없음: tools/trip/intro.py 가 numpy 로 합성한다(전깃줄 웅웅, 안내 방송 종, 웅얼거리는 목소리, 젖은 소리, 킥킥)."]


def main():
    args = sys.argv[1:]
    out = Path(args[args.index("--out") + 1]) if "--out" in args else ROOT / "build/intro/sound"
    for name, y, info in tracks():
        write_ogg(y, out / f"{name}.ogg")
        peak = 20 * np.log10(np.abs(y).max() + 1e-12)
        print(f"{name:18s} {info['seconds']:6.2f}s 검정 {info['black']}~{info['reveal']}s 웨이브 {info['wave']}s 피크 {peak:.1f} dB 보정 {info['gain_db']} dB "
              f"{(out / (name + '.ogg')).stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
