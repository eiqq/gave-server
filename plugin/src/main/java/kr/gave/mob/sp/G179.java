package kr.gave.mob.sp;

import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G179 다시 오는 게이브(정예, 튼튼이). 잡아도 다음 웨이브에 기운 자국을 달고 더 세져서 돌아와, 자기를 죽인 사람을 찾아가고 지난번 무기는 안 먹힌다.
 *
 * <p>판마다 하나의 기록(이 종 static, game.round 가 바뀌면 지움): 죽은 횟수, 마지막 처치자, 가장 많이 때린 사람, 이번 삶에서 피해 종류별로 받은 합,
 * 안 먹히는 종류, 넘긴 체력. 놓일 때 이미 다른 하나가 살아 있거나 네 번 잡혔으면 첫 틱에 조용히 사라진다(웨이브에 하나, 네 번 잡으면 영영 안 옴).
 *
 * <p>돌아올 때마다(죽은 횟수 n): 체력 x1.5^n, 빠르기 0.16 / 0.2 / 0.24 / 0.28, 피해 +4n, 기운 자국 on:scar1..n, 멍에 막대기 on:rod3(n 1 이상),
 * on:rod4(n 2 이상), 가슴 덧판 on:pl_<종류>. 지난번에 가장 많이 맞은 종류(총알, 산탄, 저격, 폭발, 불)에는 5분의 1만 받고 쏜 사람 조준점 옆에 이 종
 * 표시가 뜬다. 지난번 처치자(없으면 가장 많이 때린 사람, 그다음 가장 가까운 사람)를 찾아 곧장 가고, 그 사람 조준점 옆에 이 종 표시를 띄운다.
 * 감독이 낸 개체는 고리에서 5초 기다렸다 떼보다 늦게 걸어 나온다. 처음 10블록 안에 들면 멈춰 끙끙대며 가리킨 뒤 눈을 뜬다. 5블록 안에서 어깨 박치기(20 + 4n, 5블록 날림), 이어서 내려찍기(14 + 4n, 반경 2).
 * 못 잡고 웨이브를 넘기면(웨이브 끝 정리) 다음에 그 체력 비율 그대로 온다. 네 번째로 잡으면 판에 든 모두가 증강 뽑기 하나씩.
 *
 * <p>팩 계약(tools/gabe/sp/g179.py): 상태 "", 부위 scar1..3, rod3, rod4, pl_bullet, pl_pellet, pl_sniper, pl_explosion, pl_fire. 소리 groan(32),
 * ram(20), slam(24), clank(20). 글귀 cause_g179.
 */
