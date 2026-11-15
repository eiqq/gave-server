"""보탠 크리처 소리(2026-11-14 개수, docs/소리출처.md '크리처 보강 소리'): 가까운 공용 사건(gabe.cry_*, move_*, flesh_*, corpse_*)과
먼 저음 층(far.*)을 numpy 로 만든다. 씨앗이 고정이라 늘 같은 소리다.

틀(개수를 맡긴 쪽, 2026-11-14): 기존 소리는 그대로 두고 없는 소리를 보탠다. 분위기는 공포보다 기괴함.
- 가까운 소리(NEAR): tools/gabe/common.py 가 엔진 공용 사건 gave:gabe.<이름> 으로 굽는다(키트 조각 build/gabe/sp/common/).
  종마다의 별칭 사건 gabe.<종>.idle, gait, gait_<재질>, flesh 는 키트 finish 가 data/gabe_voice.json 표로 더한다(kit.VOICE).
  Java(kr.gave.mob.Ears)가 듣는 사람마다 예산을 걸고 부른다.
- 먼 저음 층(FAR): tools/make_far_sounds.py 가 pack_src/assets/gave/sounds/far/ 로 굽는다. Java(kr.gave.game.Horizon.farTick)가 부른다.
- 결(사용자가 고를 것, 지금은 '섞기'): 변형마다 합성(매끈하고 사람이 아닌 쪽)과 받은 소리(CC0) 가공(진짜 웃음, 허밍을 뒤집고 높이를 바꿔
  사람 같아서 더 어긋난 쪽)이 섞여 있다. 한쪽만 쓰려면 아래 표에서 그 줄의 V(...) 하나를 빼면 된다.
- 받은 소리는 감독 소리(tools/make_director_sounds.py)가 이미 팩에 넣은 CC0 웃음, 허밍(pack_src/assets/gave/sounds/director/chorus_*,
  sfx/choir_*)을 풀어 쓴다(src). 그래서 원본(ref/sfx)이 없는 PC 에서도 같은 소리가 나온다. 출처는 docs/소리출처.md.
- 크기: 짧은 소리는 가장 큰 0.4초 크기(LUFS), 먼 저음은 통합 크기(파일 -23 LUFS, 듣는 귀 -34 는 Java 음량 sfx.farVolume 이 정함).
모든 함수는 float32 mono 배열(-1..1, 44100 Hz)을 돌려준다. 먼 저음의 울림과 숨만 stereo (n, 2).
"""
import numpy as np

from . import audio

SR = audio.SR
MATERIALS = ("dry", "mud", "ash", "pad")  # 마른 땅(1막, 원정 꺼짐), 젖은 벌(2막), 재(3막), 발사장(4막)
MAT_KO = {"dry": "마른 땅", "mud": "젖은 벌", "ash": "재", "pad": "발사장"}
# 먼 부름과 대답의 배음 층 몫(작은 스피커 보정): 0.35 = 기본(이어폰, 헤드폰), 0.8 = 노트북 스피커로도 들리지만 덜 '낮게' 느껴짐
HARM = 0.35


# ================================================================== 거르기, 크기(FFT, 위상 0)

def _fft_gain(x, fn, sr=SR):
    if x.ndim == 2:
        return np.stack([_fft_gain(x[:, c], fn, sr) for c in range(x.shape[1])], 1)
    n = len(x)
    if n == 0:
        return x
    m = 1 << int(np.ceil(np.log2(n + int(0.05 * sr))))
    f = np.fft.rfftfreq(m, 1 / sr)
    return np.fft.irfft(np.fft.rfft(x, m) * fn(f), m)[:n].astype(np.float32)


def lp(x, fc, order=4, sr=SR):
    return _fft_gain(x, lambda f: 1 / np.sqrt(1 + (f / fc) ** (2 * order)), sr)


def hp(x, fc, order=4, sr=SR):
    return _fft_gain(x, lambda f: 1 / np.sqrt(1 + (fc / np.maximum(f, 1e-9)) ** (2 * order)), sr)


def bp(x, lo, hi, order=4, sr=SR):
    return lp(hp(x, lo, order, sr), hi, order, sr)


def shelf(x, fc, db, sr=SR):
    """fc 위를 db 만큼 올림(완만한 1차 선반)"""
    g = 10 ** (db / 20)
    return _fft_gain(x, lambda f: np.sqrt((1 + g * g * (f / fc) ** 2) / (1 + (f / fc) ** 2)), sr)


def place(dst, x, t, sr=SR):
    """dst(mono 또는 stereo)에 x 를 t 초 자리에 더한다(넘치면 자름). stereo dst 에 mono x 면 가운데"""
    i = int(t * sr)
    if i >= len(dst) or i + len(x) <= 0:
        return
    a, b = max(0, i), min(len(dst), i + len(x))
    seg = x[a - i:b - i]
    if dst.ndim == 2 and seg.ndim == 1:
        seg = seg[:, None]
    dst[a:b] += seg


def stereo(left, right):
    n = max(len(left), len(right))
    out = np.zeros((n, 2), np.float32)
    out[:len(left), 0] = left
    out[:len(right), 1] = right
    return out


def pitch(x, ratio):
    """게임과 같은 높이 바꾸기(높이 = 빠르기): 0.5 면 두 배 길고 한 옥타브 낮다. 선형 보간"""
    if abs(ratio - 1.0) < 1e-6:
        return x.astype(np.float32)
    n = int(len(x) / ratio)
    idx = np.arange(n, dtype=np.float64) * ratio
    i0 = np.minimum(idx.astype(np.int64), len(x) - 1)
    i1 = np.minimum(i0 + 1, len(x) - 1)
    f = (idx - i0).astype(np.float32)
    return (x[i0] * (1 - f) + x[i1] * f).astype(np.float32)


def fade(x, fin=0.005, fout=0.03, sr=SR):
    x = np.array(x, np.float32)
    a, b = min(len(x), int(fin * sr)), min(len(x), int(fout * sr))
    sh = (lambda v: v) if x.ndim == 1 else (lambda v: v[:, None])
    if a:
        x[:a] *= sh(np.linspace(0, 1, a, dtype=np.float32))
    if b:
        x[-b:] *= sh(np.linspace(1, 0, b, dtype=np.float32) ** 1.5)
    return x


def _k_gain(f, sr):
    """BS.1770 K 가중(높은 선반 +4 dB, 38 Hz 고역 통과)의 크기 응답"""
    def biquad(b, a):
        z = np.exp(-2j * np.pi * f / sr)
        return (b[0] + b[1] * z + b[2] * z * z) / (a[0] + a[1] * z + a[2] * z * z)
    A, w0 = 10 ** (4.0 / 40), 2 * np.pi * 1500.0 / sr
    al, c = np.sin(w0) / (2 * (1 / np.sqrt(2))), np.cos(w0)
    sh = biquad([A * ((A + 1) + (A - 1) * c + 2 * np.sqrt(A) * al), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - 2 * np.sqrt(A) * al)],
                [(A + 1) - (A - 1) * c + 2 * np.sqrt(A) * al, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - 2 * np.sqrt(A) * al])
    w0 = 2 * np.pi * 38.0 / sr
    al, c = np.sin(w0) / (2 * 0.5), np.cos(w0)
    return np.abs(sh * biquad([(1 + c) / 2, -(1 + c), (1 + c) / 2], [1 + al, -2 * c, 1 - al]))


def _blocks(x, sr=SR):
    """K 가중한 0.4초 블록(0.1초 걸음)의 평균 제곱(채널 합). 0.4초보다 짧으면 0.4초로 늘려 한 블록"""
    x = np.asarray(x, np.float32)
    if x.ndim == 1:
        x = x[:, None]
    n, w, h = len(x), int(0.4 * sr), int(0.1 * sr)
    if n < w:
        x = np.concatenate([x, np.zeros((w - n, x.shape[1]), np.float32)])
        n = w
    k = np.stack([_fft_gain(x[:, c], lambda f: _k_gain(f, sr), sr) for c in range(x.shape[1])], 1)
    cs = np.concatenate([np.zeros((1, k.shape[1])), np.cumsum(k.astype(np.float64) ** 2, 0)])
    starts = np.arange(0, n - w + 1, h)
    return ((cs[starts + w] - cs[starts]) / w).sum(1)


