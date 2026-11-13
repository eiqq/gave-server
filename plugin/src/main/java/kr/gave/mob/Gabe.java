package kr.gave.mob;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;

/**
 * 가짜 몹 하나. 바탕 엔티티가 없고 위치, 속도, 체력은 서버 값이며 화면은 {@link Body} 가 그린다.
 *
 * <p>종 클래스(kr.gave.mob.sp.Gxx)는 이 클래스를 상속하고 {@link #tick()} 에서 행동을 짠다. 엔진({@link Mobs})은 매 틱
 * tick() 을 부른 뒤 속도를 적분하고(바닥, 벽, 밀어내기), 판 규칙(오래 살면 변함, 물어뜯기, 돌아보기)을 걸고, 몸을 옮긴다.
 *
 * <p>계약: 종 클래스는 필드를 바꾸고 도우미를 부를 뿐 엔티티를 직접 만들지 않는다(부품 디스플레이는 {@link Body} 로).
 * 좌표는 세계 좌표(블록), 속도는 블록/틱.
 *
 * <p>적분 약속: 땅 몹(flying false)은 vel 의 x, z 만 쓰고 높이는 엔진이 바닥 + {@link #hover} 로 부드럽게 맞춘다(뛰어오르려면 hover 를 바꾼다).
 * 막힌 칸으로는 축마다 미끄러지고 턱은 {@link Flow#STEP} 까지 오른다(ghost 는 벽 무시). 하늘 몹(flying)은 vel 을 그대로 더하고 바닥 0.3 위로만 막는다.
 *
 * <p>엔진이 tick() 을 부르지 않는 때: 몹정지 시험, 물어뜯기 20틱({@link #biting()}), 끌림({@link #lured()}, 엔진이 그쪽으로 seek).
 */
public abstract class Gabe {

    public final Game game;
    public final Species sp;
    /** 판 안에서 겹치지 않는 번호. */
    public final int id;

    /** 몸 중심(맞는 구의 중심). */
    public final Vector pos = new Vector();
    /** 이번 틱에 바랄 속도(블록/틱). tick() 이 정하고 엔진이 적분한다. */
    public final Vector vel = new Vector();
    /** 몸이 보는 방향(도, 마크 약속: 0 = +Z, 90 = -X, pitch + 는 아래). 엔진이 몸에 싣는다. */
    public float yaw, pitch;

    public double hp, maxHp;
    /** 산 틱(웨이브 안에서 오래 살면 변함 규칙이 읽음). */
    public int age;
    public boolean dead, removed;
    /** 찍은 사람(실눈이 뜨이는 대상). 없으면 null. */
    public Player target;
    /** 소환, 분열, 조각으로 생긴 개체는 true(재화 0). */
    public boolean noReward;
    /** 보상 원(기본 sp.reward, 떼는 무리 합계를 마지막 한 마리가 들고 있게 바꿈). */
    public int reward;

    /** 맞는 구 반지름(블록). 탄 판정에 0.25 를 더 얹는다. */
    public double radius = 0.45;
    /** 바닥에서 떠 있는 높이(몸 중심 기준). */
    public double hover;
    /** 기본 빠르기(블록/틱). 감독 배율, 박수 같은 지원이 곱해진 값은 {@link #speed()}. */
    public double baseSpeed = 0.18;
    /** 이번 틱에만 걸리는 빠르기 배율(지원 종이 매 틱 다시 걸어야 유지). */
    public double speedBuff = 1.0;
    /** 날아다니는 종(벽, 바닥 무시)이면 true. */
    public boolean flying;
    /** 벽에 막히지 않는 종(땅밑, 웅덩이 같은 종)이면 true. */
    public boolean ghost;
    /** 다른 몹을 밀어내지 않고 겹쳐도 되는 종(떼 승객, 웅덩이). 사람 몸에서도 밀려나지 않는다. */
    public boolean noPush;
    /** 흉내 중이면 true(겨눈 사격에만 정체를 드러냄, 판 규칙 돌아보기와 물어뜯기에서 빠짐). */
    public boolean disguised;
    /** 지금 맞지 않는 상태(땅속, 반투명 등). 탄이 지나간다. */
    public boolean untargetable;

