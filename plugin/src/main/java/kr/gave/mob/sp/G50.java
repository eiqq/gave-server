package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.mob.Species;
import kr.gave.player.DeathBorn;
import kr.gave.player.Detachable;
import kr.gave.player.PState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * G50 사람 탈 게이브(함정, 방해, 사람 죽음의 결과로만 생김).
 *
 * <p>도감: 방금 죽은 사람 스킨의 몸이 쓰러진 자리에서 일어나 무리로 걸어 들어와 같이 선다. 근처 사람의 시선을 0.5초 늦게 따라 한다.
 * 얼굴에는 홍조와 작은 점 입이 하나 더 얹혀 있고, 등에서 막대기 끝 둘이 까딱이며, 5블록 안에선 머리가 게이브 얼굴로 바뀐다. 이름표는 보는
 * 사람 쪽으로 돌지 않는다. 누군가의 등 뒤에서 머리를 맞대면 들러붙어 3초 시야를 가리고 지속 피해, 끝까지(5초) 붙어 있으면 그 사람이 갇히고
 * 둘이 함께 겹얼굴 게이브(G27, 얼굴 둘)가 된다. 흉내 중에는 흘린 탄에 반응하지 않고, 겨눈 사격(조준선 0.5초)이나 폭발, 불에 몸이 무너지며
 * 얼굴 하나가 튀어 달아난다(그것까지 잡아야 보상).
 *
 * <p>26.3 에서 확인하고 쓴 것: 마네킹 엔티티({@link Mannequin}): 프로필은 {@code ResolvableProfile.resolvableProfile(사람 프로필)} 로 그 사람
 * 스킨, 설명 줄은 {@code setDescription(null)} 로 숨김, 손에 그 사람 총 모델, 자세(누움 → 섬), 머리와 몸 방향은 teleport 의 yaw, pitch.
 * 마네킹은 무적, 소리 없음, 중력 없음, 충돌 없음, 저장 안 함이고 맞는 판정은 여느 종처럼 플러그인 구(머리, 몸통, 다리)다. 얹는 조각(덧그림,
 * 5블록 얼굴, 등 막대기, 고정 이름표)은 마네킹에 태운 디스플레이라 마네킹과 한 몸으로 움직인다. 그 사람이 '내 얼굴, 내 이름 쓰지 않기'면
 * 기본 스킨과 이름 없는 표. 소환 명령처럼 죽은 사람 없이 생겨도 같다. 실제 채팅 되풀이는 하지 않는다(말풍선 없음).
 * 딸랑이 같은 끌림에는 흉내 중에 반응하지 않는다(사람인 척이 깨지지 않게). 죽음 결과 종이라 오래 살면 변함에서 뺐다.
 *
 * <p>팩 계약(tools/gabe/sp/g50.py): gave:gabe/g50(달아나는 얼굴), g50_mask(덧그림), g50_face(가까이서 보이는 얼굴), g50_back(등 막대기),
 * 덮개 g50_latch. 소리 rise(32), latch(24), collapse(24), inside(듣기), hurt(웃음 섞인 울음). 원인 글귀 cause_g50.
 */
public final class G50 extends Gabe implements DeathBorn, Detachable {

    // ------------------------------------------------------------------ 수치(도감)

