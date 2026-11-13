"""게이브 소리 도구(mono, SR=44100). 두 갈래:

1. 받은 소리(2026-11-12, 맨 아래 절): 내려받은 CC0, CC BY 효과음 원본(ref/sfx/<묶음>/, _info.json 에 주소, 라이선스, 날짜, sha256)과
   그 목록 data/sfx_lib.json(아이디 '<묶음>/<이름>', 파일, 태그, 길이, 크기). find(태그나 아이디) → prepare(자르기, 크기 맞춤) →
   ogg_bytes. 키트의 k.sample(...) 과 tools/make_director_sounds.py 가 이것을 쓴다. 목록 다시 만들기:
   python -X utf8 tools/gabe/audio.py lib        찾아보기: python -X utf8 tools/gabe/audio.py find tag:roar
2. numpy 합성(옛 방식, 권리 깨끗). kit.sound(..., synth=...) 가 ogg(libvorbis, imageio-ffmpeg 의 ffmpeg)로 굳힌다. 2026-11-11 사용자
   정정("합성하지 말고 어디서 다운해 와") 뒤로 새 소리는 받은 소리로 만든다. 이미 구운 종의 합성 소리는 그대로 둔다.
   모든 함수는 float32 mono 배열(-1..1)을 돌려준다. 씨앗을 받는 것은 같은 씨앗이면 같은 소리.
   조립 도구: tone, noise, env, lowpass, highpass, bandpass, mix, cat, silence, gain, fade.
   본보기: giggle(킥킥 파도), wet_flap(젖은 천 퍼덕), squish(찌그러짐), chitter(작은 떼 지저귐), thump(박치기).
"""
import fnmatch
import json
import os
import re
import subprocess
import tempfile
import wave
from pathlib import Path

import numpy as np

SR = 44100


def _t(dur):
    return np.arange(int(dur * SR), dtype=np.float32) / SR


def silence(dur):
    return np.zeros(int(dur * SR), np.float32)


def tone(freq, dur, shape="sine", vib=0.0, vib_rate=6.0, glide=None):
    """음. freq Hz, glide 가 있으면 끝에서 그 Hz 로 미끄러짐. shape: sine | saw | square | tri"""
    t = _t(dur)
    f = np.full_like(t, freq) if glide is None else np.linspace(freq, glide, len(t), dtype=np.float32)
    if vib:
        f = f * (1 + vib * np.sin(2 * np.pi * vib_rate * t))
    ph = 2 * np.pi * np.cumsum(f) / SR
    if shape == "saw":
        return (2 * ((ph / (2 * np.pi)) % 1.0) - 1).astype(np.float32)
    if shape == "square":
        return np.sign(np.sin(ph)).astype(np.float32)
    if shape == "tri":
        return (2 * np.abs(2 * ((ph / (2 * np.pi)) % 1.0) - 1) - 1).astype(np.float32)
    return np.sin(ph).astype(np.float32)


def noise(dur, seed=0):
    return (np.random.RandomState(seed).rand(int(dur * SR)).astype(np.float32) * 2 - 1)


def env(x, a=0.005, d=0.05, s=0.6, r=0.1):
    """ADSR 봉투(초). 길이는 x 그대로, 서 있는 구간 = 나머지"""
    n = len(x)
    A, D, R = int(a * SR), int(d * SR), int(r * SR)
    S = max(0, n - A - D - R)
    e = np.concatenate([np.linspace(0, 1, A, dtype=np.float32), np.linspace(1, s, D, dtype=np.float32),
                        np.full(S, s, np.float32), np.linspace(s, 0, R, dtype=np.float32)])
    e = np.pad(e, (0, max(0, n - len(e))))[:n]
    return x * e


def lowpass(x, fc, order=2):
    for _ in range(order):
        x = _fast_lp(x, fc)
    return x


def highpass(x, fc, order=2):
    for _ in range(order):
        x = x - _fast_lp(x, fc)
    return x


def bandpass(x, lo, hi):
    return lowpass(highpass(x, lo), hi)


def _fast_lp(x, fc):
    """한 극 저역 통과(scipy 없이, lfilter 대신 누적 근사): 짧은 소리라 파이썬 루프로도 빠름"""
    a = float(np.exp(-2 * np.pi * fc / SR))
    y = np.empty_like(x)
    acc = 0.0
    b = 1.0 - a
    for i, v in enumerate(x.tolist()):
        acc = b * v + a * acc
        y[i] = acc
    return y


def gain(x, g):
    return (x * g).astype(np.float32)


def fade(x, fin=0.003, fout=0.02):
    x = x.copy()
    a, b = int(fin * SR), int(fout * SR)
    if a:
        x[:a] *= np.linspace(0, 1, a, dtype=np.float32)
    if b:
        x[-b:] *= np.linspace(1, 0, b, dtype=np.float32)
    return x


def mix(*xs):
    n = max(len(x) for x in xs)
    out = np.zeros(n, np.float32)
    for x in xs:
        out[:len(x)] += x
    return out


def cat(*xs):
    return np.concatenate(xs).astype(np.float32)


def at(x, offset, total=None):
    """x 를 offset 초 뒤에 놓은 배열"""
    pad = np.zeros(int(offset * SR), np.float32)
    y = np.concatenate([pad, x])
    if total:
        y = np.pad(y, (0, max(0, int(total * SR) - len(y))))[:int(total * SR)]
    return y


def normalize(x, peak=0.89):
    m = float(np.max(np.abs(x))) if len(x) else 0.0
    return (x * (peak / m)).astype(np.float32) if m > 1e-6 else x


# ---------------------------------------------------------------------------------------------- 본보기 소리


def _vowel(freq, dur, formants=((700, 1.0), (1220, 0.5), (2600, 0.2)), seed=0):
    """목소리 비슷한 짧은 모음: 톱니 + 포먼트 대역 셋"""
    src = tone(freq, dur, "saw", vib=0.02, vib_rate=7) * 0.6 + noise(dur, seed) * 0.08
    out = np.zeros_like(src)
    for fc, g in formants:
        out += bandpass(src, fc * 0.8, fc * 1.25) * g
    return out


