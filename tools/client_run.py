"""격리 26.3 시험 클라로 게이브 시험 서버(26200)에 붙어 대본대로 움직이고 화면을 찍는다. 소리는 꺼진다(master 0).
좀비런 tools/client_run.py 를 옮겨 온 것(처음은 이터널리턴).

python tools/client_run.py tools/scenarios/<이름>.json
대본: [["cmd", "gave 상태"], ["wait", 2], ["shot", "a"], ...] — "{USER}" 는 시험 클라 이름.
  cmd: RCON 명령, wait: 초, button: 마우스 버튼 누름(true)/뗌(false), click: 눌렀다 뗌, shot: 창 캡처,
  tab: Tab 목록 누름(true)/뗌(false). 키는 안 먹어서 대본에 tab 이 있으면 Tab 목록 키를 오른쪽 단추에 묶는다
환경 변수: GV_GUI=<배율>(기본 0 = 자동), GV_SIZE=<가로>x<세로>(기본 1280x720), GV_BACKEND=vulkan|opengl, GV_SUBTITLES=1(자막 켜기),
  GV_SLOT=N(test_server.sh 슬롯), GV_RENDER=<청크>. 사진은 build/shots/<대본>[_<꼬리표>]/<이름>.png,
  꼬리표는 GV_TAG(없으면 gui<배율>_<가로>). GV_JOB=<작업 개체 이름>(tools/ingame.py 가 줌): 이 감싸개가 그 작업에 든 것을
  확인한 뒤에 자바 클라를 띄워, ingame.py 가 어떻게 죽든 클라가 함께 꺼지게.
클라는 팩이 붙은 뒤 5초 동안 살아 있어야 붙은 것으로 친다(그 사이 0xC0000005 등으로 꺼지면 다시 띄움, 모두 합쳐 3번까지).
26.3 클라는 창 포커스가 없으면 키가 안 먹는다 → 움직임과 시선은 명령으로.
클라 실행과 캡처는 minecraft-shader 스킬 runtime_test.py, 마우스 버튼은 scpsl/tools/winpost.py 를 쓴다.
"""
import json
import os
import shutil
import socket
import struct
import subprocess
import sys
import time
import uuid
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path.home() / ".claude/skills/minecraft-shader/scripts"))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scpsl" / "tools"))
import runtime_test as rt  # noqa: E402
import winpost  # noqa: E402

sys.stdout.reconfigure(encoding="utf-8")
ROOT = Path(__file__).resolve().parent.parent
USER = os.environ.get("GV_USER", "Ripley")  # 두 대 띄울 때 GV_USER=Bishop(시험 이름은 실제 작품 속 이름)
SLOT = int(os.environ.get("GV_SLOT", "0") or 0)
ADDR = f"127.0.0.1:{26200 + 10 * SLOT}"
SERVER_LOG = ROOT / "build" / ("server.log" if SLOT == 0 else f"server-{SLOT}.log")
GUI = os.environ.get("GV_GUI", "0")
WIDTH, HEIGHT = (int(v) for v in os.environ.get("GV_SIZE", "1280x720").split("x"))
# 팩이 붙은 뒤 이만큼(초) 살아 있어야 붙은 것으로(리소스를 다시 읽은 직후 0xC0000005 로 꺼지는 일이 있었음)
STABLE_SECONDS = 5


