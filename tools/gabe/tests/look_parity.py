"""Look.java 와 kit.py 부호기가 같은지 본다: 무작위 LookState x 월드 틱으로 벡터를 만들고 자바로 같은 값을 계산해 견준다.

python -X utf8 tools/gabe/tests/look_parity.py [--jar build/pack/Gave.jar]
- 표: build/gabe/pack_layer/assets/gave/gabe_looks.json(make_gabe 결과) + 모든 기능을 덮는 합성 모델 하나(test/all)
- 자바: tools/gabe/tests/LookParity.java 를 jar(Look 이 든 플러그인) + runtime/libraries 로 컴파일해 돌림
- 셰이더 쪽 해독(kit.decode)이 부호의 뜻을 되돌리는지도 같이 본다. 성공하면 종료 0
"""
import argparse
import json
import os
import random
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools"))
from gabe import kit  # noqa: E402

JDK = Path.home() / ".jdks/temurin-25/bin"
EXE = ".exe" if os.name == "nt" else ""


def synthetic():
    """모든 칸 종류: 거리 셋, 얼굴/살, 조건 여러 개, 자세(주기 여럿, N 1..8)"""
    slots = []
    for dist in (0, 1, 2):
        for face in (0, 1):
            for show in ([], ["open"], ["!open"], ["mut>=1"], ["mut<2"], ["mut==2"], ["on:cut_l"], ["!on:cut_l", "mut>=2"]):
                slots.append({"posed": 0, "dist": dist, "face": face, "show": show, "k": 0, "n": 1, "period": 0})
    for n in (1, 3, 4, 6, 8):
        for period in (4, 7, 10, 20, 33, 120, 200):
            for k in range(n):
                slots.append({"posed": 1, "dist": random.choice((0, 0, 1, 2)), "face": 0, "show": random.choice(([], ["on:x"], ["!open"])),
                              "k": k, "n": n, "period": period})
    # 상태 셋: 주기가 다르고(상태별 주기), 쓰는 칸이 다름(안 쓰는 칸은 기본값). "lod" 는 uses 가 없는 상태(모두 씀)
    n = len(slots)
    uses = {"": sorted(random.sample(range(n), n * 2 // 3)), "fast": sorted(random.sample(range(n), n // 2)),
            "lod": list(range(n - 7, n))}
    return {"v": 2, "sp": "TEST", "scale": 1.0, "states": ["", "fast", "lod", "none"], "default": "", "parts": ["cut_l", "x"],
            "periods": {"": 20, "fast": 10, "lod": 33}, "uses": uses, "slots": slots}


def rand_look(rng):
    return kit.LookState(palette=rng.randrange(32), open=rng.random() < 0.5, mut=rng.randrange(4), freeze=rng.random() < 0.15,
                         phase=rng.randint(-500, 5000), rate=rng.choice((1.0, 1.25, 1.5, 2.0, 0.5, 0.75, 3.3, 0.1, 1e-3, 0.0, -1.0)),
                         on=rng.sample(["cut_l", "cut_r", "x"], rng.randrange(3)),
                         hurt_until=rng.choice((0, 0, 0, rng.randint(0, 10 ** 7))),
                         pose=rng.choice((0, 0, rng.randrange(9))))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", default=str(ROOT / "build/real/Gave.jar"), help="진짜 트리 jar(python -X utf8 tools/compile_wip.py --real)")
    ap.add_argument("--cases", type=int, default=3000)
    ap.add_argument("--libs", default=str(ROOT / "runtime/libraries"), help="서버 라이브러리 폴더(사본 트리에서 돌릴 때)")
    a = ap.parse_args()
    random.seed(7)
    rng = random.Random(11)
    looks_path = ROOT / "build/gabe/pack_layer/assets/gave/gabe_looks.json"
    looks = json.loads(looks_path.read_text(encoding="utf-8")) if looks_path.is_file() else {"version": 1, "models": {}}
    looks["models"]["test/all"] = synthetic()
    cases = []
    models = list(looks["models"])
    # 해독 시험: 부호를 셰이더 뜻으로 되돌려 봄
    for m in models:
        for sl in looks["models"][m]["slots"]:
            for _ in range(3):
                st = rand_look(rng)
                wt = rng.randint(0, 10 ** 7)
                c = kit.encode_slot(sl, st, wt)
                d = kit.decode(c)
                assert d is not None and (c >> 20) == 14 and c != 0xFFFFFF, (m, sl, c)
                assert d["posed"] == bool(sl["posed"]) and d["dist"] == sl["dist"]
                if sl["posed"]:
                    assert d["k"] == sl["k"] and d["n"] == sl["n"]
                    assert (d["period"] == kit.STOPPED) == bool(st.freeze)
                    if st.freeze:
                        # 멈춤: 셰이더가 보이는 자세(위상 x n // 16)가 멈춤 자세 번호(래그돌 사체의 누운 자세)와 같아야 함
                        assert d["phase"] * sl["n"] // 16 == max(0, min(st.pose, sl["n"] - 1)), (m, sl, st.pose, d)
                else:
                    assert d["palette"] == ((st.palette & 31) if sl["face"] else 0)
                    if wt < st.hurt_until:
                        assert d["hurt"] == min(st.hurt_until, wt + kit.HURT_MAX) % 480
                    else:
                        assert d["hurt"] == kit.HURT_NONE
                # 좀비런 총 신호와 겹치지 않음(R 위 4비트 11..13, 15)
                assert (c >> 20) not in (11, 12, 13, 15)
    for i in range(a.cases):
        m = models[i % len(models)]
        st = rand_look(rng)
        wt = rng.choice((0, rng.randint(0, 10 ** 7), st.hurt_until - rng.randint(0, 12)))
        entry = looks["models"][m]
        slots = entry["slots"]
        state = rng.choice(list(entry.get("states", [""])) + ["nosuch", ""])
        cases.append({"model": m, "look": st.to_json(), "worldTime": wt, "full": kit.encode(slots, st, wt),
                      "trimmed": kit.colors(slots, st, wt), "state": state, "fullState": kit.encode_state(entry, state, st, wt),
                      "trimmedState": kit.colors_state(entry, state, st, wt)})
    tmp = Path(tempfile.mkdtemp(prefix="lookparity-"))
    vec = tmp / "vectors.json"
    vec.write_text(json.dumps({"looks": looks, "cases": cases}), encoding="utf-8")
    libs = [a.jar] + [str(p) for p in sorted(Path(a.libs).rglob("*.jar"))]
    cp = os.pathsep.join(libs)
    src = ROOT / "tools/gabe/tests/LookParity.java"
    r = subprocess.run([str(JDK / ("javac" + EXE)), "-encoding", "UTF-8", "-nowarn", "-cp", cp, "-d", str(tmp), str(src)], capture_output=True,
                       text=True, encoding="utf-8", errors="replace")
    if r.returncode:
        print(r.stdout, r.stderr)
        sys.exit("자바 시험 컴파일 실패")
    r = subprocess.run([str(JDK / ("java" + EXE)), "-Dstdout.encoding=UTF-8", "-cp", str(tmp) + os.pathsep + cp, "LookParity", str(vec)],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    print(r.stdout.strip(), r.stderr.strip()[-2000:])
    print(f"모델 {len(models)}개({', '.join(models)}), 사례 {len(cases)}, 해독 시험 통과")
    import shutil
    shutil.rmtree(tmp, ignore_errors=True)
    sys.exit(r.returncode)


if __name__ == "__main__":
    main()
