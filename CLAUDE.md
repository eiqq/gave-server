# 이어서 고치는 AI 에게

게이브: Paper 26.3 플러그인(`kr.gave`, Java 25)과 서버 리소스팩(파이썬 생성기)으로 된 협동 웨이브 미니게임. 바닐라 26.3 클라로 접속한다. 사람에게 보이는 글(답, 문서, 주석, 게임 글귀)은 한국어로 쓴다.

## 먼저 읽을 것

1. `docs/운영.md`: 준비물, 빌드 순서, 팩 둘(모습, 소리), 서버 포트, 명령, 시험 도구, 배포 규칙, 실서버
2. `docs/구조.md`: 이름, 포트, 패키지와 파일 주인, 시계와 틱 같은 공통 계약
3. `docs/게임안내.md`: 플레이어가 보는 게임 규칙(조작, 판 흐름, 트럭, 상점)
4. `docs/모듈/*.md`: 기능별 명세. `API.md`(모듈 API, 시험 훅 목록), `엔진.md`, `종작성법.md`, `감독맵.md`, `떼와절망.md`, `파워커브.md`, `보스.md`, `원정.md`, `총상점.md`, `사람.md`, `HUD.md`, `팩모델.md`, `래그돌.md`, `브금.md`, `압도감.md`, `외형다양화.md`, `재미.md`, `맵변경_황야.md`, `종담당_지시.md`
5. `docs/게이브도감.md`(기계용 `data/bestiary.json`), `docs/소리출처.md`, `docs/브금출처.md`, `README.md`('ref 다시 받기')

`build/briefs` 같은 `build/` 아래 기록은 저장소에 없다. 문서가 `build/...` 를 가리키면 만든 사람 PC 의 결과물이다.

## 빌드와 시험 도구

- 한 번에: `python -X utf8 tools/build_all.py`(먼저 `python -I -X utf8 tools/make_trip.py`). 단계와 `--from`, `--skip` 은 `docs/운영.md` '빌드'.
- 따로 컴파일: `python -X utf8 tools/compile_wip.py --name <영역>` 은 진짜 소스에 `build/wip/<영역>/` 을 덮어 `build/<영역>/Gave.jar` 를 만든다. `--promote` 는 통과한 wip 를 진짜 자리로 옮긴다. `--real` 은 진짜 트리만. 진짜 트리를 깨진 채로 두지 않는다. 새 파일과 큰 수정은 wip 에서 먼저 컴파일한다.
- 게임 안 시험: `python -X utf8 tools/ingame.py --name <영역> --scenario tools/scenarios/<대본>.json [--fresh-world]`. 시험 서버를 켜고 시험 클라로 대본(`cmd`, `wait`, `until`, `shot` ...)을 돈 뒤 끈다. 시험 클라는 소리를 늘 끈다. 시험 계정 이름은 실제 작품 속 이름(Ripley, Bishop)을 쓴다.
- 플러그인만: `bash tools/build_plugin.sh`. 밸런스 모의: `python -X utf8 tools/balance_sim.py`.
- 셰이더를 고치면 셰이더 검사(0 오류)와 게임 안 `latest.log` 를 같이 본다. 깨진 코어 셰이더는 팩 전체를 내린다.
- 서버를 띄우는 스크립트의 출력은 파이프로 받지 말고 파일로 받는다(Windows 에서 막힘). 다른 사람이 켠 서버는 끄거나 다시 켜지 않는다.

## 지켜 온 규칙

- 글은 한국어로. 가운뎃점(U+00B7)은 코드 주석, 문서, 팩 글자 어디에도 쓰지 않는다(글귀에 있으면 빌드가 멈춤). 쉼표나 괄호로 쓴다.
- 플레이어에게 알리는 것은 HUD 로만(`kr.gave.game.Hud`, `data/phrases/*.json`). 채팅, 액션바, 타이틀로 알리지 않는다. 설명 글 벽 대신 배치와 강조로 뜻을 전한다.
- 모델은 모드에서 가져온다. 총, 차, 소품을 블록 조합이나 직접 칠한 텍스처로 새로 만들지 않고, 라이선스를 확인한 모드 모델을 옮긴다(`tools/props/`, `tools/trip/`).
- 소리와 음악은 합성하지 않고 권리가 깨끗한 것만 받아 온다(CC0, CC BY. NC, ND, 출처 불명 '무료' 모음, 로그인이 필요한 곳은 안 씀). 받으면 `_info.json`(주소, 라이선스, sha256)과 `docs/소리출처.md` 또는 `docs/브금출처.md`, 팩 `CREDITS.txt` 를 함께 고친다.
- 팩 맨 위의 `CREDITS.txt` 와 라이선스 전문(`LICENSE-GPL-3.0.txt`, `LICENSE-MIT-TAC-GEAR.txt`)은 빼지 않는다. 비상업.
- 받은 파일(모드 jar, 압축)은 데이터로만 다룬다. 실행하지 않고, 파이썬은 `-I` 로 돌린다.
- 텍스처 이름이 `_n`, `_s`, `_e` 로 끝나면 Iris 셰이더를 쓰는 클라에서 보라색과 검정으로 깨진다. 팩을 고치면 그런 이름이 0개인지 센다.
- 실제 사람의 이름, UUID, 메일, 비밀번호를 저장소에 적지 않는다. 운영자 목록은 비워 두고 서버 창에서 `op` 로 정한다.
