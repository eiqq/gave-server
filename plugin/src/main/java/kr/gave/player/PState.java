package kr.gave.player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * 사람 한 명의 판 상태. Players 가 들고 있고 다른 모듈은 읽거나 Players 메서드로 바꾼다.
 *
 * <p>틱은 모두 판 시계(Game.now()). 다른 모듈이 직접 써도 되는 칸: armor, moveMul, maxHp, bleedBonus, reviveMul, raincoat(증강, 상점),
 * grabPin, grabDetach, coverDetach(grab, cover 를 건 바로 뒤에 종이 바꿈), noLikeness. 나머지는 Players 메서드로 바꾼다.
 */
public final class PState {

    public final UUID id;
    public String name;

    public Life life = Life.LOBBY;
    public long lifeAt;

    public double hp = 100, maxHp = 100;
    /** 최대 체력 빼기(키 -> {몫, 끝 틱}). 종 같은 쪽이 {@link #cutMaxHp} 로 걸고 Augments.apply 가 maxHp 를 다시 셀 때 뺀다(maxHp 를 직접 깎으면 덮어써짐). */
    public final Map<String, double[]> hpCuts = new HashMap<>();
    /** 받는 피해 배율(증강, 비옷). */
    public double armor = 1.0;
    /** 걷기 빠르기 배율(증강). Players 가 매 틱 걷기 속도에 반영한다. */
    public double moveMul = 1.0;
    /** 쓰러졌을 때 출혈에 더하는 틱(증강 '쓰러짐 출혈 +4초' = 80). */
    public int bleedBonus;
    /** 일으키기, 짜내기 빠르기 배율(증강 '일으키기 빠름', 1.0 = 3초). 일으키는 사람 쪽 값을 쓴다. */
    public double reviveMul = 1.0;
    /** 비옷 남은 수: grab, latent, cover 를 한 번씩 막고 하나 줄어든다(같은 틱의 다음 것도 막음). */
    public int raincoat;
    public long coatTornAt = -1;

    /** 쓰러졌을 때 출혈이 끝나는 판 틱(일으키는 동안은 멈춤). */
    public long bleedUntil;
    /** 일으키는(또는 짜내는) 중인 동료와 끝나는 틱(이 사람이 받는 쪽일 때). */
    public UUID reviver;
    public long reviveUntil;
    /** 이 사람이 돕는 중인 대상(일으키기 또는 짜내기), 시작과 끝 틱. 그동안 못 쏨. */
    public UUID helping;
    public Help helpKind;
    public long helpStart, helpUntil;
    /** 먹은 게이브(갇힘). */
    public Gabe eater;

    /** 마지막 죽음의 결과: 먹은 종 또는 업은(G38), 총 주운(G52), 사람 탈(G50). 없으면 null. */
    public Species deathResult;
    /** 그 결과로 생긴 개체(먹은 개체 포함). */
    public Gabe deathMob;
    /**
     * 업은 게이브(G38)가 웨이브 끝까지 살아남음: 1 이면 다음 쉬는 시간 시작 부활을 건너뛸 차례, 2 면 한 번 건너뛰었음(다음 쉬는 시간에 부활).
     * 0 은 보통.
     */
    public int holdSkips;
    /** 다음 상점 앞 부활은 재화 손실 없음(갇힌 채 나감, 나간 사이 업은 개체가 잡힘). */
    boolean freeRevive;
    /** 나간 사이 업은(총 주운) 개체가 잡힘: 돌아오면 바로 부활. */
    boolean freedAway;
    /** 서 있다가 나감: 돌아와 판에 들 때 체력을 채우지 않음. */
    boolean resumeHp;

    /** 재화(원)와 판 전체 번 돈. */
    public int money;
    public long earned;
    public int kills;
    public double dealt;
    public int downs;
    public int revives;

    /** 증강 키 목록(Augments 가 해석). */
    public final List<String> augs = new ArrayList<>();

