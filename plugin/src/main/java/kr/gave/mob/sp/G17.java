package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.Detachable;
import kr.gave.player.PState;
import kr.gave.world.Plaza;

/**
 * G17 우는 아기 게이브(함정, 미리 깔림). 싸움판 가장자리 바닥에서 뒤척이며 흐느끼고 공격하지 않는다.
 *
 * <p>도감: 누가 다가오면 팔을 뻗어 힘겹게 기어간다. 방치하면 20초 뒤 크게 울어 그 방향에 증원을 부른다(보상 없는 소환 둘, 방향 화살표).
 * 달래면(돕기 좌클릭) 진짜는 큰 재화(15원)를 주고 녹아 사라지고, 가짜는 그 사람 어깨에 붙어 18초 동안 혹이 3단계로 돋다가 그 사람을
 * 쓰러뜨리고 작은 우는 아기 4마리(보상 0)로 흩는다. 붙은 동안 본인은 귀여운 얼굴, 동료는 혹 돋은 본모습을 본다(부품 디스플레이 둘을
 * 사람마다 숨김). 본인 화면엔 심장 소리와 가장자리 혹 덮개 3단계, 돕기 연타(4초쯤)로 스스로 떼거나 동료가 어깨를 쏴 떨군다,
 * 동료가 등 뒤에서 짜내도 됨(잠복 'g17'). 진짜는 눈물이 아래로, 가짜는 이마 쪽으로 거꾸로 흐른다. 쏘면 아기 비명, 한 방에 죽는다.
 * 듣기(웅크리기)면 가짜는 울음 밑에 어른 웃음. 웨이브가 갈수록 가짜 비율이 는다.
 *
 * <p>몸: 빈 닻 + 부품(아기). 붙으면 부품 하나 더(본모습). 팩 계약(tools/gabe/sp/g17.py): 모델 gave:gabe/g17, 상태 "" 진짜, "fake",
 * "cute", "lump1" .. "lump3", 덮개 g17_lump1 .. 3. 소리 sob, wail, laugh_low, coo, heart, latch, hurt(아기 비명), die.
 * 글귀 cause_g17, notice_g17_cry.
 */
public final class G17 extends Gabe implements Detachable {

    // ------------------------------------------------------------------ 수치(도감)

    static final double HOVER = 0.2, CRAWL = 0.035, NOTICE_R = 6;
    /** 놓이는 자리(싸움판 가장자리): 중앙에서 이 거리 사이의 황야 바닥(사람이 갈 수 있는 끝 48 안쪽). */
    static final double EDGE_IN = 24, EDGE_OUT = 40;
    /** 방치하면 우는 간격, 최대 횟수, 부르는 수. */
    static final int CRY_EVERY = 400, CRY_MAX = 3, CALL = 2;
    /** 달래면 진짜가 주는 재화, 녹는 틱. */
    static final int SOOTHE_REWARD = 15, MELT = 14;
    /** 어깨: 붙는 틱(18초), 단계 틱, 스스로 떼는 좌클릭 수(4초 연타). */
    static final int ATTACH = 360, STAGE = 120, ESCAPE = 24;
    /** 흩어지는 작은 아기 수, 크기. */
    static final int SCATTER = 4;
    static final float SMALL = 0.6f;

    enum Phase { IDLE, CRAWL, MELT, SHOULDER, FALLEN }

    /** 가짜(어깨에 붙음)면 true. */
    public boolean fake;
    /** 흩어진 작은 아기(울어 부르지 않음). */
    public boolean small;
    private Phase phase = Phase.IDLE;
    private int phaseAt, nextSob, cryAt, cries, stage, nextHeart, nextVis, nextWrithe;
    private Body.Part baby, lump;
    private Player victim;
    private boolean popping, curing, melting;

    public G17(Game game, Species sp) {
        super(game, sp);
        radius = 0.25;
        hover = HOVER;
        baseSpeed = CRAWL;
        double chance = Math.max(0.35, Math.min(0.85, 0.35 + 0.02 * game.wave));
        fake = game.random.nextDouble() < chance;
    }

    @Override
    protected void build() {
        body = Body.carrier(this);
        baby = body.attach("gabe/g17");
        baby.state(fake ? "fake" : "");
    }