def lufs(x):
    """통합 크기(LUFS): 절대 문 -70, 상대 문 -10 LU. 조용하면 -99"""
    z = _blocks(x)
    lv = -0.691 + 10 * np.log10(z + 1e-12)
    z1 = z[lv > -70]
    if len(z1) == 0:
        return -99.0
    z2 = z[(lv > -70) & (lv > -0.691 + 10 * np.log10(z1.mean()) - 10)]
    return float(-0.691 + 10 * np.log10(z2.mean())) if len(z2) else -99.0


def lufs_max(x):
    """가장 큰 0.4초 창의 크기(LUFS)"""
    return float(-0.691 + 10 * np.log10(_blocks(x).max() + 1e-12))


def peak_db(x):
    return float(20 * np.log10(np.abs(x).max() + 1e-12)) if len(x) else -120.0


def to_level(x, target, short, peak):
    """크기를 target LUFS 로(short 면 가장 큰 0.4초, 아니면 통합). 봉우리는 peak dBFS 아래"""
    g = target - (lufs_max(x) if short else lufs(x))
    g -= max(0.0, peak_db(x) + g - peak)
    return (x * 10 ** (g / 20)).astype(np.float32)


def high_db(x):
    """2~6 kHz 대역 평균 세기(dBFS): 먼 저음에 높은 소리가 섞이지 않았는지 보는 데"""
    m = x.mean(1) if x.ndim == 2 else x
    n = len(m)
    k = 1 << int(np.ceil(np.log2(n)))
    P = np.abs(np.fft.rfft(m, k)) ** 2
    f = np.fft.rfftfreq(k, 1 / SR)
    return float(10 * np.log10(2 * P[(f >= 2000) & (f < 6000)].sum() / (n * k) + 1e-12))


# ================================================================== 바탕

def R(seed):
    return np.random.RandomState(int(seed) & 0x7FFFFFFF)


def n_of(dur):
    return int(round(dur * SR))


def t_of(n):
    return np.arange(n, dtype=np.float32) / SR


def white(dur, r):
    return (r.rand(n_of(dur)).astype(np.float32) * 2 - 1)


def colored(dur, r, slope):
    """세기 기울기 slope dB/옥타브의 잡음(-3 분홍, -6 갈색). 평균 제곱 1 로 맞춤"""
    n = n_of(dur)
    m = 1 << int(np.ceil(np.log2(max(2, n))))
    X = np.fft.rfft(r.randn(m))
    f = np.fft.rfftfreq(m, 1 / SR)
    g = np.ones_like(f)
    g[1:] = (f[1:] / 100.0) ** (slope / 6.0)
    g[0] = 0
    x = np.fft.irfft(X * g, m)[:n]
    return (x / (np.sqrt(np.mean(x ** 2)) + 1e-12)).astype(np.float32)


def pink(dur, r):
    return colored(dur, r, -3.0)


def brown(dur, r):
    return colored(dur, r, -6.0)


def decay(n, tau, attack=0.0):
    """exp(-t/tau) 봉투(초). attack 초 동안 0 → 1"""
    t = t_of(n)
    e = np.exp(-t / max(tau, 1e-5))
    if attack > 0:
        e *= np.minimum(1.0, t / attack)
    return e.astype(np.float32)


def hann_env(n, fin, fout):
    """올림 코사인으로 들어오고 나가는 봉투(초)"""
    e = np.ones(n, np.float32)
    a, b = min(n, n_of(fin)), min(n, n_of(fout))
    if a:
        e[:a] = 0.5 - 0.5 * np.cos(np.pi * np.arange(a) / a)
    if b:
        e[-b:] *= 0.5 + 0.5 * np.cos(np.pi * np.arange(b) / b)
    return e


def modes(dur, freqs, taus, amps, r=None, glide=0.0):
    """감쇠하는 사인 묶음(두드린 몸의 울림). glide: 끝에서 높이가 (1 + glide) 배"""
    n = n_of(dur)
    t = t_of(n)
    out = np.zeros(n, np.float32)
    for f, tau, a in zip(freqs, taus, amps):
        ph = (r.rand() * 2 * np.pi) if r is not None else 0.0
        fr = f * (1 + glide * t / max(dur, 1e-6))
        out += a * np.exp(-t / tau) * np.sin(2 * np.pi * np.cumsum(fr) / SR + ph)
    return out.astype(np.float32)


def bubbles(dur, count, r, fmin=300.0, fmax=3000.0, early=1.0, rise=0.12, big=1.2):
    """젖은 방울 구름(물, 진흙, 살이 눌리는 소리). 방울 하나 = 높이가 살짝 오르며 꺼지는 사인.
    방울 반지름 r_b = 3 / f0(m), 감쇠 d = 0.13 / r_b + 0.0072 * r_b^-1.5 (van den Doel 2005 의 물방울 모형).
    early > 1 이면 앞쪽에 몰림. big: 큰(낮은) 방울일수록 크게(지수)"""
    n = n_of(dur)
    out = np.zeros(n + n_of(0.25) + 1, np.float32)
    for _ in range(int(count)):
        f0 = float(np.exp(r.uniform(np.log(fmin), np.log(fmax))))
        rb = 3.0 / f0
        d = 0.13 / rb + 0.0072 * rb ** -1.5
        m = min(n_of(6.0 / d), n_of(0.25))
        t = t_of(m)
        f = f0 * (1 + rise * d * t)
        x = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-d * t) * np.minimum(1.0, t / 0.0006)
        a = (fmin / f0) ** big * r.uniform(0.4, 1.0)
        i = int((r.rand() ** early) * max(1, n - 1))
        out[i:i + m] += (a * x).astype(np.float32)
    return out[:n]


def grains(dur, count, r, lo=3000.0, hi=9000.0, tau=0.004, early=1.0):
    """자잘한 알갱이(모래, 자갈, 재가 튀는 소리): 짧은 대역 잡음 조각"""
    n = n_of(dur)
    out = np.zeros(n + n_of(0.05), np.float32)
    for _ in range(int(count)):
        m = n_of(r.uniform(2.5, 6.0) * tau)
        g = bp(white(m / SR, r), lo * r.uniform(0.7, 1.3), hi, 2) * decay(m, tau * r.uniform(0.6, 1.4), 0.0003)
        i = int((r.rand() ** early) * max(1, n - 1))
        out[i:i + m] += g * r.uniform(0.3, 1.0)
    return out[:n]


VOWELS = {  # 포먼트 Hz(어른 남자 평균값 근처). 얼굴 크기에 따라 scale 로 줄이고 늘린다
    "a": (730, 1090, 2440), "i": (270, 2290, 3010), "u": (300, 870, 2240), "o": (570, 840, 2410),
    "e": (530, 1840, 2480), "eu": (350, 1300, 2300), "m": (250, 1100, 2100),
}


def voice(f0, formants, r, tilt=1.0, breath=0.08, fry=0.0, jitter=0.012, bw=(100, 130, 190), gains=(1.0, 0.75, 0.45), fmax=9000.0):
    """목소리 비슷한 소리: 배음을 하나씩 더하고 배음마다 그 순간의 포먼트 곡선으로 크기를 준다(시간에 따라 변하는 모음).
    f0: Hz 배열(표본마다). formants: (n, 3) 배열 또는 길이 3 튜플. fry: 0..1 이면 한 주기 걸러 약해짐(목 긁는 소리).
    breath: 숨 섞임(포먼트를 입힌 잡음)"""
    f0 = np.asarray(f0, np.float32)
    n = len(f0)
    F = np.broadcast_to(np.asarray(formants, np.float32), (n, 3))
    if jitter:
        f0 = f0 * (1 + jitter * lp(r.randn(n).astype(np.float32), 30, 1) * 6)
    ph = 2 * np.pi * np.cumsum(f0) / SR
    out = np.zeros(n, np.float32)
    kmax = int(min(70, fmax / max(40.0, float(f0.min()))))
    for k in range(1, kmax + 1):
        fk = k * f0
        a = np.zeros(n, np.float32)
        for i in range(3):
            a += gains[i] / np.sqrt(1 + ((fk - F[:, i]) / (bw[i] * 0.5)) ** 2)
        a *= k ** -tilt
        a[fk > fmax] = 0
        out += a * np.sin(k * ph + r.rand() * 2 * np.pi * (k > 1))
    if fry > 0:
        out *= 1 - fry * (0.5 + 0.5 * np.sin(ph / 2))
    if breath > 0:
        b = white(n / SR, r)
        fm = F.mean(0)
        b = bp(b, fm[0] * 0.7, fm[0] * 1.4, 2) + 0.7 * bp(b, fm[1] * 0.8, fm[1] * 1.25, 2) + 0.5 * bp(b, fm[2] * 0.85, fm[2] * 1.2, 2)
        out += breath * b * (np.abs(out).max() + 1e-6) / (np.abs(b).max() + 1e-6)
    return out.astype(np.float32)


