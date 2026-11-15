"""ref/ 원본 다시 받기: 저장소에 남은 출처 기록(ref/**/_info.json)의 주소에서 받아 해시를 맞춰 보고, 생성기가 읽는 자리에 꺼내 둔다.

python -I -X utf8 tools/fetch_ref.py [mods] [sfx] [bgm] [skin] [shaders] [--person]     (갈래를 안 적으면 전부)

- 기록(_info.json)은 읽기만 하고 고치지 않는다. 주소가 죽었거나 해시가 다르면 그 파일은 두지 않고 '못 받음' 으로 찍는다(같은 파일을 다른 데서
  찾지 않음). 하나라도 못 받으면 종료 코드 1, 남은 것은 build/pipeline/dl/사람이_받을_것.txt 에 적는다.
- 이미 있고 해시가 맞는 파일은 다시 받지 않는다(다시 돌리면 그대로 검사가 된다).
- 받은 묶음(jar, zip, 7z, 낱개 파일)은 build/pipeline/dl/ 에 남긴다. 그 폴더 아래(하위 폴더 포함) 아무 데나 사람이 브라우저로 받아 둔 파일도
  쓴다: sha256 이 기록과 같거나(이름은 달라도 됨) 이름이 주소의 파일 이름과 같으면 받지 않고 그것을 쓴다.
  받은 파일은 데이터로만 다룬다(압축을 풀기만 하고 실행하지 않음). 7z 은 Windows 의 bsdtar(System32/tar.exe)로 푼다.
- AI 에이전트 접근을 막은 곳은 이 도구가 저절로 받지 않는다(2026-11-14 에 본 robots.txt):
  opengameart.org 는 Claude-Code, Claude-User, ClaudeBot 등에 Disallow: / 이고 그 밖의 클라이언트에는 Crawl-delay 10 만 건다.
    사람이 자기 터미널에서 돌릴 때만 --person 을 붙인다(요청 사이 10초를 쉰다). AI 에이전트는 --person 을 붙이지 않는다.
  kenney.nl 은 Claude-User, ClaudeBot 에 Disallow: / 이고 내려받기 주소가 묶음 글 페이지에서 누른 요청에만 답한다(그냥 부르면 403).
    --person 으로도 받지 않는다: 사람이 브라우저로 묶음 글 페이지(_info.json 의 page)에서 받아 build/pipeline/dl/ 아래에 둔다.
- 갈래
  mods    ref/mods/<이름>/extracted/: jar(file_url, sha512)에서 extracted 목록만 꺼낸다. file_url 이 없는 것(trafficcraft)은 Modrinth API 에
          sha512 로 물어 받는다. files(경로: sha256)와 source(깃허브)가 있는 것은 그 커밋(없으면 branch)의 파일을 낱개로 받는다.
          jar 는 build/pipeline/dl/mods/<이름>/ 에 _info.json 사본과 함께 남는다(새 무기 부품을 꺼낼 때:
          python -I -X utf8 tools/props/extract_arms.py build/pipeline/dl/mods).
  sfx     ref/sfx/<묶음>/<파일>: urls 를 받아 sources 의 sha256 과 맞추고 files 의 sha256 으로 꺼낸다.
          ref/sfx/weapons/<출처>/<파일> 은 file_url 과 files(이름: sha256).
  bgm     ref/bgm/_info.json 의 곡 전부(안 쓰는 곡도 받는다: make_bgm.py 가 있는 파일만으로 _info.json 을 다시 쓰던 도구라 빠지면 안 된다).
  skin    ref/_Gave_skin.png: 마인크래프트 플레이어 _Gave 의 지금 공개 스킨(기록된 해시가 없어 받은 것의 sha256 만 찍는다).
  shaders 바닐라 셰이더 원본 ~/.cache/mc-shader-vanilla/26.3/assets/minecraft/shaders/ 를 26.3 클라 jar(tools/mcjar.py)에서 꺼낸다.
          받는 것은 없다. 이미 있으면(만든 사람 PC) 건드리지 않는다.
"""
import base64
import hashlib
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))  # -I 는 스크립트 폴더를 경로에 넣지 않는다
from mcjar import client_jar  # noqa: E402

REF = ROOT / "ref"
DL = ROOT / "build/pipeline/dl"
TODO = DL / "사람이_받을_것.txt"
UA = {"User-Agent": "mod-asset-fetch/1.0"}  # tools/trip/fetch_*.py 와 같은 이름. 이 PC 의 이름이나 경로는 요청에 싣지 않는다
NO_AGENT = ("opengameart.org",)  # --person 일 때만 받는 곳(위 머리말)
NEVER = ("kenney.nl",)  # 사람이 브라우저로만 받는 곳
PERSON = "--person" in sys.argv
BAD, MANUAL = [], []
_pool = None
_last = {}


