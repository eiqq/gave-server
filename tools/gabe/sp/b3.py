"""B3 에워싸는 게이브(보스, 33라운드: 4막 우주선 건조 중. docs/모듈/보스.md 14절): 6블록 _Gave 얼굴 열여섯이 손잡고 경기장을 둘러싸 돌며 조여 온다.
빈 닻 + 세계 축 부품으로 그린다(모든 모델은 틀 ±1.5 블록 안의 보통 크기, Java 부품 배율로 키움).

- gave:gabe/b3(술래, 부품 배율 7): 얼굴(1.0) + 양손 막대기 둘 + 머리 위 막대기 여섯 관 + 짧은 막대기 다리 둘. 상태 ""(허밍, 손 맞잡음),
  "clap"(손을 치켜듦), "dash"(손을 뒤로 젖히고 내달림). 노릴 때 눈 뜸은 실눈 뜸 오버레이(Java part.look.open).
- gave:gabe/b3_face(고리 얼굴, 배율 6): 얼굴(1.0) + 양손 막대기 둘 + 다리 둘. 상태 "", "clap", "crack"(입이 찢어지며 깨짐).
- gave:gabe/b3_link(고리 줄): 엇갈려 맞잡은 막대기 둘(가만히, x 로 -0.5..0.5 길이 1). Java 가 (얼굴 사이 거리, 5, 5)로 늘림. "snap": 반쪽씩 늘어짐.
- 손뼉 파도는 공용 판 gave:gabe/b1_wave(b1.py), 돌진 띠는 공용 예고 판을 Java 가 쓴다.
막대기는 손, 관, 다리로만 쓴다(늘어뜨려 펄럭이는 촉수 꼴 없음).
"""
import math

from gabe import kit

ROD = 0.09
LEG = 0.22


def _hands(s, up=False, back=False):
    """양손 막대기(얼굴 옆에서 바깥으로, 이웃과 맞잡는 쪽). up: 치켜듦, back: 뒤로 젖힘"""
    for side in (1, -1):
        if up:
            d = (side * 0.55, 0.83, 0)
            w = kit.Wave(amp=(6, 8), lag=30, shape="flap")
        elif back:
            d = (side * 0.6, 0.15, -0.78)
            w = kit.Wave(amp=(3, 4), lag=40)
        else:
            d = (side * 1.0, -0.12, 0)
            w = kit.Wave(amp=(2, 3), lag=40)
        s.rod((side * 0.5, -0.12, 0), d, length=0.42, segs=2, thick=ROD, wave=w, toward=(0, 1, 0), base=False, poses=4, rag=False)


def _legs(s):
    """턱 아래 짧은 다리 둘(땅에 선 얼굴)"""
    for side in (1, -1):
        s.rod((side * 0.24, -0.5, 0), (0, -1, 0), length=LEG, segs=1, thick=ROD, toward=(0, 0, 1), base=False, rag=False)


def _crown(s):
    """머리 위 막대기 여섯 관(술래 표시): 정수리 둘레에서 바깥 위로, 작게 움찔"""
    for i in range(6):
        a = math.radians(i * 60 + 30)
        x, z = 0.3 * math.cos(a), 0.22 * math.sin(a)
        d = (0.26 * math.cos(a), 0.96, 0.26 * math.sin(a))
        s.rod((x, 0.5, z), d, length=0.5, segs=2, thick=0.07, wave=kit.Wave(amp=(0, 4), bias=(0, 6), phase=i * 60, shape="twitch"),
              toward=(math.cos(a), 0, math.sin(a)), base=False, poses=4, rag=False)


