"""맞은 번쩍임 시험 돕기(대본 pack_hurt.json 과 함께 백그라운드로): 슬롯 9 서버에 tag=hurtme 디스플레이가 생기면 바로 게임 틱을 읽어
hurtUntil = 지금 + 30 틱인 G01 색(kit 부호기)을 실어 준다. 셰이더는 끝 8틱 전부터 하얗게(1.1 .. 1.5 초 뒤). 다 하면 끝.

GV_SLOT=9 python -X utf8 tools/gabe/tests/hurt_helper.py
"""
import json
import os
import re
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools"))
os.environ.setdefault("GV_SLOT", "9")
from client_run import rcon  # noqa: E402
from gabe import kit  # noqa: E402

looks = json.loads((ROOT / "build/gabe/pack_layer/assets/gave/gabe_looks.json").read_text(encoding="utf-8"))["models"]
slots = looks["gabe/g01"]["slots"]
t_end = time.time() + 900
while time.time() < t_end:
    try:
        r = rcon("execute if entity @e[type=item_display,tag=hurtme]")
    except OSError:
        time.sleep(1)
        continue
    if "passed" in r.lower() or "count" in r.lower():
        t = int(re.findall(r"-?\d+", rcon("time query gametime"))[-1])
        st = kit.LookState(hurt_until=t + 30)
        cols = kit.colors(slots, st, t + 22)
        cmd = ('data merge entity @e[type=item_display,tag=hurtme,limit=1] {item:{id:"minecraft:paper",count:1,components:'
               '{"minecraft:item_model":"gave:gabe/g01","minecraft:custom_model_data":{strings:[""],colors:[%s]}}}}'
               % ",".join(str(c) for c in cols))
        print("gametime", t, "->", rcon(cmd).strip()[:120], flush=True)
        break
    time.sleep(0.1)
