package kr.gave.gun;

import net.minecraft.network.syncher.EntityDataAccessor;

/**
 * 살아 있는 것 깃발(LivingEntity.DATA_LIVING_ENTITY_FLAGS, 비트 1 = 아이템 쓰는 중) 정보 칸. 총 '쥠' 확인에 쓴다
 * (좀비런 net/GlowNet 의 accessor 부분만 옮김. 발광 처리기는 가져오지 않음).
 */
final class LivingFlags {
    private LivingFlags() {
    }

    static final EntityDataAccessor<Byte> LIVING_FLAGS = accessor(net.minecraft.world.entity.LivingEntity.class, "DATA_LIVING_ENTITY_FLAGS");

    @SuppressWarnings("unchecked")
    private static <T> EntityDataAccessor<T> accessor(Class<?> owner, String name) {
        try {
            java.lang.reflect.Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return (EntityDataAccessor<T>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("엔티티 정보 칸 없음 " + owner.getSimpleName() + "." + name, e);
        }
    }
}
