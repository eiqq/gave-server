package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.player.PState;
import kr.gave.world.Plaza;

/**
 * G47 문틀에 말린 게이브(방해, 함정, 미리 깔림). 2026-11-11 맵 변경(구조물 없는 황야)으로 문틀 대신 황야를 굴러다니는 회전초 꼴이다.
 *
 * <p>얼굴을 밀대로 민 듯 편 막 네 장(마디)이 돌돌 말린 공. 바람에 구르듯 천천히 떠돌다(가운데에서 16~40 블록 띠 안) 사람이 4 블록 안으로
 * 지나가면 바스락 떨다가 풀려 펄럭이며 날아가 감싼다: 2초 납작하게 눌러 못 움직이게 하고(붙잡기, 좌클릭 연타나 동료 돕기로 풀림) 5 피해.
 * 풀릴 때 마디 사이에서 새끼 가오리 둘(보상 0, 두 번까지)이 떨어진다. 맞으면 한 번만 다른 곳으로 날아가 다시 말린다.
 * 반경 10 블록 안 사람 화면에서는 근처 진짜 게이브 셋까지가 이름표 달린 동료(마네킹)로 보인다(G47Doll). 가짜는 등에 엔드 막대기 끝 둘이
 * 삐져나와 있고, 영향권 밖 사람에게는 진짜 모습 그대로 보인다. 착시가 걸린 동안 그 사람의 HUD 동료 목록이 깜빡인다. 조명탄에 비치면
 * 무늬가 드러나고(촘촘한 점묘) 10초 동안 착시가 풀린다. 마디 넷을 다 없애야 끝(마디당 3원, 남은 수는 HUD 게이지).
 *
 * <p>팩 계약(tools/gabe/sp/g47.py): 모델 gave:gabe/g47(반으로 줄여 구움) 상태 "roll", "open", "wrap" 과 드러난 "roll_lit", "open_lit",
 * "wrap_lit", 부위 gone1 .. gone4. 풀린 띠는 몸 가운데에서 앞뒤로 2.4 블록씩. 덮개 g47_wrap. 소리 rustle(20), buzz(16), wrap(16).
 * 원인 글귀 cause_g47.
 *
 * <p>도감과 다른 점: 문틀, 처마에 말려 있지 않고 굴러다닌다(맵 변경). 가짜 동료는 플레이어 패킷 대신 마네킹 엔티티. 도감 대처법의
 * '가짜 동료는 엔드 막대기가 등에 없다'는 공통 규칙(사람 흉내도 등에 막대기 끝 둘)과 어긋나 공통 규칙을 따랐다(등에 막대기 끝이 있으면 가짜).
 * 화면 일렁임(렌즈)은 종 쪽에서 걸 수 없어 뺐다.
 */
