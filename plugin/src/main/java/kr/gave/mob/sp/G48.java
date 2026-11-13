package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.WeatherType;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.world.Plaza;

/**
 * G48 열매 단 나무 게이브(함정, 지원, 미리 깔림).
 *
 * <p>도감: 엔드 막대기를 엮은 앙상한 4 블록 나무, 꼭대기에 얼굴 하나, 가지 여섯 끝에 숨 쉬는 얼굴 열매. 경기장 가장자리(가운데에서 30~44 블록)에
 * 솟아 움직이지 않는다. 둘레 구역(열매 여섯이면 반지름 8, 열매가 줄수록 3 까지)은 환한 낮이고 다른 게이브가 들어오지 못하며 초당 0.5 회복.
 * 머문 시간은 사람마다 쌓이고(안에 사람이 많을수록 빨리: 한 사람 더 있을 때마다 25%), 구역 안 가장 오래 머문 사람 머리 위로 노란 빛기둥이
 * 내려와 머문 시간만큼 좁아진다(빛기둥이 시계, 본인에게는 HUD 게이지도). 9초를 넘기면 1.5초 동안 조여 쓰러뜨린다.
 * 다 부푼 열매는 15초마다 하나씩 떨어져 눈 뜬 게이브(가오리, 보상 0)로 깨어난다(깨어난 것은 구역 안을 다닌다).
 * 맞서기: 열매를 쏘면 터지고(2원) 구역이 준다. 꼭대기 얼굴을 부수면 사라진다(줄기는 30% 만). 열매가 다 없어지면 20초 뒤 시든다.
 *
 * <p>몸: 닻 {@code gave:gabe/g48}(나무와 꼭대기) + 부품 {@code gave:gabe/g48_fruit}(열매 송이, 부위 gone1..6, ripe1..6)
 * + 부품 {@code gave:gabe/g48_beam}(빛기둥, 세계 축, 부위 show). 소리 giggle(24), wheeze(12), beam(16), drop(16). 글귀 cause_g48.
 *
 * <p>도감과 다른 점: 밝음은 진짜 빛 블록 대신 사람 화면에만 보내는 빛 블록(sendBlockChange, 월드를 안 고침)과 구역 안 사람의 화면 시각(낮),
 * 날씨(맑음)로 낸다. 안개를 빨아들이는 소용돌이와 렌즈 색온도, 음악은 종 쪽에서 걸 수 없어 뺐다. 빛기둥은 반투명 대신 점묘.
 * 빛기둥 디스플레이가 하나라 가장 오래 머문 한 사람 위에만 서고, 나머지는 HUD 게이지가 시계다.
 */
