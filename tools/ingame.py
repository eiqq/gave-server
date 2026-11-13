"""게임 안 시험을 한 번에 하나씩(여러 에이전트가 같이 쓸 때). 잠금 → 시험 슬롯 9 서버 켜기 → 시험 클라 대본 → 서버 끄기 → 잠금 풂.

python -X utf8 tools/ingame.py --name <이름> --scenario tools/scenarios/a.json [--scenario b.json ...]
    [--second-user Bishop --second-scenario tools/scenarios/idle.json] [--backend opengl|vulkan] [--fresh-world]

- jar: build/<이름>/Gave.jar (tools/compile_wip.py 가 만든 것, --real 이면 build/real). 없으면 멈춤.
  잠금을 얻은 뒤 진짜 HudSpec.java 가 jar 보다 새로우면(줄 서는 동안 HUD promote) jar 를 다시 컴파일한다(실패하면 크게 경고).
- 팩: 이 실행마다 build/<이름>/Gave-pack.zip 으로 새로 묶음(pack_src + 소품 층, 소리까지 하나로).
  GV_PACK_SPLIT=1 이면 둘로 나눠 모습 팩 build/<이름>/Gave-pack.zip 과 소리 팩 build/<이름>/Gave-sound.zip 을 만들고,
  서버(-Dgave.packDir=build/<이름>)는 같은 폴더에서 둘 다 찾아 한 요청으로 보낸다(tools/build_pack.py, kr.gave.PackServer).
- 길 둘(2026-11-12): 슬롯 9(포트 26290, RCON 26291, 팩 8299)와 슬롯 8(26280, 26281, 8298). 잠금은 길마다 하나(9 는 예전
  build/locks/ingame.lock 그대로, 8 은 ingame-8.lock), 먼저 비는 길을 쓴다. 슬롯 8 폴더(runtime-8)가 없으면 runtime-9 를 월드 빼고 복사하고
  안개 데이터팩을 깐 새 월드로 시작한다. 시험 클라 자리는 build/locks/seat-1.lock, seat-2.lock 두 자리를 판 내내 쥔다(PC 전체 2대 한도).
- 서버: 잡은 길의 슬롯, 힙 2G. --fresh-world 면 슬롯 월드를 지우고 새로 만든다(광장 생성기를 고쳤을 때, 팩을 먼저 만든 뒤에).
  잠금을 얻은 뒤 build/server-process-9.json 이 있거나 슬롯 포트가 막혀 있으면(앞 실행의 서버가 남음) 먼저 test_server.sh stop 으로
  정리한다(그 스크립트가 띄운 서버만, pid 와 생성 시각이 맞을 때만 끔). 그래도 막혀 있으면 월드를 건드리지 않고 멈춘다.
  --fresh-world 는 지우기 직전에 한 번 더 보고, 지운 뒤에도 월드 폴더가 남으면 멈춘다. 서버 켜기가 실패해도 대본을 돌리지 않는다.
- 시험 클라: PC 전체 2대 한도(다른 세션 것 포함)를 지키려고, 실행 중인 시험 클라 수를 세서 자리가 날 때까지 기다린다. 소리는 늘 꺼짐.
  감싸개(client_run.py, 두 번째 클라와 대본 클라 모두)는 Windows 작업 개체(JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE)에 넣는다:
  이 파이썬이 어떻게 죽든(작업 핸들이 닫히면) 감싸개와 그 자식 자바 클라가 함께 꺼진다. 서버는 이 작업에 넣지 않는다.
  끝나면 두 번째 클라를 프로세스 나무째 끄고 작업을 닫고, 이 슬롯 시험 클라(gameDir gave-client-9...)가 남았으면 마저 끈다.
- 사진: build/shots/<대본>_<이름>/, 클라 기록: build/<이름>/client_<대본>.log, 서버 기록: build/<이름>/server_<번호>.log
- 잠금: build/locks/ingame.lock 파일의 운영체제 잠금(msvcrt). 쥔 프로세스가 죽으면 저절로 풀려서 '주인 없는 잠금 치우기'가 없다
  (예전 폴더 잠금은 두 실행이 같이 치우고 둘 다 얻는 경쟁이 있었음). 누가 쥐었는지는 build/locks/ingame.owner.json. 최대 90분 기다림.
"""
import argparse
import ctypes
import json
import msvcrt
import os
import shutil
import socket
import subprocess
import sys
import time
from ctypes import wintypes
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOCKS = ROOT / "build/locks"
# 시험 길(슬롯). 9 의 잠금 파일은 예전 이름 그대로라 옛 코드로 줄 선 실행과도 서로 막는다
LANES = (9, 8)
SEATS = 2  # PC 전체 시험 클라 한도(다른 세션 것 포함, test_clients 로도 셈)
_lock_fd = None
_seat_fds = []
_name_fd = None  # 같은 --name 두 실행이 두 길에서 함께 돌지 않게(jar, 팩, 기록이 build/<이름>/ 하나라서)


