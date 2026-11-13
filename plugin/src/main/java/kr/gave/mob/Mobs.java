package kr.gave.mob;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.gun.Hit;
import kr.gave.gun.HitTargets;
import kr.gave.player.PState;
import kr.gave.world.Plaza;

/**
 * 가짜 몹 엔진. 공개 메서드 이름과 뜻은 계약이라 바꾸지 않는다(더하는 것은 됨).
 *
 * <p>매 틱 순서: 서 있는 사람 모음 → 흐름장 갱신(10틱마다) → 조준 머묾 갱신 → 돌아보기 규칙 → 몹마다 tick()(물어뜯기, 끌림 중엔 엔진이 대신)
 * → 적분(바닥, 벽, 경계) → 밀어내기(공간 해시 2블록 칸, 이웃 8까지)와 물어뜯기 규칙 → 사람 몸에서 밀려남 → 오래 살면 변함 → 위치 기록
 * → 몸 옮김 → 죽은 것 치움 → 시체 풀 → 래그돌 사체({@link Ragdoll}, 몹정지 중에도 돈다).
 *
 * <p>죽음: 몸을 {@link #ragdoll} 이 넘겨받아 맞은 쪽으로 튕겨 구르는 사체가 된다(시체 풀 {@link #corpses()} 에도 오름). 래그돌 예산이
 * 넘치면 예전처럼 제자리에서 납작해진다.
 *
 * <p>판정: 총, 수류탄은 {@link HitTargets} 로만 맞힌다. cast() 는 쏜 사람 핑(+보간 1틱, 최대 {@link #MAX_LAG} 틱)만큼 되돌린 위치로 판정하고,
 * damage() 는 맞은 점을 지금 좌표로 되돌려 Damage.at 에 넣고 조준 머묾 틱을 Damage.aimTicks 에 채운다.
 */
public final class Mobs implements HitTargets {

    /** 디스플레이 예산(몹 몫). 2026-11-12 200 → 480(30명 판에 늘 떼가 차 있게, 근거 docs/모듈/떼와절망.md '수와 예산'). 감독은 Director.Tune.CHILD_ROOM 을 비워 둠. */
    public static final int BUDGET = 480;
    /** 감독이 아닌 소환(시험 명령 등)도 넘지 못하는 위끝. */
    public static final int HARD_CAP = 800;
    /** 납작 사체(래그돌 예산이 넘쳤거나 래그돌을 껐을 때)의 최대 수와, 래그돌을 껐을 때 남는 틱. 래그돌 사체는 {@link Ragdoll} 이 따로 센다. */
    public static final int CORPSE_MAX = 40, CORPSE_LIFE = 160;
    /** 위치 기록 틱, 되돌리기 위끝, 클라 보간 몫(틱). */
    public static final int HIST = 10, MAX_LAG = 6, INTERP_LAG = 1;
    /** 탄 판정에 맞는 구 반지름에 얹는 값. */
    public static final double R_BULLET = 0.25;
    /** 조준 머묾을 재는 거리. */
    public static final double AIM_RANGE = 96;
    /** 오래 살면 변함 틱(Director.mutTimeMul 을 곱함). */
    public static final int MUT1 = 400, MUT2 = 800, MUT3 = 1200;
    /** 물어뜯기: 멈춤 틱, 깎는 비율, 같은 쌍 재사용 틱. 서 있는 사람에게서 BITE_CALM_R 블록 안(수평)이면 물어뜯지 않음. */
    public static final int BITE_TICKS = 20, BITE_COOLDOWN = 60;
    public static final double BITE_FRAC = 0.05, BITE_CALM_R = 3.5;
    /**
     * 판 규칙 '덮치기'(2026-11-12): 서 있는 사람 몸에 닿아 있는데 POUNCE_CD 틱 동안 아무도 안 때린 게이브는 그 사람을 문다(기본 피해 POUNCE_DMG[위협 1..5],
     * 웨이브 피해 배율은 Players.hurt 가 곱함). 종이 스스로 때리는 박자가 느리거나 사람 곁에서 머뭇거려도 닿은 게이브는 늘 아프게. 흉내, 붙잡기,
     * 사람에게 붙는 종, 사람을 무시하는 종은 {@link #NO_POUNCE}.
     */
    public static final int POUNCE_CD = 30;
    public static final double[] POUNCE_DMG = {0, 2.0, 2.5, 3.0, 3.5, 4.0};
    /** 덮치기에서 빼는 종: 붙는 것이 장치인 종(내리꽂는(머리 덮기 6초), 아기, 노크, 코앞(피해 없음), 실뭉치, 엎드린 척, 꾸러미, 문틀, 사람 탈, 축복), 사람을 무시하는 종, 연출 종. */
    public static final Set<Species> NO_POUNCE = Collections.unmodifiableSet(EnumSet.of(Species.G07, Species.G08, Species.G12, Species.G14, Species.G15,
            Species.G16, Species.G17, Species.G21, Species.G23, Species.G26, Species.G35, Species.G36, Species.G43, Species.G44, Species.G47, Species.G48,
            Species.G49, Species.G50, Species.G51, Species.G54, Species.G55, Species.G83, Species.G124, Species.G164));
    /** 값싼 길: 서 있는 모든 사람에게서 이 블록 넘게 먼 몸은 4틱에 한 번만 옮김(안개 속, 패킷 줄이기). */
    public static final double FAR_MOVE_R = 40;
    /** 표적 나누기: 가장 가까운 서 있는 사람이 PREY_R 블록 안이면 그 거리 + PREY_SLACK 안의 사람 가운데 덜 노려지는 사람(거리 + PREY_LOAD x 노리는 수)을 고른다. */
    public static final double PREY_R = 14, PREY_SLACK = 6, PREY_LOAD = 2.0, PREY_NEAREST_SLACK = 2.5;
    /** 돌아보기: 체력 비율 문턱, 틱, 같은 사람 재사용 틱. */
    public static final double GAZE_FRAC = 0.25;
    public static final int GAZE_TICKS = 40, GAZE_COOLDOWN = 200;

    public final Game game;
    public final Flow flow;
    /** 래그돌 사체(예산, 값은 config.yml ragdoll.*). */
    public final Ragdoll ragdoll;
    private final List<Gabe> live = new ArrayList<>();
    private int ids;

    /** 시험: 모든 tick 멈춤(위치 유지). */
    public boolean frozen;

    private final List<Player> standing = new ArrayList<>();
    private final List<Player> standingView = Collections.unmodifiableList(standing);
    private double[] px = new double[8], py = new double[8], pz = new double[8];
    private long standingAt = Long.MIN_VALUE;

    private final Map<UUID, int[]> aim = new HashMap<>();
    private Player gazeP;
    private long gazeUntil;
    private final Map<UUID, Double> lastFrac = new HashMap<>();
    private final Map<UUID, Long> gazeCd = new HashMap<>();
    private final Map<Long, Long> biteCd = new HashMap<>();
    private final List<Corpse> corpses = new ArrayList<>();
    private final List<Corpse> corpseView = Collections.unmodifiableList(corpses);
    /** 보스 사체(Role.BOSS): {@link #corpses()} 에 안 오름(시체 뜯기, 얼굴 꿰기, 방패, B5 빨아들이기가 보스 사체를 먹지 않게, 2026-11-12 boss_infra). */
    private final List<Corpse> bossCorpses = new ArrayList<>();
    private final List<Corpse> bossCorpseView = Collections.unmodifiableList(bossCorpses);
    private final EnumSet<Species> killedThisRound = EnumSet.noneOf(Species.class);
    /** 이미 기록한 종 코드 고장(종 이름:곳). 같은 고장은 한 번만 기록. */
    private final Set<String> broken = new java.util.HashSet<>();
    /** 시험 훅 보기: 끝 틱, 종(없으면 아무), 옆, 위 오프셋(블록). */
    private record Aim(long until, Species f, double side, double up) {
    }

    private final Map<UUID, Aim> aimAssist = new HashMap<>();
    private final List<int[]> testWalls = new ArrayList<>();
    /** 종마다 실제로 센 디스플레이 수(놓아 본 것 가운데 가장 큰 것). */
    private final EnumMap<Species, Integer> seenCost = new EnumMap<>(Species.class);
    /** 마지막 inRadius 물음(폭발 자리)과 그 틱, 걸린 개체: center() 가 떼 종을 폭발에 가장 가까운 마리로 잰다. */
    private Vector queryAt;
    private long queryTick = Long.MIN_VALUE;
    private final List<Gabe> queryHits = new ArrayList<>();
    /** 지금 처리 중인 피해(damage() 안에서만, 떼 마리 래그돌이 충격 방향을 읽음). */
    private Damage hitNow;
    /** 쏠 수 있는 게이브 아닌 것(진짜 보급 상자 같은). */
    private final List<Shootable> shootables = new ArrayList<>();
    /** 피해 배율 훅(키 → 훅, 건 차례대로 곱함). */
    private final Map<Object, DamageScale> scales = new java.util.LinkedHashMap<>();
    /** 운영자 공격력 배율 받는 범위(/gave 공격력). */
    public static final double PDMG_MIN = 0.1, PDMG_MAX = 10;
    /**
     * 운영자 공격력 배율(/gave 공격력, 설정 playerDamageMul, 기본 1): 사람이 게이브(보스 포함)에게 주는 모든 피해에 {@link #damage(Object, Damage)} 가
     * 곱한다. 사람 편: by 가 사람이거나 how 가 {@link #PDMG_DEVICE}(사람이 없는 사람 편 장치). by 가 사람이어도 게이브가 게이브를 치는 것({@link #PDMG_GABE})은 뺀다.
     */
    private double pdmg = 1.0;
    /** by 없이 나는 사람 편 장치 피해: 트럭 지붕 기관총, 증강 가시 철판, 트럭 부품 가시 철판. */
    private static final Set<String> PDMG_DEVICE = Set.of("aug_turret", "aug_spikes", "trip_spikes");
    /** by 가 사람(보상 몫)이지만 게이브가 게이브를 치는 것: G05 즙, G104 갉기. */
    private static final Set<String> PDMG_GABE = Set.of("g05_juice", "g104");
    private long tickErrAt = Long.MIN_VALUE / 2;
    /** 판 규칙이 걸린 횟수(시험, 기록용): 물어뜯기, 돌아보기, 변함 1, 변함 2, 갈라짐. */
    public int bites, gazes, mut1, mut2, splits;

    // ------------------------------------------------------------------ 공격 기록(밸런스 측정, 2026-11-12 bal12)

    /** 이 거리(수평, 높이 차 NEAR_DY 안) 안에 서 있는 사람이 있으면 '곁에 듦'으로 센다. */
    public static final double NEAR_R = 4.0, NEAR_DY = 4.0;

    /**
     * 종별 공격 기록(웨이브마다 감독이 기록에 한 줄 남기고 지움, 시험 훅 '공격'). 놓인 수, 사람 곁에 든 수와 그때 나이 합, 곁에 있던 틱, 그 가운데
     * 멈춰 있던 틱, 처음 때린 수와 그때 나이 합, 곁에 들고 처음 때리기까지 틱 합, 때린 수, 기본 피해 합(dmgMul 과 방어 전), 실제 깎은 체력 합, 찍은 수
     * (mark 가 표적 없음에서 사람으로), 물어뜯기 수.
     */
    public static final class AtkStat {
        public int spawned, reached, firstHits, hits, marks, nearToHitN, bites;
        public long nearTicks, stillTicks, ageAtNear, ageAtHit, nearToHit;
        public double raw, dealt;
    }

    private final EnumMap<Species, AtkStat> atk = new EnumMap<>(Species.class);
    /** 사람마다 맞은 수(표적 나누기 확인: 이름 → 맞은 수). */
    private final Map<String, int[]> atkPeople = new java.util.LinkedHashMap<>();
    private long atkSince;

    private AtkStat atk(Species sp) {
        return atk.computeIfAbsent(sp, k -> new AtkStat());
    }

    /** 종이 사람을 때림(Gabe.hit 가 Players.hurt 앞에 부름): 공격 기록만. */
    public void noteHit(Gabe g, Player p, double dmg) {
        if (g == null || p == null || dmg <= 0) return;
        PState s = game.players.get(p);
        if (!s.standing()) return;
        AtkStat a = atk(g.sp);
        a.hits++;
        a.raw += dmg;
        atkPeople.computeIfAbsent(p.getName(), k -> new int[1])[0]++;
        a.dealt += Math.min(s.hp, dmg * s.armor * game.director.dmgMul());
        if (g.hitAge < 0) {
            g.hitAge = g.age;
            a.firstHits++;
            a.ageAtHit += g.age;
            if (g.nearAge >= 0) {
                a.nearToHit += Math.max(0, g.age - g.nearAge);
                a.nearToHitN++;
            }
        }
        g.hitAt = game.now();
    }

    /** 2틱마다: 서 있는 사람 곁에 든 개체를 셈(곁 틱, 멈춤 틱, 처음 든 나이). */
    private void nearTick() {
        int n = standing.size();
        if (n == 0) return;
        double r2 = NEAR_R * NEAR_R;
        for (Gabe g : live) {
            if (g.dead || g.noRules) continue;
            boolean near = false;
            for (int i = 0; i < n && !near; i++) {
                double dy = g.pos.getY() - (py[i] + 0.9);
                if (Math.abs(dy) > NEAR_DY) continue;
                double dx = g.pos.getX() - px[i], dz = g.pos.getZ() - pz[i];
                near = dx * dx + dz * dz <= r2;
            }
            if (!near) continue;
            AtkStat a = atk(g.sp);
            a.nearTicks += 2;
            if (g.vel.lengthSquared() < 1e-4) a.stillTicks += 2;
            if (g.nearAge < 0) {
                g.nearAge = g.age;
                a.reached++;
                a.ageAtNear += g.age;
            }
        }
    }

    /** 서 있는 사람(이번 틱 캐시)이 (p.x, p.z) 에서 수평 r 블록 안에 있나. */
    public boolean nearStanding(Vector p, double r) {
        if (standingAt != game.now()) refreshStanding();
        double r2 = r * r;
        for (int i = 0; i < standing.size(); i++) {
            double dx = px[i] - p.getX(), dz = pz[i] - p.getZ();
            if (dx * dx + dz * dz <= r2) return true;
        }
        return false;
    }

    /** 덮치기를 건너뛸 개체: 판 규칙에서 빠짐, 흉내, 지금 안 맞음, 끌림, 물어뜯기 중, 빠지는 종, 누구를 붙잡거나 가두거나 머리에 붙은 개체. */
    private boolean noPounce(Gabe g, long now) {
        if (g.dead || g.noRules || g.disguised || g.untargetable || g.lureUntil > now || g.biteUntil > now || g.age < 20) return true;
        if (NO_POUNCE.contains(g.sp)) return true;
        return holders.contains(g);
    }

    private final Set<Gabe> holders = Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * 판 규칙 '덮치기': 웨이브 중 매 틱. 서 있는 사람 몸에 닿은 게이브가 POUNCE_CD 틱 동안 아무도 안 때렸으면 그 사람을 문다(POUNCE_DMG[위협]). 원인 글귀는
     * 그 종의 cause_gxx(없으면 cause_none). 살짝 밀치고 공용 무는 소리.
     */
    private void pounceTick() {
        int n = standing.size();
        if (n == 0 || game.state != Game.State.WAVE) return;
        long now = game.now();
        holders.clear();
        for (PState s : game.players.all()) {
            if (s.grabbedBy != null) holders.add(s.grabbedBy);
            if (s.eater != null) holders.add(s.eater);
            if (s.headBy != null) holders.add(s.headBy);
        }
        for (int k = 0; k < live.size(); k++) {
            Gabe g = live.get(k);
            if (g.sinceHit() < POUNCE_CD || noPounce(g, now)) continue;
            double reach = g.radius + 0.4 + 0.15, r2 = reach * reach;
            for (int i = 0; i < n; i++) {
                double dy = g.pos.getY() - (py[i] + 0.9);
                if (Math.abs(dy) > 1.2 + g.radius + 0.15) continue;
                double dx = px[i] - g.pos.getX(), dz = pz[i] - g.pos.getZ();
                if (dx * dx + dz * dz > r2) continue;
                Player p = standing.get(i);
                int threat = Math.max(1, Math.min(5, g.sp.threat));
                String key = "cause_" + g.sp.key();
                g.hit(p, POUNCE_DMG[threat], kr.gave.game.Hud.hasPhrase(key) ? key : "cause_none");
                if (g.dead) break;
                face(g, chest(p));
                Vector away = new Vector(dx, 0, dz);
                g.knock(p, away.lengthSquared() > 1e-6 ? away : new Vector(0, 0, 1), 0.16, 0.06);
                pounces++;
                if (!game.director.silent() && now - pounceSoundAt >= 3) {
                    pounceSoundAt = now;
                    Props.soundAround(g.loc(), "gabe.bite", Sound.ENTITY_FOX_BITE, 0.8f, 0.85f + game.random.nextFloat() * 0.2f, 1.1f, 16, -1);
                }
                break;
            }
        }
    }

    private long pounceSoundAt = Long.MIN_VALUE / 2;
    /** 덮치기 수(판마다). */
    public int pounces;

    /** 이번 틱 사람마다 노리는(prey) 게이브 수. */
    private final Map<Player, int[]> preyLoad = new java.util.IdentityHashMap<>();

