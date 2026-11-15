"""게이브 모델 키트: 종 팩 스크립트(tools/gabe/sp/gxx.py)가 쓰는 API 와 틴트 부호기(kr.gave.mob.Look 과 늘 같음).

좌표는 블록, 모델 가운데(디스플레이 자리)가 0. +Y 위, +Z 앞(얼굴 정면, 엔진 Body 가 이 쪽을 몸이 보는 쪽으로 돌림), +X 는 게이브 자신의
왼쪽(마주 본 사람에게는 오른쪽). 부위 이름의 _l, _r 도 게이브 자신 기준(_l = +X).

틴트 부호(24비트 색 하나 = 면 하나의 틴트 칸): R 위 4비트 = 14(게이브 표식), 그다음 2비트 = 검사(chk), 남은 18비트 = 내용(pay).
  pay 비트 17 = 자세 면(1) 또는 보통 면(0), 16 = 숨김, 15..14 = 거리(0 늘, 1 먼 면만, 2 가까운 면만(3블록 안))
  자세 면: 13..11 = 자세 번호 k, 10..8 = 자세 수 N-1, 7..4 = 주기 칸(PERIODS, 15 = 멈춤), 3..0 = 위상(주기의 16분의 몇)
  보통 면: 13..9 = 팔레트 비트(Look.PAL_*), 8..0 = 맞은 번쩍임 끝 틱 mod 480(511 = 없음, 480 은 24000 의 약수)
  chk = (1 + pay 의 2비트 묶음 9개의 합) & 3
셰이더(pack_fx/gabe.glsl)가 같은 비트를 읽어 프레임 접기(지금 자세가 아닌 면을 화면 밖 한 점으로), 3블록 안 가까운 면, 숨김,
팔레트, 맞은 번쩍임을 한다. 색은 흰색으로 되돌려 그림을 물들이지 않는다.

래그돌 사체(엔진 kr.gave.mob.Ragdoll, docs/모듈/래그돌.md): 종 스크립트를 고치지 않아도 키트가 모델마다 저절로 굽는다.
  - 상태마다 몸 상자(box: 가운데, 반 크기)를 재서 gabe_looks.json 의 rag 에 적는다(엔진이 그 상자로 구르고 튕김).
  - 펄럭이는 엔드 막대기(s.rod 에 wave 를 준 것, 길이 0.3 이상, 긴 것부터 4개)가 있는 상태는 그것을 '흐물 막대기'로 치고
    사체 상태 "<상태>.c" 를 더 만든다(막대기 뺀 몸 + 바닥에 닿는 면(RAG_FACES)마다 누운 막대기 자세). 죽으면 엔진이 몸을 .c 로 바꾸고
    막대기를 공용 마디 모델(gave:gabe/common_rod, tools/gabe/common.py)로 흐물거리게 그리다가, 멈추면 누운 자세를 켜 디스플레이
    하나로 합친다. 누운 자세는 자세 면으로 굽고 엔진이 멈춤 자세 번호(LookState.pose)로 고른다(구르는 동안은 '없음' 번호).
  - 끄기: k.model(rag=False)(모델 통째), s.rod(rag=False)(그 막대기만 몸에 굳은 채). 가만히 있는 막대기를 흐물거리게: s.rod(rag=True).
"""
import json
import math
import re
from contextlib import contextmanager
from pathlib import Path

import numpy as np
from PIL import Image

from . import audio, faces, flesh, render

ROOT = Path(__file__).resolve().parents[2]
NS = "gave"
PERIODS = (4, 6, 8, 10, 12, 15, 16, 20, 24, 30, 40, 48, 60, 80, 120)
STOPPED = 15
HURT_NONE = 511
HURT_MOD = 480
HURT_MAX = 8  # 번쩍임은 끝 틱 8틱 전부터(셰이더도 8)
DIST = {"any": 0, "far": 1, "near": 2}
MIN, MAX = -16.0, 32.0
SHARED_PARTS = ("rod", "flesh", "wetskin", "chain", "web", "bone")
COND = re.compile(r"^(!?open|mut(>=|<|==)[0-9]|!?on:[a-z0-9_]+)$")
NAME = re.compile(r"^[a-z0-9_]*$")
MIDDLE_DOT = chr(0xB7)
LOOKS_VERSION = 2  # gabe_looks.json 모델 항목 꼴(2: 상태별 periods, uses, 자세 칸을 상태끼리 나눠 씀)
QUAD_BUDGET = 400  # 상태 모델 하나(디스플레이 하나)의 면 수 한도: 숨긴 자세 면도 클라가 매 프레임 정점으로 보내므로 모두 셈
FLAT = 1.0 / 16  # 이보다 얇고 폭이 두께의 3배 이상인 마디(살 띠, 쇠사슬 고리, 막)는 넓은 두 면만(옆 테두리, 마구리는 1 화소도 안 됨)
# 래그돌 사체(엔진 Ragdoll): 공용 마디 모델이 가진 텍스처, 저절로 만드는 상태 이름 꼬리(종이 짓는 이름에는 점이 못 들어가 안 겹침),
# 바닥에 닿는 면 번호(0 아래 -Y, 1 위 +Y, 2 뒤통수 -Z, 3 얼굴 +Z, 4 -X, 5 +X)와 사체 자세를 굽는 차례(얼굴이 하늘을 보는 것부터)
RAG_PARTS = ("rod", "flesh", "wetskin", "bone")
RAG_CORPSE = ".c"
RAG_DOWN = {0: (0, -1, 0), 1: (0, 1, 0), 2: (0, 0, -1), 3: (0, 0, 1), 4: (-1, 0, 0), 5: (1, 0, 0)}
RAG_FACES = (2, 0, 1, 4, 5, 3)
RAG_MAX_RODS = 4  # 한 상태에서 흐물거리게 떼어 내는 막대기 수(긴 것부터)
RAG_SLOT_LIMIT = 80  # 사체 자세 칸을 더해도 넘지 않을 모델 틴트 칸 수


# ============================================================================================== 틴트 부호기(Look.java 와 같게)


class LookState:
    """kr.gave.mob.LookState 와 같은 필드(파이썬 이름은 hurt_until). pose: 멈췄을 때(freeze) 보이는 자세 번호(굳은 사체 자세)"""

    def __init__(self, palette=0, open=False, mut=0, freeze=False, phase=0, rate=1.0, on=(), hurt_until=0, pose=0):
        self.palette, self.open, self.mut, self.freeze = palette, open, mut, freeze
        self.phase, self.rate, self.on, self.hurt_until, self.pose = phase, rate, set(on), hurt_until, pose

    def to_json(self):
        return {"palette": self.palette, "open": self.open, "mut": self.mut, "freeze": self.freeze, "phase": self.phase,
                "rate": self.rate, "on": sorted(self.on), "hurtUntil": self.hurt_until, "pose": self.pose}


DEFAULT = LookState()


def cond_ok(c, st):
    if c == "open":
        return bool(st.open)
    if c == "!open":
        return not st.open
    if c.startswith("mut>="):
        return st.mut >= int(c[5:])
    if c.startswith("mut<"):
        return st.mut < int(c[4:])
    if c.startswith("mut=="):
        return st.mut == int(c[5:])
    if c.startswith("on:"):
        return c[3:] in st.on
    if c.startswith("!on:"):
        return c[4:] not in st.on
    return False


def period_index(period, rate):
    """기본 주기(틱) / 빠르기 배율에 가장 가까운 PERIODS 칸(비율 max(P/t, t/P) 가 가장 작은 것, 같으면 앞 칸)"""
    target = period / rate if rate > 0 else float(period)
    best, bd = 0, None
    for i, p in enumerate(PERIODS):
        d = max(p / target, target / p)
        if bd is None or d < bd:
            best, bd = i, d
    return best


def checksum(pay):
    s = 1
    for i in range(9):
        s += (pay >> (2 * i)) & 3
    return s & 3


