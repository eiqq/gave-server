package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.world.Plaza;

/**
 * G128 솟는 목 게이브(3막 27라운드, 원거리, 정예, 12블록, 미리 깔림). 웨이브가 열리면 땅속에 숨은 채(막대기 끝 넷만 덤불처럼, 안 맞음) 놓였다가
 * 20~40초 뒤 사람 무게 중심에서 12~18블록 떨어진 R 18~30 자리 땅이 3초 불룩거린 뒤 2초에 걸쳐 12블록으로 솟고, 긴 나팔을 분다(2.5초, 반경 128,
 * 화면 흔들림). 그 뒤 꼭대기 얼굴을 돌려 4초(박자)마다 한 사람을 겨눈다: 분홍 조준선이 가슴에 1.6초 붙어 따라다니다 0.4초 전에 굳고(삐 소리가 끊김),
 * 굳은 자리로 얼굴 포를 쏜다(0.3초 직선 비행, 반경 1.5 안 38, 반경 3 안 12). 12초마다 땅속으로 쑥 들어갔다가(2초) 사람이 가장 빽빽한 곳 발밑이
 * 3초 불룩거린 뒤 다시 솟는다(그 위에 있던 사람 15 + 띄움). 기둥 3블록 안에 온 사람은 목을 숙여 들이받아 20 + 6블록 밀림.
 *
 * <p>대처: 삐 소리가 끊기는 순간 옆으로 두 걸음. 마디 테 셋(체력 각 150 x 감독 체력 배율)을 부수면 그 마디가 꺾여 얼굴이 내려오고 3초 동안 포를 못
 * 쏜다. 셋 다 부수면 얼굴이 바닥에 처박혀(더는 쏘지도 들어가지도 못함) 피해 x2. 땅이 불룩거리면 그 자리에서 벗어난다.
 *
 * <p>몸: {@link Body#carrier} + 부품(목 마디 g128_seg 셋(broken), 꼭대기 g128("" aim horn), 숨었을 때 g128_tips). 조준선(g128_aim)과 포탄(g128_shell)은
 * 연출 디스플레이. Gabe.pos = 목 뿌리(땅 위 0.3), 몸 yaw = 얼굴이 겨누는 쪽. 사체 없음.
 */
