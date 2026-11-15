"""바닐라 26.3 클라 jar 찾기(읽기만). 팩 생성기(make_pack_src, make_hud, gabe/sp/g40, props/check_pack)와 tools/fetch_ref.py 가 쓴다.

찾는 차례: 환경 변수 GV_CLIENT_JAR → 공식 런처 자리(%APPDATA%/.minecraft/versions/26.3/26.3.jar, 만든 사람 PC 는 여기) →
fabric-loom 캐시(~/.gradle/caches/fabric-loom/26.3/minecraft-client.jar: 공식 런처로 26.3 을 켠 적 없는 PC, 같은 파일).
어디에도 없으면 공식 런처 자리를 돌려준다(부르는 쪽이 예전과 같은 '없음' 오류를 낸다). .minecraft 폴더에는 아무것도 쓰지 않는다.
"""
import os
from pathlib import Path

OFFICIAL = Path(os.environ.get("APPDATA", "")) / ".minecraft/versions/26.3/26.3.jar"
LOOM = Path.home() / ".gradle/caches/fabric-loom/26.3/minecraft-client.jar"


def client_jar():
    for p in (os.environ.get("GV_CLIENT_JAR"), OFFICIAL, LOOM):
        if p and Path(p).is_file():
            return Path(p)
    return OFFICIAL
