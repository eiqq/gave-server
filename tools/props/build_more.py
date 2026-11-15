"""build_props.py 뒤쪽: 총, 수류탄 소리(sounds.json, 자막). 좀비런 build_more.py 에서 좀비 소리, 좀비 장비 층, 도시 소품을 뺐다"""
import json
import subprocess
from pathlib import Path

import imageio_ffmpeg
import numpy as np

import sound_info as SI

FF = imageio_ffmpeg.get_ffmpeg_exe()
G = {}  # build_props 의 전역(run 에서 채움)

# ------------------------------------------------------------------ 소리

TACS = "timeless-and-classics-guns/extracted/assets/tac/sounds/"
JEGS = "just-enough-guns/extracted/assets/jeg/sounds/"
TGS = "tac-gear/extracted/assets/tac_gear/sounds/"
# 2026-11-12 wpn12 새 무기 소리: 직접 받은 CC0 소리(ref/sfx/weapons/<출처>/, 출처마다 _info.json). "sfx:" 로 시작하는 원본은 확장자까지 적는다
FFL = "sfx:firearm-sound-library/"  # The Free Firearm Sound Library(CC0), 96 kHz 24 비트 스테레오 WAV(총 한 발 + 긴 꼬리)
KEN = "sfx:kenney-scifi/"  # Kenney Sci-Fi Sounds(CC0)
SFX_RATE = 44100  # sfx 원본은 이 표본율 모노로 다시 부호화
# props.json sound_files.source(모드 이름과 판). 모드의 소리 라이선스 표기(TaC, JEG 의 CC0, TAC GEAR 의 MIT)는 믿을 근거가 없어 적지 않는다
# (2026-11-10 권리 감사, 좀비런 docs/소품출처.md. 파일마다 확인 여부는 build_props.py SOUND_OK 와 CREDITS.txt [5])
SRC_NAME = {"timeless-and-classics-guns": "Timeless and Classics Guns 0.3.14.2",
            "just-enough-guns": "Just Enough Guns 0.14.5",
            "tac-gear": "TAC GEAR 0.7.1"}