    /** 걷는 빠르기(사람 걸음쯤), 몸 중심 높이(바닥에서), 달아나는 얼굴 높이와 빠르기. */
    static final double WALK = 0.19, TORSO = 0.9, FACE_H = 1.25, FLEE_V = 0.3;
    /** 일어나는 틱, 무리에 끼는 거리, 서 있다 등 뒤를 노리기까지 틱. */
    static final int RISE_T = 30, WAIT_MIN = 60, WAIT_VAR = 60;
    static final double JOIN = 2.6, VICTIM_R = 10;
    /** 시선 따라 하기 늦음(0.5초). */
    static final int LOOK_LAG = 10;
    /** 등 뒤 자리(사람 뒤 이만큼), 붙는 거리, 들켰다고 보는 거리와 시선 코사인. */
    static final double BEHIND = 0.75, REACH = 0.5, SEEN_R = 3.5, SEEN_COS = 0.6;
    /** 들러붙음: 끝까지 틱(5초), 시야 가림 틱(3초), 피해 간격과 피해, 탈출 연타 수. */
    static final int LATCH_T = 100, COVER_T = 60, BITE_EVERY = 10, ESCAPE = 14;
    static final double BITE = 3;
    /** 게이브 얼굴이 보이는 거리(이 안의 사람에게만), 무너진 뒤 남는 체력 비율, 달아나는 틱, 달아난 얼굴의 박치기. */
    static final double NEAR = 5.0, LEFT_HP = 0.35, BUMP = 3;
    static final int FLEE_T = 120, BUMP_EVERY = 20;
    /** 마네킹 치수(블록): 목 높이, 머리 가운데(목 위), 등 막대기 높이, 이름표 높이. */
    static final float NECK = 1.40625f, HEAD_UP = 0.234f, BACK_Y = 1.3f, TAG_Y = 2.14f;

    enum Phase { RISE, JOIN, STAND, SNEAK, LATCH, FLEE, HUNT }

    private Phase phase = Phase.RISE;
    private int phaseAt, waitUntil, nextBite, lastBump = -100, flipAt, mountedAt;
    private Mannequin doll, husk;
    private ItemDisplay maskD, faceD, backD;
    private TextDisplay tagD;
    private float attachY = 1.8f, dollYaw, dollPitch, sentYaw = Float.NaN, sentPitch;
    private boolean measured, warned, born;
    private double sx = Double.NaN, sy, sz;
    private float sYaw, sPitch;
    private final Set<UUID> faceShown = new HashSet<>();
    private final float[] lagYaw = new float[LOOK_LAG], lagPitch = new float[LOOK_LAG];
    private int lagAt;
    private ResolvableProfile profile;
    private String name;
    private ItemStack gun;
    private Player victim, held;
    private int side = 1;

    public G50(Game game, Species sp) {
        super(game, sp);
        radius = 0.35;
        hover = TORSO;
        baseSpeed = WALK;
        disguised = true;
        noMutate = true;
        leaveCorpse = false;
    }

    @Override
    protected void build() {
        // 엔진 몸은 탈을 벗은 뒤의 얼굴. 탈을 쓴 동안은 빈 모델
        body = Body.of(this, Body.EMPTY, 1.6f, 1.2f);
        Location l = feet();
        l.setYaw(yaw);
        doll = game.world.spawn(l, Mannequin.class, m -> {
            m.setPersistent(false);
            m.setInvulnerable(true);
            m.setSilent(true);
            m.setGravity(false);
            m.setCollidable(false);
            m.setImmovable(true);
            m.setRemoveWhenFarAway(false);
            m.setCanPickupItems(false);
            m.setDescription(null);
            try {
                m.setPose(Pose.SLEEPING, true);
            } catch (RuntimeException ignored) {
                // 자세를 못 바꾸면 선 채로
            }
        });
        dollYaw = yaw;
    }