    @Override
    protected void spawned() {
        nextSob = age + game.random.nextInt(40);
        cryAt = age + CRY_EVERY;
        yaw = game.random.nextFloat() * 360f;
        toEdge();
    }

    /**
     * 감독이 놓은 자리가 싸움판 가장자리(중앙에서 24~40블록)를 벗어나면 같은 방위로 그 안에 옮긴다. 흩어진 작은 아기, 부른 개체(noReward),
     * 우리 개체, 사람 코앞(10블록 안: 시험 소환)에 놓인 것은 그대로 둔다.
     */
    private void toEdge() {
        if (noReward || game.director.cageSpawning()) return;
        double dx = pos.getX() - (Plaza.CX + 0.5), dz = pos.getZ() - (Plaza.CZ + 0.5), r = Math.hypot(dx, dz);
        if (r >= EDGE_IN && r <= EDGE_OUT) return;
        if (game.mobs.nearestPlayer(pos, 10) != null) return;
        if (r < 1e-3) {
            double a = game.random.nextDouble() * Math.PI * 2;
            dx = Math.cos(a);
            dz = Math.sin(a);
            r = 1;
        }
        var flow = game.mobs.flow;
        for (int tries = 0; tries < 12; tries++) {
            double want = EDGE_IN + game.random.nextDouble() * (EDGE_OUT - EDGE_IN), a = (game.random.nextDouble() - 0.5) * 0.25 * tries;
            double ux = dx / r, uz = dz / r, c = Math.cos(a), sn = Math.sin(a);
            double x = Plaza.CX + 0.5 + (ux * c - uz * sn) * want, z = Plaza.CZ + 0.5 + (ux * sn + uz * c) * want;
            if (!flow.walkable(x, z) || game.mobs.nearestPlayer(new Vector(x, flow.floor(x, z) + 0.9, z), 10) != null) continue;
            pos.setX(x).setY(flow.floor(x, z) + HOVER).setZ(z);
            // 놓인 그 틱이라 아직 클라에 안 보냄: 닻을 바로 옮겨 처음부터 새 자리에 보이게(빈 닻이라 엔티티 자리 = 몸 중심)
            if (body != null && body.anchor != null)
                body.anchor.teleport(new Location(game.world, x, pos.getY(), z)); // 26.3 Paper 는 승객과 함께 옮김
            return;
        }
    }

    /** 흩어진 작은 가짜로 만듦(부모가 부름). */
    void makeSmall() {
        small = true;
        fake = true;
        noReward = true;
        reward = 0;
        baby.state("fake");
        body.scale(SMALL, 0);
        radius = 0.18;
    }

    /** 시험용: 진짜, 가짜를 정함. */
    public void setFake(boolean f) {
        fake = f;
        if (phase == Phase.IDLE || phase == Phase.CRAWL) baby.state(f ? "fake" : "");
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
        switch (phase) {
            case IDLE, CRAWL -> ground();
            case MELT -> melt();
            case SHOULDER -> shoulder();
            case FALLEN -> fallen();
        }
    }

    /** 바닥: 뒤척이며 흐느낌, 사람이 다가오면 팔을 뻗어 기어감, 방치하면 크게 울어 증원을 부름. */
    private void ground() {
        hover = HOVER;
        Player p = nearest(16);
        if (p != null && dist(p) <= NOTICE_R) {
            if (phase != Phase.CRAWL) phase(Phase.CRAWL);
            baby.look.rate = 2.0;
            if (dist(p) > 1.0) vel.copy(toward(p).multiply(speed()));
            else hold();
            face(chest(p));
        } else {
            if (phase != Phase.IDLE) phase(Phase.IDLE);
            baby.look.rate = 1.0;
            hold();
            if (p != null) turnTo(chest(p), 3f);
            else yaw += 0.6f;
        }
        writhe();
        sob();
        if (!small && cries < CRY_MAX && age >= cryAt) wail();
    }

    private void writhe() {
        if (age < nextWrithe) return;
        nextWrithe = age + 8;
        body.roll = (age / 8) % 2 == 0 ? 9f : -9f;
        body.pose(8);
    }

