"""감독 소리와 압도감 소리를 내려받은 효과음(ref/sfx, data/sfx_lib.json)으로 만들어 팩 바탕 pack_src 에 넣는다. 합성하지 않는다(2026-11-11
사용자 정정 "합성하지 말고 어디서 다운해 와", 2026-11-12 "사운드가 최소 60개 이상", "거대한 절망감이라든지 압도감").

python -X utf8 tools/make_director_sounds.py          (그 뒤 build_pack.py, 플러그인은 그대로)
python -X utf8 tools/make_director_sounds.py --list   (사건과 변형, 원본만 보여 줌, 아무것도 안 씀)
python -X utf8 tools/make_director_sounds.py --listen (위에 더해 사람이 들어 볼 mp3: build/director_sounds/listen/<사건>.mp3,
                                                       변형을 0.5초 틈으로 이어 붙이고 sounds.json 높이를 입힘. 팩에는 안 들어감)

- director.*: 감독(Director)이 이미 부르는 사건. horde(떼 소리 층), chorus(킥킥 합창), heart(심장 박동), gate(쏠림 쪽 뿔나팔),
  alert(안개 경보), flood(땅 틈에서 물 차오름), eye(웅덩이 눈 뜸), murmur(웅얼거림). 팩에 있으면 Java 가 바닐라 대체 소리 대신 이것을 울린다.
- sfx.*: 아직 아무도 부르지 않는, 바로 쓸 수 있는 압도감 소리(압도감 연출, 보스, 보급 상자 담당용). Java 에서
  Props.soundAround(자리, "sfx.quake", 대체Sound, 볼륨, 흔들림, 대체높이, 반경, -1) 처럼 부른다. 반경은 아래 att 와 같게.
  목록과 쓰는 곳은 docs/소리출처.md '바로 쓰는 사건'.
- trip.*(2026-11-12 원정, aud_trip): 트럭(시동, 엔진, 타이어, 브레이크, 경적, 맞음, 폭발, 수리), 표지판 투표, 꺼내기, 발전기와 전기,
  사이렌, 로켓 점화와 굉음, 폭탄 휘파람, 2막 비와 천둥, 물, 3막 불씨, 4막 발사장 기계음. 이름과 반경은 원정 작업자들이 이미 부르는
  것(trip.truck_hit, trip.horn, trip.engine ...)에 맞췄다. 표는 docs/소리출처.md '원정 사건 trip.*'.
- 변형마다 원본 하나(긴 녹음은 덩어리 #n 이나 start, end 초)를 tools/gabe/audio.py prepare 로 다듬는다: 앞뒤 무음 자름, 가장 큰 0.4초
  크기를 level dBFS 로(피크 -1 아래), mono(자리 소리), libvorbis q4. 높이는 sounds.json pitch 로(파일은 원래 높이).
- 쓰는 곳: pack_src/assets/gave/sounds/director/*.ogg, sounds/sfx/*.ogg(이 도구가 통째로 다시 씀), pack_src/assets/gave/sounds.json 의
  director.*, sfx.* 키, lang/ko_kr.json, en_us.json 의 subtitles.gave.director.*, subtitles.gave.sfx.* 키(다른 키는 그대로).
  build/director_sounds/manifest.json(변형마다 원본, 구간, 높이: 받은 소리 세기와 출처 확인용).
"""
import json
import shutil
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from gabe import audio  # noqa: E402

ASSETS = ROOT / "pack_src/assets/gave"
NS = "gave"
MANIFEST = ROOT / "build/director_sounds/manifest.json"


def S(sid, pitch=1.0, volume=1.0, **o):
    """변형 하나: 받은 소리 아이디(#덩어리), 높이, 크기, start/end 초, level dBFS, max_len 초"""
    return dict(id=sid, pitch=pitch, volume=volume, **o)