public final class G48 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final int FRUITS = 6;
    /** 열매 가운데(몸 가운데 기준, 블록: 옆, 위, 앞). 팩 g48.py 의 가지 끝 - 0.38 과 같게. */
    static final double[][] FRUIT = {{0.39, -0.467, 1.071}, {0.776, 0.024, -0.925}, {-1.122, 0.446, -0.198}, {0.971, 0.762, 0.171},
        {-0.315, 1.119, -0.867}, {-0.527, 1.393, 0.628}};
    /** 몸 가운데 높이(바닥에서), 꼭대기 얼굴 높이(몸 가운데에서). */
    static final double MID = 2.0, TOP_Y = 1.75;
    static final double TOP_R = 0.42, FRUIT_R = 0.26, TRUNK_R = 0.3;
    /** 구역 반지름: 열매 여섯일 때, 열매가 없을 때. */
    static final double R_FULL = 8, R_MIN = 3;
    /** 머물 수 있는 틱(9초), 그 뒤 조이는 틱. */
    static final int STAY_MAX = 180, CLAMP_T = 30;
    static final double HEAL = 0.5, CROWD = 0.25;
    /** 열매가 익는 간격: 처음, 그 뒤, 부풀어 떨어지기까지. 열매가 다 없어진 뒤 시들기까지. */
    static final int FIRST_RIPE = 200, RIPE_EVERY = 300, RIPE_T = 60, WITHER_T = 400;
    static final int FRUIT_REWARD = 2;
    /** 열매 체력(나무 체력의 몇 배), 줄기에 맞은 피해 중 들어가는 몫. */
    static final double FRUIT_HP = 0.12, TRUNK_PASS = 0.3;
    /** 빛기둥: 높이 배율(모델 2.9 블록), 반지름(처음, 9초, 다 조임). */
    static final float BEAM_H = 2.2f;
    static final double BEAM_R0 = 3.0, BEAM_R1 = 0.7, BEAM_R2 = 0.15;
    /** 놓이는 띠(가운데에서, 블록). */
    static final double EDGE_IN = 30, EDGE_OUT = 44;
    static final int LIGHT_STEP = 3;

    private Body.Part fruit, beam;
    private final boolean[] gone = new boolean[FRUITS];
    private final double[] fruitHp = new double[FRUITS];
    private final double[] fx = new double[FRUITS], fy = new double[FRUITS], fz = new double[FRUITS];
    private boolean init;
    private int ripe = -1, ripeAt, nextRipe, witherAt = -1, sproutAt = -1, nextGiggle, nextWheeze;
    private final Map<UUID, double[]> stay = new HashMap<>();
    private Set<UUID> inside = new HashSet<>(), nowIn = new HashSet<>();
    private final Set<UUID> lightSent = new HashSet<>();
    private final List<Location> lights = new ArrayList<>();
    private BlockData lightData;
    private final List<Gabe> woke = new ArrayList<>();
    private Player victim;

    public G48(Game game, Species sp) {
        super(game, sp);
        radius = 0.5;
        hover = MID;
        baseSpeed = 0;
        noPush = true;
        noBite = true;
    }

    @Override
    protected void build() {
        // 컬링 상자는 구역만큼 넓게(빛기둥 부품이 구역 끝에 설 때도 보이게)
        body = Body.of(this, "gabe/g48", 18f, 9f);
        fruit = body.attach("gabe/g48_fruit");
        beam = body.attach("gabe/g48_beam");
        if (beam != null) {
            beam.follow = false;
            if (beam.d != null) beam.d.setBrightness(new Display.Brightness(15, 15));
        }
    }

    @Override
    protected void spawned() {
        nextRipe = age + FIRST_RIPE;
        nextGiggle = age + 30 + game.random.nextInt(60);
        nextWheeze = age + 20 + game.random.nextInt(40);
        if (game.director.cageSpawning()) return;
        // 경기장 가장자리 띠(30~44) 밖에 놓였고 곁에 사람이 없으면(시험 소환이 아니면) 같은 방향의 띠 안으로 옮긴다
        double cx = pos.getX() - (Plaza.CX + 0.5), cz = pos.getZ() - (Plaza.CZ + 0.5), r = Math.hypot(cx, cz);
        if ((r < EDGE_IN - 2 || r > EDGE_OUT + 2) && game.mobs.nearestPlayer(pos, 12.5) == null) {
            double want = EDGE_IN + game.random.nextDouble() * (EDGE_OUT - EDGE_IN);
            if (r < 1e-3) {
                cx = 1;
                cz = 0;
                r = 1;
            }
            double x = Plaza.CX + 0.5 + cx / r * want, z = Plaza.CZ + 0.5 + cz / r * want;
            if (game.mobs.flow.walkable(x, z)) pos.setX(Math.floor(x) + 0.5).setZ(Math.floor(z) + 0.5);
        }
        pos.setY(game.mobs.flow.floor(pos.getX(), pos.getZ()) + MID);
        face(new Vector(Plaza.CX + 0.5, pos.getY(), Plaza.CZ + 0.5));
        pitch = 0;
        // 땅에서 솟는다
        body.offset.set(0, -3.9f, 0);
        body.pose(0);
        sproutAt = age + 2;
    }

    private void init() {
        init = true;
        double each = Math.max(1, maxHp * FRUIT_HP);
        double yr = Math.toRadians(-yaw), c = Math.cos(yr), s = Math.sin(yr);
        for (int i = 0; i < FRUITS; i++) {
            fruitHp[i] = each;
            double x = FRUIT[i][0], z = FRUIT[i][2];
            fx[i] = pos.getX() + x * c + z * s;
            fy[i] = pos.getY() + FRUIT[i][1];
            fz[i] = pos.getZ() - x * s + z * c;
        }
        lightData = Bukkit.createBlockData(Material.LIGHT, "[level=15]");
        int bx = (int) Math.floor(pos.getX()), bz = (int) Math.floor(pos.getZ());
        int n = (int) (R_FULL / LIGHT_STEP);
        for (int ix = -n; ix <= n; ix++) {
            for (int iz = -n; iz <= n; iz++) {
                int x = bx + ix * LIGHT_STEP, z = bz + iz * LIGHT_STEP;
                if (Math.hypot(x + 0.5 - pos.getX(), z + 0.5 - pos.getZ()) > R_FULL) continue;
                int y = (int) Math.floor(game.mobs.flow.floor(x + 0.5, z + 0.5)) + 1;
                Block b = game.world.getBlockAt(x, y, z);
                if (b.getType().isAir()) lights.add(b.getLocation());
            }
        }
    }

    private int fruitsLeft() {
        int n = 0;
        for (boolean g : gone) if (!g) n++;
        return n;
    }

    /** 지금 구역 반지름(열매가 줄수록 좁아짐). */
    public double zoneR() {
        return R_MIN + (R_FULL - R_MIN) * fruitsLeft() / (double) FRUITS;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!init) init();
        hold();
        if (sproutAt >= 0 && age >= sproutAt) {
            sproutAt = -1;
            body.offset.set(0, 0, 0);
            body.pose(30);
        }
        zone();
        if (every(2)) repel();
        ripen();
        if (every(40)) lightsTick();
        if (age >= nextGiggle) {
            sound("giggle", Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, 1.5f, 24);
            nextGiggle = age + 110 + game.random.nextInt(80);
        }
        if (age >= nextWheeze) {
            sound("wheeze", Sound.ENTITY_PLAYER_BREATH, 1.6f, 12);
            nextWheeze = age + 60 + game.random.nextInt(40);
        }
        if (witherAt >= 0 && age >= witherAt) die(new Damage(Math.max(1, hp), DamageType.OTHER, null, pos.clone(), null, "wither", 0));
    }

    private double flat(Player p) {
        Location l = p.getLocation();
        return Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
    }

    /** 구역 안 사람: 머문 시간, 회복, 낮과 맑음, 게이지, 빛기둥. */
    private void zone() {
        double r = zoneR();
        List<Player> ps = game.mobs.standing();
        nowIn.clear();
        int n = 0;
        for (int i = 0; i < ps.size(); i++) if (flat(ps.get(i)) <= r) n++;
        double rate = 1 + CROWD * Math.max(0, n - 1);
        Player top = null;
        double topT = -1;
        for (int i = 0; i < ps.size(); i++) {
            Player p = ps.get(i);
            if (flat(p) > r) continue;
            UUID uid = p.getUniqueId();
            nowIn.add(uid);
            if (!inside.contains(uid)) enter(p);
            double[] t = stay.get(uid);
            if (t == null) {
                t = new double[1];
                stay.put(uid, t);
            }
            t[0] += rate;
            if (age % 20 == 0) game.players.heal(p, HEAL);
            if (age % 5 == 0) game.hud.meter(p, "g48", Math.min(1.0, t[0] / STAY_MAX), 10);
            if (t[0] > topT) {
                topT = t[0];
                top = p;
            }
        }
        if (!inside.isEmpty()) {
            for (UUID uid : inside) if (!nowIn.contains(uid)) leave(Bukkit.getPlayer(uid));
        }
        Set<UUID> sw = inside;
        inside = nowIn;
        nowIn = sw;
        pillar(top, topT);
    }

    private void enter(Player p) {
        p.setPlayerTime(6000, false);
        p.setPlayerWeather(WeatherType.CLEAR);
    }

    private void leave(Player p) {
        if (p == null || !p.isOnline()) return;
        p.resetPlayerTime();
        p.resetPlayerWeather();
        game.hud.meter(p, "g48", -1, 0);
    }

    /** 빛기둥: 가장 오래 머문 사람 머리 위, 머문 시간만큼 좁아지고 9초를 넘기면 조여 쓰러뜨린다. */
    private void pillar(Player top, double t) {
        if (beam == null) return;
        if (top == null) {
            beam.look.on.remove("show");
            if (victim != null) {
                victim = null;
                mark(null);
            }
            return;
        }
        double r;
        if (t < STAY_MAX) r = BEAM_R0 + (BEAM_R1 - BEAM_R0) * (t / STAY_MAX);
        else r = BEAM_R1 + (BEAM_R2 - BEAM_R1) * Math.min(1.0, (t - STAY_MAX) / CLAMP_T);
        beam.look.on.add("show");
        if (every(2)) {
            Location l = top.getLocation();
            float y = (float) (l.getY() + 1.45 * BEAM_H - pos.getY());
            beam.pose(new Matrix4f().translate((float) (l.getX() - pos.getX()), y, (float) (l.getZ() - pos.getZ())).scale((float) r, BEAM_H, (float) r), 2);
        }
        if (t >= STAY_MAX) {
            if (victim != top) {
                victim = top;
                mark(top);
                sound("beam", Sound.BLOCK_BEACON_POWER_SELECT, 1.5f, 16);
            }
            if (t >= STAY_MAX + CLAMP_T) {
                double[] st = stay.get(top.getUniqueId());
                if (st != null) st[0] = 0;
                victim = null;
                mark(null);
                hit(top, 1000, "cause_g48");
            }
        } else if (victim != null) {
            victim = null;
            mark(null);
        }
    }

    /** 다른 게이브는 구역에 들어오지 못한다(깨어난 열매, 하늘 종, 미리 깔리는 종은 뺌): 안에 들면 테두리 쪽으로 밀어낸다. */
    private void repel() {
        double r = zoneR(), r2 = r * r;
        if (!woke.isEmpty() && every(40)) woke.removeIf(g -> g.dead);
        for (Gabe g : game.mobs.all()) {
            if (g == this || g.dead || g.flying || g.sp == Species.G48 || g.sp.spawn == Species.Spawn.PLACED) continue;
            double dx = g.pos.getX() - pos.getX(), dz = g.pos.getZ() - pos.getZ(), d2 = dx * dx + dz * dz;
            if (d2 >= r2 || woke.contains(g)) continue;
            double d = Math.sqrt(d2);
            if (d < 1e-3) {
                dx = 1;
                dz = 0;
                d = 1;
            }
            double push = Math.min(0.7, r - d);
            g.pos.setX(g.pos.getX() + dx / d * push).setZ(g.pos.getZ() + dz / d * push);
        }
    }

    /** 15초마다 열매 하나가 다 부풀어(3초) 떨어지고 눈 뜬 게이브로 깨어난다. */
    private void ripen() {
        if (ripe >= 0) {
            if (age >= ripeAt) fall(ripe);
            return;
        }
        if (age < nextRipe || fruit == null) return;
        int left = fruitsLeft();
        if (left == 0) return;
        int k = game.random.nextInt(left);
        for (int i = 0; i < FRUITS; i++) {
            if (gone[i]) continue;
            if (k-- == 0) {
                ripe = i;
                break;
            }
        }
        ripeAt = age + RIPE_T;
        fruit.look.on.add("ripe" + (ripe + 1));
        sound("wheeze", Sound.ENTITY_PLAYER_BREATH, 1.6f, 12);
    }

    private void lose(int i) {
        gone[i] = true;
        if (fruit != null) {
            fruit.look.on.remove("ripe" + (i + 1));
            fruit.look.on.add("gone" + (i + 1));
        }
        if (ripe == i) {
            ripe = -1;
            nextRipe = age + RIPE_EVERY;
        }
        if (fruitsLeft() == 0 && witherAt < 0) witherAt = age + WITHER_T;
    }

    /** 다 부푼 열매가 떨어져 깨어남(보상 0, 구역 안을 다님). */
    private void fall(int i) {
        lose(i);
        sound("drop", Sound.ENTITY_SLIME_SQUISH_SMALL, 0.8f, 16);
        Vector at = new Vector(fx[i], game.mobs.flow.floor(fx[i], fz[i]) + Species.G01.band.hover, fz[i]);
        Gabe c = child(Species.G01, at);
        if (c == null) return;
        woke.add(c);
        if (c.body != null) c.body.look.open = true;
    }

    /** 열매를 쏴 터뜨림: 쏜 사람에게 2원, 구역이 준다. */
    private void pop(int i, Damage d) {
        lose(i);
        sound("drop", Sound.ENTITY_SLIME_SQUISH_SMALL, 0.8f, 16);
        if (noReward) return;
        Player by = d.by();
        if (by != null && by.isOnline()) game.players.addMoney(by, FRUIT_REWARD);
    }

    // ------------------------------------------------------------------ 빛(사람 화면에만 보내는 빛 블록)

    private void lightsTick() {
        if (lights.isEmpty()) return;
        for (Player p : game.world.getPlayers()) {
            UUID uid = p.getUniqueId();
            double d = p.getLocation().toVector().setY(pos.getY()).distance(pos);
            if (d <= 72 && lightSent.add(uid)) {
                for (Location l : lights) p.sendBlockChange(l, lightData);
            } else if (d > 104) lightSent.remove(uid);
        }
    }

    private void lightsOff() {
        for (UUID uid : lightSent) {
            Player p = Bukkit.getPlayer(uid);
            if (p == null || p.getWorld() != game.world) continue;
            for (Location l : lights) p.sendBlockChange(l, l.getBlock().getBlockData());
        }
        lightSent.clear();
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(FRUITS + 4);
        out.add(new double[] {pos.getX(), pos.getY() + TOP_Y, pos.getZ(), TOP_R});
        if (init) for (int i = 0; i < FRUITS; i++) if (!gone[i]) out.add(new double[] {fx[i], fy[i], fz[i], FRUIT_R});
        out.add(new double[] {pos.getX(), pos.getY() + 0.8, pos.getZ(), TRUNK_R});
        out.add(new double[] {pos.getX(), pos.getY() - 0.2, pos.getZ(), TRUNK_R});
        out.add(new double[] {pos.getX(), pos.getY() - 1.2, pos.getZ(), TRUNK_R});
        return out;
    }

    @Override
    public Vector head() {
        return new Vector(pos.getX(), pos.getY() + TOP_Y, pos.getZ());
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (!init) init();
        DamageType t = d.type();
        Vector at = d.at();
        if (t == DamageType.EXPLOSION || t == DamageType.FIRE) {
            // 폭발, 불: 3 블록 안 열매가 터지고 나무는 절반만
            if (at != null) {
                for (int i = 0; i < FRUITS; i++) {
                    if (gone[i]) continue;
                    double dx = fx[i] - at.getX(), dy = fy[i] - at.getY(), dz = fz[i] - at.getZ();
                    if (dx * dx + dy * dy + dz * dz > 9) continue;
                    fruitHp[i] -= d.amount();
                    if (fruitHp[i] <= 0) pop(i, d);
                }
            }
            return super.damage(d.times(0.5));
        }
        if (at != null && (t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER)) {
            double topD = at.distanceSquared(head());
            int best = -1;
            double bd = Double.MAX_VALUE;
            for (int i = 0; i < FRUITS; i++) {
                if (gone[i]) continue;
                double dx = fx[i] - at.getX(), dy = fy[i] - at.getY(), dz = fz[i] - at.getZ();
                double dd = dx * dx + dy * dy + dz * dz;
                if (dd < bd) {
                    bd = dd;
                    best = i;
                }
            }
            if (best >= 0 && bd < topD && bd <= (FRUIT_R + 0.3) * (FRUIT_R + 0.3)) {
                // 열매에 맞음: 열매 체력만 깎임
                fruitHp[best] -= d.amount();
                if (fruit != null) fruit.look.hurtUntil = game.worldTime() + 3;
                game.mobs.onHurt(this, d, 0.01);
                if (fruitHp[best] <= 0) pop(best, d);
                return true;
            }
            if (topD > (TOP_R + 0.3) * (TOP_R + 0.3)) d = d.times(TRUNK_PASS);
        }
        return super.damage(d);
    }

    // ------------------------------------------------------------------ 죽음, 치우기

    private void cleanup() {
        lightsOff();
        for (UUID uid : inside) leave(Bukkit.getPlayer(uid));
        inside.clear();
        if (beam != null) {
            beam.remove();
            beam = null;
        }
        victim = null;
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

    @Override
    public String toString() {
        Iterator<double[]> it = stay.values().iterator();
        double max = 0;
        while (it.hasNext()) max = Math.max(max, it.next()[0]);
        return super.toString() + " fruits=" + fruitsLeft() + String.format(java.util.Locale.ROOT, " zone=%.1f stay=%.0f", zoneR(), max) + " in=" + inside.size()
                + (ripe >= 0 ? " ripe=" + ripe : "") + (witherAt >= 0 ? " wither=" + (witherAt - age) : "") + " woke=" + woke.size() + " lights=" + lights.size() + "/" + lightSent.size();
    }
}