    @Override
    protected void spawned() {
        side = game.random.nextBoolean() ? 1 : -1;
        if (game.director.cageSpawning()) {
            // 우리 개체: 사람 쪽 일 없이 선 모습만
            stand();
            phase(Phase.STAND);
            return;
        }
        if (noReward) {
            // 갈라짐 같은 판 규칙으로 생긴 조각: 탈 없이 얼굴만
            dropAll();
            disguised = false;
            radius = 0.3;
            hover = FACE_H;
            baseSpeed = 0.17;
            leaveCorpse = true;
            body.model("gabe/g50");
            phase(Phase.HUNT);
            return;
        }
        adopt();
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

    /** 사람 죽음의 결과로 생김: 그 사람 스킨, 이름, 쓰던 총 모형(허락한 사람만). */
    @Override
    public void bornFrom(Player dead, PState state, Location at) {
        if (born || dead == null) return;
        born = true;
        boolean like = state == null || !state.noLikeness;
        if (like) {
            try {
                profile = ResolvableProfile.resolvableProfile(dead.getPlayerProfile());
            } catch (RuntimeException e) {
                profile = null;
            }
            name = dead.getName();
        }
        Guns.Arms a = game.guns.arms(dead);
        Guns.Kind k = a != null && a.primary != null ? a.primary : Guns.Kind.PISTOL;
        Guns.Spec s = Guns.SPECS.get(k);
        gun = Props.vanillaLook(Material.STICK, s.vanillaModel(), s.key(), Component.empty());
        dress();
        sound("rise", Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.6f, 32);
    }

    /** 마네킹에 스킨과 총 모형을 입힘. */
    private void dress() {
        if (doll == null || !doll.isValid()) return;
        try {
            if (profile != null) doll.setProfile(profile);
            if (gun != null && doll.getEquipment() != null) doll.getEquipment().setItemInMainHand(gun);
        } catch (RuntimeException e) {
            game.plugin.getLogger().warning("[G50] 마네킹 꾸미기 실패: " + e);
        }
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    private Location feet() {
        return new Location(game.world, pos.getX(), floor(), pos.getZ(), dollYaw, dollPitch);
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        switch (phase) {
            case RISE -> rise();
            case JOIN -> join();
            case STAND -> mingle();
            case SNEAK -> sneak();
            case LATCH -> latch();
            case FLEE -> flee();
            case HUNT -> hunt();
        }
        if (doll != null) syncDoll();
    }

    /** 쓰러진 자리에 누워 있다가 일어남. */
    private void rise() {
        hold();
        if (inPhase() >= RISE_T) {
            stand();
            phase(Phase.JOIN);
        }
    }

    /** 일어섬: 자세를 바꾸고 얹는 조각들을 태운다. */
    private void stand() {
        if (doll == null || !doll.isValid()) return;
        try {
            doll.setPose(Pose.STANDING, true);
        } catch (RuntimeException ignored) {
            // 그대로
        }
        dress();
        if (maskD == null) mountPieces();
    }

    /** 흐름장을 따라 사람들 쪽으로 걸어 들어감. */
    private void join() {
        Player p = nearest(200);
        if (p == null) {
            hold();
            return;
        }
        if (flat(p) <= JOIN) {
            hold();
            waitUntil = age + cd(WAIT_MIN) + game.random.nextInt(cd(WAIT_VAR));
            phase(Phase.STAND);
            return;
        }
        follow(speed());
        lookAlong();
    }

    /** 무리에 섞여 섬: 가장 가까운 사람의 시선을 0.5초 늦게 따라 한다. 조금 뒤 누군가의 등 뒤를 노린다. */
    private void mingle() {
        hold();
        // 곁에 있던 사람이 쓰러지거나 나가고 남은 사람이 64블록 밖이면 영영 서 있었다. 반경 제한 없이 찾아 다시 합류하러 간다(2026-11-11).
        Player p = nearest(400);
        if (p == null) return;
        if (flat(p) > JOIN + 2.5) {
            phase(Phase.JOIN);
            return;
        }
        Location l = p.getLocation();
        float y = lagYaw[lagAt], pt = lagPitch[lagAt];
        lagYaw[lagAt] = l.getYaw();
        lagPitch[lagAt] = l.getPitch();
        lagAt = (lagAt + 1) % LOOK_LAG;
        if (inPhase() > LOOK_LAG) turnDoll(y, pt, 20f);
        if (game.director.inCage(this) || age < waitUntil) return;
        Player v = pickVictim();
        if (v != null) {
            victim = v;
            phase(Phase.SNEAK);
        } else waitUntil = age + 30;
    }

    /** 등 뒤를 노릴 사람: 가까운 서 있는 사람 가운데 지금 나를 보고 있지 않은 사람. */
    private Player pickVictim() {
        List<Player> c = new ArrayList<>();
        for (Player p : game.mobs.standing()) {
            if (flat(p) > VICTIM_R || game.players.grabbedBy(p) != null) continue;
            if (!seenBy(p, 64)) c.add(p);
        }
        return c.isEmpty() ? null : c.get(game.random.nextInt(c.size()));
    }

    /** 이 사람이 나를 보고 있나(r 블록 안, 시선 쪽 원뿔). */
    private boolean seenBy(Player p, double r) {
        Location l = p.getLocation();
        Vector to = new Vector(pos.getX() - l.getX(), 0, pos.getZ() - l.getZ());
        double d = to.length();
        if (d > r || d < 1e-3) return d <= r;
        Vector f = l.getDirection().setY(0);
        if (f.lengthSquared() < 1e-6) return false;
        return f.normalize().dot(to.multiply(1 / d)) > SEEN_COS;
    }

    /** 등 뒤로 돌아 들어감. 가까이서 들키면 선 척으로 돌아간다. 등 뒤 자리에 닿으면 머리를 맞댄다. */
    private void sneak() {
        Player v = victim;
        if (v == null || !standing(v) || flat(v) > VICTIM_R + 6 || game.players.grabbedBy(v) != null) {
            backOff(40);
            return;
        }
        Location l = v.getLocation();
        Vector f = l.getDirection().setY(0);
        if (f.lengthSquared() < 1e-6) f = new Vector(0, 0, 1);
        f.normalize();
        Vector spot = new Vector(l.getX() - f.getX() * BEHIND, pos.getY(), l.getZ() - f.getZ() * BEHIND);
        Vector to = spot.clone().subtract(pos).setY(0);
        double d = to.length();
        Vector rel = new Vector(pos.getX() - l.getX(), 0, pos.getZ() - l.getZ());
        boolean behind = rel.lengthSquared() > 1e-6 && f.dot(rel.clone().normalize()) < -0.3;
        if (!behind && seenBy(v, SEEN_R)) {
            // 들킴: 아무 일 없던 척
            backOff(cd(WAIT_MIN) + game.random.nextInt(cd(WAIT_VAR)));
            return;
        }
        if (d <= REACH && behind) {
            startLatch(v);
            return;
        }
        if (!behind && rel.lengthSquared() > 1e-6 && rel.length() < 1.6 && d > 1e-4) {
            // 옆으로 돌아서 감(사람 몸을 뚫고 가지 않게)
            Vector sideways = new Vector(-rel.getZ(), 0, rel.getX()).normalize().multiply(side);
            to = sideways.multiply(0.7).add(to.multiply(0.5 / d));
            d = to.length();
        }
        if (d > 1e-4) vel.copy(to.multiply(Math.min(speed(), d) / d));
        else hold();
        lookAlong();
        if (inPhase() > 200) backOff(60);
    }

    private void backOff(int wait) {
        victim = null;
        hold();
        waitUntil = age + wait;
        phase(Phase.STAND);
    }

    private void startLatch(Player v) {
        if (!grab(v, ESCAPE)) {
            backOff(100);
            return;
        }
        held = v;
        victim = null;
        mark(v);
        noPush = true;
        game.players.cover(v, "g50_latch", COVER_T);
        sound("latch", Sound.BLOCK_HONEY_BLOCK_SLIDE, 0.6f, 24);
        nextBite = age + BITE_EVERY;
        phase(Phase.LATCH);
    }

    /** 등 뒤에서 머리를 맞댄 채: 지속 피해, 끝까지 가면 그 사람이 갇히고 둘이 겹얼굴이 된다. */
    private void latch() {
        Player v = held;
        if (v == null || !v.isOnline() || game.players.grabbedBy(v) != this) {
            unlatch();
            return;
        }
        Location l = v.getLocation();
        Vector f = l.getDirection().setY(0);
        if (f.lengthSquared() < 1e-6) f = new Vector(0, 0, 1);
        f.normalize();
        Vector want = new Vector(l.getX() - f.getX() * 0.42, pos.getY(), l.getZ() - f.getZ() * 0.42);
        Vector d = want.subtract(pos).setY(0);
        vel.copy(d.length() > 0.4 ? d.normalize().multiply(0.4) : d);
        turnDoll(l.getYaw(), 12f, 30f);
        if (age >= nextBite) {
            nextBite = age + BITE_EVERY;
            hit(v, BITE, "cause_g50");
            if (held == null || game.players.grabbedBy(v) != this) {
                unlatch();
                return;
            }
        }
        if (inPhase() % 30 == 15) sound("latch", Sound.BLOCK_HONEY_BLOCK_SLIDE, 0.6f, 24);
        if (inPhase() >= LATCH_T) merge(v);
    }

    /** 붙음이 풀림(탈출, 동료가 뗌, 쓰러짐): 선 척으로. */
    private void unlatch() {
        Player v = held;
        held = null;
        noPush = false;
        if (target != null) mark(null);
        if (v != null && v.isOnline()) {
            PState s = game.players.get(v);
            if ("g50_latch".equals(s.cover)) game.players.uncover(v);
        }
        if (phase == Phase.LATCH) backOff(100);
    }

    /** 끝까지 붙음: 그 사람이 갇히고 둘이 함께 겹얼굴 게이브(얼굴 둘)가 된다. 겹얼굴을 못 놓으면 이 개체가 그 사람을 가둔 채 탈을 벗는다. */
    private void merge(Player v) {
        held = null;
        noPush = false;
        game.players.release(v);
        PState s = game.players.get(v);
        if ("g50_latch".equals(s.cover)) game.players.uncover(v);
        Gabe c = null;
        if (Species.G27.implemented() && standing(v)) {
            c = child(Species.G27, new Vector(pos.getX(), floor() + 1.55, pos.getZ()));
            if (c != null) {
                c.maxHp *= Math.max(1.0, game.director.hpMul(Species.G27) * 0.5);
                c.hp = c.maxHp;
                c.yaw = dollYaw;
            }
        }
        sound("latch", Sound.BLOCK_HONEY_BLOCK_SLIDE, 0.5f, 24);
        if (c != null) {
            game.players.trap(v, c);
            game.players.transferTrapped(this, c);
            remove();
            return;
        }
        if (standing(v)) game.players.trap(v, this);
        collapse(null);
    }

    // ------------------------------------------------------------------ 탈을 벗음(무너짐)과 달아나는 얼굴

    /** 몸이 무너지고 얼굴 하나가 튀어 달아난다. */
    private void collapse(Vector dir) {
        if (!disguised && doll == null) return;
        if (held != null) {
            Player v = held;
            held = null;
            if (v.isOnline() && game.players.grabbedBy(v) == this) game.players.release(v);
            if (v.isOnline() && "g50_latch".equals(game.players.get(v).cover)) game.players.uncover(v);
        }
        victim = null;
        if (target != null) mark(null);
        disguised = false;
        double fl = floor();
        dropPieces();
        Mannequin m = doll;
        doll = null;
        if (m != null && m.isValid()) {
            // 바닐라 쓰러지는 죽음 모습으로 무너짐(20틱 뒤 저절로 사라짐). 떨굴 것이 없게 손을 비움
            try {
                if (m.getEquipment() != null) m.getEquipment().clear();
                m.setInvulnerable(false);
                m.setHealth(0);
                husk = m;
            } catch (RuntimeException e) {
                m.remove();
            }
        }
        sound("collapse", Sound.ENTITY_SLIME_SQUISH, 0.6f, 24);
        game.shotFx.bloodHit(new Location(game.world, pos.getX(), fl + 1.2, pos.getZ()), dir, false);
        // 얼굴 하나가 머리 자리에서 튀어나옴
        radius = 0.3;
        hover = FACE_H;
        baseSpeed = FLEE_V;
        noPush = false;
        leaveCorpse = true;
        pos.setY(fl + 1.6);
        hp = Math.min(hp, Math.max(1, maxHp * LEFT_HP));
        body.model("gabe/g50");
        body.look.rate = 2.0;
        flipAt = age + 10;
        phase(Phase.FLEE);
    }

    /** 달아남: 가장 가까운 사람 반대쪽으로 갈지자. 끝나면 돌아와 박는다. */
    private void flee() {
        if (husk != null && !husk.isValid()) husk = null;
        Player p = nearest(64);
        if (p == null) {
            hold();
        } else {
            Vector away = toward(p).multiply(-1);
            if (age >= flipAt) {
                side = -side;
                flipAt = age + 8 + game.random.nextInt(8);
            }
            Vector v = away.clone().add(new Vector(-away.getZ(), 0, away.getX()).multiply(0.7 * side));
            dash(v, speed());
            if (wallAhead(0.7)) side = -side;
            face(pos.clone().add(vel));
            pitch = 0;
        }
        if (inPhase() >= FLEE_T) {
            body.look.rate = 1.0;
            baseSpeed = 0.17;
            phase(Phase.HUNT);
        }
    }

    /** 돌아와 박음(약한 박치기). */
    private void hunt() {
        if (husk != null && !husk.isValid()) husk = null;
        Player p = nearest(64);
        if (p == null) {
            follow(speed());
            return;
        }
        follow(speed());
        face(chest(p));
        if (target != p && dist(p) < 8) mark(p);
        if (age - lastBump >= cd(BUMP_EVERY) && touches(p, 0.1)) {
            lastBump = age;
            hit(p, BUMP, "cause_g50");
            knock(p, toward(p), 0.4, 0.2);
        }
    }

    // ------------------------------------------------------------------ 마네킹과 얹는 조각

    private double flat(Player p) {
        Location l = p.getLocation();
        return Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
    }

    /** 걷는 방향을 봄. */
    private void lookAlong() {
        if (vel.lengthSquared() < 1e-6) return;
        turnDoll((float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ())), 0f, 20f);
    }

    /** 마네킹 머리와 몸 방향을 그쪽으로(한 틱에 max 도까지). */
    private void turnDoll(float wantYaw, float wantPitch, float max) {
        float d = wrap(wantYaw - dollYaw);
        dollYaw += Math.max(-max, Math.min(max, d));
        dollPitch += Math.max(-max, Math.min(max, wantPitch - dollPitch));
        yaw = dollYaw;
    }

    /** 마네킹을 이 개체 자리로(달라졌을 때만), 얹는 조각의 방향과 5블록 얼굴 보임을 맞춘다. */
    private void syncDoll() {
        Mannequin m = doll;
        if (m == null) return;
        if (!m.isValid()) {
            doll = null;
            return;
        }
        Location l = feet();
        double dx = l.getX() - sx, dy = l.getY() - sy, dz = l.getZ() - sz;
        if (Double.isNaN(sx) || dx * dx + dy * dy + dz * dz > 1e-5 || Math.abs(wrap(dollYaw - sYaw)) > 0.5f || Math.abs(dollPitch - sPitch) > 0.5f) {
            if (!m.teleport(l) && !warned) {
                warned = true;
                game.plugin.getLogger().warning("[G50] 마네킹 teleport 거절(탄 것이 있어서일 수 있음)");
            }
            sx = l.getX();
            sy = l.getY();
            sz = l.getZ();
            sYaw = dollYaw;
            sPitch = dollPitch;
        }
        if (maskD == null) return;
        if (!measured && age - mountedAt >= 2 && maskD.isValid()) {
            // 서버가 태운 자리를 한 번 재서 조각 높이를 맞춤(클라와 같은 식)
            float a = (float) (maskD.getLocation().getY() - m.getLocation().getY());
            if (a > 0.3f && a < 3f) {
                attachY = a;
                sentYaw = Float.NaN;
            }
            measured = true;
        }
        if (Float.isNaN(sentYaw) || Math.abs(wrap(dollYaw - sentYaw)) > 3f || Math.abs(dollPitch - sentPitch) > 4f) posePieces(3);
        if (every(5)) showFace();
    }

    private Matrix4f headMatrix(float scale) {
        return new Matrix4f().translate(0, NECK - attachY, 0).rotateY((float) Math.toRadians(-dollYaw)).rotateX((float) Math.toRadians(dollPitch))
                .translate(0, HEAD_UP, 0).scale(scale).rotateY((float) Math.PI);
    }

    private Matrix4f backMatrix() {
        return new Matrix4f().translate(0, BACK_Y - attachY, 0).rotateY((float) Math.toRadians(-dollYaw)).translate(0, 0, -0.14f).rotateY((float) Math.PI);
    }

    private Matrix4f tagMatrix() {
        return new Matrix4f().translate(0, TAG_Y - attachY, 0).rotateY((float) Math.toRadians(-dollYaw)).scale(0.95f);
    }

    private void posePieces(int ticks) {
        sentYaw = dollYaw;
        sentPitch = dollPitch;
        set(maskD, headMatrix(1f), ticks);
        set(faceD, headMatrix(1.04f), ticks);
        set(backD, backMatrix(), ticks);
        if (tagD != null && tagD.isValid()) {
            tagD.setInterpolationDelay(0);
            tagD.setInterpolationDuration(ticks);
            tagD.setTransformationMatrix(tagMatrix());
        }
    }

    private static void set(ItemDisplay d, Matrix4f m, int ticks) {
        if (d == null || !d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformationMatrix(m);
    }

    private ItemDisplay piece(String model, Matrix4f m, boolean visible) {
        ItemStack it = Body.item(model, "", new LookState(), game.worldTime());
        return game.world.spawn(doll.getLocation(), ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(it);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED);
            d.setBrightness(Body.LIGHT);
            d.setViewRange(1.0f);
            d.setDisplayWidth(1.6f);
            d.setDisplayHeight(1.6f);
            d.setInterpolationDuration(3);
            d.setTransformationMatrix(m);
            d.setVisibleByDefault(visible);
        });
    }

