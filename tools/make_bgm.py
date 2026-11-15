"""배경 음악(브금): 내려받은 곡(ref/bgm/)을 자르고 다듬어 팩 바탕 pack_src 에 넣는다. 합성하지 않는다(2026-11-11 사용자 정정).
2026-11-12 사용자 요청 "락 느낌의 신나고 템포 넘치는 브금으로 교체해 봐, 싸울 때": 웨이브 곡(낮음, 중간, 높음)과 보스전 곡을 기타가 분명한
140 BPM 넘는 락, 메탈로 바꿈(대기, 쉬는 시간, 전멸 곡은 그대로). 보스전 시작 신호는 받은 효과음(뿔나팔, ref/sfx).

python -X utf8 tools/make_bgm.py            (그 뒤 build_pack.py. 길이가 바뀌면 플러그인은 그대로: Java 가 팩의 music.json 을 읽음)
python -X utf8 tools/make_bgm.py --check    (위에 더해 build/bgm/check.png 그림, build/bgm/preview/ 미리듣기, 경계 검사)
python -X utf8 tools/make_bgm.py --listen   (위에 더해 사용자가 들어 볼 mp3: build/bgm/listen_rock/<차례>_<쓰는 곳>_<곡>.mp3)
python -X utf8 tools/make_bgm.py --listen-trip (위에 더해 원정 네 막과 cue 곡을 들어 볼 mp3: build/bgm/listen_trip/<차례>_<쓰는 곳>_<곡>.mp3)
python -X utf8 tools/make_bgm.py --info     (ref/bgm/_info.json 만 다시 씀: sha256)

2026-11-12 원정(aud_trip, docs/모듈/원정.md 2.1절 네 막): 막마다 싸움 곡 묶음(ACTS: 낮음, 높음, 보스), 이동, 추격전, 최후의 저항, 크레딧,
엔딩 새벽 곡(CUES), 보스 앞 드론, 마지막 단계, 쓰러짐 신호. 새 곡은 incompetech 7곡과 OpenGameArt.org 11곡(OGA: CC0, CC BY 3.0, CC BY 4.0,
곡마다 글 페이지의 License(s) 칸을 읽음). 굽기는 build/bgm/stage 에 다 구운 뒤 한꺼번에 pack_src 로 옮긴다(굽는 몇 분 동안 pack_src 가 반쯤
빈 채로 다른 작업자의 팩에 들어가지 않게).

- 곡은 incompetech.com(Kevin MacLeod, CC BY 4.0)에서 받은 mp3 원본(ref/bgm/incompetech/). 출처 표는 docs/브금출처.md, 기계용 ref/bgm/_info.json.
  CC BY 곡의 저작자 표시는 팩 CREDITS.txt [7] 절(tools/props/build_props.py write_credits 가 music_credit_lines() 를 부름. 이 도구도
  build/props/pack_layer/CREDITS.txt 의 [7] 절을 바로 고쳐 써서 build_props 를 다시 돌리지 않아도 팩에 들어감).
- 바꿔 끼우기: art/bgm/<이름>.ogg 가 있으면 그 파일을 그대로 쓴다(이름은 아래 CUTS 의 키: lobby, break_a, break_b, break_end, sting,
  wave_low_1 ...). 길이는 그 파일에서 잰다.
- 쓰는 곳: pack_src/assets/gave/sounds/music/*.ogg(스테레오, libvorbis, 스트리밍), pack_src/assets/gave/sounds.json 의 music.* 키
  (다른 키는 그대로), pack_src/assets/gave/music.json(곡마다 틱 길이, 겹침 틱, 단계 목록: kr.gave.game.Music 이 팩 zip 에서 읽음).
- 받은 파일은 자료로만: ffmpeg 로 풀고, 자르고, 크기를 맞추고, 다시 인코딩한다.
"""
import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
REF = ROOT / "ref/bgm"
ART = ROOT / "art/bgm"
ASSETS = ROOT / "pack_src/assets/gave"
OUT_DIR = ASSETS / "sounds/music"
BUILD = ROOT / "build/bgm"
NS = "gave"
SR = 44100
QUALITY = 3  # libvorbis -q:a (3 = 스테레오 약 110 kbps)
TARGET_RMS_DB = -20.0  # 소리 나는 곳 RMS 목표(총소리, 게이브 신호를 가리지 않게)
PEAK_DB = -1.0
GOT = "2026-11-11"

BY = ("CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/")
# 받은 곡(곡 페이지 = incompetech 트랙 페이지, 그 틀이 'Licensed under Creative Commons: By Attribution 4.0 License' 를 적음)
SOURCES = {
    "Gone Beyond": "USUAN1100752",
    "Childrens Theme": "USUAN1100296",
    "Industrial Music Box": "USUAN1100812",
    "Penumbra": "USUAN1200105",
    "Static Motion": "USUAN1200062",
    "Chase Pulse": "USUAN1100383",
    "Darkest Child": "USUAN1100783",
    "Chase Pulse Faster": "USUAN1100384",
    "Zombie Chase": "USUAN1100005",
    "Dopplerette": "USUAN1100659",
    "Final Count": "USUAN1100657",
    # 2026-11-12 락, 메탈(incompetech 장르 '락', pieces.json 의 bpm, 악기, 느낌으로 고름. 곡 페이지 틀 문구는 위와 같음)
    "Exhilarate": "USUAN1300028",
    "Neolith": "USUAN1100140",
    "Pump": "USUAN1200012",
    "Ready Aim Fire": "USUAN1500002",
    "Metalmania": "USUAN1700023",
    "Summon the Rawk": "USUAN1200050",
    "Burn The World Waltz ": "USUAN2100009",
    "Feral Angel Waltz ": "USUAN2100010",
    "Aggressor": "USUAN1700051",
    # 2026-11-12 원정(aud_trip): 2막 무거운 락, 최후의 저항, 크레딧, 엔딩 새벽, 보스 앞 드론
    "Big Rock": "USUAN1100305",
    "Waltz Primordial ": "USUAN2100008",
    "Take the Lead": "USUAN1100695",
    "Killers": "USUAN1600024",
    "Motherlode": "USUAN1500033",
    "Eternal Hope": "USUAN1100238",
    "Lightless Dawn": "USUAN1100655",
}
# 2026-11-12 에 받은 곡(락, 메탈 9곡 + 원정 7곡)
ROCK = {"Exhilarate", "Neolith", "Pump", "Ready Aim Fire", "Metalmania", "Summon the Rawk", "Burn The World Waltz ", "Feral Angel Waltz ", "Aggressor",
        "Big Rock", "Waltz Primordial ", "Take the Lead", "Killers", "Motherlode", "Eternal Hope", "Lightless Dawn"}
