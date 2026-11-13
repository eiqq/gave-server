package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.Species;

/**
 * G113 성난 새장 게이브(정예, 방패). 한꺼번에 세게 쏘면 안의 얼굴이 빨개지며 문을 열고 날뛰는 창살 새장.
 *
 * <p>도감: 창살 다리 넷으로 또각또각 걸어온다(0.16). 대충 쏜(겨누지 않은) 정면 탄은 절반을 창살이 튕긴다. 최근 2초(40틱)에 받은 피해가 maxHp 의 12%
 * 를 넘으면 꼭대기 종이 '땡' 하고(0.5초) 문이 벌컥 열리며 5초 날뛴다(빠르기 x2.2, 0.6초마다 창살 휘두르기 18 과 2블록 밀침, 받는 피해 절반). 그 뒤
 * 3초 주저앉아 헐떡인다(창살이 벌어져 받는 피해 x2). 안의 얼굴 빛이 곧 게이지: 노랑(문턱 절반 아래), 주황, 빨강(입이 귀까지 찢어짐). 평소엔 창살
 * 다리 찍기 10.
 *
 * <p>팩 계약(tools/gabe/sp/g113.py): 모델 gave:gabe/g113(상태 "", "rage", "pant"), 부품 gave:gabe/g113_face(부위 hot, rage). 소리 bell, ting,
 * stomp, swing, pant. 원인 글귀 cause_g113.
 */
public final class G113 extends Gabe {

    static final double SPEED = 0.16, HOVER = 1.3, RAGE_SPEED = 2.2;
    static final int WIN = 40, TELL = 10, RAGE_T = 100, PANT_T = 60, SWING_EVERY = 12, STOMP_CD = 24;
    static final double TRIP = 0.12, HOT_AT = 0.5, DEFLECT = 0.5, RAGE_TAKE = 0.5, PANT_TAKE = 2.0;
    static final double STOMP = 10, SWING = 18, SWING_R = 2.3;
    static final float FACE_Y = 0.12f;

    enum Phase { WALK, TELL, RAGE, PANT }

    private Phase phase = Phase.WALK;
    private int phaseAt, stompAt = -100, tingAt = -100;
    private final double[] win = new double[WIN];
    private int winAt = -1;
    private Body.Part face;
    private Player prey;

