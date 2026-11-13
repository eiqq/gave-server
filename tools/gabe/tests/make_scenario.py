"""팩 담당 게임 안 시험 대본을 만든다.
- tools/scenarios/pack_g01.json: 날 디스플레이(vanilla summon)에 kit 부호기 색을 실어 먼 얼굴(4블록), 가까운 얼굴(2.4블록),
  팔레트 넷, 펄럭임 영상, 옆모습(막대기 이음매, 뿌리 받침), 변이 2 막대기, G02, 그리고 엔진 경로(/gave 소환 G01 1 4, 12).
- tools/scenarios/pack_fps.json: G01 0, 50, 200 마리와 그냥 종이 200 개를 세워 F3 화면(fps)을 찍음. F3 은
  tools/gabe/tests/f3_helper.py 를 같이 돌려 누름(태그 f3 표식이 생기면).

python -X utf8 tools/gabe/tests/make_scenario.py
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools"))
from gabe import kit  # noqa: E402

looks = json.loads((ROOT / "build/gabe/pack_layer/assets/gave/gabe_looks.json").read_text(encoding="utf-8"))["models"]
G01 = looks["gabe/g01"]["slots"]


def item(model, state="", look=None):
    # 엔진 Body.item 이 쓸 상태를 아는 부호(Look.colors(model, state, look, wt) = kit.colors_state)
    cols = kit.colors_state(looks[model], state, look or kit.LookState(), 0)
    comp = '"minecraft:item_model":"gave:%s"' % model
    cmd = "strings:[\"%s\"]" % state + (",colors:[%s]" % ",".join(str(c) for c in cols) if cols else "")
    return '{id:"minecraft:paper",count:1,components:{%s,"minecraft:custom_model_data":{%s}}}' % (comp, cmd)


def summon(dx, dy, dz, model, state="", look=None, tag="pk", yaw=0):
    # Ripley 가 남쪽(yaw 0)을 볼 때 ^ ^ ^dz 앞. 변환 없음: 디스플레이가 모델을 y 로 반 바퀴 돌려 그리므로 모델 앞(+Z)이 북쪽(Ripley 쪽)을 봄
    rot = ",Rotation:[%sf,0f]" % yaw if yaw else ""
    return ("execute as {USER} at @s anchored eyes rotated 0 0 positioned ^%s ^%s ^%s run summon minecraft:item_display ~ ~ ~ "
            "{Tags:[\"%s\"],brightness:{sky:12,block:12},view_range:2.0%s,item:%s}" % (dx, dy, dz, tag, rot, item(model, state, look)))


steps = [
    ["wait", 2],
    ["cmd", "gave 시험 몹정지 {USER} on"],
    ["cmd", "gamemode creative {USER}"],
    ["cmd", "time set noon"],
    ["cmd", "execute as {USER} at @s run tp @s ~ ~ ~ 0 0"],
    ["wait", 1],
    ["cmd", summon(0, -0.1, 4, "gabe/g01")],
    ["wait", 1.5],
    ["shot", "far4"],
    ["rec", "flap", 30],
    ["wait", 1.6],
    ["recstop"],
    # 가까이(눈에서 2.4블록): 가까운 얼굴
    ["cmd", "execute as {USER} at @s run tp @s ~ ~ ~1.6 0 0"],
    ["wait", 1.2],
    ["shot", "near"],
    ["cmd", "execute as {USER} at @s run tp @s ~ ~ ~-1.6 0 0"],
    ["cmd", "kill @e[type=item_display,tag=pk]"],
    # 팔레트 넷 + 실눈 뜸, 변이 1, 변이 2, 끊긴 왼쪽
    ["cmd", summon(-2.7, 0.9, 7, "gabe/g01", look=kit.LookState(palette=1))],
    ["cmd", summon(-0.9, 0.9, 7, "gabe/g01", look=kit.LookState(palette=16))],
    ["cmd", summon(0.9, 0.9, 7, "gabe/g01", look=kit.LookState(palette=8))],
    ["cmd", summon(2.7, 0.9, 7, "gabe/g01", look=kit.LookState(palette=2 | 4))],
    ["cmd", summon(-2.7, -0.9, 7, "gabe/g01", look=kit.LookState(open=True))],
    ["cmd", summon(-0.9, -0.9, 7, "gabe/g01", look=kit.LookState(mut=1))],
    ["cmd", summon(0.9, -0.9, 7, "gabe/g01", look=kit.LookState(mut=2, phase=7))],
    ["cmd", summon(2.7, -0.9, 7, "gabe/g01", "charge", look=kit.LookState(on={"cut_l"}))],
    ["wait", 1.5],
    ["shot", "palette"],
    ["cmd", "kill @e[type=item_display,tag=pk]"],
    # 옆모습(모델을 90도 돌림): 막대기 이음매(받침 없이 이어짐), 뿌리 받침 하나, 젖은 천 띠, 변이 2 두 마디 막대기
    ["cmd", summon(-1.6, 0.2, 4.5, "gabe/g01", yaw=90)],
    ["cmd", summon(1.6, 0.2, 4.5, "gabe/g01", look=kit.LookState(mut=2, phase=3), yaw=90)],
    ["wait", 1.2],
    ["shot", "side"],
    ["cmd", "kill @e[type=item_display,tag=pk]"],
    # 떼(G02) 셋과 덩어리
    ["cmd", summon(-1.0, 0, 3.5, "gabe/g02", "a")],
    ["cmd", summon(0, 0, 3.5, "gabe/g02", "b", look=kit.LookState(phase=5))],
    ["cmd", summon(1.0, 0, 3.5, "gabe/g02", "c", look=kit.LookState(open=True))],
    ["cmd", summon(0, 0.2, 9, "gabe/g02", "lod")],
    ["wait", 1.2],
    ["shot", "g02"],
    ["cmd", "kill @e[type=item_display,tag=pk]"],
    # 엔진 경로: 판을 열고(총이 생김) /gave 소환 G01 12, 4 블록. 모험 모드라 몹이 사람을 찍고 돌아봄
    ["cmd", "gamemode adventure {USER}"],
    ["cmd", "gave 시작"],
    ["until", "state=PREP", 15],
    ["cmd", "gave 시험 총 {USER} RIFLE"],
    ["cmd", "execute as {USER} at @s run tp @s ~ ~ ~ 0 0"],
    ["cmd", "gave 소환 G01 1 12 {USER}"],
    ["wait", 0.7],
    ["shot", "spawn12"],
    ["cmd", "gave 시험 몹보기 {USER}"],
    ["cmd", "gave 시험 몹죽이기 {USER}"],
    ["cmd", "execute as {USER} at @s run tp @s ~ ~ ~ 0 0"],
    ["cmd", "gave 소환 G01 1 4 {USER}"],
    ["wait", 0.5],
    ["shot", "spawn4"],
    ["cmd", "gave 시험 몹정지 {USER} on"],
    ["cmd", "gave 시험 몹보기 {USER}"],
    ["cmd", "gave 중지"],
    ["cmd", "gave 상태"],
]


def fps_steps():
    """G01 0, 50, 200 마리, 그냥 종이 200 개: F3 화면 사진(f3_helper 가 F3 을 누름). 모두 다른 위상, 넷 중 하나는 charge"""
    grid = [(-5.4 + 1.2 * i, -0.6 + 1.0 * j, 6 + 2.5 * kk) for kk in range(4) for j in range(5) for i in range(10)]
    st = [["wait", 2], ["cmd", "gave 시험 몹정지 {USER} on"], ["cmd", "gamemode creative {USER}"], ["cmd", "time set noon"],
          ["cmd", "execute as {USER} at @s run tp @s ~ ~ ~ 0 0"], ["wait", 1],
          # 떼 덩어리(G02 lod, 자세 4 출렁임)를 5블록에서 한 번
          ["cmd", summon(0, 0.3, 5, "gabe/g02", "lod", look=kit.LookState(phase=9))], ["wait", 1.2], ["shot", "lod5"],
          ["cmd", "kill @e[type=item_display,tag=pk]"],
          ["cmd", "execute as {USER} at @s run summon minecraft:marker ~ ~ ~ {Tags:[\"f3\"]}"], ["wait", 3], ["shot", "fps0"]]
    for n, (lo, hi) in ((50, (0, 50)), (200, (50, 200))):
        for ix in range(lo, hi):
            dx, dy, dz = grid[ix]
            stt = "charge" if ix % 4 == 3 else ""
            st.append(["cmd", summon(round(dx, 2), round(dy, 2), dz, "gabe/g01", stt, look=kit.LookState(phase=(ix * 37) % 240))])
        st += [["wait", 4], ["shot", f"fps{n}"]]
    st.append(["cmd", "kill @e[type=item_display,tag=pk]"])
    for ix in range(200):
        dx, dy, dz = grid[ix]
        st.append(["cmd", "execute as {USER} at @s anchored eyes rotated 0 0 positioned ^%s ^%s ^%s run summon minecraft:item_display ~ ~ ~ "
                          "{Tags:[\"pk\"],brightness:{sky:12,block:12},item:{id:\"minecraft:paper\",count:1}}" % (round(dx, 2), round(dy, 2), dz)])
    st += [["wait", 4], ["shot", "fps200_paper"], ["cmd", "kill @e[type=item_display,tag=pk]"], ["cmd", "kill @e[type=marker,tag=f3]"]]
    return st


# 돌진 상태(charge: 20틱에 두 번 침) 펄럭임 영상, 상태를 모르는 부호(엔진이 지금 쓰는 Look.colors(model, look, wt))로 실음
charge_steps = [["wait", 2], ["cmd", "gave 시험 몹정지 {USER} on"], ["cmd", "gamemode creative {USER}"], ["cmd", "time set noon"],
                ["cmd", "execute as {USER} at @s run tp @s ~ ~ ~ 0 0"], ["wait", 1],
                ["cmd", summon(0, -0.1, 4, "gabe/g01", "charge", look=kit.LookState(phase=50))
                 .replace(",".join(map(str, kit.colors_state(looks["gabe/g01"], "charge", kit.LookState(phase=50), 0))),
                          ",".join(map(str, kit.colors(looks["gabe/g01"]["slots"], kit.LookState(phase=50), 0))))],
                ["wait", 1.2], ["rec", "charge", 30], ["wait", 1.6], ["recstop"], ["cmd", "kill @e[type=item_display,tag=pk]"]]

for name, stp in (("pack_g01", steps), ("pack_fps", fps_steps()), ("pack_charge", charge_steps)):
    out = ROOT / f"tools/scenarios/{name}.json"
    out.write_bytes(json.dumps(stp, ensure_ascii=False, indent=1).encode("utf-8"))
    print(out, len(stp), "단계,", max(len(s[1]) for s in stp if s[0] == "cmd"), "자 가장 긴 명령")
