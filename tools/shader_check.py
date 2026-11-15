"""셰이더 컴파일 검사: 만든 사람 PC 의 ~/.claude/skills/minecraft-shader/scripts/check_shaders.py 가 없는 PC 의 대체 길.

python -X utf8 tools/shader_check.py [팩 폴더(기본 pack_src)]

- core/item, core/text 의 vsh, fsh 를 게임(26.3 GlslCompiler)과 같은 shaderc 옵션, define 조합 56가지(tools/shader_check/jobs.txt)로
  SPIR-V 컴파일해 본다. include 는 게임처럼 팩 폴더 먼저, 없으면 바닐라 원본(~/.cache/mc-shader-vanilla/26.3: tools/fetch_ref.py shaders).
  하네스 tools/shader_check/Harness.java 와 일감은 2026-11-14 셰이더 고침 때 만든 것(일부러 깨뜨린 셰이더를 오류로 잡는 것까지 확인).
- 쓰는 것(새로 받지 않음): gradle 캐시의 LWJGL(~/.gradle/caches/modules-2/files-2.1/org.lwjgl/{lwjgl,lwjgl-shaderc}/<판>/ 의 본체와
  natives-windows. 두 묶음에 다 있는 가장 높은 판), 자바 ~/.jdks/temurin-25.
- 종료 코드: 0 통과, 1 컴파일 오류. 위의 것이 없어 검사를 못 하면 '건너뜀' 을 찍고 0(검사기가 없던 때와 같게 빌드를 막지 않음).
  tools/build_all.py 의 shaders 단계와 tools/make_hud.py 가 만든 사람 검사기가 없을 때 이것을 부른다.
- 못 보는 것: 일감에 없는 core 셰이더(팩이 새로 덮으면 jobs.txt 에 줄을 더할 것. 아래에서 알림), OpenGL 뒷단의 SPIRV-Cross 변환 뒤
  드라이버 컴파일, 화면. 셰이더를 고쳤으면 게임 안 latest.log 도 같이 본다.
"""
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HERE = ROOT / "tools/shader_check"
WORK = ROOT / "build/shader_check"
JDK = Path.home() / ".jdks/temurin-25/bin"
LWJGL = Path.home() / ".gradle/caches/modules-2/files-2.1/org.lwjgl"
VANILLA = Path.home() / ".cache/mc-shader-vanilla/26.3"
EXE = ".exe" if os.name == "nt" else ""
ARTS = ("lwjgl", "lwjgl-shaderc")


def jars():
    """두 묶음에 다 있는 가장 높은 판의 본체와 natives-windows jar 넷. 없으면 None"""
    if not all((LWJGL / a).is_dir() for a in ARTS):
        return None
    common = set.intersection(*({p.name for p in (LWJGL / a).iterdir() if p.is_dir()} for a in ARTS))
    for v in sorted(common, key=lambda s: [int(x) if x.isdigit() else 0 for x in s.split(".")], reverse=True):
        out = [hit[0] for a in ARTS for tail in ("", "-natives-windows") if (hit := sorted((LWJGL / a / v).glob(f"*/{a}-{v}{tail}.jar")))]
        if len(out) == 4:
            return out
    return None


def main():
    pack = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT / "pack_src"
    cp = jars()
    why = ("gradle 캐시에 LWJGL shaderc 가 없음" if not cp else f"자바 없음({JDK})" if not (JDK / ("javac" + EXE)).is_file()
           else f"바닐라 셰이더 원본 없음({VANILLA}: python -I -X utf8 tools/fetch_ref.py shaders)"
           if not (VANILLA / "assets/minecraft/shaders/core").is_dir() else None)
    if why:
        print(f"셰이더 검사 건너뜀: {why}. 셰이더를 고쳤으면 게임 안 latest.log 로 확인할 것")
        return
    classes = WORK / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    cps = os.pathsep.join(str(p) for p in cp)
    subprocess.run([str(JDK / ("javac" + EXE)), "-encoding", "UTF-8", "-nowarn", "-cp", cps, "-d", str(classes), str(HERE / "Harness.java")],
                   check=True)
    jobs = (HERE / "jobs.txt").read_text(encoding="utf-8")
    core = pack / "assets/minecraft/shaders/core"
    extra = sorted(p.name for p in core.glob("*.?sh") if f"core/{p.name} " not in jobs) if core.is_dir() else []
    r = subprocess.run([str(JDK / ("java" + EXE)), f"-Dorg.lwjgl.system.SharedLibraryExtractPath={WORK / 'natives'}",
                        "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "--enable-native-access=ALL-UNNAMED",
                        "--sun-misc-unsafe-memory-access=allow",  # LWJGL 의 Unsafe 경고 줄을 끔(JDK 23 부터 있는 옵션)
                        "-cp", os.pathsep.join([str(classes), cps]), "Harness", f"{pack};{VANILLA}", str(HERE / "jobs.txt")],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    print("\n".join(ln for ln in (r.stdout + r.stderr).strip().splitlines() if not ln.startswith("OK ")))  # 통과 줄은 빼고 실패와 합계만
    if extra:
        print("알림: 일감(tools/shader_check/jobs.txt)에 없어 검사하지 않은 core 셰이더: " + ", ".join(extra))
    sys.exit(1 if r.returncode else 0)


if __name__ == "__main__":
    main()
