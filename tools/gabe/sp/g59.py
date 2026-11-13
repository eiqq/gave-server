"""G59 물동이 게이브: 0.8 블록 얼굴이 정수리에 살로 빚은 물동이를 이고(분홍 물이 찰랑), 무릎이 뒤로 꺾인 새 다리 둘(엔드 막대기)로 달린다.
몸통(얼굴 + 물동이)이 다리보다 크고 위가 넓은 그릇 꼴(폭 1.38)이라 멀리서 '머리에 그릇을 인 새 다리'로 읽힌다(키 1.9).

모델 gave:gabe/g59 상태 ""(달리기: 다리 자세 4, 물 찰랑), "bow"(절: 얼굴과 물동이가 앞으로 깊이 숙여지고 빈 물동이 속이 보임, 무릎 굽힘),
  "dry"(물 없음, 다리 후들). 부위 "broken"(물동이가 깨져 밑동만 남음).
소리 slosh(찰랑), surge(출렁: 들이받기 예고), spill(쏟아짐), 글귀 cause_g59.
"""
import numpy as np

from gabe import faces, kit

HEAD = dict(size=0.8, depth=0.6)
JAR_H = 0.56
TOP = 0.4  # 정수리 높이


def water_tex(seed=59):
    """분홍 물: 잔물결과 흰 반짝임"""
    rng = np.random.RandomState(seed)
    yy, xx = np.mgrid[0:16, 0:16].astype(float)
    col = np.array([246, 150, 176], float) * (0.9 + 0.1 * np.sin(xx * 0.9 + yy * 0.5))[..., None]
    col[(rng.rand(16, 16) < 0.07)] = (255, 226, 234)
    col[(np.abs(xx - 5) < 2) & (np.abs(yy - 4) < 1)] = (255, 240, 244)
    out = np.zeros((16, 16, 4), np.uint8)
    out[..., :3] = np.clip(np.round(col), 0, 255)
    out[..., 3] = 255
    return out


def hollow_tex():
    """빈 물동이 속: 어두운 살빛, 가운데로 갈수록 짙음"""
    yy, xx = np.mgrid[0:16, 0:16].astype(float)
    r = np.hypot(xx - 7.5, yy - 7.5) / 8.0
    col = np.array([150, 70, 84], float) * (0.45 + 0.55 * np.clip(r, 0, 1))[..., None]
    out = np.zeros((16, 16, 4), np.uint8)
    out[..., :3] = np.clip(np.round(col), 0, 255)
    out[..., 3] = 255
    return out


def jar(k, s, rot, water, pivot):
    """정수리 위 물동이(살): 좁은 밑동, 얼굴보다 넓게 벌어진 몸통, 넓은 테두리, 물 또는 빈 속. rot 은 얼굴과 같이 숙이는 각도(없으면 None).
    위가 넓은 그릇 꼴(폭 1.3)이라 멀리서 얼굴 상자 하나가 아니라 '머리에 인 그릇'으로 읽힌다."""
    kw = dict(rot=rot, origin=pivot) if rot else {}
    side4 = ("north", "south", "east", "west")
    hollow = k.texture("hollow", hollow_tex())
    with s.when("!on:broken"):
        s.box((-0.4, TOP, -0.4), (0.4, TOP + 0.16, 0.4), "flesh", faces=side4 + ("down",), face=True, **kw)
        s.box((-0.62, TOP + 0.14, -0.62), (0.62, TOP + JAR_H, 0.62), "flesh", faces=side4 + ("down",), face=True, **kw)
        s.box((-0.69, TOP + JAR_H - 0.08, -0.69), (0.69, TOP + JAR_H, 0.69), "wetskin", faces=side4, face=True, **kw)
        if water:
            wt = k.texture("water", water_tex())
            for f in s.frames(poses=4):
                with f:
                    lift = 0.012 + 0.02 * np.sin(2 * np.pi * f.t)
                    s.box((-0.6, TOP + JAR_H - 0.03, -0.6), (0.6, TOP + JAR_H + lift, 0.6), wt, faces=("up",), uv=[0, 0, 16, 16], **kw)
        else:
            s.box((-0.6, TOP + 0.18, -0.6), (0.6, TOP + JAR_H + 0.004, 0.6), hollow, faces=("up",), uv=[0, 0, 16, 16], **kw)
    with s.when("on:broken"):
        # 깨진 밑동: 낮은 살 테두리와 빈 속
        s.box((-0.4, TOP, -0.4), (0.4, TOP + 0.12, 0.4), "flesh", faces=side4, face=True, **kw)
        s.box((-0.37, TOP, -0.37), (0.37, TOP + 0.03, 0.37), hollow, faces=("up",), uv=[0, 0, 16, 16], **kw)


def legs(s, wave_of, length=0.86, lean=-0.45):
    """새 다리 둘: 허벅지는 뒤 아래로, 정강이는 앞 아래로(무릎이 뒤로 꺾임)"""
    for side in (1, -1):
        s.rod((side * 0.2, -0.36, -0.02), (side * 0.08, -1.0, lean), length=length, segs=2, thick=0.12, wave=wave_of(side), toward=(0, 0, 1),
              poses=4)


def build(k: kit.Kit):
    m = k.model(poses=4, period=8, mut2=dict(length=0.6, segs=1, poses=2))
    # 달리기: 다리가 반대 위상으로 바삐(8틱 한 바퀴), 물이 찰랑
    s = m.state("")
    s.head(face="smile", **HEAD)
    jar(k, s, None, True, None)
    legs(s, lambda side: kit.Wave(amp=(30, 26), lag=50, bias=(0, 58), phase=0 if side > 0 else 180))
    # 절: 얼굴과 물동이가 같이 앞으로 숙여짐(빈 속이 앞을 봄), 무릎을 깊이 굽히고 가만히
    b = m.state("bow")
    pivot = (0, -0.3, 0)
    b.head(face="smile", rot=(68, 0, 0), at=(0, -0.3 + 0.3 * np.cos(np.radians(68)), 0.3 * np.sin(np.radians(68))), **HEAD)
    # 숙인 물동이: 상자를 그대로 두고 목(pivot)을 축으로 돌린다
    jar(k, b, (68, 0, 0), False, pivot)
    legs(b, lambda side: kit.Wave(amp=(2, 3), lag=30, bias=(-18, 96), phase=0 if side > 0 else 90), length=0.8)
    # 물 없음: 눈물, 다리 후들(같은 주기에 두 번)
    d = m.state("dry")
    d.head(face=faces.Face(extras=("tears",)), **HEAD)
    jar(k, d, None, False, None)
    legs(d, lambda side: kit.Wave(amp=(9, 12), lag=40, bias=(0, 58), phase=0 if side > 0 else 180, cycles=2))


def sounds(k: kit.Kit):
    k.sample("slosh", "tag:splash", "찰랑", pitch=1.25, volume=0.7, attenuation=20)
    k.sample("surge", ["oga_water40/splash_04", "oga_water40/splash_01"], "출렁", pitch=0.8, attenuation=20)
    k.sample("spill", ["oga_water40/splash_05", "oga_water40/splash_03"], "쏟아지는 물", pitch=0.65, attenuation=20)
    k.sample("bump", "tag:punch", "받힘", pitch=0.9, attenuation=16)


def phrases():
    return {"cause_g59": "물동이에 받혔다"}
