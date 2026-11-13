"""build/ 의 두 팩(모습 Gave-pack.zip, 소리 Gave-sound.zip)을 깃허브 공개 저장소의 릴리스로 올린다(동원훈련 tools/upload_pack.py 를 옮김).

팩마다 자기 릴리스 pack-<그 팩 sha1 앞 12자리> 에 그 파일 이름 그대로 올린다(Gave-pack.zip, Gave-sound.zip).
플러그인(-Dgave.packRepo=저장소)은 켤 때와 /gave 팩 때 팩마다 같은 태그의 파일을 받아 sha1 이 맞으면 그 주소를 보내고,
아니면 그 팩만 로컬 팩 포트(8088)로 보낸다. 팩을 고칠 때마다 build_pack.py 다음에 이것을 돌린다. 이미 같은 태그가 있으면
그 팩은 건너뛴다(바뀌지 않은 팩은 다시 올리지 않음). 올린 뒤 팩마다 다시 받아 sha1 을 맞춰 본다.
소리 팩이 없으면(build_pack 이 소리까지 하나로 묶은 옛 꼴) 모습 팩 하나만 올린다.

    python -X utf8 tools/upload_pack.py --public [저장소]          (기본 tinymita2-pixel/gave-pack)
    python -X utf8 tools/upload_pack.py --dry-run [--dir <폴더>]   아무것도 올리지 않고(네트워크도 쓰지 않고) 할 일만 찍음

공개 저장소라 누구나 받을 수 있다. 이 팩에는 실제 플레이어 _Gave 의 스킨 얼굴과 출처가 확인되지 않은
총소리가 들어 있으므로(docs/운영.md '배포할 때'), 사용자가 올리기로 정한 뒤에만 --public 을 붙여 돌린다.
"""
import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
README = ROOT / 'docs/pack_README.md'
DEFAULT_REPO = 'tinymita2-pixel/gave-pack'
# 올리는 차례: 소리 팩 먼저, 모습 팩을 나중에(둘 다 새로 올리면 모습 팩 릴리스가 'Latest')
FILES = ('Gave-sound.zip', 'Gave-pack.zip')
SOUNDS_JSON = re.compile(r'assets/[^/]+/sounds\.json')
GH = None


def gh_exe():
    found = shutil.which('gh')
    if found:
        return found
    local = Path(os.environ.get('LOCALAPPDATA', '')) / 'Programs/GitHub CLI/bin/gh.exe'
    if local.is_file():
        return str(local)
    raise SystemExit('gh(GitHub CLI)를 찾지 못했다.')


def gh(*args, check=True, data=None):
    r = subprocess.run([GH, *args], input=data, capture_output=True, text=True, encoding='utf-8')
    if check and r.returncode:
        raise SystemExit(f'gh {" ".join(args)} 실패:\n{r.stderr.strip()}')
    return r


def ensure_repo(repo):
    if gh('repo', 'view', repo, '--json', 'name', check=False).returncode == 0:
        return
    gh('repo', 'create', repo, '--public', '--description', '게이브 서버 리소스팩(서버가 접속할 때 자동으로 보냄)')
    print(f'저장소를 만들었다: https://github.com/{repo}')


def put_readme(repo):
    """README 를 docs/pack_README.md 와 맞춘다(빈 저장소엔 릴리스를 못 만들어서 첫 커밋 구실도 한다)."""
    text = README.read_text(encoding='utf-8')
    cur = gh('api', f'repos/{repo}/contents/README.md', check=False)
    sha = None
    if cur.returncode == 0:
        info = json.loads(cur.stdout)
        if base64.b64decode(info['content']).decode('utf-8') == text:
            return
        sha = info['sha']
    body = {'message': 'README', 'content': base64.b64encode(text.encode('utf-8')).decode('ascii')}
    if sha:
        body['sha'] = sha
    gh('api', '-X', 'PUT', f'repos/{repo}/contents/README.md', '--input', '-', data=json.dumps(body))
    print('README 를 올렸다')


def plan(folder: Path, repo: str) -> list[dict]:
    """올릴 팩들(올리는 차례): 파일, 바이트, sha1, 태그, 받을 주소. 두 팩이 어긋나 있으면(나눈 모습 팩만, 하나로 묶은 팩 옆의 옛 소리 팩) 멈춘다"""
    look, sound = folder / 'Gave-pack.zip', folder / 'Gave-sound.zip'
    if not look.is_file():
        raise SystemExit(f'{look} 가 없다. 먼저 python -X utf8 tools/build_pack.py')
    with zipfile.ZipFile(look) as z:
        look_sounds = [n for n in z.namelist() if SOUNDS_JSON.fullmatch(n)]
    if sound.is_file() and look_sounds:
        raise SystemExit(f'{look.name} 에도 {", ".join(look_sounds)} 가 있다(소리까지 하나로 묶은 팩 옆에 옛 {sound.name} 가 남음). '
                         'python -X utf8 tools/build_pack.py 로 다시 묶을 것')
    if not sound.is_file() and not look_sounds:
        raise SystemExit(f'{sound} 가 없는데 {look.name} 에도 sounds.json 이 없다(나눈 모습 팩만 남음). '
                         'python -X utf8 tools/build_pack.py 로 다시 묶을 것')
    out = []
    for name in FILES:
        path = folder / name
        if not path.is_file():
            continue
        data = path.read_bytes()
        sha1 = hashlib.sha1(data).hexdigest()
        tag = 'pack-' + sha1[:12]
        out.append({'path': path, 'name': name, 'size': len(data), 'sha1': sha1, 'tag': tag,
                    'url': f'https://github.com/{repo}/releases/download/{tag}/{name}'})
    return out