def fry_gate(f0, depth):
    """목이 긁히는 소리: 한 주기 걸러 depth(0..1, 배열도 됨)만큼 약하게 → 반 높이의 거친 떨림"""
    half = np.sign(np.sin(np.pi * np.cumsum(f0) / SR))
    return (1 - np.asarray(depth, np.float32) * (0.5 + 0.5 * half)).astype(np.float32)


def glide(n, *points):
    """(자리 0..1, 값) 점들을 잇는 곡선. 예: glide(n, (0, 260), (0.6, 340), (1, 300))"""
    xs = np.array([p[0] for p in points], np.float32) * (n - 1)
    ys = np.array([p[1] for p in points], np.float32)
    return np.interp(np.arange(n), xs, ys).astype(np.float32)


def vowel_path(n, scale, *points):
    """(자리 0..1, 모음 이름) 점들을 잇는 포먼트 길 (n, 3)"""
    xs = np.array([p[0] for p in points], np.float32) * (n - 1)
    out = np.empty((n, 3), np.float32)
    for i in range(3):
        out[:, i] = np.interp(np.arange(n), xs, [VOWELS[p[1]][i] * scale for p in points])
    return out


def reverb(x, t60, r, lp_hz=4000.0, pre=0.02, wet=0.4, build=0.0):
    """합성 잔향: 감쇠하는 잡음을 임펄스 응답으로 써서 FFT 로 겹친다. build 초 동안 응답이 서서히 커지면 멀리서 굴러오는 소리가 된다.
    돌려주는 길이 = 입력 + t60"""
    m = n_of(t60 * 1.1)
    ir = r.randn(m).astype(np.float32) * np.exp(-6.91 * t_of(m) / t60)
    if build > 0:
        ir *= np.minimum(1.0, t_of(m) / build) ** 1.5
    ir = lp(ir, lp_hz, 2)
    ir /= np.sqrt(np.sum(ir ** 2)) + 1e-12
    n = len(x) + m + n_of(pre)
    k = 1 << int(np.ceil(np.log2(n)))
    y = np.fft.irfft(np.fft.rfft(x, k) * np.fft.rfft(ir, k), k)[:n].astype(np.float32)
    out = np.zeros(n, np.float32)
    out[:len(x)] += (1 - wet) * x
    out[n_of(pre):] += wet * y[:n - n_of(pre)]
    return out


def norm(x, peak=0.9):
    m = float(np.abs(x).max()) if len(x) else 0.0
    return (x * (peak / m)).astype(np.float32) if m > 1e-9 else x.astype(np.float32)


def punch(x, drive=2.5, peak=0.9):
    """짧은 타격음을 눌러 몸을 키운다(tanh 로 봉우리만 깎음). 봉우리 대비 크기가 3~6 dB 오른다"""
    return norm(np.tanh(drive * norm(x, 1.0)), peak)


def wet_click(r, f=1800.0, amp=1.0):
    """입술이나 젖은 살이 떨어지는 '쩝' 한 번(5~12 ms)"""
    x = bubbles(0.02, 3, r, f * 0.6, f * 1.6, early=3.0) + 0.5 * hp(white(0.02, r), 2500, 2) * decay(n_of(0.02), 0.002, 0.0002)
    return norm(x, amp)


# ================================================================== 받은 소리(CC0) 재료

_SRC = {}


def src(sid):
    """받은 소리 아이디(make_director_sounds.EVENTS 의 director.chorus, sfx.choir 변형) → 그 변형이 팩 바탕에 구워진 파일을 푼 mono 배열.
    원본(ref/sfx)을 자르고 크기만 맞춘 것이라 원본이 없는 PC 에서도 같은 소리가 나온다(높이는 sounds.json 에만 있고 파일은 원래 높이)"""
    if sid not in _SRC:
        import make_director_sounds as D  # tools/ 가 sys.path 에 있어야 함(make_gabe, make_far_sounds 가 넣음)
        for ev in ("director.chorus", "sfx.choir"):
            ids = [v["id"] for v in D.EVENTS[ev][2]]
            if sid in ids:
                folder, name = ev.split(".", 1)
                _SRC[sid] = audio.load(D.ASSETS / "sounds" / folder / f"{name}_{ids.index(sid)}.ogg")
                break
        else:
            raise KeyError(f"받은 소리 {sid} 가 director.chorus, sfx.choir 변형에 없음")
    return _SRC[sid]


def syllables(x, top_db=16.0, min_len=0.05, gap=0.025):
    """음절(소리 덩어리) [(시작, 끝) 표본]: 10 ms 봉투가 가장 큰 곳보다 top_db 아래로 gap 초 넘게 내려가면 끊는다"""
    win = int(0.01 * SR)
    n = len(x) // win
    e = np.sqrt((x[:n * win].reshape(n, win) ** 2).mean(1) + 1e-12)
    on = e > e.max() * 10 ** (-top_db / 20)
    out, i, need = [], 0, max(1, int(gap / 0.01))
    while i < n:
        if not on[i]:
            i += 1
            continue
        j, quiet = i, 0
        while j < n and quiet < need:
            quiet = 0 if on[j] else quiet + 1
            j += 1
        if (j - quiet - i) * 0.01 >= min_len:
            out.append((i * win, (j - quiet) * win))
        i = j
    return out


def _take(r, sid, count):
    """그 소리에서 잇닿은 음절 count 개를 무작위 자리에서 꺼낸다"""
    x = src(sid)
    syl = syllables(x)
    count = min(count, len(syl))
    i = r.randint(0, len(syl) - count + 1)
    return [fade(x[a:b], 0.004, 0.012) for a, b in syl[i:i + count]]


# ================================================================== 가까운 소리: 울음(울음 사건이 없는 종에만)

def cry_small(seed):
    """떼 마리, 작은 몸: 높고 얇은 '킥킥'. 박자가 기계처럼 똑같고 마지막 음절이 도중에 뚝 끊긴다"""
    r = R(seed)
    count = r.randint(2, 5)
    rate = r.uniform(10.5, 12.5)
    base = r.uniform(520, 700)
    scale = r.uniform(1.35, 1.55)  # 아주 작은 입
    step = 1.0 / rate
    out = np.zeros(n_of(count * step + 0.08), np.float32)
    for i in range(count):
        d = step * 0.62 * (0.45 if i == count - 1 else 1.0)  # 마지막은 반 토막
        n = n_of(d)
        f0 = glide(n, (0, base * (1 - 0.035 * i)), (1, base * (1 - 0.035 * i) * 1.06))
        v = voice(f0, vowel_path(n, scale, (0, "e"), (1, "a")), r, tilt=0.75, breath=0.3, bw=(170, 220, 320), gains=(1.0, 0.9, 0.6))
        e = hann_env(n, 0.006, 0.004 if i == count - 1 else d * 0.5)
        place(out, v * e, i * step)
    place(out, wet_click(r, 3200, 0.35), 0.0)
    return norm(hp(out, 250, 2), 0.85)


def cry_mid(seed):
    """보통 몸: 들숨으로 올라가는 '히읍' 뒤에 낮게 내쉬는 '쿠'. 음절 앞마다 젖은 입술 소리. 얼굴은 작은데 목소리는 낮다"""
    r = R(seed)
    scale = r.uniform(1.18, 1.32)
    a = r.uniform(0.13, 0.19)
    gap = r.uniform(0.03, 0.07)
    b = r.uniform(0.16, 0.26)
    f1 = r.uniform(235, 300)
    n1, n2 = n_of(a), n_of(b)
    v1 = voice(glide(n1, (0, f1), (0.7, f1 * 1.32), (1, f1 * 1.27)), vowel_path(n1, scale, (0, "i"), (1, "eu")), r, tilt=0.85, breath=0.5,
               bw=(130, 170, 250))
    v1 *= hann_env(n1, a * 0.75, 0.006)  # 들숨: 서서히 커지다 뚝
    f2 = f1 * r.uniform(0.6, 0.7)
    v2 = voice(glide(n2, (0, f2 * 1.08), (0.3, f2), (1, f2 * 0.86)), vowel_path(n2, scale, (0, "o"), (0.6, "u"), (1, "m")), r, tilt=1.0,
               breath=0.15, fry=0.35, bw=(130, 170, 250))
    v2 *= hann_env(n2, 0.012, b * 0.55)
    out = np.zeros(n1 + n_of(gap) + n2 + n_of(0.05), np.float32)
    place(out, v1, 0.0)
    place(out, wet_click(r, 2400, 0.5), a + gap * 0.3)
    place(out, 1.15 * v2, a + gap)
    return norm(hp(out, 120, 2), 0.88)