    private void sob() {
        if (age < nextSob) return;
        nextSob = age + 50 + game.random.nextInt(35);
        sound("sob", Sound.ENTITY_CAT_BEG_FOR_FOOD, 1.6f, 32);
    }

    /** 크게 울어 그 방향(광장 가운데에서 이 아기 쪽 바깥 고리)에 증원을 부름. */
    private void wail() {
        cries++;
        cryAt = age + CRY_EVERY;
        sound("wail", Sound.ENTITY_CAT_HURT, 1.8f, 64);
        body.squash(1.2f, 1.25f, 1.2f, 3);
        Vector out = pos.clone().setY(0).subtract(new Vector(Plaza.CX, 0, Plaza.CZ));
        if (out.lengthSquared() < 1e-4) out = new Vector(1, 0, 0);
        out.normalize();
        Vector at = null;
        for (double r = Plaza.R_SPAWN_IN; r >= Plaza.R_SPAWN_IN - 18 && at == null; r -= 3) {
            double x = Plaza.CX + out.getX() * r, z = Plaza.CZ + out.getZ() * r;
            if (game.mobs.flow.walkable(x, z)) at = new Vector(x, game.mobs.flow.floor(x, z), z);
        }
        Species call = callSpecies();
        if (at == null) at = game.director.ringPoint(call);
        int n = CALL + (game.wave >= 10 ? 1 : 0);
        int made = 0;
        for (int i = 0; i < n; i++) {
            Vector p = at.clone().add(new Vector((game.random.nextDouble() - 0.5) * 3, 0, (game.random.nextDouble() - 0.5) * 3));
            p.setY(game.mobs.flow.floor(p.getX(), p.getZ()) + call.band.hover);
            if (child(call, p) != null) made++;
        }
        if (made > 0) {
            game.hud.notice("notice_g17_cry", Species.G17, 60);
            game.hud.point(null, at, 80);
        }
    }

    /** 이번 웨이브 묶음에서 몰려오는 종 하나(없으면 G01). */
    private Species callSpecies() {
        List<Species> c = new ArrayList<>();
        for (Species s : game.director.waveSpecies()) if (s != Species.G17 && s.implemented() && s.spawn == Species.Spawn.RING) c.add(s);
        return c.isEmpty() ? Species.G01 : c.get(game.random.nextInt(c.size()));
    }

    /** 달랜 진짜: 녹아 사라짐. */
    private void melt() {
        hold();
        if (!melting) {
            melting = true;
            body.squash(1.4f, 0.08f, 1.4f, MELT - 2);
        }
        if (inPhase() >= MELT) remove();
    }

    // ------------------------------------------------------------------ 어깨

    private boolean attach(Player p) {
        PState s = game.players.get(p);
        if (s.latent != null) return false;
        curing = true;
        boolean lat = game.players.latent(p, "g17", ATTACH + 60, pl -> { }, this::cured);
        curing = false;
        if (!lat) return false;
        if (!game.players.grab(p, this, ESCAPE)) {
            curing = true;
            game.players.cure(p);
            curing = false;
            return false;
        }
        s.grabPin = false;    // 걸을 수는 있음(쏘지는 못함)
        s.grabDetach = false; // 동료는 쏴서 떨군다(또는 등 뒤에서 짜냄)
        victim = p;
        mark(p);
        baby.look.open = true;
        baby.state("cute");
        flying = true;
        ghost = true;
        noPush = true;
        noBite = true; // 어깨에 붙은 동안 다른 종과 머리가 닿아도 멈추지 않음(사람을 따라가야 함)
        body.roll = 0;
        body.scale(small ? SMALL : 1f, 3);
        lump = body.attach("gabe/g17");
        if (lump != null) {
            lump.state("lump1");
            lump.look.open = true;
        }
        stage = 1;
        lumpCover(p, 1);
        nextHeart = age;
        nextVis = age;
        sound("latch", Sound.ENTITY_SLIME_SQUISH, 1.2f, 16);
        game.hud.hint(p, "hint_escape", 60);
        phase(Phase.SHOULDER);
        visibility();
        return true;
    }

