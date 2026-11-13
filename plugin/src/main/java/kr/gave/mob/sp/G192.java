package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G192 초승달 조각 게이브(4막 33라운드, 떼, 돌격, 0.8블록, 개체 하나 = 디스플레이 하나). 어둠에 깎여 초승달만 남은 얼굴 조각 40~60이 싸움터 위
 * 18블록을 한 방향으로 돈다.
 * <ul>
 * <li>무리 시계(판에 하나, {@link Flock}): 산 조각이 스물 이상이면 6초 x 박자마다 서 있는 사람 하나(같은 사람 연속 금지)를 골라 서른까지 그 머리 위
 * 8블록에 초승달 대열로 모인다(4초). 다 모이면 그 둘레 반경 4 가 일식 그늘(짙은 판 + 그늘 안 사람 시야 어둠)이 되고 6초 동안 조각들이 그늘 안
 * 사람에게 사선으로 내리꽂혀 스치고(지나는 길 0.7블록 안 7) 다시 솟는다(한 사람에게 2초에 서너 마리).</li>
 * <li>모여드는 동안 대열이 열둘 아래로 깨지면 그늘이 안 생기고 흩어진다. 스물 아래로 줄면 대열을 못 짜고 저마다 내리꽂는다.</li>
 * <li>원래 어두운 곳(LIGHTS: 빛 밖, game.mobs.dark)에 선 사람에게는 모이지 않고 바로 내려온다. 조명탄과 불 빛 안(game.mobs.lit)으로는 못 내려온다.</li>
 * </ul>
 * 도감과 다른 점: 멀리서 무리를 덩어리 하나로 그리는 LOD 는 없다(개체마다 디스플레이 하나). 그늘 가장자리의 '그늘 쪽 몸만' 은 없음.
 */
public final class G192 extends Gabe {

    static final double ORBIT_R = 20, ORBIT_H = 18, ORBIT_V = 0.3, ABOVE = 8, SHADE_R = 4, DIVE_V = 0.9, RISE_V = 0.55, CUT_R = 0.7, CUT_DMG = 7;
    static final int CYCLE = 120, GATHER_T = 80, ECLIPSE_T = 120, MIN_FLOCK = 20, MAX_FLOCK = 30, MIN_HOLD = 12, DIVE_MAX = 40, SOLO_CD = 140;

    enum Mode { ORBIT, GATHER, DIVE, RISE }

    private Mode mode = Mode.ORBIT;
    private int modeAt, slot = -1, soloAt;
    private double myR, myH;
    private int dir;
    private Player diveAt;
    private final Vector diveTo = new Vector(), prev = new Vector();
    private final Set<UUID> cutThis = new HashSet<>();
    private boolean lit;
    private Flock flock;

    public G192(Game game, Species sp) {
        super(game, sp);
        radius = 0.38;
        baseSpeed = ORBIT_V;
        flying = true;
        noPush = true;
        noBite = true;
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        myR = ORBIT_R + game.random.nextDouble() * 6 - 2;
        myH = ORBIT_H + game.random.nextDouble() * 3 - 1;
        dir = 1;
        soloAt = age + cd(SOLO_CD) / 2 + game.random.nextInt(60);
    }

    private void mode(Mode m) {
        mode = m;
        modeAt = age;
    }

    private int inMode() {
        return age - modeAt;
    }

    // ------------------------------------------------------------------ 무리 시계(판에 하나)

    /** 산 조각 모두가 나눠 쓰는 시계: 표적 고르기, 대열 자리, 일식 그늘. 그 틱에 처음 tick 하는 조각이 돌린다. */
    static final class Flock {
        final Game game;
        long tickAt = Long.MIN_VALUE, nextAt, phaseAt;
        int phase; // 0 돎, 1 모임, 2 일식
        Player target;
        UUID lastTarget;
        final Vector center = new Vector();
        final List<G192> members = new ArrayList<>(), gang = new ArrayList<>();
        ItemDisplay shade;
        long screechAt = Long.MIN_VALUE;

