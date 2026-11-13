"""G183 가짜 트럭 게이브(3막, 목표 압박, 함정: 3.2 x 6.8 블록 트럭 꼴 껍데기).

멀리서는 연기 속에 선 흰 상자 화물차: 짐칸, 낮은 운전석, 바퀴 넷, 운전석 지붕 위 등불, 지붕 위 체력 막대(Java 가 글 줄로, 줄지 않음).
가까이서 보면 몸은 트럭 빛으로 칠한 _Gave 얼굴 판을 상자 꼴로 이어 붙인 껍데기, 바퀴는 옆으로 누운 얼굴, 등불은 엔드 막대기 둘을 엇갈려 세운 대 위의
실눈 얼굴이다. 짐칸 뒷문(모델 앞 +Z)이 입이고 문 안쪽 가득 이빨 대신 엔드 막대기가 박혀 있다(진짜 트럭 모델은 쓰지 않음, 윤곽만 따라 함).

- gave:gabe/g183(shrink 0.38): 몸 가운데(0, 0, 0) = 엔진 pos, 바닥 y -1.6(Java HOVER 1.6). 앞(+Z) = 짐칸 문(입), 운전석은 -Z.
  상태 ""(닫힘), "ajar"(문이 들썩), "open"(아가리), "s", "s_open"(껍데기가 벗겨진 꼴: 등불이 깨진 뒤). 부위 w1..w4(그 바퀴가 굴러 나가 비었음).
- gave:gabe/g183_lamp: 등불 대 + 등불 얼굴(부품, 바닥 (0, 0, 0) = 운전석 지붕). 상태 "", "broken".
- gave:gabe/g183_wheel: 굴러 나온 바퀴 얼굴(같은 종의 다른 꼴 개체, 4 자세로 구름, 앞 +Z 로 굴러감, 얼굴은 +X 옆을 봄).
소리 horn(가짜 경적, 64), door(짐칸 문, 32), maw(아가리가 덮침, 32), roll(바퀴 굴러 나감, 24), smash(등불 깨짐, 48).
"""
import math

import numpy as np

from gabe import faces, kit

S = 0.38
GROUND = -1.6
CARGO = ((-1.6, -0.85, -0.95), (1.6, 1.35, 3.3))
CAB = ((-1.45, -0.85, -3.35), (1.45, 0.75, -0.95))
WHEELS = ((1.62, -2.3), (-1.62, -2.3), (1.62, 2.25), (-1.62, 2.25))
WHEEL_Y = -1.05
PAINT = np.array((217, 211, 197), float)
PANEL_FACES = ("smile", "smile_a", "half", "smile_c", "smile", "tears", "stitch", "smile")


def tint(img, rgb, keep=0.2, gain=1.15):
    a = np.asarray(img).astype(float)
    lum = (0.3 * a[..., 0] + 0.59 * a[..., 1] + 0.11 * a[..., 2]) / 255.0
    out = a.copy()
    for c in range(3):
        out[..., c] = np.clip(lum * rgb[c] * gain * (1 - keep) + a[..., c] * keep, 0, 255)
    out[..., 3] = 255
    return out.astype(np.uint8)


def textures(k):
    t = {"paint": [k.texture(f"paint{i}", tint(faces.far(faces.preset(n)), PAINT)) for i, n in enumerate(PANEL_FACES)]}
    t["paint_open"] = k.texture("paint_open", tint(faces.far(faces.preset("open")), PAINT))
    t["shell"] = k.texture("shell", tint(faces.skin_side("back"), PAINT, keep=0.1))
    t["dark"] = k.texture("inner", tint(faces.far(faces.preset("blank")), np.array((70, 22, 26), float), keep=0.0, gain=1.0))
    t["glass"] = k.texture("glass", tint(faces.far(faces.preset("bloodshot")), np.array((90, 110, 130), float), keep=0.35, gain=1.0))
    return t


