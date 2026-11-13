package kr.gave.gun;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import io.papermc.paper.datacomponent.item.SwingAnimation;
import kr.gave.Props;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import net.kyori.adventure.text.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;

/**
 * 총(좀비런 Guns 를 옮김, 좀비 쪽 연결은 GunHost 로 바꿈). 조작: 우클릭 누르고 있기 = 사격(짧게 = 한 발), Q = 재장전,
 * 좌클릭 = 돕기(host.onHelp), 숫자키 1 주무기 / 2 권총 / 3 소모품 칸(총 아님). F 수류탄은 {@link Grenades} 가 받는다.
 * 누르고 있기: 총에 아주 긴 사용 시간(consumable) + use_effects(걷기, 달리기 그대로)를 줘서 누르는 동안 서버가 '사용 중'으로 안다.
 * 채팅, Esc 로 화면이 열리면 클라가 뗌을 안 보내므로 10 틱마다 클라에만 '사용 안 함'을 보내 다시 누름이 오는지 본다(scpsl Firearms 와
 * 같은 방법). 답이 올 시간(핑 + 2 틱)까지만 계속 쏘고, 그 뒤로는 답이 올 때까지 쏘지 않는다(뗐는데 계속 쏘는 일을 줄임).
 * 반동: 연사할수록 위로 오르고(총마다 배율, 한도) 옆으로는 총마다 정해진 무늬 + 무작위. 클라 시점에 더하는 상대 회전이라 마우스를 빼앗지
 * 않고, 쏜 틱에 65%, 다음 틱에 35% 를 나눠 보낸다. 방아쇠를 놓으면 오른 만큼의 일부를 6 틱에 걸쳐 되돌린다. 쏠 때마다 시야가 살짝 좁아짐
 * (클라에만 걷기 속도를 바꿔 FOV 배율을 움직임). 퍼짐은 연사 누적 + 이동 + 공중 - 웅크림.
 * 총구 불꽃은 손에 든 총 아이템 모델 안(build_pack.py 의 muzzle_&lt;총&gt;)에 있고, 쏜 틱 도장을 custom_model_data 색으로 넣으면
 * 팩 셰이더가 2 틱 그린다(1인칭, 3인칭 모두 총구 끝). 재장전 중 탄창이 빠진 동안은 깃발 0 으로 탄창 뺀 모델을 보인다.
 * 소리: 총소리는 총마다 들리는 거리(64~128 블록) 안 사람에게 따로 보내고, 재장전은 박자에 맞춘 부품 소리(탄창 빠짐, 꽂음, 슬라이드, 노리쇠,
 * 산탄 한 발씩, 펌프), 탄착은 재질 소리, 곁을 스친 사람에겐 스침 소리.
 * <p>
 * 맞힘: 블록 광선을 먼저 쏘고 그 거리 안에서 host.targets().cast 로 가장 가까운 게이브를 찾는다. 피해 = 총 피해 x 배율 x 거리 감쇠
 * (사거리 60% 까지 1, 사거리에서 0.5), 치명(배율 crit 확률)은 2배. 관통 수만큼 이어 간다(블록은 못 뚫음).
 * 배율(GunMods)은 매 틱(그리고 쏠 때마다) host.mods(p) 로 읽어 그 사람의 실제 총 값(eff)을 다시 만든다: 탄창, 예비탄, 연사, 퍼짐, 반동,
 * 사거리, 관통, 산탄 알 수, 재장전 시간(박자 소리 시각도 같은 비율).
 * <p>
 * 굴린 총: 주무기는 {@link Gun}(종류 + 등급 + 굴린 스탯 + 특성 + 강화 단계, Arms.gun), 권총은 굴리지 않는 기본 권총(Arms.pistol, 강화만).
 * 유효 총 = 바탕 Spec x Gun.mods() x host.mods(p) ({@link #effective}). 쏘기 파이프라인(누름, 박자, 반동, 재장전, 불꽃 도장)은 그대로다.
 * 특성(폭발탄, 화염탄, 연쇄, 흡혈, 관통, 넉백, 치명)과 증강의 대상 조건 피해, 처치 회복, 아군 사격 알림은 {@link #hooks}(Augments)로 묻고 알린다.
 * 아군 사격: 탄 광선이 쏜 사람 말고 서 있는 동료 몸 상자(또는 쓰러진 동료의 좌석 자리 낮은 상자)에 몹, 블록보다 먼저 닿으면 종 갈고리
 * ({@link #addAllyShot}, 감염자 등 막대기 등)에 먼저 묻고, 아무도 받지 않으면 hooks.friendly 를 한 발에 한 사람 한 번 부르고 탄은 거기서 멈춘다.
 * 맞힐 게이브가 그 동료에게 붙은 것(붙잡음, 머리 부착, hooks.attached)이면 그 동료 몸은 탄을 막지 않는다.
 * <p>
 * 판 중에 나가면({@link #forget}) 무기 상태(굴린 총, 강화, 탄, 소모품 칸)를 맡아 두고, 같은 판에 다시 들어와 {@link #equip} 을 부르면 되살린다.
 */
public final class Guns implements Listener {
    /**
     * 무기 종류. 앞 다섯은 처음부터 있던 총, FLAME 부터는 2026-11-12 무기 랜덤 상자로 늘린 것(쏘는 방식은 {@link #MODES}).
     * 차례가 배열 칸 번호라 뒤에만 더한다
     */
    public enum Kind {
        PISTOL, SMG, SHOTGUN, RIFLE, SNIPER, FLAME, LAUNCHER, ROCKET, MINIGUN, LMG, REVOLVER, BURST, DMR, RAILGUN, TESLA, FREEZE, BOW,
        // 2026-11-12 wpn_max: 모델 표(Models)의 새 종류 아홉
        AUTOSHOT, DOUBLE, BOLTACTION, MACHINEPISTOL, DUAL, ANTIMATERIEL, SILENCED, MUSKET, LASER
    }

    /**
     * 쏘는 방식: HITSCAN 광선(옛 총 모두), BURST 한 번 당기면 count 발 뒤 ticks 쉼, SPIN 누르는 동안 ticks 에 걸쳐 돌아 빨라짐(effect 이상 돌아야 쏨),
     * CHARGE ticks 모아 쏘는 관통 광선, FLAME 불길 원뿔(cone 도, 불붙음 ticks, 불 피해 배율 effect), FREEZE 냉기 원뿔(cone, 느려짐 ticks, 빠르기
     * 배율 effect, count 틱 넘게 맞으면 얼어붙음), CHAIN 맞은 데서 radius 안으로 count 번 번지는 번개(번질 때마다 effect 배), LOB 포물선 유탄
     * (speed 블록/틱, gravity, 폭발 반경 radius, 가장자리 피해 몫 effect), ROCKET 곧은 로켓(같은 칸), BOLT 관통 화살(맞을 때마다 effect 블록 밀침),
     * BEAM 광선총(광선 판정은 HITSCAN 과 같고 곡선 탄 줄 대신 빛줄기를 그림)
     */
    public enum Fire {
        HITSCAN, BURST, SPIN, CHARGE, FLAME, FREEZE, CHAIN, LOB, ROCKET, BOLT, BEAM
    }

    /** 종류마다 쏘는 방식과 그 값(쓰지 않는 칸은 0, 뜻은 {@link Fire}) */
    public record Mode(Fire fire, int count, int ticks, double cone, double radius, double speed, double gravity, double effect) {
        static final Mode HITSCAN = new Mode(Fire.HITSCAN, 0, 0, 0, 0, 0, 0, 0);
    }

    public static final Map<Kind, Mode> MODES = new EnumMap<>(Kind.class);

    static {
        MODES.put(Kind.BURST, new Mode(Fire.BURST, 3, 7, 0, 0, 0, 0, 0));
        MODES.put(Kind.MINIGUN, new Mode(Fire.SPIN, 0, 24, 0, 0, 0, 0, 0.3));
        MODES.put(Kind.RAILGUN, new Mode(Fire.CHARGE, 0, 16, 0, 0, 0, 0, 0));
        MODES.put(Kind.FLAME, new Mode(Fire.FLAME, 0, 60, 13, 0, 0, 0, 2.4));
        MODES.put(Kind.FREEZE, new Mode(Fire.FREEZE, 30, 40, 12, 0, 0, 0, 0.45));
        MODES.put(Kind.TESLA, new Mode(Fire.CHAIN, 4, 0, 0, 6.5, 0, 0, 0.75));
        MODES.put(Kind.LAUNCHER, new Mode(Fire.LOB, 0, 70, 0, 3.4, 1.35, 0.05, 0.35));
        MODES.put(Kind.ROCKET, new Mode(Fire.ROCKET, 0, 80, 0, 4.6, 1.9, 0.0, 0.3));
        MODES.put(Kind.BOW, new Mode(Fire.BOLT, 0, 0, 0, 0, 0, 0, 0.45));
        // 새 종류(2026-11-12 wpn_max): 쌍발은 한 번 당기면 두 방(양 총열) 뒤 쉼, 대물 저격총과 머스킷은 꿰뚫고 크게 밀침, 광선총은 빛줄기
        MODES.put(Kind.DOUBLE, new Mode(Fire.BURST, 2, 20, 0, 0, 0, 0, 0));
        MODES.put(Kind.ANTIMATERIEL, new Mode(Fire.BOLT, 0, 0, 0, 0, 0, 0, 0.9));
        MODES.put(Kind.MUSKET, new Mode(Fire.BOLT, 0, 0, 0, 0, 0, 0, 0.5));
        MODES.put(Kind.LASER, new Mode(Fire.BEAM, 0, 0, 0, 0, 0, 0, 0));
    }

    /** 그 종류가 쏘는 방식(없으면 광선) */
    public static Mode mode(Kind k) {
        return k == null ? Mode.HITSCAN : MODES.getOrDefault(k, Mode.HITSCAN);
    }

    /**
     * 반동 모양: 연사 n 발째 위 반동 배율 1 + climb x min(n-1, climbMax), 옆 무늬 진폭(0..1)과 위상, 놓은 뒤 되돌리는 몫, 쏠 때 FOV 배율
     */
    public record Recoil(double climb, int climbMax, double pattern, double phase, double recover, double fov) {
    }

    /**
     * 총 하나. fire, pitch = 바닐라 대체 총소리와 그 높이(팩에 gun.&lt;총&gt;.fire 가 있으면 그 사건을 흔들림만으로 울려 높이는 sounds.json 이 정함),
     * soundRange = 총소리가 들리는 거리(바닐라 대체 소리는 이 거리만큼 음량을 키움, Props.soundAround), damage = 한 발(산탄은 한 알) 피해.
     * 단위: spread, bloom, move, up, side 는 도, reload, draw 는 틱(산탄총 reload 는 한 발 넣는 틱), range 는 블록, reserve -1 은 무한
     */
    public record Spec(Kind kind, String key, String name, int mag, int reserve, double rps, double spread, double bloom, double bloomMax,
        double move, double up, double side, int reload, int pellets, double range, int pierce, boolean shells, String vanillaModel,
        Sound fire, float pitch, int draw, double soundRange, Recoil recoil, double damage, String model, String snd) {

        /** 종류 바탕 값(모델 = 종류 키, 소리 사건 = 종류 사건) */
        public Spec(Kind kind, String key, String name, int mag, int reserve, double rps, double spread, double bloom, double bloomMax, double move,
            double up, double side, int reload, int pellets, double range, int pierce, boolean shells, String vanillaModel, Sound fire, float pitch,
            int draw, double soundRange, Recoil recoil, double damage) {
            this(kind, key, name, mag, reserve, rps, spread, bloom, bloomMax, move, up, side, reload, pellets, range, pierce, shells, vanillaModel, fire,
                pitch, draw, soundRange, recoil, damage, key, null);
        }

        /** 팩 아이템 모델 키(gave:&lt;모델&gt;, Models 표). 종류 바탕 값이면 종류 키 */
        public String model() {
            return model;
        }

        /** 이 총의 사격 소리 사건(모델 소리가 있으면 그것, 없으면 종류 사건) */
        public String fireEvent() {
            return snd != null ? snd : "gun." + key + ".fire";
        }

        /** 모델 하나의 성능 배율을 곱한 총(피해, 연사, 탄창과 예비탄, 재장전 시간, 퍼짐, 사거리, 관통 더하기. 반동 모양과 종류 키, 이름은 그대로) */
        public Spec forModel(Models.Model m) {
            if (m == null) return this;
            return new Spec(kind, key, name, Math.max(1, (int) Math.round(mag * m.mag())),
                reserve < 0 ? -1 : Math.max(0, (int) Math.round(reserve * m.mag())), Math.max(0.1, rps * m.rps()), spread * m.spread(), bloom * m.spread(),
                bloomMax * m.spread(), move * m.spread(), up, side, Math.max(1, (int) Math.round(reload * m.reload())), pellets,
                Math.max(1, range * m.range()), Math.max(0, pierce + m.pierce()), shells, vanillaModel, fire, pitch, draw, soundRange, recoil,
                damage * m.dmg(), m.key(), m.sound());
        }

        /** 배율을 건 총(키, 이름, 소리, 모델은 그대로) */
        public Spec with(GunMods m) {
            if (m == null || m.equals(GunMods.NONE)) return this;
            double sp = Math.max(0, m.spread()), rc = Math.max(0, m.recoil());
            return new Spec(kind, key, name, Math.max(1, (int) Math.round(mag * m.mag())),
                reserve < 0 ? -1 : Math.max(0, (int) Math.round(reserve * m.reserve())), Math.max(0.1, rps * m.rate()), spread * sp, bloom * sp,
                bloomMax * sp, move * sp, up * rc, side * rc, Math.max(1, (int) Math.round(reload * reloadMul(m))), Math.max(1, pellets + m.pellets()),
                Math.max(1, range * m.range()), Math.max(0, pierce + m.pierce()), shells, vanillaModel, fire, pitch, draw, soundRange, recoil,
                damage * Math.max(0, m.damage()), model, snd);
        }
    }

    /** 재장전 시간 배율(박자 소리 시각에도 같은 값을 씀): 0.2..5 로 자름 */
    static double reloadMul(GunMods m) {
        return m == null ? 1 : Math.max(0.2, Math.min(5, m.reload()));
    }

    public static final Map<Kind, Spec> SPECS = new EnumMap<>(Kind.class);

