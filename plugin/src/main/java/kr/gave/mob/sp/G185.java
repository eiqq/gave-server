package kr.gave.mob.sp;

import java.util.List;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.Species;
import kr.gave.player.Life;
import kr.gave.player.PState;

/**
 * G185 말뚝 든 게이브(떼, 돌격). 가까운 사람이 아니라 저마다 정해진 '제 사람'만 찾아오는 말뚝 든 얼굴. 제 사람이 쓰러지면 곁에 말뚝을 박아 출혈을
 * 앞당긴다.
 *
 * <p>제 사람: 놓일 때 서 있는 사람 가운데 이 종이 찾아가는 수가 가장 적은 사람(같으면 아무나)을 mark 한다(모두에게 고르게). 흐름장과 표적 나누기를
 * 타지 않고 그 사람에게 곧장 걸어 와(0.22) 말뚝으로 찌른다(상태 jab, 8 피해와 살짝 밀림). 다른 사람은 옆에 있어도 지나친다. 제 사람이 죽거나 갇히거나
 * 나가면 다시 고른다. 찾아오는 사람은 조준점 옆에 이 종 표시와 나를 찾는 수 게이지(5 가 가득)를 본다.
 *
 * <p>제 사람이 쓰러지면 그 곁에 말뚝을 박고(상태 plant, 말뚝은 같은 종 child 개체 gabe/g185_stake, 체력 12, 쓰러진 사람마다 다섯까지) 서서 지킨다:
 * 말뚝 하나마다 남은 출혈 2초 줄어듦, 일으키러 온 사람을 찔러 8. 말뚝을 쏴 뽑으면 그만큼 출혈이 돌아오고, 그 사람이 일어나거나 죽으면 말뚝은 사라진다.
 * 출혈을 줄이는 공개 메서드가 없어 PState.bleedUntil 을 직접 고친다(요청: Players 에 출혈 앞당김/되돌림 메서드).
 *
 * <p>얼굴은 흰 눈에 무표정(팔레트 PAL_WHITE_EYES | PAL_BLANK). 팩 계약(tools/gabe/sp/g185.py): 모델 gabe/g185 상태 "", "jab", "plant", 말뚝 gabe/g185_stake.
 * 소리 stake(24), jab(16), pull(20). 글귀 cause_g185.
 */