# 사건 → (한국어 자막, attenuation_distance 블록 = Java 반경, 변형 목록, 기본 level)
EVENTS = {
    # ---------------------------------------------------------------- 감독이 이미 부르는 것(Director.soundTick, startEvents, murmur, eye)
    # 떼 소리 층: 살아 있는 몰려오는 수만큼 0.2~1.8초마다 22블록 밖에서. 짧은 으르렁, 신음, 그르렁을 낮게(멀고 크게 들리게)
    "director.horde": ("몰려오는 떼", 56, [
        S("oga_snarls/monster_snarls_0#0", 0.82, max_len=2.2), S("oga_snarls/monster_snarls_0#2", 0.78, max_len=2.2),
        S("oga_snarls/monster_snarls_2_0#0", 0.8, max_len=2.2), S("oga_snarls/monsters_snarls_3_0#1", 0.75, max_len=2.0),
        S("oga_snarls/monsters_snarls_3_0#4", 0.85, max_len=2.0), S("oga_snarls/monster_snarl_5#2", 0.72, max_len=1.8),
        S("oga_snarls/monster_snarl_attack#6", 0.7, max_len=1.8), S("oga_moans/darsycho_zombie_moans_0#0", 0.8, max_len=2.2),
        S("oga_moans/darsycho_zombie_moans_0#4", 0.75, max_len=2.2), S("oga_ghoul/hungry_ghoul_0#3", 0.7, max_len=2.0),
        S("oga_zombies/zombie_16", 0.8), S("oga_zombies/zombie_17", 0.75), S("oga_zombies/zombie_21", 0.85),
        S("lrsf_horror/zombie_00", 0.8, max_len=2.2), S("lrsf_horror/zombie_02", 0.75, max_len=2.2), S("oga_monster/monster_8", 0.7),
        S("oga_zombies/zombie_4", 0.78), S("oga_zombies/zombie_7", 0.82), S("oga_zombies/zombie_12", 0.74), S("oga_zombies/zombie_15", 0.8),
        S("oga_zombies/zombie_18", 0.76), S("oga_zombies/zombie_20", 0.84), S("lrsf_horror/zombie_04", 0.78), S("oga_monster/monster_1", 0.72),
    ], -16.0),
    # 킥킥 합창(14마리부터 3~7초마다): 웃음, 킥킥을 조금 높여 귀여운 것이 비틀린 쪽으로
    "director.chorus": ("킥킥 합창", 64, [
        S("oga_giggle/group_giggling", 1.12, max_len=3.0), S("oga_laughs/laugh_1", 1.2, max_len=3.0), S("oga_laughs/laugh_4", 1.15, max_len=3.0),
        S("oga_laughs/laugh_6", 1.1, max_len=3.0), S("oga_laughter/ogalaughter_2#1", 1.25), S("oga_laughter/ogalaughter_2#4", 1.2),
        S("oga_cackle/witch_cackle_1_0", 1.15), S("lrsf_horror/laugh_evil_01", 1.2, max_len=3.0),
        S("oga_laughs/laugh_3", 1.18, max_len=3.0), S("oga_laughs/laugh_5", 1.12, max_len=3.0), S("oga_laughs/laugh_7", 1.1, max_len=3.0),
        S("lrsf_horror/laugh_evil_00", 1.22, max_len=3.0),
    ], -17.0),
    # 심장 박동(가장 가까운 무리가 중앙 원 테두리 16블록 안): 쿵-쿵 한 번
    "director.heart": ("심장 박동", 12, [
        S("oga_heartbeat/heartbeat_slow_0", 0.85, end=0.85), S("oga_heartbeat/heartbeat_slow_reverb", 0.8, end=0.85),
        S("oga_heartbeat/heartbeat_fast_0", 0.85, end=0.55), S("oga_heartbeat1/heartbeat_mp3", 0.85, end=1.0),
    ], -10.0),
    # 쏠림 쪽 고리에서(반경 140): 전쟁 뿔나팔
    "director.gate": ("뿔나팔", 140, [
        S("oga_warhorns/war_horns", 0.9, start=0.5, end=7.5), S("oga_warhorns/war_horns", 0.85, start=19.5, end=27.0),
        S("oga_horn/theircoming3_0", 0.9), S("oga_horn/theircoming_0#0", 0.85),
    ], -10.0),
    # 안개 경보(재난문자 꼴 HUD 와 함께, 사람 자리): 비튼 세 음 경보
    "director.alert": ("비틀린 경보음", 16, [
        S("kenney_digital/lowthreetone", 0.62), S("kenney_digital/zapthreetonedown", 0.55),
    ], -11.0),
    # 땅 틈에서 물이 차오름(틈마다, 반경 40): 보글거림을 낮게
    "director.flood": ("보글보글 차오름", 40, [
        S("oga_creature80/burble_01", 0.6), S("oga_creature80/burble_02", 0.55), S("kenney_scifi/slime_001", 0.7, max_len=3.0),
        S("oga_creature80b/slime_02", 0.5),
    ], -14.0),
    # 웅덩이 눈 뜸(반경 30)
    "director.eye": ("무언가 눈을 뜸", 30, [
        S("oga_creature80/weird_05", 0.55), S("oga_sfx100/weird_04", 0.6), S("lrsf_horror/gate_open_00", 0.5),
    ], -13.0),
    # 웅얼거림(반경 130): 먼 사람 목소리 웅성
    "director.murmur": ("웅얼거림", 130, [
        S("oga_crowd/crowd_shouting_0", 0.8, start=2.0, end=4.6), S("oga_crowd/crowd_shouting_0", 0.78, start=9.0, end=11.6),
        S("oga_crowd/crowd_shouting_0", 0.82, start=15.0, end=17.6), S("oga_crowd/crowd_shouting_0", 0.76, start=21.0, end=23.6),
        S("oga_creature80b/human_01", 0.75), S("oga_creature80b/human_03", 0.72), S("oga_creature80b/human_06", 0.78),
        S("oga_creature80b/human_07", 0.74), S("oga_creature80b/human_02", 0.76), S("oga_creature80b/human_04", 0.73),
        S("oga_creature80b/human_05", 0.77), S("oga_creature80b/human_08", 0.75),
    ], -18.0),
    # ---------------------------------------------------------------- 바로 쓰는 압도감 소리(아직 아무도 안 부름, docs/소리출처.md)
    "sfx.roar_far": ("멀리서 울리는 포효", 160, [
        S("lrsf_horror/monster_00", 0.7), S("lrsf_horror/monster_02", 0.65), S("lrsf_horror/monster_03", 0.7), S("lrsf_horror/monster_04", 0.62),
        S("oga_creature80b/roar_04", 0.6), S("oga_creature80/roar_02", 0.55),
    ], -10.0),
    "sfx.horde_cry": ("떼의 함성", 128, [
        S("oga_crowd/crowd_shouting_0", 0.7, start=0.4, end=6.4), S("oga_crowd/crowd_shouting_0", 0.66, start=12.0, end=18.0),
        S("oga_moans/darsycho_zombie_moans_0#4", 0.62), S("oga_snarls/monster_snarl_5", 0.6, start=0.3, end=5.7),
    ], -12.0),
    "sfx.quake": ("땅울림", 128, [
        S("oga_quake/shake", 0.8), S("oga_rumble/rumble", 0.9, start=1.0, end=9.0), S("kenney_scifi/lowfrequency_explosion_000", 0.6),
        S("kenney_scifi/spaceenginelow_002", 0.55),
    ], -10.0),
    "sfx.colossus": ("거대한 것이 지나감", 160, [
        S("kenney_scifi/spaceenginelarge_000", 0.5), S("kenney_scifi/spaceenginelarge_001", 0.55),
        S("lrsf_horror/ambience_monstersbelly_00", 0.8, start=10.0, end=18.0), S("kenney_scifi/thrusterfire_000", 0.42),
    ], -12.0),
    "sfx.stomp": ("거대한 발걸음", 96, [
        S("oga_stomp/stomp", 0.55), S("kenney_impact/impactpunch_heavy_000", 0.45), S("kenney_impact/impactsoft_heavy_002", 0.5),
        S("oga_creature80b/stomp_01", 0.6), S("kenney_scifi/lowfrequency_explosion_001", 0.8),
    ], -10.0),
    "sfx.horn": ("뿔나팔", 192, [
        S("oga_warhorns/war_horns", 0.8, start=36.0, end=46.0), S("oga_horn/theircoming_0#1", 0.85), S("oga_horn/theircoming_0#3", 0.8),
    ], -10.0),
    "sfx.thunder": ("천둥", 64, [
        S("oga_thunder/rain_thunder", 0.9, start=21.0, end=33.0), S("oga_bang25/cannon_02", 0.45),
    ], -10.0),
    "sfx.wind": ("바람", 32, [
        S("oga_wind/wind", 0.9), S("oga_wind/wind2", 0.85), S("oga_wind_loop/wind_woosh_loop", 0.8),
    ], -18.0),
    "sfx.dread": ("음산한 울림", 64, [
        S("lrsf_horror/ambience_hell_00", 0.9, start=0.0, end=20.0), S("lrsf_horror/ambience_hell_02", 0.9, start=0.0, end=20.0),
        S("lrsf_horror/ambience_monstersbelly_00", 0.85, start=30.0, end=50.0),
    ], -16.0),
    "sfx.boss_roar": ("거대한 포효", 192, [
        S("oga_horn/theircoming3_0", 0.75), S("lrsf_horror/monster_05", 0.6), S("oga_creature80b/roar_05", 0.5), S("lrsf_horror/monster_03", 0.55),
    ], -9.0),
    "sfx.wipe": ("무너지는 소리", 96, [
        S("kenney_scifi/explosioncrunch_004", 0.5), S("oga_bang25/cannon_01", 0.55), S("oga_breaking75/bfh1_rock_breaking_02", 0.6),
    ], -10.0),
    "sfx.supply": ("상자 떨어짐", 48, [
        S("kenney_impact/impactwood_heavy_000", 0.7), S("kenney_impact/impactwood_heavy_002", 0.65), S("kenney_impact/impactplank_medium_000", 0.6),
    ], -12.0),
    "sfx.scream_far": ("멀리서 비명", 96, [
        S("lrsf_horror/scream_male_00", 0.85), S("lrsf_horror/scream_male_01", 0.8), S("lrsf_horror/scream_male_03", 0.88),
    ], -14.0),
    # 낮은 종(보스 예고, 죽음 알림 같은 곳): 종과 징을 한 옥타브쯤 낮춤
    "sfx.bell": ("낮은 종소리", 128, [
        S("kenney_impact/impactbell_heavy_000", 0.42), S("oga_sfx100/bell_03", 0.5), S("oga_sfx100/gong_02", 0.5),
    ], -10.0),
    # 보스.md 9절에서 받은 것이 없던 소리(2026-11-12 aud_trip 이어받음, OGA CC0): 새벽 새소리(엔딩 dawn_birds), 말 없는 낮은 허밍(chant_hum,
    # choir_low), 많은 속삭임(whisper_many: 라틴어 흉내 글 'Lorem Ipsum' 을 읽는 목소리라 실제 말이 아님, 낮추고 작게)
    "sfx.dawn": ("새벽 새소리", 96, [
        S("oga_birds/birds_isaiah658", 1.0, start=0.0, end=10.0), S("oga_birds/birds_isaiah658", 1.0, start=11.0, end=21.0),
        S("oga_morning/amb_morning", 1.0, start=4.0, end=14.0), S("oga_birds2/birdchirping071414", 1.0),
    ], -18.0),
    "sfx.choir": ("낮은 허밍", 128, [
        S("oga_humming/ghostly_humming#2", 0.6), S("oga_humming/ghostly_humming#8", 0.6), S("oga_humming/ghostly_humming#11", 0.55),
    ], -14.0),
    "sfx.whisper": ("속삭임", 24, [
        S("oga_madness/a3_menacing#15", 0.85), S("oga_madness/a4_scared#4", 0.9), S("oga_madness/a2_scared#3", 0.8),
        S("oga_madness/a3_menacing#17", 0.9),
    ], -20.0),
    # ---------------------------------------------------------------- 원정(2026-11-12 aud_trip, docs/모듈/원정.md 12절): 받은 것만
    # 트럭. 이름과 반경은 trip_core(Truck), trip_route(Travel, RouteVote)가 이미 부르는 대로
    "trip.truck_start": ("시동", 32, [
        S("oga_car_start2/engine_start_up_01", 0.75), S("oga_car_start/generic_car_startengine", 0.7),
        S("oga_car_start/generic_car_startengine_1", 0.72),
    ], -11.0),
    # 달리는 엔진(4~6초 덩어리: 이어 붙이려면 길이보다 조금 짧게 다시 부름)
    "trip.engine": ("달리는 엔진", 48, [
        S("oga_engine2/engine_sound", 0.8, start=4.0, end=10.0), S("oga_engine2/engine_sound_1", 0.8, start=4.0, end=10.0),
        S("oga_engine_heavy/engine_heavy_loop", 1.25), S("oga_engine_heavy/engine_heavy_loop", 1.4),
    ], -12.0),
    "trip.engine_idle": ("공회전", 24, [
        S("oga_engine_heavy/engine_heavy_loop", 1.0), S("oga_engine2/engine_sound", 0.62, start=12.0, end=18.0),
    ], -15.0),
    # 엔진이 올라감(다가오며 커지는 8초: 이동 연출 시작)
    "trip.engine_rev": ("엔진이 올라감", 64, [
        S("oga_car_run/car1", 0.8, start=2.5, end=10.5), S("oga_car_run/car1", 0.9, start=2.5, end=10.5),
    ], -10.0),
    "trip.tires": ("타이어 끌리는 소리", 40, [
        S("oga_tire_skid/tires_squal_loop", 0.8), S("oga_tire_skid/tires_squal_loop", 0.92, end=1.8), S("oga_loops30/rolling", 0.7),
    ], -14.0),
    # 브레이크: 타이어 끌림 짧게, 공기 브레이크(김 빠지는 소리를 낮춤)
    "trip.brake": ("브레이크", 48, [
        S("oga_tire_skid/tires_squal_loop", 1.05, end=1.6), S("oga_steam/steam_hisses_marker_1", 0.8), S("oga_steam/steam_hisses_marker_3", 0.75),
    ], -13.0),
    "trip.horn": ("경적", 64, [S("oga_car_horn/car2", 0.72), S("oga_car_horn/car2", 0.78)], -9.0),
    "trip.truck_horn": ("트럭 경적", 96, [S("oga_car_horn/car2", 0.6), S("oga_car_horn/car2", 0.64)], -8.0),
    # 끊기는 경적(트럭 체력 10% 아래, 터지기 직전)
    "trip.horn_broken": ("끊기는 경적", 96, [
        S("oga_car_horn/car2", 0.55, end=0.45), S("oga_car_horn/car2", 0.5, end=0.7), S("oga_car_horn/car2", 0.52, start=0.3, end=0.6),
    ], -10.0),
    "trip.truck_hit": ("트럭이 맞는 소리", 40, [
        S("oga_metal27/dull_metal_collision_02_44k_32bit_stereo", 0.8), S("oga_metal27/dull_metal_collision_06_44k_32bit_stereo", 0.75),
        S("oga_metal27/dull_metal_collision_12_44k_32bit_stereo", 0.8), S("oga_metal27/dull_metal_collision_13_44k_32bit_stereo", 0.85),
        S("oga_metal27/wobble_05_44k_32bit_stereo", 0.8), S("oga_metal27/wobble_08_44k_32bit_stereo", 0.7),
        S("kenney_impact/impactmetal_heavy_000", 0.7), S("kenney_impact/impactmetal_heavy_002", 0.75), S("oga_metalwood100/metal_slam_01", 0.8),
    ], -11.0),
    # 쿵(매달림, 판이 덜컹)
    "trip.thud": ("쿵", 32, [
        S("oga_metal27/dull_metal_collision_13_44k_32bit_stereo", 0.6), S("oga_metal27/wobble_09_44k_32bit_stereo", 0.6),
        S("kenney_impact/impactmetal_heavy_004", 0.6),
    ], -10.0),
    "trip.truck_boom": ("트럭 폭발", 128, [
        S("kenney_scifi/explosioncrunch_000", 0.7), S("kenney_scifi/explosioncrunch_002", 0.65), S("kenney_scifi/lowfrequency_explosion_000", 0.9),
        S("oga_scifi50/explosion_01", 0.8), S("oga_bang25/cannon_03", 0.6),
    ], -8.0),
    "trip.truck_debris": ("쇳조각 떨어짐", 48, [
        S("oga_metalwood100/metal_falling_01", 0.8), S("oga_metalwood100/metal_falling_02", 0.75), S("oga_breaking75/bfh1_metal_falling_01", 0.8),
        S("oga_metal27/rapid_clangs_01_44k_32bit_stereo", 0.8),
    ], -12.0),
    "trip.truck_repair": ("수리", 24, [
        S("oga_metalwood100/tools_06", 0.9), S("oga_metalwood100/tools_07", 0.85), S("oga_metalwood100/tools_12", 0.9), S("oga_metalwood100/tools_01", 0.8),
        S("oga_metalwood100/tools_05", 0.85), S("oga_metalwood100/hammer_01", 0.8), S("oga_sfx100/tools_01", 0.9), S("oga_sfx100/tools_03", 0.85),
    ], -13.0),
    # 표지판 투표
    "trip.vote_open": ("표지판이 섬", 48, [S("oga_metal_impact/bong1", 0.8), S("kenney_impact/impactbell_heavy_001", 0.7)], -11.0),
    "trip.vote_tick": ("딸깍", 48, [S("kenney_interface/click_001"), S("kenney_interface/click_002"), S("kenney_interface/tick_001")], -14.0),
    "trip.vote_done": ("길이 정해짐", 48, [
        S("kenney_interface/confirmation_001"), S("kenney_interface/confirmation_002"), S("oga_metal_impact/bing1", 0.8),
    ], -11.0),
    # 꺼내기(부활): 짐칸 문. 죽은 사람의 신호는 안에서 쇠를 두드리는 소리
    "trip.revived": ("짐칸 문이 열림", 32, [
        S("oga_car_door/door_opening", 0.85), S("oga_car_door/door_closing", 0.8), S("oga_metalwood100/metal_open_01", 0.8),
    ], -10.0),
    "trip.call": ("안에서 두드리는 소리", 24, [
        S("kenney_impact/impactmetal_light_000", 0.8), S("kenney_impact/impactmetal_light_001", 0.8), S("kenney_impact/impactmetal_light_002", 0.8),
    ], -13.0),
    # 발전기, 펌프, 전기
    "trip.generator": ("발전기", 40, [
        S("oga_boiler/generator_loop", 1.0, start=2.0, end=8.0), S("oga_loops30/machine_08", 0.9), S("oga_scifi50/loop_machine_02", 0.8),
    ], -15.0),
    # 2026-11-12 버그 훑기: RoundFx, PadLights 가 부르는데 팩에 없던 사건
    "trip.flicker": ("불빛 깜빡임", 32, [S("oga_electric/crackleelectricityloop", 0.8), S("oga_breaker/switch_off", 0.7)], -14.0),
    "trip.lamp_on": ("투광등 켜짐", 48, [S("oga_breaker/switch_on", 0.9), S("oga_electric/powerup", 0.7)], -12.0),
    "trip.lamp_off": ("투광등 꺼짐", 48, [S("oga_breaker/switch_off", 0.9), S("oga_electric/death", 0.6)], -12.0),
    "trip.goal": ("목표 채움", 64, [S("kenney_interface/confirmation_001", 0.9), S("kenney_interface/confirmation_002", 0.9)], -10.0),
    "trip.pick": ("주움", 24, [S("kenney_interface/switch_002", 0.9), S("kenney_interface/switch_003", 0.9)], -12.0),
    "trip.egg": ("알 깨짐", 32, [S("lrsf_horror/bonecrack_00", 0.8), S("lrsf_horror/bonecrack_01", 0.8), S("lrsf_horror/bonecrack_02", 0.8)], -12.0),
    "trip.rise": ("땅 울림", 64, [S("kenney_scifi/lowfrequency_explosion_000", 0.8), S("kenney_scifi/spaceenginelow_000", 0.7)], -12.0),
    "trip.lamp": ("등불 웅웅", 32, [S("kenney_scifi/forcefield_000", 0.7), S("kenney_scifi/forcefield_001", 0.7)], -15.0),
    "trip.bait": ("상자 덜컥", 24, [S("kenney_impact/footstep_wood_000", 0.9), S("kenney_impact/footstep_wood_001", 0.9)], -12.0),
    "trip.pump": ("연료 펌프", 32, [S("oga_loops30/pump_01", 0.9), S("oga_loops30/pump_02", 0.85), S("oga_loops30/machine_06", 0.9)], -15.0),
    "trip.power_on": ("전기 켜짐", 48, [
        S("oga_electric/powerup", 0.9), S("oga_breaker/switch_on", 0.8), S("kenney_scifi/forcefield_000", 0.7),
    ], -11.0),
    "trip.power_off": ("전기 꺼짐", 48, [
        S("oga_electric/powerdown", 0.85), S("oga_poweroff/machinepoweroff", 0.9), S("oga_breaker/switch_off", 0.8),
    ], -11.0),
    "trip.spark": ("스파크", 24, [S("oga_electric/crackleelectricityloop", 1.0), S("oga_electric/snaploop", 1.0, end=1.5)], -14.0),
    # 사이렌(가까운 것, 지평선의 먼 것: 폭풍 속 실제 사이렌 12초)
    "trip.siren": ("사이렌", 160, [
        S("oga_facility_siren/facility_breach_siren", 0.85), S("oga_sirens/siren#1", 0.9), S("oga_sirens/siren#3", 0.9),
    ], -10.0),
    "trip.siren_far": ("먼 사이렌", 200, [
        S("oga_storm_siren/storm_3_siren", 1.0, start=40.0, end=52.0), S("oga_storm_siren/storm_3_siren", 1.0, start=57.0, end=69.0),
    ], -14.0),
    # 로켓
    "trip.countdown": ("초읽기", 96, [S("oga_scifi50/beep_01", 1.0), S("oga_scifi50/terminal_01", 1.0)], -12.0),
    "trip.rocket_ignite": ("점화", 128, [
        S("oga_flare/ignition", 0.6), S("oga_scifi50/rocket_01", 0.8), S("kenney_scifi/thrusterfire_001", 0.7, end=3.0),
    ], -9.0),
    "trip.rocket_launch": ("로켓 굉음", 256, [
        S("kenney_scifi/thrusterfire_002", 0.55), S("kenney_scifi/spaceenginelarge_003", 0.7), S("oga_rocket_engine/rocket_engine_001", 1.5),
        S("oga_jet_takeoff/engine_takeoff", 0.55),
    ], -8.0),
    # 폭탄 휘파람(재미 장치 공동 구매 폭격의 떨어지는 소리로도)
    "trip.bomb_whistle": ("떨어지는 휘파람", 96, [
        S("oga_slide_whistle/79671_joedeshon_slide_whistle_down_01", 0.75), S("oga_slide_whistle/79671_joedeshon_slide_whistle_down_01", 0.85),
        S("oga_missile/missile", 1.1),
    ], -10.0),
    # 2막 젖은 벌: 비(20초 바탕, 스트림), 가까운 천둥, 철벅, 물밑 공기 방울, 물 차오름, 붓는 소리
    "trip.rain": ("빗소리", 48, [
        S("oga_rain4/2", 1.0, end=20.0), S("oga_rain4/1", 1.0, end=20.0), S("oga_rain4/3", 1.0, start=5.0, end=25.0),
    ], -20.0),
    "trip.thunder": ("가까운 천둥", 256, [
        S("oga_thunder_close/thunder_very_close_rain_01#0", 1.0), S("oga_thunder_close/thunder_very_close_rain_01#0", 0.8),
    ], -8.0),
    "trip.splash": ("철벅", 24, [
        S("oga_water40/splash_04", 0.9), S("oga_water40/splash_01", 0.9), S("oga_water40/splash_02", 0.85), S("oga_water40/splash_05", 0.9),
        S("oga_sfx100/splash_01", 0.9), S("oga_sfx100/splash_02", 0.85),
    ], -14.0),
    "trip.bubbles": ("물밑 공기 방울", 24, [
        S("oga_water40/bubble_01", 0.7), S("oga_water40/bubble_02", 0.7), S("oga_water40/loop_bubbles_1", 0.8),
    ], -15.0),
    "trip.water_rise": ("물 차오르는 소리", 64, [
        S("oga_water40/loop_water_02", 0.8), S("oga_water40/loop_water_01", 0.7), S("oga_loops30/water_flowing", 0.6),
    ], -16.0),
    "trip.pour": ("붓는 소리", 24, [S("oga_water40/loop_water_03", 0.9), S("oga_water40/loop_water_01", 1.0)], -15.0),
    # 3막 재의 땅: 불씨(10초 바탕), 큰 불길, 가스 새는 소리
    "trip.embers": ("불씨 타는 소리", 32, [
        S("oga_fireplace/fire", 1.0, start=2.0, end=12.0), S("oga_fireplace/fire", 1.0, start=14.0, end=24.0), S("oga_fire_crackle/fire_1", 0.9),
    ], -18.0),
    "trip.fire": ("불길", 64, [
        S("oga_fireplace/fire", 0.7, start=2.0, end=10.0), S("kenney_scifi/thrusterfire_003", 0.5), S("kenney_scifi/thrusterfire_004", 0.45),
    ], -13.0),
    "trip.gas_leak": ("가스 새는 소리", 32, [
        S("oga_steam/steam_hisses_marker_4", 1.0), S("oga_steam/steam_hisses_marker_2", 0.9), S("oga_steam/steam_hisses_marker_5", 0.95),
    ], -15.0),
    # 4막 발사장: 기계음, 쇠 울림, 무전 잡음
    "trip.pad_machine": ("발사장 기계음", 64, [
        S("oga_loops30/machine_09", 0.8), S("oga_scifi50/loop_machine_01", 0.7), S("lrsf_horror/evil_machine_loop_00", 0.9, end=8.0),
        S("oga_loops30/machine_05", 0.6),
    ], -15.0),
    "trip.metal_groan": ("쇠 울림", 96, [
        S("oga_metal27/wobble_08_44k_32bit_stereo", 0.45), S("oga_metal27/metal_grit_02_44k_32bit_stereo", 0.4), S("lrsf_horror/gate_open_00", 0.4),
        S("kenney_impact/impactbell_heavy_000", 0.4),
    ], -11.0),
    "trip.radio": ("무전 잡음", 24, [
        S("oga_radio_noise/radio_noise_loop", 1.0, start=4.0, end=8.0), S("oga_radio_noise/radio_noise_loop", 1.0, start=26.0, end=30.0),
    ], -16.0),
}
PREFIXES = ("director.", "sfx.", "trip.")