    static {
        add(new Spec(Kind.PISTOL, "pistol", "권총", 15, -1, 5, 1.2, 0.5, 3.0, 1.5, 1.0, 0.5, 28, 1, 80, 0, false, "flint_and_steel",
            Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.7f, 8, 64, new Recoil(0.05, 4, 0.3, 0.0, 0.7, 0.975), 14));
        add(new Spec(Kind.SMG, "smg", "기관단총", 40, 240, 13, 1.6, 0.25, 3.5, 1.2, 0.35, 0.3, 36, 1, 70, 0, false, "iron_hoe",
            Sound.ENTITY_FIREWORK_ROCKET_BLAST, 2.0f, 9, 80, new Recoil(0.07, 10, 0.5, 1.3, 0.6, 0.985), 8));
        add(new Spec(Kind.SHOTGUN, "shotgun", "산탄총", 8, 48, 1.2, 5.0, 0, 0, 1.0, 4.0, 1.0, 9, 9, 25, 0, true, "iron_shovel",
            Sound.ENTITY_GENERIC_EXPLODE, 1.8f, 10, 80, new Recoil(0, 1, 0, 0, 0.55, 0.95), 7));
        add(new Spec(Kind.RIFLE, "rifle", "소총", 30, 180, 10, 1.0, 0.3, 3.0, 1.6, 0.6, 0.35, 44, 1, 120, 0, false, "crossbow",
            Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.4f, 10, 96, new Recoil(0.06, 10, 0.6, 0.4, 0.6, 0.98), 14));
        add(new Spec(Kind.SNIPER, "sniper", "저격소총", 10, 50, 1.6, 0.1, 1.5, 3.0, 4.0, 3.0, 0.8, 52, 1, 160, 4, false, "spyglass",
            Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 0.7f, 12, 128, new Recoil(0, 1, 0, 0, 0.5, 0.94), 70));
        // ---- 2026-11-12 무기 랜덤 상자: 쏘는 방식은 MODES. 피해는 한 번 맞힘(불길, 냉기는 한 번 쏠 때 원뿔 안 모두, 폭발은 가운데) 기준.
        // 게이브 수와 체력이 크게 늘 것을 보고(bal12) 단일 대상 초당 피해를 소총(140)과 비슷하거나 조금 위로, 범위, 관통, 묶음 무기는 그만큼 낮춤
        // 화염방사기: 연료 100(초당 16 칸), 9 블록 원뿔, 맞을 때 5 + 불붙음 3초(초당 12)
        add(new Spec(Kind.FLAME, "flamethrower", "화염방사기", 100, 300, 16, 0, 0, 0, 0, 0.12, 0.08, 70, 1, 9, 0, false, "blaze_rod",
            Sound.ITEM_FIRECHARGE_USE, 1.2f, 12, 48, new Recoil(0, 1, 0.2, 0, 0.8, 0.995), 5));
        // 유탄발사기: 6 발, 포물선, 반경 3.4 폭발 가운데 95
        add(new Spec(Kind.LAUNCHER, "launcher", "유탄발사기", 6, 30, 1.6, 0.8, 0, 0, 1.0, 3.5, 0.8, 60, 1, 60, 0, false, "crossbow",
            Sound.ENTITY_GENERIC_EXPLODE, 1.8f, 12, 64, new Recoil(0, 1, 0, 0, 0.6, 0.95), 95));
        // 로켓 발사기: 1 발, 곧게 날아가 반경 4.6 폭발 가운데 260
        add(new Spec(Kind.ROCKET, "rocket", "로켓 발사기", 1, 12, 1.0, 0.4, 0, 0, 1.0, 4.5, 1.0, 56, 1, 120, 0, false, "spyglass",
            Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.5f, 14, 96, new Recoil(0, 1, 0, 0, 0.5, 0.93), 260));
        // 미니건: 200 발, 다 돌면 초당 22 발
        add(new Spec(Kind.MINIGUN, "minigun", "미니건", 200, 600, 22, 2.2, 0.08, 3.5, 1.4, 0.22, 0.25, 110, 1, 70, 0, false, "iron_hoe",
            Sound.ENTITY_FIREWORK_ROCKET_BLAST, 2.2f, 16, 96, new Recoil(0.02, 10, 0.6, 0.7, 0.6, 0.99), 9));
        // 경기관총: 100 발 탄통, 관통 1
        add(new Spec(Kind.LMG, "lmg", "경기관총", 100, 300, 12, 1.5, 0.22, 3.2, 1.6, 0.42, 0.3, 90, 1, 100, 1, false, "crossbow",
            Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.3f, 14, 96, new Recoil(0.06, 10, 0.6, 0.9, 0.6, 0.982), 13));
        // 리볼버: 6 발, 한 발 62, 관통 2
        add(new Spec(Kind.REVOLVER, "revolver", "리볼버", 6, 48, 2.6, 0.5, 1.2, 3.0, 1.4, 2.4, 0.7, 44, 1, 90, 2, false, "flint_and_steel",
            Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.2f, 9, 80, new Recoil(0, 1, 0.3, 0, 0.6, 0.965), 62));
        // 점사 소총: 3 점사(점사 안 초당 14 발, 점사 뒤 7 틱 쉼), 곧음
        add(new Spec(Kind.BURST, "burst", "점사 소총", 36, 216, 14, 0.6, 0.15, 2.0, 1.4, 0.45, 0.25, 40, 1, 110, 0, false, "crossbow",
            Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.5f, 10, 96, new Recoil(0.05, 6, 0.4, 0.2, 0.65, 0.98), 24));
        // 지정사수 소총: 15 발, 한 발 48, 관통 2, 웅크리면 조준경
        add(new Spec(Kind.DMR, "dmr", "지정사수 소총", 15, 90, 3.5, 0.25, 0.9, 2.5, 2.4, 1.6, 0.5, 50, 1, 140, 2, false, "spyglass",
            Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.0f, 11, 112, new Recoil(0, 1, 0.3, 0, 0.55, 0.96), 48));
        // 레일건: 0.8초 모아 블록 앞까지 모두 꿰뚫는 광선 230
        add(new Spec(Kind.RAILGUN, "railgun", "레일건", 4, 24, 0.9, 0, 0, 0, 3.0, 3.6, 0.8, 70, 1, 160, 99, false, "spyglass",
            Sound.ENTITY_GUARDIAN_ATTACK, 1.8f, 14, 128, new Recoil(0, 1, 0, 0, 0.5, 0.93), 230));
        // 전기총: 24 블록 안 첫 대상 30 + 6.5 블록 안으로 네 번 번짐(번질 때마다 0.75 배)
        add(new Spec(Kind.TESLA, "tesla", "전기총", 40, 200, 6, 0.4, 0, 0, 1.0, 0.5, 0.2, 50, 1, 24, 0, false, "lightning_rod",
            Sound.ENTITY_BREEZE_SHOOT, 1.6f, 11, 64, new Recoil(0, 1, 0.3, 0, 0.7, 0.99), 30));
        // 냉동총: 10 블록 원뿔 냉기, 맞을 때 4 + 2초 느려짐(빠르기 0.45 배), 1.5초 넘게 맞으면 얼어붙음
        add(new Spec(Kind.FREEZE, "freeze", "냉동총", 120, 360, 14, 0, 0, 0, 0, 0.1, 0.06, 70, 1, 10, 0, false, "prismarine_shard",
            Sound.BLOCK_POWDER_SNOW_BREAK, 0.8f, 12, 40, new Recoil(0, 1, 0.2, 0, 0.8, 0.995), 4));
        // 복합궁: 한 발씩 시위에 걸어(14 틱) 85, 여덟 마리까지 꿰뚫고 밀침
        add(new Spec(Kind.BOW, "bow", "복합궁", 1, 40, 1.5, 0.15, 0, 0, 1.2, 1.2, 0.3, 14, 1, 90, 8, false, "bow",
            Sound.ENTITY_ARROW_SHOOT, 1.0f, 10, 32, new Recoil(0, 1, 0.2, 0, 0.6, 0.97), 85));
        // ---- 2026-11-12 wpn_max 새 종류 아홉(모델 하나하나의 배율은 Models). 화력은 소총(초당 140) 둘레, 범위, 관통 무기는 그만큼 낮춤
        // 자동 산탄총: 20 발 탄창, 초당 4.5 번 7 알(알당 4.2): 연사로 가까이서 가장 센 대신 멀면 흩어짐
        add(new Spec(Kind.AUTOSHOT, "autoshotgun", "자동 산탄총", 20, 100, 4.5, 5.5, 0.3, 3.0, 1.2, 1.4, 0.5, 70, 7, 25, 0, false, "iron_shovel",
            Sound.ENTITY_GENERIC_EXPLODE, 1.9f, 12, 80, new Recoil(0.05, 6, 0.4, 0.3, 0.6, 0.97), 4.2));
        // 쌍발 산탄총: 두 발이 한 번에(BURST 2, 연속 초당 16), 쏜 뒤 1초 쉬고 두 발을 다시 채워야 함(탄창 2), 한 방 9 알 x 7.5
        add(new Spec(Kind.DOUBLE, "doublebarrel", "쌍발 산탄총", 2, 24, 16, 5.2, 0, 0, 1.2, 3.5, 1.0, 56, 9, 25, 0, false, "iron_shovel",
            Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 11, 80, new Recoil(0, 1, 0, 0, 0.5, 0.95), 7.5));
        // 볼트액션 소총: 5 발, 한 발 110, 관통 5, 쏘면 노리쇠 당김, 웅크리면 조준경, 치명 +10%
        add(new Spec(Kind.BOLTACTION, "boltaction", "볼트액션 소총", 5, 40, 1.2, 0.05, 1.5, 3.0, 4.0, 3.2, 0.6, 60, 1, 180, 5, false, "spyglass",
            Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 0.6f, 14, 112, new Recoil(0, 1, 0, 0, 0.5, 0.93), 110));
        // 기관권총: 30 발, 초당 17 발, 한 발 7: 가까이서 빠름
        add(new Spec(Kind.MACHINEPISTOL, "machinepistol", "기관권총", 30, 210, 17, 2.4, 0.35, 4.5, 1.8, 0.4, 0.35, 32, 1, 45, 0, false, "iron_hoe",
            Sound.ENTITY_FIREWORK_ROCKET_BLAST, 2.2f, 8, 64, new Recoil(0.08, 12, 0.6, 1.1, 0.55, 0.985), 7));
        // 쌍권총: 한 번에 두 발(양손 한 발씩, 서로 다른 퍼짐), 초당 8 번, 36 발
        add(new Spec(Kind.DUAL, "dualpistol", "쌍권총", 36, 216, 8, 2.0, 0.3, 3.5, 1.5, 0.7, 0.5, 40, 2, 55, 0, false, "flint_and_steel",
            Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.7f, 9, 64, new Recoil(0.04, 6, 0.5, 0.0, 0.7, 0.98), 8));
        // 대물 저격총: 5 발, 한 발 190, 줄 선 열두 마리까지 꿰뚫고 크게 밀침, 웅크리면 조준경
        add(new Spec(Kind.ANTIMATERIEL, "antimateriel", "대물 저격총", 5, 30, 0.9, 0.1, 2.0, 3.5, 5.0, 4.5, 1.0, 90, 1, 200, 12, false, "spyglass",
            Sound.ENTITY_GENERIC_EXPLODE, 0.9f, 16, 144, new Recoil(0, 1, 0, 0, 0.5, 0.92), 190));
        // 소음기 총: 32 발, 초당 12 발 9, 총소리가 20 블록 안에만 들리고 총구 불꽃 없음, 치명 +10%
        add(new Spec(Kind.SILENCED, "silenced", "소음기 총", 32, 224, 12, 1.2, 0.25, 3.0, 1.2, 0.32, 0.25, 36, 1, 70, 0, false, "iron_hoe",
            Sound.ENTITY_ARROW_SHOOT, 1.6f, 9, 20, new Recoil(0.04, 8, 0.4, 0.9, 0.7, 0.99), 9));
        // 머스킷: 한 발 200, 줄 선 넷을 꿰뚫고 밀침, 한 발마다 장전(44 틱)
        add(new Spec(Kind.MUSKET, "musket", "머스킷", 1, 30, 1.0, 0.3, 0, 0, 3.0, 4.0, 1.0, 44, 1, 100, 3, false, "crossbow",
            Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 14, 96, new Recoil(0, 1, 0, 0, 0.5, 0.94), 200));
        // 광선총: 50 발, 초당 9 번 13, 관통 3, 반동 거의 없는 빛줄기
        add(new Spec(Kind.LASER, "laser", "광선총", 50, 300, 9, 0.2, 0, 0, 0.6, 0.05, 0.05, 40, 1, 90, 3, false, "lightning_rod",
            Sound.BLOCK_BEACON_POWER_SELECT, 2.0f, 10, 64, new Recoil(0, 1, 0, 0, 0.8, 0.995), 13));
    }

    /** 종류마다 늘 붙는 치명 확률(리볼버, 지정사수 소총) */
    static double baseCrit(Kind k) {
        return k == Kind.REVOLVER || k == Kind.BOLTACTION || k == Kind.SILENCED ? 0.1 : k == Kind.DMR ? 0.05 : 0;
    }

    /** 웅크리면 조준경(시야 배율): 저격총, 지정사수 소총, 레일건. 1 = 없음 */
    static float scope(Kind k) {
        return switch (k) {
            case SNIPER, RAILGUN -> 0.55f;
            case BOLTACTION -> 0.5f;
            case ANTIMATERIEL -> 0.45f;
            case DMR -> 0.7f;
            default -> 1f;
        };
    }

    private static void add(Spec s) {
        SPECS.put(s.kind(), s);
    }

    /** 이 총이 주는 피해 종류(흉내, 배우는 게이브가 읽음) */
    public static DamageType damageType(Kind k) {
        if (k == null) return DamageType.BULLET;
        return switch (k) {
            case SNIPER, RAILGUN, BOLTACTION, ANTIMATERIEL -> DamageType.SNIPER;
            case SHOTGUN, AUTOSHOT, DOUBLE -> DamageType.PELLET;
            case FLAME -> DamageType.FIRE;
            case LAUNCHER, ROCKET -> DamageType.EXPLOSION;
            case TESLA, FREEZE, LASER -> DamageType.OTHER;
            default -> DamageType.BULLET;
        };
    }

    /** 총구 불꽃을 그리는 종류(냉동총, 전기총, 레일건, 활은 그 무기 효과가 따로) */
    static boolean flashes(Kind k) {
        return k != Kind.FREEZE && k != Kind.TESLA && k != Kind.RAILGUN && k != Kind.BOW && k != Kind.LASER && k != Kind.SILENCED;
    }

    /** 거리 감쇠: 사거리 60% 까지 1, 사거리에서 0.5(그 사이 곧게) */
    public static double falloff(double dist, double range) {
        if (range <= 0) return 1;
        double k = (dist / range - 0.6) / 0.4;
        return 1 - 0.5 * Math.max(0, Math.min(1, k));
    }

    // ------------------------------------------------------------------ 재장전 박자

    private static final int NONE = 0, MAG_OUT = 1, MAG_IN = 2;

    /**
     * 재장전 박자 하나: 시작 뒤 at 틱(재장전 배율을 곱함)에 소리(events 중 팩에 있는 첫 것, 없으면 fallback)를 내고 act(탄창 빠짐, 꽂힘)를 보인다.
     * 소리 시각은 파형의 큰 소리(탄창 딸깍) 자리가 탄창이 보이고 사라지는 틱에 오게 앞당겨 둠. emptyOnly = 빈 총 재장전에서만.
     * pitch 는 바닐라 대체 소리 높이(모드 사건은 흔들림만, 높이는 sounds.json)
     */
    private record Cue(int at, int act, boolean emptyOnly, float pitch, Sound fallback, String... events) {
    }

    private static final Map<Kind, List<Cue>> CUES = new EnumMap<>(Kind.class);
    /** 산탄총: 재장전 시작 자세, 끝 펌프(빈 총에서 시작했을 때) 틱(재장전 배율을 곱함) */
    private static final int SHELL_START = 7, SHELL_PUMP = 8;

    static {
        CUES.put(Kind.PISTOL, List.of(new Cue(0, NONE, false, 1f, Sound.BLOCK_IRON_TRAPDOOR_OPEN, "gun.pistol.mag_out"),
            new Cue(5, MAG_OUT, false, 1f, null), new Cue(14, NONE, false, 1f, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, "gun.pistol.mag_in"),
            new Cue(18, MAG_IN, false, 1f, null), new Cue(22, NONE, true, 1f, Sound.BLOCK_PISTON_CONTRACT, "gun.pistol.slide")));
        CUES.put(Kind.SMG, List.of(new Cue(3, NONE, false, 1f, Sound.BLOCK_IRON_TRAPDOOR_OPEN, "gun.smg.mag_out"),
            new Cue(4, MAG_OUT, false, 1f, null), new Cue(22, NONE, false, 1f, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, "gun.smg.mag_in"),
            new Cue(24, MAG_IN, false, 1f, null),
            new Cue(28, NONE, true, 0.85f, Sound.BLOCK_PISTON_CONTRACT, "gun.smg.charge", "gun.pistol.slide")));
        CUES.put(Kind.RIFLE, List.of(new Cue(4, NONE, false, 1f, Sound.BLOCK_IRON_TRAPDOOR_OPEN, "gun.rifle.mag_out"),
            new Cue(5, MAG_OUT, false, 1f, null), new Cue(28, NONE, false, 1f, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, "gun.rifle.mag_in"),
            new Cue(30, MAG_IN, false, 1f, null),
            new Cue(32, NONE, true, 1.1f, Sound.BLOCK_PISTON_CONTRACT, "gun.rifle.charge", "gun.sniper.bolt_release")));
        CUES.put(Kind.SNIPER, List.of(new Cue(0, NONE, true, 1f, Sound.BLOCK_PISTON_EXTEND, "gun.sniper.bolt_pull"),
            new Cue(8, NONE, false, 1f, Sound.BLOCK_IRON_TRAPDOOR_OPEN, "gun.sniper.mag_out"), new Cue(9, MAG_OUT, false, 1f, null),
            new Cue(30, NONE, false, 1f, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, "gun.sniper.mag_in"), new Cue(32, MAG_IN, false, 1f, null),
            new Cue(38, NONE, true, 1f, Sound.BLOCK_PISTON_CONTRACT, "gun.sniper.bolt_release")));
        // 새 무기(2026-11-12): 옛 총의 부품 소리 사건을 그대로 부른다(새 소리 파일 없음). 시각은 그 종류 바탕 재장전 틱에 맞춤
        CUES.put(Kind.LMG, mag(90, "gun.rifle.mag_out", "gun.rifle.mag_in", "gun.rifle.charge"));
        CUES.put(Kind.MINIGUN, mag(110, "gun.rifle.mag_out", "gun.rifle.mag_in", "gun.smg.charge"));
        CUES.put(Kind.BURST, mag(40, "gun.rifle.mag_out", "gun.rifle.mag_in", "gun.rifle.charge"));
        CUES.put(Kind.DMR, mag(50, "gun.sniper.mag_out", "gun.sniper.mag_in", "gun.rifle.charge"));
        CUES.put(Kind.RAILGUN, mag(70, "gun.sniper.mag_out", "gun.sniper.mag_in", "gun.sniper.bolt_release"));
        CUES.put(Kind.TESLA, mag(50, "gun.smg.mag_out", "gun.smg.mag_in", "gun.smg.charge"));
        CUES.put(Kind.FLAME, mag(70, "gun.rifle.mag_out", "gun.rifle.mag_in", null));
        CUES.put(Kind.FREEZE, mag(70, "gun.rifle.mag_out", "gun.rifle.mag_in", null));
        CUES.put(Kind.LAUNCHER, mag(60, "gun.shotgun.reload_start", "gun.shotgun.shell", "gun.shotgun.pump"));
        CUES.put(Kind.ROCKET, mag(56, "gun.sniper.mag_out", "gun.sniper.mag_in", "gun.sniper.bolt_release"));
        CUES.put(Kind.REVOLVER, List.of(new Cue(2, NONE, false, 1.2f, Sound.BLOCK_IRON_TRAPDOOR_OPEN, "gun.pistol.mag_out"), new Cue(4, MAG_OUT, false, 1f, null),
            new Cue(30, NONE, false, 1.1f, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, "gun.pistol.mag_in"), new Cue(32, MAG_IN, false, 1f, null),
            new Cue(38, NONE, false, 1f, Sound.BLOCK_PISTON_CONTRACT, "gun.pistol.slide")));
        CUES.put(Kind.BOW, List.of(new Cue(3, NONE, false, 1f, Sound.ITEM_CROSSBOW_LOADING_MIDDLE), new Cue(11, NONE, false, 1f, Sound.ITEM_CROSSBOW_LOADING_END)));
        // 새 종류(2026-11-12 wpn_max): 옛 부품 소리 사건을 그대로, 시각은 그 종류 바탕 재장전 틱에 맞춤
        CUES.put(Kind.AUTOSHOT, mag(70, "gun.rifle.mag_out", "gun.rifle.mag_in", "gun.rifle.charge"));
        CUES.put(Kind.DOUBLE, mag(56, "gun.shotgun.reload_start", "gun.shotgun.shell", "gun.shotgun.pump"));
        CUES.put(Kind.BOLTACTION, mag(60, "gun.sniper.mag_out", "gun.sniper.mag_in", "gun.sniper.bolt_release"));
        CUES.put(Kind.MACHINEPISTOL, mag(32, "gun.pistol.mag_out", "gun.pistol.mag_in", "gun.pistol.slide"));
        CUES.put(Kind.DUAL, mag(40, "gun.pistol.mag_out", "gun.pistol.mag_in", "gun.pistol.slide"));
        CUES.put(Kind.ANTIMATERIEL, mag(90, "gun.sniper.mag_out", "gun.sniper.mag_in", "gun.sniper.bolt_release"));
        CUES.put(Kind.SILENCED, mag(36, "gun.smg.mag_out", "gun.smg.mag_in", "gun.smg.charge"));
        CUES.put(Kind.MUSKET, List.of(new Cue(8, NONE, false, 1f, Sound.ITEM_CROSSBOW_LOADING_START), new Cue(22, NONE, false, 1f, Sound.ITEM_CROSSBOW_LOADING_MIDDLE),
            new Cue(38, NONE, false, 1f, Sound.ITEM_CROSSBOW_LOADING_END)));
        CUES.put(Kind.LASER, mag(40, "gun.smg.mag_out", "gun.smg.mag_in", null));
    }

