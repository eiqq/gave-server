"""바닐라(26.3) 요소 모델 다루기: 그리기(직교, z 버퍼), 회전 행렬, 베드록 geo 와 Blockbench bbmodel 을 요소로 바꾸기, 검사.

좌표는 모델 단위(16 = 1 블록). 면 꼭짓점, uv 순서는 바닐라 FaceInfo 와 같다(북 = -Z, 위에서 본 위 면의 텍스처 위쪽 = 북).
"""
import io
import math

import numpy as np
from PIL import Image, ImageDraw, ImageFont

try:  # 이름표에 한글이 나오게(윈도 맑은 고딕)
    ImageDraw.ImageDraw.font = ImageFont.truetype("malgun.ttf", 12)
except OSError:
    pass

# ---------------------------------------------------------------- 회전

def rx(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]], float)


def ry(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]], float)


def rz(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]], float)


def euler_zyx(x, y, z):
    """26.3 요소 오일러 회전, Blockbench, GeckoLib 뼈 회전 모두 Rz Ry Rx(도)"""
    return rz(math.radians(z)) @ ry(math.radians(y)) @ rx(math.radians(x))


def euler_xyz(x, y, z):
    """아이템 표시 변환(ItemTransform): Rx Ry Rz(도)"""
    return rx(math.radians(x)) @ ry(math.radians(y)) @ rz(math.radians(z))


def to_euler_zyx(R):
    """R = Rz Ry Rx → (x, y, z) 도"""
    y = math.asin(max(-1.0, min(1.0, -R[2, 0])))
    if abs(math.cos(y)) > 1e-6:
        x = math.atan2(R[2, 1], R[2, 2])
        z = math.atan2(R[1, 0], R[0, 0])
    else:
        x, z = math.atan2(-R[1, 2], R[1, 1]), 0.0
    return [round(math.degrees(v), 4) + 0.0 for v in (x, y, z)]


def element_matrix(el):
    r = el.get("rotation")
    if not r:
        return np.eye(3), np.zeros(3)
    o = np.array(r.get("origin", [8, 8, 8]), float)
    if "axis" in r:
        a = math.radians(r.get("angle", 0))
        R = {"x": rx, "y": ry, "z": rz}[r["axis"]](a)
    else:
        R = euler_zyx(r.get("x", 0), r.get("y", 0), r.get("z", 0))
    return R, o

# ---------------------------------------------------------------- 면

