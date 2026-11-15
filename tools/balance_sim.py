"""게이브 밸런스 모의(떼와 절망 docs/모듈/떼와절망.md '밸런스', 35웨이브 엔딩 파워커브 docs/모듈/파워커브.md).

사람 화력(총 피해, 연사, 탄창, 재장전, 명중, 상점으로 크는 정도) 대 몰려오는 체력과 도착 속도를 간단히 굴려
인원(1, 3, 5, 10, 20, 30)과 솜씨(명중)마다 도달 웨이브(전멸) 분포, 엔딩(마지막 보스 처치) 도달 비율, 판 길이,
웨이브별 버팀(걸린 시간, 쓰러짐, 남은 체력, 번 돈, 무기와 강화)을 낸다.

숫자는 코드에서 읽는다(환경 변수 GV_SIM_WIP=build/wip/<이름> 을 주면 그 wip 폴더의 파일을 먼저):
  Director.Tune(총량, 동시 수, 박자, 체력, 빠르기, 피해, 보상, 난이도 손잡이 DIFFICULTY, 톱니 PRE_BOSS/POST_BOSS, 끝없는 구간 ENDLESS_*, 변이 차례 MUT_*,
  보스 차례 BOSS_WAVES + BOSS_EVERY), 설정 config.yml difficulty(게임처럼 0 보다 크면 DIFFICULTY 를 덮음), BossDirector.ORDER 와 BossDirector.Tune(체력 표 H, H_AT,
  화력 직선, 사람 수 몫, 회차, 보스 웨이브 떼, 보상), Trip.Tune(트럭, 수리, 꺼내기 숫자), Players.DEATH_LOSS, tools/gen_species.py TUNE(체력, 점수, 보상),
  Guns.SPECS(피해, 연사, 탄창, 재장전, 사거리), Shop.Ware(값 표), Gun.odds/RANGE/BOX(등급, 상자 종류 가중치), Mobs.BUDGET.
코드에 없는 것은 세 표에 모았다: SIM(모의 가정), CURVE(곡선과 원정 구조: 코드에 있는 손잡이는 load() 가 코드 값으로 덮고, 스테이지 갈래 표와 건조 방어처럼 아직 코드에
없는 것은 파워커브.md 의 값을 기본으로 둠), BOSS(보스 싸움 가정: 공식의 숫자는 코드에서 읽음).
그래서 값 파일 없이 돌리면 지금 코드의 곡선 그대로다(2026-11-12 curve_apply: build/curve35/proposed.json 의 숫자가 코드에 들어감. 옛 숫자는 build/curve_apply/bal12.json).

값을 덮어써 돌리기(지금 코드를 고치지 않고 제안 숫자를 시험):
  --set "DMG_BASE=0.9,G01.hp=20,PISTOL.dmg=14,slots=8,curve.ENDING_WAVE=35,boss.units=0.5,ware.BOX=130/0.05/1"
  --set-file build/curve35/proposed.json   ({"tune": {..}, "curve": {..}, "boss": {..}, "ware": {"BOX": [130, 0.05, 1]}, "sim": {..},
                                             "species": {"G01": {"hp": 13}}, "gun": {"PISTOL": {"dmg": 14}}, "odds": [[3, [70, 25, 5, 0]], ..],
                                             "caps": {"MAX_LEVEL": 20, "HP": 20, "ARMOR": 14}})
  --compare 기준.json,제안.json   두 값 묶음을 나란히(이름 '-' 는 지금 코드 그대로)

python -X utf8 tools/balance_sim.py [--seeds 12] [--people 1,3,5,10,20,30] [--skill 0.5,0.7,0.85] [--table] [--waves 1,5,10]
                                    [--curve 30] [--bosses] [--jobs 4] [--json 결과.json]

2026-11-12(bal12): 종별 접촉 피해(게임 안 측정값 x 박자 배율), 곁 3블록, 한 사람 곁 8몸, 앞줄 표적, 피하는 몫, 감독 세기, 한 종 쏠림 막기.
2026-11-12(curve35): 보스 웨이브(떼 50% + 보충, 보스 체력과 몰두, 보스 보상), 엔딩 도달 비율, 엔딩 뒤 끝없는 구간, 값 파일, 무기 종류(지금 26)와 상자 가중치(SIM gun 표에 없는 새 종류는 관통으로 어림),
  몹 예산을 코드에서 읽음(옛 200 고정 → Mobs.BUDGET - CHILD_ROOM), 판 길이(분), 붕대, 최후의 저항(끔이 기본).
2026-11-12(curve_apply): 보스 차례를 Director.Tune.BOSS_WAVES(+ BOSS_EVERY, BossDirector.ORDER)에서 읽음(옛 BOSS_EVERY 배수), 감독에 들어간 곡선 손잡이와 보스 체력 표,
  원정 숫자를 코드에서 읽음(read_curve). 엔딩 웨이브 = B5 가 처음 나오는 웨이브.
2026-11-15(gunbal): 총은 모델까지(상자가 그 종류 모델을 고르게 고르고 모델 배율, 등급, 반올림 갚기를 Guns.effective 와 같은 식으로: model_spec), 떼(마리 여럿)는
  게임처럼 한 발에 한 마리(넘친 피해는 버려짐), 폭발과 불만 무리 모두. --horde 는 종류마다 떼 막기 효율(초당 없앤 감독 단위, 버려진 피해 몫)과 보스 처치 시간.
"""
import argparse
import ast
import copy
import json
import math
import os
import random
import re
import statistics
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WIP = ROOT / os.environ["GV_SIM_WIP"] if os.environ.get("GV_SIM_WIP") else None
JAVA = "plugin/src/main/java/kr/gave/"

# ------------------------------------------------------------------ 모의 가정(코드에 없는 것)
SIM_DEFAULT = {
    "dt": 0.5,                 # 초
    "fog": 1,                  # 안개 사건(0 이면 끔, 진단용)
    "start_r": 76.0,           # 나오는 띠 반지름(사람이 중앙 원에 있으면 R_SPAWN_IN + 띠 폭 근처)
    "placed_r": 30.0,          # 미리 깔리는 종 평균 거리
    "walk": 3.0,               # 블록/초(G01 0.15 블록/틱)
    "walk_sky": 4.0,
    "contact": 3.0,            # 이 거리 안이면 사람 곁(때림, 덮치기). 2026-11-12 게임 안 측정 '곁' 4블록 기준
    "ranged": 14.0,            # 원거리 종이 치기 시작하는 거리(G03 12, G06 15~20, G41 16, G52)
    "see": 62.0,               # 안개 속에서 겨눠 쏠 수 있는 거리(기본 흙먼지)
    "slots": 8,                # 한 사람 곁에 동시에 붙어 때릴 수 있는 몸 수(그 밖은 둘레에서 밀려 기다림)
    "front": 0.25,             # 맞는 사람이 몰리는 앞줄 몫(지수 분포 평균 = 서 있는 사람 수 x front)
    # 종 한 개체가 곁에 있을 때 주는 초당 기본 피해(dmgMul, 방어 전, 1웨이브 박자). 2026-11-12 게임 안 측정(build/bal12/표.md, 봇이 서서 맞음)에서
    # 곁 1분 때림 x 한 번 피해 / 60. 없는 종은 위협 등급 값. 떼 종은 무리 하나. 원거리 종은 창 평균
    "dps_sp": {
        # 원거리 종(G03, G06, G20, G41, G52)은 서 있는 봇이 모두 맞은 값(G03 3.1, G52 3.3)을 움직이는 사람 몫으로 줄여 적음(그 위에 ranged_mul)
        "G01": 1.5, "G02": 1.1, "G03": 1.2, "G04": 2.0, "G05": 1.0, "G06": 0.4, "G09": 0.8, "G10": 1.2, "G11": 2.0, "G13": 2.0, "G16": 1.0,
        "G18": 0.5, "G19": 0.6, "G20": 0.7, "G22": 1.2, "G23": 0.0, "G24": 1.0, "G25": 1.3, "G26": 0.6, "G27": 0.5, "G28": 1.4, "G29": 1.6,
        "G30": 1.9, "G31": 1.0, "G32": 0.9, "G33": 0.6, "G34": 2.3, "G37": 0.7, "G38": 0.9, "G39": 0.3, "G40": 0.6, "G41": 0.2, "G42": 0.1,
        "G45": 0.6, "G46": 1.25, "G50": 0.5, "G52": 1.6, "G53": 1.6,
        # 사람을 직접 몰아붙이지 않는 종, 미리 깔려 기다리는 함정 종(사람이 다가가야 침)
        "G07": 0.3, "G08": 0.0, "G12": 0.0, "G14": 0.0, "G15": 0.0, "G17": 0.3, "G21": 0.3, "G35": 0.3, "G36": 0.0, "G43": 0.0, "G44": 0.3,
        "G47": 0.3, "G48": 0.3, "G49": 0.3, "G51": 0.0, "G54": 0.0, "G55": 0.3,
    },
    "dps_threat": {1: 1.2, 2: 1.6, 3: 2.0, 4: 2.6, 5: 3.2},
    "dps_cap": 6.0,            # 측정 잡음(곁에 든 틱이 아주 짧은 종) 막기
    "dodge": 0.75,             # 사람이 움직이며 싸울 때 곁 피해가 정말 들어가는 몫(봇은 서서 맞음 = 1)
    "ranged_mul": 0.5,         # 원거리 종(포물선 침, 던진 막대기)은 피할 수 있어 서서 맞는 값의 절반
    "acq": 0.30,               # 처치 뒤 다음 표적까지(초), 혼자면 등 뒤 돌아보기 몫이 더해짐
    "acq_solo_turn": 0.35,
    "acq_overlap": 0,          # 1 이면 다음 표적 찾기가 그 발의 박자와 겹침(느린 총은 노리쇠를 당기며 옮겨 겨눔). 0 은 박자 뒤에 더함(옛 가정)
    "revive_s": 3.0,
    "bleed_s": 12.0,
    "revive_hp": 0.3,
    "aug_dps": 0.09,           # 증강 하나의 평균 화력 이득
    "members": {"G02": 20, "G04": 1, "G11": 22, "G53": 24, "G45": 7, "G29": 8, "G40": 12},
    # ---- 2026-11-12 curve35
    # 무기 종류: 종류 → [피해 덤(늘 붙는 치명. 불길의 불붙음은 gun_single 이 셈), 떼 한가운데서 한 발에 맞는 마리 수(관통, 폭발, 번짐, 원뿔: 관통 감쇠가 없을 때의 값)].
    # 박자(점사 쉼, 미니건 회전, 레일건 모으기, 산탄총 한 발씩 재장전, 재장전과 쏜 뒤 쉼 겹침)는 코드의 Guns.SPECS, MODES 에서 셈한다(gun_cycle)
    "gun": {
        "PISTOL": [1.0, 1.0], "SMG": [1.0, 1.0], "SHOTGUN": [1.0, 1.3], "RIFLE": [1.0, 1.0], "SNIPER": [1.0, 2.2],
        "FLAME": [1.0, 3.5], "LAUNCHER": [1.0, 3.0], "ROCKET": [1.0, 4.5], "MINIGUN": [1.0, 1.0], "LMG": [1.0, 1.35],
        "REVOLVER": [1.05, 1.6], "BURST": [1.0, 1.0], "DMR": [1.03, 1.6], "RAILGUN": [1.0, 4.0], "TESLA": [1.0, 2.6],
        "FREEZE": [1.0, 3.0], "BOW": [1.0, 2.5],
        # 2026-11-12 wpn_max 가 더한 아홉(산탄 둘, 관통 5 볼트액션, 관통 12 대물 저격총, 관통 3 머스킷과 광선총, 나머지는 한 발에 한 마리)
        "AUTOSHOT": [1.0, 1.3], "DOUBLE": [1.0, 1.3], "BOLTACTION": [1.0, 2.25], "MACHINEPISTOL": [1.0, 1.0], "DUAL": [1.0, 1.0],
        "ANTIMATERIEL": [1.0, 2.6], "SILENCED": [1.05, 1.0], "MUSKET": [1.0, 1.9], "LASER": [1.0, 1.9],
    },
    "crowd_sat": 4.0,          # 표적 둘레(반지름 띠 ±5블록, 각도로 퍼진 만큼 나눔)에 이만큼 있으면 위 '마리 수'가 다 맞음
    "bandage_share": 0.5,      # 웨이브마다 붕대(40 회복, 3초)를 들고 가는 사람 몫(소모품 칸은 하나)
    "bandage_heal": 40.0, "bandage_s": 3.0, "bandage_at": 0.35,
    "last_stand": 0,           # 재미 장치 6 '최후의 저항'(아직 구현 전: 1 이면 켬. 서 있는 사람이 20% 이하면 피해 x1.5, 연사 x1.2, 받는 피해 x0.75, 출혈 멈춤)
    "fun_money": 0.0,          # 재미 장치(현상금, 제물, 저주)가 더하는 1인 벌이 몫(재미.md 14절 어림 0.1~0.2). 0 이면 끔
    "mut_hp": 1.05, "mut_units": 1.06, "mut_dmg": 1.04,  # 변이 하나의 어림(체력, 수, 피해): 감독 변이 여덟 가지를 뭉뚱그림
    "prep_s": 10.0, "break_s": 25.0,
    "crowd_scale": 1.0,        # 위 gun 표의 '마리 수 - 1' 에 곱함(범위 무기 가정의 민감도: 0 이면 모든 무기가 한 발에 한 마리)
    "policy": "smart",         # 스테이지 투표 흉내: smart(트럭, 죽은 사람이 급하면 정비, 잘 풀리면 보상 큰 쪽, 아니면 쉬운 쪽), greedy(보상 큰 쪽), safe(쉬운 쪽), random
    "truck_floor": 0.5,        # 사람을 안 치는 종이 트럭을 칠 때의 초당 피해 하한(위협 등급 값의 몫)
}

