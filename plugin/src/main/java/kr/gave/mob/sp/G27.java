package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.player.Detachable;
import kr.gave.player.PState;

/**
 * G27 겹얼굴 게이브(정예, 돌격, 먹는 종).
 *
 * <p>도감: 얼굴 2~5개가 녹은 밀랍처럼 반쯤 파묻힌 혹 덩어리, 본체 얼굴 하나만 눈을 뜰 수 있다. 기본 게이브처럼 머리 높이로 오다가
 * 다른 게이브와 머리가 닿으면 0.5초 멈추고 녹여 붙인다(얼굴 +1, 체력 일부 흡수). 커질수록 느리고 덩어리째 굴러 본체 자리가 바뀐다.
 * 5블록 안에서 혹이 한쪽으로 쏠린 뒤(예고) 0.3초에 두 번 꺾는 지그재그 돌진. 머리 높이에 닿으면 붙잡아 녹여 붙인다(못 움직이고 못 쏨):
 * 본체 얼굴이 바깥으로 돌아 드러나고, 3.5초 안에 누가 본체를 맞히면 풀린다. 본인은 돕기 키 연타로 최대 1초 버틴다(풀지는 못함).
 * 못 풀면 갇히고(Players.trap) 얼굴이 하나 는다. 6개가 되면 3개짜리 둘로 갈라진다. 본체 말고는 피해 10%. 죽으면 갇힌 사람들이
 * 이름 단 실뭉치(G26)로 쏟아진다. 웃음 합창은 얼굴 수만큼 음높이를 어긋나게 겹치고, 맞으면 우는 소리(팩 hurt).
 *
 * <p>얼굴 꿰는 게이브(G12)가 다섯을 꿰고 녹아 변한 개체는 얼굴 셋으로 시작한다({@link #faces(int)}, G12 가 부르지 않아도 놓인 자리에
 * 녹는 G12 가 있으면 스스로 셋으로 맞춤).
 *
 * <p>몸: 닻 {@code gave:gabe/g27}(상태 {@code ""} 얼굴 둘, {@code "f3"}, {@code "f4"}, {@code "f5"}), 부품 {@code gave:gabe/g27_eaten}
 * (가장 최근에 먹힌 사람 머리: 플레이어 머리 아이템을 싣고, '내 얼굴 쓰지 않기'면 웃지 않는 얼굴 그림 그대로), 이름표(G26Tag).
 * 소리 chorus(24), hurt(우는 소리, 16), melt(16), lean(16). 원인 글귀 {@code cause_g27}.
 */
public final class G27 extends Gabe implements Detachable {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.14;
    /** 머리 높이(바닥에서 몸 가운데). */
    static final double HEAD_H = 1.8; // 2026-11-12 거대화(1.5 배): 촛농 막대기 끝이 바닥 위
    static final double DASH_RANGE = 5, DASH_V = 0.5;
    /** 쏠림 예고 틱, 돌진 틱, 두 번 꺾는 틱(0.3초 안), 꺾는 각, 돌진 뒤 쉬는 틱, 다시 돌진까지. */
    static final int LEAN_T = 14, DASH_T = 16, ZIG1 = 3, ZIG2 = 6, RECOVER_T = 24, COOLDOWN = 50;
    static final double ZIG_DEG = 38;
    /** 붙잡고 녹이는 틱(3.5초), 연타로 버티는 최대 틱(1초), 연타 하나당 틱. */
    static final int HOLD_T = 70, HOLD_EXTRA_MAX = 20, HOLD_PER_HIT = 2;
    /** 맞는 구: 본체, 덩어리. 본체가 아닌 곳 피해 비율. */
    static final double CORE_R = 0.54, LUMP_R = 0.93, NON_CORE = 0.1;
    /** 녹여 붙이기: 멈추는 틱, 흡수하는 체력 비율, 붙일 수 있는 상대 최대 체력. 얼굴 수 한도(넘으면 갈라짐). */
    static final int MERGE_T = 10, MAX_FACES = 5;
    static final double ABSORB = 0.5, MERGE_MAX_HP = 320;
    /** 붙잡을 때 머리 높이 차 한도. 붙잡은 사람에게서 이만큼 넘게 떨어지면(딸랑이에 끌려감 등) 놓침. */
    static final double GRAB_DY = 0.75, HOLD_LOSE = 2.6;