# 사건 → [(원본, 나갈 이름, 추가 속성)] 과 자막. Java 가 부르는 사건과 고르는 변형만 넣는다(게이브 Guns, Grenades 는 좀비런 것을 옮김).
# 좀비런에서 2026-11-10 Java 를 grep 해 뺀 것:
# gun.<rifle, pistol, smg, sniper>.reload, reload_empty(TaC 통짜 재장전 소리. Java 는 박자에 맞춘 부품 소리 mag_out, mag_in, slide, charge 를 씀),
# gun.shotgun.reload_end_empty(빈 총 재장전 끝은 pump), gun.sniper.bolt(쏜 뒤는 bolt_pull, bolt_release), gun.shotgun.pump 의 JEG 변형
# (Java 는 늘 변형 0 = TaC m870_pump). gun.shotgun.shell 은 재장전이 변형 1(shell2)로 박자를 맞추지만 탄약 줍기(Loot)가 아무 변형이나
# 고르므로 둘 다 둔다. 뺀 원본은 ref/mods 에 그대로 있으니 Java 가 부르게 되면 다시 넣을 것. 게이브 몹 소리는 여기 말고 tools/make_gabe.py
# (종 팩 스크립트, gave:gabe.<종>.<이름>) 몫
SOUNDS = {
    # 돌격소총(M4)
    "gun.rifle.fire": ([(TACS + "item/m4a1_fire", "gun/rifle/fire1", {"trim": 1.6}), (JEGS + "item/assault_rifle/fire", "gun/rifle/fire2", {"trim": 1.6})], "소총 사격"),
    "gun.rifle.mag_out": ([(JEGS + "item/assault_rifle/reload_magazine_out", "gun/common/mag_out_rifle", {})], "탄창 빠짐"),
    "gun.rifle.mag_in": ([(JEGS + "item/assault_rifle/reload_magazine_in", "gun/common/mag_in_rifle", {})], "탄창 꽂음"),
    "gun.rifle.dry": ([(TACS + "item/noammo", "gun/common/dry", {})], "빈 총 딸깍"),
    "gun.rifle.draw": ([(TACS + "animation/m4_draw", "gun/rifle/draw", {})], "총 꺼냄"),
    # 펌프 산탄총(M870)
    "gun.shotgun.fire": ([(TACS + "item/m870_fire", "gun/shotgun/fire1", {"trim": 2.2}), (JEGS + "item/pump_shotgun/fire", "gun/shotgun/fire2", {})], "산탄총 사격"),
    "gun.shotgun.pump": ([(TACS + "animation/m870_pump", "gun/shotgun/pump1", {})], "펌프 당김"),
    "gun.shotgun.reload_start": ([(TACS + "animation/m870_reload_intro", "gun/shotgun/reload_start", {})], "산탄총 재장전"),
    # shell1(TaC)은 원본 앞 0.51 초가 무음이라 첫 소리 0.02 초 앞부터 자른다(lead)
    "gun.shotgun.shell": ([(TACS + "animation/m870_reload_loop", "gun/shotgun/shell1", {"lead": True}), (JEGS + "item/pump_shotgun/shell", "gun/shotgun/shell2", {})], "산탄 넣음"),
    "gun.shotgun.reload_end": ([(TACS + "animation/m870_reload_norm_end", "gun/shotgun/reload_end", {})], "산탄총 재장전 끝"),
    "gun.shotgun.dry": ([(TACS + "item/noammo", "gun/common/dry", {})], "빈 총 딸깍"),
    "gun.shotgun.draw": ([(TACS + "animation/m870_draw", "gun/shotgun/draw", {})], "총 꺼냄"),
    # 권총(글록 17)
    "gun.pistol.fire": ([(TACS + "item/glock17_fire", "gun/pistol/fire1", {}), (JEGS + "item/pistol/fire", "gun/pistol/fire2", {})], "권총 사격"),
    "gun.pistol.mag_out": ([(JEGS + "item/combat_pistol/reload_mag_out", "gun/common/mag_out_pistol", {})], "탄창 빠짐"),
    "gun.pistol.mag_in": ([(JEGS + "item/combat_pistol/reload_mag_in", "gun/common/mag_in_pistol", {})], "탄창 꽂음"),
    "gun.pistol.slide": ([(TACS + "item/cock", "gun/pistol/slide", {})], "슬라이드 당김"),
    "gun.pistol.dry": ([(TACS + "item/noammo", "gun/common/dry", {})], "빈 총 딸깍"),
    "gun.pistol.draw": ([(TACS + "animation/glock17_draw", "gun/pistol/draw", {})], "총 꺼냄"),
    # 기관단총(MP5)
    "gun.smg.fire": ([(TACS + "item/mp5a5_fire", "gun/smg/fire1", {"trim": 1.4}), (JEGS + "item/custom_smg/fire", "gun/smg/fire2", {})], "기관단총 사격"),
    "gun.smg.mag_out": ([(JEGS + "item/assault_rifle/reload_magazine_out", "gun/common/mag_out_rifle", {})], "탄창 빠짐"),
    "gun.smg.mag_in": ([(JEGS + "item/assault_rifle/reload_magazine_in", "gun/common/mag_in_rifle", {})], "탄창 꽂음"),
    "gun.smg.dry": ([(TACS + "item/noammo", "gun/common/dry", {})], "빈 총 딸깍"),
    "gun.smg.draw": ([(TACS + "animation/hk_mp5a5_draw", "gun/smg/draw", {})], "총 꺼냄"),
    # 빈 총 재장전 끝 장전 손잡이 당겼다 놓기(JEG, 당김과 놓음이 한 파일)
    "gun.rifle.charge": ([(JEGS + "item/assault_rifle/reload_ejector", "gun/rifle/charge", {})], "장전 손잡이 당김"),
    "gun.smg.charge": ([(JEGS + "item/combat_rifle/reload_ejector", "gun/smg/charge", {})], "장전 손잡이 당김"),
    # 저격소총(AWP)
    "gun.sniper.fire": ([(TACS + "item/aiawp_fire", "gun/sniper/fire1", {}), (JEGS + "item/bolt_action_rifle/fire", "gun/sniper/fire2", {})], "저격총 사격"),
    "gun.sniper.bolt_pull": ([(JEGS + "item/bolt_action_rifle/bolt_pull", "gun/sniper/bolt_pull", {})], "노리쇠 뒤로"),
    "gun.sniper.bolt_release": ([(JEGS + "item/bolt_action_rifle/bolt_release", "gun/sniper/bolt_release", {})], "노리쇠 앞으로"),
    "gun.sniper.mag_out": ([(JEGS + "item/assault_rifle/reload_magazine_out", "gun/common/mag_out_rifle", {})], "탄창 빠짐"),
    "gun.sniper.mag_in": ([(JEGS + "item/assault_rifle/reload_magazine_in", "gun/common/mag_in_rifle", {})], "탄창 꽂음"),
    "gun.sniper.dry": ([(TACS + "item/noammo", "gun/common/dry", {})], "빈 총 딸깍"),
    "gun.sniper.draw": ([(TACS + "animation/awp_draw", "gun/sniper/draw", {})], "총 꺼냄"),
    # 수류탄
    "grenade.pin": ([(TGS + "grenade_pinpull", "grenade/pin1", {}), (JEGS + "item/grenade/pin", "grenade/pin2", {})], "안전핀 뽑음"),
    "grenade.spoon": ([(TGS + "grenade_spooneject", "grenade/spoon", {})], "안전손잡이 튐"),
    # 수류탄 튕김: 전용 소리가 없어 쇠 맞는 소리를 작게(0.45), 낮게(PITCH 0.8)
    "grenade.bounce": ([(JEGS + f"block/hit/metal{i}", f"impact/metal{i}", {"volume": 0.45}) for i in (1, 2, 3)], "수류탄 튕김"),
    "grenade.explode": ([(TGS + "grenade_explode_1", "grenade/explode", {})], "수류탄 폭발"),
    "grenade.explode_far": ([(JEGS + "event/ambient/distant_explosion", "grenade/explode_far", {})], "먼 폭발"),
    # ---- 새 무기(2026-11-12 wpn12, Guns.Kind FLAME .. BOW): 직접 받은 CC0 소리. 꺼냄, 빈 총은 옛 파일을 가리키기만(새 파일 없음)
    "gun.flamethrower.fire": ([(KEN + "thrusterFire_000.ogg", "gun/flame/fire", {"cut": (0.0, 0.75), "trim": 0.75, "fade": 0.2, "norm": -4.0, "rate": SFX_RATE})],
                              "화염방사기 불길"),
    "gun.flamethrower.ignite": ([("sfx:catching-fire/flame_0.ogg", "gun/flame/ignite", {"norm": -3.0, "rate": SFX_RATE})], "불 붙임"),
    "gun.freeze.fire": ([(KEN + "thrusterFire_002.ogg", "gun/freeze/fire", {"cut": (1.0, 1.75), "trim": 0.75, "fade": 0.2, "norm": -6.0, "rate": SFX_RATE})],
                        "냉기 분사"),
    "gun.freeze.ignite": ([(KEN + "forceField_001.ogg", "gun/freeze/start", {"norm": -4.0, "rate": SFX_RATE})], "냉기 시작"),
    "gun.tesla.fire": ([("sfx:bmaczero-electricity/spark.wav", "gun/tesla/fire1", {"lead": True, "norm": -2.0, "rate": SFX_RATE}),
                        ("sfx:bmaczero-electricity/continuousspark.wav", "gun/tesla/fire2", {"norm": -3.0, "rate": SFX_RATE})], "전기 방전"),
    "gun.railgun.charge": ([(KEN + "forceField_000.ogg", "gun/railgun/charge", {"norm": -4.0, "rate": SFX_RATE})], "레일건 충전"),
    "gun.railgun.fire": ([(KEN + "laserLarge_002.ogg", "gun/railgun/fire", {"norm": -1.5, "rate": SFX_RATE})], "레일건 발사"),
    "gun.minigun.fire": ([(FFL + "P_30P.wav", "gun/minigun/fire", {"lead": True, "trim": 0.32, "fade": 0.12, "norm": -2.0, "rate": SFX_RATE})], "미니건 사격"),
    "gun.minigun.spin": ([(KEN + "spaceEngine_000.ogg", "gun/minigun/spin", {"cut": (0.0, 0.6), "trim": 0.6, "fade": 0.15, "norm": -6.0, "rate": SFX_RATE})],
                         "총열 회전"),
    "gun.lmg.fire": ([(FFL + "C_28P.wav", "gun/lmg/fire", {"lead": True, "trim": 0.7, "fade": 0.3, "norm": -1.5, "rate": SFX_RATE})], "경기관총 사격"),
    "gun.revolver.fire": ([(FFL + "V_27P.wav", "gun/revolver/fire", {"lead": True, "trim": 1.2, "fade": 0.5, "norm": -1.0, "rate": SFX_RATE})], "리볼버 사격"),
    "gun.burst.fire": ([(FFL + "D_32P.wav", "gun/burst/fire", {"lead": True, "trim": 0.55, "fade": 0.25, "norm": -1.5, "rate": SFX_RATE})], "점사 소총 사격"),
    "gun.dmr.fire": ([(FFL + "U_14P.wav", "gun/dmr/fire", {"lead": True, "trim": 1.2, "fade": 0.5, "norm": -1.0, "rate": SFX_RATE})], "지정사수 소총 사격"),
    "gun.launcher.fire": ([("sfx:cannon-fire/cannon_fire_0.ogg", "gun/launcher/fire", {"trim": 1.0, "fade": 0.4, "norm": -2.0, "rate": SFX_RATE})], "유탄 발사"),
    "gun.launcher.explode": ([("sfx:chunky-explosion/Chunky Explosion.mp3", "gun/launcher/explode", {"lead": True, "trim": 2.4, "fade": 0.8, "norm": -1.0,
                                                                                                         "rate": SFX_RATE})], "유탄 폭발"),
    "gun.rocket.fire": ([(KEN + "thrusterFire_000.ogg", "gun/rocket/fire", {"cut": (2.0, 3.2), "trim": 1.2, "fade": 0.5, "norm": -2.0, "rate": SFX_RATE})],
                        "로켓 발사"),
    "gun.rocket.explode": ([(KEN + "explosionCrunch_004.ogg", "gun/rocket/explode", {"norm": -0.5, "rate": SFX_RATE})], "로켓 폭발"),
    "gun.bow.fire": ([("minecraft:item/crossbow/shoot1", "", {"vanilla": True}), ("minecraft:item/crossbow/shoot2", "", {"vanilla": True})], "활 쏨"),
    # 맞는 소리(덤)
    "impact.metal": ([(JEGS + f"block/hit/metal{i}", f"impact/metal{i}", {}) for i in (1, 2, 3)], "탄 맞음(쇠)"),
    "impact.stone": ([(JEGS + f"block/hit/stone{i}", f"impact/stone{i}", {}) for i in (1, 2)], "탄 맞음(돌)"),
    "impact.wood": ([(JEGS + f"block/hit/wood{i}", f"impact/wood{i}", {}) for i in (1, 2, 3)], "탄 맞음(나무)"),
    "impact.flesh": ([(JEGS + "entity/bullet/hit", "impact/flesh", {})], "탄 맞음(몸)"),
    "bullet.whiz": ([(JEGS + f"entity/bullet/close{i}", f"impact/whiz{i}", {}) for i in (1, 2, 3)], "탄 스침"),
}
NEW_KEYS = ("flamethrower", "launcher", "rocket", "minigun", "lmg", "revolver", "burst", "dmr", "railgun", "tesla", "freeze", "bow")
# 2026-11-12 wpn_max 새 종류 키(무기 모델 표 KINDS): 꺼냄, 빈 총은 옛 파일, 종류 총소리는 그 종류 기본 모델 소리와 같은 파일
import arms_table  # noqa: E402

