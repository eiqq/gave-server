package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Species;
import kr.gave.mob.Gabe;

/**
 * G139 떠넘기는 게이브(4막, 방해). 잡음 속에 얼굴이 떠오르는 네모 판이 머리 높이에 떠서 느리게(0.14/틱) 온다. 14블록 안에서 한 사람 쪽으로 판을
 * 돌려 보여 준다(조준점 옆에 이 종 표시, 잡음이 커짐). 그 사람이 판을 조준한 채(Mobs.looked) 2초가 쌓이면 판 속 얼굴이 불쑥 커지며 그 사람에게 20초
 * 시계가 옮는다: 머리 위 줄어드는 붉은 고리(남들에게 보임, 본인은 조준점 아래 게이지)와 1초마다 똑딱. 0 이 되면 화면을 잡음과 얼굴이 덮으며 42 피해와
 * 3초 굳음. 시계 든 사람이 동료를 보고 돕기(좌클릭)를 하면 시계가 그 동료에게 넘어간다(HELPHOOK, 넘겨받은 사람은 12초부터, 넘길 때마다 2초씩 짧게,
 * 4초 아래로는 안 줆). 한 판(개체)에 세 사람까지. 판 게이브를 잡으면 그 판의 시계가 모두 사라진다. 판 뒷면은 피해 두 배.
 *
 * <p>시계는 웨이브가 끝나면(개체가 정리되며) 사라지고, 쓰러지거나 나간 사람의 시계도 지운다. 머리 위 고리는 쓰러짐 고리와 같은 셰이더 꼴
 * (ShotFx.downRing, 빨강)을 종 파일 안에서 띄운다(엔진에 사람 머리 위 표시 부품이 없음).
 *
 * <p>팩 계약(tools/gabe/sp/g139.py): 상태 "", "show", 덮개 g139_static. 소리 static(24), tick(16), pass(16), burst(24). 글귀 cause_g139.
 */
public final class G139 extends Gabe {

    static final double SPEED = 0.14, HOVER = 2.0, RADIUS = 0.72, SHOW_R = 14, LOOK_REACH = 22;
    static final int LOOK_NEED = 40, SHOW_MAX = 120, SHOW_CD = 40, CLOCK = 400, PASS_FIRST = 240, PASS_STEP = 40, PASS_MIN = 80;
    static final int MAX_CURSES = 3, ROOT = 60, COVER = 60, RING_EVERY = 6;
    static final double BURST = 42, BACK_MUL = 2.0;
    static final float RING_UP = 2.45f, RING_SCALE = 1.1f;
    static final String KEY = "G139";

    /** 사람 하나에 걸린 시계. */
    final class Curse {
        final UUID id;
        long end, len;
        int passes;
        ItemDisplay ring;
        long ringAt = Long.MIN_VALUE;

        Curse(UUID id, long len, int passes) {
            this.id = id;
            this.len = len;
            this.end = game.now() + len;
            this.passes = passes;
        }
    }

    /** 이 판 개체들(돕기 갈고리가 시계 주인을 찾음). */
    private static final List<G139> LIVE = new ArrayList<>();

    private final Map<UUID, Curse> curses = new HashMap<>(4);
    private Player shown;
    private int showAt, looking, showReady = 30, staticAt;

    public G139(Game game, Species sp) {
        super(game, sp);
        radius = RADIUS;
        hover = HOVER;
        baseSpeed = SPEED;
        noPush = true;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g139", 1.8f, 2.6f);
    }

    @Override
    protected void spawned() {
        G150.arm(game);
        staticAt = game.random.nextInt(30);
        LIVE.removeIf(g -> g.dead || g.game != game);
        LIVE.add(this);
        game.players.addHelpHook(KEY, G139::help);
    }

    // ------------------------------------------------------------------ 돕기 갈고리(떠넘기기)

    /** 시계 든 사람이 바라본 동료에게 시계를 넘김. */
    static boolean help(Player helper, Player mate) {
        if (helper == null || mate == null) return false;
        G139 owner = null;
        Curse c = null;
        for (G139 g : LIVE) {
            if (g.dead) continue;
            Curse k = g.curses.get(helper.getUniqueId());
            if (k != null) {
                owner = g;
                c = k;
                break;
            }
        }
        if (owner == null) return false;
        for (G139 g : LIVE) if (!g.dead && g.curses.containsKey(mate.getUniqueId())) return false;
        if (!owner.standing(mate)) return false;
        owner.drop(c);
        int passes = c.passes + 1;
        long len = Math.max(PASS_MIN, PASS_FIRST - (long) (passes - 1) * PASS_STEP);
        owner.curse(mate, len, passes);
        owner.log("떠넘김 " + helper.getName() + " -> " + mate.getName() + " 틱 " + len + " 넘김 " + passes);
        owner.soundFor(helper, "pass", Sound.UI_BUTTON_CLICK, 0.9f);
        owner.soundFor(mate, "pass", Sound.UI_BUTTON_CLICK, 1f);
        return true;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (game.state != Game.State.WAVE) {
            clearCurses();
            hold();
            return;
        }
        if (every(2)) curseTick();
        if (shown != null) show();
        else approach();
        if (age >= staticAt) {
            sound("static", Sound.BLOCK_FIRE_EXTINGUISH, 1.6f, 24);
            staticAt = age + 50 + game.random.nextInt(40);
        }
    }

    private void approach() {
        follow(speed());
        Player p = nearest(400);
        if (p != null) face(chest(p));
        if (age < showReady || curses.size() >= MAX_CURSES) return;
        Player best = null;
        double bd = SHOW_R;
        for (Player q : game.mobs.standing()) {
            if (cursedAny(q)) continue;
            double d = dist(q);
            if (d <= bd && sees(q)) {
                bd = d;
                best = q;
            }
        }
        if (best != null) {
            shown = best;
            showAt = age;
            looking = 0;
            mark(best);
            state("show");
        }
    }