# 파일 이름 끝에 빈칸이 있는 곡(받은 파일 이름 그대로 'Burn The World Waltz .mp3')
TITLES = {"Childrens Theme": "Children's Theme", "Burn The World Waltz ": "Burn The World Waltz", "Feral Angel Waltz ": "Feral Angel Waltz",
          "Waltz Primordial ": "Waltz Primordial"}
# 곡 설명에서 옮긴 것(저작자 표시 문구는 곡 페이지의 'Attribution Code' 그대로 Kevin MacLeod, 이것은 덧붙임)
NOTES = {"Burn The World Waltz ": "곡 설명: featuring the Nordic Metal talents of Alexander Nakarada",
         "Feral Angel Waltz ": "곡 설명: featuring the Nordic Metal talents of Alexander Nakarada",
         "Waltz Primordial ": "곡 설명: featuring the Nordic Metal talents of Alexander Nakarada"}
# OpenGameArt.org 에서 받은 곡(2026-11-12 원정, ref/bgm/oga/). CUTS 에서는 'oga:<이름>'. 라이선스는 글 페이지의 License(s) 칸에서 읽은 것
# (받은 기록 build/aud_trip/oga_music_got.json). attribution 은 글 페이지의 'Attribution Instructions' 칸(없으면 이름과 주소만 적음).
CC0 = ("CC0 1.0", "https://creativecommons.org/publicdomain/zero/1.0/")
BY3 = ("CC BY 3.0", "https://creativecommons.org/licenses/by/3.0/")
OGA = {
    "JRPG Epic Rock Battle Theme #1": dict(file="jrpg_battle_loop.mp3", author="HydroGene", slug="jrpg-epic-rock-battle-theme-1", lic=CC0),
    "Megasong": dict(file="megasong.mp3", author="Emma_MA", slug="megasong", lic=CC0),
    "Black Rock": dict(file="Black Rock.mp3", author="marcelofg55", slug="black-rock", lic=BY),
    "Fight Them Until We Can't": dict(file="Zander Noriega - Fight Them Until We Cant.mp3", author="Zander Noriega", slug="fight-them-until-we-cant",
                                      lic=BY3, attribution="Credit me as Zander Noriega."),
    "Bestial Paragon Interface": dict(file="Zander Noriega - Bestial Paragon Interface.mp3", author="Zander Noriega", slug="bestial-paragon-interface",
                                      lic=BY3, attribution="Credit me as Zander Noriega."),
    "The Reach Of Hunger": dict(file="Zander Noriega - The Reach Of Hunger.mp3", author="Zander Noriega", slug="the-reach-of-hunger", lic=BY3,
                                attribution="Credit me as Zander Noriega."),
    "Abelian": dict(file="Zander Noriega - Abelian.mp3", author="Zander Noriega", slug="abelian", lic=BY3),
    "Dragged Through Hellfire": dict(file="Zander Noriega - Dragged Through Hellfire.mp3", author="Zander Noriega", slug="dragged-through-hellfire",
                                     lic=BY3, attribution="Credit me as Zander Noriega."),
    "The Recon Mission": dict(file="Zander Noriega - The Recon Mission.mp3", author="Zander Noriega", slug="the-recon-mission", lic=BY3,
                              attribution="Credit me as Zander Noriega."),
    "ArcLight - Epic Orchestral/Rock Soundtrack": dict(file="ArcLight (192 Kbps).mp3", author="Matthew Pablo",
                                                       slug="arclight-epic-orchestralrock-soundtrack", lic=BY3),
    "Vilified": dict(file="Vilified (2012).mp3", author="Matthew Pablo", slug="vilified", lic=BY3),
    # 2026-11-12 이어받은 aud_trip: 2막 낮음의 느린 Waltz Primordial(107 BPM)을 빼고 빠른 헤비메탈 리프로("락의 신남과 빠르기는 지키되")
    "Heavy Metal Riffs - Monolith": dict(file="Monolith.mp3", author="Tri-Tachyon", slug="heavy-metal-riffs-monolith", lic=BY, short="Monolith"),
}
# 받은 효과음(ref/sfx, data/sfx_lib.json)에서 가져오는 음악 신호는 'sfx:<아이디>' 로 적음. 출처는 CREDITS [7b](tools/gabe/audio.py credit_lines)

