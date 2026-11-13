"""팩과 플러그인 전체를 올바른 순서로 한 번에 만든다. 단계마다 실패하면 멈추고 무엇이 실패했는지 알린다.

python -X utf8 tools/build_all.py                 전체
python -X utf8 tools/build_all.py --from hud      그 단계부터(앞 단계 결과는 있는 것을 씀)
python -X utf8 tools/build_all.py --skip props,bgm 빼고(이름은 --list)
python -X utf8 tools/build_all.py --list          단계 이름과 명령

순서: props(소품 층) → pack_src(팩 바탕) → fx(셰이더 연출) → gabe(게이브 몹 층) → director(감독 소리)
→ bgm(배경 음악) → hud(HUD 글꼴, 글귀) → hud_promote(HudSpec.java 를 코드로) → pack(build/Gave-pack.zip)
→ shaders(셰이더 검사) → layer(몹 층 검사) → dex(도감 글) → real(진짜 트리 jar build/real) → parity(키트와 Look 부호 대조) → plugin(build_plugin.sh).
단계마다 출력은 build/build_all/<단계>.out 에 쓰고(서버를 띄우지 않으니 파이프 막힘 걱정은 없지만 길어서 파일로),
실패하면 그 파일 끝 30줄을 보여 준다. 진짜 트리의 Java 를 고치지는 않는다(hud_promote 는 make_hud 가 만든 HudSpec 만 옮김).
"""
import argparse
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "build/build_all"
PY = [sys.executable, "-X", "utf8"]
SHADER_CHECK = Path.home() / ".claude/skills/minecraft-shader/scripts/check_shaders.py"

STEPS = [
    ("props", PY + ["tools/props/build_props.py"]),
    ("pack_src", PY + ["tools/make_pack_src.py"]),
    ("fx", PY + ["tools/make_fx.py"]),
    ("gabe", PY + ["tools/make_gabe.py"]),
    ("director", PY + ["tools/make_director_sounds.py"]),
    ("bgm", PY + ["tools/make_bgm.py"]),
    ("hud", PY + ["tools/make_hud.py"]),
    ("hud_promote", PY + ["tools/compile_wip.py", "--name", "hud", "--promote"]),
    ("pack", PY + ["tools/build_pack.py"]),
    ("shaders", PY + [str(SHADER_CHECK), "pack_src", "--mc", "26.3"]),
    ("layer", PY + ["tools/gabe/tests/check_layer.py"]),
    ("dex", PY + ["tools/gen_dex.py"]),  # 게임 안 도감 글(data/bestiary.json → plugin resources dex/species.json)
    ("real", PY + ["tools/compile_wip.py", "--real"]),
    ("parity", PY + ["tools/gabe/tests/look_parity.py", "--jar", "build/real/Gave.jar"]),
    ("plugin", ["bash", "tools/build_plugin.sh"]),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--from", dest="start")
    ap.add_argument("--skip", default="")
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args()
    names = [n for n, _ in STEPS]
    if a.list:
        for n, cmd in STEPS:
            print(f"{n:12} {' '.join(cmd[3:] if cmd[0] == sys.executable else cmd)}")
        return
    skip = {s for s in a.skip.split(",") if s}
    bad = (skip | ({a.start} if a.start else set())) - set(names)
    if bad:
        sys.exit(f"모르는 단계: {', '.join(sorted(bad))} (있는 것: {', '.join(names)})")
    todo = names[names.index(a.start):] if a.start else names
    OUT.mkdir(parents=True, exist_ok=True)
    t_all = time.monotonic()
    for n, cmd in STEPS:
        if n not in todo or n in skip:
            continue
        log = OUT / f"{n}.out"
        t0 = time.monotonic()
        print(f"[{n}] ...", end="", flush=True)
        with log.open("w", encoding="utf-8") as f:
            r = subprocess.run(cmd, cwd=ROOT, stdout=f, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
        dt = time.monotonic() - t0
        if r.returncode != 0:
            print(f" 실패(종료 코드 {r.returncode}, {dt:.0f}초)", flush=True)
            lines = log.read_text("utf-8", errors="replace").splitlines()
            print("\n".join(lines[-30:]))
            print(f"\n멈춤: 단계 '{n}' 실패. 전체 기록 {log.relative_to(ROOT).as_posix()}."
                  f" 고친 뒤 python -X utf8 tools/build_all.py --from {n}")
            sys.exit(1)
        last = next((l for l in reversed(log.read_text("utf-8", errors="replace").splitlines()) if l.strip()), "")
        print(f" 통과 {dt:.0f}초  {last[:110]}", flush=True)
    print(f"전체 통과 {time.monotonic() - t_all:.0f}초: build/Gave-pack.zip, 플러그인 jar(서버가 꺼져 있으면 runtime/plugins, 켜져 있으면 build/Gave.jar)")


if __name__ == "__main__":
    main()
