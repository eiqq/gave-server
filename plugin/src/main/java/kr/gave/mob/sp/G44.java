package kr.gave.mob.sp;

import java.util.ArrayList;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import kr.gave.Props;
import kr.gave.game.Crates;
import kr.gave.game.Game;
import kr.gave.game.Hud;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.Detachable;
import kr.gave.player.PState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

/**
 * G44 꾸러미 게이브(함정, 정예, 미리 깔림). 팀 보급 상자인 척 빙글 돌며 둥실거린다.
 *
 * <p>도감: 쳐다보면 회전을 멈추고 그 사람에게 정면을 돌린다(보상 흉내 공통 신호). 곁에서는 이음매가 빠드득 이를 간다. 1.5블록 안에 들면(또는 열려고
 * 손을 대면) 덥석 가둔다: 분홍 살 화면, 초당 1 피해, 10초마다 그 자리에서 '살려줘' 비명으로 다른 사람을 부른다. 입이 하나라 앞 사람을 뱉어야 다음을
 * 문다. 흘린 탄에는 반응하지 않고 겨눠 쏘면 열려 무겁게 쿵쿵 깡충 뛰며 쫓는다. 맷집이 매우 세고, 체력 절반을 깎으면 갇힌 사람을 뱉는다.
 *
 * <p>구현: 위장은 모드 탄약통 모델(gave:ammo_box)을 부품으로 겹치고 이 종 모델은 이음매 얼굴과 경첩 막대기만 그린다. 가둔 사람은 붙잡힘(못 쏨)으로
 * 두고 좌석을 바닥 밑에 두어 남들 눈에 안 보이게 한다(상자 위에 이름표와 분홍 글씨 '살려줘'). 본인 탈출은 연타 30번, 동료 떼기는 안 됨(쏴야 뱉음).
 *
 * <p>진짜 보급 상자({@link Crates})와 같게(2026-11-11 final): 감독이 낸 개체는 아무에게도 안 보이고 맞지 않은 채 기다리다가 진짜 상자처럼
 * 웨이브 시작 {@link Crates#AT_MIN}..{@link Crates#AT_MAX} 틱 뒤 {@link Crates#DROP_H} 위에서 {@link Crates#FALL} 틱 동안 떨어진다(알림
 * notice_crate, 금색 화살표, 날개 소리, 내려앉는 나무 먼지와 소리가 진짜와 같음). 놓이는 띠는 Director.placeFor 가 Crates.R_MIN..R_MAX 로
 * 맞추고, 높이, 둥실거림(sin(t x 0.12)), 회전(2틱마다 12도, 보간 2), 모델 배율과 올림은 Crates 상수를 그대로 쓴다. 진짜와 다른 모습은
 * 경첩의 작은 실눈 얼굴과 까딱이는 막대기 둘뿐이다(도감). 쳐다보면 회전을 멈추는 것은 도감의 알아보는 신호라 남겼다.
 * '진짜 상자를 쏘면 총 등급이 내려감'은 Crates 가 정할 일이라 여기 없다(지금 진짜 상자는 쏘면 부서지기만 한다).
 *
 * <p>팩 계약(tools/gabe/sp/g44.py): 모델 {@code gave:gabe/g44} 상태 {@code ""}(위장 겹침, 부위 {@code own}), {@code "open"}, {@code "full"}.
 * 소리 grind(8), snap(24), thud(24), scream(48), spit(24), snicker(듣기). 덮개 {@code g44_maw}. 글귀 {@code cause_g44}, {@code notice_g44_trap}.
 */
public final class G44 extends Gabe implements Detachable {

    // ------------------------------------------------------------------ 수치(도감)