NEW_KINDS = tuple(k for k, _, _ in arms_table.KINDS.values() if k not in NEW_KEYS and k not in ("pistol", "smg", "shotgun", "rifle", "sniper"))
ONE_HAND = ("machinepistol", "dualpistol")
# 새 총의 꺼냄, 빈 총 딸깍: 옛 파일을 가리키는 사건만 더한다(새 파일 없음, Java 가 "gun.<키>.draw", ".dry" 로 부름). 재장전 부품 소리는
# Guns.CUES 가 옛 사건(gun.rifle.mag_out 같은 것)을 바로 부른다
for _k in NEW_KEYS + NEW_KINDS:
    SOUNDS.setdefault(f"gun.{_k}.draw", (SOUNDS["gun.pistol.draw" if _k in ("revolver",) + ONE_HAND else "gun.rifle.draw"][0], "총 꺼냄"))
    SOUNDS.setdefault(f"gun.{_k}.dry", (SOUNDS["gun.rifle.dry"][0], "빈 총 딸깍"))
# 노리쇠 당기는 새 종류(볼트액션, 대물 저격총)는 저격총 노리쇠 소리(Guns.cycleTick 이 gun.<종류 키>.bolt_pull, bolt_release 를 부름)
for _k in ("boltaction", "antimateriel"):
    for _p in ("bolt_pull", "bolt_release"):
        SOUNDS.setdefault(f"gun.{_k}.{_p}", SOUNDS[f"gun.sniper.{_p}"])

