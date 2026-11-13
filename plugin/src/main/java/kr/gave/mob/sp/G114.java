package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.world.Plaza;

/**
 * G114 개미 무덤 게이브(지원, 떼, 목표 압박, 깔림). 웨이브 시작에 중앙에서 R 22~34 땅에서 3초에 걸쳐 솟는 얼굴 무덤.
 *
 * <p>도감: 2초마다 개미 줄({@link G114Ant}, 얼굴 개미 넷이 한 줄인 개체 하나, 체력 8, 보상 0)을 쏟는다(한 무덤에 동시에 여섯 줄, 모두 열다섯 줄).
 * 줄은 가장 가까운 사람에게 곧장 가고 셋에 하나는 트럭으로 가서 문다. 무덤 6블록 안에 온 사람에게는 얼굴 구멍들이 한꺼번에 개미 줄기를 뿜어 12(2초마다).
 * 수류탄이나 화염병이 무덤 위에서 터지면(던진 사람이 무덤 곁 9블록 안) 꼭대기 구멍이 막혀 더 안 나오고 무덤이 주저앉는다(보상 1.5배).
 * 멀리서 부숴도 된다(체력 500).
 *
 * <p>바꾼 것: 수류탄이 꼭대기 구멍에 '들어가는' 판정은 폭발, 불이 무덤 밑둥 반경(수평 2.2) 안에서 일어났는가로 잰다(탄은 게이브 몸에 안 막혀
 * 무덤을 지나 그 밑에 떨어지므로). 줄기는 셰이더 먼지 덩이가 무덤에서 사람 쪽으로 날아가는 것으로 그린다.
 *
 * <p>팩 계약(tools/gabe/sp/g114.py): 모델 gave:gabe/g114 상태 "", "plug", gave:gabe/g114_ant 상태 "", 소리 scratch(24), spew(20), bite(12),
 * 글귀 cause_g114.
 */
public final class G114 extends Gabe {

    static final double HOVER = 1.8, RISE_DEPTH = 3.9;
    static final int RISE = 60;
    /** 놓이는 고리(중앙에서), 줄 쏟기 간격, 동시 줄, 모든 줄. */
    static final double R_MIN = 22, R_MAX = 34;
    static final int SPEW_CD = 40, MAX_LIVE = 6, MAX_TOTAL = 15;
    /** 줄기: 반경(수평), 피해, 간격. */
    static final double JET_R = 6, JET_DMG = 12;
    static final int JET_CD = 40;
    /** 구멍 막기: 폭발, 불이 이 수평 반경 안, 던진 사람이 이 거리 안. */
    static final double PLUG_R = 2.2, PLUG_THROWER = 9;

    final List<G114Ant> lines = new ArrayList<>();
    int spewed;
    private boolean init, plugged;
    private int nextSpew = 30, nextJet, nextScratch, nextMark;

    public G114(Game game, Species sp) {
        super(game, sp);
        radius = 1.5;
        hover = HOVER;
        baseSpeed = 0;
        noPush = true;
        noMutate = true;
        noBite = true;
        ragKick = 0;
        ragRods = 0;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g114", 4f, 4f);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        hover = HOVER - RISE_DEPTH;
        yaw = game.random.nextFloat() * 360f;
    }

    private void init() {
        init = true;
        // 감독이 낸 무덤은 중앙에서 R 22~34 로(각도는 그대로). 시험 소환은 놓인 자리 그대로
        if (game.director.fromDirector(this)) {
            double cx = Plaza.CX + 0.5, cz = Plaza.CZ + 0.5;
            double dx = pos.getX() - cx, dz = pos.getZ() - cz, r = Math.hypot(dx, dz);
            double want = Math.max(R_MIN, Math.min(R_MAX, r));
            if (r < 1e-3) {
                double a = game.random.nextDouble() * Math.PI * 2;
                dx = Math.cos(a);
                dz = Math.sin(a);
                r = 1;
            }
            if (Math.abs(want - r) > 0.5) {
                pos.setX(cx + dx / r * want);
                pos.setZ(cz + dz / r * want);
            }
        }
        sound("scratch", Sound.BLOCK_GRAVEL_BREAK, 0.6f, 24);
    }

    @Override
    public void tick() {
        if (!init) init();
        hold();
        if (age % 10 == 0) lines.removeIf(a -> a.dead || a.removed);
        if (age < RISE) {
            hover = HOVER - RISE_DEPTH * (1.0 - (double) (age + 1) / RISE);
            if (age % 6 == 0) dust(1.9, 0.2, 2.4f);
            return;
        }
        hover = HOVER;
        if (age >= nextMark) {
            nextMark = age + 20;
            Player p = nearest(400);
            if (p != target) mark(p);
        }
        if (age >= nextScratch && game.mobs.nearestPlayer(pos, 36) != null) {
            nextScratch = age + 70 + game.random.nextInt(40);
            sound("scratch", Sound.BLOCK_GRAVEL_BREAK, 0.6f, 24);
        }
        if (age >= nextSpew && !plugged) spew();
        if (age >= nextJet) jet();
    }

