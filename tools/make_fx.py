"""사격, 폭발 셰이더 연출 층을 pack_src 에 만든다(26.3 전용).

python -X utf8 tools/make_fx.py
- 표식 텍스처 assets/gave/textures/item/fx_marker.png (64x64, 화소 (x*4+1, y*4+1, 197, 254))
- 모델: fx_quad(양면 정사각형, 판 z=8), fx_tracer(길이 z 8..24, 십자 두 판), fx_flat(수평 판)
- 아이템 정의: 정점 색 = custom_model_data 색 0번(종류, 씨앗, 시작 틱)
- core/item.vsh, item.fsh: 바닐라 26.3 파일(사용자 jar 에서 뽑은 것)에 작은 고리를 단다. 우리 표식 면만 pack_fx/zrfx.glsl 이 그린다
  (fsh zr_fx). vsh 는 1인칭 총 몸짓 zr_kick: 총 몸통(데이터 틴트), 총구 불꽃 정점을 쏜 뒤, 꺼낼 때, 재장전 동안 움직인다.
  게이브 몹 모델은 pack_fx/gabe.glsl(vsh gabe_vertex: 프레임 접기, 가까운 얼굴, 부위 숨김 / fsh gabe_color: 팔레트, 맞은 번쩍임,
  varying 8 gvLook). 고친 뒤에는 check_shaders.py pack_src --mc 26.3 을 손으로 돌린다
총 아이템 정의, 총구 불꽃 모델, 몸짓 틴트를 받는 총 몸통 모델은 tools/build_pack.py 가 묶을 때 소품 층 총 모델에서 만든다
(몸짓 틴트는 1인칭 자리에서만 들어가서 남의 3인칭 총은 움직이지 않는다).
"""
import json
import shutil
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "pack_src"
VAN = Path.home() / ".cache/mc-shader-vanilla/26.3/assets/minecraft/shaders/core"
NS = OUT / "assets/gave"


def marker():
    im = Image.new("RGBA", (64, 64))
    px = im.load()
    for y in range(64):
        for x in range(64):
            px[x, y] = (x * 4 + 1, y * 4 + 1, 197, 254)
    p = NS / "textures/item/fx_marker.png"
    p.parent.mkdir(parents=True, exist_ok=True)
    im.save(p)


def face(rot=0):
    f = {"uv": [0, 0, 16, 16], "texture": "#fx", "tintindex": 0}
    if rot:
        f["rotation"] = rot
    return f


def models():
    tex = {"fx": "gave:item/fx_marker", "particle": "gave:item/fx_marker"}
    quad = {"textures": tex, "elements": [{"from": [0, 0, 8], "to": [16, 16, 8], "shade": False,
                                           "faces": {"north": face(), "south": face()}}]}
    tracer = {"textures": tex, "elements": [
        {"from": [4, 8, 8], "to": [12, 8, 24], "shade": False, "faces": {"up": face(), "down": face()}},
        {"from": [8, 4, 8], "to": [8, 12, 24], "shade": False, "faces": {"east": face(90), "west": face(90)}}]}
    flat = {"textures": tex, "elements": [{"from": [0, 8, 0], "to": [16, 8, 16], "shade": False,
                                           "faces": {"up": face(), "down": face()}}]}
    for name, m in (("fx_quad", quad), ("fx_tracer", tracer), ("fx_flat", flat)):
        p = NS / f"models/item/{name}.json"
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(m, indent=1), encoding="utf-8")
        d = NS / f"items/{name}.json"
        d.parent.mkdir(parents=True, exist_ok=True)
        d.write_text(json.dumps({"model": {"type": "minecraft:model", "model": f"gave:item/{name}",
                                           # 기본 0 = 종류 0(안 그림, zrfx.glsl 맨 끝 가지). 16777215 는 종류 15 변형 3(레일 광선)으로 읽혀 색 없는 fx 아이템이 200초마다 번쩍인다
                                           "tints": [{"type": "minecraft:custom_model_data", "index": 0, "default": 0}]}},
                                indent=1), encoding="utf-8")


def patch(text, anchor, new, count=1):
    if text.count(anchor) != count:
        raise SystemExit(f"고리 자리를 못 찾음({text.count(anchor)}): {anchor!r}")
    return text.replace(anchor, new)