    enum Phase { APPROACH, MERGE, LEAN, DASH, GRAB, RECOVER }

    private Phase phase = Phase.APPROACH;
    private int phaseAt, readyAt, nextChorus;
    int faces = 2;
    private double tumble;
    private int leanSide = 1;
    private Player prey, held;
    private int holdUntil;
    private Gabe mergeWith;
    private final Vector dashDir = new Vector(0, 0, 1);
    private Body.Part eaten;
    private ItemStack eatenHead;
    private G26Tag tag;
    private int eatenRiseAt = -1, nextName;
    /** 먹힌 사람 머리 자리(본체 아래 왼쪽 표면, 조금 기울임). */
    private static final Matrix4f EATEN_AT = new Matrix4f().translate(-0.45f, -0.3f, 0.45f).rotateY(-0.5f).rotateX(0.25f);

    public G27(Game game, Species sp) {
        super(game, sp);
        radius = LUMP_R;
        hover = HEAD_H;
        baseSpeed = SPEED;
        noBite = true; // 머리가 닿으면 물어뜯지 않고 녹여 붙임(종이 직접)
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g27", 4.2f, 3.6f);
        tag = new G26Tag(this, 1.5f);
        state(stateName());
    }

    @Override
    protected void spawned() {
        nextChorus = age + game.random.nextInt(30);
        // 얼굴 꿰는 게이브가 녹아 변한 자리(G12 가 제 자리에 이 개체를 놓고 사라짐): 얼굴 셋짜리로 시작
        Gabe from = game.mobs.nearest(pos, 0.5, x -> x.sp == Species.G12);
        if (from != null) faces(3);
    }

    /** 얼굴 수를 정함(2..5). 다른 종이 이 종으로 변할 때(G12 는 3). */
    public void faces(int n) {
        faces = Math.max(2, Math.min(MAX_FACES, n));
        state(stateName());
    }

    public int faces() {
        return faces;
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    private String stateName() {
        return faces <= 2 ? "" : "f" + Math.min(MAX_FACES, faces);
    }

    @Override
    public double speed() {
        // 커질수록 느림
        return super.speed() * Math.max(0.6, 1.0 - 0.09 * (faces - 2));
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        state(stateName());
        keepEatenHead();
        chorus();
        if (age >= nextName) {
            // 갇힌 사람이 나갔으면 이름표에서 빼 줌
            nextName = age + 20;
            if (!tag.text().isEmpty()) tag.set(game.players.trappedIn(this));
        }
        switch (phase) {
            case APPROACH -> approach();
            case MERGE -> merge();
            case LEAN -> lean();
            case DASH -> dash();
            case GRAB -> grabTick();
            case RECOVER -> recover();
        }
    }

    /** 흐름장을 따라 오며 덩어리째 구름(본체 자리가 바뀜). 다른 게이브 머리가 닿으면 녹여 붙이기. 5블록 안이면 쏠림 예고. */
    private void approach() {
        follow(speed());
        double vl = Math.hypot(vel.getX(), vel.getZ());
        Player p = nearest(48);
        if (vl > 1e-6) yaw = (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ()));
        tumble = (tumble + Math.toDegrees(vl / LUMP_R)) % 360.0;
        pitch = (float) tumble;
        Gabe o = game.mobs.nearest(pos, radius + 1.4, this::mergeKind);
        if (o != null && touching(o) && !holdsPeople(o)) {
            mergeWith = o;
            game.mobs.lure(o, pos.clone(), MERGE_T + 2);
            hold();
            settle(4);
            phase(Phase.MERGE);
            return;
        }
        if (p != null && age >= readyAt && dist(p) <= DASH_RANGE && sees(p)) {
            prey = p;
            mark(p);
            hold();
            leanSide = game.random.nextBoolean() ? 1 : -1;
            face(chest(p));
            pitch = 0;
            tumble = 0;
            // 혹들이 한쪽으로 쏠림(돌진 예고)
            Vector l = left().multiply(0.28 * leanSide);
            body.offset.set((float) l.getX(), -0.08f, (float) l.getZ());
            body.roll = 24f * leanSide;
            body.squash(1.12f, 0.9f, 1f, 6);
            body.pose(6);
            sound("lean", Sound.BLOCK_HONEY_BLOCK_SLIDE, 0.7f, 16);
            phase(Phase.LEAN);
        }
    }