public final class G185 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double HOVER = 0.8, RADIUS = 0.4, SPEED = 0.22, JAB = 8, PUSH = 0.25, GUARD_R = 2.4, PLANT_R = 1.4;
    static final int JAB_CD = 20, JAB_T = 6, MARK_EVERY = 40, MAX_STAKES = 5, SHAVE = 40;
    /** 말뚝: 높이, 반지름, 체력. */
    static final double STAKE_HOVER = 0.45, STAKE_R = 0.3, STAKE_HP = 12;

    /** child 로 말뚝을 만들 때만 true(생성자가 읽음). */
    private static boolean spawnStake;

    final boolean stake;
    /** 걷는 것: 제 사람, 박았나, 찌른 틱. 말뚝: 박힌 곁의 쓰러진 사람. */
    private Player mine;
    private boolean planted;
    private int readyAt, jabAt = -100;
    private Player victim;

    public G185(Game game, Species sp) {
        super(game, sp);
        stake = spawnStake;
        if (stake) {
            radius = STAKE_R;
            hover = STAKE_HOVER;
            baseSpeed = 0;
            noRules = true;
            noPush = true;
            noBite = true;
            noMutate = true;
            ghost = true;
            leaveCorpse = false;
            maxHp = hp = STAKE_HP;
        } else {
            radius = RADIUS;
            hover = HOVER;
            baseSpeed = SPEED;
        }
    }

    @Override
    protected void build() {
        if (stake) body = Body.of(this, "gabe/g185_stake", 0.8f, 1.6f);
        else {
            body = Body.of(this, "gabe/g185", 1.0f, 1.8f);
            body.look.palette |= Look.PAL_WHITE_EYES | Look.PAL_BLANK;
        }
    }

    @Override
    protected void spawned() {
        if (stake || game.director.cageSpawning()) return;
        choose();
    }

    /** 제 사람: 서 있는 사람 가운데 찾아가는 수가 가장 적은 사람. */
    private void choose() {
        List<Player> ps = game.mobs.standing();
        Player best = null;
        int bn = Integer.MAX_VALUE, ties = 0;
        for (Player p : ps) {
            int c = seekers(p);
            if (c < bn) {
                bn = c;
                best = p;
                ties = 1;
            } else if (c == bn && game.random.nextInt(++ties) == 0) best = p;
        }
        mine = best;
        mark(best);
    }

    /** 이 사람을 찾아가는 산 말뚝 든 게이브 수(나 빼고). */
    private int seekers(Player p) {
        int n = 0;
        for (Gabe g : game.mobs.all()) if (g != this && !g.dead && g instanceof G185 o && !o.stake && o.mine == p) n++;
        return n;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (stake) {
            stakeTick();
            return;
        }
        PState s = mine == null || !mine.isOnline() ? null : game.players.get(mine);
        if (s == null || s.life != Life.ALIVE && s.life != Life.DOWNED) {
            planted = false;
            state("");
            choose();
            if (mine == null) {
                follow(speed());
                return;
            }
            s = game.players.get(mine);
        }
        if (every(MARK_EVERY)) {
            int c = seekers(mine) + 1;
            game.hud.mark(mine, "g185", MARK_EVERY + 10);
            game.hud.meter(mine, "g185", Math.min(1, c / 5.0), MARK_EVERY + 10);
        }
        if (s.life == Life.DOWNED) downed(mine);
        else walk(mine);
        if (!"plant".equals(state) && age - jabAt == JAB_T) state("");
    }

    /** 제 사람에게 곧장 걸어 와 말뚝으로 찌름. */
    private void walk(Player p) {
        if (planted) {
            planted = false;
            state("");
        }
        if (target != p) mark(p);
        Vector c = chest(p);
        if (dist(p) > 40) follow(speed());
        else seek(c.clone().setY(pos.getY()), speed());
        face(c);
        pitch = 0;
        if (age >= readyAt && touches(p, 0.3)) jab(p);
    }

    private void jab(Player p) {
        state("jab");
        jabAt = age;
        hit(p, JAB, "cause_g185");
        knock(p, toward(p), PUSH, 0.05);
        sound("jab", Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 16);
        readyAt = age + cd(JAB_CD);
    }

    /** 쓰러진 제 사람 곁에 말뚝을 박고 지킴(일으키러 온 사람을 찌름). */
    private void downed(Player p) {
        Vector feet = p.getLocation().toVector();
        double d = Math.hypot(feet.getX() - pos.getX(), feet.getZ() - pos.getZ());
        if (!planted) {
            if (d > PLANT_R) {
                seek(feet.clone().setY(pos.getY()), speed());
                face(feet);
                return;
            }
            hold();
            plant(p);
            return;
        }
        // 곁에 서서 지킴
        if (d > GUARD_R) seek(feet.clone().setY(pos.getY()), speed());
        else hold();
        if (age < readyAt) return;
        for (Player q : game.mobs.standing()) {
            if (q == p) continue;
            Vector qf = q.getLocation().toVector();
            if (Math.hypot(qf.getX() - feet.getX(), qf.getZ() - feet.getZ()) > GUARD_R || !touches(q, 0.6)) continue;
            face(chest(q));
            jab(q);
            return;
        }
    }

    private void plant(Player p) {
        planted = true;
        state("plant");
        face(p.getLocation().toVector());
        if (stakesFor(p) >= MAX_STAKES) return;
        double a = game.random.nextDouble() * Math.PI * 2;
        Vector at = p.getLocation().toVector().add(new Vector(Math.cos(a) * 0.9, 0, Math.sin(a) * 0.9));
        at.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + STAKE_HOVER);
        spawnStake = true;
        Gabe g;
        try {
            g = child(sp, at);
        } finally {
            spawnStake = false;
        }
        if (!(g instanceof G185 st)) return;
        st.victim = p;
        st.yaw = game.random.nextFloat() * 360f;
        PState s = game.players.get(p);
        game.players.bleedShift(p, -SHAVE);
        sound("stake", Sound.BLOCK_ANVIL_PLACE, 0.6f, 24);
    }

    /** 이 사람 곁에 박힌 산 말뚝 수. */
    private int stakesFor(Player p) {
        int n = 0;
        for (Gabe g : game.mobs.all()) if (!g.dead && g instanceof G185 o && o.stake && o.victim == p) n++;
        return n;
    }

    // ------------------------------------------------------------------ 말뚝

    private void stakeTick() {
        hold();
        Player p = victim;
        if (p == null || !p.isOnline() || game.players.get(p).life != Life.DOWNED) {
            // 일어나거나 죽음: 말뚝은 조용히 사라짐(되돌림 없음)
            victim = null;
            remove();
        }
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        if (stake) {
            Player p = victim;
            victim = null;
            if (p != null && p.isOnline() && game.players.get(p).life == Life.DOWNED) {
                // 뽑음: 줄인 출혈을 돌려줌
                game.players.bleedShift(p, SHAVE);
                sound("pull", Sound.BLOCK_WOOD_BREAK, 1.3f, 20);
            }
        }
        super.die(d);
    }

    /** 시험용. */
    public String info() {
        if (stake) return "stake" + (victim == null ? "" : " for=" + victim.getName());
        return "mine=" + (mine == null ? "-" : mine.getName()) + (planted ? " planted" : "");
    }

    @Override
    public String toString() {
        return super.toString() + " " + info();
    }
}