# 곡선과 원정 구조의 손잡이. 감독에 들어간 것(보스 차례, 톱니, 끝없는 구간, 변이 차례, REWARD_CROWD, 난이도)과 Trip.Tune 의 트럭, 수리, 꺼내기 숫자는 load() 가
# 코드에서 읽어 덮는다(read_curve). 여기 값은 코드에 그 이름이 없을 때의 기본값이고, 스테이지 갈래 표와 건조 방어는 코드에 아직 없어 파워커브.md 7절 값 그대로 둔다.
# 원정을 뺀 옛 구조로 돌리려면 --set "curve.TRUCK=0,curve.PAID_REVIVE=0,curve.STAGES=null".
CURVE_DEFAULT = {
    "BOSS_WAVES": None,        # 보스 웨이브 차례 [10, 20, 30, 35, 45](Director.Tune.BOSS_WAVES) 또는 옛 값 파일 꼴 {"10": "B1", ..}. 그 뒤는 BOSS_EVERY 마다 같은 차례(회차 +1)
    "BOSS_ORDER": None,        # 그 차례의 보스 ["B1", "B2", "B4", "B5", "B3"](BossDirector.ORDER)
    "ENDING_WAVE": None,       # 이 웨이브 보스를 잡으면 엔딩. None 이면 B5 가 처음 나오는 웨이브(없으면 50)
    "BOSSES": 1,               # 0 이면 보스 없음(보스 웨이브도 보통 웨이브: 보스 클래스가 아직 없는 지금 코드)
    "PRE_BOSS": [],            # 보스 앞 웨이브 총량 배율(보스까지 1, 2, 3 ... 웨이브 남았을 때)
    "POST_BOSS": [],           # 보스 뒤 웨이브 총량 배율(보스 뒤 1, 2 ... 번째)
    "DMG_LATE_FROM": 0, "DMG_PER_WAVE_LATE": 0.0,   # 이 웨이브를 넘으면 웨이브마다 피해 배율에 더 얹는 값
    "ENDLESS_FROM": 0,         # 끝없는 구간 첫 웨이브(0 이면 끔)
    "ENDLESS_DMG": 1.0, "ENDLESS_UNITS": 1.0,       # 끝없는 구간에서 웨이브마다 곱하는 피해, 총량(체력은 Tune.HP_LATE_FROM, HP_GROWTH_LATE)
    "MUT_FROM": 41, "MUT_EVERY": 5,                 # 변이가 쌓이기 시작하는 웨이브, 간격
    "REWARD_CROWD": 1.0,       # 보상에 곱하는 무리 체력 배율의 지수(지금 1: 체력 배율만큼 보상도)
    # 돈 나누기(kr.gave.player.Wallet, read_curve 가 코드에서 읽음): 처치 보상의 팀 몫(모두 똑같이), 둘 이상이 함께 잡았을 때 때린 사람들에게 더 얹는 몫
    "WALLET_TEAM": 0.7, "WALLET_JOINT": 0.1,
    "BOSS_SMALL_TEAM": 1,      # 1 이면 보스 피해에도 난이도와 적은 인원 배율(Director.bossDmgMul)을 곱함. 0 이면 보스 한 방은 사람 수, 난이도와 무관(Boss.hitBoss 가 아직 안 곱하는 지금 코드)
    "ENDING_BREAK_S": 60.0,
    "MAX_WAVE": 80,
    # ---- 난이도 손잡이 하나(DIFF): 몰려오는 총량 x DIFF^DIFF_UNITS, 체력 x DIFF^DIFF_HP, 피해(사람, 트럭, 보스 공격) x DIFF^DIFF_DMG, 보스 체력 x DIFF^DIFF_BOSS_HP
    "DIFF": 1.0, "DIFF_UNITS": 0.5, "DIFF_HP": 0.5, "DIFF_DMG": 1.0, "DIFF_BOSS_HP": 0.5,
    # ---- 트럭(원정: 중앙 상점이 트럭, 터지면 게임 오버). 0 이면 없음
    "TRUCK": 1,
    "TRUCK_HP": 1000.0,
    "TRUCK_SHARE": 0.10, "TRUCK_SHARE_PER_WAVE": 0.005, "TRUCK_SHARE_MAX": 0.30,   # 몰려오는 수 가운데 트럭을 노리는 몫
    "TRUCK_DMG": 0.71, "TRUCK_POW": 0.4,         # 트럭이 받는 피해 = 종 곁 초당 피해 x 웨이브 피해 배율 x TRUCK_DMG / 접속자^TRUCK_POW
    "REPAIR_U": 0.3, "REPAIR_FRAC": 0.10, "REPAIR_POW": 0.8,   # 수리 한 번: 값 REPAIR_U x U(무기 상자 바탕값), 회복 = 최대 체력 x REPAIR_FRAC / 접속자^REPAIR_POW
    "REPAIR_SPEND": 0.4, "REPAIR_TO": 0.9,       # (사람 흉내) 쉬는 시간에 가진 돈의 이 몫까지 내서 이 비율까지 고침
    "BOSS_TRUCK_HEAL": 0.3,                      # 보스를 잡으면 트럭 체력 회복(최대 체력 비율)
    # ---- 죽음: PAID_REVIVE 1 이면 죽은 사람은 쉬는 시간에 저절로 안 일어남(서 있는 사람이 돈을 냄)
    "PAID_REVIVE": 1, "REVIVE_U": 1.0, "REVIVE_STEP": 0.25,     # 값 = REVIVE_U x U x (1 + REVIVE_STEP x 그 사람이 이미 부활한 횟수)
    "BOSS_REVIVE": 1,                            # 보스를 잡으면 죽은 사람도 공짜로 일어남(보스.md 3.6)
    "DEATH_LOSS": 0.2,                           # 죽은 사람이 잃는 재화 몫(Players.DEATH_LOSS)
    # ---- 스테이지(원정: STAGE_LEN 라운드가 한 스테이지, 세 갈래 투표). None 이면 없음.
    # {"전투": {"w": 무게, "diff": 난이도 배수(수 하나 또는 [낮음, 높음]), "reward": 처치 보상 배수, "truck": 트럭 노림 배수, "repair": 수리 값 배수,
    #           "heal": 스테이지 끝 체력 회복 비율, "aug": 스테이지 끝 1인 증강 기대 수, "money": 스테이지 끝 1인 덤(U)}}
    "STAGES": {
        "전투": {"w": 30, "diff": 1.0, "reward": 1.0, "money": 1.0},
        "정예": {"w": 14, "diff": [1.2, 1.3], "reward": [1.5, 1.8], "aug": 0.5},
        "보상": {"w": 14, "diff": [0.9, 1.0], "reward": [1.4, 1.6], "truck": 1.3},
        "함정": {"w": 12, "diff": [1.3, 1.45], "reward": [2.0, 2.3]},
        "정비": {"w": 14, "diff": [0.6, 0.7], "reward": 0.5, "repair": 0.5, "heal": 1.0},
        "사건": {"w": 16, "diff": [0.85, 1.25], "reward": [1.0, 1.8]},
    },
    "STAGE_LEN": 2, "VOTE_S": 15.0,
    "SHIP_FROM": 31, "SHIP_TO": 34, "SHIP_DIFF": [1.0, 1.08, 1.16, 1.25], "SHIP_TRUCK": 1.8, "SHIP_REWARD": 1.0,   # 우주선 건조 방어 라운드(투표 없음, 트럭 = 우주선)
}

# 보스(docs/모듈/보스.md 4절 공식, 3.3절 떼, 3.6절 보상). atk, dodge, intro 는 모의 가정(보스는 아직 게임 안에서 돌려 보지 못함).
BOSS_DEFAULT = {
    "H": {"B1": 18500, "B2": 20000, "B3": 32000, "B4": 29000, "B5": 56000},   # maxHp = H x D(w)/D(wb) x P(N) x C(c)
    "wb": {"B1": 10, "B2": 20, "B3": 30, "B4": 40, "B5": 50},
    "D0": 60.0, "D1": 4.8,
    "P_POW": 0.84, "P1": 0.65, "P2": 0.8,
    "C_STEP": 0.25,
    "units": 0.5, "alive": 0.6, "refill": 0.1, "refill_every": 20.0,
    "intro": 6.0, "death": 5.0,
    "focus": {"B1": 0.6, "B2": 0.45, "B3": 0.55, "B4": 0.4, "B5": 0.5},        # 보스를 쏘는 시간 몫(나머지는 떼, 피하기)
    "acc0": 0.45, "acc_skill": 0.5, "weak": 1.15,                              # 보스 명중 = acc0 + acc_skill x 솜씨(0.7 이면 0.8), 약점 덤
    "expect": [[1, 200], [5, 240], [10, 215], [30, 180]], "expect_b5": 1.3, "enrage_at": 2.2,
    # 보스 공격(가정): [몇 초마다, 서 있는 사람 가운데 노려지는 몫(1 + 몫 x (서 있는 수 - 1) 명), 한 번 피해(체력 100, 방어 전)]
    "atk": {"B1": [5.0, 0.18, 30], "B2": [4.5, 0.18, 30], "B3": [4.5, 0.22, 30], "B4": [4.5, 0.2, 38], "B5": [4.0, 0.22, 45]},
    "dodge0": 0.35, "dodge_skill": 0.6,                                        # 예고를 보고 피하는 몫 = dodge0 + dodge_skill x 솜씨
    "reward_per_wave": 25, "ending_money": 2000, "augs": 1, "augs_b5": 2,
}

SIM, CURVE, BOSS = {}, {}, {}
T, SP, GUNS, WARES, RAR_MUL, ODDS, BOXW = {}, {}, {}, {}, [], [], {}
# 종류 → (쏘는 방식, count, ticks, effect): Guns.MODES. 관통 감쇠, 레일건 모으기 겹침이 코드에 있는지(옛 코드와 견줄 때)
GMODES, GUN_NEW = {}, {"pierce_keep": 0.0, "rail_keep": 0.0, "charge_overlap": False}
# 등급 배율 줄(Gun.GRADE: 피해, 연사, 탄창, 재장전, 퍼짐, 사거리)과 종류마다 등급 화력 배율(그 종류 바탕 총에 등급 배율을 Java 반올림으로 건 지속 화력 / 바탕)
GRADE_ROWS, RAR_KIND = [], {}
# 무기 모델 표(tools/props.json "arms" = jar 의 props.json: Models): 종류 → {모델 키: [피해, 연사, 탄창, 재장전, 사거리] 배율}. 모델 값 저장(model_spec)
MODELS, _SPEC = {}, {}
# 피해 셈(hurt_mob 이 쌓음, horde 가 비우고 읽음): 준 피해, 실제로 깎은 체력, 없앤 감독 단위(떼는 마리 몫), 처치(떼는 마리)
STATS = {"dealt": 0.0, "removed": 0.0, "units": 0.0, "kills": 0}
# 넓게 맞히는 방식: 게임의 떼 종은 폭발, 불이면 맞은 자리 3블록 안 모든 마리가, 그 밖은 가장 가까운 한 마리만 맞는다(G02.damage)
AREA = ("LOB", "ROCKET", "FLAME")
RARITY_PRICE = [1, 1.2, 1.45, 1.8]
UPG = 0.08
MAX_LEVEL, INHERIT, CAP_HP, CAP_ARMOR, BUDGET = 20, 0.5, 20, 14, 440
CONFIG_NAME = "지금"


def src(rel):
    w = WIP / rel if WIP else None
    return (w if w is not None and w.is_file() else ROOT / rel).read_text(encoding="utf-8")


def read_tune():
    return _scalars(_tune_body(src(JAVA + "game/Director.java")))


def _tune_body(java, start="class Tune", end="private Tune()"):
    i = java.index(start)
    return java[i:java.index(end, i)]


def _scalars(body):
    t = {}
    for m in re.finditer(r"public static (?:double|int) ([^;]+);", body):
        for part in m.group(1).split(","):
            k, v = part.split("=")
            t[k.strip()] = float(v)
    return t


def _array(body, name):
    m = re.search(r"\b" + name + r" = \{([^;]*)\};", body)
    return m.group(1) if m else None


# 감독 Tune 이름 → CURVE 이름(코드에 들어간 곡선 손잡이). sync_curve 가 옮긴다.
TUNE_TO_CURVE = [
    ("DIFFICULTY", "DIFF", float), ("DIFF_UNITS", "DIFF_UNITS", float), ("DIFF_HP", "DIFF_HP", float), ("DIFF_DMG", "DIFF_DMG", float),
    ("DIFF_BOSS_HP", "DIFF_BOSS_HP", float), ("PRE_BOSS", "PRE_BOSS", lambda v: [v] if v != 1 else []), ("POST_BOSS", "POST_BOSS", lambda v: [v] if v != 1 else []),
    ("ENDLESS_FROM", "ENDLESS_FROM", int), ("ENDLESS_DMG", "ENDLESS_DMG", float), ("ENDLESS_UNITS", "ENDLESS_UNITS", float),
    ("MUT_FROM", "MUT_FROM", int), ("MUT_EVERY", "MUT_EVERY", int), ("REWARD_CROWD", "REWARD_CROWD", float),
]
NOTES = []


def sync_curve(keys=None):
    """Tune 에 든 곡선 손잡이를 CURVE 로 옮긴다(keys 를 주면 그 이름만: --set, 값 파일의 tune 묶음으로 바꾼 것)."""
    for tk, ck, f in TUNE_TO_CURVE:
        if tk in T and (keys is None or tk in keys):
            CURVE[ck] = f(T[tk])


def read_curve():
    """코드에 있는 곡선 손잡이를 CURVE, BOSS 에 넣는다: 감독 Tune(sync_curve), 설정 difficulty, 보스 차례, 보스 체력 표, 원정 숫자, 죽음 손실."""
    NOTES.clear()
    # 설정 difficulty: 게임(Director 생성자)이 0 보다 크면 Tune.DIFFICULTY 를 덮는다
    try:
        m = re.search(r"^difficulty:\s*([0-9.]+)", src("plugin/src/main/resources/config.yml"), re.M)
        if m and float(m.group(1)) > 0 and "DIFFICULTY" in T:
            if abs(float(m.group(1)) - T["DIFFICULTY"]) > 1e-9:
                NOTES.append(f"난이도: 설정 difficulty {m.group(1)} 가 코드 상수 {T['DIFFICULTY']} 를 덮음")
            T["DIFFICULTY"] = float(m.group(1))
    except OSError:
        pass
    sync_curve()
    d = src(JAVA + "game/Director.java")
    m = re.search(r"int\[\] BOSS_WAVES = \{([^}]*)\}", d)
    if m:
        CURVE["BOSS_WAVES"] = [int(x) for x in m.group(1).split(",") if x.strip()]
    try:
        b = src(JAVA + "mob/boss/BossDirector.java")
    except OSError:
        b = ""
    m = re.search(r"Species\[\] ORDER = \{([^}]*)\}", b)
    if m:
        CURVE["BOSS_ORDER"] = [x.strip().replace("Species.", "") for x in m.group(1).split(",") if x.strip()]
    if "class Tune" in b:
        body = _tune_body(b)
        t = _scalars(body)
        names = ["B1", "B2", "B3", "B4", "B5"]
        h, at = _array(body, "H"), _array(body, "H_AT")
        if h:
            BOSS["H"] = dict(zip(names, [float(x) for x in h.split(",")]))
        if at:
            BOSS["wb"] = dict(zip(names, [int(float(x)) for x in at.split(",")]))
        for tk, bk in (("DPS_BASE", "D0"), ("DPS_PER_WAVE", "D1"), ("PEOPLE_POW", "P_POW"), ("PEOPLE_SOLO", "P1"), ("PEOPLE_DUO", "P2"), ("CYCLE_HP", "C_STEP"),
                       ("BOSS_UNITS", "units"), ("BOSS_ALIVE", "alive"), ("REFILL", "refill"), ("ENRAGE_AT", "enrage_at"), ("EXPECT_B5", "expect_b5"),
                       ("REWARD_PER_WAVE", "reward_per_wave"), ("ENDING_MONEY", "ending_money")):
            if tk in t:
                BOSS[bk] = t[tk]
        if "REFILL_EVERY" in t:
            BOSS["refill_every"] = t["REFILL_EVERY"] / 20.0
        if "ENDING_BREAK" in t:
            CURVE["ENDING_BREAK_S"] = t["ENDING_BREAK"] / 20.0
        ex = _array(body, "EXPECT")
        if ex:
            BOSS["expect"] = [[float(x), float(y)] for x, y in re.findall(r"\{\s*([0-9.]+)\s*,\s*([0-9.]+)\s*\}", ex)]
    # 보스 공격 피해에 난이도와 적은 인원 배율: 모의는 곱하는 것으로 친다(파워커브.md 8절). 코드가 아직 안 곱하면 알림만
    try:
        if "bossDmgMul" not in src(JAVA + "mob/boss/Boss.java"):
            NOTES.append("보스 공격 피해: Boss.hitBoss 가 아직 Director.bossDmgMul()(난이도 x 적은 인원 배율)을 안 곱함. 모의는 곱한 것으로 친다(끄려면 curve.BOSS_SMALL_TEAM=0)")
    except OSError:
        pass
    # 원정 숫자(Trip.Tune)
    try:
        tr = _scalars(_tune_body(src(JAVA + "trip/Trip.java")))
    except (OSError, ValueError):
        tr = {}
    for tk, ck in (("TRUCK_HP", "TRUCK_HP"), ("TRUCK_SHARE", "TRUCK_SHARE"), ("TRUCK_SHARE_PER_WAVE", "TRUCK_SHARE_PER_WAVE"), ("TRUCK_SHARE_MAX", "TRUCK_SHARE_MAX"),
                   ("TRUCK_DMG", "TRUCK_DMG"), ("TRUCK_POW", "TRUCK_POW"), ("REPAIR_U", "REPAIR_U"), ("REPAIR_FRAC", "REPAIR_FRAC"), ("REPAIR_POW", "REPAIR_POW"),
                   ("BOSS_REPAIR", "BOSS_TRUCK_HEAL"), ("REVIVE_U", "REVIVE_U"), ("REVIVE_STEP", "REVIVE_STEP")):
        if tk in tr:
            CURVE[ck] = tr[tk]
    # 돈 나누기 몫(Wallet.TEAM, JOINT)
    try:
        w = src(JAVA + "player/Wallet.java")
        for name, key in (("TEAM", "WALLET_TEAM"), ("JOINT", "WALLET_JOINT")):
            m = re.search(r"public static double " + name + r" = ([0-9.]+)", w)
            if m:
                CURVE[key] = float(m.group(1))
    except OSError:
        pass
    try:
        m = re.search(r"DEATH_LOSS = ([0-9.]+)", src(JAVA + "player/Players.java"))
        if m:
            CURVE["DEATH_LOSS"] = float(m.group(1))
    except OSError:
        pass


def finish_curve():
    """엔딩 웨이브가 비어 있으면 B5 가 처음 나오는 웨이브(없으면 50)."""
    if CURVE.get("ENDING_WAVE") is None:
        CURVE["ENDING_WAVE"] = next((w for w in range(1, 201) if (boss_at(w) or ("", 0))[0] == "B5"), 50)


def read_species():
    s = src("tools/gen_species.py")
    tree = ast.parse(s)
    tune = None
    for node in tree.body:
        if isinstance(node, ast.Assign) and getattr(node.targets[0], "id", "") == "TUNE":
            tune = ast.literal_eval(node.value)
    d = json.loads((ROOT / "data/bestiary.json").read_text(encoding="utf-8"))
    roles = {"돌격": "CHARGE", "떼": "SWARM", "원거리": "RANGED", "함정": "TRAP", "방해": "DISRUPT", "방패": "SHIELD",
             "튼튼이": "TANK", "지원": "SUPPORT", "정예": "ELITE", "보상": "BONUS", "목표 압박": "OBJECTIVE", "보스": "BOSS"}
    out = {}
    impl = {p.stem for p in (ROOT / JAVA / "mob/sp").glob("*.java") if re.fullmatch(r"(G\d{2,3}|B\d)", p.stem)}
    for v in d["species"]:
        band, spawn, hp, cost, reward = tune[v["id"]] if v["id"] in tune else (v["band"], v["spawn"], v["hp"], v["cost"], v["rewardWon"])
        rs = {roles[r.strip()] for r in re.split(r"[,，]\s*", v["role"])}
        out[v["id"]] = dict(id=v["id"], tier=v["tier"], threat=v["threat"], band=band, spawn=spawn, hp=hp, cost=cost, reward=reward,
                            roles=rs, impl=v["id"] in impl)
    return out