def giggle(seed=0, count=6, base=520.0, rate=9.0, dur=None):
    """킥킥(떼 종): 일정한 박자의 높은 '히' 소리 파도. 같은 박자로 겹쳐 기계처럼(도감 G02)"""
    rng = np.random.RandomState(seed)
    step = 1.0 / rate
    total = dur or (count * step + 0.25)
    parts = []
    for i in range(count):
        f = base * (1 + 0.04 * rng.randn()) * (1.0 - 0.03 * i)
        h = _vowel(f, step * 0.7, ((420, 0.9), (2300, 0.7), (3000, 0.25)), seed + i)
        h = env(h, 0.004, 0.03, 0.5, step * 0.35)
        parts.append(at(h, i * step, total))
    return normalize(fade(mix(*parts)), 0.8)


def wet_flap(seed=0, dur=0.32, low=180.0):
    """젖은 천 터는 '퍼덕': 낮은 쿵 + 거친 바람 + 물기 튀는 잔소리"""
    n = noise(dur, seed)
    body = env(lowpass(n, 900), 0.002, 0.06, 0.25, 0.18)
    thump = env(tone(low, 0.12, glide=low * 0.6), 0.001, 0.03, 0.2, 0.07) * 0.8
    drops = np.zeros(int(dur * SR), np.float32)
    rng = np.random.RandomState(seed + 7)
    for _ in range(9):
        o = rng.rand() * dur * 0.8
        d = env(highpass(noise(0.02, seed + _), 2500), 0.0005, 0.005, 0.2, 0.01) * (0.3 + 0.4 * rng.rand())
        drops = mix(drops, at(d, o, dur))
    return normalize(fade(mix(body, thump, drops)), 0.85)


def squish(seed=0, dur=0.28):
    """찌그러짐(젤리 박치기): 미끄러져 내려가는 낮은 음 + 젖은 잡음"""
    x = env(tone(240, dur, "tri", glide=90), 0.002, 0.05, 0.4, 0.15)
    w = env(bandpass(noise(dur, seed), 300, 1800), 0.002, 0.04, 0.3, 0.12)
    return normalize(fade(mix(x * 0.7, w * 0.6)), 0.85)


def chitter(seed=0, dur=0.5, rate=22.0, base=1800.0):
    """작은 떼 지저귐: 빠른 높은 딸깍"""
    rng = np.random.RandomState(seed)
    parts = []
    t = 0.0
    while t < dur - 0.03:
        c = env(tone(base * (0.8 + 0.4 * rng.rand()), 0.018, "square"), 0.0005, 0.004, 0.3, 0.01) * 0.4
        parts.append(at(c, t, dur))
        t += 1.0 / rate * (0.7 + 0.6 * rng.rand())
    return normalize(fade(mix(*parts)), 0.7)


def thump(seed=0, dur=0.22, freq=110.0):
    """박치기 쿵"""
    x = env(tone(freq, dur, glide=freq * 0.5), 0.001, 0.04, 0.3, 0.12)
    n = env(lowpass(noise(dur, seed), 600), 0.001, 0.02, 0.1, 0.05) * 0.5
    return normalize(fade(mix(x, n)), 0.9)


# ---------------------------------------------------------------------------------------------- ogg 로


def write_ogg(x, path, quality=5):
    """float32 mono → ogg(libvorbis, mono, 44100). 같은 입력이면 같은 파일"""
    import imageio_ffmpeg
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    pcm = (np.clip(x, -1, 1) * 32767).astype(np.int16)
    fd, tmp = tempfile.mkstemp(suffix=".wav")
    os.close(fd)
    try:
        with wave.open(tmp, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes(pcm.tobytes())
        r = subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-loglevel", "error", "-i", tmp, "-ac", "1", "-c:a", "libvorbis",
                            "-q:a", str(quality), "-fflags", "+bitexact", "-flags:a", "+bitexact", "-map_metadata", "-1", str(path)],
                           capture_output=True)
        if r.returncode:
            raise RuntimeError("ogg 굽기 실패: " + r.stderr.decode("utf-8", "replace")[-400:])
    finally:
        os.unlink(tmp)
    return path


# ============================================================================================== 받은 소리(2026-11-12)
# 내려받은 CC0, CC BY 효과음. 원본은 ref/sfx/<묶음>/ 에 받은 그대로(파일 이름만 소문자, _ 로), 묶음마다 _info.json(글 페이지, 받은 주소,
# 라이선스와 그 주소, 페이지에서 본 라이선스 글, 만든 사람, 받은 날, 원본과 파일의 sha256). 목록 data/sfx_lib.json 은 build_lib() 가
# 원본을 풀어 재고(길이, 크기) 파일 이름과 아래 표로 태그를 붙여 만든다. 받은 파일은 자료로만 다룬다(ffmpeg 로 풀기만).
# 아이디 = '<묶음>/<파일 이름에서 확장자 뺀 것>'. 찾기: find('kenney_impact/impactpunch_heavy_000'), find('tag:roar'),
# find('tag:roar+far'), find('oga_zombies/*'). 쓰기: prepare(아이디) → mono 배열(앞 무음 자름, 가장 큰 0.4초 크기를 맞춤) → ogg_bytes.

ROOT = Path(__file__).resolve().parents[2]
SFX_DIR = ROOT / "ref" / "sfx"
SFX_LIB = ROOT / "data" / "sfx_lib.json"
AUDIO_EXT = (".ogg", ".wav", ".mp3", ".flac")

