#ifndef GAVE_ZRFX_GLSL
#define GAVE_ZRFX_GLSL

// 게이브 사격, 폭발 연출(좀비런 zrfx.glsl 을 옮김, 26.3 core/item). 표식 텍스처 gave:item/fx_marker(64x64, 화소 = (x*4+1, y*4+1, 197, 254))로
// 우리 연출 면만 가려내고, 면 위 자리(0..1)는 화소 번호 + 화소 안 위치로 되살린다. 종류, 씨앗, 시작 틱은 정점 색(custom_model_data 색)
// 24비트: [종류 4][씨앗 8][시작 틱 mod 4000, 12]. 씨앗 위 2비트 = 변형(먼지 색, 탄흔 종류, 연기 색, 고리 색).
// 나이 = GameTime 틱 - 시작(클라 시계가 늦어 음수면 0). 결과 a < 0 이면 우리 것이 아님(바닐라 그대로), 종류 0 은 우리 면이지만 안 그림.
//  1 탄 궤적(세계)          2 섬광(세계 빌보드, 폭발 심)    3 먼지(변형 0 회색, 1 벽돌, 2 나무, 3 밝은 돌)   4 불똥 다발
//  5 바닥, 벽 자국(변형 0 탄흔, 1 그을음, 2 피, 3 담즙)   6 폭발 불덩이   7 충격파 고리(변형 1 담즙)
//  8 연기(변형 0 회색, 1 검댕, 2 담즙 가스)   9 담즙 덩이   10 피 안개
// 11 총구 불꽃 앞면(총 모델 안, 총구에서 정면)   12 총구 불꽃 옆면(가로 판, 판 v = 1 이 총구)   13 총구 불꽃 옆면(세로 판, 판 u = 1 이 총구)
// 14 쓰러짐 고리(바닥 판, 늘 보임, 남은 출혈만큼 호. 변형 1 빨강, 2 초록 + 씨앗 아래 6비트 남은 몫, 변형 0 가득)
// 15 새 무기(2026-11-12): 변형 0 번개(꺾인 줄), 1 불길 덩이, 2 서리 덩이, 3 레일 광선. 씨앗 아래 4비트 11..14 는 쓰지 않는다(zr_kick 신호와 겹침)

#include <minecraft:globals.glsl>

float zr_hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float zr_noise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = zr_hash(i), b = zr_hash(i + vec2(1, 0)), c = zr_hash(i + vec2(0, 1)), d = zr_hash(i + vec2(1, 1));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float zr_fbm(vec2 p) {
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += a * zr_noise(p);
        p = p * 2.03 + vec2(17.1, 9.7);
        a *= 0.5;
    }
    return v;
}

// 자국이 hold 틱 동안 그대로 있다가 out 틱에 걸쳐 사라짐
float zr_fade(float age, float hold, float out_) {
    return clamp(1.0 - (age - hold) / out_, 0.0, 1.0);
}

// 총구 불꽃 수명(틱): 0.6 틱까지 그대로, 2.3 틱에 사라짐(도장 뒤 클라가 1 틱 늦게 받아도 보이게)
float zr_flash_life(float age) {
    return 1.0 - smoothstep(0.6, 2.3, age);
}

