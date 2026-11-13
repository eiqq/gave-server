package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Mobs;
import kr.gave.mob.Species;
import kr.gave.player.Life;
import kr.gave.player.PState;
import kr.gave.world.Plaza;

/**
 * G197 수 채우는 안개 게이브(4막 34라운드, 정예 + 방해). 폭 16블록 빛나는 안개 더미(닻, 맞는 구 없음)가 사람이 가장 많은 곳으로 밀려온다(0.13).
 * 채울 수 = max(2, 서 있는 사람 / 8 올림)(재난문자 꼴 짙기 칸 = 남은 수). 안개 속(수평 8블록): 30% 느려짐, 초당 3, 옅은 화면 테(VEIL "g197_fog").
 * 속의 등불잡이 셋(부품, 각 체력 = 최대 체력의 1/3)이 안개 안에서 사람에게 다가가 4초마다(1초 전 등롱이 밝아짐) 갈고리로 낚아 가둔다(14 + 갇힘, 그 등롱이 붉어짐).
 * 2블록 안은 지팡이 18. 등불잡이를 잡으면 그가 가둔 사람이 풀려나고(FREE), 셋 다 잡으면 안개가 걷힌다. 수를 채우면 온 쪽 지평선(R 88)으로 물러가고,
 * 넘어가면 갇힌 사람은 갇힌 채 남는다(holdTrapped): 다음 웨이브 시작에 같은 쪽에서 붉은 등롱의 등불잡이만(사람마다 하나, 체력 절반) 데리고 다시 밀려오고
 * (retrap), 그 등불잡이를 잡으면 풀려난다. 20초 안에 못 구하면 다시 물러가며 갇힘 끝 규칙(trapEnd: 원정이면 죽음). 수를 못 채우면 웨이브가 끝나도 남는다(persist).
 *
 * <p>팩 계약(tools/gabe/sp/g197.py): gabe/g197(shrink 0.15), gabe/g197_bearer(shrink 0.8, "", "held"). 소리 whisper(48), hook(24), strike(16).
 * 글귀 cause_g197, cause_g197_hook, cause_g197_staff, notice_g197_count, notice_g197_back. 덮개 키 g197_fog(VEIL: HUD 그리기 요청).
 */
public final class G197 extends Gabe {

    static final String KEY = "G197";
    static final double HOVER = 2.5, SPEED = 0.13, FOG_R = 8, FOG_DMG = 3, FOG_SLOW = 0.3, HOOK_R = 1.8, HOOK_DMG = 14, STAFF_R = 2.0, STAFF_DMG = 18;
    static final double REACH = 7, BEARER_V = 0.16, OUT_R = 88, BACK_R = 84;
    static final int N = 3, HOOK_CD = 80, TELL = 20, STAFF_CD = 30, RETURN_T = 400;

    enum Phase { COME, LEAVE, RETURN }

    private static List<Player> returning;

    private Phase phase = Phase.COME;
    private int phaseAt, need, filled;
    private final Body.Part[] part = new Body.Part[N];
    private final double[] bhp = new double[N];
    private final boolean[] alive = new boolean[N];
    private final Player[] held = new Player[N], aim = new Player[N];
    private final Vector[] off = new Vector[N];
    private final int[] hookAt = new int[N], staffAt = new int[N], tellAt = {-1, -1, -1};
    private final Vector from = new Vector();
    private boolean init;
    private final List<double[]> sph = new ArrayList<>(3);
    private int sphAge = -1;

    public G197(Game game, Species sp) {
        super(game, sp);
        radius = 1.0;
        hover = HOVER;
        baseSpeed = SPEED;
        big = true;
        hpPow = Director.Tune.HP_POW_BIG;
        noMutate = true;
        noPush = true;
        noBite = true;
        ghost = true;
        persist = true;
        leaveCorpse = false;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g197", 17f, 6f);
        body.viewRange(3f);
        for (int i = 0; i < N; i++) {
            part[i] = body.attach("gabe/g197_bearer");
            if (part[i] != null) {
                part[i].follow = false;
                if (part[i].d != null) part[i].d.setBrightness(new Display.Brightness(14, 12));
            }
            double a = i * Math.PI * 2 / N;
            off[i] = new Vector(Math.cos(a) * 3.5, 0, Math.sin(a) * 3.5);
            alive[i] = true;
        }
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        from.copy(pos).setY(0);
        Back.of(game);
        if (returning != null) {
            phase(Phase.RETURN);
            List<Player> ps = returning;
            for (int i = 0; i < N; i++) {
                Player p = i < ps.size() ? ps.get(i) : null;
                if (p != null && game.players.retrap(p, this)) {
                    held[i] = p;
                    if (part[i] != null) part[i].state("held");
                } else {
                    alive[i] = false;
                    if (part[i] != null) part[i].remove();
                    part[i] = null;
                }
            }
            game.hud.notice("notice_g197_back", sp, 100);
        }
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    private boolean fighting() {
        return game.state == Game.State.WAVE;
    }

    private int bearers() {
        int n = 0;
        for (boolean b : alive) if (b) n++;
        return n;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!init) {
            init = true;
            double each = maxHp / N * (phase == Phase.RETURN ? 0.5 : 1);
            for (int i = 0; i < N; i++) bhp[i] = alive[i] ? each : 0;
            hp = maxHp = Math.max(1, each * bearers());
            need = Math.max(2, (int) Math.ceil(game.mobs.standing().size() / 8.0));
            if (phase == Phase.COME) game.hud.alert("notice_g197_count", Math.min(5, need), 120);
        }
        Player near = nearest(400);
        mark(phase == Phase.LEAVE ? null : near);
        switch (phase) {
            case COME -> come();
            case LEAVE -> leave();
            case RETURN -> back();
        }
        if (fighting()) fogTick();
        bearersTick();
        if (age % 40 == 0) sound("whisper", Sound.ENTITY_VEX_AMBIENT, 0.5f, 48);
    }