def read_guns():
    """Guns.SPECS(탄창, 예비, 연사, 재장전 틱, 알 수, 사거리, 관통, 한 발씩 넣는가, 피해)와 Guns.MODES(쏘는 방식)."""
    s = src(JAVA + "gun/Guns.java")
    g = {}
    for m in re.finditer(r'new Spec\(Kind\.(\w+), "\w+", "[^"]+", ([^)]*?)"\w+",\s*Sound\.\w+, [\d.]+f, (\d+), [\d.]+, new Recoil\([^)]*\), ([\d.]+)\)', s, re.S):
        nums = [x.strip() for x in m.group(2).split(",") if x.strip()]
        mag, reserve, rps = int(nums[0]), int(nums[1]), float(nums[2])
        reload, pellets, rng = int(nums[9]), int(nums[10]), float(nums[11])
        pierce = int(float(nums[12])) if len(nums) > 12 and re.fullmatch(r"[0-9.]+", nums[12]) else 0
        shells = len(nums) > 13 and nums[13] == "true"
        g[m.group(1)] = dict(mag=mag, reserve=reserve, rps=rps, reload=reload / 20.0, shells=shells, pellets=pellets, range=rng, dmg=float(m.group(4)),
                             pierce=pierce)
    # 단발 배율(2026-11-15 Guns.SINGLE_SHOT: Guns.add 가 바탕 한 발 피해에 곱함). 표가 없는 코드면 1
    m = re.search(r"SINGLE_SHOT = Map\.of\(([^;]*)\);", s)
    for k, v in re.findall(r"Kind\.(\w+), ([\d.]+)", m.group(1) if m else ""):
        if k in g:
            g[k]["dmg"] *= float(v)
    GMODES.clear()
    for m in re.finditer(r"MODES\.put\(Kind\.(\w+), new Mode\(Fire\.(\w+), (\d+), (\d+), [\d.]+, [\d.]+, [\d.]+, [\d.]+, ([\d.]+)\)\)", s):
        GMODES[m.group(1)] = (m.group(2), int(m.group(3)), int(m.group(4)), float(m.group(5)))
    # 새 코드인가: 관통 감쇠(Guns.pierced 0.8, 레일 0.9), 레일건 모으기와 쏜 뒤 쉼 겹침(chargeEnd)
    m = re.search(r"static double pierced\(int n\) \{\s*return Math\.max\(([\d.]+), Math\.pow\(([\d.]+), n\)\)", s)
    GUN_NEW["pierce_keep"] = float(m.group(2)) if m else 0.0
    GUN_NEW["pierce_floor"] = float(m.group(1)) if m else 0.0
    a = src(JAVA + "gun/Arsenal.java")
    m = re.search(r"Math\.max\(([\d.]+), Math\.pow\(([\d.]+), n\)\)", a)
    GUN_NEW["rail_keep"] = float(m.group(2)) if m else 0.0
    GUN_NEW["rail_floor"] = float(m.group(1)) if m else 0.0
    GUN_NEW["charge_overlap"] = "chargeEnd(" in s
    # 미니건: 새 코드(Guns.cycleTicks)는 누른 채 재장전하면 총열이 도는 채로 남는다고 셈(이어 쏘는 동안 다 돈 빠르기)
    GUN_NEW["spin_kept"] = "static double cycleTicks(" in s
    # 모델 화력: 새 코드(2026-11-15)는 모델 배율(피해 포함)이 그대로 걸리고 한 발 피해를 그 총 자신의 반올림 전 화력에 맞춤(smoothSustained),
    # 옛 코드(2026-11-14)는 표의 피해 배율을 안 쓰고 모델 없는 같은 등급 총의 화력에 맞춤
    GUN_NEW["model_own"] = "smoothSustained(" in s
    return g


def gun_cycle(kind, g=None):
    """(탄창 안 발 사이 초, 마지막 발부터 다음 탄창 첫 발까지 초, 한 바퀴 초): Guns.cycleTicks 와 같은 식.
    재장전은 마지막 발 직후 시작해 쏜 뒤 쉼과 겹친다. 산탄총은 시작 자세 7 + 한 발 x 탄창 + 끝 펌프 8 틱(시작, 펌프는 재장전 배율만큼),
    점사는 점사 뒤 쉼, 레일건은 재장전 뒤 다시 모으기(옛 코드는 발마다 쉼 뒤에 모으기), 미니건은 새 코드면 다 돈 빠르기(옛 코드는 탄창마다
    총열을 처음부터 돌림)."""
    g = g or GUNS[kind]
    fire, count, ticks, effect = GMODES.get(kind, ("HITSCAN", 0, 0, 0.0))
    mag, gap = max(1, int(g["mag"])), 20.0 / g["rps"]
    reload = g["reload"] * 20.0
    if g.get("shells"):
        mul = max(0.2, min(5.0, g["reload"] / GUNS[kind]["reload"])) if kind in GUNS else 1.0
        reload = max(1, jround(7 * mul)) + mag * reload + max(1, jround(8 * mul))
    if fire == "BURST":
        n = max(1, count)
        bursts = (mag + n - 1) // n
        last = (mag - 1) * gap + (bursts - 1) * ticks
        tail = max(reload, gap + (ticks if mag % n == 0 else 0))
    elif fire == "CHARGE":
        if GUN_NEW["charge_overlap"]:
            last, tail = (mag - 1) * max(gap, ticks), max(reload + ticks, gap)
        else:
            last, tail = (mag - 1) * (gap + ticks), max(reload, gap) + ticks
    elif fire == "SPIN" and not GUN_NEW.get("spin_kept"):
        spin, nxt, t, left = 0.0, 0.0, 0, mag
        while left > 0 and t < 100000:
            spin = min(1.0, spin + 1.0 / max(1, ticks))
            if spin >= effect:
                nxt = max(nxt, t)
                while nxt < t + 1 and left > 0:
                    left -= 1
                    nxt += gap / (0.2 + 0.8 * spin)
            t += 1
        last, tail = float(t), max(reload, 1.0)
    else:
        last, tail = (mag - 1) * gap, max(reload, gap)
    cyc = last + tail
    shot = last / (mag - 1) if mag > 1 else cyc
    return shot / 20.0, (tail if mag > 1 else 0.0) / 20.0, cyc / 20.0


def gun_single(kind, g=None):
    """한 대상 지속 초당 피해(다 맞음, 치명과 감쇠 없음): 상점 '화력' 줄과 같은 값. 불길은 불붙음 피해를 더함."""
    g = g or GUNS[kind]
    _, _, cyc = gun_cycle(kind, g)
    dps = g["dmg"] * g["pellets"] * max(1, int(g["mag"])) / cyc
    fire, _, _, effect = GMODES.get(kind, ("HITSCAN", 0, 0, 0.0))
    return dps + g["dmg"] * effect if fire == "FLAME" else dps


def jround(x):
    """Java Math.round(양수): 반은 올림(파이썬 round 는 짝수 쪽으로 가서 다름)"""
    return int(math.floor(x + 0.5))