    /** 0.5초 멈추고 녹아 붙음. */
    private void merge() {
        hold();
        Gabe o = mergeWith;
        if (o == null || o.dead || !mergeable(o)) {
            mergeWith = null;
            phase(Phase.APPROACH);
            return;
        }
        face(o.pos);
        pitch = 0;
        body.squash(1f + 0.06f * (float) Math.sin(inPhase() * 1.3), 1f, 1f, 1);
        if (inPhase() < MERGE_T) return;
        if (o.pos.distance(pos) <= radius + o.radius + 0.8) absorb(o);
        mergeWith = null;
        body.squash(1f, 1f, 1f, 3);
        phase(Phase.APPROACH);
    }

    private void lean() {
        hold();
        if (!standing(prey)) {
            unlean();
            calm();
            return;
        }
        face(chest(prey));
        pitch = 0;
        if (inPhase() >= cd(LEAN_T)) {
            dashDir.copy(toward(prey));
            unlean();
            phase(Phase.DASH);
        }
    }

    /** 지그재그 돌진: 0.3초 안에 두 번 꺾고 마지막엔 대상 쪽으로 곧게. 머리 높이에 닿으면 붙잡음. */
    private void dash() {
        int t = inPhase();
        Vector d;
        if (t < ZIG1) d = rot(dashDir, ZIG_DEG * leanSide);
        else if (t < ZIG2) d = rot(dashDir, -ZIG_DEG * leanSide);
        else d = standing(prey) ? toward(prey) : dashDir;
        dash(d, DASH_V * speed() / baseSpeed);
        face(pos.clone().add(d));
        pitch = 0;
        noPush = true;
        for (Player q : game.mobs.standing()) {
            if (!touches(q, 0.1)) continue;
            if (Math.abs(q.getEyeLocation().getY() - pos.getY()) > GRAB_DY) continue;
            if (tryGrab(q)) return;
        }
        if (t >= DASH_T || (t > ZIG2 && wallAhead(0.7))) {
            noPush = false;
            hold();
            readyAt = age + cd(COOLDOWN);
            phase(Phase.RECOVER);
        }
    }

    private boolean tryGrab(Player q) {
        if (!grab(q, 999)) return false;
        PState s = game.players.get(q);
        s.grabDetach = false; // 동료 떼기로는 안 풀림: 본체를 쏴야 풀림
        held = q;
        prey = q;
        mark(q);
        holdUntil = age + HOLD_T;
        hold();
        noPush = true;
        tumble = 0;
        pitch = 0;
        sound("melt", Sound.BLOCK_HONEY_BLOCK_SLIDE, 0.6f, 16);
        body.squash(1.2f, 0.85f, 1.1f, 3);
        phase(Phase.GRAB);
        return true;
    }

