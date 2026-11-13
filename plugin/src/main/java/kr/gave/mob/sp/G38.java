package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.mob.Band;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.player.DeathBorn;
import kr.gave.player.Life;
import kr.gave.player.PState;

/**
 * G38 업은 게이브(정예, 방해). 사람 죽음의 결과로만 생긴다(Players.rollDeathResult → {@link #bornFrom}).
 *
 * <p>도감: 한 번의 죽음이 이 결과로 정해지면 근처 게이브 하나가 와서 그 사람 머리를 업고 이 종으로 바뀐다. 가오리 게이브의 두 막대기 사이에
 * 방금 죽은 사람의 스킨 머리를 거미줄로 동여매 업고 그 위에 이름표. 무리 뒤를 맴돌며 생존자 근처로 왔다 물러나기를 되풀이하고(박치기 3 피해),
 * 업힌 머리는 쉬지 않고 두리번거리다 가까운 사람을 본다. 맞을 때마다 그 머리가 '흐흑' 운다. 잡으면 업힌 사람이 곧바로 상점 앞에서 살아나고
 * (엔진 Mobs.onDeath → Players.freeFrom), 웨이브 끝까지 살아남으면 그 사람은 다음 웨이브를 쉰다(그래서 noReward 를 켜지 않는다).
 *
 * <p>구현: Players 가 죽은 자리에 이 개체를 놓고 bornFrom 을 부르면, 24블록 안의 평범한 게이브 하나를 골라 그 개체를 치우고 이 개체가 그 자리에서
 * 시작해 죽은 자리로 와서(FETCH) 머리를 업는다(LIFT). 가까운 게이브가 없으면 죽은 자리에서 바로 업는다. 체력은 그때 웨이브 배율을 곱한다
 * (감독이 내지 않는 종이라 아무도 곱해 주지 않음). 주인 없이 놓인 개체(시험 소환, 오래 살아 갈라진 조각, 대기실 우리)는 바랜 게이브 얼굴을 업는다.
 *
 * <p>팩 계약(tools/gabe/sp/g38.py): 모델 {@code gave:gabe/g38} 상태 {@code ""}, {@code "charge"}, 부위 {@code "load"}(머리를 업음),
 * 부품 {@code gave:gabe/g38_head}(업힌 머리 자리, 사람 머리 아이템으로 바꿔 실음). 소리 hurt(흐흑), whimper(32), lift(24), bump(16).
 * 글귀 {@code cause_g38}, {@code notice_g38_carry}.
 */
public final class G38 extends Gabe implements DeathBorn {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.16, HOVER = 1.0, FACE_R = 0.42;
    /** 맴도는 거리(가장 가까운 사람에게서), 맴도는 틱(최소, 덤). */
    static final double RING = 15;
    static final int LURK_MIN = 120, LURK_EXTRA = 80;
    /** 다가와서 박치기: 준비 거리, 준비 틱, 돌진 빠르기와 틱, 표적 너머 거리. */
    static final double WINDUP_RANGE = 6.5, CHARGE_V = 0.55, OVERSHOOT = 1.2;
    static final int WINDUP = 16, CHARGE_MAX = 26, APPROACH_MAX = 200, RETREAT_MAX = 110;
    static final double DAMAGE = 4, PUSH = 0.5, PUSH_UP = 0.2;
    /** 머리를 가지러 오는 빠르기 배율, 업는 틱. */
    static final double FETCH_MUL = 1.5;
    static final int LIFT = 16, FETCH_MAX = 240;
    /** 업힌 머리 가운데(몸 기준: x 왼쪽, y 위, z 앞). 팩 g38.py HEAD_AT 과 같게. */
    static final float HEAD_X = 0f, HEAD_Y = 0.5f, HEAD_Z = -0.6f;
    /** 사람 머리 아이템은 디스플레이 자리 아래 반 블록에 그려지므로(머리 꼭대기 가운데가 자리) 그만큼 올린다. */
    static final float SKULL_UP = 0.25f;
    static final double HEAD_R = 0.3;
    /** 이 게이브로 바뀔 수 있는 가까운 게이브를 찾는 거리, 그 개체 최대 체력 한도. */
    static final double SWAP_R = 24, SWAP_MAX_HP = 400;

