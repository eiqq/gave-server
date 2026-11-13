#ifndef GAVE_HUD_FRAG_GLSL
#define GAVE_HUD_FRAG_GLSL

#include <minecraft:globals.glsl>
#include <gave:hud_spec.glsl>

// 게이브 화면 HUD 조각 쪽(hud.glsl 짝, 원본 pack_hud/hud_frag.glsl). Sampler0, ColorModulator 는 text.fsh 가 이미 선언했다.
// 모양 글리프 텍셀(tools/make_hud.py shapes):
//   막대(코드 0..63 = 길이 칸): R = 그 열 가운데의 막대 안 자리(0..255), G = 역할(0 테두리, 1 밝은 안쪽, 2 어두운 안쪽, 3 빈 여백)
//   고리 A(64..127), 고리 B(128..191), 적중(192), 점(193), 무한(194), 방향 화살표(195): R, G = 글리프 안 텍셀 자리(ZR_SHAPE_TEX 칸)
//   단색 네모(196): 칸 색 대신 G = 팔레트 번호(ZR_PAL), 가장 가까운 텍셀(판, 띠, 밑줄을 2^k 조각으로 이어도 틈이 없음)
//   B = 코드, 길이 칸(아래 6비트)은 시간 모양의 전체 길이 ZR_DUR[칸]

// 날카로운 쌍선형: 4배 텍셀 그림은 GUI 4 에서 그대로, 2, 3 처럼 줄 때는 쌍선형, 늘릴 때는 텍셀 경계만 한 픽셀 폭으로 섞는다
// (eternalmc emchud_sample). 글꼴 아틀라스는 글리프 사이를 1 텍셀 띄운다(26.3 FontTexture)
vec4 zrhud_sample(vec2 uv) {
    ivec2 size = textureSize(Sampler0, 0);
    vec2 p = uv * vec2(size) - 0.5;
    vec2 i = floor(p);
    vec2 f = p - i;
    vec2 s = 1.0 / max(fwidth(p), vec2(1e-4));
    f = clamp((f - 0.5) * max(s, vec2(1.0)) + 0.5, 0.0, 1.0);
    ivec2 a = clamp(ivec2(i), ivec2(0), size - 1);
    ivec2 b = clamp(ivec2(i) + 1, ivec2(0), size - 1);
    vec4 c00 = texelFetch(Sampler0, a, 0);
    vec4 c10 = texelFetch(Sampler0, ivec2(b.x, a.y), 0);
    vec4 c01 = texelFetch(Sampler0, ivec2(a.x, b.y), 0);
    vec4 c11 = texelFetch(Sampler0, b, 0);
    return mix(mix(c00, c10, f.x), mix(c01, c11, f.x), f.y);
}

// 화면 덮개 조각용: 같은 쌍선형이되 이 조각의 텍셀 안에서만 섞는다(이웃 글리프, 아틀라스 틈을 섞으면 조각 이음매에 줄이 보임).
// tile = 조각 안 자리(0..1, 정점에서 넘김). 조각 텍셀 크기는 텍스처 좌표와 조각 자리의 화면 미분 비로 구한다(선형이라 정확)
vec4 zrhud_sample_tile(vec2 uv, vec2 tile) {
    ivec2 size = textureSize(Sampler0, 0);
    vec2 p = uv * vec2(size);
    vec2 dt = vec2(dFdx(tile.x), dFdy(tile.y));
    vec2 dp = vec2(dFdx(p.x), dFdy(p.y));
    vec2 tsz = max(floor(abs(dp) / max(abs(dt), vec2(1e-6)) + 0.5), vec2(1.0));
    vec2 org = floor(p - tile * tsz + 0.5);
    vec2 q = clamp(p, org + 0.5, org + tsz - 0.5) - 0.5;
    vec2 i = floor(q);
    vec2 f = q - i;
    vec2 s = 1.0 / max(fwidth(p), vec2(1e-4));
    f = clamp((f - 0.5) * max(s, vec2(1.0)) + 0.5, 0.0, 1.0);
    ivec2 lo = clamp(ivec2(org), ivec2(0), size - 1);
    ivec2 hi = clamp(ivec2(org + tsz) - 1, ivec2(0), size - 1);
    ivec2 a = clamp(ivec2(i), lo, hi);
    ivec2 b = clamp(ivec2(i) + 1, lo, hi);
    vec4 c00 = texelFetch(Sampler0, a, 0);
    vec4 c10 = texelFetch(Sampler0, ivec2(b.x, a.y), 0);
    vec4 c01 = texelFetch(Sampler0, ivec2(a.x, b.y), 0);
    vec4 c11 = texelFetch(Sampler0, b, 0);
    return mix(mix(c00, c10, f.x), mix(c01, c11, f.x), f.y);
}

