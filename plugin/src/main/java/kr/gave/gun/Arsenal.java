package kr.gave.gun;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import kr.gave.Props;
import kr.gave.gun.Guns.Arms;
import kr.gave.gun.Guns.Fire;
import kr.gave.gun.Guns.Kind;
import kr.gave.gun.Guns.Mode;
import kr.gave.gun.Guns.Spec;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;

/**
 * 총 말고 다른 쏘는 방식(2026-11-12 무기 랜덤 상자, Guns.Fire): 불길과 냉기 원뿔(FLAME, FREEZE), 연쇄 번개(CHAIN), 모아 쏘는 관통 광선(CHARGE),
 * 날아가는 유탄과 로켓(LOB, ROCKET), 냉기 느려짐, 미니건 회전과 레일건 모으기 소리. Guns.fire 가 종류의 Mode 를 보고 한 발마다 부른다.
 * <p>
 * 피해는 host.targets() 로, 처치 셈은 Guns.count, 증강 알림은 Guns.hooks(대상 조건 배율, 처치 회복). 치명은 한 번 쏠 때 정한 확률(Guns 가 넘김).
 * 굴린 총 특성: 화염탄은 불붙음(불길은 1.5 배, 냉기와 번개, 폭발은 새로 붙임), 폭발탄은 폭발 반경 1.3 배(번개는 첫 대상 작은 폭발), 연쇄는 번개 +2 번,
 * 넉백은 밀침. 관통은 이 방식들에 뜻이 없어 굴릴 때 빠진다(Gun.Trait.fits).
 * <p>
 * 효과는 ShotFx 셰이더 모양(종류 15: 번개, 불길, 서리, 광선)과 폭발, 연기. 줄기와 번개, 로켓 꼬리는 사람 몫 예산(ShotFx.ownerCap)을 지켜 30명이 함께
 * 쏴도 효과 한도 50 안에서 모두 조금씩 보인다. 날아가는 탄 몸통은 모드 모델(소품 층 rocket_proj, grenade_thrown)이고 한 번에 {@link #MAX_SHELLS} 개까지.
 * 폭발은 사람을 다치게 하지 않는다(수류탄과 같음).
 */
final class Arsenal {
    /** 한 번에 날아가는 탄 한도: 넘치면 가장 오래된 것을 그 자리에서 터뜨림 */
    static final int MAX_SHELLS = 48;
    /** 냉기에 이만큼 넘게 이어 맞으면 얼어붙음(빠르기 배율) */
    static final double DEEP_MUL = 0.12;

    private final Guns guns;
    private final GunHost host;
    private final Random rnd = new Random();

    /** 날아가는 유탄, 로켓 하나 */
    private static final class Shell {
        Player by;
        Gun gun;
        Spec spec;
        Mode md;
        boolean rocket;
        Vector pos, vel;
        ItemDisplay d;
        int age;
        // trip_aug: 쪼개지는 수와 그 피해 몫, 지뢰로 기다리는 틱과 터지는 거리, 지뢰로 붙었나, 쪼개진 것인가
        int cluster;
        double clusterDmg, mineR;
        int mineTicks, mineMax;
        boolean armed, child;
    }

    private final List<Shell> shells = new ArrayList<>();

    /** 냉기 느려짐(게이브마다 하나): 끝 틱, 이어 맞은 틱 수, 마지막 맞은 틱, 빠르기 배율, 얼어붙는 틱 */
    private static final class Chill {
        long until, lastAt = -99, fxAt = -99;
        int hits, deep;
        double mul;
    }

    private final Map<Gabe, Chill> chills = new HashMap<>();
    /** 시험 셈(누적) */
    long streamHits, chainHits, railHits, blasts, launched;

    Arsenal(Guns guns, GunHost host) {
        this.guns = guns;
        this.host = host;
    }

    private ShotFx fx() {
        return guns.fx();
    }

    /** 1인칭 총구 근처(ShotFx.tracer 와 같은 자리: 앞 1.2, 오른쪽 0.36, 아래 0.26) */
    static Location muzzle(Location eye, Vector look) {
        Vector l = look.clone().normalize();
        Vector r = l.clone().crossProduct(new Vector(0, 1, 0));
        r = r.lengthSquared() < 1e-6 ? new Vector(1, 0, 0) : r.normalize();
        Vector up = r.clone().crossProduct(l).normalize();
        return eye.clone().add(l.multiply(1.0)).add(r.multiply(0.33)).add(up.multiply(-0.24));
    }

