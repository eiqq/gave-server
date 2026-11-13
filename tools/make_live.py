"""실서버 꾸러미를 만든다(사용자 표준: 25565, 팩 8088, 바탕화면 bat, 채팅 시큐어 끔, 트랜스퍼 켬).

    python -X utf8 tools/make_live.py            live/ 를 만들거나 고침(월드는 건드리지 않음), bat 를 바탕화면에
    python -X utf8 tools/make_live.py --fresh    live/gave_plaza(월드)를 지우고 다음 켤 때 새로 짓게 함

- live/ 는 runtime/ 의 서버 파일(paper.jar, libraries, versions, cache, 설정)을 복사한 것. 월드는 플러그인 생성기가 처음 켤 때 짓는다.
- 안개 데이터팩(data/datapack/gave_env)은 월드가 생기기 전에 깔려 있어야 첫 판부터 먹는다.
- 팩은 live/ 로 복사하지 않는다. 플러그인(PackServer)이 build/ 의 모습 팩 Gave-pack.zip 과, 있으면 소리 팩 Gave-sound.zip 을 읽어
  한 요청으로 보낸다(tools/build_pack.py 가 기본으로 둘로 나눔). 팩마다 깃허브 릴리스(tools/upload_pack.py)가 있으면 그 주소,
  없는 팩은 이 PC 의 8088 포트로 보낸다. 두 팩이 어긋나 있으면(나눈 모습 팩만 있음, 하나로 묶은 팩 옆에 옛 소리 팩) 멈춘다.
- 25565 가 켜져 있으면(사용자가 서버를 켜 둠) 아무것도 바꾸지 않고 멈춘다.
"""
import os
import re
import shutil
import socket
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RUNTIME = ROOT / 'runtime'
LIVE = ROOT / 'live'
WORLD = 'gave_plaza'
REPO = 'tinymita2-pixel/gave-pack'
BAT_NAME = '게이브_25565.bat'
# 운영자는 비워 둔다(공개 저장소라 개인 계정을 적지 않음). 서버를 켠 뒤 서버 창에서 op <닉네임>
OPS = '[]\n'

PROPS = {
    'server-port': '25565', 'query.port': '25565', 'enable-rcon': 'false', 'rcon.password': '', 'rcon.port': '25575',
    'online-mode': 'true', 'enforce-secure-profile': 'false', 'accepts-transfers': 'true',
    'white-list': 'false', 'enforce-whitelist': 'false', 'hide-online-players': 'true',
    'level-name': WORLD, 'motd': '게이브', 'max-players': '40', 'gamemode': 'adventure', 'difficulty': 'normal',
    'view-distance': '10', 'simulation-distance': '8', 'spawn-protection': '0', 'allow-flight': 'true',
    'allow-nether': 'false', 'generate-structures': 'false', 'pvp': 'false', 'pause-when-empty-seconds': '-1',
    'require-resource-pack': 'false', 'resource-pack': '', 'resource-pack-sha1': '', 'broadcast-rcon-to-ops': 'false',
}

BAT = r'''@echo off
title 게이브 25565
cd /d "%USERPROFILE%\Desktop\클로드\gave\live" || (echo 서버 폴더를 찾지 못했습니다. & pause & exit /b 1)
set "JAVA=%USERPROFILE%\.jdks\temurin-25\bin\java.exe"
if not exist "%JAVA%" set "JAVA=java"
netstat -ano | findstr /r /c:":25565 .*LISTENING" >nul && (echo 25565 포트를 이미 다른 프로그램이 쓰고 있습니다. 다른 서버를 먼저 끄세요. & pause & exit /b 1)
if exist "..\build\Gave.jar" copy /y "..\build\Gave.jar" "plugins\Gave.jar" >nul
echo 게이브 서버를 25565 포트로 켭니다. 끌 때는 이 창에 stop 을 입력하세요.
echo 리소스팩 두 개(모습, 소리)는 깃허브에서 받고, 거기 없는 팩은 이 PC 의 8088 포트로 보냅니다.
echo 다른 PC 에서 들어오려면 공유기에서 25565 포트를, 팩을 이 PC 에서 보낼 때는 8088 포트도 여세요.
echo 처음 켤 때는 광장을 짓느라 조금 걸립니다. 판 시작은 게임 안에서 /gave 시작
echo.
"%JAVA%" -Xms2G -Xmx4G -Dfile.encoding=UTF-8 -Dspark.backgroundProfiler=false -Dgave.packPort=8088 -Dgave.packRepo=__REPO__ -jar paper.jar --nogui
echo.
echo 서버가 꺼졌습니다.
pause
'''.replace('__REPO__', REPO)


def listening(port):
    try:
        with socket.create_connection(('127.0.0.1', port), timeout=0.5):
            return True
    except OSError:
        return False