    /** 머리에 붙어 녹임. 본체 얼굴은 바깥(사람 반대쪽)을 봄. 시간이 다 되면 갇힘, 얼굴 +1. */
    private void grabTick() {
        Player q = held;
        if (q == null || !q.isOnline() || game.players.grabbedBy(q) != this) {
            held = null;
            noPush = false;
            readyAt = age + cd(COOLDOWN);
            phase(Phase.RECOVER);
            return;
        }
        Vector eye = q.getEyeLocation().toVector();
        Vector out = pos.clone().subtract(eye).setY(0);
        if (out.lengthSquared() > HOLD_LOSE * HOLD_LOSE) {
            // 딸랑이에 끌려가는 등으로 붙잡은 머리에서 떨어짐: 놓침
            game.players.release(q);
            held = null;
            noPush = false;
            readyAt = age + cd(COOLDOWN);
            phase(Phase.RECOVER);
            return;
        }
        if (out.lengthSquared() < 1e-4) out = toward(q).multiply(-1);
        out.normalize();
        Vector want = eye.clone().add(out.clone().multiply(0.42));
        Vector d = want.subtract(pos).setY(0);
        vel.copy(d.length() > 0.5 ? d.normalize().multiply(0.5) : d);
        face(pos.clone().add(out));
        pitch = 0;
        if (inPhase() % 4 == 0) body.squash(1.15f + 0.05f * (float) Math.sin(age), 0.9f, 1.05f, 4);
        if (inPhase() % 15 == 7) sound("melt", Sound.BLOCK_HONEY_BLOCK_SLIDE, 0.6f, 16);
        int extra = Math.min(HOLD_EXTRA_MAX, game.players.get(q).escapeHits * HOLD_PER_HIT);
        if (age < holdUntil + extra) return;
        // 녹여 붙임: 갇힘, 얼굴 +1, 그 사람 머리가 덩어리 표면에
        held = null;
        noPush = false;
        game.players.trap(q, this);
        setEaten(q);
        tag.set(game.players.trappedIn(this));
        faces++;
        if (faces > MAX_FACES) split();
        state(stateName());
        body.squash(1f, 1f, 1f, 6);
        mark(null);
        readyAt = age + cd(COOLDOWN);
        phase(Phase.RECOVER);
    }

    private void recover() {
        vel.multiply(0.6);
        if (inPhase() == 2) body.squash(1f, 1f, 1f, 6);
        if (inPhase() >= cd(RECOVER_T)) calm();
    }

    private void calm() {
        if (held == null) mark(null);
        prey = null;
        noPush = false;
        phase(Phase.APPROACH);
    }

    private void unlean() {
        body.offset.set(0, 0, 0);
        body.roll = 0;
        body.squash(1f, 1f, 1f, 3);
        body.pose(3);
    }

    /** 덩어리 자세를 바로 세움(본체 앞). */
    private void settle(int ticks) {
        tumble = 0;
        pitch = 0;
        body.pose(ticks);
    }

    // ------------------------------------------------------------------ 녹여 붙이기, 갈라짐

    private boolean mergeable(Gabe x) {
        return mergeKind(x) && !holdsPeople(x);
    }

    /** 녹여 붙일 수 있는 종류인가(할당 없는 조건만: 엔진이 모든 개체에 매 틱 묻는다). */
    private boolean mergeKind(Gabe x) {
        if (x == this || x.dead || x.disguised || x.untargetable || x.noRules || x.body == null) return false;
        if (x.sp.spawn == Species.Spawn.PLACED || x.sp.spawn == Species.Spawn.DERIVED) return false;
        if (x.sp.is(Role.SWARM) || x.sp.band == kr.gave.mob.Band.SKY || Body.EMPTY.equals(x.body.model)) return false;
        return x.maxHp <= MERGE_MAX_HP && !game.director.inCage(x);
    }

    /** 사람이 걸려 있는 개체(갇힘, 죽음 결과, 붙잡힘, 머리 부착)는 붙이지 않는다. */
    private boolean holdsPeople(Gabe x) {
        for (PState s : game.players.all()) {
            if (s.eater == x || s.deathMob == x || s.grabbedBy == x || s.headBy == x) return true;
        }
        return false;
    }

    private boolean touching(Gabe o) {
        Vector a = head(), b = o.head();
        if (a == null || b == null) return false;
        double rs = radius + o.radius + 0.15;
        return Math.abs(a.getY() - b.getY()) < 0.9 && a.distanceSquared(b) <= rs * rs;
    }