    /** 어깨에 붙어 따라감, 혹이 3단계로 돋음(본인: 덮개와 심장 소리), 18초면 쓰러뜨리고 흩어짐. */
    private void shoulder() {
        if (victim == null || !victim.isOnline() || !game.players.get(victim).standing()) {
            fall();
            return;
        }
        Location l = victim.getLocation();
        double yr = Math.toRadians(l.getYaw());
        Vector right = new Vector(-Math.cos(yr), 0, -Math.sin(yr));
        Vector want = new Vector(l.getX(), l.getY() + 1.5, l.getZ()).add(right.multiply(0.38));
        vel.copy(want.subtract(pos));
        face(victim.getEyeLocation().toVector());
        int t = inPhase();
        int st = Math.min(3, 1 + t / STAGE);
        if (st != stage) {
            stage = st;
            if (lump != null) lump.state("lump" + st);
            lumpCover(victim, st);
        }
        if (age >= nextHeart) {
            nextHeart = age + (st == 1 ? 24 : st == 2 ? 16 : 10);
            if (!game.director.silent()) Props.sound(victim, "gabe.g17.heart", Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 1f, 1f);
        }
        if (age >= nextVis) visibility();
        if (t >= ATTACH) pop();
    }

    /**
     * 붙은 본인 화면 가장자리 혹(단계마다 바꿈). 동료의 돕기로는 못 떼게 한다(coverDetach false): 덮개만 떼이면 등 뒤 짜내기로 넘어가지 못한다.
     */
    private void lumpCover(Player p, int st) {
        PState s = game.players.get(p);
        if (s.cover != null && s.cover.startsWith("g17_lump")) game.players.uncover(p);
        if (game.players.cover(p, "g17_lump" + st, STAGE + 5)) s.coverDetach = false;
    }

    /** 사람마다 다른 모습: 붙은 본인은 귀여운 아기, 동료는 혹 돋은 본모습. */
    private void visibility() {
        nextVis = age + 10;
        for (Player p : game.world.getPlayers()) {
            boolean me = p == victim;
            show(p, baby, me);
            show(p, lump, !me);
        }
    }

    private void show(Player p, Body.Part part, boolean visible) {
        if (part == null || part.d == null) return;
        Entity e = part.d;
        if (visible == p.canSee(e)) return;
        if (visible) p.showEntity(game.plugin, e);
        else p.hideEntity(game.plugin, e);
    }

    /** 18초: 그 사람을 쓰러뜨리고 작은 우는 아기 넷으로 흩어짐. */
    private void pop() {
        popping = true;
        Player p = victim;
        Vector at = pos.clone();
        unhook(p);
        game.players.release(p);
        game.players.down(p, this, "cause_g17");
        for (int i = 0; i < SCATTER; i++) {
            double a = Math.PI * 2 * i / SCATTER + game.random.nextDouble() * 0.5;
            Vector q = at.clone().add(new Vector(Math.cos(a) * 1.2, 0, Math.sin(a) * 1.2));
            q.setY(game.mobs.flow.floor(q.getX(), q.getZ()) + HOVER);
            Gabe c = child(Species.G17, q);
            if (c instanceof G17 b) b.makeSmall();
        }
        if (!game.director.silent())
            Props.soundAround(loc(), "gabe.split", Sound.ENTITY_SLIME_SQUISH, 1f, 1f, 0.7f, 20, -1);
        leaveCorpse = false;
        remove();
    }

    /** 잠복과 덮개, 보이기를 풂(붙음이 끝날 때 한 번). */
    private void unhook(Player p) {
        if (p == null) return;
        PState s = game.players.get(p.getUniqueId());
        if (s == null) return;
        if ("g17".equals(s.latent)) {
            curing = true;
            game.players.cure(p);
            curing = false;
        }
        if (s.cover != null && s.cover.startsWith("g17_lump")) game.players.uncover(p);
    }

    /** 이 사람이 아직 이 개체에 붙잡혀 있나(나간 사람의 상태를 새로 만들지 않게 UUID 로 찾음). */
    private boolean holds(Player p) {
        PState s = p == null ? null : game.players.get(p.getUniqueId());
        return s != null && s.grabbedBy == this;
    }

    /** 잠복 짜냄(동료 등 뒤 돕기, 정화): 어깨에서 떨어짐. */
    private void cured(Player p) {
        if (curing || popping || dead || victim == null) return;
        curing = true;
        game.players.release(p); // detached(.., "release") 가 떨굼
        curing = false;
        if (phase == Phase.SHOULDER) fall();
    }