# 태그 → 한국어(자막 기본값, 문서). 태그는 영어 소문자 한 낱말. 압도감 쪽(ovw12, 보스): far_roar, horde_cry, quake, colossus, boss_roar, horn,
# overwhelm(압도감 후보로 손으로 고른 것)
TAG_KO = {
    "overwhelm": "압도감 후보", "far_roar": "멀리서 울리는 포효", "horde_cry": "떼의 함성", "quake": "땅울림", "colossus": "거대한 것이 지나감",
    "boss_roar": "보스 포효", "roar": "포효", "growl": "으르렁", "snarl": "으르렁", "groan": "신음", "moan": "신음", "zombie": "신음",
    "monster": "괴물 소리", "scream": "비명", "howl": "울부짖음", "voice": "목소리", "human": "사람 목소리", "crowd": "웅성", "shout": "외침",
    "giggle": "킥킥", "laugh": "웃음", "evil": "음흉한 웃음", "cute": "작은 울음", "alien": "낯선 울음", "bug": "벌레 소리", "chitter": "딸깍",
    "breath": "숨소리", "scared": "겁먹은 숨", "cough": "기침", "burp": "트림", "spit": "퉤", "eat": "씹는 소리", "snore": "코골이",
    "hurt": "아파함", "die": "죽는 소리", "attack": "덤벼듦", "grunt": "끙", "bark": "짖음", "nose": "킁", "weird": "이상한 소리",
    "rumble": "우르릉", "low": "낮은 울림", "drone": "웅웅", "engine": "웅웅", "ambience": "음산한 울림", "loop": "되풀이",
    "horn": "뿔나팔", "war": "전쟁 뿔나팔", "thunder": "천둥", "rain": "빗소리", "storm": "폭풍", "wind": "바람", "far": "멀리서",
    "heartbeat": "심장 박동", "impact": "쿵", "hit": "퍽", "punch": "퍽", "heavy": "묵직함", "light": "가벼움", "soft": "툭",
    "explosion": "폭발", "boom": "쾅", "bang": "탕", "cannon": "대포", "firework": "폭죽", "shot": "탕", "metal": "쇳소리", "glass": "유리 소리",
    "wood": "나무 소리", "stone": "돌 소리", "plate": "쇠판", "bell": "종소리", "gong": "징", "tin": "깡통", "step": "발소리",
    "stomp": "쿵쿵", "giant": "거대한 발걸음", "slime": "질척", "wet": "철퍽", "bubble": "보글", "drip": "똑똑", "door": "문",
    "creak": "삐걱", "gate": "문 열림", "cloth": "펄럭", "flap": "퍼덕", "blade": "칼날", "flesh": "살", "bone": "뼈 부러짐",
    "crack": "부러짐", "machine": "기계음", "electronic": "전자음", "laser": "광선", "hum": "윙", "fire": "불길", "falling": "떨어짐",
    "breaking": "부서짐", "coin": "동전", "book": "책", "paper": "종이", "click": "딸깍", "jingle": "짧은 곡", "music": "음악",
    "splash": "첨벙", "spring": "용수철", "switch": "스위치", "tools": "연장", "noise": "잡음", "misc": "기타", "dishes": "그릇", "pot": "냄비",
    "slam": "쾅 닫힘", "plop": "퐁", "key": "열쇠", "microwave": "전자레인지", "toilet": "변기", "material": "바닥 재질",
    "ui": "화면 소리", "beep": "삐", "alarm": "경보음", "powerup": "힘이 솟음", "glitch": "지직", "scratch": "긁힘", "bird": "새떼",
    "boss": "보스",
    # 2026-11-12 원정(aud_trip): 트럭, 길, 발사장
    "truck": "트럭 엔진", "car": "자동차", "start": "시동", "drive": "달리는 소리", "tire": "타이어", "skid": "끼익", "carhorn": "경적",
    "siren": "사이렌", "rocket": "로켓", "launch": "발사", "generator": "발전기", "pump": "펌프", "steam": "김 빠지는 소리", "hiss": "쉬익",
    "crackle": "타닥타닥", "ignite": "점화", "radio": "무전 잡음", "electric": "전기 소리", "spark": "스파크", "powerdown": "전기 꺼짐",
    "whistle": "휘파람", "crash": "충돌", "hammer": "망치질", "sheet": "철판", "saw": "톱질", "terminal": "단말 소리", "teleport": "순간이동",
    "flow": "흐르는 물", "boil": "끓는 소리", "lock": "자물쇠",
    # 2026-11-12 aud_trip 이어받음: 보스.md 9절의 빈 소리(dawn_birds, chant_hum, choir_low, whisper_many)
    "dawn": "새벽 새소리", "chant": "허밍", "choir": "낮은 합창", "whisper": "속삭임",
}

# 묶음마다 기본 태그
PACK_TAGS = {
    "kenney_impact": ["impact"], "kenney_scifi": [], "kenney_rpg": [], "oga_creature80": ["monster"], "oga_creature80b": ["monster"],
    "oga_sfx100": [], "oga_breaking75": ["impact"], "oga_bang25": ["explosion"], "oga_monster": ["monster", "growl", "voice"],
    "oga_snarls": ["monster", "snarl", "growl"], "oga_moans": ["zombie", "moan", "groan", "voice"], "oga_ghoul": ["zombie", "groan", "voice"],
    "oga_zombies": ["zombie", "groan", "voice"], "oga_zomby": ["zombie", "groan", "voice"], "oga_quake": ["quake", "rumble", "low"],
    "oga_rumble": ["rumble", "low", "quake"], "oga_warhorns": ["horn", "war", "far"], "oga_horn": ["horn", "low", "boom"],
    "oga_wind_loop": ["wind", "loop", "ambience"], "oga_wind": ["wind", "ambience"], "oga_thunder": ["thunder", "rain", "storm", "far"],
    "oga_heartbeat": ["heartbeat", "low"], "oga_heartbeat1": ["heartbeat", "low"], "oga_crowd": ["crowd", "shout", "voice", "horde_cry"],
    "oga_stomp": ["stomp", "step", "heavy", "metal"], "oga_giggle": ["giggle", "laugh", "crowd", "voice"], "oga_laughs": ["laugh", "evil", "voice"],
    "oga_cackle": ["laugh", "evil", "voice"], "oga_laughter": ["laugh", "evil", "voice"], "lrsf_horror": [],
    "kenney_digital": ["electronic"], "kenney_interface": ["ui"],
}