    private void absorb(Gabe o) {
        int add = o instanceof G27 g ? g.faces : 1;
        double oh = Math.max(0, o.hp), om = o.maxHp;
        o.noReward = true;
        o.reward = 0;
        o.leaveCorpse = false;
        try {
            o.die(new Damage(Math.max(1, o.hp), DamageType.OTHER, null, o.pos.clone(), null, "merge", 0));
        } catch (RuntimeException e) {
            o.remove();
        }
        hp += oh * ABSORB;
        maxHp += om * ABSORB;
        faces += add;
        sound("melt", Sound.BLOCK_HONEY_BLOCK_SLIDE, 0.6f, 16);
        body.squash(1.25f, 0.85f, 1.25f, 3);
        if (faces > MAX_FACES) split();
        state(stateName());
    }

    /** 6개 넘으면 3개짜리 둘로(새 조각은 보상 0, 예산이 차면 5개로 머묾). */
    private void split() {
        int total = faces;
        Vector side = left().multiply(0.7);
        Gabe c = child(Species.G27, pos.clone().add(side));
        if (!(c instanceof G27 g)) {
            faces = MAX_FACES;
            return;
        }
        faces = 3;
        g.faces = Math.max(2, Math.min(MAX_FACES, total - 3));
        hp /= 2;
        maxHp /= 2;
        g.maxHp = maxHp;
        g.hp = hp;
        g.yaw = yaw;
        g.state(g.stateName());
        pos.subtract(side);
        Props.soundAround(loc(), "gabe.split", Sound.ENTITY_SLIME_SQUISH, 1f, 0.9f, 0.7f, 20, -1);
    }

    // ------------------------------------------------------------------ 먹힌 사람 머리

    private void setEaten(Player q) {
        PState s = game.players.get(q);
        if (eaten == null) eaten = body.attach("gabe/g27_eaten");
        // 덩어리 속에서 표면으로 천천히 떠오름(다음 틱부터 2초 보간)
        if (eaten != null) {
            eaten.pose(new Matrix4f().translate(-0.12f, -0.08f, -0.05f).scale(0.7f), 0);
            eatenRiseAt = age + 2;
        }
        eatenHead = null;
        if (s == null || !s.noLikeness) {
            ItemStack it = ItemStack.of(Material.PLAYER_HEAD);
            try {
                it.editMeta(SkullMeta.class, m -> m.setPlayerProfile(q.getPlayerProfile()));
                eatenHead = it;
            } catch (RuntimeException e) {
                eatenHead = null;
            }
        }
        if (eaten == null || eaten.d == null || !eaten.d.isValid()) return;
        // 앞사람 머리가 실려 있어도 지금 사람 것으로 바꾼다. '내 얼굴 쓰지 않기'면 팩의 웃지 않는 얼굴 그림으로 되돌림
        if (eatenHead != null) eaten.d.setItemStack(eatenHead);
        else eaten.model("gabe/g27_eaten");
    }

    /** 엔진이 맞은 번쩍임 등으로 부품 아이템을 다시 실으면(팩 모델) 그 사람 머리로 되돌림(요청: Part 아이템 덮어쓰기 API). */
    private void keepEatenHead() {
        if (eaten != null && eatenRiseAt >= 0 && age >= eatenRiseAt) {
            eatenRiseAt = -1;
            eaten.pose(EATEN_AT, 40);
        }
        if (eaten == null || eatenHead == null || eaten.d == null || !eaten.d.isValid()) return;
        if (eaten.d.getItemStack().getType() != Material.PLAYER_HEAD) eaten.d.setItemStack(eatenHead);
    }

    private void chorus() {
        if (age < nextChorus || game.director.silent()) return;
        nextChorus = age + 46 + game.random.nextInt(20);
        int n = Math.max(2, faces);
        for (int i = 0; i < n; i++) {
            float pitchV = 0.9f + 0.2f * i / (n - 1);
            Props.soundAround(loc(), "gabe.g27.chorus", Sound.ENTITY_WITCH_CELEBRATE, 0.85f, pitchV, 0.8f * pitchV, 24, -1);
        }
    }

    // ------------------------------------------------------------------ 기하

    private Vector left() {
        double r = Math.toRadians(yaw);
        return new Vector(Math.cos(r), 0, Math.sin(r));
    }

    private static Vector rot(Vector v, double deg) {
        return v.clone().rotateAroundY(Math.toRadians(deg));
    }

