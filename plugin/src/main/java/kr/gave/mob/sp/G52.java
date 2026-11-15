package kr.gave.mob.sp;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.gun.Gun;
import kr.gave.gun.GunMods;
import kr.gave.gun.Guns;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.DeathBorn;
import kr.gave.player.PState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * G52 총 주운 게이브(원거리, 정예, 사람 죽음의 결과로만 생김).
 *
 * <p>도감: 막대기 둘이 앞으로 꺾여 쓰러진 사람의 바로 그 총 모델을 받쳐 든다. 위에 그 사람 이름표가 뜨고 글자가 가끔 한 글자씩 실눈 기호로
 * 바뀐다. 그 총의 증강과 스탯(연사, 탄창, 재장전 시간, 퍼짐, 산탄 알 수)을 그대로 쓰되 위력은 절반. 옆걸음 치며 쏘고, 탄창을 비우면
 * 그 총의 재장전 시간만큼 쉰다(뒤로 물러남). 총소리는 그 총 소리를 낮게 늘인 것. 잡으면 주인이 상점 앞에서 손실 없이 부활하고
 * (Players.freeFrom) 총을 되찾는다.
 *
 * <p>엔진, 황야에 맞춘 것: 엄폐물이 없어 옆걸음과 거리 지키기만 한다. '가까운 게이브가 총을 주워 이 종이 된다'는 죽은 자리에 이 종이
 * 새로 생기는 것으로(Players 가 그렇게 놓음). '총을 떨궈 주인 전용 줍기'는 줍기 없이 주인에게 바로 돌려준다(웨이브 끝 정리로 치워질 때도 총은
 * 돌려줌). 사람이 받는 피해는 한 발 = 총 피해 x 0.5(위력 절반) x 0.25(게이브 체력용 수치를 사람 체력 100 에 맞춤) x 거리 감쇠, 조준은
 * 표적의 5틱 전 자리(옆으로 뛰면 빗나감). 죽음 결과 종이라 오래 살면 변함에서 뺐다(갈라지면 총이 둘이 됨). 주인이 '내 이름 쓰지 않기'면
 * 이름표는 실눈 기호만. 소환 명령처럼 주인 없이 생기면 기본 소총에 이름 없는 표.
 *
 * <p>팩 계약(tools/gabe/sp/g52.py): 모델 gave:gabe/g52, 상태 "" 겨눔, "reload" 재장전. 소리 shot(64), pickup(32). 글귀 cause_g52, notice_g52_gun.
 * 총 모델(소품 층 gave:rifle 등)과 이름표는 닻에 태운 디스플레이 둘(종 파일이 직접 만듦).
 */
public final class G52 extends Gabe implements DeathBorn {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.13, HOVER = 1.15;
    /** 위력 절반, 게이브 체력용 총 피해를 사람 체력에 맞추는 비율. */
    static final double POWER = 0.5, VS_PLAYER = 0.25;
    /** 조준이 늦는 틱, 퍼짐 배율과 덤(도), 쏘는 거리 위끝. */
    static final int AIM_LAG = 5;
    static final double SPREAD_MUL = 1.8, SPREAD_ADD = 1.2, MAX_RANGE = 40;
    /** 총을 주워 드는 틱, 새 표적을 찍고 쏘기까지, 옆걸음 방향을 바꾸는 틱. */
    static final int PICKUP_T = 24, WINDUP = 14, SIDE_MIN = 36, SIDE_VAR = 40;
    /** 이름표 글자가 실눈으로 바뀌는 간격과 길이. */
    static final int GLITCH_EVERY = 34, GLITCH_T = 12;
    static final String SQUINT = "⌒";
    static final TextColor PINK = TextColor.color(0xF28AA6);
    /** 총 모델 자리(몸 기준: 옆, 위, 앞)와 종류별 크기. */
    static final float GUN_Y = -0.3f, GUN_Z = 0.62f;

    enum Phase { PICKUP, FIGHT, RELOAD }

    private Phase phase = Phase.PICKUP;
    private int phaseAt, sideUntil, side = 1, mag, shotsInMag, windupUntil, glitchAt = -1, glitchIdx = -1, reloadEnd, magInAt;
    private double nextShot;
    private Guns.Kind kind = Guns.Kind.RIFLE;
    private Guns.Spec spec = Guns.SPECS.get(Guns.Kind.RIFLE);
    /** 주인에게 돌려줄 주무기(권총을 쓰거나 주인이 없으면 null). */
    private Gun held;
    private UUID ownerId;
    private String ownerName;
    private Player prey;
    private final double[] aim = new double[AIM_LAG * 3];
    private int aimAt, aimN;
    private ItemDisplay gunD;
    private TextDisplay tag;
    private float gunYaw = Float.NaN, gunPitch;
    private float aimPitch;
    private String tagShown = "";