# 2026-11-12 원정(aud_trip)에서 더 받은 묶음: 기본 태그. 이름 낱말 태그는 TRIP_WORD_TAGS(이 묶음들에만 적용해 옛 묶음 항목이 안 바뀌게)
TRIP_PACK_TAGS = {
    "oga_loops30": ["loop"], "oga_scifi50": [], "oga_water40": ["wet"], "oga_metalwood100": ["impact"],
    "oga_rain4": ["rain", "loop", "ambience"], "oga_storm_siren": ["siren", "storm", "wind", "alarm", "far"], "oga_sirens": ["siren", "alarm"],
    "oga_car_start": ["engine", "car", "start"], "oga_car_start2": ["engine", "car", "start"], "oga_car_door": ["door", "car", "metal"],
    "oga_car_run": ["engine", "car", "drive", "loop"], "oga_car_horn": ["carhorn", "car"],
    "oga_rocket_engine": ["rocket", "engine", "rumble", "low", "loop"], "oga_steam": ["steam", "hiss"],
    "oga_boiler": ["generator", "machine", "loop"], "oga_generator": ["generator", "machine", "loop", "hum"],
    "oga_fireplace": ["fire", "crackle", "loop"], "oga_fire_crackle": ["fire", "crackle"], "oga_static": ["radio", "noise"],
    "oga_radio_noise": ["radio", "noise", "loop"], "oga_breaker": ["switch", "electric", "click"], "oga_poweroff": ["machine", "powerdown"],
    "oga_kart": ["car", "misc"], "oga_flare": ["fire", "ignite"], "oga_distant_boom": ["explosion", "boom", "far", "low"],
    "oga_missile": ["rocket", "launch"], "oga_electric": ["electric"], "oga_metal27": ["metal", "impact"],
    "oga_metal_impact": ["metal", "impact"], "oga_crash": ["crash", "impact", "metal"], "oga_dynamite": ["explosion", "boom"],
    "oga_engine_loops": ["engine", "car", "loop"], "oga_engine2": ["engine", "car"],
    "oga_engine_heavy": ["engine", "truck", "loop", "heavy"], "oga_tire_skid": ["tire", "skid", "loop", "car"],
    "oga_rocket_launch": ["rocket", "launch"], "oga_jet_takeoff": ["rocket", "launch", "engine"],
    "oga_thunder_close": ["thunder", "rain", "storm"], "oga_slide_whistle": ["whistle", "falling"],
    "oga_engine_hum": ["engine", "car", "drive", "loop"], "oga_facility_siren": ["siren", "alarm"],
    # 보스.md 9절(새벽 새소리, 말 없는 허밍, 많은 속삭임), 모두 CC0
    "oga_birds": ["bird", "dawn", "ambience"], "oga_birds2": ["bird", "dawn"], "oga_morning": ["bird", "dawn", "ambience", "loop"],
    "oga_humming": ["chant", "choir", "voice", "low"], "oga_madness": ["whisper", "voice", "crowd"],
}
PACK_TAGS.update(TRIP_PACK_TAGS)
TRIP_WORD_TAGS = [
    (r"^alarm", ["alarm"]), (r"^ambient|loop_ambient", ["ambience", "drone"]), (r"^machine|loop_machine", ["machine"]), (r"^noise", ["noise"]),
    (r"^pump", ["pump", "machine"]), (r"^rain|loop_rain", ["rain"]), (r"rolling", ["rumble"]), (r"^saw", ["saw", "tools"]),
    (r"water_boiling", ["boil", "bubble", "wet"]), (r"water_flowing|loop_water", ["flow", "wet"]), (r"^weird", ["weird"]),
    (r"^beep|retro_beep", ["beep"]), (r"explosion|deathboom", ["explosion", "boom"]), (r"^rocket", ["rocket"]),
    (r"^shoot|retro_laser", ["laser"]), (r"^teleport", ["teleport"]), (r"^terminal", ["terminal", "beep"]),
    (r"^bubble|loop_bubbles", ["bubble", "wet"]), (r"^slime", ["slime"]), (r"^splash", ["splash"]),
    (r"hammer", ["hammer", "tools"]), (r"^keys", ["key"]), (r"lock_open", ["lock", "click"]), (r"^metal_", ["metal"]),
    (r"metal_sheet|^wobble", ["sheet", "metal"]), (r"metal_hit|dull_metal|rapid_clangs|^clink|^bing|^bong|^thud", ["hit"]), (r"slam", ["slam"]),
    (r"spring", ["spring"]), (r"^tools", ["tools"]), (r"^wood", ["wood"]), (r"breaking|cracking", ["breaking"]), (r"falling", ["falling"]),
    (r"^door|_door|door_", ["door"]), (r"powerdown|switch_off", ["powerdown"]), (r"powerup|^charge|recharge|turbo|switch_on", ["powerup"]),
    (r"crackleelectricity|snaploop|^shield", ["spark"]), (r"metal_grit", ["scratch"]), (r"^misc", ["misc"]),
]

