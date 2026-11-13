#!/usr/bin/env bash
# Windows Git Bash용. 서버 표준 출력과 오류는 파일로만 보낸다.
set -euo pipefail
cd "$(dirname "$0")/.."
python -X utf8 - "$@" <<'PY'
from pathlib import Path
import ctypes
from ctypes import wintypes
import json
import os
import shutil
import socket
import struct
import subprocess
import sys
import time

ROOT = Path.cwd().resolve()
BUILD = ROOT / 'build'
# 슬롯(GV_SLOT=1..9): 세션마다 따로 켜는 시험 서버. runtime-N 폴더(처음 켤 때 runtime 을 복사), 포트 26200+10N, RCON 26201+10N, 팩 8290+N(게이브 몫, 다른 세션 서버 포트와 겹치지 않음).
# 슬롯 서버는 이 PC 시험 클라 전용이라 정품 인증과 화이트리스트를 늘 끈다(켤 때마다 바꿔 끼우지 않게). 슬롯 0 은 예전 그대로.
SLOT = int(os.environ.get('GV_SLOT', '0') or 0)
RUNTIME = ROOT / ('runtime' if SLOT == 0 else f'runtime-{SLOT}')
STATE = BUILD / ('server-process.json' if SLOT == 0 else f'server-process-{SLOT}.json')
LOG = BUILD / ('server.log' if SLOT == 0 else f'server-{SLOT}.log')
PORTS = (26200 + 10 * SLOT, 26201 + 10 * SLOT)
PACK_PORT = 8290 + SLOT
PASSWORD = 'gave-dev'
START_TIMEOUT = int(os.environ.get('GV_START_TIMEOUT', '420'))
if os.name != 'nt':
    raise SystemExit('이 스크립트는 Windows Git Bash와 Windows Python용입니다.')

# PID 재사용으로 다른 Java를 종료하지 않도록 생성 시각까지 대조한다.
kernel = ctypes.WinDLL('kernel32', use_last_error=True)
kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
kernel.OpenProcess.restype = wintypes.HANDLE
kernel.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
kernel.GetProcessTimes.restype = wintypes.BOOL
kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
kernel.WaitForSingleObject.restype = wintypes.DWORD
kernel.TerminateProcess.argtypes = [wintypes.HANDLE, wintypes.UINT]
kernel.TerminateProcess.restype = wintypes.BOOL
kernel.CloseHandle.argtypes = [wintypes.HANDLE]

def birth(handle):
    values = [wintypes.FILETIME() for _ in range(4)]
    if not kernel.GetProcessTimes(handle, *(ctypes.byref(v) for v in values)):
        raise ctypes.WinError(ctypes.get_last_error())
    return (values[0].dwHighDateTime << 32) | values[0].dwLowDateTime

def alive(handle):
    result = kernel.WaitForSingleObject(handle, 0)
    if result not in (0, 258):
        raise ctypes.WinError(ctypes.get_last_error())
    return result == 258

def owned_process():
    if not STATE.exists():
        return None
    data = json.loads(STATE.read_text(encoding='utf-8'))
    if data['runtime'] != str(RUNTIME):
        raise RuntimeError('저장된 서버 경로가 다릅니다. 조작하지 않습니다.')
    handle = kernel.OpenProcess(0x101001, False, data['pid'])
    if not handle:
        if ctypes.get_last_error() == 87:  # 이미 종료된 PID
            return None
        raise ctypes.WinError(ctypes.get_last_error())
    if birth(handle) != data['birth'] or not alive(handle):
        kernel.CloseHandle(handle)
        return None
    return handle

def check_ports():
    # IPv4와 IPv6 모두 검사한다. Windows 배타 바인딩으로 점유를 확인한다.
    for port in PORTS:
        for family, address in ((socket.AF_INET, '0.0.0.0'), (socket.AF_INET6, '::')):
            with socket.socket(family, socket.SOCK_STREAM) as probe:
                if family == socket.AF_INET6:
                    probe.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
                probe.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
                try:
                    probe.bind((address, port))
                except OSError as exc:
                    raise RuntimeError(f'포트 {port} 사용 중이거나 바인딩 불가: {exc}') from exc

