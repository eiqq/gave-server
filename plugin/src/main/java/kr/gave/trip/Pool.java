package kr.gave.trip;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 모금 하나(여럿이 나눠 내는 값): 꺼내기가 쓰고, 공동 구매(폭격 같은 재미 장치)도 같은 것을 쓸 수 있다.
 * 목표 값은 바뀔 수 있다(꺼내는 값은 라운드마다 내려감): 낸 돈이 목표에 닿았는지는 {@link #full(int)} 에 지금 목표를 주어 묻는다.
 */
public final class Pool {

    private final Map<UUID, Integer> paid = new LinkedHashMap<>();
    private int total;
    private UUID last;

    /** 모인 돈(원). */
    public int total() {
        return total;
    }

    /** 이 사람이 낸 돈. */
    public int paidBy(UUID id) {
        return paid.getOrDefault(id, 0);
    }

    /** 낸 사람들(낸 차례) → 낸 돈. */
    public Map<UUID, Integer> payers() {
        return java.util.Collections.unmodifiableMap(paid);
    }

    /** 마지막으로 낸 사람(없으면 null). */
    public UUID last() {
        return last;
    }

    /** 한 번 누를 때 낼 돈: 목표의 1 / parts(5원 단위 올림), 남은 값까지만. */
    public int step(int target, int parts) {
        int left = Math.max(0, target - total);
        int one = (int) Math.ceil(target / (double) Math.max(1, parts) / 5.0) * 5;
        return Math.min(left, Math.max(5, one));
    }

    /** 돈을 넣음(돈을 빼는 일은 부른 쪽이). */
    public void add(UUID who, int amount) {
        if (amount <= 0) return;
        paid.merge(who, amount, Integer::sum);
        total += amount;
        last = who;
    }

    /** 목표에 닿았나. */
    public boolean full(int target) {
        return total >= target;
    }

    /** 찬 비율 0..1. */
    public double frac(int target) {
        return target <= 0 ? 1 : Math.min(1.0, total / (double) target);
    }

    public void clear() {
        paid.clear();
        total = 0;
        last = null;
    }
}