def lane_files(slot: int) -> tuple[Path, Path]:
    tag = "" if slot == 9 else f"-{slot}"
    return LOCKS / f"ingame{tag}.lock", LOCKS / f"ingame{tag}.owner.json"


def use_slot(slot: int):
    """잠금을 얻은 길의 슬롯 값을 모듈 전역에 넣는다(아래 함수들이 읽음)"""
    global SLOT, PORTS, SERVER_LOG, SERVER_STATE, RUNTIME, WORLD, STOP_HINT, CLIENT_DIR, LOCK_FILE, OWNER
    SLOT = slot
    # 게임, RCON, 팩(test_server.sh 와 같은 셈)
    PORTS = (26200 + 10 * SLOT, 26201 + 10 * SLOT, 8290 + SLOT)
    SERVER_LOG = ROOT / "build" / f"server-{SLOT}.log"
    SERVER_STATE = ROOT / "build" / f"server-process-{SLOT}.json"
    RUNTIME = ROOT / f"runtime-{SLOT}"
    WORLD = RUNTIME / "gave_plaza"
    STOP_HINT = f"GV_SLOT={SLOT} bash tools/test_server.sh stop"
    CLIENT_DIR = f"gave-client-{SLOT}"
    LOCK_FILE, OWNER = lane_files(slot)


use_slot(LANES[0])

JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000
JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9
PROCESS_SET_QUOTA, PROCESS_TERMINATE = 0x0100, 0x0001


class _IoCounters(ctypes.Structure):
    _fields_ = [(n, ctypes.c_ulonglong) for n in ("ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
                                                   "ReadTransferCount", "WriteTransferCount", "OtherTransferCount")]


class _BasicLimit(ctypes.Structure):
    _fields_ = [("PerProcessUserTimeLimit", ctypes.c_int64), ("PerJobUserTimeLimit", ctypes.c_int64), ("LimitFlags", wintypes.DWORD),
                ("MinimumWorkingSetSize", ctypes.c_size_t), ("MaximumWorkingSetSize", ctypes.c_size_t),
                ("ActiveProcessLimit", wintypes.DWORD), ("Affinity", ctypes.c_size_t), ("PriorityClass", wintypes.DWORD),
                ("SchedulingClass", wintypes.DWORD)]


class _ExtendedLimit(ctypes.Structure):
    _fields_ = [("BasicLimitInformation", _BasicLimit), ("IoInfo", _IoCounters), ("ProcessMemoryLimit", ctypes.c_size_t),
                ("JobMemoryLimit", ctypes.c_size_t), ("PeakProcessMemoryUsed", ctypes.c_size_t), ("PeakJobMemoryUsed", ctypes.c_size_t)]