    private void come() {
        Vector c = crowd();
        if (c != null) {
            seek(c.setY(pos.getY()), speed());
            G66Giant.faceFlat(this, c);
        } else hold();
        if (filled >= need) {
            phase(Phase.LEAVE);
            persist = true;
        }
    }

    /** 서 있는 사람이 가장 많이 모인 자리(사람마다 둘레 10블록 안 수가 가장 큰 사람 자리). */
    private Vector crowd() {
        List<Player> ps = game.mobs.standing();
        Player best = null;
        int bn = -1;
        for (Player p : ps) {
            int n = 0;
            for (Player q : ps) if (q.getLocation().distanceSquared(p.getLocation()) < 100) n++;
            if (n > bn) {
                bn = n;
                best = p;
            }
        }
        return best == null ? null : best.getLocation().toVector();
    }

    private Vector outward() {
        Vector c = new Vector(Plaza.CX + 0.5, 0, Plaza.CZ + 0.5);
        Vector d = from.clone().subtract(c).setY(0);
        if (d.lengthSquared() < 1) d = pos.clone().subtract(c).setY(0);
        if (d.lengthSquared() < 1e-4) d = new Vector(1, 0, 0);
        return c.add(d.normalize().multiply(OUT_R)).setY(pos.getY());
    }

    private void leave() {
        Vector to = outward();
        seek(to, Math.max(speed(), 0.15));
        if (Math.hypot(to.getX() - pos.getX(), to.getZ() - pos.getZ()) < 3) {
            List<Player> ps = new ArrayList<>();
            for (Player p : held) {
                if (p == null || game.players.get(p).life != Life.TRAPPED) continue;
                game.players.holdTrapped(p, true);
                ps.add(p);
            }
            if (!ps.isEmpty()) Back.of(game).save(ps, Math.atan2(pos.getZ() - (Plaza.CZ + 0.5), pos.getX() - (Plaza.CX + 0.5)));
            noReward = true;
            remove();
        }
    }

    /** 돌아온 안개: 20초 동안 가운데 쪽으로, 그 뒤 물러가며 갇힘 끝. */
    private void back() {
        if (inPhase() >= RETURN_T) {
            Vector to = outward();
            seek(to, Math.max(speed(), 0.15));
            if (Math.hypot(to.getX() - pos.getX(), to.getZ() - pos.getZ()) < 3) {
                for (Player p : held) if (p != null) game.players.trapEnd(p);
                noReward = true;
                remove();
            }
            return;
        }
        Vector c = crowd();
        if (c != null) seek(c.setY(pos.getY()), speed());
        else hold();
    }

