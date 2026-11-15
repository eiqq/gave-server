package kr.gave.game;

import java.lang.reflect.Method;

import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.trip.Trip;
import kr.gave.trip.TripPart;

/**
 * 증강 → 원정 허브 연결(기본 {@link AugLinks}). 허브 {@code game.trip} 과 트럭 부품(kr.gave.trip.Truck)을 이름으로 찾아 묻는다:
 * 트럭 부품은 다른 작업자가 올리는 클래스라 여기서는 컴파일 때 기대지 않고, 있으면 공개 메서드 {@code static Vector center()},
 * {@code int part(String)} 을 리플렉션으로 부른다(없거나 이름이 바뀌면 기본값: 트럭 자리 = 상점 카운터, 부품 0).
 * game.trip 은 Augments 보다 늦게 만들어지므로 부를 때마다 읽는다.
 */
final class AugTrip implements AugLinks {

    private final Game game;
    private Class<?> seen;
    private Method center, part;
    /** 추격전 계열 특수 라운드가 도는 중인가(틱마다 한 번만 물음) */
    private boolean chase;
    private long chaseAt = -1;

    AugTrip(Game game) {
        this.game = game;
    }

    private Trip trip() {
        Trip t = game.trip;
        return t == null || !t.enabled ? null : t;
    }

    /** 트럭 부품(없으면 null). 처음 볼 때 메서드를 찾아 둠 */
    private TripPart truck() {
        Trip t = trip();
        TripPart tr = t == null ? null : t.part("Truck");
        if (tr != null && tr.getClass() != seen) {
            seen = tr.getClass();
            center = find(seen, "center");
            part = find(seen, "part", String.class);
        }
        return tr;
    }

    private static Method find(Class<?> c, String name, Class<?>... args) {
        try {
            return c.getMethod(name, args);
        } catch (NoSuchMethodException | SecurityException e) {
            return null;
        }
    }

    @Override
    public Vector truckPos() {
        TripPart tr = truck();
        if (tr != null && center != null) {
            try {
                Object o = center.invoke(tr);
                if (o instanceof Vector v) return v;
            } catch (ReflectiveOperationException | RuntimeException e) {
                center = null;
            }
        }
        return Shop.counterCenter();
    }

    @Override
    public boolean ready(String need) {
        if ("truck".equals(need)) return truck() != null;
        Trip t = trip();
        if ("revive".equals(need)) return t != null && t.has("Revive");
        return false;
    }

    @Override
    public int part(String key) {
        TripPart tr = truck();
        if (tr == null || part == null) return 0;
        try {
            Object o = part.invoke(tr, key);
            return o instanceof Number n ? n.intValue() : 0;
        } catch (ReflectiveOperationException | RuntimeException e) {
            part = null;
            return 0;
        }
    }

    /**
     * 추격전 계열(추격전, 가로막힌 길, 마지막 질주)에서는 모두 트럭 뒤 판(길이 22) 위에 타고 세상이 흐른다: 판 뒤쪽은 트럭 가운데에서 8블록이
     * 넘어, 거리로 재면 트럭 곁 증강(문지기, 짐칸 사수, 들것, 확성기, 미끼)과 자동 탄약이 안 걸린다. 그 라운드 동안은 모두 트럭 곁으로 친다.
     * 추격전 계열만 물음 'dist'(달린 몫)에 답한다(kr.gave.trip.special.Chase.ask)
     */
    @Override
    public boolean onTruck(Player p) {
        long now = game.now();
        if (now != chaseAt) {
            chaseAt = now;
            Trip t = trip();
            chase = t != null && game.state == Game.State.WAVE && t.ask("dist") != null;
        }
        return chase;
    }

    @Override
    public int act() {
        Trip t = trip();
        return t == null ? 0 : t.act();
    }

    @Override
    public int unit() {
        Trip t = game.trip;
        return t == null ? 0 : t.unit();
    }
}
