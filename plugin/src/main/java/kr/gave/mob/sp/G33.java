package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G33 토끼귀 게이브(돌격, 정예). 1 블록 얼굴에 토끼 귀처럼 솟은 막대기 둘. 머리 높이에 느리게 둥실 떠서 콧노래를 흥얼거리며 두 귀를 번갈아
 * 까딱여('쫑긋쫑긋') 소리를 찾는다.
 *
 * <p>도감: 듣는 반경(16블록 + 배운 만큼) 안에서 큰 소리 무기(저격, 산탄, 수류탄 던짐) 한 발을 낸 사람, 또는 3초 넘게 잠잠하다가 처음 쏜 사람을
 * 고른다. 고르면 콧노래가 뚝 끊기고 두 귀가 그 사람 쪽으로 착 겨눠지며 실눈을 뜬다(그 사람 조준점 옆에 이 종 아이콘이 깜빡임). 1.2초 뒤 귀를
 * 눕히고 초고속 일직선 돌진, 표적에게만 박치기 5 피해(다른 사람은 그냥 지나침). 귀를 겨눈 동안 표적이 쏘기를 멈추고 다른 사람이 쏘면 표적이
 * 그 사람으로 넘어간다. 이 종이 사람을 쓰러뜨릴 때마다(판 전체 누적) 듣는 반경 +2블록(상한 +10), 귀가 길어진다(1번, 3번째에 한 단계씩).
 * 딸랑이를 던지면 그 자리로 돌진한다(돌진로를 쏠 틈).
 *
 * <p>도감과 다른 점: 배움(쓰러뜨린 수)은 개체가 아니라 판 전체 이 종이 나눠 가진다(멀리서 귀 길이로 단계를 읽게). 폭발 소리는 수류탄을 던진
 * 사람의 던진 순간으로 친다(터지는 자리 사건이 없음).
 *
 * <p>팩 계약(tools/gabe/sp/g33.py): 모델 {@code gave:gabe/g33} 상태 {@code ""}/{@code "l1"}/{@code "l2"}(듣기), {@code "aim"}/{@code "aim1"}/
 * {@code "aim2"}, {@code "dash"}/{@code "dash1"}/{@code "dash2"}. 소리 hum(20), twitch(16), lock(24), dash(32), bump(16). 원인 글귀 {@code cause_g33}.
 */