    private void fogTick() {
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            if (G129Kit.flat(l, pos) > FOG_R || Math.abs(l.getY() - (pos.getY() - HOVER)) > 4) continue;
            game.players.slow(q, FOG_SLOW, 12, "g197fog");
            game.players.veil(q, "g197_fog", 25);
            if ((age + q.getEntityId()) % 20 == 0) hit(q, FOG_DMG, "cause_g197");
        }
    }

    /** 등불잡이: 안개 안에서 사람에게 다가가 낚기, 지팡이. 부품 자세는 2틱마다. */
    private void bearersTick() {
        double fy = game.mobs.flow.floor(pos.getX(), pos.getZ());
        for (int i = 0; i < N; i++) {
            if (!alive[i] || part[i] == null) continue;
            Vector w = pos.clone().add(off[i]);
            Player t = aim[i];
            if (phase == Phase.COME && fighting()) {
                if (t == null || !standing(t) || G129Kit.flat(t.getLocation(), pos) > FOG_R + 1) t = aim[i] = pick(w);
            } else t = aim[i] = null;
            Vector want = t != null ? t.getLocation().toVector().subtract(pos).setY(0) : new Vector(Math.cos(i * 2.1 + age * 0.01) * 3.5, 0,
                Math.sin(i * 2.1 + age * 0.01) * 3.5);
            if (want.length() > REACH) want.multiply(REACH / want.length());
            Vector d = want.subtract(off[i]);
            double dl = d.length();
            if (dl > BEARER_V) d.multiply(BEARER_V / dl);
            off[i].add(d);
            if (age % 2 == 0) {
                float yaw = t != null ? (float) Math.atan2(t.getLocation().getX() - w.getX(), t.getLocation().getZ() - w.getZ()) : 0f;
                part[i].pose(new Matrix4f().translation((float) off[i].getX(), (float) (fy + 1.6 - pos.getY()), (float) off[i].getZ()).rotateY(yaw), 2);
            }
            if (t == null || !fighting()) continue;
            double h = G129Kit.flat(t.getLocation(), w);
            if (held[i] == null && tellAt[i] < 0 && h <= HOOK_R + 1.5 && age >= hookAt[i]) {
                tellAt[i] = age;
                if (part[i].d != null) part[i].d.setBrightness(new Display.Brightness(15, 15));
            }
            if (tellAt[i] >= 0 && age - tellAt[i] >= TELL) {
                tellAt[i] = -1;
                hookAt[i] = age + cd(HOOK_CD);
                if (part[i].d != null) part[i].d.setBrightness(new Display.Brightness(14, 12));
                if (held[i] == null && h <= HOOK_R + 0.5) {
                    hit(t, HOOK_DMG, "cause_g197_hook");
                    if (standing(t) || game.players.get(t).life == Life.DOWNED) {
                        game.players.trap(t, this);
                        held[i] = t;
                        aim[i] = null;
                        filled++;
                        part[i].state("held");
                        G129Kit.soundAt(this, w, "hook", Sound.BLOCK_CHAIN_PLACE, 1f, 24);
                        game.hud.alert("notice_g197_count", Math.max(0, Math.min(5, need - filled)), 80);
                        continue;
                    }
                }
            }
            if (h <= STAFF_R && age >= staffAt[i]) {
                staffAt[i] = age + cd(STAFF_CD);
                hit(t, STAFF_DMG, "cause_g197_staff");
                G129Kit.soundAt(this, w, "strike", Sound.ENTITY_PLAYER_ATTACK_STRONG, 1f, 16);
            }
        }
    }

    private Player pick(Vector w) {
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            if (G129Kit.flat(l, pos) > FOG_R) continue;
            double d = G129Kit.flat(l, w);
            if (d < bd) {
                bd = d;
                best = q;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ 맞음

    private Vector bearerAt(int i) {
        double fy = game.mobs.flow.floor(pos.getX(), pos.getZ());
        return new Vector(pos.getX() + off[i].getX(), fy + 2.2, pos.getZ() + off[i].getZ());
    }

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        for (int i = 0; i < N; i++) {
            if (!alive[i]) continue;
            Vector b = bearerAt(i);
            sph.add(new double[] {b.getX(), b.getY(), b.getZ(), 0.85});
        }
        sphAge = age;
        return sph;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        int best = -1;
        double bd = Double.MAX_VALUE;
        Vector at = d.at() != null ? d.at() : pos;
        for (int i = 0; i < N; i++) {
            if (!alive[i]) continue;
            double dd = bearerAt(i).distance(at);
            if (dd < bd) {
                bd = dd;
                best = i;
            }
        }
        if (best < 0) return false;
        double amt = Math.min(bhp[best], d.amount());
        bhp[best] -= amt;
        hp = 0;
        for (int i = 0; i < N; i++) if (alive[i]) hp += Math.max(0, bhp[i]);
        game.mobs.onHurt(this, d, amt);
        if (bhp[best] <= 0) {
            alive[best] = false;
            sphAge = -1;
            Player p = held[best];
            held[best] = null;
            if (p != null) game.players.free(p);
            if (part[best] != null) part[best].remove();
            part[best] = null;
            G129Kit.soundAt(this, bearerAt(best), "strike", Sound.BLOCK_GLASS_BREAK, 1f, 24);
        }
        if (bearers() == 0) die(d);
        return true;
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " need=" + need + " filled=" + filled + " bearers=" + bearers();
    }

    // ------------------------------------------------------------------ 다음 웨이브에 돌아옴(FIELDTICK, FREE)

    static final class Back implements Mobs.Field {
        final Game game;
        List<Player> ps;
        double angle;

        private Back(Game game) {
            this.game = game;
        }

        static Back of(Game game) {
            Mobs.Field f = game.mobs.field(KEY);
            if (f instanceof Back b) return b;
            Back b = new Back(game);
            game.mobs.field(KEY, b);
            return b;
        }

        void save(List<Player> list, double a) {
            ps = list;
            angle = a;
        }

        @Override
        public void waveStart(int wave) {
            if (ps == null || ps.isEmpty()) return;
            List<Player> list = new ArrayList<>();
            for (Player p : ps) if (p.isOnline() && game.players.get(p).life == Life.TRAPPED) list.add(p);
            ps = null;
            if (list.isEmpty()) return;
            returning = list;
            try {
                double x = Plaza.CX + 0.5 + Math.cos(angle) * BACK_R, z = Plaza.CZ + 0.5 + Math.sin(angle) * BACK_R;
                Gabe g = game.director.spawnLike(Species.G197, new Vector(x, game.mobs.flow.floor(x, z) + HOVER, z));
                if (g == null) for (Player p : list) game.players.trapEnd(p);
            } finally {
                returning = null;
            }
        }

        @Override
        public void reset() {
            ps = null;
        }
    }
}