def recv_exact(connection, count):
    data = bytearray()
    while len(data) < count:
        chunk = connection.recv(count - len(data))
        if not chunk:
            raise EOFError('RCON 연결이 닫혔습니다.')
        data.extend(chunk)
    return bytes(data)

def receive(connection):
    length, = struct.unpack('<i', recv_exact(connection, 4))
    if not 10 <= length <= 4 * 1024 * 1024:
        raise RuntimeError('RCON 패킷 길이가 잘못되었습니다.')
    packet = recv_exact(connection, length)
    if packet[-2:] != b'\0\0':
        raise RuntimeError('RCON 패킷 끝이 잘못되었습니다.')
    request_id, kind = struct.unpack('<ii', packet[:8])
    return request_id, kind, packet[8:-2]

def send(connection, request_id, kind, text):
    packet = struct.pack('<ii', request_id, kind) + text.encode('utf-8') + b'\0\0'
    connection.sendall(struct.pack('<i', len(packet)) + packet)

def rcon(command):
    with socket.create_connection(('127.0.0.1', PORTS[1]), timeout=5) as connection:
        send(connection, 1, 3, PASSWORD)
        request_id, kind, _ = receive(connection)
        if (request_id, kind) != (1, 2):
            raise RuntimeError('RCON 인증 실패')
        send(connection, 2, 2, command)
        # 첫 응답 후 표식을 보낸다. Minecraft는 합쳐진 요청 패킷을 버릴 수 있다.
        chunks = []
        marker_sent = False
        while True:
            try:
                request_id, kind, body = receive(connection)
            except (EOFError, ConnectionError):
                if command.strip().lower() == 'stop':
                    break
                raise
            if request_id == 3:
                break
            if request_id != 2 or kind != 0:
                raise RuntimeError('예상하지 못한 RCON 응답')
            chunks.append(body)
            if command.strip().lower() == 'stop':
                break
            if not marker_sent:
                send(connection, 3, 2, '')
                marker_sent = True
        return b''.join(chunks).decode('utf-8')

def shutdown(handle):
    if alive(handle):
        try:
            print(rcon('stop'), flush=True)
        except (OSError, EOFError, RuntimeError) as exc:
            print(f'RCON stop 응답 오류: {exc}', flush=True)
        deadline = time.monotonic() + 60
        while alive(handle) and time.monotonic() < deadline:
            time.sleep(0.5)
        if alive(handle):
            print('정상 종료 시간 초과. 저장된 생성 시각과 일치하는 서버만 종료합니다.', flush=True)
            if not kernel.TerminateProcess(handle, 1):
                raise ctypes.WinError(ctypes.get_last_error())
            if kernel.WaitForSingleObject(handle, 10000) != 0:
                raise RuntimeError('서버 강제 종료 확인 실패')
    STATE.unlink(missing_ok=True)
    check_ports()
    print(f'서버 프로세스 종료 및 {PORTS[0]}/{PORTS[1]} 포트 해제 확인', flush=True)

