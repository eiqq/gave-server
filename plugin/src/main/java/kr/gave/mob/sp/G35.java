package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.Detachable;
import kr.gave.player.Life;
import kr.gave.player.PState;
import kr.gave.player.Players;

/**
 * G35 엎드린 척 게이브(함정, 미리 깔림). 이 게임의 쓰러진 동료 표시(바닥 원, 이름, 남은 초)와 같은 부품을 달고 엎드려 흐느낀다. 그 밑은 분홍
 * 뒤통수만 보이는 엎드린 얼굴이고 막대기 둘은 몸 밑에 접혀 끝만 비친다.
 *
 * <p>도감: 누가 이것을 보고 돕기(일으키기)를 시작하면(이름표 초가 초록으로 바뀜) 2초째에 얼굴을 홱 돌려 그 사람 얼굴로 튀어 오른다. 얼굴에 달라붙어
 * 4초 동안 0.5초마다 피해와 화면 덮기(덮개 g35_face). 본인 돕기 연타 12번, 동료 돕기(떼기), 또는 동료가 쏴서(붙은 동안 25 피해) 떼면 떨어져
 * 2초 떤다. 못 떼면 그 사람은 쓰러지고, 웨이브마다 한 번 엎드린 척이 하나 더 생기며(보상 0), 이 개체는 기어가 다시 엎드린다.
 * 흉내 중에는 흘린 탄에 반응하지 않고 조준선을 0.5초 댄 사격, 폭발, 불, 조명탄에 드러난다. 드러난 개체는 기어 오다 3블록 안에서 얼굴로 뛰어오른다.
 *
 * <p>알아보는 신호: 남은 초가 진짜처럼 줄다가 멈추고 거꾸로 오른다. 바닥 원이 진짜(6틱마다)보다 느리게(9틱마다) 꺼졌다 켜진다. 이름은 지금 쓰러지지
 * 않은 참가자의 것이거나 비어 있다(탭 목록, 동료 줄과 견주면 드러남. '내 이름 쓰지 않기'인 사람 이름은 안 씀). HUD 의 쓰러진 동료 방향 표시가
 * 없다. 듣기(웅크리기)면 흐느낌 속 웃음.
 *
 * <p>도감과 다른 점: 진짜 쓰러진 사람은 반쯤 묻힌 사람 몸이 보이지만 이것은 엎드린 게이브 머리다(사람 몸은 못 그림). 원이 반대로 도는 신호는 원에
 * 방향 무늬가 없어 '느린 깜빡임'으로 바꿈. 돕는 사람 조준점의 일으키기 고리는 못 그린다(요청: Players 에 가짜 돕기 채널).
 *
 * <p>팩 계약(tools/gabe/sp/g35.py): 모델 {@code gave:gabe/g35} 상태 {@code ""}(엎드림), {@code "flip"}, {@code "latch"}, 덮개 {@code g35_face}.
 * 바닥 원은 연출 모델 {@code gave:fx_flat}(Players.RING_KIND). 소리 sob(16), snicker(8, 듣기), flip(20), latch(16). 원인 글귀 {@code cause_g35}.
 */
public final class G35 extends Gabe implements Detachable {

    // ------------------------------------------------------------------ 수치(도감)

    /** 엎드린 머리 가운데 높이(바닥에서), 드러난 뒤 기는 높이, 머리 반지름. */
    static final double PRONE_Y = 0.25, CRAWL_Y = 0.42, HEAD_R = 0.34;
    /** 돕기를 시작한 뒤 튀어 오르기까지(2초), 돕는 사람이 벗어나면 끊기는 거리와 시선 코사인. */
    static final int BAIT = 40;
    static final double HELP_R = 3.4, HELP_COS = 0.55;
    /** 얼굴까지 날아가는 틱, 달라붙어 있는 틱(4초), 피해와 간격, 본인 연타 수, 쏴서 떼는 피해. */
    static final int LEAP = 6, LATCH = 80, LATCH_EVERY = 10, ESCAPE = 12;
    static final double LATCH_DMG = 1.5, SHAKE_OFF = 25, FULL_DMG = 1000;
    /** 떨어져 떠는 틱, 드러난 채 기는 빠르기, 뛰어오르는 거리, 다시 뛰기까지, 쓰러뜨린 뒤 기어가 다시 엎드리기까지. */
    static final int STUN = 40, RELEAP = 60, RETREAT = 50;
    static final double CRAWL = 0.11, LEAP_R = 3.0;
    /** 가짜 바닥 원을 다시 찍는 간격(진짜는 Players.RING_EVERY 6틱: 느린 깜빡임이 알아보는 신호). */
    static final int FAKE_RING_EVERY = 9;
    /** 가짜 남은 초: 처음 값 범위, 바닥과 천장. */
    static final int SEC_MIN = 3, SEC_MAX = 11;