public final class G47 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final int SEGS = 4;
    static final double R = 0.42;
    /** 구르는 빠르기, 돌풍 때 빠르기(블록/틱). */
    static final double ROLL_V = 0.045, GUST_V = 0.1;
    /** 떠도는 띠(가운데에서, 블록). */
    static final double BAND_IN = 16, BAND_OUT = 40;
    static final double NOTICE_R = 4.0, LOSE_R = 9.0;
    static final int ALERT = 10, REACH_MAX = 50, WRAP_T = 40, RETURN_T = 16, COOLDOWN = 80;
    static final double REACH_V = 0.3, FLEE_V = 0.6, FLEE_ALT = 3.0;
    static final double DAMAGE = 6;
    static final int ESCAPE = 10;
    static final int SEG_REWARD = 3;
    /** 착시: 영향 반경, 가짜로 바꿀 게이브를 고르는 반경, 놓는 반경, 한 번에 몇 마리. */
    static final double AURA_R = 10, PICK_R = 14, DROP_R = 18;
    static final int DOLLS = 3;
    /** 조명탄에 드러난 틱. */
    static final int LIT_T = 200;
    static final int BABIES = 2, BABY_UNROLLS = 2;
    /** 풀린 띠: 마디 길이, 반 길이. */
    static final double SEG_L = 1.2, HALF = 2.4;

    enum Phase { ROLL, ALERT, REACH, WRAP, RETURN, FLEE }

    /** 착시 하나: 진짜 게이브와 그 자리에 보이는 인형, 인형을 보여 준(진짜 몸을 숨긴) 사람들. */
    private static final class Fake {
        final Gabe target;
        final G47Doll doll;
        final Set<UUID> shown = new HashSet<>();

        Fake(Gabe target, G47Doll doll) {
            this.target = target;
            this.doll = doll;
        }
    }

    private Phase phase = Phase.ROLL;
    private int phaseAt, readyAt, nextRustle, nextBuzz, litUntil, babyUnrolls;
    private final double[] segHp = new double[SEGS];
    private final boolean[] gone = new boolean[SEGS];
    private boolean init, fled;
    private double wind, rollDeg;
    private float wrapYaw;
    private Player prey, held;
    private final Vector fleeTo = new Vector();
    private final List<Fake> fakes = new ArrayList<>();
    private final Set<UUID> inside = new HashSet<>();

    public G47(Game game, Species sp) {
        super(game, sp);
        radius = 0.45;
        flying = true; // 높이는 이 종이 직접(구르기, 풀려 날기, 감싸기)
        baseSpeed = ROLL_V;
        state("roll");
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g47", 5.5f, 5.5f);
    }

    @Override
    protected void spawned() {
        wind = game.random.nextDouble() * Math.PI * 2;
        nextRustle = age + game.random.nextInt(40);
    }

    private void init() {
        init = true;
        double each = Math.max(1, maxHp);
        for (int i = 0; i < SEGS; i++) segHp[i] = each;
        maxHp = hp = each * SEGS;
        if (!noReward) reward = SEG_REWARD * SEGS;
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    private int alive() {
        int n = 0;
        for (boolean g : gone) if (!g) n++;
        return n;
    }

    /** 상태 이름(조명탄에 드러난 동안은 _lit). */
    private void look(String base) {
        state(age < litUntil ? base + "_lit" : base);
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!init) init();
        aura();
        switch (phase) {
            case ROLL -> roll();
            case ALERT -> alert();
            case REACH -> reach();
            case WRAP -> wrap();
            case RETURN -> goBack();
            case FLEE -> flee();
        }
    }

    private double floorHere() {
        return game.mobs.flow.floor(pos.getX(), pos.getZ());
    }

    /** 바람에 구르듯 천천히 떠돈다. 사람이 4 블록 안으로 지나가면 떨기 시작. */
    private void roll() {
        look("roll");
        noPush = false;
        body.look.rate = 1.0;
        wind += (game.random.nextDouble() - 0.5) * 0.06;
        double cx = pos.getX() - (Plaza.CX + 0.5), cz = pos.getZ() - (Plaza.CZ + 0.5), r = Math.hypot(cx, cz);
        if (r < BAND_IN || r > BAND_OUT) {
            // 띠 밖: 안이면 바깥쪽, 밖이면 가운데 쪽으로 서서히 꺾는다
            double want = Math.atan2(cz, cx) + (r > BAND_OUT ? Math.PI : 0);
            double diff = Math.atan2(Math.sin(want - wind), Math.cos(want - wind));
            wind += Math.max(-0.05, Math.min(0.05, diff));
        }
        boolean gust = (age + id * 31) % 240 < 50;
        double v = (gust ? GUST_V : ROLL_V) * speed() / baseSpeed;
        double dy = floorHere() + R - pos.getY();
        vel.setX(Math.cos(wind) * v).setY(Math.max(-0.4, Math.min(0.4, dy))).setZ(Math.sin(wind) * v);
        yaw = (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ()));
        rollDeg += Math.toDegrees(v / R);
        pitch = (float) rollDeg;
        if (age >= nextRustle) {
            sound("rustle", Sound.BLOCK_LEAF_LITTER_BREAK, 1.2f, 20);
            nextRustle = age + (gust ? 18 : 50) + game.random.nextInt(20);
        }
        if (age < readyAt || !every(2)) return;
        Player p = passer();
        if (p != null) {
            prey = p;
            mark(p);
            sound("rustle", Sound.BLOCK_LEAF_LITTER_BREAK, 1.2f, 20);
            sound("buzz", Sound.BLOCK_REDSTONE_TORCH_BURNOUT, 1.6f, 16);
            phase(Phase.ALERT);
        }
    }

    /** NOTICE_R 안(수평)을 지나가는 가장 가까운 서 있는 사람. */
    private Player passer() {
        List<Player> ps = game.mobs.standing();
        Player best = null;
        double bd = NOTICE_R * NOTICE_R;
        for (int i = 0; i < ps.size(); i++) {
            Player p = ps.get(i);
            Location l = p.getLocation();
            double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ(), d = dx * dx + dz * dz;
            if (d > bd || game.players.get(p).grabbedBy != null) continue;
            bd = d;
            best = p;
        }
        return best;
    }

    /** 바스락 떨다가(0.5초) 풀린다. 풀릴 때 마디 사이에서 새끼 가오리가 떨어진다. */
    private void alert() {
        look("roll");
        vel.setX(0).setZ(0).setY(Math.max(-0.4, Math.min(0.4, floorHere() + R - pos.getY())));
        body.look.rate = 2.0;
        if (inPhase() % 3 == 0) body.squash(1f + 0.08f * (inPhase() % 2 == 0 ? 1 : -1), 1.08f, 1.08f, 3);
        if (!standing(prey) || flatTo(prey) > LOSE_R) {
            body.squash(1f, 1f, 1f, 3);
            calm(cd(COOLDOWN) / 2);
            return;
        }
        if (inPhase() >= ALERT) {
            body.squash(1f, 1f, 1f, 2);
            body.look.rate = 1.0;
            pitch = 0;
            face(chest(prey));
            pitch = 0;
            body.pose(3);
            noPush = true;
            if (babyUnrolls < BABY_UNROLLS) {
                babyUnrolls++;
                babies();
            }
            look("open");
            phase(Phase.REACH);
        }
    }

    /** 풀려 펄럭이며 그 사람에게 날아간다. 닿으면 감싼다. */
    private void reach() {
        look("open");
        if (!standing(prey) || inPhase() > REACH_MAX) {
            phase(Phase.RETURN);
            return;
        }
        Vector c = chest(prey);
        double dx = c.getX() - pos.getX(), dy = c.getY() - pos.getY(), dz = c.getZ() - pos.getZ();
        double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
        yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        pitch = 0;
        if (l <= 1.3) {
            tryWrap(prey);
            return;
        }
        double v = REACH_V * speed() / baseSpeed;
        vel.setX(dx / l * v).setY(dy / l * v).setZ(dz / l * v);
    }

    private void tryWrap(Player p) {
        if (!grab(p, ESCAPE)) {
            phase(Phase.RETURN);
            return;
        }
        held = p;
        wrapYaw = p.getLocation().getYaw();
        game.players.cover(p, "g47_wrap", WRAP_T);
        sound("wrap", Sound.BLOCK_HONEY_BLOCK_SLIDE, 1.3f, 16);
        look("wrap");
        phase(Phase.WRAP);
        hit(p, DAMAGE, "cause_g47");
    }

    /** 사람을 감싸 2초 누른다. */
    private void wrap() {
        look("wrap");
        Player p = held;
        if (p == null || !p.isOnline() || !standing(p) || game.players.grabbedBy(p) != this) {
            endWrap(false);
            return;
        }
        Vector c = chest(p);
        vel.setX(c.getX() - pos.getX()).setY(c.getY() - pos.getY()).setZ(c.getZ() - pos.getZ());
        yaw = wrapYaw;
        pitch = 0;
        if (inPhase() >= WRAP_T) endWrap(true);
    }

    private void endWrap(boolean release) {
        Player p = held;
        held = null;
        if (p != null && p.isOnline()) {
            if (release && game.players.grabbedBy(p) == this) game.players.release(p);
            PState s = game.players.get(p);
            if ("g47_wrap".equals(s.cover)) game.players.uncover(p);
        }
        if (phase == Phase.WRAP) phase(Phase.RETURN);
    }

    /** 물러나며 다시 말린다. */
    private void goBack() {
        look("open");
        pitch = 0;
        if (prey != null && prey.isOnline()) {
            Vector t = toward(prey);
            vel.setX(-t.getX() * 0.2).setY(0.05).setZ(-t.getZ() * 0.2);
        } else vel.setX(0).setY(0.05).setZ(0);
        if (inPhase() >= RETURN_T) calm(cd(COOLDOWN));
    }

    /** 표적을 풀고 다시 말려 구른다. */
    private void calm(int wait) {
        mark(null);
        prey = null;
        noPush = false;
        readyAt = age + wait;
        look("roll");
        phase(Phase.ROLL);
    }

    /** 맞으면 한 번만: 풀린 채 높이 떠서 다른 곳으로 날아가 다시 말린다. */
    private void startFlee() {
        fled = true;
        if (held != null) endWrap(true);
        double a = game.random.nextDouble() * Math.PI * 2, d = 16 + game.random.nextDouble() * 6;
        double x = pos.getX() + Math.cos(a) * d, z = pos.getZ() + Math.sin(a) * d;
        double cx = x - (Plaza.CX + 0.5), cz = z - (Plaza.CZ + 0.5), r = Math.hypot(cx, cz);
        double lim = Math.max(BAND_IN + 2, Math.min(BAND_OUT - 2, r));
        if (r > 1e-3 && lim != r) {
            x = Plaza.CX + 0.5 + cx / r * lim;
            z = Plaza.CZ + 0.5 + cz / r * lim;
        }
        fleeTo.setX(x).setY(0).setZ(z);
        mark(null);
        prey = null;
        noPush = true;
        look("open");
        sound("rustle", Sound.BLOCK_LEAF_LITTER_BREAK, 1.2f, 20);
        phase(Phase.FLEE);
    }

    private void flee() {
        look("open");
        double dx = fleeTo.getX() - pos.getX(), dz = fleeTo.getZ() - pos.getZ(), d = Math.hypot(dx, dz);
        if (d < 1.0 || inPhase() > 120) {
            calm(40);
            return;
        }
        double v = FLEE_V * speed() / baseSpeed;
        double alt = floorHere() + (d > 4 ? FLEE_ALT : R + 0.3);
        vel.setX(dx / d * v).setY(Math.max(-0.3, Math.min(0.3, (alt - pos.getY()) * 0.3))).setZ(dz / d * v);
        yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        pitch = 0;
    }

    private double flatTo(Player p) {
        Location l = p.getLocation();
        return Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
    }

    /** 새끼 가오리 둘(가오리 게이브를 작게, 보상 0). */
    private void babies() {
        for (int i = 0; i < BABIES; i++) {
            double a = game.random.nextDouble() * Math.PI * 2;
            Vector at = pos.clone().add(new Vector(Math.cos(a) * 0.8, 0, Math.sin(a) * 0.8));
            at.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + Species.G01.band.hover);
            Gabe c = child(Species.G01, at);
            if (c == null) return;
            c.maxHp = c.hp = Math.max(6, c.maxHp * 0.3 * game.director.hpMul());
            if (c.body != null) c.body.scale(0.55f, 0);
        }
    }

    // ------------------------------------------------------------------ 착시(가짜 동료)

    /** 영향권 안 사람에게만 근처 진짜 게이브를 가짜 동료로 보이게 한다. 인형은 매 틱 따라가고, 고르기와 보이기는 1초마다. */
    private void aura() {
        if (!fakes.isEmpty()) {
            for (Iterator<Fake> it = fakes.iterator(); it.hasNext();) {
                Fake f = it.next();
                Gabe g = f.target;
                if (g.dead || g.removed || g.body == null || !f.doll.valid()) {
                    drop(f);
                    it.remove();
                    continue;
                }
                f.doll.move(g.pos.getX(), game.mobs.flow.floor(g.pos.getX(), g.pos.getZ()), g.pos.getZ(), g.yaw);
            }
        }
        if (!every(20)) return;
        boolean on = age >= litUntil;
        inside.clear();
        List<Player> ps = game.mobs.standing();
        for (int i = 0; i < ps.size(); i++) {
            Player p = ps.get(i);
            if (flatTo(p) <= AURA_R) inside.add(p.getUniqueId());
        }
        for (Iterator<Fake> it = fakes.iterator(); it.hasNext();) {
            Fake f = it.next();
            if (on && !inside.isEmpty() && f.target.pos.distanceSquared(pos) <= DROP_R * DROP_R) continue;
            drop(f);
            it.remove();
        }
        if (on && !inside.isEmpty() && fakes.size() < DOLLS) {
            for (Gabe g : game.mobs.near(pos, PICK_R, this::fakeable)) {
                Player like = G47Doll.pickLikeness(game, inside);
                Location at = new Location(game.world, g.pos.getX(), game.mobs.flow.floor(g.pos.getX(), g.pos.getZ()), g.pos.getZ(), g.yaw, 0);
                fakes.add(new Fake(g, new G47Doll(game, at, like, false)));
                if (fakes.size() >= DOLLS) break;
            }
        }
        if (fakes.isEmpty()) return;
        for (Player p : game.world.getPlayers()) {
            UUID uid = p.getUniqueId();
            boolean in = inside.contains(uid);
            for (Fake f : fakes) {
                boolean want = in && !uid.equals(f.doll.likeness);
                boolean has = f.shown.contains(uid);
                if (want && !has) {
                    f.shown.add(uid);
                    f.doll.show(p);
                    f.target.body.hide(p, true);
                } else if (!want && has) {
                    f.shown.remove(uid);
                    f.doll.hide(p);
                    if (!f.target.dead && f.target.body != null) f.target.body.hide(p, false);
                }
            }
            if (in) game.hud.blinkRoster(p, 30);
        }
        if (age >= nextBuzz) {
            sound("buzz", Sound.BLOCK_REDSTONE_TORCH_BURNOUT, 1.6f, 16);
            nextBuzz = age + 60 + game.random.nextInt(40);
        }
    }

    /** 가짜 동료로 바꿔 보일 수 있는 게이브: 고리에서 걸어오는, 디스플레이 적은 땅 종(떼, 하늘, 흉내, 이미 누군가에게 숨긴 것은 뺌). */
    private boolean fakeable(Gabe g) {
        if (g == this || g.dead || g.body == null || g.sp == Species.G47 || g instanceof G49Fake) return false;
        if (g.sp.spawn != Species.Spawn.RING || g.sp.is(Role.SWARM) || g.flying || g.disguised || g.untargetable) return false;
        if (!g.body.hiddenFrom.isEmpty() || g.body.count() > 6) return false;
        for (Fake f : fakes) if (f.target == g) return false;
        return true;
    }

    /** 착시 하나를 걷는다: 인형을 지우고 숨겼던 진짜 몸을 다시 보인다. */
    private void drop(Fake f) {
        Gabe g = f.target;
        if (!g.dead && !g.removed && g.body != null) {
            for (UUID uid : f.shown) {
                Player p = game.plugin.getServer().getPlayer(uid);
                if (p != null) g.body.hide(p, false);
            }
        }
        f.shown.clear();
        f.doll.remove();
    }

    private void clearFakes() {
        for (Fake f : fakes) drop(f);
        fakes.clear();
    }

    /** 조명탄, 정화가 비춤: 무늬가 드러나고 10초 동안 착시가 풀린다. */
    @Override
    public void reveal() {
        super.reveal();
        litUntil = age + LIT_T;
        clearFakes();
        if (body != null) state(state.endsWith("_lit") ? state : state + "_lit");
    }

    // ------------------------------------------------------------------ 맞음

    private Vector forward() {
        double yr = Math.toRadians(yaw);
        return new Vector(-Math.sin(yr), 0, Math.cos(yr));
    }

    @Override
    public List<double[]> spheres() {
        if (phase == Phase.ROLL || phase == Phase.ALERT) return List.of(new double[] {pos.getX(), pos.getY(), pos.getZ(), 0.45});
        List<double[]> out = new ArrayList<>(SEGS);
        if (phase == Phase.WRAP) {
            for (int i = 0; i < SEGS; i++) {
                if (gone[i]) continue;
                double a = Math.toRadians(-wrapYaw) + Math.PI / 2 * i;
                out.add(new double[] {pos.getX() + Math.sin(a) * 0.5, pos.getY(), pos.getZ() + Math.cos(a) * 0.5, 0.45});
            }
        } else {
            Vector f = forward();
            for (int i = 0; i < SEGS; i++) {
                if (gone[i]) continue;
                double t = -HALF + SEG_L * (i + 0.5);
                out.add(new double[] {pos.getX() + f.getX() * t, pos.getY(), pos.getZ() + f.getZ() * t, 0.5});
            }
        }
        if (out.isEmpty()) out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), 0.45});
        return out;
    }

    /** 맞은 자리의 마디(말려 있으면 바깥 겹부터). 그 마디가 없으면 가장 가까운 남은 마디. */
    private int segAt(Vector at) {
        int want = 0;
        if (at != null && phase != Phase.ROLL && phase != Phase.ALERT) {
            if (phase == Phase.WRAP) {
                double a = Math.atan2(at.getX() - pos.getX(), at.getZ() - pos.getZ()) - Math.toRadians(-wrapYaw);
                want = (int) Math.round(a / (Math.PI / 2));
                want = ((want % SEGS) + SEGS) % SEGS;
            } else {
                Vector f = forward();
                double t = (at.getX() - pos.getX()) * f.getX() + (at.getZ() - pos.getZ()) * f.getZ();
                want = Math.max(0, Math.min(SEGS - 1, (int) Math.floor((t + HALF) / SEG_L)));
            }
        }
        int best = -1, bd = 99;
        for (int i = 0; i < SEGS; i++) {
            if (gone[i]) continue;
            int d = Math.abs(i - want);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (!init) init();
        double before = hp;
        DamageType t = d.type();
        if (t == DamageType.EXPLOSION || t == DamageType.FIRE) {
            for (int i = 0; i < SEGS; i++) if (!gone[i]) segHp[i] -= d.amount();
        } else {
            int i = segAt(d.at());
            if (i >= 0) segHp[i] -= d.amount();
        }
        boolean lost = false;
        for (int i = 0; i < SEGS; i++) {
            if (gone[i] || segHp[i] > 0) continue;
            gone[i] = true;
            lost = true;
            body.look.on.add("gone" + (i + 1));
            if (!noReward) {
                reward = Math.max(0, reward - SEG_REWARD);
                Player by = d.by();
                if (by != null && by.isOnline()) game.players.addMoney(by, SEG_REWARD);
            }
        }
        double h = 0;
        for (int i = 0; i < SEGS; i++) if (!gone[i]) h += Math.max(0, segHp[i]);
        hp = h;
        game.mobs.onHurt(this, d, before - hp);
        int left = alive();
        if (lost) {
            sound("rustle", Sound.BLOCK_LEAF_LITTER_BREAK, 1.2f, 20);
            // 남은 마디 수를 근처 사람에게
            for (Player p : game.mobs.standing()) if (flatTo(p) <= 16) game.hud.meter(p, "g47", left / (double) SEGS, 60);
        }
        if (left == 0) {
            die(d);
            return true;
        }
        if (!fled && t != DamageType.BITE) startFlee();
        return true;
    }

    // ------------------------------------------------------------------ 죽음, 치우기

    private void cleanup() {
        clearFakes();
        if (held != null) endWrap(true);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        cleanup();
        super.die(d);
    }

    @Override
    public void remove() {
        cleanup();
        super.remove();
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " segs=" + alive() + " fakes=" + fakes.size() + (age < litUntil ? " lit" : "") + (fled ? " fled" : "")
                + (held != null ? " held=" + held.getName() : prey != null ? " prey=" + prey.getName() : "");
    }
}