    /** 탄창 총 재장전 박자(바탕 재장전 len 틱): 뺌 10%, 꽂음 72%, 빈 총이면 끝에 장전 손잡이(charge 가 null 이면 없음) */
    private static List<Cue> mag(int len, String out, String in, String charge) {
        List<Cue> l = new ArrayList<>(List.of(new Cue(Math.round(len * 0.08f), NONE, false, 1f, Sound.BLOCK_IRON_TRAPDOOR_OPEN, out),
            new Cue(Math.round(len * 0.1f), MAG_OUT, false, 1f, null), new Cue(Math.round(len * 0.66f), NONE, false, 1f, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, in),
            new Cue(Math.round(len * 0.72f), MAG_IN, false, 1f, null)));
        if (charge != null) l.add(new Cue(Math.round(len * 0.82f), NONE, true, 1f, Sound.BLOCK_PISTON_CONTRACT, charge));
        return List.copyOf(l);
    }

    /** 놓은 뒤 반동 되돌림 비율(6 틱) */
    private static final float[] RECOVER = {0.3f, 0.25f, 0.18f, 0.13f, 0.09f, 0.05f};
    /** 쏜 뒤 총구 불꽃 도장을 남겨 두는 틱(셰이더는 2.3 틱까지 그림) */
    private static final int FLASH_KEEP = 4;
    public static final int FX_MUZZLE_FACE = 11, FX_MUZZLE_SIDE = 12, FX_MUZZLE_SIDE_V = 13;
    private static final int[] MUZZLE_KINDS = {FX_MUZZLE_FACE, FX_MUZZLE_SIDE, FX_MUZZLE_SIDE_V};
    /** 총구 불꽃 씨앗의 '1인칭 반동 따라감' 비트(셰이더 zr_kick 의 색 비트 17) */
    private static final int KICK_BIT = 1 << 5;
    /** 핫바 칸: 0 주무기, 1 권총, 2 소모품(총 아님, 다른 담당이 채움) */
    public static final int SLOT_PRIMARY = 0, SLOT_PISTOL = 1, SLOT_ITEM = 2;

    /** 한 사람의 무기 상태 */
    public static final class Arms {
        public Kind primary;
        public final int[] mag = new int[Kind.values().length], res = new int[Kind.values().length];
        public int grenades;
        /** 쏜 발 수, 죽인 수(총과 수류탄, damage 가 true 를 돌려준 것만), 맞힌 알 수 */
        public int shots, kills, hits;
        /** 마지막으로 맞힌, 죽인 판 틱(HUD 적중 표시가 읽음). 없으면 -1 */
        public long hitAt = -1, killAt = -1;
        /** 지금 배율과 그 배율을 건 총(종류 차례) */
        GunMods mods = GunMods.NONE;
        final Spec[] eff = new Spec[Kind.values().length];
        /** 총마다 다음 발 틱(바꿔 들어도 그 총 박자는 그대로: 산탄총을 쏘고 두 번 바꿔 펌프 시간을 건너뛰지 못하게) */
        final double[] next = new double[Kind.values().length];
        double bloom;
        long reloadUntil = -1, lastShot = -99, tapUntil = -1, confirmedAt = -99, probeAt = -1, drawUntil;
        Kind reloading;
        boolean holdNoFire;
        long simUntil = -1;
        double lx, lz, speed;
        // 재장전 진행
        long reloadStart, phaseStart;
        boolean reloadEmpty, magOut;
        /** 이번 재장전의 시간 배율(박자 시각에 곱함, 시작할 때 정함) */
        double reloadMul = 1;
        /** 빈 산탄총을 넣다가 당김: 펌프가 끝나면 그 한 발을 쏜다(탭은 4 틱만 살아 펌프 8 틱보다 짧음) */
        boolean fireAfterPump;
        int cueIdx, shellPhase;
        // 쏜 뒤 펌프, 노리쇠(다 못 하고 바꿨으면 그 총을 다음에 꺼낼 때 함)
        Kind cycleKind;
        long cycleAt;
        int cycleStep;
        final boolean[] cycleOwed = new boolean[Kind.values().length];
        /** 시험: 마지막 쏜 틱 4 개(돌림) */
        final long[] shotLog = {-1, -1, -1, -1};
        int shotLogAt;
        // 반동
        int burst;
        double climb, drift, lastInterval = 4, lastRecover = 0.6;
        float restYaw, restPitch, recYaw, recPitch;
        long restAt = -1;
        int recStep = -1;
        long fovUntil = -1;
        float fovMul = 1, sentWalk = -1;
        // 총구 불꽃(세계 시각), 손 총 표시 상태
        long flashAt = -99999;
        int flashSeed;
        final long[] sentStamp = {-1, -1};
        final boolean[] sentMag = new boolean[2];
        long debugFlashUntil = -1, debugMagUntil = -1, debugKickUntil = -1;
        /** 꺼낸 세계 시각(1인칭 올라오는 모습), 재장전 시작, 재장전 끝(자세 풀기) 세계 시각 */
        long drawAt = -99999, reloadWt = -1, poseEndWt = -99999;
        Kind poseEndKind;
        boolean dryClicked;
        /** 엉킴(JAM, Guns.jam): 끝 틱, 재장전 시간 배율, 헛방 확률, 퍼짐 배율. 주무기 잠금(WEAPONLOCK, Guns.lockPrimary) 끝 틱. 2026-11-12 sp_engine */
        long jamUntil = -1, primaryLockUntil = -1;
        double jamReload = 1, jamMisfire = 0, jamSpread = 1;
        /** 주무기 굴린 총(종류 = primary, 없으면 null), 권총(굴리지 않음, 강화는 됨) */
        public Gun gun;
        public Gun pistol = Gun.basic(Kind.PISTOL);
        /**
         * 소모품 칸(핫바 3, SLOT_ITEM) 종류 키(grenade, flare, molotov, rattle, cleanse, bandage, 없으면 null)와 수.
         * 수류탄은 grenades 를 쓴다(item 이 grenade 면 itemCount 는 안 씀). kr.gave.gun.Supplies 가 칸 아이템을 맞춤
         */
        public String item;
        public int itemCount;
        /** 총마다 치명 확률(Gun 특성 + 사람 배율) */
        final double[] crit = new double[Kind.values().length];
        long gunStamp = Long.MIN_VALUE;
        // ---- 새 쏘는 방식(2026-11-12, Arsenal): 미니건 회전 0..1, 점사 남은 발, 레일건 모으기 시작 틱(-1 아님), 줄기 무기 소리, 불 붙인 틱
        double spin;
        int burstLeft;
        long chargeAt = -1;
        Kind chargeKind;
        long streamSoundAt = -99, streamStartAt = -99, spinSoundAt = -99, streamFxAt = -99;

        Arms() {
            for (Kind k : Kind.values()) eff[k.ordinal()] = SPECS.get(k);
        }

        /** 배율을 건 이 사람의 총 */
        public Spec spec(Kind k) {
            return k == null ? null : eff[k.ordinal()];
        }

        /** 그 종류의 굴린 총: 권총은 pistol, 주무기 종류면 gun, 아니면 null */
        public Gun gun(Kind k) {
            if (k == Kind.PISTOL) return pistol;
            return k != null && k == primary ? gun : null;
        }

        /** 소모품 칸의 수(수류탄은 grenades) */
        public int items() {
            if (item == null) return 0;
            return item.equals("grenade") ? grenades : itemCount;
        }

        /** 지금 배율 */
        public GunMods mods() {
            return mods;
        }

        /** trip_aug: 이어 쏜 발 수(쉬면 0 으로) */
        public int streak() {
            return burst;
        }

        /** trip_aug: 종류 배율 도장(GunHooks.modsStamp) */
        long kindStamp = Long.MIN_VALUE;
    }

    private static final NamespacedKey RANGE_ENTITY = new NamespacedKey(Props.NS, "gun_entity_range");
    private final GunHost host;
    private final Random rnd = new Random();
    private final Map<UUID, Arms> arms = new HashMap<>();
    private final Map<UUID, Long> whizAt = new HashMap<>();
    /** Grenades 가 만들어질 때 이어 준다(시험 훅, 연출). 없으면 연출은 스스로 만든 것을 쓴다 */
    private Grenades grenades;
    private ShotFx fx;
    private boolean ownFx;
    /** tick() 이 마지막으로 돈 서버 틱, 그 뒤 한 번 정리했는지 */
    private long tickedAt = -1000;
    private boolean settled = true;
    /** 시험: 이 판 틱까지 tick() 을 건너뜀(판 끝처럼 Game 이 tick() 을 안 부르는 때를 흉내) */
    private long pauseUntil = -1;
    /** 증강, 특성, 아군 사격 연결(Augments 가 생성자에서 단다) */
    public GunHooks hooks = GunHooks.NONE;
    /** trip_aug: 지금 맞힘의 문맥(hooks.hitMul, onHit 가 읽음): 한 줄기에서 몇 번째로 맞았나(0 부터), 이번 맞힘이 치명이었나 */
    public int hitIndex;
    public boolean critNow;
    /** 판 중에 나간 사람의 무기 상태(같은 판에 돌아오면 equip 이 되살림, clearAll 이 비움) */
    private final Map<UUID, Arms> parked = new HashMap<>();

    /**
     * 탄이 동료 몸 상자에 닿았을 때 종이 먼저 받는 갈고리. from 에서 dir(단위)로 dist 만큼 가서 victim 몸 상자 겉면에 닿음.
     * true 면 그 탄은 거기서 멈추고(종이 처리) 아군 알림은 없다. 예: 감염자 등 막대기를 겨눠 쏘기(막대기만 맞음)
     */
    @FunctionalInterface
    public interface AllyShot {
        boolean take(Player shooter, Player victim, Vector from, Vector dir, double dist, Spec spec);
    }

    private final List<AllyShot> allyShots = new ArrayList<>();

    /** 아군 몸에 닿은 탄 갈고리를 단다(종 담당, 한 번만 달 것. 판이 끝나도 남음) */
    public void addAllyShot(AllyShot f) {
        if (f != null && !allyShots.contains(f)) allyShots.add(f);
    }

    public void removeAllyShot(AllyShot f) {
        allyShots.remove(f);
    }

    /** 화염탄 불붙음 하나(대상마다 하나, 다시 맞으면 시간만 새로) */
    private static final class Burn {
        Player by;
        Gun gun;
        double dps;
        long until, next;
    }

    private final Map<Object, Burn> burns = new HashMap<>();
    /** 폭발탄 연출을 사람마다 3 틱에 한 번만(피해는 늘) */
    private final Map<UUID, Long> popAt = new HashMap<>();

    /** 한 발(산탄 알 모두)의 특성 기록: 폭발탄, 연쇄는 한 발에 한 번, 아군 사격 알림도 한 사람에 한 번 */
    private static final class Shot {
        boolean boom, chain;
        final Set<Player> allies = new HashSet<>();
    }

    /** 총 말고 다른 쏘는 방식(불길, 냉기, 번개, 레일 광선, 유탄, 로켓)과 날아가는 탄, 느려짐 */
    final Arsenal arsenal;

    public Guns(GunHost host) {
        this.host = host;
        this.arsenal = new Arsenal(this, host);
        // 대기실처럼 Game 이 tick() 을 부르지 않으면 재장전 자세, 탄창 뺀 모델, 쏠 때 시야 킥이 그대로 남는다: 한 번 정리
        Bukkit.getScheduler().runTaskTimer(host.plugin(), this::watch, 1, 1);
    }

    /** Grenades 생성자가 부른다: 시험 훅을 넘길 곳과 연출 */
    void link(Grenades g, ShotFx f) {
        this.grenades = g;
        if (f != null) {
            if (ownFx && fx != null) fx.clear();
            this.fx = f;
            this.ownFx = false;
        }
    }

    /** 연출(Grenades 가 이어 준 판의 것, 없으면 스스로 만들어 tick, clear 도 직접 함) */
    ShotFx fx() {
        if (fx == null) {
            fx = new ShotFx(host);
            ownFx = true;
        }
        return fx;
    }

    /** tick() 이 두 틱 넘게 안 돌았음(재장전 등이 나아가지 못함) */
    private boolean stale() {
        return Bukkit.getCurrentTick() - tickedAt >= 2;
    }

    private void watch() {
        if (settled || !stale()) return;
        settled = true;
        settle();
    }

    /**
     * 손 총을 쉬는 모습으로: 재장전, 쏜 뒤 동작, 반동 되돌림을 끝내고 손 총 도장(몸짓, 탄창 빠짐, 불꽃)을 지우고 시야 배율을 원래대로.
     * tick() 이 멈추면 다음 틱에 저절로 부른다. Game.gameOver 에서 바로 불러도 된다(여러 번 불러도 같음)
     */
    public void settle() {
        long now = host.now();
        for (Map.Entry<UUID, Arms> e : arms.entrySet()) {
            Arms a = e.getValue();
            a.reloading = null;
            a.reloadUntil = -1;
            a.magOut = false;
            a.fireAfterPump = false;
            a.poseEndKind = null;
            a.cycleKind = null;
            a.simUntil = a.tapUntil = -1;
            a.debugFlashUntil = a.debugMagUntil = a.debugKickUntil = -1;
            a.flashAt = a.drawAt = -99999;
            a.restAt = -1;
            a.recStep = -1;
            a.burst = 0;
            a.climb = a.drift = 0;
            a.fovUntil = -1;
            a.spin = 0;
            a.burstLeft = 0;
            a.chargeAt = -1;
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            fov(p, a, 1f);
            visual(p, a, now);
        }
    }

    public Arms arms(Player p) {
        return p == null ? null : arms.get(p.getUniqueId());
    }

    /**
     * 나감(Gave.onQuit): 시야 걷기 속도를 되돌리고 무기 상태를 뺀다. 판 중(host.running())이면 버리지 않고 맡아 두어 같은 판에 다시 들어와
     * equip 을 부르면 굴린 총, 강화, 탄, 소모품 칸이 그대로 돌아온다
     */
    public void forget(UUID id) {
        Arms a = drop(id);
        if (a != null && host.running()) {
            quiet(a);
            parked.put(id, a);
        }
    }

    /** 판 중에 나가서 맡아 둔 무기 상태가 있나 */
    public boolean parked(UUID id) {
        return parked.containsKey(id);
    }

    private Arms drop(UUID id) {
        Player p = Bukkit.getPlayer(id);
        // 칸 옮김 중이면 아이템을 원래 칸으로(나갈 때 저장되는 인벤토리, 대기실 인벤토리가 원래대로)
        if (p != null) unstow(p);
        aside.remove(id);
        Arms a = arms.remove(id);
        whizAt.remove(id);
        if (a != null && p != null && a.sentWalk >= 0) sendWalk(p, p.getWalkSpeed() / 2f);
        return a;
    }

    /** 맡아 두거나 되살릴 때: 재장전, 쏜 뒤 동작, 반동, 시야, 손 총 도장 같은 지나가는 상태를 지움(탄, 총, 소모품은 그대로) */
    private static void quiet(Arms a) {
        a.reloading = null;
        a.reloadUntil = -1;
        a.magOut = false;
        a.fireAfterPump = false;
        a.poseEndKind = null;
        a.cycleKind = null;
        a.simUntil = a.tapUntil = a.probeAt = -1;
        a.confirmedAt = -99;
        a.holdNoFire = false;
        a.dryClicked = false;
        a.debugFlashUntil = a.debugMagUntil = a.debugKickUntil = -1;
        a.flashAt = a.drawAt = -99999;
        a.restAt = -1;
        a.restYaw = a.restPitch = 0;
        a.recStep = -1;
        a.burst = 0;
        a.climb = a.drift = a.bloom = 0;
        a.fovUntil = -1;
        a.fovMul = 1;
        a.sentWalk = -1;
        a.sentStamp[0] = a.sentStamp[1] = -1;
        a.sentMag[0] = a.sentMag[1] = false;
        a.gunStamp = Long.MIN_VALUE;
        a.spin = 0;
        a.burstLeft = 0;
        a.chargeAt = -1;
        a.chargeKind = null;
    }

    public void clearAll() {
        for (UUID id : new ArrayList<>(arms.keySet())) drop(id);
        for (UUID id : new ArrayList<>(aside.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) unstow(p);
        }
        aside.clear();
        arms.clear();
        parked.clear();
        whizAt.clear();
        burns.clear();
        popAt.clear();
        arsenal.clear();
        if (ownFx && fx != null) fx.clear();
    }

    private long worldTime() {
        return host.worldTime();
    }

    // ------------------------------------------------------------------ 배율

    /** host.mods(p) 나 굴린 총(바꿈, 강화)이 바뀌었으면 이 사람의 총(eff)을 다시 만든다 */
    private void refreshMods(Player p, Arms a) {
        GunMods m = host.mods(p);
        if (m == null) m = GunMods.NONE;
        long stamp = (a.gun == null ? 0 : a.gun.id * 1000003L + a.gun.ver()) * 31 + a.pistol.id * 7919 + a.pistol.ver();
        long ks = hooks.modsStamp(p);
        if (m.equals(a.mods) && stamp == a.gunStamp && ks == a.kindStamp) return;
        a.mods = m;
        a.gunStamp = stamp;
        a.kindStamp = ks;
        for (Kind k : Kind.values()) {
            Gun g = a.gun(k);
            // trip_aug: 종류마다 증강 배율이 다를 수 있음
            GunMods mk = hooks.kindMods(p, k, m);
            if (mk == null) mk = m;
            a.eff[k.ordinal()] = effective(k, g, mk);
            a.crit[k.ordinal()] = Math.min(1, mk.crit() + (g == null ? 0 : g.mods().crit()) + baseCrit(k));
        }
    }

    /**
     * 유효 총 = 바탕 Spec x 굴린 총 배율 x 사람 배율. 산탄 알 더하기는 산탄총(바탕 알 2 이상)에만 건다(소총이 세 알 쏘지 않게)
     */
    public static Spec effective(Kind k, Gun g, GunMods m) {
        Spec base = SPECS.get(k);
        if (g != null) base = base.forModel(Models.of(g.model));
        GunMods all = g == null ? (m == null ? GunMods.NONE : m) : m == null ? g.mods() : g.mods().times(m);
        // 산탄 알 더하기는 산탄류(바탕 알 2 이상)에만: 쌍권총의 두 발은 알이 아니라 양손이라 더하지 않음
        if ((base.pellets() <= 1 || k == Kind.DUAL) && all.pellets() != 0) all = all.withPellets(0);
        return base.with(all);
    }

