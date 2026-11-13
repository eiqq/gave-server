package kr.gave.mob.sp;

import java.util.ArrayDeque;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import io.papermc.paper.datacomponent.DataComponentTypes;
import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.Detachable;
import kr.gave.player.PState;
import net.kyori.adventure.key.Key;

/**
 * G23 코앞 게이브(방해, 시선 규칙 종). 피해 없음. 떼 틈에 섞여 오다 7블록 안의 한 사람을 골라 얼굴 0.4블록 앞에 멈추고, 그 사람이 고개를 돌리면
 * 0.3초(6틱) 늦게 흐느적이며 따라와 정면을 가린 채 갸웃 들여다본다.
 *
 * <p>대상 본인에게는 몸을 숨기고 화면 가운데 70% 를 가리는 코앞 얼굴 덮개(g23_e0..e3, 가장자리는 남음)로 그려 지연이 없다. 고개를 크게 돌리면 얼굴이 늦게
 * 따라오는 동안만 덮개가 걷힌다. 남들에게는 대상 얼굴 앞에 뜬 몸이 보인다.
 *
 * <p>마주 본 시간만큼 눈이 뜨이고(15틱마다 한 단계, '쩍'), 3초(60틱)면 '겁먹음' 4초: 느려짐 35%, 손떨림(조준이 흔들려 탄이 퍼짐), 화면 가장자리
 * 어둠(g23_fear, 코앞 얼굴은 그대로). 게이지는 조준점 아래(서버가 받은 pitch 로): 고개를 크게 숙이거나(pitch 50 넘게) 하늘을 보면(-50 넘게) 빠르게 준다.
 * 느려짐은 겁먹은 동안 짧게 이어 걸어서 이 개체가 죽거나 떨어지면 0.6초 안에 풀린다.
 *
 * <p>대처: 본인이 이것을 보고 좌클릭(돕기) 두 번이면 떨어지고, 동료가 쏘면 한 방에 죽는다(사람 몸이 탄을 막지 않음). 동료 돕기로도 떼어진다.
 * 본인의 탄은 이 몸을 그냥 지나가(본인 화면에서는 덮개 그림일 뿐) 다른 게이브를 맞히고 이것에는 피해를 주지 못한다. 비옷은 붙는 것을 한 번 막는다.
 *
 * <p>팩 계약(tools/gabe/sp/g23.py): 모델 gave:gabe/g23 상태 "e0".."e3", 덮개 g23_e0..g23_e3, g23_fear, 소리 squelch, fear, cling,
 * 원인 글귀 cause_g23, 알림 글귀 notice_g23_fear.
 */
public final class G23 extends Gabe implements Detachable {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.16, RUSH_V = 0.42;
    /** 고르는 거리, 얼굴 앞 거리(몸 중심까지). */
    static final double PICK_R = 7, FRONT = 0.72;
    /** 늦게 따라오는 틱(0.3초), 이 각보다 어긋나면 덮개가 걷힘(도). */
    static final int LAG = 6;
    static final double LAG_DEG = 24;
    /** 게이지: 가득(3초), 단계 틱, 줄어드는 빠르기, 숙이거나 올려다보는 각. */
    static final int FULL = 60, STEP = 15, DRAIN = 3;
    static final float LOOK_AWAY = 50f;
    /** 겁먹음: 틱, 느려짐, 손떨림 간격, 느려짐을 이어 거는 길이(5틱마다 이만큼). */
    static final int FEAR = 80, TREMOR_EVERY = 3, FEAR_SLOW_TICKS = 12;
    static final double FEAR_SLOW = 0.35;
    /** 본인 좌클릭 몇 번이면 떨어짐, 떨어져 떠는 틱, 다시 고르기까지. */
    static final int SHOVES = 2, DROPPED = 30, COOLDOWN = 60;
    static final double R = 0.5;

    enum Phase { DRIFT, RUSH, STARE, FEAR, DROPPED }

