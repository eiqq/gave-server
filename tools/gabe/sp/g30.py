"""G30 얼굴 다리 지네 게이브: 엔드 막대기 마디로 이은 2 블록 몸통 아래에 작은 게이브 얼굴 여섯 쌍이 막대기로 땅을 짚으며 긴다.
머리 얼굴은 입 아래 살이 길게 늘어져 바닥에 끌리고, 꼬리 끝 막대기 하나가 까딱인다. 디스플레이 셋(머리, 몸통 앞, 몸통 뒤).

- gave:gabe/g30(머리, 닻): 0.5 블록 얼굴(바닥 위 0.35), 입 아래 늘어진 젖은 살 띠, 머리 위 더듬이 막대기 둘(원형 DNA, 펄럭).
- gave:gabe/g30_seg(몸통 앞 토막): 등뼈 막대기 + 얼굴 다리 세 쌍(자세 4: 물결처럼 들었다 짚음). 상태 ""(기어감) 하나
  (황야 맵에는 탈 벽이 없어 벽 타기 접힌 상태는 뺐다).
- gave:gabe/g30_tail(몸통 뒤 토막): 등뼈 + 얼굴 다리 세 쌍 + 꼬리 막대기. 상태 ""(꼬리 까딱), "raise"(찌르기 전: 꼬리를 등 위로 치켜듦,
  두 배 빠르게 떪), "curl"(빛을 피함: 꼬리를 말아 붙임).
토막 원점은 토막 가운데(바닥 위 0.35), 앞 +Z. 토막 길이 0.8.
"""
import math

from gabe import audio, kit

ANT = kit.Wave(amp=(20, 18), lag=50, bias=(-10, 6))
DRAG = kit.Wave(amp=(6, 9, 12), lag=40, bias=(10, 18, 22))
TAIL = kit.Wave(amp=(10, 16), lag=50, shape="twitch")
TAIL_RAISE = kit.Wave(amp=(14, 18), lag=50, bias=(55, 55), shape="twitch", cycles=2)
LEG_FACE = "smile_a"


def head(s):
    h = s.head(size=0.5, depth=0.42, face="smile")
    # 입 아래로 늘어진 살(바닥에 끌림): 입 자리 아래 앞에서 아래로, 끝은 뒤로 끌림
    s.band(h.point(0.05, -0.6, 0.98), (0, -1, 0.15), length=0.6, width=0.2, segs=3, thick=0.03, wave=DRAG, toward=(0, 0, -1),
           tex="wetskin", poses=4)
    # 더듬이(원형 DNA): 머리 위 뒤에서 앞으로 휘어 펄럭
    for side in (1, -1):
        s.rod(h.point(side * 0.55, 1, -0.2), (side * 0.5, 0.8, 0.5), length=0.75, segs=2, thick=0.07, wave=ANT,
              toward=(0, -0.3, 1), poses=6)


def legs(s, fold=False):
    """얼굴 다리 세 쌍(몸통 옆 아래에서 바깥 아래를 봄). 자세마다 쌍마다 엇갈려 들었다 짚음"""
    zs = (0.26, 0.0, -0.26)
    frames = s.frames(poses=4) if not fold else [None]
    for f in frames:
        if f is not None:
            f.__enter__()
        try:
            t = f.t if f is not None else 0.0
            for i, z in enumerate(zs):
                for side in (1, -1):
                    ph = 2 * math.pi * (t + i / 3 + (0.5 if side < 0 else 0))
                    lift = max(0.0, math.sin(ph)) * 0.08 if not fold else 0.0
                    swing = math.cos(ph) * 0.05 if not fold else 0.0
                    if fold:
                        c = (side * 0.2, -0.12, z)
                        rot = (80, side * 90, 0)
                    else:
                        c = (side * 0.36, -0.2 + lift, z + swing)
                        rot = (55, side * 90, 0)
                    s.head(size=0.2, depth=0.15, at=c, face=LEG_FACE, rot=rot, open=False, torn=False)
                    # 다리 막대기: 등뼈 옆에서 얼굴로(얼굴이 땅을 짚음)
                    s.rod((side * 0.08, 0.02, z), (side * 1.0, -0.6 + lift * 2, swing), length=0.26, segs=1, thick=0.05, base=False)
        finally:
            if f is not None:
                f.__exit__()


