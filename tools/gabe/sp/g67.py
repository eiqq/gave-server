"""G67 허수아비 게이브: 엔드 막대기 기둥 하나에 가로 막대기 하나를 십자로 묶은 허수아비(키 3). 꼭대기에 0.9 블록 얼굴이 꽂혀 있고,
가로 막대기에 얇게 편 분홍 살 천이 누더기처럼 걸려 흔들리며, 양끝에 손톱만 한 얼굴 열매가 대롱대롱 달렸다. 십자 실루엣은 멀리서도 또렷.

모델 gave:gabe/g67(원점 = 가로 막대기 높이, 기둥 끝이 1.75 아래 땅) 상태 ""(누더기 천 흔들림, 열매 흔들림), "scan"(눈이 크게 뜨인 얼굴,
천과 열매가 멈춤: '삐' 훑기 0.5초), "hop"(깡충: 기둥이 접혀 짧아지고 천이 위로 날림).
모델 gave:gabe/g67_fruit(쏘는 얼굴 열매 탄).
소리 scan(살피는 삐), shoot(열매 튕김), hop(깡충 착지), swat(후려침), 글귀 cause_g67.
"""
import numpy as np

from gabe import faces, kit

BAR = 1.2
SCAN_FACE = faces.Face(eyes="bloodshot", mouth="dot", blush="pink")


def scarecrow(k, s, name):
    scan, hop = name == "scan", name == "hop"
    s.head(size=0.9, depth=0.7, at=(0, 0.72, 0), face=SCAN_FACE if scan else "smile")
    # 기둥: 얼굴 밑에서 땅까지(깡충 중에는 접혀 짧음), 발끝 받침
    pole = 0.95 if hop else 1.75
    s.rod((0, 0.3, 0), (0, -1, 0), length=0.3 + pole, segs=2, thick=0.15, toward=(0, 0, 1), rag=False)
    # 가로 막대기와 묶은 매듭
    s.rod((-BAR, 0, 0), (1, 0, 0), length=2 * BAR, segs=2, thick=0.12, toward=(0, 1, 0), base=False, rag=False)
    s.box((-0.13, -0.11, -0.13), (0.13, 0.11, 0.13), "flesh", face=True)
    # 누더기 천 둘(가로 막대기에서 늘어짐): 기본은 앞뒤로 흔들림, 훑기 중엔 멈춤, 깡충 중엔 위로 날림
    for i, x in enumerate((-0.62, 0.62)):
        if scan:
            wave = None
            d = (0, -1, 0.02)
        elif hop:
            wave = kit.Wave(amp=(10, 12), lag=40, bias=(60, 30))
            d = (0, -1, 0)
        else:
            wave = kit.Wave(amp=(14, 18, 20), lag=55, phase=i * 140)
            d = (0, -1, 0)
        s.band((x, -0.05, 0.02), d, length=0.95, width=0.5, segs=3, thick=0.02, wave=wave, toward=(0, 0, 1), tex="wetskin",
               poses=None if wave is None else 4)
    # 얼굴 열매: 짧은 꼭지에 매달린 작은 얼굴(훑기 중엔 가만히)
    for x in (-BAR + 0.05, BAR - 0.05):
        s.rod((x, 0, 0), (0, -1, 0), length=0.18, segs=1, thick=0.05, toward=(0, 0, 1), base=False, rag=False)
        s.head(size=0.24, depth=0.22, at=(x, -0.3, 0), face="smile", near=False)


def build(k: kit.Kit):
    m = k.model(poses=4, period=20, shrink=0.75, mut2=dict(root=(0, 0.5, -0.3), dir=(0.3, 1.0, -0.6), length=0.6, segs=1, poses=2))
    for name in ("", "scan", "hop"):
        scarecrow(k, m.state(name), name)
    f = k.model("g67_fruit", poses=1, period=20, mut2=False, torn=False, rag=False)
    fs = f.state("")
    fs.head(size=0.3, depth=0.28, face="smile", near=False)
    fs.rod((0, 0.15, 0), (0.2, 1, 0), length=0.14, segs=1, thick=0.05, toward=(0, 0, 1), base=False, rag=False)


def sounds(k: kit.Kit):
    k.sample("scan", ["kenney_digital/highup", "kenney_digital/pepsound1"], "살피는 삐", pitch=1.0, volume=0.9, attenuation=32)
    k.sample("shoot", "tag:plop", "열매 튕김", pitch=1.2, attenuation=20)
    k.sample("hop", "tag:step", "깡충", pitch=0.8, volume=0.8, attenuation=16)
    k.sample("swat", "tag:punch", "후려침", pitch=0.85, attenuation=16)


def phrases():
    return {"cause_g67": "허수아비 열매에 맞았다"}