    enum Phase { PRONE, BAIT, LEAP, LATCH, STUN, CRAWL, RETREAT }

    /** 판마다: 덤으로 생긴 엎드린 척의 마지막 웨이브. */
    private static final Map<Game, int[]> EXTRA = new WeakHashMap<>();

    private Phase phase = Phase.PRONE;
    private int phaseAt, readyAt, nextSob, ringAt = -99;
    private Body.Part ring;
    private TextDisplay tag;
    private String name = "";
    private int sec, secDir = -1, secAt, secSteps;
    private boolean tagGreen;
    private Player helper, victim;
    private double shaken;
    private final Vector away = new Vector(1, 0, 0);

    public G35(Game game, Species sp) {
        super(game, sp);
        radius = HEAD_R;
        hover = PRONE_Y;
        baseSpeed = CRAWL;
        disguised = true;
        noPush = true; // 쓰러진 사람 표시처럼 밀리지 않음
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g35", 3.0f, 3.0f);
        ring = body.attach("fx_flat");
        if (ring != null) {
            ring.follow = false;
            ring.pose(new Matrix4f().translate(0, (float) (0.04 - PRONE_Y), 0).scale(Players.RING_SCALE), 0);
            if (ring.d != null) {
                ring.d.setBrightness(new Display.Brightness(15, 15));
                ring.d.setViewRange(1.5f);
            }
        }
    }

