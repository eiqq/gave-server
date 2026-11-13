package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Mobs;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.world.Plaza;

/**
 * G188 사이렌 게이브(4막 31라운드, 지원 + 방해). 키 4.5블록: 막대기 셋 기둥 위 여덟 얼굴 통, 옆에 크랭크 손잡이(부품).
 * 뒤뚱뒤뚱 걸어와 트럭(우주선)에서 R 25 에 멈춰 손잡이를 5초 감고(통 얼굴이 차례로 눈을 뜸), 2초 동안 운다: 20블록 안 사람 10 피해와 조준 흔들림,
 * 판의 단계 +1(다섯까지). 단계마다 그 웨이브가 끝날 때까지 모든 게이브(보스 빼고)의 빠르기 +8%(speedBuff, 판 단위 틱)와 피해 +8%(감독 dmgBoost).
 * 울 때 온 떼가 반 초 멈칫한다. 울고 나면 고리를 따라 10블록 옮겨 가 또 감는다. 손잡이(부위 체력 60)를 맞히면 감던 것이 처음부터. 가까운 사람은 들이받기 18.
 *
 * <p>팩 계약(tools/gabe/sp/g188.py): gabe/g188(shrink 0.5, 상태 "", "siren", 부위 e1..e8 = 그 얼굴 눈 뜸), gabe/g188_crank(축 +X).
 * 소리 siren(96), crank(24), bump(24). 글귀 cause_g188, cause_g188_wail, notice_g188_stage(재난문자 꼴, 짙기 칸 = 단계).
 *
 * <p>도감과 다른 점: 울음 피해 10 은 우는 2초에 5 씩 두 번. 단계는 웨이브가 끝나거나 새 웨이브가 시작하면 0(판 단위 갈고리 G188).
 */
public final class G188 extends Gabe {

    static final String KEY = "G188";
    static final double HOVER = 2.0, SPEED = 0.15, STOP_R = 25, SHIFT = 10, SIREN_R = 20, SIREN_DMG = 5, BUMP_R = 2.2, BUMP_DMG = 18;
    static final double DRUM_Y = 1.45, CRANK_X = 1.12, CRANK_HP = 60, STEP = 0.08;
    static final int WIND = 100, SIREN_T = 40, BUMP_CD = 40, MAX_STAGE = 5, PAUSE = 10;

    enum Phase { WALK, WIND, SIREN, MOVE }

    private Phase phase = Phase.WALK;
    private int phaseAt, bumpAt, openShown;
    private double angle, crankHp = CRANK_HP;
    private final Vector stop = new Vector();
    private Body.Part crank;
    private final List<double[]> sph = new ArrayList<>(4);
    private int sphAge = -1, sirens, unwinds;

    public G188(Game game, Species sp) {
        super(game, sp);
        radius = 0.7;
        hover = HOVER;
        baseSpeed = SPEED;
        noMutate = true;
        ragKick = 0.6;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g188", 2.6f, 5f);
        crank = body.attach("gabe/g188_crank");
        if (crank != null) crank.local.translation((float) CRANK_X, (float) DRUM_Y, 0f);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        Stage.of(game);
        angle = Math.atan2(pos.getZ() - cz(), pos.getX() - cx());
        stopAt(angle);
    }

    private static double cx() {
        return Plaza.CX + 0.5;
    }

    private static double cz() {
        return Plaza.CZ + 0.5;
    }

    private void stopAt(double a) {
        angle = a;
        stop.setX(cx() + Math.cos(a) * STOP_R).setZ(cz() + Math.sin(a) * STOP_R).setY(pos.getY());
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
        Stage.of(game);
        Player p = nearest(400);
        mark(p != null && dist(p) < 40 ? p : null);
        switch (phase) {
            case WALK, MOVE -> walk(p);
            case WIND -> wind(p);
            case SIREN -> siren();
        }
        if (phase != Phase.SIREN) bump();
    }

    private void walk(Player p) {
        state("");
        if (crank != null) crank.look.rate = 1.0;
        double dx = stop.getX() - pos.getX(), dz = stop.getZ() - pos.getZ();
        if (Math.hypot(dx, dz) < 1.0 || inPhase() > 1200) {
            hold();
            startWind();
            return;
        }
        seek(stop, speed());
        G66Giant.faceFlat(this, stop);
    }

    private void startWind() {
        phase(Phase.WIND);
        crankHp = CRANK_HP;
        closeFaces();
    }

    private void closeFaces() {
        for (int i = 1; i <= 8; i++) body.look.on.remove("e" + i);
        openShown = 0;
    }

    private void wind(Player p) {
        hold();
        if (p != null) turnTo(chest(p).setY(pos.getY()), 3f);
        int need = cd(WIND);
        double f = Math.min(1, inPhase() / (double) need);
        if (crank != null) crank.look.rate = 1.0 + 3.0 * f;
        int open = (int) Math.min(8, Math.floor(f * 8.5));
        while (openShown < open) body.look.on.add("e" + (++openShown));
        if (inPhase() % Math.max(3, (int) Math.round(10 - 6 * f)) == 0) sound("crank", Sound.BLOCK_WOODEN_DOOR_OPEN, 0.6f + (float) f, 24);
        if (inPhase() >= need) startSiren();
    }

