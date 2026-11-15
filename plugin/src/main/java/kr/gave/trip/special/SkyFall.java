package kr.gave.trip.special;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.Props;
import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.mob.Band;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.LookState;
import kr.gave.mob.Mobs;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.trip.Launch;
import kr.gave.trip.Rounds;
import kr.gave.trip.Trip;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * 특수 22 하늘이 내려온다(4막 발사장, specials_draft skyfall): 하늘을 메운 얼굴들(지평선 떼 카드 gabe/horizon 상태 c 를 하늘 카드로)이 천장처럼 천천히
 * 내려온다(바닥 위 {@link LateTune#SKY_TOP} → {@link LateTune#SKY_LOW}). 얼굴 {@link LateTune#SKY_FACES} 장은 쏠 수 있는 것(엔진 Shootable)이고, 하나를
 * 다 깎으면 천장이 {@link LateTune#SKY_PUSH} 블록 올라가고 그 얼굴은 새 체력으로 다시 내려온다. 그림자에서 땅 종이 솟는다. 천장이 바닥 가까이 닿으면
 * 서 있는 사람 모두 초마다 피해, 트럭이 눌려 깎인다. 내려올수록 더 어둡다. 건조를 다 채우면 끝(Launch 가 있으면 끝 판정은 Launch).
 */
public final class SkyFall extends Late {

    private final class Face implements Mobs.Shootable {
        final double ang, rad, jitter;
        ItemDisplay d;
        double hp, maxHp;
        boolean gone;
        Vector at = new Vector();

        Face(double ang, double rad, double jitter) {
            this.ang = ang;
            this.rad = rad;
            this.jitter = jitter;
        }

        @Override
        public double rayHit(Vector o, Vector dir, double max) {
            if (gone) return Double.POSITIVE_INFINITY;
            double rr = LateTune.SKY_FACE_R;
            double ox = at.getX() - o.getX(), oy = at.getY() - o.getY(), oz = at.getZ() - o.getZ();
            double t = ox * dir.getX() + oy * dir.getY() + oz * dir.getZ();
            double m2 = ox * ox + oy * oy + oz * oz - t * t;
            if (t < -rr || m2 > rr * rr) return Double.POSITIVE_INFINITY;
            double h = Math.max(0, t - Math.sqrt(rr * rr - m2));
            return h <= max ? h : Double.POSITIVE_INFINITY;
        }

        @Override
        public boolean shot(Damage dm) {
            if (gone || !running || ended()) return false;
            hp -= dm.amount();
            hits++;
            if (dm.by() != null) game.hud.hit(dm.by(), false);
            if (hp <= 0) pushed(this);
            return false;
        }

        @Override
        public Vector center() {
            return at.clone();
        }

        @Override
        public boolean gone() {
            return gone;
        }
    }

    private final List<Face> faces = new ArrayList<>();
    private double h, build, drained;
    private int pushes, hits, risen;
    private long nextRise;
    private String model;

    public SkyFall(Game game, Trip trip, Rounds rounds) {
        super(game, trip, rounds);
    }

    @Override
    protected String icon() {
        return "ti_goal_build";
    }

    /** 발사장(Launch)이 이 라운드의 건조와 끝을 쥐고 있나: 31~34 밖에서 열리면(시험) 제 건조 막대와 끝 판정으로 돈다. */
    private boolean launch() {
        Launch l = trip.has("Launch") ? trip.part(Launch.class) : null;
        return l != null && l.building();
    }

    @Override
    protected boolean begin() {
        faces.clear();
        h = LateTune.SKY_TOP;
        build = drained = 0;
        pushes = hits = risen = 0;
        model = Look.has("gabe/horizon") ? "gabe/horizon" : "gabe/g01";
        int n = LateTune.SKY_FACES, inner = n * 3 / 8;
        double a0 = rnd.nextDouble() * Math.PI * 2;
        for (int i = 0; i < n; i++) {
            boolean in = i < inner;
            int k = in ? i : i - inner, m = in ? inner : n - inner;
            double a = a0 + Math.PI * 2 * k / m + (in ? 0 : Math.PI / m);
            Face f = new Face(a, in ? 9 : 22, rnd.nextDouble() * 2.5);
            spawnFace(f);
            faces.add(f);
            game.mobs.addShootable(f);
        }
        nextRise = game.now() + LateTune.SKY_RISE_EVERY;
        fog(1);
        darken(0.72);
        Props.soundAround(loc(new Vector(Plaza.CX + 0.5, 0, Plaza.CZ + 0.5), 20), "sfx.colossus", Sound.ENTITY_WARDEN_ROAR, 2f, 0.6f, 0.5f, 220, -1);
        return true;
    }

    private double faceHp() {
        Species sp = Species.G01;
        return hpFor(sp, LateTune.SKY_FACE_HP);
    }

    private void spawnFace(Face f) {
        f.maxHp = faceHp();
        f.hp = f.maxHp;
        f.gone = false;
        f.at = polar(f.rad, f.ang).setY(Plaza.FLOOR_Y + h + f.jitter);
        LookState ls = new LookState();
        ls.phase = rnd.nextInt(80);
        ls.rate = 0.6;
        boolean card = model.equals("gabe/horizon");
        ItemStack it = Body.item(model, card ? "c" : "", ls, game.worldTime());
        float s = (float) (card ? LateTune.SKY_SCALE : LateTune.SKY_SCALE * 4) * Look.scale(model);
        Location l = new Location(game.world, f.at.getX(), f.at.getY(), f.at.getZ());
        f.d = keep(game.world.spawn(l, ItemDisplay.class, e -> {
            e.setPersistent(false);
            e.setItemStack(it);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setBillboard(Display.Billboard.CENTER);
            e.setBrightness(new Display.Brightness(7, 9));
            e.setViewRange(4f);
            e.setDisplayWidth(12f);
            e.setDisplayHeight(10f);
            e.setTeleportDuration(20);
            e.setTransformationMatrix(new Matrix4f().scale(s).rotateY((float) Math.PI));
        }));
    }

    /** 얼굴 하나를 다 깎음: 천장이 올라가고 그 얼굴은 새 체력. */
    private void pushed(Face f) {
        pushes++;
        h = Math.min(LateTune.SKY_TOP, h + LateTune.SKY_PUSH);
        f.maxHp = faceHp();
        f.hp = f.maxHp;
        Location l = new Location(game.world, f.at.getX(), f.at.getY(), f.at.getZ());
        try {
            game.shotFx.pop(l);
        } catch (RuntimeException e) {
            // 효과 예산
        }
        Props.soundAround(l, "sfx.boss_roar", Sound.ENTITY_GHAST_HURT, 1.4f, 0.8f, 0.6f, 120, -1);
        if (f.d != null && f.d.isValid()) {
            f.d.setInterpolationDelay(0);
            f.d.setInterpolationDuration(4);
        }
    }

    @Override
    protected void end() {
        for (Face f : faces) {
            f.gone = true;
            game.mobs.removeShootable(f);
        }
        faces.clear();
    }

    @Override
    public void plan(Director.Plan p) {
        scaleCounts(p, LateTune.SKY_UNITS);
    }

    @Override
    protected boolean killAll() {
        return false;
    }

    @Override
    public boolean roundDone(boolean done) {
        if (running && launch() && !ended()) return false;
        return super.roundDone(done);
    }

    @Override
    protected double frac() {
        return launch() ? -1 : build;
    }

    @Override
    protected String text() {
        return launch() ? null : Math.round(build * 100) + "%";
    }

    /** 천장 몫(1 = 처음 높이, 0 = 가장 낮음). */
    private double sky() {
        return Math.max(0, Math.min(1, (h - LateTune.SKY_LOW) / (LateTune.SKY_TOP - LateTune.SKY_LOW)));
    }

    @Override
    protected void step(long t) {
        long now = game.now();
        h = Math.max(LateTune.SKY_LOW, h - LateTune.SKY_FALL / 20);
        double s = sky();
        darken(0.72 + 0.22 * (1 - s));
        // 얼굴 옮기기(1초마다 보간)
        if (now % 20 == 0) {
            for (Face f : faces) {
                f.at = polar(f.rad, f.ang + Math.sin(now * 0.004 + f.jitter) * 0.08).setY(Plaza.FLOOR_Y + h + f.jitter * s);
                if (f.d != null && f.d.isValid()) f.d.teleport(new Location(game.world, f.at.getX(), f.at.getY(), f.at.getZ()));
            }
            if (now % 100 == 0) Props.soundAround(loc(new Vector(Plaza.CX + 0.5, 0, Plaza.CZ + 0.5), h), "gabe.g28.drone", Sound.AMBIENT_BASALT_DELTAS_MOOD, 1.6f,
                    0.6f + (float) (1 - s) * 0.4f, 0.5f, 120, -1);
        }
        // 천장이 닿음
        if (h <= LateTune.SKY_LOW + 0.01 && now % 20 == 0) {
            for (Player p : game.players.standing()) game.players.hurt(p, LateTune.SKY_DMG * game.director.dmgCmd(), null, "cause_sp_skyfall");
            Truck tr = truck();
            if (tr != null) drained += tr.hitFrac(LateTune.SKY_TRUCK, null, "cause_sp_skyfall");
        }
        // 그림자에서 솟는 땅 종
        if (now >= nextRise && !faces.isEmpty()) {
            nextRise = now + Math.max(30, (long) (LateTune.SKY_RISE_EVERY * (0.5 + 0.5 * s)));
            Face f = faces.get(rnd.nextInt(faces.size()));
            Species sp = rounds.pick(x -> (x.band == Band.GROUND || x.band == Band.WAIST) && x.spawn == Species.Spawn.RING && !x.is(Role.BOSS));
            if (sp == null) sp = rounds.cheapSwarm();
            if (sp != null && sp.spawn == Species.Spawn.RING) {
                Vector at = polar(Math.max(14, f.rad + 4), f.ang);
                Gabe g = rounds.spawnAt(sp, at);
                if (g != null) {
                    risen++;
                    game.world.spawnParticle(Particle.BLOCK, at.getX(), Plaza.FLOOR_Y + 0.3, at.getZ(), 30, 0.5, 0.3, 0.5, 0.1,
                            game.world.getBlockAt((int) Math.floor(at.getX()), Plaza.FLOOR_Y - 1, (int) Math.floor(at.getZ())).getBlockData());
                }
            }
        }
        if (!launch()) {
            build += 1.0 / (LateTune.SKY_BUILD_SECONDS * 20);
            if (build >= 1) {
                build = 1;
                win();
                return;
            }
        }
        stream(LateTune.STREAM_SHARE);
    }

    @Override
    protected void hudMore(Player p, Map<String, Object> out) {
        out.put("sp.sky", sky());
    }

    @Override
    protected String statusMore() {
        double hpSum = 0;
        for (Face f : faces) hpSum += Math.max(0, f.hp);
        return String.format(Locale.ROOT, "sky=%.1f faces=%d hp=%.0f pushes=%d hits=%d risen=%d build=%.2f drained=%.1f model=%s", h, faces.size(), hpSum, pushes, hits, risen, build,
                drained, model);
    }

    @Override
    protected String testMore(Player p, String sub, String[] a) {
        switch (sub) {
            case "높이" -> {
                h = a.length > 4 ? Double.parseDouble(a[4]) : LateTune.SKY_LOW;
                return statusMore();
            }
            case "쏘기" -> {
                // 그 사람 눈에서 가장 가까운 얼굴을 쏜 것처럼(엔진 맞힘 길)
                if (p == null) return null;
                Face best = null;
                double bd = Double.MAX_VALUE;
                for (Face f : faces) {
                    double d = f.at.distanceSquared(p.getEyeLocation().toVector());
                    if (d < bd) {
                        bd = d;
                        best = f;
                    }
                }
                if (best == null) return "얼굴 없음";
                Vector dir = best.at.clone().subtract(p.getEyeLocation().toVector()).normalize();
                double at = best.rayHit(p.getEyeLocation().toVector(), dir, 200);
                game.mobs.damage(best, new Damage(best.maxHp + 1, kr.gave.mob.DamageType.BULLET, p, best.at.clone(), dir, "rifle", 0));
                return String.format(Locale.ROOT, "얼굴을 쏨 ray=%.1f %s", at, statusMore());
            }
            case "올려보기" -> {
                if (p == null) return null;
                Location l = p.getLocation();
                l.setPitch(-60);
                p.teleport(l);
                return statusMore();
            }
            default -> {
                return null;
            }
        }
    }
}
