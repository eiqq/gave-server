"""G138 다친 냄새 물구나무 게이브(4막, 떼, 돌격): 위아래가 뒤집힌 0.62 블록 _Gave 얼굴(턱이 위, 실눈이 아래)이 물구나무를 섰다.
지금은 아래쪽인 정수리에서 엔드 막대기 둘이 팔처럼 내려와 땅을 짚고, 얼굴 위(뒤집힌 몸통)로 살 몸통이 솟고 그 끝에서 막대기 다리 둘이 V 자로
하늘을 찬다. 위로 향한 턱 끝에 킁킁대는 작은 살 돌기. 뒤집힌 웃음이라 찡그린 얼굴로 보인다.

모델 gave:gabe/g138 상태:
  ""      물구나무 걸음: 팔 둘이 번갈아 짚음(4자세), 다리가 흔들림
  "leap"  뛰어 물기: 팔을 접고 다리를 벌림(2자세)
가운데(몸 자리) = 얼굴 가운데, 바닥 위 0.75(Java HOVER 0.75: 팔 끝이 땅).
소리 sniff(킁킁 냄새 맡는 소리, 20), bite(물기, 16).
"""
from gabe import kit

FACE = 0.62
ARM = 0.5


def body(s, k, mode):
    h = s.head(size=FACE, depth=0.5, face="flipped")
    fl = k.part_tex("flesh")
    # 턱(지금 위) 끝 킁킁 돌기
    s.box((-0.07, FACE / 2 - 0.02, 0.16), (0.07, FACE / 2 + 0.1, 0.3), fl, face=True)
    # 뒤집힌 몸통: 얼굴 위(목 자리 = 턱 뒤)에서 솟음, 얼굴보다 크게
    s.box((-0.36, FACE / 2 + 0.02, -0.34), (0.36, FACE / 2 + 0.72, 0.1), fl, rot=(-12, 0, 0), origin=(0, FACE / 2, -0.12), face=True)
    top = (0, FACE / 2 + 0.72, -0.25)
    # 다리 둘: 몸통 끝에서 V 자로 하늘을 참
    for side in (1, -1):
        if mode == "walk":
            w = kit.Wave(amp=14, phase=0 if side > 0 else 180)
            s.rod((side * 0.2, top[1], top[2]), (side * 0.45, 1, -0.25), length=0.62, segs=2, thick=0.1, wave=w, toward=(0, 0, 1), poses=4)
        else:
            s.rod((side * 0.2, top[1], top[2]), (side * 0.9, 0.7, -0.2), length=0.62, segs=2, thick=0.1, rag=True)
    # 팔 둘: 정수리(지금 아래) 양 모서리에서 땅으로
    for side in (1, -1):
        root = h.point(side * 0.75, -1, 0)
        if mode == "walk":
            w = kit.Wave(amp=26, bias=0, phase=0 if side > 0 else 180, shape="sin")
            s.rod(root, (side * 0.25, -1, 0.05), length=ARM, segs=2, thick=0.1, wave=w, toward=(0, 0, 1), poses=4)
        else:
            s.rod(root, (side * 0.6, -0.4, 0.6), length=ARM, segs=2, thick=0.1, wave=kit.Wave(amp=10, shape="twitch"), toward=(0, 1, 0), poses=2)


def build(k: kit.Kit):
    m = k.model(poses=4, period=12, mut2=False, torn=False)
    body(m.state(""), k, "walk")
    body(m.state("leap", poses=2), k, "leap")


def sounds(k: kit.Kit):
    k.sample("sniff", ["oga_creature80/nose", {"id": "oga_creature80/nose", "pitch": 1.25}, {"id": "oga_creature80/nose", "pitch": 0.85}],
             "킁킁 냄새 맡는 소리", attenuation=20)
    k.sample("bite", "tag:eat", "물기", pitch=1.1, attenuation=16)


def phrases():
    return {"cause_g138": "다친 냄새를 맡은 무리에 물렸다"}