    @Override
    protected void spawned() {
        yaw = game.random.nextFloat() * 360f;
        nextSob = age + 20 + game.random.nextInt(60);
        sec = 6 + game.random.nextInt(5);
        secSteps = 2 + game.random.nextInt(2);
        secAt = age;
        // 우리(대기실) 개체는 참가자 이름을 쓰지 않는다
        name = game.director.cageSpawning() ? "" : pickName();
        showTag();
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    /** 지금 쓰러지지 않은 참가자 가운데 하나의 이름('내 이름 쓰지 않기'는 건너뜀). 없으면 빈 이름. */
    private String pickName() {
        List<String> names = new ArrayList<>();
        for (Player p : game.players.inPlay()) {
            PState s = game.players.get(p);
            if (s.life == Life.DOWNED || s.noLikeness) continue;
            names.add(p.getName());
        }
        return names.isEmpty() ? "" : names.get(game.random.nextInt(names.size()));
    }

    // ------------------------------------------------------------------ 쓰러짐 표시(진짜와 같은 부품)

    /**
     * 이름과 남은 초 글자(진짜 쓰러짐 표시와 같은 꼴: Players.downedTag, TAG_UP, TAG_SCALE). 엔진 Body 에 글 부품이 없어 종 파일에서 직접 만든다
     * (G26Tag 와 같은 사정). 닻의 승객이 아니라 제자리에 따로 둔다: 닻은 바닥 밑에 있어 승객으로 태우면 글자가 어둡게 보이고, 엎드린 동안은
     * 움직이지 않는다. 드러날 때, 죽을 때, 치워질 때 지운다.
     */
    private void showTag() {
        if (tag != null && tag.isValid()) return;
        Location at = new Location(game.world, pos.getX(), game.mobs.flow.floor(pos.getX(), pos.getZ()) + Players.TAG_UP, pos.getZ());
        tag = game.world.spawn(at, TextDisplay.class, t -> {
            t.setPersistent(false);
            t.setBillboard(Display.Billboard.CENTER);
            t.setDefaultBackground(false);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setShadowed(true);
            t.setSeeThrough(false);
            t.setViewRange(0.6f);
            t.setTeleportDuration(2);
            t.setTransformationMatrix(new Matrix4f().scale(Players.TAG_SCALE));
            t.text(Players.downedTag(name, sec, tagGreen));
        });
    }

    private void tagText() {
        if (tag != null && tag.isValid()) tag.text(Players.downedTag(name, sec, tagGreen));
    }

    private void hideMarks() {
        if (tag != null) tag.remove();
        tag = null;
        if (ring != null) ring.remove();
        ring = null;
    }

    /** 표시 다시 달기(다시 엎드릴 때). */
    private void showMarks() {
        if (ring == null) {
            ring = body.attach("fx_flat");
            if (ring != null) {
                ring.follow = false;
                ring.pose(new Matrix4f().translate(0, (float) (0.04 - PRONE_Y), 0).scale(Players.RING_SCALE), 0);
                if (ring.d != null) {
                    ring.d.setBrightness(new Display.Brightness(15, 15));
                    ring.d.setViewRange(1.5f);
                }
            }
        }
        tagGreen = false;
        showTag();
        tagText();
        ringAt = -99;
    }

    /** 바닥 원을 다시 찍고(느린 깜빡임) 가짜 남은 초를 굴린다: 진짜처럼 몇 초 줄다가 멈추고, 거꾸로 오르고, 다시 준다. */
    private void marks() {
        if (ring != null && ring.d != null && ring.d.isValid() && age - ringAt >= FAKE_RING_EVERY) {
            ringAt = age;
            ring.d.setItemStack(game.shotFx.fx("fx_flat", Players.RING_KIND, 0));
        }
        if (phase == Phase.BAIT) return; // 일으키는 동안 진짜도 초가 멈춘다
        int step = secDir < 0 ? 20 : secDir == 0 ? 70 : 30;
        if (age - secAt < step) return;
        secAt = age;
        if (secDir == 0) secDir = sec <= (SEC_MIN + SEC_MAX) / 2 ? 1 : -1;
        else {
            sec += secDir;
            if (--secSteps <= 0 || sec <= SEC_MIN || sec >= SEC_MAX) {
                secDir = 0;
                secSteps = 2 + game.random.nextInt(3);
            }
        }
        tagText();
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        switch (phase) {
            case PRONE -> prone();
            case BAIT -> bait();
            case LEAP -> leap();
            case LATCH -> latch();
            case STUN -> stun();
            case CRAWL -> crawl();
            case RETREAT -> retreat();
        }
    }

    private void prone() {
        hold();
        hover = PRONE_Y;
        pitch = 0;
        marks();
        if (age >= nextSob) {
            sound("sob", Sound.ENTITY_GHAST_AMBIENT, 1.8f, 16);
            nextSob = age + 60 + game.random.nextInt(50);
        }
    }

    /** 누가 일으키는 중: 이름표 초가 초록, 2초째에 튀어 오른다. 그 사람이 물러나거나 눈을 돌리면 다시 엎드린 척. */
    private void bait() {
        hold();
        marks();
        Player p = helper;
        boolean ok = p != null && p.isOnline() && standing(p);
        if (ok) {
            Location l = p.getLocation();
            ok = Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ()) <= HELP_R;
            if (ok) {
                Location eye = p.getEyeLocation();
                Vector to = pos.clone().subtract(eye.toVector());
                double len = to.length();
                ok = len < 1e-3 || to.dot(eye.getDirection()) / len >= HELP_COS;
            }
        }
        if (!ok) {
            helper = null;
            mark(null);
            tagGreen = false;
            tagText();
            phase(Phase.PRONE);
            return;
        }
        if (inPhase() >= BAIT) {
            unmask();
            jump(p);
        }
    }

    /** 정체를 드러냄: 표시를 걷고 얼굴을 돌려 세운다. */
    private void unmask() {
        if (!disguised) return;
        disguised = false;
        hideMarks();
        state("flip");
        body.squash(1.1f, 1.25f, 1.1f, 2);
        sound("flip", Sound.BLOCK_BAMBOO_BREAK, 0.7f, 20);
    }

    /** 그 사람 얼굴로 뛰어오름. */
    private void jump(Player p) {
        victim = p;
        helper = null;
        mark(p);
        flying = true;
        ghost = true;
        phase(Phase.LEAP);
    }

    private Vector facePoint(Player p) {
        Location eye = p.getEyeLocation();
        Vector d = eye.getDirection().setY(0);
        if (d.lengthSquared() < 1e-6) d = new Vector(0, 0, 1);
        d.normalize().multiply(0.36);
        return new Vector(eye.getX() + d.getX(), eye.getY() - 0.05, eye.getZ() + d.getZ());
    }

    private void leap() {
        Player p = victim;
        if (p == null || !p.isOnline() || !standing(p)) {
            drop(false);
            return;
        }
        Vector want = facePoint(p);
        int left = Math.max(1, LEAP - inPhase());
        Vector d = want.clone().subtract(pos);
        vel.copy(d.multiply(1.0 / left));
        face(p.getEyeLocation().toVector());
        if (inPhase() == 1) body.squash(1f, 1f, 1f, 3);
        if (left <= 1 || pos.distanceSquared(want) < 0.36) attach(p);
    }