// 부호 거리 d(안쪽 음수)를 한 픽셀 폭 w 로 부드럽게 덮는 몫
float zrhud_cov(float d, float w) {
    return clamp(0.5 - d / max(w, 1e-5), 0.0, 1.0);
}

// 곱하지 않은 알파끼리 top 을 bottom 위에
vec4 zrhud_over(vec4 top, vec4 bottom) {
    float a = top.a + bottom.a * (1.0 - top.a);
    if (a < 1e-4) {
        return vec4(0.0);
    }
    return vec4((top.rgb * top.a + bottom.rgb * bottom.a * (1.0 - top.a)) / a, a);
}

// 고리 모양 하나: cc = 가운데 기준 -1..1, px = cc 단위 한 픽셀, fill = 12시부터 시계 방향으로 칠할 몫
vec4 zrhud_ring(vec2 cc, float px, float r0, float r1, float fill, vec4 on) {
    float r = length(cc);
    float d = max(r0 - r, r - r1);
    float band = zrhud_cov(d, px);
    float edge = zrhud_cov(d - px * 1.5, px);
    // 화면 y 는 아래로 자라므로 atan(x, -y) 가 12시 0 에서 시계 방향으로 는다
    float ang = atan(cc.x, -cc.y) * 0.15915494;
    ang = ang < 0.0 ? ang + 1.0 : ang;
    float lit = fill >= 0.999 ? 1.0 : (fill <= 0.001 ? 0.0 : zrhud_cov((ang - fill) * 6.2831853 * max(r, 0.2), px));
    vec4 col = mix(ZR_TRACK, on, lit);
    return zrhud_over(vec4(col.rgb, col.a * band), vec4(ZR_EDGE.rgb, ZR_EDGE.a * edge));
}

// 이등변 세모 부호 거리(꼭짓점이 원점, q = (밑변 반, 높이), +y 쪽으로 벌어짐. iq sdTriangleIsosceles)
float zrhud_tri(vec2 p, vec2 q) {
    p.x = abs(p.x);
    vec2 a = p - q * clamp(dot(p, q) / dot(q, q), 0.0, 1.0);
    vec2 b = p - q * vec2(clamp(p.x / q.x, 0.0, 1.0), 1.0);
    float s = -sign(q.y);
    vec2 d = min(vec2(dot(a, a), s * (p.x * q.y - p.y * q.x)), vec2(dot(b, b), s * (p.y - q.y)));
    return -sqrt(d.x) * sign(d.y);
}