public final class G128 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SEG = 4, TOP = 12, BUTT_R = 3, RISE_R = 2.2;
    /** 틱: 처음 숨어 기다림(감독이 낸 것만: 아래 끝 + 무작위), 불룩, 솟기, 나팔, 겨눔 간격(박자, 아래 끝), 따라다님, 굳음, 포탄 비행, 솟아 있는 틱, 들어감,
     * 꺾여 못 쏨, 들이받기 재사용. */
    static final int WAIT_MIN = 400, WAIT_RND = 400, RUMBLE = 60, RISE = 40, HORN = 50, AIM_EVERY = 80, AIM_MIN = 50, TRACK = 32, LOCK = 8, FLY = 6,
            UP_T = 240, SINK = 40, STUN = 60, BUTT_CD = 40;
    /** 피해: 포(가운데, 둘레), 솟음, 들이받기. 마디 테 체력. */
    static final double SHOT_DMG = 38, SHOT_EDGE = 12, RISE_DMG = 15, BUTT_DMG = 20, RING_HP = 150;

    enum Phase { BURIED, RUMBLE, RISE, HORN, UP, SINK, DOWN, DEATH }

    private Phase phase = Phase.BURIED;
    private int phaseAt, waitT = 60;
    private double rise; // 0 = 땅속, 1 = 다 솟음
    private final double[] ringHp = new double[3];
    private boolean init, firstRise = true, hornDone;
    private Body.Part head, tips;
    private final Body.Part[] segs = new Body.Part[3];
    private final Vector next = new Vector();
    private ItemDisplay rumbleFx, aimFx;
    /** 겨눔: 대상, 시작 틱, 굳은 자리. */
    private Player aimP;
    private long aimAt = -1, nextAim, stunUntil, upAt;
    private final Vector lockAt = new Vector();
    private boolean locked;
    private long shellAt = -1;
    private final Vector shellTo = new Vector();
    private final Map<UUID, Long> butted = new HashMap<>();
    private Damage last;
    private List<double[]> sph;
    private int sphAge = -1;

    public G128(Game game, Species sp) {
        super(game, sp);
        radius = 1.2;
        baseSpeed = 0.1;
        flying = true;
        ghost = true;
        noPush = true;
        noBite = true;
        noMutate = true;
        noRules = true;
        big = true;
        leaveCorpse = false;
    }

    @Override
    protected void build() {
        body = Body.carrier(this);
        body.viewRange(3.5f);
        for (int i = 0; i < 3; i++) segs[i] = body.attach("gabe/g128_seg");
        head = body.attach("gabe/g128");
        tips = body.attach("gabe/g128_tips");
        G148Sky.bright(segs);
        G148Sky.bright(head);
        poseAll(0);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        untargetable = true;
        pos.setY(floor() + 0.3);
        Player p = nearest(400);
        if (p != null) mark(p);
    }

    @Override
    public void lure(Vector at, int ticks) {
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    private int broken() {
        int n = 0;
        for (double r : ringHp) if (r <= 0) n++;
        return n;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!init) {
            init = true;
            for (int i = 0; i < 3; i++) ringHp[i] = RING_HP * G148Sky.hpK(this);
            // 감독이 낸 것은 20~40초 숨어 기다림(fromDirector 는 spawned 뒤에 서므로 첫 틱에 봄), 시험 소환은 3초
            waitT = game.director.fromDirector(this) ? WAIT_MIN + game.random.nextInt(WAIT_RND) : 60;
        }
        vel.zero();
        pitch = 0;
        vel.setY(Math.max(-1, Math.min(1, floor() + 0.3 - pos.getY())));
        if (target == null || !G148Sky.up(game, target)) {
            Player p = nearest(400);
            if (p != null) mark(p);
        }
        switch (phase) {
            case BURIED -> buried();
            case RUMBLE -> rumble();
            case RISE -> rise();
            case HORN -> horn();
            case UP -> up();
            case SINK -> sink();
            case DOWN -> down();
            case DEATH -> death();
        }
    }

    private void buried() {
        untargetable = true;
        rise = 0;
        if (inPhase() >= waitT) {
            pickSpot(firstRise);
            startRumble();
        }
    }

    /** 다음에 솟을 자리: 처음엔 사람 무게 중심에서 12~18블록, 가운데에서 R 18~30. 그 뒤로는 사람이 가장 빽빽한 곳 발밑. */
    private void pickSpot(boolean first) {
        Vector c = G148Sky.centroid(game);
        if (c == null) c = G148Sky.center();
        if (first) {
            Vector mid = G148Sky.center();
            for (int tries = 0; tries < 24; tries++) {
                double a = game.random.nextDouble() * Math.PI * 2, d = 12 + game.random.nextDouble() * 6;
                double x = c.getX() + Math.cos(a) * d, z = c.getZ() + Math.sin(a) * d;
                double r = Math.hypot(x - mid.getX(), z - mid.getZ());
                if (r >= 18 && r <= 30 && game.mobs.flow.walkable((int) Math.floor(x), (int) Math.floor(z))) {
                    next.setX(x).setZ(z);
                    return;
                }
            }
            next.setX(c.getX() + 15).setZ(c.getZ());
            return;
        }
        Player p = G148Sky.densest(game, 5, pos);
        if (p != null) next.setX(p.getLocation().getX()).setZ(p.getLocation().getZ());
        else next.setX(c.getX()).setZ(c.getZ());
    }

    private void startRumble() {
        // 땅속에서 다음 자리로 옮김(안 보이는 동안)
        pos.setX(next.getX()).setZ(next.getZ()).setY(floor() + 0.3);
        if (body != null) body.warp();
        if (tips == null) tips = body.attach("gabe/g128_tips");
        poseAll(0);
        sound("rumble", Sound.BLOCK_GRAVEL_BREAK, 0.6f, 64);
        Location at = new Location(game.world, pos.getX(), floor(), pos.getZ());
        G148Sky.quake(game, at, 3f);
        G148Sky.kill(rumbleFx);
        rumbleFx = game.shotFx.spawn(at.clone().add(0, 1.2, 0), "fx_quad", ShotFx.SMOKE, 1, ShotFx.scale(2f), Display.Billboard.CENTER, false, RUMBLE,
                ShotFx.KEY);
        game.shotFx.grow(rumbleFx, ShotFx.scale(5f), RUMBLE - 4);
        game.shotFx.drift(rumbleFx, at.clone().add(0, 4.5, 0), RUMBLE - 4);
        phase(Phase.RUMBLE);
    }

    private void rumble() {
        untargetable = true;
        if (every(15)) {
            sound("rumble", Sound.BLOCK_GRAVEL_BREAK, 0.6f, 64);
            game.horizon.shake(0.15, 8);
        }
        if (inPhase() >= RUMBLE) {
            untargetable = false;
            if (tips != null) {
                tips.remove();
                tips = null;
            }
            // 솟는 자리 위에 있던 사람: 15 + 띄움
            double fl = floor();
            for (Player p : G148Sky.standing(game)) {
                Location l = p.getLocation();
                if (l.getY() < fl + 3 && G148Sky.flat(pos, l) <= RISE_R) {
                    Vector out = l.toVector().subtract(pos).setY(0);
                    if (out.lengthSquared() < 1e-4) out = new Vector(1, 0, 0);
                    if (!game.players.launch(p, 4, out.normalize().multiply(0.6), RISE_DMG * 0.3, this, "cause_g128_rise")) knock(p, out, 1.0, 0.8);
                    hit(p, RISE_DMG, "cause_g128_rise");
                }
            }
            sound("rumble", Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 64);
            G148Sky.quake(game, new Location(game.world, pos.getX(), fl, pos.getZ()), 3.5f);
            phase(Phase.RISE);
        }
    }

    private void rise() {
        rise = Math.min(1, inPhase() / (double) RISE);
        if (every(2)) poseAll(2);
        if (inPhase() >= RISE) {
            rise = 1;
            poseAll(2);
            if (!hornDone) {
                hornDone = true;
                head.state("horn");
                sound("horn", Sound.EVENT_RAID_HORN, 0.6f, 128);
                game.horizon.shake(0.5, 20);
                phase(Phase.HORN);
            } else {
                startUp();
            }
            firstRise = false;
        }
    }

    private void horn() {
        if (inPhase() >= HORN) startUp();
    }

    private void startUp() {
        if (head != null) head.state("");
        upAt = now();
        nextAim = now() + 15;
        phase(Phase.UP);
    }

    private void up() {
        long now = now();
        butt();
        if (aimAt < 0 && now >= nextAim && now >= stunUntil) startAim();
        if (aimAt >= 0) aimTick();
        shellTick();
        if (now - upAt >= UP_T && aimAt < 0 && shellAt < 0) {
            sound("rumble", Sound.BLOCK_GRAVEL_BREAK, 0.6f, 64);
            phase(Phase.SINK);
        }
    }

    private void sink() {
        rise = Math.max(0, 1 - inPhase() / (double) SINK);
        if (every(2)) poseAll(2);
        if (inPhase() >= SINK) {
            untargetable = true;
            pickSpot(false);
            startRumble();
        }
    }

    /** 마디 셋이 다 꺾여 얼굴이 바닥에 처박힘: 들이받기만, 피해 x2. */
    private void down() {
        butt();
        shellTick();
    }

    // ------------------------------------------------------------------ 겨눔, 포

    private void startAim() {
        List<Player> ps = new ArrayList<>(game.mobs.standing());
        ps.removeIf(p -> G148Sky.flat(pos, p.getLocation()) > 56);
        if (ps.isEmpty()) {
            nextAim = now() + 20;
            return;
        }
        aimP = ps.get(game.random.nextInt(ps.size()));
        aimAt = now();
        locked = false;
        mark(aimP);
        if (head != null) head.state("aim");
    }

    /** 포구(깔때기 끝, 세계). */
    private Vector muzzle() {
        double top = topHeight();
        Vector f = G148Sky.local(0, 0, 2.9 + headForward(), yaw);
        return new Vector(pos.getX() + f.getX(), pos.getY() + top - 3.2, pos.getZ() + f.getZ());
    }

    private void aimTick() {
        long t = now() - aimAt;
        if (!locked && (aimP == null || !G148Sky.up(game, aimP))) {
            endAim();
            return;
        }
        if (!locked) {
            Location l = aimP.getLocation();
            lockAt.setX(l.getX()).setY(l.getY() + 1.0).setZ(l.getZ());
            turnTo(new Vector(l.getX(), pos.getY(), l.getZ()), 6f);
            if (every(2)) body.pose(2);
            if (t % 6 == 0) sound("beep", Sound.BLOCK_NOTE_BLOCK_PLING, (float) (0.8 + t / (double) TRACK), 48);
            if (t >= TRACK) locked = true;
        }
        drawAim();
        if (t >= TRACK + LOCK) {
            fire();
            endAim();
        }
    }

    private void drawAim() {
        Vector m = muzzle();
        Vector d = lockAt.clone().subtract(m);
        double len = d.length();
        if (len < 0.5) return;
        Quaternionf q = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), new Vector3f((float) d.getX(), (float) d.getY(), (float) d.getZ()).normalize());
        Matrix4f mx = new Matrix4f().rotate(q).scale(1.6f, 1.6f, (float) len).rotateY((float) Math.PI);
        Location at = new Location(game.world, m.getX(), m.getY(), m.getZ());
        if (aimFx == null || !aimFx.isValid()) {
            aimFx = G148Sky.fx(game, at, "gabe/g128_aim", "", mx, TRACK + LOCK + 6);
            if (aimFx != null) aimFx.setBrightness(new Display.Brightness(15, 15));
        } else if (!locked || now() - aimAt == TRACK) {
            aimFx.setInterpolationDelay(0);
            aimFx.setInterpolationDuration(2);
            aimFx.setTransformationMatrix(mx);
        }
    }

    private void endAim() {
        aimAt = -1;
        aimP = null;
        locked = false;
        G148Sky.kill(aimFx);
        aimFx = null;
        if (head != null) head.state("");
        nextAim = now() + Math.max(AIM_MIN, cd(AIM_EVERY));
    }

    private void fire() {
        Vector m = muzzle();
        shellTo.copy(lockAt);
        shellAt = now() + FLY;
        sound("fire", Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 96);
        ItemDisplay d = G148Sky.fx(game, new Location(game.world, m.getX(), m.getY(), m.getZ()), "gabe/g128_shell", "",
                G148Sky.lay("gabe/g128_shell", yaw, 1.4f, 1.4f, 1.4f), FLY + 2);
        game.shotFx.drift(d, new Location(game.world, shellTo.getX(), shellTo.getY(), shellTo.getZ()), FLY);
    }

    private void shellTick() {
        if (shellAt < 0 || now() < shellAt) return;
        shellAt = -1;
        for (Player p : G148Sky.standing(game)) {
            Location l = p.getLocation();
            double d = new Vector(l.getX(), l.getY() + 0.9, l.getZ()).distance(shellTo);
            if (d <= 1.5) hit(p, SHOT_DMG, "cause_g128");
            else if (d <= 3) hit(p, SHOT_EDGE, "cause_g128");
        }
        Location at = new Location(game.world, shellTo.getX(), shellTo.getY(), shellTo.getZ());
        game.shotFx.blast(at, 3f);
    }

    /** 기둥 3블록 안 사람을 목을 숙여 들이받음(같은 사람 2초에 한 번). */
    private void butt() {
        long now = now();
        double fl = floor();
        for (Player p : G148Sky.standing(game)) {
            Location l = p.getLocation();
            if (l.getY() > fl + 4 || G148Sky.flat(pos, l) > BUTT_R) continue;
            Long t = butted.get(p.getUniqueId());
            if (t != null && now - t < cd(BUTT_CD)) continue;
            butted.put(p.getUniqueId(), now);
            Vector out = l.toVector().subtract(pos).setY(0);
            if (out.lengthSquared() < 1e-4) out = new Vector(1, 0, 0);
            hit(p, BUTT_DMG, "cause_g128_butt");
            knock(p, out, 1.6, 0.45);
            sound("snap", Sound.ENTITY_RAVAGER_ATTACK, 0.6f, 48);
        }
    }

    // ------------------------------------------------------------------ 몸

    /** 마디마다 꺾임 각(부서지면 80 도, 번갈아 앞뒤). */
    private double bend(int i) {
        return ringHp[i] <= 0 && init ? (i % 2 == 0 ? 80 : -80) : 0;
    }

    /** 마디 끝 자리(뿌리 기준, 몸 축: y 위, z 앞)와 꼭대기 높이. */
    private double[] chain(int upto) {
        // 다 들어가면 꼭대기 얼굴까지 땅 밑(얼굴과 깔때기 위 끝이 땅 위로 삐져나오지 않게 5블록 더)
        double y = (rise - 1) * (TOP + 5), z = 0;
        for (int i = 0; i < upto; i++) {
            double a = Math.toRadians(bend(i));
            y += SEG * Math.cos(a);
            z += SEG * Math.sin(a);
        }
        return new double[] {y, z};
    }

    private double topHeight() {
        return phase == Phase.DOWN ? 1.6 : chain(3)[0];
    }

    private double headForward() {
        return phase == Phase.DOWN ? 0 : chain(3)[1];
    }

    private void poseAll(int ticks) {
        for (int i = 0; i < 3; i++) {
            if (segs[i] == null) continue;
            double[] b = chain(i);
            segs[i].pose(new Matrix4f().translate(0, (float) b[0], (float) b[1]).rotateX((float) Math.toRadians(bend(i))), ticks);
        }
        if (head != null) {
            if (phase == Phase.DOWN) {
                head.pose(new Matrix4f().translate(0, 1.6f, 0), ticks);
            } else {
                double[] t = chain(3);
                head.pose(new Matrix4f().translate(0, (float) t[0], (float) t[1]), ticks);
            }
        }
        if (tips != null) tips.pose(new Matrix4f(), ticks);
    }

    private void death() {
        rise = Math.max(0, 1 - inPhase() / 30.0);
        if (every(2)) poseAll(2);
        if (inPhase() >= 30) {
            G148Sky.quake(game, new Location(game.world, pos.getX(), floor(), pos.getZ()), 4f);
            untargetable = false;
            die(last != null ? last : new Damage(0, DamageType.OTHER, null, pos.clone(), null, "sink", 0));
        }
    }

    // ------------------------------------------------------------------ 맞음

    /** 맞는 구: 0 얼굴, 1..3 마디 테, 4 목 줄기. */
    @Override
    public List<double[]> spheres() {
        if (sph != null && sphAge == age) return sph;
        List<double[]> out = new ArrayList<>(12);
        if (rise > 0.05) {
            Vector f = G148Sky.local(0, 0, 1, yaw);
            double th = topHeight(), hf = headForward() + 2.9;
            out.add(new double[] {pos.getX() + f.getX() * hf, pos.getY() + th - 0.6, pos.getZ() + f.getZ() * hf, 1.9, 0});
            for (int i = 0; i < 3; i++) {
                double[] b = chain(i + 1);
                double[] a0 = chain(i);
                if (ringHp[i] > 0 || !init) out.add(new double[] {pos.getX() + f.getX() * b[1], pos.getY() + b[0] - 0.2, pos.getZ() + f.getZ() * b[1], 1.6, 1 + i});
                out.add(new double[] {pos.getX() + f.getX() * (a0[1] + b[1]) / 2, pos.getY() + (a0[0] + b[0]) / 2, pos.getZ() + f.getZ() * (a0[1] + b[1]) / 2,
                        1.2, 4});
            }
        }
        sph = out;
        sphAge = age;
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable || phase == Phase.DEATH || rise <= 0.05) return false;
        int part = G148Sky.partAt(spheres(), d.at());
        Damage dd = phase == Phase.DOWN ? d.times(2) : d;
        double amt = dd.amount();
        hp -= amt;
        game.mobs.onHurt(this, dd);
        if (part >= 1 && part <= 3 && ringHp[part - 1] > 0) {
            ringHp[part - 1] -= amt;
            if (ringHp[part - 1] <= 0) {
                segs[part - 1].look.on.add("broken");
                sph = null;
                sound("snap", Sound.ENTITY_ITEM_BREAK, 0.6f, 48);
                stunUntil = now() + STUN;
                if (aimAt >= 0) endAim();
                if (broken() >= 3 && hp > 0) {
                    G148Sky.quake(game, new Location(game.world, pos.getX(), floor(), pos.getZ()), 4f);
                    game.horizon.shake(0.5, 12);
                    phase(Phase.DOWN);
                }
                poseAll(6);
            }
        }
        if (hp <= 0) {
            last = dd;
            mark(null);
            if (aimAt >= 0) endAim();
            phase(Phase.DEATH);
        }
        return true;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        G148Sky.kill(aimFx);
        G148Sky.kill(rumbleFx);
        super.die(d);
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " rise=" + Math.round(rise * 100) / 100.0 + " broken=" + broken() + " aim=" + (aimAt >= 0) + " wait=" + waitT
                + " at=" + Math.round(Plaza.radius(pos.getX(), pos.getZ()));
    }
}
