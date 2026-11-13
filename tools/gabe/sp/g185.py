"""G185 말뚝 든 게이브: 0.6 블록 얼굴이 살 몸통과 엔드 막대기 다리 둘로 걷고, 제 키만 한 엔드 막대기 말뚝 하나를 어깨에 메고 온다(키 1.6).
말뚝 머리에 작은 얼굴 패가 하나 매달려 있고 패의 눈은 처음부터 떠 있다. 얼굴의 흰 눈, 무표정은 Java 팔레트(PAL_WHITE_EYES | PAL_BLANK).

모델 gave:gabe/g185: 몸 중심은 땅 위 0.8(Java). 상태 ""(걷기 2자세, 말뚝 멤), "jab"(말뚝을 앞으로 내질러 찌름), "plant"(말뚝 없이 선 몸).
모델 gave:gabe/g185_stake: 쓰러진 사람 곁에 박힌 말뚝(땅에 비스듬히, 머리에 눈 뜬 얼굴 패). 몸 중심은 땅 위 0.6.
"""
from gabe import kit

ROD = dict(segs=1, base=False, rag=False)


def tag(s, at):
    # 말뚝 머리에 매달린 작은 얼굴 패(눈을 뜬 채)
    s.head(size=0.24, depth=0.06, at=at, face="open", near=False, open=False, torn=False)


def walker(k, s, pose):
    s.head(size=0.6, depth=0.48, at=(0, 0.48, 0.02), face="smile", torn=False)
    # 몸통(얼굴보다 넓은 살 몸): 어깨가 넓은 짐꾼
    s.box((-0.36, -0.38, -0.22), (0.36, 0.2, 0.2), "flesh", face=True)
    # 다리 둘(걷기 2자세)
    for side in (1, -1):
        w = kit.Wave(amp=26, bias=0, phase=0 if side > 0 else 180)
        s.rod((side * 0.16, -0.36, 0.0), (0, -1, 0), length=0.46, thick=0.1, wave=w, toward=(0, 0, 1), poses=2, **ROD)
    if pose == "":
        # 어깨에 멘 말뚝(오른쪽 어깨에서 뒤로 비스듬히), 앞 끝에 얼굴 패
        s.rod((-0.36, -0.2, 0.5), (0.1, 1, -0.55), length=1.55, thick=0.12, **ROD)
        tag(s, (-0.3, -0.05, 0.62))
        s.rod((-0.4, 0.1, 0.0), (0, -0.4, 1), length=0.4, thick=0.08, **ROD)  # 말뚝을 붙든 팔
    elif pose == "jab":
        # 말뚝을 앞으로 곧게 내지름(끝이 몸 앞 1.3 블록)
        s.rod((0.0, -0.05, -0.2), (0, 0, 1), length=1.45, thick=0.12, **ROD)
        tag(s, (0.0, 0.12, 0.9))
        for side in (1, -1):
            s.rod((side * 0.34, 0.08, 0.0), (-side * 0.6, -0.3, 1), length=0.42, thick=0.08, **ROD)
    else:
        for side in (1, -1):
            s.rod((side * 0.38, 0.12, 0.0), (side * 0.1, -1, 0.05), length=0.48, thick=0.08, **ROD)
    s.front = (0, 0, 1)


def stake(k, s):
    s.rod((0, -0.75, 0), (0.18, 1, 0.1), length=1.35, thick=0.13, toward=(0, 0, 1), **ROD)
    tag(s, (0.2, 0.42, 0.18))
    s.front = (0, 0, 1)


def build(k: kit.Kit):
    m = k.model(poses=2, period=10, mut2=False, torn=False)
    for pose in ("", "jab", "plant"):
        walker(k, m.state(pose), pose)
    st = k.model("g185_stake", poses=1, mut2=False, torn=False, open=False, rag=False)
    stake(k, st.state(""))
    k.icon(model="g185", state="")


def sounds(k: kit.Kit):
    # 이 종 전용 신호: 말뚝 박는 소리
    k.sample("stake", "tag:tools", "말뚝 박는 소리", pitch=0.8, attenuation=24)
    # 말뚝 찌르기
    k.sample("jab", "kenney_impact/impactpunch_medium_00*", "말뚝 찌르기", pitch=1.0, attenuation=16)
    # 말뚝이 뽑힘
    k.sample("pull", "oga_metalwood100/wood_hammer_0*", "말뚝이 뽑힘", pitch=1.3, attenuation=20)


def phrases():
    return {"cause_g185": "제 사람을 찾아온 말뚝에 찔렸다"}