# 이름 → (원본, 시작 초, 끝 초(None = 끝까지), 들어올 때 페이드, 나갈 때 페이드, 다음 곡과 겹칠 초, 무엇, 크기 보정 dB)
# 시작, 끝은 ±SNAP 초 안에서 가장 조용한 곳으로 옮긴다(되풀이, 이음 자리가 튀지 않게).
CUTS = {
    "lobby": ("Gone Beyond", 0.0, 100.0, 0.0, 3.0, 1.0, "대기와 준비: 뮤직박스와 합창, 앞 두 마디묶음까지 자르고 되풀이", -2.0),
    "break_a": ("Childrens Theme", 0.0, 10.0, 0.0, 0.06, 0.0, "쉬는 시간 앞 10초(뮤직박스 동요)", -1.0),
    "break_b": ("Childrens Theme", 10.0, 20.0, 0.01, 0.6, 0.0, "쉬는 시간 다음 10초", -1.0),
    "break_end": ("Dopplerette", -5.0, None, 0.4, 0.0, 0.0, "쉬는 시간 마지막 5초: 치솟는 끝", 0.0),
    "sting": ("Zombie Chase", "hit", 2.6, 0.0, 0.8, 0.0, "웨이브 시작, 끝 신호: 첫 북 한 방", 0.0),
    # 웨이브(싸울 때): 2026-11-12 락으로 교체. 모두 기타, 드럼, 베이스가 앞에 선 곡, 145~209 BPM. 크기는 조금 낮게(-1 dB, 빽빽한 곡이라)
    "wave_low_1": ("Exhilarate", 0.0, None, 0.0, 0.0, 2.0, "웨이브 낮음: 신나고 정확한 락(170 BPM)", -1.0),
    "wave_low_2": ("Neolith", 0.0, 150.0, 0.0, 3.0, 2.5, "웨이브 낮음: 두 갈래 거친 기타(145 BPM)", -1.0),
    "wave_low_3": ("Pump", 0.0, None, 0.0, 0.0, 1.0, "웨이브 낮음: 짧고 빠른 락(164 BPM, 43초)", -1.0),
    "wave_mid_1": ("Ready Aim Fire", 0.0, 150.0, 0.0, 3.0, 2.5, "웨이브 중간: 두꺼운 소리 벽과 미친 드럼(172 BPM)", -1.0),
    "wave_mid_2": ("Metalmania", 0.0, 150.0, 0.0, 3.0, 2.5, "웨이브 중간: 메탈(150 BPM)", -1.0),
    "wave_high_1": ("Summon the Rawk", 0.0, 150.0, 0.0, 3.0, 2.5, "웨이브 높음: 더블 킥과 거친 베이스(209 BPM)", -1.0),
    "wave_high_2": ("Burn The World Waltz ", 0.0, 150.0, 0.0, 3.0, 2.5, "웨이브 높음: 무거운 노르딕 메탈 3박(177 BPM)", -1.0),
    # 보스전(10웨이브마다, Music.boss(true)): 무겁고 거대한 것
    "boss_1": ("Feral Angel Waltz ", 0.0, 150.0, 0.0, 3.0, 2.5, "보스: 무겁고 어두운 노르딕 메탈 3박(177 BPM)", 0.0),
    "boss_2": ("Aggressor", 0.0, 150.0, 0.0, 3.0, 2.5, "보스: 관현악 스피드 메탈(150 BPM), 거대한 관악과 드럼", -1.0),
    "boss_sting": ("sfx:oga_horn/theircoming3_0", 0.0, None, 0.0, 0.0, 0.0, "보스 등장 신호: 낮은 뿔나팔(받은 효과음, CC0)", 2.0),
    "over": ("Final Count", -22.0, None, 1.0, 0.0, 0.0, "전멸: 장송 행진에서 온 마지막 22초", 0.0),
    # ---------------------------------------------------------------- 2026-11-12 원정(aud_trip): 네 막, cue 곡, 보스 추가
    # 새 싸움 곡은 되풀이할 100초쯤만 잘라 쓴다(팩 크기). 뒤 막으로 갈수록 느리고 무겁고 어둡게(막 표는 ACTS).
    "a1_high_epicrock": ("oga:JRPG Epic Rock Battle Theme #1", 0.0, None, 0.0, 0.5, 0.5, "1막 높음: 밝고 빠른 락 싸움 곡(152 BPM, 되풀이 곡 통째)", -1.0),
    "a2_low_bigrock": ("Big Rock", 0.0, 100.0, 0.0, 3.0, 2.5, "2막 낮음: 두껍게 끊어 치는 파워 코드(108 BPM)", -1.0),
    "a2_low_monolith": ("oga:Heavy Metal Riffs - Monolith", 0.0, 100.0, 0.0, 3.0, 2.5, "2막 낮음: 두껍게 미는 헤비메탈 리프(160 BPM)", -1.0),
    "a2_high_takethelead": ("Take the Lead", 41.5, 141.5, 0.0, 3.0, 2.5, "2막 높음: 거칠고 세게 치는 락(140 BPM, 조용한 앞 41초는 뺌)", -1.0),
    "a3_low_fightthem": ("oga:Fight Them Until We Can't", 0.0, 100.0, 0.0, 3.0, 2.5, "3막 낮음: 차갑고 사나운 기타 메탈(90 BPM 두 배 박)", -1.0),
    "a3_low_bestial": ("oga:Bestial Paragon Interface", 23.3, 123.3, 0.0, 3.0, 2.5, "3막 낮음: 괴물 같은 무거운 메탈(120 BPM, 조용한 앞 23초는 뺌)", -1.0),
    "a3_high_hunger": ("oga:The Reach Of Hunger", 0.0, 100.0, 0.0, 3.0, 2.5, "3막 높음: 무겁고 어두운 메탈(130 BPM)", -1.0),
    "a4_low_abelian": ("oga:Abelian", 0.0, 100.0, 0.0, 3.0, 2.5, "4막 낮음: 빠른 메탈(164 BPM)", -1.0),
    "a4_high_hellfire": ("oga:Dragged Through Hellfire", 0.0, 100.0, 0.0, 3.0, 2.5, "4막 높음: 리프로 미는 잔혹한 메탈(140 BPM, 앞 절반)", -1.0),
    "a4_high_recon": ("oga:The Recon Mission", 0.0, 100.0, 0.0, 3.0, 2.5, "4막 높음: 차갑고 사나운 기타, 뾰족한 드럼(150 BPM)", -1.0),
    # 보스 추가: 3막(B4 지평선), 4막(B5 모두의 게이브). 관현악과 합창에 무거운 기타가 얹힌 거대한 곡
    "boss_3": ("oga:ArcLight - Epic Orchestral/Rock Soundtrack", 0.0, 130.0, 0.0, 3.0, 2.5, "보스(3막): 관현악, 합창에 무거운 기타와 드럼(160 BPM)", 0.0),
    "boss_4": ("oga:Vilified", 0.0, 130.0, 0.0, 3.0, 2.5, "보스(4막): 관현악 락, 처음부터 끝까지 몰아침(150 BPM)", -1.0),
    "boss_final": ("oga:Dragged Through Hellfire", 117.0, 227.0, 0.0, 3.0, 2.5, "보스 마지막 단계: 같은 곡의 뒤 절반(만든 이 말로 just plain evil)", 0.0),
    # 보스 앞 쉬는 시간은 25초: 끝 신호(break_end 5초) 앞 20초에 맞춤(Music 이 쉬는 시간 첫머리에 한 번 틀고, 길면 끝 신호 때 멈춤)
    "boss_pre": ("Lightless Dawn", 162.0, 182.0, 2.0, 3.0, 0.0, "보스 앞 쉬는 시간: 조여 오는 낮은 드론 20초", -4.0),
    "boss_down": ("Killers", -6.8, None, 0.02, 0.0, 0.0, "보스 쓰러짐 신호: 합창과 북의 마지막 한 방(6초)", 2.0),
    # cue 곡(Music.cue): 이동, 추격전, 최후의 저항, 크레딧, 엔딩 새벽
    "chase_1": ("oga:Megasong", 0.0, 104.0, 0.0, 3.0, 2.5, "추격전: 밝고 아주 빠른 메탈(175 BPM)", -1.0),
    "chase_2": ("oga:Black Rock", 0.0, 104.0, 0.0, 3.0, 2.5, "추격전: 달리는 락(148 BPM)", -1.0),
    "laststand_1": ("Killers", 0.0, 44.0, 0.0, 2.0, 1.5, "최후의 저항: 기타, 합창, 북이 처음부터 크게(105 BPM, 앞 44초)", 0.0),
    "laststand_2": ("Killers", 166.5, 223.0, 0.5, 3.0, 2.0, "최후의 저항: 같은 곡의 둘째 큰 대목(57초)", 0.0),
    "credits": ("Motherlode", 0.0, 76.0, 0.0, 5.0, 3.0, "엔딩 크레딧: 넓고 느긋한 락(90 BPM, 기타와 금관)", -1.0),
    "ending": ("Eternal Hope", -60.0, None, 3.0, 0.0, 0.0, "엔딩 새벽: 피아노와 현이 차오르다 잦아드는 곡의 마지막 60초", -3.0),
}
SNAP = 0.5
TIERS = {"low": ["wave_low_1", "wave_low_2", "wave_low_3"], "mid": ["wave_mid_1", "wave_mid_2"], "high": ["wave_high_1", "wave_high_2"]}
BOSS = ["boss_1", "boss_2"]
# 원정 네 막(Music.act(1..4)): 막마다 낮음, 높음, 보스 곡. 막을 모르면(act 0) 위 TIERS, BOSS 를 지금처럼 쓴다.
# 1 마른 길(해 질 녘): 밝고 빠른 락. 2 젖은 벌(비 오는 밤): 무거운 락, 느린 리프. 3 재의 땅(한밤): 둔중한 메탈. 4 발사장(가장 어두운 때): 가장 빠르고 잔혹한 메탈.
ACTS = {
    "1": {"low": ["wave_low_1", "wave_low_2", "wave_low_3"], "high": ["wave_mid_1", "a1_high_epicrock"], "boss": ["boss_2", "boss_1"]},
    "2": {"low": ["a2_low_bigrock", "a2_low_monolith"], "high": ["a2_high_takethelead", "wave_mid_2"], "boss": ["boss_1", "boss_2"]},
    "3": {"low": ["a3_low_fightthem", "a3_low_bestial"], "high": ["wave_high_2", "a3_high_hunger"], "boss": ["boss_3", "boss_4"]},
    "4": {"low": ["wave_high_1", "a4_low_abelian"], "high": ["a4_high_hellfire", "a4_high_recon"], "boss": ["boss_4", "boss_3"]},
}
# cue(Music.cue(키[, 틱])): tracks 를 돌려 가며, loop 가 False 면 한 번 틀고 저절로 풀림. until: 저절로 풀리는 때
# (wave_end = 웨이브를 벗어날 때, wave_start = 다음 웨이브가 시작될 때, lobby = 대기실로 갈 때). over_hush: 조용히(hush) 중에도 틂.
CUES = {
    # 이동 10초(막 넘김 16초): 추격전 둘째 곡(Black Rock)을 처음부터 = 길의 곡(원정.md 12절 '이동과 추격전에 쓸 곡 하나'). 다음 웨이브가 시작되면 풀림
    "travel": {"tracks": ["chase_2"], "loop": True, "until": "wave_start"},
    "chase": {"tracks": ["chase_1", "chase_2"], "loop": True, "until": "wave_end"},
    "laststand": {"tracks": ["laststand_1", "laststand_2"], "loop": True, "until": "wave_end"},
    "credits": {"tracks": ["credits"], "loop": True, "until": "lobby", "over_hush": True},
    "ending": {"tracks": ["ending"], "loop": False, "until": "lobby", "over_hush": True},
}
CUE_ALIAS = {"last_stand": "laststand"}


