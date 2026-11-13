"""G68 오뚝이 게이브: 아래가 둥글고 무거운 1.4 블록 살 공 몸 위에 1.0 블록 얼굴이 통째로 얹힌 오뚝이. 엔드 막대기 둘이 앞으로 뻗어
끝을 위로 꺾은 손바닥 꼴(달라고 내민 손). 뜯어 간 재화가 배 앞에 금빛으로 비친다(받을 때마다 Java 가 몸을 부풀림 1.0 → 1.7 배).
넓은 밑동과 좁은 얼굴의 오뚝이 실루엣.

모델 gave:gabe/g68 상태 ""(뒤뚱: 손이 아래로 늘어져 흔들림, 4자세), "ask"(손 내밂, 손바닥 위로), "back"(손을 위로 쳐든 채: Java 가 몸을 뒤로 젖힘).
부위 coin1..coin3(배 속 동전). 소리 jingle(짤랑이는 배), nod(끄덕), bump(박치기), crush(깔아뭉갬), 글귀 cause_g68, ask_g68_10/20/40(값 게이지 글).
"""
import numpy as np

from gabe import kit


def coin_tex():
    """금빛 동전(가운데 구멍 난 옛 돈), 바깥 투명"""
    yy, xx = np.mgrid[0:16, 0:16].astype(float)
    r = np.hypot(xx - 7.5, yy - 7.5)
    out = np.zeros((16, 16, 4), np.uint8)
    col = np.array([236, 182, 54], float) * (0.85 + 0.15 * np.cos((xx + yy) * 0.5))[..., None]
    col[(r > 5.6)] = (176, 120, 30)
    out[..., :3] = np.clip(np.round(col), 0, 255)
    disc = r <= 7.2
    hole = (np.abs(xx - 7.5) < 1.6) & (np.abs(yy - 7.5) < 1.6)
    out[..., 3] = np.where(disc & ~hole, 255, 0)
    return out


def tumbler(k, s, name):
    # 밑동: 넓고 둥근 살 공(가운데가 가장 넓고 위아래로 좁아짐)
    for (w, y0, y1) in ((1.1, -0.78, -0.66), (1.4, -0.68, -0.18), (1.2, -0.2, -0.06)):
        s.box((-w / 2, y0, -w / 2 + 0.03), (w / 2, y1, w / 2 - 0.03), "flesh", face=True)
    s.head(size=1.0, depth=0.95, at=(0, 0.42, 0), face="smile")
    # 배 속 동전(받은 만큼)
    coin = k.texture("coin", coin_tex())
    for i, (x, y) in enumerate(((-0.33, -0.48), (0.0, -0.36), (0.33, -0.48))):
        with s.when(f"on:coin{i + 1}"):
            s.box((x - 0.17, y - 0.17, 0.665), (x + 0.17, y + 0.17, 0.675), coin, faces=("south",), uv=[0, 0, 16, 16])
    # 손: 엔드 막대기 팔 + 위로 꺾은 손바닥
    for side in (1, -1):
        if name == "ask":
            arm = s.rod((side * 0.62, -0.25, 0.3), (side * 0.12, 0.05, 1.0), length=0.9, segs=2, thick=0.11, toward=(0, 1, 0), rag=False)
            tip = tuple(arm.tip())
            s.rod(tip, (side * 0.1, 1.0, 0.25), length=0.3, segs=1, thick=0.1, toward=(0, 0, 1), base=False, tex="bone", rag=False)
        elif name == "back":
            arm = s.rod((side * 0.62, -0.1, 0.1), (side * 0.5, 1.0, -0.2), length=0.85, segs=2, thick=0.11, toward=(0, 0, -1), rag=False)
            s.rod(tuple(arm.tip()), (side * 0.3, 0.6, 0.8), length=0.26, segs=1, thick=0.1, toward=(0, 1, 0), base=False, tex="bone", rag=False)
        else:
            s.rod((side * 0.66, -0.3, 0.25), (side * 0.45, -0.7, 0.55), length=0.7, segs=2, thick=0.11, toward=(0, 0, 1),
                  wave=kit.Wave(amp=(10, 14), lag=40, phase=0 if side > 0 else 180), poses=4)


def build(k: kit.Kit):
    m = k.model(poses=4, period=12, shrink=0.75, mut2=dict(root=(0, 0.85, -0.3), dir=(0.4, 1.0, -0.5), length=0.55, segs=1, poses=2))
    for name in ("", "ask", "back"):
        tumbler(k, m.state(name), name)


def sounds(k: kit.Kit):
    k.sample("jingle", "tag:coin", "짤랑이는 배", pitch=0.9, volume=1.0, attenuation=20)
    k.sample("nod", "tag:soft", "끄덕", pitch=1.1, volume=0.8, attenuation=12)
    k.sample("bump", "tag:punch", "박치기", pitch=0.75, attenuation=16)
    k.sample("crush", "tag:slam", "깔아뭉갬", pitch=0.7, attenuation=24)


def phrases():
    return {"cause_g68": "오뚝이에 깔렸다", "ask_g68_10": "10원 내놔", "ask_g68_20": "20원 내놔", "ask_g68_40": "40원 내놔"}