    private boolean cursedAny(Player q) {
        UUID id = q.getUniqueId();
        for (G139 g : LIVE) if (!g.dead && g.curses.containsKey(id)) return true;
        return false;
    }

    /** 판을 한 사람에게 보여 줌: 그 사람이 판을 조준한 틱을 셈. */
    private void show() {
        Player p = shown;
        hold();
        if (!standing(p) || dist(p) > SHOW_R + 6 || age - showAt > SHOW_MAX || cursedAny(p)) {
            endShow();
            return;
        }
        face(chest(p));
        game.hud.mark(p, "g139", 6);
        if (every(10)) soundFor(p, "static", Sound.BLOCK_FIRE_EXTINGUISH, 1f);
        if (game.mobs.looked(p, LOOK_REACH) == this) looking++;
        if (looking >= LOOK_NEED) {
            body.squash(1.25f, 1.25f, 1f, 2);
            curse(p, CLOCK, 0);
            log("시계 옮음 " + p.getName() + " 틱 " + CLOCK);
            endShow();
        }
    }

    private void endShow() {
        shown = null;
        mark(null);
        state("");
        body.squash(1f, 1f, 1f, 6);
        showReady = age + cd(SHOW_CD);
    }

    // ------------------------------------------------------------------ 시계

    void curse(Player p, long len, int passes) {
        Curse c = new Curse(p.getUniqueId(), len, passes);
        curses.put(c.id, c);
        soundFor(p, "burst", Sound.BLOCK_FIRE_EXTINGUISH, 0.6f);
        ring(c, p);
    }

    private void curseTick() {
        long now = game.now();
        for (Iterator<Curse> it = curses.values().iterator(); it.hasNext();) {
            Curse c = it.next();
            Player p = Bukkit.getPlayer(c.id);
            if (p == null || !p.isOnline() || !standing(p)) {
                if (p != null) game.hud.meter(p, "g139", -1, 0);
                removeRing(c);
                it.remove();
                continue;
            }
            long left = c.end - now;
            if (left <= 0) {
                it.remove();
                removeRing(c);
                game.hud.meter(p, "g139", -1, 0);
                hit(p, BURST, "cause_g139");
                log("시계 터짐 " + p.getName() + " 체력 " + Math.round(game.players.get(p).hp));
                game.players.root(p, ROOT, "g139");
                game.players.cover(p, "g139_static", COVER);
                soundFor(p, "burst", Sound.BLOCK_FIRE_EXTINGUISH, 1f);
                continue;
            }
            double frac = (double) left / Math.max(1, c.len);
            game.hud.meter(p, "g139", frac, 10);
            if (left % 20 < 2) soundFor(p, "tick", Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f);
            ring(c, p);
        }
    }

    /** 머리 위 줄어드는 고리(본인에게는 숨김). */
    private void ring(Curse c, Player p) {
        Location at = p.getLocation().add(0, RING_UP, 0);
        at.setYaw(0);
        at.setPitch(0);
        long now = game.now();
        double frac = Math.max(0, Math.min(1, (double) (c.end - now) / Math.max(1, c.len)));
        if (c.ring == null || !c.ring.isValid()) {
            c.ring = at.getWorld().spawn(at, ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.setItemStack(game.shotFx.downRing(frac, false));
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.setBillboard(Display.Billboard.FIXED);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setTransformationMatrix(new Matrix4f().scale(RING_SCALE));
                d.setViewRange(1.0f);
                d.setTeleportDuration(2);
            });
            p.hideEntity(game.plugin, c.ring);
            c.ringAt = now;
            return;
        }
        c.ring.teleport(at);
        if (now - c.ringAt >= RING_EVERY) {
            c.ringAt = now;
            c.ring.setItemStack(game.shotFx.downRing(frac, false));
        }
    }

    private void removeRing(Curse c) {
        if (c.ring != null) {
            c.ring.remove();
            c.ring = null;
        }
    }

    /** 시계 하나를 떼어 냄(넘길 때). */
    void drop(Curse c) {
        curses.remove(c.id);
        removeRing(c);
        Player p = Bukkit.getPlayer(c.id);
        if (p != null) game.hud.meter(p, "g139", -1, 0);
    }

    private void clearCurses() {
        if (curses.isEmpty()) return;
        log("시계 모두 지움 " + curses.size());
        for (Curse c : new ArrayList<>(curses.values())) drop(c);
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        return List.of(new double[] {pos.getX(), pos.getY(), pos.getZ(), RADIUS});
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        Vector dir = d.dir();
        if (dir != null) {
            double r = Math.toRadians(yaw);
            double dot = dir.getX() * -Math.sin(r) + dir.getZ() * Math.cos(r);
            // 탄이 판 앞쪽 방향으로 날아감 = 뒤에서 쏨 = 뒷면
            if (dot > 0.2) return super.damage(d.times(BACK_MUL));
        }
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        clearCurses();
        shown = null;
        super.die(d);
        LIVE.remove(this);
    }

    @Override
    public void remove() {
        clearCurses();
        super.remove();
        LIVE.remove(this);
    }

    /** 시험과 판 뒤 분석용 기록(드문 사건만). */
    void log(String s) {
        game.plugin.getLogger().info("[G139] " + s);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(super.toString());
        sb.append(shown != null ? " SHOW " + shown.getName() + " look=" + looking : "");
        for (Curse c : curses.values()) {
            Player p = Bukkit.getPlayer(c.id);
            sb.append(" clock[").append(p == null ? "?" : p.getName()).append(' ').append(c.end - game.now()).append(" p").append(c.passes).append(']');
        }
        return sb.toString();
    }
}
