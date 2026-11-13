#!/usr/bin/env bash
# Git Bash와 Windows Python 표준 라이브러리로 플러그인을 빌드한다(좀비런 tools/build_plugin.sh 를 옮김).
set -euo pipefail
cd "$(dirname "$0")/.."
python -X utf8 - <<'PY'
from pathlib import Path
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time

started = time.monotonic()
root = Path.cwd().resolve()
jdk = Path.home() / '.jdks/temurin-25/bin'
suffix = '.exe' if os.name == 'nt' else ''
sources = sorted((root / 'plugin/src/main/java').rglob('*.java'))
jars = sorted((root / 'runtime/libraries').rglob('*.jar'))
jars += sorted((root / 'runtime/versions').rglob('*.jar'))
if not sources or not jars:
    raise SystemExit('자바 소스 또는 서버 라이브러리가 없습니다.')
build = root / 'build/plugin'
build.mkdir(parents=True, exist_ok=True)
# 매 빌드마다 빈 디렉터리를 써서 삭제 없이 오래된 class 혼입을 막는다.
out = Path(tempfile.mkdtemp(prefix='compile-', dir=build))
classes = out / 'classes'
classes.mkdir()
source_list = out / 'sources.txt'
source_list.write_text('\n'.join('"' + p.relative_to(root).as_posix() + '"' for p in sources), encoding='utf-8')
compiled = subprocess.run([
    str(jdk / ('javac' + suffix)), '-J-Dfile.encoding=UTF-8',
    '-J-Dsun.stdout.encoding=UTF-8', '-J-Dsun.stderr.encoding=UTF-8', '--release', '25',
    '-encoding', 'UTF-8', '-Xlint:-options', '-proc:none',
    '-cp', os.pathsep.join(str(p) for p in jars), '-d', str(classes), '@' + str(source_list),
])
if compiled.returncode:
    sys.exit(compiled.returncode)
resources = root / 'plugin/src/main/resources'
if resources.is_dir():
    shutil.copytree(resources, classes, dirs_exist_ok=True)
# 소품 대응표(모드에서 가져온 모델, 장비, 스킨 목록)를 jar 에 넣는다
props = root / 'tools/props.json'
if props.is_file():
    shutil.copy2(props, classes / 'props.json')
artifact = root / 'build/Gave.jar'
subprocess.run([str(jdk / ('jar' + suffix)), '--create', '--file', str(artifact),
                '-C', str(classes), '.'], check=True)
plugins = root / 'runtime/plugins'
plugins.mkdir(exist_ok=True)
# 켜진 서버 아래 jar 를 바꾸면 그 서버가 클래스를 못 찾는다(NoClassDefFoundError).
# 서버가 켜져 있으면 build/Gave.jar 에만 두고, test_server.sh start 가 켤 때 넣는다.
try:
    with socket.create_connection(('127.0.0.1', 26200), timeout=0.5):
        running = True
except OSError:
    running = False
took = f'{len(sources)}개 소스, {time.monotonic() - started:.2f}초'
if running:
    print(f'빌드 완료: build/Gave.jar ({took}). 시험 서버(26200)가 켜져 있어 runtime 에 안 넣음, 다음 test_server.sh start 때 들어감')
else:
    shutil.copy2(artifact, plugins / 'Gave.jar')
    print(f'빌드 완료: runtime/plugins/Gave.jar ({took})')
PY