def build(k: kit.Kit):
    # 술래(부품 1, 배율 7)
    m = k.model(poses=4, period=40, mut2=False, open=True, torn=False, rag=False)
    for name, up, back in (("", False, False), ("clap", True, False), ("dash", False, True)):
        s = m.state(name)
        s.head(size=1.0, depth=0.75, face="smile")
        _hands(s, up=up, back=back)
        _legs(s)
        _crown(s)

    # 고리 얼굴(부품 2~16, 배율 6)
    fm = k.model("b3_face", poses=4, period=40, mut2=False, open=True, torn=False, rag=False)
    for name, face, up in (("", "smile", False), ("clap", "smile", True), ("crack", "torn", False)):
        s = fm.state(name)
        s.head(size=1.0, depth=0.75, face=face)
        _hands(s, up=up)
        _legs(s)

    # 고리 줄(부품 17~32): 엇갈려 맞잡은 막대기 둘, 가만히
    lm = k.model("b3_link", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    s = lm.state("")
    for dy in (0.07, -0.07):
        d = (1.0, -2 * dy, 0)
        n = math.hypot(*d)
        s.rod((-0.5, dy, 0), d, length=n, segs=2, thick=0.1, base=False, rag=False)
    s.front = (0, 0, 0)
    sn = lm.state("snap")
    for side in (1, -1):
        sn.rod((-side * 0.5, 0, 0), (side * 0.55, -0.83, 0), length=0.32, segs=2, thick=0.1, base=False, rag=False)
    sn.front = (0, 0, 0)

    k.icon(model="b3", state="")


def sounds(k: kit.Kit):
    # 받은 소리(data/sfx_lib.json, CC0 와 CC BY)만. 반경은 Java 와 같게
    hum = "oga_humming/ghostly_humming"
    k.sample("chant", [{"id": hum, "start": 2.0, "end": 6.0}, {"id": hum, "start": 14.0, "end": 18.0}, {"id": hum, "start": 31.0, "end": 35.0}],
             "허밍", pitch=0.8, volume=0.9, attenuation=128)
    k.sample("clap", ["oga_sfx100/slam_01", "oga_sfx100/slam_02", "kenney_impact/impactpunch_heavy_001"], "짝", pitch=0.6, attenuation=160)
    k.sample("stop", ["kenney_impact/impactbell_heavy_002", "kenney_impact/impactbell_heavy_003"], "뚝", pitch=0.5, attenuation=160)
    k.sample("tag", ["lrsf_horror/monster_01", "lrsf_horror/monster_06"], "술래다", pitch=0.75, attenuation=96)
    k.sample("bump", ["kenney_impact/impactpunch_heavy_003", "kenney_impact/impactpunch_heavy_004"], "퉁", pitch=0.5, attenuation=32)
    k.sample("butt", ["kenney_impact/impactpunch_heavy_000", "oga_sfx100/slam_03"], "박치기", pitch=0.6, attenuation=48)
    k.sample("break", ["lrsf_horror/bonecrack_00", "oga_breaking75/bfh1_breaking_01", "lrsf_horror/bonecrack_01"], "쩍", pitch=0.6, attenuation=96)
    k.sample("link", ["kenney_rpg/creak1", "kenney_rpg/creak2"], "삐걱", pitch=0.6, attenuation=48)
    k.sample("end", ["oga_sfx100/gong_01", "kenney_impact/impactbell_heavy_000"], "종", pitch=0.45, attenuation=192)
    k.sample("rise", ["oga_breaking75/bfh1_rock_breaking_01", "oga_breaking75/bfh1_rock_breaking_02", "oga_quake/shake"], "땅이 갈라짐", pitch=0.6,
             attenuation=160)
    k.sample("seen", [{"id": "oga_giggle/group_giggling", "start": 0.0, "end": 1.6}, {"id": "oga_giggle/group_giggling", "start": 2.0, "end": 3.6}],
             "킥킥", pitch=0.8, attenuation=64)
    k.sample("stomp", ["oga_creature80b/stomp_01", "oga_stomp/stomp"], "쿵", pitch=0.5, attenuation=96)


def phrases():
    return {
        "bs_sub_b3": "다 같이 손잡고",
        "bs_b3_p2": "고리가 조여 온다",
        "bs_b3_p3": "노래가 빨라진다",
        "notice_b3_rocket": "술래가 우주선으로",
        "cause_b3_tag": "술래에게 잡혔다",
        "cause_b3_clap": "손뼉 파도를 못 넘었다",
        "cause_b3_freeze": "움직이다 들켰다",
        "cause_b3_bump": "고리에 부딪혔다",
        "cause_b3_butt": "박치기에 맞았다",
    }