def shaders():
    vsh = (VAN / "item.vsh").read_text(encoding="utf-8")
    fsh = (VAN / "item.fsh").read_text(encoding="utf-8")
    vsh = patch(vsh, "layout(location = 5) out vec2 texCoord0;", "layout(location = 5) out vec2 texCoord0;\nlayout(location = 7) out vec4 zrTint;"
                "\nlayout(location = 8) out vec4 gvLook;")
    vsh = patch(vsh, "    texCoord0 = UV0;", "    texCoord0 = UV0;\n    zrTint = Color;")
    # 1인칭 총 반동: 총 몸통, 총구 불꽃 정점을 도장 나이만큼 뒤로, 위로(zrfx.glsl zr_kick), 몸통 데이터 틴트는 흰색으로
    vsh = patch(vsh, "#include <minecraft:sample_lightmap.glsl>", "#include <minecraft:sample_lightmap.glsl>\n#include <gave:zrfx.glsl>"
                "\n#include <gave:gabe.glsl>")
    # 26.3 의 1인칭 아이템 정점(Position)은 카메라 기준 세계 축이고 카메라 회전은 ModelViewMat 에 있다. 움직임은 눈 공간
    # (ModelViewMat * Position: x 오른쪽, y 위, -z 앞)에서 줘야 화면 기준 뒤로, 위로가 된다
    # 게이브 몹(pack_fx/gabe.glsl): 게이브 틴트 면은 프레임 접기, 가까운 얼굴, 부위 숨김을 하고 색을 흰색으로 되돌린 뒤(zr_kick 은 흰색을
    # 무시함) 팔레트, 번쩍임을 gvLook 으로 fsh 에 넘긴다. 숨길 면은 네 꼭짓점을 화면 밖 한 점으로
    vsh = patch(vsh, "    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);",
                "    vec4 zrEye = ModelViewMat * vec4(Position, 1.0);\n    vec4 zrCol = Color;\n"
                "    bool gvHide = gabe_vertex(Position, Normal, zrCol, gvLook);\n    zr_kick(zrEye.xyz, zrCol);\n"
                "    gl_Position = gvHide ? vec4(2.0, 2.0, 2.0, 1.0) : ProjMat * zrEye;")
    vsh = patch(vsh, "vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);",
                "vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, zrCol);")
    fsh = patch(fsh, "#include <minecraft:oit.glsl>", "#include <minecraft:oit.glsl>\n#include <gave:zrfx.glsl>\n#include <gave:gabe.glsl>")
    fsh = patch(fsh, "layout(location = 5) in vec2 texCoord0;", "layout(location = 5) in vec2 texCoord0;\nlayout(location = 7) in vec4 zrTint;"
                "\nlayout(location = 8) in vec4 gvLook;")
    hook = """    vec4 color = texture(Sampler0, texCoord0);
    float zrLit;
    vec4 zr = zr_fx(Sampler0, texCoord0, zrTint, zrLit);
    if (zr.a >= 0.0) {
        if (zr.a < 0.01) {
            discard;
        }
        #ifdef OIT_ALPHA_ONLY
        executeAlphaOnlyPhase(gl_FragCoord.z, zr.a);
        #else
        zr.rgb *= mix(vec3(1.0), lightMapColor.rgb, zrLit);
        #ifdef OIT_ACCUMULATE
        zr = sampleColorForAccumulation(zr);
        vec4 zrFog = vec4(FogColor.rgb * zr.a, FogColor.a);
        #else
        vec4 zrFog = FogColor;
        #endif
        fragColor = apply_fog(zr, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, zrFog);
        #endif
        return;
    }
    #ifdef OIT_ALPHA_ONLY
    color = gabe_color(color, gvLook, vec3(1.0));
    #else
    color = gabe_color(color, gvLook, vertexColor.rgb * ColorModulator.rgb * lightMapColor.rgb);
    #endif"""
    fsh = patch(fsh, "    vec4 color = texture(Sampler0, texCoord0);", hook)
    core = OUT / "assets/minecraft/shaders/core"
    core.mkdir(parents=True, exist_ok=True)
    (core / "item.vsh").write_text(vsh, encoding="utf-8")
    (core / "item.fsh").write_text(fsh, encoding="utf-8")
    inc = NS / "shaders/include"
    inc.mkdir(parents=True, exist_ok=True)
    # pack_fx 의 include 원본을 모두 옮긴다(zrfx.glsl 과, 게이브 몹 정점 함수처럼 나중에 더하는 include)
    for src in sorted((ROOT / "pack_fx").glob("*.glsl")):
        shutil.copy2(src, inc / src.name)


if __name__ == "__main__":
    marker()
    models()
    shaders()
    print("연출 층: 표식, 모델 3, 아이템 정의 3, core/item 고리")
