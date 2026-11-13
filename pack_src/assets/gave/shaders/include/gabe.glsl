#ifndef GAVE_GABE_GLSL
#define GAVE_GABE_GLSL

// 게이브 몹 모델(core/item): 프레임 접기, 3블록 안 가까운 얼굴, 부위 켜고 끄기, 팔레트, 맞은 번쩍임.
// 신호는 면 틴트(custom_model_data 색) 24비트 = [1110][chk 2][pay 18]. tools/gabe/kit.py 와 kr.gave.mob.Look 이 같은 비트를 만든다.
//  pay 17 자세 면, 16 숨김, 15..14 거리(0 늘, 1 먼 면만, 2 가까운 면만)
//  자세 면: 13..11 자세 k, 10..8 자세 수 N-1, 7..4 주기 칸(GABE_PERIOD, 15 = 멈춤), 3..0 위상(주기 16분의 몇)
//  보통 면: 13..9 팔레트 비트, 8..0 맞은 번쩍임 끝 틱 mod 480(511 없음)
//  chk = (1 + pay 2비트 묶음 9개 합) & 3. 좀비런 총 신호(R 위 4비트 11..13, 15)와 흰색(0xFFFFFF)과 겹치지 않는다.
// 숨길 면은 네 꼭짓점을 모두 화면 밖 한 점으로 보내 넓이 0 이 되게 한다. 가까운 면 판정은 그 면 평면까지의 거리(같은 면의 네
// 꼭짓점이 늘 같은 값이라 한 면이 갈라지지 않음, Position 은 카메라 기준 세계 축, Normal 은 같은 축의 면 법선).

#include <minecraft:globals.glsl>

const float GABE_NEAR_TO = 3.0;
const float GABE_FAR_FROM = 2.9;

float gabe_period(int i) {
    // tools/gabe/kit.py PERIODS 와 같게(모두 24000 의 약수: GameTime 이 24000 틱마다 돌아도 끊기지 않음)
    if (i < 8) {
        return i == 0 ? 4.0 : i == 1 ? 6.0 : i == 2 ? 8.0 : i == 3 ? 10.0 : i == 4 ? 12.0 : i == 5 ? 15.0 : i == 6 ? 16.0 : 20.0;
    }
    return i == 8 ? 24.0 : i == 9 ? 30.0 : i == 10 ? 40.0 : i == 11 ? 48.0 : i == 12 ? 60.0 : i == 13 ? 80.0 : 120.0;
}

// 정점 셰이더. col 이 게이브 틴트면 흰색으로 되돌리고 look 에 (팔레트, 번쩍임 0..1, 1, 0) 을 싣는다(자세 면은 (0, 0, 0, 0)).
// 숨길 정점이면 true.
bool gabe_vertex(vec3 pos, vec3 nrm, inout vec4 col, out vec4 look) {
    look = vec4(0.0);
    ivec3 t = ivec3(round(col.rgb * 255.0));
    if ((t.r >> 4) != 14) return false;
    int pay = ((t.r & 3) << 16) | (t.g << 8) | t.b;
    int s = 1;
    for (int i = 0; i < 9; i++) {
        s += (pay >> (2 * i)) & 3;
    }
    if ((s & 3) != ((t.r >> 2) & 3)) return false;
    col = vec4(1.0, 1.0, 1.0, col.a);
    bool hide = ((pay >> 16) & 1) == 1;
    int dist = (pay >> 14) & 3;
    if (dist == 1 || dist == 2) {
        float n2 = dot(nrm, nrm);
        float d = n2 > 1.0e-4 ? abs(dot(pos, nrm)) / sqrt(n2) : length(pos);
        if (dist == 1 && d < GABE_FAR_FROM) hide = true;
        if (dist == 2 && d >= GABE_NEAR_TO) hide = true;
    }
    float T = GameTime * 24000.0;
    if (((pay >> 17) & 1) == 1) {
        int k = (pay >> 11) & 7;
        int n = ((pay >> 8) & 7) + 1;
        int pi = (pay >> 4) & 15;
        int ph = pay & 15;
        int frame;
        if (pi >= 15) {
            frame = ph * n / 16;
        } else {
            float P = gabe_period(pi);
            frame = int(floor(mod(T + float(ph) * P / 16.0, P) * float(n) / P));
            frame = clamp(frame, 0, n - 1);
        }
        if (frame != k) hide = true;
    } else {
        int pal = (pay >> 9) & 31;
        int hurt = pay & 511;
        float flash = 0.0;
        if (hurt < 480) {
            float rem = mod(float(hurt) - T, 480.0);
            if (rem <= 8.0) flash = clamp(rem, 0.0, 1.0);
        }
        look = vec4(float(pal), flash, 1.0, 0.0);
    }
    return hide;
}

// 조각 셰이더: 텍스처 화소(부류 = 파랑 아래 3비트, 0 살 1 눈 줄 2 홍조 3 입 4 흰자 5 반짝임 6 머리 덩어리 7 이)에 팔레트와 번쩍임.
// 팔레트 1 흰 눈, 2 탈색, 4 짙은 홍조, 8 무표정, 16 금빛 홍조(Look.PAL_*). lit = 이 화소에 곱해질 빛(정점 그늘 x 빛 지도):
// 번쩍임은 빛을 되갚아 그늘, 밤에도 하얗게 빛난다.
vec4 gabe_color(vec4 c, vec4 look, vec3 lit) {
    if (look.z < 0.5) return c;
    int pal = int(round(look.x));
    vec3 rgb = c.rgb;
    if (pal != 0) {
        int cls = int(round(c.b * 255.0)) & 7;
        float lum = dot(rgb, vec3(0.299, 0.587, 0.114));
        if ((pal & 8) != 0 && (cls == 1 || cls == 3 || cls == 4 || cls == 5 || cls == 7)) {
            rgb = vec3(1.0, 0.886, 0.859) * (0.97 + 0.06 * fract(sin(dot(floor(c.rg * 255.0), vec2(12.9898, 78.233))) * 43758.5453));
        } else if ((pal & 1) != 0 && (cls == 1 || cls == 5)) {
            rgb = vec3(0.95, 0.93, 0.91) * (0.85 + 0.15 * clamp(lum * 3.0, 0.0, 1.0));
        }
        if (cls == 2) {
            if ((pal & 16) != 0) rgb = vec3(0.98, 0.78, 0.30) * (0.8 + 0.25 * lum);
            else if ((pal & 4) != 0) rgb = vec3(0.70, 0.07, 0.13) * (0.85 + 0.2 * lum);
        }
        if ((pal & 2) != 0) {
            float l = dot(rgb, vec3(0.299, 0.587, 0.114));
            rgb = mix(rgb, vec3(l * 1.08 + 0.04), 0.78);
        }
    }
    if (look.y > 0.0) {
        vec3 glow = min(vec3(1.0) / max(lit, vec3(0.2)), vec3(5.0));
        rgb = mix(rgb, glow, look.y * 0.9);
    }
    return vec4(rgb, c.a);
}

#endif