// out: rgb, a. lit = 세상 빛(lightMapColor)을 받는 정도(0 스스로 빛남 .. 1 그대로). 먼지, 피 안개는 반쯤만 받아 어두운 데서도 보임
vec4 zr_fx(sampler2D atlas, vec2 uv, vec4 tint, out float lit) {
    lit = 0.0;
    vec2 tc = uv * vec2(textureSize(atlas, 0));
    ivec4 m = ivec4(round(texelFetch(atlas, ivec2(tc), 0) * 255.0));
    if (m.b != 197 || m.a != 254 || (m.r & 3) != 1 || (m.g & 3) != 1) return vec4(-1.0);
    vec2 p = (vec2(float((m.r - 1) >> 2), float((m.g - 1) >> 2)) + fract(tc)) / 64.0;
    vec2 c = p * 2.0 - 1.0;
    float r = length(c);
    ivec3 t = ivec3(round(tint.rgb * 255.0));
    int bits = (t.r << 16) | (t.g << 8) | t.b;
    int kind = bits >> 20;
    int seedi = (bits >> 12) & 255;
    int variant = seedi >> 6;
    float seed = float(seedi);
    float start = float(bits & 4095);
    float age = mod(GameTime * 24000.0 - start, 4000.0);
    if (age > 3000.0) age = 0.0;
    vec3 col = vec3(1.0);
    float a = 0.0;
    if (kind == 1) {
        // 탄 궤적: 폭 가운데 밝은 심 + 주황 번짐, 길이 양 끝은 가늘게
        float d = abs(c.x);
        float core = exp(-d * d * 90.0), glow = exp(-d * d * 9.0) * 0.5;
        float along = pow(sin(clamp(p.y, 0.0, 1.0) * 3.14159), 0.6);
        float life = 1.0 - smoothstep(2.0, 4.5, age);
        col = mix(vec3(1.0, 0.6, 0.22), vec3(1.0, 0.97, 0.88), core);
        a = (core * 1.3 + glow) * along * life;
    } else if (kind == 2) {
        // 섬광(세계 빌보드): 별 모양 빛살 + 뜨거운 심 + 둥근 번짐
        float ang = atan(c.y, c.x) + seed * 0.0246;
        float rays = pow(abs(cos(ang * 2.5)), 10.0) * exp(-r * 3.2) + pow(abs(cos(ang * 4.0 + 1.3)), 24.0) * exp(-r * 2.2) * 0.7;
        float core = exp(-r * r * 10.0);
        float glow = exp(-r * r * 3.0) * 0.35;
        float life = 1.0 - smoothstep(0.8, 3.0, age);
        float s = core + glow + rays * (0.6 + 0.4 * zr_noise(c * 6.0 + seed));
        col = mix(vec3(1.0, 0.5, 0.12), vec3(1.0, 0.96, 0.82), clamp(core * 1.4, 0.0, 1.0));
        a = clamp(s, 0.0, 1.0) * life;
    } else if (kind == 3) {
        // 먼지: 가장자리가 일렁이며 퍼지는 덩이, 맞은 재질 색
        float k = clamp(age / 16.0, 0.0, 1.0);
        float n = zr_fbm(c * 2.4 + seed * 0.37 + vec2(0.0, age * 0.06));
        float grow = 0.55 + 0.45 * sqrt(k);
        float body = smoothstep(1.0, 0.2, r / grow + (n - 0.5) * 0.8);
        vec3 base = variant == 1 ? vec3(0.68, 0.46, 0.37) : variant == 2 ? vec3(0.6, 0.46, 0.3) : variant == 3 ? vec3(0.88, 0.86, 0.82)
            : vec3(0.66, 0.64, 0.6);
        col = base * (0.85 + 0.35 * n);
        a = body * pow(1.0 - k, 1.2) * 0.85;
        lit = 0.55;
    } else if (kind == 4) {
        // 불똥 다발: 가운데서 밖으로 튀는 짧은 줄 7개
        float life = 1.0 - smoothstep(1.2, 4.0, age);
        float s = exp(-r * r * 40.0) * (1.0 - smoothstep(0.0, 2.0, age));
        for (int i = 0; i < 7; i++) {
            float fi = float(i);
            float ai = zr_hash(vec2(seed, fi + 1.0)) * 6.2832;
            float li = 0.45 + 0.55 * zr_hash(vec2(fi + 2.0, seed + 3.0));
            vec2 dir = vec2(cos(ai), sin(ai));
            float along = dot(c, dir);
            float across = abs(dot(c, vec2(-dir.y, dir.x)));
            float head = li * clamp(0.35 + age * 0.3, 0.0, 1.0);
            float tail = head - 0.35 * li;
            float seg = smoothstep(tail, tail + 0.08, along) * smoothstep(head + 0.04, head - 0.04, along);
            s += seg * exp(-across * across * 900.0);
        }
        col = vec3(1.0, 0.85, 0.5);
        a = clamp(s, 0.0, 1.0) * life;
    } else if (kind == 5) {
        // 자국(벽, 바닥에 붙은 판)
        lit = 1.0;
        if (variant == 0) {
            // 탄흔: 검은 구멍 + 그을린 둘레 + 가는 금, 8초 뒤 2초에 걸쳐 사라짐
            float n = zr_noise(c * 5.0 + seed);
            float hole = smoothstep(0.26, 0.14, r + (n - 0.5) * 0.1);
            float ring = smoothstep(0.9, 0.25, r + (n - 0.5) * 0.35) * 0.55;
            float ang = atan(c.y, c.x);
            float cracks = pow(abs(sin(ang * 3.0 + seed)), 30.0) * smoothstep(0.75, 0.2, r) * 0.6;
            a = clamp(hole + ring + cracks, 0.0, 0.95) * zr_fade(age, 160.0, 40.0);
            col = mix(vec3(0.2, 0.18, 0.16), vec3(0.02), hole);
        } else if (variant == 1) {
            // 폭발 그을음: 큰 검은 얼룩, 13초 뒤 2초
            float n = zr_fbm(c * 2.0 + seed * 0.3);
            float body = smoothstep(1.0, 0.25, r + (n - 0.5) * 0.6);
            a = body * 0.85 * zr_fade(age, 260.0, 40.0);
            col = mix(vec3(0.13, 0.11, 0.1), vec3(0.02), smoothstep(0.6, 0.0, r)) * (0.8 + 0.4 * n);
        } else {
            // 피(2), 담즙(3): 덩이 + 둘레 방울
            float n = zr_fbm(c * 3.0 + seed * 0.5);
            float body = smoothstep(0.75, 0.45, r + (n - 0.5) * 0.7);
            float drops = 0.0;
            for (int i = 0; i < 6; i++) {
                float fi = float(i);
                vec2 o = (vec2(zr_hash(vec2(seed, fi + 1.0)), zr_hash(vec2(fi + 5.0, seed))) * 2.0 - 1.0) * 0.82;
                float rad = 0.04 + 0.06 * zr_hash(vec2(seed + fi, 7.0));
                drops += smoothstep(rad, rad * 0.6, length(c - o));
            }
            a = clamp(body + drops, 0.0, 0.92) * (variant == 2 ? zr_fade(age, 160.0, 40.0) : zr_fade(age, 120.0, 40.0));
            col = variant == 2 ? vec3(0.34, 0.02, 0.015) * (0.75 + 0.4 * n) : mix(vec3(0.33, 0.43, 0.07), vec3(0.62, 0.72, 0.16), n);
        }
    } else if (kind == 6) {
        // 폭발 불덩이: 심은 희고 노랗게, 바깥은 주황 빨강, 시간이 지나며 검댕으로
        float k = clamp(age / 20.0, 0.0, 1.0);
        float n = zr_fbm(c * 2.0 + seed * 0.21 - vec2(0.0, age * 0.09));
        float edge = r + (n - 0.5) * 0.6;
        float body = smoothstep(1.0, 0.5, edge);
        float heat = clamp((1.0 - edge) * 1.7 - k * 1.5 + n * 0.45, 0.0, 1.0);
        vec3 hot = mix(vec3(0.9, 0.22, 0.04), vec3(1.0, 0.92, 0.6), heat);
        vec3 soot = vec3(0.1, 0.09, 0.085) * (0.7 + 0.5 * n);
        col = mix(soot, hot, clamp(heat * 1.6 + (1.0 - k) * 0.35, 0.0, 1.0));
        a = body * (1.0 - smoothstep(0.65, 1.0, k));
    } else if (kind == 7) {
        // 충격파 고리(바닥 판)
        float k = clamp(age / 9.0, 0.0, 1.0);
        float n = zr_noise(vec2(atan(c.y, c.x) * 4.0, seed));
        float ring = exp(-pow((r - 0.8) * 12.0, 2.0)) * (0.7 + 0.3 * n);
        float inner = smoothstep(0.85, 0.0, r) * 0.25 * (1.0 - k);
        col = variant == 1 ? vec3(0.6, 0.75, 0.2) : vec3(1.0, 0.82, 0.58);
        a = (ring + inner) * (1.0 - k) * 0.8;
        lit = variant == 1 ? 1.0 : 0.0;
    } else if (kind == 8) {
        // 연기: 천천히 일렁이며 짙어졌다 옅어짐
        float life = variant == 2 ? 60.0 : 70.0;
        float k = clamp(age / life, 0.0, 1.0);
        float n = zr_fbm(c * 1.7 + seed * 0.13 + vec2(age * 0.012, -age * 0.02));
        float body = smoothstep(1.0, 0.15, r + (n - 0.5) * 0.85);
        vec3 base = variant == 1 ? vec3(0.13, 0.12, 0.115) : variant == 2 ? vec3(0.38, 0.48, 0.14) : vec3(0.24, 0.23, 0.22);
        col = base * (0.75 + 0.45 * n);
        a = body * smoothstep(0.0, 0.08, k) * (1.0 - smoothstep(0.45, 1.0, k)) * (variant == 2 ? 0.55 : 0.8);
        lit = 1.0;
    } else if (kind == 9) {
        // 담즙 덩이(부풀이 터짐)
        float k = clamp(age / 22.0, 0.0, 1.0);
        float n = zr_fbm(c * 2.6 + seed * 0.37 + vec2(0.0, age * 0.05));
        float body = smoothstep(1.0, 0.3, r + (n - 0.5) * 0.75);
        float clumps = smoothstep(0.55, 0.75, n) * 0.4;
        a = (body + clumps * body) * pow(1.0 - k, 1.2) * 0.95;
        col = mix(vec3(0.42, 0.55, 0.08), vec3(0.8, 0.88, 0.3), n);
        lit = 0.6;
    } else if (kind == 10) {
        // 피 안개: 가시 돋친 덩이가 퍼지며 옅어짐
        float k = clamp(age / 12.0, 0.0, 1.0);
        float n = zr_fbm(c * 3.0 + seed * 0.37 + vec2(0.0, age * 0.05));
        float ang = atan(c.y, c.x);
        float spikes = pow(abs(sin(ang * 3.5 + seed)), 6.0) * 0.35;
        float body = smoothstep(1.0, 0.3, r - spikes + (n - 0.5) * 0.6);
        a = body * pow(1.0 - k, 1.4) * 0.95;
        // 변형 1 = 게이브 피(분홍빛 빨강, ShotFx.BLOOD_PINK), 0 = 빨강
        col = (variant == 1 ? vec3(0.85, 0.30, 0.40) : vec3(0.62, 0.04, 0.03)) * (0.75 + 0.45 * n);
        lit = 0.5;
    } else if (kind == 11) {
        // 총구 불꽃 앞면: 4..6 갈래 별 + 흰 심, 처음엔 작다가 살짝 퍼짐
        float life = zr_flash_life(age);
        float grow = 0.72 + 0.28 * clamp(age, 0.0, 1.0);
        vec2 q = c / grow;
        float rr = length(q);
        float rays = float(4 + seedi % 3);
        float ang = atan(q.y, q.x) + seed * 0.0491;
        float petal = pow(abs(cos(ang * rays * 0.5)), 6.0) * exp(-rr * 2.4);
        float thin = pow(abs(cos(ang * rays * 0.5 + 1.5708)), 22.0) * exp(-rr * 3.6) * 0.55;
        float core = exp(-rr * rr * 16.0);
        float glow = exp(-rr * rr * 4.5) * 0.4;
        float s = core * 1.3 + glow + (petal + thin) * (0.6 + 0.4 * zr_noise(q * 5.0 + seed));
        col = mix(vec3(1.0, 0.48, 0.12), vec3(1.0, 0.97, 0.86), clamp(core * 1.5 + petal * 0.25, 0.0, 1.0));
        a = clamp(s, 0.0, 1.0) * life;
    } else if (kind == 12 || kind == 13) {
        // 총구 불꽃 옆면: 총구에서 앞으로 부풀었다 가늘어지는 불길, 총구 쪽이 희고 뜨거움
        float life = zr_flash_life(age);
        vec2 q = kind == 13 ? p.yx : p;
        float along = 1.0 - q.y;
        float across = abs(q.x * 2.0 - 1.0);
        float n = zr_noise(vec2(along * 6.0 + seed * 0.1, q.x * 3.0));
        float w = (0.16 + 0.62 * sin(clamp(along, 0.0, 1.0) * 3.14159) * (1.0 - along * 0.35)) * (0.75 + 0.5 * n);
        float body = 1.0 - smoothstep(w * 0.45, w, across);
        float heat = exp(-along * 3.2) * (1.0 - across / max(w, 0.001));
        col = mix(vec3(1.0, 0.45, 0.1), vec3(1.0, 0.96, 0.82), clamp(heat * 1.7, 0.0, 1.0));
        a = clamp(body * smoothstep(1.0, 0.72, along) * (0.75 + 0.5 * heat), 0.0, 1.0) * life;
    } else if (kind == 14) {
        // 쓰러짐 고리(바닥 판, 늘 보임): 남은 출혈만큼 호가 남음(판 +y 쪽에서 시계 방향으로 줄어듦), 나머지 호는 어둡게.
        // 변형 0 = 가득 찬 빨강(ShotFx.fx 로 찍은 것), 1 = 빨강 + 씨앗 아래 6비트 남은 몫(0..63), 2 = 초록(일으키는 중) + 남은 몫
        float f = variant == 0 ? 1.0 : float(seedi & 63) / 63.0;
        float ang = fract(atan(c.x, c.y) / 6.2831853 + 1.0);
        float band = 1.0 - smoothstep(0.065, 0.11, abs(r - 0.8));
        float on = 1.0 - smoothstep(f - 0.006, f, ang);
        if (f >= 0.999) on = 1.0;
        float pulse = 0.82 + 0.18 * sin(mod(GameTime * 24000.0, 400.0) * 0.6);
        col = variant == 2 ? vec3(0.3, 0.95, 0.35) : vec3(0.95, 0.1, 0.07);
        col *= mix(0.35, pulse, on);
        a = band * mix(0.22, 0.95, on);
    } else if (kind == 15) {
        // 새 무기(2026-11-12, ShotFx.WEAPON): 변형 0 번개, 1 불길 덩이, 2 서리 덩이, 3 레일 광선. 번개와 광선은 fx_tracer(c.x 가로, p.y 길이),
        // 불길과 서리는 fx_quad 빌보드
        if (variant == 0) {
            // 번개: 길이 따라 지그재그로 꺾이는 흰 파랑 줄, 2 틱마다 모양이 바뀌며 깜빡임
            float flick = floor(age * 0.5);
            float y = clamp(p.y, 0.0, 1.0);
            float off = (zr_noise(vec2(y * 7.0, seed * 0.3 + flick * 3.1)) - 0.5) * 0.9 + (zr_noise(vec2(y * 19.0, seed + flick * 5.7)) - 0.5) * 0.35;
            off *= sin(y * 3.14159);
            float d = abs(c.x - off);
            float core = exp(-d * d * 160.0), glow = exp(-d * d * 14.0) * 0.55;
            float life = 1.0 - smoothstep(2.5, 5.0, age);
            float blink = 0.7 + 0.3 * step(0.5, fract(age * 0.5 + seed * 0.013));
            col = mix(vec3(0.45, 0.65, 1.0), vec3(0.95, 0.97, 1.0), clamp(core * 1.4, 0.0, 1.0));
            a = clamp(core * 1.4 + glow, 0.0, 1.0) * life * blink;
        } else if (variant == 1) {
            // 불길 덩이: 일렁이는 가장자리, 심은 노랑 흰색, 바깥 주황 빨강, 날아가며 어두워지고 투명해짐(그을음 없음)
            float k = clamp(age / 9.0, 0.0, 1.0);
            float n = zr_fbm(c * 2.3 + vec2(seed * 0.19, -age * 0.35));
            float edge = r + (n - 0.5) * 0.75;
            float body = smoothstep(1.0, 0.45, edge);
            float heat = clamp((1.0 - edge) * 1.6 - k * 0.9 + n * 0.35, 0.0, 1.0);
            col = mix(vec3(0.85, 0.18, 0.03), vec3(1.0, 0.9, 0.45), heat);
            a = body * (1.0 - smoothstep(0.55, 1.0, k)) * 0.95;
        } else if (variant == 2) {
            // 서리 덩이: 하얀 청록 안개 + 반짝이는 얼음 알갱이
            float k = clamp(age / 10.0, 0.0, 1.0);
            float n = zr_fbm(c * 2.0 + vec2(seed * 0.23, age * 0.08));
            float body = smoothstep(1.0, 0.3, r + (n - 0.5) * 0.7);
            float sp = step(0.93, zr_hash(floor((c + 1.0) * 9.0) + seed + floor(age * 0.5))) * smoothstep(1.0, 0.5, r);
            col = mix(vec3(0.55, 0.85, 0.95), vec3(0.95, 1.0, 1.0), clamp(n * 0.8 + sp, 0.0, 1.0));
            a = clamp(body * 0.6 + sp * 0.9, 0.0, 1.0) * (1.0 - smoothstep(0.5, 1.0, k));
            lit = 0.3;
        } else {
            // 레일 광선: 청록 굵은 심이 났다가 가늘어지고, 둘레에 나선 띠
            float k = clamp(age / 9.0, 0.0, 1.0);
            float w = mix(0.35, 0.06, k);
            float d = abs(c.x);
            float core = exp(-d * d / (w * w) * 2.0);
            float spiral = pow(abs(sin(p.y * 60.0 - age * 2.0 + c.x * 3.0)), 8.0) * exp(-d * d * 3.0) * (1.0 - k);
            col = mix(vec3(0.2, 0.85, 1.0), vec3(0.95, 1.0, 1.0), clamp(core * 1.2, 0.0, 1.0));
            a = clamp(core * 1.3 + spiral * 0.6 + exp(-d * d * 4.0) * 0.25, 0.0, 1.0) * (1.0 - smoothstep(0.6, 1.0, k));
        }
    } else {
        // 우리 표식이지만 그릴 것 없음(틴트 기본 0 = 쏘지 않은 총의 불꽃 자리)
        return vec4(0.0);
    }
    return vec4(col, clamp(a, 0.0, 1.0));
}

