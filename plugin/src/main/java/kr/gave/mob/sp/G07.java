package kr.gave.mob.sp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.PState;

/**
 * G07 재화 흉내 게이브(함정, 미리 깔림). 바닥 금화인 척 빙글 돌며 둥실거린다.
 *
 * <p>도감: 누가 쳐다보면(조준선 쪽 좁은 원뿔) 회전을 멈추고 그 사람에게 앞면을 돌린다. 아무도 안 보면 0.5블록씩 사람 쪽으로 몰래 굴러온다.
 * 2블록 안에 들면 얼굴이 부채처럼 펴지며(좌르륵) 발목에 들러붙어 2초 묶고 초당 2 피해, 가진 재화 일부를 먹는다(잡히면 돌려줌).
 * 듣기(웅크리기)면 쌕쌕 밑 웃음. 바닥 그림자가 진짜보다 크다(디스플레이 그림자 반지름).
 *
 * <p>흉내 규칙(도감 구현 줄 '가짜는 판정 없이 서버 좌표만, 펴질 때 판정 생성'): 접힌 동안은 맞는 구가 없다. 흘린 탄은 그냥 지나가고
 * (피가 튀지 않아 공짜로 가려낼 수 없음), 조준선을 0.5초 댄 채 쏜 사격만 {@link G07Aim} 이 서버 좌표로 재서 펴지며 한 발에 죽는다.
 * 폭발, 불, 조명탄에는 펴진다. 딸랑이에는 끌리지 않는다(재화가 굴러가면 들킴).
 *
 * <p>진짜 재화: 공용 코드에 바닥 재화(팀 공용 웃돈)가 아직 없어, 이 종이 깔릴 때 진짜 금화({@link G07Coin}, 보상 없는 개체)를 {@link #DECOYS}개
 * 같이 깐다. 같은 모델, 같은 회전과 둥실거림이고 그림자만 작다. 진짜는 걸어가 닿으면 팀 모두가 재화를 받고, 겨눠 쏘면 반으로 깨진다.
 *
 * <p>팩 계약(tools/gabe/sp/g07.py): 모델 gave:gabe/g07, 상태 "" 동전(접힘, 막대기 움찔), "open" 펴짐, "real" 진짜 금화, "half" 깨진 금화.
 * 소리 breath, laugh(듣기), unfold, chew, pickup, crack. 원인 글귀 cause_g07.
 */