        Flock(Game game) {
            this.game = game;
        }
    }

    private static Flock FLOCK;

    private static Flock flockFor(Game game) {
        Flock f = FLOCK;
        if (f == null || f.game != game || game.now() - f.tickAt > 60) {
            if (f != null) G167Sky.kill(f.shade);
            f = new Flock(game);
            f.nextAt = game.now() + 60;
            FLOCK = f;
        }
        return f;
    }

    private void flockTick() {
        flock = flockFor(game);
        Flock f = flock;
        long now = now();
        if (f.tickAt == now) return;
        f.tickAt = now;
        f.members.clear();
        for (Gabe g : game.mobs.all()) if (g instanceof G192 c && !c.dead) f.members.add(c);
        f.gang.removeIf(c -> c.dead);
        if (!G167Sky.wave(game)) {
            endFlock(f);
            return;
        }
        switch (f.phase) {
            case 0 -> {
                if (now < f.nextAt || f.members.size() < MIN_FLOCK) return;
                Player p = pickTarget(f);
                if (p == null) {
                    f.nextAt = now + 40;
                    return;
                }
                f.target = p;
                f.lastTarget = p.getUniqueId();
                Location l = p.getLocation();
                f.center.setX(l.getX()).setY(l.getY()).setZ(l.getZ());
                // 표적에 가까운 서른까지
                List<G192> m = new ArrayList<>(f.members);
                m.removeIf(c -> c.mode == Mode.DIVE || c.mode == Mode.RISE);
                m.sort((a, b) -> Double.compare(a.pos.distanceSquared(f.center), b.pos.distanceSquared(f.center)));
                f.gang.clear();
                for (int i = 0; i < Math.min(MAX_FLOCK, m.size()); i++) {
                    G192 c = m.get(i);
                    c.slot = i;
                    c.mode(Mode.GATHER);
                    f.gang.add(c);
                }
                f.phase = 1;
                f.phaseAt = now;
                screech(f, f.gang.isEmpty() ? null : f.gang.get(0), 0.9f);
            }
            case 1 -> {
                followTarget(f, 0.15);
                if (now - f.phaseAt < cd(GATHER_T)) return;
                if (f.gang.size() < MIN_HOLD || !G167Sky.up(game, f.target) || game.mobs.lit(f.center)) {
                    endFlock(f);
                    return;
                }
                // 일식: 그늘을 펴고 조각들이 희게 빛남
                double fy = game.mobs.flow.floor(f.center.getX(), f.center.getZ());
                f.shade = G167Sky.fx(game, new Location(game.world, f.center.getX(), fy + 0.07, f.center.getZ()), "gabe/g192_shade", "",
                        G167Sky.lay("gabe/g192_shade", 0, (float) SHADE_R, 1f, (float) SHADE_R), ECLIPSE_T + 10);
                if (f.shade != null) f.shade.setBrightness(new org.bukkit.entity.Display.Brightness(0, 0));
                for (G192 c : f.gang) c.light(true);
                f.phase = 2;
                f.phaseAt = now;
                screech(f, f.gang.isEmpty() ? null : f.gang.get(0), 0.7f);
            }
            case 2 -> {
                long t = now - f.phaseAt;
                if (t >= ECLIPSE_T || f.gang.size() < 4 || game.mobs.lit(f.center)) {
                    endFlock(f);
                    return;
                }
                List<Player> in = new ArrayList<>();
                for (Player p : game.mobs.standing()) if (G167Sky.flat(f.center, p.getLocation()) <= SHADE_R) in.add(p);
                if (t % 20 == 0) {
                    for (Player p : in) p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 40, 0, false, false, false));
                }
                // 10틱마다 그늘 안 사람마다 떠 있는 조각 하나가 내리꽂힘(한 사람에게 2초에 넷)
                if (t % 10 == 5) {
                    for (Player p : in) {
                        for (G192 c : f.gang) {
                            if (c.mode == Mode.GATHER && c.inMode() > 6) {
                                c.dive(p);
                                break;
                            }
                        }
                    }
                }
            }
            default -> f.phase = 0;
        }
    }

    /** 표적: 서 있고 빛 안이 아닌 사람, 지난 표적이 아니면 먼저(조각 떼에 가까운 쪽). 원래 어두운 데 선 사람은 저마다 내리꽂으니 뺀다. */
    private Player pickTarget(Flock f) {
        Player best = null, fallback = null;
        double bd = Double.MAX_VALUE;
        for (Player p : game.mobs.standing()) {
            Vector feet = p.getLocation().toVector();
            if (game.mobs.lit(feet)) continue;
            if (fallback == null) fallback = p;
            if (p.getUniqueId().equals(f.lastTarget) && game.mobs.standing().size() > 1) continue;
            if (game.mobs.dark(feet)) continue;
            double d = G167Sky.flat(pos, p.getLocation()) + game.random.nextDouble() * 12;
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best != null ? best : fallback;
    }

    private static void followTarget(Flock f, double v) {
        if (f.target == null || !f.target.isOnline()) return;
        Location l = f.target.getLocation();
        double dx = l.getX() - f.center.getX(), dz = l.getZ() - f.center.getZ(), d = Math.hypot(dx, dz);
        if (d > 0.01) {
            double k = Math.min(v, d) / d;
            f.center.setX(f.center.getX() + dx * k).setZ(f.center.getZ() + dz * k);
        }
        f.center.setY(l.getY());
    }

    private void endFlock(Flock f) {
        G167Sky.kill(f.shade);
        f.shade = null;
        for (G192 c : f.gang) {
            c.light(false);
            c.slot = -1;
            if (c.mode == Mode.GATHER) c.mode(Mode.RISE);
        }
        f.gang.clear();
        f.target = null;
        f.phase = 0;
        f.nextAt = now() + cd(CYCLE - GATHER_T / 2);
    }

    private void screech(Flock f, G192 at, float pitch) {
        if (at == null || now() - f.screechAt < 10) return;
        f.screechAt = now();
        at.sound("screech", Sound.ENTITY_BAT_AMBIENT, pitch, 48);
    }

    // ------------------------------------------------------------------ 조각 하나

    @Override
    public void tick() {
        flockTick();
        prev.copy(pos);
        switch (mode) {
            case ORBIT -> orbit();
            case GATHER -> gather();
            case DIVE -> diveTick();
            case RISE -> rise();
        }
    }

    private Vector orbitCenter() {
        Vector c = G167Sky.center();
        return c.setY(c.getY() + myH);
    }

    private void orbit() {
        circle(orbitCenter(), myR, speed(), dir);
        Vector v = vel.clone();
        if (v.lengthSquared() > 1e-6) face(pos.clone().add(v));
        if (!G167Sky.wave(game) || age < soloAt) return;
        soloAt = age + cd(SOLO_CD) + game.random.nextInt(40);
        // 대열을 못 짤 때(스물 아래), 또는 원래 어두운 데 선 사람: 저마다 바로 내리꽂음
        boolean few = flock == null || flock.members.size() < MIN_FLOCK;
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : game.mobs.standing()) {
            Vector feet = p.getLocation().toVector();
            if (game.mobs.lit(feet)) continue;
            if (!few && !game.mobs.dark(feet)) continue;
            double d = pos.distance(feet);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        if (best != null) dive(best);
    }

    /** 대열 자리: 표적 머리 위 8블록, 초승달 꼴 호(열린 쪽이 남쪽). */
    private Vector slotAt() {
        Flock f = flock;
        int n = Math.max(1, f.gang.size());
        double a = Math.toRadians(-110 + 220.0 * slot / Math.max(1, n - 1));
        double r = (slot % 2 == 0) ? 2.4 : 3.2;
        return new Vector(f.center.getX() + Math.sin(a) * r, f.center.getY() + ABOVE + (slot % 3) * 0.35, f.center.getZ() - Math.cos(a) * r);
    }

    private void gather() {
        if (flock == null || slot < 0 || flock.phase == 0) {
            mode(Mode.RISE);
            return;
        }
        Vector s = slotAt();
        seek(s, Math.max(speed(), 0.5));
        // 머리 위에서 아래를 봄
        yaw = G167Sky.yawTo(pos, flock.center);
        pitch = 70;
    }

    /** 사람에게 사선으로 내리꽂음. */
    void dive(Player p) {
        if (p == null || game.mobs.lit(p.getLocation().toVector())) return;
        diveAt = p;
        cutThis.clear();
        Location l = p.getLocation();
        // 사선: 사람 몸통을 지나 반대쪽 아래로(땅 1블록 위에서 멈춤)
        Vector chest = new Vector(l.getX(), l.getY() + 1.0, l.getZ());
        Vector way = chest.clone().subtract(pos);
        diveTo.copy(chest.add(way.normalize().multiply(2.0)));
        double fl = game.mobs.flow.floor(diveTo.getX(), diveTo.getZ()) + 0.6;
        if (diveTo.getY() < fl) diveTo.setY(fl);
        mode(Mode.DIVE);
        if (flock != null && now() - flock.screechAt >= 10) {
            flock.screechAt = now();
            sound("swish", Sound.ENTITY_PHANTOM_SWOOP, 1.3f, 16);
        }
    }

    private void diveTick() {
        if (diveAt != null && game.mobs.lit(diveAt.getLocation().toVector())) {
            mode(Mode.RISE);
            return;
        }
        Vector d = diveTo.clone().subtract(pos);
        double l = d.length();
        double v = Math.max(DIVE_V, speed() * 2.5);
        if (l <= v || inMode() > DIVE_MAX) {
            mode(Mode.RISE);
            return;
        }
        vel.copy(d.multiply(v / l));
        face(diveTo);
        // 지나는 길(이 틱 선분)에 든 사람
        Vector next = pos.clone().add(vel);
        for (Player p : game.mobs.standing()) {
            if (cutThis.contains(p.getUniqueId())) continue;
            Location pl = p.getLocation();
            Vector ch = new Vector(pl.getX(), pl.getY() + 0.9, pl.getZ());
            if (G167Sky.segDist(ch, pos, next) <= CUT_R + 0.4) {
                cutThis.add(p.getUniqueId());
                hit(p, CUT_DMG, "cause_g192");
                mark(p);
            }
        }
    }

    private void rise() {
        Vector to = (slot >= 0 && flock != null && flock.phase != 0) ? slotAt() : orbitCenter().add(new Vector(myR * 0.7, 0, 0));
        seek(to, Math.max(RISE_V, speed()));
        if (vel.lengthSquared() > 1e-6) face(pos.clone().add(vel));
        if (pos.distanceSquared(to) < 4 || inMode() > 80) {
            if (slot >= 0 && flock != null && flock.phase != 0) mode(Mode.GATHER);
            else {
                mark(null);
                mode(Mode.ORBIT);
            }
        }
    }

    private void light(boolean on) {
        if (lit == on) return;
        lit = on;
        state(on ? "lit" : "");
        if (body != null && body.anchor != null && body.anchor.isValid())
            body.anchor.setBrightness(on ? G167Sky.GLOW : Body.LIGHT);
    }

    @Override
    public String toString() {
        Flock f = flock;
        return super.toString() + " mode=" + mode + " slot=" + slot + (f == null ? "" : " flock=" + f.phase + "/" + f.members.size() + "/" + f.gang.size()
                + " tgt=" + (f.target == null ? "-" : f.target.getName()));
    }
}
