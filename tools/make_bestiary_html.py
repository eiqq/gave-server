"""게이브 도감을 HTML 한 장으로(data/bestiary.json + build/gabe/icons + 진짜 트리의 종 클래스).

python -X utf8 tools/make_bestiary_html.py  ->  docs/게이브도감.html

- 막(1~4)마다, 보스는 따로. 카드: 아이콘, 번호, 이름, 역할, 크기, 한 줄. 누르면 생김새, 움직임, 공격, 신호, 대처.
- 클래스(kr/gave/mob/sp/<번호>.java)가 아직 없는 종은 흐리게(제작 중).
- 그림은 미리보기 판의 앞모습 칸(없으면 HUD 아이콘), base64 로 안에 넣어 파일 하나로 열린다.
"""
import base64
import html
import io
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = json.loads((ROOT / "data/bestiary.json").read_text(encoding="utf-8"))["species"]
ACTS = {a["act"]: a for a in json.loads((ROOT / "data/trip/acts_draft.json").read_text(encoding="utf-8"))["acts"]}
ICONS = ROOT / "build/gabe/icons"
PREVIEW = ROOT / "build/gabe/preview"
SP = ROOT / "plugin/src/main/java/kr/gave/mob/sp"
OUT = ROOT / "docs/게이브도감.html"

COMMON_MAX = 55  # G01~G55 는 모든 막 공통(Deck 과 같은 규칙)
FIELDS = [("look", "생김새"), ("motion", "움직임"), ("attack", "공격"), ("tell", "신호"), ("counter", "대처")]


def esc(s) -> str:
    return html.escape(str(s or "")).replace("·", ", ")


def front(sid: str) -> bytes | None:
    """미리보기 판(build/gabe/preview/<종>.png)의 왼쪽 위 칸(192x192, 기본 상태 앞모습)을 잘라 배경색을 투명으로"""
    f = PREVIEW / f"{sid.lower()}.png"
    if not f.is_file():
        return None
    from PIL import Image
    im = Image.open(f).convert("RGBA").crop((0, 0, 192, 192))
    bg = im.getpixel((1, 1))
    px = im.load()
    for y in range(192):
        for x in range(192):
            if px[x, y] == bg:
                px[x, y] = (0, 0, 0, 0)
    box = im.getbbox()
    if not box:
        return None
    buf = io.BytesIO()
    im.save(buf, "PNG", optimize=True)
    return buf.getvalue()


def icon(sid: str) -> str:
    data = front(sid)
    if data is None:
        f = ICONS / f"{sid.lower()}.png"
        if not f.is_file():
            return '<div class="ic none"></div>'
        data = f.read_bytes()
    b = base64.b64encode(data).decode("ascii")
    return f'<img class="ic" alt="" src="data:image/png;base64,{b}">'


def built(sid: str) -> bool:
    return (SP / f"{sid}.java").is_file()


def card(e: dict) -> str:
    sid = e["id"]
    ok = built(sid)
    size = e.get("sizeBlocks")
    meta = [esc(e.get("role"))]
    if size:
        meta.append(f"{esc(size)}블록")
    if e.get("rewardWon") is not None and not sid.startswith("B"):
        meta.append(f"{esc(e['rewardWon'])}원")
    body = "".join(f"<dt>{lab}</dt><dd>{esc(e.get(k))}</dd>" for k, lab in FIELDS if e.get(k))
    cls = "card" + ("" if ok else " wip") + (" boss" if sid.startswith("B") else "")
    tag = "" if ok else '<span class="tag">제작 중</span>'
    text = " ".join(str(e.get(k, "")) for k in ("name", "oneliner", "role", "look")).lower()
    return (f'<details class="{cls}" data-q="{esc(sid.lower() + " " + text)}">'
            f'<summary>{icon(sid)}<div class="hd"><div class="top"><span class="id">{esc(sid)}</span>{tag}</div>'
            f'<div class="nm">{esc(e.get("name"))}</div><div class="mt">{" ".join(f"<span>{m}</span>" for m in meta)}</div>'
            f'<div class="one">{esc(e.get("oneliner"))}</div></div></summary><dl>{body}</dl></details>')


def section(act: int, items: list, title: str, sub: str) -> str:
    n_ok = sum(built(e["id"]) for e in items)
    return (f'<section id="a{act}"><header class="sh"><h2>{esc(title)}</h2><p>{esc(sub)}</p>'
            f'<div class="cnt"><b>{n_ok}</b> / {len(items)}</div></header>'
            f'<div class="grid">{"".join(card(e) for e in items)}</div></section>')


def main():
    def key(e):
        s = e["id"]
        return (s[0], int(s[1:]))

    bosses = sorted((e for e in DATA if e["id"].startswith("B")), key=lambda e: e["act"])
    # 2026-11-12 사용자 결정: 기존 55종(G01~G55)은 모든 막 공통, 새 145종은 그 막 전용
    common = sorted((e for e in DATA if e["id"].startswith("G") and int(e["id"][1:]) <= COMMON_MAX), key=key)
    secs, nav = [section(0, common, "모든 막 공통", "어느 막에서나 나옴")], ['<a href="#a0">공통<small>55</small></a>']
    for act in (1, 2, 3, 4):
        a = ACTS.get(act, {})
        r = a.get("rounds", [0, 0])
        items = sorted((e for e in DATA if e.get("act") == act and e["id"].startswith("G") and int(e["id"][1:]) > COMMON_MAX), key=key)
        title = f"{act}막 {a.get('name', '')} 전용"
        secs.append(section(act, items, title, f"{r[0]}~{r[1]}라운드에서만"))
        nav.append(f'<a href="#a{act}">{act}막<small>{esc(a.get("name", ""))}</small></a>')
    secs.append(section(5, bosses, "보스", "10, 20, 30라운드와 35라운드 엔딩"))
    nav.append('<a href="#a5">보스<small>5</small></a>')
    total = len(DATA)
    n_ok = sum(built(e["id"]) for e in DATA)
    page = TEMPLATE.replace("{NAV}", "".join(nav)).replace("{SECTIONS}", "".join(secs))
    page = page.replace("{TOTAL}", str(total)).replace("{BUILT}", str(n_ok))
    OUT.write_text(page, encoding="utf-8")
    print(f"{OUT} ({OUT.stat().st_size // 1024} KB, {total}종, 들어간 것 {n_ok})")


