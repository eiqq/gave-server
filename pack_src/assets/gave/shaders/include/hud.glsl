#ifndef GAVE_HUD_GLSL
#define GAVE_HUD_GLSL

#include <minecraft:globals.glsl>
#include <gave:hud_spec.glsl>

// 게이브 화면 HUD(좀비런에서 옮김, core/text 의 GUI 변형 정점 쪽). tools/make_hud.py 가 바닐라 text.vsh 에 끼운다(원본은 pack_hud/hud.glsl).
// 서버(plugin kr.gave.hud)는 플레이어마다 보스바 하나의 제목 한 줄에 HUD 글리프를 모두 싣는다. 제목의 순 폭은 0 이라
// 늘 x = floor(GUI폭/2) 에서 시작하고 기준선은 y = 10 이다(26.3 BossHealthOverlay: 첫 제목 위 y = 3, 기준선 7 아래).
// 우리 글리프는 표시 색(RGB 정수)이 신호다. R = 칸 번호, B 가 종류:
//   B = ZR_B_IMG   : 그림, 글자(텍스처 색 x 팔레트). G = 팔레트(ZR_PAL)
//   B = ZR_B_SHAPE : 셰이더 모양(막대, 고리, 점, 무한, 화살표), G = 채움 0..255(화살표 칸은 각도 0..255 = 한 바퀴). 색은 칸 색(ZR_SLOT_COLOR)
//   B = ZR_B_TIMED .. + ZR_TIMED_SPAN - 1 : 시간 모양. (B - ZR_B_TIMED) x 256 + G = 끝 틱 mod ZR_PERIOD(월드 게임 틱).
//       셰이더가 GameTime 으로 남은 틱을 매 프레임 계산한다(서버는 시작과 끝에만 보냄). 길이는 글리프 텍셀이 정한다
// 바닐라 글자는 이 범위를 안 쓴다(이름 색 B 는 0, 85, 170, 255, 기본 그림자는 63 이하). 그 밖은 바닐라 그대로.
// 화면 자리 = 칸 기준점(비율 x GUI 크기 + 오프셋) + 글리프의 원래 자리 - (floor(GUI폭/2), 10).
// 칸 방식 비트(ZR_SLOT_MODE): 1 DRAIN, 2 PULSE, 4 HIDE(조각 쪽), 8 FIT(화면 덮개 조각), 16 POLAR(방향 화살표), 32 NOTRACK(조각 쪽), 64 THROB(밝기 깜빡임)

// GUI 크기는 정수인데 투영 행렬에서 되살리면 480 이 479.99997 처럼 나와 floor 가 1 작아진다(eternalmc 1080p 사고). 조금 빼고 올린다
vec2 zrhud_gui() {
    return ceil(vec2(2.0 / abs(ProjMat[0][0]), 2.0 / abs(ProjMat[1][1])) - 0.01);
}

bool zrhud_ortho() {
    return abs(ProjMat[2][3]) < 1e-5 && abs(ProjMat[3][3] - 1.0) < 1e-5;
}

