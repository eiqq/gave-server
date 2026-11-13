package kr.gave.mob.sp;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.mob.Mobs;
import kr.gave.mob.Species;
import kr.gave.trip.Trip;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;
import kr.gave.world.Roam;

/**
 * G187 테두리 갉는 게이브(4막 31라운드, 떼 + 목표 압박). 검은 얼굴 무리 열둘이 싸움터 바깥 고리(4막 처음 R 46)에 30도마다 엎드려 땅을 갉는다.
 * 고리 반지름은 판 단위 틱(G187.Ring)이 지킨다: 웨이브 중 200틱마다 0.1 x 산 무리 수만큼 줄고(22 아래로는 안 줆) 무리는 새 자리로 한 뼘 옮겨 박힌다('딱').
 * 웨이브가 끝나면 반지름 = max(지금, 34)(막 내내 잃는 땅 12블록까지), 막이 바뀌면 처음으로. 갉힌 바깥(고리 판)에 선 사람은 0.5초마다 6 과 40% 느려짐,
 * 사람의 끝(ARENA, Roam.limit)은 고리 + 3. 사람이 8블록 안에 오면 띠째 일어나 덮친다(10, 겹쳐 있는 동안 0.5초마다 4). 고리가 22 에 닿으면 갉기를
 * 멈추고 띠째 우주선(트럭)으로 기어가 문다. 무리는 늘 가장 가까운 사람을 찍어(mark) 웨이브를 붙든다: 잡으러 나가야 한다.
 *
 * <p>팩 계약(tools/gabe/sp/g187.py): gabe/g187(shrink 0.5, 상태 "", "rise"), gabe/g187_ring(고리 판 3 x 3, 안쪽 반지름 = 1.5 x RING_IN).
 * 소리 gnaw(64), stake(32), rise(24). 글귀 cause_g187, cause_g187_edge, notice_g187.
 *
 * <p>도감과 다른 점: 감독이 하나만 깔면 첫 무리가 나머지를 spawnLike 로 열둘까지 놓는다(웨이브마다). 웨이브 시작 알림의 '남은 반지름 칸'은 HUD 가
 * 수를 못 끼워 재난문자 꼴 짙기 칸(줄어든 몫 0..5)으로 대신한다.
 */
public final class G187 extends Gabe {

    static final String KEY = "G187";
    static final double START_R = 46, KEEP_R = 34, MIN_R = 22, STEP = 0.1, RING_IN = 0.2, EDGE_DMG = 6, EDGE_SLOW = 0.4, ROAM_PAD = 3;
    static final double POUNCE_R = 8, POUNCE_DMG = 10, OVER_DMG = 4, MOVE_V = 0.22, DASH_V = 0.34;
    static final int GROUPS = 12, STEP_T = 200, DASH_T = 30, BITE_CD = 30;

    enum Phase { GNAW, RISE, BACK, TRUCK }

    private static boolean filling;

    private Phase phase = Phase.GNAW;
    private int phaseAt, slot = -1, overAt, biteAt, steppedAt = -1;
    private Player prey;
    private double placedR = -1;

    public G187(Game game, Species sp) {
        super(game, sp);
        radius = 1.0;
        baseSpeed = MOVE_V;
        noPush = true;
        ghost = true;
        noBite = true;
        noMutate = true;
        ragKick = 0.4;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g187", 4f, 1.6f);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        Ring ring = Ring.of(game);
        ring.src = this;
        boolean first = !filling && count() == 1;
        slot = freeSlot();
        Vector at = slotAt(ring.r);
        game.mobs.relocate(this, at);
        faceCenter();
        placedR = ring.r;
        if (first) {
            game.hud.alert("notice_g187", ring.level(), 100);
            filling = true;
            try {
                for (int i = 1; i < GROUPS && count() < GROUPS; i++) if (game.director.spawnLike(sp, at.clone()) == null) break;
            } finally {
                filling = false;
            }
        }
    }

    private int count() {
        int n = 0;
        for (Gabe g : game.mobs.all()) if (!g.dead && g instanceof G187) n++;
        return n;
    }

    /** 다른 무리가 안 쓴 자리 번호(모두 쓰였으면 가장 적게 쓰인 것). */
    private int freeSlot() {
        int[] used = new int[GROUPS];
        for (Gabe g : game.mobs.all()) if (g != this && !g.dead && g instanceof G187 o && o.slot >= 0) used[o.slot]++;
        int best = 0;
        for (int i = 1; i < GROUPS; i++) if (used[i] < used[best]) best = i;
        return best;
    }