    /** 덧그림, 5블록 얼굴(기본은 안 보임), 등 막대기, 고정 이름표를 마네킹에 태운다. */
    private void mountPieces() {
        if (doll == null || !doll.isValid()) return;
        maskD = piece("gabe/g50_mask", headMatrix(1f), true);
        faceD = piece("gabe/g50_face", headMatrix(1.04f), false);
        backD = piece("gabe/g50_back", backMatrix(), true);
        Matrix4f tm = tagMatrix();
        Component text = name == null ? Component.text("     ") : Component.text(name, NamedTextColor.WHITE);
        tagD = game.world.spawn(doll.getLocation(), TextDisplay.class, t -> {
            t.setPersistent(false);
            // 보는 사람 쪽으로 돌지 않는 고정 이름표(몸 방향을 따름)
            t.setBillboard(Display.Billboard.FIXED);
            t.setSeeThrough(false);
            t.setShadowed(false);
            t.setDefaultBackground(true);
            t.setViewRange(0.7f);
            t.setBrightness(new Display.Brightness(12, 12));
            t.setInterpolationDuration(3);
            t.setTransformationMatrix(tm);
            t.text(text);
        });
        doll.addPassenger(maskD);
        doll.addPassenger(faceD);
        doll.addPassenger(backD);
        doll.addPassenger(tagD);
        sentYaw = dollYaw;
        sentPitch = dollPitch;
        measured = false;
        mountedAt = age;
    }