// hud = (종류 0 바닐라 / 1 그림 / 2 모양 / 3 시간 모양, 채움 또는 남은 틱(그림은 덮개 조각 안 x 0..1), 칸 방식 비트, 덮개 조각 안 y 0..1)
void zrhud_vertex(vec3 position, vec4 color, inout vec4 glPos, inout vec4 vcolor, out vec4 hud) {
    hud = vec4(0.0);
    if (!zrhud_ortho()) {
        return;
    }
    ivec3 c = ivec3(round(color.rgb * 255.0));
    int kind = 0;
    if (c.b == ZR_B_IMG) {
        kind = 1;
    } else if (c.b == ZR_B_SHAPE) {
        kind = 2;
    } else if (c.b >= ZR_B_TIMED && c.b < ZR_B_TIMED + ZR_TIMED_SPAN) {
        kind = 3;
    }
    if (kind == 0 || c.r >= ZR_SLOT_COUNT || (kind == 1 && c.g >= ZR_PAL_COUNT)) {
        return;
    }
    vec3 pos = (ModelViewMat * vec4(position, 1.0)).xyz;
    // 보스바 제목 줄 둘레 띠만(가장 큰 글리프의 위, 아래 끝 + 여유). 다른 곳 글자는 같은 색이어도 안 건드린다
    if (pos.y < ZR_BAND_TOP || pos.y > ZR_BAND_BOT) {
        return;
    }
    vec2 gui = zrhud_gui();
    vec2 local = pos.xy - vec2(floor(gui.x * 0.5), 10.0);
    vec4 s = ZR_SLOT[c.r];
    int mode = ZR_SLOT_MODE[c.r];
    vec2 off = s.zw;
    vec2 tile = vec2(0.0);
    if ((mode & 8) != 0 && kind == 1) {
        // 화면 덮개 조각: Java 는 조각을 ZR_COVER_STEP(= 조각 폭 + 1) 간격으로 놓는다. 그 1 GUI 틈으로 이 꼭짓점이 몇째 조각의
        // 왼쪽/오른쪽 모서리인지 알아내 틈 없이 붙이고, 기준 화면(480x270)이 지금 화면을 덮게(cover) 기준점째 키운다.
        // 조각 안 자리(0..1)는 조각 쪽이 아틀라스 이웃을 섞지 않게 쓰라고 넘긴다
        float fi = floor((local.x - ZR_COVER_X0 + 0.5) / ZR_COVER_STEP);
        float right = step(ZR_COVER_TW * 0.5, local.x - ZR_COVER_X0 - ZR_COVER_STEP * fi);
        local.x = ZR_COVER_X0 + ZR_COVER_TW * (fi + right);
        tile = vec2(right, step(-ZR_COVER_TH * 0.5, local.y));
        float k = max(gui.x / ZR_COVER_W, gui.y / ZR_COVER_H);
        local *= k;
        off *= k;
    } else if ((mode & 16) != 0 && kind == 2) {
        // 방향 화살표: 글리프(위를 가리키는 세모, 가운데가 펜 0)를 G 각도(12시 0, 시계 방향)만큼 돌려 타원 둘레로
        float ang = float(c.g) * 0.024543693;
        float cs = cos(ang);
        float sn = sin(ang);
        local = vec2(local.x * cs - local.y * sn, local.x * sn + local.y * cs) + vec2(sn, -cs) * ZR_PTR_R * gui;
    }
    vec2 a = s.xy * gui + off;
    // 기준점은 화면 픽셀 격자에 맞춘다(글리프 텍셀이 픽셀과 줄을 맞추게). GUI 한 칸의 화면 픽셀 수는 투영 행렬에서 바로 읽는다:
    // 창 폭이 GUI 배율로 나눠떨어지지 않으면(1280 / 3 = 426.67) 올린 gui(427)로 나눈 값은 3 이 아니라 2.998 이 되어 기준점이 픽셀 사이에
    // 놓이고, 글리프 왼쪽 끝 한 줄이 글꼴 아틀라스 이웃 텍셀을 집어 1 화소 줄(큰 웨이브 숫자 왼쪽 초록, 흰 줄)이 생겼다
    vec2 px = ScreenSize * vec2(abs(ProjMat[0][0]), abs(ProjMat[1][1])) * 0.5;
    a = floor(a * px + 0.5) / px;
    glPos = ProjMat * vec4(a + local, pos.z, 1.0);
    // 1초에 한 번 숨 쉬듯(깜빡임 팔레트, 칸 방식 2), 0.5초마다 잠깐 비침(팔레트 효과 2, 그 반대 3)
    float t = GameTime * 24000.0;
    float pulse = 0.6 + 0.4 * cos(t * 0.31415927);
    float flash = clamp((cos(t * 0.62831853) - 0.80) * 6.0, 0.0, 1.0);
    if (kind == 1) {
        vec4 pc = ZR_PAL[c.g];
        int fx = ZR_PAL_FX[c.g];
        if (fx == 1) {
            pc.a *= pulse;
        } else if (fx == 2) {
            pc.a *= flash;
        } else if (fx == 3) {
            pc.a *= 1.0 - flash;
        }
        vcolor = vec4(pc.rgb, pc.a * color.a);
        hud = vec4(1.0, tile.x, float(mode), tile.y);
        return;
    }
    vec4 sc = ZR_SLOT_COLOR[c.r];
    if ((mode & 2) != 0) {
        sc.a *= pulse;
    }
    if ((mode & 64) != 0) {
        // 밝기로 깜빡임(불투명 그대로: 밑 막대가 비치지 않음). pulse 0.2..1 → 밝기 0.4..1
        sc.rgb *= 0.4 + 0.75 * (pulse - 0.2);
    }
    vcolor = vec4(sc.rgb, sc.a * color.a);
    if (kind == 2) {
        hud = vec4(2.0, float(c.g) / 255.0, float(mode), 0.0);
    } else {
        float v = float((c.b - ZR_B_TIMED) * 256 + c.g);
        float now = mod(t, ZR_PERIOD);
        float rem = mod(v - now + ZR_PERIOD * 0.5, ZR_PERIOD) - ZR_PERIOD * 0.5;
        hud = vec4(3.0, rem, float(mode), 0.0);
    }
}

#endif