    private double hitMul(Player p, Object o, Spec s) {
        return Math.max(0, guns.hooks.hitMul(p, o, s));
    }

    private void hitBook(Player p, Arms a, Object o, double amount, boolean died, Gun gun) {
        a.hits++;
        a.hitAt = host.now();
        guns.count(a, died);
        guns.hooks.onHit(p, o, amount, died, gun);
    }

    // ------------------------------------------------------------------ 불길, 냉기

    /**
     * 불길, 냉기 한 번(한 발): 눈에서 dir 쪽 원뿔(반각 md.cone 도, 길이 s.range, 블록에 막히면 거기까지) 안 보이는 대상 모두에 s.damage.
     * 불길은 불붙음(초당 s.damage x md.effect, md.ticks), 냉기는 느려짐(md.effect 배, md.ticks, md.count 틱 넘게 이어 맞으면 얼어붙음)
     */
    void stream(Player p, Arms a, Spec s, Mode md, Location eye, Vector dir, Gun gun, double crit) {
        World w = p.getWorld();
        HitTargets t = host.targets();
        boolean fire = md.fire() == Fire.FLAME;
        Vector e = eye.toVector();
        RayTraceResult rb = w.rayTraceBlocks(eye, dir, s.range(), FluidCollisionMode.NEVER, true);
        double reach = rb == null ? s.range() : Math.max(0.8, rb.getHitPosition().distance(e));
        double cone = Math.toRadians(md.cone());
        if (t != null) for (Object o : t.inRadius(eye, reach + 1.2)) {
            // 거대한 몸(Gabe.big)은 줄기에 가장 가까운 구 표면 점으로 원뿔 각을 잰다(눈에서 가장 가까운 구가 원뿔 밖일 수 있음, 2026-11-12 sp_engine)
            Vector c = o instanceof Gabe bg && bg.big ? bg.game.mobs.aimPoint(bg, e, dir, reach + 0.9) : t.center(o);
            if (c == null) continue;
            Vector to = c.clone().subtract(e);
            double d = to.length();
            if (d > reach + 0.9 || d < 1e-3) continue;
            // 가까운 대상은 몸 반지름(0.7)만큼 원뿔이 넓은 셈
            if (to.angle(dir) > cone + Math.atan2(0.7, d)) continue;
            if (d > 1.0 && w.rayTraceBlocks(eye, to.clone().multiply(1 / d), d - 0.5, FluidCollisionMode.NEVER, true) != null) continue;
            double amount = s.damage() * hitMul(p, o, s);
            guns.critNow = crit > 0 && rnd.nextDouble() < crit;
            if (guns.critNow) amount *= 2;
            boolean died = t.damage(o, new Damage(amount, fire ? DamageType.FIRE : DamageType.OTHER, p, c.clone(), dir.clone(), s.key(), 0));
            hitBook(p, a, o, amount, died, gun);
            guns.critNow = false;
            streamHits++;
            if (died) continue;
            boolean inc = gun != null && gun.has(Gun.Trait.INCENDIARY);
            if (fire) guns.ignite(o, p, gun, s.damage() * md.effect() * (inc ? 1.5 : 1), md.ticks());
            else {
                chill(o, md);
                if (inc) guns.ignite(o, p, gun, Math.max(3, s.damage()), 40);
            }
            if (gun != null && gun.has(Gun.Trait.KNOCKBACK) && o instanceof Gabe g && rnd.nextInt(4) == 0) Guns.push(w, g, dir, 0.3);
        }
        // 효과: 불길은 사람마다 틱마다, 냉기는 2 틱에 한 덩이(총구에서 닿는 끝까지 날아가며 커짐). 사람 몫 예산이 많이 쏘는 때를 막음
        long now = host.now();
        // trip_aug: 줄기 끝 자리(불 벽)
        guns.hooks.streamEnd(p, s.kind(), eye.clone().add(dir.clone().multiply(reach)));
        if (now - a.streamFxAt >= (fire ? 1 : 2)) {
            a.streamFxAt = now;
            fx().jet(p.getUniqueId(), muzzle(eye, dir), dir, reach, fire ? ShotFx.V_FLAME : ShotFx.V_FROST);
        }
    }

    private void chill(Object o, Mode md) {
        if (!(o instanceof Gabe g)) return;
        long now = host.now();
        Chill c = chills.computeIfAbsent(g, x -> new Chill());
        long gap = now - c.lastAt;
        c.hits = gap <= 4 ? c.hits + (int) Math.max(1, gap) : Math.max(0, c.hits - 10);
        c.lastAt = now;
        c.mul = md.effect();
        c.deep = Math.max(1, md.count());
        c.until = Math.max(c.until, now + md.ticks());
    }

