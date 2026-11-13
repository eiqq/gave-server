"""G52 총 주운 게이브: 0.7 블록 얼굴, 엔드 막대기 둘이 양옆 아래에서 앞으로 꺾여 총을 받쳐 든다(총 모델과 이름표는 Java 가 따로 싣는 디스플레이).

상태: ""(겨눔: 받친 막대기가 조금 출렁), "reload"(재장전: 막대기가 더듬듯 빠르게 움찔).
소리 shot 은 이 종의 신호(낮게 늘어진 총소리). 그 사람 총의 진짜 총소리도 Java 가 낮은 높이로 같이 울린다.
"""
from gabe import audio, kit


def body(s, wave_kw):
    h = s.head(size=0.7, depth=0.5, face="smile")
    for side, ph in ((1, 0), (-1, 180)):
        w = kit.Wave(bias=(22, 46, 28), phase=ph, **wave_kw)
        # 뿌리는 볼 아래 옆, 바깥 아래로 나가다 앞 안쪽으로 꺾여 총 밑에서 만난다
        s.rod(h.point(side * 0.95, -0.55, 0.0), (side * 0.75, -0.6, 0.2), length=0.95, segs=3, thick=0.09, wave=w,
              toward=(-side * 0.45, 0.25, 1.0))
    return h


def build(k: kit.Kit):
    m = k.model(poses=8, period=20)
    body(m.state(""), dict(amp=(4, 5, 6), lag=40))
    body(m.state("reload"), dict(amp=(10, 16, 22), lag=35, shape="twitch", cycles=2))


def sounds(k: kit.Kit):
    # 낮게 늘어진 총소리(바닐라 폭죽 터짐을 반 높이로): 연사 첫 발과 몇 발마다
    k.sound("shot", "늘어진 총소리", vanilla=[("fireworks/blast1", 0.55), ("fireworks/blast_far1", 0.6), ("fireworks/largeblast1", 0.5)],
            volume=0.9, attenuation=64)
    # 총을 주워 받쳐 듦(태어날 때)
    k.sound("pickup", "총을 주워 듦", vanilla=[("item/armor/equip_iron1", 0.6), ("item/armor/equip_iron3", 0.55)], volume=1.0, attenuation=32)


def phrases():
    return {"cause_g52": "동료의 총에 맞았다", "notice_g52_gun": "총을 주웠다"}