def dry_run(packs: list[dict], folder: Path, repo: str):
    print(f'[마른 실행] 아무것도 올리지 않고 깃허브에 묻지도 않는다. 저장소 {repo}, 팩 폴더 {folder}')
    print(f'1. 저장소 {repo} 가 없으면 공개로 만들고, README 를 {README.relative_to(ROOT).as_posix()} 와 맞춘다')
    for i, p in enumerate(packs, 2):
        print(f'{i}. 릴리스 {p["tag"]}: {p["name"]} ({p["size"]} 바이트, sha1 {p["sha1"]}) 를 올린다'
              ' (그 태그가 이미 있으면 건너뛰고, 릴리스에 파일이 없으면 그 파일만 올린다)')
    print(f'{len(packs) + 2}. 팩마다 다시 받아 sha1 을 맞춰 본다:')
    for p in packs:
        print(f'   {p["url"]}')
    print(f'서버(-Dgave.packRepo={repo})는 팩마다 위 주소를 받아 sha1 이 맞으면 그 주소로, 아니면 그 팩만 이 PC 포트로 보낸다.')


def upload(p: dict, repo: str):
    view = gh('release', 'view', p['tag'], '-R', repo, '--json', 'assets', check=False)
    if view.returncode == 0:
        names = [a.get('name') for a in json.loads(view.stdout or '{}').get('assets', [])]
        if p['name'] in names:
            print(f'{p["tag"]} ({p["name"]}) 는 이미 올라가 있다')
            return
        gh('release', 'upload', p['tag'], str(p['path']), '-R', repo, '--clobber')
        print(f'{p["tag"]} 릴리스는 있었고 {p["name"]} 만 올렸다')
        return
    gh('release', 'create', p['tag'], str(p['path']), '-R', repo, '--title', f'{p["tag"]} ({p["name"]})',
       '--notes', f'{p["name"]} {p["size"]} 바이트, sha1 {p["sha1"]}', '--latest')
    print(f'{p["tag"]} ({p["name"]}) 를 올렸다')


def main():
    global GH
    ap = argparse.ArgumentParser(description='두 팩을 깃허브 공개 저장소 릴리스로 올린다')
    ap.add_argument('repo', nargs='?', default=DEFAULT_REPO)
    ap.add_argument('--public', action='store_true', help='정말 올린다(공개 저장소: 누구나 받음)')
    ap.add_argument('--dry-run', action='store_true', help='아무것도 올리지 않고 할 일만 찍는다')
    ap.add_argument('--dir', default=str(ROOT / 'build'), help='팩이 있는 폴더(기본 build)')
    a = ap.parse_args()
    folder = Path(a.dir)
    packs = plan(folder, a.repo)
    if a.dry_run:
        dry_run(packs, folder, a.repo)
        return
    if not a.public:
        raise SystemExit(
            '올리지 않았다. 이 팩은 공개 저장소에 올라가면 누구나 받는다.\n'
            '팩 안의 _Gave 얼굴(실제 플레이어 스킨)과 출처 미확인 총소리를 공개해도 되는지 사용자가 정한 뒤\n'
            'python -X utf8 tools/upload_pack.py --public  로 다시 돌릴 것(할 일만 보려면 --dry-run). 올라갈 곳:\n'
            + '\n'.join(p['url'] for p in packs))
    GH = gh_exe()
    ensure_repo(a.repo)
    put_readme(a.repo)
    for p in packs:
        upload(p, a.repo)
    for p in packs:
        req = urllib.request.Request(p['url'], headers={'User-Agent': 'gave-upload'})
        with urllib.request.urlopen(req, timeout=120) as r:
            got = hashlib.sha1(r.read()).hexdigest()
        if got != p['sha1']:
            raise SystemExit(f'받아 본 {p["name"]} sha1 이 다르다: {got} (올린 것 {p["sha1"]})')
        print(f'확인: {p["url"]}\nsha1 {p["sha1"]}')
    print('켜진 서버엔 /gave 팩 으로 다시 보낼 수 있다.')


if __name__ == '__main__':
    main()