def cycle_ticks(kind, gap, reload, mag):
    """Guns.cycle(새 코드): 발 사이 gap 틱, 빈 총을 다 채우는 reload 틱, 탄창 mag(정수) 발의 한 바퀴 틱."""
    fire, count, ticks, _ = GMODES.get(kind, ("HITSCAN", 0, 0, 0.0))
    if fire == "BURST":
        n = max(1, count)
        return (mag - 1) * gap + ((mag + n - 1) // n - 1) * ticks + max(reload, gap + (ticks if mag % n == 0 else 0))
    if fire == "CHARGE":
        return (mag - 1) * max(gap, ticks) + max(reload + ticks, gap)
    return (mag - 1) * gap + max(reload, gap)


def smooth_single(kind, mul):
    """Guns.smoothSustained: 바탕에 배율 mul [피해, 연사, 탄창, 재장전, 사거리] 을 곱한 탄창과 재장전 틱을 소수 그대로 둔 한 대상 지속 화력
    (탄창은 이웃한 두 정수의 한 바퀴를 곧게 이음)."""
    g = GUNS[kind]
    rm = max(0.2, min(5.0, mul[3]))
    mag, rl, gap = max(1.0, g["mag"] * mul[2]), g["reload"] * 20.0 * rm, 20.0 / max(0.1, g["rps"] * mul[1])
    lo = int(mag)
    f = mag - lo
    cyc = sum(wt * cycle_ticks(kind, gap, 7 * rm + k * rl + 8 * rm if g.get("shells") else rl, k) for k, wt in ((lo, 1 - f), (lo + 1, f)) if wt > 0)
    dmg = g["dmg"] * max(0.0, mul[0])
    dps = mag * dmg * g["pellets"] / cyc * 20
    fire, _, _, effect = GMODES.get(kind, ("HITSCAN", 0, 0, 0.0))
    return dps + dmg * effect if fire == "FLAME" else dps


def model_spec(kind, key=None, r=0):
    """Guns.effective(강화 0, 사람 증강 없이): 종류 바탕 x 모델 배율 x 등급 r 줄(Gun.GRADE)을 Spec.with 처럼 한 번 반올림하고, 한 발 피해를
    새 코드면 그 총 자신의 반올림 전 화력(smooth_single)에, 옛 코드면 모델 없는 같은 등급 총의 화력에 맞춘다. 강화는 피해에만 곱해지고
    반올림과 상관없어 Person.weapon 이 곱한다. 돌려줌: GUNS 꼴 dict(바꾸지 말 것: 모아 둔 값)"""
    ck = (kind, key, r)
    if ck in _SPEC:
        return _SPEC[ck]
    g = GUNS[kind]
    row = GRADE_ROWS[r] if r < len(GRADE_ROWS) else [1.0] * 6
    grade = [row[0], row[1], row[2], row[3], row[5]]
    mm = MODELS.get(kind, {}).get(key) if key else None
    own = GUN_NEW.get("model_own")
    mul = [a * b for a, b in zip(grade, ([mm[0]] if own else [1.0]) + mm[1:])] if mm else grade

    def rounded(mu):
        t = max(1, jround(g["reload"] * 20.0 * max(0.2, min(5.0, mu[3]))))
        return dict(g, dmg=g["dmg"] * mu[0], rps=max(0.1, g["rps"] * mu[1]), mag=max(1, jround(g["mag"] * mu[2])), reload=t / 20.0,
                    range=max(1.0, g["range"] * mu[4]))
    s = rounded(mul)
    got = gun_single(kind, s)
    want = smooth_single(kind, mul) if own else gun_single(kind, rounded(grade)) if mm else got
    if want > 0 and got > 0:
        s["dmg"] *= want / got
    _SPEC[ck] = s
    return s


def grade_mul(kind, r):
    """종류 kind 바탕 총(모델 배율 없이)의 등급 r 이 등급 없는 바탕보다 센 배율(한 대상 지속 화력, Guns.effective 와 같은 반올림 갚기)."""
    if kind not in RAR_KIND:
        base = gun_single(kind)
        RAR_KIND[kind] = [gun_single(kind, model_spec(kind, None, i)) / base if base > 0 else 1.0 for i in range(len(GRADE_ROWS[:4]))] or RAR_MUL
    return RAR_KIND[kind][r]


def read_models():
    """무기 모델 표(tools/props.json "arms", compile_wip 이 jar 에 넣는 것): 종류마다 모델 키와 배율. 표가 없으면 빈 것(종류 바탕 하나)."""
    try:
        arms = json.loads((ROOT / "tools/props.json").read_text(encoding="utf-8")).get("arms", {})
    except (OSError, ValueError):
        arms = {}
    out = {}
    for key, m in arms.items():
        out.setdefault(m["kind"], {})[key] = [float(m.get(x) or 1.0) for x in ("dmg", "rps", "mag", "reload", "range")]
    return out


def _pierce_crowd(crowd, keep, floor, per=0.3):
    """관통 감쇠가 든 코드: 덤으로 맞는 마리(crowd - 1, 한 마리에 per 씩 쳐 온 값)가 차례로 keep^i (아래끝 floor) 몫만 받는다."""
    extra = crowd - 1
    if extra <= 0 or keep <= 0:
        return crowd
    n = max(1, round(extra / per))
    avg = sum(max(floor, keep ** i) for i in range(1, n + 1)) / n
    return 1 + extra * avg


def gun_model(kind):
    """[피해 덤, 떼 한가운데서 한 발에 맞는 마리 수]. SIM gun 표에 없는 새 종류는 관통과 산탄으로 어림(관통 하나에 +0.3, 4 넘으면 +0.05 씩, 3 까지. 산탄 1.3).
    마리 수는 관통 감쇠와 상관없는 '맞는 수'다. 감쇠(Guns.pierced, 레일 광선)는 gun_keep 이 주고, 덤으로 맞는 마리가 차례로 그 몫만 받는다."""
    g = GUNS.get(kind, {})
    m = SIM["gun"].get(kind)
    if m:
        bonus, crowd = m
    else:
        pc = g.get("pierce", 0)
        crowd = 1 + 0.3 * min(pc, 4) + 0.05 * max(0, min(pc, 20) - 4)
        if g.get("pellets", 1) > 2:
            crowd = max(crowd, 1.3)
        bonus, crowd = 1.0, min(3.0, crowd)
    # 번개: 표의 값은 번질 때 0.75 배 기준이다. 코드의 번짐 배율(Mode.effect)이 바뀌면 번진 몫의 합만큼 덤을 맞춘다
    fire, count, _, effect = GMODES.get(kind, ("HITSCAN", 0, 0, 0.0))
    if fire == "CHAIN" and count > 0 and effect > 0:
        crowd = 1 + (crowd - 1) * sum(effect ** i for i in range(1, count + 1)) / sum(0.75 ** i for i in range(1, count + 1))
    return [bonus, crowd]


def gun_keep(kind):
    """(꿰뚫을 때마다 남는 몫, 아래끝): 덤으로 맞는 i 번째 마리는 max(아래끝, 몫^i) 만큼 받는다. 감쇠가 없는 코드나 관통이 아닌 범위(폭발, 번짐, 원뿔)는 (1, 1)."""
    g = GUNS.get(kind, {})
    fire = GMODES.get(kind, ("HITSCAN",))[0]
    if fire == "CHARGE" and GUN_NEW["rail_keep"] > 0:
        return GUN_NEW["rail_keep"], GUN_NEW.get("rail_floor", 0.0)
    if fire in ("HITSCAN", "BURST", "SPIN", "BOLT", "BEAM") and g.get("pierce", 0) > 0 and g.get("pellets", 1) <= 2 and GUN_NEW["pierce_keep"] > 0:
        return GUN_NEW["pierce_keep"], GUN_NEW.get("pierce_floor", 0.0)
    return 1.0, 1.0


def read_wares():
    s = src(JAVA + "game/Shop.java")
    body = s[s.index("enum Ware"):s.index("public final int base")]
    w = {}
    # 바탕 값, 오름, 단계 뒤의 칸 번호는 하나 이상(Ware(base, growth, level, slot, oldSlot))
    for m in re.finditer(r"(\w+)\((\d+), ([\d.]+), ([\d.]+)(?:, \d+)+\)", body):
        w[m.group(1)] = (int(m.group(2)), float(m.group(3)), float(m.group(4)))
    if "BOX" not in w and "ROLL_RIFLE" in w:
        w["BOX"] = w["ROLL_RIFLE"]
    m = re.search(r"RARITY_PRICE = \{([^}]*)\}", s)
    rp = [float(x) for x in m.group(1).split(",")] if m else [1, 1.2, 1.45, 1.8]
    return w, rp


def read_rarity():
    """등급마다 한 대상 화력 배율(소총 기준 지속 화력: 피해, 연사, 탄창, 재장전 배율을 한 바퀴로 셈), 등급 확률표, 상자 가중치.
    새 코드는 등급마다 정해진 값(Gun.GRADE), 옛 코드는 굴리는 범위(Gun.RANGE)의 가운데 값."""
    s = src(JAVA + "gun/Gun.java")
    rows = []
    m = re.search(r"GRADE = \{(.*?)\};", s, re.S)
    if m:
        for r in re.findall(r"\{([\d., ]+)\}", m.group(1)):
            rows.append([float(x) for x in r.split(",")])
    else:
        m = re.search(r"RANGE = \{(.*?)\};", s, re.S)
        for r in re.findall(r"\{((?:\{[\d., ]+\},? ?)+)\}", m.group(1)):
            rows.append([(float(a) + float(b)) / 2 for a, b in re.findall(r"\{([\d.]+), ([\d.]+)\}", r)])
    GRADE_ROWS[:] = rows
    RAR_KIND.clear()
    dmg_rate = [grade_mul("RIFLE", i) for i in range(len(rows[:4]))] if "RIFLE" in GUNS else [1.0] * 4
    odds = []
    for m in re.finditer(r"if \(wave <= (\d+)\) return new int\[\] \{(\d+), (\d+), (\d+), (\d+)\}", s):
        odds.append((int(m.group(1)), [int(m.group(i)) for i in range(2, 6)]))
    last = re.search(r"return new int\[\] \{(\d+), (\d+), (\d+), (\d+)\};\s*\}", s[s.index("odds(int wave)"):])
    odds.append((10 ** 9, [int(last.group(i)) for i in range(1, 5)]))
    box = {}
    for m in re.finditer(r"BOX\.put\(Kind\.(\w+), new double\[\] \{([\d.]+), ([\d.]+)\}\)", s):
        box[m.group(1)] = (float(m.group(2)), float(m.group(3)))
    return dmg_rate, odds, box


def read_caps():
    """강화 상한(Gun.MAX_LEVEL), 이어받는 몫(Gun.INHERIT), 단계 피해(Gun.LEVEL_DMG), 스탯 상한(Augments.Stat cap)."""
    g = src(JAVA + "gun/Gun.java")
    lv = int(re.search(r"MAX_LEVEL = (\d+);", g).group(1))
    m = re.search(r"INHERIT = ([0-9.]+);", g)
    inh = float(m.group(1)) if m else 0.0
    m = re.search(r"LEVEL_DMG = ([0-9.]+);", g)
    upg = float(m.group(1)) if m else 0.08
    a = src(JAVA + "game/Augments.java")
    caps = {k: int(v) for k, v in re.findall(r'(HP|ARMOR|MOVE|RELOAD)\("[^"]*", (\d+)\)', a)}
    return lv, inh, upg, caps.get("HP", 8), caps.get("ARMOR", 8)


def read_budget():
    """감독이 쓸 수 있는 몹 디스플레이 예산 = Mobs.BUDGET - Tune.CHILD_ROOM."""
    try:
        m = re.search(r"int BUDGET = (\d+);", src(JAVA + "mob/Mobs.java"))
        return int(m.group(1)) - int(T.get("CHILD_ROOM", 0))
    except (OSError, AttributeError):
        return 200


def merge(dst, over):
    for k, v in over.items():
        if isinstance(v, dict) and isinstance(dst.get(k), dict):
            merge(dst[k], v)
        else:
            dst[k] = v


def load(over=None, name="지금"):
    """코드에서 숫자를 다시 읽고 over(값 묶음: tune, curve, boss, ware, sim, species, gun, odds, caps)를 덮어쓴다."""
    global T, SP, GUNS, WARES, RAR_MUL, ODDS, BOXW, RARITY_PRICE, UPG, MAX_LEVEL, INHERIT, CAP_HP, CAP_ARMOR, BUDGET, SIM, CURVE, BOSS, CONFIG_NAME
    SIM = copy.deepcopy(SIM_DEFAULT)
    CURVE = copy.deepcopy(CURVE_DEFAULT)
    BOSS = copy.deepcopy(BOSS_DEFAULT)
    T = read_tune()
    SP = read_species()
    GUNS = read_guns()
    MODELS.clear()
    MODELS.update(read_models())
    _SPEC.clear()
    WARES, RARITY_PRICE = read_wares()
    RAR_MUL, ODDS, BOXW = read_rarity()
    GUN_NEW["fixed_grade"] = "GRADE = {" in src(JAVA + "gun/Gun.java")
    MAX_LEVEL, INHERIT, UPG, CAP_HP, CAP_ARMOR = read_caps()
    BUDGET = read_budget()
    CONFIG_NAME = name
    _COST.clear()
    read_curve()
    if over:
        apply(over)
        _SPEC.clear()
        RAR_KIND.clear()
    finish_curve()


def apply(over):
    global ODDS, MAX_LEVEL, CAP_HP, CAP_ARMOR, UPG, INHERIT, BUDGET, RARITY_PRICE
    for k, v in over.get("tune", {}).items():
        T[k] = float(v)
    sync_curve(set(over.get("tune", {})))
    merge(SIM, over.get("sim", {}))
    cv = over.get("curve", {})
    # 값 파일의 스테이지 갈래 표는 통째로 바꾼다(기본 표와 섞지 않음)
    if "STAGES" in cv:
        CURVE["STAGES"] = None
    if "BOSS_WAVES" in cv:
        CURVE["BOSS_WAVES"] = None
    merge(CURVE, cv)
    merge(BOSS, over.get("boss", {}))
    for k, v in over.get("ware", {}).items():
        WARES[k] = (float(v[0]), float(v[1]), float(v[2]))
    for k, v in over.get("species", {}).items():
        SP[k].update(v)
    for k, v in over.get("gun", {}).items():
        GUNS[k].update(v)
    if "odds" in over:
        ODDS = [(int(lim), list(o)) for lim, o in over["odds"]]
    caps = over.get("caps", {})
    MAX_LEVEL = int(caps.get("MAX_LEVEL", MAX_LEVEL))
    CAP_HP = int(caps.get("HP", CAP_HP))
    CAP_ARMOR = int(caps.get("ARMOR", CAP_ARMOR))
    UPG = float(caps.get("LEVEL_DMG", UPG))
    INHERIT = float(caps.get("INHERIT", INHERIT))
    if "RARITY_PRICE" in caps:
        RARITY_PRICE = list(caps["RARITY_PRICE"])
    if "CHILD_ROOM" in over.get("tune", {}) or "BUDGET" in caps:
        BUDGET = int(caps.get("BUDGET", BUDGET))


def parse_set(text):
    """--set 글: Tune 이름=값, 종.hp=값, 총.dmg=값, SIM 이름=값, curve.이름=값, boss.이름=값, ware.이름=바탕/오름/단계 를 쉼표로."""
    over = {"tune": {}, "sim": {}, "curve": {}, "boss": {}, "ware": {}, "species": {}, "gun": {}}
    for kv in filter(None, text.split(",")):
        k, v = kv.split("=")
        k = k.strip()
        if "." in k:
            o, f = k.split(".", 1)
            if o == "curve":
                over["curve"][f] = json.loads(v)
            elif o == "boss":
                over["boss"][f] = json.loads(v)
            elif o == "ware":
                over["ware"][f] = [float(x) for x in v.split("/")]
            elif o in SP:
                over["species"].setdefault(o, {})[f] = float(v)
            else:
                over["gun"].setdefault(o, {})[f] = float(v)
        elif k in SIM_DEFAULT:
            over["sim"][k] = v if isinstance(SIM_DEFAULT[k], str) else float(v)
        else:
            over["tune"][k] = float(v)
    return over


def read_config(path, extra=None):
    """값 파일(json) 하나를 값 묶음으로. '-' 나 빈 글은 지금 코드 그대로."""
    over = {}
    if path and path != "-":
        over = json.loads(Path(path).read_text(encoding="utf-8"))
    if extra:
        merge(over, extra)
    return over


# ------------------------------------------------------------------ 감독 공식

def unit(sp):
    u = 4.0 if "SWARM" in sp["roles"] else {1: 1.0, 2: 1.5, 3: 2.0, 4: 3.0}.get(sp["threat"], 4.0)
    if "ELITE" in sp["roles"]:
        u *= 1.5
    uh = T.get("UNIT_HP", 0)
    if uh > 0 and "SWARM" not in sp["roles"]:
        u = max(u, sp["hp"] / uh)
    return u


def price(ware, wave, level=0, rar=0):
    base, growth, lv = WARES[ware]
    v = base * (1 + growth * (wave - 1)) * lv ** level
    if ware == "UP_GUN":
        v *= RARITY_PRICE[max(0, min(3, rar))]
    return max(5, int(round(v / 5.0)) * 5)


def boss_table():
    """(보스 웨이브 차례, 그 차례의 보스, 그 뒤 간격). 차례가 없으면 (None, None, BOSS_EVERY): 옛 꼴(BOSS_EVERY 배수마다 B1..B5)."""
    every = int(T.get("BOSS_EVERY", 10))
    bw = CURVE.get("BOSS_WAVES")
    if not bw:
        return None, None, every
    if isinstance(bw, dict):
        waves = sorted(int(k) for k in bw)
        return waves, [bw[str(k)] for k in waves], every
    return [int(x) for x in bw], list(CURVE.get("BOSS_ORDER") or ["B1", "B2", "B3", "B4", "B5"]), every


def boss_index(w):
    """Director.bossIndex: w 웨이브가 몇 번째 보스 웨이브인가(0부터), 아니면 -1. 차례(BOSS_WAVES) 뒤로는 마지막 값에서 BOSS_EVERY 마다."""
    waves, _, every = boss_table()
    if not waves:
        return w // every - 1 if every > 0 and w >= every and w % every == 0 else -1
    if w in waves:
        return waves.index(w)
    last = waves[-1]
    if every <= 0 or w <= last or (w - last) % every:
        return -1
    return len(waves) - 1 + (w - last) // every


def boss_at(w):
    """그 웨이브 보스 자리의 (키, 회차): BossDirector.bossFor, cycleOf. 보스 자리가 아니면 None(보스를 껐어도 자리는 같음)."""
    k = boss_index(w)
    if k < 0:
        return None
    _, order, _ = boss_table()
    if not order:
        return "B%d" % (k % 5 + 1), k // 5 + 1
    return order[k % len(order)], k // len(order) + 1


def boss_of(w):
    """그 웨이브의 보스 (키, 회차). 없으면 None."""
    if not CURVE.get("BOSSES", 1):
        return None
    return boss_at(w)


def is_boss_wave(w):
    """보스 자리 웨이브인가(보스가 꺼져 있어도 자리는 같음: 숨 고르기와 안 겹치게)."""
    return boss_index(w) >= 0


def is_breather(w):
    return w >= 10 and w % 5 == 0 and not is_boss_wave(w)


def shape(w):
    """보스 앞뒤 총량 배율(톱니): 보스 앞은 조이고(PRE_BOSS), 보스 뒤는 한숨(POST_BOSS)."""
    if is_boss_wave(w) or is_breather(w):
        return 1.0
    m = 1.0
    for d, v in enumerate(CURVE.get("PRE_BOSS") or [], 1):
        if is_boss_wave(w + d):
            m *= v
            break
    for d, v in enumerate(CURVE.get("POST_BOSS") or [], 1):
        if w - d >= 1 and is_boss_wave(w - d):
            m *= v
            break
    return m


def mut_n(w):
    f, e = int(CURVE.get("MUT_FROM", 41)), max(1, int(CURVE.get("MUT_EVERY", 5)))
    return 0 if f <= 0 or w < f else (w - f) // e + 1


def endless_k(w):
    e = int(CURVE.get("ENDLESS_FROM", 0))
    return w - e + 1 if e and w >= e else 0


def dpow(key, d=None):
    """난이도 손잡이: DIFF(기본 1)의 key 지수 제곱. d 를 주면 그 값(스테이지 종류 배수)으로."""
    return (CURVE.get("DIFF", 1.0) if d is None else d) ** CURVE.get(key, 0.0)


def unit_money(w):
    """돈 단위 U = 무기 상자 바탕값(재미.md 2.3)."""
    return price("BOX", w)


def hp_mul(w):
    late = int(T["HP_LATE_FROM"])
    m = T["HP_GROWTH"] ** (w - 1) if w <= late else T["HP_GROWTH"] ** (late - 1) * T["HP_GROWTH_LATE"] ** (w - late)
    return m * T.get("HP_BASE", 1.0) * SIM["mut_hp"] ** mut_n(w) * dpow("DIFF_HP")


def people_hp(n):
    return n ** T.get("HP_POW", 0.0)


def speed_mul(w):
    """Director.speedMul: SPEED_BASE(2026-11-12 1.12, 없으면 1) + min(SPEED_MAX, SPEED_PER_WAVE x (w - 1))."""
    return T.get("SPEED_BASE", 1.0) + min(T["SPEED_MAX"], T["SPEED_PER_WAVE"] * (w - 1))


def dmg_mul(w):
    v = T.get("DMG_BASE", 1.0) + T["DMG_PER_WAVE"] * (w - 1)
    lf = int(CURVE.get("DMG_LATE_FROM", 0))
    if lf and w > lf:
        v += CURVE.get("DMG_PER_WAVE_LATE", 0.0) * (w - lf)
    v = min(T["DMG_MAX"], v)
    return v * CURVE.get("ENDLESS_DMG", 1.0) ** endless_k(w) * SIM["mut_dmg"] ** mut_n(w) * dpow("DIFF_DMG")


def people_dmg(n):
    """Director.dmgPeople: 적은 인원 배율 min(1, (접속자 / DMG_FULL_PEOPLE)^DMG_PEOPLE_POW). DMG_PEOPLE_POW 가 0 이면 옛 꼴(혼자 DMG_SOLO 에서 한 사람마다
    +DMG_SMALL_STEP, 1 까지: build/curve_apply/bal12.json)."""
    if T.get("DMG_PEOPLE_POW", 0) > 0:
        return min(1.0, (n / T.get("DMG_FULL_PEOPLE", 30.0)) ** T["DMG_PEOPLE_POW"])
    if "DMG_SMALL_STEP" not in T:
        return T.get("DMG_SOLO", 1.0) if n == 1 else 1.0
    return min(1.0, T["DMG_SOLO"] + T["DMG_SMALL_STEP"] * (n - 1))


def reward_mul(w):
    return T["REWARD_MUL"] * (1 + T["REWARD_PER_WAVE"] * (w - 1))


def alive_raw(w, n):
    return (T["ALIVE_BASE"] + T["ALIVE_PER_WAVE"] * w) * (1 + T["ALIVE_PEOPLE"] * (math.sqrt(n) - 1))


def alive_goal(w, n):
    return min(T["ALIVE_MAX"], alive_raw(w, n))


def crowd_hp(w, n):
    """사람 수 체력 배율 x 동시 수 상한에 걸려 못 낸 몫(체력으로 돌림, ^ALIVE_OVER_HP: 코드에는 아직 없는 손잡이, 지금 1)."""
    return people_hp(n) * max(1.0, alive_raw(w, n) / T["ALIVE_MAX"]) ** T.get("ALIVE_OVER_HP", 1.0)


def units_for(w, n, strength=0):
    """그 웨이브 총량(단위): 감독 plan 의 공식 + 톱니(shape) + 끝없는 구간 + 변이 어림. 숨 고르기 배율은 여기서."""
    u = max(T["UNITS_K"] * n ** T.get("UNITS_POW", 0.5) * (T["UNITS_BASE"] + w * T["UNITS_PER_WAVE"]), T["UNITS_MIN"] + T["UNITS_MIN_PER_WAVE"] * w)
    u *= 1 + T["STRENGTH_STEP"] * strength
    if is_breather(w):
        u *= T["BREATHER_UNITS"]
    u *= shape(w)
    u *= CURVE.get("ENDLESS_UNITS", 1.0) ** endless_k(w) * SIM["mut_units"] ** mut_n(w)
    return u * dpow("DIFF_UNITS")


def truck_share(w):
    return min(CURVE["TRUCK_SHARE_MAX"], CURVE["TRUCK_SHARE"] + CURVE["TRUCK_SHARE_PER_WAVE"] * (w - 1))


def revive_price(w, times=0):
    return max(5, int(round(CURVE["REVIVE_U"] * unit_money(w) * (1 + CURVE["REVIVE_STEP"] * times) / 5.0)) * 5)


def repair_price(w, mul=1.0):
    return max(5, int(round(CURVE["REPAIR_U"] * unit_money(w) * mul / 5.0)) * 5)


def repair_gain(n):
    """수리 한 번에 돌아오는 트럭 체력."""
    return CURVE["TRUCK_HP"] * CURVE["REPAIR_FRAC"] / max(1, n) ** CURVE["REPAIR_POW"]


def tempo(w):
    """Director.tempo(): 종 쿨다운, 준비 틱 배율."""
    if "TEMPO_BASE" not in T:
        return 1.0
    return max(T["TEMPO_MIN"], T["TEMPO_BASE"] - T["TEMPO_PER_WAVE"] * (w - 1))


# ------------------------------------------------------------------ 웨이브 계획(감독 plan 을 줄여 따라 함)

PASSIVE = {"G14", "G15", "G36", "G51", "G54", "G12"}


def weight(sp):
    """동시 수에서 한 개체를 몇으로 셈(Director.weight): 디스플레이 8 넘는 떼 종만 SWARM_COUNTS."""
    return T["SWARM_COUNTS"] if "SWARM" in sp["roles"] and sp["cost"] >= 8 else 1


def cap_shares(counts, lead):
    """Director.capShares: 한 종 SHARE_MAX, 사람을 무시하는 종 한 종 PASSIVE_MAX 합 PASSIVE_TOTAL. 넘친 단위는 사람 치는 종에게(없으면 G01)."""
    moving = [k for k in counts if SP[k]["spawn"] != "PLACED"]
    total = sum(counts[k] for k in moving)
    if len(moving) < 2 or total <= 0 or "SHARE_MAX" not in T:
        return
    ptot = sum(counts[k] for k in moving if k in PASSIVE)
    pscale = T["PASSIVE_TOTAL"] * total / ptot if ptot > T["PASSIVE_TOTAL"] * total else 1.0
    freed = 0.0
    for k in moving:
        c = counts[k]
        cap = min(T["PASSIVE_MAX"] * total, c * pscale) if k in PASSIVE else T["SHARE_MAX"] * total
        keep = max(1, int(cap))
        if c > keep:
            freed += (c - keep) * unit(SP[k])
            counts[k] = keep
    takers = ([lead] if lead in moving and lead not in PASSIVE else []) + [k for k in moving if k not in PASSIVE and k != lead]
    for k in takers:
        if freed <= 1e-6:
            break
        room = max(0.0, T["SHARE_MAX"] * total - counts[k]) * unit(SP[k])
        add = int(min(room, freed) // unit(SP[k]))
        if add > 0:
            counts[k] += add
            freed -= add * unit(SP[k])
    if freed >= 1:
        k = "G01"
        counts[k] = counts.get(k, 0) + max(1, round(freed / unit(SP[k])))


def plan(w, n, rnd, seen, strength=0, boss=None, diff=1.0):
    """돌려줌: dict(counts, hpb 체력 보정, breather, fog, rb 보상 보정, units 총량, lead 보충에 쓸 종). diff 는 스테이지 종류의 난이도 배수."""
    elig = [s for s in SP.values() if s["impl"] and s["tier"] <= w and s["spawn"] != "DERIVED" and not (s["roles"] & {"BONUS", "BOSS"}) and s["id"] != "G43"]
    if boss:
        elig = [s for s in elig if s["spawn"] != "PLACED"]
    share = {}
    hpb = 1.0
    breather = is_breather(w) and not boss
    lead_id = None
    fog = 0 if w < 13 or not SIM["fog"] or boss else rnd.choice([1, 2, 2, 3, 3, 4, 4, 5, 2, 3, 4, 5, 3, 4, 1, 2, 3, 4, 5, 3])
    if w <= 5:
        fresh = [s for s in elig if s["id"] not in seen]
        fresh.sort(key=lambda s: s["tier"])
        if fresh:
            seen.add(fresh[0]["id"])
        others = [s for s in elig if s["id"] in seen and s["id"] != "G01"]
        for s in others:
            share[s["id"]] = 0.14
        share["G01"] = max(0.3, 1 - 0.14 * len(others))
    elif breather:
        weak = [s for s in elig if "SWARM" in s["roles"] and s["threat"] <= 2 and s["spawn"] != "PLACED"]
        share["G01"] = 0.4 if weak else 1.0
        for s in weak:
            share[s["id"]] = 0.6 / len(weak)
        hpb *= T["HP_BREATHER"]
    else:
        want = 3 if w <= 12 else 4 if w <= 20 else 4 + rnd.randint(0, 1) if w <= 30 else 5 + rnd.randint(0, 1)
        lead = [s for s in elig if s["roles"] & {"CHARGE", "SWARM"} and s["spawn"] != "PLACED"]
        base = [s for s in elig if s["roles"] & {"SHIELD", "TANK", "SUPPORT"}]
        spike = [s for s in elig if s["roles"] & {"TRAP", "DISRUPT", "RANGED"}]
        chosen = []
        for pool, sh in ((lead, 0.42), (base, 0.2), (spike, 0.2)):
            pool = [s for s in pool if s["id"] not in chosen]
            if pool:
                c = rnd.choice(pool)
                chosen.append(c["id"])
                share[c["id"]] = sh
                if lead_id is None:
                    lead_id = c["id"]
        rest = [s for s in elig if s["id"] not in chosen]
        rnd.shuffle(rest)
        k = max(0, want - len(chosen))
        for s in rest[:k]:
            share[s["id"]] = 0.18 / max(1, k) + 0.06
        if w <= 12:
            share.setdefault("G01", 0.12)
        if fog == 1:
            sky = [s for s in elig if s["spawn"] == "SKY"]
            if sky:
                share = {s["id"]: 1.0 / min(3, len(sky)) for s in sky[:3]}
    if fog >= 4:
        hpb *= T["HP_FOG_DENSE"]
    elif fog == 1:
        hpb *= T["HP_FOG_CLEAR"]
    units = units_for(w, n, strength) * dpow("DIFF_UNITS", diff)
    units_full = units
    if boss:
        units *= BOSS["units"]
    sum_move = sum(v for k, v in share.items() if SP[k]["spawn"] != "PLACED") or 1
    counts = {}
    for k, v in share.items():
        s = SP[k]
        if s["spawn"] == "PLACED":
            counts[k] = 1 + (n - 1) // 10
        else:
            counts[k] = max(1, round(units * v / sum_move / unit(s)))
    sky_only = fog == 1 and w > 5 and not breather and all(SP[k]["spawn"] == "SKY" for k in counts)
    if not breather and not sky_only and w > 5:
        cap_shares(counts, lead_id)
    move = sum(c for k, c in counts.items() if SP[k]["spawn"] != "PLACED")
    room = int(T.get("MAX_ENTRIES", 160))
    if move > room:
        kk = move / room
        for k in counts:
            if SP[k]["spawn"] != "PLACED":
                counts[k] = max(1, math.ceil(counts[k] / kk))
        hpb *= min(T["HP_OVERFLOW_MAX"], kk)
        rb = min(T["HP_OVERFLOW_MAX"], kk)
    else:
        rb = 1.0
    movers = [k for k in counts if SP[k]["spawn"] != "PLACED" and k not in PASSIVE]
    refill = [lead_id] if lead_id in movers else movers[:1] or ["G01"]
    return dict(counts=counts, hpb=hpb, breather=breather, fog=fog, rb=rb, units=units_full, lead=refill)


# ------------------------------------------------------------------ 사람

class Person:
    def __init__(self, skill, rnd):
        self.skill = skill * rnd.uniform(0.85, 1.15)
        self.max_hp = 100.0
        self.hp = 100.0
        self.state = "up"  # up, down, dead
        self.bleed = 0.0
        self.reviving = 0.0
        self.money = 0
        self.earned = 0
        self.owed = 0.0   # 아직 안 받은 처치 돈(지갑이 1초에 한 번 줌)
        self.gun = None   # (종류, 그 모델 그 등급 총 값(model_spec), 강화, 등급 0..3, 모델 키)
        self.pistol_lv = 0
        self.augs = 0
        self.stat_hp = 0
        self.stat_armor = 0
        self.mag = 0
        self.reload = 0.0
        self.busy = 0.0
        self.downs = 0
        self.shots = 0
        self.armor = 1.0
        self.bandage = 0
        self.rolled_at = 0
        self.rev_target = None
        self.revived = 0

    def weapon(self):
        if self.gun:
            kind, g, lv = self.gun[0], self.gun[1], self.gun[2]
        else:
            kind, g, lv = "PISTOL", GUNS["PISTOL"], self.pistol_lv
        k = (1 + UPG * lv) * (1 + SIM["aug_dps"]) ** self.augs
        return kind, g, k

    def dps(self):
        """한 표적에 이어 쏠 때 초당 피해(재장전 포함, 명중 전)."""
        kind, g, k = self.weapon()
        return gun_single(kind, g) * gun_model(kind)[0] * k


def gun_base(kind):
    """(한 대상 지속 화력, 떼 덤: 관통 감쇠가 있으면 덤으로 맞는 몫을 그만큼 낮춘 값). 점수 = 한 대상 x (1 + 0.5 x (떼 덤 - 1))."""
    bonus, crowd = gun_model(kind)
    keep, floor = gun_keep(kind)
    if keep < 1:
        crowd = _pierce_crowd(crowd, keep, floor)
    return gun_single(kind) * bonus, crowd


def gun_score(gun):
    """사람이 두 총을 견줄 때의 어림 값: 그 모델 그 등급의 한 표적 화력 x 떼 덤(절반만 쳐 줌) x 강화."""
    crowd = gun_base(gun[0])[1]
    return gun_single(gun[0], gun[1]) * gun_model(gun[0])[0] * (1 + 0.5 * (crowd - 1)) * (1 + UPG * gun[2])


def roll_gun(p, wave, rnd):
    """무기 랜덤 상자(Gun.rollKind 가중치: 지금 든 종류는 1/3, Gun.odds 등급)."""
    o = next(od for lim, od in ODDS if wave <= lim)
    x = rnd.randrange(100)
    r = 0
    for i, v in enumerate(o):
        if x < v:
            r = i
            break
        x -= v
    kinds = [k for k in BOXW if k in GUNS]
    if kinds:
        cur = p.gun[0] if p.gun else None
        ws = [(BOXW[k][0] + BOXW[k][1] * max(0, wave - 1)) / (3 if k == cur else 1) for k in kinds]
        kind = rnd.choices(kinds, ws)[0]
    else:
        kind = rnd.choice(["SMG", "RIFLE", "SHOTGUN", "RIFLE"])
    # 그 종류 안 모델은 고르게(Models.pick). 새 코드(Gun.GRADE)는 같은 모델 같은 등급이면 값이 같다. 옛 코드(RANGE)는 굴린 값이라 바탕 총을 조금 흔든다
    keys = list(MODELS.get(kind, {})) or [None]
    key = keys[rnd.randrange(len(keys))]
    if GUN_NEW.get("fixed_grade"):
        return kind, model_spec(kind, key, r), 0, r, key
    return kind, dict(GUNS[kind], dmg=GUNS[kind]["dmg"] * RAR_MUL[r] * rnd.uniform(0.95, 1.05)), 0, r, key


def shop(p, wave, rnd):
    """사람 하나의 쉬는 시간 장보기(보통 사람 흉내): 큰 것(무기 상자, 증강)을 모으다 되면 사고, 남은 돈의 절반까지 강화, 체력, 방어.
    총이 없으면 상자를, 있으면 강화 3단계마다 증강을, 5웨이브에 한 번은 새 상자를 노린다. 강화가 다 찼거나 돈이 남으면 증강을 더 산다."""
    bought = []
    big = None
    if not p.gun:
        big = ("roll", price("BOX", wave))
    elif p.gun[2] >= 3 * (p.augs + 1):
        big = ("aug", price("AUG", wave, p.augs))
    elif wave - p.rolled_at >= 5 and p.gun[3] < 3:
        big = ("roll", price("BOX", wave))
    if big and p.money >= big[1]:
        p.money -= big[1]
        bought.append(big[0])
        if big[0] == "roll":
            p.rolled_at = wave
            g = roll_gun(p, wave, rnd)
            # 뽑은 총은 지금 총 강화의 INHERIT 몫을 이어받음(게임과 같음): 강화 넣은 지금 총보다 나을 때만 바꿈
            if p.gun:
                g = (g[0], g[1], int(p.gun[2] * INHERIT), g[3], g[4])
            if not p.gun or gun_score(g) > gun_score(p.gun):
                p.gun = g
        else:
            p.augs += 1
        big = None
    spend = p.money * (0.5 if big else 1.0)
    for _ in range(10):
        opts = []
        lv = p.gun[2] if p.gun else p.pistol_lv
        if p.gun:
            if p.gun[2] < MAX_LEVEL:
                opts.append((price("UP_GUN", wave, p.gun[2], p.gun[3]), "up"))
        elif p.pistol_lv < MAX_LEVEL:
            opts.append((price("UP_PISTOL", wave, p.pistol_lv), "uppistol"))
        if p.stat_hp < CAP_HP and p.stat_hp < lv // 2 + 1:
            opts.append((price("HP", wave, p.stat_hp), "hp"))
        if p.stat_armor < CAP_ARMOR and p.stat_armor < lv // 2:
            opts.append((price("ARMOR", wave, p.stat_armor), "armor"))
        opts = [o for o in opts if o[0] <= spend]
        if not opts:
            break
        opts.sort()
        cost, what = opts[0]
        p.money -= cost
        spend -= cost
        bought.append(what)
        if what == "up":
            p.gun = (p.gun[0], p.gun[1], p.gun[2] + 1, p.gun[3], p.gun[4])
        elif what == "uppistol":
            p.pistol_lv += 1
        elif what == "hp":
            p.stat_hp += 1
            p.max_hp += 10
            p.hp += 10
        elif what == "armor":
            p.stat_armor += 1
            p.armor *= 0.95
    # 돈이 남는 사람: 강화가 다 찼거나 다음 강화 값의 세 배를 들고 있으면 증강을 더 뽑음
    if p.gun:
        for _ in range(3):
            a = price("AUG", wave, p.augs)
            nxt = price("UP_GUN", wave, p.gun[2], p.gun[3]) if p.gun[2] < MAX_LEVEL else 0
            if p.money >= a and (p.gun[2] >= MAX_LEVEL or p.money >= a + 2 * nxt):
                p.money -= a
                p.augs += 1
                bought.append("aug")
            else:
                break
    if p.gun and p.money >= price("AMMO", wave) and wave % 3 == 0:
        p.money -= price("AMMO", wave)
        bought.append("ammo")
    p.bandage = 0
    if "BANDAGE" in WARES and rnd.random() < SIM["bandage_share"] and p.money >= price("BANDAGE", wave):
        p.money -= price("BANDAGE", wave)
        p.bandage = 1
    return bought


# ------------------------------------------------------------------ 웨이브 한 번

class Mob:
    # 떼(마리 여럿): hp 는 맞고 있는 앞 마리, rest 는 나머지 마리(폭발, 불만 함께 깎임), mem0 은 처음 마리 수
    __slots__ = ("sp", "r", "hp", "mhp", "members", "speed", "dps", "reward", "unit", "dead", "truck", "tdps", "rest", "mem0")


class Boss:
    """보스 하나(보스.md 4절): 체력 = H x D(w)/D(wb) x P(N) x C(c)."""

    def __init__(self, key, cycle, w, n):
        b = BOSS
        self.key, self.cycle = key, cycle
        self.max = boss_hp(key, w, n, cycle)
        self.hp = self.max
        self.focus = b["focus"].get(key, 0.5)
        self.expect = boss_expect(key, n)
        self.cd, self.frac, self.dmg = b["atk"].get(key, [5.0, 0.2, 30])
        self.next_atk = b["intro"] + self.cd
        self.next_refill = 0.0
        self.shield = 0.0
        self.cuts = [0.6, 0.25]


def boss_d(w):
    return BOSS["D0"] + BOSS["D1"] * w


def boss_people(n):
    v = max(1, n) ** BOSS["P_POW"]
    return v * (BOSS["P1"] if n <= 1 else BOSS["P2"] if n == 2 else 1.0)


def boss_hp(key, w, n, cycle=1):
    return (BOSS["H"][key] * boss_d(w) / boss_d(BOSS["wb"][key]) * boss_people(n) * (1 + BOSS["C_STEP"] * (cycle - 1)) * BOSS.get("hp_scale", 1.0)
            * dpow("DIFF_BOSS_HP"))


def boss_expect(key, n):
    pts = BOSS["expect"]
    sec = pts[-1][1]
    if n <= pts[0][0]:
        sec = pts[0][1]
    else:
        for i in range(1, len(pts)):
            if n <= pts[i][0]:
                f = (n - pts[i - 1][0]) / (pts[i][0] - pts[i - 1][0])
                sec = pts[i - 1][1] + (pts[i][1] - pts[i - 1][1]) * f
                break
    return sec * (BOSS["expect_b5"] if key == "B5" else 1.0)


def sp_dps(s, w):
    """한 개체가 곁에서 주는 초당 기본 피해(dmgMul, 방어 전). 측정값(1웨이브 박자)을 박자 배율로 늘림."""
    d = SIM["dps_sp"].get(s["id"])
    if d is None:
        d = SIM["dps_threat"][max(1, min(5, s["threat"]))]
    return min(SIM["dps_cap"], d) * tempo(1) / tempo(w)


def make_mob(s, w, hpb, n, rb=1.0, ctx=None, rnd=None):
    ctx = ctx or {}
    diff = ctx.get("diff", 1.0)
    m = Mob()
    m.sp = s
    k = hp_mul(w) * hpb * crowd_hp(w, n) * dpow("DIFF_HP", diff)
    mem = SIM["members"].get(s["id"], 1) if "SWARM" in s["roles"] else 1
    m.members = m.mem0 = mem
    m.mhp = s["hp"] * k
    m.hp = m.rest = m.mhp
    m.r = SIM["placed_r"] if s["spawn"] == "PLACED" else SIM["start_r"]
    m.speed = (SIM["walk_sky"] if s["spawn"] == "SKY" else SIM["walk"]) * speed_mul(w) * (0.0 if s["spawn"] == "PLACED" else 1.0)
    if s["spawn"] == "PLACED":
        m.speed = 0.6
    dd = dmg_mul(w) * dpow("DIFF_DMG", diff)
    m.dps = sp_dps(s, w) * dd * people_dmg(n) * (SIM["ranged_mul"] if "RANGED" in s["roles"] else 1.0) * SIM["dodge"]
    m.reward = s["reward"] * reward_mul(w) * crowd_hp(w, n) ** CURVE.get("REWARD_CROWD", 1.0) * rb * (1 + SIM["fun_money"]) * ctx.get("reward", 1.0)
    m.unit = weight(s)
    m.dead = False
    # 트럭을 노리는 개체: 사람을 안 치고 트럭 곁에서 트럭을 깎는다(목표 압박 종은 늘)
    m.truck = False
    m.tdps = 0.0
    if ctx.get("truck") is not None and s["spawn"] != "PLACED":
        share = min(0.9, truck_share(w) * ctx.get("truck_mul", 1.0))
        if "OBJECTIVE" in s["roles"] or (rnd is not None and rnd.random() < share):
            m.truck = True
            base = max(sp_dps(s, w), SIM["truck_floor"] * SIM["dps_threat"][max(1, min(5, s["threat"]))] * tempo(1) / tempo(w))
            m.tdps = base * dd * CURVE["TRUCK_DMG"] / max(1, n) ** CURVE["TRUCK_POW"]
    return m


CONTRIB = {}     # id(몹) → {사람: 실제로 깎은 양}(Mobs.onHurt 의 g.contrib)
POOL = [0.0]     # 아직 안 나눈 팀 몫(Wallet.team)


def pay_kill(m, killer):
    """Wallet.kill: 보상의 WALLET_TEAM 은 팀 몫으로, 나머지는 때린 사람들이 때린 만큼(둘 이상이 함께 잡았으면 WALLET_JOINT 를 더 얹음)."""
    c = CONTRIB.pop(id(m), None) or {killer: 1.0}
    total = max(1, round(m.reward)) if m.reward > 0 else 0
    if total <= 0:
        return
    team = CURVE.get("WALLET_TEAM", 0.7)
    POOL[0] += total * team
    mine = total * (1 - team + (CURVE.get("WALLET_JOINT", 0.1) if len(c) >= 2 else 0)) / sum(c.values())
    for q, v in c.items():
        q.owed += mine * v


def settle(people):
    """Wallet.tick 의 정산(1초에 한 번): 팀 몫을 모두에게 똑같이 얹고 쌓인 몫을 올려서 준다. 모의에는 잠수가 없어 모두가 받는다."""
    if POOL[0] > 0 and people:
        each = POOL[0] / len(people)
        POOL[0] = 0.0
        for q in people:
            q.owed += each
    for q in people:
        if q.owed > 1e-9:
            pay = math.ceil(q.owed - 1e-9)
            q.owed = 0.0
            q.money += pay
            q.earned += pay


def _left(m):
    return max(0.0, m.hp) + (m.members - 1) * max(0.0, m.rest) if m.members > 1 else max(0.0, m.hp)


def hurt_mob(m, dmg, mobs, p, area=False, pieces=1):
    """몹 하나에 피해. 죽으면 True(목록에서 빼고 보상을 지갑 규칙으로 쌓음: pay_kill). 떼(마리 여럿)는 게임(G02.damage 꼴)처럼: 폭발과 불(area)은
    모든 마리가 dmg 씩, 그 밖은 맞은 한 마리만(넘친 피해는 버려짐. 산탄 알, 쌍권총 두 발처럼 pieces 개로 된 한 방은 한 개씩 차례로 앞 마리에).
    STATS 에 준 피해, 깎은 체력, 없앤 감독 단위(떼는 마리 몫), 처치(떼는 마리)를 쌓는다."""
    if m.dead:
        return False
    before = _left(m)
    STATS["dealt"] += dmg * (m.members if area else 1)
    if m.members > 1:
        kill = 0
        if area:
            m.hp -= dmg
            m.rest -= dmg
            kill = m.members if m.rest <= 0 else 1 if m.hp <= 0 else 0
            if 0 < kill < m.members:
                m.hp = m.rest
        else:
            for _ in range(max(1, pieces)):
                m.hp -= dmg / max(1, pieces)
                if m.hp <= 0:
                    kill += 1
                    if kill >= m.members:
                        break
                    m.hp = m.rest
        m.members -= kill
        dead = m.members <= 0
    else:
        kill = 1 if dmg >= m.hp else 0
        m.hp -= dmg
        dead = m.hp <= 0
    removed = before - (0.0 if dead else _left(m))
    if removed > 0:
        c = CONTRIB.setdefault(id(m), {})
        c[p] = c.get(p, 0.0) + removed
    STATS["removed"] += removed
    STATS["kills"] += kill
    STATS["units"] += kill * unit(m.sp) / max(1, m.mem0)
    if dead:
        m.dead = True
        mobs.remove(m)
        pay_kill(m, p)
    return dead


def volley(p, i, n, mobs, hist, see, budget, rnd, mul, acq):
    """사람 하나(i 번째, n 명 가운데)가 budget 초 동안 떼를 쏨: 가까운 것부터 사람마다 조금씩 나눠 맡고, 처치 뒤 acq 초 다음 표적 찾기.
    mobs 는 가까운 차례, hist 는 5블록 띠마다 수(떼 덤). 돌려줌: (남은 초, 처치 수)"""
    kind, g, k = p.weapon()
    k *= mul
    bonus, crowd = gun_model(kind)
    k *= bonus
    keep, keep_floor = gun_keep(kind)
    shot_s, tail_s, _ = gun_cycle(kind, g)
    area = GMODES.get(kind, ("HITSCAN",))[0] in AREA
    killed = 0
    while budget > 0 and mobs:
        if p.busy > 0:
            use = min(budget, p.busy)
            p.busy -= use
            budget -= use
            continue
        if p.mag <= 0:
            # 재장전은 마지막 발 직후 시작해 쏜 뒤 쉼과 겹친다(마지막 발의 박자 몫은 이미 썼다)
            p.busy = max(0.0, tail_s - shot_s)
            p.mag = g["mag"]
            continue
        idx = min(len(mobs) - 1, (i * 2) % max(1, min(len(mobs), 2 * n)))
        m = mobs[idx]
        if m.r > min(see, g["range"]):
            break
        p.mag -= 1
        p.shots += 1
        budget -= shot_s
        fall = 1 - 0.5 * max(0, min(1, (m.r / g["range"] - 0.6) / 0.4))
        acc = p.skill * max(0.35, min(1.0, 1.15 - m.r / 70))
        if g["pellets"] > 2:
            pieces = sum(1 for _ in range(g["pellets"]) if rnd.random() < acc * max(0.15, 1 - m.r / 28))
            dmg = g["dmg"] * k * fall * pieces
        else:
            pieces = g["pellets"]
            dmg = g["dmg"] * g["pellets"] * k * fall if rnd.random() < acc else 0
        if dmg <= 0:
            continue
        more = []
        if crowd > 1 and len(mobs) > 1:
            b = int(m.r // 5)
            near = hist.get(b, 0) + 0.5 * (hist.get(b - 1, 0) + hist.get(b + 1, 0))
            sat = min(1.0, near / max(1.0, m.r / 8.0) / SIM["crowd_sat"])
            extra = (crowd - 1) * sat * SIM["crowd_scale"]
            cnt = int(extra) + (1 if rnd.random() < extra - int(extra) else 0)
            more = mobs[idx + 1:idx + 1 + cnt]
        dead = hurt_mob(m, dmg, mobs, p, area, pieces)
        for oi, o in enumerate(more):
            if hurt_mob(o, dmg * max(keep_floor, keep ** (oi + 1)), mobs, p, area, pieces):
                killed += 1
        if dead:
            killed += 1
            p.busy = max(0.0, acq - shot_s) if SIM["acq_overlap"] else acq
    if budget < 0:
        # 마지막 발의 박자가 이 걸음을 넘친 몫은 다음 걸음으로(2026-11-15 고침: 전에는 버려서 발 사이가 걸음(0.5초)보다 긴 총이 걸음마다 쐈다.
        # 머스킷 초당 2발(실제 0.45), 로켓 6.6 배, 레일건과 대물 2.2 배, 산탄총 1.5 배, 저격총 1.25 배)
        p.busy += -budget
        budget = 0.0
    return budget, killed


def wave(w, people, rnd, seen, window=600, autorevive=False, log=None, strength=0, boss=None, ctx=None):
    """ctx(스테이지와 트럭): diff 난이도 배수, reward 처치 보상 배수, truck {"hp", "max"} 또는 None, truck_mul 트럭 노림 배수."""
    ctx = ctx or {}
    truck = ctx.get("truck")
    n = len(people)
    pl = plan(w, n, rnd, seen, strength, boss, ctx.get("diff", 1.0))
    counts, hpb, fog, rb = pl["counts"], pl["hpb"], pl["fog"], pl["rb"]
    queue = []
    for k, c in counts.items():
        queue += [SP[k]] * c
    rnd.shuffle(queue)
    total = len(queue)
    goal_full = alive_goal(w, n) * (BOSS["alive"] if boss else 1.0)
    bs = Boss(boss[0], boss[1], w, n) if boss else None
    intro = BOSS["intro"] if bs else 0.0
    if bs:
        window = max(window, 1500)
    mobs = []
    t = 0.0
    next_band = 1.0 + intro
    first = True
    surge = True
    phase_until = intro + rnd.uniform(T["SURGE_MIN"], T["SURGE_MAX"]) / 20
    see = {0: SIM["see"], 1: 140, 2: 48, 3: 36, 4: 25, 5: 14}[fog]
    killed = 0
    dt = SIM["dt"]
    dmg_taken = 0.0
    by = {}
    stand = False     # 최후의 저항이 켜졌나
    enraged = False
    small = people_dmg(n) if CURVE.get("BOSS_SMALL_TEAM") else 1.0
    res = dict(wipe=False, total=total, counts=counts, by=by, boss=bs.key if bs else None)

    truck0 = truck["hp"] if truck else 0.0
    # 보스 공격 피해에 곱하는 난이도(Director.bossDmgMul 의 난이도 몫). BOSS_SMALL_TEAM 이 0 이면 난이도도 적은 인원 배율도 안 곱함(Boss.hitBoss 가 안 곱하는 코드)
    bdmg = dpow("DIFF_DMG") * dpow("DIFF_DMG", ctx.get("diff", 1.0)) if CURVE.get("BOSS_SMALL_TEAM") else 1.0

    CONTRIB.clear()

    def done(**kw):
        settle(people)
        res.update(t=t, killed=killed, dmg=dmg_taken, stand=stand, enraged=enraged, total=total, **kw)
        if bs:
            res["boss_hp"] = bs.max
        if truck:
            res["truck_lost"] = (truck0 - truck["hp"]) / truck["max"]
        return res

    while t < window:
        t += dt
        # 지갑 정산(1초에 한 번)
        if int(t) != int(t - dt):
            settle(people)
        # 내보내기
        if queue and t >= next_band:
            next_band = t + T["BAND_EVERY"] / 20
            left = len(queue)
            fin = left <= T["FINAL_SHARE"] * total
            if fin:
                surge = True
            elif t >= phase_until:
                surge = not surge
                phase_until = t + (rnd.uniform(T["SURGE_MIN"], T["SURGE_MAX"]) if surge else rnd.uniform(T["LULL_MIN"], T["LULL_MAX"])) / 20
            goal = goal_full * (T["FINAL_GOAL"] if fin else 1.0 if surge else T["LULL_GOAL"])
            alive = sum(m.unit for m in mobs if m.sp["spawn"] != "PLACED")
            if first:
                k = round(max(T["FIRST_MIN"], min(goal, T["FIRST_SHARE"] * total)))
            else:
                k = math.ceil(goal - alive)
                if k < 1 and alive < goal_full * (T.get("OVERFILL_SOLO", T["OVERFILL"]) if n == 1 else T["OVERFILL"]):
                    k = 1
                k = min(k, max(3, math.ceil(goal * T["BATCH_SHARE"])))
            disp = sum(SP_COST(m.sp) for m in mobs)
            made = 0
            while queue and made < k:
                if disp + SP_COST(queue[-1]) > BUDGET:
                    break
                s = queue.pop()
                disp += SP_COST(s)
                mobs.append(make_mob(s, w, hpb, n, rb, ctx, rnd))
                made += 1
            if made:
                first = False
        # 보스: 대기열이 비면 보충(REFILL_EVERY 마다 보통 총량 x REFILL, 화나면 2배)
        if bs and bs.hp > 0 and not queue and t >= intro and t >= bs.next_refill:
            bs.next_refill = t + BOSS["refill_every"]
            add = pl["units"] * BOSS["refill"] * (2 if enraged else 1)
            for kid in pl["lead"]:
                s = SP[kid]
                c = max(1, round(add / len(pl["lead"]) / unit(s)))
                queue += [s] * c
                total += c
        if autorevive:
            for p in people:
                if p.state != "up":
                    p.state = "up"
                    p.hp = p.max_hp * SIM["revive_hp"]
        up = [p for p in people if p.state == "up"]
        if not up:
            return done(wipe=True)
        if log is not None and abs(t / 10 - round(t / 10)) < 1e-6:
            log.append((round(t), killed, len(mobs), round(statistics.mean(p.hp for p in people)), sum(p.downs for p in people), sum(p.earned for p in people)))
        # 최후의 저항(재미 장치 6, 끔이 기본)
        if SIM["last_stand"] and not stand and n >= 2 and len(up) <= math.ceil(n * 0.2) and len(up) < n:
            stand = True
        ls_fire = 1.8 if stand else 1.0     # 피해 x1.5, 연사 x1.2
        ls_armor = 0.75 if stand else 1.0
        # 움직임
        hist = {}
        for m in mobs:
            reach = SIM["ranged"] if "RANGED" in m.sp["roles"] else SIM["contact"]
            if m.r > reach:
                m.r = max(reach, m.r - m.speed * dt)
            b = int(m.r // 5)
            hist[b] = hist.get(b, 0) + 1
        mobs.sort(key=lambda m: m.r)
        # 사격
        acq = SIM["acq"] + (SIM["acq_solo_turn"] / math.sqrt(n))
        fighting = bs is not None and bs.hp > 0 and t >= intro
        if fighting and not enraged and t - intro > BOSS["enrage_at"] * bs.expect:
            enraged = True
        for i, p in enumerate(up):
            if p.reviving > 0:
                continue
            fb = bs.focus if fighting else 0.0
            budget, got = volley(p, i, n, mobs, hist, see, dt * (1 - fb), rnd, ls_fire, acq)
            killed += got
            if fighting:
                # 보스에 쏜 시간 = 몰두 몫 + 쏠 떼가 없어 남은 시간
                left = max(0.0, budget) if not mobs or p.busy <= 0 else 0.0
                acc_b = min(1.0, BOSS["acc0"] + BOSS["acc_skill"] * p.skill)
                d = p.dps() * ls_fire * (dt * fb + left) * acc_b * BOSS["weak"]
                if t < bs.shield:
                    d *= 0.25
                bs.hp -= d
        if bs and fighting:
            # 단계 전이 보호(문턱마다 2.5초 x0.25)
            while bs.cuts and bs.hp <= bs.max * bs.cuts[0]:
                bs.cuts.pop(0)
                bs.shield = t + 2.5
            if bs.hp <= 0:
                t += BOSS["death"]
                return done(boss_dead=True, boss_t=t - intro)
            # 보스 공격: 예고를 못 피한 사람만 맞음
            if t >= bs.next_atk:
                bs.next_atk = t + bs.cd * (0.7 if enraged else 1.0)
                k_t = 1 + bs.frac * (len(up) - 1)
                cnt = int(k_t) + (1 if rnd.random() < k_t - int(k_t) else 0)
                for tgt in rnd.sample(up, min(len(up), cnt)):
                    if rnd.random() < min(0.95, BOSS["dodge0"] + BOSS["dodge_skill"] * tgt.skill):
                        continue
                    d = bs.dmg * tgt.armor * (1.25 if enraged else 1.0) * (1 + 0.2 * (bs.cycle - 1)) * small * ls_armor * bdmg
                    tgt.hp -= d
                    dmg_taken += d
                    by[bs.key] = by.get(bs.key, 0) + d
                    if tgt.hp <= 0 and tgt.state == "up":
                        tgt.state = "down"
                        tgt.bleed = SIM["bleed_s"]
                        tgt.downs += 1
                        tgt.reviving = 0
        # 맞음: 곁(contact)에 든 몸은 사람마다 slots 까지만 때림(나머지는 둘레에서 밀려 기다림), 원거리 종은 ranged 거리에서
        load = {}
        up = [p for p in up if p.state == "up"]
        if truck:
            # 트럭을 노리는 개체는 트럭 곁에서 트럭만 깎는다(사람 칸을 안 씀)
            for m in mobs:
                if m.truck and m.r <= (SIM["ranged"] if "RANGED" in m.sp["roles"] else SIM["contact"]) + 0.01:
                    truck["hp"] -= m.tdps * dt * (m.members / SIM["members"].get(m.sp["id"], 1) if m.members > 1 else 1)
            if truck["hp"] <= 0:
                truck["hp"] = 0.0
                return done(wipe=True, truck_dead=True)
        if up:
            for m in mobs:
                if m.truck:
                    continue
                ranged = "RANGED" in m.sp["roles"]
                reach = SIM["ranged"] if ranged else SIM["contact"]
                if m.r <= reach + 0.01:
                    # 표적: 앞줄(people 차례 앞쪽, 사람 수의 4분의 1 안팎)에 몰림. 게임에서도 무리의 바깥쪽 사람이 먼저 맞고(표적 나누기로 안쪽까지 조금)
                    tgt = up[min(len(up) - 1, int(rnd.expovariate(1.0 / max(1.0, len(up) * SIM["front"]))))]
                    if tgt.state != "up":
                        continue
                    if not ranged:
                        if load.get(id(tgt), 0) >= SIM["slots"]:
                            continue
                        load[id(tgt)] = load.get(id(tgt), 0) + 1
                    d = m.dps * dt * tgt.armor * ls_armor * (m.members / SIM["members"].get(m.sp["id"], 1) if m.members > 1 else 1)
                    tgt.hp -= d
                    dmg_taken += d
                    by[m.sp["id"]] = by.get(m.sp["id"], 0) + d
                    if tgt.hp <= 0:
                        tgt.state = "down"
                        tgt.bleed = SIM["bleed_s"]
                        tgt.downs += 1
                        tgt.reviving = 0
        # 붕대(소모품 칸 하나): 체력이 낮으면 3초 채널 뒤 40 회복
        for p in people:
            if p.state == "up" and p.bandage > 0 and p.hp < p.max_hp * SIM["bandage_at"] and p.reviving <= 0:
                p.bandage -= 1
                p.hp = min(p.max_hp, p.hp + SIM["bandage_heal"])
                p.busy += SIM["bandage_s"]
        # 쓰러짐, 일으키기
        for p in people:
            if p.state == "down":
                if not stand:
                    p.bleed -= dt
                helper = next((q for q in people if q.state == "up" and q.reviving <= 0), None)
                if helper and sum(1 for m in mobs if m.r <= SIM["contact"]) < 6 + 2 * n:
                    helper.reviving = SIM["revive_s"]
                    helper.rev_target = p
                if p.bleed <= 0:
                    p.state = "dead"
        for p in people:
            if p.state == "up" and p.reviving > 0:
                p.reviving -= dt
                if p.reviving <= 0 and p.rev_target is not None and p.rev_target.state == "down":
                    p.rev_target.state = "up"
                    p.rev_target.hp = p.rev_target.max_hp * SIM["revive_hp"]
        if not queue and not mobs and not (bs and bs.hp > 0):
            return done()
    return done(stall=True)


def probe(w, n, kind, skill, seed, window, level=0, augs=0):
    """그 웨이브 하나만(게임 안 시험과 맞대기): 정한 총, 쓰러지면 바로 일으킴, window 초 동안 10초마다 (초, 처치, 산 수, 체력, 쓰러짐, 돈)."""
    rnd = random.Random(seed)
    people = [Person(skill, rnd) for _ in range(n)]
    for p in people:
        p.skill = skill
        if kind != "PISTOL":
            p.gun = (kind, model_spec(kind), level, 0, None)
        else:
            p.pistol_lv = level
        p.augs = augs
    log = []
    r = wave(w, people, rnd, set(), window=window, autorevive=True, log=log)
    return r, log


# 떼 막기 효율을 견줄 바탕: 물량에 맞는 종류(이 가운데 값과 견줌)
HORDE_REF = ("SMG", "LMG", "MINIGUN", "SHOTGUN", "AUTOSHOT", "DOUBLE")


def armed(kind, skill, rnd, r=0, lv=0, augs=0, mul=1.0):
    """정한 총을 든 사람 하나(명중 그대로): 그 종류 바탕 총의 등급 r, 강화 lv, 증강 augs, 한 발 피해 x mul."""
    p = Person(skill, rnd)
    p.skill = skill
    g = model_spec(kind, None, r)
    p.gun = (kind, g if mul == 1.0 else dict(g, dmg=g["dmg"] * mul), lv, r, None)
    p.augs = augs
    return p


def horde(kind, w, n, skill, seed, secs=60.0, r=0, lv=0, augs=0, mul=1.0):
    """떼 막기 효율: 웨이브 w, 인원 n 의 떼(plan 의 종 구성, make_mob 체력)를 늘 동시 목표(alive_goal)만큼 채워 두고(죽으면 띠에서 새로)
    혼자 그 종류 바탕 총(등급 r, 강화 lv, 증강 augs, 한 발 피해 x mul)으로 secs 초 쏜다. 사람은 안 맞는다. 쏘기는 wave() 와 같은 volley.
    돌려줌: dict(units 초당 없앤 감독 단위(떼는 마리 몫), kills 초당 처치(떼는 마리), waste 넘쳐 버린 피해 몫)"""
    rnd = random.Random(seed)
    pl = plan(w, n, rnd, set())
    pool = [SP[k] for k, c in pl["counts"].items() if SP[k]["spawn"] != "PLACED" for _ in range(c)] or [SP["G01"]]
    rnd.shuffle(pool)
    p = armed(kind, skill, rnd, r, lv, augs, mul)
    goal, dt = alive_goal(w, n), SIM["dt"]
    see = {0: SIM["see"], 1: 140, 2: 48, 3: 36, 4: 25, 5: 14}[pl["fog"]]
    acq = SIM["acq"] + SIM["acq_solo_turn"] / math.sqrt(n)
    mobs, made = [], 0
    alive = 0
    while alive < goal:
        m = make_mob(pool[made % len(pool)], w, pl["hpb"], n, pl["rb"])
        m.r = rnd.uniform(SIM["contact"], SIM["start_r"])
        mobs.append(m)
        made += 1
        alive += m.unit
    for k in STATS:
        STATS[k] = 0
    t = 0.0
    while t < secs:
        t += dt
        alive = sum(m.unit for m in mobs)
        while alive < goal:
            m = make_mob(pool[made % len(pool)], w, pl["hpb"], n, pl["rb"])
            mobs.append(m)
            made += 1
            alive += m.unit
        hist = {}
        for m in mobs:
            reach = SIM["ranged"] if "RANGED" in m.sp["roles"] else SIM["contact"]
            if m.r > reach:
                m.r = max(reach, m.r - m.speed * dt)
            hist[int(m.r // 5)] = hist.get(int(m.r // 5), 0) + 1
        mobs.sort(key=lambda m: m.r)
        volley(p, 0, n, mobs, hist, see, dt, rnd, 1.0, acq)
    CONTRIB.clear()
    return dict(units=STATS["units"] / secs, kills=STATS["kills"] / secs, waste=1 - STATS["removed"] / STATS["dealt"] if STATS["dealt"] > 0 else 0.0)


def boss_probe(kind, w, n, skill, seed, r=0, lv=0, augs=0, mul=1.0):
    """보스 처치 시간(초): 웨이브 w 의 보스(와 떼)를 n 명이 모두 그 종류 바탕 총으로 잡음(쓰러지면 바로 일어남). 못 잡으면 None."""
    rnd = random.Random(seed)
    people = [armed(kind, skill, rnd, r, lv, augs, mul) for _ in range(n)]
    res = wave(w, people, rnd, set(), autorevive=True, boss=boss_of(w))
    return res.get("boss_t") if res.get("boss_dead") else None


def print_horde(points, skill, seeds):
    """종류마다 떼 막기 효율(보스 웨이브가 아닌 점)과 보스 처치 시간(보스 웨이브 점). 점 = (웨이브, 인원, 등급 0..3, 강화, 증강).
    떼 효율 괄호는 물량에 맞는 종류(HORDE_REF) 가운데 값과의 비. 값을 바꿔 보려면 --set "SNIPER.dmg=175" 처럼."""
    kinds = [k for k in GUNS if k != "PISTOL"]
    hp, bp = [x for x in points if not is_boss_wave(x[0])], [x for x in points if is_boss_wave(x[0])]
    eff = {(k, x): [horde(k, x[0], x[1], skill, 300 + s, r=x[2], lv=x[3], augs=x[4]) for s in range(seeds)] for k in kinds for x in hp}
    boss = {(k, x): [boss_probe(k, x[0], x[1], skill, 300 + s, r=x[2], lv=x[3], augs=x[4]) for s in range(seeds)] for k in kinds for x in bp}
    ref = {x: statistics.median(statistics.mean(e["units"] for e in eff[(k, x)]) for k in HORDE_REF) for x in hp}
    print(f"명중 {skill}, 씨앗 {seeds}. 떼 = 초당 없앤 감독 단위(물량 바탕 {'/'.join(HORDE_REF)} 가운데 값과의 비), 초당 처치(떼는 마리), 버린 피해 %. "
          "보스 = 모두 그 총일 때 처치 초")
    head = " | ".join([f"떼 {x[0]}웨이브 {x[1]}명 등급{x[2]} +{x[3]} 증강{x[4]}" for x in hp] + [f"보스 {x[0]}웨이브 {x[1]}명" for x in bp])
    print(f"종류 | 단발 피해(일반, 전설) | {head}")
    for k in kinds:
        cells = []
        for x in hp:
            u = statistics.mean(e["units"] for e in eff[(k, x)])
            cells.append(f"{u:.2f}({u / ref[x]:.2f}) {statistics.mean(e['kills'] for e in eff[(k, x)]):.2f} {100 * statistics.mean(e['waste'] for e in eff[(k, x)]):.0f}%")
        for x in bp:
            ts = [v for v in boss[(k, x)] if v is not None]
            cells.append(f"{statistics.mean(ts):.0f}" + ("" if len(ts) == seeds else f"({len(ts)}/{seeds})") if ts else "-")
        print(f"{k}{'*' if k in HORDE_REF else ''} | {model_spec(k, None, 0)['dmg']:.1f}, {model_spec(k, None, 3)['dmg']:.1f} | " + " | ".join(cells))


_COST = {}


def SP_COST(s):
    c = _COST.get(s["id"])
    if c is None:
        c = max(s["cost"], 1)
        _COST[s["id"]] = c
    return c


STAGE_PLAIN = dict(diff=1.0, reward=1.0, truck_mul=1.0, repair=1.0, heal=0.0, aug=0.0, money=0.0)


def stage_roll(spec, rnd):
    """스테이지 종류 값 하나를 굴림(값이 [낮음, 높음] 이면 같은 분위로: 어려울수록 보상도 큼)."""
    q = rnd.random()

    def val(x, d):
        if x is None:
            return d
        if isinstance(x, (list, tuple)):
            return x[0] + (x[1] - x[0]) * q
        return x
    return dict(diff=val(spec.get("diff"), 1.0), reward=val(spec.get("reward"), 1.0), truck_mul=val(spec.get("truck"), 1.0),
                repair=val(spec.get("repair"), 1.0), heal=val(spec.get("heal"), 0.0), aug=val(spec.get("aug"), 0.0), money=val(spec.get("money"), 0.0))


def vote(stages, rnd, state):
    """세 갈래(서로 다른 종류)를 무게로 뽑고 사람 흉내(SIM policy)로 하나 고른다. state: truck 트럭 체력 비율, dead 죽은 사람 몫, strength 감독 세기."""
    pool = [(k, v.get("w", 1)) for k, v in stages.items()]
    opts = []
    for _ in range(min(3, len(pool))):
        x = rnd.random() * sum(wt for _, wt in pool)
        for i, (k, wt) in enumerate(pool):
            x -= wt
            if x <= 0 or i == len(pool) - 1:
                opts.append(k)
                pool.pop(i)
                break
    rolled = {k: stage_roll(stages[k], rnd) for k in opts}
    pol = SIM.get("policy", "smart")
    if pol == "random":
        pick = rnd.choice(opts)
    elif pol == "greedy":
        pick = max(opts, key=lambda k: rolled[k]["reward"])
    elif pol == "safe":
        pick = min(opts, key=lambda k: rolled[k]["diff"])
    else:
        rest = [k for k in opts if rolled[k]["heal"] > 0 or rolled[k]["repair"] < 1]
        work = [k for k in opts if k not in rest]
        if (state["truck"] < 0.5 or state["dead"] > 0.2) and rest:
            pick = rest[0]
        elif state["strength"] >= 2 and work:
            pick = max(work, key=lambda k: rolled[k]["reward"])
        else:
            easy = [k for k in work if rolled[k]["diff"] <= 1.05]
            pick = max(easy, key=lambda k: rolled[k]["reward"]) if easy else min(opts, key=lambda k: rolled[k]["diff"])
    return pick, rolled[pick]


def run(n, skill, seed, max_wave=None, trace=None):
    """한 판. 돌려줌: dict(reach 게임 오버가 난 웨이브(끝까지 가면 max_wave), ended 엔딩 보스를 잡았나, t 판 길이 초, t_end 엔딩까지 초,
    cause 'wipe' 모두 쓰러짐 | 'truck' 트럭이 터짐 | None)."""
    rnd = random.Random(seed)
    people = [Person(skill, rnd) for _ in range(n)]
    seen = set()
    strength = 0
    clock = SIM["prep_s"]
    ended, t_end = False, None
    endw = int(CURVE["ENDING_WAVE"])
    maxw = int(max_wave or CURVE.get("MAX_WAVE", 80))
    stages = CURVE.get("STAGES")
    slen = max(1, int(CURVE.get("STAGE_LEN", 2)))
    ship_from, ship_to = int(CURVE.get("SHIP_FROM", 0)), int(CURVE.get("SHIP_TO", 0))
    truck = {"hp": float(CURVE["TRUCK_HP"]), "max": float(CURVE["TRUCK_HP"])} if CURVE.get("TRUCK") else None
    kind, cur = "전투", dict(STAGE_PLAIN)
    paid = bool(CURVE.get("PAID_REVIVE"))
    for w in range(1, maxw + 1):
        downs0 = sum(p.downs for p in people)
        boss = boss_of(w)
        if stages:
            if ship_from and ship_from <= w <= ship_to:
                sd = CURVE["SHIP_DIFF"]
                kind = "건조"
                cur = dict(STAGE_PLAIN, diff=sd[min(w - ship_from, len(sd) - 1)], reward=CURVE["SHIP_REWARD"], truck_mul=CURVE["SHIP_TRUCK"])
            elif ship_to and w > ship_to:
                kind, cur = "전투", dict(STAGE_PLAIN)
            elif w > 1 and (w - 1) % slen == 0:
                state = dict(truck=truck["hp"] / truck["max"] if truck else 1.0, dead=sum(1 for p in people if p.state == "dead") / n, strength=strength)
                kind, cur = vote(stages, rnd, state)
                clock += CURVE.get("VOTE_S", 0.0)
        ctx = dict(truck=truck)
        if not boss:
            ctx.update(diff=cur["diff"], reward=cur["reward"], truck_mul=cur["truck_mul"])
        r = wave(w, people, rnd, seen, strength=strength, boss=boss, ctx=ctx)
        clock += r["t"]
        # 감독 세기(Director.beginBreak): 쓰러짐 0 이고 평균 체력 0.75 이상이면 +1(3 까지), 절반 넘게 쓰러지거나 0.35 아래면 -1(-2 까지)
        upn = [p for p in people if p.state == "up"]
        avg = statistics.mean(p.hp / p.max_hp for p in upn) if upn else 0
        dw = sum(p.downs for p in people) - downs0
        if dw == 0 and avg >= 0.75:
            strength = min(3, strength + 1)
        elif dw >= max(1, math.ceil(n * 0.5)) or avg < 0.35:
            strength = max(-2, strength - 1)
        row = None
        if trace is not None:
            guns = [p.gun for p in people if p.gun]
            row = dict(w=w, t=r["t"], wipe=r["wipe"], total=r["total"], downs=sum(p.downs for p in people) / n,
                       hp=avg, earned=statistics.mean(p.earned for p in people),
                       dps=statistics.mean(p.dps() for p in people), goal=alive_goal(w, n) * (BOSS["alive"] if boss else 1.0), str=strength,
                       wdowns=dw / n, dead=sum(1 for p in people if p.state == "dead") / n,
                       lvl=statistics.mean(g[2] for g in guns) if guns else 0, rar=statistics.mean(g[3] for g in guns) if guns else 0,
                       augs=statistics.mean(p.augs for p in people), shp=statistics.mean(p.stat_hp for p in people),
                       sarm=statistics.mean(p.stat_armor for p in people), money=statistics.mean(p.money for p in people),
                       maxhp=statistics.mean(p.max_hp for p in people), boss=r.get("boss"), boss_t=r.get("boss_t", 0), boss_hp=r.get("boss_hp", 0),
                       enraged=r.get("enraged", False), stall=r.get("stall", False), clock=clock, kind=kind if not boss else "보스",
                       truck=truck["hp"] / truck["max"] if truck else 1.0, truck_lost=r.get("truck_lost", 0.0), repair=0.0, revive=0.0, sdiff=cur["diff"] if not boss else 1.0)
            trace.append(row)
        if r["wipe"]:
            return dict(reach=w, ended=ended, t=clock, t_end=t_end, cause="truck" if r.get("truck_dead") else "wipe")
        won = boss is not None and r.get("boss_dead")
        if won:
            # 보스 보상(보스.md 3.6): 모두 재화, 증강, 부활, 가득 찬 체력. 트럭도 조금 고쳐짐
            money = BOSS["reward_per_wave"] * w * (1 + BOSS["C_STEP"] * (boss[1] - 1))
            for p in people:
                p.money += round(money)
                p.earned += round(money)
                p.augs += BOSS["augs_b5"] if boss[0] == "B5" else BOSS["augs"]
                if p.state != "dead" or not paid or CURVE.get("BOSS_REVIVE", 1):
                    p.state = "up"
                    p.hp = p.max_hp
            if truck:
                truck["hp"] = min(truck["max"], truck["hp"] + truck["max"] * CURVE.get("BOSS_TRUCK_HEAL", 0.0))
            if w == endw and not ended:
                ended, t_end = True, clock
                for p in people:
                    p.money += BOSS["ending_money"]
        # 스테이지 끝 덤(종류마다: 회복, 증강, 재화)
        if stages and w % slen == 0 and not (ship_from and w >= ship_from):
            for p in people:
                if p.state == "dead":
                    continue
                if cur["heal"] > 0 and p.state == "up":
                    p.hp = min(p.max_hp, p.hp + p.max_hp * cur["heal"])
                if cur["aug"] > 0:
                    p.augs += int(cur["aug"]) + (1 if rnd.random() < cur["aug"] - int(cur["aug"]) else 0)
                if cur["money"] > 0:
                    p.money += round(cur["money"] * unit_money(w))
        # 쉬는 시간
        clock += CURVE["ENDING_BREAK_S"] if won and w == endw else SIM["break_s"]
        for p in people:
            if p.state == "up":
                p.hp = min(p.max_hp, p.hp + p.max_hp * T.get("BREAK_HEAL", 0.25))
            elif p.state == "down":
                p.state = "up"
                p.hp = p.max_hp
            elif not paid:
                p.money = int(p.money * (1 - CURVE.get("DEATH_LOSS", 0.2)))
                p.state = "up"
                p.hp = p.max_hp
        if paid:
            # 죽은 사람은 서 있는 사람이 돈을 내야 일어남(화력이 큰 사람부터, 가장 돈 많은 사람이 냄)
            for p in sorted((q for q in people if q.state == "dead"), key=lambda q: -q.dps()):
                cost = revive_price(w, p.revived)
                payer = max((q for q in people if q.state == "up"), key=lambda q: q.money, default=None)
                if payer is None or payer.money < cost:
                    continue
                payer.money -= cost
                p.revived += 1
                p.state = "up"
                p.hp = p.max_hp
                p.money = int(p.money * (1 - CURVE.get("DEATH_LOSS", 0.2)))
                if row is not None:
                    row["revive"] += cost / n
        if truck and truck["hp"] < truck["max"] * CURVE["REPAIR_TO"]:
            # 수리(사람 흉내): 돈 많은 사람부터 가진 돈의 REPAIR_SPEND 까지 눌러 REPAIR_TO 까지
            pr, gain = repair_price(w, cur["repair"]), repair_gain(n)
            need = truck["max"] * CURVE["REPAIR_TO"] - truck["hp"]
            for q in sorted((q for q in people if q.state == "up"), key=lambda q: -q.money):
                room = q.money * CURVE["REPAIR_SPEND"]
                while need > 0 and room >= pr:
                    q.money -= pr
                    room -= pr
                    truck["hp"] = min(truck["max"], truck["hp"] + gain)
                    need -= gain
                    if row is not None:
                        row["repair"] += pr / n
                if need <= 0:
                    break
        for p in people:
            p.reviving = 0
            p.mag = 0
            if p.state == "up":
                shop(p, w + 1, rnd)
            p.downs = 0 if trace is None else p.downs
            p.earned = 0
    return dict(reach=maxw, ended=ended, t=clock, t_end=t_end, cause=None)


# ------------------------------------------------------------------ 여러 판 돌리기

_LOADED = None


def _task(a):
    """다른 프로세스에서 한 판: (값 묶음 이름, 값 묶음, 사람 수, 솜씨, 씨앗)."""
    global _LOADED
    name, over, n, skill, seed = a
    key = (name, json.dumps(over, sort_keys=True, ensure_ascii=False))
    if _LOADED != key:
        load(over, name)
        _LOADED = key
    r = run(n, skill, seed)
    return name, n, skill, seed, r["reach"], r["ended"], r["t"], r["t_end"], r["cause"]


def run_many(tasks, jobs):
    if jobs <= 1 or len(tasks) < 8:
        return [_task(a) for a in tasks]
    from concurrent.futures import ProcessPoolExecutor
    with ProcessPoolExecutor(max_workers=jobs) as ex:
        return list(ex.map(_task, tasks, chunksize=max(1, len(tasks) // (jobs * 8))))


def quant(xs, q):
    xs = sorted(xs)
    if not xs:
        return 0
    i = (len(xs) - 1) * q
    lo, hi = int(math.floor(i)), int(math.ceil(i))
    return xs[lo] + (xs[hi] - xs[lo]) * (i - lo)


def summarize(rows, marks=(10, 20, 30)):
    """rows: (reach, ended, t, t_end, cause) 여럿 → 도달 웨이브 분포, 엔딩 비율, 판 길이, 그 웨이브를 넘긴 판의 몫, 트럭이 터져 끝난 몫."""
    reach = [r[0] for r in rows]
    ends = [r for r in rows if r[1]]
    over = [r for r in rows if r[4]]
    return dict(n=len(rows), med=statistics.median(reach), lo=min(reach), hi=max(reach), q1=quant(reach, 0.25), q3=quant(reach, 0.75),
                end=len(ends) / max(1, len(rows)), min_all=statistics.median(r[2] for r in rows) / 60,
                min_end=statistics.median(r[3] for r in ends) / 60 if ends else 0,
                past={str(m): sum(1 for x in reach if x > m) / max(1, len(rows)) for m in marks},
                truck=sum(1 for r in over if r[4] == "truck") / max(1, len(over)))


def wave_table(n, skill, seeds, show):
    rows = {}
    for s in range(seeds):
        tr = []
        run(n, skill, 1000 + s, trace=tr)
        for r in tr:
            rows.setdefault(r["w"], []).append(r)
    out = []
    for w in show:
        rs = rows.get(w)
        if not rs:
            continue
        f = lambda k: statistics.mean(r[k] for r in rs)
        out.append(dict(w=w, runs=len(rs), boss=rs[0]["boss"], **{k: f(k) for k in ("t", "goal", "total", "downs", "wdowns", "hp", "earned", "dps", "lvl", "rar", "augs",
                                                                                   "shp", "sarm", "money", "maxhp", "boss_t", "boss_hp", "clock", "dead",
                                                                                   "truck", "truck_lost", "repair", "revive", "sdiff")}))
    return out


def print_curve(n, skill, seeds, upto):
    """파워커브 표(문서용 마크다운): 감독 공식 열 + 모의 열(첫 씨앗들 평균)."""
    tab = {r["w"]: r for r in wave_table(n, skill, seeds, range(1, upto + 1))}
    print(f"| 웨이브 | 자리 | 총량(단위) | 동시 목표 | 체력 배율 | 피해 배율 | 박자 | 걸린 초 | 몰려온 수 | 1인 쓰러짐 | 끝 체력 | 1인 벌이 | 1인 화력 | 강화 | 증강 | 살아남은 판 |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for w in range(1, upto + 1):
        b = boss_of(w)
        kind = (b[0] if b else "보스 자리") if is_boss_wave(w) else "숨 고르기" if is_breather(w) else ""
        u = units_for(w, n) * (BOSS["units"] if b else 1.0)
        r = tab.get(w)
        sim = (f"{r['t']:.0f} | {r['total']:.0f} | {r['wdowns']:.2f} | {r['hp']:.2f} | {r['earned']:.0f} | {r['dps']:.0f} | {r['lvl']:.1f} | {r['augs']:.1f} | {r['runs']}/{seeds}"
               if r else "- | - | - | - | - | - | - | - | 0")
        print(f"| {w} | {kind} | {u:.0f} | {alive_goal(w, n) * (BOSS['alive'] if b else 1.0):.0f} | {hp_mul(w) * crowd_hp(w, n):.2f} | {dmg_mul(w) * people_dmg(n):.2f} | {tempo(w):.2f} | {sim} |")


def print_bosses(people, skill, seeds):
    """보스 체력과 걸리는 시간: 공식(사람 수 x D(w) x 명중 x 몰두 x 약점)과 모의(잡은 판 평균)."""
    waves = [w for w in range(1, int(CURVE.get("MAX_WAVE", 80)) + 1) if boss_of(w)][:5]
    acc = min(1.0, BOSS["acc0"] + BOSS["acc_skill"] * skill)
    print("보스 | 웨이브 | 사람 | 체력 | 공식 초(D(w) 기준) | 모의 초(잡은 판) | 모의 1인 화력 | 잡은 판")
    for n in people:
        tab = {r["w"]: r for r in wave_table(n, skill, seeds, waves)}
        for w in waves:
            key, c = boss_of(w)
            hp = boss_hp(key, w, n, c)
            est = hp / (n * boss_d(w) * acc * BOSS["focus"][key] * BOSS["weak"])
            r = tab.get(w)
            print(f"{key} | {w} | {n} | {hp:,.0f} | {est:.0f} | " + (f"{r['boss_t']:.0f} | {r['dps']:.0f} | {r['runs']}/{seeds}" if r else "- | - | 0"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seeds", type=int, default=12)
    ap.add_argument("--people", default="1,3,5,10,20,30")
    ap.add_argument("--skill", default="0.55", help="명중(솜씨). 쉼표로 여럿(0.5,0.7,0.85)")
    ap.add_argument("--waves", default="1,3,5,8,10,13,16,20,25,30,35")
    ap.add_argument("--table", action="store_true", help="웨이브별 표(첫 씨앗들 평균)")
    ap.add_argument("--curve", type=int, default=0, help="이 사람 수의 파워커브 표(마크다운, 1~--upto 웨이브)")
    ap.add_argument("--upto", type=int, default=40)
    ap.add_argument("--bosses", action="store_true", help="보스 체력과 걸리는 시간 표")
    ap.add_argument("--probe", default="", help="웨이브:총:초[:강화] 를 쉼표로(예 1:PISTOL:60,5:PISTOL:50,10:RIFLE:50) 혼자, 명중 --skill")
    ap.add_argument("--horde", default="", help="웨이브:인원:등급(0..3):강화:증강 을 쉼표로(예 15:30:1:15:5,20:30:1:17:6): 종류마다 떼 막기 효율, 보스 웨이브면 보스 처치 초")
    ap.add_argument("--set", default="", help="모의에서만 바꿔 보기: Tune 이름=값, 종.hp=값, SIM 이름=값, 총.dmg=값, curve.이름=값, boss.이름=값, ware.이름=바탕/오름/단계")
    ap.add_argument("--set-file", default="", help="값 파일(json: tune, curve, boss, ware, sim, species, gun, odds, caps). --set 은 그 위에 덮음")
    ap.add_argument("--compare", default="", help="값 파일 둘 이상을 쉼표로: 나란히 견줌('-' 는 지금 코드 그대로)")
    ap.add_argument("--jobs", type=int, default=1, help="프로세스 수(판을 나눠 돌림)")
    ap.add_argument("--json", default="", help="요약을 json 으로도 저장")
    a = ap.parse_args()
    extra = parse_set(a.set) if a.set else None
    ns = [int(x) for x in a.people.split(",")]
    skills = [float(x) for x in a.skill.split(",")]
    configs = []
    for path in (a.compare.split(",") if a.compare else [a.set_file or "-"]):
        name = "지금" if path in ("-", "") else Path(path).stem
        configs.append((name, read_config(path, extra)))
    load(configs[0][1], configs[0][0])
    if a.probe:
        for item in a.probe.split(","):
            f = item.split(":")
            w, kind, win = int(f[0]), f[1], float(f[2])
            lv = int(f[3]) if len(f) > 3 else 0
            for sd in range(3):
                r, log = probe(w, 1, kind, skills[0], 50 + sd, win, lv)
                print(f"웨이브 {w} {kind}+{lv} 씨앗 {sd}: 총 {r['total']} 처치 {r['killed']} 걸림 {r['t']:.0f}초 {r['counts']} 피해 {dict((k, round(v)) for k, v in r['by'].items())} | (초, 처치, 산, 체력, 쓰러짐, 돈) {log}")
        return
    if a.horde:
        print_horde([tuple(int(v) for v in x.split(":")) for x in a.horde.split(",")], skills[0], a.seeds)
        return
    if a.curve:
        print_curve(a.curve, skills[0], a.seeds, a.upto)
        return
    if a.bosses:
        for name, over in configs:
            load(over, name)
            print(f"[{name}] 명중 {skills[0]}")
            print_bosses(ns, skills[0], a.seeds)
        return
    show = [int(x) for x in a.waves.split(",")]
    tasks = [(name, over, n, sk, 1000 + s) for name, over in configs for sk in skills for n in ns for s in range(a.seeds)]
    got = {}
    for name, n, sk, seed, reach, ended, t, t_end, cause in run_many(tasks, a.jobs):
        got.setdefault((name, sk, n), []).append((reach, ended, t, t_end, cause))
    summary = {}
    for name, over in configs:
        load(over, name)
        endw = int(CURVE["ENDING_WAVE"])
        print(f"[{name}] 엔딩 {endw}웨이브, 보스 {'켬' if CURVE.get('BOSSES', 1) else '끔'} " + " ".join(f"{w}:{boss_of(w)[0]}" for w in range(1, 61) if boss_of(w)))
        print(f"  곡선: 난이도 {CURVE['DIFF']}(총량^{CURVE['DIFF_UNITS']} 체력^{CURVE['DIFF_HP']} 피해^{CURVE['DIFF_DMG']} 보스 체력^{CURVE['DIFF_BOSS_HP']}) 톱니 앞 {CURVE.get('PRE_BOSS')} 뒤 {CURVE.get('POST_BOSS')} "
              f"끝없는 구간 {CURVE.get('ENDLESS_FROM')}~ 피해 x{CURVE.get('ENDLESS_DMG')} 총량 x{CURVE.get('ENDLESS_UNITS')} 변이 {CURVE.get('MUT_FROM')}부터 {CURVE.get('MUT_EVERY')}마다 "
              f"보상 무리 지수 {CURVE.get('REWARD_CROWD')} | 보스 H {BOSS['H']} 기준 웨이브 {BOSS['wb']} D(w)={BOSS['D0']}+{BOSS['D1']}w")
        print(f"  원정: 트럭 {'체력 %g, 노림 %g+%g/웨이브(<=%g), 피해 x%g / 사람^%g, 수리 %gU 로 %g%% / 사람^%g' % (CURVE['TRUCK_HP'], CURVE['TRUCK_SHARE'], CURVE['TRUCK_SHARE_PER_WAVE'], CURVE['TRUCK_SHARE_MAX'], CURVE['TRUCK_DMG'], CURVE['TRUCK_POW'], CURVE['REPAIR_U'], CURVE['REPAIR_FRAC'] * 100, CURVE['REPAIR_POW']) if CURVE.get('TRUCK') else '없음'}, "
              f"부활 {'%gU x (1 + %g x 횟수)' % (CURVE['REVIVE_U'], CURVE['REVIVE_STEP']) if CURVE.get('PAID_REVIVE') else '저절로'}(죽음 손실 {CURVE.get('DEATH_LOSS')}), "
              f"갈래 {list(CURVE['STAGES']) if CURVE.get('STAGES') else '없음'}, 건조 방어 {CURVE.get('SHIP_FROM')}~{CURVE.get('SHIP_TO')} x{CURVE.get('SHIP_DIFF')}")
        for note in NOTES:
            print("  알림: " + note)
        print(f"  Tune: units K={T['UNITS_K']} pow={T.get('UNITS_POW', 0.5)} min={T['UNITS_MIN']}+{T['UNITS_MIN_PER_WAVE']}w alive=({T['ALIVE_BASE']}+{T['ALIVE_PER_WAVE']}w)x(1+{T['ALIVE_PEOPLE']}(√n-1))<={T['ALIVE_MAX']:.0f} "
              f"hp x{T['HP_GROWTH']}({T['HP_LATE_FROM']:.0f} 뒤 x{T['HP_GROWTH_LATE']}) 인원 체력^{T.get('HP_POW', 0)} dmg min({T['DMG_MAX']}, {T.get('DMG_BASE', 1)}+{T['DMG_PER_WAVE']}w) 적은 인원 (n/{T.get('DMG_FULL_PEOPLE', 0):.0f})^{T.get('DMG_PEOPLE_POW', 0)} 체력 밑 {T.get('HP_BASE', 1)} "
              f"reward x{T['REWARD_MUL']}+{T['REWARD_PER_WAVE']}w, 몹 예산 {BUDGET}")
        print(f"  G01 체력 {SP['G01']['hp']} 보상 {SP['G01']['reward']}, 권총 {GUNS['PISTOL']['dmg']} x {GUNS['PISTOL']['rps']}/초, 무기 상자 {price('BOX', 1)}원, 증강 {price('AUG', 1)}원")
        for sk in skills:
            print(f"  명중 {sk}: 인원 | 도달 웨이브 중앙값 (최소..최대, 사분위) | 10 넘김 | 20 넘김 | 30 넘김 | 엔딩 | 트럭으로 끝난 몫 | 판 길이 분(모두 / 엔딩까지)")
            for n in ns:
                s = summarize(got[(name, sk, n)])
                summary[f"{name}|{sk}|{n}"] = s
                print(f"  {n:>6} | {s['med']:>5.1f} ({s['lo']}..{s['hi']}, {s['q1']:.0f}~{s['q3']:.0f}) | {s['past']['10'] * 100:>3.0f}% | {s['past']['20'] * 100:>3.0f}% | "
                      f"{s['past']['30'] * 100:>3.0f}% | {s['end'] * 100:>3.0f}% | {s['truck'] * 100:>3.0f}% | {s['min_all']:.0f} / {s['min_end']:.0f}")
        if a.table:
            for n in ns:
                print(f"\n  [{name}] 인원 {n} 명중 {skills[-1]}: 웨이브 | 걸린 초 | 동시 목표 | 총수 | 그 웨이브 1인 쓰러짐 | 죽어 있는 몫 | 끝 체력 | 1인 번 돈 | 1인 화력 | 강화 | 등급 | 증강 | 체력, 방어 스탯 | 남은 돈 | 트럭(끝, 잃음) | 1인 수리, 부활 값 | 판 시계(분) | 살아남은 판")
                for r in wave_table(n, skills[-1], min(a.seeds, 6), show):
                    bt = f" 보스 {r['boss']} {r['boss_t']:.0f}초 체력 {r['boss_hp']:,.0f}" if r["boss"] else ""
                    print(f"  {r['w']:>5} | {r['t']:>5.0f} | {r['goal']:>4.0f} | {r['total']:>4.0f} | {r['wdowns']:>4.2f} | {r['dead']:>4.2f} | {r['hp']:>4.2f} | {r['earned']:>5.0f} | {r['dps']:>5.0f} | {r['lvl']:>4.1f} | {r['rar']:>3.1f} | "
                          f"{r['augs']:>4.1f} | {r['shp']:>4.1f}, {r['sarm']:>4.1f} | {r['money']:>5.0f} | {r['truck']:>4.2f}, {r['truck_lost']:>4.2f} | {r['repair']:>4.0f}, {r['revive']:>4.0f} | {r['clock'] / 60:>5.1f} | {r['runs']}{bt}")
    if a.json:
        Path(a.json).write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding="utf-8")


load()

if __name__ == "__main__":
    main()