    /** 불길, 냉기 소리: 쏘기 시작하면 점화 소리, 이어 쏘는 동안 12 틱마다 줄기 소리를 이어 붙임 */
    void streamSound(Player p, Arms a, Spec s, Location eye, long now) {
        boolean fire = s.kind() == Kind.FLAME;
        if (now - a.streamStartAt > 6) Props.soundAround(eye, "gun." + s.key() + ".ignite", fire ? Sound.ITEM_FIRECHARGE_USE : Sound.BLOCK_POWDER_SNOW_PLACE,
            0.9f, 0.95f + rnd.nextFloat() * 0.1f, 1f, 24, -1);
        a.streamStartAt = now;
        if (now - a.streamSoundAt < 12) return;
        a.streamSoundAt = now;
        Props.soundAround(eye, s.fireEvent(), s.fire(), 1f, 0.96f + rnd.nextFloat() * 0.08f, s.pitch(), s.soundRange(), -1);
    }

    // ------------------------------------------------------------------ 번개

    /**
     * 연쇄 번개: s.range 안 첫 대상(블록에 막힘)에 s.damage, 그 대상에서 md.radius 안 가장 가까운 대상으로 md.count 번(연쇄 특성 +2) 번지며
     * 번질 때마다 md.effect 배. 대상이 없으면 허공에 짧은 방전. 첫 대상 자리를 돌려줌(스침 소리는 없음)
     */
    Location chain(Player p, Arms a, Spec s, Mode md, Location eye, Vector dir, Gun gun, double crit) {
        World w = p.getWorld();
        HitTargets t = host.targets();
        Vector e = eye.toVector();
        RayTraceResult rb = w.rayTraceBlocks(eye, dir, s.range(), FluidCollisionMode.NEVER, true);
        double blockAt = rb == null ? s.range() : rb.getHitPosition().distance(e);
        Hit h = t == null ? null : t.cast(p, e.clone(), dir.clone(), blockAt, new HashSet<>());
        Location m = muzzle(eye, dir);
        if (h == null || h.target() == null) {
            Location end = rb != null ? rb.getHitPosition().toLocation(w) : eye.clone().add(dir.clone().multiply(Math.min(s.range(), 9)));
            fx().bolt(m, end, p.getUniqueId());
            if (rb != null) fx().zap(end);
            return end;
        }
        Object cur = h.target();
        Vector curPos = h.pos() == null ? e.clone().add(dir.clone().multiply(h.dist())) : h.pos().clone();
        double dmg = s.damage() * hitMul(p, cur, s);
        guns.critNow = crit > 0 && rnd.nextDouble() < crit;
        if (guns.critNow) dmg *= 2;
        fx().bolt(m, curPos.toLocation(w), p.getUniqueId());
        zap(p, a, s, gun, cur, curPos, dir, dmg, true);
        guns.critNow = false;
        Set<Object> done = new HashSet<>();
        done.add(cur);
        int jumps = md.count() + (gun != null && gun.has(Gun.Trait.CHAIN) ? 2 : 0) + Math.max(0, guns.hooks.chainExtra(p));
        Vector from = t.center(cur);
        if (from == null) from = curPos;
        double jd = dmg;
        for (int j = 0; j < jumps; j++) {
            Object next = null;
            Vector nc = null;
            double best = md.radius() * md.radius();
            for (Object o : t.inRadius(from.toLocation(w), md.radius())) {
                if (done.contains(o)) continue;
                Vector c = t.center(o);
                if (c == null) continue;
                double dd = c.distanceSquared(from);
                if (dd >= best) continue;
                if (dd > 1 && w.rayTraceBlocks(from.toLocation(w), c.clone().subtract(from).normalize(), Math.sqrt(dd) - 0.3, FluidCollisionMode.NEVER,
                    true) != null) continue;
                best = dd;
                next = o;
                nc = c;
            }
            if (next == null) break;
            done.add(next);
            jd *= md.effect();
            fx().bolt(from.toLocation(w), nc.toLocation(w), p.getUniqueId());
            Vector jdir = nc.clone().subtract(from);
            jdir = jdir.lengthSquared() < 1e-6 ? dir.clone() : jdir.normalize();
            zap(p, a, s, gun, next, nc, jdir, jd, false);
            from = nc;
        }
        return curPos.toLocation(w);
    }

