package kr.gave.mob.sp;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Corpse;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G12 얼굴 꿰는 게이브(방해). 사람을 때리지 않고 시체 근처를 맴돌며 죽은 게이브 얼굴을 쇠사슬에 꿰어 단다(최대 다섯).
 *
 * <p>도감: 꿸수록 낮고 느려진다. 다섯이 차면 사슬째 녹아 겹얼굴 게이브(G27)로 변한다. 꿰인 얼굴들이 매달린 채 킥킥대고 서로 비벼지며
 * 뽀드득. 체력이 낮아 우선 사살하면 꿰인 얼굴 하나당 1원 더. 시체 풀에서 시체를 지운다(G11 과 같은 시체를 다툼).
 *
 * <p>팩 계약(tools/gabe/sp/g12.py): 모델 gave:gabe/g12, 상태 "" 빈 사슬, "s1" .. "s5" 꿰인 수. 소리 grind(뽀드득), giggle(매달린 얼굴),
 * clink(꿰기), melt(녹아 붙음). 글귀 cause_g12(때리지 않으니 쓰이지 않음), notice_g12_merge.
 */
public final class G12 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final int MAX = 5;
    /** 다섯이 차서 녹아 붙은 겹얼굴의 얼굴 수. */
    static final int MERGE_FACES = 3;
    /** 몸 중심 높이(빈 사슬), 꿸 때마다 내려감, 빠르기 배율. */
    static final double HOVER0 = 1.0, HOVER_STEP = 0.07, SPEED = 0.13, SLOW_EACH = 0.9;
    /** 사람과 두는 거리(이보다 가까우면 물러남, 멀면 다가옴), 맴돌기 반지름. */
    static final double KEEP_NEAR = 7, KEEP_FAR = 15;
    /** 시체 찾는 거리, 시체 곁에 사람이 이만큼 가까우면 안 감, 꿰는 틱, 녹는 틱. */
    static final double CORPSE_R = 32, CORPSE_SAFE = 2.5;
    static final int PICK = 24, MELT = 40, MELT_RETRY = 200;

    enum Phase { WANDER, SEEK, PICK, MELT }

    private Phase phase = Phase.WANDER;
    private int phaseAt, strung, nextGrind, nextGiggle, orbitDir = 1, meltAt = -1;
    private Corpse meal;
    private boolean bowed;

    public G12(Game game, Species sp) {
        super(game, sp);
        radius = 0.45;
        hover = HOVER0;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g12", 1.6f, 1.6f);
    }

    @Override
    protected void spawned() {
        orbitDir = game.random.nextBoolean() ? 1 : -1;
        nextGrind = age + 20 + game.random.nextInt(40);
        nextGiggle = age + 40 + game.random.nextInt(60);
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
            case WANDER -> wander();
            case SEEK -> seekMeal();
            case PICK -> pick();
            case MELT -> melt();
        }
        noises();
    }

    /** 싸움에 끼지 않음: 사람과 7~15블록을 두고 맴돈다. 시체가 보이면 그쪽으로. */
    private void wander() {
        if (strung >= MAX) {
            if (meltAt < 0 || age >= meltAt) startMelt();
            else keepAway();
            return;
        }
        if (every(10)) {
            Corpse c = findMeal();
            if (c != null) {
                meal = c;
                phase(Phase.SEEK);
                return;
            }
        }
        keepAway();
    }

    private void keepAway() {
        Player p = nearest(400); // 게이브는 72~84블록에서 나오므로 반경을 넉넉히(전에는 멀면 사람을 못 찾아 외곽에서 멈춤, 2026-11-11)
        if (p == null) {
            hold();
            return;
        }
        double d = flat(p);
        Vector to = toward(p);
        if (d < KEEP_NEAR) vel.copy(to.multiply(-speed()));
        else if (d > KEEP_FAR) follow(speed());
        else {
            // 맴돌기(접선), 가끔 방향을 바꿈
            if (game.random.nextInt(400) == 0) orbitDir = -orbitDir;
            vel.copy(new Vector(-to.getZ() * orbitDir, 0, to.getX() * orbitDir).multiply(speed() * 0.7));
        }
        if (wallAhead(0.8)) orbitDir = -orbitDir;
        face(chest(p));
    }

    /** 시체 쪽으로(사람이 시체 곁에 오면 포기). */
    private void seekMeal() {
        if (meal == null || meal.gone() || crowded(meal)) {
            meal = null;
            phase(Phase.WANDER);
            return;
        }
        Vector to = meal.pos.clone().setY(pos.getY());
        double d = Math.hypot(to.getX() - pos.getX(), to.getZ() - pos.getZ());
        if (d <= 0.7) {
            hold();
            bowed = false;
            phase(Phase.PICK);
            return;
        }
        Vector v = to.subtract(pos).setY(0);
        vel.copy(v.multiply(Math.min(speed(), d) / d));
        face(meal.pos.clone().add(new Vector(0, 0.2, 0)));
    }

    /** 집게로 얼굴을 집어 사슬에 꿴다(고개 숙임, 철컥). */
    private void pick() {
        hold();
        if (meal == null || meal.gone()) {
            meal = null;
            body.squash(1f, 1f, 1f, 3);
            phase(Phase.WANDER);
            return;
        }
        face(meal.pos);
        if (!bowed && inPhase() >= 2) {
            bowed = true;
            body.squash(1.05f, 0.92f, 1.08f, 6);
        }
        if (inPhase() >= PICK) {
            game.mobs.eat(meal);
            meal = null;
            strung++;
            state("s" + strung);
            if (!noReward) reward = sp.reward + strung;
            hover = HOVER0 - HOVER_STEP * strung;
            baseSpeed = SPEED * Math.pow(SLOW_EACH, strung);
            body.squash(1f, 1f, 1f, 4);
            sound("clink", Sound.BLOCK_CHAIN_PLACE, 1.2f, 16);
            phase(Phase.WANDER);
        }
    }

    private void startMelt() {
        if (!Species.G27.implemented() || !game.mobs.budgetFor(Species.G27)) {
            meltAt = age + MELT_RETRY;
            keepAway();
            return;
        }
        hold();
        sound("melt", Sound.ENTITY_SLIME_SQUISH, 0.6f, 24);
        phase(Phase.MELT);
    }

    /** 사슬째 녹아 붙음: 부들부들 흔들리며 가라앉은 뒤 겹얼굴로. */
    private void melt() {
        hold();
        int t = inPhase();
        if (t % 4 == 0) {
            float w = 1f + 0.12f * (float) Math.sin(t * 0.9), h = 1f - 0.25f * t / MELT;
            body.squash(w, h, 2f - w, 4);
        }
        Player p = nearest(40);
        if (p != null) face(chest(p));
        if (t < MELT) return;
        Gabe c = game.mobs.spawn(Species.G27, pos.clone(), true);
        if (c == null) {
            body.squash(1f, 1f, 1f, 6);
            meltAt = age + MELT_RETRY;
            phase(Phase.WANDER);
            return;
        }
        // 변신이지 분열이 아님: 이 개체가 보상 개체였으면 겹얼굴도 보상과 웨이브 체력 배율을 받음
        c.noReward = noReward;
        c.reward = noReward ? 0 : Species.G27.reward;
        double mul = game.director.hpMul(Species.G27);
        c.maxHp *= mul;
        c.hp = c.maxHp;
        c.yaw = yaw;
        // 도감: 얼굴 셋짜리 겹얼굴로(G27 은 tick 마다 얼굴 수로 상태를 다시 고름)
        if (c instanceof G27 lump) lump.faces = Math.max(lump.faces, MERGE_FACES);
        game.hud.notice("notice_g12_merge", Species.G27, 60);
        leaveCorpse = false;
        remove();
    }

    // ------------------------------------------------------------------ 도우미

    private void noises() {
        if (strung <= 0) return;
        if (age >= nextGrind && vel.lengthSquared() > 1e-4) {
            sound("grind", Sound.BLOCK_CHAIN_STEP, 1.5f, 16);
            nextGrind = age + 34 + game.random.nextInt(30);
        }
        if (age >= nextGiggle) {
            sound("giggle", Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, 1.7f, 16);
            nextGiggle = age + 60 + game.random.nextInt(60) - strung * 6;
        }
    }

    private Corpse findMeal() {
        Corpse best = null;
        double bd = CORPSE_R * CORPSE_R;
        for (Corpse c : game.mobs.corpses()) {
            if (c.gone() || crowded(c)) continue;
            double dx = c.pos.getX() - pos.getX(), dz = c.pos.getZ() - pos.getZ();
            double d = dx * dx + dz * dz;
            if (d < bd && game.mobs.flow.walkable(c.pos.getX(), c.pos.getZ())) {
                bd = d;
                best = c;
            }
        }
        return best;
    }

    /** 시체 곁에 사람이 있음(싸움에 끼지 않음). */
    private boolean crowded(Corpse c) {
        return game.mobs.nearestPlayer(c.pos.clone().add(new Vector(0, 0.1, 0)), CORPSE_SAFE) != null;
    }

    private double flat(Player p) {
        Location l = p.getLocation();
        return Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
    }

    /** 시험용: 꿴 얼굴 수를 바로 정함(0..5). */
    public void strungForTest(int n) {
        strung = Math.max(0, Math.min(MAX, n));
        state(strung == 0 ? "" : "s" + strung);
        if (!noReward) reward = sp.reward + strung;
        hover = HOVER0 - HOVER_STEP * strung;
        baseSpeed = SPEED * Math.pow(SLOW_EACH, strung);
    }

    public String phaseName() {
        return phase.name() + " strung=" + strung;
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " strung=" + strung + " reward=" + reward;
    }
}
