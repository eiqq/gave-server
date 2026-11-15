package kr.gave.trip.special;

/**
 * 뒤 막 특수 라운드 열 가지(trip_special_b)의 숫자 한곳. 처음 값이고 파워커브와 게임 안 시험이 맞춘다. 사용자 요구 '어마어마하게 어렵게, 막이 오를수록
 * 어둡게'를 따라 막마다 어둡게 몫(DIM_ACT), 체력, 피해, 빠르기 배율을 높게 잡았다. 켠 채 바꾸기는 시험 훅 '특수값 x 이름 값'.
 */
public final class LateTune {

    private LateTune() {
    }

    // ------------------------------------------------------------------ 바탕(모든 특수)

    /** 막마다 테마 어둡게(0..1, Theme.dim): 1막 .. 4막. 특수가 고르는 값은 이것보다 어두울 수만 있다. */
    public static double[] DIM_ACT = {0, 0.2, 0.35, 0.55, 0.72};
    /** 막마다 게이브 배율(1막 .. 4막): 체력, 사람에게 주는 피해, 빠르기. 특수마다의 몫에 곱해진다(trip_special_a 의 Special.Tune 과 같은 처음 값). */
    public static double[] HP_ACT = {1.10, 1.25, 1.40, 1.60}, DMG_ACT = {1.10, 1.20, 1.30, 1.45}, SPEED_ACT = {1.05, 1.08, 1.12, 1.15};
    /** 라운드 이름과 한 줄 알림 띠(틱). */
    public static int BANNER_TICKS = 50, LINE_TICKS = 50;
    /** 끝나지 않는 특수의 안전 끝(초): 넘기면 놓침. */
    public static int MAX_SECONDS = 300;
    /** 감독 대기열이 비면 채움: 살아 있는 수가 감독 동시 수 목표의 이 몫 아래면 STREAM_STEP 마리씩(40틱마다. 큰 판은 그 몫의 10분의 1 까지 한 번에). */
    public static double STREAM_SHARE = 0.75;
    public static int STREAM_STEP = 6, STREAM_EVERY = 40;

    // ------------------------------------------------------------------ 밀물(2막)

    /** 버티는 초, 물이 한 칸 오르는 틱, 물 높이 칸(Theme.flood 0..1). */
    public static int TIDE_SECONDS = 100, TIDE_STEP = 400;
    public static double[] TIDE_LEVELS = {0.15, 0.35, 0.55, 0.75, 0.9};
    /** 물 안 느려짐(%), 물밑 손: 초마다 붙잡힐 몫, 묶이는 틱, 피해, 물 안 게이브 빠르기. */
    public static double TIDE_SLOW = 35, TIDE_GRAB = 0.12, TIDE_GRAB_DMG = 4, TIDE_MOB_SPEED = 1.25;
    public static int TIDE_GRAB_TICKS = 30;
    /** 바퀴가 잠기는 물 높이, 트럭이 잠긴 동안 초당 깎임(최대 체력 몫), 배수 손잡이(트럭 동쪽 자리 반지름, 다 돌리는 틱, 퍼낸 뒤 막아 주는 틱). */
    public static double TIDE_WHEEL = 0.5, TIDE_TRUCK = 0.008, PUMP_R = 1.8;
    public static int PUMP_TICKS = 80, PUMP_HOLD = 240;
    /** 감독 계획 배율(밀물: 수 x, 체력 x, 피해 x). */
    public static double TIDE_UNITS = 1.25, TIDE_HP = 1.15, TIDE_DMG = 1.2;

    // ------------------------------------------------------------------ 이름 부르는 밤(3막)

    /** 가짜 수: 바탕 + 사람마다, 가장 많이. 더 생기는 틱, 그때 수, 더 생기는 끝(초). 놓이는 반지름. */
    public static double CALL_BASE = 5, CALL_PER = 0.5, CALL_RMIN = 14, CALL_RMAX = 40;
    public static int CALL_MAX = 16, CALL_MORE_EVERY = 400, CALL_MORE = 2, CALL_MORE_UNTIL = 80;
    /** 속삭임 사이(틱), 쓰러진 사람 곁에 생기는 가짜 거리. */
    public static int CALL_WHISPER_MIN = 120, CALL_WHISPER_MAX = 200;
    public static double CALL_NEAR = 6;
    /** 안개 단계, 수 x, 피해 x. */
    public static int CALL_FOG = 3;
    public static double CALL_UNITS = 1.1, CALL_DMG = 1.25;

    // ------------------------------------------------------------------ 가라앉는 트럭(3~4막)

    /** 바퀴를 무는 얼굴 체력(웨이브 1 바탕, 감독 체력 배율을 곱함: 사람 수 몫은 그 배율에 들어 있어 SINK_HP_POW 는 안 읽는다), 몸 배율. */
    public static double SINK_HP = 260, SINK_HP_POW = 0.6;
    public static float SINK_SCALE = 1.7f;
    /** 초당 트럭 깎임(넷이 다 물었을 때 최대 체력 몫), 쏴서 맞는 거리(트럭 발자국에서), 먼 사격 피해 배율. */
    public static double SINK_DRAIN = 0.011, SINK_REACH = 7.5, SINK_FAR = 0.1;
    /** 트럭이 가라앉는 깊이(블록, 넷이 다 물면 이만큼까지), 틱마다 가라앉는 몫. 트럭 노림 x. */
    public static double SINK_DEPTH = 1.3, SINK_RATE = 0.0006, SINK_SHARE = 2.5, SINK_UNITS = 1.2;

    // ------------------------------------------------------------------ 등 돌리면(3~4막)