def ffmpeg():
    import imageio_ffmpeg
    return imageio_ffmpeg.get_ffmpeg_exe()


def decode(path):
    """소리 파일 → float32 (n, 2), 44100"""
    r = subprocess.run([ffmpeg(), "-loglevel", "error", "-i", str(path), "-ac", "2", "-ar", str(SR), "-f", "f32le", "-"], capture_output=True)
    if r.returncode:
        raise RuntimeError(f"풀기 실패 {path}: " + r.stderr.decode("utf-8", "replace")[-300:])
    return np.frombuffer(r.stdout, np.float32).reshape(-1, 2).copy()


def write_ogg(x, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    pcm = (np.clip(x, -1, 1) * 32767).astype("<i2").tobytes()
    r = subprocess.run([ffmpeg(), "-y", "-loglevel", "error", "-f", "s16le", "-ar", str(SR), "-ac", "2", "-i", "-", "-c:a", "libvorbis",
                        "-q:a", str(QUALITY), "-fflags", "+bitexact", "-flags:a", "+bitexact", "-map_metadata", "-1", str(path)],
                       input=pcm, capture_output=True)
    if r.returncode:
        raise RuntimeError("ogg 굽기 실패: " + r.stderr.decode("utf-8", "replace")[-300:])


def src_path(name):
    if name.startswith("sfx:"):
        return ROOT / "ref/sfx" / _sfx_entry(name[4:])["file"]
    if name.startswith("oga:"):
        return REF / "oga" / OGA[name[4:]]["file"]
    return REF / "incompetech" / (name + ".mp3")


def src_title(name):
    """곡 이름(들어 보기 파일 이름, 문서용)"""
    if name.startswith("sfx:"):
        return name[4:].replace("/", "_")
    if name.startswith("oga:"):
        return OGA[name[4:]].get("short", name[4:].split(" - ")[0])
    return TITLES.get(name, name).strip()


def _sfx_entry(sid):
    lib = json.loads((ROOT / "data/sfx_lib.json").read_text(encoding="utf-8"))
    for e in lib["sounds"]:
        if e["id"] == sid:
            return e
    raise KeyError(f"받은 효과음 {sid} 가 data/sfx_lib.json 에 없음")


def frame_rms(m, win):
    n = len(m) // win
    return np.sqrt((m[:n * win].reshape(n, win) ** 2).mean(1) + 1e-12)


def quietest(x, t, span=SNAP):
    """t 초 둘레 ±span 에서 20ms 창 RMS 가 가장 작은 곳의 가운데(초)"""
    win = int(0.02 * SR)
    a, b = max(0, int((t - span) * SR)), min(len(x), int((t + span) * SR))
    if b - a < win * 2:
        return t
    r = frame_rms(x[a:b].mean(1), win)
    return (a + int(np.argmin(r)) * win + win // 2) / SR


def first_hit(x):
    """앞 소리 없음 뒤 첫 큰 소리(시작 초)"""
    win = int(0.01 * SR)
    r = frame_rms(x.mean(1), win)
    thr = r.max() * 0.25
    i = int(np.argmax(r > thr))
    return max(0, i * win - int(0.02 * SR)) / SR


def lead_trim(x, t0):
    """t0 부터 앞 무음(-50 dB 아래)을 건너뛴 시작(초)"""
    win = int(0.01 * SR)
    seg = x[int(t0 * SR):int((t0 + 10) * SR)]
    r = frame_rms(seg.mean(1), win)
    on = np.nonzero(r > 10 ** (-50 / 20))[0]
    return t0 + (on[0] * win / SR if len(on) else 0.0)


def tail_trim(x):
    """끝 무음(-55 dB 아래) 자름"""
    win = int(0.01 * SR)
    r = frame_rms(x.mean(1), win)
    on = np.nonzero(r > 10 ** (-55 / 20))[0]
    return x[:(on[-1] + 1) * win] if len(on) else x


def active_rms_db(x):
    r = frame_rms(x.mean(1), int(0.05 * SR))
    act = r[r > r.max() * 0.05]
    return 20 * np.log10(np.sqrt((act ** 2).mean()) + 1e-12)


def cut(key):
    src, t0, t1, fin, fout, _x, _what, trim_db = CUTS[key]
    x = decode(src_path(src))
    dur = len(x) / SR
    if t0 == "hit":
        s = first_hit(x)
        e = s + t1
    else:
        if t0 < 0:
            dur = len(tail_trim(x)) / SR  # 끝에서 셀 때는 끝 무음을 뺀 끝에서
        s = dur + t0 if t0 < 0 else t0
        e = dur if t1 is None else t1
        if t0 == 0:
            s = lead_trim(x, 0.0)
        elif t0 > 0:
            s = quietest(x, s)
        if t1 is not None:
            e = quietest(x, e)
    y = x[int(s * SR):int(e * SR)].copy()
    if t1 is None:
        y = tail_trim(y)
    if fin > 0:
        n = min(len(y), int(fin * SR))
        y[:n] *= np.linspace(0, 1, n, dtype=np.float32)[:, None] ** 2
    else:
        n = int(0.004 * SR)
        y[:n] *= np.linspace(0, 1, n, dtype=np.float32)[:, None]
    if fout > 0:
        n = min(len(y), int(fout * SR))
        y[-n:] *= np.linspace(1, 0, n, dtype=np.float32)[:, None] ** 1.5
    else:
        n = int(0.01 * SR)
        y[-n:] *= np.linspace(1, 0, n, dtype=np.float32)[:, None]
    g = TARGET_RMS_DB + trim_db - active_rms_db(y)
    peak = 20 * np.log10(np.abs(y).max() + 1e-12)
    g = min(g, PEAK_DB - peak)
    y *= np.float32(10 ** (g / 20))
    return y, {"from": src, "start": round(float(s), 3), "end": round(float(s + len(y) / SR), 3), "gain_db": round(float(g), 2)}


def music_credit_lines():
    """CREDITS.txt [7] 절(배경 음악)과 [7b] 절(받은 효과음, tools/gabe/audio.py credit_lines). build_props.write_credits 와 이 도구가
    같이 씀(build_props 는 이 함수가 돌려준 줄을 끝에 붙인다). [7] 은 ref/bgm/_info.json 의 쓴 곡만"""
    info = json.loads((REF / "_info.json").read_text(encoding="utf-8")) if (REF / "_info.json").is_file() else {}
    used = sorted({c[0] for c in CUTS.values() if not c[0].startswith(("sfx:", "oga:"))})
    L = ["[7] 배경 음악(assets/gave/sounds/music, tools/make_bgm.py)",
         f"  고친 내용({GOT}, 웨이브와 보스 곡은 2026-11-12): 앞뒤를 자름, 되풀이와 이음 자리에서 줄어들게(페이드) 다듬음, 크기를 맞춤,",
         f"  libvorbis q{QUALITY} 스테레오로 다시 인코딩. 원작자가 이 팩을 만들거나 승인한 것이 아니다.",
         "  (1) Kevin MacLeod (incompetech.com) 곡, Licensed under Creative Commons: By Attribution 4.0 License",
         f"      {BY[1]}"]
    for src in used:
        e = info.get(src, {})
        keys = [k for k, c in CUTS.items() if c[0] == src]
        note = f" ({NOTES[src]})" if src in NOTES else ""
        L.append(f'  - "{TITLES.get(src, src)}" Kevin MacLeod (incompetech.com){note}, {e.get("page", "")}, 쓰는 곳: {", ".join(keys)}')
    oga = sorted({c[0][4:] for c in CUTS.values() if c[0].startswith("oga:")})
    if oga:
        L.append("  (2) OpenGameArt.org 에서 받은 곡(곡마다 만든 사람과 라이선스가 다름. CC BY 는 저작자 표시, CC0 는 고마움으로 적음)")
        for name in oga:
            o = OGA[name]
            keys = [k for k, c in CUTS.items() if c[0] == "oga:" + name]
            ask = f' (요청: {o["attribution"]})' if o.get("attribution") else ""
            L.append(f'  - "{name}" {o["author"]}, https://opengameart.org/content/{o["slug"]}, {o["lic"][0]} {o["lic"][1]}{ask}, '
                     f'쓰는 곳: {", ".join(keys)}')
    return L + [""] + sfx_credit_lines()


def sfx_credit_lines():
    """[7b] 받은 효과음 절(tools/gabe/audio.py 를 파일로 불러 씀: build_props 가 이 도구를 불러도 sys.path 를 건드리지 않게)"""
    import importlib.util
    spec = importlib.util.spec_from_file_location("gave_audio_credit", ROOT / "tools/gabe/audio.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.credit_lines()


def patch_credits():
    """build/props/pack_layer/CREDITS.txt 의 [7] 절을 지금 것으로(없으면 끝에 더함). 층이 없으면 건너뜀"""
    p = ROOT / "build/props/pack_layer/CREDITS.txt"
    if not p.is_file():
        print("CREDITS.txt 층이 없어 [7] 절은 다음 build_props 때 들어감")
        return
    t = p.read_text(encoding="utf-8").rstrip("\n").split("\n")
    if any(line.startswith("[7] 배경 음악") for line in t):
        i = next(i for i, line in enumerate(t) if line.startswith("[7] 배경 음악"))
        j = next((k for k in range(i + 1, len(t)) if t[k].startswith("[") and not t[k].startswith(("[7b] 받은 효과음", "[8] 받은 효과음"))),
                 len(t))
        t = t[:i] + music_credit_lines() + ([""] + t[j:] if j < len(t) else [])
        while t and t[-1] == "":
            t.pop()
    else:
        t += [""] + music_credit_lines()
    p.write_text("\n".join(t) + "\n", encoding="utf-8")
    print(f"CREDITS.txt [7] 절 → {p}")


def write_info():
    old = json.loads((REF / "_info.json").read_text(encoding="utf-8")) if (REF / "_info.json").is_file() else {}
    # 기록된 원본이 하나라도 없으면(ref/ 를 다시 받는 중인 PC) 기록을 다시 쓰지 않는다: 없는 곡이 _info.json 에서 빠져 출처 기록이 줄어든다
    gone = [v["file"] for v in old.values() if not (ROOT / v["file"]).is_file()]
    if gone:
        print(f"ref/bgm/_info.json 은 그대로 둠: 기록된 원본 {len(gone)}개가 없다({gone[0]} ...). python -I -X utf8 tools/fetch_ref.py bgm")
        return
    out = {}
    for src, isrc in SOURCES.items():
        f = src_path(src)
        if not f.is_file():
            continue
        out[src] = {
            "title": TITLES.get(src, src),
            "author": "Kevin MacLeod",
            "site": "incompetech.com",
            "page": f"https://incompetech.com/music/royalty-free/index.html?isrc={isrc}",
            "file_url": "https://incompetech.com/music/royalty-free/mp3-royaltyfree/" + f.name.replace(" ", "%20"),
            "license": BY[0],
            "license_url": BY[1],
            "license_seen": "트랙 페이지 틀: 'Licensed under Creative Commons: By Attribution 4.0 License'",
            "downloaded": "2026-11-12" if src in ROCK else old.get(src, {}).get("downloaded", GOT),
            "file": str(f.relative_to(ROOT)).replace("\\", "/"),
            "bytes": f.stat().st_size,
            "sha256": hashlib.sha256(f.read_bytes()).hexdigest(),
            "used": any(c[0] == src for c in CUTS.values()),
            **({"note": NOTES[src]} if src in NOTES else {}),
        }
    seen = json.loads((ROOT / "build/aud_trip/oga_music_got.json").read_text(encoding="utf-8")) if (ROOT / "build/aud_trip/oga_music_got.json").is_file() else {}
    for name, o in OGA.items():
        f = REF / "oga" / o["file"]
        if not f.is_file():
            continue
        g = seen.get(o["slug"], {})
        prev = old.get("oga:" + name, {})
        out["oga:" + name] = {
            "title": name,
            "author": o["author"],
            "site": "opengameart.org",
            "page": f"https://opengameart.org/content/{o['slug']}",
            "file_url": g.get("file_url", prev.get("file_url", "")),
            "license": o["lic"][0],
            "license_url": o["lic"][1],
            "license_seen": ("OpenGameArt 글 페이지 License(s) 칸: " + ", ".join(g["licenses_on_page"]) + " (" + ", ".join(g["license_urls_on_page"]) + ")")
            if g else prev.get("license_seen", ""),
            "attribution": o.get("attribution", ""),
            "downloaded": g.get("downloaded", prev.get("downloaded", "2026-11-12")),
            "file": str(f.relative_to(ROOT)).replace("\\", "/"),
            "bytes": f.stat().st_size,
            "sha256": hashlib.sha256(f.read_bytes()).hexdigest(),
            "used": any(c[0] == "oga:" + name for c in CUTS.values()),
            **({"from_zip": {"zip_bytes": g["zip_bytes"], "member": g["member"], "member_crc32": g["member_crc32"]}} if g.get("zip_bytes") else
               ({"from_zip": prev["from_zip"]} if prev.get("from_zip") else {})),
        }
    (REF / "_info.json").write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"출처 {len(out)}곡 → {REF / '_info.json'}")


def merge_sounds(update):
    path = ASSETS / "sounds.json"
    old = json.loads(path.read_text(encoding="utf-8")) if path.is_file() else {}
    old = {k: v for k, v in old.items() if not k.startswith("music.")}
    old.update(update)
    path.write_text(json.dumps(old, ensure_ascii=False, indent=1), encoding="utf-8")


def main():
    args = sys.argv[1:]
    write_info()
    if args == ["--info"]:
        return
    # 다 구울 때까지 pack_src 는 그대로 둔다(굽는 몇 분 사이에 다른 작업자가 팩을 묶어도 빈 폴더가 들어가지 않게): build/bgm/stage 에 굽고 끝에 옮김
    stage = BUILD / "stage"
    if stage.is_dir():
        shutil.rmtree(stage)
    stage.mkdir(parents=True)
    for name, group in list(ACTS.items()) + [("cue " + k, {"tracks": v["tracks"]}) for k, v in CUES.items()]:
        for lst in group.values():
            for k in lst:
                if k not in CUTS:
                    raise KeyError(f"{name}: 곡 {k} 가 CUTS 에 없음")
    sounds, tracks, made = {}, {}, {}
    for key, c in CUTS.items():
        dst = stage / f"{key}.ogg"
        art = ART / f"{key}.ogg"
        if art.is_file():
            shutil.copyfile(art, dst)
            meta = {"from": str(art.relative_to(ROOT)).replace("\\", "/")}
        else:
            y, meta = cut(key)
            write_ogg(y, dst)
        sec = len(decode(dst)) / SR
        made[key] = OUT_DIR / f"{key}.ogg"
        tracks[key] = {"ticks": int(sec * 20), "xfade": int(round(c[5] * 20)), "seconds": round(sec, 2), **meta}
        sounds[f"music.{key}"] = {"sounds": [{"name": f"{NS}:music/{key}", "stream": True}]}
        print(f"{key:20s} {sec:6.1f}s {dst.stat().st_size / 1024:7.0f} KB  {meta}", flush=True)
    # 한꺼번에 옮김
    if OUT_DIR.is_dir():
        shutil.rmtree(OUT_DIR)
    OUT_DIR.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(stage), str(OUT_DIR))
    merge_sounds(sounds)
    # acts, cues, boss_pre, boss_down, boss_final 은 2026-11-12 원정부터(옛 Music.java 는 모르는 키라 무시)
    doc = {"tracks": tracks, "tiers": TIERS, "lobby": "lobby", "break": ["break_a", "break_b"], "break_end": "break_end", "sting": "sting",
           "over": "over", "boss": BOSS, "boss_sting": "boss_sting",
           "acts": ACTS, "cues": CUES, "cue_alias": CUE_ALIAS, "boss_pre": "boss_pre", "boss_down": "boss_down", "boss_final": ["boss_final"]}
    (ASSETS / "music.json").write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
    total = sum(p.stat().st_size for p in made.values())
    print(f"음악 {len(made)}개 {total / 1048576:.2f} MB → {OUT_DIR}, music.json, sounds.json music.*")
    patch_credits()
    if "--check" in args or "--listen" in args:
        check(made, tracks)
    if "--listen" in args:
        listen(made)
    if "--listen-trip" in args:
        listen_trip(made)


LISTEN = BUILD / "listen_rock"
# 사용자가 들어 볼 mp3(팩에 들어가는 그대로를 mp3 로): 차례, 쓰는 곳, 곡
LISTEN_ORDER = [("wave_low_1", "웨이브낮음1"), ("wave_low_2", "웨이브낮음2"), ("wave_low_3", "웨이브낮음3"), ("wave_mid_1", "웨이브중간1"),
                ("wave_mid_2", "웨이브중간2"), ("wave_high_1", "웨이브높음1"), ("wave_high_2", "웨이브높음2"), ("boss_sting", "보스등장신호"),
                ("boss_1", "보스전1"), ("boss_2", "보스전2")]


def listen(made):
    if LISTEN.is_dir():
        shutil.rmtree(LISTEN)
    LISTEN.mkdir(parents=True)
    for i, (key, where) in enumerate(LISTEN_ORDER, 1):
        src = CUTS[key][0]
        title = TITLES.get(src, src).strip() if not src.startswith("sfx:") else src[4:].replace("/", "_")
        dst = LISTEN / f"{i:02d}_{where}_{title}.mp3"
        r = subprocess.run([ffmpeg(), "-y", "-loglevel", "error", "-i", str(made[key]), "-c:a", "libmp3lame", "-q:a", "2", "-map_metadata", "-1",
                            str(dst)], capture_output=True)
        if r.returncode:
            raise RuntimeError("mp3 굽기 실패: " + r.stderr.decode("utf-8", "replace")[-300:])
        print(f"들어 보기 {dst.name} {dst.stat().st_size / 1048576:.1f} MB")
    print(f"→ {LISTEN}")


LISTEN_TRIP = BUILD / "listen_trip"
ACT_KO = {"1": "1막마른길", "2": "2막젖은벌", "3": "3막재의땅", "4": "4막발사장"}
TIER_KO = {"low": "낮음", "high": "높음", "boss": "보스"}
# 원정에서 쓰는 차례대로: 막마다 낮음, 높음, 보스 그리고 cue 곡(쓰는 곳 이름, 곡 키)
LISTEN_TRIP_EXTRA = [("추격전1", "chase_1"), ("이동과추격전2", "chase_2"), ("최후의저항1", "laststand_1"), ("최후의저항2", "laststand_2"),
                     ("보스앞쉬는시간", "boss_pre"), ("보스등장신호", "boss_sting"), ("보스마지막단계", "boss_final"), ("보스쓰러짐신호", "boss_down"),
                     ("엔딩새벽", "ending"), ("엔딩크레딧", "credits")]


def listen_trip(made):
    """원정 네 막과 cue 곡을 사용자가 들어 볼 mp3(팩에 들어가는 그대로): <차례>_<쓰는 곳>_<곡 이름>.mp3"""
    if LISTEN_TRIP.is_dir():
        shutil.rmtree(LISTEN_TRIP)
    LISTEN_TRIP.mkdir(parents=True)
    order = []
    for act, group in ACTS.items():
        for tier in ("low", "high", "boss"):
            for i, key in enumerate(group[tier], 1):
                order.append((f"{ACT_KO[act]}_{TIER_KO[tier]}{i}", key))
    order += LISTEN_TRIP_EXTRA
    total = 0
    for i, (where, key) in enumerate(order, 1):
        title = "".join(ch for ch in src_title(CUTS[key][0]) if ch not in '\\/:*?"<>|#')
        dst = LISTEN_TRIP / f"{i:02d}_{where}_{title}.mp3"
        r = subprocess.run([ffmpeg(), "-y", "-loglevel", "error", "-i", str(made[key]), "-c:a", "libmp3lame", "-q:a", "2", "-map_metadata", "-1",
                            str(dst)], capture_output=True)
        if r.returncode:
            raise RuntimeError("mp3 굽기 실패: " + r.stderr.decode("utf-8", "replace")[-300:])
        total += dst.stat().st_size
    print(f"들어 보기(원정) {len(order)}개 {total / 1048576:.1f} MB → {LISTEN_TRIP}")


# ------------------------------------------------------------------ 확인(그림, 미리듣기, 이음 자리)

def check(made, tracks):
    from PIL import Image, ImageDraw
    BUILD.mkdir(parents=True, exist_ok=True)
    prev = BUILD / "preview"
    if prev.is_dir():
        shutil.rmtree(prev)
    prev.mkdir()
    data = {k: decode(p) for k, p in made.items()}
    print("\n이름          길이    RMS(dB) 피크(dB) 왼오 상관")
    for k, x in data.items():
        rms = 20 * np.log10(np.sqrt((x ** 2).mean()) + 1e-12)
        pk = 20 * np.log10(np.abs(x).max() + 1e-12)
        corr = float(np.corrcoef(x[:, 0], x[:, 1])[0, 1])
        print(f"{k:12s} {len(x) / SR:6.1f} {rms:8.1f} {pk:8.1f} {corr:8.2f}")

    def seq(parts):
        """[(이름, 시작 초)] 를 겹쳐 섞음(서버가 트는 그대로)"""
        end = max(s + len(data[k]) / SR for k, s in parts)
        y = np.zeros((int(end * SR) + 1, 2), np.float32)
        for k, s in parts:
            a = int(s * SR)
            y[a:a + len(data[k])] += data[k]
        return y

    def T(k):
        return tracks[k]["ticks"] / 20

    def X(k):
        return tracks[k]["xfade"] / 20
    # 상황마다 팩에 든 그대로 앞 40초
    for name, k in (("1_대기", "lobby"), ("3_웨이브_낮음", "wave_low_1"), ("4_웨이브_중간", "wave_mid_1"), ("5_웨이브_높음", "wave_high_1"),
                    ("8_보스", "boss_1"), ("7_전멸", "over")):
        write_ogg(data[k][:40 * SR], prev / f"{name}.ogg")
    # 쉬는 시간 25초 그대로(앞 10, 다음 10, 끝 5) + 웨이브 시작 신호
    brk = seq([("break_a", 0), ("break_b", T("break_a")), ("break_end", 20), ("sting", 25), ("wave_low_2", 25)])
    write_ogg(brk[:40 * SR], prev / "2_쉬는시간_25초에서_웨이브로.ogg")
    # 이음 자리: 같은 단계 두 곡이 이어지는 곳 앞뒤 20초, 대기 되풀이 자리
    seams = {}
    for tier, ks in list(TIERS.items()) + [("boss", BOSS)]:
        a, b = ks[0], ks[1]
        y = seq([(a, 0), (b, T(a) - X(a))])
        s = T(a) - X(a)
        seams[tier] = (y, s)
        write_ogg(y[int((s - 20) * SR):int((s + 20) * SR)], prev / f"6_이음_{tier}.ogg")
    y = seq([("lobby", 0), ("lobby", T("lobby") - X("lobby"))])
    seams["lobby"] = (y, T("lobby") - X("lobby"))
    seams["break"] = (brk, T("break_a"))
    seams["break_end"] = (brk, 20.0)
    write_ogg(y[int((seams["lobby"][1] - 15) * SR):int((seams["lobby"][1] + 15) * SR)], prev / "1_대기_되풀이자리.ogg")
    print("\n이음 자리: 앞 1초 RMS, 뒤 1초 RMS(dB), 자리 둘레 10ms 최대 표본 차이(튐), 둘레 평소 최대 차이")
    for k, (y, s) in seams.items():
        a = int(s * SR)
        m = y.mean(1)
        before = 20 * np.log10(np.sqrt((m[a - SR:a] ** 2).mean()) + 1e-12)
        after = 20 * np.log10(np.sqrt((m[a:a + SR] ** 2).mean()) + 1e-12)
        jump = np.abs(np.diff(m[a - 441:a + 441])).max()
        usual = np.abs(np.diff(m[a - 3 * SR:a - SR])).max()
        print(f"  {k:10s} {s:7.2f}s  {before:6.1f} → {after:6.1f}  튐 {jump:.4f} (평소 {usual:.4f})")
    # 그림: 곡마다 파형(왼, 오) + 스펙트로그램, 이음 자리 확대
    rows = list(data.items())
    W, H = 1400, 120
    img = Image.new("RGB", (W, H * (len(rows) + len(seams))), (18, 18, 18))
    d = ImageDraw.Draw(img)
    for j, (k, x) in enumerate(rows):
        draw_row(d, j * H, W, H, k, x, len(x) / SR)
    for j, (k, (y, s)) in enumerate(seams.items()):
        a, b = int((s - 2) * SR), int((s + 2) * SR)
        draw_row(d, (len(rows) + j) * H, W, H, f"이음 {k} ±2s", y[a:b], 4.0, mark=0.5)
    img.save(BUILD / "check.png")
    print(f"\n그림 {BUILD / 'check.png'}, 미리듣기 {prev}")


def draw_row(d, y0, W, H, name, x, sec, mark=None):
    from PIL import ImageFont
    try:
        font = ImageFont.truetype(str(ROOT / "art/fonts/Galmuri11-Bold.ttf"), 12)
    except OSError:
        font = None
    L0 = 150
    pw = W - L0 - 10
    d.text((4, y0 + 4), name, fill=(255, 255, 255), font=font)
    d.text((4, y0 + 22), f"{sec:.1f}s", fill=(190, 190, 190), font=font)
    m = x.mean(1)
    # 스펙트로그램(위 60px, 60~12000 Hz 로그)
    N = 2048
    cols = pw
    hop = max(1, (len(m) - N) // cols)
    freqs = np.fft.rfftfreq(N, 1 / SR)
    edges = np.geomspace(60, 12000, 61)
    idx = [np.nonzero((freqs >= edges[i]) & (freqs < edges[i + 1]))[0] for i in range(60)]
    idx = [ix if len(ix) else np.array([int(edges[i] / (SR / N))]) for i, ix in enumerate(idx)]
    spec = np.zeros((60, cols), np.float32)
    win = np.hanning(N).astype(np.float32)
    for c in range(cols):
        seg = m[c * hop:c * hop + N]
        if len(seg) < N:
            seg = np.pad(seg, (0, N - len(seg)))
        s = np.abs(np.fft.rfft(seg * win))
        spec[:, c] = [s[ix].mean() for ix in idx]
    spec = 20 * np.log10(spec + 1e-9)
    spec = np.clip((spec - (spec.max() - 70)) / 70, 0, 1)
    from PIL import Image
    rgb = np.stack([spec * 255, spec ** 2 * 200, (1 - spec) * 90 * (spec > 0)], -1).astype(np.uint8)[::-1]
    d._image.paste(Image.fromarray(rgb, "RGB"), (L0, y0 + 2))
    # 파형(아래 52px): 왼 위쪽, 오 아래쪽, 열마다 최대
    per = max(1, len(x) // pw)
    for c in range(min(pw, len(x) // per)):
        blk = x[c * per:(c + 1) * per]
        l, r = np.abs(blk[:, 0]).max(), np.abs(blk[:, 1]).max()
        yc = y0 + 90
        d.line([L0 + c, yc, L0 + c, yc - int(l * 26)], fill=(110, 210, 120))
        d.line([L0 + c, yc, L0 + c, yc + int(r * 26)], fill=(110, 170, 220))
    if mark is not None:
        xm = L0 + int(pw * mark)
        d.line([xm, y0 + 2, xm, y0 + H - 4], fill=(255, 70, 70))
    else:
        for t in range(0, int(sec) + 1, 10):
            xm = L0 + int(pw * t / sec)
            d.line([xm, y0 + 62, xm, y0 + 66], fill=(255, 90, 90))


if __name__ == "__main__":
    main()