# 면마다 꼭짓점 4개(바닐라 FaceInfo 순서). 상자 lo, hi 에서 고른다: 0 = lo, 1 = hi
FACE_VERTS = {
    "down": [(0, 0, 1), (0, 0, 0), (1, 0, 0), (1, 0, 1)],
    "up": [(0, 1, 0), (0, 1, 1), (1, 1, 1), (1, 1, 0)],
    "north": [(1, 1, 0), (1, 0, 0), (0, 0, 0), (0, 1, 0)],
    "south": [(0, 1, 1), (0, 0, 1), (1, 0, 1), (1, 1, 1)],
    "west": [(0, 1, 0), (0, 0, 0), (0, 0, 1), (0, 1, 1)],
    "east": [(1, 1, 1), (1, 0, 1), (1, 0, 0), (1, 1, 0)],
}
NORMALS = {"down": (0, -1, 0), "up": (0, 1, 0), "north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0)}
# uv 꼭짓점 번호 → (u 쪽, v 쪽): 0 = (u1, v1), 1 = (u1, v2), 2 = (u2, v2), 3 = (u2, v1)
UV_IDX = [(0, 1), (0, 3), (2, 3), (2, 1)]


def default_uv(face, lo, hi):
    """uv 를 안 적은 면: 바닐라처럼 좌표에서 정함"""
    x0, y0, z0 = lo
    x1, y1, z1 = hi
    return {"down": [x0, 16 - z1, x1, 16 - z0], "up": [x0, z0, x1, z1], "north": [16 - x1, 16 - y1, 16 - x0, 16 - y0],
            "south": [x0, 16 - y1, x1, 16 - y0], "west": [z0, 16 - y1, z1, 16 - y0], "east": [16 - z1, 16 - y1, 16 - z0, 16 - y0]}[face]


def face_quads(el):
    """요소 → [(면 이름, 꼭짓점 4x3(회전 뒤), uv 4x2(0..16), 면 dict)]"""
    lo = np.array(el["from"], float)
    hi = np.array(el["to"], float)
    R, o = element_matrix(el)
    out = []
    for fn, f in el.get("faces", {}).items():
        P = np.array([[hi[i] if b else lo[i] for i, b in enumerate(v)] for v in FACE_VERTS[fn]], float)
        P = (P - o) @ R.T + o
        uv = f.get("uv") or default_uv(fn, lo, hi)
        rot = int(f.get("rotation", 0)) // 90 % 4
        U = np.array([[uv[UV_IDX[(i + rot) % 4][0]], uv[UV_IDX[(i + rot) % 4][1]]] for i in range(4)], float)
        out.append((fn, P, U, f))
    return out

# ---------------------------------------------------------------- 그리기

class Canvas:
    """직교 투영 z 버퍼 그림판. 보는 방향 d(카메라 → 모델), 위 up"""

    def __init__(self, size, d, up=(0, 1, 0), bg=(0, 0, 0, 0)):
        d = np.array(d, float); d /= np.linalg.norm(d)
        up = np.array(up, float)
        right = np.cross(d, up); right /= np.linalg.norm(right)
        cup = np.cross(right, d)
        self.V = np.array([right, cup, -d])  # 행: 화면 x, 화면 y, 깊이(클수록 가까움)
        self.d = d
        self.W, self.H = size
        self.img = np.zeros((self.H, self.W, 4), np.float32)
        self.img[:] = np.array(bg, np.float32) / 255
        self.z = np.full((self.H, self.W), -1e9, np.float32)
        self.scale, self.off = 1.0, np.zeros(2)

    def fit(self, pts, margin=0.06):
        q = np.asarray(pts) @ self.V.T
        lo, hi = q[:, :2].min(0), q[:, :2].max(0)
        span = np.maximum(hi - lo, 1e-6)
        self.scale = min(self.W * (1 - 2 * margin) / span[0], self.H * (1 - 2 * margin) / span[1])
        self.off = (lo + hi) / 2

    def proj(self, P):
        q = np.asarray(P, float) @ self.V.T
        sx = (q[:, 0] - self.off[0]) * self.scale + self.W / 2
        sy = -(q[:, 1] - self.off[1]) * self.scale + self.H / 2
        return np.stack([sx, sy, q[:, 2]], 1)

    def quad(self, P, U=None, tex=None, color=(200, 200, 200, 255), light=1.0, cutout=True):
        """P: 꼭짓점 4개(0 → 1 → 2 → 3, 평행사변형). U: 꼭짓점 uv(0..16), tex: RGBA numpy(0..1)"""
        S = self.proj(P)
        a, b, c = S[0], S[3], S[1]  # 원점, s 방향(0 → 3), t 방향(0 → 1)
        e1, e2 = b[:2] - a[:2], c[:2] - a[:2]
        det = e1[0] * e2[1] - e1[1] * e2[0]
        if abs(det) < 1e-9 or det < 0:  # 뒷면은 그리지 않음(게임도 아이템 면 뒤를 가린다)
            return
        x0, y0 = np.floor(S[:, :2].min(0)).astype(int)
        x1, y1 = np.ceil(S[:, :2].max(0)).astype(int)
        x0, y0 = max(x0, 0), max(y0, 0)
        x1, y1 = min(x1, self.W - 1), min(y1, self.H - 1)
        if x1 < x0 or y1 < y0:
            return
        ys, xs = np.mgrid[y0:y1 + 1, x0:x1 + 1]
        px, py = xs + 0.5 - a[0], ys + 0.5 - a[1]
        s = (px * e2[1] - py * e2[0]) / det
        t = (e1[0] * py - e1[1] * px) / det
        m = (s >= -1e-4) & (s <= 1 + 1e-4) & (t >= -1e-4) & (t <= 1 + 1e-4)
        if not m.any():
            return
        depth = a[2] + s * (b[2] - a[2]) + t * (c[2] - a[2])
        if tex is not None and U is not None:
            uv = U[0][None, None] + s[..., None] * (U[3] - U[0])[None, None] + t[..., None] * (U[1] - U[0])[None, None]
            th, tw = tex.shape[:2]
            tu = np.clip((uv[..., 0] / 16 * tw).astype(int), 0, tw - 1)
            tv = np.clip((uv[..., 1] / 16 * th).astype(int), 0, th - 1)
            col = tex[tv, tu]
        else:
            col = np.broadcast_to(np.array(color, np.float32) / 255, s.shape + (4,))
        m &= col[..., 3] > (0.5 if cutout else 0.01)
        zc = self.z[y0:y1 + 1, x0:x1 + 1]
        m &= depth > zc
        if not m.any():
            return
        rgb = col[..., :3] * light
        blk = self.img[y0:y1 + 1, x0:x1 + 1]
        blk[m, :3] = rgb[m]
        blk[m, 3] = 1.0
        zc[m] = depth[m]

    def image(self):
        return Image.fromarray((np.clip(self.img, 0, 1) * 255).astype(np.uint8), "RGBA")


LIGHT_DIR = np.array([0.35, 1.0, -0.55]) / np.linalg.norm([0.35, 1.0, -0.55])


def shade(n):
    return 0.55 + 0.45 * max(0.0, float(np.dot(n, LIGHT_DIR))) + 0.1 * max(0.0, -float(n[2]))


def draw_model(cv, elements, textures, M=np.eye(3), t=np.zeros(3), skip_back=False):
    """textures: '#변수' 또는 이름 → RGBA numpy. M, t: 모델 좌표에 거는 변환(점 = M p + t)"""
    for el in elements:
        for fn, P, U, f in face_quads(el):
            P2 = P @ M.T + t
            n = np.cross(P2[3] - P2[0], P2[1] - P2[0])
            nn = np.linalg.norm(n)
            if nn < 1e-9:
                n = np.array(NORMALS[fn], float)
            else:
                n = n / nn
                # 바깥 법선: 면 꼭짓점 순서(0→3 오른쪽, 0→1 아래)로 구한 외적 = 면 바깥 쪽
            tex = textures.get(f.get("texture", "")) if isinstance(f.get("texture"), str) else None
            cv.quad(P2, U, tex, light=shade(n))


def all_points(elements, M=np.eye(3), t=np.zeros(3)):
    pts = [P @ M.T + t for el in elements for _, P, _, _ in face_quads(el)]
    return np.concatenate(pts) if pts else np.zeros((1, 3))


VIEWS = {  # 이름 → (카메라에서 모델로 보는 방향, 위)
    "front": ((0, 0, 1), (0, 1, 0)),      # 북(-Z)에서 남쪽으로
    "side": ((-1, 0, 0), (0, 1, 0)),      # 동(+X)에서 서쪽으로
    "back": ((0, 0, -1), (0, 1, 0)),
    "top": ((0, -1, 0), (0, 0, -1)),      # 위에서, 화면 위 = 북
    "iso": ((-0.62, -0.5, 0.6), (0, 1, 0)),
}


def render_views(elements, textures, views=("front", "side", "top", "iso"), cell=256, extra=None, label=None):
    """여러 방향 그림을 가로로 이어 붙인다. extra: (요소, 텍스처) 참고용으로 같이 그릴 것(머리 상자 등)"""
    pts = all_points(elements)
    if extra:
        pts = np.concatenate([pts, all_points(extra[0])])
    ims = []
    for v in views:
        d, up = VIEWS[v]
        cv = Canvas((cell, cell), d, up, bg=(58, 62, 72, 255))
        cv.fit(pts)
        if extra:
            draw_model(cv, extra[0], extra[1])
        draw_model(cv, elements, textures)
        im = cv.image()
        ImageDraw.Draw(im).text((4, 3), v, fill=(255, 255, 255, 255))
        ims.append(im)
    out = Image.new("RGBA", (cell * len(ims), cell + (16 if label else 0)), (40, 42, 50, 255))
    for i, im in enumerate(ims):
        out.paste(im, (i * cell, 16 if label else 0))
    if label:
        ImageDraw.Draw(out).text((4, 2), label, fill=(255, 230, 150, 255))
    return out


def tex_array(png_bytes_or_img):
    im = png_bytes_or_img if isinstance(png_bytes_or_img, Image.Image) else Image.open(io.BytesIO(png_bytes_or_img))
    return np.asarray(im.convert("RGBA"), np.float32) / 255

# ---------------------------------------------------------------- 베드록 geo → 요소

def _bone_chain(bones, name):
    chain = []
    while name:
        b = bones[name]
        chain.append(b)
        name = b.get("parent")
    return chain[::-1]


def box_uv(u, v, size, mirror=False):
    """베드록/Blockbench 상자 uv(픽셀) → 면별 [u1, v1, u2, v2](자바 순서)"""
    sx, sy, sz = (math.floor(s + 1e-6) for s in size)
    f = {
        "north": [u + sz, v + sz, u + sz + sx, v + sz + sy],
        "east": [u, v + sz, u + sz, v + sz + sy],
        "south": [u + 2 * sz + sx, v + sz, u + 2 * sz + 2 * sx, v + sz + sy],
        "west": [u + sz + sx, v + sz, u + 2 * sz + sx, v + sz + sy],
        "up": [u + sz + sx, v + sz, u + sz, v],
        "down": [u + sz + 2 * sx, v, u + sz + sx, v + sz],
    }
    if mirror:
        f["east"], f["west"] = f["west"], f["east"]
        f = {k: [x[2], x[1], x[0], x[3]] for k, x in f.items()}
    return f


def geo_elements(geo, tex_var="#0", keep_bone=lambda name, chain: True, tex_size=None):
    """베드록 geo(1.12 형식, GeckoLib) → 요소 목록(Blockbench 좌표 = x 를 뒤집은 자바 좌표, 단위는 그대로).
    뼈 회전, 상자 회전을 합쳐 요소 하나의 오일러 회전으로. uv 는 텍스처 픽셀 → 0..16"""
    g = geo["minecraft:geometry"][0]
    tw = (tex_size or (g["description"]["texture_width"], g["description"]["texture_height"]))[0]
    th = (tex_size or (g["description"]["texture_width"], g["description"]["texture_height"]))[1]
    bones = {b["name"]: b for b in g["bones"]}
    out = []
    for b in g["bones"]:
        chain = _bone_chain(bones, b["name"])
        if not keep_bone(b["name"], [c["name"] for c in chain]):
            continue
        for c in b.get("cubes", []):
            size = c["size"]
            org = c["origin"]
            inf = c.get("inflate", 0.0)
            lo = np.array([-(org[0] + size[0]), org[1], org[2]], float) - inf
            hi = np.array([-org[0], org[1] + size[1], org[2] + size[2]], float) + inf
            piv = np.array(c.get("pivot", [0, 0, 0]), float) * [-1, 1, 1]
            r = c.get("rotation", [0, 0, 0])
            Rc = euler_zyx(-r[0], -r[1], r[2])
            # 뼈 사슬(뿌리 → 잎)
            Rt, o = Rc.copy(), piv.copy()
            for bb in reversed(chain):
                br = bb.get("rotation", [0, 0, 0])
                if any(abs(v) > 1e-9 for v in br):
                    Rb = euler_zyx(-br[0], -br[1], br[2])
                    bp = np.array(bb.get("pivot", [0, 0, 0]), float) * [-1, 1, 1]
                    Rt = Rb @ Rt
                    o = Rb @ (o - bp) + bp
            shift = o - piv
            faces = {}
            uvd = c.get("uv")
            if isinstance(uvd, list):
                fu = box_uv(uvd[0], uvd[1], size, c.get("mirror", b.get("mirror", False)))
            else:
                fu = {}
                for fn, fd in (uvd or {}).items():
                    u0, v0 = fd["uv"]
                    w, h = fd.get("uv_size", [0, 0])
                    q = [u0, v0, u0 + w, v0 + h]
                    if fn in ("up", "down"):
                        q = [q[2], q[3], q[0], q[1]]
                    fu[fn] = q
            for fn, q in fu.items():
                if abs(q[2] - q[0]) < 1e-9 and abs(q[3] - q[1]) < 1e-9:
                    continue
                uv = [q[0] * 16 / tw, q[1] * 16 / th, q[2] * 16 / tw, q[3] * 16 / th]
                faces[fn] = {"uv": [round(min(max(x, 0), 16), 4) for x in uv], "texture": tex_var}
            if not faces:
                continue
            el = {"from": list(lo + shift), "to": list(hi + shift), "faces": faces, "_bone": b["name"]}
            ang = to_euler_zyx(Rt)
            if any(abs(a) > 1e-3 for a in ang):
                el["rotation"] = {"origin": list(o), "x": ang[0], "y": ang[1], "z": ang[2]}
            out.append(el)
    return out


def bbmodel_elements(bb, tex_var_of=lambda idx: "#0"):
    """Blockbench bbmodel(자바 블록 형식이나 자유 형식) → 요소. 텍스처 번호 → 변수. uv 는 해상도로 0..16"""
    rw, rh = bb["resolution"]["width"], bb["resolution"]["height"]
    groups = {}

    def walk(nodes, chain):
        for n in nodes:
            if isinstance(n, dict):
                groups[n["uuid"]] = n
                walk(n.get("children", []), chain + [n])
            else:
                parent_of[n] = chain
    parent_of = {}
    walk(bb.get("outliner", []), [])
    out = []
    for e in bb["elements"]:
        if e.get("type", "cube") != "cube" or e.get("visibility") is False:
            continue
        lo, hi = np.array(e["from"], float), np.array(e["to"], float)
        inf = e.get("inflate", 0.0)
        lo, hi = lo - inf, hi + inf
        o = np.array(e.get("origin", [0, 0, 0]), float)
        r = e.get("rotation", [0, 0, 0])
        Rt, oo = euler_zyx(*r), o.copy()
        for gr in reversed(parent_of.get(e["uuid"], [])):
            gr_r = gr.get("rotation", [0, 0, 0])
            if any(abs(v) > 1e-9 for v in gr_r):
                Rg = euler_zyx(*gr_r)
                go = np.array(gr.get("origin", [0, 0, 0]), float)
                Rt = Rg @ Rt
                oo = Rg @ (oo - go) + go
        shift = oo - o
        faces = {}
        for fn, f in e.get("faces", {}).items():
            if f.get("texture") is None:
                continue
            u = f.get("uv", [0, 0, 0, 0])
            fd = {"uv": [round(min(max(v, 0), 16), 4) for v in (u[0] * 16 / rw, u[1] * 16 / rh, u[2] * 16 / rw, u[3] * 16 / rh)],
                  "texture": tex_var_of(f["texture"])}
            if f.get("rotation"):
                fd["rotation"] = f["rotation"]
            faces[fn] = fd
        if not faces:
            continue
        el = {"from": list(lo + shift), "to": list(hi + shift), "faces": faces, "_name": e.get("name", "")}
        ang = to_euler_zyx(Rt)
        if any(abs(a) > 1e-3 for a in ang):
            el["rotation"] = {"origin": list(oo), "x": ang[0], "y": ang[1], "z": ang[2]}
        out.append(el)
    return out

# ---------------------------------------------------------------- 옮기기, 다듬기

def transform(elements, k=1.0, offset=(0, 0, 0)):
    """모든 요소에 p → k p + offset(고른 배율이라 회전은 그대로)"""
    off = np.array(offset, float)
    out = []
    for e in elements:
        e2 = dict(e)
        e2["from"] = [round(float(v), 4) for v in np.array(e["from"], float) * k + off]
        e2["to"] = [round(float(v), 4) for v in np.array(e["to"], float) * k + off]
        if e.get("rotation"):
            r = dict(e["rotation"])
            r["origin"] = [round(float(v), 4) for v in np.array(r.get("origin", [8, 8, 8]), float) * k + off]
            e2["rotation"] = r
        out.append(e2)
    return out


def rotate_all(elements, R, center):
    """모든 요소를 center 둘레로 R 만큼 돌린다(요소 회전과 합쳐 오일러 하나로)"""
    center = np.array(center, float)
    out = []
    for e in elements:
        Re, o = element_matrix(e)
        if not e.get("rotation"):
            o = (np.array(e["from"], float) + np.array(e["to"], float)) / 2
        o2 = R @ (o - center) + center
        sh = o2 - o
        e2 = dict(e)
        e2["from"] = list(np.array(e["from"], float) + sh)
        e2["to"] = list(np.array(e["to"], float) + sh)
        ang = to_euler_zyx(R @ Re)
        if any(abs(a) > 1e-3 for a in ang):
            e2["rotation"] = {"origin": list(o2), "x": ang[0], "y": ang[1], "z": ang[2]}
        else:
            e2.pop("rotation", None)
        out.append(e2)
    return out


def vbounds(elements):
    """보이는 범위(회전 뒤 면 꼭짓점만)"""
    p = np.concatenate([P for e in elements for _, P, _, _ in face_quads(e)])
    return p.min(0), p.max(0)


def bounds(elements, rotated=True):
    pts = []
    for e in elements:
        if rotated:
            for _, P, _, _ in face_quads(e):
                pts.append(P)
        pts.append(np.array([e["from"], e["to"]], float))
    p = np.concatenate(pts)
    return p.min(0), p.max(0)


def clean(elements):
    """내부 표시(_bone 등) 지우고 숫자 다듬기"""
    out = []
    for e in elements:
        e2 = {k: v for k, v in e.items() if not k.startswith("_")}
        e2["from"] = [round(float(v), 4) + 0.0 for v in e2["from"]]
        e2["to"] = [round(float(v), 4) + 0.0 for v in e2["to"]]
        # from > to 인 축은 맞바꾸고 그 축의 면 이름도 맞바꾼다(모드 모델에 뒤집힌 상자가 있음)
        for ax, (fa, fb) in enumerate((("west", "east"), ("down", "up"), ("north", "south"))):
            if e2["from"][ax] > e2["to"][ax]:
                e2["from"][ax], e2["to"][ax] = e2["to"][ax], e2["from"][ax]
                fc = e2.get("faces", {})
                a, c = fc.pop(fa, None), fc.pop(fb, None)
                if a is not None:
                    fc[fb] = a
                if c is not None:
                    fc[fa] = c
        if "rotation" in e2:
            r = e2["rotation"]
            r["origin"] = [round(float(v), 4) + 0.0 for v in r["origin"]]
            for a in ("x", "y", "z", "angle"):
                if a in r:
                    r[a] = round(float(r[a]), 4) + 0.0
        out.append(e2)
    return out