    /** 5블록 안 사람에게만 게이브 얼굴을 보임(0.5블록 문턱). */
    private void showFace() {
        if (faceD == null || !faceD.isValid()) return;
        double hx = pos.getX(), hy = floor() + 1.64, hz = pos.getZ();
        for (Player p : game.world.getPlayers()) {
            Location e = p.getEyeLocation();
            double d = Math.sqrt((e.getX() - hx) * (e.getX() - hx) + (e.getY() - hy) * (e.getY() - hy) + (e.getZ() - hz) * (e.getZ() - hz));
            UUID id = p.getUniqueId();
            boolean was = faceShown.contains(id);
            boolean now = was ? d <= NEAR + 0.5 : d <= NEAR;
            if (now == was) continue;
            if (now) {
                faceShown.add(id);
                p.showEntity(game.plugin, faceD);
            } else {
                faceShown.remove(id);
                p.hideEntity(game.plugin, faceD);
            }
        }
    }

    private void dropPieces() {
        if (maskD != null) maskD.remove();
        if (faceD != null) faceD.remove();
        if (backD != null) backD.remove();
        if (tagD != null) tagD.remove();
        maskD = faceD = backD = null;
        tagD = null;
        faceShown.clear();
    }

    private void dropAll() {
        dropPieces();
        if (doll != null) doll.remove();
        if (husk != null && husk.isValid()) husk.remove();
        doll = null;
        husk = null;
    }