    public G52(Game game, Species sp) {
        super(game, sp);
        radius = 0.4;
        hover = HOVER;
        baseSpeed = SPEED;
        noMutate = true;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g52", 2.2f, 1.8f);
        mag = spec.mag();
        makeGun();
        makeTag();
    }

    @Override
    protected void spawned() {
        side = game.random.nextBoolean() ? 1 : -1;
        if (!game.director.cageSpawning()) adopt();
    }

    /**
     * 소환 명령처럼 죽음 결과 굴림 없이 생겼을 때: 죽음 결과가 아직 없는 죽은 사람(가장 최근에 죽은 사람)이 있으면 그 사람 것으로 한다.
     * 실제 판에서는 Players 가 놓은 바로 뒤에 bornFrom 을 부르므로(그때 그 사람의 결과는 아직 비어 있음) 같은 사람을 고르게 되고,
     * 뒤따르는 bornFrom 은 두 번째라 무시된다.
     */
    private void adopt() {
        Player best = null;
        PState bs = null;
        for (Player p : game.players.withLife(kr.gave.player.Life.DEAD)) {
            PState s = game.players.get(p);
            if (s.deathMob != null || s.deathResult != null) continue;
            if (bs == null || s.lifeAt > bs.lifeAt) {
                best = p;
                bs = s;
            }
        }
        if (best != null) bornFrom(best, bs, best.getLocation());
    }

    /** 사람 죽음의 결과로 생김: 그 사람 주무기를 빼앗아 그 총과 증강으로 쏜다(주무기가 없으면 그 사람 권총 값). */
    @Override
    public void bornFrom(Player dead, PState state, Location at) {
        if (ownerId != null || dead == null) return;
        ownerId = dead.getUniqueId();
        ownerName = state != null && state.noLikeness ? null : dead.getName();
        GunMods mods = game.augments.mods(dead);
        Gun g = game.guns.takePrimary(dead);
        if (g != null) {
            held = g;
            kind = g.kind;
            spec = Guns.effective(kind, g, mods);
        } else {
            kind = Guns.Kind.PISTOL;
            spec = Guns.effective(kind, game.guns.pistolGun(dead), mods);
        }
        mag = spec.mag();
        if (gunD != null && gunD.isValid()) gunD.setItemStack(gunItem());
        gunYaw = Float.NaN;
        showTag(true);
        game.hud.notice("notice_g52_gun", Species.G52, 60);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        switch (phase) {
            case PICKUP -> pickup();
            case FIGHT -> fight();
            case RELOAD -> reload();
        }
        syncGun();
        if (every(2)) showTag(false);
    }

    /** 총을 주워 받쳐 든다. */
    private void pickup() {
        hold();
        if (inPhase() == 1) sound("pickup", Sound.ITEM_ARMOR_EQUIP_IRON, 0.6f, 32);
        Player p = nearest(64);
        if (p != null) turnTo(chest(p), 9f);
        if (inPhase() >= PICKUP_T) phase(Phase.FIGHT);
    }

    /** 옆걸음 치며 표적과 거리를 지키고, 찍은 뒤 0.7초부터 그 총 연사로 쏜다. */
    private void fight() {
        state("");
        Player p = pickPrey();
        if (p == null) {
            if (target != null) mark(null);
            prey = null;
            follow(speed());
            if (vel.lengthSquared() > 1e-6) turnTo(pos.clone().add(vel), 9f);
            aimPitch *= 0.8f;
            return;
        }
        if (p != prey) {
            prey = p;
            mark(p);
            windupUntil = age + cd(WINDUP);
            aimN = 0;
        }
        remember(p);
        strafe(p, false);
        aimAtPrey(p);
        double d = dist(p);
        if (age < windupUntil || d > Math.min(MAX_RANGE, spec.range() * 0.9) || !sees(p)) return;
        if (mag <= 0) {
            startReload();
            return;
        }
        if (age >= nextShot) {
            fire(p);
            nextShot = Math.max(nextShot, age - 1) + 20.0 / Math.max(0.2, spec.rps());
        }
    }