# 모델마다 총소리(2026-11-12 wpn_max): 무기 모델 표 snd = 받은 CC0 총소리(The Free Firearm Sound Library 가까운 한 발, Kenney 레이저).
# 같은 원본 + 같은 처리는 파일 하나를 같이 가리키고(높이만 모델마다 sounds.json pitch), 연사 무기는 꼬리를 짧게 자른다.
# 소음기 총은 같은 녹음을 낮은 통과 필터로 먹먹하게(합성 아님, 받은 녹음을 거른 것). snd 가 없는 모델은 Java 가 종류 사건(gun.<종류 키>.fire)을 부름
FAST = {"SMG", "RIFLE", "BURST", "LMG", "MINIGUN", "MACHINEPISTOL", "DUAL", "AUTOSHOT", "SILENCED", "LASER"}
SND_OPT = {"fast": {"lead": True, "trim": 0.5, "fade": 0.2, "norm": -1.5, "rate": SFX_RATE},
           "slow": {"lead": True, "trim": 1.2, "fade": 0.5, "norm": -1.0, "rate": SFX_RATE},
           "quiet": {"lead": True, "trim": 0.45, "fade": 0.2, "norm": -9.0, "rate": SFX_RATE, "af": "lowpass=f=1500,lowpass=f=1500"},
           "laser": {"norm": -3.0, "rate": SFX_RATE}}
MODEL_FIRE = {}  # 모델 키 → 사건(props.json arms 의 sound)
for _r in arms_table.ROWS:
    _s = _r.get("snd")
    if not _s:
        continue
    if _s.startswith("laser:"):
        _src, _cls = KEN + _s[6:] + ".ogg", "laser"
    else:
        _src, _cls = FFL + _s + ".wav", "quiet" if _r["kind"] == "SILENCED" else "fast" if _r["kind"] in FAST else "slow"
    _opt = dict(SND_OPT[_cls])
    if abs(_r.get("pitch", 1.0) - 1.0) > 1e-6:
        _opt["pitch"] = _r["pitch"]
    _ev = f"gun.{_r['key']}.fire"
    SOUNDS[_ev] = ([(_src, f"gun/model/{_s.split(':')[-1].lower()}_{_cls}", _opt)], f"{_r['name']} 사격")
    MODEL_FIRE[_r["key"]] = _ev
# 새 종류 키의 총소리(종류 사건): 그 종류 기본 모델 소리 그대로
for _kind, (_kk, _kn, _def) in arms_table.KINDS.items():
    if _kk in NEW_KINDS and f"gun.{_def}.fire" in SOUNDS:
        _lst, _ = SOUNDS[f"gun.{_def}.fire"]
        SOUNDS[f"gun.{_kk}.fire"] = ([(a, b, {k: v for k, v in c.items() if k != "pitch"}) for a, b, c in _lst], f"{_kn} 사격")