// 1인칭 총 움직임(정점 셰이더, pos = 눈 공간 x 오른쪽 y 위 -z 앞): 총 몸통 면은 틴트로 [F 4][도장 상위 4][몸짓 4][도장 중간 4][15 - 세기 4][도장 하위 4] 를 받는다
// (쏜 뒤 4 틱, 꺼내는 동안, 재장전 동안과 끝난 뒤 6 틱만, 그 밖엔 흰색). 몸짓 E = 반동(세기 0..3), D = 꺼내기(세기 = 꺼내는 시간
// 6, 8, 10, 12 틱), C = 재장전 자세로 들어감(도장 = 시작), B = 재장전 자세에서 돌아옴(도장 = 끝).
// 총구 불꽃 면(종류 11..13)은 씨앗 [세기 2][반동 켬 1][무작위 5]. 이 몸통 틴트와 반동 켠 불꽃 색은 총 아이템 정의가 1인칭 자리
// (firstperson_*)에서만 넣는다(tools/build_pack.py): 남의 3인칭 총은 내 카메라 바로 옆에 있어도 움직이지 않는다. 카메라 2.5 블록
// 거리는 다른 아이템 틴트가 우연히 같을 때를 막는 울타리일 뿐(1인칭 총은 불꽃 끝까지 1.9 블록 안이라 한 모델이 갈라지지 않음).
// 반동은 도장 뒤 나이에 따라 총을 뒤로 밀고 손 근처를 축으로 총구를 들어 올림, 꺼내기는 화면 아래에서 비스듬히 올라옴, 재장전은
// 총을 왼쪽으로 눕히고 총구를 내려 탄창 자리를 보임. 몸통 틴트는 흰색으로 되돌려 색은 그대로.
vec3 zr_rot_x(vec3 q, float a) {
    return vec3(q.x, q.y * cos(a) - q.z * sin(a), q.y * sin(a) + q.z * cos(a));
}