    private void attach(Player p) {
        if (!grab(p, ESCAPE)) {
            drop(false);
            return;
        }
        PState s = game.players.get(p);
        s.grabPin = false; // 눈은 가렸지만 걸을 수는 있음(쏘지는 못함)
        s.grabDetach = true;
        shaken = 0;
        game.players.cover(p, "g35_face", LATCH + 10);
        body.hide(p, true);
        state("latch");
        sound("latch", Sound.ENTITY_SLIME_ATTACK, 0.8f, 16);
        game.hud.hint(p, "hint_escape", 60);
        phase(Phase.LATCH);
        stick(p);
    }

    private void stick(Player p) {
        vel.copy(facePoint(p).subtract(pos));
        yaw = p.getLocation().getYaw() + 180f;
        pitch = 0;
    }

    /** 얼굴에 붙은 4초: 0.5초마다 피해, 다 채우면 쓰러뜨린다. */
    private void latch() {
        Player p = victim;
        if (p == null || !p.isOnline() || game.players.grabbedBy(p) != this) {
            unhook(p);
            drop(true);
            return;
        }
        stick(p);
        int t = inPhase();
        game.hud.meter(p, "g35", 1.0 - (double) t / LATCH, 6);
        if (t > 0 && t % LATCH_EVERY == 0) {
            hit(p, LATCH_DMG, "cause_g35");
            if (victim == null) return; // 그 피해로 쓰러져 detached 가 이미 풂
        }
        if (t >= LATCH) {
            victim = null;
            unhook(p);
            if (game.players.grabbedBy(p) == this) game.players.release(p);
            hit(p, FULL_DMG, "cause_g35");
            more(p);
            flee(p);
        }
    }

    /** 덮개, 숨김, 게이지를 걷음(붙음이 끝날 때 한 번). */
    private void unhook(Player p) {
        if (p == null) return;
        PState s = game.players.get(p);
        if ("g35_face".equals(s.cover)) game.players.uncover(p);
        body.hide(p, false);
        game.hud.meter(p, "g35", -1, 0);
    }

    /** 얼굴에서(또는 뛰다가) 떨어짐. stunned 면 2초 떤다. */
    private void drop(boolean stunned) {
        victim = null;
        flying = false;
        ghost = false;
        hover = CRAWL_Y;
        mark(null);
        state("flip");
        pitch = 0;
        vel.zero();
        readyAt = age + cd(RELEAP);
        if (stunned) {
            body.squash(1.25f, 0.6f, 1.25f, 3);
            phase(Phase.STUN);
        } else phase(Phase.CRAWL);
    }

    private void stun() {
        hold();
        body.roll = (float) Math.sin(age * 1.6) * 7f;
        if (inPhase() == 6) body.squash(1f, 1f, 1f, 5);
        if (inPhase() >= STUN) {
            body.roll = 0;
            phase(Phase.CRAWL);
        }
    }

    /** 드러난 채 기어 옴. 3블록 안에서 보이는 사람 얼굴로 뛰어오른다. */
    private void crawl() {
        hover = CRAWL_Y + 0.08 * Math.abs(Math.sin(age * 0.35));
        follow(speed());
        Player p = nearest(24);
        if (p == null) {
            if (target != null) mark(null);
            return;
        }
        face(chest(p));
        pitch = 0;
        if (target != p) mark(p);
        if (age >= readyAt && dist(p) <= LEAP_R && sees(p) && game.players.grabbedBy(p) == null) {
            body.squash(1.2f, 0.75f, 1.2f, 2);
            sound("flip", Sound.BLOCK_BAMBOO_BREAK, 0.9f, 20);
            jump(p);
        }
    }

    /** 사람을 쓰러뜨린 뒤: 그 사람 곁에서 조금 기어가 다시 엎드린 척(새 이름, 새 초). */
    private void flee(Player from) {
        flying = false;
        ghost = false;
        hover = CRAWL_Y;
        mark(null);
        state("flip");
        Vector d = pos.clone().subtract(from.getLocation().toVector()).setY(0);
        if (d.lengthSquared() < 1e-4) d = new Vector(game.random.nextDouble() - 0.5, 0, game.random.nextDouble() - 0.5);
        if (d.lengthSquared() < 1e-6) d = new Vector(1, 0, 0);
        away.copy(d.normalize());
        phase(Phase.RETREAT);
    }

