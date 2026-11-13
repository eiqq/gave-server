"""G174 손자국 게이브: 땅에 찍힌 지름 2 블록의 검은 손자국 얼룩. 손바닥 자리 가운데에 _Gave 얼굴 하나가 실눈으로 땅에 눌려 하늘을 보고,
손가락 다섯 자리 끝에 짧은 엔드 막대기가 손톱처럼 땅에서 비죽 솟아 있다. 얼룩에서 손이 기어 나온다: 0.5 블록 얼굴 손바닥에 엔드 막대기
손가락 다섯, 손가락으로 땅을 짚고 거미처럼 긴다.

모델 gave:gabe/g174(얼룩, 몸 중심 = 땅 위 0.03, 납작): 상태 ""(실눈, Java 가 발을 들이면 look.open 으로 눈 뜸). 사체 없음.
모델 gave:gabe/g174_hand(손, 몸 중심 = 땅 위 0.32): 상태 ""(기기 4자세, 8틱).
소리 crawl(딸깍 기어 나오는 손, 24), grip(발목을 움켜쥠, 20), claw(할큄, 16), burn(얼룩이 타 지워짐, 24). 글귀 cause_g174, cause_g174_stain.
"""
import numpy as np

from gabe import kit

# 손자국(64x64, 2 블록): 손바닥 타원 + 손가락 넷 + 엄지
FINGERS = [(-0.62, 0.95, 0.15), (-0.2, 1.08, 0.15), (0.22, 1.04, 0.14), (0.6, 0.86, 0.13), (0.95, 0.05, 0.14)]  # (x, z, 반지름) 블록, z + 앞


def stain_tex(n=64, seed=174):
    rng = np.random.RandomState(seed)
    out = np.zeros((n, n, 4), np.uint8)
    ys, xs = np.mgrid[0:n, 0:n]
    # 픽셀 → 블록(가운데 0, -1..1), 텍스처 위쪽이 앞(+Z)
    bx = (xs + 0.5) / n * 2 - 1
    bz = 1 - (ys + 0.5) / n * 2
    m = (bx / 0.62) ** 2 + ((bz + 0.12) / 0.66) ** 2 <= 1.0
    for fx, fz, r in FINGERS:
        # 손가락: 손바닥 가운데에서 끝까지 굵은 선
        t = np.clip((bx * fx + (bz + 0.1) * (fz + 0.1)) / (fx * fx + (fz + 0.1) ** 2), 0, 1)
        dx, dz = bx - t * fx, bz + 0.1 - t * (fz + 0.1)
        m |= dx * dx + dz * dz <= r * r
    # 가장자리 번짐(얼룩)
    edge = (np.hypot(bx, bz) < 1.0) & (rng.rand(n, n) < 0.05)
    m |= edge & (np.abs(bx) < 0.95)
    col = np.zeros((n, n, 3), np.float32) + (22, 14, 16)
    col += rng.randint(-6, 7, (n, n, 1))
    out[..., :3] = np.clip(col, 0, 255).astype(np.uint8)
    out[..., 3] = np.where(m, 255, 0).astype(np.uint8)
    # 'up' 면의 텍스처 위쪽은 모델 뒤(-Z): 손가락이 앞(+Z, 손톱 막대기 쪽)을 보게 위아래를 뒤집음
    return np.ascontiguousarray(out[::-1])


def build(k: kit.Kit):
    m = k.model(poses=1, period=20, mut2=False, torn=False, rag=False)
    s = m.state("")
    s.quad((0, 0.0, 0), (2, 2), "up", k.texture("print", stain_tex()), face=False)
    # 손바닥 가운데 얼굴: 땅에 눌려 하늘을 봄
    s.head(size=0.62, depth=0.1, at=(0, 0.05, -0.1), face="smile", rot=(-90, 0, 0))
    # 손톱 막대기: 손가락 끝에서 땅 위로 비죽
    for fx, fz, _ in FINGERS:
        s.rod((fx * 0.98, 0.0, fz * 0.98), (fx * 0.3, 1.0, fz * 0.3), length=0.3, segs=1, thick=0.06, base=False, rag=False)
    s.front = (0, 1, 0)

    h = k.model("g174_hand", poses=4, period=8, mut2=False, torn=False)
    s = h.state("")
    hd = s.head(size=0.5, depth=0.2, at=(0, 0.06, 0), face="half", rot=(-40, 0, 0))
    crawl = [kit.Wave(amp=(16, 22), lag=50, bias=(0, 28), phase=ph) for ph in (0, 90, 180, 270, 45)]
    for i, (u, d) in enumerate([(-0.8, (-0.45, -0.55, 1)), (-0.28, (-0.15, -0.6, 1)), (0.28, (0.15, -0.6, 1)), (0.8, (0.45, -0.55, 1)),
                                (1.0, (1, -0.6, 0.1))]):
        root = hd.point(u, -0.6, 0.0) if i < 4 else hd.point(1.0, -0.2, -0.2)
        s.rod(root, d, length=0.5, segs=2, thick=0.055, wave=crawl[i], toward=(0, 1, 0), base=False, poses=4)
    k.icon(model="g174_hand", state="")


def sounds(k: kit.Kit):
    # 이 종 전용 신호: 얼룩에서 손이 기어 나올 때 바닥을 딸깍딸깍 긁는 소리
    k.sample("crawl", "tag:flesh", "딸깍 기어 나오는 손", pitch=1.5, attenuation=24)
    k.sample("grip", "tag:slime", "발목을 움켜쥠", pitch=0.8, attenuation=20)
    k.sample("claw", "kenney_rpg/knifeslice*", "할큄", pitch=1.3, attenuation=16, n=2)
    k.sample("burn", "tag:fire", "얼룩이 타 지워짐", pitch=0.9, attenuation=24)


def phrases():
    return {"cause_g174": "기어 나온 손에 할퀴었다", "cause_g174_stain": "손자국이 발목을 움켜쥐었다"}