    public G113(Game game, Species sp) {
        super(game, sp);
        radius = 0.95;
        hover = HOVER;
        baseSpeed = SPEED;
        ragKick = 0.5;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g113", 2.4f, 3.0f);
        face = body.attach("gabe/g113_face");
        if (face != null) face.pose(new Matrix4f().translate(0, FACE_Y, 0), 0);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    private Vector fwd() {
        double r = Math.toRadians(yaw);
        return new Vector(-Math.sin(r), 0, Math.cos(r));
    }

    /** 최근 40틱 받은 피해 합. */
    private double heat() {
        double s = 0;
        for (double v : win) s += v;
        return s;
    }

    private double trip() {
        return TRIP * maxHp;
    }

    private void roll() {
        // 지난 틱 칸들을 비움(틱이 건너뛰어도 맞게)
        if (winAt < 0) winAt = age;
        while (winAt < age) {
            winAt++;
            win[Math.floorMod(winAt, WIN)] = 0;
        }
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        roll();
        switch (phase) {
            case WALK -> walk();
            case TELL -> tell();
            case RAGE -> rage();
            case PANT -> pant();
        }
        swingFace();
    }

    /** 안의 얼굴 빛(노랑, 주황, 빨강)과 흔들림. */
    private void glow(int level) {
        if (face == null) return;
        boolean hot = level >= 1, red = level >= 2;
        if (hot) face.look.on.add("hot");
        else face.look.on.remove("hot");
        if (red) face.look.on.add("rage");
        else face.look.on.remove("rage");
        face.look.mut = red ? 1 : 0;
        if (hot) face.look.palette |= Look.PAL_DEEP_BLUSH;
        else face.look.palette &= ~Look.PAL_DEEP_BLUSH;
        face.look.open = body.look.open;
    }

    private void swingFace() {
        if (face == null || !every(4)) return;
        double amp = phase == Phase.RAGE ? 16 : phase == Phase.PANT ? 3 : 6;
        double a = Math.sin(age * (phase == Phase.RAGE ? 0.9 : 0.3)) * amp;
        face.pose(new Matrix4f().translate(0, FACE_Y + 0.5f, 0).rotateZ((float) Math.toRadians(a)).translate(0, -0.5f, 0), 4);
    }

    private void walk() {
        follow(speed());
        Player p = nearest(400);
        double h = heat();
        glow(h >= trip() * HOT_AT ? 1 : 0);
        if (p == null) {
            if (vel.lengthSquared() > 1e-6) face(pos.clone().add(vel));
            pitch = 0;
            mark(null);
            return;
        }
        double d = dist(p);
        if (vel.lengthSquared() > 1e-6 && d > 4) face(pos.clone().add(vel));
        else face(chest(p));
        pitch = 0;
        mark(d <= 10 ? p : null);
        if (age - stompAt >= cd(STOMP_CD) && touches(p, 0.25)) {
            stompAt = age;
            hit(p, STOMP, "cause_g113");
            knock(p, toward(p), 0.35, 0.1);
            sound("stomp", Sound.BLOCK_CHAIN_HIT, 0.8f, 20);
            body.squash(1.04f, 0.92f, 1.04f, 2);
        }
        if (h >= trip()) {
            prey = p;
            mark(p);
            hold();
            sound("bell", Sound.BLOCK_BELL_USE, 1.0f, 32);
            glow(2);
            phase(Phase.TELL);
        }
    }

    /** 꼭대기 종이 '땡': 0.5초 부르르 떨고 문을 연다. */
    private void tell() {
        hold();
        body.roll = (float) (Math.sin(inPhase() * 2.1) * 4);
        if (inPhase() < TELL) return;
        body.roll = 0;
        state("rage");
        body.squash(1.08f, 1.04f, 1.08f, 3);
        phase(Phase.RAGE);
    }

    /** 5초 날뜀: 빠르게 쫓으며 0.6초마다 창살을 휘두름. */
    private void rage() {
        glow(2);
        if (!standing(prey)) {
            prey = nearest(400);
            mark(prey);
        }
        if (prey != null) {
            Vector want = prey.getLocation().toVector();
            if (dist(prey) > 1.4) seek(want, speed() * RAGE_SPEED);
            else vel.multiply(0.5);
            face(chest(prey));
        } else follow(speed() * RAGE_SPEED);
        pitch = 0;
        int t = inPhase();
        body.roll = (float) (Math.sin(t * 0.7) * 7);
        if (t > 0 && t % cd(SWING_EVERY) == 0) {
            sound("swing", Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 24);
            body.squash(1.12f, 0.95f, 1.12f, 2);
            for (Player q : game.mobs.standing()) {
                if (dist(q) > SWING_R + radius * 0.5) continue;
                hit(q, SWING, "cause_g113");
                knock(q, toward(q), 0.5, 0.25);
            }
        }
        if (t >= RAGE_T) {
            body.roll = 0;
            body.squash(1.0f, 0.82f, 1.0f, 6);
            hover = HOVER - 0.2;
            state("pant");
            sound("pant", Sound.ENTITY_WOLF_PANT, 0.7f, 20);
            mark(null);
            phase(Phase.PANT);
        }
    }

    /** 3초 주저앉아 헐떡임(약점). */
    private void pant() {
        hold();
        glow(1);
        if (inPhase() % 20 == 10) {
            body.squash(1.0f, 0.86f, 1.0f, 8);
            sound("pant", Sound.ENTITY_WOLF_PANT, 0.7f, 20);
        } else if (inPhase() % 20 == 0) body.squash(1.0f, 0.8f, 1.0f, 8);
        if (inPhase() < PANT_T) return;
        body.squash(1f, 1f, 1f, 6);
        hover = HOVER;
        state("");
        for (int i = 0; i < WIN; i++) win[i] = 0;
        prey = null;
        phase(Phase.WALK);
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(2);
        out.add(new double[] {pos.getX(), pos.getY() + 0.25, pos.getZ(), 0.95});
        out.add(new double[] {pos.getX(), pos.getY() - 0.8, pos.getZ(), 0.7});
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        boolean gun = t == DamageType.BULLET || t == DamageType.PELLET;
        if (phase == Phase.WALK && gun && !d.aimed()) {
            // 대충 쏜 정면 탄: 절반을 창살이 튕김(겨눈 사격은 창살 사이로)
            boolean front = d.dir() == null || d.dir().getX() * fwd().getX() + d.dir().getZ() * fwd().getZ() < 0.2;
            if (front && game.random.nextDouble() < DEFLECT) {
                if (age - tingAt >= 3) {
                    tingAt = age;
                    sound("ting", Sound.BLOCK_CHAIN_HIT, 1.6f, 20);
                }
                if (d.by() != null) game.hud.hit(d.by(), false);
                return true;
            }
        }
        double k = phase == Phase.RAGE ? RAGE_TAKE : phase == Phase.PANT ? PANT_TAKE : 1.0;
        Damage e = k == 1.0 ? d : d.times(k);
        if (phase == Phase.WALK) {
            roll();
            win[Math.floorMod(age, WIN)] += e.amount();
        }
        return super.damage(e);
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " heat=" + Math.round(heat()) + "/" + Math.round(trip());
    }
}