def cry_big(seed):
    """큰 몸: 두 목소리가 조금 어긋난 채 '음마암' 하고 입을 열었다 닫는다. 끝에서 목이 긁힌다"""
    r = R(seed)
    dur = r.uniform(0.95, 1.5)
    n = n_of(dur)
    base = r.uniform(84, 104)
    ratio = r.choice([1.059, 1.414, 1.335])  # 반음, 증4도, 완전4도
    scale = r.uniform(0.9, 1.02)
    path = vowel_path(n, scale, (0, "m"), (0.3, "a"), (0.62, "o"), (1, "m"))
    f0 = glide(n, (0, base * 1.05), (0.25, base), (0.8, base * 0.97), (1, base * 0.88))
    v = voice(f0, path, r, tilt=1.15, breath=0.07)
    v2 = voice(f0 * ratio * (1 + 0.004 * np.sin(2 * np.pi * 0.9 * t_of(n))), path * 1.04, r, tilt=1.3, breath=0.05)
    out = (v + 0.6 * v2) * hann_env(n, 0.09, dur * 0.3) * fry_gate(f0, glide(n, (0, 0.0), (0.6, 0.1), (1, 0.7)))
    full = np.zeros(n + n_of(0.03), np.float32)
    place(full, out, 0.02)
    place(full, wet_click(r, 1400, 0.3), 0.0)
    return norm(hp(full, 60, 2), 0.88)


def cry_ground(seed):
    """기는 몸: 목을 긁는 '그그그극'. 빨라졌다 느려지는 딱딱 소리(문 삐걱임과 목 울림 사이)"""
    r = R(seed)
    dur = r.uniform(0.55, 0.9)
    n = n_of(dur)
    rate = glide(n, (0, r.uniform(9, 14)), (0.45, r.uniform(30, 44)), (1, r.uniform(8, 13)))
    ticks = np.nonzero(np.diff(np.floor(np.cumsum(rate) / SR)) > 0)[0]
    scale = r.uniform(0.95, 1.2)
    out = np.zeros(n + n_of(0.05), np.float32)
    f1, f2 = 620 * scale, 1750 * scale
    for j, i in enumerate(ticks):
        m = n_of(0.022)
        c = modes(0.022, [f1 * r.uniform(0.94, 1.06), f2 * r.uniform(0.94, 1.06), 3100 * scale], [0.006, 0.004, 0.0025], [1.0, 0.7, 0.35], r)
        c += 0.25 * hp(white(0.022, r), 1500, 2) * decay(m, 0.002)
        out[i:i + m] += c * (0.55 + 0.45 * np.sin(np.pi * j / max(1, len(ticks) - 1)))
    hum = voice(np.full(n, r.uniform(70, 88), np.float32), vowel_path(n, scale, (0, "eu"), (1, "o")), r, tilt=1.5, breath=0.3, fry=0.6)
    out[:n] += 0.22 * hum * hann_env(n, 0.05, 0.2)
    return norm(hp(out, 90, 2), 0.85)


def rec_cry_small(seed):
    """작은 몸(받은 웃음): 진짜 웃음의 '하' 서넛을 두 배 가까이 높이고 박자를 기계처럼 똑같이 다시 놓는다. 마지막은 반 토막"""
    r = R(seed)
    parts = _take(r, r.choice(["oga_laughter/ogalaughter_2#4", "oga_laughs/laugh_4"]), r.randint(3, 5))
    ratio = r.uniform(1.65, 1.95)
    step = 1.0 / r.uniform(10.5, 12.5)
    out = np.zeros(n_of(len(parts) * step + 0.15), np.float32)
    for i, p in enumerate(parts):
        last = i == len(parts) - 1
        p = pitch(p, ratio * (1 - 0.03 * i))[:n_of(step * (0.4 if last else 0.85))]
        place(out, fade(p, 0.003, 0.004 if last else 0.02), i * step)
    place(out, wet_click(r, 3200, 0.3), 0.0)
    return norm(hp(out, 300, 2), 0.85)


def rec_cry_mid(seed):
    """보통 몸(받은 웃음): 진짜 웃음 두세 음절을 통째로 뒤집어(들숨처럼 커지다 뚝 끊김) 조금 낮추고, 끊기는 자리에 젖은 입술 소리"""
    r = R(seed)
    parts = _take(r, r.choice(["oga_giggle/group_giggling", "oga_laughter/ogalaughter_2#1", "oga_laughter/ogalaughter_2#4"]), r.randint(2, 4))
    ratio = r.uniform(0.78, 0.95)
    gap = r.uniform(0.02, 0.05)
    xs = [pitch(p[::-1].copy(), ratio) for p in parts[::-1]]
    out = np.zeros(sum(len(x) for x in xs) + n_of(gap) * len(xs) + n_of(0.06), np.float32)
    t = 0.0
    for x in xs:
        place(out, fade(x, 0.02, 0.003), t)
        t += len(x) / SR
        place(out, wet_click(r, r.uniform(1800, 2800), 0.45), t)
        t += gap
    return norm(hp(out, 140, 2), 0.88)


def rec_cry_big(seed):
    """큰 몸(받은 목소리): 길게 끈 진짜 목소리를 한 옥타브 넘게 낮추고, 조금 어긋난 한 겹을 더 얹는다. 끝에서 목이 긁힌다"""
    r = R(seed)
    x = src("oga_laughter/ogalaughter_2#1")
    a = n_of(r.uniform(0.70, 0.78))
    seg = fade(x[a:a + n_of(r.uniform(0.5, 0.66))], 0.02, 0.05)
    ratio = r.uniform(0.40, 0.5)
    v1 = pitch(seg, ratio)
    v2 = pitch(seg, ratio * r.choice([1.059, 1.335, 1.414]))
    out = np.zeros(len(v1) + n_of(0.04), np.float32)
    place(out, v1, 0.02)
    place(out, 0.6 * v2, 0.02 + r.uniform(0.0, 0.05))
    out *= fry_gate(np.full(len(out), 200.0 * ratio, np.float32), glide(len(out), (0, 0.0), (0.55, 0.1), (1, 0.75)))
    place(out, wet_click(r, 1400, 0.3), 0.0)
    return norm(lp(hp(out, 55, 2), 3200, 2), 0.88)


# ================================================================== 가까운 소리: 걸음과 이동(이동 사건이 없는 종에만)

def _ground(mat, r, weight=1.0, ring=0.05):
    """땅 재질 층(발이 닿는 순간 땅이 내는 소리). weight 0.3(가벼운 막대기 끝) ~ 2(무거운 몸). ring: 발사장 쇠판 울림의 몫"""
    w = float(weight)
    if mat == "dry":   # 마른 땅: 자갈 알갱이가 튀고 낮은 먼지 소리
        x = np.zeros(n_of(0.22), np.float32)
        place(x, grains(0.16, 10 + 22 * w, r, 2500, 9000, 0.0035, early=2.2), 0.0)
        place(x, 0.5 * w * bp(white(0.12, r), 180, 1100, 2) * decay(n_of(0.12), 0.03, 0.004), 0.0)
        return x
    if mat == "mud":   # 젖은 벌: 방울이 터지고 발을 뗄 때 빨려 올라오는 '쩍'
        x = np.zeros(n_of(0.34), np.float32)
        place(x, bubbles(0.12, 14 + 30 * w, r, 260 / w ** 0.5, 2600, early=2.0), 0.0)
        place(x, 0.45 * lp(white(0.1, r), 1500, 2) * decay(n_of(0.1), 0.022, 0.003), 0.0)
        place(x, 0.55 * bubbles(0.07, 8 + 8 * w, r, 500, 3500, early=0.5, rise=0.3), r.uniform(0.13, 0.2))
        return x
    if mat == "ash":   # 재: 부드럽게 꺼지는 '폭', 높은 쪽이 길게 흩어진다
        x = np.zeros(n_of(0.36), np.float32)
        place(x, 0.8 * bp(white(0.3, r), 1400, 7500, 2) * decay(n_of(0.3), 0.07, 0.012), 0.0)
        place(x, 0.5 * w * lp(white(0.1, r), 420, 2) * decay(n_of(0.1), 0.03, 0.006), 0.0)
        return x
    # 발사장: 단단한 바닥의 '딱' 과 쇠판의 가는 울림
    x = np.zeros(n_of(0.4), np.float32)
    place(x, hp(white(0.012, r), 1800, 2) * decay(n_of(0.012), 0.0016, 0.0002), 0.0)
    place(x, 0.5 * bp(white(0.05, r), 700, 3800, 2) * decay(n_of(0.05), 0.009, 0.0004), 0.0)
    f = r.uniform(380, 520)
    place(x, ring * w * modes(0.38, [f, f * 2.71, f * 4.9, f * 7.3], [0.07, 0.05, 0.03, 0.02], [1, 0.6, 0.35, 0.2], r), 0.0)
    return x


