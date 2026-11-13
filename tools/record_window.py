"""시험 클라 창을 정상 속도 영상(mp4)으로 찍는다. 창이 가려져 있어도 되게 PrintWindow(PW_RENDERFULLCONTENT)로 읽어
imageio-ffmpeg 에 든 ffmpeg 로 넘긴다. 소리는 없다(시험 클라는 늘 음소거).

client_run.py 대본 ["rec", "이름"] 으로 시작, ["recstop"] 으로 끝. 따로 쓸 때:
python -X utf8 tools/record_window.py <pid> <out.mp4> <초>
"""
import ctypes
import ctypes.wintypes as W
import json
import subprocess
import sys
import threading
import time
from pathlib import Path

user32 = ctypes.windll.user32
gdi32 = ctypes.windll.gdi32
user32.SetProcessDPIAware()


class BITMAPINFOHEADER(ctypes.Structure):
    _fields_ = [("biSize", W.DWORD), ("biWidth", W.LONG), ("biHeight", W.LONG), ("biPlanes", W.WORD), ("biBitCount", W.WORD),
                ("biCompression", W.DWORD), ("biSizeImage", W.DWORD), ("biXPelsPerMeter", W.LONG), ("biYPelsPerMeter", W.LONG),
                ("biClrUsed", W.DWORD), ("biClrImportant", W.DWORD)]


def find_window(pid):
    """그 프로세스의 보이는 창 중 가장 큰 것."""
    best = [None, 0]
    proc = ctypes.WINFUNCTYPE(W.BOOL, W.HWND, W.LPARAM)

    def cb(h, _):
        p = W.DWORD()
        user32.GetWindowThreadProcessId(h, ctypes.byref(p))
        if p.value == pid and user32.IsWindowVisible(h):
            r = W.RECT()
            user32.GetClientRect(h, ctypes.byref(r))
            area = r.right * r.bottom
            if area > best[1]:
                best[0], best[1] = h, area
        return True

    user32.EnumWindows(proc(cb), 0)
    return best[0]


def ffmpeg_exe():
    import imageio_ffmpeg
    return imageio_ffmpeg.get_ffmpeg_exe()


class Recorder:
    def __init__(self, pid, out: Path, fps=30):
        self.pid, self.out, self.fps = pid, Path(out), fps
        self.stop_flag = threading.Event()
        self.thread = None
        self.stats = {}

    def start(self):
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def stop(self):
        self.stop_flag.set()
        if self.thread:
            self.thread.join(60)
        return self.stats

    def _run(self):
        h = find_window(self.pid)
        if not h:
            self.stats = {"error": "창 없음"}
            return
        r = W.RECT()
        user32.GetClientRect(h, ctypes.byref(r))
        w, hgt = r.right & ~1, r.bottom & ~1
        win_dc = user32.GetDC(h)
        mem = gdi32.CreateCompatibleDC(win_dc)
        bmi = BITMAPINFOHEADER(ctypes.sizeof(BITMAPINFOHEADER), w, -hgt, 1, 32, 0, 0, 0, 0, 0, 0)
        bits = ctypes.c_void_p()
        dib = gdi32.CreateDIBSection(win_dc, ctypes.byref(bmi), 0, ctypes.byref(bits), None, 0)
        gdi32.SelectObject(mem, dib)
        self.out.parent.mkdir(parents=True, exist_ok=True)
        ff = subprocess.Popen([ffmpeg_exe(), "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "bgra", "-s", f"{w}x{hgt}",
                               "-r", str(self.fps), "-i", "-", "-c:v", "libx264", "-preset", "veryfast", "-crf", "21",
                               "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(self.out)], stdin=subprocess.PIPE)
        size = w * hgt * 4
        t0 = time.perf_counter()
        start_ms = int(time.time() * 1000)  # 서버 로그 시각과 맞출 벽시계(tools/mux_bgm.py)
        written, grabs, grab_ms, last = 0, 0, [], None
        try:
            while not self.stop_flag.is_set():
                a = time.perf_counter()
                user32.PrintWindow(h, mem, 3)
                frame = ctypes.string_at(bits, size)
                grab_ms.append((time.perf_counter() - a) * 1000)
                grabs += 1
                # 벽시계에 맞춰 쓴다: 늦었으면 같은 장을 되풀이해 영상 길이를 실제 시간과 같게
                due = int((time.perf_counter() - t0) * self.fps) + 1
                while written < due:
                    ff.stdin.write(frame)
                    written += 1
                last = frame
                nxt = t0 + written / self.fps
                sl = nxt - time.perf_counter()
                if sl > 0:
                    time.sleep(sl)
        finally:
            ff.stdin.close()
            ff.wait(120)
            gdi32.DeleteObject(dib)
            gdi32.DeleteDC(mem)
            user32.ReleaseDC(h, win_dc)
        dur = time.perf_counter() - t0
        grab_ms.sort()
        self.stats = {"초": round(dur, 2), "쓴 장": written, "새로 읽은 장": grabs, "실제 읽기 fps": round(grabs / dur, 1) if dur else 0,
                      "읽기 ms 중앙": round(grab_ms[len(grab_ms) // 2], 1) if grab_ms else None,
                      "읽기 ms 95%": round(grab_ms[int(len(grab_ms) * 0.95)], 1) if grab_ms else None, "크기": f"{w}x{hgt}",
                      "시작ms": start_ms}


if __name__ == "__main__":
    rec = Recorder(int(sys.argv[1]), Path(sys.argv[2]))
    rec.start()
    time.sleep(float(sys.argv[3]))
    print(json.dumps(rec.stop(), ensure_ascii=False))