def shell(s, k, t, stripped):
    flesh = k.part_tex("flesh")
    base = flesh if stripped else t["shell"]
    (x0, y0, z0), (x1, y1, z1) = CARGO
    s.box(CARGO[0], CARGO[1], base, faces=("east", "west", "up", "down", "north"), face=True)
    (cx0, cy0, cz0), (cx1, cy1, cz1) = CAB
    s.box(CAB[0], CAB[1], base, faces=("east", "west", "up", "north", "down"), face=True)
    s.box((-1.35, -1.2, -3.2), (1.35, -0.85, 3.15), k.skin_tex("bottom"), faces=("east", "west", "north", "south", "down"), face=True)
    # 짐칸 옆과 지붕 얼굴 판(벗겨진 꼴은 몇 장만 너덜너덜 남음)
    keep = (0, 3, 5, 8, 13) if stripped else None
    n = 0
    for side, facing in ((1, "east"), (-1, "west")):
        for zi, z in enumerate((-0.2, 1.17, 2.55)):
            for yi, y in enumerate((-0.3, 0.8)):
                n += 1
                if keep is not None and n not in keep:
                    continue
                rot = (0, 0, 9 * side) if stripped else None
                s.quad((side * (x1 + 0.02), y, z), (1.32, 1.05), facing, t["paint"][(zi * 2 + yi + (side > 0)) % 8], face=True, rot=rot,
                       origin=(side * (x1 + 0.02), y, z) if rot else None)
    for zi, z in enumerate((-0.2, 1.17, 2.55)):
        for x in (-0.8, 0.8):
            n += 1
            if keep is not None and n not in keep:
                continue
            s.quad((x, y1 + 0.02, z), (1.55, 1.35), "up", t["paint"][(zi + int(x > 0) * 3) % 8], face=True)
    # 운전석: 옆 판 둘, 앞 유리(핏발 선 눈), 지붕
    if not stripped:
        for side, facing in ((1, "east"), (-1, "west")):
            s.quad((side * (cx1 + 0.02), -0.05, -2.15), (1.9, 1.4), facing, t["paint"][3 + (side > 0)], face=True)
        s.quad((0, -0.05, cz0 - 0.02), (2.6, 1.35), "north", t["glass"], face=True)
        s.quad((0, cy1 + 0.02, -2.15), (2.6, 2.1), "up", t["paint"][6], face=True)
    else:
        s.quad((0, -0.05, cz0 - 0.02), (2.0, 1.2), "north", k.face_tex("bloodshot"), face=True)
    # 바퀴: 옆으로 누운 얼굴 넷(굴러 나가면 빈 자리)
    for i, (x, z) in enumerate(WHEELS):
        side = 1 if x > 0 else -1
        with s.when(f"!on:w{i + 1}"):
            s.head(size=1.1, depth=0.45, at=(x, WHEEL_Y, z), face="smile" if i % 2 else "half", near=False, open=False, torn=False,
                   rot=kit.euler_of(kit.rot_axis((0, 1, 0), 90 * side) @ kit.rot_axis((0, 0, 1), 90)))


def door(s, k, t, angle, stripped):
    """짐칸 뒷문 두 짝(경첩 x = +-1.6, z = 3.3), angle = 바깥으로 연 각(도)"""
    (x0, y0, z0), (x1, y1, z1) = CARGO
    for side in (1, -1):
        hinge = (side * x1, 0, z1)
        rot = (0, side * angle, 0) if angle else None
        lo = (0 if side > 0 else -x1, y0, z1) if side > 0 else (-x1, y0, z1)
        hi = (x1, y1, z1 + 0.12) if side > 0 else (0, y1, z1 + 0.12)
        s.box(lo, hi, {"south": t["shell"] if not stripped else k.part_tex("flesh"), "north": t["dark"], "up": t["shell"], "down": t["shell"],
              "east": t["shell"], "west": t["shell"]}, uv=[0, 0, 16, 16], face=True, rot=rot, origin=hinge)
        fc = (side * 0.8, 0.25, z1 + 0.14)
        f = t["paint_open"] if angle >= 60 else t["paint"][1 if side > 0 else 2]
        s.quad(fc, (1.4, 1.4), "south", f, face=True, rot=rot, origin=hinge)


