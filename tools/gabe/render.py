"""모델 요소를 정사영으로 그리는 작은 그림기(HUD 아이콘, 종 담당 미리보기). 게임 화면과 같지는 않다(빛, 셰이더 없음).

요소(kit 의 Elem)는 px 좌표 from/to, 회전(원점, 3x3 행렬), 면(방향 → uv, 텍스처 id). 면 꼭짓점과 uv 짝은 마인크래프트 규칙대로
(남 면은 +Z 에서 볼 때 왼쪽 위가 (x0, y1), uv 의 u1, v1).
"""
import numpy as np
from PIL import Image

# 면 → 꼭짓점 (왼위, 오른위, 오른아래, 왼아래) 를 (x0|x1, y0|y1, z0|z1) 고르는 표(0 = from, 1 = to)
FACE_CORNERS = {
    "south": ((0, 1, 1), (1, 1, 1), (1, 0, 1), (0, 0, 1)),
    "north": ((1, 1, 0), (0, 1, 0), (0, 0, 0), (1, 0, 0)),
    "east": ((1, 1, 1), (1, 1, 0), (1, 0, 0), (1, 0, 1)),
    "west": ((0, 1, 0), (0, 1, 1), (0, 0, 1), (0, 0, 0)),
    "up": ((0, 1, 0), (1, 1, 0), (1, 1, 1), (0, 1, 1)),
    "down": ((0, 0, 1), (1, 0, 1), (1, 0, 0), (0, 0, 0)),
}
NORMALS = {"south": (0, 0, 1), "north": (0, 0, -1), "east": (1, 0, 0), "west": (-1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0)}
# 보는 쪽: front = +Z 에서(게이브 얼굴 정면), side = +X 에서, top = +Y 에서(앞이 아래), back = -Z 에서
VIEWS = {
    "front": np.array([[1, 0, 0], [0, -1, 0], [0, 0, 1]], np.float64),
    "back": np.array([[-1, 0, 0], [0, -1, 0], [0, 0, -1]], np.float64),
    "side": np.array([[0, 0, -1], [0, -1, 0], [1, 0, 0]], np.float64),
    "top": np.array([[1, 0, 0], [0, 0, 1], [0, 1, 0]], np.float64),
}
SHADE = {"south": 1.0, "north": 0.7, "east": 0.85, "west": 0.85, "up": 1.0, "down": 0.6}


def quads(elems):
    """요소들 → 그릴 면 목록 [(꼭짓점 4 (px, 회전 반영), 바깥 법선, uv, 텍스처 id, 면 이름)]"""
    out = []
    for e in elems:
        f, t = np.array(e.frm, np.float64), np.array(e.to, np.float64)
        R = e.rot_m if e.rot_m is not None else np.eye(3)
        o = np.array(e.origin if e.origin is not None else (8, 8, 8), np.float64)
        for name, fc in e.faces.items():
            corners = []
            for sel in FACE_CORNERS[name]:
                p = np.array([t[i] if sel[i] else f[i] for i in range(3)])
                corners.append(R @ (p - o) + o)
            n = R @ np.array(NORMALS[name], np.float64)
            out.append((np.array(corners), n, fc.uv, fc.tex, name))
    return out


def render(elems, textures, view="front", size=64, margin=3, ss=4, fit=None, bg=None):
    """정사영 그림. textures: id → RGBA ndarray. fit: (xmin, ymin, xmax, ymax) 화면 좌표(px)를 주면 그 틀에 맞춤(여러 장 같은 배율용)"""
    V = VIEWS[view]
    qs = []
    for corners, n, uv, tex, name in quads(elems):
        sc = corners @ V.T  # 화면 x, y(아래로), 깊이(클수록 가까움)
        nz = (V @ n)[2]
        if nz <= 1e-4:
            continue
        qs.append((sc, uv, tex, name, nz))
    if not qs:
        return Image.new("RGBA", (size, size), bg or (0, 0, 0, 0)), None
    pts = np.concatenate([q[0][:, :2] for q in qs])
    if fit is None:
        fit = (pts[:, 0].min(), pts[:, 1].min(), pts[:, 0].max(), pts[:, 1].max())
    x0, y0, x1, y1 = fit
    W = size * ss
    span = max(x1 - x0, y1 - y0, 1e-3)
    scale = (W - 2 * margin * ss) / span
    ox = (W - (x1 - x0) * scale) / 2 - x0 * scale
    oy = (W - (y1 - y0) * scale) / 2 - y0 * scale
    img = np.zeros((W, W, 4), np.float32)
    if bg:
        img[:] = bg
    qs.sort(key=lambda q: q[0][:, 2].mean())
    for sc, uv, tex, name, nz in qs:
        arr = textures.get(tex)
        if arr is None:
            continue
        P = sc[:, :2] * scale + np.array([ox, oy])
        tl, tr, br, bl = P
        ex, ey = tr - tl, bl - tl
        det = ex[0] * ey[1] - ex[1] * ey[0]
        if abs(det) < 1e-6:
            continue
        bx0, by0 = np.floor(P.min(0)).astype(int)
        bx1, by1 = np.ceil(P.max(0)).astype(int)
        bx0, by0 = max(bx0, 0), max(by0, 0)
        bx1, by1 = min(bx1, W - 1), min(by1, W - 1)
        if bx1 < bx0 or by1 < by0:
            continue
        ys, xs = np.mgrid[by0:by1 + 1, bx0:bx1 + 1].astype(np.float32)
        px, py = xs + 0.5 - tl[0], ys + 0.5 - tl[1]
        a = (px * ey[1] - py * ey[0]) / det
        b = (ex[0] * py - ex[1] * px) / det
        inside = (a >= 0) & (a <= 1) & (b >= 0) & (b <= 1)
        if not inside.any():
            continue
        u1, v1, u2, v2 = uv
        tu = (u1 + a * (u2 - u1)) / 16.0
        tv = (v1 + b * (v2 - v1)) / 16.0
        th, tw = arr.shape[:2]
        ix = np.clip((tu * tw).astype(int), 0, tw - 1)
        iy = np.clip((tv * th).astype(int), 0, th - 1)
        col = arr[iy, ix].astype(np.float32)
        ok = inside & (col[..., 3] > 0)
        light = SHADE[name] * (0.75 + 0.25 * nz)
        col[..., :3] *= light
        sub = img[by0:by1 + 1, bx0:bx1 + 1]
        sub[ok] = np.concatenate([col[ok][:, :3], np.full((ok.sum(), 1), 255, np.float32)], 1)
    # 줄이기(알파 가중)
    im = img.reshape(size, ss, size, ss, 4)
    a = im[..., 3:4].sum(axis=(1, 3))
    rgb = (im[..., :3] * im[..., 3:4]).sum(axis=(1, 3)) / np.maximum(a, 1e-3)
    out = np.concatenate([rgb, a / (ss * ss)], -1)
    return Image.fromarray(np.clip(np.round(out), 0, 255).astype(np.uint8)), fit


def bounds(elems, view="front"):
    V = VIEWS[view]
    pts = []
    for corners, n, uv, tex, name in quads(elems):
        pts.append((corners @ V.T)[:, :2])
    if not pts:
        return None
    p = np.concatenate(pts)
    return (p[:, 0].min(), p[:, 1].min(), p[:, 0].max(), p[:, 1].max())
