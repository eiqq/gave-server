"""시험 클라에 F3(디버그 화면, fps)을 눌러 주는 도우미(대본 pack_fps.json 과 함께 백그라운드로). 슬롯 서버에 tag=f3 표식이
생기면 이 슬롯 시험 클라(gameDir gave-client-<슬롯>, Ripley) 창에 F3 을 한 번 보내고 끝. 창 메시지로 보내므로 포커스가 없어도 된다.

GV_SLOT=9 python -X utf8 tools/gabe/tests/f3_helper.py
"""
import ctypes
import os
import sys
import time
from pathlib import Path

import psutil

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools"))
os.environ.setdefault("GV_SLOT", "9")
from client_run import rcon  # noqa: E402

sys.path.insert(0, "C:/Users/PC/Desktop/클로드/scpsl/tools")
import winpost  # noqa: E402

SLOT = os.environ["GV_SLOT"]
VK_F3, SC_F3 = 0x72, 0x3D


def client_pid():
    want = f"gave-client-{SLOT}"
    for p in psutil.process_iter(["name", "cmdline"]):
        try:
            cl = p.info["cmdline"] or []
        except (psutil.Error, OSError):
            continue
        if "--quickPlayMultiplayer" in cl and "--gameDir" in cl:
            gd = cl[cl.index("--gameDir") + 1].replace("\\", "/").rstrip("/")
            if gd.endswith("/" + want):
                return p.pid
    return None


t_end = time.time() + 900
while time.time() < t_end:
    try:
        r = rcon("execute if entity @e[type=marker,tag=f3]")
    except OSError:
        time.sleep(1)
        continue
    if "passed" in r.lower() or "count" in r.lower():
        pid = client_pid()
        h = winpost.hwnd_of(pid) if pid else None
        if not h:
            print("시험 클라 창 없음", pid, flush=True)
            break
        winpost.user32.PostMessageW(h, winpost.WM_KEYDOWN, VK_F3, 1 | (SC_F3 << 16))
        time.sleep(0.08)
        ok = winpost.user32.PostMessageW(h, winpost.WM_KEYUP, VK_F3, 1 | (SC_F3 << 16) | (1 << 30) | (1 << 31))
        print("F3 보냄", pid, ok, flush=True)
        break
    time.sleep(0.2)