def interior(s, k, t):
    """열린 짐칸 속: 안쪽을 보는 검붉은 벽 + 위아래 옆에서 비죽 나온 엔드 막대기 이빨"""
    (x0, y0, z0), (x1, y1, z1) = CARGO
    d = t["dark"]
    s.quad((0, 0.25, z0 + 0.06), (3.1, 2.1), "south", d, face=True)
    s.quad((x1 - 0.06, 0.25, 1.17), (4.2, 2.1), "west", d, face=True)
    s.quad((x0 + 0.06, 0.25, 1.17), (4.2, 2.1), "east", d, face=True)
    s.quad((0, y1 - 0.06, 1.17), (3.1, 4.2), "down", d, face=True)
    s.quad((0, y0 + 0.06, 1.17), (3.1, 4.2), "up", d, face=True)
    for i, z in enumerate((3.0, 2.2, 1.4)):
        for x in (-1.0, 0.0, 1.0):
            s.rod((x + 0.15 * (i % 2), y1 - 0.08, z), (0, -1, 0.35), length=0.75, segs=1, thick=0.1, base=False, toward=(1, 0, 0), rag=False)
            s.rod((x - 0.15 * (i % 2), y0 + 0.08, z), (0, 1, 0.35), length=0.65, segs=1, thick=0.1, base=False, toward=(1, 0, 0), rag=False)


def lamp(k):
    m = k.model("g183_lamp", poses=1, period=20, shrink=0.6, mut2=False, torn=False, rag=False)
    for st in ("", "broken"):
        s = m.state(st)
        for side in (1, -1):
            if st == "":
                s.rod((side * 0.35, 0.0, 0), (-side * 0.32, 1, 0), length=1.1, segs=1, thick=0.13, base=True, toward=(0, 0, 1), rag=False)
            else:
                s.rod((side * 0.35, 0.0, 0), (-side * 0.6, 1, 0.5 * side), length=0.9, segs=1, thick=0.13, base=True, toward=(0, 0, 1), rag=False)
        if st == "":
            s.head(size=0.75, depth=0.6, at=(0, 1.35, 0), face=faces.Face(blush="gold"), near=False)
        else:
            s.box((-0.3, 0.7, -0.25), (0.3, 0.95, 0.25), "flesh", face=True)
            s.head(size=0.5, depth=0.4, at=(0.15, 0.95, 0.1), face="torn", near=False, rot=(25, 20, 40))
        s.front = (0, 0, 1)


def wheel(k):
    m = k.model("g183_wheel", poses=4, period=8, mut2=False, torn=False)
    s = m.state("")
    for f in s.frames():
        with f:
            s.head(size=1.1, depth=0.45, at=(0, 0, 0), face="smile", near=False,
                   rot=kit.euler_of(kit.rot_axis((0, 1, 0), 90) @ kit.rot_axis((0, 0, 1), -f.k * 90)))
    s.front = (1, 0, 0)


def build(k: kit.Kit):
    t = textures(k)
    m = k.model(poses=1, period=20, shrink=S, mut2=False, torn=False)
    for st, angle, stripped in (("", 0, False), ("ajar", 14, False), ("open", 105, False), ("s", 0, True), ("s_open", 105, True)):
        s = m.state(st)
        shell(s, k, t, stripped)
        door(s, k, t, angle, stripped)
        if angle:
            interior(s, k, t)
        s.front = (0, 0, 1)
    lamp(k)
    wheel(k)
    k.icon(model="g183", state="open")


def sounds(k: kit.Kit):
    k.sample("horn", "tag:carhorn", "가짜 경적", pitch=0.92, attenuation=64, n=1)
    k.sample("door", "tag:door+car", "짐칸 문", pitch=0.85, attenuation=32)
    k.sample("maw", "tag:monster+eat", "짐칸 아가리가 덮침", pitch=0.6, attenuation=32)
    k.sample("roll", "tag:tire", "바퀴 얼굴이 굴러 나감", pitch=0.8, attenuation=24, n=1)
    k.sample("smash", "tag:glass", "가짜 등불이 깨짐", pitch=0.7, attenuation=48)


def phrases():
    return {"cause_g183": "가짜 트럭의 짐칸에 삼켜졌다", "cause_g183_maw": "가짜 트럭의 아가리에 물렸다", "cause_g183_wheel": "굴러 온 바퀴 얼굴에 치였다"}