def step(mat, seed, weight=1.0):
    """발 하나가 닿는 소리. weight < 0.6 = 막대기 끝이 딛는 '틱', 0.6~1.4 = 살 발바닥 '찹', 그 위 = 무거운 '쿵'"""
    r = R(seed)
    w = float(weight)
    out = np.zeros(n_of(0.42), np.float32)
    if w < 0.6:      # 막대기 끝(엔드 막대기는 단단하고 가늘다)
        f = r.uniform(1300, 2200)
        place(out, 0.9 * modes(0.05, [f, f * 2.76, f * 5.4], [0.011, 0.007, 0.004], [1, 0.55, 0.3], r), 0.0)
    elif w <= 1.4:   # 살 발바닥
        place(out, lp(white(0.05, r), 1700, 2) * decay(n_of(0.05), 0.014, 0.001), 0.0)
        place(out, 0.8 * modes(0.12, [r.uniform(120, 175), r.uniform(240, 330)], [0.03, 0.02], [1, 0.5], r), 0.0)
        place(out, 0.3 * bubbles(0.05, 6, r, 900, 3500, early=2.5), 0.004)
    else:            # 무거운 몸
        f = r.uniform(46, 68)
        place(out, 1.3 * modes(0.4, [f, f * 1.9, f * 3.2], [0.13, 0.07, 0.035], [1, 0.6, 0.3], r, glide=-0.12), 0.0)
        place(out, 0.7 * lp(white(0.08, r), 900, 2) * decay(n_of(0.08), 0.022, 0.002), 0.0)
    place(out, (0.55 if w < 0.6 else 0.85) * norm(_ground(mat, r, w), 1.0), 0.002)
    return norm(out, 0.88)


def drag(mat, seed, weight=1.0):
    """몸을 끄는 소리(0.5~0.9초): 걸렸다 미끄러지기를 되풀이하는 살 + 땅 재질"""
    r = R(seed)
    dur = r.uniform(0.5, 0.9)
    n = n_of(dur)
    rate = r.uniform(11, 22)
    saw = (np.cumsum(rate * (1 + 0.35 * lp(r.randn(n).astype(np.float32), 6, 1) * 8)) / SR) % 1.0
    slip = np.exp(-saw / 0.22).astype(np.float32)  # 걸렸다 풀릴 때마다 짧게 커진다(불규칙한 톱니)
    body = hann_env(n, dur * 0.3, dur * 0.35)
    skin = bp(pink(dur, r), 250, 1800, 2) * slip * body  # 살이 쓸리는 소리
    low = 0.5 * weight * lp(brown(dur, r), 160, 2) * body
    if mat == "dry":
        g = bp(white(dur, r), 1800, 7500, 2) * slip ** 0.5 * body + 0.6 * grains(dur, 60 * dur, r, 3000, 9500, 0.003)[:n]
        out = 0.7 * skin + 0.9 * g + low
    elif mat == "mud":
        slurp = bubbles(dur, 150 * dur, r, 220, 2400, early=1.0) * body
        out = 0.5 * lp(skin, 1200, 2) + 1.3 * slurp + low
        place(out, 0.6 * bubbles(0.08, 12, r, 500, 3500, early=0.5, rise=0.3), dur * 0.82)  # 끝에서 떨어지는 '쩍'
    elif mat == "ash":
        hiss = bp(white(dur, r), 2200, 9000, 2) * (0.55 + 0.45 * slip) * body
        out = 0.45 * lp(skin, 900, 2) + 0.9 * hiss + 0.6 * low
    else:
        f = r.uniform(900, 1500)   # 단단한 바닥에 살이 밀리며 내는 가는 '끼익'(걸린 동안만, 드문드문)
        on = np.clip(lp(r.randn(n).astype(np.float32), 5, 1) * 30, 0, 1)
        sq = np.sin(2 * np.pi * np.cumsum(f * (1 + 0.05 * saw)) / SR) * slip * body * on
        out = 0.9 * skin + 0.28 * sq.astype(np.float32) + low + 0.35 * hp(white(dur, r), 3000, 2) * slip ** 2 * body
    return norm(hp(out.astype(np.float32), 55, 2), 0.85)


def flap(seed, size=1.0):
    """젖은 막이 퍼덕이는 소리: 내리치는 바람 + 끝의 찰싹 + 뒤따르는 물방울. size 가 크면 낮고 길다"""
    r = R(seed)
    dur = 0.3 * size ** 0.5
    out = np.zeros(n_of(dur + 0.16), np.float32)
    m = n_of(dur * 0.55)
    sweep = glide(m, (0, 0.0), (1, 1.0))
    w = white(m / SR, r)
    air = (1 - sweep) * bp(w, 180 / size, 520 / size, 2) + sweep * bp(w, 420 / size, 1500 / size, 2)
    place(out, 0.8 * air * hann_env(m, dur * 0.3, dur * 0.12), 0.0)
    slap_t = dur * 0.5
    place(out, 1.0 * bp(white(0.05, r), 500, 5200, 2) * decay(n_of(0.05), 0.011, 0.0006), slap_t)
    place(out, 0.9 * modes(0.14, [r.uniform(135, 185) / size, r.uniform(300, 380) / size], [0.04, 0.022], [1, 0.45], r, glide=-0.25), slap_t)
    place(out, 0.55 * bubbles(0.2, 8 + r.randint(0, 9), r, 1300, 5200, early=1.6), slap_t + 0.012)
    return norm(hp(out, 70, 2), 0.86)


# ================================================================== 가까운 소리: 죽을 때 얹는 살덩이 층, 사체(래그돌)

def _tape_giggle(r, dur, f_start, f_end, scale):
    """테이프가 늘어지듯 느려지며 꺼지는 웃음: 음절 사이가 벌어지고 높이가 떨어진다"""
    n = n_of(dur)
    syl = (np.cumsum(glide(n, (0, 10.5), (1, 3.0))) / SR) % 1.0
    gate = (np.clip(np.sin(np.pi * syl / 0.55), 0, 1) ** 1.5).astype(np.float32)
    f0 = glide(n, (0, f_start), (0.5, f_start * 0.7), (1, f_end))
    v = voice(f0, vowel_path(n, scale, (0, "i"), (0.5, "e"), (1, "eu")), r, tilt=1.0, breath=0.2)
    return v * gate * glide(n, (0, 1.0), (0.7, 0.6), (1, 0.0))