    /** 보는 거리, 시선 원뿔 반각의 코사인(30도), 보이는 개체 빠르기, 안 보이는 개체 빠르기와 피해, 멈춘 개체가 받는 피해. */
    public static double EYES_RANGE = 40, EYES_COS = 0.866, EYES_FROZEN = 0.0, EYES_RUSH = 1.8, EYES_RUSH_DMG = 1.3, EYES_FROZEN_DMG = 0.5;
    /** 등 뒤 심장 소리 거리, 수 x. */
    public static double EYES_HEART_R = 12, EYES_UNITS = 1.3;
    public static int EYES_FOG = 2;

    // ------------------------------------------------------------------ 얼굴 폭풍(3~4막)

    /** 바람이 도는 틱(90도), 바람을 마주 본다고 치는 코사인, 얼굴 하나 붙는 틱(마주 보는 동안), 바람 그늘(트럭 뒤) 거리. */
    public static int STORM_TURN = 500, STORM_FACE_TICKS = 50;
    public static double STORM_COS = 0.5, STORM_LEE = 7;
    /** 얼굴 하나 떼는 좌클릭 수(본인), 동료는 한 번, 바람에 밀리는 세기, 바람 따라 게이브 빠르기. */
    public static int STORM_CLICKS = 3;
    public static double STORM_PUSH = 0.05, STORM_SPEED = 1.3, STORM_UNITS = 1.2, STORM_DMG = 1.2;
    public static int STORM_FOG = 3;

    // ------------------------------------------------------------------ 투광등(4막)

    /** 건조 한 칸 초(등 넷이 다 켜졌을 때), 넷 다 꺼지면 초당 뒤로 가는 몫(그 라운드 막대의 몫: 발사장에서도 같다). */
    public static double LIT_BUILD_SECONDS = 120, LIT_BACK = 0.01;
    /** 등 끄러 가는 하늘 종 고르기 틱(LIT_RAID_FULL 명 기준: 사람이 적으면 그만큼 길게, 다섯 배까지), 끄는 거리, 수 x. */
    public static int LIT_RAID_EVERY = 160, LIT_RAID_FULL = 30;
    public static double LIT_RAID_R = 2.6, LIT_UNITS = 1.2, LIT_HP = 1.15;

    // ------------------------------------------------------------------ 연료 화재(4막)

    /** 불 고리: 처음 반지름, 조여 오는 끝(초 FIRE_SHRINK_S 동안), 그 뒤 트럭까지 조이는 빠르기(블록/초), 트럭에 닿았다고 보는 반지름. */
    public static double FIRE_R0 = 44, FIRE_R1 = 16, FIRE_LATE = 0.12, FIRE_TRUCK_R = 5;
    public static int FIRE_SHRINK_S = 100;
    /** 불 띠 폭(FIRE_BAND 는 안 읽는다: 타는 땅은 고리의 0.9배 바깥 전부), 사람 초당 피해, 게이브 초당 피해(최대 체력 몫), 불붙은 게이브 피해 x, 옮긴 불(틱, 초당 피해), 트럭 초당 깎임. */
    public static double FIRE_BAND = 5, FIRE_DMG = 6, FIRE_MOB = 0.03, FIRE_BURN_MUL = 1.4, FIRE_SPREAD_DMG = 2, FIRE_TRUCK = 0.015;
    public static int FIRE_BURN_TICKS = 60, FIRE_MOB_BURN = 200;
    /** 밸브: 반지름, 잠그는 거리, 잠그는 틱, 건조 초. */
    public static double VALVE_R = 20, VALVE_REACH = 2.2, FIRE_BUILD_SECONDS = 110;
    public static int VALVE_TICKS = 120;
    public static double FIRE_UNITS = 1.15;

    // ------------------------------------------------------------------ 하늘이 내려온다(4막)

    /** 천장 높이(바닥 위 블록): 처음, 가장 낮음, 내려오는 빠르기(블록/초), 얼굴 하나 올리면 +, 얼굴 수, 얼굴 체력(웨이브 1 바탕), 맞는 반지름. */
    public static double SKY_TOP = 30, SKY_LOW = 6, SKY_FALL = 0.3, SKY_PUSH = 2, SKY_FACE_HP = 90, SKY_FACE_R = 2.4;
    public static int SKY_FACES = 16;
    /** 천장이 닿았을 때 초당 사람 피해, 트럭 초당 깎임, 그림자에서 솟는 땅 종 틱, 건조 초. */
    public static double SKY_DMG = 4, SKY_TRUCK = 0.01, SKY_BUILD_SECONDS = 120, SKY_UNITS = 1.15;
    public static int SKY_RISE_EVERY = 120;
    public static float SKY_SCALE = 1.6f;

    // ------------------------------------------------------------------ 추격전 바탕(가로막힌 길, 마지막 질주): trip_special_a 의 Chase 를 쓴다

    /** 얼굴 벽: 겹 수, 벽 체력(웨이브 1 바탕, 사람 수^0.7), 남은 몫마다 트럭 깎임. */
    public static int RAM_WALLS = 3;
    public static double RAM_WALL_HP = 900, RAM_FAIL = 0.12;
    /** 마지막 질주: 달리는 초, 수 x, 정예가 뛰어오르는 첫 틱과 간격, 단계마다 빠르기 덧(흐름과 게이브), 앞 불빛 거리(처음, 끝: 표시 추적 128 안). */
    public static int LAST_SECONDS = 130, LAST_ELITE_FIRST = 400, LAST_ELITE_EVERY = 240;
    public static double LAST_UNITS = 1.2, LAST_STEP_SPEED = 0.25, LAST_FAR = 110, LAST_NEAR = 50;
}