    // 상태 이상: 키(계열) → 끝나는 틱
    public final Map<String, Long> slowUntil = new HashMap<>();
    /** 계열 → 느려짐 비율(0..1, 0.3 = 30% 느림). 합은 0.5 까지만 걸림. */
    public final Map<String, Double> slowPct = new HashMap<>();
    public long rootUntil;
    public String rootGroup;
    /** 계열 → 면역 끝나는 틱(느려짐, 묶임, 끌림이 끝나면 1.5초). */
    public final Map<String, Long> immuneUntil = new HashMap<>();
    /** 붙잡은 게이브(못 쏨, 좌클릭 연타로 탈출). */
    public Gabe grabbedBy;
    public int escapeNeed, escapeHits;
    public long grabAt;
    /** 붙잡힌 동안 제자리에 묶나(기본 true). 끌고 가는 종은 carry 를 쓰면 좌석이 대신 잡는다. */
    public boolean grabPin = true;
    /** 동료 돕기(detach)로 뗄 수 있나(기본 true, 겹얼굴처럼 쏴야 풀리는 종은 false). */
    public boolean grabDetach = true;
    /** 화면 덮기 하나(키, 끝 틱). */
    public String cover;
    public long coverUntil;
    /** 동료 돕기로 덮기도 지워지나(기본 true). */
    public boolean coverDetach = true;
    /** 남들에게 보이는 머리 칸 모델과 붙인 개체. */
    public ItemStack headItem;
    public Gabe headBy;
    /** 잠복(종 키, 터지는 틱). 한 사람에 하나. */
    public String latent;
    public long latentAt, latentStart;
    public Consumer<Player> latentPop, latentCure;

    /** 아군 탄에 맞아 사격이 끊기는 틱(Game.blocked 가 gunLocked 로 읽음). */
    public long gunLockUntil;
    public long lastFriendlyAt = -100;

    /** 마지막으로 다치게 한 종, 원인 글귀 키, 틱. */
    public Species lastBy;
    public String lastCause;
    public long lastHurtAt;

    /**
     * 사람별 공격력 배율(총 직격 피해에 곱함, 기본 1). 종이 사람에게 거는 버프(G49 축복 같은)가 쓴다: 건 쪽이 끝날 때 되돌린다
     * (곱해 두고 나눠 되돌리기). Augments.hitMul 이 곱한다(수류탄, 불에는 안 걸림).
     */
    public double attackMul = 1;

    /** 이번 판 개인 설정: 내 얼굴, 내 이름을 쓰는 종에 쓰지 않기. */
    public boolean noLikeness;

    /** 판 중에 나갔음(상태는 남기고 판 계산에서 뺌). 나갈 때의 상태. */
    public boolean offline;
    public Life quitLife;
    /** 판 중간 합류(돌아온 사람이 아님): 판에 들 때 팀 평균 재화의 절반을 받음. */
    public boolean joinBonus;
    long lastHelpAt = -100;

    // ------------------------------------------------------------------ 원정(docs/모듈/원정.md 7절, kr.gave.trip.Revive 와 Shop 이 씀)

    /** 이 판에서 죽은 횟수, 돈을 내 꺼내진 횟수(꺼내는 값이 오름). */
    public int deaths, revived;
    /** 죽은 라운드(값이 라운드마다 내려감), 지금 죽음에 모인 돈(원). */
    public int deadRound;
    public int revivePool;
    /** 이 죽음의 깎임: 동료가 내 얼굴을 한 게이브를 잡음, 갇힌 채 라운드가 끝나 죽은 것으로 침. */
    public boolean faceKilled, trappedDeath;
    /** '살려 달라' 신호를 마지막으로 보낸 틱. */
    public long callAt = -100000;
    /** 꺼내진 직후 무적이 끝나는 틱. */
    public long safeUntil;
    /** 남을 꺼내는 데 낸 돈, 트럭 수리에 낸 돈(칭호, 기록용). */
    public int revivePaid, repairPaid;