# 파일 이름 낱말 → 태그(앞에서부터 모두 봄)
WORD_TAGS = [
    (r"footstep", ["step"]), (r"carpet|concrete|grass|snow", ["material"]), (r"impactbell", ["bell", "metal"]),
    (r"impactglass", ["glass"]), (r"impactmetal|impacttin|impactplate", ["metal"]), (r"impactmining", ["stone"]),
    (r"impactplank|impactwood", ["wood"]), (r"impactpunch", ["punch", "hit", "flesh"]), (r"impactsoft", ["soft", "hit"]),
    (r"impactgeneric", ["hit"]), (r"_heavy", ["heavy"]), (r"_light", ["light"]), (r"computernoise", ["electronic", "noise"]),
    (r"doorclose|dooropen|door_", ["door"]), (r"enginecircular", ["engine", "drone", "loop"]), (r"explosioncrunch", ["explosion", "boom"]),
    (r"forcefield", ["electronic", "hum"]), (r"laser", ["laser", "electronic"]), (r"lowfrequency_explosion", ["explosion", "boom", "low", "quake"]),
    (r"^slime|_slime", ["slime", "wet"]), (r"spaceengine", ["engine", "drone", "low", "loop"]), (r"thrusterfire", ["fire", "engine", "rumble"]),
    (r"belthandle|handlesmallleather|dropleather|cloth", ["cloth", "flap"]), (r"book", ["book"]), (r"^chop", ["blade", "wood"]),
    (r"creak", ["creak", "wood"]), (r"drawknife|knifeslice|stab_knife", ["blade"]), (r"knifeslice|stab_knife", ["flesh"]),
    (r"handlecoins", ["coin"]), (r"metalclick|metallatch", ["metal", "click"]), (r"metalpot", ["metal", "pot"]),
    (r"^alien", ["alien", "voice"]), (r"^barking", ["bark"]), (r"^breath", ["breath"]), (r"^bug", ["bug", "chitter"]),
    (r"^burble", ["wet", "bubble"]), (r"^burp", ["burp", "wet"]), (r"^cough", ["cough", "voice"]), (r"^cute", ["cute", "voice"]),
    (r"^eat", ["eat", "wet"]), (r"^grunt", ["grunt", "voice"]), (r"^howl", ["howl", "far"]), (r"^hurt", ["hurt", "voice"]),
    (r"^misc", ["misc"]), (r"^monster", ["monster", "growl"]), (r"^nose", ["nose"]), (r"^ooh", ["voice"]), (r"^roar", ["roar", "growl"]),
    (r"^scream|scream_", ["scream", "voice"]), (r"^snore", ["snore"]), (r"^spit", ["spit", "wet"]), (r"^troll", ["monster", "grunt", "heavy"]),
    (r"^weird", ["weird"]), (r"^attack", ["attack", "voice"]), (r"^die", ["die", "voice"]), (r"^human", ["human", "voice"]),
    (r"^stomp", ["stomp", "step", "heavy"]), (r"^bell", ["bell", "metal"]), (r"^dishes", ["dishes"]), (r"^explosion", ["explosion", "boom"]),
    (r"^glass", ["glass"]), (r"^gong", ["gong", "metal", "bell"]), (r"^hit", ["hit"]), (r"^key", ["key", "metal"]), (r"^machine", ["machine"]),
    (r"^metal", ["metal"]), (r"microwave", ["microwave"]), (r"^noise", ["noise"]), (r"^other", ["misc"]), (r"^paper", ["paper"]),
    (r"^plop", ["plop", "wet"]), (r"^pot", ["pot", "metal"]), (r"^shot", ["shot", "bang"]), (r"^slam", ["slam", "door", "heavy"]),
    (r"^splash", ["splash", "wet"]), (r"^spring", ["spring"]), (r"^switch", ["switch", "click"]), (r"^toilet", ["toilet", "wet"]),
    (r"^tools", ["tools", "metal"]), (r"^wooded_box|^wooden", ["wood"]), (r"breaking", ["breaking"]), (r"falling", ["falling"]),
    (r"bfh1_hit|_hit_", ["hit"]), (r"bfh1_rock", ["stone"]), (r"bfh1_wood", ["wood"]), (r"bfh1_metal", ["metal"]), (r"bfh1_glass", ["glass"]),
    (r"^bang", ["bang", "explosion"]), (r"^cannon", ["cannon", "boom", "explosion"]), (r"^fw", ["firework", "explosion"]),
    (r"loop", ["loop"]), (r"^ambience", ["ambience", "drone", "loop"]), (r"hell|monstersbelly", ["low"]), (r"murderofcrows", ["flap", "bird"]),
    (r"blood_drip", ["drip", "wet"]), (r"^bonecrack", ["bone", "crack", "flesh"]), (r"breath_scared", ["breath", "scared", "voice"]),
    (r"evil_machine", ["machine", "drone"]), (r"gate_open", ["gate", "creak", "metal"]), (r"^jingle", ["jingle", "music"]),
    (r"laugh_evil|^laugh", ["laugh", "evil", "voice"]), (r"^zombie", ["zombie", "groan", "voice"]),
    (r"wind", ["wind"]), (r"theircoming", ["horn", "boss"]),
    (r"^phaser|^phasejump|^zap", ["laser"]), (r"^powerup", ["powerup"]), (r"tone", ["beep", "alarm"]), (r"^pepsound|^high|^low", ["beep"]),
    (r"^spacetrash", ["metal", "falling"]), (r"^error", ["beep", "alarm"]), (r"^glitch", ["glitch", "electronic"]), (r"^bong", ["bell"]),
    (r"^back|^close|^confirmation|^maximize|^minimize|^open|^question|^select|^toggle|^tick|^scroll", ["click"]),
    (r"^click|^switch_|^pluck", ["click"]), (r"^drop_", ["plop"]), (r"^scratch", ["scratch"]),
]

