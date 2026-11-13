"""G23 코앞 게이브: 1.3배 얼굴(1.04 블록), 엔드 막대기 둘은 뒤에서 X자로 접혀 꿈틀. 파생 그림 4단계: 실눈, 반쯤 뜬 눈, 다 뜬 눈(반짝임이
동공만큼 커짐), 점 입이 살짝 벌어짐.

모델 gave:gabe/g23 상태 "e0".."e3"(눈 뜨임 단계, 기본 e0). 남들에게만 보임(대상 본인에게는 Java 가 숨기고 화면 덮개로 그림).
덮개 g23_e0 .. g23_e3: 대상 본인 화면 가운데 70% 를 가리는 코앞 얼굴(가장자리는 비움), g23_fear: 겁먹음(화면 가장자리 어둠).
"""
import numpy as np
from PIL import Image

from gabe import audio, faces, kit

SIZE = 1.04
DEPTH = 0.62
STAGES = [faces.Face(), faces.Face(eyes="half"), faces.Face(eyes="open"), faces.Face(eyes="open")]
# 32 판 기준 눈동자 자리(open 눈: 왼눈 x 6..8, 오른눈 23..25, y 13..15), 입 자리
SPARK = [(6, 13), (23, 13)]


def stage_img(i, near):
    """단계 i 의 얼굴(32 또는 128): 2 이상은 반짝임을 동공만큼 키우고, 3 은 점 입이 살짝 벌어짐"""
    f = STAGES[i]
    a = (faces.near(f) if near else faces.far(f)).copy()
    k = a.shape[0] // 32
    if i >= 2:
        for (x, y) in SPARK:
            for yy in range(y * k, (y + 2) * k + (k // 2)):
                for xx in range(x * k, (x + 2) * k + (k // 2)):
                    dx = (xx + 0.5) / k - (x + 1.25)
                    dy = (yy + 0.5) / k - (y + 1.25)
                    if dx * dx + dy * dy <= 1.35:
                        a[yy, xx, :3] = (252, 250, 250)
                        a[yy, xx, 2] = (250 & 0xF8) | faces.CLS_SPARK
    if i >= 3:
        # 점 입(16..19, 24..27)이 아래로 살짝 벌어짐: 안쪽 어둠 + 아랫니 한 줄
        for yy in range(24 * k, 30 * k):
            for xx in range(15 * k, 21 * k):
                dx = ((xx + 0.5) / k - 18.0) / 2.6
                dy = ((yy + 0.5) / k - 26.6) / 2.9
                r = dx * dx + dy * dy
                if r <= 1.0:
                    inner = r <= 0.55
                    col = (40, 10, 12) if inner else (105, 32, 32)
                    a[yy, xx, :3] = col
                    a[yy, xx, 2] = (col[2] & 0xF8) | faces.CLS_MOUTH
                    if inner and (yy / k) > 27.6 and int(xx / k) % 2 == 0:
                        a[yy, xx, :3] = (236, 226, 208)
                        a[yy, xx, 2] = (208 & 0xF8) | faces.CLS_TEETH
    return a


def cover_img(i, W=480, H=270):
    """대상 화면: 가운데 70% 를 가리는 코앞 얼굴(가까운 얼굴 그림을 키움), 가장자리로 갈수록 투명. 단계마다 눈이 뜨임"""
    face = Image.fromarray(stage_img(i, True)).resize((int(H * 1.25), int(H * 1.25)), Image.BICUBIC)
    fa = np.asarray(face, np.float32)
    out = np.zeros((H, W, 4), np.float32)
    s = fa.shape[0]
    ox, oy = (W - s) // 2, (H - s) // 2 + 8
    ys, xs = max(0, -oy), max(0, -ox)
    ye, xe = min(s, H - oy), min(s, W - ox)
    out[oy + ys:oy + ye, ox + xs:ox + xe] = fa[ys:ye, xs:xe]
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    # 화면 가운데 타원 70%: 안은 불투명, 가장자리 띠에서 빠르게 투명
    r = np.hypot((xx - W / 2) / (W * 0.42), (yy - H / 2) / (H * 0.6))
    alpha = np.clip((1.0 - r) / 0.12, 0, 1) * (out[..., 3] / 255.0)
    out[..., 3] = alpha * 255
    out[..., :3] *= (0.75 + 0.25 * np.clip(1.2 - r, 0, 1))[..., None]
    return Image.fromarray(np.clip(np.round(out), 0, 255).astype(np.uint8))


def fear_img(W=480, H=270):
    """겁먹음: 코앞 얼굴(다 뜬 눈, 벌어진 입)은 그대로 두고 그 위로 화면 가장자리가 짙게 어두워짐(붉은 기). 덮개는 한 번에 하나라
    얼굴과 어둠을 한 그림에 담는다(어둠만 그리면 겁먹은 동안 코앞 얼굴이 사라져 오히려 잘 보이게 됨)"""
    base = np.asarray(cover_img(3, W, H), np.float32)
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    r = np.hypot((xx - W / 2) / (W / 2), (yy - H / 2) / (H / 2))
    da = (np.clip((r - 0.45) / 0.55, 0, 1) ** 1.4 * (235 / 255.0))[..., None]
    fa = base[..., 3:4] / 255.0
    dark = np.array([26, 6, 10], np.float32)
    alpha = da + fa * (1 - da)
    out = np.zeros((H, W, 4), np.float32)
    out[..., :3] = (dark * da + base[..., :3] * fa * (1 - da)) / np.maximum(alpha, 1e-6)
    out[..., 3:4] = alpha * 255
    return Image.fromarray(np.clip(np.round(out), 0, 255).astype(np.uint8))


def build(k: kit.Kit):
    m = k.model(poses=6, period=24, mut2=dict(root=lambda side: (side * 0.3, 0.45, -0.3), dir=(0.4, 1.0, -0.6), length=0.6, poses=4))
    for i in range(4):
        s = m.state(f"e{i}")
        h = s.head(size=SIZE, depth=DEPTH, face=STAGES[i], open=False)
        far = k.texture(f"e{i}", stage_img(i, False), classes=True)
        near = k.texture(f"e{i}_n", stage_img(i, True), classes=True)
        z = DEPTH / 2 + 0.008
        s.quad((0, 0, z), (SIZE, SIZE), "south", far, dist="far", face=True)
        s.quad((0, 0, z + 0.004), (SIZE, SIZE), "south", near, dist="near", face=True)
        # 막대기 둘: 뒤통수에서 X 자로 접혀 엇갈림(조금씩 꿈틀)
        for side in (1, -1):
            s.rod(h.point(side * 0.75, 0.75, -0.9), (-side * 0.7, -1.0, -0.35), length=1.05, segs=2, thick=0.09,
                  wave=kit.Wave(amp=(6, 10), lag=50, phase=0 if side > 0 else 180), toward=(0, 0, -1))
        k.cover(f"g23_e{i}", cover_img(i))
    m.default = "e0"
    k.cover("g23_fear", fear_img())
    k.icon(model="g23", state="e2")


def squelch(seed=0, dur=0.3):
    """'쩍': 끈적하게 눈꺼풀이 떨어지는 소리"""
    pop = audio.env(audio.bandpass(audio.noise(dur, seed), 500, 3000), 0.001, 0.03, 0.25, 0.1)
    t = audio.env(audio.tone(320, dur, glide=520), 0.002, 0.05, 0.2, 0.1) * 0.5
    return audio.normalize(audio.fade(audio.mix(pop, t), 0.001, 0.02), 0.8)


def sounds(k: kit.Kit):
    # 이 종의 신호: 눈이 한 단계 뜨일 때마다 끈적한 '쩍'
    k.sound("squelch", "쩍", synth=lambda i: squelch(seed=120 + i), attenuation=16)
    k.sound("fear", "숨이 막힘", vanilla=[("mob/warden/heartbeat_1", 0.8)], volume=1.0, attenuation=8)
    k.sound("cling", "코앞에 붙음", synth=lambda i: audio.squish(seed=124 + i, dur=0.22), attenuation=16)


def phrases():
    return {"cause_g23": "코앞 얼굴을 마주 봤다", "notice_g23_fear": "겁먹었다"}
