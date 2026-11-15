"""먼 저음 층 far.*(보탠 크리처 소리, 2026-11-14 개수)를 tools/gabe/flesh.py FAR 표로 구워 팩 바탕 pack_src 에 넣는다.

python -X utf8 tools/make_far_sounds.py           굽기(그 뒤 build_pack.py)
python -X utf8 tools/make_far_sounds.py --check   자가 점검만(가까운 공용 사건 NEAR 와 먼 저음 FAR 모두, data/gabe_voice.json 표). 아무것도 안 씀

- 지평선 너머 떼가 한 목소리처럼 내는 낮은 울음. 듣는 귀에서 브금보다 11~13 LU 아래(가만히 집중해야 들림). 파일은 통합 -23 LUFS 이고 듣는 크기는
  Java 가 정한다(kr.gave.game.Horizon.farTick, config.yml sfx.far, sfx.farVolume). 사건과 쓰는 때는 docs/모듈/압도감.md '먼 저음 층'.
- 22050 Hz(500 Hz 아래뿐), 스트림(정적 채널이 총소리로 찰 때도 빠지지 않게). call, answer, giant 는 mono(방향 있음), swell, breath 는
  stereo(방향과 거리 감쇠 없이 그대로 남). 자막은 방향이 있는 셋에만.
- 쓰는 곳: pack_src/assets/gave/sounds/far/*.ogg(이 도구가 통째로 다시 씀), pack_src/assets/gave/sounds.json 의 far.* 키,
  lang/ko_kr.json, en_us.json 의 subtitles.gave.far.* 키(다른 키는 그대로).
- 받은 소리 가공 변형(rec_far_*)은 감독 소리가 팩 바탕에 넣은 CC0 허밍(sounds/sfx/choir_*)을 푼다: tools/make_director_sounds.py 다음에 돌린다.
"""
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from gabe import audio, flesh, kit  # noqa: E402
from make_director_sounds import ASSETS, NS, merge_json  # noqa: E402


def check():
    bad = flesh.check(flesh.NEAR) + flesh.check(flesh.FAR)
    kit.voice_aliases("")  # 종 표를 읽으며 검사(틀리면 여기서 멈춤)
    print(f"점검: 가까운 사건 {len(flesh.NEAR)}, 먼 저음 {len(flesh.FAR)}, 실패 {len(bad)} {bad}. 종 표 {len(kit._VOICE)}줄 통과")
    return not bad


def main():
    if "--check" in sys.argv:
        sys.exit(0 if check() else 1)
    out, sounds, lang = {}, {}, {}
    for name, e in flesh.FAR.items():
        entries = []
        for i, (fn, seed) in enumerate(e["variants"]):
            x = flesh.resample(flesh.master(fn(seed), e), flesh.FAR_SR)
            rel = f"far/{name}_{i}"
            out[rel] = audio.ogg_bytes(x, sr=flesh.FAR_SR)  # 모두 굽고 나서 한꺼번에 씀(중간에 실패하면 pack_src 는 그대로)
            entries.append({"name": f"{NS}:{rel}", "volume": 1.0, "pitch": 1.0, "attenuation_distance": e["att"], "stream": True})
        sounds[f"far.{name}"] = {"sounds": entries}
        if e["sub"]:
            key = f"subtitles.{NS}.far.{name}"
            sounds[f"far.{name}"]["subtitle"] = key
            lang[key] = e["sub"]
        print(f"far.{name:8s} 변형 {len(entries)}  {sum(len(out[s['name'].split(':', 1)[1]]) for s in entries) / 1024:6.1f} KB  {e['sub'] or '(자막 없음)'}")
    d = ASSETS / "sounds/far"
    shutil.rmtree(d, ignore_errors=True)
    d.mkdir(parents=True)
    for rel, b in out.items():
        (ASSETS / "sounds" / f"{rel}.ogg").write_bytes(b)
    merge_json(ASSETS / "sounds.json", sounds, ("far.",))
    for lg in ("ko_kr", "en_us"):
        merge_json(ASSETS / f"lang/{lg}.json", lang, (f"subtitles.{NS}.far.",))
    print(f"먼 저음 사건 {len(sounds)}개, 파일 {len(out)}개, {sum(map(len, out.values())) / 1048576:.2f} MB → {d}, sounds.json, lang")


if __name__ == "__main__":
    main()