    // ------------------------------------------------------------------ 새 종 갈고리(2026-11-12 sp_engine, docs/모듈/API.md '새 종 갈고리'): 읽기만, 바꾸기는 Players 메서드로

    /** 마지막으로 이 사람을 쓰러뜨린 종과 그 판 틱(LASTKILLER, Players.down 이 적음, 판에 들 때 지움). 없으면 null, -1. */
    public Species lastDownBy;
    public long lastDownAt = -1;
    /** 이 틱까지 빗나감 판정을 건너뜀(NOMISS, Players.noMiss(p, 틱)). 뛰기, 웅크리기로 빗나가는 종은 Players.noMiss(p) 를 읽는다. */
    public long noMissUntil;
    /** 지금 잠복 칸이 '붙음'(STICK, Players.stick): 판 전체 잠복 상한(latentCap)에 안 든다. */
    public boolean latentFree;
    /** 갇힘 이어 가기(FREE, Players.holdTrapped): 쉬는 시간 첫 틱의 갇힘 끝 규칙과 먹은 개체가 사라짐(remove)에도 갇힌 채 둠. */
    public boolean trapHold;
    /** 이어 가는 갇힘의 먹은 개체가 없어진 틱(-1 = 있음). Players.TRAP_HOLD_MAX 틱 넘게 아무도 안 이어 받으면 갇힘 끝 규칙. */
    public long trapLostAt = -1;
    /** 옅은 화면 테(VEIL, Players.veil): 덮개(cover)와 따로 하나, 끝 틱. 그림은 Hud 가 그린다. */
    public String veil;
    public long veilUntil;
    /** 태워 주는 좌석(SEAT, Players.ride)이 끝나는 틱: 그때까지 좌석에 탄 채 쏠 수 있다. */
    public long rideUntil;
    /** 받는 피해 배율(TAKEN, Players.taken): 키 → {배율, 끝 틱}. */
    final Map<String, double[]> taken = new HashMap<>();

    /** 받는 피해 배율(TAKEN) 곱(끝난 것은 치움). */
    public double takenMul(long now) {
        if (taken.isEmpty()) return 1;
        double k = 1;
        for (java.util.Iterator<double[]> it = taken.values().iterator(); it.hasNext();) {
            double[] t = it.next();
            if (t[1] <= now) it.remove();
            else k *= t[0];
        }
        return k;
    }

    /** 돕기 종류. */
    public enum Help { REVIVE, SQUEEZE }

    public PState(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    public boolean standing() {
        return life == Life.ALIVE;
    }

    /** 최대 체력에서 amount 를 뺀다(키마다 하나, until 틱까지: 건 쪽이 계속 다시 걸어야 남음). amount 0 이하면 그 키를 지움. */
    public void cutMaxHp(String key, double amount, long until) {
        if (amount <= 0) hpCuts.remove(key);
        else hpCuts.put(key, new double[] {amount, until});
    }

    /** 지금(now 틱) 살아 있는 최대 체력 빼기 합(끝난 것은 지움). */
    public double maxHpCut(long now) {
        if (hpCuts.isEmpty()) return 0;
        hpCuts.values().removeIf(c -> c[1] < now);
        double sum = 0;
        for (double[] c : hpCuts.values()) sum += c[0];
        return sum;
    }

    public double hpFrac() {
        return maxHp <= 0 ? 0 : Math.max(0, hp / maxHp);
    }

    /** 아군 탄 끊김이나 돕기 채널로 지금 못 쏘면 true(Game.blocked 가 읽음). */
    public boolean gunLocked(long now) {
        return gunLockUntil > now || helping != null;
    }

    /** 지금 걸린 느려짐 합(0..0.5). */
    public double slowSum(long now) {
        double sum = 0;
        for (Map.Entry<String, Long> e : slowUntil.entrySet()) {
            if (e.getValue() > now) sum += slowPct.getOrDefault(e.getKey(), 0.0);
        }
        return Math.min(0.5, sum);
    }

    public boolean rooted(long now) {
        return rootUntil > now;
    }
}
