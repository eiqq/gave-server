# 게이브(Gave) 서버 소스

마인크래프트 자바 26.3(바닐라 클라이언트)으로 들어오는 Paper 26.3 협동 웨이브 미니게임. 사방에서 몰려오는 게이브를 총으로 막고 트럭 상점에서 강해지며 35라운드를 건넌다. 플러그인(Java 25)과 서버 리소스팩(모습 팩, 소리 팩)을 파이썬 도구로 만든다.

- 바로 여는 서버 꾸러미(압축 풀고 bat 하나): https://github.com/tinymita2-pixel/gave-server/releases/tag/server-2026-11-13
- 서버가 접속자에게 보내는 팩: https://github.com/tinymita2-pixel/gave-pack/releases
- 다른 사람의 AI 가 이어서 고칠 때 먼저 읽을 것: `AGENTS.md`(`CLAUDE.md` 와 같은 내용)

## 폴더 지도

| 폴더 | 내용 |
|---|---|
| `plugin/src/main/java/kr/gave/` | 플러그인 소스. 패키지와 파일 주인은 `docs/구조.md` 1절 |
| `plugin/src/main/resources/` | `plugin.yml`, 기본 `config.yml`, 도감(`dex/`, `dexpack/`), 원정 데이터(`trip/`) |
| `tools/` | 빌드(`build_all.py`, `build_plugin.sh`, `compile_wip.py`), 팩 생성기(`make_*.py`, `props/`, `gabe/`, `trip/`), 팩 묶기(`build_pack.py`), 시험(`ingame.py`, `test_server.sh`, `client_run.py`, `scenarios/`), 밸런스 모의(`balance_sim.py`), 실서버(`make_live.py`), 팩 올리기(`upload_pack.py`) |
| `data/` | 종 데이터 `bestiary.json`, HUD 한국어 글귀 `phrases/`, 효과음 목록 `sfx_lib.json`, 안개 데이터팩 `datapack/gave_env`, 원정 초안 `trip/` |
| `docs/` | `운영.md`(빌드, 시험, 실서버), `구조.md`(이름, 포트, 패키지), `게임안내.md`, `모듈/*.md`(기능별 명세), `게이브도감.md`, `소리출처.md`, `브금출처.md`, `reuse/`(좀비런에서 가져온 것), `조사/` |
| `pack_src/` | 팩 바탕(`assets/gave`, `assets/minecraft`). 생성기가 고쳐 쓰는 곳 |
| `pack_fx/`, `pack_hud/` | 셰이더 조각(glsl). `make_fx.py`, `make_hud.py` 가 `pack_src` 로 옮긴다 |
| `art/fonts/` | HUD 글귀를 그리는 Galmuri11 Bold(OFL 1.1) |
| `바탕화면/` | 원래 실서버 bat(CP949, CRLF). `tools/make_live.py` 가 만든다 |
| `ref/` | 받은 원본은 없고 출처 기록 `_info.json` 만(아래 'ref 다시 받기') |

저장소에 없는 것: `build/`(만든 팩과 jar, 시험 기록), `runtime*/`(시험 서버), `live/`(실서버), `logs/`, `ref/` 원본.

## 빌드

준비물(자세히는 `docs/운영.md` '준비물'):

- 자바 25. 도구는 `~/.jdks/temurin-25/bin` 을 쓴다(`tools/build_plugin.sh`, `tools/compile_wip.py`). 다른 곳에 깔았으면 그 줄을 고치거나 그 자리에 연결한다.
- Paper 26.3-231 을 `runtime/paper.jar` 에 두고 한 번 켠다(`runtime/libraries`, `runtime/versions` 가 플러그인 컴파일 클래스패스). 서버 꾸러미의 `paper.jar` 와 같다.
- 파이썬 3 + Pillow 11.1.0, numpy, imageio-ffmpeg. Pillow 는 11.1.0 이어야 팩 PNG 바이트가 릴리스와 같다(12.x 는 화소는 같고 압축 바이트와 도형 가장자리 몇 화소가 달라 팩 sha1 이 바뀐다). 다른 판이 깔려 있으면 `python -m venv` 로 따로 만들어 `pip install pillow==11.1.0 numpy imageio-ffmpeg` 하고 팩 생성기를 그 파이썬으로 돌린다
- 26.3 클라 jar `%APPDATA%/.minecraft/versions/26.3/26.3.jar`(공식 런처로 한 번 켬). 다른 자리면 환경 변수 `GV_CLIENT_JAR` 로 알려 준다(찾는 차례는 `tools/mcjar.py`). 바닐라 셰이더 원본 `~/.cache/mc-shader-vanilla/26.3/assets/minecraft/shaders/core` 는 `python -I -X utf8 tools/fetch_ref.py shaders` 가 그 jar 에서 꺼낸다
- 셰이더 검사기 `~/.claude/skills/minecraft-shader/scripts/check_shaders.py` 는 만든 사람 PC 의 도구라 저장소에 없다. 없으면 `tools/shader_check.py` 가 대신한다(gradle 캐시의 LWJGL shaderc 로 게임과 같은 옵션 컴파일, 그것도 없으면 건너뜀). 셰이더를 고쳤으면 게임 안 `latest.log` 도 본다.
- 팩을 다시 만들려면 `ref/` 원본(아래 'ref 다시 받기', 대부분 `python -I -X utf8 tools/fetch_ref.py`)