    /** 위장: 상자 가운데 높이(바닥 위), 둥실거림, 회전(도/틱), 크기, 모델 올림. 진짜 보급 상자(Crates)와 같은 값. */
    static final double BOX_H = Crates.BOX_H, BOB = Crates.BOB;
    static final float SPIN = Crates.SPIN, BOX_SCALE = Crates.SCALE, LIFT_UP = Crates.LIFT, OPEN_SCALE = 1.7f, FULL_SCALE = 2.1f;
    /** 쳐다봄을 재는 거리, 원뿔 최소 각(도). */
    static final double WATCH_R = 28, WATCH_DEG = 4.5;
    /** 덥석 거리(수평), 가둔 동안 피해(초당 1), 비명 간격(10초), 본인 탈출 연타. */
    static final double TRIGGER = 1.5, CHEW = 1;
    static final int CHEW_EVERY = 20, SCREAM_EVERY = 200, ESCAPE = 30;
    /** 깡충 뛰기: 한 번 틱(뜬 틱 + 앉은 틱), 뜨는 높이, 뜬 동안 빠르기 배율. */
    static final int HOP = 15, AIR = 9;
    static final double HOP_UP = 0.85, AIR_MUL = 1.7, SPEED = 0.125, OPEN_H = 0.58;
    static final int OPENING = 8, STUN = 34;
    /** 가둔 사람 좌석: 바닥 밑(남들 눈에 안 보이게). */
    static final double SEAT_DOWN = 1.45;
    static final TextColor PINK = TextColor.color(0xff8fb4), GRAY = TextColor.color(0x9a9a9a);

    enum Phase { WAIT, FALL, BOX, OPENING, CHASE, HOLD, STUN }

    private Phase phase = Phase.BOX;
    private int dropDelay = -1, landedAt;
    private int phaseAt, nextGrind, nextChew, nextScream, screamShown = -1;
    private Player watcher, prey, held;
    private String heldName;
    private double spitAt;
    private Body.Part can;
    private TextDisplay tag;

    public G44(Game game, Species sp) {
        super(game, sp);
        radius = 0.5;
        hover = BOX_H;
        baseSpeed = SPEED;
        disguised = true;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g44", 2.6f, 1.8f);
        body.turnTicks = 3;
        body.scale(BOX_SCALE, 0);
        if (Props.has("ammo_box")) {
            // 보급 상자 몸: 모드 탄약통 모델(바닥이 가운데보다 0.5 아래라 0.17 올려 이 종 모델 이음매 높이와 맞춤)
            can = body.attach("ammo_box");
            if (can != null) can.pose(new Matrix4f().translate(0f, LIFT_UP, 0f), 0);
        }
        if (can == null) body.look.on.add("own");
    }

    @Override
    protected void spawned() {
        yaw = game.random.nextFloat() * 360f;
        nextGrind = age + game.random.nextInt(40);
        if (game.director.cageSpawning()) return;
        // 진짜 보급 상자처럼 하늘에서 떨어진다: 그때까지 아무에게도 안 보이고 맞지 않음
        phase(Phase.WAIT);
        untargetable = true;
        hover = BOX_H + Crates.DROP_H;
        pos.setY(floor() + hover);
        hideAll();
    }

    /** 기다리는 동안: 들어온 사람에게도 숨김. */
    private void hideAll() {
        if (body == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) if (!body.hidden(p)) body.hide(p, true);
    }

    /** 기다림: 감독이 낸 개체는 진짜 상자처럼 웨이브 시작 뒤 AT_MIN..AT_MAX 틱에, 시험 소환은 바로 떨어진다. */
    private void waitTick() {
        hold();
        if (dropDelay < 0)
            dropDelay = game.director.fromDirector(this) ? Crates.AT_MIN + game.random.nextInt(Crates.AT_MAX - Crates.AT_MIN) : 0;
        if (every(10)) hideAll();
        if (inPhase() < dropDelay) return;
        untargetable = false;
        if (body != null) for (Player p : new ArrayList<>(body.hiddenFrom)) body.hide(p, false);
        phase(Phase.FALL);
        Location l = loc();
        game.hud.notice("notice_crate", 80);
        game.hud.point(null, pos.clone().setY(floor() + 1), 100);
        Props.sound(l, null, Sound.ITEM_ELYTRA_FLYING, 0.6f, 1.6f, 1f);
    }

    /** 떨어짐: 진짜 상자와 같은 가속 곡선(DROP_H x (1 - k^2)), 내려앉을 때 나무 먼지와 소리. */
    private void fall() {
        hold();
        int t = inPhase();
        double k = Math.min(1.0, (double) t / Crates.FALL);
        hover = BOX_H + Crates.DROP_H * (1 - k * k);
        pos.setY(floor() + hover);
        spin();
        if (t >= Crates.FALL) {
            Location at = new Location(game.world, pos.getX(), floor() + 0.05, pos.getZ());
            game.shotFx.hit(at, BlockFace.UP, Material.OAK_PLANKS, new Vector(0, -1, 0), true);
            Props.sound(at, null, Sound.BLOCK_WOOD_FALL, 1.2f, 0.7f, 1f);
            landedAt = age;
            phase(Phase.BOX);
        }
    }