# 귀에 들리는 높이 = sounds.json pitch(2026-11-10 높이 계약, Props.java 머리 주석). Java 는 팩에 그 사건이 있으면 흔들림(1 둘레 무작위,
# 없으면 1)만 pitch 로 보내고 클라가 sounds.json pitch x 보낸 pitch 를 0.5~2.0 으로 자른다. 모드 소리 높이를 바꾸려면 여기만 고치고
# 다시 빌드한다(Java 호출의 fallbackPitch 는 팩에 사건이 없을 때 울리는 바닐라 대체 소리에만 쓴다). 적지 않은 사건은 1
# 폭발 둘은 높이 계약 전 Java 호출 높이(0.95, 먼 폭발은 낮고 길게 0.6)를 그대로 이어받음
PITCH = {"grenade.bounce": 0.8, "grenade.explode": 0.95, "grenade.explode_far": 0.6,
         # 새 무기(2026-11-12): 냉기는 불길 소리를 높여 치익, 유탄은 대포 소리를 높여 퉁, 레일건은 낮춰 길게, 총열 회전은 높여 윙
         "gun.freeze.fire": 1.6, "gun.launcher.fire": 1.45, "gun.railgun.fire": 0.7, "gun.minigun.spin": 1.35, "gun.rocket.fire": 1.15,
         "gun.railgun.charge": 0.85}

# 들리는 거리(sounds.json attenuation_distance)는 Java 가 소리를 보내는 반경과 맞춘다. 클라는 attenuation_distance x 음량(1 이상)까지
# 줄여 들려주므로 이 값이 보내는 반경보다 길면 반경 끝에서 소리가 뚝 끊긴다(반경 밖 사람은 패킷을 못 받음). 반경은 Java 코드에서 봄:
#  - Props.soundAround(.., range): range 안 사람마다 따로 보냄(음량 1 이하) → range. 총소리는 Guns Spec.soundRange(GUN_FIRE_RANGE),
#    꺼냄, 빈 총 딸깍 12(GUN_PART_RANGE), 탄착 20, 수류탄 핀, 안전손잡이 16, 튕김 24. 재장전 부품 소리는 16 이라 기본값(적지 않음)
#  - Props.sound(자리): 서버가 16 x 음량(1 이하면 16) 블록 안에 뿌리고 클라도 16 x 음량까지 → 16
#  - Props.soundTo(한 사람): 부르는 쪽이 고른 사람에게만. 가까운 폭발은 48 블록 안 사람에게, 그 밖은 192 까지 먼 폭발로 넘겨주므로
#    소리가 사라지는 끝이 없어 96 그대로. 48 블록에서 가까운 폭발은 50%, 먼 폭발은 Java 가 음량 2/3 으로 보내 0.667 x 75% = 50% 라 이어진다
#    (Grenades.explode. 48 로 맞추면 12 블록만 넘어도 48 블록의 먼 폭발보다 작아짐).
#    탄 스침(2.5 블록 안 한 사람), 탄약 줍기(본인 자리)는 기본 16
GUN_FIRE_RANGE = {"rifle": 96, "shotgun": 80, "pistol": 64, "smg": 80, "sniper": 128,
                  # 새 무기(Guns.SPECS soundRange 와 같게)
                  "flamethrower": 48, "launcher": 64, "rocket": 96, "minigun": 96, "lmg": 96, "revolver": 80, "burst": 96, "dmr": 112,
                  "railgun": 128, "tesla": 64, "freeze": 40, "bow": 32}
GUN_PART_RANGE = {"draw": 12, "dry": 12}
# 모델 키 → 종류 키(모델 총소리 반경). 새 종류 반경은 Guns.SPECS soundRange 와 같게
MODEL_KIND = {r["key"]: arms_table.KINDS[r["kind"]][0] for r in arms_table.ROWS}
GUN_FIRE_RANGE.update({"autoshotgun": 80, "doublebarrel": 80, "boltaction": 112, "machinepistol": 64, "dualpistol": 64, "antimateriel": 144,
                       "silenced": 20, "musket": 96, "laser": 64})
RANGE = {"grenade.explode": 96, "grenade.explode_far": 192, "grenade.pin": 16, "grenade.spoon": 16, "grenade.bounce": 24,
         "impact.metal": 20, "impact.stone": 20, "impact.wood": 20, "impact.flesh": 20,
         # 새 무기: 폭발은 수류탄처럼 가까운 소리 96(멀면 grenade.explode_far), 충전, 회전, 점화는 Java soundAround 반경
         "gun.launcher.explode": 96, "gun.rocket.explode": 96, "gun.railgun.charge": 24, "gun.minigun.spin": 32,
         "gun.flamethrower.ignite": 24, "gun.freeze.ignite": 24}