    /** 이 사람이 그 총으로 쏠 때 치명 확률(Gun 특성 + 사람 배율) */
    public double crit(Player p, Kind k) {
        Arms a = arms(p);
        return a == null || k == null ? 0 : a.crit[k.ordinal()];
    }

    /** 굴린 총이 바뀌었으면 지금 다시 만든다(강화 직후 HUD, 상점이 새 값을 읽게) */
    public void refresh(Player p) {
        Arms a = arms(p);
        if (a != null) refreshMods(p, a);
    }

    /** 이 사람의 배율을 건 총(배율은 지난 틱에 읽은 것) */
    public Spec spec(Player p, Kind k) {
        Arms a = arms(p);
        return a == null ? SPECS.get(k) : a.spec(k);
    }

    // ------------------------------------------------------------------ 지급

    /**
     * 판에 들 때: 무기 상태를 새로 만들고 인벤토리를 비운 뒤 권총(칸 1)을 쥐여 준다. 소모품은 이 뒤에 칸 2 에 넣을 것.
     * 같은 판에 나갔다 돌아온 사람(맡아 둔 상태가 있고 판이 돌고 있음)이면 그 상태를 되살려 총 칸을 다시 만든다(소모품 칸은 Supplies 가 맞춤)
     */
    public void equip(Player p) {
        // 인벤토리를 새로 만드니 칸 옮김(갈림길 투표)도 끝
        aside.remove(p.getUniqueId());
        Arms old = arms.get(p.getUniqueId());
        if (old != null && old.sentWalk >= 0) sendWalk(p, p.getWalkSpeed() / 2f);
        Arms back = parked.remove(p.getUniqueId());
        if (back != null && host.running()) {
            restore(p, back);
            return;
        }
        Arms a = new Arms();
        arms.put(p.getUniqueId(), a);
        refreshMods(p, a);
        a.mag[Kind.PISTOL.ordinal()] = a.spec(Kind.PISTOL).mag();
        p.getInventory().clear();
        p.getInventory().setItem(SLOT_PISTOL, item(SPECS.get(Kind.PISTOL)));
        p.getInventory().setHeldItemSlot(SLOT_PISTOL);
        a.drawAt = worldTime();
    }

    /** 맡아 둔 상태를 되살림: 인벤토리를 비우고 권총, 주무기 칸을 다시 만들어 주무기(없으면 권총)를 듦 */
    private void restore(Player p, Arms a) {
        quiet(a);
        aside.remove(p.getUniqueId());
        arms.put(p.getUniqueId(), a);
        refreshMods(p, a);
        p.getInventory().clear();
        p.getInventory().setItem(SLOT_PISTOL, item(SPECS.get(Kind.PISTOL)));
        if (a.primary != null) p.getInventory().setItem(SLOT_PRIMARY, item(a.spec(a.primary)));
        Kind k = a.primary != null ? a.primary : Kind.PISTOL;
        p.getInventory().setHeldItemSlot(a.primary != null ? SLOT_PRIMARY : SLOT_PISTOL);
        a.drawUntil = host.now() + a.spec(k).draw();
        a.drawAt = worldTime();
    }

    /** 주무기(칸 0)를 k 로(굴리지 않은 기본 총): 탄창, 예비탄 가득, 바로 꺼내 듦. 권총은 칸 1 에 늘 있어 무시 */
    public void givePrimary(Player p, Kind k) {
        if (k == null || k == Kind.PISTOL) return;
        givePrimary(p, Gun.basic(k));
    }

    /** 주무기(칸 0)를 굴린 총 g 로: 탄창, 예비탄 가득, 바로 꺼내 듦(전에 든 주무기는 버림). 권총 종류면 무시 */
    public void givePrimary(Player p, Gun g) {
        Arms a = arms(p);
        if (a == null || g == null || g.kind == Kind.PISTOL) return;
        Kind k = g.kind;
        a.gun = g;
        a.primary = k;
        refreshMods(p, a);
        Spec s = a.spec(k);
        a.mag[k.ordinal()] = s.mag();
        a.res[k.ordinal()] = s.reserve();
        cancelReload(a);
        a.cycleKind = null;
        p.getInventory().setItem(at(p, SLOT_PRIMARY), item(s));
        a.sentStamp[0] = -1;
        a.sentMag[0] = false;
        hold(p, SLOT_PRIMARY);
        a.drawUntil = host.now() + s.draw();
        a.drawAt = worldTime();
        a.next[k.ordinal()] = host.now();
        a.cycleOwed[k.ordinal()] = false;
        Props.soundAround(p.getLocation(), "gun." + s.key() + ".draw", Sound.ITEM_ARMOR_EQUIP_IRON, 0.8f, 1f, 1f, 12, -1);
    }

    /** 탄약: 가진 주무기 예비탄 가득. 채운 게 있으면 true */
    public boolean refill(Player p) {
        Arms a = arms(p);
        if (a == null || a.primary == null) return false;
        Spec s = a.spec(a.primary);
        if (a.res[a.primary.ordinal()] >= s.reserve()) return false;
        a.res[a.primary.ordinal()] = s.reserve();
        return true;
    }

    /** 이미 든 주무기 k 를 다시 삼: 탄창과 예비탄 가득. 채운 게 있으면 true */
    public boolean refillFull(Player p, Kind k) {
        Arms a = arms(p);
        if (a == null || a.primary != k) return false;
        Spec s = a.spec(k);
        int i = k.ordinal();
        if (a.mag[i] >= s.mag() && a.res[i] >= s.reserve()) return false;
        a.mag[i] = s.mag();
        a.res[i] = s.reserve();
        if (a.reloading == k) cancelReload(a);
        return true;
    }

    /**
     * 탄약 채우기(상점, 진짜 보급 상자): 가진 모든 총(주무기와 권총)의 장전된 탄창과 예비탄을 가득(2026-11-12 사용자 '총알 사면 장전된 것도 꽉').
     * 그 총을 재장전하던 중이면 재장전을 끝냄(이미 가득). 권총 예비탄은 무한이라 탄창만. 채운 게 있으면 true
     */
    public boolean refillAll(Player p) {
        Arms a = arms(p);
        if (a == null) return false;
        refreshMods(p, a);
        boolean any = false;
        for (Kind k : Kind.values()) {
            if (k != Kind.PISTOL && k != a.primary) continue;
            Spec s = a.spec(k);
            int i = k.ordinal();
            if (a.mag[i] < s.mag()) {
                a.mag[i] = s.mag();
                if (a.reloading == k) cancelReload(a);
                any = true;
            }
            if (s.reserve() >= 0 && a.res[i] < s.reserve()) {
                a.res[i] = s.reserve();
                any = true;
            }
        }
        return any;
    }

    /** 손에 든 굴린 총(칸 0 주무기, 칸 1 권총, 칸 2 를 들었으면 주무기, 주무기가 없으면 권총). 판에 없으면 null */
    public Gun gun(Player p) {
        Arms a = arms(p);
        if (a == null) return null;
        int slot = slot(p);
        if (slot == SLOT_PISTOL || a.gun == null) return a.pistol;
        return a.gun;
    }

    /** 주무기 굴린 총(없으면 null) */
    public Gun primaryGun(Player p) {
        Arms a = arms(p);
        return a == null ? null : a.gun;
    }

    /** 권총(굴리지 않음, 강화 단계만) */
    public Gun pistolGun(Player p) {
        Arms a = arms(p);
        return a == null ? null : a.pistol;
    }

    /**
     * 강화 한 단계(+8% 피해): pistol 이면 권총, 아니면 주무기. 새 단계, 못 하면 -1(총 없음, 최대). 탄창 수는 그대로(피해만 오름)
     */
    public int upgrade(Player p, boolean pistol) {
        Arms a = arms(p);
        if (a == null) return -1;
        Gun g = pistol ? a.pistol : a.gun;
        if (g == null || !g.upgrade()) return -1;
        refreshMods(p, a);
        return g.level();
    }

    /** 주무기를 빼앗음(총 주운 게이브): 칸 0 을 비우고 그 총을 돌려줌(없으면 null). 돌려줄 때는 givePrimary(p, gun) */
    public Gun takePrimary(Player p) {
        Arms a = arms(p);
        if (a == null || a.gun == null) return null;
        Gun g = a.gun;
        if (a.reloading == a.primary) cancelReload(a);
        a.gun = null;
        a.primary = null;
        p.getInventory().setItem(at(p, SLOT_PRIMARY), null);
        if (slot(p) == SLOT_PRIMARY) hold(p, SLOT_PISTOL);
        refreshMods(p, a);
        return g;
    }