    @Override
    public void detached(Player p, Player helper, String how) {
        if (popping || dead || phase != Phase.SHOULDER) return;
        fall();
    }

    /** 어깨에서 떨어짐(스스로 뗌, 짜냄, 그 사람 쓰러짐): 본모습이 드러난 채 바닥에서 기어감. */
    private void fall() {
        Player p = victim;
        victim = null;
        unhook(p);
        if (holds(p)) {
            curing = true;
            game.players.release(p);
            curing = false;
        }
        flying = false;
        ghost = false;
        noPush = false;
        noBite = false;
        mark(null);
        baby.look.open = false;
        baby.state("lump" + Math.max(1, stage));
        if (lump != null) {
            lump.remove();
            lump = null;
        }
        for (Player o : game.world.getPlayers()) show(o, baby, true);
        Vector away = p != null ? pos.clone().subtract(p.getLocation().toVector()).setY(0) : new Vector();
        if (away.lengthSquared() > 1e-4) vel.copy(away.normalize().multiply(0.3));
        phase(Phase.FALLEN);
    }

    /** 떨어진 가짜: 본모습 그대로 흐느끼며 기어감(쏘면 한 방). */
    private void fallen() {
        hover = HOVER;
        // 16블록 안의 사람만 찾으면 사람들이 멀어진 뒤 그 자리에 영영 남았다. 멀면 흐름장을 따라 온다(2026-11-12)
        Player p = nearest(400);
        if (p != null && dist(p) > 16) {
            follow(speed());
            face(chest(p));
        } else if (p != null && dist(p) > 1.0) {
            vel.copy(toward(p).multiply(speed()));
            face(chest(p));
        } else hold();
        sob();
    }

    // ------------------------------------------------------------------ 돕기, 맞음, 듣기

    @Override
    public boolean helped(Player p) {
        if (phase == Phase.MELT || phase == Phase.SHOULDER || !standing(p)) return false;
        if (!fake) {
            game.players.addMoney(p, SOOTHE_REWARD);
            sound("coo", Sound.ENTITY_CAT_PURREOW, 1.6f, 12);
            noReward = true;
            reward = 0;
            hold();
            phase(Phase.MELT);
            return true;
        }
        if (attach(p)) return true;
        // 이미 다른 잠복이 걸린 사람: 붙지 못하고 비명만
        sound("hurt", Sound.ENTITY_CAT_HURT, 1.8f, 16);
        return true;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable || phase == Phase.MELT) return false;
        DamageType t = d.type();
        if (t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER)
            d = new Damage(Math.max(d.amount(), hp + 1), t, d.by(), d.at(), d.dir(), d.how(), d.aimTicks());
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        if (victim != null) {
            Player p = victim;
            popping = true; // detached 가 떨구지 않게(죽음이 풂)
            unhook(p);
            // 시체는 본모습(혹 돋은 얼굴) 하나만: 귀여운 얼굴 부품을 본모습으로 바꾸고 따로 보이던 부품은 치움
            baby.look.open = false;
            baby.state("lump" + Math.max(1, stage));
            if (lump != null) {
                lump.remove();
                lump = null;
            }
            for (Player o : game.world.getPlayers()) show(o, baby, true);
        }
        super.die(d);
    }

    @Override
    public void remove() {
        if (victim != null && !popping) {
            Player p = victim;
            popping = true;
            unhook(p);
            if (holds(p)) game.players.release(p);
        }
        super.remove();
    }

    /** 딸랑이: 어깨에 붙었거나 녹는 중이면 끌리지 않는다(붙은 사람을 따라가야 함). */
    @Override
    public void lure(Vector at, int ticks) {
        if (phase != Phase.SHOULDER && phase != Phase.MELT) super.lure(at, ticks);
    }

    @Override
    public String listenSound() {
        if (phase == Phase.MELT) return null;
        return fake ? "gave:gabe.g17.laugh_low" : "gave:gabe.g17.sob";
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + (fake ? " fake" : " real") + (small ? " small" : "") + " stage=" + stage + " cries=" + cries
                + (victim != null ? " on=" + victim.getName() : "");
    }
}