class ClientJob:
    """시험 클라 감싸개를 넣는 작업 개체(KILL_ON_JOB_CLOSE). 핸들은 이 프로세스만 쥐므로 이 파이썬이 죽으면 운영체제가 닫고,
    그때 작업 안의 감싸개와 자바 클라가 모두 꺼진다. 감싸개는 환경 변수 GV_JOB(작업 이름)으로 자기가 작업에 든 것을 확인한 뒤에
    자바를 띄운다(작업에 들기 전에 띄운 자식은 작업 밖에 남으므로)."""

    def __init__(self):
        k = ctypes.WinDLL("kernel32", use_last_error=True)
        k.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
        k.CreateJobObjectW.restype = wintypes.HANDLE
        k.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
        k.SetInformationJobObject.restype = wintypes.BOOL
        k.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        k.OpenProcess.restype = wintypes.HANDLE
        k.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
        k.AssignProcessToJobObject.restype = wintypes.BOOL
        k.CloseHandle.argtypes = [wintypes.HANDLE]
        k.CloseHandle.restype = wintypes.BOOL
        self.k = k
        self.name = f"gave-ingame-{os.getpid()}-{time.monotonic_ns()}"
        self.handle = k.CreateJobObjectW(None, self.name)
        if not self.handle:
            raise ctypes.WinError(ctypes.get_last_error())
        info = _ExtendedLimit()
        info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        if not k.SetInformationJobObject(self.handle, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, ctypes.byref(info), ctypes.sizeof(info)):
            err = ctypes.get_last_error()
            k.CloseHandle(self.handle)
            raise ctypes.WinError(err)

    def popen(self, args, env: dict, **kw) -> subprocess.Popen:
        """감싸개를 띄우고 바로 작업에 넣는다(감싸개는 GV_JOB 으로 들어간 것을 확인하고 자바를 띄움)"""
        proc = subprocess.Popen(args, env=dict(env, GV_JOB=self.name), **kw)
        h = self.k.OpenProcess(PROCESS_SET_QUOTA | PROCESS_TERMINATE, False, proc.pid)
        ok = bool(h) and bool(self.k.AssignProcessToJobObject(self.handle, h))
        err = ctypes.get_last_error()
        if h:
            self.k.CloseHandle(h)
        if not ok:
            print(f"감싸개 {proc.pid} 를 작업 개체에 못 넣음(오류 {err}): 끝날 때 프로세스 나무와 gameDir 로 끔", flush=True)
        return proc

    def close(self):
        """작업 핸들을 닫음: 작업 안에 남은 감싸개와 자바 클라가 함께 꺼진다"""
        if self.handle:
            self.k.CloseHandle(self.handle)
            self.handle = None


def ps(cmd: str) -> str:
    r = subprocess.run(["powershell", "-NoProfile", "-Command", cmd], capture_output=True, text=True, encoding="utf-8", errors="replace")
    return r.stdout.strip()


def test_clients() -> int:
    out = ps("(Get-CimInstance Win32_Process -Filter \"Name='java.exe' or Name='javaw.exe'\" | "
             "Where-Object { $_.CommandLine -like '*--quickPlayMultiplayer*' } | Measure-Object).Count")
    try:
        return int(out)
    except ValueError:
        return 0


def kill_slot_clients() -> int:
    """이 슬롯 시험 클라(gameDir 에 gave-client-9 가 든 quickPlay 자바)가 남았으면 끈다. 잠금을 쥔 동안만 부른다"""
    out = ps("$p = Get-CimInstance Win32_Process -Filter \"Name='java.exe' or Name='javaw.exe'\" | "
             f"Where-Object {{ $_.CommandLine -like '*--quickPlayMultiplayer*' -and $_.CommandLine -like '*{CLIENT_DIR}*' }}; "
             "$p | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }; ($p | Measure-Object).Count")
    try:
        return int(out)
    except ValueError:
        return 0


def kill_tree(proc: subprocess.Popen):
    """파이썬 감싸개(client_run.py)와 그 자식 자바 클라를 함께 끈다(terminate 는 감싸개만 죽여 자바가 고아로 남았음)"""
    if proc.poll() is None:
        subprocess.run(["taskkill", "/T", "/F", "/PID", str(proc.pid)], capture_output=True)
        try:
            proc.wait(30)
        except subprocess.TimeoutExpired:
            proc.kill()


def free_gb() -> float:
    out = ps("(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory")
    try:
        return int(out) / 1024 / 1024
    except ValueError:
        return 0.0


def busy_ports() -> list[int]:
    out = []
    for port in PORTS:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            try:
                probe.bind(("0.0.0.0", port))
            except OSError:
                out.append(port)
    return out