# Java 가 부르는 자리와 값(기록용, props.json sounds 의 call. 소리 출력에는 쓰지 않음): 부르는 곳, 음량, 흔들림(Java 가 보내는 pitch),
# 바닐라 대체 소리와 그 높이(fallbackPitch). 예전에 sounds.json pitch 를 호출 pitch 로 되돌려 적던 사건들(좀비런 docs/소품출처.md '호출 pitch 되돌림' 표).
# Java 를 바꾸면 여기와 그 표도 맞출 것
CALL = {
    "gun.rifle.charge": ("Guns CUES RIFLE 32틱(빈 총 재장전), Props.soundAround 16", 0.9, [0.97, 1.03], "BLOCK_PISTON_CONTRACT", 1.1),
    "gun.smg.charge": ("Guns CUES SMG 28틱(빈 총 재장전), Props.soundAround 16", 0.9, [0.97, 1.03], "BLOCK_PISTON_CONTRACT", 0.85)}

# props.json sound_notes(소리 계약이라 이 파일이 정한다. run 이 build_props.write_props_json 뒤에 바꿔 끼움)
SOUND_NOTES = ("모든 사건은 sounds.json 에 자막(ko_kr, en_us) 과 함께. 원본이 스테레오면 모노로 다시 부호화(libvorbis q7), 모노면 바이트 그대로 복사. "
               "연사 총 소리는 꼬리를 잘라 겹침을 줄임(sound_files.process). "
               "높이 계약(Props.java 머리 주석): 팩에 사건이 있으면 Java 는 흔들림(1 둘레 무작위, 없으면 1)만 pitch 로 보내고, 귀에 들리는 높이는 "
               "sounds.json pitch(사건의 pitch, 없으면 1)가 정한다(클라가 둘을 곱해 0.5~2.0 으로 자름). 모드 소리 높이를 바꾸려면 build_more.py PITCH 만 고친다. "
               "call 은 Java 가 부르는 자리와 값이고 fallback_pitch 는 팩에 사건이 없을 때 울리는 바닐라 대체 소리에만 쓴다. "
               "attenuation_distance 는 Java 가 보내는 반경과 맞춘다(soundAround 는 그 range, Props.sound 는 서버가 16 x 음량 안에 뿌려 16, "
               "몸 소리 soundOn 은 16, 가까운 폭발은 48 밖을 먼 폭발이 넘겨받아 96). 반경보다 길면 반경 끝에서 뚝 끊긴다. "
               "Java 가 부르지 않는 사건, 고르지 않는 변형은 층에 넣지 않는다(build_more.py SOUNDS 위 주석). "
               "vanilla_reference 인 사건은 팩에 파일 없이 바닐라 소리를 가리킴(files 의 minecraft: 경로)")


def source(srcrel):
    """원본 → (파일 경로, props.json from 글, 출처 이름). sfx: 는 ref/sfx/weapons/<출처>/<파일>(확장자 포함), 나머지는 ref/mods/<모드>/extracted/..(.ogg)"""
    if srcrel.startswith("sfx:"):
        rel = srcrel[4:]
        src = rel.split("/")[0]
        info = G["ROOT"] / "ref/sfx/weapons" / src / "_info.json"
        title = json.loads(info.read_text(encoding="utf-8")).get("title", src) if info.is_file() else src
        return G["ROOT"] / "ref/sfx/weapons" / rel, f"sfx/weapons/{src}: {rel.split('/', 1)[1]}", title
    mod = srcrel.split("/")[0]
    return G["REF"] / (srcrel + ".ogg"), f"{mod}: {srcrel.split('/extracted/')[1]}.ogg", SRC_NAME[mod]


def pcm(path, rate, af=None):
    """소리 파일 → 모노 float32(표본율 rate). af: 섞기 전에 거는 ffmpeg 오디오 필터"""
    args = [FF, "-v", "error", "-i", str(path)]
    if af:
        args += ["-af", af]
    args += ["-ac", "1", "-ar", str(rate), "-f", "f32le", "-"]
    return np.frombuffer(subprocess.run(args, capture_output=True, check=True).stdout, np.float32).copy()


def peak_db(x):
    return 20 * np.log10(float(np.abs(x).max()) + 1e-12)


def fade(x, rate, t_in=0.0, t_out=0.0):
    x = x.copy()
    if t_in > 0:
        n = min(len(x), int(rate * t_in))
        x[:n] *= np.linspace(0, 1, n, endpoint=False)
    if t_out > 0:
        n = min(len(x), int(rate * t_out))
        x[len(x) - n:] *= np.linspace(1, 0, n)
    return x


def onset(x, rel_db=-40.0):
    """첫 소리: 최대 음량보다 rel_db 아래 문턱을 처음 넘는 표본"""
    idx = np.nonzero(np.abs(x) > np.abs(x).max() * 10 ** (rel_db / 20))[0]
    return int(idx[0]) if len(idx) else 0


NEW_OPTS = ("lead", "norm", "mix", "cut", "rate", "af")