    /** 본체 얼굴 가운데(덩어리가 구르면 자리가 바뀜). */
    private Vector core() {
        Vector v = new Vector(0, 0, 0.48).rotateAroundZ(Math.toRadians(body.roll)).rotateAroundX(Math.toRadians(pitch))
                .rotateAroundY(Math.toRadians(-yaw));
        return pos.clone().add(new Vector(body.offset.x, body.offset.y, body.offset.z)).add(v);
    }

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(2);
        Vector c = core();
        out.add(new double[] {c.getX(), c.getY(), c.getZ(), CORE_R});
        out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), LUMP_R});
        return out;
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        boolean coreHit = false;
        double k = 1.0;
        if (t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER) {
            coreHit = d.at() != null && coreHit(d);
            k = coreHit ? 1.0 : NON_CORE;
        } else if (t == DamageType.EXPLOSION || t == DamageType.FIRE) {
            k = 0.5;
        }
        if (phase == Phase.GRAB && held != null && (coreHit || t == DamageType.EXPLOSION)) {
            // 본체를 맞힘: 붙잡은 사람을 놓고 움찔
            game.players.release(held);
            held = null;
            noPush = false;
            body.squash(0.8f, 1.2f, 0.8f, 2);
            readyAt = age + cd(COOLDOWN);
            phase(Phase.RECOVER);
        }
        double amt = d.amount() * k;
        double before = hp;
        hp -= amt;
        game.mobs.onHurt(this, d, Math.min(before, amt));
        if (hp <= 0) die(d);
        return true;
    }

    /**
     * 본체 얼굴(1.11 블록 머리, 2026-11-12 거대화)을 맞혔나. 맞은 점이 본체 가운데에서 0.99 안이거나(정면), 탄 길이 본체 가운데 0.63 안을 지나가면(옆에서 겨눠 쏜 탄:
     * 덩어리 겉에 먼저 닿아도 본체 머리를 지나감) 본체로 친다. 뒤통수 쪽에서 덩어리를 뚫고 온 탄은 아니다.
     */
    private boolean coreHit(Damage d) {
        Vector c = core(), at = d.at();
        double tx = c.getX() - at.getX(), ty = c.getY() - at.getY(), tz = c.getZ() - at.getZ();
        if (tx * tx + ty * ty + tz * tz <= (CORE_R + 0.45) * (CORE_R + 0.45)) return true;
        Vector dir = d.dir();
        if (dir == null) return false;
        double along = tx * dir.getX() + ty * dir.getY() + tz * dir.getZ();
        if (along < -0.45) return false;
        double px = tx - dir.getX() * along, py = ty - dir.getY() * along, pz = tz - dir.getZ() * along;
        if (px * px + py * py + pz * pz > 0.63 * 0.63) return false;
        // 탄이 들어온 쪽이 본체 반구인가(덩어리 가운데에서 본체 쪽으로 -0.3 블록보다 앞)
        double cx = c.getX() - pos.getX(), cy = c.getY() - pos.getY(), cz = c.getZ() - pos.getZ(), cl = Math.sqrt(cx * cx + cy * cy + cz * cz);
        if (cl < 1e-6) return true;
        double side = ((at.getX() - pos.getX()) * cx + (at.getY() - pos.getY()) * cy + (at.getZ() - pos.getZ()) * cz) / cl;
        return side >= -0.3;
    }

    @Override
    public void detached(Player victim, Player helper, String how) {
        if (victim == held) {
            held = null;
            noPush = false;
        }
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        if (held != null) {
            game.players.release(held);
            held = null;
        }
        // 갇힌 사람들은 이름 단 실뭉치로 쏟아진다(정리로 치워질 때는 엔진이 바로 부활시킴)
        if (!G25.cleanup(d)) G26.spill(this);
        if (tag != null) tag.remove();
        super.die(d);
    }

    @Override
    public void remove() {
        if (tag != null) tag.remove();
        super.remove();
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " faces=" + faces + (held != null ? " held=" + held.getName() : "");
    }
}