def slot_left() -> str | None:
    """앞 실행의 슬롯 서버가 남은 흔적(서버 기록 파일, 막힌 포트). 없으면 None"""
    why = []
    if SERVER_STATE.exists():
        why.append(f"build/{SERVER_STATE.name} 있음")
    busy = busy_ports()
    if busy:
        why.append("포트 " + "/".join(map(str, busy)) + " 쓰는 중")
    return ", ".join(why) or None


def try_lock(fd: int) -> bool:
    try:
        os.lseek(fd, 0, os.SEEK_SET)
        msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)
        return True
    except OSError:
        return False


def unlock(fd: int):
    try:
        os.lseek(fd, 0, os.SEEK_SET)
        msvcrt.locking(fd, msvcrt.LK_UNLCK, 1)
    except OSError:
        pass
    os.close(fd)


def acquire(name: str):
    """먼저 비는 길(LANES)의 잠금을 얻고 그 슬롯으로 바꾼다"""
    global _lock_fd, _name_fd
    LOCKS.mkdir(parents=True, exist_ok=True)
    t0 = time.monotonic()
    last = 0
    nfd = os.open(LOCKS / f"name-{name}.lock", os.O_RDWR | os.O_CREAT)
    while not try_lock(nfd):
        if time.monotonic() - last > 60:
            print(f"같은 이름({name})의 다른 시험이 돌거나 줄 서 있음: 그 뒤에", flush=True)
            last = time.monotonic()
        if time.monotonic() - t0 > 360 * 60:
            sys.exit("6시간 넘게 이름 잠금을 못 얻음")
        time.sleep(5)
    _name_fd = nfd
    fds = {s: os.open(lane_files(s)[0], os.O_RDWR | os.O_CREAT) for s in LANES}
    last = 0
    got = None
    while got is None:
        for s, fd in fds.items():
            if try_lock(fd):
                got = s
                break
        if got is not None:
            break
        if time.monotonic() - last > 60:
            owners = []
            for s in LANES:
                try:
                    owners.append(f"슬롯 {s} " + lane_files(s)[1].read_text(encoding="utf-8"))
                except OSError:
                    owners.append(f"슬롯 {s} ?")
            print("게임 시험 차례 기다림: 지금 " + ", ".join(owners), flush=True)
            last = time.monotonic()
        if time.monotonic() - t0 > 360 * 60:  # 2026-11-12: 작업자가 많아 줄이 길다(90분에서 6시간으로)
            sys.exit("6시간 넘게 잠금을 못 얻음")
        time.sleep(5)
    for s, fd in fds.items():
        if s != got:
            os.close(fd)
    _lock_fd = fds[got]
    use_slot(got)
    OWNER.write_text(json.dumps({"pid": os.getpid(), "name": name, "since": time.strftime("%H:%M:%S"), "slot": got},
                                ensure_ascii=False), encoding="utf-8")
    print(f"잠금 얻음 ({name}, 슬롯 {got}, 포트 {PORTS[0]})", flush=True)
    prepare_lane()


def prepare_lane():
    """슬롯 9 말고 다른 길의 runtime 폴더가 없으면 runtime-9 를 월드, 기록, 로그 빼고 복사하고 안개 데이터팩을 깐 새 월드를 준비한다.
    (test_server.sh 는 없는 슬롯 폴더를 runtime 에서 복사하는데, 그 월드는 안개 데이터팩이 없는 옛 것이다.) 잠금을 쥔 동안만 부른다"""
    if SLOT == 9 or RUNTIME.exists():
        return
    base = ROOT / "runtime-9"
    print(f"슬롯 {SLOT} 폴더를 만든다: {base.name} 에서 월드 빼고 복사", flush=True)
    shutil.copytree(base, RUNTIME, ignore=shutil.ignore_patterns("logs", "session.lock", "stats.json", "matches", "gave_plaza",
                                                                  "records.jsonl", "crash-reports", "hs_err_pid*"))
    dp = ROOT / "data/datapack/gave_env"
    if dp.is_dir():
        shutil.copytree(dp, WORLD / "datapacks" / "gave_env")
        print("안개 데이터팩 gave_env 를 새 월드에 깔아 둠", flush=True)