    private Vector slotAt(double r) {
        double a = Math.toRadians(slot * 360.0 / GROUPS + 7);
        double x = Plaza.CX + 0.5 + Math.cos(a) * r, z = Plaza.CZ + 0.5 + Math.sin(a) * r;
        return new Vector(x, game.mobs.flow.floor(x, z) + hover, z);
    }

    private void faceCenter() {
        face(new Vector(Plaza.CX + 0.5, pos.getY(), Plaza.CZ + 0.5));
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
        Ring ring = Ring.of(game);
        if (ring.src == null || ring.src.dead) ring.src = this;
        Player near = nearest(400);
        mark(near);
        if (ring.r <= MIN_R + 0.05 && phase != Phase.TRUCK && Truck.get() != null) {
            phase(Phase.TRUCK);
            state("rise");
            sound("rise", Sound.ENTITY_SILVERFISH_AMBIENT, 0.6f, 24);
        }
        switch (phase) {
            case GNAW -> gnaw(ring, near);
            case RISE -> rise();
            case BACK -> back(ring);
            case TRUCK -> truck();
        }
        if (age % 60 == (id % 60)) sound("gnaw", Sound.ENTITY_GENERIC_EAT, 0.6f, 64);
    }

    private void gnaw(Ring ring, Player near) {
        state("");
        Vector to = slotAt(ring.r);
        double d = Math.hypot(to.getX() - pos.getX(), to.getZ() - pos.getZ());
        if (d > 0.3) {
            seek(to, Math.max(speed(), d > 4 ? 0.4 : MOVE_V));
            steppedAt = -1;
        } else {
            hold();
            if (placedR != ring.r && steppedAt < 0) {
                steppedAt = age;
                placedR = ring.r;
                sound("stake", Sound.BLOCK_WOOD_BREAK, 0.8f, 32);
                body.squash(1.04f, 1.3f, 1.04f, 3);
            }
        }
        faceCenter();
        if (near != null && dist(near) < POUNCE_R) {
            prey = near;
            phase(Phase.RISE);
            state("rise");
            sound("rise", Sound.ENTITY_SILVERFISH_AMBIENT, 0.6f, 24);
        }
    }

    /** 띠째 일어나 덮침: 닿으면 10, 겹쳐 있는 동안 0.5초마다 4. */
    private void rise() {
        if (prey == null || !standing(prey)) {
            phase(Phase.BACK);
            return;
        }
        G66Giant.faceFlat(this, chest(prey));
        if (inPhase() < 8) {
            hold();
            return;
        }
        if (inPhase() < 8 + DASH_T) dash(toward(prey), DASH_V * Math.max(1, speed() / MOVE_V));
        else hold();
        if (touches(prey, 0.4)) {
            if (overAt == 0) {
                hit(prey, POUNCE_DMG, "cause_g187");
                body.squash(1.2f, 0.7f, 1.2f, 3);
                overAt = age;
            } else if ((age - overAt) % 10 == 0) {
                hit(prey, OVER_DMG, "cause_g187");
            }
        }
        if (inPhase() > 8 + DASH_T + 20 && (dist(prey) > POUNCE_R + 2 || inPhase() > 160)) phase(Phase.BACK);
    }

    private void back(Ring ring) {
        overAt = 0;
        prey = null;
        state("");
        Vector to = slotAt(ring.r);
        seek(to, Math.max(speed(), 0.3));
        G66Giant.faceFlat(this, to);
        if (Math.hypot(to.getX() - pos.getX(), to.getZ() - pos.getZ()) < 0.6 || inPhase() > 300) phase(Phase.GNAW);
    }

    /** 고리가 22 에 닿음: 우주선(트럭)으로 기어가 묾. */
    private void truck() {
        Truck t = Truck.get();
        if (t == null || !t.alive()) {
            phase(Phase.BACK);
            return;
        }
        Vector c = Truck.center().setY(pos.getY());
        if (Truck.dist(pos.getX(), pos.getZ()) > 1.3) {
            seek(c, Math.max(speed(), 0.25));
            face(c);
            return;
        }
        hold();
        face(c);
        if (age >= biteAt) {
            biteAt = age + cd(BITE_CD);
            t.hit(t.biteDamage(sp), this, "cause_g187");
            body.squash(1.1f, 0.8f, 1.1f, 3);
            sound("gnaw", Sound.ENTITY_GENERIC_EAT, 0.6f, 64);
        }
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " slot=" + slot + " R=" + String.format("%.2f", Ring.of(game).r) + " roam=" + String.format("%.1f", Roam.radius());
    }