def process(src_path, opt, rate):
    """앞 무음 자르기(lead), 구간(cut), 끝 자르기(trim, fade), 섞기(mix), 최대 음량 맞춤(norm). (표본, 처리 설명)"""
    how = []
    x = pcm(src_path, rate, opt.get("af"))
    if opt.get("af"):
        how.append(f"ffmpeg 필터 {opt['af']}")
    if opt.get("cut"):
        a, b = opt["cut"]
        x = x[int(a * rate):int(b * rate)]
        how.append(f"{a}~{b}초 구간")
    if opt.get("lead"):
        s = max(0, onset(x) - int(0.02 * rate))
        if s > 0:
            x = fade(x[s:], rate, t_in=0.005)
            how.append(f"앞 무음 {s / rate:.2f}초 자름(첫 소리 0.02초 앞부터, 5 ms 페이드 인)")
    if opt.get("trim") and len(x) > opt["trim"] * rate:
        f = opt.get("fade", 0.25)
        d0 = len(x) / rate
        x = fade(x[:int(opt["trim"] * rate)], rate, t_out=f)
        how.append(f"{d0:.2f}초 → {opt['trim']}초로 자름(끝 {f}초 페이드)")
    if opt.get("mix"):
        x = x * 10 ** ((opt.get("base_peak", -3.0) - peak_db(x)) / 20)
        d = int(opt.get("delay", 0) * rate)
        x = np.concatenate([np.zeros(d, np.float32), x])
        for lay in opt["mix"]:
            y = pcm(G["REF"] / (lay["src"] + ".ogg"), rate, lay.get("af"))
            if lay.get("dur") and len(y) > lay["dur"] * rate:
                y = fade(y[:int(lay["dur"] * rate)], rate, t_out=lay.get("fade", 0.2))
            y = y * 10 ** ((lay.get("peak", -6.0) - peak_db(y)) / 20)
            n = max(len(x), len(y))
            x = np.pad(x, (0, n - len(x))) + np.pad(y, (0, n - len(y)))
            how.append(f"{lay['src'].split('/')[0]}: {lay['src'].split('/sounds/')[1]} 섞음({lay.get('af', '')}, 앞 {lay.get('dur')}초, 최대 {lay.get('peak', -6.0)} dB)")
    if opt.get("norm") is not None:
        g = opt["norm"] - peak_db(x)
        x = x * 10 ** (g / 20)
        how.append(f"최대 음량 {opt['norm']} dBFS 로({g:+.1f} dB)")
    if np.abs(x).max() > 0.99:
        x = x * (0.95 / np.abs(x).max())
    return x.astype("<f4"), how


def encode(src_path, dst_path, opt=None):
    """원본 ogg → 모노 ogg. 이미 모노이고 손대지 않으면 바이트 그대로 복사, 아니면 libvorbis q7 로 다시 부호화(끝 자를 때 0.25 초 줄어듦).
    lead, norm, mix, cut 이 있으면 numpy 로 처리한 뒤 부호화"""
    opt = opt or {}
    trim = opt.get("trim")
    info = SI.info(src_path)
    dst_path.parent.mkdir(parents=True, exist_ok=True)
    if any(opt.get(k) is not None and opt.get(k) is not False for k in NEW_OPTS):
        rate = int(opt.get("rate") or info["rate"])
        x, how = process(src_path, opt, rate)
        if rate != info["rate"]:
            how.insert(0, f"{info['rate']} Hz → {rate} Hz")
        subprocess.run([FF, "-y", "-hide_banner", "-loglevel", "error", "-f", "f32le", "-ar", str(rate), "-ac", "1", "-i", "-",
                        "-c:a", "libvorbis", "-q:a", "7", "-map_metadata", "-1", str(dst_path)], input=x.tobytes(), check=True)
        if info["layout"] != "mono":
            how.insert(0, "스테레오 → 모노(L+R 평균)")
        return ", ".join(how + ["libvorbis q7 다시 부호화"]), info
    if info["layout"] == "mono" and not (trim and info["dur"] > trim):
        dst_path.write_bytes(src_path.read_bytes())
        return "그대로", info
    args = [FF, "-y", "-hide_banner", "-loglevel", "error", "-i", str(src_path)]
    af = []
    if trim and info["dur"] > trim:
        args += ["-t", f"{trim}"]
        af.append(f"afade=t=out:st={trim - 0.25:.3f}:d=0.25")
    if af:
        args += ["-af", ",".join(af)]
    args += ["-ac", "1", "-ar", str(info["rate"]), "-c:a", "libvorbis", "-q:a", "7", "-map_metadata", "-1", str(dst_path)]
    subprocess.run(args, check=True)
    how = []
    if info["layout"] != "mono":
        how.append("스테레오 → 모노(L+R 평균)")
    if trim and info["dur"] > trim:
        how.append(f"{info['dur']:.2f}초 → {trim}초로 자름(끝 0.25초 줄어듦)")
    return ", ".join(how) + ", libvorbis q7 다시 부호화", info


def entry_pitch(ev, opt):
    """sounds.json pitch(= 귀에 들리는 높이): 파일 opt 의 pitch, 없으면 PITCH. 1 이면 None(적지 않음)"""
    p = opt.get("pitch", PITCH.get(ev))
    return None if p is None or abs(p - 1.0) < 1e-6 else p