public final class G179 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double HOVER = 1.3, RADIUS = 0.75;
    static final double[] SPEED = {0.16, 0.2, 0.24, 0.28};
    static final double HP_STEP = 1.5, DMG_STEP = 4, IMMUNE_MUL = 0.2;
    /** 박치기: 거리, 준비, 돌진(빠르기, 틱), 피해, 날림(수평, 위), 내려찍기(늦음, 피해, 반경), 쿨다운. 가리키기 거리와 틱. */
    static final double RAM_R = 5, DASH_V = 0.5, RAM = 20, THROW = 1.2, THROW_UP = 0.45, SLAM = 14, SLAM_R = 2, POINT_R = 10;
    static final int WIND = 10, DASH_T = 12, SLAM_AT = 8, RAM_CD = 50, POINT_T = 30, MARK_EVERY = 40, LATE = 100;
    static final DamageType[] KINDS = {DamageType.BULLET, DamageType.PELLET, DamageType.SNIPER, DamageType.EXPLOSION, DamageType.FIRE};

    enum Phase { WALK, POINT, WIND, DASH, SLAM }

    /** 판 하나의 기록. */
    static final class Record {
        Game game;
        int round = Integer.MIN_VALUE;
        int deaths;
        UUID lastKiller, topHitter;
        DamageType immune;
        double carryFrac = -1;
        int carryWave = -1;
        final double[] byType = new double[DamageType.values().length];
    }

    private static final Record REC = new Record();

    private Phase phase = Phase.WALK;
    private int phaseAt, readyAt, clankAt = -100;
    private boolean init, vanish, pointed;
    private int n;
    private DamageType immune;
    private Player prey;
    private final Vector dashDir = new Vector();

    public G179(Game game, Species sp) {
        super(game, sp);
        radius = RADIUS;
        hover = HOVER;
        baseSpeed = SPEED[0];
        ragKick = 0.5;
        noBite = true;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g179", 2.4f, 2.8f);
    }

    private Record rec() {
        Record r = REC;
        if (r.game != game || r.round != game.round) {
            r.game = game;
            r.round = game.round;
            r.deaths = 0;
            r.lastKiller = r.topHitter = null;
            r.immune = null;
            r.carryFrac = -1;
            r.carryWave = -1;
            java.util.Arrays.fill(r.byType, 0);
        }
        return r;
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        Record r = rec();
        if (r.deaths >= 4) vanish = true;
        for (Gabe g : game.mobs.all()) if (g != this && !g.dead && g instanceof G179) vanish = true;
    }

    /** 첫 틱(감독 체력 배율 뒤): 돌아온 횟수만큼 세지고 기록을 읽음. */
    private void init() {
        init = true;
        Record r = rec();
        n = Math.min(4, r.deaths);
        maxHp *= Math.pow(HP_STEP, n);
        hp = maxHp;
        if (r.carryFrac > 0 && r.carryWave == game.wave - 1) hp = Math.max(1, maxHp * r.carryFrac);
        r.carryFrac = -1;
        java.util.Arrays.fill(r.byType, 0);
        baseSpeed = SPEED[Math.min(3, n)];
        immune = n > 0 ? r.immune : null;
        for (int i = 1; i <= Math.min(3, n); i++) body.look.on.add("scar" + i);
        if (n >= 1) body.look.on.add("rod3");
        if (n >= 2) body.look.on.add("rod4");
        if (immune != null) body.look.on.add("pl_" + immune.name().toLowerCase(java.util.Locale.ROOT));
        prey = pickPrey(r);
        // 찾아갈 사람: 눈은 아직 감은 채(가까이 와서 가리킨 뒤 뜸)
        target = prey;
        pointed = n == 0;
    }

    private Player pickPrey(Record r) {
        for (UUID id : new UUID[] {r.lastKiller, r.topHitter}) {
            if (id == null) continue;
            Player p = Bukkit.getPlayer(id);
            if (standing(p)) return p;
        }
        return nearest(400);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (vanish) {
            remove();
            return;
        }
        if (!init) init();
        // 떼보다 조금 늦게 혼자 걸어 나옴: 감독이 낸 개체는 고리(흙먼지 속)에서 LATE 틱 기다림
        if (age < LATE && game.director.fromDirector(this)) {
            hold();
            return;
        }
        if (!standing(prey)) {
            prey = pickPrey(rec());
            if (prey != null && pointed) mark(prey);
            else target = prey;
        }
        if (prey != null && every(MARK_EVERY)) game.hud.mark(prey, "g179", MARK_EVERY + 10);
        switch (phase) {
            case WALK -> walk();
            case POINT -> point();
            case WIND -> wind();
            case DASH -> dashing();
            case SLAM -> slam();
        }
    }

    private void walk() {
        Player p = prey;
        if (p == null) {
            follow(speed());
            return;
        }
        double d = dist(p);
        if (d > 30) follow(speed());
        else seek(chest(p).setY(pos.getY()), speed());
        face(chest(p));
        pitch = 0;
        if (every(60)) sound("groan", Sound.ENTITY_ZOMBIE_AMBIENT, 0.6f, 32);
        if (!pointed && d <= POINT_R) {
            hold();
            sound("groan", Sound.ENTITY_ZOMBIE_AMBIENT, 0.6f, 32);
            phase(Phase.POINT);
            return;
        }
        if (pointed && age >= readyAt && d <= RAM_R && sees(p)) {
            hold();
            body.look.rate = 2;
            pitch = 14;
            sound("groan", Sound.ENTITY_ZOMBIE_AMBIENT, 0.6f, 32);
            phase(Phase.WIND);
        }
    }

    /** 나를 죽인 사람 앞에 서서 기운 자국을 가리킨 뒤 눈을 뜸. */
    private void point() {
        hold();
        if (prey != null) face(chest(prey));
        if (inPhase() >= POINT_T) {
            pointed = true;
            if (prey != null) mark(prey);
            readyAt = age;
            phase(Phase.WALK);
        }
    }

    private void wind() {
        Player p = prey;
        if (!standing(p)) {
            calm();
            return;
        }
        hold();
        face(chest(p));
        pitch = 14;
        if (inPhase() >= cd(WIND)) {
            dashDir.copy(toward(p));
            phase(Phase.DASH);
        }
    }

    private void dashing() {
        dash(dashDir, DASH_V * speed() / baseSpeed + 0.2);
        face(pos.clone().add(dashDir));
        pitch = 14;
        for (Player q : game.mobs.standing()) {
            if (!touches(q, 0.2)) continue;
            hit(q, RAM + DMG_STEP * n, "cause_g179");
            knock(q, dashDir, THROW, THROW_UP);
            sound("ram", Sound.ENTITY_IRON_GOLEM_ATTACK, 0.7f, 20);
            hold();
            phase(Phase.SLAM);
            return;
        }
        if (inPhase() >= DASH_T || wallAhead(0.8)) {
            hold();
            phase(Phase.SLAM);
        }
    }

    private void slam() {
        hold();
        if (inPhase() == SLAM_AT) {
            sound("slam", Sound.ENTITY_GENERIC_BIG_FALL, 0.5f, 24);
            body.squash(1.12f, 0.85f, 1.12f, 2);
            Vector feet = pos.clone().setY(floor());
            for (Player q : game.mobs.standing()) {
                if (q.getLocation().toVector().distance(feet) <= SLAM_R + RADIUS) hit(q, SLAM + DMG_STEP * n, "cause_g179");
            }
        }
        if (inPhase() >= SLAM_AT + 6) calm();
    }

    private void calm() {
        body.look.rate = 1;
        body.squash(1f, 1f, 1f, 4);
        pitch = 0;
        readyAt = age + cd(RAM_CD);
        phase(Phase.WALK);
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (!init) init();
        Damage use = d;
        if (immune != null && d.type() == immune) {
            use = d.times(IMMUNE_MUL);
            if (d.by() != null) game.hud.mark(d.by(), "g179", 20);
            if (age - clankAt >= 8) {
                clankAt = age;
                sound("clank", Sound.ITEM_SHIELD_BLOCK, 0.9f, 20);
            }
        }
        double before = hp;
        boolean r = super.damage(use);
        rec().byType[d.type().ordinal()] += Math.max(0, before - Math.max(hp, 0));
        return r;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        Record r = rec();
        if (!vanish) {
            if (noReward || d == null) {
                // 웨이브 끝 정리(못 잡음): 다음 웨이브에 이 체력 비율 그대로
                r.carryFrac = maxHp > 0 ? Math.max(0.05, hp / maxHp) : -1;
                r.carryWave = game.wave;
            } else {
                r.deaths++;
                UUID top = topHitter();
                r.topHitter = top;
                r.lastKiller = d.by() != null ? d.by().getUniqueId() : top;
                DamageType best = null;
                double bv = 0;
                for (DamageType k : KINDS) {
                    if (r.byType[k.ordinal()] > bv) {
                        bv = r.byType[k.ordinal()];
                        best = k;
                    }
                }
                r.immune = best != null ? best : (contains(KINDS, d.type()) ? d.type() : DamageType.BULLET);
                r.carryFrac = -1;
                if (r.deaths == 4) for (Player p : game.players.inPlay()) game.augments.offer(p, 3);
            }
        }
        super.die(d);
    }

    private UUID topHitter() {
        Map<UUID, Double> c = game.mobs.contributions(this);
        UUID best = null;
        double bv = 0;
        if (c == null) return null;
        for (Map.Entry<UUID, Double> e : c.entrySet()) {
            if (e.getValue() > bv) {
                bv = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    private static boolean contains(DamageType[] a, DamageType t) {
        for (DamageType x : a) if (x == t) return true;
        return false;
    }

    /** 시험용. */
    public String info() {
        return "n=" + n + " immune=" + immune + " ph=" + phase + (prey == null ? "" : " prey=" + prey.getName()) + " deaths=" + REC.deaths;
    }

    @Override
    public String toString() {
        return super.toString() + " " + info();
    }
}
