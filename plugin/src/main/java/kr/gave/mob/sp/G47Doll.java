package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.joml.Matrix4f;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.LookState;
import kr.gave.player.PState;
import net.kyori.adventure.text.Component;

/**
 * 가짜 동료 인형(G47 의 착시, G49 축복 끝의 가짜 동료): 26.3 의 마네킹 엔티티(플레이어 모델, 스킨, 이름표)와 등에 삐져나온 엔드 막대기
 * 끝 둘(원형 DNA: 이것으로 가짜를 알아본다, 모델 gave:gabe/g47_tips).
 *
 * <p>도감은 PacketEvents 로 가짜 플레이어 패킷을 보내라고 했지만 26.3 Paper 에는 마네킹이 있어 그대로 쓴다. 판정은 없다(무적, 안 밀림,
 * 중력 없음). 맞는 구는 이 인형을 쓰는 게이브 쪽에 있다. 스킨과 이름은 판에 든 사람 것(프로필 조회 없이 접속 중인 사람의 프로필 그대로),
 * '내 얼굴, 내 이름 쓰지 않기'(PState.noLikeness)인 사람은 고르지 않는다. 고를 사람이 없으면 기본 스킨에 이름표 없음.
 * everyone 이 false 면 아무에게도 안 보이게 낳고 show(p) 로 보일 사람만 고른다.
 */
final class G47Doll {

    /** 막대기 끝 자리: 마네킹 승객 자리(머리 위)에서 아래로, 등 뒤로. */
    static final float TIPS_DOWN = -0.5f, TIPS_BACK = -0.2f;

    private final Game game;
    Mannequin man;
    ItemDisplay tips;
    /** 흉내 낸 사람(없으면 null). 그 사람에게는 이 인형을 보이지 않는다. */
    final UUID likeness;
    private float sentYaw = Float.NaN;

    G47Doll(Game game, Location at, Player like, boolean everyone) {
        this.game = game;
        this.likeness = like == null ? null : like.getUniqueId();
        Location l = at.clone();
        l.setPitch(0);
        man = l.getWorld().spawn(l, Mannequin.class, m -> {
            m.setPersistent(false);
            m.setVisibleByDefault(everyone);
            m.setInvulnerable(true);
            m.setSilent(true);
            m.setGravity(false);
            m.setImmovable(true);
            m.setCollidable(false);
            m.setCanPickupItems(false);
            m.setRemoveWhenFarAway(false);
            m.setDescription(null);
            if (like != null) {
                m.setProfile(ResolvableProfile.resolvableProfile(like.getPlayerProfile()));
                m.customName(Component.text(like.getName()));
                m.setCustomNameVisible(true);
                ItemStack hand = like.getInventory().getItemInMainHand();
                if (!hand.isEmpty() && m.getEquipment() != null) m.getEquipment().setItemInMainHand(hand.clone());
            }
        });
        ItemStack it = Body.item("gabe/g47_tips", "", new LookState(), game.worldTime());
        tips = l.getWorld().spawn(l, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setVisibleByDefault(everyone);
            d.setItemStack(it);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED);
            d.setBrightness(Body.LIGHT);
            d.setViewRange(1.0f);
            d.setTeleportDuration(2);
            d.setTransformationMatrix(new Matrix4f().translate(0, TIPS_DOWN, TIPS_BACK).rotateY((float) Math.PI));
        });
        man.addPassenger(tips);
    }

    boolean valid() {
        return man != null && man.isValid();
    }

    /** 발 자리 (x, y, z), 보는 쪽 yaw(도). 매 틱 불러도 된다. */
    void move(double x, double y, double z, float yaw) {
        if (!valid()) return;
        Location l = man.getLocation();
        if (Math.abs(l.getX() - x) > 1e-3 || Math.abs(l.getY() - y) > 1e-3 || Math.abs(l.getZ() - z) > 1e-3 || Math.abs(wrap(l.getYaw() - yaw)) > 1f) {
            man.teleport(new Location(l.getWorld(), x, y, z, yaw, 0));
        }
        if (tips != null && tips.isValid() && (Float.isNaN(sentYaw) || Math.abs(wrap(sentYaw - yaw)) > 4f)) {
            sentYaw = yaw;
            tips.setRotation(yaw, 0);
        }
    }

    void show(Player p) {
        if (!valid()) return;
        p.showEntity(game.plugin, man);
        if (tips != null && tips.isValid()) p.showEntity(game.plugin, tips);
    }

    void hide(Player p) {
        if (!valid()) return;
        p.hideEntity(game.plugin, man);
        if (tips != null && tips.isValid()) p.hideEntity(game.plugin, tips);
    }

    void swing() {
        if (valid()) man.swingMainHand();
    }

    void remove() {
        if (tips != null) tips.remove();
        tips = null;
        if (man != null) man.remove();
        man = null;
    }

    private static float wrap(float d) {
        d %= 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    /**
     * 흉내 낼 사람 고르기: 판에 든 사람 가운데 얼굴, 이름을 써도 되는 사람. avoid(지금 착시를 보는 사람들)에 없는 사람을 먼저 고르고,
     * 없으면 avoid 안에서(그 사람 자신에게는 안 보임, 둘 이상일 때만). 고를 사람이 없으면 null.
     */
    static Player pickLikeness(Game game, Collection<UUID> avoid) {
        List<Player> out = new ArrayList<>(), in = new ArrayList<>();
        for (Player p : game.players.inPlay()) {
            PState s = game.players.get(p);
            if (s == null || s.noLikeness) continue;
            if (avoid != null && avoid.contains(p.getUniqueId())) in.add(p);
            else out.add(p);
        }
        if (!out.isEmpty()) return out.get(game.random.nextInt(out.size()));
        if (in.size() >= 2) return in.get(game.random.nextInt(in.size()));
        return null;
    }
}
