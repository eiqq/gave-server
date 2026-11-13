package kr.gave.mob.sp;

import java.util.List;

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
import kr.gave.gun.Guns;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.LookState;
import kr.gave.mob.Role;
import kr.gave.world.Plaza;

/**
 * 묶음 9(땅의 거인 2: G115 G125 G129 G130 G116 G100 G131)가 같이 쓰는 도우미. 공용 엔진이 아니라 이 묶음 종 파일 안의 것이다.
 *
 * <ul>
 * <li>{@link #at}, {@link #fwd}, {@link #left}, {@link #toLocal}: 몸 틀(+X 몸 왼쪽, +Y 위, +Z 앞)과 세계 사이.</li>
 * <li>{@link #gesture}: 상태를 켜며 손으로 짠 자세가 0 부터 돌게 look.phase 를 맞춤.</li>
 * <li>{@link #stomp}, {@link #ring}, {@link #wave}, {@link #warn}, {@link #plate}, {@link #strip}: 거인 걸음 소리와 화면 끄덕, 바닥 고리,
 * 퍼지는 충격파, 빨간 예고 고리, 보스 공용 판 모델(그림자, 예고 원, 예고 띠: tools/gabe/sp/b1.py)을 연출 예산(ShotFx) 안에서. 발광 상자 아님.</li>
 * <li>{@link #crush}: 거인 발밑의 값싼 게이브(떼, 점수 2 이하)를 보상 없이 밟아 죽임(보스, 다른 거인, 자신 빼고).</li>
 * <li>{@link Legs}: 부품 둘로 그리는 곧은 다리(장대, 뼈 막대기, 헝겊 다리). 발은 세계에 디디고, 몸이 앞으로 가면 뒤처진 발부터 한 발씩 들어 앞으로 옮긴다.
 * 다리 부품은 follow = false(세계 축)로 엉덩이에서 발까지 돌리고 길이를 맞춰 늘인다.</li>
 * </ul>
 */
final class G129Kit {

    private G129Kit() {
    }

    // ------------------------------------------------------------------ 몸 틀

    /** 몸 틀 점 → 세계 점(yaw 만). */
    static Vector at(Gabe g, double x, double y, double z) {
        double r = Math.toRadians(g.yaw);
        double fx = -Math.sin(r), fz = Math.cos(r);
        return new Vector(g.pos.getX() + fz * x + fx * z, g.pos.getY() + y, g.pos.getZ() - fx * x + fz * z);
    }

    static Vector fwd(Gabe g) {
        double r = Math.toRadians(g.yaw);
        return new Vector(-Math.sin(r), 0, Math.cos(r));
    }

    static Vector left(Gabe g) {
        double r = Math.toRadians(g.yaw);
        return new Vector(Math.cos(r), 0, Math.sin(r));
    }

    /** 세계 점 → 몸 틀 점(yaw 만). */
    static Vector toLocal(Gabe g, Vector w) {
        double r = Math.toRadians(g.yaw);
        double dx = w.getX() - g.pos.getX(), dz = w.getZ() - g.pos.getZ();
        return new Vector(dx * Math.cos(r) + dz * Math.sin(r), w.getY() - g.pos.getY(), -dx * Math.sin(r) + dz * Math.cos(r));
    }

