package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.world.Plaza;

/**
 * G164 몰이 게이브(떼, 함정). 막대기로 땅을 두드리는 몰이꾼 얼굴 띠가 사람들을 한쪽으로 몰아가고, 몰려간 물가에서 기다리던 왜가리가 부리로 내리찍어
 * 진흙에 꽂는다.
 *
 * <p>띠 묶기(G56 과 같은 꼴): 감독은 개체를 고리의 서로 다른 각도에 하나씩 놓으므로, 놓인 직후(spawned) 그 웨이브의 '열린 띠'(이 종 static)에 붙는다.
 * 띠가 아직 사람에게서 멀면 그 자리(띠 안 제 칸)로 옮긴다(고리는 흙먼지 속이라 안 보임). 웨이브에 띠는 하나: 이미 몰이가 끝난 웨이브에 새로 온 것은
 * 혼자 기어 와 덮친다. 띠는 모이는 동안(GATHER) 모자란 몫을 보상 없는 몰이꾼으로 MIN_BAND 까지 채운다.
 *
 * <p>차례: GATHER(가장자리에서 모임) → COME(사람들 가운데 쪽으로 다가옴, 빠르게) → 사람들에게서 SWEEP_AT 블록이 되면 방향을 굳히고 반대쪽 물가에
 * 왜가리 둘셋을 세움(보상 있는 child, 체력 60) → SWEEP(0.12블록/틱으로 사람들을 가로질러 훑음. 띠에 닿은 사람은 4 피해와 띠가 가는 쪽으로 밀림,
 * 0.5초마다) → 띠가 물가를 지나거나 사람이 모두 띠 뒤로 넘어가거나 몰이꾼이 거의 다 죽으면 DONE(몰이꾼은 저마다 덮쳐 옴). 띠 자리는 고정이라
 * 몇 마리만 잡으면 구멍이 나고, 그 구멍으로 띠 뒤로 넘어가면 몰이가 끝난다.
 *
 * <p>왜가리: 외발로 서서 기다리다 6블록 안에 사람이 들면 목을 뒤로 접고(0.5초, 그동안 피해 x2) 번개처럼 뻗어 내리찍는다(18 피해와 1.2초 묶임).
 * 묶인 사람은 동료가 그 왜가리를 맞히면 풀린다. 몰이가 끝나고 10초 뒤(또는 몰이꾼이 다 죽으면) 물가를 떠나 사람 쪽으로 걸어 온다.
 *
 * <p>팩 계약(tools/gabe/sp/g164.py): 몰이꾼 모델 gabe/g164 상태 "", 왜가리 모델 gabe/g164_heron 상태 "", "cock", "strike". 소리 beat(40), stab(24),
 * cock(20), 글귀 cause_g164.
 */