    private void zap(Player p, Arms a, Spec s, Gun gun, Object o, Vector at, Vector dir, double dmg, boolean first) {
        HitTargets t = host.targets();
        World w = p.getWorld();
        boolean died = t.damage(o, new Damage(dmg, DamageType.OTHER, p, at.clone(), dir.clone(), first ? s.key() : "chain", 0));
        hitBook(p, a, o, dmg, died, gun);
        chainHits++;
        fx().zap(at.toLocation(w));
        if (!died && gun != null && gun.has(Gun.Trait.INCENDIARY)) guns.ignite(o, p, gun, Math.max(4, s.damage() * 0.5), 60);
        if (!died && gun != null && gun.has(Gun.Trait.KNOCKBACK) && o instanceof Gabe g) Guns.push(w, g, dir, 0.35);
        if (first && gun != null && gun.has(Gun.Trait.EXPLOSIVE)) {
            fx().pop(at.toLocation(w));
            double pd = s.damage() * 0.45;
            for (Object x : t.inRadius(at.toLocation(w), 1.8)) {
                if (x == o) continue;
                boolean k = t.damage(x, new Damage(pd, DamageType.EXPLOSION, p, t.center(x), dir.clone(), "explosive", 0));
                hitBook(p, a, x, pd, k, gun);
            }
        }
    }

    // ------------------------------------------------------------------ 레일 광선

    /** 레일건 모으기 시작 소리 */
    void chargeStart(Player p, Spec s) {
        Props.soundAround(p.getEyeLocation(), "gun." + s.key() + ".charge", Sound.BLOCK_BEACON_POWER_SELECT, 0.9f, 1f, 1.6f, 24, -1);
    }

    /**
     * 레일 광선: 블록에 닿을 때까지 줄 위의 대상 모두(40 마리까지)에 s.damage(치명은 한 번 정해 모두에). 끝 자리를 돌려줌
     */
    Location rail(Player p, Arms a, Spec s, Mode md, Location eye, Vector dir, Gun gun, double crit) {
        World w = p.getWorld();
        HitTargets t = host.targets();
        Vector e = eye.toVector();
        RayTraceResult rb = w.rayTraceBlocks(eye, dir, s.range(), FluidCollisionMode.NEVER, true);
        double blockAt = rb == null ? s.range() : rb.getHitPosition().distance(e);
        Set<Object> hit = new HashSet<>();
        double mul = crit > 0 && rnd.nextDouble() < crit ? 2 : 1;
        int n = 0;
        while (t != null && n < 40) {
            Hit h = t.cast(p, e.clone(), dir.clone(), blockAt, hit);
            if (h == null || h.target() == null) break;
            hit.add(h.target());
            Vector pos = h.pos() == null ? e.clone().add(dir.clone().multiply(h.dist())) : h.pos().clone();
            guns.hitIndex = n;
            guns.critNow = mul > 1;
            double amount = s.damage() * hitMul(p, h.target(), s) * mul;
            boolean died = t.damage(h.target(), new Damage(amount, DamageType.SNIPER, p, pos.clone(), dir.clone(), s.key(), 0));
            hitBook(p, a, h.target(), amount, died, gun);
            guns.critNow = false;
            guns.hitIndex = 0;
            railHits++;
            fx().bloodHit(pos.toLocation(w), dir, n < 3);
            if (!died && gun != null && gun.has(Gun.Trait.INCENDIARY)) guns.ignite(h.target(), p, gun, Math.max(6, s.damage() * 0.1), 60);
            if (!died && gun != null && gun.has(Gun.Trait.KNOCKBACK) && h.target() instanceof Gabe g) Guns.push(w, g, dir, 0.6);
            n++;
        }
        Location end = rb != null ? rb.getHitPosition().toLocation(w) : eye.clone().add(dir.clone().multiply(s.range()));
        fx().beam(muzzle(eye, dir), end);
        if (rb != null) fx().hit(end, rb.getHitBlockFace(), rb.getHitBlock() == null ? Material.STONE : rb.getHitBlock().getType(), dir, true);
        if (gun != null && gun.has(Gun.Trait.EXPLOSIVE) && rb != null) fx().pop(end);
        return end;
    }

    // ------------------------------------------------------------------ 날아가는 탄(유탄, 로켓)