    enum Phase { FETCH, LIFT, LURK, APPROACH, WINDUP, CHARGE, RETREAT }

    private Phase phase = Phase.LURK;
    private int phaseAt, lurkUntil, nextWhimper, nextLook;
    private boolean born, loaded;
    private Player prey;
    private final Vector corpse = new Vector(), dashDir = new Vector(0, 0, 1);
    private double dashGone, dashLen;
    private int side = 1;
    /** 업힌 사람(이름표, 머리). 주인이 없으면 null. */
    private String carriedName;
    private ItemStack skull;
    private Body.Part head;
    private G26Tag tag;
    private float lookYaw, lookNod;

    public G38(Game game, Species sp) {
        super(game, sp);
        radius = FACE_R;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        // 얼굴 0.8 + 뒤 위로 벌린 막대기(끝이 옆 1.2, 위 1.0)와 업힌 머리
        body = Body.of(this, "gabe/g38", 3.2f, 2.6f);
    }

    @Override
    protected void spawned() {
        side = game.random.nextBoolean() ? 1 : -1;
        nextWhimper = age + 40 + game.random.nextInt(80);
        lurkUntil = age + cd(LURK_MIN) + game.random.nextInt(cd(LURK_EXTRA));
        if (game.director.cageSpawning()) {
            // 대기실 우리: 사람 쪽 일 없이 바랜 얼굴을 업은 모습만
            born = true;
            load(false);
        }
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    // ------------------------------------------------------------------ 죽음에서 태어남

    @Override
    public void bornFrom(Player dead, PState state, Location at) {
        born = true;
        corpse.setX(at.getX()).setZ(at.getZ()).setY(game.mobs.flow.floor(at.getX(), at.getZ()) + HOVER);
        boolean likeness = state == null || !state.noLikeness;
        if (likeness && dead != null) {
            carriedName = dead.getName();
            ItemStack it = ItemStack.of(Material.PLAYER_HEAD);
            try {
                it.editMeta(SkullMeta.class, m -> m.setPlayerProfile(dead.getPlayerProfile()));
                skull = it;
            } catch (RuntimeException e) {
                skull = null;
            }
        }
        // 웨이브 체력 배율(감독이 내지 않는 종)
        double k = game.director.hpMul(sp);
        if (k > 0) {
            maxHp = sp.hp * k;
            hp = maxHp;
        }
        // 가까운 평범한 게이브 하나가 와서 이 종으로 바뀐다: 그 개체를 치우고 그 자리에서 시작
        Gabe o = game.mobs.nearest(corpse, SWAP_R, this::convertible);
        if (o != null && o.pos.clone().setY(corpse.getY()).distanceSquared(corpse) > 2.5 * 2.5) {
            Vector from = o.pos.clone();
            float fy = o.yaw;
            o.noReward = true;
            o.reward = 0;
            o.leaveCorpse = false;
            o.remove();
            pos.setX(from.getX()).setZ(from.getZ()).setY(game.mobs.flow.floor(from.getX(), from.getZ()) + HOVER);
            yaw = fy;
            phase(Phase.FETCH);
        } else {
            pos.copy(corpse);
            phase(Phase.LIFT);
            sound("lift", Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.7f, 24);
        }
    }

    /** 이 종으로 바뀔 수 있는 게이브: 고리에서 걸어온 낱개 종, 사람을 잡고 있지 않은 것. */
    private boolean convertible(Gabe x) {
        if (x == this || x.dead || x.removed || x.disguised || x.untargetable || x.noRules || x.noReward || x.body == null) return false;
        if (x.sp.spawn != Species.Spawn.RING || x.sp.is(Role.SWARM) || x.sp.is(Role.ELITE) || x.sp.is(Role.BONUS)) return false;
        if (x.sp.band == Band.SKY || x.flying || Body.EMPTY.equals(x.body.model) || x.maxHp > SWAP_MAX_HP) return false;
        if (game.director.inCage(x) || game.director.lettered(x) || !game.players.trappedIn(x).isEmpty()) return false;
        for (PState s : game.players.all()) if (s.grabbedBy == x || s.headBy == x || s.deathMob == x) return false;
        return true;
    }

    /**
     * 주인 없이 놓인 개체의 첫 틱. 시험 훅 '업힘 이름 G38' 은 죽은 사람의 업은 개체를 시험 더미로 두므로, 그런 사람이 있으면 넘겨받아
     * (Players.transferTrapped) 진짜 흐름처럼 bornFrom 을 탄다. 판에서는 죽음 결과로 생긴 개체가 놓이자마자 bornFrom 을 받으므로 여기 오지 않는다.
     */
    private void firstTick() {
        born = true;
        for (PState s : game.players.all()) {
            if (s.life != Life.DEAD || s.deathResult != Species.G38 || s.deathMob == null || s.deathMob == this || s.deathMob.dead) continue;
            if (s.deathMob instanceof G38 || noReward) continue;
            Player p = Bukkit.getPlayer(s.id);
            if (p == null || s.deathMob.pos.distanceSquared(pos) > 40 * 40) continue;
            Gabe dummy = s.deathMob;
            Location at = dummy.loc();
            game.players.transferTrapped(dummy, this);
            dummy.noReward = true;
            dummy.remove();
            bornFrom(p, s, at);
            return;
        }
        load(false);
    }

    /** 머리를 업는다(거미줄 바구니, 업힌 머리 부품, 이름표). announce 면 모두에게 알림. */
    private void load(boolean announce) {
        if (loaded) return;
        loaded = true;
        body.look.on.add("load");
        head = body.attach("gabe/g38_head");
        if (head != null) {
            // 사람 얼굴을 못 쓰면 이목구비가 바랜 게이브 얼굴
            head.look.palette = Look.PAL_PALE;
            head.pose(headPose(0, 0), 0);
            keepSkull();
        }
        if (carriedName != null) {
            tag = new G26Tag(this, HEAD_Y + 0.62f);
            tag.setNames(List.of(carriedName));
        }
        if (announce) {
            sound("whimper", Sound.ENTITY_GHAST_AMBIENT, 1.6f, 32);
            game.hud.notice("notice_g38_carry", sp, 60);
        }
    }

    private Matrix4f headPose(float relYaw, float nod) {
        return new Matrix4f().translate(HEAD_X, HEAD_Y + (skull != null ? SKULL_UP : 0f), HEAD_Z).rotateY((float) Math.toRadians(-relYaw))
                .rotateX((float) Math.toRadians(nod));
    }

    /** 엔진이 부품 아이템을 다시 실으면(맞은 번쩍임 등) 사람 머리로 되돌림. */
    private void keepSkull() {
        if (head == null || skull == null || head.d == null || !head.d.isValid()) return;
        if (head.d.getItemStack().getType() != Material.PLAYER_HEAD) head.d.setItemStack(skull);
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!born) firstTick();
        if (prey != null && !standing(prey)) prey = null;
        switch (phase) {
            case FETCH -> fetch();
            case LIFT -> lift();
            case LURK -> lurk();
            case APPROACH -> approach();
            case WINDUP -> windup();
            case CHARGE -> charge();
            case RETREAT -> retreat();
        }
        if (loaded) headTick();
    }