    /** 재장전: 그 총의 재장전 시간만큼 쉬며 뒤로 물러난다. */
    private void reload() {
        state("reload");
        Player p = prey != null && standing(prey) ? prey : nearest(64);
        if (p != null) {
            remember(p);
            strafe(p, true);
            aimAtPrey(p);
        } else hold();
        if (age == magInAt && !game.director.silent())
            Props.soundAround(loc(), "gun." + spec.key() + (spec.shells() ? ".pump" : ".mag_in"), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.9f, 0.68f, 0.7f, 16, -1);
        if (age >= reloadEnd) {
            mag = spec.mag();
            shotsInMag = 0;
            windupUntil = age + 6;
            state("");
            phase(Phase.FIGHT);
        }
    }

    private void startReload() {
        int t = spec.shells() ? spec.reload() * spec.mag() + 15 : spec.reload();
        reloadEnd = age + Math.max(10, t);
        magInAt = age + Math.max(6, (int) (t * 0.7));
        if (!game.director.silent())
            Props.soundAround(loc(), "gun." + spec.key() + (spec.shells() ? ".reload_start" : ".mag_out"), Sound.BLOCK_IRON_TRAPDOOR_OPEN, 0.9f, 0.68f, 0.7f, 16, -1);
        phase(Phase.RELOAD);
    }

    /** 표적: 지금 표적이 서 있고 보이면 그대로, 아니면 가리지 않은 가장 가까운 사람. */
    private Player pickPrey() {
        if (prey != null && standing(prey) && dist(prey) <= MAX_RANGE + 8 && (every(10) ? sees(prey) : true)) return prey;
        return acquire(MAX_RANGE + 8);
    }

    /** 옆걸음(가끔 방향 바꿈) + 그 총에 맞는 거리 지키기. back 이면 뒤로 물러나며. */
    private void strafe(Player p, boolean back) {
        if (age >= sideUntil) {
            side = -side;
            sideUntil = age + SIDE_MIN + game.random.nextInt(SIDE_VAR);
        }
        double ideal = Math.max(6, Math.min(16, spec.range() * 0.35)) + (back ? 5 : 0);
        Vector t = toward(p);
        double d = Math.hypot(p.getLocation().getX() - pos.getX(), p.getLocation().getZ() - pos.getZ());
        double radial = Math.max(-1, Math.min(1, (d - ideal) / 3.0));
        double v = speed();
        vel.setX((-t.getZ() * side * 0.8 + t.getX() * radial * 0.7) * v).setY(0).setZ((t.getX() * side * 0.8 + t.getZ() * radial * 0.7) * v);
        if (wallAhead(0.7)) {
            side = -side;
            sideUntil = age + SIDE_MIN;
        }
    }

    private void aimAtPrey(Player p) {
        Vector c = chest(p);
        turnTo(c, 12f);
        pitch = 0;
        Vector m = muzzle();
        double dy = c.getY() - m.getY(), dh = Math.hypot(c.getX() - m.getX(), c.getZ() - m.getZ());
        float want = (float) Math.toDegrees(-Math.atan2(dy, Math.max(0.5, dh)));
        aimPitch += Math.max(-8f, Math.min(8f, want - aimPitch));
    }

    /** 표적 몸통 자리를 틱마다 적어 둔다(조준은 AIM_LAG 틱 전 자리). */
    private void remember(Player p) {
        Vector c = chest(p);
        int i = aimAt * 3;
        aim[i] = c.getX();
        aim[i + 1] = c.getY();
        aim[i + 2] = c.getZ();
        aimAt = (aimAt + 1) % AIM_LAG;
        aimN = Math.min(AIM_LAG, aimN + 1);
    }

    private Vector lagged(Player p) {
        if (aimN < AIM_LAG) return chest(p);
        int i = aimAt * 3; // 가장 오래된 칸
        return new Vector(aim[i], aim[i + 1], aim[i + 2]);
    }

    // ------------------------------------------------------------------ 사격