    // ------------------------------------------------------------------ 고리(판 단위 틱 FIELDTICK, ARENA)

    /** 이 자리가 갉힌 땅인가(고리 반지름 밖). 고리가 없으면 false. 포도송이(G191)의 못 막기가 묻는다. */
    static boolean eaten(Game game, double x, double z) {
        Mobs.Field f = game.mobs.field(KEY);
        return f instanceof Ring r && r.d != null && Plaza.radius(x, z) > r.r;
    }

    /** 고리 반지름과 고리 판, 갉힌 땅 피해, 사람의 끝. 막이 바뀌면 처음으로. */
    static final class Ring implements Mobs.Field {
        final Game game;
        double r = START_R;
        int act = -1;
        long stepAt;
        ItemDisplay d;
        double shownR = -1;
        Gabe src;

        private Ring(Game game) {
            this.game = game;
        }

        static Ring of(Game game) {
            Mobs.Field f = game.mobs.field(KEY);
            if (f instanceof Ring r) return r;
            Ring r = new Ring(game);
            game.mobs.field(KEY, r);
            r.act = Trip.actOf(Math.max(1, game.wave));
            r.stepAt = game.now() + STEP_T;
            return r;
        }

        /** 알림 짙기 칸: 줄어든 몫(0..5). */
        int level() {
            return (int) Math.max(0, Math.min(5, Math.round((START_R - r) / (START_R - MIN_R) * 5)));
        }

        @Override
        public boolean tick(long now) {
            if (game.state == Game.State.LOBBY || game.state == Game.State.OVER) {
                clear();
                return true;
            }
            int a = Trip.actOf(Math.max(1, game.wave));
            if (a != act) {
                act = a;
                r = START_R;
                clear();
            }
            int alive = 0;
            for (Gabe g : game.mobs.all()) if (!g.dead && g instanceof G187 o && o.phase != Phase.TRUCK) alive++;
            if (game.state == Game.State.WAVE && now >= stepAt) {
                stepAt = now + STEP_T;
                if (alive > 0) r = Math.max(MIN_R, r - STEP * alive);
            }
            if (r >= START_R - 1e-6 && alive == 0) {
                clear();
                return true;
            }
            show();
            Roam.limit(KEY, r + ROAM_PAD);
            if (game.state == Game.State.WAVE && now % 10 == 0 && src != null) edge();
            return true;
        }

        private void edge() {
            for (Player q : game.mobs.standing()) {
                Location l = q.getLocation();
                if (Plaza.radius(l.getX(), l.getZ()) <= r + 0.3) continue;
                src.hit(q, EDGE_DMG, "cause_g187_edge");
                game.players.slow(q, EDGE_SLOW, 14, "g187edge");
            }
        }

        private void show() {
            if (d == null || !d.isValid()) {
                double y = Plaza.FLOOR_Y + 0.06;
                LookState ls = new LookState();
                ls.freeze = true;
                d = game.world.spawn(new Location(game.world, Plaza.CX + 0.5, y, Plaza.CZ + 0.5), ItemDisplay.class, x -> {
                    x.setPersistent(false);
                    x.setItemStack(Body.item("gabe/g187_ring", "", ls, game.worldTime()));
                    x.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                    x.setBillboard(Display.Billboard.FIXED);
                    x.setBrightness(new Display.Brightness(0, 0));
                    x.setViewRange(8f);
                    x.setDisplayWidth(400f);
                    x.setDisplayHeight(2f);
                    x.setTransformationMatrix(matrix(r));
                });
                shownR = r;
                return;
            }
            if (Math.abs(shownR - r) > 0.02) {
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(20);
                d.setTransformationMatrix(matrix(r));
                shownR = r;
            }
        }

        private static Matrix4f matrix(double r) {
            float s = (float) (r / (1.5 * RING_IN));
            return new Matrix4f().scale(s, 1, s).rotateY((float) Math.PI);
        }

        private void clear() {
            if (d != null && d.isValid()) d.remove();
            d = null;
            shownR = -1;
            Roam.limit(KEY, 0);
        }

        @Override
        public void waveStart(int wave) {
            stepAt = game.now() + STEP_T;
        }

        @Override
        public void waveEnd(int wave) {
            r = Math.max(r, KEEP_R);
        }

        @Override
        public void reset() {
            clear();
            r = START_R;
        }
    }
}
