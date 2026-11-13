"""ogg 파일의 채널 수, 길이, 표본율, 최대 음량을 찍는다(ffmpeg 로 읽기만).

python -I -X utf8 sound_info.py <ogg 또는 폴더> [...]
"""
import re
import subprocess
import sys
from pathlib import Path

import imageio_ffmpeg

FF = imageio_ffmpeg.get_ffmpeg_exe()


def info(p):
    r = subprocess.run([FF, "-hide_banner", "-i", str(p), "-af", "volumedetect", "-f", "null", "-"],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    t = r.stderr
    m = re.search(r"Audio: (\w+), (\d+) Hz, (\w+)", t)
    d = re.search(r"Duration: (\d+):(\d+):([\d.]+)", t)
    mx = re.search(r"max_volume: ([-\d.]+) dB", t)
    mean = re.search(r"mean_volume: ([-\d.]+) dB", t)
    dur = int(d.group(1)) * 3600 + int(d.group(2)) * 60 + float(d.group(3)) if d else -1
    return {"codec": m.group(1) if m else "?", "rate": int(m.group(2)) if m else 0, "layout": m.group(3) if m else "?",
            "dur": round(dur, 3), "max_db": float(mx.group(1)) if mx else None, "mean_db": float(mean.group(1)) if mean else None}


def lead(p, rel_db=-40.0, rate=22050):
    """앞 무음 길이(초): 최대 음량보다 rel_db 아래 문턱을 처음 넘는 곳"""
    import numpy as np
    r = subprocess.run([FF, "-v", "error", "-i", str(p), "-ac", "1", "-ar", str(rate), "-f", "f32le", "-"], capture_output=True)
    x = np.abs(np.frombuffer(r.stdout, np.float32))
    if len(x) == 0:
        return 0.0
    idx = np.nonzero(x > x.max() * 10 ** (rel_db / 20))[0]
    return round(float(idx[0]) / rate, 3) if len(idx) else 0.0


if __name__ == "__main__":
    for a in sys.argv[1:]:
        ps = sorted(Path(a).rglob("*.ogg")) if Path(a).is_dir() else [Path(a)]
        for p in ps:
            i = info(p)
            print(f"{i['layout']:<7} {i['rate']:>6} {i['dur']:>6.2f}s max {i['max_db']} mean {i['mean_db']}  {p.stat().st_size // 1024:>4}KB  {p}")