def write_props():
    src = (RUNTIME / 'server.properties').read_text(encoding='utf-8').splitlines()
    rows, seen = [], set()
    for line in src:
        key = line.split('=', 1)[0]
        if '=' in line and not line.startswith('#') and key in PROPS:
            rows.append(f'{key}={PROPS[key]}')
            seen.add(key)
        else:
            rows.append(line)
    rows += [f'{k}={v}' for k, v in PROPS.items() if k not in seen]
    # 한글 motd 는 properties 규칙대로 유니코드 이스케이프로 적는다
    out = []
    for r in rows:
        out.append(''.join(c if ord(c) < 128 else '\\u%04x' % ord(c) for c in r))
    (LIVE / 'server.properties').write_text('\n'.join(out) + '\n', encoding='ascii')


def pack_note(look, sound):
    """실서버가 보낼 팩 설명. 모습 팩과 소리 팩이 어긋나면(소리가 어디에도 없음, 소리가 두 팩에 다 있음) 멈춘다(upload_pack.py 와 같은 검사)"""
    with zipfile.ZipFile(look) as z:
        look_sounds = [n for n in z.namelist() if re.fullmatch(r'assets/[^/]+/sounds\.json', n)]
    if sound.is_file() and look_sounds:
        raise SystemExit(f'{look.name} 에도 sounds.json 이 있는데 옆에 옛 {sound.name} 가 남았다. python -X utf8 tools/build_pack.py 로 다시 묶을 것')
    if not sound.is_file() and not look_sounds:
        raise SystemExit(f'{sound} 가 없는데 {look.name} 에도 sounds.json 이 없다(소리 없는 팩). python -X utf8 tools/build_pack.py 로 다시 묶을 것')
    if sound.is_file():
        return f'모습 {look.name} {look.stat().st_size / 1e6:.1f}MB + 소리 {sound.name} {sound.stat().st_size / 1e6:.1f}MB'
    return f'{look.name} {look.stat().st_size / 1e6:.1f}MB 하나(소리까지 든 옛 꼴)'


def main():
    fresh = '--fresh' in sys.argv[1:]
    if listening(25565):
        raise SystemExit('25565 가 켜져 있다(사용자가 서버를 켜 둔 것일 수 있음). 아무것도 바꾸지 않는다.')
    jar = ROOT / 'build/Gave.jar'
    pack = ROOT / 'build/Gave-pack.zip'
    for need in (jar, pack, RUNTIME / 'paper.jar'):
        if not need.exists():
            raise SystemExit(f'{need} 가 없다. 먼저 팩과 플러그인을 빌드할 것(docs/운영.md)')
    packs = pack_note(pack, ROOT / 'build/Gave-sound.zip')
    LIVE.mkdir(exist_ok=True)
    for name in ('paper.jar', 'eula.txt', 'bukkit.yml', 'spigot.yml', 'commands.yml'):
        shutil.copy2(RUNTIME / name, LIVE / name)
    for name in ('libraries', 'versions', 'cache', 'config'):
        if (RUNTIME / name).is_dir():
            shutil.copytree(RUNTIME / name, LIVE / name, dirs_exist_ok=True)
    write_props()
    if not (LIVE / 'ops.json').exists() or (LIVE / 'ops.json').read_text(encoding='utf-8').strip() in ('', '[]'):
        (LIVE / 'ops.json').write_text(OPS, encoding='utf-8')
    (LIVE / 'plugins').mkdir(exist_ok=True)
    shutil.copy2(jar, LIVE / 'plugins/Gave.jar')
    # 사용자 요청(2026-11-11): 실서버에 KAKC 2.0(영어 자판 한글 자동 변환 + 채팅 신고 차단)도 넣는다
    kakc = ROOT.parent / 'totalwar/kakc/build/KAKC-2.0.0.jar'
    if kakc.is_file():
        shutil.copy2(kakc, LIVE / 'plugins/KAKC-2.0.0.jar')
    cfg = ROOT / 'plugin/src/main/resources/config.yml'
    world = LIVE / WORLD
    if fresh and world.exists():
        shutil.rmtree(world)
        print('월드를 지웠다(다음 켤 때 새로 지음)')
    dp_src = ROOT / 'data/datapack/gave_env'
    dp_dst = world / 'datapacks/gave_env'
    if dp_src.is_dir():
        if dp_dst.exists():
            shutil.rmtree(dp_dst)
        dp_dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(dp_src, dp_dst)
    bat_dir = ROOT / '바탕화면'
    bat_dir.mkdir(exist_ok=True)
    data = BAT.replace('\r\n', '\n').replace('\n', '\r\n').encode('cp949')
    (bat_dir / BAT_NAME).write_bytes(data)
    desktop = Path(os.environ.get('USERPROFILE', str(Path.home()))) / 'Desktop'
    if desktop.is_dir():
        (desktop / BAT_NAME).write_bytes(data)
    size = sum(f.stat().st_size for f in LIVE.rglob('*') if f.is_file()) / 1e6
    print(f'live/ 준비 끝({size:.0f} MB): 포트 25565, 팩 8088, 저장소 {REPO}(릴리스가 없는 팩은 이 PC 에서 보냄)')
    print(f'보낼 팩(build/ 에서 읽음): {packs}')
    print(f'바탕화면 {BAT_NAME} 과 {bat_dir / BAT_NAME} 을 썼다. 설정 기본값은 {cfg.name}(첫 켬 뒤 live/plugins/Gave/config.yml)')


if __name__ == '__main__':
    main()