    /** 수평 거리. */
    static double flat(Vector a, Vector b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    static double flat(Location a, Vector b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    /** 바닥 높이로 내린 사본. */
    static Vector ground(Gabe g, Vector v) {
        Vector o = v.clone();
        o.setY(g.game.mobs.flow.floor(o.getX(), o.getZ()));
        return o;
    }

    /** at 쪽을 보게 yaw 만 돌림(pitch 0: Gabe.face 는 높이 차로 pitch 를 넣어 거인 몸 전체가 숙여짐). */
    static void faceFlat(Gabe g, Vector at) {
        double dx = at.getX() - g.pos.getX(), dz = at.getZ() - g.pos.getZ();
        if (dx * dx + dz * dz < 1e-8) return;
        g.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        g.pitch = 0;
    }

    /** 큰 몸의 아래쪽 면(올려다볼 때 보이는 그늘 면)이 너무 어둡지 않게 닻과 부품 밝기를 (13, 10)으로(보스 B2 와 같은 값). 부품을 다 붙인 뒤 부름. */
    static void bright(Body b) {
        if (b == null) return;
        org.bukkit.entity.Display.Brightness br = new org.bukkit.entity.Display.Brightness(13, 10);
        if (b.anchor != null) b.anchor.setBrightness(br);
        for (ItemDisplay d : b.parts) if (d != null && d.isValid()) d.setBrightness(br);
    }

    /** 몸 방향 yaw(도, 마크 약속: 0 = +Z, 90 = -X)로 dir(수평)을 보는 값. */
    static float yawOf(Vector dir) {
        return (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
    }

    /**
     * 상태 st(주기 period 틱, 자세 n)를 켜고 지금 틱이 자세 0 의 첫머리가 되게 위상을 맞춘다(셰이더: 자세 = floor(((T + ph16 x P / 16) mod P) x n / P),
     * ph16 = floorMod(phase, P) x 16 / P).
     */
    static void gesture(Gabe g, String st, int period, int n) {
        g.state(st);
        if (g.body == null) return;
        g.body.look.rate = 1.0;
        g.body.look.freeze = false;
        long t = Math.floorMod(g.game.worldTime(), 24000L);
        int best = 0;
        double bestRes = Double.MAX_VALUE;
        for (int ph = 0; ph < period; ph++) {
            int q = Math.floorMod(ph, period) * 16 / period;
            double m = ((t + q * period / 16.0) % period + period) % period;
            double res = m < period / (double) n ? m : 1e6 + m;
            if (res < bestRes) {
                bestRes = res;
                best = ph;
            }
        }
        g.body.look.phase = best;
    }

    /** 지금 틱에 셰이더가 그리는 자세 번호(빠르기 배율 1). */
    static int frame(Gabe g, int period, int n) {
        long t = Math.floorMod(g.game.worldTime(), 24000L);
        int q = Math.floorMod(g.body == null ? 0 : g.body.look.phase, period) * 16 / period;
        double m = ((t + q * period / 16.0) % period + period) % period;
        return Math.min(n - 1, (int) Math.floor(m * n / period));
    }

    // ------------------------------------------------------------------ 연출

    /** 거인 걸음 한 번: 공용 sfx.stomp, 발밑 흙 고리, kickR 안 사람 화면 끄덕. */
    static void stomp(Gabe g, Vector foot, double range, double kickR, float volume) {
        if (!g.game.director.silent())
            Props.soundAround(new Location(g.game.world, foot.getX(), foot.getY(), foot.getZ()), "sfx.stomp", Sound.ENTITY_RAVAGER_STEP, volume,
                0.85f + g.game.random.nextFloat() * 0.2f, 0.6f, range, -1);
        ring(g, foot, 1.8, 10);
        double r2 = kickR * kickR;
        for (Player p : g.game.mobs.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - foot.getX(), dz = l.getZ() - foot.getZ();
            if (dx * dx + dz * dz > r2) continue;
            // 2026-11-12 사용자 '시야 각도를 바꾸는 게이브는 안 나오게': 거인 걸음 화면 끄덕 뺌(거인은 그대로 나옴)
        }
    }

    /** 이 종 소리를 몸이 아닌 자리에서(gave:gabe.gxx.이름). */
    static void soundAt(Gabe g, Vector at, String name, Sound fallback, float volume, double range) {
        if (g.game.director.silent()) return;
        Props.soundAround(new Location(g.game.world, at.getX(), at.getY(), at.getZ()), "gabe." + g.sp.key() + "." + name, fallback, volume,
            0.94f + g.game.random.nextFloat() * 0.12f, 0.7f, range, -1);
    }

    /** 바닥 흙 고리(충격파 셰이더 고리, 종류 RING): 반지름 r, life 틱. 예산이 차면 안 그림. */
    static ItemDisplay ring(Gabe g, Vector c, double r, int life) {
        if (!g.game.shotFx.room(ShotFx.EXTRA)) return null;
        double y = g.game.mobs.flow.floor(c.getX(), c.getZ()) + 0.06;
        ItemDisplay d = g.game.shotFx.spawn(new Location(g.game.world, c.getX(), y, c.getZ()), "fx_flat", ShotFx.RING, 0, ShotFx.scale((float) (r * 2.5)),
            Display.Billboard.FIXED, false, life, ShotFx.EXTRA);
        box(d, r * 2.5);
        return d;
    }

    /** 바닥을 기어 퍼지는 흰 충격파(r0 에서 r1 까지 ticks 동안): 뛰어 넘는 고리. */
    static void wave(Gabe g, Vector c, double r0, double r1, int ticks) {
        double y = g.game.mobs.flow.floor(c.getX(), c.getZ()) + 0.08;
        ItemDisplay d = g.game.shotFx.spawn(new Location(g.game.world, c.getX(), y, c.getZ()), "fx_flat", ShotFx.RING, 0,
            ShotFx.scale((float) (Math.max(0.5, r0) * 2.5)), Display.Billboard.FIXED, true, ticks + 6, ShotFx.KEY);
        box(d, r1 * 2.5);
        g.game.shotFx.grow(d, ShotFx.scale((float) (r1 * 2.5)), ticks);
    }

    /** 빨간 예고 고리(가득 찬 쓰러짐 고리): 반지름 r, life 틱. */
    static ItemDisplay warn(Gabe g, Vector c, double r, int life) {
        double y = g.game.mobs.flow.floor(c.getX(), c.getZ()) + 0.07;
        ItemDisplay d = g.game.shotFx.spawn(new Location(g.game.world, c.getX(), y, c.getZ()), "fx_flat", ShotFx.DOWN_RING, 0,
            ShotFx.scale((float) (r * 2.5)), Display.Billboard.FIXED, false, life, ShotFx.KEY);
        box(d, r * 2.5);
        return d;
    }

    static void box(ItemDisplay d, double size) {
        if (d == null) return;
        d.setDisplayWidth((float) Math.max(2, size * 1.5));
        d.setDisplayHeight(4f);
    }

    /**
     * 보스 공용 판 모델 하나(gabe/b1_shadow, b1_warn_disc 같은 바닥에 눕는 반지름 1 판)를 반지름 r 로 바닥에 깐다(연출 예산 KEY). 팩에 그 모델이 없으면
     * 빨간 예고 고리로 대신. grow > 0 이면 r x 0.35 에서 시작해 grow 틱 동안 r 까지 자람.
     */
    static ItemDisplay plate(Gabe g, String model, String state, Vector c, double r, int life, int grow) {
        if (!Look.has(model)) return warn(g, c, r, life);
        double y = g.game.mobs.flow.floor(c.getX(), c.getZ()) + 0.07;
        float r0 = (float) (grow > 0 ? r * 0.35 : r);
        ItemDisplay d = g.game.shotFx.spawn(new Location(g.game.world, c.getX(), y, c.getZ()), "fx_flat", 0, 0, flatM(r0, r0), Display.Billboard.FIXED,
            false, life, ShotFx.KEY);
        if (d == null) return null;
        LookState ls = new LookState();
        ls.freeze = true;
        d.setItemStack(Body.item(model, state, ls, g.game.worldTime()));
        d.setBrightness(Body.LIGHT);
        d.setViewRange(3f);
        box(d, r * 2);
        if (grow > 0) g.game.shotFx.grow(d, flatM((float) r, (float) r), grow);
        return d;
    }

    /** 판 모델의 상태를 바꿈(그림자 짙어짐 같은 것). */
    static void plateState(Gabe g, ItemDisplay d, String model, String state) {
        if (d == null || !d.isValid() || !Look.has(model)) return;
        LookState ls = new LookState();
        ls.freeze = true;
        d.setItemStack(Body.item(model, state, ls, g.game.worldTime()));
    }

    /** 예고 띠(gabe/b1_warn_strip: 폭 1, 길이 1, 뒤 끝에서 +Z 로)를 from 에서 to 까지 폭 width 로. fill 이면 뒤 끝에서 차오름(ticks 동안). */
    static ItemDisplay strip(Gabe g, Vector from, Vector to, double width, int life, boolean fill) {
        String m = "gabe/b1_warn_strip";
        double len = flat(from, to);
        if (!Look.has(m) || len < 0.5) {
            Vector mid = from.clone().add(to).multiply(0.5);
            return warn(g, mid, Math.max(width, len / 2), life);
        }
        double y = g.game.mobs.flow.floor(from.getX(), from.getZ()) + 0.07;
        double yaw = Math.atan2(to.getX() - from.getX(), to.getZ() - from.getZ());
        Matrix4f base = new Matrix4f().rotateY((float) yaw).scale((float) width, 1, (float) len).rotateY((float) Math.PI);
        ItemDisplay d = g.game.shotFx.spawn(new Location(g.game.world, from.getX(), y, from.getZ()), "fx_flat", 0, 0, base, Display.Billboard.FIXED,
            false, life, ShotFx.KEY);
        if (d == null) return null;
        LookState ls = new LookState();
        ls.freeze = true;
        d.setItemStack(Body.item(m, "", ls, g.game.worldTime()));
        d.setBrightness(Body.LIGHT);
        d.setViewRange(3f);
        box(d, len * 2);
        if (fill) {
            ItemDisplay in = g.game.shotFx.spawn(new Location(g.game.world, from.getX(), y + 0.01, from.getZ()), "fx_flat", 0, 0,
                new Matrix4f().rotateY((float) yaw).scale((float) width, 1, 0.05f).rotateY((float) Math.PI), Display.Billboard.FIXED, false, life,
                ShotFx.KEY);
            if (in != null) {
                in.setItemStack(Body.item(m, "fill", ls, g.game.worldTime()));
                in.setBrightness(Body.LIGHT);
                in.setViewRange(3f);
                box(in, len * 2);
                g.game.shotFx.grow(in, base, Math.max(2, life - 4));
            }
        }
        return d;
    }

    static Matrix4f flatM(float sx, float sz) {
        return new Matrix4f().scale(sx, 1, sz).rotateY((float) Math.PI);
    }

    /** 물웅덩이 물결(거대한 것이 다가올 때): 몸에서 range 안 웅덩이마다 고리 하나. */
    static void ripples(Gabe g, double range) {
        for (Vector w : Plaza.puddles()) {
            if (flat(w, g.pos) > range) continue;
            ring(g, w, 1.2 + g.game.random.nextDouble(), 14);
        }
    }

    // ------------------------------------------------------------------ 밟기

    /** 밟아 죽여도 되는 값싼 게이브인가(떼, 점수 2 이하, 보스와 다른 거인과 자신 빼고). */
    static boolean cheap(Gabe self, Gabe o) {
        if (o == self || o.dead || o.untargetable || o.disguised || o.sp.is(Role.BOSS) || o.big) return false;
        return o.sp.is(Role.SWARM) || o.sp.cost <= 2 || o.maxHp <= 60;
    }

    /**
     * 발밑(at 둘레 r) 값싼 게이브를 보상 없이 밟아 죽임(래그돌로 튐). bonus 가 있으면 그 사람에게 밟힌 것 보상의 몫 share 를 줌(G130). 죽인 수.
     */
    static int crush(Gabe self, Vector at, double r, Player bonus, double share) {
        int n = 0;
        List<Gabe> hit = self.game.mobs.near(at, r + 1.0, o -> cheap(self, o) && flat(o.pos, at) <= r + o.radius);
        for (Gabe o : hit) {
            int rew = o.reward;
            o.noReward = true;
            Vector dir = o.pos.clone().subtract(at).setY(0);
            if (dir.lengthSquared() < 1e-6) dir = new Vector(1, 0, 0);
            o.damage(new Damage(Math.max(o.hp, 1) * 4 + 50, DamageType.EXPLOSION, null, o.pos.clone(), dir.normalize(), "g129", 0));
            if (o.dead) {
                n++;
                if (bonus != null && share > 0 && rew > 0) self.game.players.addMoney(bonus, (int) Math.max(1, Math.round(rew * share)));
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ 다리

    /** 발이 땅에 닿음. */
    interface Land {
        void landed(int leg, Vector foot);
    }

    /** 발을 들어 옮기기 시작함(디딜 자리를 알림: 그림자 같은 예고). */
    interface Lift {
        void lifted(int leg, Vector to, int ticks);
    }

    /**
     * 부품 둘로 그리는 곧은 다리. 다리 모델은 엉덩이가 (0, 0, 0), 발이 (0, -len, 0) 인 모델(앞 +Z). 엉덩이 자리는 몸 틀 (hipX[i], hipY, hipZ).
     * 몸이 움직이면 엉덩이 아래 쉼 자리에서 reach 넘게 벌어진 발(다른 발이 디딘 채일 때)을 swing 틱 동안 lift 높이로 들어 쉼 자리 + 가는 쪽 lead 로 옮긴다.
     */
    static final class Legs {
        final Gabe g;
        final double len;
        final double[] hipX;
        double hipY, hipZ;
        final Body.Part[] part;
        final Vector[] foot, from, to;
        final int[] swingAt;
        final int[] swingLen;
        int swing = 20;
        double lift = 1.2, reach = 2.5, lead = 2.5, stance = 0.4;
        /** 발이 디딘 채 고정(무릎 꿇음 같은 몸짓): Java 가 foot 을 직접 정함. */
        boolean pinned;
        Land land;
        Lift lifted;
        private int posedAt = -100;
        private final Vector lastPos = new Vector();
        private float lastYaw, bodyYaw;
        private boolean ready;
        int steps;

        Legs(Gabe g, String model, double len, double[] hipX, double hipY, double hipZ) {
            this.g = g;
            this.len = len;
            this.hipX = hipX;
            this.hipY = hipY;
            this.hipZ = hipZ;
            int n = hipX.length;
            part = new Body.Part[n];
            foot = new Vector[n];
            from = new Vector[n];
            to = new Vector[n];
            swingAt = new int[n];
            swingLen = new int[n];
            for (int i = 0; i < n; i++) {
                foot[i] = new Vector();
                from[i] = new Vector();
                to[i] = new Vector();
                swingAt[i] = -1;
                part[i] = g.body.attach(model);
                if (part[i] != null) part[i].follow = false;
            }
        }

        Vector hip(int i) {
            return at(g, hipX[i], hipY, hipZ);
        }

        /** 다리 i 의 쉼 자리(엉덩이 아래 바닥, 바깥으로 stance) + 가는 쪽 dir 로 ahead. */
        Vector rest(int i, Vector dir, double ahead) {
            Vector h = hip(i);
            Vector out = left(g).multiply(Math.signum(hipX[i]) * stance);
            Vector r = h.add(out);
            if (dir != null && ahead != 0) r.add(dir.clone().multiply(ahead));
            r.setY(g.game.mobs.flow.floor(r.getX(), r.getZ()));
            return r;
        }

        /** 모든 발을 지금 쉼 자리에 디딤. */
        void plant() {
            // 엇갈려 디딤(한 발은 앞, 한 발은 뒤): 처음 걸을 때 두 발이 한꺼번에 뒤처지지 않게
            Vector f = fwd(g);
            for (int i = 0; i < foot.length; i++) {
                foot[i].copy(rest(i, f, (i % 2 == 0 ? 0.5 : -0.5) * reach));
                swingAt[i] = -1;
            }
            ready = true;
            pose(true);
        }

        boolean swinging() {
            for (int s : swingAt) if (s >= 0) return true;
            return false;
        }

        boolean swinging(int i) {
            return swingAt[i] >= 0;
        }

        /** 다리 i 를 to 로 ticks 동안 들어 옮김(몸짓). */
        void step(int i, Vector target, int ticks) {
            from[i].copy(foot[i]);
            to[i].copy(target);
            to[i].setY(g.game.mobs.flow.floor(target.getX(), target.getZ()));
            swingAt[i] = g.age;
            swingLen[i] = Math.max(2, ticks);
            if (lifted != null) lifted.lifted(i, to[i], swingLen[i]);
        }

        /** 매 틱(종의 tick 끝, vel 을 정한 뒤). */
        void tick() {
            if (!ready) plant();
            // 닻 몸은 15도 넘게 돌아야 방향을 다시 보낸다: 다리(세계 축)와 어긋나지 않게 3도마다 몸 방향을 보냄
            float dy = ((g.yaw - bodyYaw) % 360f + 540f) % 360f - 180f;
            if (Math.abs(dy) > 3f && g.body != null) {
                g.body.pose(3);
                bodyYaw = g.yaw;
            }
            for (int i = 0; i < foot.length; i++) {
                if (swingAt[i] < 0) continue;
                double t = (g.age - swingAt[i]) / (double) swingLen[i];
                if (t >= 1) {
                    foot[i].copy(to[i]);
                    swingAt[i] = -1;
                    steps++;
                    if (land != null) land.landed(i, foot[i].clone());
                } else {
                    double e = t * t * (3 - 2 * t);
                    foot[i].setX(from[i].getX() + (to[i].getX() - from[i].getX()) * e);
                    foot[i].setZ(from[i].getZ() + (to[i].getZ() - from[i].getZ()) * e);
                    foot[i].setY(from[i].getY() + (to[i].getY() - from[i].getY()) * e + lift * Math.sin(Math.PI * t));
                }
            }
            if (!pinned) {
                Vector v = g.vel.clone().setY(0);
                double sp = v.length();
                Vector dir = sp > 1e-4 ? v.multiply(1 / sp) : null;
                // 한 번에 한 발. 다른 발을 옮기는 중이면 이 발이 reach 의 1.6배 넘게 뒤처졌을 때만(다리가 지나치게 늘어나지 않게)
                boolean busy = swinging();
                int best = -1;
                double bd = busy ? reach * 1.6 : 0;
                for (int i = 0; i < foot.length; i++) {
                    if (swingAt[i] >= 0) continue;
                    double d = flat(foot[i], rest(i, null, 0));
                    if (d > bd) {
                        bd = d;
                        best = i;
                    }
                }
                // 디딜 자리: 발을 옮기는 동안 몸이 갈 만큼(sp x swing)을 더해, 닿을 때 엉덩이보다 lead 만큼 앞
                if (best >= 0 && bd > reach) step(best, rest(best, dir, dir == null ? 0 : lead + sp * swing), swing);
            }
            pose(false);
        }

        /** 다리 부품 자세(2틱마다, 몸이나 발이 움직였을 때). */
        void pose(boolean now) {
            boolean moved = g.pos.distanceSquared(lastPos) > 1e-4 || Math.abs(g.yaw - lastYaw) > 0.5f || swinging();
            if (!now && (g.age - posedAt < 2 || !moved)) return;
            posedAt = g.age;
            lastPos.copy(g.pos);
            lastYaw = g.yaw;
            float yawR = (float) Math.toRadians(-g.yaw);
            for (int i = 0; i < foot.length; i++) {
                if (part[i] == null) continue;
                Vector h = hip(i);
                Vector d = foot[i].clone().subtract(h);
                double l = Math.max(0.3, d.length());
                Vector3f u = new Vector3f((float) (d.getX() / l), (float) (d.getY() / l), (float) (d.getZ() / l));
                Quaternionf q = new Quaternionf().rotationTo(new Vector3f(0, -1, 0), u);
                Matrix4f m = new Matrix4f().translate((float) (h.getX() - g.pos.getX()), (float) (h.getY() - g.pos.getY()), (float) (h.getZ() - g.pos.getZ()))
                    .rotate(q).rotateY(yawR).scale(1f, (float) (l / len), 1f);
                part[i].pose(m, now ? 0 : 2);
            }
        }

        /** 다리 i 의 무릎(엉덩이와 발 가운데) 세계 점. */
        Vector knee(int i) {
            return hip(i).add(foot[i]).multiply(0.5);
        }

        /** 다리 i 위 몫 f(0 엉덩이 .. 1 발) 의 점. */
        Vector along(int i, double f) {
            Vector h = hip(i);
            return h.add(foot[i].clone().subtract(h).multiply(f));
        }

        /** 가장 많이 벌어진 발의 수평 거리(쉼 자리에서). 몸 높이 출렁임에 씀. */
        double spread() {
            double m = 0;
            for (int i = 0; i < foot.length; i++) {
                if (swingAt[i] >= 0) continue;
                m = Math.max(m, flat(foot[i], rest(i, null, 0)));
            }
            return m;
        }
    }
}