def wait_for_job():
    """ingame.py 가 이 감싸개를 작업 개체(이름 GV_JOB, KILL_ON_JOB_CLOSE)에 넣을 때까지 기다린다(최대 10초).
    작업에 든 뒤에 띄운 자식(자바 클라)만 작업에 함께 들어 ingame.py 가 죽을 때 같이 꺼진다. GV_JOB 이 없으면(혼자 돌릴 때) 바로 돌아감"""
    name = os.environ.get("GV_JOB")
    if not name or os.name != "nt":
        return
    import ctypes
    from ctypes import wintypes
    k = ctypes.WinDLL("kernel32", use_last_error=True)
    k.OpenJobObjectW.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.LPCWSTR]
    k.OpenJobObjectW.restype = wintypes.HANDLE
    k.IsProcessInJob.argtypes = [wintypes.HANDLE, wintypes.HANDLE, ctypes.POINTER(wintypes.BOOL)]
    k.IsProcessInJob.restype = wintypes.BOOL
    k.GetCurrentProcess.restype = wintypes.HANDLE
    k.CloseHandle.argtypes = [wintypes.HANDLE]
    job = k.OpenJobObjectW(0x0004, False, name)  # JOB_OBJECT_QUERY, 물려주지 않음
    if not job:
        print(f"작업 개체 {name} 를 못 엶(오류 {ctypes.get_last_error()}): 그대로 띄움", flush=True)
        return
    try:
        inside = wintypes.BOOL()
        t0 = time.time()
        while time.time() - t0 < 10:
            if k.IsProcessInJob(k.GetCurrentProcess(), job, ctypes.byref(inside)) and inside:
                return
            time.sleep(0.05)
        print("10초 안에 작업 개체에 안 들어감: 그대로 띄움(끝날 때 ingame.py 가 프로세스 나무와 gameDir 로 끔)", flush=True)
    finally:
        # 이 핸들을 쥐고 있으면 ingame.py 가 죽어도 작업이 닫히지 않아 함께 꺼지지 않으므로 바로 닫는다
        k.CloseHandle(job)


def exit_text(code) -> str:
    """종료 코드(음수 아닌 32비트로 보면 0xC0000005 같은 Windows 예외 코드)"""
    return f"{code}(0x{code & 0xFFFFFFFF:08X})" if isinstance(code, int) and (code < 0 or code > 255) else str(code)


def rcon(command):
    def pkt(i, k, s):
        b = struct.pack("<ii", i, k) + s.encode("utf-8") + b"\0\0"
        return struct.pack("<i", len(b)) + b

    def read(c):
        n = struct.unpack("<i", c.recv(4))[0]
        data = b""
        while len(data) < n:
            data += c.recv(n - len(data))
        return data[8:-2].decode("utf-8", "replace")

    with socket.create_connection(("127.0.0.1", 26201 + 10 * SLOT), timeout=10) as c:
        c.sendall(pkt(1, 3, "gave-dev"))
        read(c)
        c.sendall(pkt(2, 2, command))
        return read(c)


def servers_dat():
    def s_(t):
        b = t.encode("utf-8")
        return struct.pack(">H", len(b)) + b
    entry = (bytes([8]) + s_("ip") + s_(ADDR) + bytes([8]) + s_("name") + s_("gave")
             + bytes([1]) + s_("acceptTextures") + bytes([1]) + bytes([0]))
    return bytes([10]) + s_("") + bytes([9]) + s_("servers") + bytes([10]) + struct.pack(">i", 1) + entry + bytes([0])


