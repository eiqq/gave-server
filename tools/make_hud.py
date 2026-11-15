"""게이브 화면 HUD 층을 만든다(좀비런 make_hud.py 에서 옮겨 게이브용으로 다시 짬, 26.3 전용): 글꼴, 그림 글리프, 셰이더 모양 글리프,
core/text 고리, 플러그인 표 HudSpec.java.

python -X utf8 tools/make_hud.py [--preview build/hud/preview.png] [--mock build/hud/mock.png [--no-check]]

- 보스바 제목 한 줄에 실린 표시 색 글리프를 core/text(GUI 변형)가 화면 칸으로 옮긴다(신호는 pack_hud/hud.glsl 머리말).
  칸 표(SLOTS)와 팔레트(PALETTE)의 이름은 kr.gave.game.Hud 가 HudLine.slot / pal 로 부른다. 칸 차례 = 그리는 차례(덮개가 맨 밑).
- 총, 수류탄 그림은 소품 층(build/props/pack_layer, 읽기만)의 모드 모델을 옆에서 그린 실루엣(tools/props/vmodel.py).
  소모품 아이콘과 동전은 총 담당 make_shop_icons.py 가 그린 pack_src/assets/gave/textures/item/shop/*.png(읽기만, 없으면 건너뜀).
  글자는 바닐라 ascii.png(1배, 2배). 막대, 고리, 적중 표시, 점, 무한, 방향 화살표는 셰이더가 그린다(글리프 텍셀에는 자리와 모양 번호만).
- 한국어 글귀(phrases): data/phrases/*.json({키: 글})과 data/bestiary.json 종 이름(키 name_g01 .. name_g55)을 Galmuri11 Bold
  (art/fonts, OFL 1.1)로 미리 그린 그림 글리프. 그림 이름 = 글귀 키(소문자), 240 텍셀보다 넓으면 키.2, 키.3 으로 나눈다.
- 종 아이콘: build/gabe/icons/<종>.png(64x64, make_gabe 가 만듦) 하나를 세 크기 글리프 ic_l_<종>(20 GUI, 죽은 이유 카드),
  ic_m_<종>(14, 알림과 처음 보는 종), ic_s_<종>(10, 동료 줄, 표시, 게이지)로. 없는 종은 Java 가 ic_*_default(새로 그린 얼굴)를 쓴다.
- 화면 덮개: build/gabe/covers.json(make_gabe) + 이 생성기의 dark, test, letterbox(보스 등장). 16:9 로 가운데 자르고 480x270(원본 폭 960 이상이면 960x540)
  으로 맞춰 4 x 3 조각(120 x 90 GUI) cv_<키>_<0..11> 로 굽는다. Java 는 조각을 121 간격으로 놓고 셰이더(FIT)가 틈을 없애며 화면을 덮게 키운다.
- 쓰는 곳: pack_src/assets/gave/font/hud*.json, pack_src/assets/gave/textures/font/hud/, pack_src/assets/gave/shaders/include/
  hud*.glsl, pack_src/assets/minecraft/shaders/core/text.vsh, text.fsh, 그리고 build/wip/hud/.../hud/HudSpec.java(compile_wip --promote 로 옮김).
- 먼저 임시 사본(pack_src + 새 파일)에서 check_shaders.py --mc 26.3 을 돌려 0 오류일 때만 pack_src 에 쓴다(깨진 core 셰이더는 팩 전체를 내림).
  여러 사람이 같이 돌려도 build/locks/make_hud.lock 운영체제 잠금으로 줄을 선다.
- --mock 은 427x240, 480x270 GUI 배치 그림 셋(전투, 쓰러짐과 쉬는 시간, 덮개)을 <이름>_a_427.png 처럼 만든다(게임 아님, 대강).
  바닐라가 그리는 자리(핫바 아이템, 자막, 채팅, 조준점)를 옅은 선으로 같이 그린다.
함정(eternalmc docs/HUD설계.md): 글리프는 240 텍셀 아래, 0 <= ascent <= height, GUI 크기는 올림(ceil(x - 0.01)).
"""
import argparse
import io
import re
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

from mcjar import client_jar  # 26.3 클라 jar 를 공식 런처 자리 말고도 찾는다(GV_CLIENT_JAR, fabric-loom 캐시. 공식 자리에 있으면 예전과 같음)

ROOT = Path(__file__).resolve().parent.parent
PACK = ROOT / "pack_src"
SRC = ROOT / "pack_hud"
PL = ROOT / "build/props/pack_layer"  # 소품 층(다른 담당, 읽기만)
GABE = ROOT / "build/gabe"  # 게이브 몹 층 산출물(팩 담당 make_gabe, 읽기만)
ICON_DIR = GABE / "icons"
COVERS_JSON = GABE / "covers.json"
SHOP_TEX = PACK / "assets/gave/textures/item/shop"  # 총 담당 make_shop_icons.py 몫(읽기만)
JAR = client_jar()  # tools/mcjar.py: 공식 런처로 26.3 을 켠 적 없는 PC 에서도 돌게
VANILLA = Path.home() / ".cache/mc-shader-vanilla/26.3/assets/minecraft/shaders/core"
JAVA_OUT = ROOT / "build/wip/hud/plugin/src/main/java/kr/gave/hud/HudSpec.java"
CHECK = Path.home() / ".claude/skills/minecraft-shader/scripts/check_shaders.py"
LOCK = ROOT / "build/locks/make_hud.lock"
TEX = "assets/gave/textures/font/hud"  # 이 폴더와 font/hud*.json 은 통째로 이 생성기 것
# 한국어 글귀 그림(phrases): Galmuri11 Bold(OFL 1.1, art/fonts/LICENSE-Galmuri.txt). 글꼴 파일은 팩에 넣지 않고 그린 그림만 넣는다
FONT_KO = ROOT / "art/fonts/Galmuri11-Bold.ttf"
PHRASE_DIR = ROOT / "data/phrases"  # *.json = {키: 글}. 파일 이름은 묶음 이름일 뿐(common.json, G01.json ...)
BESTIARY = ROOT / "data/bestiary.json"  # species[].id, name → 키 name_g01 .. name_g55
KO_PX = 12  # Galmuri11 이 화소에 딱 맞는 크기(12 에서 계조 2 개, 한글 잉크 11 줄, 그리기 원점 기준 1..11 줄, 기준선 12 줄)
KO_TOP = 1  # 그림 첫 줄 = 그리기 원점 기준 1 줄(한글 잉크 맨 위)
KO_ROWS, KO_ROWS_DESC = 12, 16  # 그림 높이 텍셀: 잉크 11 + 그림자 1, 기준선 아래 잉크(g, y, p 같은)가 있으면 16
KO_SCALE = (3, 4)  # 텍셀 1 = 3/4 GUI: 높이 12 텍셀 = 9 GUI, 16 텍셀 = 12 GUI(GUI 배율 4 에서 글꼴 화소 1 = 화면 3 화소)
KO_ASC = 8  # 기준선 위 GUI(잉크 11 줄 = 8.25 GUI 를 내림, 그림자 줄은 기준선 아래)
KO_STEP = 4  # 넓은 글귀를 나누는 자리(텍셀 4 = GUI 3, 조각 x 가 GUI 정수라 이어 붙인 자리가 맞음)
KO_MAX = 240  # 한 그림 최대 폭(텍셀), 26.3 글리프 한계
KO_SHADOW = (24, 24, 26, 224)  # 오른쪽 아래 1 화소 그림자(팔레트가 곱해도 어둡게 남음)

sys.path.insert(0, str(ROOT / "tools/props"))
import vmodel as V  # noqa: E402  소품 담당 미리보기 그리기 도구(읽기만)

# ---------------------------------------------------------------- 신호, 표