    private void startSiren() {
        phase(Phase.SIREN);
        state("siren");
        if (crank != null) crank.look.rate = 4.0;
        sirens++;
        sound("siren", Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.5f, 96);
        Stage st = Stage.of(game);
        st.raise();
        game.hud.alert("notice_g188_stage", st.stage, 100);
        body.squash(1.08f, 1.12f, 1.08f, 4);
        wail(true);
    }

    private void siren() {
        hold();
        if (inPhase() == SIREN_T / 2) wail(true);
        else if (inPhase() % 4 == 0) wail(false);
        if (inPhase() >= SIREN_T) {
            state("");
            closeFaces();
            double shift = SHIFT / STOP_R * (game.random.nextBoolean() ? 1 : -1);
            stopAt(angle + shift);
            phase(Phase.MOVE);
        }
    }

    /** 울음: 20블록 안 사람 흔들림(damage 면 5 피해도). */
    private void wail(boolean damage) {
        double r2 = SIREN_R * SIREN_R;
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ();
            if (dx * dx + dz * dz > r2) continue;
            Guns.kick(q, (game.random.nextFloat() - 0.5f) * 3.0f, (game.random.nextFloat() - 0.5f) * 2.2f);
            if (damage) hit(q, SIREN_DMG, "cause_g188_wail");
        }
    }

    private void bump() {
        if (age < bumpAt) return;
        Player q = nearest(BUMP_R + 1.5);
        if (q == null || !touches(q, BUMP_R - radius)) return;
        bumpAt = age + cd(BUMP_CD);
        hit(q, BUMP_DMG, "cause_g188");
        knock(q, toward(q), 1.0, 0.4);
        G66Giant.faceFlat(this, chest(q));
        body.squash(1.1f, 0.85f, 1.25f, 3);
        sound("bump", Sound.ENTITY_IRON_GOLEM_ATTACK, 0.8f, 24);
    }

    // ------------------------------------------------------------------ 맞음

    private Vector crankAt() {
        return G129Kit.at(this, CRANK_X + 0.45, DRUM_Y, 0);
    }

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        sph.add(new double[] {pos.getX(), pos.getY() + DRUM_Y, pos.getZ(), 1.1});   // 0 통
        sph.add(new double[] {pos.getX(), pos.getY() - 0.1, pos.getZ(), 0.42});     // 1 기둥
        sph.add(new double[] {pos.getX(), pos.getY() - 1.3, pos.getZ(), 0.5});      // 2 발
        Vector c = crankAt();
        sph.add(new double[] {c.getX(), c.getY(), c.getZ(), 0.42});                 // 3 손잡이
        sphAge = age;
        return sph;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (d.at() != null && phase == Phase.WIND) {
            List<double[]> s = spheres();
            int best = -1;
            double bd = Double.MAX_VALUE;
            for (int i = 0; i < s.size(); i++) {
                double[] c = s.get(i);
                double dd = Math.sqrt(Math.pow(d.at().getX() - c[0], 2) + Math.pow(d.at().getY() - c[1], 2) + Math.pow(d.at().getZ() - c[2], 2)) - c[3];
                if (dd < bd) {
                    bd = dd;
                    best = i;
                }
            }
            if (best == 3) {
                crankHp -= d.amount();
                if (crankHp <= 0) {
                    unwinds++;
                    phase(Phase.WIND);
                    crankHp = CRANK_HP;
                    closeFaces();
                    if (crank != null) crank.look.rate = 1.0;
                    sound("crank", Sound.BLOCK_WOODEN_DOOR_CLOSE, 0.5f, 24);
                    body.squash(0.94f, 1.05f, 0.94f, 3);
                }
            }
        }
        return super.damage(d);
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " crank=" + Math.round(crankHp) + " sirens=" + sirens + " unwinds=" + unwinds + " stage="
            + Stage.of(game).stage + " stop=" + Math.round(Math.hypot(pos.getX() - stop.getX(), pos.getZ() - stop.getZ()));
    }

    // ------------------------------------------------------------------ 판 단위 단계(FIELDTICK, DMGMUL)

    /** 사이렌 단계: 웨이브마다 0, 울 때마다 +1(다섯까지). 단계마다 모든 게이브(보스 빼고) 빠르기, 피해 +8%. */
    static final class Stage implements Mobs.Field {
        final Game game;
        int stage;
        long pauseUntil;

        private Stage(Game game) {
            this.game = game;
        }

        static Stage of(Game game) {
            Mobs.Field f = game.mobs.field(KEY);
            if (f instanceof Stage s) return s;
            Stage s = new Stage(game);
            game.mobs.field(KEY, s);
            return s;
        }

        void raise() {
            if (stage < MAX_STAGE) stage++;
            game.director.dmgBoost(KEY, 1 + STEP * stage);
            pauseUntil = game.now() + PAUSE;
        }

        @Override
        public boolean tick(long now) {
            if (stage <= 0 || game.state != Game.State.WAVE) return true;
            double mul = 1 + STEP * stage;
            boolean pause = now < pauseUntil;
            for (Gabe g : game.mobs.all()) {
                if (g.dead || g.sp.is(Role.BOSS)) continue;
                if (pause) g.speedBuff = Math.min(g.speedBuff, 0.2);
                else g.speedBuff = Math.max(g.speedBuff, mul);
            }
            return true;
        }

        @Override
        public void waveStart(int wave) {
            clear();
        }

        @Override
        public void waveEnd(int wave) {
            clear();
        }

        @Override
        public void reset() {
            stage = 0;
        }

        private void clear() {
            stage = 0;
            game.director.dmgBoost(KEY, 1);
        }
    }
}