순서(명령은 모두 저장소 맨 위에서):

1. `python -I -X utf8 tools/make_trip.py`: 원정 층(트럭, 우주선, 표지판) `build/trip/pack_layer`. `build_all.py` 단계에 없으니 처음과 원정 소품을 바꿨을 때 먼저 돌린다(없으면 팩에서 빠짐).
2. `python -X utf8 tools/build_all.py`: props → gcb → pack_src → shop_ui → fx → gabe → director → bgm → hud → hud_promote → pack → shaders → layer → dex → real → parity → plugin. 결과 `build/Gave-pack.zip`, `build/Gave-sound.zip`, `build/Gave.jar`. 실패하면 그 단계에서 멈추고, 고친 뒤 `--from <단계>`. `--skip`, `--list`.
3. 플러그인만 고칠 때는 `ref/` 없이 `bash tools/build_plugin.sh` 만으로 된다. 팩은 gave-pack 릴리스의 두 zip 을 `build/` 에 둔다(플러그인이 팩 안 `music.json`, `gabe_looks.json` 을 읽으므로 팩이 꼭 있어야 함).

시험: `python -X utf8 tools/ingame.py --name <영역> --scenario tools/scenarios/<대본>.json`(시험 서버를 켜고 시험 클라로 대본을 돈 뒤 끔). 따로 컴파일은 `python -X utf8 tools/compile_wip.py --name <영역> [--promote]`. 포트와 잠금은 `docs/운영.md` '시험 도구'.

## ref 다시 받기

대부분은 `python -I -X utf8 tools/fetch_ref.py [mods] [sfx] [bgm] [skin] [shaders]` 가 아래 기록(`_info.json`)의 주소에서 받아 해시를 맞추고 생성기가 읽는 자리에 꺼낸다(이미 있고 해시가 맞으면 다시 받지 않음). 못 받은 것은 `build/pipeline/dl/사람이_받을_것.txt` 에 남는다. AI 에이전트의 접근을 막은 곳(opengameart.org, kenney.nl)은 사람이 받는다: opengameart 는 사람이 자기 터미널에서 `--person` 을 붙여 돌리고, kenney 는 브라우저로 받아 `build/pipeline/dl/` 아래에 둔다(그 도구 머리 주석).