    /** 죽은 자리로 곧장 옴(아직 머리 없음). */
    private void fetch() {
        Vector d = corpse.clone().subtract(pos).setY(0);
        double l = d.length();
        if (l <= 0.6 || inPhase() >= FETCH_MAX) {
            hold();
            phase(Phase.LIFT);
            sound("lift", Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.7f, 24);
            return;
        }
        double v = Math.min(speed() * FETCH_MUL, l);
        vel.copy(d.multiply(v / l));
        face(pos.clone().add(vel));
        pitch = 0;
    }

    /** 몸을 숙여 머리를 들어 올려 동여맨다. */
    private void lift() {
        hold();
        int t = inPhase();
        if (t == 1) {
            body.offset.set(0, -0.32f, 0);
            pitch = 28;
            body.pose(6);
        }
        if (t == 8) {
            load(true);
            body.offset.set(0, 0, 0);
            pitch = 0;
            body.pose(6);
        }
        if (t >= LIFT) {
            lurkUntil = age + 60 + game.random.nextInt(cd(LURK_EXTRA));
            phase(Phase.RETREAT);
        }
    }

    /** 무리 뒤를 맴돎: 가장 가까운 사람에게서 RING 블록을 지키며 옆으로 돈다. 얼굴은 사람을 보고 업힌 머리는 등 뒤. */
    private void lurk() {
        Player p = nearest(400); // 게이브는 72~84블록에서 나오므로 반경을 넉넉히(전에는 멀면 사람을 못 찾아 외곽에서 멈춤, 2026-11-11)
        if (p == null) {
            hold();
            return;
        }
        Vector away = toward(p).multiply(-1);
        double d = flat(p), v = speed();
        double radial = Math.max(-1, Math.min(1, (RING - d) * 0.25));
        Vector tang = new Vector(-away.getZ(), 0, away.getX()).multiply(side * 0.7 * v);
        vel.copy(steer(away.multiply(radial * v).add(tang)));
        face(chest(p));
        pitch = 0;
        if (age >= lurkUntil) {
            prey = p;
            phase(Phase.APPROACH);
        }
    }

