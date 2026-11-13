"""여러 에이전트가 같은 소스 폴더를 쓸 때의 따로 컴파일(공유 폴더 규칙: 중간본이 남의 빌드를 깨지 않게).

python -X utf8 tools/compile_wip.py --name <이름> [--promote]
python -X utf8 tools/compile_wip.py --real            (진짜 소스 트리만, 출력 build/real/Gave.jar)

- 고칠 파일은 진짜 자리 대신 build/wip/<이름>/ 아래 같은 상대 경로에 둔다
  (예: build/wip/gun/plugin/src/main/java/kr/gave/gun/Guns.java).
- 진짜 plugin/src/main 을 임시로 복사하고 wip 파일을 덮어 컴파일, 성공하면 build/<이름>/Gave.jar(자원 + tools/props.json 포함).
- --promote: 컴파일이 통과하면 wip 파일을 진짜 자리로 옮긴다(같은 폴더 임시 파일 → 바꿔치기) 그리고 wip 에서 지운다.
  promote 직전에 진짜 트리 + wip 로 한 번 더 컴파일하므로, 그사이 남이 올린 변경과도 맞는지 확인된다.
런타임 폴더(runtime/plugins)는 건드리지 않는다. 시험 서버에는 tools/ingame.py 가 이 jar 를 넣는다.
"""
import argparse
import msvcrt
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JDK = Path.home() / ".jdks/temurin-25/bin"
EXE = ".exe" if os.name == "nt" else ""


JAVAC_SEATS = 3  # 2026-11-12: 작업자 스물 넘게 동시에 컴파일해 메모리가 몰리고 페이지 파일이 커져 C: 가 찼다. 동시 javac 셋까지
JAVAC_XMX = "-J-Xmx1536m"


class JavacSeat:
    """build/locks/javac-<n>.lock 셋 가운데 빈 것을 쥔다(운영체제 잠금이라 죽으면 저절로 풀림)"""

    def __enter__(self):
        locks = ROOT / "build/locks"
        locks.mkdir(parents=True, exist_ok=True)
        t0 = time.monotonic()
        last = 0.0
        while True:
            for i in range(JAVAC_SEATS):
                fd = os.open(locks / f"javac-{i + 1}.lock", os.O_RDWR | os.O_CREAT)
                try:
                    os.lseek(fd, 0, os.SEEK_SET)
                    msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)
                    self.fd = fd
                    return self
                except OSError:
                    os.close(fd)
            if time.monotonic() - last > 60:
                print(f"javac 자리 기다림(동시 {JAVAC_SEATS}개까지)", flush=True)
                last = time.monotonic()
            if time.monotonic() - t0 > 60 * 60:
                sys.exit("60분 넘게 javac 자리를 못 얻음")
            time.sleep(2)

    def __exit__(self, *exc):
        try:
            os.lseek(self.fd, 0, os.SEEK_SET)
            msvcrt.locking(self.fd, msvcrt.LK_UNLCK, 1)
        except OSError:
            pass
        os.close(self.fd)
        return False


def compile_tree(src_main: Path, out_dir: Path, jar: Path) -> bool:
    sources = sorted((src_main / "java").rglob("*.java"))
    libs = sorted((ROOT / "runtime/libraries").rglob("*.jar")) + sorted((ROOT / "runtime/versions").rglob("*.jar"))
    classes = out_dir / "classes"
    classes.mkdir(parents=True)
    lst = out_dir / "sources.txt"
    lst.write_text("\n".join('"' + p.as_posix() + '"' for p in sources), encoding="utf-8")
    with JavacSeat():
        r = subprocess.run([str(JDK / ("javac" + EXE)), JAVAC_XMX, "-J-Dfile.encoding=UTF-8", "-J-Dsun.stdout.encoding=UTF-8", "-J-Dsun.stderr.encoding=UTF-8",
                            "--release", "25", "-encoding", "UTF-8", "-Xlint:-options", "-proc:none", "-nowarn",
                            "-cp", os.pathsep.join(str(p) for p in libs), "-d", str(classes), "@" + str(lst)])
    if r.returncode:
        return False
    res = src_main / "resources"
    if res.is_dir():
        shutil.copytree(res, classes, dirs_exist_ok=True)
    props = ROOT / "tools/props.json"
    if props.is_file():
        shutil.copy2(props, classes / "props.json")
    jar.parent.mkdir(parents=True, exist_ok=True)
    tmp = jar.with_suffix(".tmp.jar")
    subprocess.run([str(JDK / ("jar" + EXE)), "--create", "--file", str(tmp), "-C", str(classes), "."], check=True)
    os.replace(tmp, jar)
    print(f"컴파일 통과: {len(sources)}개 소스 → {jar}")
    return True


def build(name: str | None, wip: Path | None) -> bool:
    tag = name or "real"
    work = ROOT / "build" / tag
    work.mkdir(parents=True, exist_ok=True)
    for old in work.glob("compile-*"):
        shutil.rmtree(old, ignore_errors=True)
    tmp = Path(tempfile.mkdtemp(prefix="compile-", dir=work))
    src = tmp / "main"
    shutil.copytree(ROOT / "plugin/src/main", src)
    n = 0
    if wip is not None and (wip / "plugin/src/main").is_dir():
        for f in (wip / "plugin/src/main").rglob("*"):
            if f.is_file():
                dst = src / f.relative_to(wip / "plugin/src/main")
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(f, dst)
                n += 1
    print(f"wip 덮은 파일 {n}개")
    ok = compile_tree(src, tmp / "out", work / "Gave.jar")
    shutil.rmtree(tmp, ignore_errors=True)
    return ok


def promote(wip: Path):
    moved = []
    for f in sorted(wip.rglob("*")):
        if not f.is_file():
            continue
        rel = f.relative_to(wip)
        dst = ROOT / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        tmp = dst.with_name(dst.name + f".promote{os.getpid()}")
        shutil.copy2(f, tmp)
        os.replace(tmp, dst)
        f.unlink()
        moved.append(rel.as_posix())
    for d in sorted((p for p in wip.rglob("*") if p.is_dir()), key=lambda p: -len(p.parts)):
        try:
            d.rmdir()
        except OSError:
            pass
    print("진짜 자리로 옮김:", *moved, sep="\n  ")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name")
    ap.add_argument("--real", action="store_true")
    ap.add_argument("--promote", action="store_true")
    a = ap.parse_args()
    t0 = time.monotonic()
    if a.real:
        ok = build(None, None)
    else:
        if not a.name:
            sys.exit("--name 이나 --real")
        wip = ROOT / "build/wip" / a.name
        ok = build(a.name, wip)
        if ok and a.promote:
            promote(wip)
    print(f"{time.monotonic() - t0:.1f}초")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