def die(seed, size="mid"):
    """죽는 소리(살덩이). small: '뽁' 과 짧은 삑. mid: 터짐 + 살 튀는 소리 + 바람 빠지는 소리 + 늘어지는 웃음. big: 묵직한 쿵 + 긴 꾸르륵 + 낮은 신음"""
    r = R(seed)
    if size == "small":
        out = np.zeros(n_of(0.3), np.float32)
        place(out, bubbles(0.03, 1, r, 480, 720, early=9.0, rise=0.5, big=0), 0.0)
        place(out, 0.6 * hp(white(0.004, r), 2500, 2) * decay(n_of(0.004), 0.001), 0.0)
        place(out, 0.5 * bubbles(0.1, 10, r, 1200, 5000, early=2.0), 0.01)
        n = n_of(0.07)
        v = voice(glide(n, (0, 950), (1, 1500)), vowel_path(n, 1.5, (0, "i"), (1, "i")), r, tilt=0.8, breath=0.3)
        place(out, 0.4 * norm(v * hann_env(n, 0.02, 0.01), 1.0), 0.03)
        return norm(hp(out, 200, 2), 0.88)
    if size == "mid":
        dur = r.uniform(0.8, 1.0)
        out = np.zeros(n_of(dur + 0.1), np.float32)
        f = r.uniform(72, 105)
        place(out, 1.2 * modes(0.3, [f, f * 2.05, f * 3.3], [0.085, 0.045, 0.022], [1, 0.55, 0.3], r, glide=-0.3), 0.0)
        place(out, 0.9 * lp(white(0.07, r), 3200, 2) * decay(n_of(0.07), 0.02, 0.0006), 0.0)
        place(out, 0.85 * bubbles(0.26, 90 + r.randint(0, 40), r, 300, 3200, early=2.4), 0.008)
        # 바람 빠지는 소리: 떨리며 내려가는 좁은 대역 잡음
        m = n_of(dur * 0.7)
        t = t_of(m)
        fl = 0.5 + 0.5 * np.sin(2 * np.pi * np.cumsum(glide(m, (0, 38), (1, 16))) / SR)
        w = white(m / SR, r)
        hiss = np.zeros(m, np.float32)
        for a, b, lo, hi in ((0.0, 0.4, 600, 1500), (0.3, 0.75, 380, 950), (0.65, 1.0, 230, 600)):
            hiss += bp(w, lo, hi, 2) * np.clip(np.minimum((t / t[-1] - a) / 0.1, (b - t / t[-1]) / 0.1), 0, 1)
        place(out, 0.55 * hiss * fl ** 2 * glide(m, (0, 0.0), (0.08, 1.0), (1, 0.0)), 0.05)
        g = _tape_giggle(r, dur * 0.8, r.uniform(380, 470), r.uniform(120, 160), r.uniform(1.2, 1.3))
        place(out, 0.42 * norm(g, 1.0), 0.06)
        return punch(hp(out, 45, 2), 2.0, 0.9)
    dur = r.uniform(1.3, 1.8)
    out = np.zeros(n_of(dur + 0.15), np.float32)
    f = r.uniform(44, 62)
    place(out, 1.4 * modes(0.6, [f, f * 1.8, f * 2.9, f * 4.4], [0.2, 0.11, 0.06, 0.03], [1, 0.6, 0.35, 0.2], r, glide=-0.2), 0.0)
    place(out, 0.9 * lp(white(0.12, r), 2200, 2) * decay(n_of(0.12), 0.035, 0.001), 0.0)
    place(out, 0.9 * bubbles(0.4, 140, r, 180, 2400, early=2.2), 0.01)
    place(out, 0.6 * bubbles(dur - 0.3, 120 * (dur - 0.3), r, 150, 1300, early=1.6), 0.25)
    n = n_of(dur * 0.75)
    base = r.uniform(86, 100)
    f0 = glide(n, (0, base), (1, base * 0.62))
    v = voice(f0, vowel_path(n, 0.95, (0, "a"), (0.5, "o"), (1, "m")), r, tilt=1.2, breath=0.1) * fry_gate(f0, glide(n, (0, 0.0), (1, 0.6)))
    place(out, 0.4 * norm(v * hann_env(n, 0.05, dur * 0.4), 1.0), 0.1)
    return punch(hp(out, 32, 2), 2.0, 0.9)


def corpse_land(mat, seed, size=1.0):
    """사체가 땅에 떨어지는 '철퍽'. 몸의 낮은 쿵 + 살 찰싹 + 땅 재질"""
    r = R(seed)
    out = np.zeros(n_of(0.5), np.float32)
    f = r.uniform(58, 92) / size
    hard = {"dry": 1.0, "mud": 0.75, "ash": 0.6, "pad": 1.15}[mat]
    place(out, 1.2 * hard * modes(0.3, [f, f * 2.1, f * 3.4], [0.09, 0.05, 0.025], [1, 0.6, 0.3], r, glide=-0.2), 0.0)
    slap_hi = {"dry": 2600, "mud": 1800, "ash": 1100, "pad": 4200}[mat]
    place(out, 0.95 * hard * bp(white(0.07, r), 260, slap_hi, 2) * decay(n_of(0.07), 0.018, 0.0006), 0.0)
    place(out, 0.45 * bubbles(0.1, 14, r, 700, 3600, early=2.2), 0.005)
    place(out, 0.9 * norm(_ground(mat, r, 1.7 * size, ring=0.14), 1.0), 0.003)
    return punch(hp(out, 40, 2), 2.2, 0.9)


def corpse_pile(seed):
    """사체가 사체 위에 얹히는 소리: 살과 살이 붙는 '쩍', 눌리며 새어 나오는 방울"""
    r = R(seed)
    out = np.zeros(n_of(0.42), np.float32)
    place(out, 0.9 * modes(0.16, [r.uniform(105, 150), r.uniform(190, 250)], [0.04, 0.025], [1, 0.6], r, glide=-0.15), 0.0)
    place(out, 0.8 * lp(white(0.06, r), 1500, 2) * decay(n_of(0.06), 0.016, 0.0012), 0.0)
    place(out, 0.9 * bubbles(0.1, 40, r, 400, 3000, early=2.4), 0.004)
    place(out, 0.6 * bubbles(0.2, 26, r, 500, 2600, early=0.8, rise=0.25), 0.1)
    return punch(hp(out, 70, 2), 2.0, 0.85)


def rod_clatter(mat, seed):
    """엔드 막대기가 땅에 떨어져 튀는 '딸그락'. 재질에 따라 진흙은 한 번 '툭', 발사장은 맑게 오래"""
    r = R(seed)
    out = np.zeros(n_of(0.6), np.float32)
    f = r.uniform(900, 1500)
    ring = {"dry": 1.0, "mud": 0.35, "ash": 0.6, "pad": 2.2}[mat]
    hits = {"dry": 4, "mud": 1, "ash": 2, "pad": 5}[mat]
    t, gap, a = 0.0, r.uniform(0.07, 0.11), 1.0
    for _ in range(hits):
        k = r.uniform(0.96, 1.04)
        m = modes(0.12, [f * k, f * 2.76 * k, f * 5.4 * k, f * 8.93 * k], [0.022 * ring, 0.014 * ring, 0.008 * ring, 0.005 * ring],
                  [1, 0.7, 0.45, 0.25], r)
        place(out, a * m, t)
        place(out, a * 0.5 * norm(_ground(mat, r, 0.35, ring=0.3), 1.0), t)
        t += gap
        gap *= r.uniform(0.55, 0.7)
        a *= 0.62
    return norm(hp(out, 300 if mat != "mud" else 120, 2), 0.85)


def corpse_sink(seed):
    """사체가 땅으로 꺼지는 소리(1초): 낮게 빨려 들어가는 '쭈우욱'"""
    r = R(seed)
    dur = 1.0
    n = n_of(dur)
    w = white(dur, r)
    out = np.zeros(n, np.float32)
    t = t_of(n) / dur
    for a, b, lo, hi in ((0.0, 0.45, 500, 1300), (0.3, 0.75, 300, 800), (0.6, 1.0, 160, 450)):
        out += bp(w, lo, hi, 2) * np.clip(np.minimum((t - a) / 0.12, (b - t) / 0.12), 0, 1)
    out *= hann_env(n, 0.25, 0.3) * (0.6 + 0.4 * np.sin(2 * np.pi * 19 * t_of(n)) ** 2)
    out += 0.8 * bubbles(dur, 60, r, 200, 1500, early=0.7, rise=0.2) * hann_env(n, 0.2, 0.2)
    place(out, 0.5 * bubbles(0.06, 10, r, 300, 1200, early=0.5, rise=0.4), dur * 0.86)
    return norm(hp(out, 60, 2), 0.7)


# ================================================================== 먼 저음 층

def narrow(dur, f, width, r):
    """좁은 대역 잡음(가운데 f Hz, 폭 width Hz): 높이는 느껴지지만 순음처럼 매끈하지 않고 느리게 일렁인다(낮은 순음은 전기 험처럼 들림)"""
    n = n_of(dur)
    m = 1 << int(np.ceil(np.log2(max(2, n))))
    fr = np.fft.rfftfreq(m, 1 / SR)
    x = np.fft.irfft(np.fft.rfft(r.randn(m)) * np.exp(-0.5 * ((fr - f) / (width / 2.355)) ** 2), m)[:n]
    return (x / (np.abs(x).max() + 1e-12)).astype(np.float32)