| 자리 | 쓰는 도구 | 다시 받는 길 |
|---|---|---|
| `ref/mods/timeless-and-classics-guns`, `just-enough-guns`, `scorched-guns-2`, `tac-gear` | `tools/props/build_props.py`, `build_more.py`, `build_arms.py` | 각 `_info.json` 의 `file_url` 에서 jar 를 받아 `sha512` 를 맞추고 `python -I -X utf8 tools/props/extract_arms.py <jar 폴더>`. 처음 꺼낸 도구(좀비런 `tools/props/fetch_modrinth.py`, `extract_sources.py`)는 이 저장소에 없다 |
| `ref/mods/the-car-engine` | `tools/trip/truck.py` | `python -I -X utf8 tools/trip/fetch_tce.py` |
| `ref/mods/stellaris` | `tools/trip/launch.py` | `python -I -X utf8 tools/trip/fetch_stellaris.py` |
| `ref/mods/trafficcraft`, `trafficcraft-padlamp`, `mrcrayfish-vehicle` | `tools/trip/route.py`, `padlamp.py`, `special_a.py` | `_info.json` 의 모드 주소에서 같은 판 jar 를 받아 `extracted/` 에 적힌 파일만 꺼낸다 |
| `ref/sfx/<묶음>` | `tools/gabe/audio.py`, `make_director_sounds.py`, `make_bgm.py`, `props/build_more.py` | `docs/소리출처.md` 표(글 페이지, 라이선스, sha256)와 묶음마다 `_info.json`(받은 주소, 파일 sha256). 받는 스크립트는 없고 손으로 받아 소리 파일만 꺼낸다. 목록 `data/sfx_lib.json` 은 `python -X utf8 tools/gabe/audio.py lib` 가 다시 쓴다 |
| `ref/bgm/incompetech`, `ref/bgm/oga` | `tools/make_bgm.py` | `docs/브금출처.md`(곡 페이지), `ref/bgm/_info.json`(sha256) |
| `ref/_Gave_skin.png` | `tools/gabe/faces.py` | 마인크래프트 플레이어 `_Gave` 의 64x64 공개 스킨 |
| `ref/gcb/extracted` | `tools/props/build_gcb.py` | 공개 주소 없음. 다른 서버(GCB)의 리소스팩 폴더가 있으면 `python -I -X utf8 tools/props/gcb_import.py <그 폴더>`, 없으면 그 서버 운영자에게서 `ref/gcb/extracted`(파일 6개, `_info.json` 의 sha256)를 받는다. 없어도 빌드는 되고 그 총 셋(마스티프, 렘페이지, 볼트 피스톨)만 빠진다 |

`docs/소품출처.md` 는 이 저장소에 없다(좀비런 프로젝트 문서). 모드 소품의 출처와 라이선스는 `ref/mods/*/_info.json` 과 팩 `CREDITS.txt`(`tools/props/build_props.py` 의 `write_credits` 가 씀)에 있다.

## 실서버 꾸러미 만들기

- `python -X utf8 tools/make_live.py`: `runtime/` 을 바탕으로 `live/`(포트 25565, 팩 8088, `enforce-secure-profile=false`, `accepts-transfers=true`, RCON 끔)와 바탕화면 bat 을 만든다. 운영자는 비워 두니 서버 창에서 `op <닉네임>`.
- 팩을 바꾸면 `python -X utf8 tools/upload_pack.py --dry-run` 으로 본 뒤 `--public` 으로 팩 저장소 릴리스(`pack-<sha1 앞 12자리>`)에 올린다. 서버는 `-Dgave.packRepo=<저장소>` 가 있으면 그 릴리스 주소를, 없거나 다르면 이 PC 의 `-Dgave.packPort` 로 보낸다.
- 남에게 줄 꾸러미는 릴리스 `server-2026-11-13` 꼴: `live/` 에서 `cache`, `libraries`, `versions`, `logs`, 플레이어 기록을 빼고, 두 팩을 `pack/` 에 넣어 `-Dgave.packDir=pack -Dgave.datapackDir=datapack/gave_env` 로 켠다.

## 출처와 라이선스

- 팬이 만든 비상업 프로젝트다. Mojang, Microsoft 와 관계없다(NOT AN OFFICIAL MINECRAFT PRODUCT). 후원, 유료 기능, 광고 수익과 엮지 않는다.
- 팩에 들어가는 남의 자료와 조건은 팩 `CREDITS.txt` 와 `docs/운영.md` '배포할 때': Timeless and Classics Guns(CC BY-NC-SA 4.0 과 GPL-3.0), TAC GEAR(MIT), 그 밖의 모드(`ref/mods/*/_info.json`), Kevin MacLeod 와 OpenGameArt.org 음악(CC BY, CC0), Kenney, OpenGameArt.org 효과음(CC0, CC BY), Galmuri11(OFL 1.1).
- 게이브 몹 얼굴은 마인크래프트 플레이어 `_Gave` 의 스킨 얼굴을 본뜬 것이다. 팩의 총, 수류탄 소리 가운데 45개는 출처가 확인되지 않았다. 권리자가 요청하면 내린다.
- 이 저장소의 코드와 도구에는 아직 라이선스 파일이 없다(정하는 것은 저장소 주인 몫). 팩에 든 모드 자료를 다시 나눌 때는 위 조건(비상업, 같은 조건, GPL 전문과 수정 고지)을 따른다.