    /** 유탄(포물선), 로켓(곧게) 하나를 총구에서 dir 쪽으로 쏨 */
    void launch(Player p, Arms a, Spec s, Mode md, Location eye, Vector dir, Gun gun) {
        // 한도는 날아가는 것만 셈(trip_aug: 땅에 붙어 기다리는 지뢰는 사람마다 한도가 따로 있고 여기서 밀려 터지지 않게). 지뢰까지 합쳐 두 배면 가장 오래된 것부터
        int flying = 0;
        for (Shell x : shells) if (!x.armed) flying++;
        if (flying >= MAX_SHELLS || shells.size() >= MAX_SHELLS * 2) {
            Shell old = null;
            for (Shell x : shells) if (!x.armed || shells.size() >= MAX_SHELLS * 2) {
                old = x;
                break;
            }
            if (old != null) {
                shells.remove(old);
                blast(old, old.pos.clone());
            }
        }
        World w = p.getWorld();
        Shell sh = new Shell();
        sh.by = p;
        sh.gun = gun;
        sh.spec = s;
        sh.md = md;
        sh.rocket = md.fire() == Fire.ROCKET;
        Location m = muzzle(eye, dir);
        // 벽에 붙어 쏘면 총구가 벽 안: 눈에서 총구까지 막히면 눈 앞 0.3 에서 출발
        Vector em = m.toVector().subtract(eye.toVector());
        RayTraceResult blk = w.rayTraceBlocks(eye, em.clone().normalize(), em.length(), FluidCollisionMode.NEVER, true);
        sh.pos = (blk == null ? m : eye.clone().add(dir.clone().multiply(0.3))).toVector();
        sh.vel = dir.clone().normalize().multiply(md.speed());
        if (!sh.rocket) sh.vel.add(new Vector(0, 0.05, 0));
        // trip_aug: 쪼개지는 유탄, 지뢰(증강)
        double[] rule = guns.hooks.shellRule(p, s.kind());
        if (rule != null && rule.length >= 5) {
            sh.cluster = (int) rule[0];
            sh.clusterDmg = rule[1];
            sh.mineTicks = (int) rule[2];
            sh.mineMax = (int) rule[3];
            sh.mineR = rule[4];
        }
        sh.d = fx().shell(sh.pos.toLocation(w), sh.rocket ? "rocket_proj" : "grenade_thrown", sh.vel, md.ticks() + 5 + sh.mineTicks);
        shells.add(sh);
        launched++;
        if (sh.rocket) fx().trail(eye.clone().subtract(dir.clone().multiply(0.6)), p.getUniqueId());
    }

    /** 한 틱 움직임: 블록과 게이브(광선)에 닿으면 터짐. 끝났으면 true */
    private boolean step(Shell sh) {
        World w = host.world();
        sh.age++;
        if (sh.armed) return mineStep(sh);
        Vector from = sh.pos.clone();
        sh.vel.setY(sh.vel.getY() - sh.md.gravity());
        double len = sh.vel.length();
        if (len < 1e-4) {
            blast(sh, from);
            return true;
        }
        Vector dir = sh.vel.clone().multiply(1 / len);
        RayTraceResult rb = w.rayTraceBlocks(from.toLocation(w), dir, len, FluidCollisionMode.NEVER, true);
        double blockAt = rb == null ? len : rb.getHitPosition().distance(from);
        HitTargets t = host.targets();
        Hit h = t == null ? null : t.cast(null, from.clone(), dir.clone(), blockAt, Set.of());
        if (h != null && h.target() != null && h.dist() <= blockAt) {
            blast(sh, h.pos() == null ? from.clone().add(dir.clone().multiply(h.dist())) : h.pos().clone());
            return true;
        }
        if (rb != null) {
            Vector at = rb.getHitPosition().subtract(dir.clone().multiply(0.2));
            // trip_aug: 지뢰는 땅에 붙어 기다린다(한 사람 한도를 넘으면 가장 오래된 것이 터짐)
            if (sh.mineTicks > 0 && !sh.child) {
                arm(sh, at);
                return false;
            }
            blast(sh, at);
            return true;
        }
        sh.pos.add(sh.vel);
        if (sh.age > sh.md.ticks() || sh.pos.getY() < w.getMinHeight()) {
            blast(sh, sh.pos.clone());
            return true;
        }
        fx().moveShell(sh.d, sh.pos.toLocation(w), dir);
        if (sh.rocket && sh.age % 2 == 0) fx().trail(sh.pos.toLocation(w).subtract(dir.clone().multiply(0.55)), sh.by == null ? null : sh.by.getUniqueId());
        return false;
    }