public final class G164 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double HOVER = 0.28, RADIUS = 0.36;
    /** 다가오는 빠르기, 훑는 빠르기(블록/틱, 감독 배율 곱함). 띠에서 처진 몰이꾼이 제 칸으로 따라붙는 최대 빠르기. */
    static final double COME = 0.22, SWEEP = 0.12, CATCH = 0.36;
    /** 띠 간격, 활꼴 깊이(띠 끝이 가운데보다 앞섬, 띠 반폭에 대한 몫). */
    static final double SPACING = 0.9, ARC = 0.28;
    /** 띠 묶기: 모이는 틱, 최소, 최대 몰이꾼 수. 사람에게서 멂(이 밖이면 자리 옮김을 받아 줌). */
    static final int GATHER = 40, MIN_BAND = 20, MAX_BAND = 36, SWEEP_MAX = 1400;
    static final double FAR = 40;
    /** 사람들 가운데에서 이 거리가 되면 훑기 시작, 물가 거리(띠 반대쪽). */
    static final double SWEEP_AT = 26, SHORE = 20;
    /** 띠 닿음: 피해, 밀침(0.8 = 땅 마찰로 약 2블록), 사람마다 쿨다운(띠 전체로, 띠가 끝난 뒤 흩어진 몰이꾼도 같은 띠면 함께 셈). */
    static final double TOUCH = 4, PUSH = 0.8, PUSH_UP = 0.1, TOUCH_R = 1.0;
    static final int TOUCH_CD = 10, BEAT_EVERY = 6;
    /** 왜가리: 높이, 체력, 보상, 찍는 거리, 준비, 쿨다운, 뻗기(빠르기, 틱), 피해, 묶임, 풀려 걷는 빠르기와 기다림. */
    static final double HERON_HOVER = 1.6, HERON_HP = 60, HERON_REACH = 6, LUNGE = 0.85, BEAK = 1.5, STAB = 18, WADE = 0.08;
    static final int HERON_REWARD = 8, WINDUP = 10, STRIKE_CD = 30, LUNGE_T = 6, ROOT = 24, FREE_AFTER = 200, PIN_GRACE = 40;

    enum Phase { GATHER, COME, SWEEP, DONE }

    enum HPhase { WAIT, COCK, STRIKE, BACK }

    /** 한 띠(웨이브에 하나). */
    static final class Herd {
        final Game game;
        final int round, wave;
        final long t0;
        final List<G164> members = new ArrayList<>();
        final List<G164> herons = new ArrayList<>();
        final Vector anchor = new Vector(), dir = new Vector(0, 0, 1), side = new Vector(1, 0, 0), shore = new Vector();
        final Map<UUID, Long> touchUntil = new HashMap<>();
        Phase phase = Phase.GATHER;
        long phaseAt, beatTick = Long.MIN_VALUE, nextBeat, doneAt = -1;
        int nextSlot;
        double halfW = 1;

        Herd(Game game, long t0, Vector at) {
            this.game = game;
            this.round = game.round;
            this.wave = game.wave;
            this.t0 = t0;
            this.phaseAt = t0;
            anchor.copy(at);
        }

        boolean same(Game g) {
            return g == game && g.round == round && g.wave == wave;
        }
    }

    private static final List<Herd> HERDS = new ArrayList<>();
    /** 사람마다 왜가리 부리에 꽂힌 끝 틱(왜가리끼리 나눔): 풀리고 PIN_GRACE 틱이 지나야 다시 찍는다(둘셋이 번갈아 찍어 영영 묶어 두지 않게). */
    private static final Map<UUID, Long> PINNED = new HashMap<>();
    /** child 로 왜가리를 만들 때만 true(생성자가 읽음). */
    private static boolean spawnHeron;

    final boolean heron;
    private Herd herd;
    /** 띠 안 제 칸(가운데에서 옆으로 몇 칸, 고정: 죽어도 다시 매기지 않아 구멍이 남음). */
    private int slot;
    private boolean loose;
    private int readyAt;
    // 왜가리
    private HPhase hph = HPhase.WAIT;
    private int hAt;
    private final Vector spot = new Vector(), lungeDir = new Vector();
    private Player prey, pinned;
    private long pinnedUntil;

    public G164(Game game, Species sp) {
        super(game, sp);
        heron = spawnHeron;
        if (heron) {
            radius = 0.55;
            hover = HERON_HOVER;
            baseSpeed = WADE;
            noPush = true;
            noBite = true;
            noMutate = true;
        } else {
            radius = RADIUS;
            hover = HOVER;
            baseSpeed = SWEEP;
            noPush = true;
        }
    }

    @Override
    protected void build() {
        if (heron) body = Body.of(this, "gabe/g164_heron", 2.0f, 3.4f);
        else body = Body.of(this, "gabe/g164", 1.4f, 0.8f);
    }

    // ------------------------------------------------------------------ 놓임, 띠 묶기

    @Override
    protected void spawned() {
        if (heron) {
            spot.copy(pos);
            return;
        }
        if (game.director.cageSpawning()) {
            loose = true;
            return;
        }
        long now = game.now();
        Herd join = null;
        boolean doneThisWave = false;
        for (Iterator<Herd> it = HERDS.iterator(); it.hasNext();) {
            Herd h = it.next();
            if (!h.same(game)) {
                it.remove();
                continue;
            }
            if (h.phase == Phase.DONE) doneThisWave = true;
            else if (join == null && h.members.size() < MAX_BAND) join = h;
        }
        if (join == null) {
            if (doneThisWave) {
                loose = true;
                return;
            }
            join = new Herd(game, now, pos);
            Vector d = towardPeople(pos);
            join.dir.copy(d);
            join.side.setX(d.getZ()).setY(0).setZ(-d.getX());
            HERDS.add(join);
        }
        enter(join, true);
    }

    /** 띠에 든다(제 칸을 받고, 띠가 아직 사람에게서 멀면 그 칸으로 옮김). */
    private void enter(Herd h, boolean move) {
        herd = h;
        slot = lateral(h.nextSlot++);
        h.members.add(this);
        h.halfW = Math.max(h.halfW, Math.abs(slot) * SPACING + SPACING);
        if (move && game.mobs.nearestPlayer(h.anchor, FAR) == null && game.mobs.nearestPlayer(pos, FAR) == null) {
            Vector at = slotPos();
            if (game.mobs.flow.walkable(at.getX(), at.getZ())) {
                pos.setX(at.getX());
                pos.setZ(at.getZ());
                pos.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + HOVER);
            }
        }
        yaw = (float) Math.toDegrees(Math.atan2(-h.dir.getX(), h.dir.getZ()));
    }

    /** 칸 번호 k(들어온 차례) → 가운데에서 옆으로 몇 칸(0, +1, -1, +2, -2 ...). */
    private static int lateral(int k) {
        return k == 0 ? 0 : (k % 2 == 1 ? (k + 1) / 2 : -(k / 2));
    }

    /** 사람들 가운데(서 있는 사람 평균, 없으면 중앙) 쪽 수평 단위 벡터. */
    private Vector towardPeople(Vector from) {
        Vector c = centroid();
        Vector d = c.subtract(from).setY(0);
        if (d.lengthSquared() < 1e-6) d = game.mobs.flow.dir(from).setY(0);
        if (d.lengthSquared() < 1e-6) d = new Vector(0, 0, 1);
        return d.normalize();
    }

    private Vector centroid() {
        List<Player> ps = game.mobs.standing();
        if (ps.isEmpty()) return new Vector(Plaza.CX + 0.5, Plaza.FLOOR_Y, Plaza.CZ + 0.5);
        double x = 0, z = 0;
        for (Player p : ps) {
            x += p.getLocation().getX();
            z += p.getLocation().getZ();
        }
        return new Vector(x / ps.size(), Plaza.FLOOR_Y, z / ps.size());
    }

    /** 내 칸의 지금 자리(띠 닻 + 옆 + 활꼴 앞섬). */
    private Vector slotPos() {
        Herd h = herd;
        double l = slot * SPACING;
        double k = l / Math.max(1, h.halfW);
        double f = ARC * h.halfW * k * k;
        return h.anchor.clone().add(h.side.clone().multiply(l)).add(h.dir.clone().multiply(f));
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (heron) {
            heronTick();
            return;
        }
        if (loose || herd == null || herd.phase == Phase.DONE) {
            looseTick();
            return;
        }
        long now = now();
        if (herd.beatTick != now) herdBeat(now);
        if (herd.phase == Phase.DONE) {
            looseTick();
            return;
        }
        Vector want = slotPos();
        Vector to = want.subtract(pos).setY(0);
        double gap = to.length();
        double v = (herd.phase == Phase.SWEEP ? SWEEP : herd.phase == Phase.COME ? COME : 0) * speed() / baseSpeed;
        double cap = Math.max(v, Math.min(CATCH, gap * 0.25 + v));
        if (gap > 1e-4) vel.copy(to.multiply(Math.min(cap, gap) / gap));
        else hold();
        yaw = (float) Math.toDegrees(Math.atan2(-herd.dir.getX(), herd.dir.getZ()));
    }

    /** 띠 박자(한 틱에 한 번): 닻 옮기기, 차례 바꾸기, 두드림 소리, 띠 닿음. */
    private void herdBeat(long now) {
        Herd h = herd;
        h.beatTick = now;
        h.members.removeIf(m -> m.dead);
        h.herons.removeIf(m -> m.dead);
        double mul = speed() / baseSpeed;
        switch (h.phase) {
            case GATHER -> {
                if (now - h.phaseAt >= GATHER) {
                    fill(h);
                    h.phase = Phase.COME;
                    h.phaseAt = now;
                }
            }
            case COME -> {
                Vector c = centroid();
                Vector d = c.clone().subtract(h.anchor).setY(0);
                double dist = d.length();
                if (dist > 1e-3) {
                    // 사람들 쪽으로 천천히 고개를 틂(한 틱 2도)
                    d.multiply(1 / dist);
                    turn(h, d, Math.toRadians(2));
                }
                h.anchor.add(h.dir.clone().multiply(COME * mul));
                if (dist <= SWEEP_AT) startSweep(h, c, now);
                else if (now - h.phaseAt > SWEEP_MAX) done(h, now);
            }
            case SWEEP -> {
                h.anchor.add(h.dir.clone().multiply(SWEEP * mul));
                boolean past = h.anchor.clone().subtract(h.shore).dot(h.dir) > 0;
                if (past || h.members.size() < 4 || now - h.phaseAt > SWEEP_MAX || allBehind(h)) done(h, now);
            }
            default -> {
            }
        }
        if (h.phase == Phase.DONE) return;
        if (h.phase != Phase.GATHER && now >= h.nextBeat && !h.members.isEmpty()) {
            h.nextBeat = now + BEAT_EVERY;
            G164 mid = h.members.get(0);
            for (G164 m : h.members) if (Math.abs(m.slot) < Math.abs(mid.slot)) mid = m;
            mid.sound("beat", Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 40);
        }
        if (h.phase == Phase.SWEEP || h.phase == Phase.COME) touchBand(h, now);
    }

    /** 띠 방향을 목표 쪽으로 한 틱 maxRad 까지 돌림. */
    private static void turn(Herd h, Vector want, double maxRad) {
        double a0 = Math.atan2(h.dir.getZ(), h.dir.getX()), a1 = Math.atan2(want.getZ(), want.getX());
        double da = Math.atan2(Math.sin(a1 - a0), Math.cos(a1 - a0));
        da = Math.max(-maxRad, Math.min(maxRad, da));
        double a = a0 + da;
        h.dir.setX(Math.cos(a)).setY(0).setZ(Math.sin(a));
        h.side.setX(h.dir.getZ()).setY(0).setZ(-h.dir.getX());
    }

    /** 모자란 몰이꾼을 보상 없는 child 로 채움(띠 칸에 바로). */
    private void fill(Herd h) {
        int need = MIN_BAND - h.members.size();
        for (int i = 0; i < need; i++) {
            int k = h.nextSlot;
            double l = lateral(k) * SPACING;
            Vector at = h.anchor.clone().add(h.side.clone().multiply(l));
            if (!game.mobs.flow.walkable(at.getX(), at.getZ())) {
                h.nextSlot++;
                continue;
            }
            at.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + HOVER);
            // child 의 spawned() 가 이 띠(이 웨이브의 열린 띠)에 스스로 든다
            Gabe c = child(sp, at);
            if (c == null) break;
            c.maxHp = c.hp = sp.hp * game.director.hpMul(sp);
        }
    }

    /** 훑기 시작: 방향을 굳히고 반대쪽 물가에 왜가리를 세움. */
    private void startSweep(Herd h, Vector c, long now) {
        h.phase = Phase.SWEEP;
        h.phaseAt = now;
        Vector shore = c.clone().add(h.dir.clone().multiply(SHORE));
        // 물가: 그 둘레 10블록 안 물웅덩이가 있으면 그 물속
        List<Vector> pd = Plaza.puddles();
        double best = 100;
        for (Vector p : pd) {
            double d2 = p.clone().setY(0).distanceSquared(shore.clone().setY(0));
            if (d2 < best) {
                best = d2;
                shore.setX(p.getX()).setZ(p.getZ());
            }
        }
        h.shore.copy(shore);
        int n = h.members.size() >= 24 ? 3 : 2;
        for (int i = 0; i < n; i++) {
            double off = (i - (n - 1) / 2.0) * 3.5;
            Vector at = shore.clone().add(h.side.clone().multiply(off));
            for (int t = 0; t < 6 && !game.mobs.flow.walkable(at.getX(), at.getZ()); t++) at.subtract(h.dir.clone().multiply(2));
            if (!game.mobs.flow.walkable(at.getX(), at.getZ())) continue;
            at.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + HERON_HOVER);
            spawnHeron = true;
            Gabe g;
            try {
                g = child(sp, at);
            } finally {
                spawnHeron = false;
            }
            if (!(g instanceof G164 b)) continue;
            b.herd = h;
            b.maxHp = b.hp = HERON_HP * game.director.hpMul(sp);
            b.noReward = false;
            b.reward = (int) Math.max(1, Math.round(HERON_REWARD * game.director.rewardMul()));
            // 물을 내려다봄: 띠가 오는 쪽(사람들 쪽)을 봄
            b.yaw = (float) Math.toDegrees(Math.atan2(h.dir.getX(), -h.dir.getZ()));
            h.herons.add(b);
        }
    }

    private void done(Herd h, long now) {
        h.phase = Phase.DONE;
        h.doneAt = now;
    }

    /** 서 있는 사람(띠 30블록 안)이 모두 띠 뒤로 넘어갔나. */
    private static boolean allBehind(Herd h) {
        int near = 0;
        for (Player p : h.game.mobs.standing()) {
            Vector r = p.getLocation().toVector().subtract(h.anchor).setY(0);
            if (r.lengthSquared() > 900) continue;
            near++;
            if (r.dot(h.dir) > -2.5) return false;
        }
        return near > 0;
    }

    /** 띠에 닿은 사람: 4 피해와 띠가 가는 쪽으로 밀림(사람마다 0.5초에 한 번, 띠 전체로). */
    private static void touchBand(Herd h, long now) {
        double r2 = (TOUCH_R + RADIUS) * (TOUCH_R + RADIUS);
        for (Player p : h.game.mobs.standing()) {
            Long until = h.touchUntil.get(p.getUniqueId());
            if (until != null && until > now) continue;
            Vector c = p.getLocation().toVector();
            for (G164 m : h.members) {
                double dx = c.getX() - m.pos.getX(), dz = c.getZ() - m.pos.getZ();
                if (dx * dx + dz * dz > r2 || Math.abs(c.getY() + 0.5 - m.pos.getY()) > 1.5) continue;
                h.touchUntil.put(p.getUniqueId(), now + TOUCH_CD);
                m.hit(p, TOUCH, "cause_g164");
                m.knock(p, h.dir, PUSH, PUSH_UP);
                break;
            }
        }
    }

    /** 띠가 끝났거나 혼자 온 몰이꾼: 저마다 가장 가까운 사람에게 기어 와 두드려 덮침. */
    private void looseTick() {
        Player p = nearest(400);
        if (p == null) {
            follow(COME);
            return;
        }
        follow(COME * speed() / baseSpeed);
        face(chest(p));
        if (dist(p) < 6 && target != p) mark(p);
        if (age >= readyAt && touches(p, 0.3)) {
            // 띠에서 풀린 몰이꾼도 한 사람에게는 띠 전체로 0.5초에 한 번만(스무 마리가 한꺼번에 때려 바로 쓰러뜨리지 않게)
            long now = now();
            if (herd != null) {
                Long until = herd.touchUntil.get(p.getUniqueId());
                if (until != null && until > now) return;
                herd.touchUntil.put(p.getUniqueId(), now + TOUCH_CD);
            }
            hit(p, TOUCH, "cause_g164");
            knock(p, toward(p), 0.3, 0.1);
            readyAt = age + cd(TOUCH_CD * 2);
            if (every(2)) sound("beat", Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 40);
        }
    }

    // ------------------------------------------------------------------ 왜가리

    private void hphase(HPhase p) {
        hph = p;
        hAt = age;
    }

    private void heronTick() {
        unpinIfDone();
        boolean free = herd == null || herd.phase == Phase.DONE && now() - herd.doneAt >= FREE_AFTER || !anyBeater(herd);
        switch (hph) {
            case WAIT -> {
                Player p = nearest(free ? 400 : HERON_REACH);
                if (free && p != null && dist(p) > HERON_REACH - 1) {
                    // 물가를 떠나 사람 쪽으로 천천히 걸어 옴
                    seek(chest(p).setY(pos.getY()), WADE * speed() / baseSpeed);
                    face(chest(p));
                    if (target != null) mark(null);
                } else if (free && p == null) {
                    follow(WADE);
                } else {
                    // 외발로 서서 물(앞 땅)을 내려다봄
                    vel.copy(spot.clone().subtract(pos).setY(0).multiply(0.1));
                    if (vel.length() > WADE) vel.normalize().multiply(WADE);
                }
                if (p != null && age >= readyAt && dist(p) <= HERON_REACH && standing(p) && !pinnedRecently(p)) {
                    prey = p;
                    mark(p);
                    hold();
                    face(chest(p));
                    state("cock");
                    sound("cock", Sound.ENTITY_PHANTOM_FLAP, 0.6f, 20);
                    hphase(HPhase.COCK);
                }
            }
            case COCK -> {
                if (!standing(prey)) {
                    calm();
                    return;
                }
                hold();
                face(chest(prey));
                if (age - hAt >= cd(WINDUP)) {
                    lungeDir.copy(toward(prey));
                    state("strike");
                    hphase(HPhase.STRIKE);
                }
            }
            case STRIKE -> {
                // 목을 뻗으며 몸째 앞으로 내리찍음(부리 끝이 몸 앞 BEAK)
                dash(lungeDir, LUNGE);
                if (standing(prey)) {
                    Vector tip = pos.clone().add(lungeDir.clone().multiply(BEAK));
                    Vector c = chest(prey);
                    double dx = c.getX() - tip.getX(), dz = c.getZ() - tip.getZ();
                    if (dx * dx + dz * dz <= 1.3 * 1.3) {
                        hit(prey, STAB, "cause_g164");
                        game.players.root(prey, ROOT, "beak");
                        pinned = prey;
                        pinnedUntil = now() + ROOT;
                        PINNED.put(prey.getUniqueId(), pinnedUntil);
                        sound("stab", Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.8f, 24);
                        hold();
                        readyAt = age + cd(STRIKE_CD);
                        state("");
                        hphase(HPhase.BACK);
                        return;
                    }
                }
                if (age - hAt >= LUNGE_T) {
                    hold();
                    readyAt = age + cd(STRIKE_CD);
                    state("");
                    hphase(HPhase.BACK);
                }
            }
            case BACK -> {
                // 물가로 되돌아감(풀린 뒤에는 그 자리가 새 물가)
                if (free) spot.copy(pos);
                Vector to = spot.clone().subtract(pos).setY(0);
                double d = to.length();
                if (d < 0.3 || age - hAt > 80) {
                    hold();
                    mark(null);
                    prey = null;
                    hphase(HPhase.WAIT);
                } else seek(spot.clone().setY(pos.getY()), Math.min(d, WADE * 2));
            }
        }
    }

    /** 이 사람이 방금(풀린 지 PIN_GRACE 틱 안) 어느 왜가리에게 꽂혔나. */
    private boolean pinnedRecently(Player p) {
        Long until = PINNED.get(p.getUniqueId());
        if (until == null) return false;
        long now = now();
        if (until + PIN_GRACE <= now || until - now > ROOT * 4) {
            PINNED.remove(p.getUniqueId());
            return false;
        }
        return true;
    }

    /** 띠에 산 몰이꾼이 남았나(띠 박자는 산 몰이꾼만 돌리므로 목록에 죽은 것이 남아 있을 수 있음). */
    private static boolean anyBeater(Herd h) {
        for (G164 m : h.members) if (!m.dead) return true;
        return false;
    }

    private void calm() {
        mark(null);
        prey = null;
        state("");
        hphase(HPhase.BACK);
    }

    private void unpinIfDone() {
        if (pinned != null && now() >= pinnedUntil) pinned = null;
    }

    /** 이 왜가리에게 꽂힌 사람을 풂(묶임을 모두 지움: 묶임만 따로 푸는 API 가 없음). */
    private void unpin() {
        Player p = pinned;
        pinned = null;
        if (p != null && p.isOnline() && now() < pinnedUntil) game.players.unroot(p, "beak");
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    @Override
    public List<double[]> spheres() {
        if (!heron) return super.spheres();
        List<double[]> out = new ArrayList<>(2);
        out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), 0.6});
        double r = Math.toRadians(yaw);
        double fx = -Math.sin(r), fz = Math.cos(r);
        double up, fw;
        if ("cock".equals(state)) {
            up = 0.98;
            fw = -0.1;
        } else if ("strike".equals(state)) {
            up = -0.3;
            fw = 1.5;
        } else {
            up = 1.3;
            fw = 0.75;
        }
        out.add(new double[] {pos.getX() + fx * fw, pos.getY() + up, pos.getZ() + fz * fw, 0.4});
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (!heron) return super.damage(d);
        // 목이 접힐 때 x2, 맞으면 꽂힌 사람이 풀림(꽂힌 사람 자신이 쏜 것은 빼고)
        Damage use = hph == HPhase.COCK ? d.times(2) : d;
        if (pinned != null && d.by() != pinned) unpin();
        return super.damage(use);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        if (heron) unpin();
        super.die(d);
    }

    @Override
    public void remove() {
        if (heron) unpin();
        super.remove();
    }

    /** 시험용. */
    public String info() {
        if (heron) return "heron " + hph;
        if (herd == null || loose) return "loose";
        return "herd " + herd.phase + " n=" + herd.members.size() + " slot=" + slot + " herons=" + herd.herons.size();
    }

    @Override
    public String toString() {
        return super.toString() + " " + info();
    }
}
