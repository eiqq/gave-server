"""G171 빈틈 노리는 게이브: 어깨 높이 1 블록의 마른 개 꼴. 0.6 블록 얼굴이 고개를 낮춘 채 앞을 올려다보고, 몸통은 갈비뼈처럼 휜
엔드 막대기 넷(좌우 둘씩 꺾인 막대기), 다리는 가는 막대기 넷, 꼬리 자리에 작은 얼굴 하나가 뒤를 본다.

모델 gave:gabe/g171, 몸 중심(0, 0, 0) = 땅 위 0.8(Java hover 0.8, 'low' 는 0.6).
- ""(걷기 4자세, 12틱 한 바퀴): 다리 넷이 엇갈려 앞뒤로 흔들림.
- "low"(몸 낮춤): 무릎을 꺾고 고개를 더 숙임, 다리는 가만히.
- "leap"(덮침): 앞다리는 앞으로, 뒷다리는 뒤로 쭉 뻗음.
소리 growl(낮은 으르렁, 20), lunge(덮칠 때 짖듯 끊는 소리, 20), bite(무는 소리, 16). 글귀 cause_g171.
"""
from gabe import kit

LEG = dict(segs=2, thick=0.07)
WALK = kit.Wave(amp=(24, 14), lag=40, bias=(0, 6))


def ribs(s, y_top):
    """갈비 넷: 등뼈 위에서 옆으로 꺾여 배 아래로 휜 막대기(좌우 두 마디씩, 자세 없음)"""
    for z in (-0.32, -0.1, 0.12, 0.32):
        for side in (1, -1):
            s.rod((side * 0.02, y_top, z), (side * 1.0, -0.35, 0), length=0.24, segs=1, thick=0.05, base=False, rag=False)
            s.rod((side * 0.24, y_top - 0.08, z), (side * 0.25, -1.0, 0.05), length=0.3, segs=1, thick=0.05, base=False, rag=False)


def dog(s, legs, head_y, head_tilt, y_top=0.16):
    # 등뼈(꼬리에서 목까지)
    s.rod((0, y_top, -0.62), (0, 0.06, 1), length=1.12, segs=1, thick=0.07, base=False, rag=False)
    ribs(s, y_top)
    # 얼굴: 고개를 낮추고 올려다봄(조금 위로 젖힘)
    s.head(size=0.6, depth=0.42, at=(0, head_y, 0.74), face="smile", rot=(head_tilt, 0, 0))
    # 꼬리 자리의 작은 얼굴(뒤를 봄)
    s.head(size=0.26, depth=0.2, at=(0, y_top + 0.06, -0.74), face="half", near=False, open=False, torn=False, rot=(0, 180, 0))
    # 다리 넷: 어깨, 엉덩이 아래로
    for (x, z), wave, bias in legs:
        s.rod((x, y_top - 0.3, z), (0, -1, 0.0 if bias is None else bias), length=0.68, wave=wave, toward=(0, 0, 1),
              poses=4 if s.poses >= 4 else None, base=False, **LEG)


def build(k: kit.Kit):
    m = k.model(poses=4, period=12)
    at = [(0.16, 0.36), (-0.16, 0.36), (0.16, -0.48), (-0.16, -0.48)]
    # 걷기: 왼앞과 오른뒤가 같이, 오른앞과 왼뒤가 반대로
    walk = [kit.Wave(amp=WALK.amp, lag=WALK.lag, bias=WALK.bias, phase=ph) for ph in (0, 180, 180, 0)]
    dog(m.state(""), [(a, w, None) for a, w in zip(at, walk)], head_y=0.0, head_tilt=-14)
    # 몸 낮춤: 무릎을 꺾은 다리(물결 없이 굽힌 두 마디는 Wave(amp=0, bias)로: 자세 하나)
    bent_f = kit.Wave(amp=0, bias=(38, -86))
    bent_b = kit.Wave(amp=0, bias=(-38, 86))
    dog(m.state("low", poses=1), [(at[0], bent_f, None), (at[1], bent_f, None), (at[2], bent_b, None), (at[3], bent_b, None)],
        head_y=-0.12, head_tilt=-22)
    # 덮침: 앞다리 앞으로, 뒷다리 뒤로
    fwd = kit.Wave(amp=0, bias=(62, 10))
    back = kit.Wave(amp=0, bias=(-62, -10))
    dog(m.state("leap", poses=1), [(at[0], fwd, None), (at[1], fwd, None), (at[2], back, None), (at[3], back, None)],
        head_y=0.04, head_tilt=-4)


def sounds(k: kit.Kit):
    # 이 종 전용 신호: 낮게 끓는 으르렁(둘레를 돌며 울림), 덮치기 직전에 뚝 끊긴다(Java)
    k.sample("growl", "tag:growl-boss_roar", "낮은 으르렁", pitch=0.75, attenuation=20)
    k.sample("lunge", "tag:snarl", "덮치는 소리", pitch=1.1, attenuation=20)
    k.sample("bite", "tag:flesh", "무는 소리", pitch=1.2, attenuation=16)


def phrases():
    return {"cause_g171": "빈틈에 물렸다"}