// hud = (종류, 채움 또는 남은 틱, 칸 방식, 덮개 조각 y). 칸 방식 비트: 1 남은 몫으로 채움(줄어듦), 2 깜빡임(정점에서), 4 끝나면 숨김,
// 8 덮개 조각(조각 안에서만 섞기), 32 막대 빈 몫 안 그림
vec4 zrhud_fragment(vec2 uv, vec4 vcolor, vec4 hud) {
    int mode = int(hud.z + 0.5);
    if (hud.x < 1.5) {
        vec4 col = ((mode & 8) != 0 ? zrhud_sample_tile(uv, hud.yw) : zrhud_sample(uv)) * vcolor * ColorModulator;
        if (col.a < 0.02) {
            discard;
        }
        return col;
    }
    ivec2 size = textureSize(Sampler0, 0);
    vec2 p = uv * vec2(size);
    vec2 fw = fwidth(p);
    vec4 t = texelFetch(Sampler0, clamp(ivec2(floor(p)), ivec2(0), size - 1), 0);
    if (t.a < 0.5) {
        discard;
    }
    int code = int(t.b * 255.0 + 0.5);
    if (code == 196) {
        return ZR_PAL[clamp(int(hud.y * 255.0 + 0.5), 0, ZR_PAL_COUNT - 1)] * ColorModulator;
    }
    float fill = hud.y;
    float life = 1.0;
    if (hud.x > 2.5) {
        float dur = code >= 192 ? ZR_HIT_TICKS : ZR_DUR[code & 63];
        float left = clamp(hud.y / dur, 0.0, 1.0);
        fill = (mode & 1) != 0 ? left : 1.0 - left;
        life = left;
        if ((mode & 4) != 0 && hud.y <= 0.0) {
            discard;
        }
    }
    if (code < 64) {
        // 막대(바닐라 보스 막대 꼴): 테두리, 채운 몫은 칸 색, 빈 몫은 어두운 바탕(NOTRACK 이면 빈 몫을 비움: 밑 막대가 보임)
        int role = int(t.g * 255.0 + 0.5);
        bool lit = t.r < fill;
        vec4 col;
        if (role >= 3) {
            discard;
        } else if (!lit && (mode & 32) != 0) {
            discard;
        } else if (role == 0) {
            col = ZR_EDGE;
        } else if (lit) {
            col = vec4(vcolor.rgb * (role == 2 ? 0.74 : 1.0), vcolor.a);
        } else {
            col = ZR_TRACK;
        }
        return col * ColorModulator;
    }
    vec2 l = (floor(t.rg * 255.0 + 0.5) + fract(p)) / ZR_SHAPE_TEX;
    vec2 cc = l * 2.0 - 1.0;
    float px = 2.0 * max(fw.x, fw.y) / ZR_SHAPE_TEX;
    vec4 col;
    if (code < 128) {
        col = zrhud_ring(cc, px, ZR_RING_A_IN, ZR_RING_A_OUT, fill, vcolor);
    } else if (code < 192) {
        col = zrhud_ring(cc, px, ZR_RING_B_IN, ZR_RING_B_OUT, fill, vcolor);
    } else if (code == 192) {
        // 적중 표시: 대각선 네 줄, 지나며 조금 벌어지고 옅어진다
        float grow = 1.0 - life;
        float a0 = 0.46 + 0.10 * grow;
        float a1 = 0.90 + 0.06 * grow;
        float w = 0.085;
        vec2 q = abs(vec2(cc.x + cc.y, cc.x - cc.y) * 0.70710678);
        float d1 = max(q.y - w, max(a0 - q.x, q.x - a1));
        float d2 = max(q.x - w, max(a0 - q.y, q.y - a1));
        float d = min(d1, d2);
        col = zrhud_over(vec4(vcolor.rgb, vcolor.a * zrhud_cov(d, px)), vec4(ZR_EDGE.rgb, ZR_EDGE.a * 0.8 * zrhud_cov(d - px * 1.2, px)));
        col.a *= smoothstep(0.0, 0.45, life);
    } else if (code == 193) {
        // 점
        float d = length(cc) - 0.62;
        col = zrhud_over(vec4(vcolor.rgb, vcolor.a * zrhud_cov(d, px)), vec4(ZR_EDGE.rgb, ZR_EDGE.a * zrhud_cov(d - 0.3, px)));
    } else if (code == 194) {
        // 무한(권총 예비탄): 맞닿은 고리 둘
        vec2 e = vec2(abs(cc.x) - 0.45, cc.y);
        float d = abs(length(e) - 0.40) - 0.11;
        col = zrhud_over(vec4(vcolor.rgb, vcolor.a * zrhud_cov(d, px)), vec4(ZR_EDGE.rgb, ZR_EDGE.a * 0.7 * zrhud_cov(d - px * 1.2, px)));
    } else if (code == 195) {
        // 방향 화살표: 위를 가리키는 화살촉(밑을 파낸 세모, 정점이 각도만큼 돌려 타원 둘레에 놓음), 어두운 테두리
        float body = zrhud_tri(cc - vec2(0.0, -0.86), vec2(0.62, 1.56));
        float notch = zrhud_tri(cc - vec2(0.0, 0.22), vec2(1.30, 0.94));
        float d = max(body, -notch) - 0.04;
        col = zrhud_over(vec4(vcolor.rgb, vcolor.a * zrhud_cov(d, px)), vec4(ZR_EDGE.rgb, ZR_EDGE.a * zrhud_cov(d - px * 1.6, px)));
    } else {
        discard;
    }
    if (col.a < 0.01) {
        discard;
    }
    return col * ColorModulator;
}

#endif