def digest(src, algo="sha256"):
    h = hashlib.new(algo)
    if isinstance(src, (bytes, bytearray)):
        h.update(src)
    else:
        with open(src, "rb") as f:
            for b in iter(lambda: f.read(1 << 20), b""):
                h.update(b)
    return h.hexdigest()


def fail(what, why):
    BAD.append(f"{what}: {why}")
    print(f"  못 받음 {what}: {why}", flush=True)


def get(url, timeout=120):
    host = urllib.parse.urlsplit(url).hostname or ""
    if host.endswith(NEVER) or (host.endswith(NO_AGENT) and not PERSON):
        raise PermissionError("AI 에이전트 접근을 막은 곳(사람이 받을 것)")
    if host.endswith(NO_AGENT):  # robots.txt 의 Crawl-delay 10
        time.sleep(max(0.0, _last.get(host, 0) + 10 - time.monotonic()))
        _last[host] = time.monotonic()
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout)


def pool():
    """build/pipeline/dl 아래 모든 파일: {sha256: 경로}, {이름: 경로}(받아 둔 것과 사람이 넣어 둔 것)"""
    global _pool
    if _pool is None:
        files = [p for p in DL.rglob("*") if p.is_file() and p != TODO and p.suffix != ".part"] if DL.is_dir() else []
        _pool = ({digest(p): p for p in files}, {p.name: p for p in files})
    return _pool


def source(url, sub, accept=None, algo="sha256", page=None):
    """원본 하나의 경로. 받아 둔 것(해시가 같은 것, build/pipeline/dl/<sub>/ 의 같은 이름, 다른 곳의 같은 이름 차례)이 있으면 그것,
    없으면 url 에서 build/pipeline/dl/<sub>/ 로 받는다. accept(해시 목록)가 있으면 그 가운데 하나와 맞아야 한다(없으면 꺼낸 파일의 해시로
    나중에 맞춘다). 이 도구가 못 받는 곳이면 MANUAL 에 적고 None"""
    name = urllib.parse.unquote(urllib.parse.urlsplit(url).path.rsplit("/", 1)[-1])
    by_hash, by_name = pool()
    dst = DL / sub / name
    cands = [by_hash.get(accept[0])] if accept and len(accept) == 1 and algo == "sha256" else []
    for hit in cands + [dst, by_name.get(name)]:
        if hit and hit.is_file() and (accept is None or digest(hit, algo) in accept):
            return hit
    try:
        dst.parent.mkdir(parents=True, exist_ok=True)
        tmp = dst.with_name(dst.name + ".part")
        with get(url) as r, open(tmp, "wb") as f:
            shutil.copyfileobj(r, f, 1 << 20)
    except PermissionError:
        MANUAL.append(f"{page or url}\t{url}\t" + (f"{algo}={accept[0]}" if accept and len(accept) == 1 else "(해시는 그 묶음 _info.json 의 sources 나 files 로 맞춤)"))
        return None
    got = digest(tmp, algo)
    if accept is not None and got not in accept:
        tmp.unlink()
        raise ValueError(f"{algo} 가 기록과 다름(받은 것 {got[:16]}.., 배포처의 파일이 바뀜)")
    os.replace(tmp, dst)
    by_hash[digest(dst)] = by_name[name] = dst
    return dst


def members(arc, names=()):
    """받은 파일 하나 → [(안의 경로, 바이트)]. zip 은 안의 파일 전부, 7z 은 names(파일 이름)에 든 것만, 그 밖은 그 파일 하나"""
    if zipfile.is_zipfile(arc):
        with zipfile.ZipFile(arc) as z:
            return [(n, z.read(n)) for n in z.namelist() if not n.endswith("/")]
    if arc.suffix.lower() == ".7z":
        tar = Path(os.environ.get("SystemRoot", "C:/Windows")) / "System32/tar.exe"
        listing = subprocess.run([str(tar), "-tf", str(arc)], capture_output=True, check=True).stdout.decode("utf-8", "replace").splitlines()
        want = [n for n in listing if n.rsplit("/", 1)[-1] in names]
        with tempfile.TemporaryDirectory(dir=DL) as tmp:
            subprocess.run([str(tar), "-xf", str(arc), "-C", tmp] + want, check=True)
            return [(n, (Path(tmp) / n).read_bytes()) for n in want]
    return [(arc.name, arc.read_bytes())]


def put(dst, data):
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_bytes(data)