    /**
     * 폭발(유탄, 로켓): 반경 md.radius(폭발탄 특성 1.3 배) 안 보이는 대상에 가운데 s.damage, 가장자리 md.effect 몫(그 사이 곧게). 사람은 다치지 않음.
     * 로켓은 수류탄 같은 큰 폭발 연출, 유탄은 작은 폭발. 가까우면 폭발음, 멀면 먼 폭발음, 16 블록 안 흔들림
     */
    private void blast(Shell sh, Vector at) {
        World w = host.world();
        Location loc = at.toLocation(w);
        fx().end(sh.d);
        Spec s = sh.spec;
        Mode md = sh.md;
        Gun gun = sh.gun;
        double r = md.radius() * (gun != null && gun.has(Gun.Trait.EXPLOSIVE) ? 1.3 : 1) * (sh.by == null ? 1 : Math.max(0.1, guns.hooks.blastMul(sh.by, s.kind())));
        if (sh.cluster > 0 && !sh.child) scatter(sh, at);
        if (sh.rocket) fx().explosion(loc);
        else fx().blast(loc, (float) r);
        blasts++;
        String ev = "gun." + s.key() + ".explode";
        for (Player o : w.getPlayers()) {
            double d = o.getLocation().distance(loc);
            if (d < 48) Props.soundTo(o, loc, ev, Sound.ENTITY_GENERIC_EXPLODE, Props.hasSound(ev) ? 1f : 6f, 0.95f + rnd.nextFloat() * 0.1f,
                sh.rocket ? 0.85f : 1.05f, -1);
            else if (d < 192) Props.soundTo(o, loc, "grenade.explode_far", Sound.ENTITY_GENERIC_EXPLODE, Props.hasSound("grenade.explode_far") ? 0.667f : 12f,
                1f, 0.6f, -1);
            if (o.getGameMode() != GameMode.SPECTATOR && d < 16) {
                float k = (float) ((sh.rocket ? 5 : 3.2) * (1 - d / 16));
                Guns.kick(o, (float) (rnd.nextGaussian() * k * 0.5), (float) (-Math.abs(rnd.nextGaussian()) * k * 0.6));
            }
        }
        HitTargets t = host.targets();
        if (t == null) return;
        Arms a = sh.by == null ? null : guns.arms(sh.by);
        double crit = a == null ? 0 : guns.crit(sh.by, s.kind());
        double mul = crit > 0 && rnd.nextDouble() < crit ? 2 : 1;
        guns.critNow = mul > 1;
        Location eye0 = loc.clone().add(0, 0.4, 0);
        Vector e0 = eye0.toVector();
        for (Object o : t.inRadius(loc, r + 1)) {
            Vector c = t.center(o);
            if (c == null) continue;
            double best = -1;
            for (double hgt : new double[] {-0.45, 0.0, 0.6}) {
                Vector sp = c.clone().add(new Vector(0, hgt, 0));
                double d = sp.distance(e0);
                if (d > r) continue;
                if (d < 0.6 || w.rayTraceBlocks(eye0, sp.clone().subtract(e0).normalize(), d - 0.2, FluidCollisionMode.NEVER, true) == null)
                    if (best < 0 || d < best) best = d;
            }
            if (best < 0) continue;
            Vector dir = c.clone().subtract(at);
            dir = dir.lengthSquared() < 1e-6 ? new Vector(0, 1, 0) : dir.normalize();
            double k = Math.min(1, best / r);
            double amount = s.damage() * (1 - (1 - md.effect()) * k) * mul * (sh.by == null ? 1 : hitMul(sh.by, o, s));
            boolean died = t.damage(o, new Damage(amount, DamageType.EXPLOSION, sh.by, c.clone(), dir, s.key(), 0));
            if (a != null) hitBook(sh.by, a, o, amount, died, gun);
            if (died) continue;
            if (gun != null && gun.has(Gun.Trait.INCENDIARY)) guns.ignite(o, sh.by, gun, Math.max(6, s.damage() * 0.12), 60);
            if (o instanceof Gabe g && (sh.rocket || gun != null && gun.has(Gun.Trait.KNOCKBACK))) Guns.push(w, g, dir, (sh.rocket ? 1.4 : 0.8) * (1 - k));
        }
        guns.critNow = false;
    }

    // ------------------------------------------------------------------ trip_aug: 지뢰, 쪼개짐