    /** 생존자 근처로 다가옴. 보이는 거리 안에 들면 박치기 준비. */
    private void approach() {
        Player p = prey != null ? prey : nearest(96);
        if (p == null || inPhase() >= APPROACH_MAX) {
            toRetreat();
            return;
        }
        prey = p;
        follow(speed());
        face(chest(p));
        pitch = 0;
        if (dist(p) <= WINDUP_RANGE && sees(p)) {
            mark(p);
            hold();
            phase(Phase.WINDUP);
        }
    }

    private void windup() {
        if (prey == null || !sees(prey)) {
            toRetreat();
            return;
        }
        face(chest(prey));
        pitch = 0;
        vel.copy(toward(prey).multiply(-0.02));
        int t = inPhase();
        body.look.rate = 1.0 + Math.floor(4.0 * t / cd(WINDUP)) * 0.25;
        if (t >= cd(WINDUP)) {
            dashDir.copy(toward(prey));
            dashLen = dist(prey) + OVERSHOOT;
            dashGone = 0;
            body.look.rate = 1.0;
            state("charge");
            phase(Phase.CHARGE);
        }
    }

    /** 일직선 박치기. 닿은 사람을 박고 물러난다. */
    private void charge() {
        double v = CHARGE_V * speed() / baseSpeed;
        dash(dashDir, v);
        face(pos.clone().add(dashDir));
        pitch = 0;
        dashGone += v;
        for (Player p : game.mobs.standing()) {
            if (!touches(p, 0.1)) continue;
            hit(p, DAMAGE, "cause_g38");
            knock(p, dashDir, PUSH, PUSH_UP);
            sound("bump", Sound.ENTITY_SLIME_ATTACK, 1.3f, 16);
            body.squash(1.3f, 0.75f, 0.8f, 2);
            toRetreat();
            return;
        }
        if (dashGone >= dashLen || inPhase() >= CHARGE_MAX || wallAhead(0.7)) toRetreat();
    }

    private void toRetreat() {
        mark(null);
        prey = null;
        body.look.rate = 1.0;
        state("");
        lurkUntil = age + cd(LURK_MIN) + game.random.nextInt(cd(LURK_EXTRA));
        phase(Phase.RETREAT);
    }

    /** 물러남: 가장 가까운 사람에게서 RING 블록까지. */
    private void retreat() {
        if (inPhase() == 3) body.squash(1f, 1f, 1f, 4);
        Player p = nearest(96);
        if (p == null || flat(p) >= RING - 1 || inPhase() >= RETREAT_MAX) {
            side = game.random.nextBoolean() ? 1 : -1;
            phase(Phase.LURK);
            return;
        }
        Vector away = toward(p).multiply(-1);
        // 등 뒤가 막혔으면(한가운데 보급 상자 카운터 등) 옆으로 꺾어 돌아 나감: 그냥 밀면 그 칸에 막혀 제자리에 선다
        vel.copy(steer(away.multiply(speed() * 1.25)));
        // 물러나면서도 얼굴은 사람 쪽(뒷걸음), 업힌 머리가 등 뒤에서 앞장섬
        face(chest(p));
        pitch = 0;
    }

