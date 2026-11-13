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

    @Override
    public boolean onTruck(Player p) {
        return false;
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