    /** 진짜 상자와 같은 회전: 2틱마다 SPIN x 2 도, 보간 2틱. */
    private void spin() {
        if ((age - phaseAt) % 2 != 0) return;
        yaw = (yaw + SPIN * 2) % 360f;
        body.pose(2);
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
        if (prey != null && !standing(prey)) prey = null;
        if (held != null && (!held.isOnline() || game.players.grabbedBy(held) != this)) lost();
        switch (phase) {
            case WAIT -> waitTick();
            case FALL -> fall();
            case BOX -> box();
            case OPENING -> opening();
            case CHASE -> chase();
            case HOLD -> holdTick();
            case STUN -> stun();
        }
        if (tag != null && screamShown >= 0 && age - screamShown >= 44) {
            screamShown = -1;
            tagText(false);
        }
    }

    /** 위장: 빙글 돌며 둥실. 쳐다보는 사람이 있으면 멈추고 정면을 돌림. 가까우면 덥석. */
    private void box() {
        hold();
        pitch = 0;
        hover = BOX_H + Math.sin((age - landedAt) * 0.12) * BOB;
        pos.setY(floor() + hover);
        if (every(2)) watcher = watcher();
        if (watcher != null && !standing(watcher)) watcher = null;
        if (watcher != null) turnTo(watcher.getEyeLocation().toVector(), 16f);
        else spin();
        pitch = 0;
        Player near = nearest(TRIGGER + 2);
        if (near != null && flat(near) <= TRIGGER) {
            open();
            swallow(near);
            return;
        }
        if (age >= nextGrind) {
            nextGrind = age + 50 + game.random.nextInt(30);
            if (nearest(4.5) != null) sound("grind", Sound.BLOCK_GRINDSTONE_USE, 1.6f, 8);
        }
    }

    /** 정체가 드러나 열림(탄약통 부품을 치우고 이 종의 열린 입 모델로). */
    private void open() {
        if (phase != Phase.BOX && phase != Phase.FALL) return;
        disguised = false;
        if (can != null) {
            can.remove();
            can = null;
        }
        body.look.on.remove("own");
        state("open");
        radius = 0.7;
        hover = OPEN_H;
        body.scale(OPEN_SCALE, 4);
        body.squash(1.15f, 0.85f, 1.15f, 2);
        sound("snap", Sound.BLOCK_CHEST_OPEN, 0.6f, 24);
        phase(Phase.OPENING);
    }

    private void opening() {
        hold();
        if (inPhase() == 3) body.squash(1f, 1f, 1f, 4);
        Player p = nearest(40);
        if (p != null) turnTo(chest(p), 20f);
        if (inPhase() >= OPENING) phase(Phase.CHASE);
    }

    /** 무겁게 쿵쿵 깡충 뛰며 가장 가까운 사람을 쫓는다. 닿으면 덥석. */
    private void chase() {
        Player p = nearest(400); // 게이브는 72~84블록에서 나오므로 반경을 넉넉히(전에는 멀면 사람을 못 찾아 외곽에서 멈춤, 2026-11-11)
        int t = inPhase() % HOP;
        if (p == null) {
            hold();
            hover = OPEN_H;
            if (target != null) mark(null);
            return;
        }
        if (target != p) mark(p);
        prey = p;
        turnTo(chest(p), 12f);
        pitch = 0;
        if (t < AIR) {
            follow(speed() * AIR_MUL);
            hover = OPEN_H + HOP_UP * Math.sin(Math.PI * (t + 1) / (AIR + 1));
        } else {
            hold();
            hover = OPEN_H;
            if (t == AIR) {
                sound("thud", Sound.ENTITY_RAVAGER_STEP, 0.7f, 24);
                body.squash(1.22f, 0.72f, 1.22f, 2);
            } else if (t == AIR + 3) body.squash(1f, 1f, 1f, 3);
        }
        for (Player q : game.mobs.standing()) {
            if (flat(q) > TRIGGER || Math.abs(q.getLocation().getY() - floor()) > 2.2) continue;
            if (swallow(q)) return;
        }
    }

