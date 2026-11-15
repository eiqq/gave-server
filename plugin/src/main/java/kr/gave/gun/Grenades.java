package kr.gave.gun;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import kr.gave.Props;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import net.kyori.adventure.text.Component;

/**
 * 수류탄(좀비런 Grenades 를 옮김): F 로 던짐(눈높이로 던지면 20 블록 안팎, 위로 던지면 더 멀리), 튕기며 구르다 던진 뒤 2초에 터짐.
 * 반경 6 안에서 폭발 자리에서 보이는(블록에 안 가린) 게이브에게 거리 감쇠 피해(가운데 120, 가장자리 25, 종류 EXPLOSION, 게이브 체력 배율의
 * 제곱근만큼 커짐). 사람은 안 다침. 맞힘과 처치는 총과 같은 길(Guns.credit)로 기록해 처치 증강이 터진다.
 * 16 블록 안 사람은 화면이 흔들림. 가까운 사람은 폭발음, 먼 사람(48 블록 밖)은 먼 폭발음.
 * 날아가는 수류탄은 모드 모델(grenade_thrown, 실제 크기)을 진행 방향 옆 축으로 굴려 보인다.
 * <p>
 * F(손 바꾸기)는 여기서 받는다(우선순위 NORMAL, 이미 취소된 이벤트는 건너뜀): 소모품 담당이 F 로 든 소모품을 쓰려면 LOW 에서 받아
 * 쓰고 취소하면 수류탄이 나가지 않는다.
 */
public final class Grenades implements Listener {
    public static final int MAX = 2;
    private static final double RADIUS = 6, SPEED = 1.2, LIFT = 0.18, GRAVITY = 0.06, DRAG = 0.99;
    /** 거리 감쇠 피해: 가운데, 가장자리(RADIUS) */
    private static final double DMG_CENTER = 120, DMG_EDGE = 25;
    private static final int FUSE = 40;
    private static final float SCALE = 1.5f;
    private final GunHost host;
    private final Guns guns;
    private final ShotFx fx;
    private final List<Nade> nades = new ArrayList<>();
    private String lastLog = "없음";

    private static final class Nade {
        ItemDisplay d;
        Vector pos, vel, axis = new Vector(1, 0, 0);
        long boom, thrown;
        Player owner;
        Location from;
        float spin, spinRate = 0.45f;
        int bounces;
        double firstLand = -1;
        boolean resting;
    }

    public Grenades(GunHost host, Guns guns, ShotFx fx) {
        this.host = host;
        this.guns = guns;
        this.fx = fx;
        guns.link(this, fx);
    }

    public static ItemStack look() {
        return Props.vanillaLook(Material.PAPER, "firework_star", "grenade_thrown", Component.text("수류탄"));
    }