    /** 총 아이템. 이름은 비워 둠: 총을 바꿀 때 바닐라가 핫바 위에 띄우는 이름 글자가 없게(무엇을 들었는지는 HUD 총 그림이 보임) */
    public static ItemStack item(Spec s) {
        ItemStack it = Props.vanillaLook(Material.STICK, s.vanillaModel(), s.model(), Component.empty());
        it.setData(DataComponentTypes.CONSUMABLE, io.papermc.paper.datacomponent.item.Consumable.consumable().consumeSeconds(100000F)
            .animation(io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation.NONE).hasConsumeParticles(false)
            .sound(net.kyori.adventure.key.Key.key("minecraft", "intentionally_empty")).build());
        it.setData(DataComponentTypes.USE_EFFECTS, io.papermc.paper.datacomponent.item.UseEffects.useEffects().canSprint(true).speedMultiplier(1F)
            .interactVibrations(false).build());
        it.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.itemAttributes()
            .addModifier(Attribute.ENTITY_INTERACTION_RANGE, new AttributeModifier(RANGE_ENTITY, -3, AttributeModifier.Operation.ADD_NUMBER,
                EquipmentSlotGroup.MAINHAND)).build());
        it.setData(DataComponentTypes.ATTACK_RANGE, io.papermc.paper.datacomponent.item.AttackRange.attackRange()
            .minReach(64).maxReach(64).minCreativeReach(64).maxCreativeReach(64).hitboxMargin(0).build());
        it.setData(DataComponentTypes.INTERACT_ANIMATION, SwingAnimation.swingAnimation().type(SwingAnimation.Animation.NONE).build());
        return it;
    }

    /** 손에 든 총(배율을 건 값). 칸 2(소모품)나 빈 주무기 칸이면 null */
    public Spec held(Player p) {
        Arms a = arms(p);
        if (a == null) return null;
        int slot = slot(p);
        if (slot == SLOT_PRIMARY && a.primary != null) return a.spec(a.primary);
        if (slot == SLOT_PISTOL) return a.spec(Kind.PISTOL);
        return null;
    }

    // ------------------------------------------------------------------ 칸 옮김(원정 갈림길 숫자키 투표)

    /** 옮겨 둔 칸: Guns 가 보는 원래 칸(0, 1), 아이템이 실제로 있는 칸(3..8), 옮길 때 그 칸에 다른 아이템이 있어 맞바꿨나 */
    private static final class Aside {
        int logical;
        final int phys;
        final boolean swapped;

        Aside(int logical, int phys, boolean swapped) {
            this.logical = logical;
            this.phys = phys;
            this.swapped = swapped;
        }
    }

    private final Map<UUID, Aside> aside = new HashMap<>();
    /** 시험: 되돌릴 때 원래 칸에 뜻밖의 아이템이 있던 수(맞바꿔 잃지 않음) */
    public int asideClash;

    /** Guns 가 보는 든 칸: 옮겨 둔 칸을 들었으면 원래 칸 */
    public int slot(Player p) {
        int h = p.getInventory().getHeldItemSlot();
        Aside s = aside.get(p.getUniqueId());
        return s != null && h == s.phys ? s.logical : h;
    }

    /** 칸 logical(0..2)의 아이템이 실제로 놓인 인벤토리 칸 */
    private int at(Player p, int logical) {
        Aside s = aside.get(p.getUniqueId());
        return s != null && s.logical == logical ? s.phys : logical;
    }

    /** 옮겨 둔 실제 칸(없으면 -1) */
    public int aside(Player p) {
        Aside s = aside.get(p.getUniqueId());
        return s == null ? -1 : s.phys;
    }

    /**
     * 칸 logical 을 든다. 칸 옮김 중이면 옮겨 둔 아이템을 원래 칸으로 돌리고 새 칸(총)의 아이템을 옮겨 둔 칸에 넣어 그 칸을 그대로 든다
     * (손에는 새 총, 숫자키 1 2 3 은 계속 칸 바꿈을 보냄). 소모품 칸은 옮기지 않는다(Supplies 가 칸 2 를 맞추므로): 되돌리고 칸 2 를 듦
     */
    private void hold(Player p, int logical) {
        Aside s = aside.get(p.getUniqueId());
        if (s == null || logical > SLOT_PISTOL) {
            unstow(p);
            p.getInventory().setHeldItemSlot(logical);
            return;
        }
        if (s.logical != logical) {
            swap(p, s.logical, s.phys);
            s.logical = logical;
            swap(p, s.logical, s.phys);
        }
        p.getInventory().setHeldItemSlot(s.phys);
    }

    /** 두 칸의 아이템을 맞바꾼다(만들거나 지우지 않음) */
    private static void swap(Player p, int i, int j) {
        PlayerInventory inv = p.getInventory();
        ItemStack x = inv.getItem(i), y = inv.getItem(j);
        x = x == null ? null : x.clone();
        y = y == null ? null : y.clone();
        inv.setItem(i, y);
        inv.setItem(j, x);
    }

    /**
     * 칸 옮김(원정 갈림길 숫자키 투표, RouteVote): 지금 든 칸의 번호 키는 클라가 칸 바꿈을 안 보내서 그 갈래를 못 고르므로, 든 총(칸 0, 1)의 아이템을
     * 빈 칸(인덱스 3..8 가운데 앞쪽, 없으면 8 과 맞바꿈)으로 옮기고 그 칸을 든다. 손에는 같은 총 아이템이 그대로이고 Guns 는 그 칸을 원래 칸으로 본다
     * (쏘기, 재장전, 탄, 강화, 등급, 손 총 도장이 그대로). 소모품 칸(2)이나 다른 칸을 들었으면 주무기(없거나 잠겼으면 권총)를 꺼내 옮긴다.
     * 이미 옮겨 두었고 그 칸을 들고 있으면 그대로. 옮겨 둔 칸, 못 하면 -1. 되돌리기는 {@link #unstow}
     */
    public int stow(Player p) {
        Arms a = arms(p);
        if (a == null) return -1;
        PlayerInventory inv = p.getInventory();
        Aside cur = aside.get(p.getUniqueId());
        if (cur != null) {
            if (inv.getHeldItemSlot() == cur.phys) return cur.phys;
            unstow(p);
        }
        int h = inv.getHeldItemSlot(), logical = h;
        if (logical > SLOT_PISTOL) logical = a.primary != null && a.primaryLockUntil <= host.now() ? SLOT_PRIMARY : SLOT_PISTOL;
        int phys = 8;
        for (int i = SLOT_ITEM + 1; i <= 8; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.isEmpty()) {
                phys = i;
                break;
            }
        }
        ItemStack there = inv.getItem(phys);
        aside.put(p.getUniqueId(), new Aside(logical, phys, there != null && !there.isEmpty()));
        swap(p, logical, phys);
        inv.setHeldItemSlot(phys);
        if (logical != h) switched(p, a, logical);
        return phys;
    }

    /**
     * 칸 옮김을 되돌린다: 옮겨 둔 아이템을 원래 칸으로, 옮겨 둔 칸을 들고 있었으면 원래 칸을 든다(다른 칸을 들었으면 그대로).
     * 원래 칸에 뜻밖의 아이템이 있으면 맞바꿔서 아무것도 잃거나 늘지 않게 하고 asideClash 를 센다. 옮긴 적이 없으면 아무것도 안 함
     */
    public void unstow(Player p) {
        Aside s = aside.remove(p.getUniqueId());
        if (s == null) return;
        PlayerInventory inv = p.getInventory();
        ItemStack back = inv.getItem(s.logical);
        if (!s.swapped && back != null && !back.isEmpty()) asideClash++;
        swap(p, s.logical, s.phys);
        if (inv.getHeldItemSlot() == s.phys) inv.setHeldItemSlot(s.logical);
    }

    // ------------------------------------------------------------------ 입력

    private static boolean usingHand(Player p) {
        return p.isHandRaised() && p.getActiveItemHand() == EquipmentSlot.HAND;
    }

    private static int holdGap(Player p) {
        return Math.max(6, (int) Math.ceil(p.getPing() / 50.0) + 3);
    }

    /** 확인 신호를 보낸 뒤 다시 누름이 올 만한 틱(왕복 + 클라 한 틱 + 여유 한 틱) */
    private static int graceTicks(Player p) {
        return (int) Math.ceil(p.getPing() / 50.0) + 2;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Arms a = arms(p);
        if (a == null) return;
        e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        if (e.getHand() != EquipmentSlot.HAND) return;
        boolean right = e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK;
        if (!right) return;
        long now = host.now();
        // 서버가 아직 '사용 중'이면 확인 신호에 대한 다시 누름(누른 채): 상점 열기, 탭으로 치지 않는다
        boolean fresh = !usingHand(p);
        if (fresh) {
            if (a.holdNoFire) return;
            if (host.tryUse(p)) {
                a.holdNoFire = true;
                return;
            }
            a.tapUntil = now + 4;
        }
        a.confirmedAt = now;
        a.probeAt = -1;
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE) return;
        e.setCancelled(true);
        if (arms(p) != null) reload(p);
    }

    /** 칸 바꿈. 다른 갈고리가 이미 막은 바꿈(원정 갈림길 숫자키 투표)에는 재장전 끊기, 꺼내기 시간을 걸지 않는다(ignoreCancelled). */
    @EventHandler(ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        Arms a = arms(p);
        if (a == null) return;
        int ns = e.getNewSlot();
        if (ns > SLOT_ITEM || ns == SLOT_PRIMARY && a.primary == null || ns == SLOT_PRIMARY && a.primaryLockUntil > host.now()) {
            e.setCancelled(true);
            return;
        }
        int was = slot(p);
        // 칸 옮김 중에 막히지 않은 바꿈(투표 창 밖): 옮긴 아이템을 먼저 원래 칸으로 돌려 새 칸이 제 아이템을 가리키게
        unstow(p);
        if (ns != was) switched(p, a, ns);
    }

    /**
     * 총 바꿈: 재장전 취소, 꺼내는 시간 동안 못 쏨. 연사 박자는 총마다 따로라 그대로 이어진다(바꿔 쏘기로 빨라지지 않음).
     * 쏜 뒤 펌프, 노리쇠를 다 못 하고 바꾼 총은 다음에 꺼낼 때 꺼내는 동안 한다. 칸 2(소모품)로 바꾸면 총 일은 쉰다
     */
    private void switched(Player p, Arms a, int slot) {
        cancelReload(a);
        a.spin = 0;
        a.burstLeft = 0;
        a.chargeAt = -1;
        if (a.cycleKind != null) a.cycleOwed[a.cycleKind.ordinal()] = true;
        a.cycleKind = null;
        Spec s = slot == SLOT_PRIMARY ? a.spec(a.primary) : slot == SLOT_PISTOL ? a.spec(Kind.PISTOL) : null;
        if (s == null) return;
        long now = host.now();
        a.drawUntil = now + s.draw();
        a.drawAt = worldTime();
        int k = s.kind().ordinal();
        if (a.cycleOwed[k]) {
            // 펌프(시작 뒤 4 틱), 노리쇠(1, 4 틱)가 다 꺼내기 전에 끝나게
            a.cycleOwed[k] = false;
            a.cycleKind = s.kind();
            a.cycleAt = now + Math.max(0, s.draw() - 6);
            a.cycleStep = 0;
        }
        Props.soundAround(p.getLocation(), "gun." + s.key() + ".draw", Sound.ITEM_ARMOR_EQUIP_IRON, 0.8f, 1f, 1f, 12, -1);
    }

    /** 좌클릭: 돕기(일으키기, 떼기, 달래기, 상점, 붙잡혔을 때 탈출 연타). 무엇을 할지는 판(host)이 정한다 */
    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player p = e.getPlayer();
        Arms a = arms(p);
        if (a == null || !host.running()) return;
        host.onHelp(p);
    }

    /** '쥠' 판정(scpsl Firearms.triggerHeld + 답 기다리는 동안은 핑 만큼만 쏨) */
    private boolean triggerHeld(Player p, Arms a, long tk) {
        if (tk < a.simUntil) return true;
        if (!usingHand(p) || p.getGameMode() == GameMode.SPECTATOR) {
            a.probeAt = -1;
            a.holdNoFire = false;
            return false;
        }
        if (a.holdNoFire || p.getOpenInventory().getType() != InventoryType.CRAFTING) return false;
        if (a.probeAt >= 0) {
            long waited = tk - a.probeAt;
            if (waited > holdGap(p) + 2) {
                a.probeAt = -1;
                p.clearActiveItem();
                return false;
            }
            return waited <= graceTicks(p);
        } else if (tk - a.confirmedAt >= 10) {
            var h = ((CraftPlayer) p).getHandle();
            byte flags = h.getEntityData().get(LivingFlags.LIVING_FLAGS);
            h.connection.send(new ClientboundSetEntityDataPacket(h.getId(),
                List.of(SynchedEntityData.DataValue.create(LivingFlags.LIVING_FLAGS, (byte) (flags & ~1)))));
            a.probeAt = tk;
        }
        return true;
    }

    // ------------------------------------------------------------------ 틱

    public void tick() {
        long now = host.now();
        if (ownFx && fx != null) fx.tick();
        if (now < pauseUntil) return;
        tickedAt = Bukkit.getCurrentTick();
        settled = false;
        burnTick(now);
        arsenal.tick(now);
        Set<UUID> done = new HashSet<>();
        for (Player p : host.shooters()) {
            Arms a = arms(p);
            if (a == null) continue;
            done.add(p.getUniqueId());
            refreshMods(p, a);
            Location l = p.getLocation();
            a.speed = a.speed * 0.6 + Math.hypot(l.getX() - a.lx, l.getZ() - a.lz) * 0.4;
            a.lx = l.getX();
            a.lz = l.getZ();
            if (now - a.lastShot > 2) a.bloom *= 0.82;
            recoilTick(p, a, now);
            // 주무기 잠금(WEAPONLOCK): 주무기를 들고 있으면 권총으로
            if (a.primaryLockUntil > now && slot(p) == SLOT_PRIMARY) {
                hold(p, SLOT_PISTOL);
                switched(p, a, SLOT_PISTOL);
            }
            Spec s = held(p);
            if (s != null) {
                cycleTick(p, a, now);
                if (a.reloading != null && a.reloading != s.kind()) cancelReload(a);
                if (a.reloading != null) reloadTick(p, a, s, now);
                boolean trigger = triggerHeld(p, a, now) || now <= a.tapUntil;
                // 점사: 한 번 당기면 남은 발을 다 쏜다(짧게 눌러도 세 발)
                if (a.burstLeft > 0) trigger = true;
                if (!host.running() || host.blocked(p) || now < a.drawUntil) trigger = false;
                if (!trigger) {
                    a.dryClicked = false;
                    arsenal.idle(p, a, s, now);
                } else pull(p, a, s, now);
            } else {
                a.spin = 0;
                a.burstLeft = 0;
                a.chargeAt = -1;
            }
            visual(p, a, now);
        }
        // 쏠 수 없는 사람(쓰러짐, 죽음, 관전): 재장전, 반동, 시야 킥을 풀고 손 총을 쉬는 모습으로
        for (Map.Entry<UUID, Arms> e : arms.entrySet()) {
            if (done.contains(e.getKey())) continue;
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            rest(p, e.getValue(), now);
        }
    }

    private void rest(Player p, Arms a, long now) {
        if (a.reloading != null) cancelReload(a);
        a.spin = 0;
        a.burstLeft = 0;
        a.chargeAt = -1;
        a.simUntil = a.tapUntil = -1;
        a.restAt = -1;
        a.recStep = -1;
        a.burst = 0;
        a.climb = a.drift = 0;
        a.fovUntil = -1;
        if (a.sentWalk >= 0) fov(p, a, 1f);
        visual(p, a, now);
    }

    /** 방아쇠가 당겨진 틱: 연사 박자대로 쏨(한 틱 최대 3 발) */
    private void pull(Player p, Arms a, Spec s, long now) {
        int k = s.kind().ordinal();
        if (a.reloading != null) {
            // 산탄총은 한 발이라도 들었으면 넣다가 쏠 수 있다. 빈 총에서 시작했으면 먼저 펌프하고(이미 끝 펌프 중이면 그대로),
            // 펌프가 끝나는 틱에 이 당김을 쏜다(탭은 4 틱만 살아 펌프 8 틱보다 짧아서 따로 기억)
            if (!s.shells() || a.mag[k] <= 0) return;
            if (!a.reloadEmpty) cancelReload(a);
            else {
                if (a.shellPhase != 2) startPump(p, a, s, now);
                a.fireAfterPump = true;
            }
            if (a.reloading != null) return;
        }
        Mode md = mode(s.kind());
        // 미니건: 누르는 동안 총열이 돌아 빨라지고, md.effect 만큼 돌기 전에는 안 나감
        if (md.fire() == Fire.SPIN) {
            a.spin = Math.min(1, a.spin + 1.0 / Math.max(1, md.ticks()));
            arsenal.spinSound(p, a, now);
            if (a.spin < md.effect()) {
                a.next[k] = Math.max(a.next[k], now);
                return;
            }
        }
        // 레일건: 쏠 박자가 되면 md.ticks 동안 모은 뒤에 나감(떼면 Arsenal.idle 이 풀어 다시 모음)
        if (md.fire() == Fire.CHARGE && a.mag[k] <= 0 && a.res[k] <= 0 && s.reserve() >= 0 && a.next[k] <= now + 1e-9 && hooks.dryFire(p, s))
            a.mag[k] = 1;
        if (md.fire() == Fire.CHARGE && a.mag[k] > 0) {
            if (a.next[k] > now + 1e-9) return;
            if (a.chargeAt < 0 || a.chargeKind != s.kind()) {
                a.chargeAt = now;
                a.chargeKind = s.kind();
                arsenal.chargeStart(p, s);
                return;
            }
            if (now - a.chargeAt < md.ticks() * hooks.chargeMul(p, s.kind())) return;
        }
        // 한 틱 넘게 쉬었으면 지금부터, 아니면 소수 박자를 이어 감(초당 13 발 = 1.54 틱마다, 매번 지금으로 맞추면 2 틱마다로 떨어짐)
        if (a.next[k] < now - 1) a.next[k] = now;
        int fired = 0;
        while (a.next[k] <= now + 1e-9 && fired < 3) {
            // trip_aug: 탄창도 예비탄도 없으면 증강이 한 발을 대신 낼 수 있음(피로 사는 탄)
            if (a.mag[k] <= 0 && a.res[k] <= 0 && s.reserve() >= 0 && hooks.dryFire(p, s)) a.mag[k] = 1;
            if (a.mag[k] <= 0) {
                a.burstLeft = 0;
                a.chargeAt = -1;
                boolean first = !a.dryClicked;
                if (first) Props.soundAround(p.getLocation(), "gun." + s.key() + ".dry", Sound.BLOCK_DISPENSER_FAIL, 0.8f, 1f, 1f, 12, -1);
                a.dryClicked = true;
                a.next[k] = now + 6;
                a.tapUntil = -1;
                // 주무기 탄창도 예비탄도 비었으면 권총(무한)으로 저절로 바꿈(처음 하는 사람이 2 를 몰라 그대로 쓰러지지 않게)
                if (first && s.kind() != Kind.PISTOL && a.res[k] <= 0 && slot(p) == SLOT_PRIMARY) {
                    hold(p, SLOT_PISTOL);
                    break;
                }
                reload(p);
                break;
            }
            // 엉킴 헛방(JAM): 철컥 소리만, 이 박자는 날아감(탄은 안 씀)
            if (a.jamUntil > now && a.jamMisfire > 0 && rnd.nextDouble() < a.jamMisfire) {
                Props.soundAround(p.getLocation(), "gun." + s.key() + ".dry", Sound.BLOCK_DISPENSER_FAIL, 0.8f, 1f, 1.3f, 12, -1);
                a.next[k] += 20.0 / Math.max(0.1, s.rps()) + 4;
                a.tapUntil = -1;
                a.burstLeft = 0;
                jamMisfires++;
                break;
            }
            s = fire(p, a, s);
            fired++;
            a.tapUntil = -1;
            // 미니건은 돈 만큼(20% .. 100%) 빠르게
            a.next[k] += 20.0 / (md.fire() == Fire.SPIN ? s.rps() * (0.2 + 0.8 * a.spin) : s.rps());
            if (md.fire() == Fire.BURST) {
                // 점사 첫 발이면 남은 발을 세고, 마지막 발 뒤에는 md.ticks 쉼
                a.burstLeft = a.burstLeft <= 0 ? hooks.burstCount(p, s.kind(), md.count()) - 1 : a.burstLeft - 1;
                if (a.burstLeft <= 0) {
                    a.burstLeft = 0;
                    a.next[k] += md.ticks();
                }
            }
            if (md.fire() == Fire.CHARGE) a.chargeAt = -1;
            host.onShot(p, s.key());
            // 마지막 발이 자동 재장전을 시작했으면 같은 틱에 빈 소리를 내지 않게 멈춤(연사 배율이 크면 한 틱 여러 발)
            if (a.reloading != null) {
                a.burstLeft = 0;
                break;
            }
            if (md.fire() == Fire.CHARGE) break;
        }
    }

    /** 한 발. 쏠 때 배율을 다시 읽어 그 값으로 쏜다(돌려준 총이 이번 발에 쓴 값) */
    private Spec fire(Player p, Arms a, Spec s0) {
        refreshMods(p, a);
        Spec s = a.spec(s0.kind());
        Shot shot = new Shot();
        int k = s.kind().ordinal();
        long now = host.now();
        a.mag[k]--;
        a.shots++;
        a.lastShot = now;
        a.shotLog[a.shotLogAt++ & 3] = now;
        a.lastInterval = 20.0 / s.rps();
        a.lastRecover = s.recoil().recover();
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        double spread = s.spread() + a.bloom
            + (s.move() * Math.min(1, a.speed / 0.22) + (p.isOnGround() ? 0 : s.move() * 1.5)) * hooks.moveSpread(p, s.kind());
        if (p.isSneaking()) spread *= 0.6;
        if (a.jamUntil > now) spread *= a.jamSpread;
        Location firstEnd = null;
        Mode md = mode(s.kind());
        Gun gun = a.gun(s.kind());
        switch (md.fire()) {
            // 총 말고 다른 쏘는 방식(2026-11-12): 불길, 냉기 원뿔, 번개, 레일 광선, 날아가는 탄
            case FLAME, FREEZE -> arsenal.stream(p, a, s, md, eye, cone(dir, spread), gun, a.crit[k]);
            case CHAIN -> firstEnd = arsenal.chain(p, a, s, md, eye, cone(dir, spread), gun, a.crit[k]);
            case CHARGE -> firstEnd = arsenal.rail(p, a, s, md, eye, cone(dir, spread), gun, a.crit[k]);
            case LOB, ROCKET -> arsenal.launch(p, a, s, md, eye, cone(dir, spread), gun);
            default -> {
                int sounds = 0;
                for (int i = 0; i < s.pellets(); i++) {
                    Vector d = cone(dir, spread);
                    Location end = trace(p, a, eye, d, s, shot, i, sounds < 3);
                    sounds++;
                    // 광선총: 곡선 탄 줄 대신 빛줄기(연사가 빨라 한 발 걸러 그림, 사람 몫 예산은 ShotFx 가 지킴)
                    if (md.fire() == Fire.BEAM) {
                        if (i == 0 && ((a.shots & 1) == 0 || s.rps() < 6)) fx().beam(Arsenal.muzzle(eye, dir), end);
                    } else if (i < 3) fx().tracer(p, eye, end, i == 0);
                    if (firstEnd == null) firstEnd = end;
                }
            }
        }
        a.bloom = Math.min(s.bloomMax(), a.bloom + s.bloom());
        recoilShot(p, a, s, now);
        // 총소리: 흔들림 0.96~1.04, s.pitch() 는 바닐라 대체 소리에만(바닐라 음량은 soundAround 가 들리는 거리만큼 키움).
        // 불길, 냉기는 줄기 소리를 Arsenal 이 박자에 맞춰 이어 붙이고, 미니건은 두 발에 한 번
        if (md.fire() == Fire.FLAME || md.fire() == Fire.FREEZE) arsenal.streamSound(p, a, s, eye, now);
        else if (md.fire() != Fire.SPIN || (a.shots & 1) == 0)
            Props.soundAround(eye, s.fireEvent(), s.fire(), 1f, 0.96f + rnd.nextFloat() * 0.08f, s.pitch(), s.soundRange(), -1);
        // 총구 불꽃: 냉동총, 전기총, 레일건, 활은 없음(그 무기 효과가 따로 그림)
        if (flashes(s.kind())) {
            a.flashAt = worldTime();
            a.flashSeed = kickLevel(s.kind()) << 6 | KICK_BIT | rnd.nextInt(32);
            if (!Props.has(s.model())) fx().muzzle(p);
        }
        if (md.fire() == Fire.HITSCAN || md.fire() == Fire.BURST || md.fire() == Fire.SPIN || md.fire() == Fire.BOLT || md.fire() == Fire.BEAM)
            whiz(p, eye, firstEnd);
        if (a.mag[k] == 0 && (s.reserve() < 0 || a.res[k] > 0)) {
            a.cycleKind = null;
            reload(p);
        } else if (a.mag[k] > 0 && (s.kind() == Kind.SHOTGUN || s.kind() == Kind.SNIPER || s.kind() == Kind.BOLTACTION || s.kind() == Kind.ANTIMATERIEL)) {
            a.cycleKind = s.kind();
            a.cycleAt = now;
            a.cycleStep = 0;
        }
        return s;
    }

    /**
     * 한 줄기: 블록 광선을 먼저 쏘고 그 거리 안에서 가장 가까운 게이브를 맞힌다. 맞히면 피해를 주고 관통 수만큼 이어 감(블록은 못 뚫음).
     * 끝 자리를 돌려줌. sound = 탄착 소리를 낼지(산탄 9 알이 다 내지 않게)
     */
    private Location trace(Player p, Arms a, Location eye, Vector d, Spec s, Shot shot, int pellet, boolean sound) {
        World w = p.getWorld();
        HitTargets targets = host.targets();
        Set<Object> hit = new HashSet<>();
        Vector from = eye.toVector();
        double left = s.range(), travelled = 0;
        int pierce = s.pierce();
        DamageType type = damageType(s.kind());
        Gun gun = a.gun(s.kind());
        double critChance = a.crit[s.kind().ordinal()];
        while (true) {
            Location fromLoc = from.toLocation(w);
            RayTraceResult rb = w.rayTraceBlocks(fromLoc, d, left, FluidCollisionMode.NEVER, true);
            double blockDist = rb == null ? left : rb.getHitPosition().distance(from);
            Hit h = targets == null ? null : targets.cast(p, from.clone(), d.clone(), blockDist, hit);
            if (h != null && (h.target() == null || h.dist() > blockDist)) h = null;
            // 아군 사격: 서 있는 동료 몸(또는 쓰러진 동료 좌석 자리 낮은 상자)이 몹, 블록보다 가까우면 피해 없이 탄이 거기서 멈춤(한 발에 한 사람
            // 한 번 알림). 맞힐 게이브가 그 동료에게 붙은 것이면 그 동료 몸은 건너뜀(붙잡은 것, 머리에 붙은 것을 동료 쪽에서 쏘기)
            double near = h == null ? blockDist : h.dist();
            Player ally = null;
            double allyDist = near;
            Object aim = h == null ? null : h.target();
            for (Player o : host.shooters()) {
                if (o == p || o.getWorld() != w || aim != null && hooks.attached(o, aim)) continue;
                double dist = boxHit(o.getBoundingBox(), from, d, allyDist);
                if (dist < allyDist) {
                    allyDist = dist;
                    ally = o;
                }
            }
            for (Player o : hooks.downedMates()) {
                if (o == p || o.getWorld() != w) continue;
                double dist = boxHit(downBox(o), from, d, allyDist);
                if (dist < allyDist) {
                    allyDist = dist;
                    ally = o;
                }
            }
            if (ally != null) {
                boolean taken = false;
                for (AllyShot f : allyShots) {
                    try {
                        if (f.take(p, ally, from.clone(), d.clone(), allyDist, s)) {
                            taken = true;
                            break;
                        }
                    } catch (RuntimeException ex) {
                        host.plugin().getLogger().warning("[총] 아군 탄 갈고리 오류: " + ex);
                    }
                }
                if (!taken && shot.allies.add(ally)) hooks.friendly(p, ally);
                return from.clone().add(d.clone().multiply(allyDist)).toLocation(w);
            }
            if (h != null) {
                hit.add(h.target());
                Vector pos = h.pos() == null ? from.clone().add(d.clone().multiply(h.dist())) : h.pos().clone();
                Location at = pos.toLocation(w);
                hitIndex = hit.size() - 1;
                double amount = s.damage() * falloff(travelled + h.dist(), s.range()) * Math.max(0, hooks.hitMul(p, h.target(), s));
                critNow = critChance > 0 && rnd.nextDouble() < critChance;
                if (critNow) amount *= 2;
                a.hits++;
                a.hitAt = host.now();
                boolean died = targets.damage(h.target(), new Damage(amount, type, p, pos.clone(), d.clone(), s.key(), 0));
                if (died) {
                    a.kills++;
                    a.killAt = host.now();
                }
                hooks.onHit(p, h.target(), amount, died, gun);
                critNow = false;
                hitIndex = 0;
                if (gun != null && !gun.traits.isEmpty()) traits(p, a, gun, s, shot, h.target(), pos, d, amount, died, hit);
                // 복합궁 화살: 꿰뚫은 개체마다 뒤로 밀침
                if (!died && h.target() instanceof Gabe hg && mode(s.kind()).fire() == Fire.BOLT) push(w, hg, d, mode(s.kind()).effect());
                fx().bloodHit(at, d, pellet < 3);
                if (sound) Props.soundAround(at, "impact.flesh", Sound.ENTITY_PLAYER_HURT, 0.9f, 0.9f + rnd.nextFloat() * 0.2f, 1f, 20, -1);
                if (pierce-- > 0) {
                    left -= h.dist();
                    travelled += h.dist();
                    from = pos.add(d.clone().multiply(0.05));
                    if (left > 0.5) continue;
                }
                return at;
            }
            if (rb == null) return fromLoc.add(d.clone().multiply(left));
            Location at = rb.getHitPosition().toLocation(w);
            Block b = rb.getHitBlock();
            Material mat = b == null ? Material.STONE : b.getType();
            fx().hit(at, rb.getHitBlockFace(), mat, d, pellet < 3);
            if (sound) impactSound(at, mat);
            return at;
        }
    }

    /** 상자 bb 에 from 에서 d(단위)로 max 안에 닿는 거리, 안 닿으면 +무한(가운데가 광선에서 먼 상자는 바로 버림) */
    private static double boxHit(BoundingBox bb, Vector from, Vector d, double max) {
        Vector c = bb.getCenter();
        double t = c.clone().subtract(from).dot(d);
        if (t < 0 || t > max + 1.5 || c.clone().subtract(from).subtract(d.clone().multiply(t)).lengthSquared() > 2.25) return Double.POSITIVE_INFINITY;
        RayTraceResult rp = bb.rayTrace(from, d, max);
        return rp == null ? Double.POSITIVE_INFINITY : rp.getHitPosition().distance(from);
    }

    /**
     * 쓰러진 동료 몸 상자: 좌석 자리(바닥)부터 앉은 머리 끝(+1.45)까지, 가로 1.1. 사람 발은 좌석보다 0.6 아래라 서 있는 상자의 아랫부분은
     * 바닥에 묻혀 있으므로 바닥 위로 보이는 앉은 몸만 친다
     */
    private static BoundingBox downBox(Player o) {
        Location l = o.getVehicle() != null ? o.getVehicle().getLocation() : o.getLocation();
        return new BoundingBox(l.getX() - 0.55, l.getY() - 0.05, l.getZ() - 0.55, l.getX() + 0.55, l.getY() + 1.45, l.getZ() + 0.55);
    }

    // ------------------------------------------------------------------ 특성

    /** 맞힌 뒤 굴린 총 특성(흡혈, 관통, 치명은 hooks, 배율에서 이미 처리됨) */
    private void traits(Player p, Arms a, Gun gun, Spec s, Shot shot, Object target, Vector pos, Vector d, double amount, boolean died,
        Set<Object> hit) {
        HitTargets t = host.targets();
        World w = p.getWorld();
        if (gun.has(Gun.Trait.EXPLOSIVE) && !shot.boom) {
            shot.boom = true;
            Location at = pos.toLocation(w);
            long now = host.now();
            Long last = popAt.get(p.getUniqueId());
            if (last == null || now - last >= 3) {
                popAt.put(p.getUniqueId(), now);
                fx().pop(at);
                Props.soundAround(at, null, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1f, 1.7f, 24, -1);
            }
            double dmg = s.damage() * 0.45;
            for (Object o : t.inRadius(at, 1.8)) {
                if (o == target) continue;
                boolean k = t.damage(o, new Damage(dmg, DamageType.EXPLOSION, p, t.center(o), d.clone(), "explosive", 0));
                count(a, k);
                hooks.onHit(p, o, dmg, k, gun);
            }
        }
        if (gun.has(Gun.Trait.INCENDIARY) && !died) {
            Burn b = burns.get(target);
            if (b == null) {
                b = new Burn();
                b.next = host.now() + 10;
                burns.put(target, b);
            }
            b.by = p;
            b.gun = gun;
            b.dps = Math.max(b.dps, Math.max(4, s.damage() * 0.5));
            b.until = host.now() + 60;
        }
        if (gun.has(Gun.Trait.CHAIN) && !shot.chain) {
            shot.chain = true;
            Set<Object> done = new HashSet<>(hit);
            done.add(target);
            Vector from = t.center(target);
            if (from == null) from = pos.clone();
            double dmg = amount * 0.5;
            for (int jump = 0; jump < 2 && from != null; jump++) {
                Object next = null;
                double best = 4.5 * 4.5;
                for (Object o : t.inRadius(from.toLocation(w), 4.5)) {
                    if (done.contains(o)) continue;
                    Vector c = t.center(o);
                    if (c == null) continue;
                    double dd = c.distanceSquared(from);
                    if (dd < best) {
                        best = dd;
                        next = o;
                    }
                }
                if (next == null) break;
                Vector c = t.center(next);
                done.add(next);
                fx().streak(from.toLocation(w), c.toLocation(w));
                // 같은 자리(분열 직후, 같은 자리 소환)면 방향은 탄 방향(0 벡터 normalize 로 NaN 이 되지 않게)
                Vector jd = c.clone().subtract(from);
                jd = jd.lengthSquared() < 1e-6 ? d.clone() : jd.normalize();
                boolean k = t.damage(next, new Damage(dmg, DamageType.BULLET, p, c.clone(), jd, "chain", 0));
                count(a, k);
                hooks.onHit(p, next, dmg, k, gun);
                from = c;
                dmg *= 0.5;
            }
        }
        if (gun.has(Gun.Trait.KNOCKBACK) && !died && target instanceof Gabe g) push(w, g, d, Math.max(0.1, Math.min(0.8, s.damage() * 0.04)));
    }

    /** 게이브를 d 의 수평 쪽으로 dist 블록 밀침(발 자리가 막히면 그대로). 넉백 특성, 복합궁 화살, 폭발 */
    static void push(World w, Gabe g, Vector d, double dist) {
        Vector hd = d.clone().setY(0);
        if (hd.lengthSquared() < 1e-6 || dist <= 0) return;
        Vector to = g.pos.clone().add(hd.normalize().multiply(dist));
        Block feet = w.getBlockAt(to.getBlockX(), (int) Math.floor(to.getY()), to.getBlockZ());
        if (feet.isPassable()) g.pos.copy(to);
    }

    void count(Arms a, boolean killed) {
        if (!killed) return;
        a.kills++;
        a.killAt = host.now();
    }

    /** 화염탄 불붙음: 10 틱마다 반 초 몫 피해(종류 FIRE), 작은 불꽃 연출(덤). 죽거나 시간이 다하면 끝 */
    private void burnTick(long now) {
        if (burns.isEmpty()) return;
        HitTargets t = host.targets();
        for (var it = burns.entrySet().iterator(); it.hasNext();) {
            var e = it.next();
            Object o = e.getKey();
            Burn b = e.getValue();
            if (t == null || now > b.until || o instanceof Gabe g && g.dead) {
                it.remove();
                continue;
            }
            if (now < b.next) continue;
            b.next = now + 10;
            Vector c = t.center(o);
            if (c == null) {
                it.remove();
                continue;
            }
            fx().flame(c.toLocation(host.world()), 0.6f, 14, ShotFx.EXTRA);
            double dmg = b.dps * 0.5;
            boolean k = t.damage(o, new Damage(dmg, DamageType.FIRE, b.by, c.clone(), new Vector(0, 1, 0), "incendiary", 0));
            Arms a = b.by == null ? null : arms(b.by);
            if (a != null) count(a, k);
            if (b.by != null) hooks.onHit(b.by, o, dmg, k, b.gun);
            if (k) it.remove();
        }
    }

    /**
     * 불붙임(화염탄 특성, 화염방사기, 불 폭발): 대상마다 하나, 다시 걸리면 초당 피해는 큰 쪽, 시간은 새로. Arsenal 이 부른다
     */
    void ignite(Object target, Player by, Gun gun, double dps, int ticks) {
        if (target == null || target instanceof Gabe g && g.dead) return;
        Burn b = burns.get(target);
        if (b == null) {
            b = new Burn();
            b.next = host.now() + 10;
            burns.put(target, b);
        }
        b.by = by;
        b.gun = gun;
        b.dps = Math.max(b.dps, dps);
        b.until = Math.max(b.until, host.now() + (long) (ticks * (by == null ? 1 : Math.max(0.1, hooks.burnMul(by)))));
    }

    // ------------------------------------------------------------------ trip_aug: 증강이 부르는 공개 창구

    /** 불붙임(총 특성 없이). 길이에 hooks.burnMul 이 곱해진다 */
    public void burn(Object target, Player by, double dps, int ticks) {
        ignite(target, by, null, dps, ticks);
    }

    /** 지금 불붙어 있나 */
    public boolean burningOn(Object target) {
        return burns.containsKey(target);
    }

    /** 지금 냉기에 느려지거나 얼어 있나 */
    public boolean chilledOn(Object target) {
        return arsenal.chilledOn(target);
    }

    /** 게이브를 d 의 수평 쪽으로 dist 블록 밀침 */
    public static void shove(World w, Gabe g, Vector d, double dist) {
        push(w, g, d, dist);
    }

    /** 지금 불붙은 대상 수(시험) */
    public int burning() {
        return burns.size();
    }

    /** 탄착 소리: 쇠, 나무, 유리, 그 밖은 돌 */
    private void impactSound(Location at, Material m) {
        float jitter = 0.9f + rnd.nextFloat() * 0.2f;
        switch (ShotFx.material(m)) {
            case ShotFx.MAT_METAL -> Props.soundAround(at, "impact.metal", Sound.BLOCK_ANVIL_LAND, 0.8f, jitter, 1f, 20, -1);
            case ShotFx.MAT_WOOD -> Props.soundAround(at, "impact.wood", Sound.BLOCK_WOOD_HIT, 0.9f, jitter, 1f, 20, -1);
            // 유리는 바닐라 소리만: 1.4 x 0.93~1.07 = 1.3~1.5
            case ShotFx.MAT_GLASS -> Props.soundAround(at, null, Sound.BLOCK_GLASS_HIT, 1.0f, 0.93f + rnd.nextFloat() * 0.14f, 1.4f, 20, -1);
            default -> Props.soundAround(at, "impact.stone", Sound.BLOCK_STONE_HIT, 0.9f, jitter, 1f, 20, -1);
        }
    }

    /** 탄이 곁(2.5 블록 안)을 스쳐 간 사람(쏜 사람 빼고)에게 그 사람만 듣는 스침 소리 */
    private void whiz(Player shooter, Location from, Location to) {
        if (to == null) return;
        Vector a = from.toVector(), ab = to.toVector().subtract(a);
        double len2 = ab.lengthSquared();
        if (len2 < 4) return;
        long now = host.now();
        for (Player o : shooter.getWorld().getPlayers()) {
            if (o == shooter || o.getGameMode() == GameMode.SPECTATOR) continue;
            Vector e = o.getEyeLocation().toVector();
            double t = e.clone().subtract(a).dot(ab) / len2;
            if (t <= 0.02 || t >= 0.97) continue;
            Vector c = a.clone().add(ab.clone().multiply(t));
            if (c.distanceSquared(e) > 2.5 * 2.5) continue;
            Long last = whizAt.get(o.getUniqueId());
            if (last != null && now - last < 3) continue;
            whizAt.put(o.getUniqueId(), now);
            Props.soundTo(o, c.toLocation(o.getWorld()), "bullet.whiz", null, 0.9f, 0.9f + rnd.nextFloat() * 0.2f, 1f, -1);
        }
    }

    private Vector cone(Vector dir, double deg) {
        if (deg <= 0) return dir.clone();
        double a = Math.toRadians(deg) * Math.sqrt(rnd.nextDouble()), t = rnd.nextDouble() * Math.PI * 2;
        Vector u = Math.abs(dir.getY()) > 0.99 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        Vector x = dir.clone().crossProduct(u).normalize(), y = dir.clone().crossProduct(x).normalize();
        return dir.clone().multiply(Math.cos(a)).add(x.multiply(Math.sin(a) * Math.cos(t))).add(y.multiply(Math.sin(a) * Math.sin(t))).normalize();
    }

    /** 클라 시점에 더하는 상대 회전(마우스를 빼앗지 않음). 수류탄 흔들림, 맞음 흔들림에도 쓴다 */
    public static void kick(Player p, float yaw, float pitch) {
        ((CraftPlayer) p).getHandle().connection.send(new ClientboundPlayerRotationPacket(yaw, true, pitch, true));
    }

    // ------------------------------------------------------------------ 반동, 시야

    private void recoilShot(Player p, Arms a, Spec s, long now) {
        Recoil rc = s.recoil();
        int n = ++a.burst;
        double crouch = p.isSneaking() ? 0.75 : 1.0;
        double up = s.up() * (1 + rc.climb() * Math.min(n - 1, rc.climbMax())) * (0.9 + 0.2 * rnd.nextDouble()) * crouch;
        double ramp = Math.max(0, Math.min(1, (n - 2) / 4.0));
        double side = s.side() * (rc.pattern() * Math.sin(n * 0.55 + rc.phase()) * ramp + (rnd.nextDouble() * 2 - 1) * (1 - rc.pattern() * 0.5)) * crouch;
        float y0 = (float) (side * 0.65), p0 = (float) (-up * 0.65);
        kick(p, y0, p0);
        a.restYaw += (float) side - y0;
        a.restPitch += (float) -up - p0;
        a.restAt = now + 1;
        a.climb += up;
        a.drift += side;
        a.recStep = -1;
        a.fovUntil = now + 2;
        a.fovMul = (float) rc.fov();
    }

    private void recoilTick(Player p, Arms a, long now) {
        if (a.restAt >= 0 && now >= a.restAt) {
            kick(p, a.restYaw, a.restPitch);
            a.restYaw = a.restPitch = 0;
            a.restAt = -1;
        }
        // 방아쇠를 놓음(다음 발 박자(최대 5 틱) + 3 틱 동안 안 쏨): 오른 만큼의 일부를 되돌림
        if (a.burst > 0 && now - a.lastShot > Math.min(a.lastInterval, 5) + 3) {
            a.recPitch = (float) (a.climb * a.lastRecover);
            a.recYaw = (float) (-a.drift * 0.3);
            a.recStep = 0;
            a.burst = 0;
            a.climb = a.drift = 0;
        }
        if (a.recStep >= 0) {
            float w = RECOVER[a.recStep++];
            kick(p, a.recYaw * w, a.recPitch * w);
            if (a.recStep >= RECOVER.length) a.recStep = -1;
        }
        // 웅크리면 조준경(저격총, 지정사수 소총, 레일건): 쏠 때 킥과 곱하되 fov() 가 맞출 수 있는 아래끝(0.56) 위로
        float base = scopeK(p, a);
        fov(p, a, now < a.fovUntil ? Math.max(0.56f, a.fovMul * base) : base);
    }

    /** 조준경 배율: 웅크리고 조준경 총을 들었고 재장전 중이 아니면 그 총 배율, 아니면 1 */
    private float scopeK(Player p, Arms a) {
        if (!p.isSneaking() || a.reloading != null) return 1f;
        Spec s = held(p);
        return s == null ? 1f : scope(s.kind());
    }

    /** 쏠 때 시야 킥: 클라에만 걷기 속도를 바꿔 보낸다(마크 FOV 배율 = (이동 속도 속성 / 걷기 속도 + 1) / 2) */
    private void fov(Player p, Arms a, float k) {
        float base = p.getWalkSpeed() / 2f;
        float w = base;
        if (Math.abs(k - 1) > 1e-3) {
            AttributeInstance attr = p.getAttribute(Attribute.MOVEMENT_SPEED);
            double av = attr == null ? base : attr.getValue();
            double denom = k * (av / base + 1) - 1;
            if (av > 1e-4 && denom > 0.05) w = (float) (av / denom);
        }
        if (a.sentWalk < 0 && Math.abs(w - base) < 1e-6) return;
        if (Math.abs(w - a.sentWalk) < 1e-6) return;
        a.sentWalk = w;
        sendWalk(p, w);
    }

    private static void sendWalk(Player p, float walk) {
        var h = ((CraftPlayer) p).getHandle();
        net.minecraft.world.entity.player.Abilities ab = new net.minecraft.world.entity.player.Abilities();
        ab.apply(h.getAbilities().pack());
        ab.setWalkingSpeed(walk);
        h.connection.send(new ClientboundPlayerAbilitiesPacket(ab));
    }

    // ------------------------------------------------------------------ 손 총 모습(탄창, 총구 불꽃)

    /** 1인칭 반동 세기(셰이더 zr_kick): 0 기관단총, 1 소총, 2 권총, 3 산탄총, 저격총 */
    static int kickLevel(Kind k) {
        return switch (k) {
            case SMG, MINIGUN, FLAME, FREEZE, MACHINEPISTOL, SILENCED -> 0;
            case RIFLE, LMG, BURST, TESLA, LASER -> 1;
            case PISTOL, REVOLVER, DMR, DUAL -> 2;
            default -> 3;
        };
    }

    /**
     * 손에 든 총 아이템의 custom_model_data: 깃발 0 = 탄창 빠짐, 색 0..2 = 남이 보는(3인칭) 총구 불꽃 도장(종류 11..13,
     * 씨앗 [반동 세기 2][0][무작위 5]), 색 3 = 1인칭 몸통 몸짓 도장([F][도장 상위][몸짓][도장 중간][15 - 세기][도장 하위], 없으면 흰색),
     * 색 4..6 = 1인칭 총구 불꽃 도장(씨앗 [세기 2][반동 켬 1][무작위 5]). 몸짓 E 반동, D 꺼내기, C 재장전 자세, B 재장전 자세 풀기
     * (셰이더 zr_kick). 아이템은 모두가 받지만 색 3..6 은 총 아이템 정의가 1인칭 자리(firstperson_*)에서만 읽는다
     * (build_pack.py): 곁에 선 동료의 총이 내 눈 공간에서 움직이거나 찢어지지 않는다. 바뀔 때만 보낸다
     */
    private void visual(Player p, Arms a, long now) {
        int held = slot(p);
        long wt = worldTime();
        for (int slot = 0; slot <= 1; slot++) {
            Kind kind = slot == 0 ? a.primary : Kind.PISTOL;
            if (kind == null) continue;
            boolean isHeld = slot == held;
            boolean mag = isHeld && (a.magOut && a.reloading == kind || now < a.debugMagUntil);
            long flashAt = -1, bodyAt = -1;
            int seed = a.flashSeed;
            int level = kickLevel(kind);
            int sig = 0xE;
            int drawLen = SPECS.get(kind).draw();
            if (isHeld && now < a.debugFlashUntil) {
                flashAt = wt;
                seed = level << 6 | (int) (wt * 7 & 31);
            } else if (isHeld && now < a.debugKickUntil) {
                bodyAt = wt;
            } else if (isHeld && wt - a.flashAt <= FLASH_KEEP && a.flashAt >= a.drawAt) {
                flashAt = bodyAt = a.flashAt;
            } else if (isHeld && wt - a.drawAt <= drawLen + 1) {
                bodyAt = a.drawAt;
                sig = 0xD;
                level = Math.max(0, Math.min(3, (drawLen - 5) / 2));
            } else if (isHeld && a.reloading == kind && a.reloadWt >= 0) {
                bodyAt = a.reloadWt;
                sig = 0xC;
                level = 0;
            } else if (isHeld && a.poseEndKind == kind && wt - a.poseEndWt <= 6) {
                bodyAt = a.poseEndWt;
                sig = 0xB;
                level = 0;
            }
            long key = (flashAt + 1) * 31 + (bodyAt + 1) * 1000003L + seed * 7L + sig;
            if (mag == a.sentMag[slot] && key == a.sentStamp[slot]) continue;
            int inv = at(p, slot);
            ItemStack it = p.getInventory().getItem(inv);
            if (it == null || it.getType() != Material.STICK) continue;
            if (!mag && flashAt < 0 && bodyAt < 0) it.resetData(DataComponentTypes.CUSTOM_MODEL_DATA);
            else {
                CustomModelData.Builder b = CustomModelData.customModelData().addFlag(mag);
                if (flashAt >= 0 || bodyAt >= 0) {
                    int stamp = (int) Math.floorMod(flashAt, 4000L);
                    int third = (seed & 255 & ~KICK_BIT) << 12 | stamp, first = (seed & 255) << 12 | stamp;
                    for (int kindFx : MUZZLE_KINDS) b.addColor(Color.fromRGB(flashAt < 0 ? 0 : kindFx << 20 | third));
                    int st = (int) Math.floorMod(bodyAt, 4000L);
                    b.addColor(Color.fromRGB(bodyAt < 0 ? 0xFFFFFF
                        : (0xF0 | st >> 8 & 15) << 16 | (sig << 4 | st >> 4 & 15) << 8 | (15 - level) << 4 | st & 15));
                    for (int kindFx : MUZZLE_KINDS) b.addColor(Color.fromRGB(flashAt < 0 ? 0 : kindFx << 20 | first));
                }
                it.setData(DataComponentTypes.CUSTOM_MODEL_DATA, b.build());
            }
            p.getInventory().setItem(inv, it);
            a.sentMag[slot] = mag;
            a.sentStamp[slot] = key;
        }
    }

    // ------------------------------------------------------------------ 재장전

    /** 든 총 재장전(Q). 붙잡힘, 쓰러짐, 탄창 가득, 예비탄 없음, tick() 이 멈춘 동안이면 안 함 */
    public void reload(Player p) {
        Arms a = arms(p);
        Spec s = held(p);
        // tick() 이 멈춘 동안(대기실)은 재장전이 나아가지 못하니 시작하지 않는다
        if (a == null || s == null || a.reloading != null || host.blocked(p) || stale()) return;
        int k = s.kind().ordinal();
        if (a.mag[k] >= s.mag() || s.reserve() >= 0 && a.res[k] <= 0) return;
        long now = host.now();
        a.reloading = s.kind();
        a.reloadStart = a.phaseStart = now;
        a.reloadWt = worldTime();
        a.reloadEmpty = a.mag[k] == 0;
        // 엉킴(JAM): 재장전 시간 배율(박자 시각도 같이 늘어남)
        double jr = a.jamUntil > now ? Math.max(0.2, a.jamReload) : 1;
        a.reloadMul = cueMul(s) * jr;
        a.cueIdx = 0;
        a.magOut = false;
        a.cycleKind = null;
        if (s.shells()) {
            a.shellPhase = 0;
            a.reloadUntil = now + scaled(SHELL_START, a.reloadMul);
            sound(p, s, "reload_start", Sound.BLOCK_IRON_TRAPDOOR_OPEN, 1f, -1);
        } else {
            a.reloadUntil = now + Math.max(1, Math.round(s.reload() * jr));
        }
    }

    // ------------------------------------------------------------------ 새 종 갈고리(2026-11-12 sp_engine, docs/모듈/API.md '새 종 갈고리')

    /** 엉킴 헛방 수(시험). */
    public int jamMisfires;

    /** RELOAD: 든 총을 재장전하는 중인가. */
    public boolean reloading(Player p) {
        Arms a = arms(p);
        return a != null && a.reloading != null;
    }

    /** RELOAD: 재장전 진행 0..1(재장전 중이 아니면 -1). */
    public double reloadProgress(Player p) {
        Arms a = arms(p);
        if (a == null || a.reloading == null) return -1;
        double len = Math.max(1, a.reloadUntil - a.phaseStart);
        return Math.max(0, Math.min(1, 1 - Math.max(0, a.reloadUntil - host.now()) / len));
    }

    /** RELOAD: 재장전을 끊는다(넣던 탄은 안 들어감, 다시 Q 로). 끊었으면 true. */
    public boolean cancelReload(Player p) {
        Arms a = arms(p);
        if (a == null || a.reloading == null) return false;
        cancelReload(a);
        return true;
    }

    /**
     * AMMO: 예비탄을 몫만큼 뺀다. 주무기와 권총마다 그 총 예비탄 최대(Spec.reserve)의 frac(0..1, 한 발 이상)을 빼고 0 아래로는 안 간다. 무한 예비탄(권총 기본)은
     * 그대로. magToo 면 예비탄이 모자란 만큼 탄창에서도 뺀다. 뺀 발 수 합.
     */
    public int takeAmmo(Player p, double frac, boolean magToo) {
        Arms a = arms(p);
        if (a == null || frac <= 0) return 0;
        int took = 0;
        for (Kind k : new Kind[] {a.primary, Kind.PISTOL}) {
            if (k == null) continue;
            Spec s = a.spec(k);
            if (s == null || s.reserve() < 0) continue;
            int i = k.ordinal();
            int want = Math.max(1, (int) Math.round(s.reserve() * Math.min(1, frac)));
            int r = Math.max(0, Math.min(a.res[i], want));
            a.res[i] -= r;
            took += r;
            if (magToo && r < want) {
                int m = Math.max(0, Math.min(a.mag[i], want - r));
                a.mag[i] -= m;
                took += m;
            }
        }
        return took;
    }

    /**
     * JAM: 이 사람 총을 ticks 동안 엉키게 한다. reloadMul = 재장전 시간 배율(1.5 = 1.5배 느림, 그동안 시작한 재장전에 걸림), misfire = 한 발마다 헛방 확률(0..1, 철컥
     * 소리만 나고 탄은 안 씀), spreadMul = 퍼짐 배율. 이미 엉켜 있으면 끝은 늦은 쪽, 값은 큰 쪽. 다 1, 0, 1 이거나 ticks 0 이하면 풂.
     */
    public void jam(Player p, int ticks, double reloadMul, double misfire, double spreadMul) {
        Arms a = arms(p);
        if (a == null) return;
        long now = host.now();
        if (ticks <= 0) {
            a.jamUntil = -1;
            a.jamReload = 1;
            a.jamMisfire = 0;
            a.jamSpread = 1;
            return;
        }
        boolean on = a.jamUntil > now;
        a.jamUntil = Math.max(a.jamUntil, now + ticks);
        a.jamReload = on ? Math.max(a.jamReload, reloadMul) : reloadMul;
        a.jamMisfire = Math.max(0, Math.min(1, on ? Math.max(a.jamMisfire, misfire) : misfire));
        a.jamSpread = on ? Math.max(a.jamSpread, spreadMul) : spreadMul;
    }

    /** JAM: 지금 엉켜 있나. */
    public boolean jammed(Player p) {
        Arms a = arms(p);
        return a != null && a.jamUntil > host.now();
    }

    /** WEAPONLOCK: ticks 동안 주무기를 못 듦(들고 있으면 권총으로 바뀌고, 숫자키 1 이 막힘). 0 이하면 풂. */
    public void lockPrimary(Player p, int ticks) {
        Arms a = arms(p);
        if (a == null) return;
        a.primaryLockUntil = ticks <= 0 ? -1 : Math.max(a.primaryLockUntil, host.now() + ticks);
    }

    /** WEAPONLOCK: 지금 주무기가 잠겼나. */
    public boolean primaryLocked(Player p) {
        Arms a = arms(p);
        return a != null && a.primaryLockUntil > host.now();
    }

    /**
     * 재장전 박자 시각 배율 = 이 총의 실제 재장전 틱 / 바탕 재장전 틱(굴린 총 x 사람 배율이 반올림까지 들어감): 끝나는 틱과 탄창 꽂힘,
     * 장전 소리가 같은 비율로 당겨지고 늘어남(산탄총은 한 발 넣는 틱의 비율을 시작 자세, 끝 펌프에 씀)
     */
    static double cueMul(Spec s) {
        return Math.max(0.2, Math.min(5, (double) s.reload() / SPECS.get(s.kind()).reload()));
    }

    private static int scaled(int ticks, double mul) {
        return Math.max(ticks == 0 ? 0 : 1, (int) Math.round(ticks * mul));
    }

    private void cancelReload(Arms a) {
        if (a.reloading != null) {
            a.poseEndWt = worldTime();
            a.poseEndKind = a.reloading;
        }
        a.reloading = null;
        a.reloadUntil = -1;
        a.magOut = false;
        a.fireAfterPump = false;
    }

    private void reloadTick(Player p, Arms a, Spec s, long now) {
        int k = s.kind().ordinal();
        if (s.shells()) {
            shellTick(p, a, s, now);
            return;
        }
        long el = now - a.reloadStart;
        List<Cue> cues = CUES.getOrDefault(s.kind(), List.of());
        while (a.cueIdx < cues.size() && scaled(cues.get(a.cueIdx).at(), a.reloadMul) <= el) {
            Cue c = cues.get(a.cueIdx++);
            if (c.emptyOnly() && !a.reloadEmpty) continue;
            if (c.act() == MAG_OUT) a.magOut = true;
            else if (c.act() == MAG_IN) a.magOut = false;
            if (c.events().length > 0 || c.fallback() != null) {
                String ev = null;
                for (String e : c.events()) if (Props.hasSound(e)) {
                    ev = e;
                    break;
                }
                Props.soundAround(p.getLocation().add(0, 1.2, 0), ev, c.fallback(), 0.9f, 0.97f + rnd.nextFloat() * 0.06f, c.pitch(), 16, -1);
            }
        }
        if (now < a.reloadUntil) return;
        int need = s.mag() - a.mag[k];
        int take = s.reserve() < 0 ? need : Math.min(need, a.res[k]);
        a.mag[k] += Math.max(0, take);
        if (s.reserve() >= 0) a.res[k] -= Math.max(0, take);
        cancelReload(a);
    }

    /** 산탄총: 시작 자세(7 틱) → 한 발씩 9 틱(넣는 소리가 넣는 틱에 오게) → 빈 총에서 시작했으면 끝에 펌프(8 틱), 아니면 끝 소리만. 틱은 재장전 배율을 곱함 */
    private void shellTick(Player p, Arms a, Spec s, long now) {
        int k = s.kind().ordinal();
        if (a.shellPhase == 2) {
            if (now >= a.reloadUntil) {
                // 넣다가 당겨서 시작한 펌프: 끝난 이 틱에 그 한 발(tick() 은 재장전 다음에 방아쇠를 봄)
                boolean shot = a.fireAfterPump;
                cancelReload(a);
                if (shot) a.tapUntil = now + 4;
            }
            return;
        }
        if (a.shellPhase == 1 && now == a.reloadUntil - 1) sound(p, s, "shell", Sound.BLOCK_IRON_TRAPDOOR_OPEN, 0.95f + rnd.nextFloat() * 0.1f, 1);
        if (now < a.reloadUntil) return;
        if (a.shellPhase == 0) {
            a.shellPhase = 1;
        } else {
            a.mag[k]++;
            if (s.reserve() >= 0) a.res[k]--;
        }
        if (a.mag[k] < s.mag() && (s.reserve() < 0 || a.res[k] > 0)) {
            a.phaseStart = now;
            a.reloadUntil = now + s.reload();
            if (a.shellPhase == 1 && now + 1 >= a.reloadUntil - 1) sound(p, s, "shell", Sound.BLOCK_IRON_TRAPDOOR_OPEN, 1f, 1);
            return;
        }
        if (a.reloadEmpty) startPump(p, a, s, now);
        else {
            sound(p, s, "reload_end", Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 1f, -1);
            cancelReload(a);
        }
    }

    private void startPump(Player p, Arms a, Spec s, long now) {
        a.shellPhase = 2;
        a.phaseStart = now;
        a.reloadUntil = now + scaled(SHELL_PUMP, a.reloadMul);
        sound(p, s, "pump", Sound.BLOCK_PISTON_CONTRACT, 1f, 0);
    }

    /** 쏜 뒤 동작 소리: 산탄총 펌프(4 틱 뒤), 저격총 노리쇠 당김(1 틱), 밂(4 틱) */
    private void cycleTick(Player p, Arms a, long now) {
        if (a.cycleKind == null) return;
        Spec s = SPECS.get(a.cycleKind);
        long el = now - a.cycleAt;
        if (a.cycleKind == Kind.SHOTGUN) {
            if (el >= 4) {
                sound(p, s, "pump", Sound.BLOCK_PISTON_CONTRACT, 1f, 0);
                a.cycleKind = null;
            }
        } else if (a.cycleKind == Kind.SNIPER || a.cycleKind == Kind.BOLTACTION || a.cycleKind == Kind.ANTIMATERIEL) {
            if (a.cycleStep == 0 && el >= 1) {
                sound(p, s, "bolt_pull", Sound.BLOCK_PISTON_EXTEND, 1f, -1);
                a.cycleStep = 1;
            }
            if (a.cycleStep == 1 && el >= 4) {
                sound(p, s, "bolt_release", Sound.BLOCK_PISTON_CONTRACT, 1f, -1);
                a.cycleKind = null;
            }
        } else {
            a.cycleKind = null;
        }
    }

    /** 총 손질 소리(16 블록 안 사람들, 소리 듣는 게이브는 onShot 으로 따로 앎). jitter 는 흔들림(바닐라 대체 소리도 1 x jitter) */
    private void sound(Player p, Spec s, String part, Sound fallback, float jitter, int variant) {
        Props.soundAround(p.getLocation().add(0, 1.2, 0), "gun." + s.key() + "." + part, fallback, 0.9f, jitter, 1f, 16, variant);
    }

    /** HUD 용: 든 레일건을 모은 몫(0..1), 모으지 않으면 -1 */
    public double charge(Player p) {
        Arms a = arms(p);
        Spec s = held(p);
        if (a == null || s == null || a.chargeAt < 0 || a.chargeKind != s.kind()) return -1;
        return Math.min(1, (host.now() - a.chargeAt) / Math.max(1, mode(s.kind()).ticks() * hooks.chargeMul(p, s.kind())));
    }

    /** HUD 용: 든 미니건 총열 회전(0..1), 미니건이 아니면 -1 */
    public double spin(Player p) {
        Arms a = arms(p);
        Spec s = held(p);
        return a == null || s == null || mode(s.kind()).fire() != Fire.SPIN ? -1 : a.spin;
    }

    /** HUD 용: {탄창, 예비(-1 무한), 재장전 중 0..1(-1 아님, 산탄총은 지금 한 발 또는 펌프의 진행)}. 총을 안 들었으면 null */
    public double[] ammo(Player p) {
        Arms a = arms(p);
        Spec s = held(p);
        if (a == null || s == null) return null;
        int k = s.kind().ordinal();
        double rl = -1;
        if (a.reloading != null) {
            double len = Math.max(1, a.reloadUntil - a.phaseStart);
            rl = Math.max(0, Math.min(1, 1 - Math.max(0, a.reloadUntil - host.now()) / len));
        }
        return new double[] {a.mag[k], s.reserve() < 0 ? -1 : a.res[k], rl};
    }

    /**
     * HUD 용 재장전 단계: {남은 틱, 이 단계 전체 틱}. 재장전 중이 아니면 null. 탄창 총은 재장전 전체 한 단계,
     * 산탄총은 시작 자세(7) / 한 발(9) / 끝 펌프(8) 마다 새 단계(탄창 수는 한 발 넣을 때마다 오름)
     */
    public int[] reloadPhase(Player p) {
        Arms a = arms(p);
        Spec s = held(p);
        if (a == null || s == null || a.reloading != s.kind()) return null;
        long now = host.now();
        return new int[] {(int) Math.max(0, a.reloadUntil - now), (int) Math.max(1, a.reloadUntil - a.phaseStart)};
    }

    // ------------------------------------------------------------------ 시험

    /**
     * 시험(이름 x 로도 됨): 모델목록 x = 종류별 모델 수, 모델상자 x [웨이브] [수] = 무기 상자를 수 번 굴려 모델마다 나온 수(사지 않음),
     * 안 나온 모델과 종류별 수
     */
    private String modelTest(String act, String[] a) {
        if (act.equals("모델목록")) {
            StringBuilder sb = new StringBuilder("models=" + Models.all().size());
            for (Kind k : Kind.values()) sb.append(' ').append(k.name()).append('=').append(Models.list(k).size());
            return sb.toString();
        }
        int wave = a.length > 3 ? (int) parse(a[3]) : 10;
        int cnt = a.length > 4 ? (int) parse(a[4]) : 2000;
        Map<String, Integer> seen = new java.util.TreeMap<>();
        Map<Kind, Integer> byKind = new EnumMap<>(Kind.class);
        for (int i = 0; i < cnt; i++) {
            Gun g = Gun.rollBox(Math.max(1, wave), null, rnd);
            seen.merge(g.model, 1, Integer::sum);
            byKind.merge(g.kind, 1, Integer::sum);
        }
        List<String> missing = new ArrayList<>();
        for (Models.Model m : Models.all()) if (m.kind() != Kind.PISTOL && !seen.containsKey(m.key())) missing.add(m.key());
        int lo = Integer.MAX_VALUE, hi = 0;
        for (int v : seen.values()) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        StringBuilder sb = new StringBuilder("wave=" + wave + " n=" + cnt + " models=" + Models.all().size() + " seen=" + seen.size() + " missing="
            + missing + " min=" + (seen.isEmpty() ? 0 : lo) + " max=" + hi + " |");
        for (Map.Entry<Kind, Integer> e : byKind.entrySet()) sb.append(' ').append(e.getKey().name()).append('=').append(e.getValue());
        return sb.toString();
    }

    /** 시험: ticks 틱 동안 방아쇠를 당긴 것으로 친다 */
    public void testFire(Player p, int ticks) {
        Arms a = arms(p);
        if (a != null) a.simUntil = host.now() + ticks;
    }

    /**
     * 시험 훅: /gave 시험 &lt;행동&gt; &lt;이름&gt; ... 중 이 클래스(와 수류탄, 연출)가 맡는 것. 처리하면 결과 글, 아니면 null(p 는 null 일 수 있음).
     * 쏘기 틱 / 재장전 / 총 KIND / 수류탄 / 수류탄주기 n / 총상태 / 돌기 도 [위아래] / 탄창 n / 예비 n / 바꾸기 0|1|2 / 섬광 틱 / 탄창빼기 틱 /
     * 반동보기 틱 / 웅크리기 on|off / 탭 / 멈춤 틱 / 쏜틱 / 총색 / 누름 / 배율 / 총굴리기 KIND [등급] [특성...] / 총정보 / 강화 [권총] / 불붙음,
     * 그 밖은 Grenades.test(수류탄기록), ShotFx.test(폭발, 터짐, 피, 연출수, 자국시험, 예산시험)
     */
    public String test(Player p, String[] a) {
        if (a.length < 2) return null;
        String act = a[1];
        double n = a.length > 3 ? parse(a[3]) : Double.NaN;
        if (act.equals("모델목록") || act.equals("모델상자")) return modelTest(act, a);
        if (p == null) return null;
        Arms ar = arms(p);
        switch (act) {
            // 새 종 갈고리 시험(2026-11-12 sp_engine)
            case "탄약빼기" -> {
                // 탄약빼기 <이름> [몫 0.25] [탄창도 0|1]
                if (ar == null) return "총 없음";
                int took = takeAmmo(p, Double.isNaN(n) ? 0.25 : n, a.length > 4 && a[4].equals("1"));
                double[] am = ammo(p);
                return "took=" + took + " ammo=" + (am == null ? "-" : (int) am[0] + "/" + (int) am[1]);
            }
            case "엉킴" -> {
                // 엉킴 <이름> [틱 200] [재장전 배 2] [헛방 0.5] [퍼짐 배 2]
                if (ar == null) return "총 없음";
                jam(p, Double.isNaN(n) ? 200 : (int) n, a.length > 4 ? parse(a[4]) : 2, a.length > 5 ? parse(a[5]) : 0.5, a.length > 6 ? parse(a[6]) : 2);
                return "jammed=" + jammed(p) + " misfires=" + jamMisfires;
            }
            case "주무기잠금" -> {
                if (ar == null) return "총 없음";
                lockPrimary(p, Double.isNaN(n) ? 200 : (int) n);
                return "locked=" + primaryLocked(p) + " slot=" + slot(p);
            }
            case "재장전끊기" -> {
                boolean was = reloading(p);
                return "was=" + was + " cancelled=" + cancelReload(p) + " now=" + reloading(p);
            }
            case "쏘기" -> {
                if (ar == null) return "총 없음";
                testFire(p, (int) (Double.isNaN(n) ? 20 : n));
                return "ok t=" + host.now();
            }
            case "재장전" -> {
                if (ar == null) return "총 없음";
                reload(p);
                return "reloading=" + ar.reloading;
            }
            case "총" -> {
                if (ar == null) return "총 없음";
                if (a.length < 4) return "총 " + kinds();
                Kind k;
                try {
                    k = Kind.valueOf(a[3].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return "모르는 총: " + a[3];
                }
                if (k == Kind.PISTOL) return "권총은 칸 1 에 늘 있음";
                givePrimary(p, k);
                return "ok " + k.name().toLowerCase(Locale.ROOT);
            }
            case "총굴리기" -> {
                // 총굴리기 <이름> <종류> [등급] [특성...]: 굴린 총을 주무기로(등급을 안 주면 지금 웨이브 확률표 대신 일반)
                if (ar == null) return "총 없음";
                if (a.length < 4) return "총굴리기 <이름> " + kinds() + " [COMMON|RARE|EPIC|LEGENDARY] [특성...]";
                Kind k;
                try {
                    k = Kind.valueOf(a[3].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return "모르는 총: " + a[3];
                }
                if (k == Kind.PISTOL) return "권총은 굴리지 않음";
                Gun.Rarity r = a.length > 4 ? Gun.Rarity.of(a[4]) : Gun.Rarity.COMMON;
                if (r == null) return "모르는 등급: " + a[4];
                Gun g = Gun.roll(k, r, rnd);
                if (a.length > 5) {
                    java.util.EnumSet<Gun.Trait> ts = java.util.EnumSet.noneOf(Gun.Trait.class);
                    for (int i = 5; i < a.length; i++) {
                        Gun.Trait t = Gun.Trait.of(a[i]);
                        if (t == null) return "모르는 특성: " + a[i];
                        ts.add(t);
                    }
                    g = new Gun(k, g.model, r, g.damage, g.rate, g.mag, g.reload, g.spread, g.range, g.pierce, ts, 0);
                }
                givePrimary(p, g);
                return "ok " + g.info();
            }
            case "총모델" -> {
                // 총모델 <이름> <모델 키> [등급] [특성...]: 그 모델의 굴린 총을 주무기로
                if (ar == null) return "총 없음";
                if (a.length < 4) return "총모델 <이름> <모델 키> [COMMON|RARE|EPIC|LEGENDARY] [특성...] (모델 " + Models.all().size() + ")";
                Models.Model md = Models.of(a[3]);
                if (md == null || md.kind() == Kind.PISTOL) return "모르는 모델: " + a[3];
                Gun.Rarity r = a.length > 4 ? Gun.Rarity.of(a[4]) : Gun.Rarity.COMMON;
                if (r == null) return "모르는 등급: " + a[4];
                Gun g = Gun.roll(md.kind(), md.key(), r, rnd);
                if (a.length > 5) {
                    java.util.EnumSet<Gun.Trait> ts = java.util.EnumSet.noneOf(Gun.Trait.class);
                    for (int i = 5; i < a.length; i++) {
                        Gun.Trait t = Gun.Trait.of(a[i]);
                        if (t == null) return "모르는 특성: " + a[i];
                        ts.add(t);
                    }
                    g = new Gun(md.kind(), md.key(), r, g.damage, g.rate, g.mag, g.reload, g.spread, g.range, g.pierce, ts, 0);
                }
                givePrimary(p, g);
                return "ok " + g.info();
            }
            case "총정보" -> {
                if (ar == null) return "총 없음";
                refreshMods(p, ar);
                Gun g = gun(p);
                Spec s = g == null ? null : ar.spec(g.kind);
                return (g == null ? "총없음" : g.info()) + (s == null ? "" : String.format(Locale.ROOT,
                    " | eff dmg=%.2f rps=%.2f mag=%d res=%d reload=%d spread=%.2f range=%.1f pierce=%d pellets=%d crit=%.2f", s.damage(), s.rps(), s.mag(),
                    s.reserve(), s.reload(), s.spread(), s.range(), s.pierce(), s.pellets(), ar.crit[g.kind.ordinal()])) + " | ammo=" + ar.mag[g == null ? 0
                        : g.kind.ordinal()] + "/" + ar.res[g == null ? 0 : g.kind.ordinal()] + " item=" + ar.item + "x" + ar.items() + " | mods=" + fmtMods(ar.mods)
                    + " " + hooks.info(p);
            }
            case "강화" -> {
                // 강화 <이름> [권총]: 상점 값 없이 한 단계
                if (ar == null) return "총 없음";
                boolean pistol = a.length > 3 && (a[3].equals("권총") || a[3].equalsIgnoreCase("pistol"));
                int lv = upgrade(p, pistol);
                return lv < 0 ? "강화 못 함" : "level=" + lv;
            }
            case "불붙음" -> {
                return "burning=" + burns.size();
            }
            case "장전박자" -> {
                // 든 총 재장전 박자(시작 뒤 틱): 끝, 탄창 빠짐(out), 꽂힘(in), 소리. 빈 총 재장전 기준(emptyOnly 포함)
                if (ar == null) return "총 없음";
                refreshMods(p, ar);
                Spec s = held(p);
                if (s == null) return "총 안 듦";
                double mul = cueMul(s);
                StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%s mul=%.3f end=%d", s.key(), mul, s.shells() ? -1 : s.reload()));
                if (s.shells()) sb.append(" start=").append(scaled(SHELL_START, mul)).append(" shell=").append(s.reload()).append(" pump=")
                    .append(scaled(SHELL_PUMP, mul));
                else for (Cue c : CUES.getOrDefault(s.kind(), List.of())) {
                    int at = scaled(c.at(), mul);
                    sb.append(' ').append(c.act() == MAG_OUT ? "out" : c.act() == MAG_IN ? "in" : "snd").append('@').append(at)
                        .append(at > s.reload() ? "!" : "");
                }
                return sb.toString();
            }
            case "맡기기" -> {
                // 시험: 무기 상태만 나간 것처럼 맡김(forget) 뒤 바로 equip 으로 되살림
                if (ar == null) return "총 없음";
                String before = ar.gun + " pistol=" + ar.pistol + " ammo=" + (ar.primary == null ? "-" : ar.mag[ar.primary.ordinal()] + "/"
                    + ar.res[ar.primary.ordinal()]);
                forget(p.getUniqueId());
                boolean was = parked(p.getUniqueId());
                equip(p);
                Arms nw = arms(p);
                return "parked=" + was + " before=" + before + " after=" + nw.gun + " pistol=" + nw.pistol + " ammo=" + (nw.primary == null ? "-"
                    : nw.mag[nw.primary.ordinal()] + "/" + nw.res[nw.primary.ordinal()]) + " slot=" + slot(p);
            }
            case "수류탄" -> {
                if (ar == null || grenades == null) return "수류탄 없음";
                if (ar.grenades <= 0) ar.grenades = 1;
                grenades.throwFrom(p);
                return "ok 남음=" + ar.grenades;
            }
            case "수류탄주기" -> {
                if (ar == null) return "총 없음";
                ar.grenades = (int) Math.max(0, Double.isNaN(n) ? Grenades.MAX : n);
                return "grenades=" + ar.grenades;
            }
            case "총상태" -> {
                if (ar == null) return "총 없음";
                Spec s = held(p);
                int k = s == null ? 0 : s.kind().ordinal();
                // 시험 클라 기록은 200 자에서 잘려서 자주 보는 값을 앞에
                return String.format("yaw=%.2f pitch=%.2f", p.getLocation().getYaw(), p.getLocation().getPitch()) + " using=" + usingHand(p)
                    + " shots=" + ar.shots + " held=" + (s == null ? "-" : s.key()) + " mag=" + (s == null ? 0 : ar.mag[k]) + " res="
                    + (s == null ? 0 : ar.res[k]) + " grenades=" + ar.grenades + " reloading=" + ar.reloading + " magOut=" + ar.magOut + " burst=" + ar.burst
                    + String.format(" climb=%.2f drift=%.2f bloom=%.2f", ar.climb, ar.drift, ar.bloom) + " walk=" + ar.sentWalk + " phase=" + ar.shellPhase
                    + " slot=" + slot(p) + " draw=" + Math.max(0, ar.drawUntil - host.now()) + " sentMag=" + ar.sentMag[0] + ","
                    + ar.sentMag[1] + " hits=" + ar.hits + " kills=" + ar.kills + " probe=" + (ar.probeAt < 0 ? -1 : host.now() - ar.probeAt)
                    + " t=" + host.now() + " aside=" + aside(p);
            }
            case "배율" -> {
                if (ar == null) return "총 없음";
                Spec s = held(p);
                return "mods=" + ar.mods + (s == null ? "" : String.format(" eff mag=%d res=%d rps=%.2f reload=%d pellets=%d pierce=%d range=%.1f dmg=%.1f",
                    s.mag(), s.reserve(), s.rps(), s.reload(), s.pellets(), s.pierce(), s.range(), s.damage()));
            }
            case "돌기" -> {
                Location l = p.getLocation();
                l.setYaw(l.getYaw() + (float) (Double.isNaN(n) ? 90 : n));
                if (a.length > 4) l.setPitch((float) parse(a[4]));
                p.teleport(l);
                return "ok";
            }
            case "탄창", "예비" -> {
                Spec s = held(p);
                if (ar == null || s == null || Double.isNaN(n)) return "총 없음";
                if (act.equals("탄창")) ar.mag[s.kind().ordinal()] = (int) n;
                else ar.res[s.kind().ordinal()] = (int) n;
                return "ok";
            }
            case "바꾸기" -> {
                if (ar == null || Double.isNaN(n)) return "총 없음";
                int slot = (int) n;
                if (slot == SLOT_PRIMARY && ar.primary == null || slot < 0 || slot > SLOT_ITEM) return "그 칸 없음";
                if (slot != slot(p)) switched(p, ar, slot);
                hold(p, slot);
                return "ok t=" + host.now();
            }
            case "섬광", "탄창빼기", "반동보기" -> {
                if (ar == null) return "총 없음";
                long until = host.now() + (long) (Double.isNaN(n) ? 100 : n);
                if (act.equals("섬광")) ar.debugFlashUntil = until;
                else if (act.equals("반동보기")) ar.debugKickUntil = until;
                else ar.debugMagUntil = until;
                return "ok";
            }
            case "웅크리기" -> {
                p.setSneaking(a.length > 3 && a[3].equals("on"));
                return "ok";
            }
            case "탭" -> {
                // 짧게 한 번 누름(onInteract 의 새 누름과 같음): 4 틱 동안 한 발
                if (ar == null) return "총 없음";
                ar.tapUntil = host.now() + 4;
                ar.confirmedAt = host.now();
                ar.probeAt = -1;
                return "ok t=" + host.now();
            }
            case "멈춤" -> {
                // n 틱(기본 40) 동안 tick() 을 건너뜀: Game 이 tick() 을 안 부를 때 저절로 정리되는지 봄
                pauseUntil = host.now() + (long) (Double.isNaN(n) ? 40 : n);
                return "ok t=" + host.now();
            }
            case "쏜틱" -> {
                if (ar == null) return "총 없음";
                StringBuilder sb = new StringBuilder("shots=" + ar.shots + " ticks=");
                for (int i = 0; i < 4; i++) sb.append(i == 0 ? "" : ",").append(ar.shotLog[(ar.shotLogAt + i) & 3]);
                Spec s = held(p);
                ItemStack it = p.getInventory().getItemInMainHand();
                CustomModelData cmd = it.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
                return sb + " held=" + (s == null ? "-" : s.key()) + " mag=" + (s == null ? 0 : ar.mag[s.kind().ordinal()]) + " reloading="
                    + ar.reloading + " phase=" + ar.shellPhase + " walk=" + ar.sentWalk + " cmd=" + (cmd == null ? "없음"
                        : cmd.flags() + "/" + cmd.colors().size()) + " stale=" + stale() + " t=" + host.now();
            }
            case "총색" -> {
                // 손에 든 총 아이템의 custom_model_data(깃발, 색 16진수)
                CustomModelData cmd = p.getInventory().getItemInMainHand().getData(DataComponentTypes.CUSTOM_MODEL_DATA);
                if (cmd == null) return "없음 t=" + host.now() + " wt=" + worldTime();
                StringBuilder sb = new StringBuilder(cmd.flags().toString());
                for (Color c : cmd.colors()) sb.append(' ').append(String.format("%06X", c.asRGB()));
                return sb + " t=" + host.now() + " wt=" + worldTime();
            }
            case "누름" -> {
                // 서버 쪽 '사용 중'을 켜서 실제 쥠 판정(확인 신호, 핑만큼 더 쏘기, 시간 초과)을 거치게 함. 시험 클라는 다시 누르지 않으니
                // 10 틱 + 핑 여유 뒤 멈추고 holdGap 뒤 사용이 풀려야 함
                p.startUsingItem(EquipmentSlot.HAND);
                if (ar != null) {
                    ar.confirmedAt = host.now();
                    ar.probeAt = -1;
                    ar.tapUntil = host.now() + 4;
                }
                return "using=" + usingHand(p) + " t=" + host.now();
            }
            default -> {
            }
        }
        String r = arsenal.test(p, a);
        if (r == null) r = grenades == null ? null : grenades.test(p, a);
        if (r == null) r = fx().test(p, a);
        return r;
    }

    /** 종류 이름 목록(시험 훅 안내) */
    static String kinds() {
        StringBuilder sb = new StringBuilder();
        for (Kind k : Kind.values()) sb.append(sb.length() == 0 ? "" : "|").append(k.name());
        return sb.toString();
    }

    private static String fmtMods(GunMods m) {
        return String.format(Locale.ROOT, "dmg=%.2f rate=%.2f mag=%.2f reload=%.2f spread=%.2f recoil=%.2f range=%.2f pierce=%d pellets=%d crit=%.2f res=%.2f",
            m.damage(), m.rate(), m.mag(), m.reload(), m.spread(), m.recoil(), m.range(), m.pierce(), m.pellets(), m.crit(), m.reserve());
    }

    static double parse(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