    /** 판 규칙 '오래 살면 변함'에서 뺌(기본: 떼 역할, 미리 깔리는 종). 종 생성자에서 바꿔도 된다. */
    public boolean noMutate;
    /** 판 규칙 모두(변함, 물어뜯기, 돌아보기)에서 뺌: 대기실 우리, 연출용 개체. */
    public boolean noRules;
    /** 물어뜯기에서만 뺌. */
    public boolean noBite;
    /** 죽으면 사체를 남김(래그돌, 예산이 없으면 납작해진 몸). false 면 몸을 바로 지움(터지는 종 등). */
    public boolean leaveCorpse = true;
    /** 사체가 래그돌(맞은 쪽으로 튕겨 구르고 막대기가 늘어짐). false 면 예전처럼 제자리에서 납작해짐. leaveCorpse 가 false 면 어느 쪽도 없음. */
    public boolean ragdoll = true;
    /** 래그돌 충격 배율(무거운 종은 0.5 처럼 작게, 0 이면 어떤 무기에도 제자리에서 쓰러짐). */
    public double ragKick = 1.0;
    /** 래그돌에서 흐물거리는 막대기 수 상한(-1 = 모델이 가진 만큼, config ragdoll.rods 까지. 0 = 막대기가 몸에 굳은 채 통째로 구름). */
    public int ragRods = -1;
    /**
     * 굳은 사체를 남기는 틱(-1 = 설정 ragdoll.settleSeconds). 0 보다 크면 그만큼 남고 굳은 사체 예산(ragdoll.settled)에 밀려 먼저 꺼지지 않는다.
     * 보스는 400~600(쉬는 시간 내내 보이게, docs/모듈/보스.md 11.7). 2026-11-12 boss_infra.
     */
    public int ragHold = -1;
    /**
     * 거대한 몸(보스, 2026-11-12 boss_infra). 켜면 폭발, 불 반경({@link Mobs#inRadius})은 몸 중심이 아니라 {@link #spheres()} 의 구마다 '구 표면이
     * 반경 안'(폭발점에서 구 중심까지 거리 - 구 반지름 &lt;= 반경)으로 재고, 같은 틱의 {@link Mobs#center} 는 그 물음 자리에서 가장 가까운 구 표면 점을
     * 준다(수류탄, 화염병이 거대한 몸 가장자리에도 닿고 맞은 점이 그 구가 됨). 구가 많은 몸은 spheres() 를 틱마다 한 번만 계산해 같은 목록을 돌려줄 것.
     */
    public boolean big;

    // ------------------------------------------------------------------ 새 종 갈고리(2026-11-12 sp_engine, docs/모듈/API.md '새 종 갈고리')

    /**
     * 이 개체가 사람에게 주는 피해 배율(기본 1, MOBDMG): Players.hurt 가 이 개체가 원인(src)인 모든 피해에 곱한다(감독 dmgMul, 사람 armor 와 함께).
     * 지원 종이 둘레 개체를 세게 만들 때 쓴다. 건 쪽이 되돌린다(곱해 두고 나눠 되돌리기). 이 틱만 거는 값은 {@link #dmgBuff}.
     */
    public double dmgMul = 1.0;
    /** 이번 틱에만 걸리는 피해 배율(speedBuff 와 같은 꼴: 지원 종이 매 틱 다시 걸어야 유지, 엔진이 틱 끝에 1 로 되돌림). MOBDMG */
    public double dmgBuff = 1.0;
    double dmgBuffPrev = 1.0;
    /**
     * 체력의 사람 수 지수(HPEXP, 기본 NaN = 감독 Director.Tune.HP_POW). 생성자에서 넣으면 감독이 놓을 때 체력의 사람 수 몫을 접속자^hpPow 로 바꾼다
     * (거대 종은 {@code hpPow = Director.Tune.HP_POW_BIG}). 감독이 내지 않은 개체(child, 시험 소환)에는 안 씀.
     */
    public double hpPow = Double.NaN;
    /** 웨이브를 붙들지 않음(DIRECTOR): 살아 있어도 웨이브가 끝난다(감독 waveDone 이 세지 않음). 웨이브 끝 정리에서는 여느 개체처럼 치워진다. */
    public boolean noHold;
    /**
     * 웨이브 끝 정리에서 빠짐(DIRECTOR): 쉬는 시간과 다음 웨이브까지 살아 남고, 웨이브를 붙들지도 않는다(noHold 와 같음). 쉬는 시간에도 tick 이 불리니
     * 사람을 치지 않으려면 종이 {@code game.state} 를 본다. 판이 바뀌면(중지, 전멸 뒤 대기실) 엔진이 치운다. 막힘 정리(STALL)에서도 빠진다.
     */
    public boolean persist;
    /** 남이 가림(UNTARGET, {@link #cloak}): 이 틱까지 탄, 폭발, 불이 지나간다. 종 자신의 untargetable 과 따로 센다. */
    long cloakUntil = Long.MIN_VALUE / 2;
    /** 폭발 물음(inRadius)이 이 개체를 넣은 자리와 틱(BIG: 같은 틱의 center 가 겹친 물음에도 제 물음 자리를 씀). */
    Vector queryAt;
    long queryTick = Long.MIN_VALUE;

