"""RCON 명령 몇 개 보내기(시험 서버). python -X utf8 tools/rcon_cmd.py "명령" "명령" ... ("wait 3" 이면 3초 쉼)"""
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from client_run import rcon  # noqa: E402

for c in sys.argv[1:]:
    if c.startswith("wait "):
        time.sleep(float(c.split()[1]))
        continue
    print(">", c, "->", rcon(c).strip()[:300])
