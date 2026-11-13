package kr.gave.mob.sp;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import io.papermc.paper.datacomponent.DataComponentTypes;
import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.Detachable;
import kr.gave.player.PState;
import kr.gave.world.Plaza;
import net.kyori.adventure.key.Key;

/**
 * G21 노크하는 게이브(함정, 미리 깔림). 전장은 처마 없는 황야라(docs/모듈/맵변경_황야.md) 하늘에서 내려온 듯 끝이 안 보이게 위로 길게 뻗은
 * 엔드 막대기 둘에 거꾸로 매달린다: 중앙 원 테두리 근처(가운데에서 10~14블록) 허공, 머리 높이 3~4블록에 서너 마리가 주렁주렁. 막대기 아래 마디를
 * 팔꿈치처럼 꺾어 허공을 '똑, 똑' 두드린다(1초에 한 번, 이 종 전용 소리).
 *
 * <p>아래(수평 3.5블록 안)로 사람이 지나가면 그 사람을 찍고(실눈이 뜨임, 얼굴이 내려다봄) 박자가 빨라지다가 1.5초 뒤 막대기를 타고 미끄러져 내려와
 * 등에 올라탄다. 막대기는 그때 하늘로 빨려 올라가고, 떨어진 뒤 제자리 밑으로 기어 돌아오면 다시 내려와 타고 오른다.
 *
 * <p>등에 탄 동안: 1초마다 그 사람의 시점을 하늘로 꺾어 올리고(pitch -20, -75 까지) 초당 2 피해. 본인이 두 발 쏘면(하늘을 향한 채 아무 데나)
 * 떨어지고, 동료가 등의 이것을 쏘면(사람 몸이 탄을 막지 않음) 떨어진다. 동료 돕기(좌클릭)로도 떼어지고 비옷은 올라타는 것을 한 번 막는다.
 * 떨어지면 1.5초 바닥에서 떨다가 기어서 제자리로 돌아가 다시 매달린다. 내려온 자리에서 3블록 안에 사람이 없으면 바닥에 내려 4초까지 기어 쫓는다.
 *
 * <p>디스플레이 둘: 닻 gave:gabe/g21(shrink 0.5, 상태 "", "fast", "drop", "ride", "crawl": 얼굴과 막대기 아래 2.6블록)과 부품
 * gave:gabe/g21_sky(1블록 막대기 둘을 세로로 64배 늘인 것, 끝은 멀어서 안 보임). 매달린 동안은 몸을 눕히지 않는다(pitch 0, 판 규칙 돌아보기에서
 * 빠짐): 긴 막대기가 함께 기울기 때문. 내려다보는 고개는 모델 상태가 그린다.
 *
 * <p>팩 계약(tools/gabe/sp/g21.py): 위 모델과 상태, 소리 knock(한 번 '똑', 모델 노크 자세가 시작하는 틱에 맞춰 울림), slide, ride,
 * 원인 글귀 cause_g21. 본인 화면에는 이 몸을 숨기고(시점이 꺾이는 것으로 느낌), 남들에게는 등에 붙은 몸이 보인다.
 */
public final class G21 extends Gabe implements Detachable {

    // ------------------------------------------------------------------ 수치(도감)

    /** 매달리는 고리(가운데에서 수평 블록)와 다른 노크하는 게이브와 떨어지는 거리. */
    static final double RING_IN = 10, RING_OUT = 14, APART = 3.0;
    /** 매달린 몸 중심 높이(바닥 위, 개체마다 0.25씩 셋). */
    static final double HANG_UP = 3.2;
    /** 모델 막대기가 끝나는 높이(몸 중심 위, g21.py ROD_TOP), 하늘 막대기 길이, 거둘 때 올리는 높이. */
    static final float SKY_FROM = 2.6f, SKY_LEN = 64f, SKY_AWAY = 90f;
    /** 알아채는 수평 거리, 놓아주는 거리, 빨라진 박자로 기다리는 틱. */
    static final double NOTICE_R = 3.5, LOSE_R = 6.0;
    static final int ALERT = 30;
    /** 노크 자세 주기(틱, g21.py 상태 "" 와 "fast"): 느릴 땐 한 바퀴에 0, 5틱째 두 번, 빠를 땐 4틱마다. */
    static final int KNOCK_P = 20, KNOCK_FAST_P = 8;
    /** 막대기를 타고 내려오는 빠르기, 내려오는 끝 높이(바닥 위). */
    static final double SLIDE_V = 0.22, ROPE_END = 1.85;
    /** 뛰어 붙는 거리, 틱. 기어 쫓는 빠르기, 최대 틱. */
    static final double LEAP_R = 3.0, CRAWL_V = 0.2, CRAWL_LEAP_R = 1.7;
    static final int LEAP = 6, CHASE_MAX = 80;
    /** 등에 탄 동안: 1초마다 피해, 시점 꺾기(도), 이 위로는 안 꺾음, 본인 탄 몇 발이면 떨어짐. */
    static final double RIDE_DMG = 3;
    static final float PITCH_KICK = 20f, PITCH_TOP = -75f;
    static final int SHOTS_OFF = 2;
    /** 떨어져 떠는 틱, 기어 돌아가는 빠르기, 막대기가 내려오길 기다리는 틱, 타고 오르는 빠르기. */
    static final int FALLEN = 30, REACH = 10;
    static final double HOME_V = 0.16, CLIMB_V = 0.12;
    /** 떨어진 뒤 다시 덮치기까지(틱). */
    static final int COOLDOWN = 100;
    static final double R = 0.36;