    /** 지금 상태 이름(모델 select 의 strings[0], "" 이 기본). */
    public String state = "";
    public int stateAt;

    /** 몸(디스플레이). spawn 뒤에 생긴다. */
    public Body body;

    // 엔진이 쓰는 값(같은 패키지만)
    int mutStage;
    long biteUntil;
    Gabe biteWith;
    long lureUntil;
    final Vector lureAt = new Vector();
    double buffPrev = 1.0;
    final double[] hist = new double[Mobs.HIST * 3];
    int histN, histAt;
    Map<UUID, Double> contrib;
    long hurtSoundAt = Long.MIN_VALUE / 2;
    /** 공격 기록(밸런스 측정, Mobs.noteHit): 처음 사람 곁에 든 나이, 처음 때린 나이, 마지막으로 때린 판 틱. */
    int nearAge = -1, hitAge = -1;
    long hitAt = Long.MIN_VALUE / 2;
    /** 표적 나누기(Mobs.prey): 사람 곁에서 이 개체가 노리는 사람. 멀면 null. */
    Player prey;

    protected Gabe(Game game, Species sp) {
        this.game = game;
        this.sp = sp;
        this.id = game.mobs.nextId();
        this.hover = sp.band.hover;
        // 게이브 체력 배율(설정 mobHp, 보스 빼고: Mobs.mobHp)
        this.maxHp = this.hp = sp.is(Role.BOSS) ? sp.hp : sp.hp * game.mobs.mobHp;
        this.reward = sp.reward;
        this.flying = sp.band == Band.SKY;
        this.noMutate = sp.is(Role.SWARM) || sp.spawn == Species.Spawn.PLACED;
    }

    // ------------------------------------------------------------------ 종이 채우는 것

    /** 매 틱. vel 을 정하고 공격, 상태를 바꾼다. */
    public abstract void tick();

    /** 몸을 만든다(기본: 이 종 모델 하나). 부품이 더 있으면 덮어쓴다. */
    protected void build() {
        body = Body.of(this, "gabe/" + sp.key());
    }

    /** 세상에 막 놓인 뒤 한 번. */
    protected void spawned() {
    }