def _harmonics(x, lo=110.0, hi=380.0, drive=4.0, amount=0.35):
    """작은 스피커와 이어폰용 배음 층: 아주 낮은 음을 일그러뜨려 생긴 2~6배음만 얇게 더한다(없어진 바탕음을 귀가 메움)"""
    p = np.abs(x).max() + 1e-9
    y = np.tanh(drive * x / p) + 0.5 * np.abs(np.tanh(drive * x / p))  # 홀수, 짝수 배음
    y = bp(y.astype(np.float32), lo, hi, 3)
    y *= np.sqrt(np.mean(x ** 2)) / (np.sqrt(np.mean(y ** 2)) + 1e-9)
    return (x + amount * y).astype(np.float32)


def _throat_choir(dur, base, r, voices=6, contour=((0, 1.05), (0.25, 1.0), (0.8, 0.97), (1, 0.9)), form=(150, 250), tilt=1.6):
    """아주 낮은 목소리 여럿이 조금씩 어긋나 겹친 소리(멀리서 떼가 우는 소리의 바탕)"""
    n = n_of(dur)
    out = np.zeros(n, np.float32)
    t = t_of(n)
    for _ in range(voices):
        det = 1 + r.uniform(-0.017, 0.017)
        drift = 1 + 0.008 * lp(r.randn(n).astype(np.float32), 0.4, 1) * 60
        f0 = base * det * glide(n, *contour) * drift
        ph = 2 * np.pi * np.cumsum(f0) / SR
        fc = form[0] + (form[1] - form[0]) * (0.5 + 0.5 * np.sin(2 * np.pi * r.uniform(0.08, 0.2) * t + r.rand() * 6.28))
        x = np.zeros(n, np.float32)
        for k in range(1, int(min(12, 460 / base)) + 1):
            x += k ** -tilt * (1 + 1.6 / np.sqrt(1 + ((k * f0 - fc) / 45.0) ** 2)) * np.sin(k * ph + r.rand() * 6.28)
        out += x * (0.75 + 0.25 * np.sin(2 * np.pi * r.uniform(0.25, 0.6) * t + r.rand() * 6.28)) * r.uniform(0.7, 1.0)
    return out / voices


def far_call(seed, base=None, harm=None):
    """먼 부름(11~13초, mono, 방향 있음): 지평선 너머 떼가 한 목소리처럼 길게 내는 낮은 울음. 느리게 들어와 한숨처럼 내려가며 꺼진다"""
    r = R(seed)
    dur = r.uniform(7.0, 8.5)
    base = base or r.choice([58.0, 62.0, 66.0, 70.0])
    n = n_of(dur)
    x = _throat_choir(dur, base, r, voices=6)
    purr = lp(brown(dur, r), 170, 3) * (0.8 + 0.2 * np.sin(2 * np.pi * r.uniform(6, 9) * t_of(n)))
    x = x / (np.abs(x).max() + 1e-9) + 0.22 * purr / (np.abs(purr).max() + 1e-9)
    x *= hann_env(n, 2.6, 3.2)
    x = lp(_harmonics(x, amount=HARM if harm is None else harm), 420, 4)
    x = reverb(x, 3.5, r, lp_hz=480, pre=0.05, wet=0.6, build=0.25)
    return norm(hp(x, 28, 3), 0.7)


def far_answer(seed, base=None, harm=None):
    """대답(7~9초, mono, 다른 쪽에서): 부름보다 조금 높고 짧다. 끝이 살짝 올라간다(묻는 것처럼)"""
    r = R(seed)
    dur = r.uniform(3.2, 4.2)
    base = base or r.choice([74.0, 78.0, 84.0])
    n = n_of(dur)
    x = _throat_choir(dur, base, r, voices=4, contour=((0, 0.97), (0.5, 1.0), (0.85, 1.0), (1, 1.07)), form=(170, 290))
    x = x / (np.abs(x).max() + 1e-9)
    x *= hann_env(n, 1.4, 1.6)
    x = lp(_harmonics(x, 130, 420, amount=HARM if harm is None else harm), 480, 4)
    x = reverb(x, 2.8, r, lp_hz=520, pre=0.05, wet=0.6, build=0.2)
    return norm(hp(x, 32, 3), 0.7)


def _hum_layers(r, seg_len, ratio, layers=3):
    x = src(r.choice(["oga_humming/ghostly_humming#2", "oga_humming/ghostly_humming#8", "oga_humming/ghostly_humming#11"]))
    out = None
    for i in range(layers):
        a = n_of(r.uniform(0.3, max(0.35, len(x) / SR - seg_len - 1.7)))
        y = pitch(x[a:a + n_of(seg_len)], ratio * (1 + r.uniform(-0.02, 0.02)))
        off = n_of(r.uniform(0.0, 0.8)) if i else 0
        if out is None:
            out = np.zeros(len(y) + n_of(1.0), np.float32)
        out[off:off + len(y)] += y[:len(out) - off] * r.uniform(0.7, 1.0)
    return out


def rec_far_call(seed):
    """먼 부름(받은 허밍): 사람의 허밍 3초를 세 겹으로 겹쳐 두 옥타브 가까이 낮춘다(목이 거대한 것처럼). 느리게 들어와 길게 꺼진다"""
    r = R(seed)
    x = _hum_layers(r, r.uniform(2.6, 3.1), r.uniform(0.31, 0.36))
    x *= hann_env(len(x), 2.6, 3.0)
    x = lp(_harmonics(x, 120, 400, amount=0.3), 440, 4)
    x = reverb(x, 3.5, r, lp_hz=480, pre=0.05, wet=0.6, build=0.25)
    return norm(hp(x, 28, 3), 0.7)


def rec_far_answer(seed):
    """대답(받은 허밍): 조금 높고 짧게"""
    r = R(seed)
    x = _hum_layers(r, r.uniform(1.5, 1.9), r.uniform(0.39, 0.44), layers=2)
    x *= hann_env(len(x), 1.3, 1.6)
    x = lp(_harmonics(x, 140, 440, amount=0.3), 500, 4)
    x = reverb(x, 2.8, r, lp_hz=520, pre=0.05, wet=0.6, build=0.2)
    return norm(hp(x, 32, 3), 0.7)


def far_swell(seed):
    """땅 밑 울림(10~11초, stereo, 방향 없음): 아주 낮은 잡음과 음 두셋이 천천히 부풀었다 꺼진다. 좌우가 서로 달라 사방에서 감싸는 듯하다"""
    r = R(seed)
    dur = r.uniform(10.0, 11.5)
    n = n_of(dur)
    chans = []
    f1 = r.uniform(40, 48)
    for _ in range(2):
        x = 1.0 * lp(brown(dur, r), 85, 4)
        for f, a in ((f1, 0.9), (f1 * r.uniform(1.31, 1.37), 0.6), (f1 * r.uniform(1.95, 2.05), 0.4)):
            x += a * 0.5 * narrow(dur, f, 2.5, r)
        roll = bp(brown(dur, r), 95, 250, 3) * np.clip(lp(np.abs(r.randn(n)).astype(np.float32), 3.0, 1) * 25 - 0.9, 0, 3)
        x = x / (np.abs(x).max() + 1e-9) + 0.2 * roll / (np.abs(roll).max() + 1e-9)
        chans.append(hp(lp(_harmonics(x, 100, 280, amount=0.25), 300, 4), 26, 3) * hann_env(n, 4.2, 5.0))
    return norm(stereo(chans[0], chans[1]), 0.7)


def far_breath(seed):
    """지평선의 숨(쉬는 시간용, 14~16초, stereo): 아주 느린 들숨과 날숨 두 번. 바람 같지만 박자가 숨이다"""
    r = R(seed)
    cyc = r.uniform(6.8, 7.8)
    dur = cyc * 2 + 0.5
    n = n_of(dur)
    t = t_of(n)
    chans = []
    for _ in range(2):
        x = np.zeros(n, np.float32)
        for i in range(2):
            t0 = i * cyc + 0.2
            for start, length, lo, hi, a in ((t0, cyc * 0.4, 110, 260, 0.8), (t0 + cyc * 0.44, cyc * 0.5, 70, 190, 1.0)):
                m = n_of(length)
                seg = bp(pink(length, r), lo, hi, 3) * np.sin(np.pi * np.arange(m) / m) ** 1.6
                place(x, a * seg / (np.abs(seg).max() + 1e-9), start)
        hum = narrow(dur, r.uniform(52, 60), 3.0, r)[:n] * (0.5 + 0.5 * np.sin(2 * np.pi * t / cyc - 1.2)) ** 2
        x = x + 0.35 * hum
        chans.append(hp(lp(_harmonics(x.astype(np.float32), 120, 300, amount=0.2), 320, 4), 30, 3) * hann_env(n, 1.5, 2.5))
    return norm(stereo(chans[0], chans[1]), 0.6)