def take(label, want, arcs):
    """want {놓을 경로: sha256} 가운데 아직 없는 것을 arcs(받은 원본들)에서 sha256 으로 찾아 놓는다 → 놓은 수"""
    todo = {p: h for p, h in want.items() if not (p.is_file() and digest(p) == h)}
    blobs = {}
    for arc in arcs:
        for _, data in members(arc, {p.name for p in todo}):
            blobs[digest(data)] = data
    for p, h in todo.items():
        if h in blobs:
            put(p, blobs[h])
        elif arcs:
            fail(f"{label}/{p.name}", "받은 원본 안에 sha256 이 맞는 파일이 없음")
    return sum(1 for p, h in todo.items() if h in blobs)


def mods():
    for info_p in sorted((REF / "mods").glob("*/_info.json")):
        info = json.loads(info_p.read_text(encoding="utf-8"))
        d, name = info_p.parent, info_p.parent.name
        try:
            if isinstance(info.get("files"), dict):  # 깃허브에서 낱개로 받은 것
                repo = info["source"].split("github.com/")[1].strip("/")
                rev = info.get("commit") or info["branch"]
                n = 0
                for rel, want in info["files"].items():
                    dst = d / "extracted" / rel
                    if dst.is_file() and digest(dst) == want:
                        continue
                    # 저장소 안 경로가 기록과 다른 것이 있다(mrcrayfish-vehicle 은 src/main/resources/ 아래): sha256 이 맞는 쪽을 쓴다
                    for cand in (rel, "src/main/resources/" + rel):
                        try:
                            data = get(f"https://raw.githubusercontent.com/{repo}/{rev}/{urllib.parse.quote(cand)}").read()
                        except urllib.error.HTTPError:
                            continue
                        if digest(data) == want:
                            put(dst, data)
                            n += 1
                            break
                    else:
                        fail(f"mods/{name}/{rel}", f"{repo}@{rev[:12]} 에 없거나 sha256 이 기록과 다름")
                print(f"mods/{name}: 파일 {len(info['files'])}개(이번에 받음 {n})", flush=True)
                continue
            missing = [f for f in info["extracted"] if not (d / "extracted" / f).is_file()]
            if missing:
                url = info.get("file_url")
                if not url:  # 받은 주소를 안 적은 기록(trafficcraft): 같은 판 파일을 Modrinth 에 sha512 로 묻는다
                    v = json.loads(get(f"https://api.modrinth.com/v2/version_file/{info['sha512']}?algorithm=sha512").read())
                    url = next(f["url"] for f in v["files"] if f["hashes"]["sha512"] == info["sha512"])
                jar = source(url, f"mods/{info.get('slug', name)}", [info["sha512"]], "sha512")
                side = jar.parent / "_info.json"
                if jar.parent != d and not side.exists():
                    shutil.copyfile(info_p, side)  # extract_arms.py 가 jar 옆에서 읽는다
                with zipfile.ZipFile(jar) as z:
                    for f in missing:
                        put(d / "extracted" / f, z.read(f))
            print(f"mods/{name}: 파일 {len(info['extracted'])}개(이번에 꺼냄 {len(missing)})", flush=True)
        except Exception as e:  # noqa: BLE001  한 묶음이 안 돼도 나머지는 받는다
            fail(f"mods/{name}", f"{type(e).__name__}: {e}")


def sfx():
    for info_p in sorted((REF / "sfx").rglob("_info.json")):
        info = json.loads(info_p.read_text(encoding="utf-8"))
        d = info_p.parent
        pack = d.relative_to(REF / "sfx").as_posix()
        if isinstance(info["files"], dict):  # weapons 꼴: 이름 → sha256, 묶음이면 archive_sha256
            want = {d / n: h for n, h in info["files"].items()}
            urls = [u.strip() for u in info["file_url"].split(", ")]
            known = [info["archive_sha256"]] if info.get("archive_sha256") else list(info["files"].values())
            page = info.get("url")
        else:
            want = {d / f["file"]: f["sha256"] for f in info["files"]}
            urls = [u.split(" (")[0] for u in info["urls"]]
            known = [s["sha256"] for s in info["sources"]]
            page = info.get("page")
        if all(p.is_file() and digest(p) == h for p, h in want.items()):
            print(f"sfx/{pack}: 파일 {len(want)}개 있음", flush=True)
            continue
        arcs = [pool()[0][h] for h in known if h in pool()[0]]  # 받아 둔 것(사람이 넣은 것 포함)을 sha256 으로
        if len(arcs) < len(known):
            for url in urls:
                try:
                    arc = source(url, f"sfx/{pack}", known, page=page)
                    if arc and arc not in arcs:
                        arcs.append(arc)
                except Exception as e:  # noqa: BLE001
                    fail(f"sfx/{pack} {url}", f"{type(e).__name__}: {e}")
        n = take(f"sfx/{pack}", want, arcs)
        left = sum(1 for p, h in want.items() if not (p.is_file() and digest(p) == h))
        print(f"sfx/{pack}: 파일 {len(want)}개(이번에 꺼냄 {n}" + (f", 아직 없음 {left}" if left else "") + ")", flush=True)