    /** 맞았을 때. 기본: 체력을 깎고 0 이하면 죽음. 막거나 튕기려면 덮어쓰고 false 를 돌려준다. */
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        hp -= d.amount();
        game.mobs.onHurt(this, d);
        if (hp <= 0) die(d);
        return true;
    }

    /** 죽음(보상, 시체 연출, 몸 치우기). 덮어쓰면 super.die(d) 를 부를 것. */
    public void die(Damage d) {
        if (dead) return;
        dead = true;
        game.mobs.onDeath(this, d);
    }

    /** 몸과 함께 사라짐(보상 없음). */
    public void remove() {
        removed = true;
        dead = true;
        if (body != null) body.remove();
    }

    /** 맞는 구들(세계 좌표 중심 x,y,z, 반지름). 기본은 pos 하나. 여러 마디 종은 덮어쓴다. */
    public List<double[]> spheres() {
        return List.of(new double[] {pos.getX(), pos.getY(), pos.getZ(), radius});
    }

    /** 물어뜯기 규칙이 보는 머리 자리(기본 pos). 머리가 몸 중심에서 먼 종은 덮어쓴다. */
    public Vector head() {
        return pos;
    }

    /** 사람이 이 개체를 보고 돕기(좌클릭)를 했을 때(달래기, 떼기 같은 종 상호작용). 썼으면 true. */
    public boolean helped(Player p) {
        return false;
    }

    /** 조명탄, 정화 같은 것이 정체를 드러내게 했을 때(흉내 종이 덮어씀). */
    public void reveal() {
        disguised = false;
    }

    /** 딸랑이 같은 것에 주의가 끌림(at 쪽으로 ticks 동안). 기본: 엔진이 seek 으로 끌고 감. */
    public void lure(Vector at, int ticks) {
        game.mobs.lure(this, at, ticks);
    }

    /** 듣기(웅크리기)로 들리는 속 울음. 흉내 종은 웃음 섞인 소리를 돌려준다. null 이면 소리 없음. */
    public String listenSound() {
        return null;
    }

    /** 다른 종과 물어뜯기가 막 시작됨(둘 다 20틱 멈춤, 체력 5% 깎임은 엔진이 함). 반응이 필요한 종만 덮어쓴다. */
    public void bitten(Gabe other) {
    }

    // ------------------------------------------------------------------ 도우미(엔진이 구현)

    public Location loc() {
        return new Location(game.world, pos.getX(), pos.getY(), pos.getZ(), yaw, pitch);
    }

    /** 감독 배율 x 판 규칙 x 이번 틱 버프를 곱한 빠르기(블록/틱). */
    public double speed() {
        return baseSpeed * game.director.speedMul() * buff() * game.mobs.ruleSpeed(this);
    }

    /** 지금 걸린 지원 버프(이번 틱에 걸렸으면 그것, 아니면 지난 틱 것). */
    public double buff() {
        return speedBuff != 1.0 ? speedBuff : buffPrev;
    }

    /** 바닥 높이(이 x, z 의 걷는 면 y). */
    public double floor() {
        return game.mobs.flow.floor(pos.getX(), pos.getZ());
    }

    /** 흐름장을 따라 가장 가까운 사람 쪽으로(vel 설정). 하늘 종은 직선 + 높이 유지. */
    public void follow(double speed) {
        game.mobs.follow(this, speed);
    }

    /** 점으로 곧장(벽은 엔진이 막음). */
    public void seek(Vector to, double speed) {
        game.mobs.seek(this, to, speed);
    }

    /** 멈춤. */
    public void hold() {
        vel.zero();
    }

    /** 보는 방향만 바꿈. */
    public void face(Vector at) {
        game.mobs.face(this, at);
    }

    /** 보는 방향을 at 쪽으로 한 틱에 maxDeg 까지만 돌림(느리게 돌아섬). 다 돌았으면 true. */
    public boolean turnTo(Vector at, float maxDeg) {
        return game.mobs.turnTo(this, at, maxDeg);
    }

    /**
     * 반경 안 가장 가까운 서 있는 사람(쓰러진 사람 빼고). 2026-11-12: 사람 곁에서 이 개체가 나눠 고른 사람(Mobs.prey)이 반경 안이고 가장 가까운 사람보다
     * 2.5블록 넘게 멀지 않으면 그 사람(여럿이 모여 있어도 떼가 바깥 사람 하나에만 몰리지 않게). 혼자면 늘 그 사람.
     */
    public Player nearest(double r) {
        return game.mobs.nearestFor(this, r);
    }

    /** 표적 나누기로 이 개체가 노리는 사람(사람 곁이 아니면 null). */
    public Player chosenPrey() {
        return prey;
    }

    /**
     * 공격 박자: 쿨다운이나 준비 틱에 감독 박자 배율(Director.tempo(), 웨이브가 깊을수록 짧음)을 곱한 틱(1 이상). 종은 {@code readyAt = age + cd(COOLDOWN)},
     * {@code inPhase() >= cd(WINDUP)} 처럼 쓴다(2026-11-12, 사람을 안 노린다는 판 뒤 말).
     */
    public int cd(int ticks) {
        return Math.max(1, (int) Math.round(ticks * game.director.tempo()));
    }

    /** 반경 안 가장 가까운, 블록에 가리지 않은 서 있는 사람(표적 고르기). */
    public Player acquire(double r) {
        return game.mobs.acquire(this, r);
    }

    /** 표적을 정하고 실눈을 뜸(찍었다). null 이면 표적 풂. */
    public void mark(Player p) {
        game.mobs.mark(this, p);
    }

    /** 이 사람이 아직 서 있나(표적 확인). */
    public boolean standing(Player p) {
        return p != null && p.isOnline() && game.players.get(p).standing();
    }

    /** 사람과 닿았나(몸 반지름 + reach, 사람 몸통 기준). */
    public boolean touches(Player p, double reach) {
        return game.mobs.touches(this, p, reach);
    }

    /** 사람 몸통 가운데(발 + 0.9). */
    public Vector chest(Player p) {
        return Mobs.chest(p);
    }

    /** 몸 중심에서 사람 몸통까지 거리. */
    public double dist(Player p) {
        return Mobs.chest(p).distance(pos);
    }

    /** 사람 쪽 수평 단위 벡터. */
    public Vector toward(Player p) {
        Vector d = Mobs.chest(p).subtract(pos).setY(0);
        double l = d.length();
        return l < 1e-6 ? d : d.multiply(1 / l);
    }

    /** 몸 중심에서 사람 눈까지 블록에 가리지 않았나. */
    public boolean sees(Player p) {
        return game.mobs.clearLine(pos, p.getEyeLocation().toVector());
    }

    /** 일직선 돌진(수평 방향 dir, 빠르기 speed). 땅 몹은 벽에 막히면 엔진이 멈춘다({@link #wallAhead}). */
    public void dash(Vector dir, double speed) {
        Vector d = flying ? dir.clone() : dir.clone().setY(0);
        double l = d.length();
        if (l < 1e-6) vel.zero();
        else vel.copy(d.multiply(speed / l));
    }

    /** 지금 속도 쪽 dist 블록 앞이 막혔나(땅 몹용, 하늘과 ghost 는 늘 false). */
    public boolean wallAhead(double dist) {
        if (flying || ghost) return false;
        double l = Math.hypot(vel.getX(), vel.getZ());
        if (l < 1e-6) return false;
        double x = pos.getX() + vel.getX() / l * dist, z = pos.getZ() + vel.getZ() / l * dist;
        return !game.mobs.flow.walkable(x, z) || game.mobs.flow.floor(x, z) - floor() > Flow.STEP;
    }

    /**
     * 사람을 밀침(수평 dir 쪽 strength 블록/틱, 위로 up). 사람 속도에 더하되 여럿이 한꺼번에 밀어도 수평 속도는 strength 의 1.5배,
     * 위 속도는 up 의 1.5배를 넘지 않는다(떼에 둘러싸여 수십 블록 날아가지 않게).
     */
    public void knock(Player p, Vector dir, double strength, double up) {
        Vector d = dir.clone().setY(0);
        double l = d.length();
        if (l > 1e-6) d.multiply(strength / l);
        Vector v = p.getVelocity().add(d);
        double h = Math.hypot(v.getX(), v.getZ()), cap = Math.max(strength * 1.5, 0.05);
        if (h > cap) {
            v.setX(v.getX() * cap / h);
            v.setZ(v.getZ() * cap / h);
        }
        v.setY(Math.min(Math.max(v.getY(), 0) + up, Math.max(up * 1.5, 0.05)));
        p.setVelocity(v);
    }

    /** 하늘 선회: center 둘레 반지름 r 을 speed 로 돈다(높이는 center.y). dir 1 반시계, -1 시계. */
    public void circle(Vector center, double r, double speed, int dir) {
        game.mobs.circle(this, center, r, speed, dir);
    }

    /** 사람에게 피해(도감 기본 수치를 그대로, 웨이브 피해 배율과 방어는 Players.hurt 가 곱함). 원인 키는 HUD 글귀(cause_g01 처럼 data/phrases/G01.json). */
    public void hit(Player p, double dmg, String causeKey) {
        game.mobs.noteHit(this, p, dmg);
        game.players.hurt(p, dmg, this, causeKey);
    }

    /** 이 개체가 사람을 마지막으로 때린 뒤 지난 틱(한 번도 안 때렸으면 아주 큰 값). */
    public long sinceHit() {
        return game.now() - hitAt;
    }

    /** 붙잡기(못 쏨, 좌클릭 clicks 번으로 탈출). 이미 붙잡혔으면 false. */
    public boolean grab(Player p, int clicks) {
        return game.players.grab(p, this, clicks);
    }

    /** 상태 바꾸기(모델 select). 같은 상태면 아무것도 안 함. */
    public void state(String s) {
        if (s.equals(state)) return;
        state = s;
        stateAt = age;
        if (body != null) body.state(s);
    }

    public int inState() {
        return age - stateAt;
    }

    /** 맞은 번쩍임을 지금 건다(엔진이 맞을 때 저절로 부름). */
    public void flash() {
        if (body != null) body.flash(game.worldTime() + 3);
    }

    /** 오래 살면 변함 단계(0 없음, 1 입 찢어짐, 2 막대기 넷 + 빠르기 x1.25, 3 갈라짐까지 끝). */
    public int mut() {
        return mutStage;
    }

    /** 물어뜯기 중(엔진이 tick 을 부르지 않음). */
    public boolean biting() {
        return biteUntil > game.now();
    }

    /** 끌림 중(엔진이 tick 대신 그쪽으로 seek). */
    public boolean lured() {
        return lureUntil > game.now();
    }

    /** 판 규칙 돌아보기로 지금 바라보는 사람(없으면 null). */
    public Player gaze() {
        return game.mobs.gazeTarget(this);
    }

    /** 체력 비율 0..1. */
    public double hpFrac() {
        return maxHp <= 0 ? 0 : Math.max(0, hp / maxHp);
    }

    /** n 틱에 한 번 참(개체마다 어긋나게, 무거운 일을 나눌 때). */
    public boolean every(int n) {
        return n <= 1 || (age + id) % n == 0;
    }

    /** 판 시계(틱). */
    public long now() {
        return game.now();
    }

    /** 이 종 소리(gave:gabe.gxx.이름). 팩에 없으면 바닐라 대체음. range 블록 안 사람에게. 소리 없는 웨이브엔 안 남. */
    public void sound(String name, Sound fallback, double range) {
        sound(name, fallback, 1f, range);
    }

    /** 대체음 높이 fallbackPitch 를 정하는 판. */
    public void sound(String name, Sound fallback, float fallbackPitch, double range) {
        if (game.director.silent()) return;
        kr.gave.Props.soundAround(loc(), "gabe." + sp.key() + "." + name, fallback, 1f, 0.94f + game.random.nextFloat() * 0.12f,
                fallbackPitch, range, -1);
    }

    /**
     * 한 사람에게만 들리는 이 종 소리(VOLUME): 이 몸 자리에서 volume(0..1, 사람마다 다르게 줄 수 있음)으로. 멀수록 크게 같은 셈은 종이 volume 으로 한다
     * (모드 소리는 클라가 attenuation_distance 로 또 줄이니 팩의 attenuation 을 크게). 소리 없는 웨이브엔 안 남. 바탕은 {@code Props.soundTo}.
     */
    public void soundFor(Player p, String name, Sound fallback, float volume) {
        if (p == null || game.director.silent()) return;
        kr.gave.Props.soundTo(p, loc(), "gabe." + sp.key() + "." + name, fallback, Math.max(0f, volume), 0.94f + game.random.nextFloat() * 0.12f, 1f, -1);
    }

    /** 사람에게 주는 피해에 곱할 이 개체 배율(dmgMul x 이번 틱 dmgBuff). Players.hurt 가 읽음. MOBDMG */
    public double hurtMul() {
        return dmgMul * (dmgBuff != 1.0 ? dmgBuff : dmgBuffPrev);
    }

    /**
     * 남이 이 개체를 ticks 동안 가림(UNTARGET): 그동안 총 광선, 폭발, 불, 모든 피해가 지나간다(Mobs.cast, inRadius, damage 가 뺌). 종 자신의 untargetable 은
     * 건드리지 않으니 가리는 쪽과 숨는 쪽이 서로 덮어쓰지 않는다. 계속 가리려면 매 틱(또는 짧게 자주) 다시 부른다. 여럿이 걸면 가장 늦은 끝.
     */
    public void cloak(int ticks) {
        cloakUntil = Math.max(cloakUntil, game.now() + Math.max(0, ticks));
    }

    /** 남이 가려 지금 안 맞나(UNTARGET). */
    public boolean cloaked() {
        return cloakUntil > game.now();
    }

    /** 지금 탄이 지나가나(untargetable 이거나 남이 가림). */
    public boolean unhittable() {
        return untargetable || cloaked();
    }

    /** 같은 띠의 다른 게이브(지원 종용). */
    public List<Gabe> kin(double r) {
        return game.mobs.near(pos, r, g -> g != this && !g.dead && g.sp.band == sp.band);
    }

    /** 분열, 소환. 보상 0 으로 놓는다. 예산이 차면 null. */
    public Gabe child(Species s, Vector at) {
        Gabe c = game.mobs.spawn(s, at, true);
        return c;
    }

    @Override
    public String toString() {
        return sp.name() + "#" + id + (dead ? "(dead)" : "") + " hp=" + Math.round(hp) + "/" + Math.round(maxHp) + " st=" + state;
    }
}