B_IMG, B_SHAPE, B_TIMED = 96, 97, 98
PERIOD = 3000  # 시간 모양 끝 틱 주기(24000 의 약수: GameTime 이 24000 틱마다 감겨도 이어짐)
TIMED_SPAN = -(-PERIOD // 256)
assert B_TIMED + TIMED_SPAN <= 128, "신호 B 가 바닐라 이름 색(170)에 닿는다"
MOVE_BITS = 10  # 펜 이동: +2^i = U+E000+i, -2^i = U+E010+i
RES = 4  # 그림 텍셀/GUI
SHAPE_TEX = 32  # 원형 모양 글리프 텍셀(자리 신호라 크기와 화질은 무관)
HIT_TICKS = 7
# 시간 모양 길이 칸(틱). 재장전 9, 28, 36, 44, 52, 일으키기 60, 출혈 240, 준비 200, 쉬는 시간 300, 500(50웨이브부터 15초 = 300) 은 딱 맞음
DUR = list(range(1, 13)) + list(range(14, 41, 2)) + list(range(44, 101, 4)) + list(range(110, 301, 10)) + [320, 400, 500]
assert len(DUR) == 64, len(DUR)
RING_A = (0.80, 0.97)  # 일으키기 고리(40 GUI) 안, 바깥 반지름(가운데 기준 -1..1)
RING_B = (0.88, 0.97)  # 출혈, 탈출 고리(48 GUI)
TRACK = (0.06, 0.06, 0.08, 0.62)  # 막대, 고리 빈 몫
EDGE = (0.0, 0.0, 0.0, 0.78)  # 막대, 고리 테두리
HP_W = 84  # 체력 막대 폭(GUI = 텍셀)
# 화면 덮개: 기준 화면(GUI), 조각(GUI), 조각 놓는 간격(1 GUI 틈 = 셰이더가 꼭짓점이 어느 조각의 어느 모서리인지 알아보는 표)
COVER_W, COVER_H = 480, 270
COVER_TW, COVER_TH = 120, 90
COVER_COLS, COVER_ROWS = COVER_W // COVER_TW, COVER_H // COVER_TH
COVER_STEP = COVER_TW + 1
COVER_X0 = -COVER_W // 2
PTR_R = (0.30, 0.25)  # 방향 화살표 타원 반지름(GUI 폭, 높이에 대한 비율)

# 칸 방식 비트: DRAIN 남은 몫으로 채움(줄어듦), PULSE 깜빡임, HIDE 시간 모양이 끝나면 숨김, FIT 화면 덮개 조각(틈 없애고 화면 덮게 키움),
# POLAR 방향 화살표(G = 각도 0..255, 셰이더가 타원 둘레로 옮기고 돌림), NOTRACK 막대 빈 몫을 안 그림(밑의 막대가 보임),
# THROB 밝기로 깜빡임(불투명 그대로: 밑의 막대가 비치지 않음)
DRAIN, PULSE, HIDE, FIT, POLAR, NOTRACK, THROB = 1, 2, 4, 8, 16, 32, 64
WHITE = (0.96, 0.96, 0.96, 1.0)
RED = (0.90, 0.20, 0.17, 1.0)
GREEN = (0.36, 0.86, 0.32, 1.0)
GOLD = (1.0, 0.76, 0.18, 1.0)
ORANGE = (1.0, 0.56, 0.16, 1.0)
PURPLE = (0.72, 0.42, 1.0, 1.0)
LIGHT = (0.86, 0.86, 0.88, 1.0)
HPC = (0.52, 0.88, 0.42, 1.0)  # 체력 막대(평소 연두, 깎인 몫 흰색과 갈리게), 25% 아래는 RED 깜빡임
BOSS_RED = (0.78, 0.10, 0.12, 1.0)  # 보스 체력 막대(짙은 빨강)
IMMUNE = (0.55, 0.55, 0.58, 1.0)  # 보스 안 먹힘 적중(회색)
BOSS_W = 200  # 보스 막대 폭(GUI = 텍셀)
TRUCKC = (0.90, 0.80, 0.46, 1.0)  # 원정 트럭 체력 막대(모래빛: 사람 체력 연두, 보스 빨강과 갈리게), 25% 아래는 RED 깜빡임

# 칸: 이름, 기준 x 비율, 기준 y 비율, 오프셋 x, 오프셋 y(글자 기준선 자리), 모양 색, 방식
# 427x240 과 480x270 GUI 에서 겹치지 않게(--mock 으로 확인). 바닐라가 쓰는 곳: 핫바 아이템(아래 가운데), 자막(오른쪽 아래, 아래 30 위),
# 채팅(왼쪽 아래, 아래 40 위), 조준점. 이 칸 차례가 그리는 차례(덮개가 맨 밑)
SLOTS = [
    # 화면 덮개(맨 밑): 줄마다 칸 하나, 조각 4개(Java 가 x = -240 + 121 c 에 놓음)
    *[(f"CV{r}", 0.5, 0.5, 0, -COVER_H // 2 + COVER_TH * (r + 1), None, FIT) for r in range(COVER_ROWS)],
    # 맨 위 가운데: 웨이브 번호(크게), 남은 게이브 막대(줄어듦), 준비, 쉬는 시간 남은 시간(셰이더 시계), 남은 수
    ("WV_BAR", 0.5, 0.0, -60, 26, RED, 0),
    ("WV_BAR_TIME", 0.5, 0.0, -60, 26, LIGHT, DRAIN),
    ("WV_NUM", 0.5, 0.0, 0, 17, None, 0),
    ("WV_LEFT", 0.5, 0.0, 64, 27, None, 0),
    # 알림 띠(웨이브 시작, 끝, 사건, 방향 알림, 재난문자 꼴)
    ("NT", 0.5, 0.0, 0, 44, None, 0),
    # 오른쪽 위: 재화(동전 + 숫자), 얻은 +n, 처음 보는 종(이름 + 아이콘) 셋
    ("MONEY", 1.0, 0.0, -6, 17, None, 0),
    ("MONEY_GAIN", 1.0, 0.0, -6, 28, None, 0),
    *[(f"FS{i}", 1.0, 0.0, -6, 62 + 16 * i, None, 0) for i in range(3)],
    # 왼쪽 위: 동료 8줄(이름, 갇힘은 먹은 종 아이콘), 쓰러진 사람 출혈 막대(셰이더 시계)
    *[(f"TM{i}", 0.0, 0.0, 4, 12 + 10 * i, None, 0) for i in range(8)],
    *[(f"TMB{i}", 0.0, 0.0, 4, 12 + 10 * i, RED, DRAIN) for i in range(8)],
    # 왼쪽 아래: 체력(밑 막대 = 깎인 몫 흰색 + 빈 몫, 위 막대 = 지금 체력)
    ("HP_HURT", 0.0, 1.0, 6, -10, WHITE, 0),
    ("HP", 0.0, 1.0, 6, -10, HPC, NOTRACK),
    ("HP_LOW", 0.0, 1.0, 6, -10, RED, THROB | NOTRACK),
    ("HP_NUM", 0.0, 1.0, 6 + HP_W + 4, -9, None, 0),
    # 오른쪽 아래(아래 30 GUI 안, 자막 아래): [소모품 + 수] [+강화] [총 실루엣] [탄창 크게] [예비 작게]
    ("W_GUN", 1.0, 1.0, -66, -8, None, 0),
    ("W_UL", 1.0, 1.0, -66, -5, None, 0),
    ("W_RLD", 1.0, 1.0, -66, -2, LIGHT, 0),
    ("W_MAG", 1.0, 1.0, -28, -8, None, 0),
    ("W_RES", 1.0, 1.0, -24, -8, None, 0),
    ("W_INF", 1.0, 1.0, -24, -8, LIGHT, 0),
    # 아래 가운데(핫바 위): 상점 표시, 증강 대기 수
    ("SHOP", 0.5, 1.0, 0, -28, None, 0),
    # 가운데: 방향 화살표(타원 둘레), 출혈, 일으키기, 탈출 고리, 적중, 표시, 게이지, 한 줄 도움말, 죽은 이유 카드(두 줄)
    ("PTR_WAVE", 0.5, 0.5, 0, 0, ORANGE, POLAR | PULSE),
    ("PTR_POINT", 0.5, 0.5, 0, 0, GOLD, POLAR),
    ("PTR_GRAB", 0.5, 0.5, 0, 0, PURPLE, POLAR),
    ("PTR_DOWN", 0.5, 0.5, 0, 0, RED, POLAR | PULSE),
    ("C_BLEED", 0.5, 0.5, 0, 0, RED, DRAIN),
    ("C_BLEED_HOLD", 0.5, 0.5, 0, 0, RED, 0),
    ("C_REVIVE", 0.5, 0.5, 0, 0, GREEN, 0),
    ("C_ESCAPE", 0.5, 0.5, 0, 0, PURPLE, PULSE),
    ("C_HIT", 0.5, 0.5, 0, 0, WHITE, HIDE),
    ("C_KILL", 0.5, 0.5, 0, 0, RED, HIDE),
    ("MARK", 0.5, 0.5, 15, -6, None, 0),
    ("METER", 0.5, 0.5, -26, 30, ORANGE, 0),
    ("HINT", 0.5, 0.5, 0, 42, None, 0),
    ("CARD", 0.5, 0.5, 0, 54, None, 0),
    ("CARD2", 0.5, 0.5, 0, 65, None, 0),
    # 보스(docs/모듈/보스.md 7절, 2026-11-12 boss_infra). 앞 칸 번호가 그대로이게 맨 뒤에 붙인다.
    # 보스 틀: 웨이브 숫자(WV_NUM) 아래 200 막대(깎인 흰 몫 위에 빨간 지금 몫, 문턱 눈금), 오른쪽 단계 점, 그 아래 이름. 틀이 있는 동안 알림 띠는 NT_LOW
    ("BS_HURT", 0.5, 0.0, -100, 27, WHITE, 0),
    ("BS_BAR", 0.5, 0.0, -100, 27, BOSS_RED, NOTRACK),
    ("BS_MARK", 0.5, 0.0, -100, 27, None, 0),
    ("BS_PH", 0.5, 0.0, 104, 28, None, 0),
    ("BS_NAME", 0.5, 0.0, 0, 39, None, 0),
    ("NT_LOW", 0.5, 0.0, 0, 58, None, 0),
    # 등장 큰 제목(2배 글귀 t_*), 부제, 엔딩 숫자(화면 3분의 1 높이)
    # (제목 윗선이 방향 화살촉 타원 꼭대기(가운데 위 0.25 H)에 닿지 않게 5 내림. 엔딩 숫자 아랫선은 조준점 1 위)
    ("BS_TITLE", 0.5, 0.33, 0, 5, None, 0),
    ("BS_SUB", 0.5, 0.33, 0, 20, None, 0),
    ("END_NUM", 0.5, 0.33, 0, 36, None, 0),
    # 보스 약점 적중(금색), 안 먹힘(회색): 적중 표시 모양 그대로 색만
    ("C_WEAK", 0.5, 0.5, 0, 0, GOLD, HIDE),
    ("C_IMMUNE", 0.5, 0.5, 0, 0, IMMUNE, HIDE),
    # 원정(docs/모듈/원정.md 12절, 2026-11-12 trip_core). 트럭 체력(핫바 위 가운데, 상점 표시 자리: 흰 깎인 몫 위에 지금 몫 84 x 6, 왼쪽에 트럭 아이콘),
    # 트럭이 맞을 때 트럭 쪽 화살촉, 스테이지 표시(웨이브 숫자 오른쪽), 도착한 스테이지 이름(보스 부제 자리, 같이 안 뜸), 길 투표 집계(알림 띠 아래),
    # 죽은 사람 화면의 모금 막대와 글, 검은 화면 한가운데 글 한 줄(맵 전환, 엔딩)
    ("TRK_HURT", 0.5, 1.0, -36, -27, WHITE, 0),
    ("TRK_BAR", 0.5, 1.0, -36, -27, TRUCKC, NOTRACK),
    ("TRK_LOW", 0.5, 1.0, -36, -27, RED, THROB | NOTRACK),
    ("TRK_ICON", 0.5, 1.0, -56, -26, None, 0),
    ("PTR_TRUCK", 0.5, 0.5, 0, 0, WHITE, POLAR | PULSE),
    ("ST_TAG", 0.5, 0.0, 78, 17, None, 0),
    ("ST_NAME", 0.5, 0.33, 0, 20, None, 0),
    ("TALLY", 0.5, 0.0, 0, 72, None, 0),
    ("RV_BAR", 0.5, 0.5, -20, 30, GOLD, 0),
    ("RV_TXT", 0.5, 0.5, 0, 42, None, 0),
    ("STORY", 0.5, 0.5, 0, 4, None, 0),
    # 원정 라운드 목표(trip_rounds, 원정.md 5.0절): 목표가 있는 라운드는 맨 위 웨이브 막대 자리에 금빛 차오르는 막대(GOAL_BAR) 또는 줄어드는 시간 막대(GOAL_TIME),
    # 막대 왼쪽에 목표 아이콘, 오른쪽(WV_LEFT 자리)에 남은 초나 3/6, 그 오른쪽에 떨어진 짐 체력(GOAL_CARGO). 라운드 이름 띠는 알림 띠(NT)와 그 아래(NT_LOW) 칸을 같이 씀
    ("GOAL_IC", 0.5, 0.0, -64, 31, None, 0),
    ("GOAL_BAR", 0.5, 0.0, -60, 26, GOLD, 0),
    ("GOAL_TIME", 0.5, 0.0, -60, 26, LIGHT, 0),
    ("GOAL_CARGO", 0.5, 0.0, 92, 28, RED, 0),
    # 옅은 화면 테(PState.veil, sp_engine VEIL): 덮개와 따로, IMG_DIM 으로 옅게(맨 뒤라 다른 칸 위에 그려짐: 테 그림은 가장자리만)
    *[(f"VL{r}", 0.5, 0.5, 0, -COVER_H // 2 + COVER_TH * (r + 1), None, FIT) for r in range(COVER_ROWS)],
]
assert len(SLOTS) < 128
assert len({s[0] for s in SLOTS}) == len(SLOTS)

# 그림, 글자 팔레트(G): 이름, RGBA(텍스처에 곱함), 효과(1 = 1초에 한 번 숨 쉬듯, 2 = 0.5초마다 잠깐 비침, 3 = 2 의 반대)
PALETTE = [
    ("WHITE", (1.0, 1.0, 1.0, 1.0), 0),
    ("SHADOW", (0.10, 0.10, 0.11, 0.88), 0),
    ("GRAY", (0.72, 0.72, 0.74, 1.0), 0),
    ("DIM", (0.46, 0.46, 0.49, 0.92), 0),
    ("GOLD", (1.0, 0.84, 0.32, 1.0), 0),
    ("RED", (1.0, 0.36, 0.32, 1.0), 0),
    ("RED_BLINK", (1.0, 0.30, 0.26, 1.0), 1),
    ("GREEN", (0.48, 1.0, 0.45, 1.0), 0),
    ("GHOST", (1.0, 1.0, 1.0, 0.24), 0),
    ("IMG_DIM", (1.0, 1.0, 1.0, 0.5), 0),
    ("PANEL", (0.0, 0.0, 0.0, 0.46), 0),
    ("STRIKE", (0.95, 0.20, 0.18, 0.95), 0),
    ("PURPLE", (0.80, 0.56, 1.0, 1.0), 0),
    ("PURPLE_BLINK", (0.80, 0.56, 1.0, 1.0), 1),
    ("ORANGE", (1.0, 0.62, 0.22, 1.0), 0),
    ("COIN", (1.0, 0.88, 0.46, 1.0), 0),
    ("R_COMMON", (0.71, 0.71, 0.71, 1.0), 0),
    ("R_RARE", (0.31, 0.64, 1.0, 1.0), 0),
    ("R_EPIC", (0.72, 0.42, 1.0, 1.0), 0),
    ("R_LEGENDARY", (1.0, 0.77, 0.24, 1.0), 0),
    ("ALERT", (0.82, 0.10, 0.08, 0.94), 0),
    ("PANEL_ALERT", (0.10, 0.02, 0.02, 0.74), 0),
    ("WHITE_BLINK", (1.0, 1.0, 1.0, 1.0), 1),
    ("FLASH", (1.0, 1.0, 1.0, 1.0), 2),
    ("ANTI_FLASH", (1.0, 1.0, 1.0, 1.0), 3),
    # 글자 그림자: 효과 있는 팔레트의 글자는 같은 효과의 그림자를 쓴다(HudSpec.PAL_SHADOW, 안 그러면 숨은 글자의 그림자가 비침)
    ("SHADOW_PULSE", (0.10, 0.10, 0.11, 0.88), 1),
    ("SHADOW_FLASH", (0.10, 0.10, 0.11, 0.88), 2),
    ("SHADOW_ANTI", (0.10, 0.10, 0.11, 0.88), 3),
]
_SHADOW_BY_FX = {0: "SHADOW", 1: "SHADOW_PULSE", 2: "SHADOW_FLASH", 3: "SHADOW_ANTI"}
PAL_SHADOW = [[p[0] for p in PALETTE].index(_SHADOW_BY_FX[p[2]]) for p in PALETTE]

GUNS = ["pistol", "smg", "shotgun", "rifle", "sniper",  # Guns.Kind 순서(PISTOL, SMG, SHOTGUN, RIFLE, SNIPER, 그 뒤 2026-11-12 새 무기)
        "flamethrower", "launcher", "rocket", "minigun", "lmg", "revolver", "burst", "dmr", "railgun", "tesla", "freeze", "bow",
        # 2026-11-12 wpn_max 새 종류 아홉(AUTOSHOT .. LASER 순서, 총괄 요청으로 boss_infra 가 넣음)
        "autoshotgun", "doublebarrel", "boltaction", "machinepistol", "dualpistol", "antimateriel", "silenced", "musket", "laser"]
# 총 실루엣: 크기 이름 → (최대 폭, 최대 높이 GUI, 위로 올림(ascent - 높이)). l = 오른쪽 아래 무기 칸, m = 알림
GUN_SIZES = {"l": (60, 22, 0), "m": (30, 10, -1)}  # 이름은 소문자(자원 위치 규칙)
SUPPLIES = ["flare", "molotov", "rattle", "cleanse", "bandage"]  # kr.gave.gun.Supplies.KINDS(수류탄 빼고), 그림은 상점 아이콘
# 종 아이콘 크기: 이름 → (높이, ascent GUI)
ICON_SIZES = {"l": (20, 8), "m": (14, 11), "s": (10, 8)}


# ---------------------------------------------------------------- 출력 모음

class Out:
    def __init__(self):
        self.files = {}

    def put(self, rel, data):
        self.files[rel] = data if isinstance(data, bytes) else data.encode("utf-8")

    def png(self, rel, img):
        b = io.BytesIO()
        img.save(b, "PNG", optimize=True)
        self.put(rel, b.getvalue())


OUT = Out()
PROVIDERS = []  # gave:hud 글꼴 제공자(펜 이동 다음에)
IMAGES = []  # (이름, 글자, 전진 폭, 잉크 폭 GUI, 높이, ascent)
IMAGE_FILE = {}  # 이름 → 그림 파일(TEX 안 상대 이름, 같은 그림을 여러 크기로 쓰는 아이콘은 하나)
SHAPES = {}  # 이름 → (첫 글자, 전진 폭, 높이, ascent)
COVERS = []  # 덮개 키
ICONS = []  # 아이콘이 있는 종 키(g01 ..)
# 그림 글자 자리: U+E100..E3FF 다음 U+E600..F8FF(E400..E5FF 는 모양 묶음, E000..E01F 는 펜 이동)
IMAGE_RANGES = [(0xE100, 0xE400), (0xE600, 0xF900)]
_next_image = [0, IMAGE_RANGES[0][0]]


def ink_width(img):
    a = np.asarray(img.getchannel("A"))
    cols = np.nonzero(a.max(axis=0))[0]
    return int(cols[-1]) + 1 if len(cols) else 0


def advance(img, height):
    """26.3 BitmapProvider: (int)(0.5 + 잉크 폭 x 높이/칸 높이) + 1"""
    return int(0.5 + ink_width(img) * height / img.height) + 1


def next_char(name):
    r, c = _next_image
    if c >= IMAGE_RANGES[r][1]:
        r += 1
        if r >= len(IMAGE_RANGES):
            raise SystemExit(f"그림 글리프 글자 자리가 모자람({name}, {len(IMAGES)}장)")
        c = IMAGE_RANGES[r][0]
    _next_image[0], _next_image[1] = r, c + 1
    return chr(c)


def add_image(name, img, height, ascent, file=None):
    """그림 글리프 하나(글꼴 gave:hud). height, ascent 는 GUI 정수, ascent <= height. file = 이미 쓴 그림 파일 이름(같은 그림, 다른 크기)"""
    assert 0 <= ascent <= height, (name, height, ascent)
    assert re.fullmatch(r"[a-z0-9_.-]+", name), f"자원 위치는 소문자만: {name}"
    assert img.width <= 240 and img.height <= 240, (name, img.size)
    assert name not in IMAGE_FILE, f"그림 글리프 이름이 겹침: {name}"
    ch = next_char(name)
    fname = file or name
    if file is None:
        OUT.png(f"{TEX}/{name}.png", img)
    IMAGE_FILE[name] = fname
    PROVIDERS.append({"type": "bitmap", "file": f"gave:font/hud/{fname}.png", "height": height, "ascent": ascent, "chars": [ch]})
    IMAGES.append((name, ch, advance(img, height), round(ink_width(img) * height / img.height), height, ascent))
    return ch


def add_sheet(name, cells, cols, height, ascent, first):
    """같은 크기 글리프 여럿을 한 그림에(글자 first, first+1, ...). 셰이더 모양 글리프용"""
    cw, chh = cells[0].size
    assert cw <= 240 and chh <= 240
    assert re.fullmatch(r"[a-z0-9_.-]+", name) and 0 <= ascent <= height, name
    assert 0xE400 <= first and first + len(cells) <= 0xE600, f"모양 묶음 {name} 글자 자리는 U+E400..E5FF"
    for other, (f2, *_r) in SHAPES.items():
        n2 = 64 if other.startswith(("bar_", "ring_")) else 1
        assert first + len(cells) <= f2 or f2 + n2 <= first, f"모양 묶음 {name} 과 {other} 글자 자리가 겹침"
    rows = -(-len(cells) // cols)
    sheet = Image.new("RGBA", (cw * cols, chh * rows), (0, 0, 0, 0))
    chars = []
    for i, c in enumerate(cells):
        sheet.paste(c, ((i % cols) * cw, (i // cols) * chh))
    for r in range(rows):
        row = "".join(chr(first + r * cols + k) if r * cols + k < len(cells) else "\u0000" for k in range(cols))
        chars.append(row)
    OUT.png(f"{TEX}/{name}.png", sheet)
    PROVIDERS.append({"type": "bitmap", "file": f"gave:font/hud/{name}.png", "height": height, "ascent": ascent, "chars": chars})
    SHAPES[name] = (first, advance(cells[0], height), height, ascent)


# ---------------------------------------------------------------- 그림 도우미

def bleed(arr, iters=6):
    """곱하지 않은 RGBA(0..1 배열)에서 투명 텍셀 색을 이웃 색으로 메움(쌍선형이 가장자리를 검게 물들이지 않게) → PIL"""
    rgb, a = arr[..., :3].astype(np.float32).copy(), arr[..., 3].astype(np.float32)
    known = a > 0.004
    for _ in range(iters):
        if known.all() or not known.any():
            break
        acc = np.zeros_like(rgb)
        n = np.zeros(a.shape, np.float32)
        for dy, dx in ((0, 1), (0, -1), (1, 0), (-1, 0)):
            k = np.roll(known, (dy, dx), (0, 1))
            acc += np.roll(rgb, (dy, dx), (0, 1)) * k[..., None]
            n += k
        new = (~known) & (n > 0)
        rgb[new] = acc[new] / n[new][:, None]
        known = known | new
    return Image.fromarray((np.dstack([rgb, a]) * 255 + 0.5).clip(0, 255).astype(np.uint8), "RGBA")


def resize_pm(img, size):
    """알파를 곱한 채로 줄이고 늘림(가장자리 검은 테 없음) + 투명 색 메움"""
    a = np.asarray(img.convert("RGBA"), np.float32) / 255.0
    al = a[..., 3]
    chans = [a[..., i] * al for i in range(3)] + [al]
    res = [np.asarray(Image.fromarray(np.ascontiguousarray(c), "F").resize(size, Image.LANCZOS)) for c in chans]
    A = np.clip(res[3], 0, 1)
    rgb = np.clip(np.stack(res[:3], -1) / np.maximum(A, 1e-4)[..., None], 0, 1)
    return bleed(np.dstack([rgb, A]))


def drawn(w, h, fn, ss=4):
    """w x h 텍셀 그림을 ss 배로 그려 줄임(부드러운 가장자리). fn(draw, s) 에서 s = 텍셀 하나의 큰 그림 화소"""
    big = Image.new("RGBA", (w * ss, h * ss), (255, 255, 255, 0))
    fn(ImageDraw.Draw(big), ss)
    return resize_pm(big, (w, h))


def glyph_img(img, h_gui, pad_bottom=0, w_gui=None):
    """그림을 RES 텍셀/GUI 로 맞추고 아래에 투명 pad_bottom GUI 를 붙임(기준선 위로 띄우기: ascent <= height 규칙)"""
    w_gui = w_gui or max(1, round(img.width * h_gui / img.height))
    im = resize_pm(img, (w_gui * RES, h_gui * RES)) if img.size != (w_gui * RES, h_gui * RES) else img
    if pad_bottom:
        out = Image.new("RGBA", (im.width, im.height + pad_bottom * RES), (0, 0, 0, 0))
        out.paste(im, (0, 0))
        im = out
    return im


# ---------------------------------------------------------------- 글자(바닐라 ascii.png 를 1배, 2배로)

def jar_png(path):
    with zipfile.ZipFile(JAR) as z:
        return Image.open(io.BytesIO(z.read(path))).convert("RGBA")


def text_fonts():
    with zipfile.ZipFile(JAR) as z:
        inc = json.loads(z.read("assets/minecraft/font/include/default.json"))
    prov = next(p for p in inc["providers"] if p.get("file") == "minecraft:font/ascii.png")
    grid = prov["chars"]
    img = jar_png("assets/minecraft/textures/font/ascii.png")
    cw, chh = img.width // len(grid[0]), img.height // len(grid)
    keep = {chr(c) for c in range(0x21, 0x7F)}
    rows = ["".join(c if c in keep else "\u0000" for c in row) for row in grid]
    tables = {}
    for name, k in (("hud_s", 1), ("hud_m", 2)):
        adv = {" ": 4 * k}
        for r, row in enumerate(grid):
            for c, chv in enumerate(row):
                if chv not in keep:
                    continue
                cell = img.crop((c * cw, r * chh, (c + 1) * cw, (r + 1) * chh))
                adv[chv] = int(0.5 + ink_width(cell) * (8 * k) / chh) + 1
        OUT.put(f"assets/gave/font/{name}.json", json.dumps({"providers": [
            {"type": "space", "advances": {" ": 4 * k}},
            {"type": "bitmap", "file": "minecraft:font/ascii.png", "height": 8 * k, "ascent": 7 * k, "chars": rows}]}, indent=1))
        tables[name] = adv
    return tables


# ---------------------------------------------------------------- 총 실루엣(모드 모델을 옆에서)

def tex_img(tid):
    ns, _, p = tid.partition(":")
    f = PL / f"assets/{ns}/textures/{p}.png"
    if f.exists():
        return Image.open(f).convert("RGBA")
    return jar_png(f"assets/{ns}/textures/{p}.png")


def load_model(key):
    f = PL / f"assets/gave/models/item/{key}.json"
    if not f.exists():
        return None
    m = json.loads(f.read_text(encoding="utf-8"))
    tex = {"#" + k: V.tex_array(tex_img(v)) for k, v in m["textures"].items() if k != "particle"}
    return m["elements"], tex


def render(els, tex, view, w, h, ss=4, frame=None):
    """직교 그림(텍셀 w x h), ss 배로 그려 상자 평균 → (rgb, 알파). frame = 맞출 테두리 요소(없으면 els)"""
    d, up = V.VIEWS[view]
    cv = V.Canvas((w * ss, h * ss), d, up, bg=(0, 0, 0, 0))
    cv.fit(V.all_points(frame or els), margin=0.0)
    V.draw_model(cv, els, tex)
    big = np.clip(cv.img, 0, 1).reshape(h, ss, w, ss, 4)
    a = big[..., 3].mean(axis=(1, 3))
    rgb = (big[..., :3] * big[..., 3:4]).sum(axis=(1, 3)) / np.maximum(big[..., 3].sum(axis=(1, 3))[..., None], 1e-6)
    return rgb, a


def span(els, view):
    d, up = V.VIEWS[view]
    cv = V.Canvas((8, 8), d, up)
    q = V.all_points(els) @ cv.V.T
    s = q.max(0) - q.min(0)
    return s[0], s[1]


def dilate(a, r):
    out = a.copy()
    h, w = a.shape
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            if dx * dx + dy * dy > r * r + r:
                continue
            sh = np.zeros_like(a)
            sh[max(0, dy):h + min(0, dy), max(0, dx):w + min(0, dx)] = a[max(0, -dy):h - max(0, dy), max(0, -dx):w - max(0, dx)]
            out = np.maximum(out, sh)
    return out


def silhouette(rgb, a, edge):
    """밝은 실루엣(모델 밝기로 옅은 음영) + 어두운 테두리. edge = 테두리 텍셀"""
    lum = rgb @ np.array([0.299, 0.587, 0.114])
    m = a > 0.5
    lo, hi = (np.percentile(lum[m], [6, 94]) if m.any() else (0.0, 1.0))
    t = np.clip((lum - lo) / max(hi - lo, 1e-3), 0, 1)
    g = 0.70 + 0.30 * t
    col = np.stack([g, g, g * 0.985], -1)
    ring = np.clip(dilate(a, edge) - a, 0, 1) * 0.72
    out_a = a + ring * (1 - a)
    out_rgb = col * (a / np.maximum(out_a, 1e-6))[..., None]
    return Image.fromarray((np.dstack([out_rgb, out_a]) * 255 + 0.5).clip(0, 255).astype(np.uint8), "RGBA")


def gun_icon(key, max_w, max_h, edge=2):
    """모델 옆모습 실루엣. _nomag(재장전 연출)은 탄창 있는 총과 같은 틀에 그려 총몸이 그 자리에 있게"""
    m = load_model(key)
    if m is None:
        return None
    els, tex = m
    base = load_model(key[:-len("_nomag")]) if key.endswith("_nomag") else None
    frame = base[0] if base else None
    sx, sy = span(frame or els, "side")
    s = min((max_w * RES - 2 * edge) / sx, (max_h * RES - 2 * edge) / sy)
    w = max(1, min(math.ceil(sx * s), max_w * RES - 2 * edge))
    h = max(1, min(math.ceil(sy * s), max_h * RES - 2 * edge))
    rgb, a = render(els, tex, "side", w, h, frame=frame)
    pad = np.zeros((h + 2 * edge, w + 2 * edge, 3)), np.zeros((h + 2 * edge, w + 2 * edge))
    pad[0][edge:edge + h, edge:edge + w] = rgb
    pad[1][edge:edge + h, edge:edge + w] = a
    im = silhouette(pad[0], pad[1], edge)
    hg = math.ceil(im.height / RES)
    canvas = Image.new("RGBA", (im.width, hg * RES), (0, 0, 0, 0))
    canvas.paste(im, (0, (hg * RES - im.height + 1) // 2))
    return canvas, hg


def truck_icon(max_w=18, max_h=9, edge=2):
    """원정 트럭 아이콘: 모드 모델(The Car Engine 상자 화물차)의 옆모습 실루엣(tools/trip/truck.py side_image). 받은 모델이 없으면 None"""
    f = ROOT / "tools/trip/truck.py"
    if not f.is_file():
        return None
    try:
        import importlib.util
        spec = importlib.util.spec_from_file_location("trip_truck_icon", f)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        src = mod.side_image(max_w * RES - 2 * edge, max_h * RES - 2 * edge)
    except (OSError, KeyError, ValueError, AttributeError) as e:
        print(f"트럭 아이콘 못 만듦: {e}")
        return None
    arr = np.asarray(src.convert("RGBA")).astype(np.float64) / 255.0
    h, w = arr.shape[:2]
    pad = np.zeros((h + 2 * edge, w + 2 * edge, 3)), np.zeros((h + 2 * edge, w + 2 * edge))
    pad[0][edge:edge + h, edge:edge + w] = arr[..., :3]
    pad[1][edge:edge + h, edge:edge + w] = arr[..., 3]
    im = silhouette(pad[0], pad[1], edge)
    hg = math.ceil(im.height / RES)
    canvas = Image.new("RGBA", (im.width, hg * RES), (0, 0, 0, 0))
    canvas.paste(im, (0, (hg * RES - im.height + 1) // 2))
    return canvas, hg


def nade_icon(size=12, edge=2):
    m = load_model("grenade")
    if m is None:
        return None
    els, tex = m
    sx, sy = span(els, "side")
    s = (size * RES - 2 * edge) / max(sx, sy)
    w, h = max(1, math.ceil(sx * s)), max(1, math.ceil(sy * s))
    rgb, a = render(els, tex, "side", w, h)
    full = size * RES
    prgb, pa = np.zeros((full, full, 3)), np.zeros((full, full))
    ox, oy = (full - w) // 2, full - edge - h
    prgb[oy:oy + h, ox:ox + w] = rgb
    pa[oy:oy + h, ox:ox + w] = a
    return silhouette(prgb, pa, edge)


# ---------------------------------------------------------------- 한국어 글귀(Galmuri11 Bold 로 미리 그린 그림 글리프)

PHRASES = []  # (키, 글, [조각 그림 이름], [조각 x GUI], 전체 폭 GUI)


def phrase_sources():
    """글귀 모음 {키: (글, 출처)}: bestiary 종 이름(name_g01 ..) 다음 data/phrases/*.json(파일 이름 순). 글귀 파일이 종 이름 키를
    다시 적으면 그 글을 쓴다(알림 한 줄). 글귀 파일끼리 같은 키는 멈춘다"""
    out = {}
    if BESTIARY.is_file():
        for sp in json.loads(BESTIARY.read_text(encoding="utf-8")).get("species", []):
            if sp.get("id") and sp.get("name"):
                out[f"name_{sp['id'].lower()}"] = (sp["name"], "data/bestiary.json")
                # 보스(B1..B5)는 등장 큰 제목 t_b1 .. 도 도감 이름으로(2배 글귀). 보스 글귀 파일(data/phrases/Bx.json)이 t_bx 를 적으면 그 글을 쓴다
                if re.fullmatch(r"B\d+", sp["id"]):
                    out[f"t_{sp['id'].lower()}"] = (sp["name"], "data/bestiary.json")
    owner = {}
    for f in sorted(PHRASE_DIR.glob("*.json")) if PHRASE_DIR.is_dir() else []:
        data = json.loads(f.read_text(encoding="utf-8"))
        if not isinstance(data, dict):
            raise SystemExit(f"{f.name}: 글귀 파일은 {{키: 글}} 이어야 한다")
        for k, text in data.items():
            if k in owner:
                raise SystemExit(f"글귀 키 {k} 가 {owner[k]} 와 {f.name} 에 둘 다 있다")
            if k in out:
                print(f"글귀 {k}: {f.name} 의 글이 {out[k][1]} 의 글을 덮음")
            owner[k] = f.name
            out[k] = (str(text), f"data/phrases/{f.name}")
    return out


def phrase_name(key):
    """글귀 키 → 그림 이름(소문자 자원 위치 규칙 [a-z0-9_.-], '.' 은 조각 번호에 씀)"""
    n = key.lower()
    if not re.fullmatch(r"[a-z0-9_-]+", n):
        n = re.sub(r"[^a-z0-9_-]", "_", n)
        print(f"글귀 키 {key!r} 는 소문자, 숫자, _, - 만 쓸 수 있어 그림 이름을 {n} 로 바꿈(Java 는 PHRASES 의 키로 부를 것)")
    return n


# Galmuri11 에 없는 글자(한자 故 같은 것, 2026-11-12 opening): 그 글자만 art/fonts/fallback/u<16진 코드>.png 그림으로 끼워 그린다(글자는 그대로).
# 그림은 tools/hud_fallback.py 가 Source Han Sans K Medium(Adobe, OFL 1.1) 11 크기 단색으로 굽는다. 한자에 그림이 없으면 멈추고 굽는 법을 알린다
FALLBACK_DIR = ROOT / "art/fonts/fallback"
_FALLBACK = {}


def ko_fallback(ch):
    """그 글자의 대체 그림(흰 잉크 마스크 L, 잉크 상자) 또는 None(Galmuri 로 그림)"""
    if ch not in _FALLBACK:
        p = FALLBACK_DIR / f"u{ord(ch):04x}.png"
        img = None
        if p.is_file():
            a = np.asarray(Image.open(p).convert("RGBA").getchannel("A")) > 127
            if a.shape[0] > 11:
                raise SystemExit(f"{p.name}: 잉크 {a.shape[0]} 줄 > 11(Galmuri11 한글 잉크 줄)")
            img = Image.fromarray((a * 255).astype(np.uint8), "L")
        elif 0x3400 <= ord(ch) <= 0x9FFF or 0xF900 <= ord(ch) <= 0xFAFF or 0x20000 <= ord(ch) <= 0x2FFFF:
            raise SystemExit(f"글귀의 한자 {ch!r}(U+{ord(ch):04X}): Galmuri11 에 없다. python -X utf8 tools/hud_fallback.py {ch} 로 대체 그림을 구울 것")
        _FALLBACK[ch] = img
    return _FALLBACK[ch]


def ko_render(text, font):
    """글 한 줄 → 흰 글자 + 오른쪽 아래 1 화소 그림자(RGBA, 높이 KO_ROWS 또는 KO_ROWS_DESC 텍셀). 화소 글꼴이라 계조 없이 그림.
    왼쪽은 첫 잉크 열에서 자른다. 빈 글이면 None. Galmuri11 에 없는 글자는 ko_fallback 그림(한글 잉크 줄 KO_TOP 부터, 폭 + 1 칸 나아감)"""
    segs = []  # 글 조각(Galmuri) 또는 대체 그림
    for ch in text:
        fb = ko_fallback(ch)
        if fb is not None:
            segs.append(fb)
        elif segs and isinstance(segs[-1], str):
            segs[-1] += ch
        else:
            segs.append(ch)
    w = sum(int(math.ceil(font.getlength(s))) if isinstance(s, str) else s.width + 1 for s in segs) + 8
    m = Image.new("L", (w, KO_PX * 2), 0)
    d = ImageDraw.Draw(m)
    d.fontmode = "1"
    x = 0
    for s in segs:
        if isinstance(s, str):
            d.text((x, 0), s, font=font, fill=255)
            x += int(round(font.getlength(s)))
        else:
            m.paste(255, (x, KO_TOP + (11 - s.height) // 2), s)
            x += s.width + 1
    ink = np.asarray(m) > 127
    cols = np.nonzero(ink.any(0))[0]
    rows = np.nonzero(ink.any(1))[0]
    if not len(cols):
        return None
    if rows[0] < KO_TOP:
        raise SystemExit(f"글귀 {text!r}: 줄 위로 넘친 잉크(그리기 원점 기준 {rows[0]} 줄)")
    h = KO_ROWS if rows[-1] + 2 - KO_TOP <= KO_ROWS else KO_ROWS_DESC
    if rows[-1] + 2 - KO_TOP > h:
        raise SystemExit(f"글귀 {text!r}: 기준선 아래로 너무 긴 잉크")
    ink = ink[KO_TOP:KO_TOP + h, cols[0]:cols[-1] + 1]
    ink = np.pad(ink, ((0, max(0, h - ink.shape[0])), (0, 1)))[:h]
    shadow = np.zeros_like(ink)
    shadow[1:, 1:] = ink[:-1, :-1]
    px = np.zeros(ink.shape + (4,), np.uint8)
    px[shadow & ~ink] = KO_SHADOW
    px[ink] = (255, 255, 255, 255)
    return Image.fromarray(px, "RGBA")


def ko_cuts(img):
    """240 텍셀보다 넓은 그림을 나눌 자리(텍셀, KO_STEP 배수). 되도록 잉크와 그림자가 없는 열 사이에서 자른다"""
    a = np.asarray(img.getchannel("A")) > 0
    empty = ~a.any(0)
    cuts, start, w = [0], 0, img.width
    while w - start > KO_MAX:
        c = start + KO_MAX
        for x in range(start + KO_MAX, start + KO_MAX // 2, -KO_STEP):
            if empty[x - 1] and empty[x]:
                c = x
                break
        cuts.append(c)
        start = c
    return cuts


def phrases():
    """글귀를 모두 그림 글리프로(add_image). 그림 이름 = 글귀 키, 넓으면 키.2, 키.3 ... 조각. (만든 수, 빠진 키)"""
    src = phrase_sources()
    if not src:
        return 0, []
    if not FONT_KO.is_file():
        raise SystemExit(f"한국어 글귀 글꼴 {FONT_KO} 가 없다(jeomseonmyeon/art/fonts 에서 복사)")
    font = ImageFont.truetype(str(FONT_KO), KO_PX)
    num, den = KO_SCALE
    skipped = []
    for key, (text, origin) in src.items():
        if chr(0xB7) in text:
            raise SystemExit(f"글귀 {key}({origin}): 가운데점(U+00B7)은 쓰지 않는다. 쉼표나 괄호로")
        line = " ".join(text.split())
        img = ko_render(line, font) if line else None
        if img is None:
            skipped.append(key)
            continue
        name = phrase_name(key)
        # 키가 t_ 로 시작하면 2배(텍셀 1 = 3/2 GUI, 높이 18 GUI): 보스 등장 큰 제목, 엔딩 카드(2026-11-12 boss_infra)
        big = key.startswith("t_")
        kn, kd = (num * 2, den) if big else (num, den)
        h_gui = img.height * kn // kd
        assert img.height * kn % kd == 0, (key, img.height)
        parts, xs = [], []
        cuts = ko_cuts(img) + [img.width]
        for i in range(len(cuts) - 1):
            part = img.crop((cuts[i], 0, cuts[i + 1], img.height))
            pname = name if i == 0 else f"{name}.{i + 1}"
            add_image(pname, part, h_gui, KO_ASC * (2 if big else 1))
            parts.append(pname)
            xs.append(cuts[i] * kn // kd)
        PHRASES.append((key, line, parts, xs, round(img.width * kn / kd)))
    return len(PHRASES), skipped


# ---------------------------------------------------------------- 셰이더 모양 글리프

def bar_cell(w, h, code, pad=0, shade_from=None):
    """막대 텍셀: R = 열 가운데의 막대 안 자리, G = 역할(0 테두리, 1 밝은 안쪽, 2 어두운 안쪽), B = 코드. 아래 pad 줄은 투명"""
    a = np.zeros((h + pad, w, 4), np.uint8)
    shade_from = h - 2 if shade_from is None else shade_from
    for y in range(h):
        for x in range(w):
            border = x == 0 or x == w - 1 or y == 0 or y == h - 1
            role = 0 if border else (2 if y >= shade_from else 1)
            a[y, x] = (round((x + 0.5) / w * 255), role, code, 255)
    return Image.fromarray(a, "RGBA")


def coord_cell(code):
    a = np.zeros((SHAPE_TEX, SHAPE_TEX, 4), np.uint8)
    for y in range(SHAPE_TEX):
        for x in range(SHAPE_TEX):
            a[y, x] = (x, y, code, 255)
    return Image.fromarray(a, "RGBA")


# 흰 네모 종류: 이름, 높이, 잉크 줄, ascent(GUI)
RECTS = (("strike", 4, 1, 4), ("panel", 17, 17, 12), ("band", 20, 20, 15), ("uline", 2, 2, 0), ("plate", 24, 24, 10))

# 막대 묶음 모양(목업용): 이름 → (폭, 높이, 아래 빈 줄)
BARS = {"bar_phase": (120, 5, 0), "bar_reload": (60, 3, 0), "bar_mini": (16, 3, 2), "bar_hp": (HP_W, 6, 0), "bar_meter": (40, 3, 0),
        "bar_boss": (BOSS_W, 6, 0)}


def shapes():
    add_sheet("bar_phase", [bar_cell(120, 5, b) for b in range(64)], 1, 5, 5, 0xE400)
    add_sheet("bar_reload", [bar_cell(60, 3, b) for b in range(64)], 1, 3, 3, 0xE440)
    add_sheet("bar_mini", [bar_cell(16, 3, b, pad=2) for b in range(64)], 1, 5, 5, 0xE480)
    add_sheet("bar_hp", [bar_cell(HP_W, 6, b) for b in range(64)], 1, 6, 6, 0xE4C0)
    add_sheet("ring_a", [coord_cell(64 + b) for b in range(64)], 8, 40, 20, 0xE500)
    add_sheet("ring_b", [coord_cell(128 + b) for b in range(64)], 8, 48, 24, 0xE540)
    add_sheet("hit", [coord_cell(192)], 1, 24, 12, 0xE580)
    add_sheet("dot", [coord_cell(193)], 1, 7, 6, 0xE581)
    add_sheet("inf", [coord_cell(194)], 1, 10, 9, 0xE582)
    add_sheet("ptr", [coord_cell(195)], 1, 12, 6, 0xE583)
    add_sheet("bar_meter", [bar_cell(40, 3, b) for b in range(64)], 1, 3, 3, 0xE5C0)


SOLID = 196  # 단색 네모 모양 코드: G = 팔레트 번호, 조각 쪽이 가장 가까운 텍셀로만 그려 이어 붙인 자리에 틈이 없다


def rects():
    """단색 네모(셰이더 모양, G = 팔레트). 폭 2^k 를 이어 붙인다(HudLine.rect): 줄(이름 긋기, 기준선 위 4 에 1 줄), 판(높이 17),
    띠(알림 바탕, 높이 20), 밑줄(총 등급, 2 줄, 기준선 아래), 두 줄 판(죽은 이유 카드, 높이 24). 그림 글리프로 하면 쌍선형이
    가장자리에서 아틀라스 틈을 섞어 반투명 판의 이음매에 줄이 보였다(2026-11-10 게임 사진)"""
    ch = 0xE584
    for kind, h, ink, asc in RECTS:
        for k in range(8):
            w = 1 << k
            a = np.zeros((h, w, 4), np.uint8)
            a[:ink] = (0, 0, SOLID, 255)
            add_sheet(f"{kind}_{w}", [Image.fromarray(a, "RGBA")], 1, h, asc, ch)
            ch += 1
    assert ch <= 0xE5C0, "단색 네모 글자 자리가 bar_meter 와 겹침"
    # 보스 체력 막대(200 x 6, 시간 모양이 아니라 채움만 쓰므로 글자 하나): 단색 네모 바로 뒤 빈 자리(bar_meter 앞)
    add_sheet("bar_boss", [bar_cell(BOSS_W, 6, 0)], 1, 6, 6, ch)
    assert ch + 1 <= 0xE5C0, "bar_boss 글자 자리가 bar_meter 와 겹침"


# ---------------------------------------------------------------- 그림(아이콘)

SKIN = (255, 226, 219)
INK = (42, 28, 30)
EYE = (89, 27, 27)
BLUSH = (255, 176, 168)


def default_face(n=64):
    """종 아이콘이 없을 때: 연분홍 살 얼굴(스킨 화소는 안 씀, 색만 맞춰 새로 그림)"""
    def f(d, s):
        m = n * s / 64
        d.rounded_rectangle((6 * m, 6 * m, 58 * m, 58 * m), radius=10 * m, fill=SKIN + (255,), outline=INK + (255,), width=round(3 * m))
        for cx in (22, 42):
            d.arc(((cx - 7) * m, 22 * m, (cx + 7) * m, 34 * m), 200, 340, fill=EYE + (255,), width=round(3 * m))
        for cx in (17, 47):
            d.ellipse(((cx - 5) * m, 36 * m, (cx + 5) * m, 42 * m), fill=BLUSH + (200,))
        d.arc((25 * m, 36 * m, 39 * m, 46 * m), 20, 160, fill=EYE + (255,), width=round(2.5 * m))
    return drawn(n, n, f)


def icon_person():
    def f(d, s):
        d.ellipse((9 * s, 1 * s, 19 * s, 11 * s), fill=(255, 255, 255, 255))
        d.pieslice((3 * s, 13 * s, 25 * s, 39 * s), 180, 360, fill=(255, 255, 255, 255))
    return drawn(28, 28, f)


def icon_bell():
    def f(d, s):
        w = (255, 255, 255, 255)
        d.ellipse((24 * s, 2 * s, 32 * s, 10 * s), fill=w)
        d.pieslice((10 * s, 6 * s, 46 * s, 42 * s), 180, 360, fill=w)
        d.rectangle((10 * s, 24 * s, 46 * s, 40 * s), fill=w)
        d.polygon([(4 * s, 46 * s), (52 * s, 46 * s), (46 * s, 38 * s), (10 * s, 38 * s)], fill=w)
        d.ellipse((23 * s, 45 * s, 33 * s, 55 * s), fill=w)
    return drawn(56, 56, f)


def icon_alert():
    def f(d, s):
        w = (255, 255, 255, 255)
        d.polygon([(20 * s, 2 * s), (38 * s, 36 * s), (2 * s, 36 * s)], fill=w)
        d.rounded_rectangle((18 * s, 11 * s, 22 * s, 26 * s), radius=2 * s, fill=(0, 0, 0, 0))
        d.ellipse((18 * s, 28 * s, 22 * s, 32 * s), fill=(0, 0, 0, 0))
    return drawn(40, 40, f)


def icon_card():
    def f(d, s):
        w = (255, 255, 255, 255)
        d.rounded_rectangle((6 * s, 1 * s, 30 * s, 39 * s), radius=4 * s, outline=w, width=3 * s)
        c, r = (18, 20), 8
        pts = [(c[0] + (r if i % 2 == 0 else r * 0.42) * math.sin(i * math.pi / 5), c[1] - (r if i % 2 == 0 else r * 0.42) * math.cos(i * math.pi / 5))
               for i in range(10)]
        d.polygon([(x * s, y * s) for x, y in pts], fill=w)
    return drawn(36, 40, f)


def icon_shop():
    def f(d, s):
        w = (255, 255, 255, 255)
        d.polygon([(4 * s, 18 * s), (10 * s, 4 * s), (38 * s, 4 * s), (44 * s, 18 * s)], fill=w)
        for i in range(4):
            d.pieslice(((4 + i * 10) * s, 12 * s, (14 + i * 10) * s, 24 * s), 0, 180, fill=w)
        d.rectangle((8 * s, 22 * s, 11 * s, 44 * s), fill=w)
        d.rectangle((37 * s, 22 * s, 40 * s, 44 * s), fill=w)
        d.rectangle((6 * s, 32 * s, 42 * s, 36 * s), fill=w)
    return drawn(48, 48, f)


def icon_pip():
    def f(d, s):
        d.rounded_rectangle((0, 0, 16 * s - 1, 28 * s - 1), radius=3 * s, fill=(255, 255, 255, 255))
    return drawn(16, 28, f)


def icon_notch():
    """보스 막대 문턱 눈금: 1 x 8 GUI(RES 텍셀/GUI), 가운데 흰 줄 + 양옆 어두운 테(빨간 막대 위에서도 보이게)"""
    a = np.zeros((8 * RES, RES, 4), np.uint8)
    a[:, :] = (30, 20, 22, 230)
    a[:, 1:RES - 1] = (255, 255, 255, 255)
    return Image.fromarray(a, "RGBA")


def icon_boss():
    """보스 아이콘이 아직 없는 보스용 공용 그림: 엔드 막대기 다섯을 왕관처럼 꽂은 실눈 웃는 얼굴(그림자 테두리)"""
    def f(d, s):
        rod = (236, 228, 214, 255)
        for i, (x, top) in enumerate(((12, 9), (18, 4), (24, 1), (30, 4), (36, 9))):
            d.rounded_rectangle(((x - 1.6) * s, top * s, (x + 1.6) * s, 18 * s), radius=1.2 * s, fill=rod, outline=INK + (255,), width=round(1 * s))
        d.rounded_rectangle((5 * s, 15 * s, 43 * s, 46 * s), radius=8 * s, fill=SKIN + (255,), outline=INK + (255,), width=round(2.5 * s))
        for cx in (17, 31):
            d.arc(((cx - 6) * s, 23 * s, (cx + 6) * s, 33 * s), 200, 340, fill=EYE + (255,), width=round(2.6 * s))
        for cx in (12, 36):
            d.ellipse(((cx - 4) * s, 33 * s, (cx + 4) * s, 38 * s), fill=BLUSH + (210,))
        d.ellipse((22 * s, 36 * s, 26 * s, 40 * s), fill=(66, 20, 20, 255))
    return drawn(48, 48, f)


def icon_coin():
    f = SHOP_TEX / "coin.png"
    if f.is_file():
        return Image.open(f).convert("RGBA")

    def g(d, s):
        d.ellipse((2 * s, 2 * s, 30 * s, 30 * s), fill=(255, 205, 70, 255), outline=(150, 100, 20, 255), width=2 * s)
        d.ellipse((9 * s, 9 * s, 23 * s, 23 * s), outline=(200, 140, 30, 255), width=2 * s)
    return drawn(32, 32, g)


GOAL_KINDS = ("kill", "survive", "truck", "hold", "collect", "hunt", "timed", "build", "start")


def icon_goal(kind):
    """라운드 목표 아이콘(흰 기호 40 x 40): kill 조준선, survive 모래시계, truck 방패, hold 깃발, collect 상자, hunt 별, timed 시계, build 쌓인 판, start 번개"""
    w, clear = (255, 255, 255, 255), (0, 0, 0, 0)

    def f(d, s):
        if kind == "kill":
            d.ellipse((6 * s, 6 * s, 34 * s, 34 * s), outline=w, width=4 * s)
            for a, b, c, e in ((18, 0, 22, 12), (18, 28, 22, 40), (0, 18, 12, 22), (28, 18, 40, 22)):
                d.rectangle((a * s, b * s, c * s, e * s), fill=w)
        elif kind == "survive":
            d.polygon([(6 * s, 2 * s), (34 * s, 2 * s), (20 * s, 20 * s)], fill=w)
            d.polygon([(20 * s, 20 * s), (6 * s, 38 * s), (34 * s, 38 * s)], outline=w, width=3 * s)
            d.polygon([(20 * s, 27 * s), (11 * s, 36 * s), (29 * s, 36 * s)], fill=w)
        elif kind == "truck":
            d.polygon([(20 * s, 2 * s), (36 * s, 8 * s), (34 * s, 24 * s), (20 * s, 38 * s), (6 * s, 24 * s), (4 * s, 8 * s)], fill=w)
            d.polygon([(20 * s, 9 * s), (29 * s, 13 * s), (28 * s, 23 * s), (20 * s, 31 * s)], fill=clear)
        elif kind == "hold":
            d.rectangle((8 * s, 2 * s, 12 * s, 38 * s), fill=w)
            d.polygon([(12 * s, 3 * s), (36 * s, 11 * s), (12 * s, 20 * s)], fill=w)
            d.ellipse((2 * s, 33 * s, 26 * s, 39 * s), outline=w, width=2 * s)
        elif kind == "collect":
            d.rectangle((4 * s, 6 * s, 36 * s, 38 * s), outline=w, width=4 * s)
            d.rectangle((4 * s, 15 * s, 36 * s, 18 * s), fill=w)
            d.rectangle((17 * s, 6 * s, 23 * s, 18 * s), fill=w)
        elif kind == "hunt":
            import math as _m
            pts = [(20 + (18 if i % 2 == 0 else 7.5) * _m.sin(i * _m.pi / 5), 21 - (18 if i % 2 == 0 else 7.5) * _m.cos(i * _m.pi / 5)) for i in range(10)]
            d.polygon([(x * s, y * s) for x, y in pts], fill=w)
        elif kind == "timed":
            d.ellipse((3 * s, 3 * s, 37 * s, 37 * s), outline=w, width=4 * s)
            d.rectangle((18 * s, 9 * s, 22 * s, 21 * s), fill=w)
            d.polygon([(19 * s, 18 * s), (29 * s, 26 * s), (27 * s, 29 * s), (17 * s, 21 * s)], fill=w)
        elif kind == "build":
            for i, (x0, x1) in enumerate(((4, 36), (8, 32), (12, 28))):
                y = 38 - i * 12
                d.rectangle((x0 * s, (y - 9) * s, x1 * s, y * s), fill=w)
        elif kind == "start":
            d.polygon([(24 * s, 1 * s), (6 * s, 23 * s), (18 * s, 23 * s), (14 * s, 39 * s), (34 * s, 15 * s), (22 * s, 15 * s)], fill=w)
    return drawn(40, 40, f)


def images():
    """총, 소모품, 동전, 기호 그림. (만든 것, 없는 것)"""
    made, missing = [], []
    for size, (mw, mh, lift) in GUN_SIZES.items():
        for g in GUNS + [f"{g}_nomag" for g in GUNS]:
            if size != "l" and g.endswith("_nomag"):
                continue
            r = gun_icon(g, mw, mh)
            if r is None:
                if size == "l" and not g.endswith("_nomag"):
                    missing.append(g)
                continue
            im, hg = r
            add_image(f"gun_{size}_{g}", im, hg, hg + lift)
            made.append(f"gun_{size}_{g}")
    n = nade_icon()
    if n is not None:
        add_image("it_grenade", n, 12, 10)
    else:
        missing.append("grenade")
    for k in SUPPLIES:
        f = SHOP_TEX / f"{k}.png"
        if f.is_file():
            add_image(f"it_{k}", glyph_img(Image.open(f).convert("RGBA"), 12), 12, 10)
        else:
            missing.append(f"shop/{k}")
    add_image("coin", glyph_img(icon_coin(), 12, pad_bottom=1), 13, 13)
    add_image("person", glyph_img(icon_person(), 7), 7, 7)
    add_image("bell", glyph_img(icon_bell(), 14), 14, 11)
    add_image("alert", glyph_img(icon_alert(), 10), 10, 9)
    add_image("card", glyph_img(icon_card(), 10), 10, 9)
    add_image("shop", glyph_img(icon_shop(), 12), 12, 11)
    add_image("pip", glyph_img(icon_pip(), 7), 7, 7)
    # 보스 틀(2026-11-12 boss_infra): 단계 문턱 눈금(1 x 8 흰 금, 막대 위아래로 1씩 넘침), 보스 아이콘이 없는 보스용 공용 그림(막대기 왕관 쓴 얼굴)
    add_image("bs_notch", icon_notch(), 8, 7)
    add_image("ic_boss", glyph_img(icon_boss(), 10), 10, 8)
    # 원정: 트럭 아이콘(모드 모델 옆모습). 없으면 Java 가 아이콘 없이 막대만 그린다
    t = truck_icon()
    if t is not None:
        add_image("ti_truck", t[0], t[1], t[1] - 1)
    else:
        missing.append("ti_truck")
    # 원정 라운드 목표 아이콘(trip_rounds 의 goal.icon = ti_goal_<kind>)
    for k in GOAL_KINDS:
        add_image(f"ti_goal_{k}", glyph_img(icon_goal(k), 10), 10, 9)
    return made, missing


def species_icons():
    """build/gabe/icons/<종>.png 하나를 세 크기 글리프로. 기본 얼굴(default)도. (있는 종 수, 없는 종 수)"""
    keys = []
    if BESTIARY.is_file():
        keys = [sp["id"].lower() for sp in json.loads(BESTIARY.read_text(encoding="utf-8")).get("species", []) if sp.get("id")]
    srcs = [("default", default_face())]
    for k in keys:
        f = ICON_DIR / f"{k}.png"
        if f.is_file():
            im = Image.open(f).convert("RGBA")
            srcs.append((k, resize_pm(im, (64, 64)) if im.size != (64, 64) else bleed(np.asarray(im, np.float32) / 255)))
    for k, im in srcs:
        OUT.png(f"{TEX}/ic_{k}.png", im)
        for size, (h, asc) in ICON_SIZES.items():
            add_image(f"ic_{size}_{k}", im, h, asc, file=f"ic_{k}")
        if k != "default":
            ICONS.append(k)
    return len(ICONS), len(keys) - len(ICONS)


# ---------------------------------------------------------------- 화면 덮개

def cover_dark():
    """가장자리부터 어두워지는 덮개(어둠, 눈 감김 같은 종이 같이 씀)"""
    W, H = COVER_W, COVER_H
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    r = np.hypot((xx + 0.5 - W / 2) / (W / 2), (yy + 0.5 - H / 2) / (H / 2))
    a = np.clip((r - 0.35) / 0.75, 0, 1) ** 1.4 * 0.96
    arr = np.zeros((H, W, 4), np.float32)
    arr[..., :3] = 0.02
    arr[..., 3] = a
    return Image.fromarray((arr * 255 + 0.5).astype(np.uint8), "RGBA")


def cover_letterbox():
    """보스 등장 레터박스(2026-11-12 boss_infra, 보스.md 7.4): 위아래 34 화소(480x270 기준) 검은 띠 + 가장자리가 옅게 어두워짐. 가운데는 비어
    HUD 와 싸움이 그대로 보인다. 다섯 보스가 같이 쓴다(hud.cover(p, "letterbox", 틱))"""
    W, H = COVER_W, COVER_H
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    r = np.hypot((xx + 0.5 - W / 2) / (W / 2), (yy + 0.5 - H / 2) / (H / 2))
    a = np.clip((r - 0.75) / 0.6, 0, 1) ** 1.6 * 0.45
    band = 34
    a[:band] = 0.96
    a[H - band:] = 0.96
    # 띠 안쪽 가장자리 2 화소는 부드럽게
    a[band:band + 2] = np.maximum(a[band:band + 2], 0.5)
    a[H - band - 2:H - band] = np.maximum(a[H - band - 2:H - band], 0.5)
    arr = np.zeros((H, W, 4), np.float32)
    arr[..., :3] = 0.01
    arr[..., 3] = a
    return Image.fromarray((arr * 255 + 0.5).astype(np.uint8), "RGBA")


def cover_black():
    """온 화면 검정(원정: 맵 전환, 판 시작, 게임 오버, 엔딩의 글 한 줄 뒤판). 가운데 글(STORY 칸)은 덮개 위에 그려진다"""
    arr = np.zeros((COVER_H, COVER_W, 4), np.float32)
    arr[..., :3] = 0.0
    arr[..., 3] = 1.0
    return Image.fromarray((arr * 255 + 0.5).astype(np.uint8), "RGBA")


def cover_slit():
    """실눈 구멍(원정: 죽어서 게이브 몸에 갇힌 사람의 화면). 가운데가 가로로 긴 눈구멍, 둘레는 검정, 구멍 가장자리는 부드럽게"""
    W, H = COVER_W, COVER_H
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    x = (xx + 0.5 - W / 2) / (W * 0.40)
    y = (yy + 0.5 - H / 2) / (H * 0.20)
    # 아몬드 꼴: |y| < 1 - x^2 안이 구멍
    d = np.abs(y) - (1 - x * x)
    a = np.clip(d / 0.22 + 0.5, 0, 1) ** 0.8
    a[np.abs(x) >= 1] = 1.0
    arr = np.zeros((H, W, 4), np.float32)
    arr[..., :3] = 0.0
    arr[..., 3] = a * 0.985
    return Image.fromarray((arr * 255 + 0.5).astype(np.uint8), "RGBA")


def cover_test():
    """시험 덮개(조각 번호, 가장자리 틀, 가운데 십자): 조각 자리와 화면 덮기를 게임에서 확인. 960x540(조밀한 쪽)"""
    W, H = COVER_W * 2, COVER_H * 2
    im = Image.new("RGBA", (W, H), (20, 40, 70, 110))
    d = ImageDraw.Draw(im)
    tw, th = W // COVER_COLS, H // COVER_ROWS
    font = ImageFont.truetype(str(FONT_KO), 48) if FONT_KO.is_file() else None
    for t in range(COVER_COLS * COVER_ROWS):
        r, c = divmod(t, COVER_COLS)
        x0, y0 = c * tw, r * th
        d.rectangle((x0 + 2, y0 + 2, x0 + tw - 3, y0 + th - 3), outline=(255, 200, 60, 200), width=2)
        d.text((x0 + 16, y0 + 10), str(t), fill=(255, 255, 255, 230), font=font)
    d.rectangle((0, 0, W - 1, H - 1), outline=(255, 60, 60, 255), width=6)
    d.line((W // 2 - 30, H // 2, W // 2 + 30, H // 2), fill=(255, 255, 255, 255), width=3)
    d.line((W // 2, H // 2 - 30, W // 2, H // 2 + 30), fill=(255, 255, 255, 255), width=3)
    return im


def fit_cover(src):
    """16:9 로 가운데 자르고 480x270(원본 폭 960 이상이면 960x540)으로 → (그림, 조밀도)"""
    im = src.convert("RGBA")
    W, H = im.size
    want = COVER_W / COVER_H
    if W / H > want + 1e-3:
        nw = round(H * want)
        im = im.crop(((W - nw) // 2, 0, (W - nw) // 2 + nw, H))
    elif W / H < want - 1e-3:
        nh = round(W / want)
        im = im.crop((0, (H - nh) // 2, W, (H - nh) // 2 + nh))
    d = 2 if im.width >= COVER_W * 2 else 1
    size = (COVER_W * d, COVER_H * d)
    if im.size != size:
        return resize_pm(im, size), d
    return bleed(np.asarray(im, np.float32) / 255), d


def covers():
    """덮개를 조각 글리프 cv_<키>_<0..11> 로. 다 투명한 조각은 뺀다(Java 는 HudLine.has 로 봄). → 덮개 수"""
    src = [("dark", cover_dark()), ("test", cover_test()), ("letterbox", cover_letterbox()), ("black", cover_black()), ("slit", cover_slit())]
    if COVERS_JSON.is_file():
        for key, c in json.loads(COVERS_JSON.read_text(encoding="utf-8") or "{}").items():
            f = GABE / c.get("file", f"covers/{key}.png")
            if not re.fullmatch(r"[a-z0-9_]+", key):
                raise SystemExit(f"덮개 키 {key!r}: 소문자, 숫자, _ 만")
            if any(k == key for k, _ in src):
                raise SystemExit(f"덮개 키 {key} 가 이미 있다(dark, test, letterbox, black, slit 는 HUD 담당 기본 덮개)")
            if not f.is_file():
                print(f"덮개 {key}: 그림 {f} 가 없어 건너뜀")
                continue
            src.append((key, Image.open(f)))
    for key, im in src:
        img, d = fit_cover(im)
        tw, th = COVER_TW * d, COVER_TH * d
        for t in range(COVER_COLS * COVER_ROWS):
            r, c = divmod(t, COVER_COLS)
            tile = img.crop((c * tw, r * th, (c + 1) * tw, (r + 1) * th))
            if np.asarray(tile.getchannel("A")).max() == 0:
                continue
            add_image(f"cv_{key}_{t}", tile, COVER_TH, COVER_TH)
        COVERS.append(key)
    return len(COVERS)


# ---------------------------------------------------------------- 셰이더

def patch(src, anchor, add, after=True):
    if src.count(anchor) != 1:
        raise SystemExit(f"바닐라 셰이더에서 '{anchor.strip()}' 를 한 번 찾지 못했다(26.3 바뀜?)")
    return src.replace(anchor, anchor + add if after else add + anchor)


def glsl_vec4(v):
    return "vec4(" + ", ".join(f"{x:.4f}" for x in v) + ")"


def spec_glsl(band_top, band_bot):
    n = len(SLOTS)
    L = ["// tools/make_hud.py 가 만든다(손으로 고치지 말 것). 칸 = (기준 x 비율, 기준 y 비율, 오프셋 x, 오프셋 y), 오프셋 y 는 글자 기준선(GUI).",
         "#ifndef GAVE_HUD_SPEC_GLSL", "#define GAVE_HUD_SPEC_GLSL",
         f"const int ZR_B_IMG = {B_IMG};", f"const int ZR_B_SHAPE = {B_SHAPE};", f"const int ZR_B_TIMED = {B_TIMED};",
         f"const int ZR_TIMED_SPAN = {TIMED_SPAN};", f"const float ZR_PERIOD = {PERIOD:.1f};",
         f"const float ZR_BAND_TOP = {band_top:.1f};", f"const float ZR_BAND_BOT = {band_bot:.1f};",
         f"const float ZR_SHAPE_TEX = {SHAPE_TEX:.1f};", f"const float ZR_HIT_TICKS = {HIT_TICKS:.1f};",
         f"const float ZR_RING_A_IN = {RING_A[0]:.4f};", f"const float ZR_RING_A_OUT = {RING_A[1]:.4f};",
         f"const float ZR_RING_B_IN = {RING_B[0]:.4f};", f"const float ZR_RING_B_OUT = {RING_B[1]:.4f};",
         f"const vec4 ZR_TRACK = {glsl_vec4(TRACK)};", f"const vec4 ZR_EDGE = {glsl_vec4(EDGE)};",
         f"const float ZR_COVER_W = {COVER_W:.1f};", f"const float ZR_COVER_H = {COVER_H:.1f};",
         f"const float ZR_COVER_TW = {COVER_TW:.1f};", f"const float ZR_COVER_TH = {COVER_TH:.1f};",
         f"const float ZR_COVER_STEP = {COVER_STEP:.1f};", f"const float ZR_COVER_X0 = {COVER_X0:.1f};",
         f"const vec2 ZR_PTR_R = vec2({PTR_R[0]:.4f}, {PTR_R[1]:.4f});",
         f"const int ZR_SLOT_COUNT = {n};",
         f"const vec4 ZR_SLOT[{n}] = vec4[](\n    " + ",\n    ".join(glsl_vec4((s[1], s[2], s[3], s[4])) for s in SLOTS) + "\n);",
         f"const vec4 ZR_SLOT_COLOR[{n}] = vec4[](\n    " + ",\n    ".join(glsl_vec4(s[5] or WHITE) for s in SLOTS) + "\n);",
         f"const int ZR_SLOT_MODE[{n}] = int[](" + ", ".join(str(s[6]) for s in SLOTS) + ");",
         f"const int ZR_PAL_COUNT = {len(PALETTE)};",
         f"const vec4 ZR_PAL[{len(PALETTE)}] = vec4[](\n    " + ",\n    ".join(glsl_vec4(p[1]) for p in PALETTE) + "\n);",
         f"const int ZR_PAL_FX[{len(PALETTE)}] = int[](" + ", ".join(str(p[2]) for p in PALETTE) + ");",
         f"const float ZR_DUR[64] = float[](" + ", ".join(f"{d:.1f}" for d in DUR) + ");",
         "#endif", ""]
    return "\n".join(L)


def shaders(band_top, band_bot):
    vsh = (VANILLA / "text.vsh").read_text(encoding="utf-8")
    fsh = (VANILLA / "text.fsh").read_text(encoding="utf-8")
    vsh = patch(vsh, "#include <minecraft:projection.glsl>\n", "#ifdef IS_GUI\n#include <gave:hud.glsl>\n#endif\n")
    vsh = patch(vsh, "layout(location = 3) out vec2 texCoord0;\n", "layout(location = 4) out vec4 zrHud;\n")
    vsh = patch(vsh, "    texCoord0 = UV0;\n",
                "#ifdef IS_GUI\n    // 게이브 HUD(gave:hud.glsl): 보스바 제목의 표시 색 글리프를 화면 칸으로 옮긴다\n"
                "    zrhud_vertex(Position, Color, gl_Position, vertexColor, zrHud);\n#else\n    zrHud = vec4(0.0);\n#endif\n")
    fsh = patch(fsh, "uniform sampler2D Sampler0;\n", "#ifdef IS_GUI\n#include <gave:hud_frag.glsl>\n#endif\n")
    fsh = patch(fsh, "layout(location = 3) in vec2 texCoord0;\n", "layout(location = 4) in vec4 zrHud;\n")
    fsh = patch(fsh, "void main() {\n",
                "#if defined(IS_GUI) && !defined(OIT_ALPHA_ONLY)\n    if (zrHud.x > 0.5) {\n"
                "        fragColor = zrhud_fragment(texCoord0, vertexColor, zrHud);\n        return;\n    }\n#endif\n")
    OUT.put("assets/minecraft/shaders/core/text.vsh", vsh)
    OUT.put("assets/minecraft/shaders/core/text.fsh", fsh)
    OUT.put("assets/gave/shaders/include/hud.glsl", (SRC / "hud.glsl").read_text(encoding="utf-8"))
    OUT.put("assets/gave/shaders/include/hud_frag.glsl", (SRC / "hud_frag.glsl").read_text(encoding="utf-8"))
    OUT.put("assets/gave/shaders/include/hud_spec.glsl", spec_glsl(band_top, band_bot))


# ---------------------------------------------------------------- 플러그인 표

def jstr(s):
    return '"' + "".join(c if 0x20 <= ord(c) < 0x7F and c not in '"\\' else f"\\u{ord(c):04x}" for c in s) + '"'


# 큰 배열은 정적 배열 초기화로 쓰면 HudSpec 의 정적 초기화 코드가 64KB 를 넘는다(글리프 1856, 글귀 931 에서 'code too large', 2026-11-12).
# 그래서 글자열 상수(한 상수 15000 글자 이하: 수정 UTF-8 로 64KB 안)를 이어 붙여 클래스가 읽힐 때 푼다
J_SEP = "\u0001"
J_CHUNK = 15000


def _chunks(text):
    return [text[i:i + J_CHUNK] for i in range(0, len(text), J_CHUNK)] or [""]


def jstrs(items):
    items = list(items)
    if not items:
        return "new String[0]"
    return "strs(" + ", ".join(jstr(c) for c in _chunks(J_SEP.join(items))) + ")"


def jints(nums):
    nums = list(nums)
    if not nums:
        return "new int[0]"
    return "ints(" + ", ".join(jstr(c) for c in _chunks(",".join(str(int(x)) for x in nums))) + ")"


def jints2(rows):
    rows = list(rows)
    if not rows:
        return "new int[0][]"
    return "ints2(" + ", ".join(jstr(c) for c in _chunks(";".join(",".join(str(int(x)) for x in r) for r in rows))) + ")"


JAVA_HELPERS = [
    "    /** 큰 배열 풀기(make_hud.py jstrs, jints, jints2: 정적 초기화 64KB 한도를 피함) */",
    "    private static String[] strs(String... parts) {",
    "        return String.join(\"\", parts).split(\"\\u0001\", -1);",
    "    }",
    "",
    "    private static int[] ints(String... parts) {",
    "        String j = String.join(\"\", parts);",
    "        if (j.isEmpty()) return new int[0];",
    "        String[] a = j.split(\",\", -1);",
    "        int[] out = new int[a.length];",
    "        for (int i = 0; i < a.length; i++) out[i] = Integer.parseInt(a[i]);",
    "        return out;",
    "    }",
    "",
    "    private static int[][] ints2(String... parts) {",
    "        String j = String.join(\"\", parts);",
    "        String[] rows = j.split(\";\", -1);",
    "        int[][] out = new int[rows.length][];",
    "        for (int r = 0; r < rows.length; r++) out[r] = rows[r].isEmpty() ? new int[0] : ints(rows[r]);",
    "        return out;",
    "    }",
    "",
]


def java(tables):
    def adv_string(t):
        return jstr("".join(chr(0x1000 + t.get(chr(c), 0)) for c in range(0x20, 0x7F)))

    L = ["package kr.gave.hud;", "",
         "/** tools/make_hud.py 가 만든다(손으로 고치지 말 것). 팩 pack_src 의 HUD 글꼴, 글리프, 칸, 팔레트와 짝이다. */",
         "public final class HudSpec {", "    private HudSpec() {", "    }", "", *JAVA_HELPERS,
         '    public static final String FONT_HUD = "gave:hud", FONT_S = "gave:hud_s", FONT_M = "gave:hud_m";',
         f"    public static final int B_IMG = {B_IMG}, B_SHAPE = {B_SHAPE}, B_TIMED = {B_TIMED}, TIMED_SPAN = {TIMED_SPAN}, PERIOD = {PERIOD};",
         f"    public static final int MOVE_BITS = {MOVE_BITS}, HIT_TICKS = {HIT_TICKS};",
         "    /** 화면 덮개: 조각 cv_<키>_<0..COLS*ROWS-1>(줄 r = 칸 CV<r>, 칸 c 는 x = COVER_X0 + COVER_STEP * c 에 놓음) */",
         f"    public static final int COVER_X0 = {COVER_X0}, COVER_STEP = {COVER_STEP}, COVER_COLS = {COVER_COLS}, COVER_ROWS = {COVER_ROWS};",
         f"    /** 체력 막대 폭(bar_hp 텍셀) */",
         f"    public static final int HP_W = {HP_W};",
         "    /** 글자 0x20..0x7E 전진 폭(+0x1000): 1배, 2배 */",
         f"    public static final String ADV_S = {adv_string(tables['hud_s'])};",
         f"    public static final String ADV_M = {adv_string(tables['hud_m'])};",
         "    /** 그림 글리프: 이름, 글자, 전진 폭, 잉크 폭, 높이, ascent(GUI) */",
         "    public static final String[] IMAGES = " + jstrs(i[0] for i in IMAGES) + ";",
         "    public static final String IMAGE_CHARS = " + jstr("".join(i[1] for i in IMAGES)) + ";",
         "    public static final int[] IMAGE_ADV = " + jints(i[2] for i in IMAGES) + ";",
         "    public static final int[] IMAGE_INK = " + jints(i[3] for i in IMAGES) + ";",
         "    public static final int[] IMAGE_H = " + jints(i[4] for i in IMAGES) + ";",
         "    public static final int[] IMAGE_ASC = " + jints(i[5] for i in IMAGES) + ";",
         "    /** 셰이더 모양 글리프: 이름, 첫 글자(길이 칸 0), 전진 폭 */",
         "    public static final String[] SHAPES = {" + ", ".join(jstr(k) for k in SHAPES) + "};",
         "    public static final String SHAPE_CHARS = " + jstr("".join(chr(v[0]) for v in SHAPES.values())) + ";",
         "    public static final int[] SHAPE_ADV = {" + ", ".join(str(v[1]) for v in SHAPES.values()) + "};",
         "    /** 시간 모양 길이 칸(틱) */",
         "    public static final int[] DUR = {" + ", ".join(str(d) for d in DUR) + "};",
         "    public static final String[] SLOTS = {" + ", ".join(jstr(s[0]) for s in SLOTS) + "};",
         "    public static final String[] PALETTE = {" + ", ".join(jstr(p[0]) for p in PALETTE) + "};",
         "    /** 팔레트마다 글자 그림자 팔레트(같은 깜빡임 효과) */",
         "    public static final int[] PAL_SHADOW = {" + ", ".join(str(i) for i in PAL_SHADOW) + "};",
         "    /** 덮개 키(build/gabe/covers.json + dark, test, letterbox), 아이콘이 있는 종 키(g01 ..) */",
         "    public static final String[] COVERS = " + jstrs(COVERS) + ";",
         "    public static final String[] ICONS = " + jstrs(ICONS) + ";",
         "    /** 한국어 글귀(data/phrases/*.json, 종 이름 name_g01 ..): 키, 글(시험 표시용), 조각 그림 이름(쉼표로 이음, 하나면 키와 같음),",
         "     *  조각 x(GUI, 글귀 왼쪽 끝 기준), 전체 폭(GUI). 그림 높이 9 GUI(기준선 아래 잉크가 있으면 12), ascent 8 */",
         "    public static final String[] PHRASES = " + jstrs(p[0] for p in PHRASES) + ";",
         "    public static final String[] PHRASE_TEXT = " + jstrs(p[1] for p in PHRASES) + ";",
         "    public static final String[] PHRASE_PARTS = " + jstrs(",".join(p[2]) for p in PHRASES) + ";",
         "    public static final int[][] PHRASE_X = " + jints2(p[3] for p in PHRASES) + ";",
         "    public static final int[] PHRASE_W = " + jints(p[4] for p in PHRASES) + ";",
         "}", ""]
    return "\n".join(L)


# ---------------------------------------------------------------- 쓰기, 검사

def space_provider():
    adv = {}
    for i in range(MOVE_BITS):
        adv[chr(0xE000 + i)] = 1 << i
        adv[chr(0xE010 + i)] = -(1 << i)
    return {"type": "space", "advances": adv}


def write_to(root):
    for rel, data in OUT.files.items():
        p = root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        tmp = p.with_name(p.name + ".tmp")
        tmp.write_bytes(data)
        os.replace(tmp, p)


def clean(root):
    """이 생성기 몫 폴더의 예전 파일을 지운다(이번에 안 만든 글리프)"""
    keep = set(OUT.files)
    for d in (root / TEX, root / "assets/gave/font"):
        if not d.is_dir():
            continue
        for f in d.rglob("*"):
            rel = f.relative_to(root).as_posix()
            if f.is_file() and rel not in keep and (d.name == "hud" or f.name.startswith("hud")):
                f.unlink()


def check(stage):
    # 만든 사람 PC 의 검사기가 없으면 tools/shader_check.py 로 대신한다(LWJGL shaderc 컴파일. 그것도 못 돌리는 PC 면 건너뜀을 찍고 통과)
    cmd = [str(CHECK), str(stage), "--mc", "26.3"] if CHECK.is_file() else [str(ROOT / "tools/shader_check.py"), str(stage)]
    r = subprocess.run([sys.executable, "-X", "utf8", *cmd], capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    tail = "\n".join((r.stdout + r.stderr).strip().splitlines()[-25:])
    return r.returncode == 0, tail


class Lock:
    """build/locks/make_hud.lock 운영체제 잠금(쥔 프로세스가 죽으면 저절로 풀림). 여러 사람이 같이 돌려도 pack_src 쓰기가 섞이지 않게"""

    def __enter__(self):
        LOCK.parent.mkdir(parents=True, exist_ok=True)
        self.f = open(LOCK, "a+b")
        t0 = time.time()
        while True:
            try:
                if os.name == "nt":
                    import msvcrt
                    self.f.seek(0)
                    msvcrt.locking(self.f.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(self.f.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                return self
            except OSError:
                if time.time() - t0 > 600:
                    raise SystemExit("make_hud 잠금을 10분 기다려도 못 얻음")
                time.sleep(0.5)

    def __exit__(self, *a):
        try:
            if os.name == "nt":
                import msvcrt
                self.f.seek(0)
                msvcrt.locking(self.f.fileno(), msvcrt.LK_UNLCK, 1)
        finally:
            self.f.close()


def preview(path):
    """그림 글리프 모음(검은 바탕과 회색 바탕). 덮개 조각, 네모 조각은 뺌"""
    seen, ims = set(), []
    for n, *_ in IMAGES:
        f = IMAGE_FILE[n]
        if n.startswith(tuple(r[0] + "_" for r in RECTS) + ("cv_",)) or f in seen:
            continue
        seen.add(f)
        ims.append(Image.open(io.BytesIO(OUT.files[f"{TEX}/{f}.png"])))
    W = 1100
    x = y = 0
    rowh = 0
    pos = []
    for im in ims:
        if x + im.width + 8 > W:
            x, y, rowh = 0, y + rowh + 8, 0
        pos.append((x, y))
        x += im.width + 8
        rowh = max(rowh, im.height)
    H = y + rowh + 8
    out = Image.new("RGBA", (W, H * 2), (0, 0, 0, 255))
    out.paste((120, 128, 136, 255), (0, H, W, H * 2))
    for im, (px, py) in zip(ims, pos):
        out.alpha_composite(im, (px + 4, py + 4))
        out.alpha_composite(im, (px + 4, H + py + 4))
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    out.save(path)


# ---------------------------------------------------------------- 배치 목업(게임 아님): 칸 표와 글리프로 대표 화면을 그림

class Mock:
    """GUI W x H 를 k 배 화소로. 칸 기준점, 글리프 ascent, 펜 이동, 셰이더 모양(막대, 고리, 적중, 화살표, 덮개 FIT)을 셰이더처럼 흉내"""
    _ascii = None

    def __init__(self, gui, k=3, bg=None):
        self.W, self.H = gui
        self.k = k
        self.img = Image.new("RGBA", (self.W * k, self.H * k), (78, 84, 92, 255))
        if bg and Path(bg).exists():
            self.img = Image.open(bg).convert("RGBA").resize((self.W * k, self.H * k), Image.LANCZOS)
        if Mock._ascii is None:
            with zipfile.ZipFile(JAR) as z:
                grid = next(p for p in json.loads(z.read("assets/minecraft/font/include/default.json"))["providers"]
                            if p.get("file") == "minecraft:font/ascii.png")["chars"]
            Mock._ascii = (jar_png("assets/minecraft/textures/font/ascii.png"), {c: (x, y) for y, row in enumerate(grid) for x, c in enumerate(row)})
        self.pal = {n: v for n, v, _ in PALETTE}
        self.img_by = {i[0]: i for i in IMAGES}
        self.slot = {s[0]: s for s in SLOTS}
        self.ph = {p[0]: p for p in PHRASES}

    # 바닐라 자리(옅은 선): 핫바 아이템 3칸, 자막(오른쪽 아래, 아래 30 위), 채팅(아래 40 위), 조준점
    def vanilla(self):
        d = ImageDraw.Draw(self.img)
        k, W, H = self.k, self.W, self.H
        c = (90, 220, 255, 150)
        for i in range(3):
            x = W // 2 - 91 + 3 + 20 * i
            d.rectangle((x * k, (H - 19) * k, (x + 16) * k, (H - 3) * k), outline=c, width=1)
        d.rectangle(((W - 122) * k, (H - 60) * k, (W - 2) * k, (H - 30) * k), outline=(255, 200, 90, 150), width=1)
        d.rectangle((2 * k, (H - 130) * k, 322 * k, (H - 40) * k), outline=(200, 160, 255, 110), width=1)
        cx, cy = W // 2 * k, H // 2 * k
        d.line((cx - 4 * k, cy, cx + 4 * k, cy), fill=(255, 255, 255, 200))
        d.line((cx, cy - 4 * k, cx, cy + 4 * k), fill=(255, 255, 255, 200))

    def anchor(self, slot):
        s = self.slot[slot]
        return s[1] * self.W + s[3], s[2] * self.H + s[4]

    def tint(self, im, rgba):
        a = np.asarray(im, np.float32) / 255
        a[..., :4] *= np.array(rgba, np.float32)
        return Image.fromarray((a * 255).clip(0, 255).astype(np.uint8), "RGBA")

    def put(self, im, x, y):
        self.img.alpha_composite(im, (int(round(x)), int(round(y))))

    def line(self, slot):
        return MockLine(self, slot)

    def save(self, path):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.img.convert("RGB").save(path)


class MockLine:
    def __init__(self, m, slot):
        self.m, self.slot, self.pen = m, slot, 0

    def at(self, x):
        self.pen = x
        return self

    def width(self, font, s):
        f = 1 if font == "S" else 2
        ascii_img, where = Mock._ascii
        w = 0
        for ch in s:
            if ch == " ":
                w += 4 * f
                continue
            cx, cy = where[ch]
            w += int(0.5 + ink_width(ascii_img.crop((cx * 8, cy * 8, cx * 8 + 8, cy * 8 + 8))) * f) + 1
        return w

    def text(self, font, s, p="WHITE"):
        m, k = self.m, self.m.k
        ax, ay = m.anchor(self.slot)
        f = 1 if font == "S" else 2
        ascii_img, where = Mock._ascii
        for ch in s:
            if ch == " ":
                self.pen += 4 * f
                continue
            cx, cy = where[ch]
            raw = ascii_img.crop((cx * 8, cy * 8, cx * 8 + 8, cy * 8 + 8))
            cell = raw.resize((8 * f * k, 8 * f * k), Image.NEAREST)
            top = (ay - 7 * f) * k
            m.put(m.tint(cell, m.pal["SHADOW"]), (ax + self.pen + f) * k, top + f * k)
            m.put(m.tint(cell, m.pal[p]), (ax + self.pen) * k, top)
            self.pen += int(0.5 + ink_width(raw) * f) + 1
        return self

    def text_right(self, x, font, s, p="WHITE"):
        return self.at(x - self.width(font, s) + 1).text(font, s, p)

    def ink(self, name):
        return self.m.img_by[name][3] if name in self.m.img_by else 0

    def img(self, name, p="WHITE"):
        m, k = self.m, self.m.k
        if name not in m.img_by:
            return self
        n, ch, adv, ink, h, asc = m.img_by[name]
        ax, ay = m.anchor(self.slot)
        im = Image.open(io.BytesIO(OUT.files[f"{TEX}/{IMAGE_FILE[name]}.png"]))
        im = im.resize((max(1, round(im.width * h / im.height * k)), h * k), Image.LANCZOS)
        m.put(m.tint(im, m.pal[p]), (ax + self.pen) * k, (ay - asc) * k)
        self.pen += adv
        return self

    def rect(self, kind, w, p):
        asc = SHAPES[f"{kind}_1"][3]
        inkh = next(r[2] for r in RECTS if r[0] == kind)
        ax, ay = self.m.anchor(self.slot)
        k = self.m.k
        col = tuple(int(c * 255) for c in self.m.pal[p][:3]) + (int(self.m.pal[p][3] * 255),)
        self.m.put(Image.new("RGBA", (w * k, inkh * k), col), (ax + self.pen) * k, (ay - asc) * k)
        self.pen += w
        return self

    def phrase(self, key, p="WHITE"):
        ph = self.m.ph.get(key)
        if ph is None:
            return self
        x0 = self.pen
        for part, x in zip(ph[2], ph[3]):
            self.at(x0 + x).img(part, p)
        return self.at(x0 + ph[4])

    def pw(self, key):
        ph = self.m.ph.get(key)
        return ph[4] if ph else 0

    def color(self):
        return self.m.slot[self.slot][5] or WHITE

    def bar(self, name, fill, track=True, color=None):
        m, k = self.m, self.m.k
        w, h, pad = BARS[name]
        asc = SHAPES[name][3]
        ax, ay = m.anchor(self.slot)
        col = color or self.color()
        if self.m.slot[self.slot][6] & NOTRACK:
            track = False
        arr = np.zeros((h * k, w * k, 4), np.float32)
        lit = int(round(fill * w))
        for x in range(w):
            for y in range(h):
                border = x in (0, w - 1) or y in (0, h - 1)
                if border:
                    if x < lit or track:
                        c = EDGE
                    else:
                        continue
                elif x < lit:
                    s = 0.74 if y >= h - 2 else 1.0
                    c = (col[0] * s, col[1] * s, col[2] * s, col[3])
                elif track:
                    c = TRACK
                else:
                    continue
                arr[y * k:(y + 1) * k, x * k:(x + 1) * k] = c
        m.put(Image.fromarray((arr * 255).astype(np.uint8), "RGBA"), (ax + self.pen) * k, (ay - asc) * k)
        self.pen += SHAPES[name][1]
        return self

    def ring(self, name, fill, color=None):
        m, k = self.m, self.m.k
        size = SHAPES[name][2]
        asc = SHAPES[name][3]
        r0 = (RING_A if name == "ring_a" else RING_B)[0]
        ax, ay = m.anchor(self.slot)
        n = size * k
        yy, xx = np.mgrid[0:n, 0:n]
        cx = (xx + 0.5) / n * 2 - 1
        cy = (yy + 0.5) / n * 2 - 1
        r = np.hypot(cx, cy)
        ang = (np.arctan2(cx, -cy) / (2 * np.pi)) % 1
        band = (r >= r0) & (r <= 0.97)
        col = np.zeros((n, n, 4), np.float32)
        col[band] = TRACK
        col[band & (ang < fill)] = color or self.color()
        m.put(Image.fromarray((col * 255).astype(np.uint8), "RGBA"), (ax + self.pen) * k, (ay - asc) * k)
        self.pen += SHAPES[name][1]
        return self

    def hit(self):
        m, k = self.m, self.m.k
        ax, ay = m.anchor(self.slot)
        n = 24 * k
        yy, xx = np.mgrid[0:n, 0:n]
        cx, cy = (xx + 0.5) / n * 2 - 1, (yy + 0.5) / n * 2 - 1
        q1, q2 = np.abs(cx + cy) / 1.414, np.abs(cx - cy) / 1.414
        msk = ((q2 < 0.085) & (q1 > 0.46) & (q1 < 0.90)) | ((q1 < 0.085) & (q2 > 0.46) & (q2 < 0.90))
        hit = np.zeros((n, n, 4), np.float32)
        hit[msk] = self.color()
        m.put(Image.fromarray((hit * 255).astype(np.uint8), "RGBA"), (ax - 12) * k, (ay - 12) * k)
        return self

    def ptr(self, deg):
        """방향 화살표(셰이더 POLAR 흉내): 타원 둘레 deg(위 0, 시계 방향) 자리에 바깥을 가리키는 세모"""
        m, k = self.m, self.m.k
        ax, ay = m.anchor(self.slot)
        a = math.radians(deg)
        px, py = ax + math.sin(a) * PTR_R[0] * m.W, ay - math.cos(a) * PTR_R[1] * m.H
        pts = []
        for lx, ly in ((0, -5.2), (3.7, 4.0), (0, 1.3), (-3.7, 4.0)):
            pts.append(((px + lx * math.cos(a) - ly * math.sin(a)) * k, (py + lx * math.sin(a) + ly * math.cos(a)) * k))
        d = ImageDraw.Draw(m.img)
        c = self.color()
        d.polygon(pts, fill=tuple(int(v * 255) for v in c[:3]) + (255,), outline=(0, 0, 0, 200))
        return self

    def cover_pal(self, key, p):
        """덮개를 팔레트 p 로(옅은 화면 테)"""
        m, k = self.m, self.m.k
        f = max(m.W / COVER_W, m.H / COVER_H)
        for t in range(COVER_COLS * COVER_ROWS):
            name = f"cv_{key}_{t}"
            if name not in m.img_by:
                continue
            r, c = divmod(t, COVER_COLS)
            im = Image.open(io.BytesIO(OUT.files[f"{TEX}/{IMAGE_FILE[name]}.png"]))
            x = m.W / 2 + (COVER_X0 + COVER_TW * c) * f
            y = m.H / 2 + (-COVER_H / 2 + COVER_TH * r) * f
            im = im.resize((round(COVER_TW * f * k), round(COVER_TH * f * k)), Image.LANCZOS)
            m.put(m.tint(im, m.pal[p]), x * k, y * k)
        return self

    def cover(self, key):
        """덮개(FIT): 조각을 화면을 덮게 키워 가운데에"""
        m, k = self.m, self.m.k
        f = max(m.W / COVER_W, m.H / COVER_H)
        for t in range(COVER_COLS * COVER_ROWS):
            name = f"cv_{key}_{t}"
            if name not in m.img_by:
                continue
            r, c = divmod(t, COVER_COLS)
            im = Image.open(io.BytesIO(OUT.files[f"{TEX}/{IMAGE_FILE[name]}.png"]))
            x = m.W / 2 + (COVER_X0 + COVER_TW * c) * f
            y = m.H / 2 + (-COVER_H / 2 + COVER_TH * r) * f
            im = im.resize((round(COVER_TW * f * k), round(COVER_TH * f * k)), Image.LANCZOS)
            m.put(im, x * k, y * k)
        return self


def mock_scene(gui, scene, bg=None):
    """대표 화면(Java Hud 합성과 같은 배치). scene: a 전투(웨이브 7, 재난문자, 동료 8줄), b 쓰러짐과 쉬는 시간(동료 접기), c 덮개.
    이름은 실제 작품(에일리언 2) 속 이름"""
    m = Mock(gui, bg=bg)
    L = m.line
    if scene == "c":
        L("CV0").cover("test")
    # 맨 위 가운데
    if scene in ("a", "c"):
        L("WV_BAR").bar("bar_phase", 23 / 40)
        L("WV_NUM").at(-(L("WV_NUM").width("M", "7") - 1) // 2).text("M", "7", "WHITE")
        L("WV_LEFT").text("S", "23", "GRAY")
        nt = L("NT")
        w = 14 + 4 + nt.pw("ev_fog") + 6 + 5 * 6 - 1
        x0 = -(w // 2)
        nt.at(x0 - 4).rect("band", w + 8, "PANEL_ALERT")
        nt.at(x0 - 4).rect("band", 18, "ALERT")
        nt.at(x0).img("alert", "WHITE")
        nt.at(x0 + 18).phrase("ev_fog", "WHITE")
        px = x0 + 18 + nt.pw("ev_fog") + 6
        for i in range(5):
            nt.at(px + 6 * i).img("pip", "ORANGE" if i < 3 else "GHOST")
    else:
        L("WV_BAR_TIME").bar("bar_phase", 0.18)
        L("WV_NUM").at(-(L("WV_NUM").width("M", "8") - 1) // 2).text("M", "8", "GRAY")
        L("WV_LEFT").text("S", "4", "GOLD")
        nt = L("NT")
        w = 14 + 4 + nt.pw("wave_clear")
        x0 = -(w // 2)
        nt.at(x0 - 5).rect("band", w + 10, "PANEL")
        nt.at(x0).img("bell", "GREEN")
        nt.at(x0 + 18).phrase("wave_clear", "WHITE")
    # 오른쪽 위: 재화
    money = "1250"
    ml = L("MONEY")
    mw = ml.width("M", money)
    ml.text_right(0, "M", money, "COIN")
    ml.at(-(mw - 1) - 3 - ml.ink("coin")).img("coin", "WHITE")
    L("MONEY_GAIN").text_right(0, "S", "+35", "GREEN")
    if scene in ("a", "c"):
        for i, sp in enumerate(("g02", "g01")):
            fl = L(f"FS{i}")
            icon = f"ic_m_{sp}" if f"ic_m_{sp}" in m.img_by else "ic_m_default"
            fl.at(-fl.ink(icon)).img(icon, "WHITE")
            fl.at(-fl.ink(icon) - 3 - fl.pw(f"name_{sp}")).phrase(f"name_{sp}", "WHITE")
    # 왼쪽 위: 동료
    if scene in ("a", "c"):
        rows = [("Hicks", "down"), ("Vasquez", "trap"), ("Hudson", "dead"), ("Ripley", "me"), ("Bishop", "alive"), ("Gorman", "alive"),
                ("Drake", "grab"), ("Apone", "alive")]
    else:
        rows = [("Hicks", "down"), ("Vasquez", "revived"), ("count", "6/12")]
    for i, (nm, st) in enumerate(rows):
        tl = L(f"TM{i}")
        if nm == "count":
            tl.img("person", "WHITE")
            tl.at(10).text("S", st, "WHITE")
            continue
        p = {"down": "RED_BLINK", "trap": "PURPLE", "dead": "DIM", "me": "GOLD", "alive": "WHITE", "grab": "PURPLE_BLINK", "revived": "GREEN"}[st]
        tl.at(0)
        nw = tl.width("S", nm)
        tl.text("S", nm, p)
        if st == "dead":
            tl.at(-1).rect("strike", nw, "STRIKE")
        if st == "down":
            L(f"TMB{i}").at(nw + 2).bar("bar_mini", 0.6)
        if st == "trap":
            tl.at(nw + 2).img("ic_s_g02" if "ic_s_g02" in m.img_by else "ic_s_default", "WHITE")
    # 왼쪽 아래: 체력
    if scene in ("a", "c"):
        L("HP_HURT").at(0).bar("bar_hp", 80 / 100)
        L("HP").at(0).bar("bar_hp", 62 / 100)
        L("HP_NUM").text("S", "62", "GRAY")
    else:
        L("HP_HURT").at(0).bar("bar_hp", 0)
    # 오른쪽 아래: 총
    if scene in ("a", "c"):
        icon = "gun_l_rifle"
        gl = L("W_GUN")
        gi = gl.ink(icon)
        gl.at(-gi).img(icon, "WHITE")
        lv = "+3"
        lw = gl.width("S", lv)
        gl.at(-gi - 3 - lw + 1).text("S", lv, "GOLD")
        ix = -gi - 3 - lw - 7
        cw = gl.width("S", "2")
        gl.at(ix - cw + 1).text("S", "2", "WHITE")
        gl.at(ix - cw - 1 - gl.ink("it_molotov")).img("it_molotov", "WHITE")
        L("W_UL").at(-gi).rect("uline", gi, "R_LEGENDARY")
        L("W_MAG").text_right(0, "M", "7", "RED")
        L("W_RES").text("S", "120", "GRAY")
    # 아래 가운데: 상점(쉬는 시간)
    if scene == "b":
        sl = L("SHOP")
        sw = sl.ink("shop") + 8 + sl.ink("card") + 2 + sl.width("S", "2")
        x0 = -(sw // 2)
        sl.at(x0).img("shop", "GOLD")
        sl.at(x0 + sl.ink("shop") + 8).img("card", "WHITE")
        sl.text("S", "2", "WHITE")
    # 가운데
    if scene in ("a", "c"):
        L("PTR_DOWN").ptr(40)
        L("PTR_GRAB").ptr(205)
        L("PTR_WAVE").ptr(90)
        L("PTR_WAVE").ptr(270)
        L("C_HIT").hit()
        ml = L("MARK")
        ml.img("ic_s_g01" if "ic_s_g01" in m.img_by else "ic_s_default", "RED_BLINK")
        mt = L("METER")
        mt.img("ic_s_g02" if "ic_s_g02" in m.img_by else "ic_s_default", "WHITE")
        mt.at(12).bar("bar_meter", 0.4)
    else:
        L("PTR_DOWN").ptr(130)
        L("C_BLEED_HOLD").at(-24).ring("ring_b", 0.55)
        L("C_REVIVE").at(-20).ring("ring_a", 0.35)
        sp = "g01"
        icon = f"ic_l_{sp}" if f"ic_l_{sp}" in m.img_by else "ic_l_default"
        cl = L("CARD")
        nw, cw2 = cl.pw(f"name_{sp}"), cl.pw(f"cause_{sp}")
        tw = max(nw, cw2)
        iw = cl.ink(icon)
        w = iw + 5 + tw
        x0 = -(w // 2)
        cl.at(x0 - 6).rect("plate", w + 12, "PANEL")
        cl.at(x0).img(icon, "WHITE")
        cl.at(x0 + iw + 5).phrase(f"name_{sp}", "WHITE")
        L("CARD2").at(x0 + iw + 5).phrase(f"cause_{sp}", "RED")
    m.vanilla()
    return m


def mock_boss(gui, scene, bg=None):
    """보스 배치(Java Hud 의 보스 줄과 같은 배치, 2026-11-12 boss_infra). d 보스 싸움(보스 틀, 틀 아래 재난문자 알림, 노림 아이콘, 약점 적중,
    긴 이름 동료 8줄, 처음 보는 종), e 등장(레터박스, 큰 제목, 부제, 방향 알림), f 엔딩 카드, g 보스 앞 쉬는 시간(다음 웨이브 빨강 + 보스 아이콘).
    이름이 가장 긴 보스(하늘 덮는 게이브)로 그린다. 사람 이름은 실제 작품(에일리언 2) 속 이름"""
    m = Mock(gui, bg=bg)
    L = m.line
    boss = "b2"
    ic_s = f"ic_s_{boss}" if f"ic_s_{boss}" in m.img_by else "ic_boss"
    ic_m = f"ic_m_{boss}" if f"ic_m_{boss}" in m.img_by else "ic_boss"
    if scene == "e":
        L("CV0").cover("letterbox")
    num = "35" if scene == "f" else "20"
    wl = L("WV_NUM")
    nw = wl.width("M", num) - 1
    if scene == "g":
        # 쉬는 시간: 남은 시간 막대, 다음 웨이브 숫자 빨강 + 보스 아이콘, 알림 '큰 것이 온다'
        L("WV_BAR_TIME").bar("bar_phase", 0.4)
        wl.at(-(nw // 2)).text("M", num, "RED")
        wl.at(nw - nw // 2 + 4).img(ic_s, "WHITE")
        nt = L("NT")
        iw = nt.ink(ic_m)
        w = iw + 4 + nt.pw("bs_next")
        x0 = -(w // 2)
        nt.at(x0 - 5).rect("band", w + 10, "PANEL")
        nt.at(x0).img(ic_m, "WHITE")
        nt.at(x0 + iw + 4).phrase("bs_next", "WHITE")
    else:
        wl.at(-(nw // 2)).text("M", num, "RED")
    if scene in ("d", "f"):
        # 보스 틀: 깎인 흰 몫 위에 빨간 지금 몫, 문턱 눈금, 단계 점, 이름
        frac, chip = (0.0, 0.0) if scene == "f" else (0.58, 0.66)
        L("BS_HURT").at(0).bar("bar_boss", chip)
        L("BS_BAR").at(0).bar("bar_boss", frac)
        mk = L("BS_MARK")
        for c in (0.65, 0.30):
            mk.at(round(BOSS_W * c)).img("bs_notch", "WHITE")
        ph = L("BS_PH")
        for i in range(3):
            ph.at(6 * i).img("pip", "WHITE" if i < 2 else "GHOST")
        nl = L("BS_NAME")
        nl.at(-(nl.pw(f"name_{boss}") // 2)).phrase(f"name_{boss}", "GRAY" if scene == "f" else "WHITE")
    if scene == "d":
        # 틀 아래 알림 띠(NT_LOW): 단계 알림은 재난문자 꼴
        key = "bs_dummy_p2"
        nt = L("NT_LOW")
        lead, pips = 18, 6 + 5 * 6 - 2
        w = lead + nt.pw(key) + pips
        x0 = -(w // 2)
        nt.at(x0 - 4).rect("band", w + 8, "PANEL_ALERT")
        nt.at(x0 - 4).rect("band", lead, "ALERT")
        nt.at(x0 - 4 + (lead - nt.ink("alert")) // 2).img("alert", "WHITE")
        nt.at(x0 + lead).phrase(key, "WHITE")
        for i in range(5):
            nt.at(x0 + lead + nt.pw(key) + 6 + 6 * i).img("pip", "ORANGE" if i < 4 else "GHOST")
    if scene == "e":
        # 등장: 방향 알림 '온다'(NT) + 주황 화살촉, 화면 3분의 1 높이에 큰 제목(빨강)과 부제
        nt = L("NT")
        w = nt.pw("bs_come")
        nt.at(-(w // 2) - 5).rect("band", w + 10, "PANEL")
        nt.at(-(w // 2)).phrase("bs_come", "WHITE")
        L("PTR_WAVE").ptr(300)
        tl = L("BS_TITLE")
        tl.at(-(tl.pw(f"t_{boss}") // 2)).phrase(f"t_{boss}", "RED")
        sl = L("BS_SUB")
        sl.at(-(sl.pw("bs_dummy_sub") // 2)).phrase("bs_dummy_sub", "WHITE")
    if scene == "f":
        # 엔딩 카드: 큰 제목(금색), 부제, 웨이브 + 판 시간 + 사람 수
        tl = L("BS_TITLE")
        tl.at(-(tl.pw("t_end") // 2)).phrase("t_end", "GOLD")
        sl = L("BS_SUB")
        sl.at(-(sl.pw("end_sub") // 2)).phrase("end_sub", "WHITE")
        el = L("END_NUM")
        wv, tm, n = "35", "58:07", "30"
        ww, tw, pw_, pi = el.width("M", wv) - 1, el.width("M", tm) - 1, el.width("M", n) - 1, el.ink("person")
        gap = 14
        x = -((ww + gap + tw + gap + pi + 3 + pw_) // 2)
        el.at(x).text("M", wv, "GOLD")
        x += ww + gap
        el.at(x).text("M", tm, "GOLD")
        x += tw + gap
        el.at(x).img("person", "GOLD")
        el.at(x + pi + 3).text("M", n, "GOLD")
    # 오른쪽 위: 재화(다섯 자리), 처음 보는 종 둘
    money = "12500"
    ml = L("MONEY")
    mw = ml.width("M", money)
    ml.text_right(0, "M", money, "COIN")
    ml.at(-(mw - 1) - 3 - ml.ink("coin")).img("coin", "WHITE")
    if scene in ("d", "e"):
        for i, sp in enumerate(("g39", "g30")):
            fl = L(f"FS{i}")
            icon = f"ic_m_{sp}" if f"ic_m_{sp}" in m.img_by else "ic_m_default"
            fl.at(-fl.ink(icon)).img(icon, "WHITE")
            fl.at(-fl.ink(icon) - 3 - fl.pw(f"name_{sp}")).phrase(f"name_{sp}", "WHITE")
    # 왼쪽 위: 동료 8줄(16자 이름 + 출혈 막대가 보스 틀과 닿는지 보려고 둘째, 셋째 줄에)
    rows = [("Hicks", "me"), ("PfcWilliamHudson", "down"), ("PvtRicoFrost_USCM", "down"), ("Vasquez", "trap"), ("Ripley", "alive"), ("Bishop", "alive"),
            ("CplDwayneHicks_2", "down"), ("Apone", "alive")]
    for i, (nm, st) in enumerate(rows):
        nm = nm[:16]
        tl = L(f"TM{i}")
        p = {"down": "RED_BLINK", "trap": "PURPLE", "me": "GOLD", "alive": "WHITE"}[st]
        tl.at(0)
        w = tl.width("S", nm)
        tl.text("S", nm, p)
        if st == "down":
            L(f"TMB{i}").at(w + 2).bar("bar_mini", 0.6)
        if st == "trap":
            tl.at(w + 2).img("ic_s_g02" if "ic_s_g02" in m.img_by else "ic_s_default", "WHITE")
    # 왼쪽 아래 체력, 오른쪽 아래 총
    L("HP_HURT").at(0).bar("bar_hp", 0.8)
    L("HP").at(0).bar("bar_hp", 0.62)
    L("HP_NUM").text("S", "62", "GRAY")
    icon = "gun_l_rifle"
    gl = L("W_GUN")
    gi = gl.ink(icon)
    gl.at(-gi).img(icon, "WHITE")
    L("W_MAG").text_right(0, "M", "24", "WHITE")
    L("W_RES").text("S", "120", "GRAY")
    if scene == "d":
        # 가운데: 약점 적중(금색), 노림 아이콘(빨강 깜빡), 약점 화살촉, 한 줄 도움말
        L("C_WEAK").hit()
        L("MARK").img(ic_s, "RED_BLINK")
        L("PTR_POINT").ptr(20)
        hl = L("HINT")
        hl.at(-(hl.pw("hint_bs_weak") // 2)).phrase("hint_bs_weak", "WHITE")
    m.vanilla()
    return m


def mock_vote(tl, kinds, votes, mine=0, pick=0, left=0):
    """길 투표 판(Java Hud.votePanel 과 같은 배치): 칸마다 [판][번호 M][갈래 종류 글귀][표 수 S], 끝에 남은 초. 내 칸(정해지면 뽑힌 칸)은 금빛 띠와 금빛 글"""
    n = len(kinds)
    label = [f"rt_type_{k}" if f"rt_type_{k}" in tl.m.ph else k for k in kinds]
    count = [str(v) for v in votes]
    num = [str(i + 1) for i in range(n)]
    inner = 0
    for i in range(n):
        lw = tl.pw(label[i]) if label[i] in tl.m.ph else tl.width("S", label[i])
        inner = max(inner, tl.width("M", num[i]) + 4 + lw + 5 + tl.width("S", count[i]))
    cell, gap = inner + 10, 5
    time = str(left) if left and not pick else None
    total = n * cell + (n - 1) * gap + (gap + 2 + tl.width("S", time) if time else 0)
    x = -(total // 2)
    lit = pick or mine
    for i in range(n):
        me, gone = lit == i + 1, pick and lit != i + 1
        main = "DIM" if gone else "GOLD" if me else "WHITE"
        sub = "DIM" if gone else "GOLD" if me else "GRAY"
        tl.at(x).rect("band", cell, "PANEL")
        if me:
            tl.at(x).rect("band", 2, "GOLD")
        tl.at(x + 5).text("M", num[i], main)
        tl.at(tl.pen + 3)
        if label[i] in tl.m.ph:
            tl.phrase(label[i], sub)
        else:
            tl.text("S", label[i], sub)
        tl.at(x + cell - 5 - tl.width("S", count[i]) + 1).text("S", count[i], "DIM" if count[i] == "0" else sub)
        x += cell + gap
    if time:
        tl.at(x + 2).text("S", time, "GRAY")
    return tl


def mock_round(gui, scene, bg=None):
    """원정 라운드 목표(Java Hud.goal, roundBand 와 같은 배치): m 라운드 시작 띠(목표 아이콘 + 이름, 그 아래 한 줄) + 금빛 목표 막대 + 오른쪽 3/6 + 짐 체력,
    n 버티기(줄어드는 시간 막대 + 남은 초) + 옅은 화면 테(덮개 하나를 옅게)"""
    m = Mock(gui, bg=bg)
    L = m.line
    if scene == "n":
        veil = next((c for c in ("g37_cloth", "g26_thread", "dark") if f"cv_{c}_0" in m.img_by), None)
        if veil:
            # 옅게: IMG_DIM(알파 0.5)
            L("VL0").cover_pal(veil, "IMG_DIM")
    wl = L("WV_NUM")
    num = "14"
    wl.at(-((wl.width("M", num) - 1) // 2)).text("M", num, "WHITE")
    kind = "collect" if scene == "m" else "survive"
    if scene == "m":
        L("GOAL_BAR").bar("bar_phase", 0.5)
        L("WV_LEFT").text("S", "3/6", "WHITE")
        L("GOAL_CARGO").bar("bar_meter", 0.7)
    else:
        L("GOAL_TIME").bar("bar_phase", 0.62)
        L("WV_LEFT").text("S", "74", "WHITE")
    ic = f"ti_goal_{kind}"
    if ic in m.img_by:
        g = L("GOAL_IC")
        g.at(-g.ink(ic)).img(ic, "GOLD")
    if scene == "m":
        name, line = ("rd_r_collect", "rd_r_collect_l")
        nm = L("NT")
        pw = nm.pw(name) if name in m.ph else 0
        iw = nm.ink(ic) if ic in m.img_by else 0
        w = (iw + 4 if iw else 0) + pw
        x0 = -(w // 2)
        nm.at(x0 - 5).rect("band", w + 10, "PANEL")
        if iw:
            nm.at(x0).img(ic, "GOLD")
        nm.at(x0 + (iw + 4 if iw else 0)).phrase(name, "WHITE")
        if line in m.ph:
            ll = L("NT_LOW")
            ll.at(-(ll.pw(line) // 2)).phrase(line, "GRAY")
    L("HP_HURT").bar("bar_hp", 0.8, color=WHITE)
    L("HP").bar("bar_hp", 0.7)
    L("TRK_HURT").bar("bar_hp", 0.6, color=WHITE)
    L("TRK_BAR").bar("bar_hp", 0.6)
    if "ti_truck" in m.img_by:
        L("TRK_ICON").img("ti_truck", "WHITE")
    return m


def mock_trip(gui, scene, bg=None):
    """원정 배치(Java Hud.trip 과 같은 배치, 2026-11-12 trip_core). h 보스 싸움 중 트럭이 맞는 화면(보스 틀 + 트럭 막대와 아이콘 + 트럭 쪽 화살촉 +
    스테이지 표시 + 체력, 무기), i 쉬는 시간 길 투표(집계 줄, 스테이지 이름, 트럭 막대), j 죽은 사람 화면(실눈 덮개, 모금 막대, 글), k 검은 화면 가운데 글 한 줄(가장 긴 엔딩 줄)"""
    m = Mock(gui, bg=bg)
    L = m.line
    if scene == "k":
        L("CV0").cover("black")
        key = "ending_4" if "ending_4" in m.ph else next(iter(m.ph))
        st = L("STORY")
        st.at(-(st.pw(key) // 2)).phrase(key, "WHITE")
        return m
    if scene == "j":
        L("CV0").cover("slit")
        L("RV_BAR").bar("bar_meter", 0.5)
        key = "trip_trapped" if "trip_trapped" in m.ph else "trapped"
        rt = L("RV_TXT")
        rt.at(-(rt.pw(key) // 2)).phrase(key, "GRAY")
    num = "20" if scene == "h" else "12"
    wl = L("WV_NUM")
    nw = wl.width("M", num) - 1
    wl.at(-(nw // 2)).text("M", num, "RED" if scene == "h" else "WHITE")
    # 스테이지 표시: 남은 스테이지 수(작은 숫자) + 트럭 아이콘
    tg = L("ST_TAG")
    if "ti_truck" in m.img_by:
        tg.img("ti_truck", "GRAY")
        tg.at(tg.ink("ti_truck") + 3)
    tg.text("S", "9", "GRAY")
    if scene == "h":
        boss = "b2"
        L("BS_HURT").bar("bar_boss", 0.66, color=WHITE)
        L("BS_BAR").bar("bar_boss", 0.58)
        nm = L("BS_NAME")
        key = f"name_{boss}" if f"name_{boss}" in m.ph else "bs_next"
        nm.at(-(nm.pw(key) // 2)).phrase(key, "WHITE")
        L("PTR_TRUCK").ptr(205)
    if scene in ("i", "l"):
        L("WV_BAR_TIME").bar("bar_phase", 0.6)
        # 길 투표 판(숫자키 1, 2, 3): i 는 표를 받는 중(내 표 2, 남은 13초), l 은 정해진 뒤(3 이 뽑힘, 보스 길목)
        if scene == "i":
            mock_vote(L("TALLY"), ["fight", "cache", "trap"], [12, 9, 6], mine=2, left=13)
        else:
            mock_vote(L("TALLY"), ["rest", "unknown", "gate"], [4, 0, 17], mine=1, pick=3)
        sn = L("ST_NAME")
        key = "trip_truck_dying" if "trip_truck_dying" in m.ph else "bs_next"
        if scene == "i":
            sn.at(-(sn.pw(key) // 2)).phrase(key, "WHITE")
    if scene != "j":
        L("HP_HURT").bar("bar_hp", 0.8, color=WHITE)
        L("HP").bar("bar_hp", 0.7)
    # 트럭 체력: 흰 깎인 몫 위에 지금 몫, 왼쪽에 아이콘
    frac, chip = (0.22, 0.30) if scene == "h" else (0.74, 0.74)
    L("TRK_HURT").bar("bar_hp", chip, color=WHITE)
    L("TRK_LOW" if frac < 0.25 else "TRK_BAR").bar("bar_hp", frac)
    if "ti_truck" in m.img_by:
        L("TRK_ICON").img("ti_truck", "GOLD" if scene in ("i", "l") else "WHITE")
    return m


def mock(path, bg=None):
    """배치 그림: a, b, c 장면과 보스 장면 d, e, f, g x 427x240, 480x270 → <이름>_<장면>_<폭>.png"""
    p = Path(path)
    out = []
    for scene in ("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n"):
        for gui in ((427, 240), (480, 270)):
            if scene == "c" and gui[0] == 480:
                continue
            f = p.with_name(f"{p.stem}_{scene}_{gui[0]}{p.suffix or '.png'}")
            (mock_round if scene in "mn" else mock_trip if scene in "hijkl" else mock_boss if scene in "defg" else mock_scene)(gui, scene, bg).save(f)
            out.append(f)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview")
    ap.add_argument("--mock")
    ap.add_argument("--mock-bg")
    ap.add_argument("--no-check", action="store_true")
    ap.add_argument("--check-only", action="store_true", help="임시 사본에서 셰이더 검사만(pack_src, HudSpec 안 씀)")
    a = ap.parse_args()
    if a.no_check and (not a.mock or a.check_only):
        ap.error("--no-check 는 --mock 과만 쓴다(배치 그림만 그리고 pack_src, HudSpec 은 쓰지 않음)")
    tables = text_fonts()
    shapes()
    rects()
    made, missing = images()
    n_icon, n_noicon = species_icons()
    n_cover = covers()
    n_phrase, empty_phrase = phrases()
    asc = [i[5] for i in IMAGES] + [v[3] for v in SHAPES.values()] + [14]
    desc = [i[4] - i[5] for i in IMAGES] + [v[2] - v[3] for v in SHAPES.values()] + [2]
    band_top, band_bot = 10 - max(asc) - 4, 10 + max(desc) + 4
    OUT.put("assets/gave/font/hud.json", json.dumps({"providers": [space_provider()] + PROVIDERS}, indent=1, ensure_ascii=True))
    shaders(band_top, band_bot)
    if a.preview:
        preview(a.preview)
    if a.mock:
        for f in mock(a.mock, bg=a.mock_bg):
            print("배치 그림:", f)
        if a.no_check:
            return
    with Lock():
        if not a.no_check:
            stage = Path(tempfile.mkdtemp(prefix="hudstage-", dir=ROOT / "build"))
            try:
                shutil.copytree(PACK, stage, dirs_exist_ok=True)
                clean(stage)
                write_to(stage)
                ok, tail = check(stage)
            finally:
                shutil.rmtree(stage, ignore_errors=True)
            print(tail)
            if not ok:
                raise SystemExit("셰이더 검사 실패: pack_src 에 쓰지 않았다")
            if a.check_only:
                print(f"검사만: 그림 글리프 {len(IMAGES)}, 띠 {band_top}..{band_bot}")
                return
        clean(PACK)
        write_to(PACK)
        JAVA_OUT.parent.mkdir(parents=True, exist_ok=True)
        tmp = JAVA_OUT.with_name(JAVA_OUT.name + ".tmp")
        tmp.write_text(java(tables), encoding="utf-8", newline="\n")
        os.replace(tmp, JAVA_OUT)
    print(f"HUD 층: 파일 {len(OUT.files)}개, 그림 글리프 {len(IMAGES)}, 모양 묶음 {len(SHAPES)}, 띠 {band_top}..{band_bot}")
    print("총 실루엣:", len(made), "| 없는 그림(Java 가 건너뜀):", ", ".join(missing) or "없음")
    print(f"종 아이콘: {n_icon}종(없는 {n_noicon}종은 기본 얼굴) | 덮개: {n_cover}개({', '.join(COVERS)})")
    split = [p[0] for p in PHRASES if len(p[2]) > 1]
    print(f"한국어 글귀: {n_phrase}개" + (f", 나눠 그린 것 {', '.join(split)}" if split else "")
          + (f" | 빈 글이라 뺀 키: {', '.join(empty_phrase)}" if empty_phrase else ""))
    print("플러그인 표:", JAVA_OUT.relative_to(ROOT).as_posix(), "(python -X utf8 tools/compile_wip.py --name hud --promote 로 옮길 것)")


if __name__ == "__main__":
    main()