    private void countPrey() {
        preyLoad.clear();
        for (Gabe g : live) {
            if (g.dead || g.prey == null) continue;
            preyLoad.computeIfAbsent(g.prey, k -> new int[1])[0]++;
        }
    }

    /**
     * 표적 나누기: 가장 가까운 서 있는 사람이 PREY_R 블록(수평) 안이면, 그 거리 + PREY_SLACK 안의 사람 가운데 '거리 + PREY_LOAD x 이미 노리는 게이브 수'가
     * 가장 작은 사람을 노린다(고른 사람이 서 있고 범위 안이면 그대로 둠). 멀면 null(흐름장이 가장 가까운 사람 쪽으로). 30명이 모여 있어도 바깥 몇 명만
     * 맞지 않게, 몰려온 떼가 사람들 사이로 고르게 파고든다. 혼자면 늘 그 사람.
     */
    public Player prey(Gabe g) {
        if (standingAt != game.now()) refreshStanding();
        // 강제 표적(FORCE, 2026-11-12 sp_engine): 둘레에 걸린 사람이 있으면 거리와 상관없이 그 사람
        Player fp = forcedFor(g);
        if (fp != null) {
            if (fp != g.prey) {
                if (g.prey != null) {
                    int[] l = preyLoad.get(g.prey);
                    if (l != null) l[0]--;
                }
                preyLoad.computeIfAbsent(fp, k -> new int[1])[0]++;
            }
            g.prey = fp;
            return fp;
        }
        int n = standing.size();
        if (n == 0) {
            g.prey = null;
            return null;
        }
        double best = Double.MAX_VALUE;
        double[] d = preyDist.length >= n ? preyDist : (preyDist = new double[n * 2]);
        for (int i = 0; i < n; i++) {
            double dx = px[i] - g.pos.getX(), dz = pz[i] - g.pos.getZ();
            d[i] = Math.sqrt(dx * dx + dz * dz);
            best = Math.min(best, d[i]);
        }
        if (best > PREY_R) {
            g.prey = null;
            return null;
        }
        if (g.prey != null) {
            int at = standing.indexOf(g.prey);
            if (at >= 0 && d[at] <= best + PREY_SLACK) return g.prey;
        }
        Player pick = null;
        double ps = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (d[i] > best + PREY_SLACK) continue;
            int[] l = preyLoad.get(standing.get(i));
            double s = d[i] + PREY_LOAD * (l == null ? 0 : l[0]);
            if (s < ps) {
                ps = s;
                pick = standing.get(i);
            }
        }
        if (pick != null && pick != g.prey) {
            if (g.prey != null) {
                int[] l = preyLoad.get(g.prey);
                if (l != null) l[0]--;
            }
            preyLoad.computeIfAbsent(pick, k -> new int[1])[0]++;
        }
        g.prey = pick;
        return pick;
    }

    private double[] preyDist = new double[8];

    /** 반경 안 서 있는 사람 가운데 이 개체가 노릴 사람: 고른 사람(prey)이 r 안이고 가장 가까운 사람보다 PREY_NEAREST_SLACK 넘게 멀지 않으면 그 사람, 아니면 가장 가까운 사람. */
    public Player nearestFor(Gabe g, double r) {
        Player fp = forcedFor(g);
        if (fp != null && chest(fp).distanceSquared(g.pos) <= r * r) return fp;
        Player near = nearestPlayer(g.pos, r);
        Player p = g.prey;
        if (near == null || p == null || p == near || !game.players.get(p).standing()) return near;
        double dp = chest(p).distance(g.pos), dn = chest(near).distance(g.pos);
        return dp <= r && dp <= dn + PREY_NEAREST_SLACK ? p : near;
    }

    /** 공격 기록 지우기(감독이 웨이브 시작에 부름). */
    public void atkReset() {
        atk.clear();
        atkPeople.clear();
        atkSince = game.now();
    }

    /** 공격 기록 한 줄: 종마다 수 reach(곁에 든 수) t(처음 곁에 든 초) d(곁에서 처음 때리기까지 초) hpm(곁 1분에 한 마리가 때린 수) st(곁에서 멈춘 몫) dmg(한 번 기본 피해). */
    public String atkSummary(int max) {
        List<Map.Entry<Species, AtkStat>> rows = new ArrayList<>(atk.entrySet());
        rows.sort((x, y) -> Integer.compare(y.getValue().spawned + y.getValue().hits, x.getValue().spawned + x.getValue().hits));
        StringBuilder sb = new StringBuilder();
        int k = 0, hits = 0;
        double dealt = 0;
        for (Map.Entry<Species, AtkStat> e : rows) {
            AtkStat a = e.getValue();
            hits += a.hits;
            dealt += a.dealt;
            if (k++ >= max) continue;
            sb.append(' ').append(e.getKey().name()).append(':').append(a.spawned).append('/').append(a.reached);
            sb.append(String.format(Locale.ROOT, " t=%.1f d=%s fh=%d hpm=%.1f st=%.2f dmg=%.1f h=%d m=%d b=%d", a.reached == 0 ? -1.0 : a.ageAtNear / 20.0 / a.reached,
                    a.nearToHitN == 0 ? "-" : String.format(Locale.ROOT, "%.1f", a.nearToHit / 20.0 / a.nearToHitN), a.firstHits,
                    a.nearTicks == 0 ? 0.0 : a.hits / (a.nearTicks / 1200.0), a.nearTicks == 0 ? 0.0 : (double) a.stillTicks / a.nearTicks,
                    a.hits == 0 ? 0.0 : a.raw / a.hits, a.hits, a.marks, a.bites)).append(';');
        }
        StringBuilder who = new StringBuilder();
        if (atkPeople.size() > 1) {
            who.append(" 사람");
            for (Map.Entry<String, int[]> e : atkPeople.entrySet()) who.append(' ').append(e.getKey()).append('=').append(e.getValue()[0]);
        }
        return String.format(Locale.ROOT, "hits=%d dealt=%.0f %.0fs", hits, dealt, (game.now() - atkSince) / 20.0) + who + sb;
    }

    private static final int HC = 2, HW = (Flow.W + HC - 1) / HC;
    private final int[] head = new int[HW * HW];
    private int[] link = new int[256];
    private int[] hcell = new int[256];

    private final double[] tickMs = new double[100];
    private int tickN, tickAt;
    private double lastTickMs;
    /** 틱 단계별 시간(나노초): 흐름과 조준, 행동, 적분과 규칙, 몸 옮김, 죽은 것과 시체 풀, 래그돌. 가장 느린 틱의 내역을 '몹시간' 이 보여 준다. */
    private static final String[] PHASES = {"flow", "ai", "move", "body", "corpse", "rag"};
    private final long[] phaseNs = new long[PHASES.length], worstNs = new long[PHASES.length];
    private double worstMs;
    private long worstAt = Long.MIN_VALUE / 2;
    /** 15ms 넘게 걸린 틱(최근 6개): JVM 이 켜진 뒤 ms, 걸린 ms, 단계별 내역. GC, 세이프포인트 기록(-Xlog 의 uptime)과 맞춰 볼 때. */
    private static final double SLOW_MS = 15;
    private static final long JVM_START = java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
    private final String[] slow = new String[6];
    private int slowN;
    private boolean timingLog;
    /** 시험 훅 '패킷': 한 사람에게 나가는 패킷 수와 바이트를 틱마다 센다. */
    private NetMeter meter;
    private long meterSent;
    /** 시험 훅 '몹빠름': 이 종이 이 빠르기 넘게 움직인 틱에 이 종류 피해로 죽인다(돌진 중 죽음). */
    private DamageType fastType;
    private Species fastSp;
    private double fastMin, fastLast;
    private long fastUntil;
    private int fastLeft, fastKilled;
    private static final Set<Object> NONE = Set.of();

    public Mobs(Game game) {
        this.game = game;
        this.flow = new Flow(game);
        this.ragdoll = new Ragdoll(game, this);
        // 운영자 공격력 배율(/gave 공격력): 설정 playerDamageMul, 범위 밖이면 가장자리로
        try {
            double v = game.plugin.getConfig().getDouble("playerDamageMul", 1.0);
            pdmg = Double.isNaN(v) || Double.isInfinite(v) ? 1.0 : Math.max(PDMG_MIN, Math.min(PDMG_MAX, v));
        } catch (RuntimeException e) {
            pdmg = 1.0;
        }
        // 게이브 체력 배율(보스 빼고): 설정 mobHp
        try {
            double v = game.plugin.getConfig().getDouble("mobHp", MOB_HP_DEFAULT);
            mobHp = Double.isNaN(v) || Double.isInfinite(v) || v <= 0 ? MOB_HP_DEFAULT : Math.min(100, v);
        } catch (RuntimeException e) {
            mobHp = MOB_HP_DEFAULT;
        }
    }

    /**
     * 게이브 체력 배율(설정 mobHp, 기본 0.75: 2026-11-12 사용자 "전반적으로 모든 몹의 생명력을 25% 줄여"). 보스(Role.BOSS)가 아닌 모든 개체의 처음 체력
     * (종 기본 체력 sp.hp)에 Gabe 생성자가 곱한다: 감독이 낸 것, 소환, 파생, 덱, 특수, 종이 부르는 것 모두. 그 뒤 감독 체력 배율 같은 곱셈과
     * maxHp 에서 나누는 몫(떼 마리, 마디, 부위)은 저절로 따라온다. 종이 고정 상수로 체력을 다시 정하는 것은 안 따라온다. 보스는 설정 bossHp.
     */
    public static final double MOB_HP_DEFAULT = 0.75;
    public double mobHp = MOB_HP_DEFAULT;

    /** 지금 운영자 공격력 배율. */
    public double pdmg() {
        return pdmg;
    }

    /** 운영자 공격력 배율을 바꾼다(바로 먹음). 범위(PDMG_MIN..PDMG_MAX) 밖이거나 숫자가 아니면 false 로 그대로 둔다. */
    public boolean pdmg(double v) {
        if (Double.isNaN(v) || v < PDMG_MIN - 1e-9 || v > PDMG_MAX + 1e-9) return false;
        pdmg = v;
        return true;
    }

    /** 사람 편 피해인가(공격력 배율을 곱할 것): by 가 사람이거나 사람 편 장치, 게이브끼리 치는 것은 뺌. */
    private static boolean playerSide(Damage d) {
        String how = d.how();
        if (how != null && PDMG_GABE.contains(how)) return false;
        return d.by() != null || (how != null && PDMG_DEVICE.contains(how));
    }

    public int nextId() {
        return ++ids;
    }

    // ------------------------------------------------------------------ 생성, 목록

    /**
     * 종 하나를 at 에 놓는다. child 면 보상 0. 예산이 차거나 클래스가 없거나 종 코드(생성자, build, spawned)가 예외를 던지면 null
     * (child 는 BUDGET, 그 밖은 HARD_CAP). 들여보낼지는 {@link #costOf}(실제로 본 디스플레이 수, 처음엔 sp.cost)로 잰다.
     * 설정 bannedSpecies 로 막은 종({@link SpeciesBan})도 null: /gave 명령 안, 시험 훅이 연 종, 이미 살아 있는 같은 종(분열, 짝)은 통과.
     */
    public Gabe spawn(Species sp, Vector at, boolean child) {
        if (!sp.implemented()) return null;
        if (SpeciesBan.blocks(sp) && !aliveOf(sp)) return null;
        int used = budgetUsed(), cost = costOf(sp);
        if (child ? used + cost > BUDGET : used + cost > HARD_CAP) return null;
        Gabe g;
        try {
            g = sp.create(game);
        } catch (RuntimeException e) {
            if (broken.add(sp.name() + ":create")) game.plugin.getLogger().log(Level.WARNING, "[몹] " + sp.name() + " 생성자 예외", e);
            return null;
        }
        if (g == null) return null;
        g.noReward = child;
        if (child) g.reward = 0;
        Gabe out = place(g, at);
        // 처음 보는 떼 종은 어림(sp.cost)보다 크게 나올 수 있다: child 는 예산을 넘기지 않게 바로 거둠
        if (out != null && child && budgetUsed() > BUDGET) {
            out.remove();
            return null;
        }
        return out;
    }

    /** 만든 개체를 놓는다(몸 만들기, spawned 부르기). build() 나 spawned() 가 예외를 던지면 몸을 지우고 null. */
    public <T extends Gabe> T place(T g, Vector at) {
        g.pos.copy(at);
        for (int i = 0; i < HIST; i++) record(g);
        try {
            g.build();
        } catch (RuntimeException e) {
            fail(g, "build", e);
            return null;
        }
        live.add(g);
        try {
            g.spawned();
        } catch (RuntimeException e) {
            fail(g, "spawned", e);
            return null;
        }
        if (g.body != null) seenCost.merge(g.sp, g.body.count(), Math::max);
        atk(g.sp).spawned++;
        return g;
    }

    /** 이 종 하나가 쓸 디스플레이 어림: 이미 놓아 본 종은 실제로 센 수(떼 종은 마리 + 닻 + 덩어리), 처음이면 sp.cost. */
    public int costOf(Species sp) {
        Integer c = seenCost.get(sp);
        return c == null ? sp.cost : Math.max(sp.cost, c);
    }

    /** 이 종 하나를 더 내보낼 수 있으면 true({@link #costOf} 로 잼). */
    public boolean budgetFor(Species sp) {
        return budgetFor(costOf(sp));
    }

    public Gabe spawn(Species sp, Location at) {
        return spawn(sp, at.toVector(), false);
    }

    public List<Gabe> all() {
        return Collections.unmodifiableList(live);
    }

    /** 살아 있는 수. */
    public int alive() {
        int n = 0;
        for (Gabe g : live) if (!g.dead) n++;
        return n;
    }

    /** 그 종이 하나라도 살아 있나(막은 종의 분열, 짝: 시험으로 이미 들어온 개체). */
    private boolean aliveOf(Species sp) {
        for (Gabe g : live) if (!g.dead && g.sp == sp) return true;
        return false;
    }

    public List<Gabe> near(Vector p, double r, Predicate<Gabe> f) {
        List<Gabe> out = new ArrayList<>();
        double r2 = r * r;
        for (Gabe g : live) if (!g.dead && g.pos.distanceSquared(p) <= r2 && f.test(g)) out.add(g);
        return out;
    }

    public Gabe nearest(Vector p, double r, Predicate<Gabe> f) {
        Gabe best = null;
        double bd = r * r;
        for (Gabe g : live) {
            if (g.dead || !f.test(g)) continue;
            double d = g.pos.distanceSquared(p);
            if (d <= bd) {
                bd = d;
                best = g;
            }
        }
        return best;
    }

    /** 번호로 찾기(없거나 죽었으면 null). */
    public Gabe byId(int id) {
        for (Gabe g : live) if (g.id == id && !g.dead) return g;
        return null;
    }

    /** 이번 틱의 서 있는 사람(Players.standing 을 틱마다 한 번 모은 것, 고치지 말 것). */
    public List<Player> standing() {
        if (standingAt != game.now()) refreshStanding();
        return standingView;
    }

    /** 사람 몸통 가운데(발 + 0.9). */
    public static Vector chest(Player p) {
        Location l = p.getLocation();
        return new Vector(l.getX(), l.getY() + 0.9, l.getZ());
    }

    // ------------------------------------------------------------------ 틱

    public void tick() {
        long t0 = System.nanoTime();
        try {
            step();
        } catch (RuntimeException e) {
            // 엔진 안 어디서 던져도 이 틱만 버리고 나머지 모듈(사람, 총, 상점, HUD)은 돈다. 10초에 한 번만 기록
            long now = game.now();
            if (now - tickErrAt >= 200) {
                tickErrAt = now;
                game.plugin.getLogger().log(Level.WARNING, "[몹] 틱 예외(이 틱은 건너뜀)", e);
            }
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        lastTickMs = ms;
        if (ms >= SLOW_MS) {
            StringBuilder w = new StringBuilder();
            for (int i = 0; i < PHASES.length; i++) w.append(i == 0 ? "" : " ").append(PHASES[i]).append(String.format(Locale.ROOT, "=%.1f", phaseNs[i] / 1e6));
            slow[slowN++ % slow.length] = String.format(Locale.ROOT, "@%dms:%.1fms(%s)", System.currentTimeMillis() - JVM_START, ms, w);
        }
        // 최근 200틱 안에서 가장 느린 틱의 단계별 내역을 남겨 둔다
        if (ms >= worstMs || game.now() - worstAt > 200) {
            worstMs = ms;
            worstAt = game.now();
            System.arraycopy(phaseNs, 0, worstNs, 0, phaseNs.length);
        }
        tickMs[tickAt] = ms;
        tickAt = (tickAt + 1) % tickMs.length;
        tickN = Math.min(tickMs.length, tickN + 1);
        if (timingLog && game.now() % 100 == 0) {
            game.plugin.getLogger().info(String.format(Locale.ROOT, "[몹] 틱 평균 %.3fms 최대 %.3fms 마리 %d 디스플레이 %d 흐름 %.3fms(%d칸)", tickAvg(), tickMax(),
                    alive(), budgetUsed(), flow.lastMs, flow.reached));
        }
    }

    private void step() {
        long t = System.nanoTime(), u;
        java.util.Arrays.fill(phaseNs, 0);
        refreshStanding();
        if (!frozen) {
            flow.tick();
            aimUpdate();
            gazeUpdate();
            phaseNs[0] = (u = System.nanoTime()) - t;
            t = u;
            countPrey();
            int n = live.size();
            for (int i = 0; i < n; i++) {
                Gabe g = live.get(i);
                if (g.dead) continue;
                g.age++;
                behave(g);
            }
            phaseNs[1] = (u = System.nanoTime()) - t;
            t = u;
            integrate();
            separate();
            pushFromPlayers();
            rules();
            pounceTick();
            if ((game.now() & 1) == 0) nearTick();
            for (Gabe g : live) {
                if (g.dead) continue;
                record(g);
                g.buffPrev = g.speedBuff;
                g.speedBuff = 1.0;
                g.dmgBuffPrev = g.dmgBuff;
                g.dmgBuff = 1.0;
            }
            if (fastType != null) fastKill();
            phaseNs[2] = (u = System.nanoTime()) - t;
            t = u;
        }
        boolean silent = game.director.silent();
        long now = game.now();
        int sn = standing.size();
        double farSq = FAR_MOVE_R * FAR_MOVE_R;
        for (int i = 0; i < live.size(); i++) {
            Gabe g = live.get(i);
            if (g.dead || g.body == null) continue;
            g.body.forceFreeze = silent;
            // 값싼 길(2026-11-12): 서 있는 모든 사람에게서 FAR_MOVE_R 블록 넘게 먼 몸(흙먼지 속)은 4틱에 한 번만 옮긴다(패킷 줄이기)
            if (sn > 0 && ((now + g.id) & 3) != 0) {
                double best = Double.MAX_VALUE;
                for (int k = 0; k < sn && best > farSq; k++) {
                    double dx = px[k] - g.pos.getX(), dz = pz[k] - g.pos.getZ();
                    best = Math.min(best, dx * dx + dz * dz);
                }
                if (best > farSq) continue;
            }
            try {
                g.body.move();
            } catch (RuntimeException e) {
                fail(g, "몸", e);
            }
        }
        phaseNs[3] = (u = System.nanoTime()) - t;
        t = u;
        live.removeIf(g -> g.dead);
        corpseTick();
        phaseNs[4] = (u = System.nanoTime()) - t;
        t = u;
        try {
            ragdoll.tick();
        } catch (RuntimeException e) {
            // 래그돌이 던지면 사체를 모두 치우고 계속(판은 멈추지 않음)
            game.plugin.getLogger().log(Level.WARNING, "[몹] 래그돌 틱 예외, 사체를 치움", e);
            for (Corpse c : corpses) c.remove();
            corpses.clear();
            for (Corpse c : bossCorpses) c.remove();
            bossCorpses.clear();
            ragdoll.clear();
        }
        phaseNs[5] = System.nanoTime() - t;
        shotPoll();
        fieldTick();
        aimAssistTick();
        if (meter != null) {
            if (meter.player.isOnline()) meter.tick();
            else meter = null;
        }
    }

    /** 시험 훅 '몹빠름'의 틱: 빠르게 움직인 개체를 옆에서 맞은 것으로 죽인다(이어받은 속도와 충격이 따로 보이게 탄 방향은 움직임과 직각). */
    private void fastKill() {
        if (game.now() > fastUntil || fastLeft <= 0) {
            fastType = null;
            return;
        }
        for (Gabe g : new ArrayList<>(live)) {
            if (g.dead || g.untargetable || (fastSp != null && g.sp != fastSp)) continue;
            Vector v = lastMove(g);
            double sp = v.length();
            if (sp < fastMin) continue;
            Vector dir = new Vector(-v.getZ(), 0, v.getX());
            if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1);
            dir.normalize();
            fastLast = sp;
            if (damage(g, new Damage(99999, fastType, null, center(g), dir, "test", 0))) fastKilled++;
            if (--fastLeft <= 0) break;
        }
    }

    private void refreshStanding() {
        standingAt = game.now();
        standing.clear();
        standing.addAll(game.players.standing());
        int n = standing.size();
        if (px.length < n) {
            px = new double[n * 2];
            py = new double[n * 2];
            pz = new double[n * 2];
        }
        for (int i = 0; i < n; i++) {
            Location l = standing.get(i).getLocation();
            px[i] = l.getX();
            py[i] = l.getY();
            pz[i] = l.getZ();
        }
    }

    private void behave(Gabe g) {
        long now = game.now();
        if (g.biteUntil > now) {
            g.vel.zero();
            if (g.biteWith != null) face(g, g.biteWith.pos);
            g.yaw += (float) (game.random.nextGaussian() * 6);
            return;
        }
        if (g.lureUntil > now) {
            if (g.pos.distanceSquared(g.lureAt) > 1.5 * 1.5) {
                if (g.flying) seek(g, g.lureAt, g.speed());
                else {
                    Vector d = g.lureAt.clone().subtract(g.pos).setY(0);
                    double l = d.length();
                    g.vel.copy(l < 1e-6 ? d : d.multiply(g.speed() / l));
                }
            } else g.vel.zero();
            face(g, g.lureAt);
        } else {
            try {
                g.tick();
            } catch (RuntimeException e) {
                fail(g, "tick", e);
                return;
            }
        }
        Player gz = gazeTarget(g);
        if (gz != null) face(g, chest(gz));
    }

    /** 종 코드가 예외를 던지면 그 개체만 치우고(판은 계속, 붙잡히거나 갇힌 사람은 풀어 줌) 종마다 한 번 기록. */
    private void fail(Gabe g, String where, RuntimeException e) {
        if (broken.add(g.sp.name() + ":" + where)) game.plugin.getLogger().log(Level.WARNING, "[몹] " + g.sp.name() + " " + where + " 예외, 그 개체를 치움", e);
        try {
            g.remove();
        } catch (RuntimeException e2) {
            g.dead = g.removed = true;
            if (g.body != null) g.body.remove();
        }
        try {
            unbind(g);
        } catch (RuntimeException e2) {
            game.plugin.getLogger().log(Level.WARNING, "[몹] 풀어 주기 예외", e2);
        }
    }

    /** 이 개체에 붙잡힌 사람은 놓아 주고, 갇힌 사람과 이 개체로 묶인 죽은 사람(업은, 총 주운)은 상점 앞 부활(Players.freeFrom 이 거름). */
    private void unbind(Gabe g) {
        for (PState s : game.players.all()) {
            if (s.grabbedBy == g) {
                Player p = Bukkit.getPlayer(s.id);
                if (p != null) game.players.release(p);
                else s.grabbedBy = null;
            }
        }
        game.players.freeFrom(g);
    }

    /** 종이 덮어쓴 spheres(): 던지면 그 개체를 치우고 빈 목록. */
    private List<double[]> spheresOf(Gabe g) {
        try {
            List<double[]> s = g.spheres();
            return s == null ? List.of() : s;
        } catch (RuntimeException e) {
            fail(g, "spheres", e);
            return List.of();
        }
    }

    // ------------------------------------------------------------------ 적분

    private void integrate() {
        for (Gabe g : live) {
            if (g.dead) continue;
            double x = g.pos.getX(), y = g.pos.getY(), z = g.pos.getZ();
            double vx = g.vel.getX(), vz = g.vel.getZ();
            if (!Double.isFinite(vx) || !Double.isFinite(vz) || !Double.isFinite(g.vel.getY())) {
                g.vel.zero();
                vx = vz = 0;
            }
            if (g.flying) {
                x += vx;
                z += vz;
                y += g.vel.getY();
                double fl = flow.floor(x, z) + 0.3;
                if (y < fl) y = fl;
            } else {
                if (!g.ghost) {
                    boolean inOpen = flow.walkable(x, z);
                    if (vx != 0 && !passable(g, x, z, x + vx, z, Math.signum(vx), 0, inOpen)) vx = 0;
                    if (vz != 0 && !passable(g, x, z, x + vx, z + vz, 0, Math.signum(vz), inOpen)) vz = 0;
                }
                x += vx;
                z += vz;
                double target = flow.floor(x, z) + g.hover;
                double dy = target - y;
                if (dy > 0) y += Math.min(dy, Math.max(0.12, dy * 0.5));
                else y += Math.max(dy, -0.5);
            }
            // 경계(R_EDGE 밖으로 못 나감): 경기장 안에 있는 몹만
            double ex = x - Plaza.CX, ez = z - Plaza.CZ, ed = Math.sqrt(ex * ex + ez * ez);
            double lim = Plaza.R_EDGE - g.radius;
            if (ed > lim && ed < Plaza.R_EDGE + 24) {
                x = Plaza.CX + ex / ed * lim;
                z = Plaza.CZ + ez / ed * lim;
            }
            g.pos.setX(x).setY(y).setZ(z);
        }
    }

    /** 땅 몹이 (x, z) 에서 (nx, nz) 로 갈 수 있나. 앞 가장자리(반지름 0.8배, 최대 0.36)까지 열린 칸이고 턱이 STEP 이하. */
    private boolean passable(Gabe g, double x, double z, double nx, double nz, double sx, double sz, boolean inOpen) {
        if (!inOpen) return true;
        double m = Math.min(g.radius * 0.8, 0.36);
        double fx = nx + sx * m, fz = nz + sz * m;
        if (!flow.walkable(nx, nz) || !flow.walkable(fx, fz)) return false;
        double here = flow.floor(x, z);
        return flow.floor(nx, nz) - here <= Flow.STEP + 0.05 && flow.floor(fx, fz) - here <= Flow.STEP + 0.05;
    }

    /** 밀어내기로 옮김(땅 몹은 막힌 칸으로는 안 감). */
    private void shove(Gabe g, double dx, double dz) {
        double x = g.pos.getX(), z = g.pos.getZ();
        if (!g.flying && !g.ghost) {
            boolean inOpen = flow.walkable(x, z);
            if (dx != 0 && !passable(g, x, z, x + dx, z, Math.signum(dx), 0, inOpen)) dx = 0;
            if (dz != 0 && !passable(g, x, z, x + dx, z + dz, 0, Math.signum(dz), inOpen)) dz = 0;
        }
        g.pos.setX(x + dx).setZ(z + dz);
    }

    private void separate() {
        int n = live.size();
        if (link.length < n) {
            link = new int[n * 2];
            hcell = new int[n * 2];
        }
        Arrays.fill(head, -1);
        for (int i = 0; i < n; i++) {
            Gabe g = live.get(i);
            hcell[i] = -1;
            if (g.dead) continue;
            int ix = (int) Math.floor(g.pos.getX()) - Plaza.MIN, iz = (int) Math.floor(g.pos.getZ()) - Plaza.MIN;
            if (ix < 0 || iz < 0 || ix >= Flow.W || iz >= Flow.W) continue;
            int c = (iz / HC) * HW + ix / HC;
            hcell[i] = c;
            link[i] = head[c];
            head[c] = i;
        }
        long now = game.now();
        for (int i = 0; i < n; i++) {
            int c = hcell[i];
            if (c < 0) continue;
            Gabe g = live.get(i);
            if (g.dead) continue;
            int cx = c % HW, cz = c / HW, contacts = 0;
            scan:
            for (int dz = -1; dz <= 1; dz++) {
                int zz = cz + dz;
                if (zz < 0 || zz >= HW) continue;
                for (int dx = -1; dx <= 1; dx++) {
                    int xx = cx + dx;
                    if (xx < 0 || xx >= HW) continue;
                    for (int j = head[zz * HW + xx]; j >= 0; j = link[j]) {
                        if (j <= i) continue;
                        Gabe o = live.get(j);
                        if (o.dead) continue;
                        // 물어뜯기는 몸 겹침과 따로 머리 자리로 잰다(head() 를 덮어쓴 종, 띠가 다른 종도)
                        if (g.sp != o.sp) {
                            biteCheck(g, o, now);
                            if (g.dead) break scan;
                            if (o.dead) continue;
                        }
                        double ddx = o.pos.getX() - g.pos.getX(), ddz = o.pos.getZ() - g.pos.getZ(), ddy = o.pos.getY() - g.pos.getY();
                        double rs = g.radius + o.radius;
                        if (Math.abs(ddy) >= rs) continue;
                        double d2 = ddx * ddx + ddz * ddz;
                        if (d2 >= rs * rs) continue;
                        contacts++;
                        if (!g.noPush && !o.noPush) {
                            double d = Math.sqrt(d2);
                            if (d < 1e-4) {
                                double a = (g.id * 2.399963) % (Math.PI * 2);
                                ddx = Math.cos(a);
                                ddz = Math.sin(a);
                                d = 1;
                            }
                            double k = (rs - Math.min(d, rs)) * 0.25 / d;
                            shove(g, -ddx * k, -ddz * k);
                            shove(o, ddx * k, ddz * k);
                        }
                        if (contacts >= 8) break scan;
                    }
                }
            }
        }
    }

    /** 시험 훅 '공격 x 물기 off': 물어뜯기 규칙을 끔(측정만). */
    public boolean biteOff;

    private boolean biteable(Gabe g, long now) {
        return !biteOff && !g.dead && !g.disguised && !g.noRules && !g.noBite && !g.untargetable && g.biteUntil <= now;
    }

    /** 다른 종 둘의 머리(head())가 닿았나(머리 거리 &lt; 반지름 합 + 0.1, 높이 차 &lt; 0.6). 종 코드(head, bitten, damage)가 던지면 그 개체만 치움. */
    private void biteCheck(Gabe a, Gabe b, long now) {
        if (a.sp == b.sp || !biteable(a, now) || !biteable(b, now)) return;
        Vector ha, hb;
        try {
            ha = a.head();
        } catch (RuntimeException e) {
            fail(a, "head", e);
            return;
        }
        try {
            hb = b.head();
        } catch (RuntimeException e) {
            fail(b, "head", e);
            return;
        }
        if (ha == null || hb == null) return;
        double rs = a.radius + b.radius + 0.1;
        if (Math.abs(ha.getY() - hb.getY()) > 0.6 || ha.distanceSquared(hb) > rs * rs) return;
        // 사람을 치는 중(표적을 찍음)이거나 사람 곁(BITE_CALM_R)에 몰린 개체는 서로 물어뜯지 않는다: 몰린 떼가 사람 앞에서 1초씩 멈춰 서로 물던 것(2026-11-12)
        if (a.target != null || b.target != null || nearStanding(a.pos, BITE_CALM_R) || nearStanding(b.pos, BITE_CALM_R)) return;
        long key = a.id < b.id ? ((long) a.id << 32) | b.id : ((long) b.id << 32) | a.id;
        Long cd = biteCd.get(key);
        if (cd != null && cd > now) return;
        biteCd.put(key, now + BITE_TICKS + BITE_COOLDOWN);
        bites++;
        atk(a.sp).bites++;
        atk(b.sp).bites++;
        a.biteUntil = b.biteUntil = now + BITE_TICKS;
        a.biteWith = b;
        b.biteWith = a;
        if (!game.director.silent()) {
            Location at = a.loc().add(b.loc()).multiply(0.5);
            Props.soundAround(at, "gabe.bite", Sound.ENTITY_FOX_BITE, 1f, 0.9f + game.random.nextFloat() * 0.2f, 1.3f, 20, -1);
        }
        try {
            a.bitten(b);
        } catch (RuntimeException e) {
            fail(a, "bitten", e);
        }
        try {
            b.bitten(a);
        } catch (RuntimeException e) {
            fail(b, "bitten", e);
        }
        Damage da = new Damage(a.maxHp * BITE_FRAC, DamageType.BITE, null, a.pos.clone(), null, "bite", 0);
        Damage db = new Damage(b.maxHp * BITE_FRAC, DamageType.BITE, null, b.pos.clone(), null, "bite", 0);
        if (!a.dead) {
            try {
                a.damage(da);
            } catch (RuntimeException e) {
                fail(a, "damage", e);
            }
        }
        if (!b.dead) {
            try {
                b.damage(db);
            } catch (RuntimeException e) {
                fail(b, "damage", e);
            }
        }
    }

    private void pushFromPlayers() {
        int n = standing.size();
        if (n == 0) return;
        for (Gabe g : live) {
            if (g.dead || g.noPush || g.ghost) continue;
            for (int i = 0; i < n; i++) {
                double dy = g.pos.getY() - py[i];
                if (dy < -g.radius || dy > 1.9 + g.radius) continue;
                double dx = g.pos.getX() - px[i], dz = g.pos.getZ() - pz[i];
                double rs = g.radius + 0.35, d2 = dx * dx + dz * dz;
                if (d2 >= rs * rs) continue;
                double d = Math.sqrt(d2);
                if (d < 1e-4) {
                    dx = 1;
                    d = 1;
                }
                double k = (rs - Math.min(d, rs)) * 0.5 / d;
                shove(g, dx * k, dz * k);
            }
        }
    }

    // ------------------------------------------------------------------ 판 규칙

    private void rules() {
        long now = game.now();
        if (game.state == Game.State.WAVE) {
            double mul = Math.max(0.05, game.director.mutTimeMul());
            int t1 = (int) (MUT1 * mul), t2 = (int) (MUT2 * mul), t3 = (int) (MUT3 * mul);
            int n = live.size();
            for (int i = 0; i < n; i++) {
                Gabe g = live.get(i);
                if (g.dead || g.noMutate || g.noRules || g.disguised) continue;
                if (g.mutStage < 1 && g.age >= t1) {
                    g.mutStage = 1;
                    mut1++;
                    if (g.body != null) g.body.look.mut = 1;
                }
                if (g.mutStage < 2 && g.age >= t2) {
                    g.mutStage = 2;
                    mut2++;
                    if (g.body != null) g.body.look.mut = 2;
                }
                if (g.mutStage < 3 && g.age >= t3) {
                    g.mutStage = 3;
                    split(g);
                }
            }
        }
        if (now % 200 == 0) biteCd.values().removeIf(t -> t <= now);
    }

    /** 오래 살면 변함 60초: 얼굴이 갈라져 둘(이 개체 체력 절반, 같은 종 보상 0 하나 더, 예산 안에서만). */
    private void split(Gabe g) {
        if (g.hp < 2 || !budgetFor(g.sp)) return;
        double yawR = Math.toRadians(-g.yaw);
        Vector side = new Vector(Math.cos(yawR), 0, -Math.sin(yawR)).multiply(0.5);
        Gabe c = spawn(g.sp, g.pos.clone().add(side), true);
        if (c == null) return;
        splits++;
        g.hp /= 2;
        c.maxHp = g.maxHp;
        c.hp = g.hp;
        c.age = g.age;
        c.mutStage = 3;
        c.yaw = g.yaw;
        if (c.body != null) c.body.look.mut = 2;
        g.pos.subtract(side);
        if (!game.director.silent())
            Props.soundAround(g.loc(), "gabe.split", Sound.ENTITY_SLIME_SQUISH, 1f, 0.9f + game.random.nextFloat() * 0.2f, 0.7f, 20, -1);
    }

    /** 판 규칙이 거는 빠르기 배율(오래 살면 변함 40초 x1.25). */
    public double ruleSpeed(Gabe g) {
        return g.mutStage >= 2 ? 1.25 : 1.0;
    }

    private void gazeUpdate() {
        long now = game.now();
        for (Player p : standing) {
            UUID id = p.getUniqueId();
            double f = game.players.get(p).hpFrac();
            Double prev = lastFrac.put(id, f);
            if (prev != null && prev >= GAZE_FRAC && f < GAZE_FRAC) {
                Long cd = gazeCd.get(id);
                if (cd == null || cd <= now) {
                    gazeP = p;
                    gazes++;
                    gazeUntil = now + GAZE_TICKS;
                    gazeCd.put(id, now + GAZE_COOLDOWN);
                }
            }
        }
        if (gazeP != null) {
            PState s = gazeP.isOnline() ? game.players.get(gazeP) : null;
            if (s == null || !s.standing() || s.hpFrac() >= GAZE_FRAC || now >= gazeUntil) gazeP = null;
        }
    }

    /** 돌아보기 규칙으로 g 가 지금 바라볼 사람(없거나 g 가 빠지는 개체면 null). */
    public Player gazeTarget(Gabe g) {
        if (gazeP == null || g.dead || g.disguised || g.noRules) return null;
        return gazeP;
    }

    /** 돌아보기 규칙이 지금 걸린 사람(없으면 null). */
    public Player gaze() {
        return gazeP;
    }

    // ------------------------------------------------------------------ 위치 기록, 되돌리기

    private static void record(Gabe g) {
        int i = g.histAt * 3;
        g.hist[i] = g.pos.getX();
        g.hist[i + 1] = g.pos.getY();
        g.hist[i + 2] = g.pos.getZ();
        g.histAt = (g.histAt + 1) % HIST;
        g.histN = Math.min(HIST, g.histN + 1);
    }

    /** k 틱 전 위치 - 지금 위치(out 에 x,y,z). 기록이 모자라면 가장 오래된 것. */
    private static void pastOffset(Gabe g, int k, double[] out) {
        if (k <= 0 || g.histN <= 1) {
            out[0] = out[1] = out[2] = 0;
            return;
        }
        int kk = Math.min(k, g.histN - 1);
        int idx = ((g.histAt - 1 - kk) % HIST + HIST) % HIST * 3;
        out[0] = g.hist[idx] - g.pos.getX();
        out[1] = g.hist[idx + 1] - g.pos.getY();
        out[2] = g.hist[idx + 2] - g.pos.getZ();
    }

    /** 이 사람 화면에서 몹이 늦게 보이는 틱(핑 + 보간, 최대 MAX_LAG). */
    public static int lagTicks(Player p) {
        if (p == null) return 0;
        return Math.min(MAX_LAG, (int) Math.round(p.getPing() / 50.0) + INTERP_LAG);
    }

    // ------------------------------------------------------------------ 조준 머묾

    private void aimUpdate() {
        if (live.isEmpty()) {
            aim.clear();
            return;
        }
        for (Player p : standing) {
            Location eye = p.getEyeLocation();
            Vector o = eye.toVector(), d = eye.getDirection();
            RayTraceResult rb = game.world.rayTraceBlocks(eye, d, AIM_RANGE, FluidCollisionMode.NEVER, true);
            double lim = rb == null ? AIM_RANGE : rb.getHitPosition().distance(o);
            Hit h = castGabes(p, o, d, lim, NONE);
            int gid = h == null ? 0 : ((Gabe) h.target()).id;
            int[] a = aim.computeIfAbsent(p.getUniqueId(), k -> new int[2]);
            if (gid != 0 && gid == a[0]) a[1]++;
            else {
                a[0] = gid;
                a[1] = gid == 0 ? 0 : 1;
            }
        }
    }

    /** 이 사람이 쏘기 전 이 개체에 조준선을 둔 틱. */
    public int aimTicks(UUID shooter, Gabe g) {
        int[] a = aim.get(shooter);
        return a != null && g != null && a[0] == g.id ? a[1] : 0;
    }

    // ------------------------------------------------------------------ 움직임 도우미(Gabe 가 부름)

    public void follow(Gabe g, double speed) {
        // 사람 곁(PREY_R)에서는 노릴 사람을 나눠 고르고(prey) 그 사람에게 곧장. 멀면 흐름장(가장 가까운 사람)
        Player pr = prey(g);
        if (g.flying) {
            Player t = pr != null ? pr : nearestPlayer(g.pos, 1000);
            Vector to = t != null ? chest(t) : Plaza.center(game.world).toVector();
            Vector d = to.subtract(g.pos).setY(0);
            double l = d.length();
            if (l < 1e-6) d.zero();
            else d.multiply(speed / l);
            double dy = flow.floor(g.pos.getX(), g.pos.getZ()) + g.hover - g.pos.getY();
            d.setY(Math.max(-speed, Math.min(speed, dy * 0.2)));
            g.vel.copy(d);
            return;
        }
        if (pr != null && pr != flow.target(g.pos.getX(), g.pos.getZ())) {
            // 흐름장이 가리키는 사람과 다른 사람을 고름: 그 사람에게 곧장(평지 황야). 바로 앞 칸이 막혔으면 흐름장대로
            double dx = pr.getLocation().getX() - g.pos.getX(), dz = pr.getLocation().getZ() - g.pos.getZ(), l = Math.sqrt(dx * dx + dz * dz);
            if (l > 1e-6 && (g.ghost || flow.walkable(g.pos.getX() + dx / l * 0.8, g.pos.getZ() + dz / l * 0.8))) {
                g.vel.setX(dx / l * speed).setY(0).setZ(dz / l * speed);
                return;
            }
        }
        Vector d = flow.dir(g.pos);
        g.vel.setX(d.getX() * speed).setY(0).setZ(d.getZ() * speed);
    }

    public void seek(Gabe g, Vector to, double speed) {
        Vector d = to.clone().subtract(g.pos);
        double l = d.length();
        if (l < 1e-6) g.vel.zero();
        else g.vel.copy(d.multiply(Math.min(speed, l) / l));
    }

    public void face(Gabe g, Vector at) {
        Vector d = at.clone().subtract(g.pos);
        if (d.lengthSquared() < 1e-8) return;
        g.yaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
        g.pitch = (float) Math.toDegrees(-Math.atan2(d.getY(), Math.hypot(d.getX(), d.getZ())));
    }

    /** 수평으로 maxDeg 씩만 돌아섬(pitch 는 0 쪽으로). 다 돌았으면 true. */
    public boolean turnTo(Gabe g, Vector at, float maxDeg) {
        Vector d = at.clone().subtract(g.pos);
        float want = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
        float diff = (want - g.yaw) % 360f;
        if (diff > 180f) diff -= 360f;
        if (diff < -180f) diff += 360f;
        g.pitch *= 0.8f;
        if (Math.abs(diff) <= maxDeg) {
            g.yaw = want;
            return true;
        }
        g.yaw += Math.signum(diff) * maxDeg;
        return false;
    }

    /** 하늘 선회(Gabe.circle). */
    public void circle(Gabe g, Vector c, double r, double speed, int dir) {
        double dx = g.pos.getX() - c.getX(), dz = g.pos.getZ() - c.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 1e-4) {
            dx = 1;
            d = 1;
        }
        double nx = dx / d, nz = dz / d;
        double tx = -nz * dir, tz = nx * dir;
        double radial = (r - d) * 0.15;
        double vx = tx * speed + nx * radial, vz = tz * speed + nz * radial;
        double vl = Math.sqrt(vx * vx + vz * vz);
        if (vl > speed * 1.5) {
            vx *= speed * 1.5 / vl;
            vz *= speed * 1.5 / vl;
        }
        double vy = Math.max(-speed, Math.min(speed, (c.getY() - g.pos.getY()) * 0.2));
        g.vel.setX(vx).setY(vy).setZ(vz);
    }

    public Player nearestPlayer(Vector p, double r) {
        if (standingAt != game.now()) refreshStanding();
        Player best = null;
        double bd = r * r;
        for (int i = 0; i < standing.size(); i++) {
            double dx = px[i] - p.getX(), dy = py[i] + 0.9 - p.getY(), dz = pz[i] - p.getZ();
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= bd) {
                bd = d;
                best = standing.get(i);
            }
        }
        return best;
    }

    /** 반경 안 가장 가까운, 몸 중심에서 눈까지 블록에 가리지 않은 서 있는 사람. */
    public Player acquire(Gabe g, double r) {
        if (standingAt != game.now()) refreshStanding();
        Player fp = forcedFor(g);
        if (fp != null && chest(fp).distanceSquared(g.pos) <= r * r && clearLine(g.pos, fp.getEyeLocation().toVector())) return fp;
        Player best = null;
        double bd = r * r;
        for (int i = 0; i < standing.size(); i++) {
            double dx = px[i] - g.pos.getX(), dy = py[i] + 0.9 - g.pos.getY(), dz = pz[i] - g.pos.getZ();
            double d = dx * dx + dy * dy + dz * dz;
            if (d > bd) continue;
            Player p = standing.get(i);
            if (!clearLine(g.pos, p.getEyeLocation().toVector())) continue;
            bd = d;
            best = p;
        }
        return best;
    }

    /** a 에서 b 까지 블록에 막히지 않았나. */
    public boolean clearLine(Vector a, Vector b) {
        Vector d = b.clone().subtract(a);
        double l = d.length();
        if (l < 1e-6) return true;
        RayTraceResult r = game.world.rayTraceBlocks(a.toLocation(game.world), d.multiply(1 / l), l, FluidCollisionMode.NEVER, true);
        return r == null;
    }

    public void mark(Gabe g, Player p) {
        if (p != null && g.target == null) atk(g.sp).marks++;
        g.target = p;
        if (g.body != null) g.body.look.open = p != null;
    }

    public boolean touches(Gabe g, Player p, double reach) {
        Vector c = chest(p);
        double r = g.radius + 0.4 + reach;
        return Math.abs(g.pos.getY() - c.getY()) < 1.2 + g.radius + reach && c.clone().setY(0).distanceSquared(g.pos.clone().setY(0)) <= r * r;
    }

    // ------------------------------------------------------------------ 맞음, 죽음, 보상

    /** 맞은 뒤(피해 기록, 맞은 연출). Gabe.damage 가 부름. 실제로 깎인 양은 amount + min(0, hp). */
    public void onHurt(Gabe g, Damage d) {
        onHurt(g, d, d.amount() + Math.min(0, g.hp));
    }

    /** 맞은 뒤, 실제로 깎인 양 effective(떼처럼 체력 모양이 다른 종이 직접 부름). */
    public void onHurt(Gabe g, Damage d, double effective) {
        Player by = d.by();
        if (by != null && effective > 0) {
            if (g.contrib == null) g.contrib = new HashMap<>();
            g.contrib.merge(by.getUniqueId(), effective, Double::sum);
            game.players.get(by).dealt += effective;
            if (g.hp > 0) game.hud.hit(by, false);
        }
        if (g.body != null && d.type() != DamageType.BITE) g.body.flash(game.worldTime() + 3);
        long now = game.now();
        if (now - g.hurtSoundAt >= 4 && d.type() != DamageType.BITE && !game.director.silent()) {
            g.hurtSoundAt = now;
            Props.soundAround(g.loc(), "gabe." + g.sp.key() + ".hurt", Sound.ENTITY_SLIME_SQUISH_SMALL, 0.8f, 0.92f + game.random.nextFloat() * 0.16f,
                    1.8f, 16, -1);
        }
    }

    /** 죽은 뒤(보상, 시체, 몸 치우기). Gabe.die 가 부름. */
    public void onDeath(Gabe g, Damage d) {
        int total = g.noReward ? 0 : g.reward;
        if (total > 0) payout(g, d, total);
        Player killer = d == null ? null : d.by();
        if (killer != null && killer.isOnline()) {
            game.players.get(killer).kills++;
            game.hud.hit(killer, true);
            if (killedThisRound.add(g.sp)) game.hud.firstKilled(g.sp);
        }
        // 갇힌 사람(eater)과 죽음 결과로 묶인 사람(deathMob: 업은 G38, 총 주운 G52) 모두: freeFrom 이 안에서 거른다
        unbind(g);
        if (!game.director.silent())
            Props.soundAround(g.loc(), "gabe." + g.sp.key() + ".die", Sound.ENTITY_SLIME_SQUISH, 1f, 0.9f + game.random.nextFloat() * 0.2f, 1.3f, 24, -1);
        if (g.body == null) return;
        // 몸에 붙은 글 줄(NAMETAG)은 사체에 남기지 않는다
        g.body.dropTags();
        if (!g.leaveCorpse) {
            g.body.remove();
            return;
        }
        // 래그돌 사체(맞은 쪽으로 튕겨 구름). 예산이 없거나 꺼져 있으면 예전처럼 제자리에서 납작해짐
        boolean rag;
        try {
            rag = ragdoll.start(g, d);
        } catch (RuntimeException e) {
            if (broken.add("ragdoll:start")) game.plugin.getLogger().log(Level.WARNING, "[몹] 래그돌 시작 예외(납작 사체로)", e);
            rag = false;
        }
        if (rag) return;
        if (!g.body.removed && g.body.corpse(8)) addCorpse(g.sp, g.pos.clone().setY(g.floor()), g.body, null, g.ragHold > 0 ? g.ragHold : ragdoll.flatLife(g));
        else g.body.remove();
    }

    /** 죽기 직전 한 틱 동안 움직인 만큼(블록/틱, 최대 1.2). 래그돌 사체가 이어받는 속도. */
    Vector lastMove(Gabe g) {
        if (g.histN < 2) return new Vector();
        int a = ((g.histAt - 1) % HIST + HIST) % HIST * 3, b = ((g.histAt - 2) % HIST + HIST) % HIST * 3;
        // 이 틱에 이미 옮겼으면(기록 전) 그 움직임, 아니면 마지막 두 기록의 차이
        Vector v = new Vector(g.pos.getX() - g.hist[a], g.pos.getY() - g.hist[a + 1], g.pos.getZ() - g.hist[a + 2]);
        if (v.lengthSquared() < 1e-8) v = new Vector(g.hist[a] - g.hist[b], g.hist[a + 1] - g.hist[b + 1], g.hist[a + 2] - g.hist[b + 2]);
        double l = v.length();
        if (!Double.isFinite(l)) return new Vector();
        if (l > 1.2) v.multiply(1.2 / l);
        return v;
    }

    /**
     * 피해 배율 훅: 게이브가 {@link #damage(Object, Damage)} 로 받는 모든 피해(총 직격, 산탄, 저격, 수류탄, 폭발탄, 화염탄, 화염병, 연쇄)에
     * 곱할 배율을 돌려준다(1 이면 그대로, 0 이면 막음). 피해 종류는 {@link Damage#type()}. 판 전체 저항(G51 배움)처럼 종이 남의 피해를
     * 바꿀 때 쓴다. 훅 안에서 피해를 다시 주지 말 것.
     */
    @FunctionalInterface
    public interface DamageScale {
        double scale(Gabe target, Damage d);
    }

    /**
     * 게이브가 아닌데 총으로 쏠 수 있는 것(진짜 보급 상자 kr.gave.game.Crates). 총 광선이 게이브보다 먼저 닿으면 그것이 맞고
     * {@link #damage(Object, Damage)} 가 {@link #shot} 으로 넘긴다. 수류탄 반경({@link #inRadius})에는 들지 않는다.
     */
    public interface Shootable {
        /** o 에서 dir(단위)로 max 안에 닿는 거리, 안 닿으면 +무한. */
        double rayHit(Vector o, Vector dir, double max);

        /** 맞음. 죽은 것으로 칠 때만 true(보통 false: 처치, 흡혈 증강이 안 걸리게). */
        boolean shot(Damage d);

        /** 맞는 가운데. */
        Vector center();

        /** 사라졌으면 true(목록에서 빠짐). */
        boolean gone();
    }

    public void addShootable(Shootable s) {
        if (s != null && !shootables.contains(s)) shootables.add(s);
    }

    public void removeShootable(Shootable s) {
        shootables.remove(s);
    }

    /** 피해 배율 훅을 건다(같은 키로 다시 걸면 바꿈). 판이 바뀌어도 남으니 건 쪽이 판을 살펴 1 을 돌려주거나 {@link #unscale} 한다. */
    public void scale(Object key, DamageScale s) {
        scales.put(key, s);
    }

    /** 피해 배율 훅을 뗀다. */
    public void unscale(Object key) {
        scales.remove(key);
    }

    private double scaleOf(Gabe g, Damage d) {
        if (scales.isEmpty()) return 1;
        double m = 1;
        for (DamageScale s : scales.values()) {
            try {
                m *= Math.max(0, s.scale(g, d));
            } catch (RuntimeException e) {
                fail(g, "damage", e);
            }
        }
        return m;
    }

    /** 지금 처리 중인 피해(HitTargets.damage 안에서만, 아니면 null). */
    public Damage hitNow() {
        return hitNow;
    }

    /** 이 틱의 폭발 자리(마지막 inRadius 물음 자리), 이 틱에 물음이 없었으면 null. */
    public Vector blastAt() {
        return queryAt != null && queryTick == game.now() ? queryAt : null;
    }

    /** 보상을 피해 기여 비율로 나눠 줌(가장 큰 나머지 순으로 원 단위를 맞춤). 기여가 없으면 처치한 사람에게 모두. */
    private void payout(Gabe g, Damage d, int total) {
        Map<UUID, Double> c = g.contrib;
        if (c == null || c.isEmpty()) {
            if (d != null && d.by() != null && d.by().isOnline()) game.players.addMoney(d.by(), total);
            return;
        }
        double sum = 0;
        for (double v : c.values()) sum += v;
        if (sum <= 0) return;
        List<UUID> ids = new ArrayList<>(c.keySet());
        int[] give = new int[ids.size()];
        double[] frac = new double[ids.size()];
        int given = 0;
        for (int i = 0; i < ids.size(); i++) {
            double share = total * c.get(ids.get(i)) / sum;
            give[i] = (int) Math.floor(share);
            frac[i] = share - give[i];
            given += give[i];
        }
        for (int left = total - given; left > 0; left--) {
            int best = 0;
            for (int i = 1; i < frac.length; i++) if (frac[i] > frac[best]) best = i;
            give[best]++;
            frac[best] = -1;
        }
        // 2026-11-12 사용자 '어시스트 했을 때 얻는 돈도 늘려봐': 잡은 사람 몫은 그대로, 함께 때린 사람 몫은 assistMul 배(기본 2).
        // 피해를 10% 넘게 준 사람은 몫이 0 원이어도 1 원은 받는다
        double assistMul = Math.max(1.0, game.plugin.getConfig().getDouble("assistMul", 2.0));
        UUID killer = d != null && d.by() != null ? d.by().getUniqueId() : null;
        for (int i = 0; i < ids.size(); i++) {
            if (ids.get(i).equals(killer)) continue;
            double part = c.get(ids.get(i)) / sum;
            int base = give[i] > 0 ? give[i] : part >= 0.1 ? 1 : 0;
            give[i] = (int) Math.round(base * assistMul);
        }
        for (int i = 0; i < ids.size(); i++) {
            if (give[i] <= 0) continue;
            Player p = Bukkit.getPlayer(ids.get(i));
            if (p != null && p.isOnline()) game.players.addMoney(p, give[i]);
        }
    }

    /** 이 개체에 사람별로 준 피해(읽기용 사본). */
    public Map<UUID, Double> contributions(Gabe g) {
        return g.contrib == null ? Map.of() : Map.copyOf(g.contrib);
    }

    // ------------------------------------------------------------------ 시체 풀

    /** 납작 사체 하나를 시체 풀에. 납작 사체만 CORPSE_MAX 까지(넘으면 오래된 납작 사체부터 치움). */
    void addCorpse(Species sp, Vector pos, Body body, ItemDisplay single, int life) {
        long now = game.now();
        if (sp != null && sp.is(Role.BOSS)) {
            bossCorpses.add(new Corpse(sp, pos, now, now + life, body, single));
            return;
        }
        corpses.add(new Corpse(sp, pos, now, now + life, body, single));
        int flat = 0;
        for (Corpse c : corpses) if (c.doll == null) flat++;
        for (Iterator<Corpse> it = corpses.iterator(); flat > CORPSE_MAX && it.hasNext();) {
            Corpse c = it.next();
            if (c.doll != null) continue;
            c.remove();
            it.remove();
            flat--;
        }
    }

    /** 래그돌 사체 하나를 시체 풀에(Ragdoll 이 부름). 수는 Ragdoll 예산이 정한다. */
    Corpse addRagCorpse(Species sp, Vector pos, Body body, ItemDisplay single, Ragdoll.Doll doll, long until) {
        Corpse c = new Corpse(sp, pos, game.now(), until, body, single);
        c.doll = doll;
        if (sp != null && sp.is(Role.BOSS)) bossCorpses.add(c);
        else corpses.add(c);
        return c;
    }

    void addCorpse(Species sp, Vector pos, ItemDisplay single, int life) {
        addCorpse(sp, pos, null, single, life);
    }

    private void corpseTick() {
        long now = game.now();
        corpseTick(corpses, now);
        if (!bossCorpses.isEmpty()) corpseTick(bossCorpses, now);
    }

    private static void corpseTick(List<Corpse> l, long now) {
        for (Iterator<Corpse> it = l.iterator(); it.hasNext();) {
            Corpse c = it.next();
            if (c.gone || c.until <= now) {
                c.remove();
                it.remove();
            }
        }
    }

    /** 지금 남은 시체들(오래된 것부터, 읽기 전용). 보스 사체(Role.BOSS)는 빠진다({@link #bossCorpses()}): 다른 종이 고르거나 먹지 않게. */
    public List<Corpse> corpses() {
        return corpseView;
    }

    /** 보스 사체(굳은 채 Gabe.ragHold 틱 남음, 읽기 전용). 보스 담당만 쓴다(보급 상자 자리 같은 것). */
    public List<Corpse> bossCorpses() {
        return bossCorpseView;
    }

    /** 시체 하나를 치움(먹힘). 이미 없었으면 false. */
    public boolean eat(Corpse c) {
        if (c == null || c.gone) return false;
        c.remove();
        return true;
    }

    // ------------------------------------------------------------------ 떼

    /**
     * 무리 닻 만들기: owner.body 가 없으면 빈 닻(Body.carrier)을 만들어 붙이고, 마리 n 개를 같은 모델로 승객에 태운다(대형 자리는 0, 종이
     * {@link Swarm.Member#home} 을 채운 뒤 formation 을 부름). 15블록 밖 사람에게는 덩어리(상태 "lod") 하나만 보인다.
     */
    public Swarm swarm(Gabe owner, String model, int n) {
        if (owner.body == null) owner.body = Body.carrier(owner);
        Swarm s = new Swarm(owner, owner.body, model);
        owner.body.swarm = s;
        for (int i = 0; i < n; i++) s.add(new Vector(), "");
        s.makeLod();
        return s;
    }

    // ------------------------------------------------------------------ 새 종 갈고리(2026-11-12 sp_engine, docs/모듈/API.md '새 종 갈고리')

    /** FORCE 한 줄: 사람, 반경(0 이하 = 판 전체), 끝 틱. */
    private static final class Force {
        Player p;
        double r;
        long until;
    }

    private final Map<Object, Force> forces = new java.util.LinkedHashMap<>();

    /**
     * FORCE(강제 표적): 이 사람 둘레 r 블록(수평, 0 이하면 판 전체) 안의 게이브가 ticks 동안 이 사람만 노린다. 엔진 도우미가 읽는다: 표적 나누기
     * ({@link #prey}, 그래서 {@code follow} 가 거리와 상관없이 그 사람에게 곧장), {@code Gabe.nearest(r)}, {@code Gabe.acquire(r)}(반경 안이면 그 사람).
     * 제 표적을 따로 고르는 종 코드는 {@link #forcedFor} 를 읽어야 따른다. 키마다 하나(같은 키로 다시 걸면 바꿈, p null 이나 ticks 0 이면 뗌).
     * 보스, 흉내 중, noRules 개체는 빠진다. 여럿이 걸리면 가장 가까운 사람.
     */
    public void force(Object key, Player p, double r, int ticks) {
        if (key == null) return;
        if (p == null || ticks <= 0) {
            forces.remove(key);
            return;
        }
        Force f = forces.computeIfAbsent(key, k -> new Force());
        f.p = p;
        f.r = r;
        f.until = game.now() + ticks;
    }

    /** 강제 표적 뗌. */
    public void unforce(Object key) {
        forces.remove(key);
    }

    /** 이 개체에 걸린 강제 표적(서 있는 사람만, 없으면 null). FORCE */
    public Player forcedFor(Gabe g) {
        if (forces.isEmpty() || g == null || g.dead || g.noRules || g.disguised || g.sp.is(Role.BOSS)) return null;
        long now = game.now();
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Iterator<Force> it = forces.values().iterator(); it.hasNext();) {
            Force f = it.next();
            if (now >= f.until || f.p == null || !f.p.isOnline()) {
                it.remove();
                continue;
            }
            if (!game.players.get(f.p).standing()) continue;
            Location l = f.p.getLocation();
            double dx = l.getX() - g.pos.getX(), dz = l.getZ() - g.pos.getZ(), d = dx * dx + dz * dz;
            if (f.r > 0 && d > f.r * f.r) continue;
            if (d < bd) {
                bd = d;
                best = f.p;
            }
        }
        return best;
    }

    /**
     * RELOCATE: 개체를 at 으로 한 번에 옮긴다(몸 보간을 끊음: 다음 틱에 닻이 미끄러지지 않고 그 자리에 나타남). 위치 기록(핑 되돌림)을 새 자리로 채우고
     * 속도, 끌림, 표적 나누기를 지운다. 땅 몹은 다음 틱에 엔진이 바닥 + hover 로 높이를 맞춘다. 보스, 죽은 개체는 false.
     */
    public boolean relocate(Gabe g, Vector at) {
        if (g == null || g.dead || at == null || g.sp.is(Role.BOSS)) return false;
        g.pos.copy(at);
        g.vel.zero();
        g.histN = 0;
        for (int i = 0; i < HIST; i++) record(g);
        if (g.prey != null) {
            int[] l = preyLoad.get(g.prey);
            if (l != null) l[0]--;
            g.prey = null;
        }
        g.lureUntil = 0;
        if (g.body != null) g.body.warp();
        return true;
    }

    /**
     * BIG(불길, 냉기 원뿔 같은 줄기): 눈 o 에서 단위 방향 d 로 max 블록까지의 줄기에 가장 가까운 거대한 몸의 점(줄기가 구를 지나면 줄기 위 점, 아니면
     * 구 표면에서 줄기 쪽 점, 줄기에서 본 각이 가장 작은 구). 거대한 몸이 아니면 {@link #center}. 줄기 무기는 이 점으로 원뿔 각을 잰다.
     */
    public Vector aimPoint(Object target, Vector o, Vector d, double max) {
        if (!(target instanceof Gabe g) || !g.big) return center(target);
        Vector best = null;
        double bc = -2;
        for (double[] s : spheresOf(g)) {
            double ox = s[0] - o.getX(), oy = s[1] - o.getY(), oz = s[2] - o.getZ();
            double t = Math.max(0, Math.min(max, ox * d.getX() + oy * d.getY() + oz * d.getZ()));
            double px = o.getX() + d.getX() * t, py = o.getY() + d.getY() * t, pz = o.getZ() + d.getZ() * t;
            double mx = px - s[0], my = py - s[1], mz = pz - s[2], m = Math.sqrt(mx * mx + my * my + mz * mz);
            if (m > s[3] && m > 1e-9) {
                double k = s[3] / m;
                px = s[0] + mx * k;
                py = s[1] + my * k;
                pz = s[2] + mz * k;
            }
            double vx = px - o.getX(), vy = py - o.getY(), vz = pz - o.getZ(), vl = Math.sqrt(vx * vx + vy * vy + vz * vz);
            double c = vl < 1e-9 ? 1 : (vx * d.getX() + vy * d.getY() + vz * d.getZ()) / vl;
            if (c > bc) {
                bc = c;
                best = new Vector(px, py, pz);
            }
        }
        return best != null ? best : center(target);
    }

    // FIRELOG: 서 있는 사람이 쏜 기록(사람마다 틱마다 한 줄: 눈 자리, 늘어난 발 수)과 폭발 기록
    private static final int SHOT_LOG = 4096, BLAST_LOG = 256, LOG_MAX_TICKS = 200;
    private final double[] slx = new double[SHOT_LOG], sly = new double[SHOT_LOG], slz = new double[SHOT_LOG];
    private final long[] slt = new long[SHOT_LOG];
    private final int[] sln = new int[SHOT_LOG];
    private final UUID[] slu = new UUID[SHOT_LOG];
    private int slAt, slN;
    private final Map<UUID, Integer> shotSeen = new HashMap<>();
    private final double[] blx = new double[BLAST_LOG], bly = new double[BLAST_LOG], blz = new double[BLAST_LOG], blr = new double[BLAST_LOG];
    private final long[] blt = new long[BLAST_LOG];
    private int blAt, blN;

    private void shotPoll() {
        long now = game.now();
        for (Player p : standing) {
            kr.gave.gun.Guns.Arms a = game.guns.arms(p);
            if (a == null) continue;
            UUID id = p.getUniqueId();
            Integer was = shotSeen.put(id, a.shots);
            if (was == null || a.shots <= was) continue;
            int i = slAt;
            slAt = (slAt + 1) % SHOT_LOG;
            slN = Math.min(SHOT_LOG, slN + 1);
            Location l = p.getEyeLocation();
            slx[i] = l.getX();
            sly[i] = l.getY();
            slz[i] = l.getZ();
            slt[i] = now;
            sln[i] = a.shots - was;
            slu[i] = id;
        }
    }

    /**
     * FIRELOG: 최근 ticks 틱(최대 200) 동안 at 에서 r 블록 안(쏜 사람 눈 자리)에서 쏜 발 수. 서 있는 사람의 모든 무기(총, 활, 불길, 냉기 한 번이 한 발)를
     * 엔진이 틱마다 Guns.Arms.shots 의 늘어남으로 센다(G16, G33 이 종 안에서 하던 셈을 공용으로). at 이 null 이면 어디서나.
     */
    public int shotsNear(Vector at, double r, int ticks) {
        long from = game.now() - Math.min(LOG_MAX_TICKS, Math.max(0, ticks));
        double r2 = r * r;
        int n = 0;
        for (int k = 0; k < slN; k++) {
            int i = ((slAt - 1 - k) % SHOT_LOG + SHOT_LOG) % SHOT_LOG;
            if (slt[i] <= from) break;
            if (at != null) {
                double dx = slx[i] - at.getX(), dy = sly[i] - at.getY(), dz = slz[i] - at.getZ();
                if (dx * dx + dy * dy + dz * dz > r2) continue;
            }
            n += sln[i];
        }
        return n;
    }

    /** FIRELOG: 이 사람이 최근 ticks 틱(최대 200) 동안 쏜 발 수. */
    public int shotsBy(Player p, int ticks) {
        if (p == null) return 0;
        UUID id = p.getUniqueId();
        long from = game.now() - Math.min(LOG_MAX_TICKS, Math.max(0, ticks));
        int n = 0;
        for (int k = 0; k < slN; k++) {
            int i = ((slAt - 1 - k) % SHOT_LOG + SHOT_LOG) % SHOT_LOG;
            if (slt[i] <= from) break;
            if (id.equals(slu[i])) n += sln[i];
        }
        return n;
    }

    /** 폭발 하나를 적음(ShotFx 의 폭발 연출 explosion, pop, blast 가 부름: 수류탄, 폭발탄, 로켓, 유탄, 증강 폭발, 그 연출을 쓰는 종 폭발). FIRELOG */
    public void noteBlast(Vector at, double r) {
        if (at == null) return;
        int i = blAt;
        blAt = (blAt + 1) % BLAST_LOG;
        blN = Math.min(BLAST_LOG, blN + 1);
        blx[i] = at.getX();
        bly[i] = at.getY();
        blz[i] = at.getZ();
        blr[i] = r;
        blt[i] = game.now();
    }

    /** FIRELOG: 최근 ticks 틱(최대 200) 동안 at 에서 r 블록 안 폭발 수(at null 이면 어디서나). */
    public int blastsNear(Vector at, double r, int ticks) {
        long from = game.now() - Math.min(LOG_MAX_TICKS, Math.max(0, ticks));
        double r2 = r * r;
        int n = 0;
        for (int k = 0; k < blN; k++) {
            int i = ((blAt - 1 - k) % BLAST_LOG + BLAST_LOG) % BLAST_LOG;
            if (blt[i] <= from) break;
            if (at != null) {
                double dx = blx[i] - at.getX(), dy = bly[i] - at.getY(), dz = blz[i] - at.getZ();
                if (dx * dx + dy * dy + dz * dz > r2) continue;
            }
            n++;
        }
        return n;
    }

    /** FIRELOG: 최근 ticks 틱 동안 at 에서 r 안 가장 최근 폭발 자리(없으면 null). */
    public Vector lastBlastNear(Vector at, double r, int ticks) {
        long from = game.now() - Math.min(LOG_MAX_TICKS, Math.max(0, ticks));
        double r2 = r * r;
        for (int k = 0; k < blN; k++) {
            int i = ((blAt - 1 - k) % BLAST_LOG + BLAST_LOG) % BLAST_LOG;
            if (blt[i] <= from) break;
            double dx = blx[i] - at.getX(), dy = bly[i] - at.getY(), dz = blz[i] - at.getZ();
            if (dx * dx + dy * dy + dz * dz <= r2) return new Vector(blx[i], bly[i], blz[i]);
        }
        return null;
    }

    /**
     * FIELDTICK: 종 개체가 없어도 도는 판 단위 갈고리(막 내내 남는 땅, 못, 줄어든 싸움터 같은 것). 엔진이 Mobs.tick 끝에서 부른다(준비, 웨이브, 쉬는 시간,
     * 판 끝 연출 동안. 대기실에서는 안 돎). 종은 static 하나를 키로 건다(같은 키로 다시 걸면 바꿈). 던지면 떼어 내고 한 번 기록.
     */
    public interface Field {
        /** 매 틱. false 를 돌려주면 떼어 냄. */
        default boolean tick(long now) {
            return true;
        }

        /** 웨이브가 막 시작함(그 웨이브 첫 엔진 틱). */
        default void waveStart(int wave) {
        }

        /** 웨이브가 막 끝남(쉬는 시간 첫 엔진 틱, 감독의 남은 게이브 정리 뒤). 종 개체가 이미 죽었어도 불린다(웨이브 끝 통지). */
        default void waveEnd(int wave) {
        }

        /** 판이 바뀜(중지, 전멸 뒤 대기실, Mobs.clear): 이 뒤 엔진이 떼어 냄. */
        default void reset() {
        }
    }

    private final Map<Object, Field> fields = new java.util.LinkedHashMap<>();
    private Game.State fieldState;
    private long fieldStateAt = Long.MIN_VALUE;

    /** 판 단위 갈고리를 키로 건다(f null 이면 뗌). FIELDTICK */
    public void field(Object key, Field f) {
        if (key == null) return;
        if (f == null) fields.remove(key);
        else fields.put(key, f);
    }

    /** 이 키로 걸린 갈고리(없으면 null). */
    public Field field(Object key) {
        return fields.get(key);
    }

    public void unfield(Object key) {
        fields.remove(key);
    }

    private void fieldTick() {
        Game.State was = fieldState;
        boolean edge = game.state != fieldState || game.stateAt != fieldStateAt;
        fieldState = game.state;
        fieldStateAt = game.stateAt;
        if (fields.isEmpty()) return;
        long now = game.now();
        for (Map.Entry<Object, Field> e : new ArrayList<>(fields.entrySet())) {
            Field f = e.getValue();
            try {
                if (edge && game.state == Game.State.WAVE) f.waveStart(game.wave);
                if (edge && game.state == Game.State.BREAK && was == Game.State.WAVE) f.waveEnd(game.wave);
                if (!f.tick(now)) fields.remove(e.getKey(), f);
            } catch (RuntimeException ex) {
                fields.remove(e.getKey(), f);
                if (broken.add("field:" + e.getKey())) game.plugin.getLogger().log(Level.WARNING, "[몹] 판 갈고리 " + e.getKey() + " 예외(떼어 냄)", ex);
            }
        }
    }

    /**
     * LIGHTS: 빛 하나(발사장 투광등처럼 맵에 선 것). 세운 쪽이 {@link #addLight} 로 올리고 없어질 때 {@link #removeLight}(또는 gone 이 true).
     * 끄고 켜기는 그 빛이 {@code off(누가, 끔)} 으로 받는다(누가 = 끈 개체나 키, 모두가 풀어야 다시 켜짐: 셈은 빛 쪽이 함).
     */
    public interface Light {
        Vector pos();

        double radius();

        boolean lit();

        default void off(Object by, boolean off) {
        }

        default boolean gone() {
            return false;
        }
    }

    private final List<Light> lights = new ArrayList<>();

    public void addLight(Light l) {
        if (l != null && !lights.contains(l)) lights.add(l);
    }

    public void removeLight(Light l) {
        lights.remove(l);
    }

    /** 올라온 빛(없어진 것은 치움). LIGHTS */
    public List<Light> lights() {
        lights.removeIf(Light::gone);
        return Collections.unmodifiableList(lights);
    }

    /** LIGHTS: 이 자리가 빛 안인가(켜진 빛 반경 안, 조명탄 빛, 불 웅덩이). */
    public boolean lit(Vector at) {
        if (at == null) return false;
        for (Light l : lights()) {
            if (!l.lit()) continue;
            Vector c = l.pos();
            double dx = c.getX() - at.getX(), dz = c.getZ() - at.getZ(), r = l.radius();
            if (dx * dx + dz * dz <= r * r) return true;
        }
        try {
            if (game.shop.inFlare(at, 0) || game.shop.inFire(at)) return true;
        } catch (RuntimeException e) {
            return false;
        }
        // 트럭 등불(Truck.lampLevel 0..14 몫만큼 반경 LAMP_R)
        kr.gave.trip.Truck tr = kr.gave.trip.Truck.get();
        if (tr != null) {
            int lv = tr.lampLevel();
            if (lv > 0 && kr.gave.trip.Truck.dist(at.getX(), at.getZ()) <= kr.gave.trip.Truck.LAMP_R * lv / 14.0) return true;
        }
        return false;
    }

    /**
     * LIGHTS: 여기가 어두운가. 빛 안({@link #lit})이면 아님. 맵에 빛이 하나라도 올라와 있으면(발사장) 빛 밖은 어둠, 없으면 밤 사건(director.night()) 동안만
     * 어둠(그 밖은 낮). 종이 화면을 어둡게 하는 일(game.horizon.dusk)은 이 물음에 들지 않는다.
     */
    public boolean dark(Vector at) {
        if (lit(at)) return false;
        if (!lights().isEmpty()) return true;
        return game.director.night();
    }

    // ------------------------------------------------------------------ 예산, 정리

    public void clear() {
        for (Gabe g : live) g.remove();
        live.clear();
        for (Corpse c : corpses) c.remove();
        corpses.clear();
        for (Corpse c : bossCorpses) c.remove();
        bossCorpses.clear();
        ragdoll.clear();
        aim.clear();
        gazeP = null;
        lastFrac.clear();
        gazeCd.clear();
        biteCd.clear();
        killedThisRound.clear();
        aimAssist.clear();
        queryAt = null;
        queryHits.clear();
        bites = gazes = mut1 = mut2 = splits = 0;
        frozen = false;
        atkReset();
        // 새 종 갈고리(sp_engine): 판 단위 갈고리는 reset 을 부른 뒤 떼고, 강제 표적과 사격, 폭발 기록을 지움(빛은 세운 쪽이 뗌)
        for (Map.Entry<Object, Field> e : new ArrayList<>(fields.entrySet())) {
            try {
                e.getValue().reset();
            } catch (RuntimeException ex) {
                game.plugin.getLogger().log(Level.WARNING, "[몹] 판 갈고리 " + e.getKey() + " reset 예외", ex);
            }
        }
        fields.clear();
        forces.clear();
        slN = slAt = blN = blAt = 0;
        shotSeen.clear();
        kr.gave.world.Roam.clearLimits();
    }

    /** 화면 디스플레이 점수 예산을 쓴 양(몹 몫): 실제 살아 있는 디스플레이 수(닻 + 부품 + 마리 + 덩어리). */
    public int budgetUsed() {
        int n = 0;
        for (Gabe g : live) {
            if (g.dead) continue;
            n += g.body != null ? g.body.count() : g.sp.cost;
        }
        return n;
    }

    /** cost 만큼 더 내보낼 수 있으면 true. */
    public boolean budgetFor(int cost) {
        return budgetUsed() + cost <= BUDGET;
    }

    public double tickAvg() {
        if (tickN == 0) return 0;
        double s = 0;
        for (int i = 0; i < tickN; i++) s += tickMs[i];
        return s / tickN;
    }

    public double tickMax() {
        double m = 0;
        for (int i = 0; i < tickN; i++) m = Math.max(m, tickMs[i]);
        return m;
    }

    // ------------------------------------------------------------------ HitTargets(총, 수류탄)

    @Override
    public Hit cast(Player shooter, Vector origin, Vector dir, double maxDist, Set<Object> exclude) {
        Hit best = castGabes(shooter, origin, dir, maxDist, exclude);
        if (shootables.isEmpty()) return best;
        double bestAt = best == null ? maxDist : best.dist();
        shootables.removeIf(Shootable::gone);
        for (Shootable s : shootables) {
            if (exclude.contains(s)) continue;
            double at = s.rayHit(origin, dir, bestAt);
            if (at >= bestAt) continue;
            bestAt = at;
            best = new Hit(s, origin.clone().add(dir.clone().multiply(at)), at);
        }
        return best;
    }

    /** cast 의 게이브만(쏠 수 있는 것 빼고): 엔진 안 조준 기록, 시험 훅. 결과 target 은 늘 Gabe. */
    private Hit castGabes(Player shooter, Vector origin, Vector dir, double maxDist, Set<Object> exclude) {
        int k = lagTicks(shooter);
        double[] off = new double[3];
        Hit best = null;
        double bestAt = maxDist;
        double odx = origin.getX(), ody = origin.getY(), odz = origin.getZ();
        double ddx = dir.getX(), ddy = dir.getY(), ddz = dir.getZ();
        for (int gi = 0; gi < live.size(); gi++) {
            Gabe g = live.get(gi);
            if (g.dead || g.unhittable() || exclude.contains(g)) continue;
            pastOffset(g, k, off);
            for (double[] s : spheresOf(g)) {
                double ox = s[0] + off[0] - odx, oy = s[1] + off[1] - ody, oz = s[2] + off[2] - odz;
                double t = ox * ddx + oy * ddy + oz * ddz;
                double rr = s[3] + R_BULLET;
                if (t < -rr) continue;
                double m2 = ox * ox + oy * oy + oz * oz - t * t;
                if (m2 > rr * rr) continue;
                double at = Math.max(0, t - Math.sqrt(rr * rr - m2));
                if (at > bestAt) continue;
                bestAt = at;
                best = new Hit(g, new Vector(odx + ddx * at, ody + ddy * at, odz + ddz * at), at);
            }
        }
        return best;
    }

    @Override
    public boolean damage(Object target, Damage d) {
        if (target instanceof Shootable s) return !s.gone() && s.shot(d);
        if (!(target instanceof Gabe g) || g.dead) return false;
        // 남이 가린 개체(Gabe.cloak, UNTARGET)는 어떤 피해도 받지 않는다(포탑, 증강 폭발처럼 cast 를 거치지 않는 것까지)
        if (g.cloaked()) return false;
        Player by = d.by();
        Vector at = d.at();
        int aimed = d.aimTicks();
        if (by != null) {
            aimed = Math.max(aimed, aimTicks(by.getUniqueId(), g));
            DamageType t = d.type();
            if (at != null && (t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER)) {
                double[] off = new double[3];
                pastOffset(g, lagTicks(by), off);
                at = at.clone().subtract(new Vector(off[0], off[1], off[2]));
            }
        }
        Damage d2 = new Damage(d.amount(), d.type(), by, at, d.dir(), d.how(), aimed);
        double k = scaleOf(g, d2);
        // 운영자 공격력 배율(/gave 공격력): 사람 편 피해에만
        if (pdmg != 1 && playerSide(d2)) k *= pdmg;
        if (k != 1) d2 = d2.times(k);
        Damage outer = hitNow;
        hitNow = d2;
        try {
            g.damage(d2);
        } catch (RuntimeException e) {
            fail(g, "damage", e);
        } finally {
            hitNow = outer;
        }
        return g.dead;
    }

    /**
     * 반경 안 맞을 수 있는 개체(폭발, 불, 연쇄). 떼 종은 닻이 밖이어도 산 마리 하나가 반경 안이면 든다. 이 물음 자리를 기억해 두고
     * 같은 틱의 {@link #center} 가 떼 종을 그 자리에 가장 가까운 마리로 잰다.
     */
    @Override
    public List<Object> inRadius(Location c, double r) {
        Vector v = c.toVector();
        double r2 = r * r;
        List<Object> out = new ArrayList<>();
        queryAt = v;
        queryTick = game.now();
        queryHits.clear();
        long now = game.now();
        for (Gabe g : live) {
            if (g.dead || g.unhittable()) continue;
            if (g.big) {
                // 거대한 몸(보스): 구마다 '구 표면이 반경 안'(2026-11-12 boss_infra, docs/모듈/보스.md 11.6)
                if (bigWithin(g, v, r)) {
                    out.add(g);
                    queryHits.add(g);
                    g.queryAt = v;
                    g.queryTick = now;
                }
                continue;
            }
            Swarm s = g.body != null ? g.body.swarm : null;
            if (g.pos.distanceSquared(v) <= r2 || (s != null && s.anyWithin(v, r))) {
                out.add(g);
                if (s != null) {
                    queryHits.add(g);
                    g.queryAt = v;
                    g.queryTick = now;
                }
            }
        }
        return out;
    }

    /** 거대한 몸의 구 하나라도 표면이 v 에서 r 안인가(구가 없으면 몸 중심으로). */
    private boolean bigWithin(Gabe g, Vector v, double r) {
        List<double[]> ss = spheresOf(g);
        if (ss.isEmpty()) return g.pos.distanceSquared(v) <= r * r;
        for (double[] s : ss) {
            double dx = s[0] - v.getX(), dy = s[1] - v.getY(), dz = s[2] - v.getZ();
            double lim = r + s[3];
            if (dx * dx + dy * dy + dz * dz <= lim * lim) return true;
        }
        return false;
    }

    /** 거대한 몸에서 at 에 가장 가까운 구 표면 점(at 이 구 안이면 at 그대로). 구가 없으면 몸 중심. */
    private Vector bigNearest(Gabe g, Vector at) {
        List<double[]> ss = spheresOf(g);
        Vector best = null;
        double bd = Double.MAX_VALUE;
        for (double[] s : ss) {
            double dx = at.getX() - s[0], dy = at.getY() - s[1], dz = at.getZ() - s[2];
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz), gap = d - s[3];
            if (gap >= bd) continue;
            bd = gap;
            if (gap <= 0) best = at.clone();
            else {
                double k = s[3] / d;
                best = new Vector(s[0] + dx * k, s[1] + dy * k, s[2] + dz * k);
            }
        }
        return best != null ? best : g.pos.clone();
    }

    /**
     * 대상 중심. 보통 몸 중심(pos). 떼 종은 이 틱 inRadius 에 걸린 것이면 그 물음 자리(폭발 점)에 가장 가까운 산 마리, 아니면 산 마리들의
     * 가운데(덮칠 때는 닻에서 멀리 흩어지므로 닻이 아니라 마리 쪽).
     */
    @Override
    public Vector center(Object target) {
        if (target instanceof Shootable s) return s.center();
        if (!(target instanceof Gabe g)) return null;
        // 이 틱 inRadius 가 이 개체를 넣은 물음 자리(개체마다 기억: 폭발 피해 도중 다른 물음이 끼어도(증강 폭발, 연쇄) 제 폭발점을 씀, 2026-11-12 sp_engine)
        Vector q = g.queryAt != null && g.queryTick == game.now() ? g.queryAt : null;
        if (g.big) {
            // 거대한 몸: 이 틱 inRadius 에 걸렸으면 그 물음 자리(폭발점)에 가장 가까운 구 표면 점, 아니면 몸 중심
            if (q != null) return bigNearest(g, q);
            return g.pos.clone();
        }
        Swarm s = g.body != null ? g.body.swarm : null;
        if (s != null && s.alive() > 0) {
            if (q != null) {
                Swarm.Member m = s.nearest(q);
                if (m != null) return m.pos();
            }
            return s.centroid();
        }
        return g.pos.clone();
    }

    /** 반경 안 흉내 종 정체를 드러냄(조명탄). */
    public void reveal(Vector at, double r) {
        for (Gabe g : near(at, r, x -> true)) {
            try {
                g.reveal();
            } catch (RuntimeException e) {
                fail(g, "reveal", e);
            }
        }
    }

    /** g 의 주의를 at 쪽으로 ticks 동안 끎(딸랑이). 엔진이 그동안 tick 대신 seek. */
    public void lure(Gabe g, Vector at, int ticks) {
        g.lureAt.copy(at);
        g.lureUntil = Math.max(g.lureUntil, game.now() + ticks);
    }

    /** 반경 안 게이브 모두 lure. */
    public void lure(Vector at, double r, int ticks) {
        for (Gabe g : near(at, r, x -> true)) {
            try {
                g.lure(at, ticks);
            } catch (RuntimeException e) {
                fail(g, "lure", e);
            }
        }
    }

    /** 이 사람이 보는 방향 reach 안 가장 가까운 게이브(돕기 대상, 블록에 가리면 없음). */
    public Gabe looked(Player p, double reach) {
        Location eye = p.getEyeLocation();
        Vector o = eye.toVector(), d = eye.getDirection();
        RayTraceResult rb = game.world.rayTraceBlocks(eye, d, reach, FluidCollisionMode.NEVER, true);
        double lim = rb == null ? reach : rb.getHitPosition().distance(o);
        Hit h = castGabes(p, o, d, lim, NONE);
        return h == null ? null : (Gabe) h.target();
    }

    /** g.helped(p) 를 감싼 것: 종 코드가 던지면 그 개체만 치우고 false. 다른 모듈은 이것으로 부를 것. */
    public boolean helped(Gabe g, Player p) {
        if (g == null || g.dead) return false;
        try {
            return g.helped(p);
        } catch (RuntimeException e) {
            fail(g, "helped", e);
            return false;
        }
    }

    /** g.listenSound() 를 감싼 것: 종 코드가 던지면 그 개체만 치우고 null. */
    public String listenSound(Gabe g) {
        if (g == null || g.dead) return null;
        try {
            return g.listenSound();
        } catch (RuntimeException e) {
            fail(g, "listenSound", e);
            return null;
        }
    }

    // ------------------------------------------------------------------ 시험 훅

    private void aimAssistTick() {
        if (aimAssist.isEmpty()) return;
        long now = game.now();
        for (Iterator<Map.Entry<UUID, Aim>> it = aimAssist.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, Aim> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            Aim m = e.getValue();
            if (p == null || m.until() < now) {
                it.remove();
                continue;
            }
            aimAt(p, m.f(), m.side(), m.up());
        }
    }

    /** p 가 가장 가까운(종 f, 없으면 아무) 게이브의 중심을 보게 돌림. 돌렸으면 그 개체. */
    private Gabe aimAt(Player p, Species f) {
        return aimAt(p, f, 0, 0);
    }

    /** aimAt 에 오프셋: 중심에서 보는 사람 기준 오른쪽으로 side, 위로 up 블록 떨어진 점을 본다(부위 약점 시험). */
    private Gabe aimAt(Player p, Species f, double side, double up) {
        Vector eye = p.getEyeLocation().toVector();
        Gabe g = nearest(eye, 200, x -> !x.untargetable && (f == null || x.sp == f));
        if (g == null) return null;
        List<double[]> ss = spheresOf(g);
        if (g.dead) return null;
        Vector c = ss.isEmpty() ? g.pos : new Vector(ss.get(0)[0], ss.get(0)[1], ss.get(0)[2]);
        Vector d = c.clone().subtract(eye);
        if (side != 0 || up != 0) {
            double h = Math.hypot(d.getX(), d.getZ());
            // 오른쪽 = 시선 수평 방향을 시계로 90도(마크 yaw: +X 가 서쪽 기준 오른쪽은 (-dz, dx))
            if (h > 1e-6) d.add(new Vector(-d.getZ() / h * side, 0, d.getX() / h * side));
            d.setY(d.getY() + up);
        }
        Location l = p.getLocation();
        l.setYaw((float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ())));
        l.setPitch((float) Math.toDegrees(-Math.atan2(d.getY(), Math.hypot(d.getX(), d.getZ()))));
        p.teleport(l);
        return g;
    }

    private static double num(String[] a, int i, double def) {
        if (a.length <= i) return def;
        try {
            return Double.parseDouble(a[i]);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String fmt(Vector v) {
        return String.format(Locale.ROOT, "%.1f,%.1f,%.1f", v.getX(), v.getY(), v.getZ());
    }

    /**
     * 시험 훅(/gave 시험 &lt;행동&gt; &lt;이름&gt; ...): 몹목록 [종] / 몹정지 on|off / 몹죽이기 [종] / 흐름 &lt;x&gt; &lt;z&gt; / 몹보기 /
     * 보기 [종] [틱](가장 가까운 게이브를 보게, 틱이면 그동안 계속) / 예산 / 몹시간 [on|off] / 몹놓기 &lt;종&gt; &lt;수&gt; &lt;x&gt; &lt;z&gt; [흩뿌림] /
     * 몹고리 &lt;종&gt; &lt;수&gt; &lt;반지름&gt; / 몹하나 &lt;번호&gt; / 래그돌 [on|off|다시] / 래그돌값 &lt;키&gt; &lt;값&gt; / 래그돌검사 /
     * 몹치기 &lt;종류&gt; [수] [종] [방향] / 몹빠름 [종류 종 빠르기 [수] [틱]] / 패킷 [on|off|다시]. 이름 자리는 아무 글이나 되는 행동도 있다
     * (사람이 필요 없을 때).
     */
    public String test(Player p, String[] a) {
        if (a.length < 2) return null;
        String act = a[1];
        switch (act) {
            // 새 종 갈고리 시험(2026-11-12 sp_engine)
            case "강제표적" -> {
                // 강제표적 <이름> [반경 0=판 전체] [틱] | 강제표적 <이름> 끝
                if (a.length > 3 && a[3].equals("끝")) {
                    unforce("test");
                    return "forced=off";
                }
                if (p == null) return "강제표적 <이름> [반경] [틱]";
                force("test", p, num(a, 3, 0), (int) num(a, 4, 200));
                int n = 0;
                for (Gabe g : live) if (!g.dead && forcedFor(g) == p) n++;
                return "forced=" + n + " to=" + p.getName();
            }
            case "몹옮기기" -> {
                // 몹옮기기 <이름|x> <종|아무> <x> <z>: 그 종 가운데 사람(없으면 가운데)에 가장 가까운 개체를 x z 바닥 + hover 로
                Species f = a.length > 3 && !a[3].equals("아무") ? Species.of(a[3]) : null;
                Vector from = p != null ? p.getLocation().toVector() : Plaza.center(game.world).toVector();
                Gabe g = nearest(from, 400, x -> f == null || x.sp == f);
                if (g == null) return "옮길 개체 없음";
                double x = num(a, 4, g.pos.getX()), z = num(a, 5, g.pos.getZ());
                Vector before = g.pos.clone();
                boolean ok = relocate(g, new Vector(x, g.flying ? g.pos.getY() : flow.floor(x, z) + g.hover, z));
                return "relocate=" + ok + " " + g.sp.name() + "#" + g.id + " from=" + fmt(before) + " to=" + fmt(g.pos);
            }
            case "몹가림" -> {
                // 몹가림 <이름|x> [종] [틱]: 그 종 모두(없으면 모두) 틱 동안 가림
                Species f = a.length > 3 ? Species.of(a[3]) : null;
                int t = (int) num(a, 4, 100), n = 0;
                for (Gabe g : live) {
                    if (g.dead || f != null && g.sp != f) continue;
                    g.cloak(t);
                    n++;
                }
                return "cloaked=" + n + " ticks=" + t;
            }
            case "사격기록" -> {
                // 사격기록 <이름> [반경] [틱]
                if (p == null) return "사격기록 <이름> [반경] [틱]";
                double r = num(a, 3, 8);
                int t = (int) num(a, 4, 20);
                Vector at = p.getEyeLocation().toVector();
                Vector lb = lastBlastNear(at, Math.max(r, 12), t);
                return "near=" + shotsNear(at, r, t) + " by=" + shotsBy(p, t) + " blasts=" + blastsNear(at, Math.max(r, 12), t) + " last=" + (lb == null ? "-" : fmt(lb))
                    + " dark=" + dark(p.getLocation().toVector()) + " lit=" + lit(p.getLocation().toVector());
            }
            case "몹이름표" -> {
                // 몹이름표 <이름> [글|끔] [위] [크기]: 그 사람에게 가장 가까운 개체 몸에 글 한 줄
                Vector from = p != null ? p.getLocation().toVector() : Plaza.center(game.world).toVector();
                Gabe g = nearest(from, 200, x -> x.body != null);
                if (g == null) return "개체 없음";
                String text = a.length > 3 ? a[3] : p != null ? p.getName() : "Ripley";
                if (text.equals("끔")) {
                    g.body.dropTags();
                    return "tags=0";
                }
                Body.Tag t = g.body.tags.isEmpty() ? g.body.tag((float) num(a, 4, 1.5), (float) num(a, 5, 2)) : g.body.tags.get(0);
                t.place((float) num(a, 4, 1.5), (float) num(a, 5, 2), 3).text(text);
                return "tag " + g.sp.name() + "#" + g.id + " text=" + t.text() + " shown=" + (t.d != null) + " count=" + g.body.count();
            }
            case "판갈고리" -> {
                // 판갈고리 x [켬|끔]: 시험용 판 단위 갈고리(틱, 웨이브 시작과 끝을 셈)
                if (a.length > 3 && a[3].equals("끔")) {
                    unfield("test");
                    return "field=off";
                }
                if (a.length > 3 && a[3].equals("켬")) {
                    int[] c = new int[3];
                    field("test", new Field() {
                        @Override
                        public boolean tick(long now) {
                            c[0]++;
                            return true;
                        }

                        @Override
                        public void waveStart(int wave) {
                            c[1]++;
                            game.plugin.getLogger().info("[몹] 시험 판갈고리 웨이브 시작 " + wave);
                        }

                        @Override
                        public void waveEnd(int wave) {
                            c[2]++;
                            game.plugin.getLogger().info("[몹] 시험 판갈고리 웨이브 끝 " + wave);
                        }

                        @Override
                        public String toString() {
                            return "ticks=" + c[0] + " starts=" + c[1] + " ends=" + c[2];
                        }
                    });
                }
                Field f = field("test");
                return "field=" + (f == null ? "-" : f.toString()) + " fields=" + fields.size();
            }
            case "몹목록" -> {
                Species f = a.length > 3 ? Species.of(a[3]) : null;
                StringBuilder sb = new StringBuilder();
                int n = 0;
                for (Gabe g : live) {
                    if (g.dead || (f != null && g.sp != f)) continue;
                    if (n < 6) sb.append(' ').append(g.sp.name()).append('#').append(g.id).append('@').append(fmt(g.pos)).append(" hp=")
                            .append(Math.round(g.hp)).append(" st=").append(g.state.isEmpty() ? "-" : g.state);
                    n++;
                }
                return "mobs=" + n + sb;
            }
            case "몹정지" -> {
                frozen = a.length > 3 && a[3].equalsIgnoreCase("on");
                if (frozen) for (Gabe g : live) g.vel.zero();
                return "frozen=" + frozen;
            }
            case "몹죽이기" -> {
                Species f = a.length > 3 ? Species.of(a[3]) : null;
                int n = 0;
                for (Gabe g : new ArrayList<>(live)) {
                    if (g.dead || (f != null && g.sp != f)) continue;
                    // 보상 없음(그전에 사람이 준 피해 기여도 버림)
                    g.noReward = true;
                    g.reward = 0;
                    g.contrib = null;
                    try {
                        g.die(new Damage(g.hp, DamageType.OTHER, null, g.pos.clone(), null, "test", 0));
                    } catch (RuntimeException e) {
                        fail(g, "die", e);
                    }
                    n++;
                }
                return "killed=" + n + " corpses=" + corpses.size();
            }
            case "흐름" -> {
                double x = num(a, 3, p == null ? 0 : p.getLocation().getX()), z = num(a, 4, p == null ? 0 : p.getLocation().getZ());
                Vector d = flow.dir(new Vector(x, 0, z));
                Player t = flow.target(x, z);
                return String.format(Locale.ROOT, "dist=%d dir=%.2f,%.2f walk=%b floor=%.1f to=%s bfs=%.3fms reached=%d ", flow.dist(x, z), d.getX(),
                        d.getZ(), flow.walkable(x, z), flow.floor(x, z), t == null ? "-" : t.getName(), flow.lastMs, flow.reached) + flow.seedInfo(x, z);
            }
            case "몹보기" -> {
                if (p == null) return "사람 없음";
                Gabe g = looked(p, 64);
                if (g == null) g = nearest(p.getLocation().toVector(), 64, x -> true);
                if (g == null) return "없음";
                return g.sp.name() + "#" + g.id + " at=" + fmt(g.pos) + String.format(Locale.ROOT, " d=%.1f yaw=%.0f", g.pos.distance(p.getEyeLocation().toVector()), g.yaw)
                        + " hp=" + Math.round(g.hp) + "/" + Math.round(g.maxHp) + " st=" + (g.state.isEmpty() ? "-" : g.state) + " mut=" + g.mutStage
                        + " aim=" + aimTicks(p.getUniqueId(), g) + " open=" + (g.body != null && g.body.look.open) + " on=" + (g.body == null ? "-" : g.body.look.on)
                        + " disp=" + (g.body == null ? 0 : g.body.count()) + " target=" + (g.target == null ? "-" : g.target.getName())
                        + " hurt=" + (g.body == null ? "-" : g.body.look.hurtUntil == 0 ? "0" : String.valueOf(g.body.look.hurtUntil - game.worldTime()))
                        + " stamp=" + stamp(g.body == null ? null : g.body.anchor);
            }
            case "몹폭발" -> {
                // 몹폭발 [반경] [피해]: 사람 발밑 0.4 위에서 수류탄과 같은 길(inRadius → center → damage EXPLOSION)
                if (p == null) return "사람 없음";
                double r = num(a, 3, 3), dmg = num(a, 4, 50);
                Location at = p.getLocation().add(0, 0.4, 0);
                int hits = 0, kills = 0, members = 0;
                StringBuilder sb = new StringBuilder();
                for (Object o : inRadius(at, r)) {
                    Gabe g = (Gabe) o;
                    Vector c = center(o);
                    Swarm s = g.body == null ? null : g.body.swarm;
                    int before = s == null ? 0 : s.alive();
                    if (damage(o, new Damage(dmg, DamageType.EXPLOSION, p, c.clone(), c.clone().subtract(at.toVector()).normalize(), "test", 0))) kills++;
                    if (s != null) members += before - s.alive();
                    hits++;
                    sb.append(' ').append(g.sp.name()).append('#').append(g.id).append(" c=").append(fmt(c))
                            .append(String.format(Locale.ROOT, " cd=%.1f ad=%.1f", c.distance(at.toVector()), g.pos.distance(at.toVector())));
                }
                return "hits=" + hits + " kills=" + kills + " members=" + members + sb;
            }
            case "떼모음" -> {
                // 떼모음 <번호>: 떼 종 마리들을 사람 몸통 둘레로 옮김(대형 반 크기, 닻은 그대로). 덮친 순간처럼 마리가 닻에서 멀리 떨어진 꼴
                if (p == null) return "사람 없음";
                Gabe g = pick(p, a, 3);
                if (g == null || g.body == null || g.body.swarm == null) return "떼 없음";
                Vector rel = chest(p).subtract(g.pos);
                for (Swarm.Member m : g.body.swarm.members) {
                    if (m.dead) continue;
                    m.moveTo(rel.clone().add(Swarm.rotate(m.home, g.body.swarm.heading).multiply(0.5)), 1);
                }
                return "moved=" + g.body.swarm.alive() + String.format(Locale.ROOT, " anchorDist=%.1f", rel.length());
            }
            case "몹고장" -> {
                // 몹고장 <곳>: 일부러 예외를 던지는 시험 개체(G01 모양)를 사람 앞 3블록에 놓음. 곳: tick, spheres, head, bitten, build, spawned, damage
                if (p == null) return "사람 없음";
                String where = a.length > 3 ? a[3] : "tick";
                int before = budgetUsed();
                Vector at = p.getLocation().toVector().add(p.getLocation().getDirection().setY(0).normalize().multiply(3));
                at.setY(flow.floor(at.getX(), at.getZ()) + Species.G01.band.hover);
                Gabe g = place(new Broken(game, where), at);
                return "placed=" + (g != null) + " where=" + where + " budget " + before + "->" + budgetUsed() + " alive=" + alive();
            }
            case "몹틀" -> {
                // 몹틀: 빈 닻 + 부품 하나(g01 모양)로 된 시험 개체를 사람 앞 4블록에 놓음. 제자리에서 틱마다 6도씩 돈다(부품이 몸 방향을 따르나)
                if (p == null) return "사람 없음";
                Vector at = p.getLocation().toVector().add(p.getLocation().getDirection().setY(0).normalize().multiply(4));
                at.setY(flow.floor(at.getX(), at.getZ()) + Species.G01.band.hover);
                Gabe g = place(new Rig(game), at);
                return g == null ? "실패" : "rig=" + g.id + " disp=" + g.body.count();
            }
            case "보기" -> {
                if (p == null) return "사람 없음";
                Species f = a.length > 3 ? Species.of(a[3]) : null;
                // 보기 <이름> [종] [틱] [옆] [위]: 옆(오른쪽 +), 위 오프셋 블록(부위 약점 시험)
                int i0 = f != null ? 4 : 3;
                int ticks = (int) num(a, i0, 0);
                double side = num(a, i0 + 1, 0), up = num(a, i0 + 2, 0);
                Gabe g = aimAt(p, f, side, up);
                if (g == null) return "없음";
                if (ticks > 0) aimAssist.put(p.getUniqueId(), new Aim(game.now() + ticks, f, side, up));
                return "ok " + g.sp.name() + "#" + g.id + String.format(Locale.ROOT, " d=%.1f", g.pos.distance(p.getEyeLocation().toVector()))
                        + (side != 0 || up != 0 ? String.format(Locale.ROOT, " off=%.2f,%.2f", side, up) : "");
            }
            case "래그돌" -> {
                // 래그돌 <이름> [on|off|다시]: 래그돌 사체 수와 예산(움직임, 굳음, 꺼지는 중), 가장 새 구의 상태. 다시 = config 를 다시 읽음
                if (a.length > 3) {
                    switch (a[3]) {
                        case "on" -> ragdoll.enabled = true;
                        case "off" -> ragdoll.enabled = false;
                        case "다시" -> {
                            game.plugin.reloadConfig();
                            ragdoll.load();
                        }
                        default -> {
                        }
                    }
                }
                int flat = 0;
                for (Corpse c : corpses) if (c.doll == null) flat++;
                return ragdoll.status() + " corpses=" + corpses.size() + " flatNow=" + flat;
            }
            case "래그돌값" -> {
                // 래그돌값 <이름> <키> <값>: 값 하나를 바꿈(active, activeDisplays, settled, cheap, settleSeconds, rods, kick, gravity, bounce,
                // friction, minAir, maxAir, pile 0|1, faceUp 0|1 ...)
                if (a.length < 5) return "래그돌값 <이름> <키> <값>";
                String r = ragdoll.set(a[3], num(a, 4, 0));
                return r == null ? "모르는 키: " + a[3] : r;
            }
            case "래그돌검사" -> {
                // 래그돌검사 <이름>: 굳은 사체의 면별 수, 얼굴이 깔린 수, 더미 높이, 허공에 뜬 수
                return ragdoll.audit();
            }
            case "몹빠름" -> {
                // 몹빠름 <이름> <종류> <종> <빠르기> [수] [틱]: 그 종이 한 틱에 그 블록 넘게 움직이면(돌진) 그 종류 피해로 죽인다(수 마리까지,
                // 틱 동안). 인자 없이 부르면 지금까지 죽인 수와 마지막 빠르기
                if (a.length > 5) {
                    try {
                        fastType = DamageType.valueOf(a[3].toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        return "몹빠름 <이름> <BULLET|PELLET|SNIPER|EXPLOSION|FIRE|BITE|OTHER> <종> <빠르기> [수] [틱]";
                    }
                    fastSp = Species.of(a[4]);
                    fastMin = num(a, 5, 0.4);
                    fastLeft = (int) num(a, 6, 1);
                    fastUntil = game.now() + (long) num(a, 7, 200);
                    fastKilled = 0;
                    fastLast = 0;
                }
                return "fast=" + (fastType == null ? "-" : fastType.name()) + " left=" + fastLeft + " killed=" + fastKilled
                        + String.format(Locale.ROOT, " lastSpeed=%.2f ", fastLast) + ragdoll.status();
            }
            case "패킷" -> {
                // 패킷 <이름> [on|off|다시]: 그 사람에게 나가는 패킷 수와 바이트(압축 뒤)를 틱마다 센다. on 붙임, 다시 0 으로, off 뗌. 인자 없으면 읽기.
                // ragSent = 그동안 래그돌이 보낸 자세 수(디스플레이마다 한 번)
                String op = a.length > 3 ? a[3] : "";
                if (op.equals("on")) {
                    if (p == null) return "사람 없음";
                    if (meter != null) meter.detach();
                    meter = NetMeter.attach(p);
                    meterSent = ragdoll.sent;
                    return "meter on " + p.getName();
                }
                if (meter == null) return "meter off";
                String r = meter.report() + " ragSent=" + (ragdoll.sent - meterSent);
                if (op.equals("다시")) {
                    meter.reset();
                    meterSent = ragdoll.sent;
                } else if (op.equals("off")) {
                    meter.detach();
                    meter = null;
                }
                return r;
            }
            case "몹치기" -> {
                // 몹치기 <이름> <종류> [수] [종] [방향 yaw]: 사람 눈에서 가까운 개체부터 그 종류 피해(BULLET, PELLET, SNIPER, EXPLOSION, FIRE, BITE,
                // OTHER)로 수 번 쳐서 죽임(떼 종은 한 번에 한 마리씩 여러 번). 탄 방향은 눈에서 개체로(yaw 를 주면 그 방향: 0 남, 90 서, 180 북,
                // 270 동), 맞은 점은 가운데에서 조금 벗어남. 폭발은 사람 발밑(yaw 를 주면 개체에서 그 반대로 2블록)이 폭발점
                if (p == null) return "사람 없음";
                DamageType type;
                try {
                    type = DamageType.valueOf(a.length > 3 ? a[3].toUpperCase(Locale.ROOT) : "");
                } catch (IllegalArgumentException e) {
                    return "몹치기 <이름> <BULLET|PELLET|SNIPER|EXPLOSION|FIRE|BITE|OTHER> [수] [종] [방향 yaw]";
                }
                int max = (int) num(a, 4, 1);
                Species f = a.length > 5 ? Species.of(a[5]) : null;
                double yaw = num(a, 6, Double.NaN);
                Vector fixed = Double.isNaN(yaw) ? null : new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
                Vector eye = p.getEyeLocation().toVector();
                int n = 0, dead = 0;
                for (int k = 0; k < max; k++) {
                    Gabe g = nearest(eye, 200, x -> !x.untargetable && (f == null || x.sp == f));
                    if (g == null) break;
                    Vector c = center(g);
                    Vector dir = fixed != null ? fixed.clone() : c.clone().subtract(eye);
                    if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1);
                    dir.normalize();
                    if (type == DamageType.EXPLOSION) {
                        // 수류탄과 같은 차례(inRadius 가 폭발점을 기억, center 가 떼의 가장 가까운 마리)
                        Location boom = fixed != null ? c.clone().subtract(dir.clone().multiply(2)).toLocation(game.world) : p.getLocation().add(0, 0.4, 0);
                        inRadius(boom, 0.01);
                        queryHits.add(g);
                        g.queryAt = boom.toVector();
                        g.queryTick = game.now();
                        c = center(g);
                    }
                    Vector at = type == DamageType.EXPLOSION ? c.clone() : c.clone().add(new Vector((game.random.nextDouble() - 0.5) * 0.5,
                            (game.random.nextDouble() - 0.5) * 0.5, (game.random.nextDouble() - 0.5) * 0.5));
                    if (damage(g, new Damage(99999, type, null, at, dir, "test", 0))) dead++;
                    n++;
                }
                return "hit=" + n + " dead=" + dead + " " + ragdoll.status();
            }
            case "공격" -> {
                // 공격 <이름> [다시 | 방어 <값> | 물기 on|off | 수 <n>]: 종별 공격 기록(웨이브 시작에 지워짐). 방어 = 판에 든 서 있는 사람 armor 를 그 값으로
                // (측정 동안 안 쓰러지게, 증강이나 상점 스탯을 다시 계산하면 돌아옴), 물기 off = 물어뜯기 규칙을 끔(시험만)
                String op = a.length > 3 ? a[3] : "";
                switch (op) {
                    case "다시" -> atkReset();
                    case "방어" -> {
                        double v = num(a, 4, 1);
                        for (Player q : standing) game.players.get(q).armor = v;
                    }
                    case "물기" -> biteOff = a.length > 4 && a[4].equalsIgnoreCase("off");
                    default -> {
                    }
                }
                int max = op.equals("수") ? (int) num(a, 4, 12) : 60;
                return "[공격] bite=" + (biteOff ? "off" : "on") + " " + atkSummary(max);
            }
            case "예산" -> {
                int mobs = budgetUsed();
                return "budget=" + mobs + "/" + BUDGET + " mobs=" + alive() + " corpses=" + corpses.size() + " rag=" + ragdoll.displays() + " fx=" + game.shotFx.used()
                        + " bites=" + bites + " gazes=" + gazes + " mut1=" + mut1 + " mut2=" + mut2 + " splits=" + splits + " seen=" + seenCost;
            }
            case "몹시간" -> {
                if (a.length > 3) timingLog = a[3].equalsIgnoreCase("on");
                // worst = 최근 200틱 안에서 가장 느린 틱과 그 단계별 시간(흐름과 조준, 행동, 적분과 규칙, 몸 옮김, 시체 풀, 래그돌)
                StringBuilder w = new StringBuilder();
                for (int i = 0; i < PHASES.length; i++) w.append(i == 0 ? "" : " ").append(PHASES[i]).append(String.format(Locale.ROOT, "=%.2f", worstNs[i] / 1e6));
                return String.format(Locale.ROOT, "tick avg=%.3fms max=%.3fms last=%.3fms n=%d mobs=%d disp=%d bfs=%.3fms reached=%d log=%b", tickAvg(), tickMax(),
                        lastTickMs, tickN, alive(), budgetUsed(), flow.lastMs, flow.reached, timingLog)
                        + String.format(Locale.ROOT, " worst=%.2fms(%s)@-%dt rag=%d ragStart=%.2f/%.2fms ragPlan=%.3f/%.3fms", worstMs, w, game.now() - worstAt,
                                ragdoll.displays(), ragdoll.startAvgMs(), ragdoll.startMaxMs, ragdoll.planAvgMs(), ragdoll.planMaxMs)
                        + " slow=" + slowN + java.util.Arrays.toString(java.util.Arrays.stream(slow).filter(x -> x != null).toArray());
            }
            case "몹놓기" -> {
                Species sp = a.length > 3 ? Species.of(a[3]) : null;
                if (sp == null) return "몹놓기 <종> <수> <x> <z> [흩뿌림]";
                int n = (int) num(a, 4, 1);
                double x = num(a, 5, 0), z = num(a, 6, 0), r = num(a, 7, 0);
                int made = 0;
                for (int i = 0; i < n; i++) {
                    double ang = i * 2.399963, rr = r * Math.sqrt((i + 0.5) / n);
                    double sx = x + Math.cos(ang) * rr, sz = z + Math.sin(ang) * rr;
                    if (spawn(sp, new Vector(sx, flow.floor(sx, sz) + sp.band.hover, sz), false) != null) made++;
                }
                return "made=" + made;
            }
            case "몹고리" -> {
                Species sp = a.length > 3 ? Species.of(a[3]) : null;
                if (sp == null) return "몹고리 <종> <수> <반지름>";
                int n = (int) num(a, 4, 10);
                double r = num(a, 5, 60);
                int made = 0;
                for (int i = 0; i < n; i++) {
                    double ang = Math.PI * 2 * i / n;
                    double sx = Plaza.CX + Math.cos(ang) * r, sz = Plaza.CZ + Math.sin(ang) * r;
                    if (spawn(sp, new Vector(sx, flow.floor(sx, sz) + sp.band.hover, sz), false) != null) made++;
                }
                return "made=" + made;
            }
            case "흐름벽" -> {
                // 흐름벽 <x1> <z1> <x2> <z2> [높이]: 시험 벽(돌 벽돌 블록 + 흐름장 막음). 흐름벽 지움: 블록과 막음을 걷음
                if (a.length > 3 && a[3].equals("지움")) {
                    for (int[] w : testWalls) {
                        for (int x = w[0]; x <= w[2]; x++)
                            for (int z = w[1]; z <= w[3]; z++)
                                for (int y = 0; y < w[4]; y++) game.world.getBlockAt(x, Plaza.FLOOR_Y + y, z).setType(org.bukkit.Material.AIR);
                    }
                    testWalls.clear();
                    flow.clearTestWalls();
                    return "walls=0";
                }
                int x1 = (int) num(a, 3, 0), z1 = (int) num(a, 4, 0), x2 = (int) num(a, 5, 0), z2 = (int) num(a, 6, 0), h = (int) num(a, 7, 3);
                int[] w = {Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2), h};
                for (int x = w[0]; x <= w[2]; x++)
                    for (int z = w[1]; z <= w[3]; z++)
                        for (int y = 0; y < h; y++) game.world.getBlockAt(x, Plaza.FLOOR_Y + y, z).setType(org.bukkit.Material.STONE_BRICKS);
                testWalls.add(w);
                flow.testWall(w[0], w[1], w[2], w[3], true);
                return "walls=" + testWalls.size();
            }
            case "몹나이" -> {
                // 몹나이 <틱> [종]: 산 개체 나이를 더함(오래 살면 변함 시험)
                int add = (int) num(a, 3, 400);
                Species f = a.length > 4 ? Species.of(a[4]) : null;
                int n = 0;
                for (Gabe g : live) {
                    if (g.dead || (f != null && g.sp != f)) continue;
                    g.age += add;
                    n++;
                }
                return "aged=" + n + " +" + add;
            }
            case "몹하나" -> {
                Gabe g = pick(p, a, 3);
                if (g == null) return "없음";
                Swarm s = g.body == null ? null : g.body.swarm;
                Swarm.Member m0 = s == null ? null : s.nearest(null);
                return g + " at=" + fmt(g.pos) + " vel=" + fmt(g.vel.clone().multiply(20)) + String.format(Locale.ROOT, " yaw=%.0f", g.yaw) + " age=" + g.age
                        + " mut=" + g.mutStage + " bite=" + g.biting() + " disp=" + (g.body == null ? 0 : g.body.count())
                        + " pose=" + posed(g.body == null ? null : g.body.anchor) + " m0=" + posed(m0 == null ? null : m0.d)
                        + " lod=" + posed(s == null ? null : s.lod) + " m0stamp=" + stamp(m0 == null ? null : m0.d)
                        + (g.body != null && !g.body.pieces.isEmpty() ? String.format(Locale.ROOT, " part0Yaw=%.0f", shownYaw(g.body.pieces.get(0).d)) : "");
            }
            default -> {
                return null;
            }
        }
    }

    /** 시험: a[i] 가 번호면 그 개체, 종 이름(G02)이면 사람에게 가장 가까운 그 종. */
    private Gabe pick(Player p, String[] a, int i) {
        if (a.length <= i) return null;
        try {
            return byId(Integer.parseInt(a[i]));
        } catch (NumberFormatException e) {
            Species f = Species.of(a[i]);
            if (f == null) return null;
            Vector at = p != null ? p.getLocation().toVector() : new Vector(Plaza.CX, Plaza.FLOOR_Y, Plaza.CZ);
            return nearest(at, 1000, x -> x.sp == f);
        }
    }

    /** 디스플레이에 실린 아이템의 맞은 번쩍임 도장(보통 면 가운데 가장 작은 값, 없으면 "none"). 셰이더가 읽는 그대로. */
    private static String stamp(ItemDisplay d) {
        if (d == null || !d.isValid()) return "-";
        CustomModelData cmd = d.getItemStack().getData(DataComponentTypes.CUSTOM_MODEL_DATA);
        if (cmd == null) return "-";
        int best = Look.HURT_NONE;
        for (org.bukkit.Color c : cmd.colors()) {
            int pay = c.asRGB() & 0x3FFFF;
            if (((pay >> 17) & 1) == 0) best = Math.min(best, pay & 511);
        }
        return best == Look.HURT_NONE ? "none" : String.valueOf(best);
    }

    /** 디스플레이에 실린 아이템의 첫 자세 면 주기 칸(15 = 멈춤, 소리 없는 웨이브), 자세 면이 없으면 "-". */
    private static String posed(ItemDisplay d) {
        if (d == null || !d.isValid()) return "-";
        CustomModelData cmd = d.getItemStack().getData(DataComponentTypes.CUSTOM_MODEL_DATA);
        if (cmd == null) return "-";
        for (org.bukkit.Color c : cmd.colors()) {
            int pay = c.asRGB() & 0x3FFFF;
            if (((pay >> 17) & 1) == 1) return String.valueOf((pay >> 4) & 15);
        }
        return "-";
    }

    /** 디스플레이 transformation 이 그리는 몸 앞 방향(도, Gabe.yaw 와 같은 약속): 모델 앞 -Z 를 세계로 옮긴 것. */
    private static float shownYaw(ItemDisplay d) {
        if (d == null || !d.isValid()) return Float.NaN;
        org.bukkit.util.Transformation t = d.getTransformation();
        Matrix4f m = new Matrix4f().translate(t.getTranslation()).rotate(t.getLeftRotation()).scale(t.getScale()).rotate(t.getRightRotation());
        org.joml.Vector3f f = m.transformDirection(new org.joml.Vector3f(0, 0, -1));
        return (float) Math.toDegrees(Math.atan2(-f.x, f.z));
    }

    /** 시험 훅 몹틀: 빈 닻(Body.carrier) + 부품 하나. 제자리에서 돈다. */
    private static final class Rig extends Gabe {
        Rig(Game game) {
            super(game, Species.G01);
            noReward = true;
            reward = 0;
            noRules = true;
        }

        @Override
        protected void build() {
            body = Body.carrier(this);
            body.attach("gabe/g01");
        }

        @Override
        public void tick() {
            hold();
            yaw += 6;
        }
    }

    /** 시험 훅 몹고장: 정해 둔 곳에서 일부러 던지는 개체(예외 격리 확인용, G01 모양). */
    private static final class Broken extends Gabe {
        final String where;

        Broken(Game game, String where) {
            super(game, Species.G01);
            this.where = where;
            noReward = true;
            reward = 0;
        }

        private void boom(String w) {
            if (where.equals(w)) throw new IllegalStateException("시험 고장: " + w);
        }

        @Override
        protected void build() {
            body = Body.of(this, "gabe/g01");
            boom("build");
        }

        @Override
        protected void spawned() {
            boom("spawned");
        }

        @Override
        public void tick() {
            boom("tick");
            hold();
        }

        @Override
        public List<double[]> spheres() {
            boom("spheres");
            return super.spheres();
        }

        @Override
        public Vector head() {
            boom("head");
            return super.head();
        }

        @Override
        public void bitten(Gabe other) {
            boom("bitten");
        }

        @Override
        public boolean damage(Damage d) {
            boom("damage");
            return super.damage(d);
        }
    }

    /** 시험용: 이 사람 자리의 흐름 방향을 바로 다시 계산. */
    public void flowNow() {
        flow.invalidate();
    }

    /** 살아 있는 개체 중 종 sp 의 수. */
    public int count(Species sp) {
        int n = 0;
        for (Gabe g : live) if (!g.dead && g.sp == sp) n++;
        return n;
    }

    /** 이 판에 이미 한 번이라도 잡힌 종들. */
    public Set<Species> killedSpecies() {
        return Collections.unmodifiableSet(killedThisRound);
    }
}