    private static float scale() {
        return Props.has("grenade_thrown") ? SCALE : 0.45f;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE) return;
        e.setCancelled(true);
        if (guns.arms(p) != null && host.running() && !host.blocked(p)) throwFrom(p);
    }

    /** 가진 수류탄 하나를 던진다(guns.arms(p).grenades 를 하나 씀). 없으면 아무것도 안 함 */
    public void throwFrom(Player p) {
        Guns.Arms a = guns.arms(p);
        if (a == null || a.grenades <= 0) return;
        a.grenades--;
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        Vector right = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1e-6) right = new Vector(1, 0, 0);
        right.normalize();
        Nade n = new Nade();
        n.owner = p;
        n.from = p.getLocation();
        // 오른손 앞에서 놓음. 그 자리가 벽 속이면 눈에서
        Vector start = eye.toVector().add(dir.clone().multiply(0.5)).add(right.clone().multiply(0.2)).add(new Vector(0, -0.12, 0));
        RayTraceResult block = p.getWorld().rayTraceBlocks(eye, start.clone().subtract(eye.toVector()).normalize(), 0.75, FluidCollisionMode.NEVER, true);
        n.pos = block == null ? start : eye.toVector();
        Vector inherit = p.getVelocity().clone().multiply(0.5).setY(0);
        n.vel = dir.clone().multiply(SPEED).add(new Vector(0, LIFT, 0)).add(inherit);
        n.axis = right.clone();
        n.boom = host.now() + FUSE;
        n.thrown = host.now();
        float s = scale();
        n.d = p.getWorld().spawn(n.pos.toLocation(p.getWorld()), ItemDisplay.class, d -> {
            d.setItemStack(look());
            d.setTeleportDuration(1);
            d.setInterpolationDuration(1);
            d.setPersistent(false);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(s), new AxisAngle4f()));
        });
        nades.add(n);
        Props.soundAround(eye, "grenade.pin", Sound.ENTITY_SNOWBALL_THROW, 1f, 1f, 1f, 16, -1);
        Props.soundAround(eye, null, Sound.ENTITY_SNOWBALL_THROW, 0.7f, 1f, 0.55f, 16, -1);
        Bukkit.getScheduler().runTaskLater(host.plugin(), () -> {
            if (n.d.isValid()) Props.soundAround(n.pos.toLocation(n.d.getWorld()), "grenade.spoon", null, 0.9f, 1f, 1f, 16, -1);
        }, 2);
    }

    public void tick() {
        long now = host.now();
        for (Iterator<Nade> it = nades.iterator(); it.hasNext();) {
            Nade n = it.next();
            if (!n.d.isValid()) {
                it.remove();
                continue;
            }
            n.vel.setY(n.vel.getY() - GRAVITY);
            n.vel.multiply(DRAG);
            double len = n.vel.length();
            if (len > 1e-4) {
                RayTraceResult r = n.d.getWorld().rayTraceBlocks(n.pos.toLocation(n.d.getWorld()), n.vel.clone().multiply(1 / len), len,
                    FluidCollisionMode.NEVER, true);
                if (r != null && r.getHitBlockFace() != null) {
                    Vector nrm = r.getHitBlockFace().getDirection();
                    double vn = n.vel.dot(nrm);
                    n.vel.subtract(nrm.clone().multiply(vn * 1.35)).multiply(0.62);
                    boolean floor = nrm.getY() > 0.7;
                    n.pos = r.getHitPosition().add(nrm.clone().multiply(floor ? 0.08 : 0.12));
                    if (floor && n.firstLand < 0) n.firstLand = Math.hypot(n.pos.getX() - n.from.getX(), n.pos.getZ() - n.from.getZ());
                    if (n.bounces++ < 6 && Math.abs(vn) > 0.1) Props.soundAround(n.pos.toLocation(n.d.getWorld()), "grenade.bounce",
                        Sound.BLOCK_METAL_HIT, (float) Math.min(1, 0.35 + Math.abs(vn)), 0.9f + (float) Math.random() * 0.2f, 1f, 24, -1);
                    if (floor && Math.abs(n.vel.getY()) < 0.09) n.vel.setY(0);
                    n.spinRate *= 0.6f;
                    n.resting = floor && n.vel.lengthSquared() < 0.0004;
                } else {
                    n.pos.add(n.vel);
                }
            }
            // 진행 방향 옆 축으로 굴림. 멈추면 옆으로 누운 자세
            Vector h = n.vel.clone().setY(0);
            if (h.lengthSquared() > 1e-4) n.axis = h.normalize().crossProduct(new Vector(0, 1, 0)).multiply(-1);
            n.spin += n.resting ? 0 : Math.max(0.05f, n.spinRate);
            Quaternionf q = new Quaternionf().rotateAxis(n.spin, (float) n.axis.getX(), (float) n.axis.getY(), (float) n.axis.getZ());
            if (n.resting) q = new Quaternionf().rotateY(n.spin).rotateZ((float) (Math.PI / 2));
            float s = scale();
            n.d.setInterpolationDelay(0);
            n.d.setInterpolationDuration(1);
            n.d.setTransformation(new Transformation(new Vector3f(), q, new Vector3f(s), new Quaternionf()));
            Location l = n.pos.toLocation(n.d.getWorld());
            if (n.resting) l.add(0, -0.03, 0);
            n.d.teleport(l);
            if (now >= n.boom) {
                explode(n);
                n.d.remove();
                it.remove();
            }
        }
    }

    /** 폭발 자리에서 d 블록인 대상이 받는 피해(가운데 120, RADIUS 에서 25, 그 사이 곧게) */
    static double falloff(double d) {
        double k = Math.max(0, Math.min(1, d / RADIUS));
        return DMG_CENTER - (DMG_CENTER - DMG_EDGE) * k;
    }

    private void explode(Nade n) {
        World w = n.d.getWorld();
        Location at = n.pos.toLocation(w);
        fx.explosion(at);
        // 가까우면 폭발음, 멀면 먼 폭발음(사람마다 따로). 바닐라 대체 소리는 음량 6, 12(들리는 거리 16 x 음량), 높이 0.95, 0.6.
        // 모드 먼 폭발은 음량 2/3: 클라 이득 = 음량 x (1 - 거리 / 감쇠 192) 라 48 블록에서 0.667 x 0.75 = 0.5 로 가까운 폭발(감쇠 96, 0.5)과 이어진다
        for (Player o : w.getPlayers()) {
            double d = o.getLocation().distance(at);
            if (d < 48) Props.soundTo(o, at, "grenade.explode", Sound.ENTITY_GENERIC_EXPLODE, Props.hasSound("grenade.explode") ? 1f : 6f, 1f, 0.95f, -1);
            else if (d < 192) Props.soundTo(o, at, "grenade.explode_far", Sound.ENTITY_GENERIC_EXPLODE, Props.hasSound("grenade.explode_far") ? 0.667f : 12f,
                1f, 0.6f, -1);
        }
        // 시야 검사: 폭발 자리 0.4 위에서 대상 몸 세 높이(중심 아래, 중심, 위)로 블록 광선. 하나라도 보이면 맞음, 가장 가까운 보인 점 거리로 감쇠
        Location eye0 = at.clone().add(0, 0.4, 0);
        Vector e0 = eye0.toVector();
        HitTargets t = host.targets();
        int hits = 0, kills = 0;
        // 판 크기와 웨이브를 조금 따라간다(게이브 체력 배율의 제곱근): 30명 판에서도 가운데 한 무리는 잡히게, 혼자 판에서는 그대로
        double scale = Math.sqrt(guns.crowdHp());
        for (Object o : t.inRadius(at, RADIUS + 1)) {
            Vector c = t.center(o);
            if (c == null) continue;
            double best = -1;
            for (double hgt : new double[] {-0.45, 0.0, 0.6}) {
                Vector sp = c.clone().add(new Vector(0, hgt, 0));
                double d = sp.distance(e0);
                if (d > RADIUS) continue;
                if (d < 0.5 || w.rayTraceBlocks(eye0, sp.clone().subtract(e0).normalize(), d - 0.2, FluidCollisionMode.NEVER, true) == null) {
                    if (best < 0 || d < best) best = d;
                }
            }
            if (best < 0) continue;
            Vector dir = c.clone().subtract(at.toVector());
            dir = dir.lengthSquared() < 1e-6 ? new Vector(0, 1, 0) : dir.normalize();
            hits++;
            double amount = falloff(best) * scale;
            boolean died = t.damage(o, new Damage(amount, DamageType.EXPLOSION, n.owner, c.clone(), dir, "grenade", 0));
            if (died) kills++;
            // 처치 셈, 처치 회복과 현상금 같은 증강 알림도 총과 같은 길로
            guns.credit(n.owner, o, amount, died, null, false);
        }
        // 흔들림: 16 블록 안(관전자 빼고)
        for (Player o : w.getPlayers()) {
            if (o.getGameMode() == GameMode.SPECTATOR) continue;
            double d = o.getLocation().distance(at);
            if (d < 16) shake(o, (float) (4.0 * (1 - d / 16)));
        }
        lastLog = String.format("첫착지=%.1f 터진자리=%.1f 튕김=%d 맞힘=%d 죽임=%d", n.firstLand, Math.hypot(at.getX() - n.from.getX(), at.getZ() - n.from.getZ()),
            n.bounces, hits, kills);
        host.plugin().getLogger().info("[수류탄] " + lastLog);
    }

    /** 폭발 흔들림: 6 틱에 걸쳐 줄어드는 좌우 위아래 떨림(합이 0 이라 끝나면 시선이 제자리) */
    private void shake(Player p, float amp) {
        if (amp < 0.3f) return;
        float[] k = {1f, -1.7f, 1.4f, -1.0f, 0.6f, -0.3f};
        for (int i = 0; i < k.length; i++) {
            float y = amp * k[i], pt = -amp * k[i] * 0.6f;
            Bukkit.getScheduler().runTaskLater(host.plugin(), () -> {
                if (p.isOnline()) Guns.kick(p, y, pt);
            }, i + 1);
        }
    }

    public void clear() {
        for (Nade n : nades) n.d.remove();
        nades.clear();
    }

    /** 날아가는 수류탄 수 */
    public int flying() {
        return nades.size();
    }

    /** 시험 훅(Guns.test 가 넘김): 수류탄기록 = 마지막 폭발의 첫 착지 거리, 터진 자리 거리, 튕긴 수, 맞힌 수, 죽인 수 */
    public String test(Player p, String[] a) {
        if (a.length > 1 && a[1].equals("수류탄기록")) return lastLog;
        return null;
    }
}
