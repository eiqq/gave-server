package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.Species;

/**
 * G172 잿가루 게이브(떼, 튼튼이). 쌀알만 한 잿빛 얼굴 한 줌이 재 위를 흐르고, 총알에 맞으면 둘레 줌이 맞은 자리로 몰려와 사람 꼴로 뭉쳐 일어선다
 * (폭발과 불은 뭉치게 하지 않고 두 배로 든다).
 *
 * <p>단계: 0 한 줌(상태 "", 0.2블록/틱, 발목 갉기 3), 1 무릎 꿇은 재 사람(상태 kneel, 0.24, 들이받기 12와 밀림), 2 선 재 사람(상태 stand, 0.18, 끌어안기 22와
 * 넘어짐 0.6초). 총알(BULLET, PELLET, SNIPER)에 맞은 줌이나 무릎 꿇은 것은 뭉침의 가운데가 되고(그것이 죽었으면 맞은 자리 5블록 안 가장 가까운 줌이
 * 제자리에서 가운데가 됨), 맞은 자리 5블록 안의 줌과 재 사람 넷까지가 0.4블록/틱으로 몰려와 흡수된다(흡수된 것은 보상 없이 사라짐). 0.8초 뒤 하나라도
 * 흡수됐으면 한 단계 오른다: 체력 = 제 체력 + 흡수한 체력 + 30(무릎) 또는 80(선 꼴), 몸이 작게 시작해 커짐. 두 단계까지.
 *
 * <p>보상: 한 줌 0원, 뭉친 덩이는 단계마다 감독이 정한 한 몫(4원 x 보상 배율). 잿빛은 팔레트 PAL_PALE.
 *
 * <p>팩 계약(tools/gabe/sp/g172.py): 상태 "", "kneel", "stand". 소리 sift(20), merge(28), ram(16). 글귀 cause_g172.
 */