def spine(s):
    s.rod((0, 0.04, 0.42), (0, 0, -1), length=0.84, segs=2, thick=0.13)


def build(k: kit.Kit):
    m = k.model(poses=6, period=24)
    head(m.state(""))
    seg = k.model("g30_seg", poses=4, period=12, mut2=False, open=False, torn=False)
    s = seg.state("")
    spine(s)
    legs(s, False)
    tail = k.model("g30_tail", poses=4, period=12, mut2=False, open=False, torn=False)
    for name in ("", "raise", "curl"):
        s = tail.state(name)
        spine(s)
        legs(s, False)
        root = (0, 0.06, -0.42)
        if name == "":
            s.rod(root, (0, 0.35, -1), length=0.6, segs=2, thick=0.08, wave=TAIL, toward=(0, 1, 0))
        elif name == "raise":
            s.rod(root, (0, 0.35, -1), length=0.75, segs=2, thick=0.08, wave=TAIL_RAISE, toward=(0, 1, 0))
        else:
            s.rod(root, (0, 1, -0.2), length=0.55, segs=2, thick=0.08, wave=kit.Wave(amp=(4, 4), lag=30, bias=(40, 60)),
                  toward=(0, 0, 1))
    k.icon(model="g30", state="")


def _scrape(seed):
    """칠판 긁는 소리(이 종 전용): 높은 끽 소리가 떨리며 미끄러짐 + 거친 마찰. Java 가 가까울수록 높여 울림"""
    d = 0.7
    f0 = 2300 + seed * 140
    sq = audio.tone(f0, d, "saw", vib=0.035, vib_rate=31, glide=f0 * 1.18)
    sq = audio.bandpass(sq, 1800, 5200)
    grit = audio.bandpass(audio.noise(d, 950 + seed), 2500, 7000) * 0.45
    x = audio.env(audio.mix(sq * 0.7, grit), 0.03, 0.05, 0.75, 0.2)
    return audio.normalize(audio.fade(x, 0.01, 0.08), 0.6)


def _sting(seed):
    a = audio.env(audio.bandpass(audio.noise(0.12, 960 + seed), 1500, 6000), 0.001, 0.02, 0.3, 0.06)
    b = audio.squish(seed=970 + seed, dur=0.22)
    return audio.normalize(audio.fade(audio.mix(a, audio.gain(b, 0.7))), 0.9)


def sounds(k: kit.Kit):
    k.sound("scrape", "칠판 긁는 소리", synth=_scrape, volume=0.7, attenuation=24)
    k.sound("sting", "꼬리에 찔림", synth=_sting, volume=1.0, attenuation=16)
    k.sound("crawl", "다리 수십 개가 바닥을 두드림", vanilla=[("mob/silverfish/step1", 0.7), ("mob/silverfish/step3", 0.75),
                                                ("mob/spider/step2", 1.3)], volume=0.6, attenuation=12)
    k.sound("baby", "등에서 새끼가 나옴", vanilla=[("mob/silverfish/say2", 0.6), ("mob/silverfish/say4", 0.55)],
            synth=lambda i: audio.chitter(seed=990 + i, dur=0.45, base=1400), volume=1.0, attenuation=16)
    k.sound("recoil", "빛에 움츠림", vanilla=[("mob/silverfish/hit2", 0.7), ("mob/silverfish/hit3", 0.65)], volume=0.9, attenuation=16)


def phrases():
    return {"cause_g30": "지네 꼬리에 찔렸다", "cause_g30_bite": "지네에게 물렸다", "cause_g30_back": "등에서 새끼 지네가 나왔다",
            "cause_g30_baby": "새끼 지네에게 물렸다"}