    enum Phase { HANG, ALERT, DROP, LEAP, CHASE, RIDE, FALLEN, HOME, CLIMB }

    private Phase phase = Phase.HANG;
    private int phaseAt, readyAt;
    private final Vector home = new Vector();
    private Player prey, rider;
    private int shotBase;
    private final Vector leapFrom = new Vector();
    private Body.Part sky;
    private boolean skyDown = true;

    public G21(Game game, Species sp) {
        super(game, sp);
        radius = R;
        flying = true; // 높이는 이 종이 직접(허공, 막대기, 등, 바닥)
        noPush = true;
        baseSpeed = CRAWL_V;
    }

    @Override
    protected void build() {
        // 컬링 상자: 얼굴에서 모델 막대기 끝(위 2.6)까지
        body = Body.of(this, "gabe/" + sp.key(), 1.6f, 5.6f);
        // 노크 자세와 소리를 맞추려고 위상을 5틱 단위로(주기 20, 8 모두 틱이 딱 떨어짐)
        body.look.phase = 5 * (id % 4);
        sky = body.attach("gabe/g21_sky");
        if (sky != null) {
            sky.pose(skyMatrix(0), 0);
            // 닻 자리(몸 중심 아래 2.8)에서 하늘 막대기 끝까지 덮는 컬링 상자
            sky.d.setDisplayWidth(1.6f);
            sky.d.setDisplayHeight(2.8f + SKY_FROM + SKY_LEN + 2f);
        }
    }