    /** 총구 자리(세계). */
    private Vector muzzle() {
        double yr = Math.toRadians(yaw), pr = Math.toRadians(aimPitch);
        Vector f = new Vector(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
        return pos.clone().add(new Vector(0, GUN_Y, 0)).add(f.multiply(GUN_Z + gunLen() * 0.5));
    }

    private void fire(Player p) {
        Vector from = muzzle();
        Vector to = lagged(p);
        Vector base = to.clone().subtract(from);
        if (base.lengthSquared() < 1e-6) return;
        base.normalize();
        double spread = Math.toRadians(spec.spread() * SPREAD_MUL + SPREAD_ADD);
        int pellets = Math.max(1, spec.pellets());
        for (int i = 0; i < pellets; i++) {
            Vector dir = base.clone().add(new Vector(game.random.nextGaussian(), game.random.nextGaussian(), game.random.nextGaussian()).multiply(spread * 0.6))
                    .normalize();
            shot(from, dir, i == 0);
        }
        Location ml = from.toLocation(game.world);
        game.shotFx.spawn(ml, "fx_quad", ShotFx.FLASH, 0, ShotFx.scale(pellets > 1 ? 0.95f : 0.7f), Display.Billboard.CENTER, true, 3, ShotFx.EXTRA);
        if (!game.director.silent()) {
            // 그 총 소리를 낮게 늘여서(모드 소리는 보낸 높이가 곱해짐)
            Props.soundAround(ml, "gun." + spec.key() + ".fire", spec.fire(), 1f, 0.6f + game.random.nextFloat() * 0.06f, spec.pitch() * 0.7f,
                    Math.min(80, spec.soundRange()), -1);
        }
        if (shotsInMag % 6 == 0) sound("shot", Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.6f, 64);
        shotsInMag++;
        mag--;
    }

    /** 탄 하나: 블록과 서 있는 사람 몸 상자에 광선. 맞으면 피해, 탄 궤적과 탄착은 총 연출(ShotFx)을 그대로 쓴다. */
    private void shot(Vector from, Vector dir, boolean main) {
        double range = Math.min(64, spec.range());
        RayTraceResult rb = game.world.rayTraceBlocks(from.toLocation(game.world), dir, range, FluidCollisionMode.NEVER, true);
        double best = rb == null ? range : rb.getHitPosition().distance(from);
        Player hitP = null;
        Vector end = rb == null ? from.clone().add(dir.clone().multiply(range)) : rb.getHitPosition();
        for (Player q : game.mobs.standing()) {
            RayTraceResult r = q.getBoundingBox().expand(0.05).rayTrace(from, dir, best);
            if (r == null) continue;
            double d = r.getHitPosition().distance(from);
            if (d < best) {
                best = d;
                hitP = q;
                end = r.getHitPosition();
            }
        }
        tracer(from, end, dir, main);
        if (hitP != null) {
            hit(hitP, spec.damage() / Guns.singleShot(kind) * POWER * VS_PLAYER * Guns.falloff(best, spec.range()), "cause_g52");
        } else if (rb != null && rb.getHitBlock() != null && main) {
            game.shotFx.hit(end.toLocation(game.world), rb.getHitBlockFace(), rb.getHitBlock().getType(), dir, false);
        }
    }

    /** 탄 궤적(ShotFx.tracer 와 같은 모양을 이 개체 총구에서). */
    private void tracer(Vector from, Vector end, Vector dir, boolean main) {
        double travel = end.distance(from);
        if (travel < 1.5) return;
        float len = (float) Math.min(4.5, Math.max(0.8, travel * 0.25));
        float w = (main ? 0.32f : 0.26f) * (float) Math.min(1, 0.6 + travel / 40);
        int n = (int) Math.max(1, Math.min(3, Math.round(travel / 45)));
        ItemDisplay d = game.shotFx.spawn(from.toLocation(game.world), "fx_tracer", ShotFx.TRACER, 0, ShotFx.along(dir, w, len), Display.Billboard.FIXED,
                true, n + 4, main ? ShotFx.NORMAL : ShotFx.EXTRA);
        game.shotFx.drift(d, end.clone().subtract(dir.clone().multiply(len)).toLocation(game.world), n);
    }

    // ------------------------------------------------------------------ 총 모델, 이름표(닻에 태운 디스플레이)

    private ItemStack gunItem() {
        return Props.vanillaLook(Material.STICK, spec.vanillaModel(), spec.key(), Component.empty());
    }

    private float gunScale() {
        if (!Props.has(spec.key())) return 0.7f;
        return switch (kind) {
            case PISTOL -> 0.32f;
            case SMG -> 0.5f;
            case SHOTGUN, SNIPER -> 0.36f;
            default -> 0.4f;
        };
    }

    /** 총 길이(블록, 총구 자리 어림). */
    private double gunLen() {
        return switch (kind) {
            case PISTOL -> 0.45;
            case SMG -> 0.65;
            default -> 1.0;
        };
    }

    private float lift() {
        return body == null || body.anchor == null ? 0f : (float) (pos.getY() - body.anchor.getLocation().getY());
    }

    private Matrix4f gunMatrix() {
        // 아이템 디스플레이는 모델을 y 로 반 바퀴 돌려 그리므로 총 모델의 총구(-Z)가 그대로 몸 앞(+Z)을 본다
        return new Matrix4f().translate(0, lift(), 0).rotateY((float) Math.toRadians(-yaw)).rotateX((float) Math.toRadians(aimPitch))
                .translate(0, GUN_Y, GUN_Z).scale(gunScale());
    }

    private void makeGun() {
        if (body == null || body.anchor == null) return;
        ItemStack it = gunItem();
        Matrix4f m = gunMatrix();
        gunD = body.anchor.getWorld().spawn(body.anchor.getLocation(), ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(it);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED);
            d.setBrightness(Body.LIGHT);
            d.setViewRange(1.5f);
            d.setDisplayWidth(2.4f);
            d.setDisplayHeight(2.4f);
            d.setInterpolationDuration(2);
            d.setTransformationMatrix(m);
        });
        body.anchor.addPassenger(gunD);
        gunYaw = yaw;
        gunPitch = aimPitch;
    }

    /** 총이 몸 방향과 겨눈 높이각을 따라감(4도 넘게 달라지면 2틱 보간으로). */
    private void syncGun() {
        if (gunD == null || !gunD.isValid()) return;
        float dy = Float.isNaN(gunYaw) ? 999f : Math.abs(wrap(yaw - gunYaw));
        if (dy < 4f && Math.abs(aimPitch - gunPitch) < 3f) return;
        gunYaw = yaw;
        gunPitch = aimPitch;
        gunD.setInterpolationDelay(0);
        gunD.setInterpolationDuration(2);
        gunD.setTransformationMatrix(gunMatrix());
    }

    private void makeTag() {
        if (body == null || body.anchor == null) return;
        float y = lift() + 0.78f;
        tag = body.anchor.getWorld().spawn(body.anchor.getLocation(), TextDisplay.class, t -> {
            t.setPersistent(false);
            t.setBillboard(Display.Billboard.CENTER);
            t.setSeeThrough(false);
            t.setShadowed(false);
            t.setDefaultBackground(true);
            t.setViewRange(0.7f);
            t.setBrightness(new Display.Brightness(12, 12));
            t.setTransformationMatrix(new Matrix4f().translate(0, y, 0).scale(0.9f));
            t.text(tagText(-1));
        });
        body.anchor.addPassenger(tag);
        tagShown = "";
    }

    /** 이름표 글: 주인 이름(가끔 한 글자가 분홍 실눈 기호), 이름을 못 쓰면 실눈 기호만. */
    private Component tagText(int glitch) {
        if (ownerName == null || ownerName.isEmpty()) return Component.text(SQUINT + " " + SQUINT, PINK);
        Component c = Component.empty();
        for (int i = 0; i < ownerName.length(); i++) {
            if (i == glitch) c = c.append(Component.text(SQUINT, PINK));
            else c = c.append(Component.text(String.valueOf(ownerName.charAt(i)), NamedTextColor.WHITE));
        }
        return c;
    }

    private void showTag(boolean force) {
        if (tag == null || !tag.isValid()) return;
        int n = ownerName == null ? 0 : ownerName.length();
        if (n > 0) {
            if (glitchIdx >= 0 && age >= glitchAt + GLITCH_T) glitchIdx = -1;
            if (glitchIdx < 0 && age >= glitchAt + GLITCH_EVERY) {
                glitchAt = age;
                glitchIdx = game.random.nextInt(n);
            }
        }
        String key = (ownerName == null ? "" : ownerName) + "#" + glitchIdx;
        if (!force && key.equals(tagShown)) return;
        tagShown = key;
        tag.text(tagText(glitchIdx));
    }

    private void dropDisplays() {
        if (gunD != null) gunD.remove();
        if (tag != null) tag.remove();
        gunD = null;
        tag = null;
    }

    private static float wrap(float d) {
        d %= 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    // ------------------------------------------------------------------ 죽음: 총은 주인에게

    /** 주운 주무기를 주인에게 돌려준다(주인이 접속 중일 때). */
    private void giveBack() {
        Gun g = held;
        held = null;
        if (g == null || ownerId == null) return;
        Player o = Bukkit.getPlayer(ownerId);
        if (o == null || !o.isOnline() || game.guns.arms(o) == null) return;
        if (game.guns.primaryGun(o) != null) return; // 그사이 다른 주무기를 얻었으면 그대로 둠
        game.guns.givePrimary(o, g);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        dropDisplays();
        super.die(d); // 사람이 잡았으면 여기서 주인이 상점 앞에 부활(Players.freeFrom)
        giveBack();
    }

    @Override
    public void remove() {
        dropDisplays();
        super.remove();
        giveBack();
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " gun=" + kind + (held != null ? "(held)" : "") + " mag=" + mag + "/" + spec.mag() + " owner="
                + (ownerName == null ? "-" : ownerName) + " prey=" + (prey == null ? "-" : prey.getName());
    }
}