def far_giant(seed):
    """보스 앞(16초, mono): 거대한 것 하나의 울음. 천천히 올랐다 길게 내려가고 몸 떨림이 실린다. 어떤 스피커로도 들리게 배음을 두껍게"""
    r = R(seed)
    dur = r.uniform(10.5, 12.0)
    n = n_of(dur)
    t = t_of(n)
    x = _throat_choir(dur, r.uniform(37, 41), r, voices=3, contour=((0, 0.94), (0.3, 1.22), (0.55, 1.1), (1, 0.82)), form=(140, 210), tilt=1.25)
    x *= 1 - 0.3 * (0.5 + 0.5 * np.sin(2 * np.pi * 4.1 * t))  # 몸 떨림
    rough = lp(brown(dur, r), 140, 3)
    x = x / (np.abs(x).max() + 1e-9) + 0.3 * rough / (np.abs(rough).max() + 1e-9)
    x *= hann_env(n, 3.0, 4.5)
    x = lp(_harmonics(x, 90, 420, amount=0.5), 520, 4)
    x = reverb(x, 5.0, r, lp_hz=520, pre=0.06, wet=0.55, build=0.3)
    return norm(hp(x, 24, 3), 0.75)


# ================================================================== 사건 표

def V(fn, *seeds):
    """변형들: fn(씨앗) 을 씨앗마다"""
    return [(fn, s) for s in seeds]


def E(sub, att, variants, level, tilt=0.0, short=True, stereo=False):
    """사건 하나: 자막(None 이면 자막 없음), 들리는 거리(attenuation_distance = Java 가 보내는 반경), 변형, 크기(LUFS),
    1.5 kHz 위 선반(dB), short(가장 큰 0.4초로 맞춤, 아니면 통합), stereo(방향 없는 먼 소리)"""
    return dict(sub=sub, att=att, variants=variants, level=level, tilt=tilt, short=short, stereo=stereo)


# 가까운 공용 사건 gave:gabe.<이름>(기존 소리보다 작게: 울음은 기존 중앙 -15.8 보다 2~4 dB, 이동은 기존 중앙 -19.3 보다 아래)
NEAR = {
    "cry_small": E("킥킥", 16, V(cry_small, 11, 12, 13, 14) + V(rec_cry_small, 21, 22, 23, 24), -19, tilt=2),
    "cry_mid": E("젖은 숨소리", 20, V(cry_mid, 31, 32, 33, 34) + V(rec_cry_mid, 41, 42, 43, 44), -18, tilt=2),
    "cry_big": E("겹친 낮은 울음", 28, V(cry_big, 51, 52, 53) + V(rec_cry_big, 61, 62, 63), -17),
    "cry_ground": E("목 긁는 소리", 16, V(cry_ground, 71, 72, 73, 74), -20, tilt=2),
    "move_flap": E("젖은 퍼덕", 16, V(flap, 81, 82, 83, 84, 85, 86), -22, tilt=3),
}
for _m in MATERIALS:
    _ko = MAT_KO[_m]
    NEAR[f"move_tick_{_m}"] = E(f"막대기 발소리({_ko})", 12, V(lambda s, m=_m: step(m, s, 0.4), 101, 102, 103), -24)
    NEAR[f"move_pat_{_m}"] = E(f"살 발소리({_ko})", 12, V(lambda s, m=_m: step(m, s, 1.0), 111, 112, 113), -22)
    NEAR[f"move_thud_{_m}"] = E(f"묵직한 발소리({_ko})", 24, V(lambda s, m=_m: step(m, s, 1.8), 121, 122), -20)
    NEAR[f"move_drag_{_m}"] = E(f"몸 끄는 소리({_ko})", 14, V(lambda s, m=_m: drag(m, s), 131, 132, 133), -22)
# 죽을 때의 살덩이 층: 파일은 온 크기, Java 가 기존 죽음 소리와 같이 음량 Ears.FLESH_VOLUME(0.3)으로 얹는다
NEAR["flesh_small"] = E("뽁", 16, V(lambda s: die(s, "small"), 151, 152, 153, 154), -18, tilt=2)
NEAR["flesh_mid"] = E("살덩이 터짐", 24, V(lambda s: die(s, "mid"), 161, 162, 163, 164, 165), -15, tilt=4)
NEAR["flesh_big"] = E("큰 살덩이 무너짐", 32, V(lambda s: die(s, "big"), 171, 172, 173), -14, tilt=3)
# 사체(래그돌, 전에는 소리가 없었음)
for _m in MATERIALS:
    _ko = MAT_KO[_m]
    NEAR[f"corpse_land_{_m}"] = E(f"사체 떨어짐({_ko})", 16, V(lambda s, m=_m: corpse_land(m, s), 181, 182, 183), -17, tilt=3)
    NEAR[f"corpse_rod_{_m}"] = E(f"막대기 딸그락({_ko})", 12, V(lambda s, m=_m: rod_clatter(m, s), 191, 192), -21)
NEAR["corpse_pile"] = E("사체 쌓임", 12, V(corpse_pile, 201, 202, 203, 204), -19, tilt=3)
NEAR["corpse_sink"] = E("사체 꺼짐", 10, V(corpse_sink, 211, 212), -23)

# 먼 저음 층 gave:far.<이름>(파일 -23 LUFS 통합, 22050 Hz, 스트림. 자막은 방향이 있는 부름, 대답, 거대한 것에만)
FAR = {
    "call": E("멀리서 낮은 울음", 128, V(far_call, 301, 302, 303, 304) + V(rec_far_call, 311, 312, 313), -23, short=False),
    "answer": E("멀리서 대답", 128, V(far_answer, 321, 322, 323) + V(rec_far_answer, 331, 332), -23, short=False),
    "swell": E(None, 16, V(far_swell, 341, 342, 343), -23, short=False, stereo=True),
    "breath": E(None, 16, V(far_breath, 351, 352), -23, short=False, stereo=True),
    "giant": E("거대한 것의 울음", 192, V(far_giant, 361, 362), -23, short=False),
}
FAR_SR = 22050  # 먼 저음은 500 Hz 아래뿐이라 낮은 표본율로 구워 크기를 줄인다


def master(x, e):
    """사건의 목표 크기로: 높은 쪽 선반(tilt dB, 1.5 kHz 위), 끝 다듬기, 크기 맞춤. 봉우리는 짧은 소리 -1, 먼 저음 -3 dBFS 아래"""
    if e["tilt"]:
        x = shelf(x, 1500, e["tilt"])
    return to_level(fade(x, 0.002, 0.015), e["level"], e["short"], -1.0 if e["short"] else -3.0)


def resample(x, sr):
    """44100 → sr(낮추기 전에 sr x 0.45 위를 거름)"""
    x = lp(x, sr * 0.45, 6)
    n = int(len(x) * sr / SR)
    idx = np.arange(n) * (SR / sr)
    i0 = np.minimum(idx.astype(np.int64), len(x) - 1)
    i1 = np.minimum(i0 + 1, len(x) - 1)
    f = (idx - i0).astype(np.float32)
    if x.ndim == 2:
        f = f[:, None]
    return (x[i0] * (1 - f) + x[i1] * f).astype(np.float32)


def check(table):
    """자가 점검 → 실패한 사건 이름 목록. 사건마다 첫 변형: 같은 씨앗 → 같은 소리, 숫자만 있음, 봉우리 -0.9 dBFS 아래, 0.05~20초,
    먼 저음(stereo 이거나 크기를 통합으로 맞춘 것)은 2~6 kHz 가 -70 dBFS 아래, 자막에 가운뎃점 없음"""
    bad = []
    for name, e in table.items():
        fn, seed = e["variants"][0]
        a, b = fn(seed), fn(seed)
        x = master(a, e)
        ok = np.array_equal(a, b) and np.isfinite(x).all() and peak_db(x) <= -0.9 and 0.05 < len(x) / SR < 20
        ok = ok and (e["short"] or high_db(x) < -70) and (x.ndim == 2) == e["stereo"] and chr(0xB7) not in (e["sub"] or "")
        if not ok:
            bad.append(name)
            print(f"  실패 {name}: 봉우리 {peak_db(x):.1f} dBFS, {len(x) / SR:.2f}초")
    return bad