    /**
     * 가려는 수평 속도 v 앞(0.9블록)이 막혔으면 side 쪽부터 35도씩(최대 140도) 꺾은 첫 열린 방향으로, 꺾은 쪽을 side 로 기억한다.
     * 다 막혔으면 그대로(엔진이 막음).
     */
    private Vector steer(Vector v) {
        double l = Math.hypot(v.getX(), v.getZ());
        if (l < 1e-6 || flying || ghost) return v;
        double fl = floor();
        for (int i = 0; i <= 8; i++) {
            int sg = i == 0 ? 0 : (i % 2 == 1 ? side : -side);
            double a = Math.toRadians(35.0 * ((i + 1) / 2)) * sg, c = Math.cos(a), sn = Math.sin(a);
            double dx = (v.getX() * c - v.getZ() * sn) / l, dz = (v.getX() * sn + v.getZ() * c) / l;
            double x = pos.getX() + dx * 0.9, z = pos.getZ() + dz * 0.9;
            if (!game.mobs.flow.walkable(x, z) || game.mobs.flow.floor(x, z) - fl > kr.gave.mob.Flow.STEP) continue;
            if (sg != 0) side = sg;
            return new Vector(dx * l, v.getY(), dz * l);
        }
        return v;
    }

    /** 업힌 머리: 쉬지 않고 두리번거리다 가까운 사람을 본다. 흐느낌. */
    private void headTick() {
        keepSkull();
        if (head != null && age >= nextLook) {
            nextLook = age + 8 + game.random.nextInt(8);
            Player n = nearest(14);
            if (n != null && game.random.nextInt(4) != 0) {
                Vector hp = headPos();
                Vector d = n.getEyeLocation().toVector().subtract(hp);
                float world = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
                lookYaw = wrap(world - yaw);
                lookNod = (float) Math.max(-25, Math.min(25, Math.toDegrees(-Math.atan2(d.getY(), Math.hypot(d.getX(), d.getZ())))));
            } else {
                lookYaw = wrap(180f + (game.random.nextFloat() * 2 - 1) * 110f);
                lookNod = (game.random.nextFloat() * 2 - 1) * 14f;
            }
            head.pose(headPose(lookYaw, lookNod), 6);
        }
        if (age >= nextWhimper) {
            nextWhimper = age + 130 + game.random.nextInt(70);
            sound("whimper", Sound.ENTITY_GHAST_AMBIENT, 1.6f, 32);
        }
    }

    // ------------------------------------------------------------------ 기하, 맞음, 죽음

    private double flat(Player p) {
        Location l = p.getLocation();
        return Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
    }

    private static float wrap(float d) {
        d %= 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    /** 업힌 머리 가운데(세계 좌표). */
    private Vector headPos() {
        double r = Math.toRadians(yaw);
        double fx = -Math.sin(r), fz = Math.cos(r), lx = Math.cos(r), lz = Math.sin(r);
        return new Vector(pos.getX() + lx * HEAD_X + fx * HEAD_Z, pos.getY() + HEAD_Y, pos.getZ() + lz * HEAD_X + fz * HEAD_Z);
    }

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(2);
        out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), FACE_R});
        if (loaded) {
            Vector h = headPos();
            out.add(new double[] {h.getX(), h.getY(), h.getZ(), HEAD_R});
        }
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        boolean r = super.damage(d);
        // 맞으면 머리가 움찔 고개를 돌림(우는 소리는 엔진이 hurt 사건으로)
        if (r && !dead && head != null && d.type() != DamageType.BITE) {
            lookNod = -18f;
            head.pose(headPose(lookYaw + (game.random.nextBoolean() ? 25f : -25f), lookNod), 2);
            nextLook = age + 10;
        }
        return r;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        clearCarried();
        super.die(d);
    }

    @Override
    public void remove() {
        clearCarried();
        super.remove();
    }

    /** 이름표와 업힌 머리를 치움(잡히면 그 사람은 상점 앞에서 살아나므로 시체에 머리를 남기지 않는다). */
    private void clearCarried() {
        if (tag != null) tag.remove();
        tag = null;
        if (head != null) {
            head.remove();
            head = null;
        }
        if (body != null) body.look.on.remove("load");
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + (loaded ? " load" : "") + (carriedName != null ? " of=" + carriedName : "") + (skull != null ? " skull" : "");
    }
}