public final class G07 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    /** 동전 중심 높이(바닥에서), 둥실거림 폭. */
    static final double HOVER = 0.42, BOB = 0.05;
    /** 회전(도/틱, 진짜 재화와 같은 값). */
    static final float SPIN = 10f;
    /** 몰래 굴러오기: 사람이 이 안에 있을 때, 한 번에 0.5블록, 간격, 굴러가는 틱. */
    static final double CREEP_R = 20, CREEP_STEP = 0.5;
    static final int CREEP_EVERY = 40, CREEP_TICKS = 8, UNWATCHED = 30;
    /** 펴지는 거리(수평), 펴진 뒤 달려드는 빠르기, 묶는 틱, 피해 간격, 피해. */
    static final double TRIGGER = 2.0, LUNGE = 0.32;
    static final int LATCH = 40, BITE_EVERY = 10, UNFOLD = 6, RELATCH = 60;
    static final double BITE = 1.0;
    /** 먹는 재화: 가진 것의 비율, 최소, 최대. */
    static final double EAT_FRAC = 0.15;
    static final int EAT_MIN = 5, EAT_MAX = 40;
    /** 펴진 뒤 기어 오는 빠르기. */
    static final double CRAWL = 0.07;
    /** 그림자 반지름(가짜가 더 큼: 속에 접힌 얼굴 크기만큼). */
    static final float SHADOW = 0.85f, SHADOW_REAL = 0.45f;
    /** 가짜 하나가 깔릴 때 같이 까는 진짜 금화 수. */
    static final int DECOYS = 1;

    enum Phase { COIN, CREEP, UNFOLD, LATCH, CRAWL }

    private Phase phase = Phase.COIN;
    private int phaseAt, unwatched, nextCreep, nextBreath, nextBite, readyAt;
    private boolean unsquash;
    private Player prey;
    private final Vector creepTo = new Vector();
    private float creepYaw;
    private final Map<UUID, Integer> eaten = new HashMap<>();
    private final G07Aim aim = new G07Aim();

    public G07(Game game, Species sp) {
        super(game, sp);
        radius = 0.3;
        hover = HOVER;
        baseSpeed = CRAWL;
        disguised = true;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g07", 0.8f, 0.8f);
        body.turnTicks = 2;
        body.anchor.setShadowRadius(SHADOW);
        body.anchor.setShadowStrength(0.9f);
    }

    @Override
    protected void spawned() {
        yaw = game.random.nextFloat() * 360f;
        nextCreep = age + CREEP_EVERY + game.random.nextInt(CREEP_EVERY);
        nextBreath = age + game.random.nextInt(40);
        // 진짜 금화를 같이 깜(감독이 놓았거나 소환한 것만: 분열, 우리 개체는 noReward 라 빠짐)
        if (!noReward && !game.director.cageSpawning()) G07Coin.scatter(this, DECOYS);
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
        if (disguised) {
            Player shooter = aim.tick(this);
            if (shooter != null) {
                shot(shooter);
                return;
            }
        }
        switch (phase) {
            case COIN -> coin();
            case CREEP -> creep();
            case UNFOLD -> unfold();
            case LATCH -> latch();
            case CRAWL -> crawl();
        }
    }

    /** 동전: 빙글 돌며 둥실, 쳐다보는 사람이 있으면 멈추고 앞면을 돌림, 가까우면 펴짐. */
    private void coin() {
        hold();
        hover = HOVER + Math.sin((age + id * 7) * 0.12) * BOB;
        Player watcher = aim.watcher;
        if (watcher != null) {
            unwatched = 0;
            turnTo(watcher.getEyeLocation().toVector(), 18f);
        } else {
            unwatched++;
            yaw += SPIN;
        }
        pitch = 0;
        Player near = nearest(TRIGGER + 1.5);
        if (near != null && flat(near) <= TRIGGER) {
            open(near);
            return;
        }
        breath();
        if (watcher == null && unwatched >= UNWATCHED && age >= nextCreep) {
            Player p = nearest(CREEP_R);
            nextCreep = age + CREEP_EVERY + game.random.nextInt(20);
            if (p != null && flat(p) > TRIGGER + 0.3) {
                Vector d = toward(p);
                creepTo.copy(pos.clone().add(d.multiply(CREEP_STEP)));
                creepYaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
                phase(Phase.CREEP);
            }
        }
    }

    /** 0.5블록 몰래 굴러옴(동전이 옆으로 서서 굴러감). 누가 쳐다보면 바로 멈춤. */
    private void creep() {
        hover = HOVER;
        if (aim.watcher != null || inPhase() >= CREEP_TICKS) {
            hold();
            body.roll = 0;
            phase(Phase.COIN);
            return;
        }
        Vector d = creepTo.clone().subtract(pos).setY(0);
        double l = d.length();
        double v = CREEP_STEP / CREEP_TICKS;
        if (l < 1e-4) vel.zero();
        else vel.copy(d.multiply(Math.min(v, l) / l));
        yaw = creepYaw + 90f;
        pitch = 0;
        // 굴러감: 바퀴 축(앞뒤 축) 둘레로 거리 / 반지름 만큼
        body.roll = (float) ((body.roll + Math.toDegrees(v / 0.27)) % 360f);
    }

    /** 정체가 드러남: 얼굴이 좌르륵 펴짐(이때부터 맞는 구가 생긴다). */
    private void unveil(int ticks) {
        disguised = false;
        state("open");
        body.roll = 0;
        body.squash(1f, 1f, 1f, ticks);
        hover = 0.45;
        sound("unfold", Sound.BLOCK_CHAIN_STEP, 1.6f, 16);
    }

    private void open(Player p) {
        prey = p;
        unveil(0);
        mark(p);
        // 접힌 얇은 판에서 부채처럼 펴짐: 다음 틱에 풀어 줌
        body.squash(0.06f, 1f, 1f, 0);
        unsquash = true;
        phase(Phase.UNFOLD);
    }

    /** 조준선을 0.5초 댄 사격: 펴지며 한 발에 죽는다(보상은 쏜 사람에게). */
    private void shot(Player by) {
        unveil(2);
        Vector dir = pos.clone().subtract(by.getEyeLocation().toVector());
        if (dir.lengthSquared() > 1e-6) dir.normalize();
        super.damage(new Damage(hp + 1, DamageType.BULLET, by, pos.clone(), dir, "aimed", G07Aim.AIM));
    }

    /** 좌르륵 펴지며 발목으로 달려듦. */
    private void unfold() {
        if (unsquash) {
            unsquash = false;
            body.squash(1f, 1f, 1f, 5);
        }
        if (!standing(prey)) {
            toCrawl();
            return;
        }
        face(ankle(prey));
        Vector d = ankle(prey).subtract(pos).setY(0);
        double l = d.length();
        if (l < 1e-4) vel.zero();
        else vel.copy(d.multiply(Math.min(LUNGE, l) / l));
        if (inPhase() >= 2 && touches(prey, 0.15)) {
            bite(prey);
            return;
        }
        if (inPhase() >= UNFOLD + 14) toCrawl();
    }

    private void bite(Player p) {
        prey = p;
        mark(p);
        noPush = true;
        // 가진 재화 일부를 먹음(잡히면 돌려줌)
        PState s = game.players.get(p);
        int take = (int) Math.min(Math.min(s.money, EAT_MAX), Math.max(EAT_MIN, Math.round(s.money * EAT_FRAC)));
        if (take > 0 && game.players.spend(p, take)) eaten.merge(p.getUniqueId(), take, Integer::sum);
        nextBite = age;
        hover = 0.3;
        body.squash(1.15f, 0.8f, 1.1f, 2);
        phase(Phase.LATCH);
    }

    /** 발목에 들러붙음: 2초 묶고 0.5초마다 1 피해, 오물오물. 묶임은 짧게 이어 걸어서 이 개체가 죽으면 바로 풀린다. */
    private void latch() {
        if (unsquash) {
            unsquash = false;
            body.squash(1f, 1f, 1f, 3);
        }
        if (!standing(prey) || inPhase() >= LATCH) {
            toCrawl();
            return;
        }
        game.players.root(prey, 4, "g07");
        Vector want = ankle(prey);
        vel.copy(want.subtract(pos).setY(0));
        face(chest(prey));
        if (age >= nextBite) {
            hit(prey, BITE, "cause_g07");
            sound("chew", Sound.ENTITY_GENERIC_EAT, 0.7f, 12);
            nextBite = age + BITE_EVERY;
        }
    }

    private void toCrawl() {
        noPush = false;
        unsquash = false;
        body.squash(1f, 1f, 1f, 4);
        hover = 0.45;
        readyAt = age + RELATCH;
        mark(null);
        prey = null;
        phase(Phase.CRAWL);
    }

    /** 펴진 채 기어 옴(정체가 드러남). 다시 닿으면 또 문다. 쫓는 동안은 표적을 세워 웨이브가 끝나지 않게 한다. */
    private void crawl() {
        follow(speed());
        Player p = nearest(96);
        if (p != null) {
            face(chest(p));
            if (target != p) mark(p);
            if (age >= readyAt && touches(p, 0.1)) bite(p);
        } else {
            if (target != null) mark(null);
            if (vel.lengthSquared() > 1e-8) face(pos.clone().add(vel));
        }
    }

    // ------------------------------------------------------------------ 도우미

    /** 사람 발목 자리(발에서 몸 쪽으로 0.35, 위 0.25). */
    private Vector ankle(Player p) {
        Location l = p.getLocation();
        Vector d = pos.clone().subtract(l.toVector()).setY(0);
        double len = d.length();
        if (len > 1e-4) d.multiply(0.35 / len);
        return new Vector(l.getX() + d.getX(), l.getY() + 0.25, l.getZ() + d.getZ());
    }

    private double flat(Player p) {
        Location l = p.getLocation();
        return Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
    }

    /** 가까우면(4블록) 쌕쌕. */
    private void breath() {
        if (age < nextBreath) return;
        nextBreath = age + 45 + game.random.nextInt(30);
        if (nearest(4) != null) sound("breath", Sound.ENTITY_PLAYER_BREATH, 1.4f, 6);
    }

    // ------------------------------------------------------------------ 맞음, 드러남, 듣기

    /** 접힌 동안은 판정이 없다(겨눈 사격은 tick 의 {@link G07Aim} 이 잼). */
    @Override
    public List<double[]> spheres() {
        return disguised ? List.of() : super.spheres();
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (disguised) {
            DamageType t = d.type();
            if (t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER) return false;
            // 폭발, 불: 펴지며 맞음
            reveal();
        }
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        // 잡히면 먹은 재화를 돌려줌(웨이브 끝 정리처럼 보상 없는 죽음은 돌려주지 않음)
        if (!noReward) {
            for (Map.Entry<UUID, Integer> e : eaten.entrySet()) {
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null && p.isOnline() && e.getValue() > 0) game.players.addMoneyRaw(p, e.getValue()); // 돌려받기: 돈 배율 없음
            }
        }
        eaten.clear();
        // 접힌 채 정리되면 시체 없이 사라짐(진짜 재화와 구별되지 않게)
        if (disguised) leaveCorpse = false;
        super.die(d);
    }

    /** 조명탄, 정화: 펴져서 기어 온다. */
    @Override
    public void reveal() {
        if (!disguised) return;
        unveil(3);
        if (phase == Phase.COIN || phase == Phase.CREEP) toCrawl();
    }

    /** 딸랑이: 접힌 동안은 끌리지 않는다(재화가 굴러가면 들킴). */
    @Override
    public void lure(Vector at, int ticks) {
        if (!disguised) super.lure(at, ticks);
    }

    @Override
    public String listenSound() {
        return disguised ? "gave:gabe.g07.laugh" : null;
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + (disguised ? " disg" : "") + (aim.watcher != null ? " watched=" + aim.dwell(aim.watcher) : "") + " ate="
                + eaten.values();
    }
}