    /** 지뢰로 붙음: 그 자리에 멈춰 기다린다. 같은 사람 지뢰가 한도를 넘으면 가장 오래된 것부터 터뜨림(틱 중 목록을 고치지 않게 나이를 채워 둠) */
    private void arm(Shell sh, Vector at) {
        sh.armed = true;
        sh.age = 0;
        sh.pos = at.clone();
        sh.vel = new Vector();
        fx().moveShell(sh.d, at.toLocation(host.world()), new Vector(0, 1, 0));
        Props.soundAround(at.toLocation(host.world()), null, Sound.BLOCK_TRIPWIRE_CLICK_ON, 0.8f, 1f, 1.4f, 12, -1);
        int mine = 0;
        Shell oldest = null;
        for (Shell o : shells) {
            if (!o.armed || o.by != sh.by) continue;
            mine++;
            if (o != sh && (oldest == null || o.age > oldest.age)) oldest = o;
        }
        if (sh.mineMax > 0 && mine > sh.mineMax && oldest != null) oldest.age = oldest.mineTicks + 1;
    }

    /** 붙은 지뢰 한 틱: 게이브가 mineR 안에 오거나 시간이 다하면 터짐 */
    private boolean mineStep(Shell sh) {
        HitTargets t = host.targets();
        Location loc = sh.pos.toLocation(host.world());
        boolean near = sh.age > 6 && t != null && sh.age % 2 == 0 && !t.inRadius(loc, sh.mineR).isEmpty();
        if (near || sh.age > sh.mineTicks) {
            blast(sh, sh.pos.clone());
            return true;
        }
        return false;
    }

    /** 터지며 작은 탄 cluster 개를 사방 위로 뿌림(피해 clusterDmg 몫, 반경 0.7 배, 다시 쪼개지지 않음). 틱 중이라 목록 끝에 더하지 않고 따로 모았다가 넣음 */
    private void scatter(Shell sh, Vector at) {
        World w = host.world();
        Spec small = sh.spec.with(GunMods.NONE.mulDamage(Math.max(0.05, sh.clusterDmg)));
        Mode md = new Mode(Fire.LOB, 0, 30, 0, sh.md.radius() * 0.7, 0.45, 0.06, sh.md.effect());
        double a0 = rnd.nextDouble() * Math.PI * 2;
        for (int i = 0; i < sh.cluster && shells.size() + born.size() < MAX_SHELLS; i++) {
            double ang = a0 + i * Math.PI * 2 / sh.cluster;
            Shell c = new Shell();
            c.by = sh.by;
            c.gun = sh.gun;
            c.spec = small;
            c.md = md;
            c.child = true;
            c.pos = at.clone().add(new Vector(0, 0.35, 0));
            c.vel = new Vector(Math.cos(ang) * 0.32, 0.36, Math.sin(ang) * 0.32);
            c.d = fx().shell(c.pos.toLocation(w), "grenade_thrown", c.vel, md.ticks() + 5);
            born.add(c);
        }
    }

    /** 이 틱에 새로 생긴 쪼개진 탄(틱이 끝나면 목록에 넣음) */
    private final List<Shell> born = new ArrayList<>();

    /** 지금 냉기에 걸려 있나 */
    boolean chilledOn(Object o) {
        return o instanceof Gabe g && chills.containsKey(g);
    }

    // ------------------------------------------------------------------ 틱, 그 밖

    /** 날아가는 탄을 움직이고 냉기 느려짐을 건다(Guns.tick 이 몹 다음에 부름: 다음 몹 틱의 빠르기에 걸림) */
    void tick(long now) {
        for (Iterator<Shell> it = shells.iterator(); it.hasNext();) {
            Shell sh = it.next();
            boolean done;
            try {
                done = step(sh);
            } catch (RuntimeException ex) {
                host.plugin().getLogger().warning("[총] 날아가는 탄 오류: " + ex);
                fx().end(sh.d);
                done = true;
            }
            if (done) it.remove();
        }
        if (!born.isEmpty()) {
            shells.addAll(born);
            born.clear();
        }
        for (Iterator<Map.Entry<Gabe, Chill>> it = chills.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Gabe, Chill> e = it.next();
            Gabe g = e.getKey();
            Chill c = e.getValue();
            if (g.dead || g.removed || now > c.until) {
                it.remove();
                continue;
            }
            boolean deep = c.hits >= c.deep && now - c.lastAt <= 20;
            g.speedBuff *= deep ? DEEP_MUL : c.mul;
            if (now - c.fxAt >= (deep ? 8 : 16)) {
                c.fxAt = now;
                fx().frost(g.loc().add(0, 0.2, 0));
            }
        }
    }

    /** 방아쇠를 놓은 틱: 미니건 총열이 천천히 멈추고 레일건 모으기가 풀림 */
    void idle(Player p, Arms a, Spec s, long now) {
        Mode md = Guns.mode(s.kind());
        if (a.spin > 0 && !guns.hooks.keepSpin(p)) a.spin = Math.max(0, a.spin - 1.0 / Math.max(1, md.ticks() * 0.75));
        if (a.chargeAt >= 0) a.chargeAt = -1;
    }