def take_seats(need: int):
    """시험 클라 자리 need 개를 한꺼번에 쥔다(못 쥐면 다 놓고 다시). 판이 끝날 때까지 쥐고 release 가 놓는다"""
    t0 = time.monotonic()
    last = 0
    paths = [LOCKS / f"seat-{i + 1}.lock" for i in range(SEATS)]
    while True:
        got = []
        for path in paths:
            fd = os.open(path, os.O_RDWR | os.O_CREAT)
            if try_lock(fd):
                got.append(fd)
                if len(got) == need:
                    break
            else:
                os.close(fd)
        if len(got) == need:
            _seat_fds.extend(got)
            return
        for fd in got:
            unlock(fd)
        if time.monotonic() - last > 60:
            print(f"시험 클라 자리(seat) 기다림: 필요 {need}", flush=True)
            last = time.monotonic()
        if time.monotonic() - t0 > 90 * 60:
            sys.exit("90분 넘게 시험 클라 자리(seat)를 못 얻음")
        time.sleep(5)


def release():
    global _lock_fd, _name_fd
    if _name_fd is not None:
        unlock(_name_fd)
        _name_fd = None
    if _lock_fd is None:
        return
    try:
        if json.loads(OWNER.read_text(encoding="utf-8")).get("pid") == os.getpid():
            OWNER.unlink()
    except (OSError, ValueError):
        pass
    while _seat_fds:
        unlock(_seat_fds.pop())
    unlock(_lock_fd)
    _lock_fd = None
    print("잠금 풂", flush=True)


def wait_clients(need: int):
    t0 = time.monotonic()
    last = 0
    while True:
        n = test_clients()
        if n + need <= 2:
            return
        if time.monotonic() - last > 60:
            print(f"시험 클라 자리 기다림: 지금 {n}대 실행 중, 필요 {need}", flush=True)
            last = time.monotonic()
        if time.monotonic() - t0 > 30 * 60:
            sys.exit("30분 넘게 시험 클라 자리가 안 남")
        time.sleep(10)


def env(name: str, **extra) -> dict:
    e = dict(os.environ)
    e.update({"GV_SLOT": str(SLOT), "GV_XMX": "2G", "GV_CLIENT_XMX": "2G", "GV_TAG": name, "GV_RCON_MAX": "600"})
    e.update({k: str(v) for k, v in extra.items()})
    return e


def server(action: str, name: str, **extra) -> tuple[int, str]:
    out = ROOT / "build" / name / f"server_{action}.out"
    with out.open("wb") as f:
        r = subprocess.run(["bash", "tools/test_server.sh", action], cwd=ROOT, stdout=f, stderr=subprocess.STDOUT, env=env(name, **extra))
    return r.returncode, out.read_text(encoding="utf-8", errors="replace")


def last_line(text: str) -> str:
    lines = text.strip().splitlines()
    return lines[-1] if lines else "(출력 없음)"


def clear_slot(name: str):
    """앞 실행이 남긴 슬롯 서버를 정리. test_server.sh stop 은 그 스크립트가 띄운 서버만(pid 와 생성 시각이 맞을 때) 끄고,
    남의 프로세스가 포트를 쥐었거나 기록이 다른 폴더 것이면 아무것도 끄지 않는다. 그래도 남으면 멈춘다"""
    why = slot_left()
    if why is None:
        return
    print(f"앞 실행의 슬롯 {SLOT} 서버 흔적({why}): test_server.sh stop 으로 정리", flush=True)
    rc, text = server("stop", name)
    print(f"stop 종료 코드 {rc}: {last_line(text)}", flush=True)
    why = slot_left()
    if why is not None:
        sys.exit(f"슬롯 {SLOT} 서버를 정리하지 못함({why}): 월드를 건드리지 않고 멈춤. 무엇이 쥐었는지 보고 {STOP_HINT}")


HUDSPEC = ROOT / "plugin/src/main/java/kr/gave/hud/HudSpec.java"