def merge_json(path, update, prefixes):
    old = json.loads(path.read_text(encoding="utf-8")) if path.is_file() else {}
    old = {k: v for k, v in old.items() if not k.startswith(prefixes)}
    old.update(update)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(old, ensure_ascii=False, indent=1), encoding="utf-8")


def main():
    if "--list" in sys.argv:
        for ev, (sub, att, vs, lv) in EVENTS.items():
            print(f"{ev:18s} {sub:12s} 반경 {att:3d} 변형 {len(vs):2d}: " + ", ".join(v["id"] for v in vs))
        return
    sounds, ko, en, man, out = {}, {}, {}, [], {}
    total = 0
    for ev, (sub, att, vs, lv) in EVENTS.items():
        if chr(0xB7) in sub:
            raise ValueError("자막에 가운뎃점 금지")
        folder, name = ev.split(".", 1)
        entries = []
        for i, v in enumerate(vs):
            x = audio.prepare(v["id"], start=v.get("start"), end=v.get("end"), level=v.get("level", lv), max_len=v.get("max_len"))
            b = audio.ogg_bytes(x)
            rel = f"{folder}/{name}_{i}"
            out[rel] = b  # 모두 굽고 나서 한꺼번에 씀(중간에 실패하면 pack_src 는 그대로)
            total += len(b)
            ent = {"name": f"{NS}:{rel}", "volume": round(v.get("volume", 1.0), 3), "pitch": round(v["pitch"], 3), "attenuation_distance": att}
            sec = len(x) / audio.SR
            if sec > 8:
                ent["stream"] = True
            entries.append(ent)
            e = audio.entry(v["id"])
            man.append({"event": ev, "file": f"assets/{NS}/sounds/{rel}.ogg", "from": v["id"], "src_file": e["file"], "pack": e["pack"],
                        "license": audio.lib()["packs"][e["pack"]]["license"], "start": v.get("start"), "end": v.get("end"),
                        "pitch": v["pitch"], "seconds": round(sec, 2), "heard": round(sec / v["pitch"], 2), "bytes": len(b)})
        sounds[ev] = {"sounds": entries, "subtitle": f"subtitles.{NS}.{ev}"}
        ko[f"subtitles.{NS}.{ev}"] = sub
        en[f"subtitles.{NS}.{ev}"] = sub
    for d in ("director", "sfx", "trip"):
        shutil.rmtree(ASSETS / "sounds" / d, ignore_errors=True)
        (ASSETS / "sounds" / d).mkdir(parents=True)
    for rel, b in out.items():
        (ASSETS / "sounds" / f"{rel}.ogg").write_bytes(b)
    merge_json(ASSETS / "sounds.json", sounds, PREFIXES)
    merge_json(ASSETS / "lang/ko_kr.json", ko, tuple(f"subtitles.{NS}.{p}" for p in PREFIXES))
    merge_json(ASSETS / "lang/en_us.json", en, tuple(f"subtitles.{NS}.{p}" for p in PREFIXES))
    MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    MANIFEST.write_text(json.dumps(man, ensure_ascii=False, indent=1), encoding="utf-8")
    if "--listen" in sys.argv:
        listen(sounds, out)
    src = {m["src_file"] for m in man}
    for pre in PREFIXES:
        ms = [m for m in man if m["event"].startswith(pre)]
        print(f"  {pre}* 사건 {len({m['event'] for m in ms})} 변형 {len(ms)} 원본 {len({m['src_file'] for m in ms})} {sum(m['bytes'] for m in ms) / 1048576:.2f} MB")
    print(f"감독, 압도감, 원정 소리 사건 {len(sounds)}개, 변형 {len(man)}개(받은 원본 파일 {len(src)}개, 묶음 {len({m['pack'] for m in man})}), "
          f"{total / 1048576:.2f} MB → {ASSETS / 'sounds'}/director, sfx, sounds.json, lang")