# 손으로 고른 것(이름과 길이, 크기, 저음 몫을 보고 고름): 아이디 glob → 더할 태그. 압도감 후보는 overwhelm 을 함께.
PICKS = [
    ("oga_creature80/roar_*", ["far_roar", "boss_roar", "overwhelm"]), ("oga_creature80b/roar_*", ["far_roar", "boss_roar", "overwhelm"]),
    ("oga_creature80/troll_*", ["far_roar", "overwhelm"]), ("oga_creature80/howl", ["far_roar", "overwhelm"]),
    ("oga_monster/*", ["far_roar", "overwhelm"]), ("lrsf_horror/monster_*", ["far_roar", "boss_roar", "overwhelm"]),
    ("oga_snarls/*", ["horde_cry", "overwhelm"]), ("oga_zombies/*", ["horde_cry"]), ("oga_moans/*", ["horde_cry", "overwhelm"]),
    ("oga_ghoul/*", ["horde_cry"]), ("lrsf_horror/zombie_*", ["horde_cry"]), ("oga_crowd/*", ["overwhelm"]),
    ("oga_quake/*", ["overwhelm"]), ("oga_rumble/*", ["overwhelm"]), ("kenney_scifi/lowfrequency_explosion_*", ["overwhelm"]),
    ("kenney_scifi/spaceenginelarge_*", ["colossus", "overwhelm"]), ("kenney_scifi/spaceenginelow_*", ["colossus", "quake", "overwhelm"]),
    ("kenney_scifi/enginecircular_*", ["colossus"]), ("kenney_scifi/thrusterfire_*", ["colossus"]),
    ("oga_stomp/*", ["giant", "colossus", "overwhelm"]), ("oga_creature80b/stomp_01", ["giant", "colossus"]),
    ("kenney_impact/impactpunch_heavy_*", ["giant"]), ("kenney_impact/impactsoft_heavy_*", ["giant"]),
    ("oga_warhorns/*", ["overwhelm"]), ("oga_horn/*", ["overwhelm", "boss_roar"]), ("oga_thunder/*", ["overwhelm"]),
    ("lrsf_horror/ambience_hell_*", ["overwhelm"]), ("lrsf_horror/ambience_monstersbelly_00", ["overwhelm", "colossus"]),
    ("oga_bang25/cannon_*", ["far", "boom"]), ("oga_sfx100/gong_*", ["boom", "low"]), ("oga_heartbeat/*", ["overwhelm"]),
]


def ffmpeg_exe():
    import imageio_ffmpeg
    return imageio_ffmpeg.get_ffmpeg_exe()


def probe(path):
    """소리 파일 → {codec, sr, ch, dur}(ffmpeg -i 의 알림 글에서 읽음)"""
    r = subprocess.run([ffmpeg_exe(), "-hide_banner", "-i", str(path)], capture_output=True)
    t = r.stderr.decode("utf-8", "replace")
    m = re.search(r"Audio: (\w+)[^,]*, (\d+) Hz, ([^,]+),", t)
    d = re.search(r"Duration: (\d+):(\d+):([\d.]+)", t)
    ch = m.group(3).strip() if m else ""
    return {"codec": m.group(1) if m else "", "sr": int(m.group(2)) if m else 0,
            "ch": 1 if ch == "mono" else 2 if ch == "stereo" else int(re.sub(r"\D", "", ch) or 0),
            "dur": round(int(d.group(1)) * 3600 + int(d.group(2)) * 60 + float(d.group(3)), 3) if d else 0.0}


def load(path, sr=SR, mono=True):
    """소리 파일 → float32 배열(mono 면 (n,), 아니면 (n, 2)). 받은 파일은 이렇게 풀기만 한다"""
    ch = 1 if mono else 2
    r = subprocess.run([ffmpeg_exe(), "-loglevel", "error", "-i", str(path), "-ac", str(ch), "-ar", str(sr), "-f", "f32le", "-"],
                       capture_output=True)
    if r.returncode:
        raise RuntimeError(f"풀기 실패 {path}: " + r.stderr.decode("utf-8", "replace")[-300:])
    x = np.frombuffer(r.stdout, np.float32).copy()
    return x if mono else x.reshape(-1, 2)


_LIB = None


def lib(reload=False):
    """data/sfx_lib.json(없으면 오류: python -X utf8 tools/gabe/audio.py lib)"""
    global _LIB
    if _LIB is None or reload:
        if not SFX_LIB.is_file():
            raise FileNotFoundError(f"{SFX_LIB} 없음: python -X utf8 tools/gabe/audio.py lib")
        _LIB = json.loads(SFX_LIB.read_text(encoding="utf-8"))
        _LIB["by_id"] = {e["id"]: e for e in _LIB["sounds"]}
    return _LIB


def entry(sid):
    sid = split_ref(sid)[0]
    e = lib()["by_id"].get(sid)
    if e is None:
        raise KeyError(f"받은 소리 {sid!r} 가 data/sfx_lib.json 에 없음(찾기: python -X utf8 tools/gabe/audio.py find {sid.split('/')[0]}/*)")
    return e


def parts_of(x, sr, top_db=30.0, gap=0.18, min_len=0.25, limit=24):
    """긴 녹음 안의 소리 덩어리 [(시작, 끝) 초]: 가장 큰 곳보다 top_db 아래가 gap 초 넘게 이어지면 끊는다(목록 'parts', 아이디#n)"""
    win = int(0.01 * sr)
    r = _frame_rms(x, win)
    on = r > r.max() * 10 ** (-top_db / 20)
    out, i, n = [], 0, len(on)
    need = max(1, int(gap / 0.01))
    while i < n:
        if not on[i]:
            i += 1
            continue
        j, quiet = i, 0
        while j < n and quiet < need:
            quiet = 0 if on[j] else quiet + 1
            j += 1
        e = j - quiet
        if (e - i) * 0.01 >= min_len:
            out.append((round(i * 0.01, 3), round(e * 0.01, 3)))
        i = j
    return out[:limit]


def split_ref(ref):
    """'묶음/이름#2' → ('묶음/이름', 2), 번호가 없으면 None"""
    if "#" in ref:
        a, b = ref.rsplit("#", 1)
        return a, int(b)
    return ref, None


