package kr.gave.mob.sp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.mob.Species;

/**
 * G18 겹겹 덧댄 게이브(튼튼이). 1.6블록 얼굴에 다른 게이브 얼굴 가죽 5장이 비늘처럼 기워져 있고, 남은 가죽 장수가 곧 체력이다(체력 막대 없음:
 * 체력을 여섯 칸으로 나눠 한 칸이 깎일 때마다 바깥 가죽 한 장이 '쩌억' 벗겨져 펄럭이며 떨어지고 바닥에서 잠깐 꿈틀댄다, 맨 끝은 분홍 살 얼굴).
 *
 * <p>허리 높이로 느리게 밀고 들어오며('쿵, 쿵') 닿은 사람을 비벼 밀어내고, 0.8초에 한 번 몸통 밀치기 2 피해와 강한 밀침(진형을 무너뜨림).
 * 가죽이 두 장 남으면 빨라진다. 폭발은 가죽을 두 장씩 벗긴다. 6블록 안 사람을 찍으면(가장 바깥 가죽의 눈이 뜨임) 그쪽으로 민다.
 * 황야는 나오는 고리가 멀어서(72블록 넘게) 사람이 24블록 밖이면 걸음을 재촉하고(떼와 함께 닿게), 그 안에서부터 느리게 민다.
 *
 * <p>체력이 밖에서 바뀌어도(오래 살면 갈라짐: 엔진이 체력을 반으로, 조각은 그 체력으로 태어남) 매 틱 가죽 장수를 체력에 맞춘다.
 *
 * <p>팩 계약(tools/gabe/sp/g18.py): 모델 gave:gabe/g18 상태 "s5" .. "s0"(남은 가죽 장수), 모델 gave:gabe/g18_skin 상태 "a" .. "e"(떨어진 가죽),
 * 소리 stomp, peel, shove, 원인 글귀 cause_g18.
 */