    /** 미니건 회전 소리(누르는 동안 10 틱마다, 돌수록 높게) */
    void spinSound(Player p, Arms a, long now) {
        if (now - a.spinSoundAt < 10) return;
        a.spinSoundAt = now;
        Props.soundAround(p.getEyeLocation(), "gun.minigun.spin", Sound.BLOCK_BEACON_AMBIENT, 0.8f, (float) (0.8 + 0.4 * a.spin), 1.8f, 32, -1);
    }

    /** 지금 얼어붙거나 느려진 수, 날아가는 탄 수 */
    int chilled() {
        return chills.size();
    }

    int flying() {
        return shells.size();
    }

    void clear() {
        for (Shell sh : shells) fx().end(sh.d);
        for (Shell sh : born) fx().end(sh.d);
        born.clear();
        shells.clear();
        chills.clear();
    }

    /** 시험 훅: 무기목록, 무기상태 &lt;이름&gt;, 날아가는탄, 얼림 &lt;이름&gt; &lt;틱&gt;(가까운 게이브를 얼림) */
    String test(Player p, String[] a) {
        if (a.length < 2) return null;
        switch (a[1]) {
            case "무기목록" -> {
                StringBuilder sb = new StringBuilder();
                for (Kind k : Kind.values()) {
                    Spec s = Guns.SPECS.get(k);
                    sb.append(k.name()).append('(').append(s.key()).append(' ').append(Guns.mode(k).fire()).append(String.format(Locale.ROOT,
                        " dmg=%.0f rps=%.1f mag=%d) ", s.damage(), s.rps(), s.mag()));
                }
                return sb.toString().trim();
            }
            case "무기상태" -> {
                if (p == null) return "없음";
                Arms ar = guns.arms(p);
                if (ar == null) return "총 없음";
                StringBuilder ch = new StringBuilder();
                for (Map.Entry<Gabe, Chill> e : chills.entrySet())
                    ch.append(String.format(Locale.ROOT, " %s#%d:buff=%.2f,hits=%d", e.getKey().sp.name(), e.getKey().id, e.getKey().speedBuff, e.getValue().hits));
                return String.format(Locale.ROOT, "spin=%.2f charge=%.2f burstLeft=%d shells=%d chilled=%d burning=%d hits(stream=%d chain=%d rail=%d) blasts=%d launched=%d",
                    ar.spin, guns.charge(p), ar.burstLeft, shells.size(), chills.size(), guns.burning(), streamHits, chainHits, railHits, blasts, launched) + ch;
            }
            case "효과시험" -> {
                // 효과시험 <이름> <변형 0 번개 | 1 불길 | 2 서리 | 3 광선> [틱]: 눈 앞으로 2 틱마다 그 효과를 낳음(셰이더 모양만 봄)
                if (p == null) return "없음";
                int v = a.length > 3 ? (int) Guns.parse(a[3]) : 1;
                int ticks = a.length > 4 ? (int) Guns.parse(a[4]) : 60;
                Location eye = p.getEyeLocation();
                Vector dir = eye.getDirection();
                int[] made = {0};
                org.bukkit.scheduler.BukkitTask[] task = {null};
                task[0] = org.bukkit.Bukkit.getScheduler().runTaskTimer(host.plugin(), () -> {
                    if (made[0]++ * 2 >= ticks || !p.isOnline()) {
                        task[0].cancel();
                        return;
                    }
                    Location m = muzzle(eye, dir);
                    switch (v) {
                        case 0 -> fx().bolt(m, eye.clone().add(dir.clone().multiply(8)), p.getUniqueId());
                        case 3 -> fx().beam(m, eye.clone().add(dir.clone().multiply(30)));
                        default -> fx().jet(p.getUniqueId(), m, dir, 8, v);
                    }
                }, 1, 2);
                return "ok variant=" + v + " ticks=" + ticks + " live=" + fx().used();
            }
            case "날아가는탄" -> {
                StringBuilder sb = new StringBuilder("shells=" + shells.size());
                for (Shell sh : shells)
                    sb.append(String.format(Locale.ROOT, " [%s %.1f,%.1f,%.1f age=%d]", sh.spec.key(), sh.pos.getX(), sh.pos.getY(), sh.pos.getZ(), sh.age));
                return sb.toString();
            }
            default -> {
                return null;
            }
        }
    }
}