vec3 zr_rot_z(vec3 q, float a) {
    return vec3(q.x * cos(a) - q.y * sin(a), q.x * sin(a) + q.y * cos(a), q.z);
}

void zr_kick(inout vec3 pos, inout vec4 col) {
    if (dot(pos, pos) > 6.25) return;
    ivec3 t = ivec3(round(col.rgb * 255.0));
    int hr = t.r >> 4;
    int sig = 0;
    int level = 0;
    int stamp = 0;
    if (hr == 15 && (t.g >> 4) >= 11 && (t.g >> 4) <= 14 && (t.b >> 4) >= 12) {
        sig = t.g >> 4;
        level = 15 - (t.b >> 4);
        stamp = ((t.r & 15) << 8) | ((t.g & 15) << 4) | (t.b & 15);
        col = vec4(1.0, 1.0, 1.0, col.a);
    } else if (hr >= 11 && hr <= 13) {
        // 총구 불꽃: 씨앗 [세기 2][반동 켬 1][무작위 5]
        int bits = (t.r << 16) | (t.g << 8) | t.b;
        if (((bits >> 17) & 1) == 1) sig = 14;
        level = (bits >> 18) & 3;
        stamp = bits & 4095;
    }
    if (sig == 0) return;
    float age = mod(GameTime * 24000.0 - float(stamp), 4000.0);
    if (age > 3000.0) age = 0.0;
    vec3 pivot = vec3(0.3, -0.45, -0.55);
    if (sig == 13) {
        float e = 1.0 - clamp(age / (6.0 + 2.0 * float(level)), 0.0, 1.0);
        e = e * e * (3.0 - 2.0 * e);
        pos = pivot + zr_rot_x(pos - pivot, radians(-28.0) * e) + vec3(0.06 * e, -0.42 * e, 0.0);
        return;
    }
    if (sig == 12 || sig == 11) {
        float e = clamp(age / 5.0, 0.0, 1.0);
        e = e * e * (3.0 - 2.0 * e);
        if (sig == 11) e = 1.0 - e;
        vec3 q = zr_rot_z(zr_rot_x(pos - pivot, radians(-9.0) * e), radians(20.0) * e);
        pos = pivot + q + vec3(-0.05 * e, -0.035 * e, 0.0);
        return;
    }
    if (age > 6.0) return;
    float k = smoothstep(0.0, 0.3, age) * exp(-max(age - 0.3, 0.0) * 1.3);
    // 세기별 (뒤로, 위로 블록, 들림 도)
    vec3 lv = level == 0 ? vec3(0.04, 0.015, 2.2) : level == 1 ? vec3(0.055, 0.02, 3.0) : level == 2 ? vec3(0.07, 0.03, 5.0)
        : vec3(0.11, 0.045, 7.0);
    pos = pivot + zr_rot_x(pos - pivot, radians(lv.z) * k) + vec3(0.0, lv.y * k, lv.x * k);
}

#endif