public final class G172 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double[] HOVER = {0.1, 0.6, 1.2}, RADIUS = {0.32, 0.55, 0.75}, SPEED = {0.2, 0.24, 0.18}, GAIN = {0, 30, 80};
    static final String[] STATE = {"", "kneel", "stand"};
    /** 뭉침: 부르는 반경, 수, 걸리는 틱(0.8초), 흡수 거리, 몰려오는 빠르기. */
    static final double MERGE_R = 5, ABSORB_R = 0.8, FEED_SPEED = 0.4;
    static final int MERGE_N = 4, MERGE_T = 16;
    /** 공격: 갉기, 들이받기(준비, 돌진 빠르기와 틱, 쿨다운), 끌어안기. */
    static final double GNAW = 3, RAM = 12, RAM_V = 0.45, HUG = 22, RAM_RANGE = 3.2;
    static final int GNAW_CD = 10, RAM_WIND = 8, RAM_T = 8, RAM_CD = 30, HUG_CD = 40, HUG_ROOT = 12;

    private int stage;
    private boolean init;
    private int unitReward;
    /** 뭉치는 중인 가운데(흡수할 몫, 흡수한 수, 시작 나이, 부른 것들). */
    private boolean merging;
    private int mergeAt, absorbed;
    private double pending;
    private final List<G172> feeders = new ArrayList<>(MERGE_N);
    /** 몰려가는 중이면 가운데. */
    private G172 feedTo;
    private int readyAt, ramAt = -1;
    private final Vector ramDir = new Vector();
    private boolean ramming;
    private int growAt = -1;

    public G172(Game game, Species sp) {
        super(game, sp);
        radius = RADIUS[0];
        hover = HOVER[0];
        baseSpeed = SPEED[0];
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g172", 1.0f, 0.6f);
        body.look.palette |= Look.PAL_PALE;
    }

    /** 첫 틱: 감독이 곱한 보상을 한 몫으로 기억하고 한 줌은 0 원. */
    private void init() {
        init = true;
        unitReward = noReward ? 0 : reward;
        reward = 0;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!init) init();
        if (age == growAt) body.scale(1f, 8);
        if (feedTo != null) {
            feedTick();
            return;
        }
        if (merging) {
            mergeTick();
            return;
        }
        Player p = nearest(400);
        if (p == null) {
            follow(speed());
            return;
        }
        switch (stage) {
            case 0 -> handful(p);
            case 1 -> kneel(p);
            default -> stand(p);
        }
    }

    private void handful(Player p) {
        follow(speed());
        face(chest(p));
        if (age >= readyAt && touches(p, 0.1)) {
            hit(p, GNAW, "cause_g172");
            readyAt = age + cd(GNAW_CD);
        }
    }

    /** 무릎 꿇은 재 사람: 비틀거리며 달려들어 들이받음. */
    private void kneel(Player p) {
        if (ramming) {
            dash(ramDir, RAM_V * speed() / baseSpeed);
            if (touches(p, 0.2)) {
                hit(p, RAM, "cause_g172");
                knock(p, ramDir, 0.6, 0.2);
                sound("ram", Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.7f, 16);
                endRam();
            } else if (age - ramAt >= RAM_WIND + RAM_T || wallAhead(0.6)) endRam();
            return;
        }
        if (ramAt >= 0) {
            hold();
            face(chest(p));
            if (age - ramAt >= cd(RAM_WIND)) {
                ramDir.copy(toward(p));
                ramming = true;
            }
            return;
        }
        follow(speed());
        face(chest(p));
        body.roll = (float) (Math.sin(age * 0.35) * 7);
        if (age >= readyAt && dist(p) <= RAM_RANGE && sees(p)) {
            if (target != p) mark(p);
            ramAt = age;
            hold();
        }
    }

    private void endRam() {
        ramming = false;
        ramAt = -1;
        hold();
        readyAt = age + cd(RAM_CD);
    }

    /** 선 재 사람: 느리게 비틀거리며 와 끌어안음. */
    private void stand(Player p) {
        follow(speed());
        face(chest(p));
        body.roll = (float) (Math.sin(age * 0.22) * 9);
        if (dist(p) < 10 && target != p) mark(p);
        if (age >= readyAt && touches(p, 0.3)) {
            hit(p, HUG, "cause_g172");
            game.players.root(p, HUG_ROOT, "g172");
            sound("ram", Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.6f, 16);
            readyAt = age + cd(HUG_CD);
        }
    }

    // ------------------------------------------------------------------ 뭉침

    /** 맞은 자리로 몰려가는 중: 가운데에 닿으면 흡수됨. */
    private void feedTick() {
        G172 c = feedTo;
        if (c.dead || !c.merging) {
            feedTo = null;
            return;
        }
        Vector to = c.pos.clone().subtract(pos).setY(0);
        if (to.length() <= ABSORB_R + c.radius) {
            c.pending += Math.max(0, hp);
            c.absorbed++;
            c.feeders.remove(this);
            feedTo = null;
            noReward = true;
            remove();
            return;
        }
        seek(c.pos, FEED_SPEED);
        face(c.pos);
    }

    private void mergeTick() {
        hold();
        if (every(4)) game.world.spawnParticle(Particle.ASH, pos.getX(), pos.getY() + 0.3, pos.getZ(), 6, 0.4, 0.4, 0.4, 0.02);
        if (age - mergeAt < MERGE_T) return;
        merging = false;
        for (G172 f : feeders) if (f.feedTo == this) f.feedTo = null;
        feeders.clear();
        if (absorbed <= 0 || stage >= 2) {
            pending = 0;
            absorbed = 0;
            return;
        }
        stage++;
        hp += pending + GAIN[stage];
        maxHp = Math.max(maxHp, hp);
        pending = 0;
        absorbed = 0;
        state(STATE[stage]);
        hover = HOVER[stage];
        radius = RADIUS[stage];
        baseSpeed = SPEED[stage];
        reward = unitReward * stage;
        // 작게 시작해 커짐(다음 틱에 1 로 보간)
        body.scale(0.55f, 0);
        growAt = age + 1;
        readyAt = age + 10;
        sound("merge", Sound.BLOCK_SAND_BREAK, 0.6f, 28);
        Player p = nearest(400);
        if (p != null) mark(p);
    }

    /** at 에서 뭉침을 시작(가운데 = 이것). 둘레 줌과 재 사람을 부름. */
    private void startMerge(Vector at) {
        if (merging || stage >= 2) return;
        merging = true;
        mergeAt = age;
        absorbed = 0;
        pending = 0;
        feeders.clear();
        sound("sift", Sound.BLOCK_SAND_FALL, 0.7f, 20);
        for (Gabe g : game.mobs.near(at, MERGE_R, g -> g != this && g instanceof G172 o && !o.dead && o.stage < 2 && o.feedTo == null
                && !o.merging)) {
            G172 o = (G172) g;
            o.feedTo = this;
            feeders.add(o);
            if (feeders.size() >= MERGE_N) break;
        }
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (!init) init();
        DamageType t = d.type();
        if (t == DamageType.EXPLOSION || t == DamageType.FIRE) return super.damage(d.times(2));
        boolean gun = t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER;
        Vector at = d.at() != null ? d.at().clone() : pos.clone();
        boolean r = super.damage(d);
        if (!gun || stage >= 2 || feedTo != null) return r;
        if (!dead) {
            startMerge(at);
            return r;
        }
        // 맞은 것이 죽음: 맞은 자리 둘레에서 가장 가까운 줌이 가운데가 됨(제자리에서, 나머지가 그쪽으로 몰려감)
        G172 core = null;
        double best = MERGE_R * MERGE_R;
        for (Gabe g : game.mobs.near(at, MERGE_R, g -> g != this && g instanceof G172 o && !o.dead && o.stage < 2 && o.feedTo == null
                && !o.merging)) {
            double d2 = g.pos.distanceSquared(at);
            if (d2 < best) {
                best = d2;
                core = (G172) g;
            }
        }
        if (core != null) core.startMerge(at);
        return r;
    }

    /** 시험용. */
    public String info() {
        return "stage=" + stage + (merging ? " merging(" + absorbed + "/" + feeders.size() + ")" : "") + (feedTo != null ? " feed" : "");
    }

    @Override
    public String toString() {
        return super.toString() + " " + info();
    }
}