def find(query, limit=None, min_dur=0.0, max_dur=1e9):
    """아이디('묶음/이름'), glob('묶음/*'), 태그('tag:roar', 여럿은 'tag:roar+far', 빼기는 'tag:roar-boss')로 찾는다. 아이디 차례."""
    L = lib()["sounds"]
    if query.startswith("tag:"):
        want, drop = [], []
        for part in re.findall(r"[+-]?[a-z0-9_]+", query[4:]):
            (drop if part.startswith("-") else want).append(part.lstrip("+-"))
        out = [e for e in L if all(t in e["tags"] for t in want) and not any(t in e["tags"] for t in drop)]
    elif any(c in query for c in "*?["):
        out = [e for e in L if fnmatch.fnmatchcase(e["id"], query)]
    else:
        out = [entry(query)]
    out = [e for e in out if min_dur <= e["dur"] <= max_dur]
    return out[:limit] if limit else out


def _frame_rms(x, win):
    n = len(x) // win
    if n == 0:
        return np.array([np.sqrt((x ** 2).mean() + 1e-12)]) if len(x) else np.array([1e-6])
    return np.sqrt((x[:n * win].reshape(n, win) ** 2).mean(1) + 1e-12)


def prepare(src, *, start=None, end=None, trim=True, level=-14.0, peak=-1.0, fade_in=0.004, fade_out=0.03, max_len=None, mono=True,
            sr=SR):
    """받은 소리 하나 → float32 배열(mono 기본). src: 아이디 또는 find 의 항목. start, end: 초(원본 기준, 음수면 끝에서).
    trim: 앞 무음(가장 큰 곳보다 45 dB 아래)과 끝 무음(55 dB 아래)을 자름. level: 가장 큰 0.4초 창의 RMS 를 이 dBFS 로(피크는 peak 아래).
    max_len: 초, 넘으면 자르고 끝을 줄어들게."""
    part = None
    if isinstance(src, str):
        src, part = split_ref(src)
    e = entry(src) if isinstance(src, str) else src
    if part is not None:
        ps = e.get("parts") or []
        if not 0 <= part < len(ps):
            raise IndexError(f"{e['id']}#{part}: 덩어리는 0..{len(ps) - 1}")
        s0, e0 = ps[part]
        start = max(0.0, s0 - 0.03) if start is None else s0 + start
        end = e0 + 0.12 if end is None else s0 + end
    x = load(SFX_DIR / e["file"], sr=sr, mono=mono)
    n = len(x)
    a = 0 if start is None else int((n / sr + start if start < 0 else start) * sr)
    b = n if end is None else int((n / sr + end if end < 0 else end) * sr)
    x = x[max(0, a):max(0, min(n, b))].copy()
    if len(x) == 0:
        raise ValueError(f"{e['id']}: 자른 구간이 비었음(start={start}, end={end})")
    m = x if mono else x.mean(1)
    if trim:
        win = int(0.005 * sr)
        r = _frame_rms(m, win)
        top = r.max()
        on = np.nonzero(r > top * 10 ** (-45 / 20))[0]
        off = np.nonzero(r > top * 10 ** (-55 / 20))[0]
        if len(on):
            x = x[max(0, on[0] * win - win):(off[-1] + 1) * win + win]
    if max_len and len(x) > int(max_len * sr):
        x = x[:int(max_len * sr)].copy()
        fade_out = max(fade_out, min(0.5, max_len * 0.15))
    fi, fo = min(len(x), int(fade_in * sr)), min(len(x), int(fade_out * sr))
    shape = (lambda v: v) if mono else (lambda v: v[:, None])
    if fi:
        x[:fi] *= shape(np.linspace(0, 1, fi, dtype=np.float32))
    if fo:
        x[-fo:] *= shape(np.linspace(1, 0, fo, dtype=np.float32) ** 1.5)
    m = x if mono else x.mean(1)
    loud = 20 * np.log10(_frame_rms(m, int(0.4 * sr)).max() + 1e-12)
    pk = 20 * np.log10(np.abs(x).max() + 1e-12)
    g = min(level - loud, peak - pk)
    return (x * np.float32(10 ** (g / 20))).astype(np.float32)


def ogg_bytes(x, quality=4, sr=SR):
    """float32 배열(mono (n,) 또는 stereo (n, 2)) → ogg(libvorbis) 바이트. 같은 입력이면 같은 바이트"""
    ch = 1 if x.ndim == 1 else 2
    pcm = (np.clip(x, -1, 1) * 32767).astype("<i2").tobytes()
    r = subprocess.run([ffmpeg_exe(), "-loglevel", "error", "-f", "s16le", "-ar", str(sr), "-ac", str(ch), "-i", "-", "-c:a", "libvorbis",
                        "-q:a", str(quality), "-fflags", "+bitexact", "-flags:a", "+bitexact", "-map_metadata", "-1", "-f", "ogg", "-"],
                       input=pcm, capture_output=True)
    if r.returncode:
        raise RuntimeError("ogg 굽기 실패: " + r.stderr.decode("utf-8", "replace")[-400:])
    return r.stdout


def ko(tags):
    """태그 목록 → 첫 한국어 이름(자막 기본값)"""
    for t in tags:
        if t in TAG_KO and t not in ("overwhelm", "voice", "loop", "far", "heavy", "light", "low", "material", "misc"):
            return TAG_KO[t]
    return "소리"


def _tags_for(sid, pack, stem):
    tags = list(PACK_TAGS.get(pack, []))
    for pat, add in (TRIP_WORD_TAGS if pack in TRIP_PACK_TAGS else WORD_TAGS):
        if re.search(pat, stem):
            tags += add
    for pat, add in PICKS:
        if fnmatch.fnmatchcase(sid, pat):
            tags += add
    return list(dict.fromkeys(tags))