def attenuation(ev):
    """sounds.json attenuation_distance(Java 가 보내는 반경, RANGE 위 주석). None 이면 적지 않음(기본 16)"""
    if ev in RANGE:
        return RANGE[ev]
    k = ev.split(".")
    if len(k) == 3 and k[0] == "gun" and k[1] in MODEL_KIND:  # 모델 총소리: 그 종류의 반경
        k[1] = MODEL_KIND[k[1]]
    if k[0] == "gun" and len(k) == 3:
        return GUN_FIRE_RANGE.get(k[1]) if k[2] == "fire" else GUN_PART_RANGE.get(k[2])
    return None


def build_sounds():
    REF, A, NS = G["REF"], G["A"], G["NS"]
    sj, lang_ko, lang_en = {}, {}, {}
    done = {}
    log = []
    for ev, (lst, sub) in SOUNDS.items():
        entries = []
        for srcrel, name, opt in lst:
            if opt.get("vanilla"):  # 바닐라 소리 파일을 가리킴(팩에 파일을 넣지 않음)
                e = {"name": srcrel}
                if srcrel not in done:
                    done[srcrel] = "참조만"
                    log.append({"file": srcrel + ".ogg", "from": "바닐라 26.3 기본 소리(assets/minecraft/sounds)", "source": "Minecraft 26.3",
                                "process": "팩에 넣지 않고 sounds.json 에서 가리키기만 함", "vanilla": True})
            else:
                sp, frm, sname = source(srcrel)
                dp = A / "sounds" / (name + ".ogg")
                if name not in done:
                    how, info = encode(sp, dp, opt)
                    done[name] = how
                    ent = {"file": f"{name}.ogg", "from": frm, "source": sname,
                           "process": how, "src_layout": info["layout"], "src_dur": info["dur"]}
                    if opt.get("mix"):  # 섞은 원본도 적는다(check_pack.py 가 원본 주석의 출처 흔적을 봄)
                        ent["mixed"] = [f"{lay['src'].split('/')[0]}: {lay['src'].split('/extracted/')[1]}.ogg" for lay in opt["mix"]]
                    log.append(ent)
                    G["written"][f"assets/{NS}/sounds/{name}.ogg"] = dp
                e = {"name": f"{NS}:{name}"}
            if opt.get("volume"):
                e["volume"] = opt["volume"]
            p = entry_pitch(ev, opt)
            if p:
                e["pitch"] = p
            rng = attenuation(ev)
            if rng:
                e["attenuation_distance"] = rng
            entries.append(e)
        skey = f"subtitles.{NS}.{ev}"
        sj[ev] = {"sounds": entries, "subtitle": skey}
        lang_ko[skey] = sub
        lang_en[skey] = ev.replace(".", " ")
        # props.json sounds: 파일, 자막, sounds.json 과 같은 pitch(= 들리는 높이)와 attenuation_distance, Java 호출(call, 기록용)
        snd = {"event": f"{NS}:{ev}", "files": [(s + ".ogg") if o.get("vanilla") else f"{n}.ogg" for s, n, o in lst], "subtitle_ko": sub}
        for k in ("pitch", "attenuation_distance"):
            vals = sorted({e[k] for e in entries if k in e})
            if vals:
                snd[k] = vals[0] if len(vals) == 1 else vals
        if ev in CALL:
            where, vol, jitter, fallback, fallback_pitch = CALL[ev]
            snd["call"] = {"where": where, "volume": vol, "jitter": jitter, "fallback": fallback, "fallback_pitch": fallback_pitch}
        if any(o.get("vanilla") for _, _, o in lst):
            snd["vanilla_reference"] = True
        G["SOURCES"]["sound:" + ev] = dict(mod="", url="", version="", license="", detail="", sound=snd)
    G["put_json"](f"assets/{NS}/sounds.json", sj)
    G["put_json"](f"assets/{NS}/lang/ko_kr.json", lang_ko)
    G["put_json"](f"assets/{NS}/lang/en_us.json", lang_en)
    G["SOUND_LOG"] = log


def run(g):
    G.update(g)
    build_sounds()
    g["SOUND_LOG"] = G["SOUND_LOG"]  # build_props 의 전역에도(props.json sound_files)
    # props.json sound_notes 는 소리 계약이라 이 파일 몫: build_props.write_props_json 이 적는 옛 글(호출 pitch 되돌림)을 다 쓴 뒤
    # SOUND_NOTES 로 바꿔 끼운다(build_props.main 은 이 run 다음에 이름으로 write_props_json 을 부름). build_props.py 가 SOUND_NOTES 를
    # 직접 쓰게 되면 이 감싸개는 지울 것
    write = g["write_props_json"]

    def write_props_json():
        write()
        p = g.get("PROPS_JSON", g["ROOT"] / "tools/props.json")  # build_props.PROPS_JSON: 굽는 자리를 돌리면 표 파일도 따라감
        obj = json.loads(p.read_text(encoding="utf-8"))
        obj["sound_notes"] = SOUND_NOTES
        p.write_text(json.dumps(obj, ensure_ascii=False, indent=1), encoding="utf-8")

    g["write_props_json"] = write_props_json