def prepare_slot():
    """슬롯 폴더가 없으면 runtime 을 복사하고(로그, 잠금 빼고) 포트와 인증 설정을 슬롯에 맞춘다."""
    if SLOT == 0:
        return
    base = ROOT / 'runtime'
    if not RUNTIME.exists():
        print(f'슬롯 {SLOT} 폴더를 만든다: {RUNTIME}', flush=True)
        shutil.copytree(base, RUNTIME, ignore=shutil.ignore_patterns('logs', 'session.lock', 'stats.json', 'matches'))
    path = RUNTIME / 'server.properties'
    want = {'server-port': str(PORTS[0]), 'query.port': str(PORTS[0]), 'enable-rcon': 'true', 'rcon.port': str(PORTS[1]),
            'rcon.password': PASSWORD, 'online-mode': 'false', 'white-list': 'false', 'enforce-whitelist': 'false',
            'motd': f'gave test slot {SLOT}'}
    rows, seen = [], set()
    for line in path.read_text(encoding='utf-8').splitlines():
        key = line.split('=', 1)[0]
        if '=' in line and not line.startswith('#') and key in want:
            rows.append(f'{key}={want[key]}')
            seen.add(key)
        else:
            rows.append(line)
    rows += [f'{k}={v}' for k, v in want.items() if k not in seen]
    path.write_text('\n'.join(rows) + '\n', encoding='utf-8')