def hud_sync(name: str, jar: Path):
    """줄을 서는 동안 HUD 가 promote 되면(make_hud + compile_wip --name hud --promote) jar 의 HudSpec 이 팩과 어긋나
    덮개와 글귀가 깨진다. 잠금을 얻은 뒤 진짜 HudSpec.java 가 jar 보다 새로우면 jar 를 다시 컴파일한다(진짜 트리 + 내 wip).
    컴파일이 실패하면 크게 경고하고 옛 jar 로 간다."""
    if not HUDSPEC.is_file() or HUDSPEC.stat().st_mtime <= jar.stat().st_mtime:
        return
    print(f"HudSpec.java 가 jar 보다 새로움({time.strftime('%H:%M', time.localtime(HUDSPEC.stat().st_mtime))}): jar 다시 컴파일", flush=True)
    args = ["--real"] if name == "real" else ["--name", name]
    r = subprocess.run([sys.executable, "-X", "utf8", "tools/compile_wip.py", *args], cwd=ROOT,
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode == 0:
        print("jar 다시 컴파일 통과(HudSpec 이 팩과 맞음)", flush=True)
    else:
        tail = " | ".join((r.stdout + r.stderr).strip().splitlines()[-8:])
        print("!!! 경고: jar 다시 컴파일 실패. 이 실행의 jar 는 HudSpec 이 팩과 어긋나 HUD 덮개와 글귀가 깨져 보일 수 있다 !!! " + tail, flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", required=True)
    ap.add_argument("--scenario", action="append", required=True)
    ap.add_argument("--second-user")
    ap.add_argument("--second-scenario", default="tools/scenarios/idle.json")
    ap.add_argument("--backend", default="opengl")
    ap.add_argument("--fresh-world", action="store_true")
    ap.add_argument("--keep-pack", action="store_true",
                    help="build/<이름>/Gave-pack.zip(나눴으면 Gave-sound.zip 도)이 있으면 다시 묶지 않음(소품 층을 고치는 중일 때)")
    a = ap.parse_args()
    work = ROOT / "build" / a.name
    jar = work / "Gave.jar"
    if not jar.exists():
        sys.exit(f"{jar} 없음: 먼저 python -X utf8 tools/compile_wip.py --name {a.name}")
    acquire(a.name)
    hud_sync(a.name, jar)
    second = None
    job = None
    started = False
    try:
        clear_slot(a.name)
        job = ClientJob()
        left = kill_slot_clients()
        if left:
            print(f"앞 실행이 남긴 슬롯 시험 클라 {left}대를 끔", flush=True)
        pack = work / "Gave-pack.zip"
        # 소리 팩(GV_PACK_SPLIT=1 일 때 build_pack 이 모습 팩 옆에 만듦). 서버는 이 폴더에 있으면 함께 보낸다
        sound = work / "Gave-sound.zip"
        if a.keep_pack and pack.exists():
            print(f"팩 그대로 씀: {pack} ({time.strftime('%H:%M', time.localtime(pack.stat().st_mtime))})"
                  + (f" + {sound.name}" if sound.exists() else ""), flush=True)
            if HUDSPEC.is_file() and HUDSPEC.stat().st_mtime > pack.stat().st_mtime:
                print("!!! 경고: --keep-pack 팩이 HudSpec.java 보다 낡음: HUD 덮개와 글귀가 깨져 보일 수 있다(--keep-pack 을 빼고 다시) !!!", flush=True)
        else:
            # GV_PACK_SPLIT 은 env() 가 그대로 넘긴다(1 이면 둘로 나눔, 아니면 하나로 묶고 옆의 옛 소리 팩은 build_pack 이 지움)
            r = subprocess.run([sys.executable, "-X", "utf8", "tools/build_pack.py"], cwd=ROOT, env=env(a.name, GV_PACK_OUT=pack),
                               capture_output=True, text=True, encoding="utf-8", errors="replace")
            print(r.stdout.strip() or r.stderr.strip(), flush=True)
            if r.returncode != 0:
                sys.exit("팩 묶기 실패")
        if os.environ.get("GV_PACK_SPLIT") == "1" and not sound.exists():
            print("!!! 경고: GV_PACK_SPLIT=1 인데 소리 팩이 없어 팩 하나로 보낸다(--keep-pack 을 빼고 다시) !!!", flush=True)
        print("보낼 팩: " + (f"{pack.name} + {sound.name}(둘로 나눔)" if sound.exists() else f"{pack.name} 하나"), flush=True)
        if a.fresh_world:
            why = slot_left()
            if why is not None:
                sys.exit(f"슬롯 월드를 지우기 전에 다시 보니 서버 흔적이 있음({why}): 월드를 건드리지 않고 멈춤. {STOP_HINT}")
            shutil.rmtree(WORLD, ignore_errors=True)
            if WORLD.exists():
                sys.exit(f"슬롯 월드 못 지움: {WORLD} 가 남음(파일을 잡은 프로세스가 있음). 서버가 남았으면 {STOP_HINT} 뒤 다시")
            print("슬롯 월드 지움(새로 생성)", flush=True)
            # 안개 데이터팩은 월드를 만들 때 읽혀야 첫 판부터 먹는다(플러그인 Fog.install 은 다음 켤 때부터)
            dp = ROOT / "data/datapack/gave_env"
            if dp.is_dir():
                shutil.copytree(dp, WORLD / "datapacks" / "gave_env")
                print("안개 데이터팩 gave_env 를 새 월드에 깔아 둠", flush=True)
        print(f"남은 메모리 {free_gb():.1f}GB", flush=True)
        rc, text = server("start", a.name, GV_JAR=jar, GV_PACK_DIR=work)
        print(last_line(text), flush=True)
        if rc != 0:
            sys.exit(f"서버 켜기 실패(종료 코드 {rc}): 대본을 돌리지 않음. build/{a.name}/server_start.out. 서버가 남았으면 {STOP_HINT}")
        started = True
        need = 2 if a.second_user else 1
        take_seats(need)
        wait_clients(need)
        if a.second_user:
            start = SERVER_LOG.stat().st_size
            second = job.popen([sys.executable, "-X", "utf8", "tools/client_run.py", a.second_scenario],
                               env(a.name, GV_USER=a.second_user, GV_BACKEND=a.backend), cwd=ROOT,
                               stdout=open(work / "client_second.log", "wb"), stderr=subprocess.STDOUT)
            t0 = time.time()
            # 팩이 둘이면 이 줄은 두 팩이 모두 붙은 뒤 한 번 나온다(팩마다 줄은 '팩 상태 <이름> 모습|소리 <상태>')
            while time.time() - t0 < 240:
                if f"팩 상태 {a.second_user} SUCCESSFULLY_LOADED" in SERVER_LOG.read_bytes()[start:].decode("utf-8", "replace"):
                    break
                if second.poll() is not None:
                    break
                time.sleep(2)
            print(f"두 번째 클라 {a.second_user}: {'접속' if second.poll() is None else '꺼짐'}", flush=True)
            time.sleep(4)
        for sc in a.scenario:
            log = work / f"client_{Path(sc).stem}.log"
            with log.open("wb") as f:
                job.popen([sys.executable, "-X", "utf8", "tools/client_run.py", sc], env(a.name, GV_BACKEND=a.backend), cwd=ROOT,
                          stdout=f, stderr=subprocess.STDOUT).wait()
            text = log.read_text(encoding="utf-8", errors="replace")
            print(f"--- {sc} (사진 build/shots/{Path(sc).stem}_{a.name}/) ---", flush=True)
            print("\n".join(text.splitlines()[-60:]), flush=True)
    finally:
        if second is not None:
            kill_tree(second)
        if job is not None:
            job.close()
        left = kill_slot_clients()
        if left:
            print(f"남은 슬롯 시험 클라 {left}대를 끔", flush=True)
        if started:
            try:
                print(last_line(server("stop", a.name)[1]), flush=True)
            except Exception as e:  # noqa: BLE001
                print("서버 끄기 실패:", e, flush=True)
            if SERVER_LOG.exists():
                n = len(list(work.glob("server_*.log")))
                shutil.copy2(SERVER_LOG, work / f"server_{n}.log")
                print(f"서버 기록: build/{a.name}/server_{n}.log", flush=True)
        release()


if __name__ == "__main__":
    main()