    private Phase phase = Phase.DRIFT;
    private int phaseAt, readyAt, gauge, stage = -1, shoves;
    private Player prey, victim;
    private final ArrayDeque<Vector> looks = new ArrayDeque<>();
    private String shown;
    /** 대상 본인이 쏜 발 수(마지막으로 본 값)와 그 사람이 쏜 틱: 그 틱의 탄은 이 몸을 지나간다. */
    private int victimShots;
    private long passTick = Long.MIN_VALUE;

    public G23(Game game, Species sp) {
        super(game, sp);
        radius = R;
        baseSpeed = SPEED;
        state("e0");
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/" + sp.key(), 1.6f, 1.6f);
    }

    @Override
    protected void spawned() {
        readyAt = age + 20;
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
            case DRIFT -> drift();
            case RUSH -> rush();
            case STARE -> stare();
            case FEAR -> fear();
            case DROPPED -> dropped();
        }
    }

    /** 떼 틈에 섞여 옴. 7블록 안 보이는 사람(아직 아무도 붙지 않은)을 고름. */
    private void drift() {
        flying = false;
        noPush = false;
        follow(speed());
        Player near = nearest(40);
        if (near != null) face(chest(near));
        body.roll = 0;
        if (age < readyAt) return;
        Player p = acquire(PICK_R);
        if (p != null && free(p)) {
            prey = p;
            mark(p);
            phase(Phase.RUSH);
        }
    }

    private boolean free(Player p) {
        PState s = game.players.get(p);
        return s.standing() && s.headBy == null && s.grabbedBy == null;
    }

    /** 얼굴 앞으로 미끄러져 옴. */
    private void rush() {
        if (!standing(prey) || !free(prey) || inPhase() > 60) {
            calm();
            return;
        }
        flying = true;
        noPush = true;
        Vector want = frontOf(prey, dir(prey));
        Vector d = want.clone().subtract(pos);
        double l = d.length();
        vel.copy(l < 1e-6 ? d : d.multiply(Math.min(l, RUSH_V * speed() / baseSpeed) / l));
        face(prey.getEyeLocation().toVector());
        if (l < 0.45) attach(prey);
    }

    private static final ItemStack NOTHING = nothing();

    private static ItemStack nothing() {
        ItemStack it = ItemStack.of(Material.PAPER);
        it.setData(DataComponentTypes.ITEM_MODEL, Key.key(Props.NS, Body.EMPTY));
        return it;
    }

    private void attach(Player p) {
        PState ps = game.players.get(p);
        // 비옷은 붙는 것을 한 번 막는다(찢어지고, 이 개체는 튕겨 떨어짐)
        if (!ps.standing() || ps.headBy != null || game.players.coat(p, ps)) {
            bounce();
            return;
        }
        game.players.attachHead(p, NOTHING, this);
        if (ps.headBy != this) {
            calm();
            return;
        }
        victim = p;
        mark(p);
        noBite = true; // 얼굴 앞에 붙은 동안 물어뜯기로 멈추면 덮개만 남는다
        Guns.Arms arms = game.guns.arms(p);
        victimShots = arms == null ? 0 : arms.shots;
        body.hide(p, true);
        looks.clear();
        gauge = 0;
        stage = -1;
        shoves = 0;
        sound("cling", Sound.ENTITY_SLIME_SQUISH, 1.2f, 16);
        phase(Phase.STARE);
        stageTo(0);
    }

    private static Vector dir(Player p) {
        return p.getEyeLocation().getDirection();
    }

    private static Vector frontOf(Player p, Vector d) {
        return p.getEyeLocation().toVector().add(d.clone().multiply(FRONT));
    }

    /** 늦게(6틱 전 시선) 따라와 얼굴 앞을 가림. 갸웃. 덮개는 시선이 크게 어긋날 때만 걷힘. */
    private boolean follow(Player p) {
        Vector now = dir(p);
        looks.addLast(now.clone());
        while (looks.size() > LAG + 1) looks.removeFirst();
        Vector lag = looks.peekFirst();
        Vector want = frontOf(p, lag);
        vel.copy(want.subtract(pos));
        face(p.getEyeLocation().toVector());
        body.roll = (float) (Math.sin(age * 0.07 + id) * 16);
        double ang = Math.toDegrees(now.angle(lag));
        return ang <= LAG_DEG;
    }

    private void stare() {
        Player p = victim;
        if (!attached(p)) {
            unhook(p, true);
            return;
        }
        boolean inView = follow(p);
        float pitchNow = p.getLocation().getPitch();
        boolean away = Math.abs(pitchNow) >= LOOK_AWAY;
        if (inView && !away) gauge++;
        else gauge = Math.max(0, gauge - DRAIN);
        int st = Math.min(3, gauge / STEP);
        if (st != stage) stageTo(st);
        show(p, inView ? "g23_e" + stage : null);
        game.hud.meter(p, sp.key(), (double) gauge / FULL, 6);
        if (gauge >= FULL) scare(p);
    }

    private void stageTo(int st) {
        boolean up = st > stage;
        stage = st;
        state("e" + st);
        if (up && st > 0) {
            sound("squelch", Sound.BLOCK_HONEY_BLOCK_BREAK, 1.2f, 16);
            body.squash(1.06f, 1.06f, 0.9f, 2);
        } else body.squash(1f, 1f, 1f, 3);
    }

    /** 대상 화면 덮개를 key 로(null 이면 걷음). 이 종 덮개가 아닌 것은 건드리지 않음. */
    private void show(Player p, String key) {
        PState s = game.players.get(p);
        long now = game.now();
        boolean ours = s.cover != null && s.cover.startsWith("g23_") && s.coverUntil > now;
        if (key == null) {
            if (ours) game.players.uncover(p);
            shown = null;
            return;
        }
        if (ours && key.equals(s.cover) && s.coverUntil - now >= 20) return;
        if (ours) game.players.uncover(p);
        if (game.players.cover(p, key, 200)) shown = key;
    }

    /** 겁먹음 4초: 느려짐, 손떨림, 화면 가장자리 어둠. 얼굴은 그대로 앞에(덮개 g23_fear 가 다 뜬 눈 얼굴과 어둠을 함께 그림). */
    private void scare(Player p) {
        game.players.slow(p, FEAR_SLOW, FEAR_SLOW_TICKS, "g23");
        show(p, null);
        shown = game.players.cover(p, "g23_fear", FEAR) ? "g23_fear" : null;
        game.hud.notice(p, "notice_g23_fear", 40);
        if (!game.director.silent()) Props.soundTo(p, p.getLocation(), "gabe.g23.fear", Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 1f, 1f, -1);
        game.hud.meter(p, sp.key(), -1, 0);
        phase(Phase.FEAR);
    }

    private void fear() {
        Player p = victim;
        if (!attached(p)) {
            unhook(p, true);
            return;
        }
        follow(p);
        int t = inPhase();
        if (t % TREMOR_EVERY == 0) {
            float a = (float) game.random.nextGaussian() * 1.6f, b = (float) game.random.nextGaussian() * 1.2f;
            Guns.kick(p, a, b);
        }
        if (t % 5 == 0 && t < FEAR) {
            // 느려짐은 짧게 이어 건다(이 개체가 죽거나 떨어지면 곧 풀리게). 다른 덮개에 밀렸던 어둠도 다시
            game.players.slow(p, FEAR_SLOW, FEAR_SLOW_TICKS, "g23");
            if (game.players.get(p).cover == null && game.players.cover(p, "g23_fear", FEAR - t)) shown = "g23_fear";
        }
        if (t >= FEAR) {
            PState s = game.players.get(p);
            if ("g23_fear".equals(s.cover)) game.players.uncover(p);
            gauge = 0;
            stage = -1;
            stageTo(0);
            phase(Phase.STARE);
        }
    }

    private boolean attached(Player p) {
        return p != null && p.isOnline() && standing(p) && game.players.get(p).headBy == this;
    }

    /** 떨어짐: 덮개, 숨김, 게이지를 풂. 살아 있으면 바닥으로 떨어져 잠깐 떪. */
    private void unhook(Player p, boolean drop) {
        victim = null;
        if (p != null) {
            PState s = game.players.get(p.getUniqueId());
            if (s != null && s.cover != null && s.cover.startsWith("g23_")) game.players.uncover(p);
            if (body != null) body.hide(p, false);
            game.hud.meter(p, sp.key(), -1, 0);
            if (s != null && s.headBy == this) game.players.attachHead(p, null);
        }
        shown = null;
        noBite = false;
        mark(null);
        prey = null;
        if (body != null) body.roll = 0;
        if (!dead && drop) bounce();
    }

    /** 튕겨 떨어짐(떼어짐, 비옷에 막힘): 바닥으로 떨어져 잠깐 떨다가 다시 떠돈다. */
    private void bounce() {
        mark(null);
        prey = null;
        stage = -1;
        stageTo(0);
        body.squash(1.15f, 0.8f, 1.15f, 3);
        phase(Phase.DROPPED);
    }

    /** 딸랑이, 거미줄: 떠돌 때만 끌린다(사람 얼굴로 달려들거나 붙어 있는 동안은 무시). */
    @Override
    public void lure(Vector at, int ticks) {
        if (phase == Phase.DRIFT) super.lure(at, ticks);
    }

    private void dropped() {
        flying = true;
        noPush = false;
        double fl = floor();
        vel.setX(0).setZ(0).setY(Math.max(-0.4, fl + 0.55 - pos.getY()));
        pitch = 20;
        if (inPhase() >= DROPPED) {
            body.squash(1f, 1f, 1f, 4);
            pitch = 0;
            readyAt = age + COOLDOWN;
            phase(Phase.DRIFT);
        }
    }

    private void calm() {
        mark(null);
        prey = null;
        readyAt = age + 20;
        phase(Phase.DRIFT);
    }

    // ------------------------------------------------------------------ 상호작용, 맞음

    /** 대상 본인이 이것을 보고 좌클릭: 두 번이면 떨어짐. */
    @Override
    public boolean helped(Player p) {
        if (p == null || p != victim) return false;
        shoves++;
        body.squash(0.85f, 0.85f, 1.1f, 2);
        sound("cling", Sound.ENTITY_SLIME_SQUISH, 1.4f, 16);
        if (shoves >= SHOVES) unhook(p, true);
        return true;
    }

    @Override
    public void detached(Player p, Player helper, String how) {
        if (p != null && p == victim) unhook(p, true);
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (victim != null) {
            Player by = d.by();
            if (by != null && by == victim) return false; // 본인은 무기로 떼지 못한다(탄은 spheres 가 지나가게 하고, 폭발과 불은 여기서 막음)
            if (by != null) {
                // 동료가 쏘면 한 방
                Damage kill = new Damage(Math.max(d.amount(), hp + 1), d.type(), by, d.at(), d.dir(), d.how(), d.aimTicks());
                return super.damage(kill);
            }
        }
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        Player v = victim;
        super.die(d);
        if (v != null) unhook(v, false);
    }

    @Override
    public void remove() {
        Player v = victim;
        super.remove();
        if (v != null) unhook(v, false);
    }

    /**
     * 맞는 구 하나. 붙어 있는 동안 대상 본인이 쏜 틱에는 비운다: 이 몸은 그 사람 눈앞 0.7블록에 있어(탄 여유까지 치면 눈이 구 안)
     * 본인의 모든 탄이 여기서 멈춘다. 본인 화면에서 이 종은 덮개 그림일 뿐이니 탄은 지나가 다른 게이브를 맞혀야 한다. 총은 쏜 발 수를
     * 올린 바로 뒤에 광선을 쏘므로(Guns.fire), 발 수가 바뀐 것을 처음 본 이 부름이 그 사람의 광선이고 그 틱이 끝날 때까지 비워 둔다
     * (산탄 여러 알, 관통). 같은 틱에 그 뒤로 쏜 동료 탄도 지나가지만 다음 틱엔 맞는다.
     */
    @Override
    public List<double[]> spheres() {
        Player v = victim;
        if (v != null) {
            Guns.Arms a = game.guns.arms(v);
            long now = game.now();
            if (a != null && a.shots != victimShots) {
                victimShots = a.shots;
                passTick = now;
            }
            if (passTick == now) return List.of();
        }
        return List.of(new double[] {pos.getX(), pos.getY(), pos.getZ(), R});
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " gauge=" + gauge + (victim != null ? " on=" + victim.getName() : "") + (shown != null ? " cover=" + shown : "");
    }
}