    /** 덥석 가둠. 면역, 비옷으로 못 잡으면 false. */
    private boolean swallow(Player p) {
        if (held != null || !grab(p, ESCAPE)) return false;
        PState s = game.players.get(p);
        s.grabPin = false; // 좌석(바닥 밑)이 붙든다
        s.grabDetach = false; // 동료 돕기로는 못 뗌: 쏴서 뱉게 해야 함
        held = p;
        heldName = s.noLikeness ? null : p.getName();
        prey = p;
        mark(p);
        if (Hud.hasCover("g44_maw") && game.players.cover(p, "g44_maw", 20 * 90)) s.coverDetach = false;
        spitAt = Math.min(hp - 0.25 * maxHp, maxHp * 0.5);
        nextChew = age + CHEW_EVERY;
        nextScream = age + 60;
        state("full");
        hover = OPEN_H + 0.1;
        body.scale(FULL_SCALE, 4);
        body.squash(1.2f, 0.8f, 1.2f, 2);
        sound("snap", Sound.BLOCK_CHEST_CLOSE, 0.6f, 24);
        game.hud.notice("notice_g44_trap", sp, 60);
        tagMake();
        seat(p);
        phase(Phase.HOLD);
        return true;
    }

    private void seat(Player p) {
        game.players.carry(p, new Vector(pos.getX(), floor() - SEAT_DOWN, pos.getZ()));
    }

    /** 가둔 동안: 제자리에서 꿈틀, 초당 1 피해, 10초마다 '살려줘'. 다른 사람이 닿으면 앞 사람을 뱉고 그 사람을 문다. 체력 절반이 깎이면 뱉음. */
    private void holdTick() {
        Player p = held;
        if (p == null) {
            phase(Phase.STUN);
            return;
        }
        hold();
        pitch = 0;
        seat(p);
        if (inPhase() == 3) body.squash(1f, 1f, 1f, 4);
        if (inPhase() % 10 == 5) yaw += game.random.nextBoolean() ? 9f : -9f;
        if (age >= nextChew) {
            nextChew = age + CHEW_EVERY;
            hit(p, CHEW, "cause_g44");
            if (held != p) return;
        }
        if (age >= nextScream) {
            nextScream = age + SCREAM_EVERY;
            sound("scream", Sound.ENTITY_GHAST_SCREAM, 1.3f, 48);
            screamShown = age;
            tagText(true);
        }
        if (hp <= spitAt) {
            spit(true);
            return;
        }
        for (Player q : game.mobs.standing()) {
            if (q == p || flat(q) > TRIGGER) continue;
            if (game.players.immune(q, "grab")) continue;
            // 입이 하나: 앞 사람을 뱉고 새 사람을 문다
            spit(false);
            swallow(q);
            return;
        }
    }

    /** 가둔 사람을 뱉는다(그 자리 바닥에 서고 옆으로 튕겨 나감). stun 이면 한동안 움찔. */
    private void spit(boolean stun) {
        Player p = held;
        if (p == null) return;
        game.players.release(p); // → detached → lost()
        held = null;
        if (p.isOnline() && standing(p)) {
            double a = game.random.nextDouble() * Math.PI * 2;
            knock(p, new Vector(Math.cos(a), 0, Math.sin(a)), 0.6, 0.35);
        }
        sound("spit", Sound.ENTITY_LLAMA_SPIT, 0.7f, 24);
        if (stun) {
            body.squash(0.85f, 1.2f, 0.85f, 2);
            phase(Phase.STUN);
        }
    }

    /** 가둔 사람을 놓침(뱉음, 탈출, 쓰러짐, 나감): 덮개와 이름표를 걷고 입을 연다. */
    private void lost() {
        Player p = held;
        held = null;
        heldName = null;
        if (p != null && p.isOnline()) {
            PState s = game.players.get(p);
            if ("g44_maw".equals(s.cover)) game.players.uncover(p);
            // 좌석이 바닥 밑이라 풀린 뒤 땅속에 남지 않게 다음 틱에 상자 자리 바닥 위로 올린다
            double x = pos.getX(), z = pos.getZ(), fl = floor();
            org.bukkit.Bukkit.getScheduler().runTask(game.plugin, () -> {
                if (!p.isOnline() || p.isInsideVehicle()) return;
                Location l = p.getLocation();
                if (l.getY() >= fl - 0.05) return;
                l.setX(x);
                l.setZ(z);
                l.setY(fl);
                p.teleport(l);
            });
        }
        tagRemove();
        if (dead) return;
        state("open");
        hover = OPEN_H;
        body.scale(OPEN_SCALE, 4);
        mark(null);
        prey = null;
        if (phase == Phase.HOLD) phase(Phase.STUN);
    }