    /** 하늘 막대기 자세(몸 기준): 모델 막대기 끝에서 위로 SKY_LEN, up 만큼 더 올림. */
    private static Matrix4f skyMatrix(float up) {
        return new Matrix4f().translate(0, SKY_FROM + up, 0).scale(1, SKY_LEN, 1);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) {
            // 우리 안: 그 자리에 매달린 꼴로만(막대기는 천장까지만)
            home.copy(pos);
            if (sky != null) sky.pose(new Matrix4f().translate(0, SKY_FROM, 0).scale(1, 1.5f, 1), 0);
            return;
        }
        // 놓인 자리의 방향은 살리고 중앙 원 테두리 고리(10~14블록) 허공으로 옮긴다. 다른 노크하는 게이브 자리와 겹치면 고리를 따라 돈다
        double cx = Plaza.CX + 0.5, cz = Plaza.CZ + 0.5;
        double dx = pos.getX() - cx, dz = pos.getZ() - cz;
        double r = Math.hypot(dx, dz);
        double ang = r < 0.5 ? game.random.nextDouble() * Math.PI * 2 : Math.atan2(dz, dx);
        double rr = r >= RING_IN && r <= RING_OUT ? r : RING_IN + game.random.nextDouble() * (RING_OUT - RING_IN);
        double x = cx, z = cz;
        for (int tries = 0; tries < 24; tries++) {
            x = cx + Math.cos(ang) * rr;
            z = cz + Math.sin(ang) * rr;
            if (!taken(x, z)) break;
            ang += 2.399963; // 황금각: 몇 번 돌아도 고르게 퍼짐
        }
        home.setX(x).setY(game.mobs.flow.floor(x, z) + HANG_UP + (id % 3) * 0.25).setZ(z);
        pos.copy(home);
        // 원 안쪽을 보고 매달림
        yaw = (float) Math.toDegrees(Math.atan2(-(cx - x), cz - z));
        pitch = 0;
        noRules = true;
        state("");
    }

    /** 다른 노크하는 게이브의 제자리가 이 자리에서 APART 블록 안인가(자리를 비운 개체도 제자리로 센다). */
    private boolean taken(double x, double z) {
        for (Gabe g : game.mobs.all()) {
            if (g == this || g.dead || !(g instanceof G21 o)) continue;
            double dx = o.home.getX() - x, dz = o.home.getZ() - z;
            if (dx * dx + dz * dz < APART * APART) return true;
        }
        return false;
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
        // 막대기에 매달린 동안은 판 규칙(돌아보기, 물어뜯기)에서 빠진다: 몸이 눕거나 멈추면 긴 막대기가 어긋난다
        noRules = p == Phase.HANG || p == Phase.ALERT || p == Phase.DROP || p == Phase.CLIMB;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    // ------------------------------------------------------------------ 하늘 막대기

    /** 막대기를 놓음: 하늘로 빨려 올라감. */
    private void skyUp() {
        if (sky == null || !skyDown) return;
        skyDown = false;
        // 이 뒤로 몸이 눕고 돌아도 올라가는 막대기는 곧게: 몸 방향을 따르지 않게 하고 지금 방향만 싣는다
        sky.follow = false;
        sky.pose(new Matrix4f().rotateY((float) Math.toRadians(-yaw)).mul(skyMatrix(SKY_AWAY)), 10);
    }

    /** 막대기가 다시 내려옴(ticks 틱에 걸쳐). */
    private void skyDown(int ticks) {
        if (sky == null || skyDown) return;
        skyDown = true;
        sky.follow = true;
        sky.pose(skyMatrix(0), ticks);
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        switch (phase) {
            case HANG -> hang();
            case ALERT -> alert();
            case DROP -> drop();
            case LEAP -> leap();
            case CHASE -> chase();
            case RIDE -> ride();
            case FALLEN -> fallen();
            case HOME -> goHome();
            case CLIMB -> climb();
        }
    }

    private void stay(Vector at) {
        vel.copy(at.clone().subtract(pos));
    }

    /** 수평으로만 그 사람 쪽을 봄(매달린 동안 몸은 눕히지 않는다). */
    private void turn(Player p) {
        Location l = p.getLocation();
        double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ();
        if (dx * dx + dz * dz > 0.01) yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        pitch = 0;
    }

    /** 매달려 느리게 노크. 아래로 사람이 지나가면 알아챔. */
    private void hang() {
        state("");
        stay(home);
        pitch = 0;
        knock(false);
        Player p = age >= readyAt && every(3) ? below() : null;
        if (p != null) {
            prey = p;
            mark(p);
            turn(p);
            phase(Phase.ALERT);
        }
    }

    /** 박자가 빨라짐(내려오기 전 1.5초). 사람이 멀어지면 다시 느리게. */
    private void alert() {
        state("fast");
        stay(home);
        knock(true);
        if (!standing(prey) || flat(prey, home) > LOSE_R) {
            calm();
            return;
        }
        turn(prey);
        if (inPhase() >= ALERT) {
            sound("slide", Sound.BLOCK_HONEY_BLOCK_SLIDE, 1.3f, 16);
            phase(Phase.DROP);
        }
    }

    /** 막대기를 타고 미끄러져 내려옴(끝 = 사람 머리 높이). */
    private void drop() {
        state("drop");
        double bottom = game.mobs.flow.floor(home.getX(), home.getZ()) + ROPE_END;
        Vector want = home.clone().setY(Math.max(bottom, pos.getY() - SLIDE_V));
        stay(want);
        if (standing(prey)) turn(prey);
        if (pos.getY() <= bottom + 0.05) {
            if (standing(prey) && prey.getLocation().toVector().distance(pos) <= LEAP_R + 1.2) startLeap();
            else {
                // 내려와 기어 쫓음
                skyUp();
                state("crawl");
                phase(Phase.CHASE);
            }
        }
    }

    private void startLeap() {
        skyUp();
        leapFrom.copy(pos);
        state("ride");
        phase(Phase.LEAP);
    }

    /** 등으로 뛰어 붙음(6틱 직선). 닿으면 올라탐. */
    private void leap() {
        if (!standing(prey)) {
            fall();
            return;
        }
        Vector to = backPoint(prey);
        double u = Math.min(1.0, (inPhase() + 1.0) / LEAP);
        Vector want = leapFrom.clone().multiply(1 - u).add(to.clone().multiply(u));
        want.setY(want.getY() + Math.sin(u * Math.PI) * 0.5);
        stay(want);
        face(chest(prey));
        if (u >= 1.0) {
            if (!mount(prey)) fall();
        }
    }

    /** 바닥에서 기어 쫓음(4초까지), 가까우면 뛰어 붙음. */
    private void chase() {
        state("crawl");
        pitch = 0;
        if (!standing(prey) || inPhase() > CHASE_MAX) {
            mark(null);
            prey = null;
            phase(Phase.HOME);
            return;
        }
        Vector to = toward(prey).multiply(CRAWL_V * speed() / baseSpeed);
        double fl = floor();
        vel.copy(to).setY(fl + 0.35 - pos.getY());
        face(chest(prey));
        if (prey.getLocation().toVector().distance(pos) <= CRAWL_LEAP_R) startLeap();
    }

    /** 사람 등 자리(몸통 뒤, 어깨 높이). */
    private static Vector backPoint(Player p) {
        Location l = p.getLocation();
        double r = Math.toRadians(l.getYaw());
        Vector fwd = new Vector(-Math.sin(r), 0, Math.cos(r));
        return l.toVector().add(new Vector(0, 1.3, 0)).subtract(fwd.multiply(0.42));
    }

    private static final ItemStack NOTHING = nothing();

    private static ItemStack nothing() {
        ItemStack it = ItemStack.of(Material.PAPER);
        it.setData(DataComponentTypes.ITEM_MODEL, Key.key(Props.NS, Body.EMPTY));
        return it;
    }

    /**
     * 올라탐: 머리 부착(남들 눈에 머리 칸은 비움, 동료 탄이 사람 몸을 지나 이것을 맞힘, 돕기로 떼어짐). 이미 머리에 뭔가 붙었거나 붙잡혔으면 실패,
     * 비옷이 한 번 막는다.
     */
    private boolean mount(Player p) {
        PState s = game.players.get(p);
        if (!s.standing() || s.headBy != null || s.grabbedBy != null) return false;
        if (game.players.coat(p, s)) return false;
        game.players.attachHead(p, NOTHING, this);
        if (s.headBy != this) return false;
        rider = p;
        mark(p);
        Guns.Arms a = game.guns.arms(p);
        shotBase = a == null ? 0 : a.shots;
        body.hide(p, true);
        state("ride");
        sound("ride", Sound.ENTITY_SLIME_ATTACK, 0.9f, 16);
        lookUp(p);
        phase(Phase.RIDE);
        return true;
    }

    /** 등에 탄 동안: 등을 따라가며 1초마다 시점을 하늘로 꺾고 2 피해. 본인이 두 발 쏘면 떨어짐. */
    private void ride() {
        Player p = rider;
        if (p == null || !p.isOnline() || !standing(p) || game.players.get(p).headBy != this) {
            fall();
            return;
        }
        stay(backPoint(p));
        yaw = p.getLocation().getYaw() + 180f;
        pitch = -10f;
        Guns.Arms a = game.guns.arms(p);
        if (a != null && a.shots - shotBase >= SHOTS_OFF) {
            fall();
            return;
        }
        int t = inPhase();
        if (t > 0 && t % 20 == 0) {
            hit(p, RIDE_DMG, "cause_g21");
            if (rider != null) lookUp(p);
        }
    }

    private void lookUp(Player p) {
        float pitchNow = p.getLocation().getPitch();
        if (pitchNow <= PITCH_TOP) return;
        Guns.kick(p, 0f, -Math.min(PITCH_KICK, pitchNow - PITCH_TOP));
    }

    /** 사람에게서 손을 뗌(숨김, 머리 부착). 두 번 불러도 됨. */
    private void letGo() {
        Player r = rider;
        rider = null;
        if (r == null) return;
        if (body != null) body.hide(r, false);
        PState s = game.players.get(r.getUniqueId());
        if (s != null && s.headBy == this) game.players.attachHead(r, null);
    }

    /** 떨어짐(등에서, 뛰다 놓침): 바닥에 떨어져 떪. */
    private void fall() {
        letGo();
        skyUp();
        mark(null);
        prey = null;
        state("crawl");
        pitch = 0;
        body.squash(1.2f, 0.6f, 1.2f, 3);
        readyAt = age + cd(COOLDOWN);
        phase(Phase.FALLEN);
    }

    private void fallen() {
        double fl = floor();
        vel.setX(0).setZ(0).setY(Math.max(-0.5, fl + 0.3 - pos.getY()));
        if (inPhase() >= FALLEN) {
            body.squash(1f, 1f, 1f, 4);
            phase(Phase.HOME);
        }
    }

    /** 기어서 제자리 밑으로. */
    private void goHome() {
        state("crawl");
        pitch = 0;
        Vector d = home.clone().subtract(pos).setY(0);
        double l = d.length();
        double fl = floor();
        if (l < 0.25) {
            // 막대기가 하늘에서 다시 내려오고, 닿으면 타고 오른다
            hold();
            pitch = 0;
            skyDown(REACH);
            phase(Phase.CLIMB);
            return;
        }
        vel.copy(d.multiply(Math.min(l, HOME_V * speed() / baseSpeed) / l)).setY(fl + 0.35 - pos.getY());
        face(home.clone().setY(pos.getY()));
        // 지나는 길에 사람이 가까우면 다시 덮침
        Player p = age >= readyAt ? nearest(CRAWL_LEAP_R) : null;
        if (p != null && sees(p) && game.players.get(p).headBy == null) {
            prey = p;
            mark(p);
            startLeap();
        }
    }

    /** 내려온 막대기를 잡고 다시 제자리로 오름. */
    private void climb() {
        pitch = 0;
        if (inPhase() <= REACH) {
            // 막대기가 닿을 때까지 바닥에서 기다림
            state("crawl");
            vel.setX(home.getX() - pos.getX()).setZ(home.getZ() - pos.getZ()).setY(floor() + 0.35 - pos.getY());
            if (inPhase() == REACH) sound("slide", Sound.BLOCK_HONEY_BLOCK_SLIDE, 1.3f, 16);
            return;
        }
        state("drop");
        Vector want = home.clone().setY(Math.min(home.getY(), pos.getY() + CLIMB_V));
        stay(want);
        if (pos.getY() >= home.getY() - 0.03) phase(Phase.HANG);
    }

    private void calm() {
        mark(null);
        prey = null;
        phase(Phase.HANG);
    }

    /**
     * 노크 소리: 모델의 노크 자세가 시작하는 틱에 '똑' 한 번. 셰이더는 (월드 틱 + 위상) 을 주기로 나눈 자리로 자세를 고르고
     * (Look.encodeSlot 의 위상 칸), 느린 상태는 자세 0, 2(0, 5틱째), 빠른 상태는 자세 0, 2(0, 4틱째)에서 막대기가 꺾인다.
     */
    private void knock(boolean fast) {
        int p = fast ? KNOCK_FAST_P : KNOCK_P;
        int off = Math.floorMod(body.look.phase, p) * 16 / p * p / 16;
        int u = (int) Math.floorMod(game.worldTime() + off, (long) p);
        if (fast ? u % 4 == 0 : u == 0 || u == 5) sound("knock", Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 1.8f, 20);
    }

    /** 바로 아래(수평 NOTICE_R 안, 이 몸보다 낮은) 서 있는 사람(블록에 안 가린). */
    private Player below() {
        Player best = null;
        double bd = NOTICE_R;
        for (Player p : game.mobs.standing()) {
            double d = flat(p, home);
            if (d > bd || p.getLocation().getY() > home.getY()) continue;
            if (game.players.get(p).headBy != null || !sees(p)) continue;
            bd = d;
            best = p;
        }
        return best;
    }

    private static double flat(Player p, Vector at) {
        Location l = p.getLocation();
        return Math.hypot(l.getX() - at.getX(), l.getZ() - at.getZ());
    }

    /** 딸랑이, 거미줄: 바닥을 기는 동안만 끌린다(매달렸거나 등에 탔으면 무시: 허공으로 끌려 나오지 않게). */
    @Override
    public void lure(Vector at, int ticks) {
        if (phase == Phase.CHASE || phase == Phase.HOME) super.lure(at, ticks);
    }

    // ------------------------------------------------------------------ 떼어짐, 맞음

    @Override
    public void detached(Player victim, Player helper, String how) {
        if (victim != null && victim == rider && !dead) fall();
    }

    @Override
    public boolean damage(Damage d) {
        boolean r = super.damage(d);
        // 등에 탄 것을 동료가 쏘면 떨어짐
        if (!dead && phase == Phase.RIDE && d.by() != null && d.by() != rider) fall();
        return r;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        // 매달린 채 죽으면 시체가 바닥에 놓이게, 하늘 막대기는 치운다(시체에 64블록 막대기가 남지 않게). 시체는 기는 꼴(짧은 막대기)
        if (sky != null) {
            sky.remove();
            sky = null;
        }
        state("crawl");
        double fl = floor();
        if (pos.getY() - fl > 0.6) pos.setY(fl + 0.3);
        super.die(d);
        letGo();
    }

    @Override
    public void remove() {
        super.remove();
        letGo();
    }

    @Override
    public List<double[]> spheres() {
        return List.of(new double[] {pos.getX(), pos.getY(), pos.getZ(), R});
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + (rider != null ? " on=" + rider.getName() : prey != null ? " prey=" + prey.getName() : "")
                + (skyDown ? "" : " sky=up");
    }
}