def start():
    previous = owned_process()
    if previous:
        kernel.CloseHandle(previous)
        raise RuntimeError('이 스크립트가 시작한 서버가 이미 실행 중입니다.')
    prepare_slot()
    properties = dict(line.split('=', 1) for line in
                      (RUNTIME / 'server.properties').read_text(encoding='utf-8').splitlines()
                      if '=' in line and not line.startswith('#'))
    expected = {'server-port': str(PORTS[0]), 'enable-rcon': 'true',
                'rcon.port': str(PORTS[1]), 'rcon.password': PASSWORD}
    if any(properties.get(k) != v for k, v in expected.items()):
        raise RuntimeError('server.properties의 시험 포트 또는 RCON 설정이 다릅니다.')
    check_ports()
    print(f'{PORTS[0]}/{PORTS[1]} 포트 비어 있음 확인(슬롯 {SLOT})', flush=True)
    BUILD.mkdir(exist_ok=True)
    # 서버가 켜져 있는 동안 build_plugin.sh 가 build/ 에만 둔 새 jar 를 넣는다
    # GV_JAR: 병렬 세션이 따로 빌드한 jar 를 그 슬롯에 넣을 때
    fresh = Path(os.environ['GV_JAR']) if os.environ.get('GV_JAR') else BUILD / 'Gave.jar'
    installed = RUNTIME / 'plugins/Gave.jar'
    if os.environ.get('GV_JAR') and fresh.exists():
        RUNTIME.joinpath('plugins').mkdir(parents=True, exist_ok=True)
        shutil.copy2(fresh, installed)
        print(f'따로 빌드한 플러그인을 넣음: {fresh} → {RUNTIME.name}/plugins', flush=True)
    if fresh.exists() and (not installed.exists() or fresh.stat().st_mtime > installed.stat().st_mtime):
        shutil.copy2(fresh, installed)
        print(f'새로 빌드한 플러그인을 넣음: build/Gave.jar → {RUNTIME.name}/plugins', flush=True)
    java = Path.home() / '.jdks/temurin-25/bin/java.exe'
    # 팩 폴더(GV_PACK_DIR → -Dgave.packDir, 상대 경로면 서버 폴더 기준, 없으면 build/): 플러그인(PackServer)은 모습 팩 Gave-pack.zip 과
    # 같은 폴더에 있으면 소리 팩 Gave-sound.zip 도 한 요청으로 보낸다(GV_PACK_SPLIT=1 로 묶으면 둘, 아니면 하나: tools/build_pack.py)
    pack_dir = Path(os.environ['GV_PACK_DIR']) if os.environ.get('GV_PACK_DIR') else BUILD
    if not pack_dir.is_absolute():
        pack_dir = RUNTIME / pack_dir
    packs = [f'{n} {(pack_dir / n).stat().st_size / 1e6:.1f}MB' for n in ('Gave-pack.zip', 'Gave-sound.zip') if (pack_dir / n).is_file()]
    print(f'팩 폴더 {pack_dir}: ' + (' + '.join(packs) if packs else '팩 없음(tools/build_pack.py)'), flush=True)
    if os.environ.get('GV_PACK_SPLIT') == '1' and not (pack_dir / 'Gave-sound.zip').is_file():
        print('경고: GV_PACK_SPLIT=1 인데 소리 팩 Gave-sound.zip 이 없어 팩 하나로 보낸다', flush=True)
    started = time.monotonic()
    with LOG.open('wb') as output:
        process = subprocess.Popen([
            str(java), '-Xms1G', '-Xmx' + os.environ.get('GV_XMX', '3G'), '-Dfile.encoding=UTF-8',
            '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
            '-Dterminal.jline=false', '-Dterminal.ansi=false', f'-Dgave.packPort={PACK_PORT}', '-Dspark.backgroundProfiler=false',
        ] + ([f"-Dgave.packDir={os.environ['GV_PACK_DIR']}"] if os.environ.get('GV_PACK_DIR') else []) + (
            [f"-Dgave.packRepo={os.environ['GV_PACK_REPO']}"] if os.environ.get('GV_PACK_REPO') else []) + [
            '-jar', str(RUNTIME / 'paper.jar'), '--nogui', '--noconsole',
        ], cwd=RUNTIME, stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP)
    handle = kernel.OpenProcess(0x101001, False, process.pid)
    try:
        if not handle:
            raise ctypes.WinError(ctypes.get_last_error())
        STATE.write_text(json.dumps({'pid': process.pid, 'birth': birth(handle),
                                    'runtime': str(RUNTIME)}, indent=2), encoding='utf-8')
        print(f'시험 서버 PID={process.pid}, 로그={LOG}', flush=True)
        # 새 월드는 네더, 엔드 생성 구역을 처음 만들고 다른 서버가 같이 돌면 3분을 넘기도 한다
        while time.monotonic() - started < START_TIMEOUT:
            if process.poll() is not None:
                raise RuntimeError(f'서버가 조기 종료되었습니다: {process.returncode}. build/server.log 확인')
            log = LOG.read_text(encoding='utf-8', errors='replace')
            if 'Done (' in log:
                # Done 이후 RCON 리스너가 열리는 짧은 간격까지 기다린다.
                try:
                    with socket.create_connection(('127.0.0.1', PORTS[1]), timeout=0.5):
                        pass
                except OSError:
                    time.sleep(0.5)
                    continue
                print(f'서버 준비 완료: {time.monotonic() - started:.2f}초', flush=True)
                return
            time.sleep(0.5)
        raise RuntimeError(f'{START_TIMEOUT}초 안에 Done 및 RCON 준비를 확인하지 못했습니다.')
    except BaseException:
        if handle:
            shutdown(handle)
        else:
            process.kill()
            process.wait(timeout=10)
        raise
    finally:
        if handle:
            kernel.CloseHandle(handle)

try:
    action = sys.argv[1] if len(sys.argv) > 1 else ''
    if action == 'start' and len(sys.argv) == 2:
        start()
    elif action in ('stop', 'rcon') and (len(sys.argv) == 2 if action == 'stop' else len(sys.argv) >= 3):
        handle = owned_process()
        if not handle:
            if action == 'stop':
                check_ports()
                STATE.unlink(missing_ok=True)
                print(f'추적 중인 서버 없음. {PORTS[0]}/{PORTS[1]} 포트 비어 있음 확인')
            else:
                raise RuntimeError('이 스크립트로 시작한 서버가 없습니다. RCON을 보내지 않습니다.')
        else:
            try:
                if action == 'stop':
                    shutdown(handle)
                else:
                    print(rcon(' '.join(sys.argv[2:])))
            finally:
                kernel.CloseHandle(handle)
    else:
        raise RuntimeError('사용법: bash tools/test_server.sh start | rcon <명령> | stop')
except (OSError, EOFError, RuntimeError, ValueError, KeyError) as exc:
    print(f'오류: {exc}', file=sys.stderr)
    sys.exit(1)
PY