    private void stun() {
        hold();
        if (inPhase() == 4) body.squash(1f, 1f, 1f, 4);
        if (inPhase() >= STUN) phase(Phase.CHASE);
    }

    // ------------------------------------------------------------------ 이름표(갇힌 이름, 분홍 '살려줘')

    private void tagMake() {
        tagRemove();
        if (body == null || body.anchor == null || !body.anchor.isValid()) return;
        var anchor = body.anchor;
        float y = (float) (pos.getY() - anchor.getLocation().getY()) + 1.25f;
        tag = anchor.getWorld().spawn(anchor.getLocation(), TextDisplay.class, t -> {
            t.setPersistent(false);
            t.setBillboard(Display.Billboard.CENTER);
            t.setSeeThrough(false);
            t.setShadowed(false);
            t.setDefaultBackground(false);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setViewRange(0.7f);
            t.setBrightness(new Display.Brightness(12, 12));
            t.setTransformation(new Transformation(new Vector3f(0, y, 0), new AxisAngle4f(), new Vector3f(1f, 1f, 1f), new AxisAngle4f()));
        });
        anchor.addPassenger(tag);
        tagText(false);
    }

    /** 이름(회색)과, 비명 중이면 그 위에 분홍 글씨(시스템이 지어낸 말은 분홍). */
    private void tagText(boolean scream) {
        if (tag == null || !tag.isValid()) return;
        Component c = Component.empty();
        if (scream) c = c.append(Component.text("살려줘").color(PINK));
        if (heldName != null) {
            if (scream) c = c.append(Component.newline());
            c = c.append(Component.text(heldName).color(GRAY));
        }
        tag.text(c);
    }

    private void tagRemove() {
        if (tag != null) tag.remove();
        tag = null;
        screamShown = -1;
    }

    // ------------------------------------------------------------------ 도우미

    /** 이 상자를 조준선 쪽 좁은 원뿔로 보고 있는 가장 가까운 서 있는 사람. */
    private Player watcher() {
        Player best = null;
        double bd = WATCH_R;
        for (Player p : game.mobs.standing()) {
            Location e = p.getEyeLocation();
            Vector v = pos.clone().subtract(e.toVector());
            double d = v.length();
            if (d > bd || d < 1e-3) continue;
            double cos = v.multiply(1 / d).dot(e.getDirection());
            double lim = Math.cos(Math.toRadians(Math.max(WATCH_DEG, Math.toDegrees(Math.atan(0.6 / d)))));
            if (cos < lim) continue;
            if (!game.mobs.clearLine(e.toVector(), pos)) continue;
            bd = d;
            best = p;
        }
        return best;
    }

    private double flat(Player p) {
        Location l = p.getLocation();
        return Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
    }

    // ------------------------------------------------------------------ 맞음, 돕기, 듣기, 떼기, 죽음

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        boolean gun = t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER;
        if (phase == Phase.WAIT) return false;
        if (phase == Phase.BOX || phase == Phase.FALL) {
            // 흘린 탄에는 반응하지 않고, 조준선을 0.5초 댄 사격(또는 폭발, 불)에 열린다
            if (gun && !d.aimed()) return false;
            open();
        }
        return super.damage(d);
    }

    /** 보급 상자를 열려고(돕기) 손을 대면 바로 덥석. */
    @Override
    public boolean helped(Player p) {
        if (phase != Phase.BOX || !standing(p)) return false;
        open();
        swallow(p);
        return true;
    }

    @Override
    public void reveal() {
        if (phase == Phase.WAIT) return;
        if (phase == Phase.BOX || phase == Phase.FALL) open();
        else disguised = false;
    }

    @Override
    public String listenSound() {
        return phase == Phase.BOX || phase == Phase.FALL ? "gave:gabe.g44.snicker" : null;
    }

    @Override
    public void detached(Player victim, Player helper, String how) {
        if (victim == held) lost();
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        super.die(d);
        // 엔진이 붙잡힘을 풀며 detached 를 부르지만(→ lost), 이름표는 여기서도 걷는다
        tagRemove();
    }

    @Override
    public void remove() {
        tagRemove();
        super.remove();
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + (disguised ? " disg" : "") + (watcher != null ? " watched" : "")
                + (held != null ? " held=" + held.getName() : "");
    }
}