    private static float wrap(float d) {
        d %= 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    // ------------------------------------------------------------------ 맞음, 떼기, 듣기, 끌림

    @Override
    public List<double[]> spheres() {
        if (!disguised) return super.spheres();
        double fl = floor();
        List<double[]> out = new ArrayList<>(3);
        out.add(new double[] {pos.getX(), fl + 1.62, pos.getZ(), 0.28});
        out.add(new double[] {pos.getX(), fl + 1.05, pos.getZ(), 0.36});
        out.add(new double[] {pos.getX(), fl + 0.42, pos.getZ(), 0.3});
        return out;
    }

    @Override
    public Vector head() {
        return disguised ? new Vector(pos.getX(), floor() + 1.62, pos.getZ()) : pos;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (disguised) {
            DamageType t = d.type();
            boolean gun = t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER;
            // 흉내 중: 흘린 탄은 무시, 겨눈 사격(조준선 0.5초)과 폭발, 불에 탈이 무너짐
            if (gun && !d.aimed()) return false;
            if (t == DamageType.BITE) return false;
            // 이 한 발은 탈이 받아 냄: 몸이 무너지고 얼굴이 남은 체력으로 튀어나온다
            double before = hp;
            collapse(d.dir());
            game.mobs.onHurt(this, d, Math.max(0, before - hp));
            return true;
        }
        return super.damage(d);
    }

    /** 조명탄, 정화: 탈이 벗겨짐. */
    @Override
    public void reveal() {
        if (disguised && phase != Phase.RISE) collapse(null);
    }

    /** 흉내 중에는 끌리지 않는다(사람인 척). */
    @Override
    public void lure(Vector at, int ticks) {
        if (!disguised) super.lure(at, ticks);
    }

    @Override
    public String listenSound() {
        return disguised ? "gave:gabe.g50.inside" : null;
    }

    @Override
    public void detached(Player v, Player helper, String how) {
        if (v == held) unlatch();
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        if (held != null) {
            Player v = held;
            held = null;
            if (v.isOnline() && game.players.grabbedBy(v) == this) game.players.release(v);
            if (v.isOnline() && "g50_latch".equals(game.players.get(v).cover)) game.players.uncover(v);
        }
        dropAll();
        super.die(d);
    }

    @Override
    public void remove() {
        if (held != null) {
            Player v = held;
            held = null;
            if (v.isOnline() && game.players.grabbedBy(v) == this) game.players.release(v);
            if (v.isOnline() && "g50_latch".equals(game.players.get(v).cover)) game.players.uncover(v);
        }
        dropAll();
        super.remove();
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + (disguised ? " disg" : "") + (name != null ? " as=" + name : "") + (held != null ? " held=" + held.getName() : "")
                + (doll != null ? " doll" : "") + String.format(" attach=%.2f", attachY);
    }
}