TEMPLATE = """<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>게이브 도감</title>
<style>
:root {
  --bg: #f4f1ea; --panel: #fffdf8; --ink: #22201c; --dim: #6f6a60; --line: #ddd6c8;
  --accent: #b5402a; --chip: #ece6da; --boss: #3a2a26;
}
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    --bg: #15130f; --panel: #1f1c17; --ink: #ece7dc; --dim: #9a9387; --line: #34302a;
    --accent: #e0694f; --chip: #2b2721; --boss: #e9d9cf;
  }
}
:root[data-theme="dark"] {
  --bg: #15130f; --panel: #1f1c17; --ink: #ece7dc; --dim: #9a9387; --line: #34302a;
  --accent: #e0694f; --chip: #2b2721; --boss: #e9d9cf;
}
* { box-sizing: border-box; }
body { margin: 0; background: var(--bg); color: var(--ink);
  font: 15px/1.55 "Pretendard", "Apple SD Gothic Neo", "Malgun Gothic", system-ui, sans-serif; }
.wrap { max-width: 1180px; margin: 0 auto; padding: 0 16px 64px; }
.top-bar { position: sticky; top: 0; z-index: 5; background: var(--bg); border-bottom: 1px solid var(--line); }
.top-bar .wrap { display: flex; flex-wrap: wrap; gap: 10px 18px; align-items: center; padding-top: 12px; padding-bottom: 12px; }
h1 { font-size: 22px; margin: 0; letter-spacing: -0.02em; }
h1 span { color: var(--dim); font-weight: 500; font-size: 14px; margin-left: 8px; }
nav { display: flex; gap: 6px; flex-wrap: wrap; }
nav a { text-decoration: none; color: var(--ink); background: var(--chip); padding: 5px 11px; border-radius: 999px; font-size: 14px; font-weight: 600; }
nav a small { font-weight: 400; color: var(--dim); margin-left: 5px; }
input[type=search] { flex: 1 1 180px; min-width: 0; max-width: 280px; margin-left: auto; padding: 7px 12px; border-radius: 8px;
  border: 1px solid var(--line); background: var(--panel); color: var(--ink); font: inherit; }
section { scroll-margin-top: 70px; }
.sh { display: flex; align-items: baseline; gap: 12px; margin: 34px 0 12px; }
.sh h2 { margin: 0; font-size: 20px; }
.sh p { margin: 0; color: var(--dim); }
.cnt { margin-left: auto; color: var(--dim); font-variant-numeric: tabular-nums; }
.cnt b { color: var(--ink); }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(330px, 1fr)); gap: 10px; }
@media (max-width: 420px) { .grid { grid-template-columns: 1fr; } }
.card { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; }
.card summary { list-style: none; display: flex; gap: 12px; padding: 12px; cursor: pointer; }
.card summary::-webkit-details-marker { display: none; }
.card[open] { grid-column: 1 / -1; }
.card.wip { opacity: .55; }
.card.boss { border-color: var(--boss); border-width: 2px; }
.ic { width: 96px; height: 96px; flex: none; image-rendering: pixelated; border-radius: 8px; background: var(--chip); object-fit: contain; }
.ic.none { background: repeating-linear-gradient(45deg, var(--chip), var(--chip) 6px, transparent 6px, transparent 12px); }
.hd { min-width: 0; }
.top { display: flex; gap: 8px; align-items: center; }
.id { font-size: 12px; color: var(--dim); font-variant-numeric: tabular-nums; }
.tag { font-size: 11px; color: var(--accent); border: 1px solid var(--accent); border-radius: 4px; padding: 0 5px; }
.nm { font-weight: 700; font-size: 16px; }
.mt { display: flex; flex-wrap: wrap; gap: 4px; margin: 3px 0 4px; }
.mt span { font-size: 12px; background: var(--chip); padding: 1px 7px; border-radius: 4px; color: var(--dim); }
.one { font-size: 14px; }
dl { margin: 0; padding: 0 16px 14px 120px; display: grid; grid-template-columns: 64px 1fr; gap: 6px 12px; }
@media (max-width: 560px) { dl { padding-left: 14px; grid-template-columns: 1fr; } dt { margin-top: 6px; } }
dt { font-weight: 700; color: var(--accent); font-size: 13px; }
dd { margin: 0; font-size: 14px; }
.hide { display: none; }
</style>
</head>
<body>
<div class="top-bar"><div class="wrap">
<h1>게이브 도감<span>{TOTAL}종, 들어간 것 {BUILT}</span></h1>
<nav>{NAV}</nav>
<input type="search" id="q" placeholder="이름, 생김새로 찾기">
</div></div>
<div class="wrap">{SECTIONS}</div>
<script>
const q = document.getElementById('q');
q.addEventListener('input', () => {
  const v = q.value.trim().toLowerCase();
  document.querySelectorAll('.card').forEach(c => c.classList.toggle('hide', v && !c.dataset.q.includes(v)));
});
</script>
</body>
</html>
"""

if __name__ == "__main__":
    main()