def main():
    wait_for_job()
    script = Path(sys.argv[1])
    steps = json.loads(script.read_text(encoding="utf-8").replace("{USER}", USER))
    mc = rt.default_mc_dir()
    version = "26.3"
    work = Path.home() / ".cache" / (("gave-client" if SLOT == 0 else f"gave-client-{SLOT}") + ("" if USER == "Ripley" else "-" + USER))
    if work.exists():
        shutil.rmtree(work)
    work.mkdir(parents=True)
    with zipfile.ZipFile(mc / "versions" / version / f"{version}.jar") as zf:
        data_version = json.loads(zf.read("version.json")).get("world_version")
    rt.write_options(mc / "options.txt", work / "options.txt", None, os.environ.get("GV_BACKEND", "vulkan"), data_version)
    opts = (work / "options.txt").read_text(encoding="utf-8")
    assert "soundCategory_master:0.0" in opts, "시험 클라는 소리가 꺼져 있어야 한다"
    lines = [ln for ln in opts.splitlines()
             if not ln.startswith(("enableVsync:", "maxFps:", "inactivityFpsLimit:", "renderDistance:", "guiScale:", "showSubtitles:",
                                   "key_key.playerlist:", "key_key.quickActions:", "key_key.attack:", "chatVisibility:"))]
    # 공격(휘두름)은 창 메시지 마우스 클릭이 게임에 안 닿아 시험 클라에서는 R 키로(대본 ["key", "R"])
    lines.append("key_key.attack:key.keyboard.r")
    # 자막(오른쪽 아래)은 미니맵을 덮는다. 사용자 클라 설정을 따라오지 않게 기본은 끄고, 볼 때만 GV_SUBTITLES=1
    subtitles = "true" if os.environ.get("GV_SUBTITLES") == "1" else "false"
    uses_tab = any(step[0] == "tab" for step in steps)
    lines.append("key_key.playerlist:" + ("key.mouse.right" if uses_tab else "key.keyboard.tab"))
    # 빠른 행동 키(스킬 창, datapack/eternalmc_ui)는 시험 클라에서 V(대본 ["key", "V"])
    lines.append("key_key.quickActions:key.keyboard.v")
    (work / "options.txt").write_text("\n".join(lines + ["enableVsync:false", "maxFps:120", 'inactivityFpsLimit:"minimized"',
                                                         f"renderDistance:{os.environ.get('GV_RENDER', '8')}", f"guiScale:{GUI}",
                                                         f"showSubtitles:{subtitles}"]
                                               + (["chatVisibility:2"] if os.environ.get("GV_HIDECHAT") == "1" else [])) + "\n",
                                      encoding="utf-8")
    (work / "servers.dat").write_bytes(servers_dat())
    cp, meta = rt.classpath(mc, version)
    natives = work / "natives"
    natives.mkdir()
    cmd = [str(rt.find_java()), "-Xmx" + os.environ.get("GV_CLIENT_XMX", "3G"), "--enable-native-access=ALL-UNNAMED",
           f"-Djava.library.path={natives}", f"-Dorg.lwjgl.system.SharedLibraryExtractPath={natives / 'lwjgl'}",
           f"-Djna.tmpdir={natives / 'jna'}", f"-Dio.netty.native.workdir={natives / 'netty'}",
           "-cp", cp, meta["mainClass"],
           "--username", USER, "--version", version, "--gameDir", str(work),
           "--assetsDir", str(mc / "assets"), "--assetIndex", meta["assetIndex"]["id"],
           "--uuid", uuid.uuid4().hex, "--accessToken", "0", "--userType", "legacy",
           "--versionType", "release", "--width", str(WIDTH), "--height", str(HEIGHT),
           "--quickPlayPath", str(work / "quickplay.json"), "--quickPlayMultiplayer", ADDR]
    tag = os.environ.get("GV_TAG") or f"gui{GUI}_{WIDTH}" + ("" if USER == "Ripley" else "_" + USER)
    out = ROOT / "build" / "shots" / f"{script.stem}_{tag}"
    # 폴더가 다른 프로그램(탐색기 등)에 잡혀 있을 수 있어 폴더째 지우지 않고 안의 파일만 지운다
    out.mkdir(parents=True, exist_ok=True)
    for f in out.iterdir():
        if f.is_file():
            f.unlink()
    proc = None
    recorder = None

    def launch():
        """클라를 띄워 접속하고 팩이 붙은 뒤 STABLE_SECONDS 동안 살아 있을 때까지. 격리 클라가 리소스를 다시 읽다 멈추거나 꺼지는 일
        (팩이 붙고 몇 초 안에 0xC0000005 로 꺼지는 것 포함)이 있어 모두 합쳐 3번까지 → proc 또는 None.
        살아 있는지 보는 동안 청크와 엔티티도 받는다(예전의 접속 뒤 4초 기다림 대신)"""
        for attempt in (1, 2, 3):
            start = SERVER_LOG.stat().st_size
            p = subprocess.Popen(cmd, cwd=work, stdout=open(work / "stdout.txt", "wb"), stderr=subprocess.STDOUT)
            t0, got = time.time(), None
            while time.time() - t0 < 240 and p.poll() is None:
                text = SERVER_LOG.read_bytes()[start:].decode("utf-8", "replace")
                got = next((st for st in ("SUCCESSFULLY_LOADED", "FAILED_DOWNLOAD", "FAILED_RELOAD", "DECLINED", "INVALID_URL", "DISCARDED")
                            if f"팩 상태 {USER} {st}" in text), None)
                if got:
                    break
                time.sleep(1)
            loaded = got == "SUCCESSFULLY_LOADED" and p.poll() is None
            if loaded:
                t1 = time.time()
                while time.time() - t1 < STABLE_SECONDS and p.poll() is None:
                    time.sleep(0.25)
            alive = p.poll() is None
            state = "살아 있음" if alive else f"꺼짐 {exit_text(p.returncode)}" + (f"(팩이 붙고 {STABLE_SECONDS}초 안에)" if loaded else "")
            print(f"{attempt}회차: 팩 {got}, 클라 {state}, {round(time.time() - t0)}초", flush=True)
            if loaded and alive:
                return p
            if p.poll() is None:
                p.kill()
                p.wait(20)
            log = work / "logs" / "latest.log"
            if log.exists():
                shutil.copy(log, out / f"client_fail_{attempt}.log")
            if (work / "stdout.txt").exists():
                shutil.copy(work / "stdout.txt", out / f"client_fail_{attempt}_stdout.txt")
            # 자바가 0xC0000005 같은 것으로 죽으면 gameDir 에 hs_err_pid*.log 를 남긴다
            for crash in work.glob("hs_err_pid*.log"):
                shutil.move(crash, out / f"client_fail_{attempt}_{crash.name}")
            time.sleep(3)
        return None

    try:
        proc = launch()
        if proc is None:
            print("클라를 붙이지 못함")
            return
        rcon(f"op {USER}")
        recorder = None
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        for step in steps:
            if proc.poll() is not None:
                print("클라가 끝남, 코드", proc.returncode)
                break
            kind = step[0]
            if kind == "cmd":
                print(">", step[1], "->", rcon(step[1]).strip()[:int(os.environ.get("GV_RCON_MAX", "200"))])
            elif kind == "wait":
                time.sleep(step[1])
            elif kind == "button":
                print("button", step[1:], winpost.button(proc.pid, step[1], step[2]))
            elif kind == "key":
                print("key", step[1], winpost.key(proc.pid, step[1]))
            elif kind == "keyshot":
                # 키를 누른 채(Tab 등) 찍기: ["keyshot", "TAB", 이름]
                import threading
                th = threading.Thread(target=winpost.key, args=(proc.pid, step[1], 2.5))
                th.start()
                time.sleep(1.2)
                print(step[2], rt.window_capture(proc.pid, out / f"{step[2]}.png"))
                th.join()
            elif kind == "moveto":
                # 창 안 비율 자리로 마우스만 옮김(툴팁 보기)
                print("moveto", step[1:], winpost.move(proc.pid, step[1], step[2]))
            elif kind == "clickat":
                # 창 안 비율 자리(fx, fy)를 왼쪽 클릭(다이얼로그 단추)
                print("clickat", step[1:], winpost.click(proc.pid, step[1], step[2]))
            elif kind == "tab":
                print("tab", step[1], winpost.button(proc.pid, "right", step[1]))
            elif kind == "click":
                winpost.button(proc.pid, step[1], True)
                time.sleep(0.08)
                print("click", step[1], winpost.button(proc.pid, step[1], False))
            elif kind == "shot":
                print(step[1], rt.window_capture(proc.pid, out / f"{step[1]}.png"))
            elif kind == "rec":
                # 정상 속도 영상: ["rec", "이름"(, fps)] ... ["recstop"]. 긴 한 판은 fps 2 정도로
                from record_window import Recorder
                recorder = Recorder(proc.pid, out / f"{step[1]}.mp4", fps=step[2] if len(step) > 2 else 30)
                recorder.start()
                print("녹화 시작", step[1])
            elif kind == "recstop":
                print("녹화 끝", recorder.stop() if recorder else "녹화 안 함")
                recorder = None
            elif kind == "until":
                # ["until", "글", 최대 초]: /gave 상태 에 그 글이 나올 때까지
                t_end = time.time() + step[2]
                got = ""
                while time.time() < t_end and proc.poll() is None:
                    got = rcon("gave 상태")
                    want = step[1] if isinstance(step[1], list) else [step[1]]
                    if all(w in got for w in want):
                        break
                    time.sleep(0.25)
                print("until", step[1], "->", got.strip()[:120])
        log = work / "logs" / "latest.log"
        if log.exists():
            shutil.copy(log, out / "client_latest.log")
    finally:
        if recorder is not None:
            print("녹화 끝(정리)", recorder.stop())
        if proc is not None and proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(20)
            except subprocess.TimeoutExpired:
                proc.kill()


if __name__ == "__main__":
    main()