def listen(sounds, out):
    """사건마다 변형을 차례로 이어 붙인 mp3(높이는 sounds.json pitch 대로 재생 빠르기를 바꿔 입힘: 게임과 같은 방식)"""
    import subprocess
    import tempfile
    d = ROOT / "build/director_sounds/listen"
    shutil.rmtree(d, ignore_errors=True)
    d.mkdir(parents=True)
    ff = audio.ffmpeg_exe()
    for ev, v in sounds.items():
        with tempfile.TemporaryDirectory() as td:
            parts = []
            for i, s in enumerate(v["sounds"]):
                rel = s["name"].split(":", 1)[1]
                src = Path(td) / f"{i}.ogg"
                src.write_bytes(out[rel])
                # 높이 = 빠르기(게임과 같음): pitch 0.5 면 두 배 촘촘히 다시 뽑아 44.1 kHz 로 틀어 두 배 느리고 한 옥타브 낮게
                x = audio.load(src, sr=int(round(audio.SR / s["pitch"])))
                parts += [x, np.zeros(int(0.5 * audio.SR), np.float32)]
            y = np.concatenate(parts) if parts else np.zeros(1, np.float32)
            pcm = (np.clip(y, -1, 1) * 32767).astype("<i2").tobytes()
            dst = d / f"{ev}.mp3"
            subprocess.run([ff, "-y", "-loglevel", "error", "-f", "s16le", "-ar", str(audio.SR), "-ac", "1", "-i", "-", "-c:a", "libmp3lame",
                            "-q:a", "4", str(dst)], input=pcm, check=True)
    print(f"들어 보기 {len(sounds)}개 → {d}")


if __name__ == "__main__":
    main()