public final class G33 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.09, HOVER = 2.0, DIVE = 1.35;
    /** 듣는 반경, 쓰러뜨릴 때마다 더하는 값, 더하는 상한(= 5번). */
    static final double HEAR = 22, HEAR_STEP = 2, HEAR_ADD_MAX = 10;
    /** 잠잠하다고 치는 틱(3초), 큰 소리로 치는 소리 등급. */
    static final int QUIET = 60, LOUD = 3;
    /** 귀를 겨눈 채 뜸 들이는 틱, 표적이 넘어간 뒤 남기는 최소 틱, 표적이 조용했다고 치는 틱. */
    static final int AIM = 24, AIM_AGAIN = 12, HUSH = 6;
    /** 돌진 빠르기(블록/틱 = 초당 22블록), 표적 너머로 더 가는 거리, 최대 틱. */
    static final double DASH_V = 1.1, OVERSHOOT = 2.5;
    static final int DASH_MAX = 32;
    /** 박치기 피해, 밀침, 돌진 뒤 쉬는 틱. */
    static final double DAMAGE = 6, PUSH = 0.6, PUSH_UP = 0.25;
    static final int RECOVER = 50;
    /** 귀 까딱임 한 바퀴(모델 주기와 같음), 콧노래 간격. */
    static final int EAR_PERIOD = 40, HUM_EVERY = 34;

    enum Phase { LISTEN, AIM, DASH, RECOVER }

    private Phase phase = Phase.LISTEN;
    private int phaseAt, aimUntil, nextHum, heardAt = -QUIET;
    private Player prey;
    /** 딸랑이 자리(사람 대신 겨누는 곳), 없으면 null. */
    private Vector decoy;
    private final Vector dashDir = new Vector(0, 0, 1);
    private final Vector prev = new Vector();
    private double dashGone, dashLen, slideV;
    private boolean struck;

    public G33(Game game, Species sp) {
        super(game, sp);
        radius = 0.5;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g33", 2.2f, 3.6f);
        // 귀 까딱임과 소리를 맞추려고 위상 0(모든 토끼귀가 한 박자로 까딱임)
        body.look.phase = 0;
        look("");
    }

    @Override
    protected void spawned() {
        nextHum = age + game.random.nextInt(HUM_EVERY);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    /** 귀 길이 단계를 붙인 상태 이름으로(듣기 "", l1, l2 / aim, aim1, aim2 / dash, dash1, dash2). */
    private void look(String base) {
        int lv = Ear.of(game).level();
        state(lv == 0 ? base : base.isEmpty() ? "l" + lv : base + lv);
    }

    private double hearing() {
        return HEAR + Math.min(HEAR_ADD_MAX, HEAR_STEP * Ear.of(game).downs);
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        Ear ear = Ear.of(game);
        ear.update(game);
        switch (phase) {
            case LISTEN -> listen(ear);
            case AIM -> aim(ear);
            case DASH -> dashing(ear);
            case RECOVER -> recover();
        }
    }

    /** 느리게 둥실 떠서 흥얼거리며 귀를 까딱임. 반경 안에서 큰 소리 한 발, 또는 잠잠하다 처음 난 총소리의 임자를 고른다. */
    private void listen(Ear ear) {
        look("");
        hover = HOVER + 0.22 * Math.sin((age + id * 9) * 0.07);
        follow(speed());
        Player near = nearest(64);
        if (near != null) turnTo(chest(near), 1.5f);
        pitch = 0;
        long wt = game.worldTime();
        if (wt % EAR_PERIOD == 1 || wt % EAR_PERIOD == EAR_PERIOD / 2 + 1) sound("twitch", Sound.BLOCK_WOOL_STEP, 1.6f, 16);
        if (age >= nextHum) {
            sound("hum", Sound.ENTITY_CAT_PURR, 1.4f, 20);
            nextHum = age + HUM_EVERY + game.random.nextInt(8);
        }
        // 이번 틱에 난 소리 가운데 반경 안에서 가장 큰 것
        double r = hearing();
        Player loudest = null;
        int best = 0;
        for (int i = 0; i < ear.who.size(); i++) {
            Player p = ear.who.get(i);
            if (!standing(p) || dist(p) > r) continue;
            int rank = ear.rank.get(i);
            if (rank > best) {
                best = rank;
                loudest = p;
            }
        }
        if (loudest == null) return;
        boolean quiet = age - heardAt >= QUIET;
        heardAt = age;
        if (best >= LOUD || quiet) lockOn(loudest, null);
    }

    /** 귀를 겨눔: 콧노래가 끊기고 실눈을 뜬다. 사람이면 그 사람 HUD 에 표시. */
    private void lockOn(Player p, Vector at) {
        prey = p;
        decoy = at;
        mark(p);
        hold();
        look("aim");
        sound("lock", Sound.BLOCK_TRIPWIRE_CLICK_ON, 1.4f, 24);
        aimUntil = age + cd(AIM);
        if (p != null) game.hud.mark(p, "g33", cd(AIM) + 30);
        phase(Phase.AIM);
    }

    /** 1.2초 겨눔. 표적이 조용한 사이 다른 사람이 쏘면 그 사람으로 넘어간다. 끝나면 그 순간 표적 자리로 돌진. */
    private void aim(Ear ear) {
        hold();
        hover = HOVER;
        if (decoy == null && !standing(prey)) {
            calm();
            return;
        }
        if (decoy == null) {
            double r = hearing();
            for (int i = 0; i < ear.who.size(); i++) {
                Player p = ear.who.get(i);
                if (p == prey || !standing(p) || dist(p) > r) continue;
                if (game.now() - ear.lastShot(prey) < HUSH) continue;
                // 표적이 넘어감
                prey = p;
                mark(p);
                sound("lock", Sound.BLOCK_TRIPWIRE_CLICK_ON, 1.4f, 24);
                game.hud.mark(p, "g33", AIM_AGAIN + 30);
                aimUntil = Math.max(aimUntil, age + AIM_AGAIN);
                break;
            }
        }
        Vector to = decoy != null ? decoy : chest(prey);
        face(to);
        pitch = Math.max(-25f, Math.min(25f, pitch));
        if (age < aimUntil) return;
        Vector d = to.clone().subtract(pos).setY(0);
        double l = d.length();
        dashDir.copy(l < 1e-6 ? new Vector(0, 0, 1) : d.multiply(1 / l));
        dashLen = l + OVERSHOOT;
        dashGone = 0;
        struck = false;
        noPush = true;
        hover = DIVE;
        pitch = 0;
        look("dash");
        sound("dash", Sound.ENTITY_PHANTOM_SWOOP, 1.5f, 32);
        prev.copy(pos);
        phase(Phase.DASH);
    }

    /** 귀를 눕히고 초고속 일직선 돌진. 표적에게만 박치기(한 틱에 1블록 넘게 가므로 지난 자리부터 쓸어 봄). */
    private void dashing(Ear ear) {
        double v = DASH_V * speed() / baseSpeed;
        dash(dashDir, v);
        face(pos.clone().add(dashDir));
        pitch = 0;
        if (!struck && prey != null && standing(prey) && swept(prey)) strike(ear, prey);
        prev.copy(pos);
        dashGone += v;
        if (struck || dashGone >= dashLen || inPhase() >= DASH_MAX || wallAhead(1.2)) {
            slideV = struck ? 0.15 : Math.min(0.5, v);
            noPush = false;
            hover = HOVER;
            look("");
            mark(null);
            prey = null;
            decoy = null;
            phase(Phase.RECOVER);
        }
    }

    /** 지난 틱 자리에서 지금 자리까지의 선분이 표적 몸통을 스쳤나(수평 거리, 높이 차는 touches 와 같은 한도). */
    private boolean swept(Player p) {
        Vector c = chest(p);
        if (Math.abs(pos.getY() - c.getY()) > 1.2 + radius + 0.2) return false;
        double ax = prev.getX(), az = prev.getZ(), bx = pos.getX(), bz = pos.getZ();
        double dx = bx - ax, dz = bz - az, l2 = dx * dx + dz * dz;
        double t = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((c.getX() - ax) * dx + (c.getZ() - az) * dz) / l2));
        double qx = ax + dx * t - c.getX(), qz = az + dz * t - c.getZ();
        double reach = radius + 0.4 + 0.2;
        return qx * qx + qz * qz <= reach * reach;
    }

    private void strike(Ear ear, Player p) {
        struck = true;
        hit(p, DAMAGE, "cause_g33");
        knock(p, dashDir, PUSH, PUSH_UP);
        sound("bump", Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.7f, 16);
        body.squash(1.3f, 0.75f, 0.8f, 2);
        if (!game.players.get(p).standing()) ear.learn();
    }

    /** 미끄러지며 느려진 뒤 다시 귀를 세움. */
    private void recover() {
        dash(dashDir, slideV);
        slideV *= 0.84;
        if (inPhase() == 3) body.squash(1f, 1f, 1f, 4);
        Player p = nearest(64);
        if (inPhase() > 12 && p != null) turnTo(chest(p), 5f);
        if (inPhase() >= cd(RECOVER)) {
            heardAt = age; // 방금 뒤의 총소리는 '잠잠하다 처음'으로 치지 않음
            hold();
            phase(Phase.LISTEN);
        }
    }

    private void calm() {
        mark(null);
        prey = null;
        decoy = null;
        noPush = false;
        hover = HOVER;
        look("");
        phase(Phase.LISTEN);
    }

    /** 딸랑이: 엔진에 끌려가지 않고, 듣는 중이거나 겨누는 중이면 그 자리를 겨눠 돌진한다. */
    @Override
    public void lure(Vector at, int ticks) {
        if (dead || at == null) return;
        if (phase == Phase.LISTEN || phase == Phase.AIM) lockOn(null, at.clone());
    }

    /** 시험용. */
    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + String.format(java.util.Locale.ROOT, " hear=%.0f", hearing()) + " downs=" + Ear.of(game).downs
                + (prey != null ? " prey=" + prey.getName() : "") + (decoy != null ? " decoy" : "");
    }

    // ------------------------------------------------------------------ 소리 듣기(판마다 하나)

    /**
     * 이번 틱에 난 무기 소리: 쏜 사람(쏜 발 수가 늚, 든 총의 소리 등급)과 수류탄을 던진 사람(등급 3). 그 틱에 처음 부른 개체가 갱신한다.
     * 배움(이 종이 쓰러뜨린 사람 수, 판마다 0 부터)도 여기 둔다.
     */
    static final class Ear {

        private static final Map<Game, Ear> ALL = new WeakHashMap<>();

        static Ear of(Game game) {
            return ALL.computeIfAbsent(game, k -> new Ear());
        }

        /** 이번 틱에 소리 낸 사람과 그 등급(1 권총, 기관단총 / 2 소총 / 3 산탄, 저격, 수류탄). */
        final List<Player> who = new ArrayList<>();
        final List<Integer> rank = new ArrayList<>();
        /** 이 종이 쓰러뜨린 사람 수(상한 5). */
        int downs;
        private final Map<UUID, int[]> seen = new HashMap<>();
        private final Map<UUID, Long> shotAt = new HashMap<>();
        private long tick = Long.MIN_VALUE;
        private int round = Integer.MIN_VALUE;

        int level() {
            return downs >= 3 ? 2 : downs >= 1 ? 1 : 0;
        }

        void learn() {
            downs = Math.min((int) (HEAR_ADD_MAX / HEAR_STEP), downs + 1);
        }

        long lastShot(Player p) {
            Long t = p == null ? null : shotAt.get(p.getUniqueId());
            return t == null ? Long.MIN_VALUE / 2 : t;
        }

        void update(Game game) {
            long now = game.now();
            if (now == tick) return;
            if (game.round != round || now < tick) {
                round = game.round;
                downs = 0;
                seen.clear();
                shotAt.clear();
            }
            tick = now;
            who.clear();
            rank.clear();
            List<Player> ps = game.mobs.standing();
            for (int i = 0; i < ps.size(); i++) {
                Player p = ps.get(i);
                Guns.Arms a = game.guns.arms(p);
                if (a == null) continue;
                int[] s = seen.get(p.getUniqueId());
                if (s == null) {
                    seen.put(p.getUniqueId(), new int[] {a.shots, a.grenades});
                    continue;
                }
                int r = 0;
                if (a.shots > s[0]) {
                    Guns.Spec g = game.guns.held(p);
                    Guns.Kind k = g == null ? null : g.kind();
                    r = k == Guns.Kind.SNIPER || k == Guns.Kind.SHOTGUN ? 3 : k == Guns.Kind.RIFLE ? 2 : 1;
                }
                if (a.grenades < s[1]) r = 3;
                s[0] = a.shots;
                s[1] = a.grenades;
                if (r > 0) {
                    who.add(p);
                    rank.add(r);
                    shotAt.put(p.getUniqueId(), now);
                }
            }
        }
    }
}