def pose_phase(pose, n):
    """멈춘 자세 번호 → 위상 칸(셰이더는 멈춤일 때 자세 = 위상 * n // 16 을 보인다): 자세 0 은 0, 그 밖은 ceil(16 * 자세 / n)"""
    k = max(0, min(int(pose), n - 1))
    return 0 if k == 0 else min(15, -(-16 * k // n))


def encode_slot(slot, st, world_time, period=None):
    """틴트 칸 하나 → 24비트 색. slot: dict(posed, dist, face, show, k, n, period). period: 자세 칸의 주기를 이 값으로(상태별 주기)"""
    hide = not all(cond_ok(c, st) for c in slot["show"])
    dist = slot["dist"]
    pay = (1 << 16 if hide else 0) | (dist << 14)
    if slot["posed"]:
        n = slot["n"]
        if st.freeze:
            pi = STOPPED
            ph = pose_phase(getattr(st, "pose", 0), n)
        else:
            pi = period_index(period if period is not None else slot["period"], st.rate)
            p = PERIODS[pi]
            ph = ((st.phase % p) + p) % p * 16 // p
        pay |= (1 << 17) | (slot["k"] << 11) | ((n - 1) << 8) | (pi << 4) | ph
    else:
        pal = (st.palette & 31) if slot["face"] else 0
        hurt = HURT_NONE
        if world_time < st.hurt_until:
            end = min(st.hurt_until, world_time + HURT_MAX)
            hurt = end % HURT_MOD
        pay |= (pal << 9) | hurt
    return 0xE00000 | (checksum(pay) << 18) | pay


def encode(slots, st, world_time):
    return [encode_slot(s, st, world_time) for s in slots]


def colors(slots, st, world_time):
    """보낼 custom_model_data 색 목록(상태를 모를 때, Look.colors(model, look, wt)): 모든 칸을 칸 주기로. 끝에서부터 아이템 정의
    기본값(기본 look)과 같은 칸을 잘라냄"""
    return _trim(slots, encode(slots, st, world_time))


def _trim(slots, full):
    n = len(full)
    while n > 0 and full[n - 1] == encode_slot(slots[n - 1], DEFAULT, 0):
        n -= 1
    return full[:n]


def state_of(entry, state):
    """아이템 정의 select 와 같게: 모르는 상태 이름은 기본 상태(fallback)"""
    state = state or ""
    return state if state in entry.get("states", ()) else entry.get("default", "")


def encode_state(entry, state, st, world_time):
    """상태를 알 때(Look.encode(model, state, look, wt)): 자세 칸은 그 상태의 주기로, 그 상태 모델이 안 쓰는 칸은 기본값으로.
    entry = gabe_looks.json 모델 항목(slots, states, default, periods, uses)"""
    slots = entry["slots"]
    s = state_of(entry, state)
    per = entry.get("periods", {}).get(s)
    uses = entry.get("uses", {}).get(s)
    used = set(uses) if uses is not None else None
    out = []
    for i, sl in enumerate(slots):
        if used is not None and i not in used:
            out.append(encode_slot(sl, DEFAULT, 0))
        else:
            out.append(encode_slot(sl, st, world_time, per if sl["posed"] else None))
    return out


def colors_state(entry, state, st, world_time):
    """보낼 색 목록(상태를 알 때, Look.colors(model, state, look, wt)). 끝의 기본값 칸을 잘라냄"""
    return _trim(entry["slots"], encode_state(entry, state, st, world_time))


def decode(color):
    """24비트 색 → 뜻(시험, 디버그용). 게이브 색이 아니면 None"""
    if (color >> 20) != 14:
        return None
    pay = color & 0x3FFFF
    if ((color >> 18) & 3) != checksum(pay):
        return None
    d = {"hide": bool(pay >> 16 & 1), "dist": pay >> 14 & 3, "posed": bool(pay >> 17 & 1)}
    if d["posed"]:
        d.update(k=pay >> 11 & 7, n=(pay >> 8 & 7) + 1, period=pay >> 4 & 15, phase=pay & 15)
    else:
        d.update(palette=pay >> 9 & 31, hurt=pay & 511)
    return d


# ============================================================================================== 작은 벡터 도구


def v3(a):
    return np.array(a, np.float64)


def unit(a):
    a = v3(a)
    n = np.linalg.norm(a)
    if n < 1e-9:
        raise ValueError("길이 0 방향")
    return a / n


def rot_axis(axis, deg):
    """오른손 회전 행렬(axis 둘레 deg 도)"""
    x, y, z = unit(axis)
    a = math.radians(deg)
    c, s, C = math.cos(a), math.sin(a), 1 - math.cos(a)
    return np.array([[c + x * x * C, x * y * C - z * s, x * z * C + y * s],
                     [y * x * C + z * s, c + y * y * C, y * z * C - x * s],
                     [z * x * C - y * s, z * y * C + x * s, c + z * z * C]])


def euler_m(x, y, z):
    """26.3 요소 회전(오일러 x, y, z 도) = Rz Ry Rx (Matrix4f.rotationZYX(z, y, x))"""
    return rot_axis((0, 0, 1), z) @ rot_axis((0, 1, 0), y) @ rot_axis((1, 0, 0), x)


def euler_of(M):
    """회전 행렬 → (x, y, z) 도, M = Rz Ry Rx"""
    sy = -M[2, 0]
    sy = max(-1.0, min(1.0, sy))
    y = math.asin(sy)
    if abs(math.cos(y)) > 1e-6:
        x = math.atan2(M[2, 1], M[2, 2])
        z = math.atan2(M[1, 0], M[0, 0])
    else:
        x = 0.0
        z = math.atan2(-M[0, 1], M[1, 1])
    return tuple(round(math.degrees(v), 4) for v in (x, y, z))


# ============================================================================================== 물결(펄럭임)


class Wave:
    """사슬 마디 각도 = bias_i + amp_i * sin(2pi * t - i * lag + phase)(도). t = 자세 번호 / 자세 수. 마디 각도는 뿌리부터 쌓인다.
    amp, bias: 수 하나 또는 마디마다 목록. lag: 마디마다 늦는 위상(도, 뿌리에서 끝으로 가는 파도). phase: 전체 위상(도).
    shape: "sin"(기본) | "flap"(위로 빨리 치고 천천히 내림) | "twitch"(대부분 가만히, 한 번 움찔).
    cycles: 상태 주기 한 바퀴에 물결이 도는 수(정수). 2 면 같은 주기 안에서 두 번 쳐서 두 배 빠르게 보인다(자세 N 장이 두 바퀴를 나눠
    가지므로 한 바퀴 자세는 N/2). 상태 주기를 그대로 두고 빠르기만 바꾸면 상태 이름을 모르는 Look.colors 로도 빠르기가 맞다"""

    def __init__(self, amp=20.0, lag=40.0, bias=0.0, phase=0.0, shape="sin", cycles=1):
        self.amp, self.lag, self.bias, self.phase, self.shape = amp, lag, bias, phase, shape
        self.cycles = int(cycles)
        if self.cycles < 1 or self.cycles != cycles:
            raise ValueError("cycles 는 1 이상 정수")

    @staticmethod
    def _at(v, i):
        if isinstance(v, (list, tuple)):
            return float(v[min(i, len(v) - 1)])
        return float(v)

    def angle(self, i, t):
        x = 2 * math.pi * t * self.cycles - math.radians(self.lag) * i + math.radians(self.phase)
        if self.shape == "flap":
            s = math.sin(x + 0.6 * math.sin(x))
        elif self.shape == "twitch":
            w = (x / (2 * math.pi)) % 1.0
            s = math.sin(w * 2 * math.pi * 4) if w < 0.25 else 0.0
        else:
            s = math.sin(x)
        return self._at(self.bias, i) + self._at(self.amp, i) * s

    def scaled(self, k):
        """폭만 k 배"""
        amp = [a * k for a in self.amp] if isinstance(self.amp, (list, tuple)) else self.amp * k
        return Wave(amp, self.lag, self.bias, self.phase, self.shape, self.cycles)


# ============================================================================================== 모델 요소


class FaceSpec:
    __slots__ = ("uv", "tex", "slot")

    def __init__(self, uv, tex, slot):
        self.uv, self.tex, self.slot = uv, tex, slot


class Elem:
    """요소 하나(px 좌표). rot_m 는 3x3, origin 은 px. chain: 이 요소가 속한 사슬(Chain, 막은 두 사슬의 튜플, 없으면 None)"""
    __slots__ = ("frm", "to", "origin", "rot_m", "euler", "faces", "shade", "tag", "chain")

    def __init__(self, frm, to, faces, origin=None, rot_m=None, shade=True, tag="", chain=None):
        self.frm, self.to, self.faces, self.shade, self.tag = list(frm), list(to), faces, shade, tag
        self.origin = list(origin) if origin is not None else None
        self.rot_m = rot_m
        self.euler = euler_of(rot_m) if rot_m is not None else None
        self.chain = chain

    def copy(self):
        """면 칸을 따로 갖는 사본(사체 상태가 같은 요소를 다시 쓸 때: 칸 번호를 상태마다 한 번씩만 바꾸게)"""
        return Elem(self.frm, self.to, {n: FaceSpec(list(fc.uv), fc.tex, fc.slot) for n, fc in self.faces.items()}, self.origin,
                    self.rot_m, self.shade, self.tag, self.chain)


class Frame:
    """자세 k(0..n-1), t = k/n. with f: 안에서 만든 요소는 이 자세에서만 보임"""

    def __init__(self, state, k, n):
        self.state, self.k, self.n, self.t = state, k, n, k / n

    def __enter__(self):
        if self.state._frame is not None:
            raise RuntimeError("자세 안에 자세를 또 열 수 없음")
        self.state._frame = self
        return self

    def __exit__(self, *a):
        self.state._frame = None


class Head:
    """얼굴 상자. point(u, v, w): u 가로(-1 오른쪽 끝(-X) .. 1 왼쪽 끝(+X)), v 세로(-1 아래 .. 1 위), w 깊이(-1 뒤통수 .. 1 얼굴 앞면)"""

    def __init__(self, center, w, h, d, R):
        self.center, self.w, self.h, self.d, self.R = v3(center), w, h, d, R

    def point(self, u, v, w=0.0):
        local = v3((u * self.w / 2, v * self.h / 2, w * self.d / 2))
        return tuple(self.center + self.R @ local)

    def axis(self, a):
        return tuple(self.R @ unit(a))


class Chain:
    """사슬(막대기, 살 띠). hinges[k] = 자세 k 의 마디 이음점(뿌리, ..., 끝) 블록 좌표. 자세 없는 사슬은 hinges[None]. n = 자세 수.
    래그돌용 기록: kind(rod | band), root, dir(단위), length, thick, tex(텍스처 id), part(공용 부품 이름 또는 None), show(만들 때 조건),
    waved(물결이 있나), rag(s.rod 의 rag 값), elems(마디 요소들)"""

    def __init__(self, n=1):
        self.hinges = {}
        self.dirs = {}
        self.n = n
        self.kind, self.root, self.dir, self.length, self.thick = "rod", (0.0, 0.0, 0.0), (0.0, 1.0, 0.0), 0.0, 0.0
        self.tex, self.part, self.show, self.waved, self.rag = None, None, (), False, None
        self.elems = []

    def tip(self, k=None):
        return self.hinges[k][-1]


class State:
    """모델 상태 하나(아이템 정의 select 의 strings[0] 값 하나 = 모델 파일 하나)"""

    def __init__(self, model, name, poses, period):
        self.model, self.name, self.poses, self.period = model, name, poses, period
        self.elems = []
        self._show = []
        self._frame = None
        self.heads = []
        self.chains = []
        self.lumped = False  # lump() 로 만든 떼 덩어리(LOD) 상태: 래그돌 사체 상태를 만들지 않음
        # 얼굴이 보는 방향(블록 좌표 벡터): 사체가 굳을 때 이 방향이 바닥에 깔리지 않게 눕는다. None 이면 가장 큰 head() 의 앞면에서
        # 저절로 정한다(head() 없이 얼굴을 그린 상태는 엔진 기본값: 닻 몸과 떼 마리는 앞 +Z, 부품은 얼굴 없음). (0, 0, 0) = 얼굴 없음
        self.front = None

    # ------------------------------------------------------------------ 맥락

    @contextmanager
    def when(self, *conds):
        """이 안에서 만든 요소는 조건이 모두 맞을 때만 보임: "open", "!open", "mut>=1", "mut<2", "on:cut_l", "!on:cut_l" """
        for c in conds:
            if not COND.match(c):
                raise ValueError(f"조건 {c!r} 을 모름(open, !open, mut>=N, mut<N, mut==N, on:이름, !on:이름)")
            if c.lstrip("!").startswith("on:"):
                self.model.parts.add(c.lstrip("!")[3:])
        self._show.extend(conds)
        try:
            yield self
        finally:
            del self._show[len(self._show) - len(conds):]

    def frames(self, poses=None):
        """자세 0..n-1(n = poses, 기본 상태 자세 수). for f in s.frames(): with f: ... (f.t 0..1 로 모양을 정함).
        작은 부위는 poses=4 처럼 줄이면 면이 그만큼 준다(한 바퀴는 같은 주기, 자세 하나가 더 오래 머묾)"""
        n = self._poses(poses)
        return [Frame(self, k, n) for k in range(n)]

    def _poses(self, poses):
        n = self.poses if poses is None else int(poses)
        if not 1 <= n <= 8:
            raise ValueError("자세 수는 1..8")
        return n

    # ------------------------------------------------------------------ 바탕

    def px(self, p):
        s = self.model.shrink
        return [8.0 + 16.0 * s * float(c) for c in p]

    def _slot(self, dist="any", face=False, extra=()):
        show = tuple(sorted(set(self._show) | set(extra)))
        f = self._frame
        if f is None:
            key = (False, DIST[dist], bool(face), show, 0, 1)
        else:
            # 주기는 칸에 넣지 않는다: 상태끼리 같은 자세 칸을 나눠 쓰고, 보낼 때 그 상태의 주기로 부호(Look.colors(model, state, ..))
            key = (True, DIST[dist], False, show, f.k, f.n)
        return self.model.slot(key)

    def _check(self, e):
        for v in e.frm + e.to:
            if not (MIN - 1e-6 <= v <= MAX + 1e-6):
                raise ValueError(f"{self.model.name}:{self.name} 요소 좌표 {e.frm}..{e.to} 가 -16..32 밖(모델 shrink 를 줄이거나 "
                                 f"요소를 가운데 가까이). 태그 {e.tag}")

    def add(self, e):
        self._check(e)
        self.elems.append(e)
        return e

    def box(self, frm, to, tex, *, uv=None, faces=None, rot=None, origin=None, dist="any", face=False, shade=True, show=()):
        """상자(블록 좌표). tex: 텍스처 id 또는 {면 이름: id}. uv: {면: [u1, v1, u2, v2]} 또는 [u1, v1, u2, v2](모든 면) 또는
        None(면 크기에 맞춘 자동 uv). faces: 그릴 면 이름들(기본 6면). rot: (x, y, z) 도(26.3 오일러), origin: 블록(기본 상자 가운데).
        face=True 면 팔레트, 맞은 번쩍임을 받음(얼굴, 살). dist: any | far | near. show: 이 요소만 더할 조건"""
        f, t = self.px(frm), self.px(to)
        names = faces or ("north", "south", "east", "west", "up", "down")
        fs = {}
        for n in names:
            tx = tex[n] if isinstance(tex, dict) else tex
            if tx is None:
                continue
            tx = self.model.kit.tex_id(tx)
            u = uv.get(n) if isinstance(uv, dict) else uv
            if u is None:
                u = _auto_uv(n, f, t)
            fs[n] = FaceSpec(list(u), tx, self._slot(dist, face, show))
        R = None
        o = None
        if rot is not None and any(abs(a) > 1e-9 for a in rot):
            R = euler_m(*rot)
            o = self.px(origin) if origin is not None else [(a + b) / 2 for a, b in zip(f, t)]
        return self.add(Elem(f, t, fs, o, R, shade, tag="box"))

    def quad(self, center, size, facing="south", tex=None, *, uv=(0, 0, 16, 16), dist="any", face=False, show=(), rot=None,
             origin=None):
        """판 하나(두께 0). facing: 보는 쪽(south = +Z 앞). size: (가로, 세로) 블록"""
        c = v3(center)
        w, h = size
        if facing in ("south", "north"):
            d = (w / 2, h / 2, 0)
        elif facing in ("east", "west"):
            d = (0, h / 2, w / 2)
        else:
            d = (w / 2, 0, h / 2)
        return self.box(tuple(c - v3(d)), tuple(c + v3(d)), tex, uv=list(uv), faces=(facing,), dist=dist, face=face, show=show,
                        rot=rot, origin=origin)

    # ------------------------------------------------------------------ 얼굴

    def head(self, size=0.8, depth=None, at=(0, 0, 0), face="smile", *, height=None, near=True, sides="skin", open=None, torn=None,
             rot=None, show=()):
        """얼굴 상자: 앞(+Z) 먼 얼굴(32), 3블록 안 가까운 얼굴(128), 옆, 뒤, 위, 아래는 스킨 머리. 실눈 뜸(open) 오버레이와
        입 찢어짐(torn, 변이 1) 오버레이를 저절로 붙인다(모델 기본값, False 로 끔). face: faces.PRESETS 이름 또는 faces.Face.
        sides: "skin"(스킨 옆면) 또는 텍스처 id 하나(모든 옆면). rot: 머리 전체 회전(도). → Head"""
        k = self.model.kit
        w = size
        h = height if height is not None else size
        d = depth if depth is not None else size * 0.75
        c = v3(at)
        R = euler_m(*rot) if rot else np.eye(3)
        o = c
        f = faces.preset(face)
        front = k.face_tex(f)
        if sides == "skin":
            st = {"north": k.skin_tex("back"), "east": k.skin_tex("left"), "west": k.skin_tex("right"), "up": k.skin_tex("top"),
                  "down": k.skin_tex("bottom")}
        else:
            st = {n: sides for n in ("north", "east", "west", "up", "down")}
        rot_kw = dict(rot=rot, origin=tuple(o)) if rot else {}
        lo, hi = c - v3((w / 2, h / 2, d / 2)), c + v3((w / 2, h / 2, d / 2))
        with self.when(*show):
            # 앞면(먼 얼굴), 그 밖 다섯 면
            self.box(tuple(lo), tuple(hi), {"south": front}, faces=("south",), uv=[0, 0, 16, 16], dist="far" if near else "any",
                     face=True, **rot_kw)
            self.box(tuple(lo), tuple(hi), st, faces=("north", "east", "west", "up", "down"), uv=[0, 0, 16, 16], face=True, **rot_kw)
            z = hi[2]
            ep = 1.0 / (16 * self.model.shrink)  # 1 px(블록)
            if near:
                self.quad((c[0], c[1], z + 0.08 * ep), (w, h), "south", k.face_tex(f, near=True), dist="near", face=True, **rot_kw)
            use_open = self.model.open if open is None else open
            use_torn = self.model.torn if torn is None else torn
            for kind, use, cond, dz in (("open", use_open, "open", 0.16), ("torn", use_torn, "mut>=1", 0.24)):
                if not use:
                    continue
                ov = k.overlay_tex(f, kind)
                if ov is None:
                    continue
                (bx0, by0, bx1, by1), id32, id128 = ov
                cx = lo[0] + (bx0 + bx1) / 2 / 32 * w
                cy = hi[1] - (by0 + by1) / 2 / 32 * h
                sz = ((bx1 - bx0) / 32 * w, (by1 - by0) / 32 * h)
                with self.when(cond):
                    self.quad((cx, cy, z + dz * ep), sz, "south", id32, dist="far" if near else "any", face=True, **rot_kw)
                    if near:
                        self.quad((cx, cy, z + (dz + 0.04) * ep), sz, "south", id128, dist="near", face=True, **rot_kw)
        hd = Head(c, w, h, d, R)
        self.heads.append(hd)
        return hd

    # ------------------------------------------------------------------ 사슬

    def _segment(self, hinge, d, f, L, wid, thick, tex_side, tex_cap, uv_side, uv_cap, face, tag, cap_lo=True, cap_hi=True):
        """마디 하나: 이음점 hinge(블록)에서 d 쪽으로 L 블록. 단면은 f 쪽 wid, f x d 쪽 thick. 회전 전 상자는 이음점에서 위(+Y)나
        아래(-Y)로 세워 -16..32 안에 들게 고르고, 회전으로 d 쪽에 눕힌다. cap_lo / cap_hi: 이음점 쪽 / 끝 쪽 마구리를 그릴지
        (사슬 속 이음매 마구리는 앞 마디 속에 묻혀 안 보이므로 뺀다). thick 이 FLAT 보다 얇으면 넓은 두 면만"""
        s = self.model.shrink
        hp = self.px(hinge)
        Lp, wp, tp = 16 * s * L, 16 * s * wid, 16 * s * thick
        d, f = unit(d), unit(f)
        z = np.cross(f, d)
        for sign in (1, -1):
            y0, y1 = (hp[1], hp[1] + Lp) if sign > 0 else (hp[1] - Lp, hp[1])
            frm = [hp[0] - wp / 2, y0, hp[2] - tp / 2]
            to = [hp[0] + wp / 2, y1, hp[2] + tp / 2]
            if all(MIN <= v <= MAX for v in frm + to):
                break
        else:
            raise ValueError(f"{self.model.name}:{self.name} 사슬 이음점 {tuple(round(c, 3) for c in hinge)} 이 모델 틀 밖(shrink 를 줄일 것)")
        M = np.column_stack([f, d, z]) if sign > 0 else np.column_stack([-f, -d, z])
        fs = {}
        slot = self._slot("any", face)
        flat = thick < FLAT - 1e-9 and wid >= 3 * thick  # 납작한 것만(가는 네모 막대기는 네 옆면 그대로)
        # north/south 의 법선은 f x d 쪽(넓은 면: L x wid), east/west 는 f 쪽(L x thick)
        for n in (("north", "south") if flat else ("north", "south", "east", "west")):
            fs[n] = FaceSpec(list(uv_side), tex_side, slot)
        cap_top, cap_bot = ("up", "down") if sign > 0 else ("down", "up")
        if cap_hi and not flat:
            fs[cap_top] = FaceSpec(list(uv_cap), tex_cap, slot)
        if cap_lo and not flat:
            fs[cap_bot] = FaceSpec(list(uv_cap), tex_cap, slot)
        return self.add(Elem(frm, to, fs, hp, M, True, tag=tag))

    def _chain(self, root, dir, length, segs, wid, thick, wave, toward, tex, uv_side, uv_cap, base, face, tag, band=False, poses=None,
               rag=None, part=None):
        d0 = unit(dir)
        tw = unit(toward)
        f = np.cross(d0, tw)
        if np.linalg.norm(f) < 1e-6:
            raise ValueError("toward 가 dir 과 나란함")
        f = unit(f)
        L = length / segs
        frames = [None] if wave is None else self.frames(poses)
        ch = Chain(1 if wave is None else len(frames))
        ch.kind, ch.root, ch.dir, ch.length, ch.thick = ("band" if band else "rod"), tuple(v3(root)), tuple(d0), float(length), float(thick)
        ch.tex, ch.part, ch.show, ch.waved, ch.rag = tex, part, tuple(self._show), wave is not None, rag
        knob = base and not band
        if knob:
            # 받침은 뿌리에 하나, 자세 없이(첫 마디가 받침 안에서 돈다): 자세마다 굽지 않아 면을 아낀다
            a0 = float(np.mean([wave.angle(0, fr.t) for fr in frames])) if wave is not None else 0.0
            db = rot_axis(f, a0) @ d0
            bw = wid * 1.9
            self._segment(tuple(v3(root) - db * 0.02), db, f, max(0.03, wid * 0.45), bw, bw, tex, tex, [2, 6, 6, 7], [2, 2, 6, 6], face,
                          tag + "_base")
        for fr in frames:
            if fr is not None:
                fr.__enter__()
            try:
                p = v3(root)
                cum = 0.0
                pts, dirs = [tuple(p)], []
                for i in range(segs):
                    cum += (wave.angle(i, fr.t) if fr is not None else 0.0) if wave is not None else 0.0
                    d = rot_axis(f, cum) @ d0
                    if band:
                        vs = [uv_side[0], uv_side[1] + (uv_side[3] - uv_side[1]) * i / segs, uv_side[2],
                              uv_side[1] + (uv_side[3] - uv_side[1]) * (i + 1) / segs]
                    else:
                        vs = uv_side
                    e = self._segment(tuple(p), d, f, L * (1.04 if i < segs - 1 else 1.0), wid, thick, tex, tex, vs, uv_cap, face, tag,
                                      cap_lo=(i == 0 and not knob), cap_hi=(i == segs - 1))
                    e.chain = ch
                    ch.elems.append(e)
                    p = p + d * L
                    pts.append(tuple(p))
                    dirs.append(tuple(d))
                ch.hinges[fr.k if fr else None] = pts
                ch.dirs[fr.k if fr else None] = dirs
            finally:
                if fr is not None:
                    fr.__exit__()
        self.chains.append(ch)
        return ch

    def rod(self, root, dir, length=1.2, segs=3, thick=0.11, wave=None, toward=(0, 1, 0), base=True, tex="rod", poses=None, rag=None):
        """엔드 막대기 사슬(원형 DNA). root 뿌리(블록), dir 뻗는 쪽, toward 는 물결 각도가 + 일 때 휘는 쪽(기본 위).
        wave=None 이면 가만히(자세 없음). base: 뿌리에 엔드 막대기 받침 하나(자세 없음). poses: 이 막대기만 자세 수를 줄임
        (기본 상태 자세 수, 작은 부위는 4). 면 수 = 자세 x (마디 x 4 + 1) + 받침 6.
        rag: 죽은 뒤 래그돌 사체에서 이 막대기가 흐물거리는가. None(기본) = 물결이 있고 길이 0.3 이상이면 흐물거림, False = 몸에 굳은 채,
        True = 가만히 있는 막대기도 흐물거림, 정수 = 흐물거리는 마디 수(1..3, 기본은 길이 0.5 아래 1, 1.5 아래 2, 그 위 3).
        텍스처가 공용 부품(rod, flesh, wetskin, bone)일 때만. → Chain"""
        t = self.model.kit.tex_id(tex)
        return self._chain(root, dir, length, segs, thick, thick, wave, toward, t, [0, 0, 2, 15], [2, 0, 4, 2], base, False, "rod",
                           poses=poses, rag=rag, part=next((n for n in RAG_PARTS if t == f"{NS}:item/gabe/{n}"), None))

    def band(self, root, dir, length, width, segs=3, thick=0.03, wave=None, toward=(0, 1, 0), tex="flesh", face=True, poses=None):
        """납작한 띠(분홍 살 띠, 젖은 천 가장자리, 혀): 폭은 휘는 축 쪽(width), 두께 thick. 텍스처는 띠 길이를 따라 이어짐.
        thick 이 1/16 블록보다 얇으면 넓은 두 면만 그림(면 수 = 자세 x 마디 x 2). face=True 면 자세 없는 띠가 팔레트를 받음
        (공용 살 텍스처는 화소 부류 0 이라 탈색만 먹음). → Chain"""
        t = self.model.kit.tex_id(tex)
        return self._chain(root, dir, length, segs, width, thick, wave, toward, t, [0, 0, 16, 16], [0, 0, 16, 2], False, face, "band",
                           band=True, poses=poses)

    def chain(self, a, b, links=6, sag=0.25, size=0.09, tex="chain", swing=None, poses=None):
        """쇠사슬: a 에서 b 로 늘어진 고리 links 개(엇갈려 90도씩, 고리마다 넓은 두 면). swing=Wave 면 a-b 선 둘레로 흔들림(자세마다).
        → 고리 가운데 점 목록"""
        A, B = v3(a), v3(b)
        t = self.model.kit.tex_id(tex)
        frames = [None] if swing is None else self.frames(poses)
        out = []
        for fr in frames:
            if fr is not None:
                fr.__enter__()
            try:
                ang = swing.angle(0, fr.t) if (swing is not None and fr is not None) else 0.0
                axis = B - A
                down = v3((0, -1, 0))
                down = unit(down - axis * (down @ axis) / max(axis @ axis, 1e-9)) if np.linalg.norm(np.cross(axis, down)) > 1e-6 else v3((1, 0, 0))
                Rs = rot_axis(axis, ang)
                pts = []
                for i in range(links):
                    u = (i + 0.5) / links
                    p = A + (B - A) * u + Rs @ (down * sag * 4 * u * (1 - u))
                    pts.append(p)
                for i, p in enumerate(pts):
                    nxt = pts[min(i + 1, links - 1)] - pts[max(i - 1, 0)]
                    d = unit(nxt) if np.linalg.norm(nxt) > 1e-9 else unit(axis)
                    side = np.cross(d, v3((0, 1, 0)))
                    if np.linalg.norm(side) < 1e-6:
                        side = v3((1, 0, 0))
                    f = rot_axis(d, 90 * (i % 2)) @ unit(side)
                    L = np.linalg.norm(B - A) / links * 1.25
                    self._segment(tuple(p - d * L / 2), d, f, L, size, 0.01, t, t, [0, 0, 16, 16], [0, 0, 16, 1], False, "chain")
                out.append(pts)
            finally:
                if fr is not None:
                    fr.__exit__()
        return out

    def membrane(self, a: Chain, b: Chain, tex="web", face=False):
        """두 사슬 사이 막(거미줄, 물갈퀴): 자세마다 마디 이음점 짝을 잇는 판(평행사변형을 직사각형 판으로 근사, 넓은 두 면).
        두 사슬의 자세 수가 같아야 함"""
        t = self.model.kit.tex_id(tex)
        if None not in a.hinges and None not in b.hinges and a.n != b.n:
            raise ValueError(f"막: 두 사슬의 자세 수가 다름({a.n}, {b.n})")
        for k in a.hinges:
            if k not in b.hinges:
                continue
            ha, hb = a.hinges[k], b.hinges[k]
            fr = None if k is None else Frame(self, k, a.n)
            if fr is not None:
                fr.__enter__()
            try:
                for i in range(min(len(ha), len(hb)) - 1):
                    p0, p1, q0, q1 = v3(ha[i]), v3(ha[i + 1]), v3(hb[i]), v3(hb[i + 1])
                    m0, m1 = (p0 + q0) / 2, (p1 + q1) / 2
                    along = m1 - m0
                    if np.linalg.norm(along) < 1e-6:
                        continue
                    across = ((q0 - p0) + (q1 - p1)) / 2
                    wid = float(np.linalg.norm(across))
                    if wid < 1e-4:
                        continue
                    d = unit(along)
                    f = across - d * (across @ d)
                    if np.linalg.norm(f) < 1e-6:
                        continue
                    e = self._segment(tuple(m0), d, unit(f), float(np.linalg.norm(along)), wid, 0.005, t, t, [0, 0, 16, 16], [0, 0, 16, 1],
                                      face, "membrane")
                    e.chain = (a, b)  # 두 사슬 가운데 하나라도 흐물 막대기면 사체에서 막도 같이 빠진다
            finally:
                if fr is not None:
                    fr.__exit__()

    def teeth(self, center, radius, count=10, axis=(0, 0, 1), length=0.08, size=0.04, tex="bone", inward=0.3):
        """뼈 이빨 고리: center 둘레 radius 에 count 개, axis 쪽으로 length 만큼 솟고 안쪽으로 inward(0..1) 만큼 기욺"""
        t = self.model.kit.tex_id(tex)
        ax = unit(axis)
        ref = v3((0, 1, 0)) if abs(ax[1]) < 0.9 else v3((1, 0, 0))
        e1 = unit(np.cross(ax, ref))
        e2 = np.cross(ax, e1)
        for i in range(count):
            a = 2 * math.pi * i / count
            r = e1 * math.cos(a) + e2 * math.sin(a)
            p = v3(center) + r * radius
            d = unit(ax * (1 - inward) - r * inward)
            self._segment(tuple(p), d, unit(np.cross(d, r)) if np.linalg.norm(np.cross(d, r)) > 1e-6 else e1, length, size, size,
                          t, t, [0, 0, 16, 16], [0, 0, 16, 16], False, "tooth", cap_lo=False)

    def lump(self, count=16, radius=(1.0, 0.45, 1.0), size=0.3, face="smile", seed=1, faces_list=None, wobble=0.0, poses=None):
        """멀리서 보는 떼 덩어리(LOD): 작은 얼굴 상자 count 개를 타원체 안에 흩뿌림(가까운 얼굴, 오버레이 없음).
        wobble > 0 이면 자세마다 조금씩 출렁임(블록, 자세 수 poses 기본 min(4, 상태 자세 수): 면 수 = count x 6 x 자세)"""
        rng = np.random.RandomState(seed)
        k = self.model.kit
        self.lumped = True
        flist = faces_list or [face]
        spots = []
        for i in range(count):
            while True:
                p = rng.uniform(-1, 1, 3)
                if p @ p <= 1:
                    break
            spots.append((p * v3(radius), flist[i % len(flist)], rng.rand() * 2 * math.pi, rng.uniform(-25, 25)))
        frames = [None] if wobble <= 0 else self.frames(poses if poses is not None else min(4, self.poses))
        for fr in frames:
            if fr is not None:
                fr.__enter__()
            try:
                for (p, fc, ph, yaw) in spots:
                    off = v3((0, 0, 0))
                    if fr is not None:
                        off = v3((0, math.sin(2 * math.pi * fr.t + ph) * wobble, math.cos(2 * math.pi * fr.t + ph) * wobble * 0.5))
                    c = p + off
                    hs = size / 2
                    tex = {"south": k.face_tex(fc), "north": k.skin_tex("back"), "east": k.skin_tex("left"),
                           "west": k.skin_tex("right"), "up": k.skin_tex("top"), "down": k.skin_tex("bottom")}
                    self.box(tuple(c - hs), tuple(c + hs), tex, uv=[0, 0, 16, 16], rot=(0, yaw, 0), origin=tuple(c), face=True)
            finally:
                if fr is not None:
                    fr.__exit__()


def _auto_uv(n, f, t):
    """면 크기에 맞춘 uv(px 크기 그대로, 16 넘으면 자름)"""
    sx, sy, sz = (abs(t[i] - f[i]) for i in range(3))
    w, h = {"north": (sx, sy), "south": (sx, sy), "east": (sz, sy), "west": (sz, sy), "up": (sx, sz), "down": (sx, sz)}[n]
    return [0, 0, min(16.0, round(w, 4)), min(16.0, round(h, 4))]


def _lay_rod(root, d0, length, n, thick, box, f, index, limit=None):
    """굳은 사체의 누운 막대기: 몸 상자 box(가운데 3 + 반 크기 3)가 면 f(RAG_DOWN)를 바닥에 대고 누웠을 때, 뿌리 root 에서 바닥으로
    처져 바닥을 따라 눕는 마디 이음점 n + 1 개(모델 좌표, 블록). 원래 뻗던 쪽(d0)의 바닥 성분으로 눕고 마디마다 조금씩 꺾인다
    (늘어진 느낌). 뿌리가 높으면 앞 마디가 72도까지 처진다. limit: 이음점(끝 빼고)이 가운데에서 이 거리(블록) 안에 들게
    (모델 틀 -16..32 px), 넘으면 더 세게 말아 눕힌다"""
    c, h = v3(box[:3]), v3(box[3:])
    dn = v3(RAG_DOWN[f])
    floor = float(c @ dn + np.abs(dn) @ h)  # 모델 원점에서 바닥까지(dn 쪽 거리)
    r = v3(root)
    d0 = unit(d0)
    u = d0 - dn * float(d0 @ dn)
    if np.linalg.norm(u) < 0.25:
        u = (r - c) - dn * float((r - c) @ dn)
    if np.linalg.norm(u) < 0.05:
        u = np.cross(dn, v3((1, 0, 0)) if abs(dn[0]) < 0.5 else v3((0, 0, 1))) * (1.0 if index % 2 == 0 else -1.0)
    u = unit(u)
    side = np.cross(dn, u)
    off = float((r - c) @ side)
    sgn = (1.0 if index % 2 == 0 else -1.0) if abs(off) < 1e-6 else (1.0 if off > 0 else -1.0)
    s = length / n
    pts = []
    for wig in ((16.0, -30.0, 22.0), (38.0, 46.0, 54.0), (64.0, 72.0, 80.0), (92.0, 100.0, 108.0), (125.0, 135.0, 145.0)):
        hgt = max(0.0, (floor - thick / 2) - float(r @ dn))
        p, yaw = r.copy(), 0.0
        pts = [tuple(float(v) for v in p)]
        for k in range(n):
            sin_t = min(hgt / s, math.sin(math.radians(72))) if hgt > 1e-3 else 0.0
            cos_t = math.sqrt(max(0.0, 1 - sin_t * sin_t))
            yaw += math.radians(wig[k % 3]) * sgn
            dh = u * math.cos(yaw) + side * math.sin(yaw)
            p = p + (dh * cos_t + dn * sin_t) * s
            hgt -= s * sin_t
            pts.append(tuple(float(v) for v in p))
        if limit is None or all(max(abs(v) for v in q) <= limit for q in pts[:-1]):
            break
    return pts


# ============================================================================================== 모델(아이템 정의 하나)


class Model:
    def __init__(self, kit, name, poses, period, shrink, mut2, open, torn, wave, budget=QUAD_BUDGET, rag=True):
        if not NAME.fullmatch(name) or not name:
            raise ValueError(f"모델 이름 {name!r} 은 소문자, 숫자, _ 만")
        if not 1 <= poses <= 8:
            raise ValueError("자세 수는 1..8")
        self.kit, self.name, self.poses, self.period, self.shrink = kit, name, poses, period, shrink
        self.mut2, self.open, self.torn = mut2, open, torn
        self.budget = int(budget)
        self.wave = wave or Wave(amp=(18, 16, 14), lag=45)
        self.states = {}
        self.default = ""
        self.slots = []
        self.slot_ix = {}
        self.parts = set()
        self.rag = rag  # False 면 흐물 막대기, 사체 상태를 만들지 않음(몸 상자만 적음: 얼어붙은 몸이 통째로 구름)
        self.rag_rods = RAG_MAX_RODS
        self.rag_info = {}  # 상태 → 래그돌 정보(_auto_rag 가 채움, looks 의 rag)

    @property
    def item(self):
        return f"gabe/{self.name}"

    def slot(self, key):
        if key not in self.slot_ix:
            self.slot_ix[key] = len(self.slots)
            posed, dist, face, show, k, n = key
            # period 는 build_files 가 정함(기본 상태가 쓰는 자세 칸이면 그 주기 = 상태를 모르는 Look.colors 가 쓰는 값)
            self.slots.append({"posed": posed, "dist": dist, "face": face, "show": list(show), "k": k, "n": n, "period": 0})
        return self.slot_ix[key]

    def quads(self, state):
        """그 상태 모델의 면 수(숨긴 자세 면, 조건 면 모두)"""
        return sum(len(e.faces) for e in self.states[state].elems)

    def state(self, name="", *, poses=None, period=None):
        """상태(아이템 정의 strings[0] 값). period: 이 상태 펄럭임 주기(틱, 모델 기본값), poses: 자세 수.
        상태끼리 자세 칸을 나눠 쓰고 주기는 보낼 때 정해지므로, 다른 주기는 상태를 아는 Look.colors(model, state, ..) 로 실을 때만
        맞는다. 어느 쪽으로 실어도 맞게 빠르기만 바꾸려면 같은 주기에 Wave(cycles=2)"""
        if not NAME.fullmatch(name):
            raise ValueError(f"상태 이름 {name!r} 은 소문자, 숫자, _ 만(\"\" = 기본)")
        if name in self.states:
            return self.states[name]
        p = poses or self.poses
        if not 1 <= p <= 8:
            raise ValueError("자세 수는 1..8")
        s = State(self, name, p, period or self.period)
        self.states[name] = s
        return s

    def _auto_mut2(self):
        """변이 2: 막대기 둘 더(첫 얼굴 뒤통수 위 모서리에서 위, 뒤로, 두 마디, 자세 4). 개체 대부분은 이것을 숨긴 채 살지만 면은
        늘 실리므로 작게 둔다. mut2 = dict(root, dir, length, segs, thick, wave, toward, poses) 로 바꾸거나 False 로 끔"""
        if not self.mut2:
            return
        cfg = self.mut2 if isinstance(self.mut2, dict) else {}
        for s in self.states.values():
            if not s.heads and "root" not in cfg:
                continue
            hd = s.heads[0] if s.heads else None
            size = hd.w if hd else 0.6
            with s.when("mut>=2"):
                for side in (1, -1):
                    root = cfg.get("root")
                    if root is None:
                        root = hd.point(side * 0.7, 0.85, -0.8)
                    elif callable(root):
                        root = root(side)
                    else:
                        root = (root[0] * side, root[1], root[2])
                    d = cfg.get("dir", (0.45, 1.0, -0.7))
                    d = (d[0] * side, d[1], d[2])
                    s.rod(root, d, length=cfg.get("length", 1.1 * size), segs=cfg.get("segs", 2), thick=cfg.get("thick", 0.09),
                          wave=cfg.get("wave", self.wave), toward=cfg.get("toward", (-side * 1.0, 0.3, 0)),
                          poses=cfg.get("poses", min(4, s.poses)))

    # ------------------------------------------------------------------ 래그돌 사체

    def _rag_chains(self, s):
        """이 상태에서 흐물거리게 떼어 낼 막대기 사슬(긴 것부터 rag_rods 개)"""
        out = []
        for c in s.chains:
            if c.kind != "rod" or c.part is None or c.rag is False or c.rag == 0 or not c.elems:
                continue
            if c.rag or (c.waved and c.length >= 0.3):
                out.append(c)
        out.sort(key=lambda c: -c.length)
        return out[:self.rag_rods]

    def _rag_box(self, elems):
        """기본 겉모습, 자세 0 에서 보이는 요소들의 테두리 상자 → [가운데 x, y, z, 반 크기 x, y, z](블록) 또는 None.
        기본 겉모습에서 보이는 요소가 하나도 없는 모델(조건이 켜져야 보이는 부품)은 모든 요소로 잰다"""
        for strict in (True, False):
            pts = []
            for e in elems:
                if strict:
                    for fc in e.faces.values():
                        sl = self.slots[fc.slot]
                        if sl["dist"] != DIST["near"] and not (sl["posed"] and sl["k"] != 0) and all(cond_ok(c, DEFAULT) for c in sl["show"]):
                            break
                    else:
                        continue
                R = e.rot_m if e.rot_m is not None else np.eye(3)
                o = v3(e.origin if e.origin is not None else (8, 8, 8))
                for ix in range(8):
                    p = v3([(e.to if ix >> a & 1 else e.frm)[a] for a in range(3)])
                    pts.append((R @ (p - o) + o - 8.0) / (16.0 * self.shrink))
            if pts:
                a = np.array(pts)
                lo, hi = a.min(0), a.max(0)
                return [float(v) for v in (lo + hi) / 2] + [float(v) for v in np.maximum((hi - lo) / 2, 0.03)]
        return None

    def _rag_front(self, s):
        """얼굴이 보는 방향(몸 틀의 단위 벡터, looks 의 rag.front): s.front 를 적었으면 그것(0 벡터면 얼굴 없음), 아니면 가장 큰
        얼굴 상자(head)의 앞면 법선(같은 크기면 먼저 만든 것). 얼굴 상자가 없으면 None(적지 않음)"""
        if s.front is not None:
            v = v3(s.front)
            n = float(np.linalg.norm(v))
            return [0.0, 0.0, 0.0] if n < 1e-6 else [round(float(c), 4) for c in v / n]
        if not s.heads:
            return None
        h = max(s.heads, key=lambda hd: hd.w * hd.h)
        return [round(float(c), 4) for c in h.R @ v3((0, 0, 1))]

    def _rag_corpse(self, st, body, plans, faces_):
        """사체 상태 st 를 채운다: 몸(자세 면은 자세 0 만 남겨 보통 면 칸으로) + 면 faces_ 마다 누운 막대기(자세 면 칸, 자세 번호 =
        faces_ 안 차례, 자세 수는 len(faces_) + 1 이상). 엔진이 멈춤 자세 번호(LookState.pose)로 누운 자세를 고르고, 구르는 동안은
        자세 번호 len(faces_)(누운 막대기 없음)로 둔다. 그래서 몸은 자세 칸을 쓰지 않는다"""
        for e in body:
            fs = {}
            for n, fc in e.faces.items():
                sl = self.slots[fc.slot]
                if sl["posed"]:
                    if sl["k"] != 0:
                        continue
                    slot = self.slot((False, sl["dist"], False, tuple(sl["show"]), 0, 1))
                else:
                    slot = fc.slot
                fs[n] = FaceSpec(list(fc.uv), fc.tex, slot)
            if fs:
                st.elems.append(Elem(e.frm, e.to, fs, e.origin, e.rot_m, e.shade, e.tag))
        nv = len(faces_)
        for c, n, lie in plans:
            # 누운 자세 칸: 이 막대기가 펄럭일 때 쓰던 자세 칸(같은 조건, 자세 수)을 다시 쓸 수 있으면 다시 쓴다(칸을 아낌)
            nn = c.n if (c.waved and c.n >= nv + 1) else nv + 1
            for vi, f in enumerate(faces_):
                dn = v3(RAG_DOWN[f])
                st._frame = Frame(st, vi, nn)
                st._show = list(c.show)
                try:
                    nodes = lie[f]
                    for i in range(n):
                        a, b = v3(nodes[i]), v3(nodes[i + 1])
                        L = float(np.linalg.norm(b - a))
                        if L < 1e-6:
                            continue
                        d = (b - a) / L
                        side = np.cross(dn, d)
                        if np.linalg.norm(side) < 1e-6:
                            side = np.cross(d, v3((1, 0, 0)) if abs(d[0]) < 0.9 else v3((0, 1, 0)))
                        e = st._segment(tuple(a), d, unit(side), L * (1.03 if i < n - 1 else 1.0), c.thick, c.thick, c.tex, c.tex,
                                        [0, 0, 2, 15], [2, 0, 4, 2], False, "rod_lie", cap_lo=False, cap_hi=(i == n - 1))
                        if abs(float(d @ dn)) < 0.05:
                            e.faces.pop("north", None)  # 바닥에 붙은 면(법선이 dn 쪽)은 안 보임
                finally:
                    st._frame = None
                    st._show = []

    def _auto_rag(self):
        """래그돌 사체 정보(looks 의 rag): 상태마다 몸 상자와 얼굴 방향을 재고, 흐물 막대기가 있는 상태는 사체 상태 <상태>.c 를 만든다
        (막대기 뺀 몸 + 바닥에 닿는 면마다 누운 막대기 자세). 면 수 한도, 칸 수 한도(RAG_SLOT_LIMIT), 모델 틀을 넘으면 누운 자세 수를
        6 → 4 → 2 → 1 → 0 으로 줄인다(0 이면 막대기 뺀 몸만: 엔진이 굳을 때 막대기를 거둔다). 덩어리(lump) 상태는 건너뛴다"""
        info = {}
        for sname in list(self.states):
            s = self.states[sname]
            floppy = self._rag_chains(s) if (self.rag and not s.lumped) else []
            fl = {id(c) for c in floppy}

            def gone(e):
                cs = e.chain if isinstance(e.chain, tuple) else (e.chain,)
                return any(c is not None and id(c) in fl for c in cs)

            body = [e for e in s.elems if not gone(e)]
            box = self._rag_box(body)
            if box is None:
                continue
            ent = {"box": [round(v, 4) for v in box]}
            front = self._rag_front(s)
            if front is not None:
                ent["front"] = front
            if floppy:
                plans = []
                for i, c in enumerate(floppy):
                    n = c.rag if (isinstance(c.rag, int) and not isinstance(c.rag, bool)) else (1 if c.length < 0.5 else 2 if c.length < 1.5 else 3)
                    n = max(1, min(3, int(n)))
                    lim = 1.5 / self.shrink - c.thick  # 이음점이 모델 틀(-16..32 px) 안에 들 거리
                    plans.append((c, n, {f: _lay_rod(c.root, c.dir, c.length, n, c.thick, box, f, i, lim) for f in RAG_FACES}))
                for nv in (6, 4, 2, 1, 0):
                    st = State(self, sname + RAG_CORPSE, s.poses, s.period)
                    n_slots, ix = len(self.slots), dict(self.slot_ix)
                    try:
                        self._rag_corpse(st, body, plans, RAG_FACES[:nv])
                        if nv and (sum(len(e.faces) for e in st.elems) > self.budget or len(self.slots) > max(RAG_SLOT_LIMIT, n_slots)):
                            raise ValueError("사체 상태가 면 수나 칸 수 한도를 넘음")
                        break
                    except ValueError:
                        if nv == 0:
                            raise
                        del self.slots[n_slots:]
                        self.slot_ix = ix
                faces_ = RAG_FACES[:nv]
                self.states[st.name] = st
                ent["corpse"] = st.name
                ent["faces"] = list(faces_)
                ent["rods"] = [{"root": [round(v, 4) for v in c.root], "dir": [round(v, 4) for v in c.dir], "len": round(c.length, 4),
                                "thick": round(c.thick, 4), "part": c.part, "show": list(c.show), "segs": n,
                                "lie": [[[round(v, 4) for v in p] for p in lie[f]] for f in faces_]}
                               for c, n, lie in plans]
            info[sname] = ent
        self.rag_info = info

    def build_files(self):
        """모델 json(상태마다), 아이템 정의, 역할표 → {경로: 바이트}, looks 항목"""
        if self.default not in self.states:
            raise ValueError(f"{self.name}: 기본 상태 {self.default!r} 가 없음(state(\"\") 를 만들거나 model.default 를 정할 것)")
        for sname, s in self.states.items():
            for e in s.elems:
                for fc in e.faces.values():
                    if fc.tex not in self.kit.textures:
                        raise ValueError(f"{self.name}:{sname} 요소({e.tag}) 텍스처 {fc.tex!r} 를 키트가 모름(k.texture, k.part_tex, "
                                         f"k.face_tex 가 돌려준 id 나 공용 부품 이름 {', '.join(SHARED_PARTS)} 만)")
            q = self.quads(sname)
            if q > self.budget:
                raise ValueError(f"{self.name}:{sname} 면 {q}개가 한도 {self.budget} 를 넘음(숨긴 자세 면도 매 프레임 실림: 작은 부위는 "
                                 f"poses=4, 마디 수를 줄이거나 부위를 다른 상태로)")
        used = {n: {fc.slot for e in s.elems for fc in e.faces.values()} for n, s in self.states.items()}
        # 자세 칸의 주기(상태를 모르는 Look.colors(model, look, wt) 와 아이템 정의 기본값이 씀): 기본 상태가 쓰면 그 상태 주기,
        # 아니면 처음 쓰는 상태의 주기. 상태를 아는 Look.colors(model, state, look, wt) 는 늘 그 상태 주기로 부호한다
        seq = [self.default] + [n for n in self.states if n != self.default]
        for i, sl in enumerate(self.slots):
            if sl["posed"]:
                sl["period"] = next(self.states[n].period for n in seq if i in used[n])
        # 틴트 칸 차례: 기본 상태가 쓰는 칸(보통 면, 자세 면) 먼저, 다른 상태만 쓰는 칸 뒤. Look 이 기본값과 같은 끝 칸을 잘라 보내고
        # 상태를 알면 그 상태가 안 쓰는 칸을 기본값으로 두므로, 기본 상태 개체는 앞 칸만 보낸다
        dflt = used[self.default]
        order = sorted(range(len(self.slots)), key=lambda i: (i not in dflt, self.slots[i]["posed"], i))
        remap = {old: new for new, old in enumerate(order)}
        self.slots = [self.slots[i] for i in order]
        self.slot_ix = {kk: remap[v] for kk, v in self.slot_ix.items()}
        for s in self.states.values():
            for e in s.elems:
                for fc in e.faces.values():
                    fc.slot = remap[fc.slot]
        used = {n: sorted(remap[i] for i in u) for n, u in used.items()}
        files = {}
        tex_vars = {}
        for sname, s in self.states.items():
            texmap = {}
            els = []
            for e in s.elems:
                fj = {}
                for n, fc in e.faces.items():
                    if fc.tex not in texmap:
                        texmap[fc.tex] = f"t{len(texmap)}"
                    fj[n] = {"uv": [round(v, 4) for v in fc.uv], "texture": "#" + texmap[fc.tex], "tintindex": fc.slot}
                ej = {"from": [round(v, 4) for v in e.frm], "to": [round(v, 4) for v in e.to], "faces": fj}
                if not e.shade:
                    ej["shade"] = False
                if e.rot_m is not None:
                    x, y, z = e.euler
                    ej["rotation"] = {"origin": [round(v, 4) for v in e.origin], "x": x, "y": y, "z": z}
                els.append(ej)
            if not els:
                raise ValueError(f"{self.name}:{sname} 요소가 없음")
            textures = {v: k for k, v in texmap.items()}
            textures["particle"] = next(iter(texmap))
            mname = self.name if sname == "" else f"{self.name}_{sname}"
            files[f"assets/{NS}/models/item/gabe/{mname}.json"] = json.dumps({"textures": textures, "elements": els},
                                                                            separators=(",", ":")).encode()
            tex_vars[sname] = mname
        tints = [{"type": "minecraft:custom_model_data", "index": i, "default": encode_slot(sl, DEFAULT, 0)}
                 for i, sl in enumerate(self.slots)]

        def ref(sname):
            return {"type": "minecraft:model", "model": f"{NS}:item/gabe/{tex_vars[sname]}", "tints": tints}

        cases = [{"when": n, "model": ref(n)} for n in self.states if n != self.default]
        if cases:
            model = {"type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0, "cases": cases,
                     "fallback": ref(self.default)}
        else:
            model = ref(self.default)
        files[f"assets/{NS}/items/gabe/{self.name}.json"] = json.dumps({"hand_animation_on_swap": False, "model": model},
                                                                       separators=(",", ":")).encode()
        looks = {"v": LOOKS_VERSION, "sp": self.kit.sp, "scale": round(1.0 / self.shrink, 6), "states": list(self.states),
                 "default": self.default, "parts": sorted(self.parts), "poses": self.poses, "period": self.period,
                 "periods": {n: s.period for n, s in self.states.items()}, "uses": used,
                 "quads": {n: self.quads(n) for n in self.states}, "budget": self.budget,
                 "slots": [{"posed": int(s["posed"]), "dist": s["dist"], "face": int(s["face"]), "show": s["show"], "k": s["k"],
                            "n": s["n"], "period": s["period"]} for s in self.slots]}
        if self.rag_info:
            # 래그돌 사체(엔진 Ragdoll 이 읽음, 옛 엔진은 모르는 칸이라 건너뜀): 상태 → box, corpse(<상태>.c), faces(누운 자세의 면), rods
            looks["rag"] = self.rag_info
        return files, looks


# ============================================================================================== 종 하나의 키트


class Kit:
    """종 하나(gxx)의 키트. 종 스크립트의 build(k), sounds(k) 가 받는다"""

    def __init__(self, sp: str, info: dict | None = None):
        self.sp = sp.upper()
        self.key = sp.lower()
        self.info = info or {}
        self.models = {}
        self.textures = {}  # id → RGBA ndarray
        self.sounds_json = {}
        self.lang = {}
        self.ogg = {}  # 팩 경로 → 소리 배열
        self.ogg_bytes = {}  # 팩 경로 → 받은 소리를 다듬어 굳힌 ogg 바이트(k.sample)
        self.samples = []  # 쓴 받은 소리 아이디(출처 확인용, 같은 차례)
        self.covers = {}  # 키 → (PIL 그림)
        self.icon_img = None
        self.icon_from = None

    # ------------------------------------------------------------------ 모델

    def model(self, name=None, *, poses=8, period=20, shrink=1.0, mut2=True, open=True, torn=True, wave=None, budget=QUAD_BUDGET,
              rag=True) -> Model:
        """아이템 정의 하나(gave:gabe/<name>, 기본 이름 = 종 번호 소문자). poses: 펄럭임 자세 수(1..8), period: 한 바퀴 틱
        (셰이더 주기 칸에 맞춰 반올림: 4 6 8 10 12 15 16 20 24 30 40 48 60 80 120), shrink: 모델이 ±1.5 블록을 넘으면 0.5 처럼
        줄이고(엔진 Body 가 Look.scale 로 되돌림), mut2/open/torn: 변이 2 막대기 둘, 실눈 뜸, 입 찢어짐을 저절로 붙일지,
        wave: 변이 2 막대기의 물결(기본 Wave(amp=(18, 16, 14), lag=45)), budget: 상태 모델 하나의 면 수 한도(기본 400, 넘으면 굽기 실패),
        rag: 래그돌 사체 상태(<상태>.c: 막대기 뺀 몸 + 누운 막대기 자세)를 저절로 만들지(False 면 죽은 몸이 얼어붙은 채 통째로 구름)"""
        name = name or self.key
        if not name.startswith(self.key):
            raise ValueError(f"모델 이름은 종 번호로 시작({self.key}, {self.key}_spit 처럼)")
        if name in self.models:
            return self.models[name]
        m = Model(self, name, poses, period, shrink, mut2, open, torn, wave, budget, rag)
        self.models[name] = m
        return m

    # ------------------------------------------------------------------ 텍스처

    @staticmethod
    def _safe_tid(tid):
        """Iris, OptiFine 같은 셰이더 모드는 이름이 _n, _s 로 끝나는 텍스처를 PBR 노멀, 스페큘러 맵으로 보고 아틀라스에서 뺀다(_e 는 발광 맵).
        그러면 그 텍스처를 쓰는 면이 보라 검정으로 나온다(2026-11-11 실서버에서 가까운 얼굴 90장이 그랬다). 끝 글자를 바꿔 피한다."""
        for suf, rep in (("_n", "_nr"), ("_s", "_sp"), ("_e", "_em")):
            if tid.endswith(suf):
                return tid[:-len(suf)] + rep
        return tid

    def _put(self, tid, arr):
        tid = self._safe_tid(tid)
        if tid in self.textures:
            return tid
        arr = np.asarray(arr)
        if arr.dtype != np.uint8 or arr.ndim != 3 or arr.shape[2] != 4:
            raise ValueError(f"텍스처 {tid}: uint8 RGBA 배열이어야 함")
        a = arr[..., 3]
        if ((a > 0) & (a < 255)).any():
            raise ValueError(f"텍스처 {tid}: 반투명 화소(알파 1..254)는 반투명 층으로 빠져 정렬이 깨짐(0 아니면 255 로)")
        self.textures[tid] = arr
        return tid

    def texture(self, name, img, classes=False):
        """종 전용 텍스처(PIL 그림 또는 RGBA 배열) → id gave:item/gabe/<종>/<name>. 알파는 0 또는 255 만.
        셰이더 팔레트는 파랑 아래 3비트를 화소 부류로 읽으므로(0 살 1 눈 줄 2 홍조 3 입 4 흰자 5 반짝임 6 머리 7 이) 기본은 그 비트를
        0(살)으로 지운다. faces.Canvas.rgba() 처럼 부류를 일부러 새긴 그림이면 classes=True"""
        if not NAME.fullmatch(name):
            raise ValueError("텍스처 이름은 소문자, 숫자, _ 만")
        arr = np.array(img.convert("RGBA") if isinstance(img, Image.Image) else img, dtype=np.uint8, copy=True)
        if not classes and arr.ndim == 3 and arr.shape[2] == 4:
            arr[..., 2] &= 0xF8
        return self._put(f"{NS}:item/gabe/{self.key}/{name}", arr)

    def part_tex(self, name):
        """공용 부품 텍스처: rod(엔드 막대기), flesh(분홍 살), wetskin(젖은 천 살), chain(쇠사슬), web(거미줄), bone(뼈). 화소 부류 0"""
        if name not in faces.PARTS:
            if ":" in name:
                return name
            raise KeyError(f"부품 텍스처 {name!r} 없음({', '.join(faces.PARTS)})")
        return self._put(f"{NS}:item/gabe/{name}", faces.PARTS[name]())

    def tex_id(self, tex):
        """면 텍스처 값 → id: 공용 부품 이름(rod, flesh, ...)은 part_tex 로, 그 밖은 그대로(이름공간이 있어야 함, build_files 가
        키트가 만든 텍스처인지 다시 확인)"""
        if isinstance(tex, str) and tex in faces.PARTS:
            return self.part_tex(tex)
        if not isinstance(tex, str) or ":" not in tex:
            raise ValueError(f"텍스처 {tex!r}: k.texture, k.part_tex, k.face_tex 가 돌려준 id 나 공용 부품 이름({', '.join(faces.PARTS)})")
        return tex

    def face_tex(self, face, near=False):
        """얼굴 텍스처 id(먼 32 또는 가까운 128). face: PRESETS 이름 또는 faces.Face"""
        f = faces.preset(face)
        key = f.key()
        if near:
            return self._put(f"{NS}:item/gabe/face/{key}_n", faces.near(f))
        return self._put(f"{NS}:item/gabe/face/{key}", faces.far(f))

    def overlay_tex(self, face, kind):
        """오버레이(open | torn): (bbox32, id32, id128) 또는 None"""
        f = faces.preset(face)
        ov = faces.overlay(f, kind)
        if ov is None:
            return None
        bb, a, b = ov
        key = f.key()
        return bb, self._put(f"{NS}:item/gabe/face/{key}__{kind}", a), self._put(f"{NS}:item/gabe/face/{key}__{kind}_n", b)

    def skin_tex(self, which):
        return self._put(f"{NS}:item/gabe/face/head_{which}", faces.skin_side(which))

    # ------------------------------------------------------------------ 소리

    def sound(self, name, subtitle, *, vanilla=(), synth=None, volume=1.0, pitch=1.0, attenuation=16, event=None, sample=(), alias=None):
        """소리 사건 gave:gabe.<종>.<name>(Java: Gabe.sound(name, ...)). 변형마다 하나씩 무작위로 울림.
        vanilla: ["mob/phantom/flap1", ("mob/slime/big1", 0.8), {"name": ..., "pitch": ..., "volume": ...}] 처럼 바닐라 소리 파일
        (minecraft: 를 붙이지 않은 경로, 피치를 바꿔 가리킴). synth: audio 함수가 만든 배열 하나, 목록, 또는 seed → 배열 함수(변형 3개).
        sample: 받은 소리(data/sfx_lib.json) 목록. 꼴은 k.sample 의 src 와 같음(아이디, 'tag:..', dict). 새 소리는 synth 대신 이것으로.
        subtitle: 한국어 자막(자막 키 subtitles.gave.gabe.<종>.<name>). pitch 는 들리는 높이(Java 는 흔들림만 보냄).
        attenuation: 들리는 거리(블록, Java 가 보내는 반경과 맞출 것. 위협 예고음은 넓게)
        alias: 다른 사건 이름(예 'gabe.cry_mid'). 주면 그 사건을 가리키는 별칭(type event: 높이와 크기는 곱해지고 들리는 거리와 자막은 그 사건 것,
        subtitle 은 None)"""
        if not NAME.fullmatch(name):
            raise ValueError("소리 이름은 소문자, 숫자, _ 만")
        if MIDDLE_DOT in (subtitle or ""):
            raise ValueError("자막에 가운뎃점 금지")
        ev = event or f"gabe.{self.key}.{name}"
        entries = [{"name": f"{NS}:{alias}", "type": "event", "volume": round(volume, 3), "pitch": round(pitch, 3)}] if alias else []
        for v in vanilla:
            if isinstance(v, str):
                v = {"name": v}
            elif isinstance(v, (tuple, list)):
                v = {"name": v[0], "pitch": v[1]}
            n = v["name"]
            if not n.startswith("minecraft:"):
                n = "minecraft:" + n
            _check_vanilla(n)
            entries.append({"name": n, "volume": round(v.get("volume", volume), 3), "pitch": round(v.get("pitch", 1.0) * pitch, 3),
                            "attenuation_distance": int(attenuation)})
        if synth is not None:
            if callable(synth):
                arrs = [synth(i) for i in range(3)]
            elif isinstance(synth, (list, tuple)):
                arrs = list(synth)
            else:
                arrs = [synth]
            for i, a in enumerate(arrs):
                rel = f"gabe/{self.key}/{name}_{i}"
                self.ogg[f"assets/{NS}/sounds/{rel}.ogg"] = np.asarray(a, np.float32)
                entries.append({"name": f"{NS}:{rel}", "volume": round(volume, 3), "pitch": round(pitch, 3),
                                "attenuation_distance": int(attenuation)})
        for i, (ref, o) in enumerate(_sample_refs(sample, name)):
            rel = f"gabe/{self.key}/{name}_s{i}"
            x = audio.prepare(ref, start=o.get("start"), end=o.get("end"), level=o.get("level", -14.0), max_len=o.get("max_len"))
            self.ogg_bytes[f"assets/{NS}/sounds/{rel}.ogg"] = audio.ogg_bytes(x)
            self.samples.append(ref)
            ent = {"name": f"{NS}:{rel}", "volume": round(o.get("volume", 1.0) * volume, 3), "pitch": round(o.get("pitch", 1.0) * pitch, 3),
                   "attenuation_distance": int(attenuation)}
            if len(x) > 8 * audio.SR:
                ent["stream"] = True  # 긴 소리는 스트리밍(메모리에 통째로 올리지 않음)
            entries.append(ent)
        if not entries:
            raise ValueError(f"소리 {name}: vanilla, synth, sample 가운데 하나는 있어야 함")
        self.sounds_json[ev] = {"sounds": entries, "subtitle": f"subtitles.{NS}.{alias or ev}"}
        if not alias:
            self.lang[f"subtitles.{NS}.{ev}"] = subtitle
        return f"{NS}:{ev}"

    def sample(self, name, src, subtitle=None, *, pitch=1.0, volume=1.0, attenuation=16, n=3, level=-14.0, event=None):
        """받은 소리(내려받은 CC0, CC BY 효과음, data/sfx_lib.json)로 소리 사건 gave:gabe.<종>.<name> 을 만든다(Java: Gabe.sound(name, ..)).
        src: 아이디 'kenney_impact/impactpunch_heavy_000', 긴 녹음의 덩어리 'oga_snarls/monster_snarls_0#1', 'tag:roar'(태그로 찾아
        n 개, 여럿은 'tag:roar+far', 빼기 'tag:roar-boss'), glob 'oga_zombies/zombie_1*', 또는 {"id": .., "pitch": .., "volume": ..,
        "start": 초, "end": 초, "level": dB, "max_len": 초} 이나 그 목록. 변형마다 한 파일(앞 무음 자름, 가장 큰 0.4초 크기를 level dBFS 로,
        mono, libvorbis q4) → assets/gave/sounds/gabe/<종>/<name>_s<i>.ogg. 8초 넘는 것은 stream.
        subtitle: 한국어 자막(없으면 첫 소리 태그의 한국어, 예: roar → 포효). pitch 는 들리는 높이(sounds.json), attenuation = Java 반경.
        찾아보기: python -X utf8 tools/gabe/audio.py find tag:roar"""
        refs = _sample_refs(src, name, n)
        if subtitle is None:
            q = src if isinstance(src, str) else ""
            want = re.findall(r"(?:^|\+)([a-z0-9_]+)", q[4:]) if q.startswith("tag:") else []
            subtitle = audio.ko(want + audio.entry(refs[0][0])["tags"])
        return self.sound(name, subtitle, sample=[dict(o, id=r, level=o.get("level", level)) for r, o in refs], volume=volume, pitch=pitch,
                          attenuation=attenuation, event=event)

    # ------------------------------------------------------------------ 화면 덮개, 아이콘

    def cover(self, key, img):
        """화면 덮개 그림(사람 화면 전체를 덮는 그림, make_hud 가 240 화소 조각 글리프로 굽는다): key 는 소문자(종 번호로 시작 권장).
        img: PIL 그림(RGBA, 알파 0..255 됨, 960x540 이하 권장) → build/gabe/covers/<key>.png, covers.json"""
        if not NAME.fullmatch(key) or not key:
            raise ValueError("덮개 키는 소문자, 숫자, _ 만")
        im = img.convert("RGBA")
        if im.width > 1920 or im.height > 1080:
            raise ValueError("덮개 그림은 1920x1080 이하")
        self.covers[key] = im
        return key

    def cover_face(self, key, face="smile", size=(480, 270), zoom=1.6, vignette=0.85):
        """얼굴을 화면 가득 들이민 덮개(가까운 얼굴 128 을 키워 가운데, 가장자리 어둡게)"""
        f = faces.preset(face)
        nb = Image.fromarray(faces.near(f))
        W, H = size
        s = int(max(W, H) * zoom)
        big = nb.resize((s, s), Image.BICUBIC)
        out = Image.new("RGBA", (W, H), (0, 0, 0, 255))
        out.alpha_composite(big, ((W - s) // 2, (H - s) // 2))
        arr = np.asarray(out, np.float32)
        yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
        r = np.hypot((xx - W / 2) / (W / 2), (yy - H / 2) / (H / 2))
        arr[..., :3] *= (1 - vignette * np.clip((r - 0.55) / 0.6, 0, 1))[..., None]
        return self.cover(key, Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8)))

    def icon(self, img=None, *, model=None, state=None, view="front"):
        """HUD 아이콘(64x64, 투명 바탕). img 를 주면 그 그림, 아니면 model(기본 첫 모델)의 state(기본 기본 상태) 정면 그림"""
        if img is not None:
            self.icon_img = img.convert("RGBA").resize((64, 64), Image.LANCZOS)
        else:
            self.icon_from = (model, state, view)

    # ------------------------------------------------------------------ 굽기

    def visible(self, model, state, k=0, look=DEFAULT, near=False):
        """그 상태에서 look 일 때 자세 k 에 보이는 요소(면 단위로 거른 사본) — 미리보기, 아이콘용"""
        s = model.states[state]
        out = []
        for e in s.elems:
            fs = {}
            for n, fc in e.faces.items():
                sl = model.slots[fc.slot]
                if not all(cond_ok(c, look) for c in sl["show"]):
                    continue
                if sl["dist"] == DIST["near"] and not near:
                    continue
                if sl["dist"] == DIST["far"] and near:
                    continue
                if sl["posed"] and sl["k"] != k % sl["n"]:
                    continue
                fs[n] = fc
            if fs:
                ne = Elem(e.frm, e.to, fs, e.origin, e.rot_m, e.shade, e.tag)
                out.append(ne)
        return out

    def render(self, model, state="", k=0, look=DEFAULT, view="front", size=64, fit=None, near=False, bg=None):
        els = self.visible(model, state, k, look, near)
        return render.render(els, self.textures, view=view, size=size, fit=fit, bg=bg)

    def finish(self):
        """모델 마무리(변이 2 막대기), 엔진이 모든 종에 울리는 hurt, die 소리 기본값과 보탠 크리처 소리 별칭(voice_aliases)
        → (팩 파일 {경로: 바이트}, looks {모델: 항목}, 아이콘 그림)"""
        if self.models and self.key != "common":
            for name, (sub, van, att) in DEFAULT_SOUNDS.items():
                if f"gabe.{self.key}.{name}" not in self.sounds_json:
                    self.sound(name, sub, vanilla=van, attenuation=att)
            for name, target, p in voice_aliases(self.key):
                if f"gabe.{self.key}.{name}" not in self.sounds_json:
                    self.sound(name, None, alias=target, pitch=p)
        files, looks = {}, {}
        for m in self.models.values():
            m._auto_mut2()
            m._auto_rag()
            f, lk = m.build_files()
            files.update(f)
            looks[m.item] = lk
        for tid, arr in self.textures.items():
            ns, path = tid.split(":", 1)
            buf = _png(arr)
            files[f"assets/{ns}/textures/{path}.png"] = buf
        for path, arr in self.ogg.items():
            files[path] = arr
        files.update(self.ogg_bytes)  # 받은 소리(이미 ogg 바이트)
        icon = self.icon_img
        if icon is None and self.models and self.key != "common":
            mname, sname, view = self.icon_from or (None, None, "front")
            m = self.models[mname] if mname else next(iter(self.models.values()))
            sname = sname if sname is not None else m.default
            fit = None
            st = m.states[sname]
            if st.heads and view == "front":
                # 얼굴이 아이콘을 채우게: 첫 얼굴 둘레 1.9배 틀(막대기 끝은 잘려 나가도 됨)
                h = st.heads[0]
                cx, cy = (8 + 16 * m.shrink * h.center[0]), -(8 + 16 * m.shrink * h.center[1])
                half = 16 * m.shrink * max(h.w, h.h) / 2 * 1.9
                fit = (cx - half, cy - half, cx + half, cy + half)
            icon, _ = self.render(m, sname, view=view, size=64, fit=fit)
        return files, looks, icon


def _sample_refs(sample, name, n=3, max_dur=6.0):
    """k.sound(sample=..), k.sample(src) 를 [(아이디[#덩어리], 옵션)] 로 푼다. 'tag:', glob 은 data/sfx_lib.json 에서 찾아 n 개를 고른다
    (아이디 차례로 늘어놓고 사건 이름에서 정한 자리부터 고른 간격으로: 같은 입력이면 늘 같은 것). max_dur 초 넘는 녹음은 그 덩어리(parts)로 바꿔 고름"""
    if not sample:
        return []
    if isinstance(sample, (str, dict)):
        sample = [sample]
    out = []
    for s in sample:
        if isinstance(s, dict):
            o = {k: v for k, v in s.items() if k != "id"}
            for r, o2 in _sample_refs(s["id"], name, n, max_dur):
                out.append((r, {**o2, **o}))
            continue
        if s.startswith("tag:") or any(c in s for c in "*?["):
            cand = []
            for e in audio.find(s):
                if e["dur"] <= max_dur:
                    cand.append(e["id"])
                else:
                    cand += [f"{e['id']}#{i}" for i, (a, b) in enumerate(e.get("parts") or []) if b - a <= max_dur]
            if not cand:
                raise ValueError(f"받은 소리 {s!r}: 맞는 것이 없음(python -X utf8 tools/gabe/audio.py find {s})")
            k = min(n, len(cand))
            off = sum(map(ord, name)) % len(cand)
            out += [(cand[(off + i * len(cand) // k) % len(cand)], {}) for i in range(k)]
        else:
            audio.entry(s)  # 없는 아이디면 여기서 오류
            out.append((s, {}))
    return out


# 엔진(Mobs)이 모든 종에 울리는 소리: 종 스크립트가 같은 이름으로 sound() 를 부르면 그것이 이김. (자막, 바닐라 파일, 들리는 거리 = Java 반경)
DEFAULT_SOUNDS = {
    "hurt": ("게이브 움찔", [("mob/slime/small1", 1.45), ("mob/slime/small3", 1.5), ("mob/slime/small5", 1.4)], 16),
    "die": ("게이브 철퍽", [("mob/slime/big2", 0.72), ("mob/slime/big4", 0.68)], 24),
}


# 보탠 크리처 소리(2026-11-14, tools/gabe/flesh.py NEAR)를 종마다 무엇으로 쓸지. Java kr.gave.mob.Ears 가 gabe.<종>.idle, gait, gait_<재질>, flesh 를 부름
VOICE = ROOT / "data/gabe_voice.json"
_VOICE = None


def _voice_rows(v):
    rows = [("flesh", f"gabe.flesh_{v['size']}")]
    if v["cry"]:
        rows.append(("idle", f"gabe.cry_{v['cry']}"))
    if v["move"] == "flap":
        rows.append(("gait", "gabe.move_flap"))
    elif v["move"]:
        rows += [(f"gait_{m}", f"gabe.move_{v['move']}_{m}") for m in flesh.MATERIALS]
    return [(name, target, v["pitch"]) for name, target in rows]


def voice_aliases(key):
    """data/gabe_voice.json 의 그 종 줄 → [(사건 이름, 가리킬 공용 사건, 높이)]. 표에 없는 종은 []. 표가 틀리면(없는 공용 사건, 높이 범위 밖) 멈춤"""
    global _VOICE
    if _VOICE is None:
        table = json.loads(VOICE.read_text(encoding="utf-8"))["species"]
        for k, v in table.items():
            bad = [t for _, t, _ in _voice_rows(v) if t.split(".", 1)[1] not in flesh.NEAR]
            if bad or not 0.5 <= v["pitch"] <= 2.0:
                raise ValueError(f"{VOICE.name} {k}: 없는 공용 사건 {bad} 또는 높이 {v['pitch']} 가 0.5..2 밖")
        _VOICE = table
    v = _VOICE.get(key)
    return _voice_rows(v) if v else []


def _png(arr):
    import io
    b = io.BytesIO()
    Image.fromarray(arr).save(b, "PNG", optimize=True)
    return b.getvalue()


_VANILLA = None


def _check_vanilla(name):
    """바닐라 소리 파일이 26.3 자산 색인에 있는지(없으면 오류). 색인이 없으면 건너뜀"""
    global _VANILLA
    if _VANILLA is None:
        import os
        idx = Path(os.environ.get("APPDATA", "")) / ".minecraft/assets/indexes/34.json"
        if idx.is_file():
            objs = json.loads(idx.read_text(encoding="utf-8"))["objects"]
            _VANILLA = {k[len("minecraft/sounds/"):-4] for k in objs if k.startswith("minecraft/sounds/") and k.endswith(".ogg")}
        else:
            _VANILLA = set()
    if _VANILLA and name.split(":", 1)[1] not in _VANILLA:
        raise ValueError(f"바닐라 소리 {name} 가 26.3 자산에 없음")