    private void retreat() {
        hover = CRAWL_Y;
        vel.copy(away.clone().multiply(speed()));
        if (wallAhead(0.6)) vel.zero();
        face(pos.clone().add(away));
        pitch = 0;
        if (inPhase() >= RETREAT) lieDown();
    }

    /** 다시 엎드린 척. */
    private void lieDown() {
        hold();
        disguised = true;
        hover = PRONE_Y;
        state("");
        name = pickName();
        sec = 7 + game.random.nextInt(4);
        secDir = -1;
        secSteps = 2 + game.random.nextInt(2);
        secAt = age;
        showMarks();
        nextSob = age + 30;
        phase(Phase.PRONE);
    }

    /** 웨이브마다 한 번: 엎드린 척 하나 더(보상 0, 쓰러진 사람에서 6~10블록 떨어진 곳). */
    private void more(Player near) {
        int[] last = EXTRA.computeIfAbsent(game, k -> new int[] {Integer.MIN_VALUE, Integer.MIN_VALUE});
        if (last[0] == game.round && last[1] == game.wave) return;
        Location l = near.getLocation();
        for (int tries = 0; tries < 8; tries++) {
            double a = game.random.nextDouble() * Math.PI * 2, r = 6 + game.random.nextDouble() * 4;
            double x = l.getX() + Math.cos(a) * r, z = l.getZ() + Math.sin(a) * r;
            if (!game.mobs.flow.walkable(x, z)) continue;
            Gabe c = child(Species.G35, new Vector(x, game.mobs.flow.floor(x, z) + PRONE_Y, z));
            if (c != null) {
                last[0] = game.round;
                last[1] = game.wave;
            }
            return;
        }
    }

    // ------------------------------------------------------------------ 돕기, 떼어짐, 맞음, 듣기

    /** 일으키려고 손을 댐(이 개체를 보고 좌클릭): 2초 뒤 튀어 오른다. */
    @Override
    public boolean helped(Player p) {
        if (phase != Phase.PRONE || !standing(p)) return false;
        helper = p;
        mark(p);
        tagGreen = true;
        tagText();
        sound("sob", Sound.ENTITY_GHAST_AMBIENT, 1.8f, 16);
        phase(Phase.BAIT);
        return true;
    }

    @Override
    public void detached(Player p, Player by, String how) {
        if (p == null || p != victim || phase != Phase.LATCH) return;
        unhook(p);
        if (!dead) drop(true);
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        boolean gun = t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER;
        if (disguised) {
            // 흘린 탄은 무시, 조준선을 0.5초 댄 사격과 폭발, 불에는 드러남
            if (gun && !d.aimed()) return false;
            reveal();
        }
        double before = hp;
        boolean r = super.damage(d);
        if (!dead && phase == Phase.LATCH && victim != null) {
            shaken += Math.max(0, before - hp);
            if (shaken >= SHAKE_OFF) {
                // 동료가 쏴서 뗌
                Player p = victim;
                unhook(p);
                victim = null;
                if (game.players.grabbedBy(p) == this) game.players.release(p);
                drop(true);
            }
        }
        return r;
    }

    /** 조명탄, 겨눈 사격: 엎드린 척이 드러나 기어 온다. */
    @Override
    public void reveal() {
        if (!disguised) return;
        Player h = helper;
        helper = null;
        unmask();
        hover = CRAWL_Y;
        readyAt = age + 20;
        if (h != null) mark(null);
        phase(Phase.CRAWL);
    }

    /** 딸랑이: 엎드린 척하는 동안은 끌려가지 않는다. */
    @Override
    public void lure(Vector at, int ticks) {
        if (phase == Phase.CRAWL) super.lure(at, ticks);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        Player p = victim;
        victim = null;
        hideMarks();
        super.die(d);
        if (p != null) unhook(p);
    }

    @Override
    public void remove() {
        Player p = victim;
        victim = null;
        hideMarks();
        if (p != null) {
            unhook(p);
            if (game.players.grabbedBy(p) == this) game.players.release(p);
        }
        super.remove();
    }

    @Override
    public String listenSound() {
        return disguised ? "gave:gabe.g35.snicker" : null;
    }

    /** 시험용. */
    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + (disguised ? " disg" : "") + " name=" + (name.isEmpty() ? "-" : name) + " sec=" + sec
                + (helper != null ? " helper=" + helper.getName() : "") + (victim != null ? " on=" + victim.getName() : "")
                + String.format(java.util.Locale.ROOT, " shaken=%.0f", shaken);
    }
}