def build_lib():
    """ref/sfx/*/_info.json 과 원본을 읽어 data/sfx_lib.json 을 새로 쓴다 → 항목 수"""
    import hashlib
    packs, sounds = {}, []
    for d in sorted(p for p in SFX_DIR.iterdir() if p.is_dir() and (p / "_info.json").is_file()):
        info = json.loads((d / "_info.json").read_text(encoding="utf-8"))
        if d.name not in PACK_TAGS:
            continue  # 남의 묶음(weapons 등)은 목록에 넣지 않음
        packs[d.name] = {k: info.get(k, "") for k in ("title", "author", "site", "page", "license", "license_url", "license_seen", "note",
                                                       "downloaded")}
        sha = {f["file"]: f["sha256"] for f in info.get("files", [])}
        for f in sorted(d.iterdir()):
            if f.suffix.lower() not in AUDIO_EXT:
                continue
            b = f.read_bytes()
            h = hashlib.sha256(b).hexdigest()
            if sha.get(f.name) and sha[f.name] != h:
                raise ValueError(f"{f}: sha256 이 _info.json 과 다름(받은 뒤 바뀜)")
            sid = f"{d.name}/{f.stem}"
            pr = probe(f)
            x = load(f, sr=22050)
            dur = len(x) / 22050
            r = _frame_rms(x, int(0.05 * 22050))
            act = r[r > r.max() * 0.05]
            spec = np.abs(np.fft.rfft(x[:22050 * 30])) ** 2 if len(x) else np.zeros(2)
            fr = np.fft.rfftfreq(min(len(x), 22050 * 30), 1 / 22050) if len(x) else np.zeros(2)
            tot = spec.sum() + 1e-12
            extra = {}
            if dur > 3.0:
                ps = parts_of(x, 22050)
                if len(ps) > 1:
                    extra["parts"] = ps
            sounds.append({
                "id": sid, "file": f"{d.name}/{f.name}", "pack": d.name, "tags": _tags_for(sid, d.name, f.stem),
                "dur": round(dur, 3), "ch": pr["ch"], "sr": pr["sr"], "codec": pr["codec"], "bytes": len(b),
                "rms_db": round(float(20 * np.log10(np.sqrt((act ** 2).mean()) + 1e-12)), 1),
                "loud_db": round(float(20 * np.log10(_frame_rms(x, int(0.4 * 22050)).max() + 1e-12)), 1),
                "peak_db": round(float(20 * np.log10(np.abs(x).max() + 1e-12)), 1),
                "low": round(float(spec[fr < 150].sum() / tot), 2),
                "centroid": int((spec * fr).sum() / tot),
                "sha256": h,
                **extra,
            })
    doc = {"about": "받은 효과음 목록(tools/gabe/audio.py build_lib 가 만듦, 손으로 고치지 말 것). 원본 ref/sfx/<묶음>/, 출처 docs/소리출처.md. "
                    "dur 초, bytes 원본 크기, rms_db 소리 나는 곳 RMS, loud_db 가장 큰 0.4초 RMS, peak_db 피크, low 150Hz 아래 에너지 몫, "
                    "centroid 스펙트럼 무게중심 Hz. 쓰는 법 docs/모듈/종작성법.md 소리 절(k.sample)",
           "version": 1, "tags": {t: TAG_KO.get(t, t) for t in sorted({t for s in sounds for t in s["tags"]})}, "packs": packs, "sounds": sounds}
    SFX_LIB.write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
    global _LIB
    _LIB = None
    return len(sounds)


def credit_lines():
    """CREDITS.txt 의 받은 효과음 절 [7b](make_bgm.music_credit_lines 가 [7] 다음에 붙임. [8] 부터는 무기 담당 절): 묶음마다 제목, 만든 사람,
    주소, 라이선스"""
    if not SFX_LIB.is_file():
        return []
    packs = json.loads(SFX_LIB.read_text(encoding="utf-8"))["packs"]
    by = [k for k, p in packs.items() if not p["license"].startswith("CC0")]
    L = ["[7b] 받은 효과음(assets/gave/sounds/director, sfx, 음악 신호 boss_sting, 종의 *_s<번호>. tools/make_director_sounds.py, 키트 k.sample)",
         "  원본은 아래 묶음에서 받았다. 고친 내용: 자름, 앞뒤 무음 자름, 크기 맞춤, 모노로 바꿈(자리 소리), libvorbis 로 다시 인코딩, 높이는",
         "  sounds.json pitch 로 바꿈. 원작자가 이 팩을 만들거나 승인한 것이 아니다.",
         "  CC BY(저작자 표시 필요): " + (", ".join(by) if by else "없음") + ". 나머지는 CC0(표시 의무 없음, 고마움으로 적음)."]
    for k, p in packs.items():
        L.append(f'  - {k}: "{p["title"]}" {p["author"]} ({p["page"]}), {p["license"]} {p["license_url"]}')
        if not p["license"].startswith("CC0") and p.get("note"):
            L.append(f"    저작자 표시 요청(글 페이지): {p['note']}")
    return L


if __name__ == "__main__":
    import sys
    a = sys.argv[1:]
    if a[:1] == ["lib"]:
        n = build_lib()
        d = json.loads(SFX_LIB.read_text(encoding="utf-8"))
        print(f"받은 소리 {n}개, 묶음 {len(d['packs'])}, 태그 {len(d['tags'])} → {SFX_LIB}")
    elif a[:1] == ["find"] and len(a) > 1:
        for e in find(a[1]):
            ps = e.get("parts") or []
            print(f"{e['id']:48s} {e['dur']:6.2f}s ch{e['ch']} loud {e['loud_db']:6.1f} low {e['low']:.2f} c {e['centroid']:5d}"
                  f"{' #' + str(len(ps)) if ps else ''}  {' '.join(e['tags'])}")
            if ps and len(a) > 2 and a[2] == "parts":
                print("      " + " ".join(f"#{i}:{s:.2f}-{t:.2f}" for i, (s, t) in enumerate(ps)))
    else:
        print("python -X utf8 tools/gabe/audio.py lib | find <아이디|묶음/*|tag:a+b-c>")