    /** 구멍 하나에서 개미 줄 하나. */
    private void spew() {
        if (spewed >= MAX_TOTAL || lines.size() >= MAX_LIVE) {
            nextSpew = age + 20;
            return;
        }
        if (!game.mobs.budgetFor(1)) {
            nextSpew = age + 20;
            return;
        }
        nextSpew = age + cd(SPEW_CD);
        double a = game.random.nextDouble() * Math.PI * 2;
        Vector at = new Vector(pos.getX() + Math.cos(a) * 2.1, 0, pos.getZ() + Math.sin(a) * 2.1);
        at.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + G114Ant.HOVER);
        boolean truck = spewed % 3 == 2;
        G114Ant ant = game.mobs.place(new G114Ant(game, sp, this, truck), at);
        if (ant == null) return;
        spewed++;
        lines.add(ant);
        ant.yaw = (float) Math.toDegrees(Math.atan2(-Math.cos(a), Math.sin(a)));
        if (spewed % 2 == 1) sound("spew", Sound.ENTITY_SILVERFISH_AMBIENT, 0.8f, 20);
    }

    /** 6블록 안 사람 모두에게 얼굴 구멍들이 개미 줄기를 뿜음. */
    private void jet() {
        boolean any = false;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            if (Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ()) > JET_R + 1.5) continue;
            any = true;
            hit(p, JET_DMG, "cause_g114");
            Vector from = new Vector(pos.getX(), pos.getY() - 0.4, pos.getZ());
            Vector to = chest(p);
            Vector dir = to.clone().subtract(from);
            double len = dir.length();
            if (len > 1e-3) from.add(dir.clone().multiply(Math.min(1.8, len * 0.5) / len));
            for (int i = 0; i < 2; i++) {
                ItemDisplay d = game.shotFx.spawn(from.toLocation(game.world), "fx_quad", ShotFx.DUST, 1, ShotFx.scale(0.9f), Display.Billboard.CENTER,
                    false, 16, i == 0 ? ShotFx.NORMAL : ShotFx.EXTRA);
                game.shotFx.grow(d, ShotFx.scale(1.8f), 8);
                game.shotFx.drift(d, to.toLocation(game.world).add(game.random.nextGaussian() * 0.3, -0.3 + i * 0.4, game.random.nextGaussian() * 0.3), 6);
            }
        }
        if (any) {
            sound("spew", Sound.ENTITY_SILVERFISH_AMBIENT, 0.7f, 20);
            body.squash(1.06f, 0.94f, 1.06f, 2);
            nextJet = age + cd(JET_CD);
        } else nextJet = age + 5;
    }

    private void dust(double r, double up, float size) {
        double a = game.random.nextDouble() * Math.PI * 2;
        Location at = new Location(game.world, pos.getX() + Math.cos(a) * r, floor() + up, pos.getZ() + Math.sin(a) * r);
        ItemDisplay d = game.shotFx.spawn(at, "fx_quad", ShotFx.DUST, 1, ShotFx.scale(size), Display.Billboard.CENTER, false, 16, ShotFx.EXTRA);
        game.shotFx.grow(d, ShotFx.scale(size * 1.8f), 14);
        game.shotFx.drift(d, at.clone().add(0, 1.6, 0), 14);
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(3);
        out.add(new double[] {pos.getX(), pos.getY() - 0.9, pos.getZ(), 1.75});
        out.add(new double[] {pos.getX(), pos.getY() + 0.3, pos.getZ(), 1.15});
        out.add(new double[] {pos.getX(), pos.getY() + 1.25, pos.getZ(), 0.7});
        return out;
    }

    @Override
    public Vector head() {
        return new Vector(pos.getX(), pos.getY() + 1.4, pos.getZ());
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (!plugged && age >= RISE && (d.type() == DamageType.EXPLOSION || d.type() == DamageType.FIRE) && intoHole(d)) {
            plugged = true;
            state("plug");
            reward = Math.max(1, (int) Math.round(reward * 1.5));
            sound("scratch", Sound.BLOCK_GRAVEL_BREAK, 0.5f, 24);
            dust(1.2, 1.5, 3f);
            dust(1.6, 0.6, 3f);
            game.mobs.onHurt(this, d, hp);
            hp = 0;
            die(d);
            return true;
        }
        return super.damage(d);
    }

    /** 폭발, 불이 무덤 위(밑둥 반경 안)에서 일어났고 던진 사람이 무덤 곁인가. */
    private boolean intoHole(Damage d) {
        Vector at = game.mobs.blastAt();
        if (at == null) at = d.at();
        if (at == null) return false;
        if (Math.hypot(at.getX() - pos.getX(), at.getZ() - pos.getZ()) > PLUG_R) return false;
        if (at.getY() > pos.getY() + HOVER + 2) return false;
        Player by = d.by();
        if (by == null) return false;
        Location l = by.getLocation();
        return Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ()) <= PLUG_THROWER;
    }

    @Override
    public String toString() {
        return super.toString() + " lines=" + lines.size() + " spewed=" + spewed + (plugged ? " plugged" : "") + (age < RISE ? " rising" : "");
    }
}