public final class G18 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final int SKINS = 5;
    /** 빠르기(블록/틱): 느리게, 가죽 두 장 이하면 배율. 사람이 FAR 블록 밖이면 FAR_V 로 재촉. */
    static final double SPEED = 0.07, FAST_MUL = 1.7, FAR = 24, FAR_V = 0.15;
    /** 찍는 거리(이 안이면 그 사람 쪽으로 곧장 밂). */
    static final double MARK_RANGE = 6;
    /** 밀치기 피해, 세기, 위, 사람마다 쉬는 틱, 비비기(피해 없는 밀어냄) 세기. */
    static final double SHOVE_DMG = 4, SHOVE = 0.95, SHOVE_UP = 0.32, RUB = 0.14;
    static final int SHOVE_EVERY = 16;
    /** 쿵 걸음 간격(틱). */
    static final int STOMP_EVERY = 20;
    /** 몸 반지름(얼굴 2.6 블록, 2026-11-12 거대화). */
    static final double R = 1.4;
    /** 떨어진 가죽 수명(틱). */
    static final int SKIN_LIFE = 50;

    private int skins = SKINS;
    private double layer;
    private boolean init;
    private int nextStomp, unsquashAt = -1;
    private final Map<UUID, Long> shovedAt = new HashMap<>();

    public G18(Game game, Species sp) {
        super(game, sp);
        radius = R;
        hover = 2.6; // 얼굴 아래가 바닥에서 1.3, 굵은 살기둥 다리 넷이 바닥까지 버팀(2026-11-12 거대화)
        baseSpeed = SPEED;
        state("s5");
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/" + sp.key(), 3.2f, 5.4f);
    }

    @Override
    protected void spawned() {
        nextStomp = age + game.random.nextInt(STOMP_EVERY);
    }

    /** 첫 틱(감독이 체력 배율을 곱한 뒤): 한 장의 체력을 정하고, 깎인 채 태어난 조각은 소리 없이 그 장수로. */
    private void init() {
        init = true;
        layer = Math.max(1, maxHp) / (SKINS + 1);
        int left = skinsFor(hp);
        if (left < skins) {
            skins = left;
            state("s" + skins);
        }
    }

    /** 이 체력에 남아 있을 가죽 장수(0..5). */
    private int skinsFor(double h) {
        return h <= 0 ? 0 : Math.max(0, Math.min(SKINS, (int) Math.ceil(h / layer) - 1));
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!init) init();
        // 체력이 밖에서 깎였으면(갈라짐) 그만큼 벗겨짐
        int left = skinsFor(hp);
        while (skins > left) peel();
        Player p = nearest(48);
        double dp = p == null ? Double.MAX_VALUE : dist(p);
        boolean far = dp > FAR;
        double spd = speed() * Math.max(skins <= 2 ? FAST_MUL : 1.0, far ? FAR_V / SPEED : 1.0);
        if (dp <= MARK_RANGE && sees(p)) {
            if (target != p) mark(p);
            vel.copy(toward(p).multiply(spd));
            face(chest(p));
        } else {
            if (target != null) mark(null);
            follow(spd);
            if (p != null) face(chest(p));
        }
        pitch = 0;
        if (age >= nextStomp && vel.lengthSquared() > 1e-5) {
            sound("stomp", Sound.ENTITY_RAVAGER_STEP, 0.6f, 24);
            squash(1.04f, 0.94f, 1f, 3);
            nextStomp = age + (skins <= 2 || far ? STOMP_EVERY * 2 / 3 : STOMP_EVERY);
        }
        if (age == unsquashAt) body.squash(1f, 1f, 1f, 4);
        // 가장 가까운 사람도 닿을 거리 밖이면 밀치기 검사를 건너뜀(사람 30명을 매 틱 훑지 않게)
        if (dp <= R + 2.5) push();
    }

    private void squash(float x, float y, float z, int t) {
        body.squash(x, y, z, t);
        unsquashAt = age + t + 2;
    }

    /** 닿은 사람을 비벼 밀어내고, 사람마다 0.8초에 한 번 밀치기. */
    private void push() {
        long now = game.now();
        for (Player q : game.mobs.standing()) {
            if (!touches(q, 0.15)) continue;
            Vector away = toward(q);
            if (away.lengthSquared() < 1e-6) away = new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
            Long last = shovedAt.get(q.getUniqueId());
            if (last == null || now - last >= cd(SHOVE_EVERY)) {
                shovedAt.put(q.getUniqueId(), now);
                hit(q, SHOVE_DMG, "cause_g18");
                knock(q, away, SHOVE, SHOVE_UP);
                sound("shove", Sound.ENTITY_SLIME_ATTACK, 0.6f, 16);
                squash(0.92f, 1.06f, 0.85f, 2);
            } else knock(q, away, RUB, 0);
        }
    }

    // ------------------------------------------------------------------ 맞음: 가죽 장수가 체력

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (!init) init();
        double before = hp;
        hp -= d.amount();
        // 폭발은 두 장씩: 맞은 뒤 남을 가죽을 지금보다 둘 적게
        if (d.type() == DamageType.EXPLOSION && skins > 0) {
            int want = Math.max(0, skins - 2);
            hp = Math.min(hp, (want + 1) * layer - 0.01);
        }
        game.mobs.onHurt(this, d, before - Math.max(0, hp));
        int left = skinsFor(hp);
        while (skins > left) peel();
        if (hp <= 0) die(d);
        return true;
    }

    /** 바깥 가죽 한 장이 벗겨져 펄럭이며 떨어짐. */
    private void peel() {
        int idx = skins - 1;
        skins--;
        state("s" + skins);
        sound("peel", Sound.BLOCK_HONEY_BLOCK_BREAK, 0.6f, 24);
        squash(1.08f, 0.94f, 0.9f, 2);
        dropSkin(idx);
    }

    /** 떨어진 가죽(연출 디스플레이 하나): 얼굴 앞에서 바닥으로 뒤집히며 떨어져 잠깐 꿈틀대다 사라짐. */
    private void dropSkin(int idx) {
        double r = Math.toRadians(yaw);
        Vector fwd = new Vector(-Math.sin(r), 0, Math.cos(r));
        Vector left = new Vector(fwd.getZ(), 0, -fwd.getX());
        double[][] at = {{-0.65, 0.65}, {0.65, 0.65}, {-0.65, -0.62}, {0.65, -0.62}, {0, 0}};
        double[] a = at[Math.max(0, Math.min(4, idx))];
        Vector from = pos.clone().add(fwd.clone().multiply(0.85)).add(left.clone().multiply(a[0])).add(new Vector(0, a[1], 0));
        Location l = from.toLocation(game.world);
        Matrix4f start = new Matrix4f().rotateY((float) -r).rotateX((float) (Math.PI / 2));
        ItemDisplay d = game.shotFx.spawn(l, "fx_quad", 0, 0, start, Display.Billboard.FIXED, false, SKIN_LIFE, ShotFx.NORMAL);
        if (d == null) return;
        LookState ls = new LookState();
        ls.phase = game.random.nextInt(12);
        d.setItemStack(Body.item("gabe/g18_skin", String.valueOf("abcde".charAt(Math.max(0, Math.min(4, idx)))), ls, game.worldTime()));
        d.setBrightness(Body.LIGHT);
        double fl = game.mobs.flow.floor(from.getX(), from.getZ());
        Vector land = from.clone().add(fwd.clone().multiply(0.7 + game.random.nextDouble() * 0.4)).setY(fl + 0.03);
        float spin = (float) (game.random.nextGaussian() * 0.6);
        game.shotFx.grow(d, new Matrix4f().rotateY((float) -r + spin), 8);
        game.shotFx.drift(d, land.toLocation(game.world), 8);
    }

    @Override
    public List<double[]> spheres() {
        return List.of(new double[] {pos.getX(), pos.getY(), pos.getZ(), R});
    }

    /** 시험용: 남은 가죽 장수. */
    public int skins() {
        return skins;
    }

    @Override
    public String toString() {
        return super.toString() + " skins=" + skins;
    }
}