def bgm():
    info = json.loads((REF / "bgm/_info.json").read_text(encoding="utf-8"))
    n = 0
    for key, e in info.items():
        dst = ROOT / e["file"]
        if dst.is_file() and digest(dst) == e["sha256"]:
            continue
        try:
            direct = not e["file_url"].lower().endswith(".zip")
            arc = source(e["file_url"], "bgm", [e["sha256"]] if direct else None, page=e.get("page"))
            if arc:
                n += take("bgm", {dst: e["sha256"]}, [arc])
        except Exception as ex:  # noqa: BLE001
            fail(f"bgm/{key}", f"{type(ex).__name__}: {ex}")
    left = sum(1 for e in info.values() if not (ROOT / e["file"]).is_file())
    print(f"bgm: 곡 {len(info)}개(이번에 받음 {n}" + (f", 아직 없음 {left}" if left else "") + ")", flush=True)


def skin():
    dst = REF / "_Gave_skin.png"
    if not dst.is_file():
        try:
            uid = json.loads(get("https://api.mojang.com/users/profiles/minecraft/_Gave").read())["id"]
            prof = json.loads(get(f"https://sessionserver.mojang.com/session/minecraft/profile/{uid}").read())
            tex = json.loads(base64.b64decode(next(p["value"] for p in prof["properties"] if p["name"] == "textures")))
            data = get(tex["textures"]["SKIN"]["url"]).read()
            if data[:8] != b"\x89PNG\r\n\x1a\n" or struct.unpack(">II", data[16:24]) != (64, 64):
                raise ValueError("64x64 PNG 가 아님")
            put(dst, data)
        except Exception as e:  # noqa: BLE001
            return fail("skin", f"{type(e).__name__}: {e}")
    print(f"skin: {dst.relative_to(ROOT).as_posix()} sha256 {digest(dst)}", flush=True)


def shaders():
    out = Path.home() / ".cache/mc-shader-vanilla/26.3"
    if (out / "assets/minecraft/shaders/core/item.vsh").is_file():
        return print(f"shaders: 이미 있음 {out}", flush=True)
    jar = client_jar()
    if not jar.is_file():
        return fail("shaders", f"26.3 클라 jar 없음({jar}). GV_CLIENT_JAR 로 알려 줄 것")
    with zipfile.ZipFile(jar) as z:
        names = [n for n in z.namelist() if n.startswith("assets/minecraft/shaders/") and not n.endswith("/")]
        for n in names:
            put(out / n, z.read(n))
    print(f"shaders: {len(names)}개 → {out} (꺼낸 곳 {jar})", flush=True)


def main():
    kinds = {"mods": mods, "sfx": sfx, "bgm": bgm, "skin": skin, "shaders": shaders}
    want = [a for a in sys.argv[1:] if a in kinds] or list(kinds)
    unknown = [a for a in sys.argv[1:] if a not in kinds and a != "--person"]
    if unknown:
        sys.exit(f"모르는 인자 {unknown}: {', '.join(kinds)} 와 --person 만")
    DL.mkdir(parents=True, exist_ok=True)
    for k in want:
        kinds[k]()
    if MANUAL:
        rows = list(dict.fromkeys(MANUAL))
        TODO.write_text("# 사람이 받을 것(AI 에이전트 접근을 막은 곳). 받은 파일을 build/pipeline/dl/ 아래 아무 데나 두고 tools/fetch_ref.py 를 다시 돌린다.\n"
                        "# opengameart.org 는 사람이 자기 터미널에서 python -I -X utf8 tools/fetch_ref.py sfx bgm --person 으로도 받을 수 있다.\n"
                        "# kenney.nl 은 브라우저로 글 페이지에서만. 칸: 글 페이지 <탭> 파일 주소 <탭> 맞출 해시\n" + "\n".join(rows) + "\n",
                        encoding="utf-8")
        print(f"사람이 받을 것 {len(rows)}개 → {TODO.relative_to(ROOT).as_posix()}")
    elif TODO.is_file():
        TODO.unlink()
    if BAD or MANUAL:
        print(f"끝: 못 받음 {len(BAD)}개, 사람이 받을 것 {len(dict.fromkeys(MANUAL))}개")
        sys.exit(1)
    print("끝: 모두 있음")


if __name__ == "__main__":
    main()
