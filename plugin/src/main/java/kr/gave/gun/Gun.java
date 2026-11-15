package kr.gave.gun;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import kr.gave.gun.Guns.Kind;
import kr.gave.gun.Guns.Spec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * 총 하나: 종류 + 모델 + 등급 + 강화 단계. 굴리는 것은 종류, 모델, 등급뿐이고 성능은 그 셋으로 정해진다(2026-11-14 사용자 '같은 등급이면 성능
 * 통일'): 등급 배율은 {@link #GRADE} 한 벌, 특성은 모델마다 정해진 둘({@link #traitsOf}: 영웅은 첫째, 전설은 둘 다). 같은 모델 같은 등급이면
 * 누가 뽑아도 같은 총이고, 같은 종류 같은 등급이면 모델이 달라도 한 대상 지속 화력이 같다(Guns.effective 가 한 발 피해로 맞춤).
 * 유효 총 = Guns.SPECS(종류) x 모델 배율(Models) x {@link #mods()} x GunHost.mods(p).
 * 권총은 굴리지 않는 기본 권총({@link #basic})이고 강화만 된다. 강화 단계는 {@link #upgrade()}, {@link #inherit} 로만 바꾼다
 * (ver 가 올라 Guns 가 유효 총을 다시 만듦). 강화는 총을 바꿔도 그대로 따라간다({@link #INHERIT} 1).
 */
public final class Gun {

    /** 등급: 이름, 색(상점 이름, HUD 테두리) */
    public enum Rarity {
        COMMON("일반", 0xB4B4B4), RARE("희귀", 0x4FA3FF), EPIC("영웅", 0xB86BFF), LEGENDARY("전설", 0xFFC53D);

        public final String ko;
        public final int rgb;

        Rarity(String ko, int rgb) {
            this.ko = ko;
            this.rgb = rgb;
        }

        public TextColor color() {
            return TextColor.color(rgb);
        }

        /** 한 단계 낮은 등급(일반은 그대로). 흉내 종의 '진짜 상자 등급 하락' 등 */
        public Rarity lower() {
            return this == COMMON ? COMMON : values()[ordinal() - 1];
        }

        public Rarity higher() {
            return this == LEGENDARY ? LEGENDARY : values()[ordinal() + 1];
        }

        /** 소문자 키(common, rare, epic, legendary): 팩 모델 이름 */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Rarity of(String s) {
            for (Rarity r : values()) if (r.name().equalsIgnoreCase(s) || r.ko.equals(s)) return r;
            return null;
        }
    }

    /** 특성('더 잘하게' 만). 영웅 1개, 전설 2개. 어떤 특성인지는 모델마다 정해져 있다({@link #traitsOf}) */
    public enum Trait {
        /** 맞은 자리 반경 1.8 작은 폭발(그 발 피해 45%, 한 발에 한 번) */
        EXPLOSIVE("폭발탄"),
        /** 3초 불붙음(초당 그 발 피해 50%, 다시 맞으면 시간만 새로) */
        INCENDIARY("화염탄"),
        /** 맞은 개체 옆 4.5블록 안 다른 개체로 두 번 번짐(50%, 25%, 한 발에 한 번) */
        CHAIN("연쇄"),
        /** 처치 시 체력 5 */
        LIFESTEAL("흡혈"),
        /** 관통 +2 */
        PIERCE("관통 +2"),
        /** 맞은 개체를 뒤로 밀침(피해에 비례, 0.1..0.8블록) */
        KNOCKBACK("넉백"),
        /** 치명 +15% */
        CRIT("치명 +15%");

        public final String ko;

        Trait(String ko) {
            this.ko = ko;
        }

        public static Trait of(String s) {
            for (Trait t : values()) if (t.name().equalsIgnoreCase(s) || t.ko.equals(s)) return t;
            return null;
        }

        /**
         * 이 쏘는 방식에서 실제로 일하는 특성인가(Guns.traits, Arsenal 의 구현 기준). 불길, 냉기: 관통, 폭발탄, 연쇄 없음. 번개: 관통 없음.
         * 유탄: 관통, 연쇄 없음. 로켓: 관통, 연쇄 없음, 넉백은 늘 밀치므로 뺌. 레일건: 이미 다 꿰뚫고 폭발탄, 연쇄는 구현이 없음
         */
        public boolean fits(Guns.Fire f) {
            return switch (f) {
                case FLAME, FREEZE -> this != PIERCE && this != EXPLOSIVE && this != CHAIN;
                case CHAIN -> this != PIERCE;
                case LOB -> this != PIERCE && this != CHAIN;
                case ROCKET -> this != PIERCE && this != CHAIN && this != KNOCKBACK;
                case CHARGE -> this != PIERCE && this != CHAIN && this != EXPLOSIVE;
                default -> true;
            };
        }
    }

    /** 강화 상한(2026-11-11 10 → 20: 늦은 판에 돈 쓸 곳. 값은 단계마다 1.25 배로 계속 오름, Shop.Ware.UP_GUN) */
    public static final int MAX_LEVEL = 20;
    /**
     * 새로 뽑은 총(상점 뽑기, 꾸러미 같은 다른 데서 생긴 총)이 바꿀 때 이어받는 지금 총 강화 단계 몫. 1 = 그대로(2026-11-14: 강화는 총이 아니라
     * 사람의 주무기 강화처럼 따라간다. 0.5 였을 때는 중반부터 강화에 쓴 돈이 아까워 총을 못 바꿨다)
     */
    public static final double INHERIT = 1.0;
    /** 강화 단계마다 피해 +8% */
    public static final double LEVEL_DMG = 0.08;
    public static final double LIFESTEAL_HP = 5, CRIT_ADD = 0.15;
    public static final int PIERCE_ADD = 2;

    private static final AtomicLong IDS = new AtomicLong();

    /** 판 안에서 겹치지 않는 번호 */
    public final long id = IDS.incrementAndGet();
    public final Kind kind;
    /** 모델 키(소품 층 gave:&lt;키&gt;, Models 표): 같은 종류 안에서 어떤 총인가(이름, 성능 배율, 총소리, 그림). 종류 키면 그 종류 기본 모델 */
    public final String model;
    public final Rarity rarity;
    /** 등급 배율(1 = 바탕 그대로, {@link #GRADE}). reload 는 시간 배율(작을수록 빠름), spread 는 작을수록 곧음 */
    public final double damage, rate, mag, reload, spread, range;
    /** 등급 관통 더하기 */
    public final int pierce;
    public final Set<Trait> traits;
    private int level, ver;

    public Gun(Kind kind, Rarity rarity, double damage, double rate, double mag, double reload, double spread, double range, int pierce,
            Set<Trait> traits, int level) {
        this(kind, Models.defaultKey(kind), rarity, damage, rate, mag, reload, spread, range, pierce, traits, level);
    }

    public Gun(Kind kind, String model, Rarity rarity, double damage, double rate, double mag, double reload, double spread, double range, int pierce,
            Set<Trait> traits, int level) {
        this.kind = kind;
        this.model = model == null ? Models.defaultKey(kind) : model;
        this.rarity = rarity;
        this.damage = damage;
        this.rate = rate;
        this.mag = mag;
        this.reload = reload;
        this.spread = spread;
        this.range = range;
        this.pierce = pierce;
        this.traits = Collections.unmodifiableSet(traits.isEmpty() ? EnumSet.noneOf(Trait.class) : EnumSet.copyOf(traits));
        this.level = Math.max(0, Math.min(MAX_LEVEL, level));
    }

    /** 굴리지 않은 기본 총(일반, 배율 1, 특성 없음): 권총, 시험 훅 '총 KIND' */
    public static Gun basic(Kind k) {
        return new Gun(k, Rarity.COMMON, 1, 1, 1, 1, 1, 1, 0, EnumSet.noneOf(Trait.class), 0);
    }

    public int level() {
        return level;
    }

    /** 바뀔 때마다 오르는 판(Guns 가 유효 총을 다시 만들지 봄) */
    public int ver() {
        return ver;
    }

    public boolean has(Trait t) {
        return traits.contains(t);
    }

    /** 지금 총 강화 단계의 {@link #INHERIT} 몫(내림)을 이어받음: 이미 그보다 높으면 그대로. 오르면 true */
    public boolean inherit(int fromLevel) {
        int lv = Math.min(MAX_LEVEL, (int) Math.floor(Math.max(0, fromLevel) * INHERIT));
        if (lv <= level) return false;
        level = lv;
        ver++;
        return true;
    }

    /** 강화 한 단계(+8% 피해). 이미 최대면 false */
    public boolean upgrade() {
        if (level >= MAX_LEVEL) return false;
        level++;
        ver++;
        return true;
    }

    /** 이 총의 배율(등급 x 강화 단계 x 특성 관통, 치명). 예비탄은 탄창 배율과 같이 움직임(탄창 수가 같게) */
    public GunMods mods() {
        return new GunMods(damage * (1 + LEVEL_DMG * level), rate, mag, reload, spread, 1, range, pierce + (has(Trait.PIERCE) ? PIERCE_ADD : 0), 0,
                has(Trait.CRIT) ? CRIT_ADD : 0, mag);
    }

    /** 배율만 건 이 총(사람 증강 빼고): 상점 비교 줄 */
    public Spec spec() {
        return Guns.effective(kind, this, GunMods.NONE);
    }

    // ------------------------------------------------------------------ 등급, 특성(정해진 값)

    /**
     * 등급마다 정해진 배율: 피해, 연사, 탄창, 재장전(시간), 퍼짐, 사거리. 한 대상 지속 화력으로 일반 1 : 희귀 1.24 : 영웅 1.53 : 전설 1.88 쯤
     * (소총 기준: 등급 사이가 겹치지 않고 한 단계마다 눈에 띄게). tools/balance_sim.py 가 이 표를 읽는다
     */
    private static final double[][] GRADE = {
        {1.00, 1.00, 1.00, 1.00, 1.00, 1.00},
        {1.14, 1.03, 1.10, 0.93, 0.90, 1.05},
        {1.30, 1.06, 1.20, 0.86, 0.80, 1.10},
        {1.48, 1.10, 1.30, 0.80, 0.70, 1.15}};
    /** 등급마다 관통 더하기(관통이 뜻 있는 방식만: 광선, 점사, 회전, 화살, 빛줄기) */
    private static final int[] GRADE_PIERCE = {0, 0, 1, 1};

    private static boolean pierces(Guns.Fire f) {
        return f == Guns.Fire.HITSCAN || f == Guns.Fire.BURST || f == Guns.Fire.SPIN || f == Guns.Fire.BOLT || f == Guns.Fire.BEAM;
    }

    /**
     * 그 모델의 특성: 그 방식에 맞는({@link Trait#fits}) 특성 가운데 모델 키로 정해지는 둘. 영웅은 첫째, 전설은 둘 다, 그 아래는 없음.
     * 무작위가 아니라 모델의 개성이다(같은 모델은 늘 같은 특성). 특성 종류나 fits 를 바꾸면 모델마다 특성이 다시 정해진다
     */
    public static Set<Trait> traitsOf(Kind k, String model, Rarity r) {
        Set<Trait> out = EnumSet.noneOf(Trait.class);
        int n = r == Rarity.LEGENDARY ? 2 : r == Rarity.EPIC ? 1 : 0;
        if (n == 0) return out;
        Guns.Fire fire = Guns.mode(k).fire();
        List<Trait> pool = new ArrayList<>();
        for (Trait t : Trait.values()) if (t.fits(fire)) pool.add(t);
        if (pool.isEmpty()) return out;
        int h = (model == null ? k.name() : model).hashCode();
        int first = Math.floorMod(h, pool.size());
        out.add(pool.get(first));
        if (n > 1 && pool.size() > 1) out.add(pool.get((first + 1 + Math.floorMod(h / 31, pool.size() - 1)) % pool.size()));
        return out;
    }

    /** k 종류의 모델 model, r 등급 총(같은 인자면 늘 같은 성능) */
    public static Gun of(Kind k, String model, Rarity r) {
        double[] g = GRADE[r.ordinal()];
        String m = model == null ? Models.defaultKey(k) : model;
        return new Gun(k, m, r, g[0], g[1], g[2], g[3], g[4], g[5], pierces(Guns.mode(k).fire()) ? GRADE_PIERCE[r.ordinal()] : 0, traitsOf(k, m, r), 0);
    }

    // ------------------------------------------------------------------ 굴리기(종류, 모델, 등급만)

    /** k 종류, r 등급: 그 종류 안 모델을 고르게 굴림 */
    public static Gun roll(Kind k, Rarity r, Random rnd) {
        return of(k, Models.pick(k, rnd), r);
    }

    /** k 종류의 모델 model 을 r 등급으로(굴릴 것이 없다: {@link #of} 와 같음. 옛 호출 꼴을 지킴) */
    public static Gun roll(Kind k, String model, Rarity r, Random rnd) {
        return of(k, model, r);
    }

    /**
     * 웨이브에 맞는 등급 확률표(일반, 희귀, 영웅, 전설 %). 1~3: 70/25/5/0, 4~8: 50/35/13/2, 9~15: 35/38/22/5, 16~25: 22/38/30/10, 26~: 12/33/38/17
     */
    public static int[] odds(int wave) {
        if (wave <= 3) return new int[] {70, 25, 5, 0};
        if (wave <= 8) return new int[] {50, 35, 13, 2};
        if (wave <= 15) return new int[] {35, 38, 22, 5};
        if (wave <= 25) return new int[] {22, 38, 30, 10};
        return new int[] {12, 33, 38, 17};
    }

    /**
     * 무기 랜덤 상자 종류 가중치: {웨이브 1 의 무게, 웨이브마다 더하는 무게}. 권총은 상자에 없다. 흔한 총(기관단총, 산탄총, 소총)이 가장 자주,
     * 무거운 것(미니건, 로켓, 레일건, 유탄, 전기총)은 드물다가 웨이브가 오를수록 자주. 지금 든 종류는 1/3 로(다양하게 뜨게)
     */
    private static final java.util.Map<Kind, double[]> BOX = new java.util.EnumMap<>(Kind.class);

    static {
        BOX.put(Kind.SMG, new double[] {10, 0});
        BOX.put(Kind.SHOTGUN, new double[] {10, 0});
        BOX.put(Kind.RIFLE, new double[] {10, 0});
        BOX.put(Kind.SNIPER, new double[] {7, 0});
        BOX.put(Kind.BURST, new double[] {8, 0});
        BOX.put(Kind.REVOLVER, new double[] {7, 0});
        BOX.put(Kind.BOW, new double[] {6, 0});
        BOX.put(Kind.DMR, new double[] {7, 0.05});
        BOX.put(Kind.LMG, new double[] {7, 0.05});
        BOX.put(Kind.FLAME, new double[] {8, 0.05});
        BOX.put(Kind.FREEZE, new double[] {6, 0.05});
        BOX.put(Kind.LAUNCHER, new double[] {6, 0.08});
        BOX.put(Kind.TESLA, new double[] {6, 0.08});
        BOX.put(Kind.MINIGUN, new double[] {3, 0.12});
        BOX.put(Kind.ROCKET, new double[] {3, 0.12});
        BOX.put(Kind.RAILGUN, new double[] {2.5, 0.12});
        // 2026-11-12 wpn_max 새 종류 아홉: 종류를 먼저 이 무게로 고르고 그 종류 안 모델은 고르게(Models.pick)
        BOX.put(Kind.AUTOSHOT, new double[] {7, 0.04});
        BOX.put(Kind.DOUBLE, new double[] {7, 0});
        BOX.put(Kind.BOLTACTION, new double[] {6, 0.02});
        BOX.put(Kind.MACHINEPISTOL, new double[] {8, 0});
        BOX.put(Kind.DUAL, new double[] {7, 0});
        BOX.put(Kind.ANTIMATERIEL, new double[] {2.5, 0.12});
        BOX.put(Kind.SILENCED, new double[] {8, 0});
        BOX.put(Kind.MUSKET, new double[] {6, 0.03});
        BOX.put(Kind.LASER, new double[] {5, 0.08});
    }

    /** 상자 가중치(웨이브 wave, 지금 종류 current 는 1/3). 권총, 없는 종류는 0 */
    public static double boxWeight(Kind k, int wave, Kind current) {
        double[] w = BOX.get(k);
        if (w == null) return 0;
        double v = w[0] + w[1] * Math.max(0, wave - 1);
        return k == current ? v / 3 : v;
    }

    /** 무기 랜덤 상자 종류 하나를 굴림 */
    public static Kind rollKind(int wave, Kind current, Random rnd) {
        double sum = 0;
        for (Kind k : Kind.values()) sum += boxWeight(k, wave, current);
        double x = rnd.nextDouble() * sum;
        for (Kind k : Kind.values()) {
            x -= boxWeight(k, wave, current);
            if (x < 0) return k;
        }
        return Kind.RIFLE;
    }

    /** 무기 랜덤 상자 하나: 종류(가중치)와 등급(웨이브 확률표)을 함께 굴린 총 */
    public static Gun rollBox(int wave, Kind current, Random rnd) {
        return roll(rollKind(wave, current, rnd), rollRarity(wave, rnd), rnd);
    }

    public static Rarity rollRarity(int wave, Random rnd) {
        int[] o = odds(wave);
        int x = rnd.nextInt(100);
        for (int i = 0; i < o.length; i++) {
            if (x < o[i]) return Rarity.values()[i];
            x -= o[i];
        }
        return Rarity.COMMON;
    }

    // ------------------------------------------------------------------ 보이기

    /** 종류 한국어 이름 */
    public static String kindName(Kind k) {
        return Guns.SPECS.get(k).name();
    }

    /** 모델 한국어 이름(표에 없으면 종류 이름) */
    public String modelName() {
        Models.Model m = Models.of(model);
        return m == null || m.name() == null || m.name().isEmpty() ? kindName(kind) : m.name();
    }

    /** "희귀 AK-47 +3" */
    public String name() {
        return rarity.ko + " " + modelName() + (level > 0 ? " +" + level : "");
    }

    /** 등급 색 이름(기울임 없음) */
    public Component title() {
        return Component.text(name(), rarity.color()).decoration(TextDecoration.ITALIC, false);
    }

    private static final TextColor GOOD = TextColor.color(0x7CF07C), BAD = TextColor.color(0xFF7A7A), PLAIN = TextColor.color(0xD8D8D8),
        DIM = TextColor.color(0x8A8A8A), TRAIT = TextColor.color(0xFFC53D);

    /** 한 대상 지속 화력(초당 피해: 탄창을 다 쏘고 재장전까지 한 바퀴, 사람 증강 빼고). 상점 '화력' 줄 */
    public double power() {
        return Guns.sustained(spec());
    }

    /**
     * 상점 스탯 줄. 맨 위 '화력'(한 대상 지속 초당 피해)은 어떤 총과도 견줘 색을 매기고(나으면 초록, 못하면 빨강), 그 아래 값들은 other 가
     * 같은 종류일 때만 색을 매긴다(다른 종류끼리는 뜻이 달라 견줄 수 없다: 화염방사기 '피해 5'가 소총 14 보다 못한 것이 아님). 그 방식에서
     * 쓰지 않는 값(불길의 퍼짐, 레일건 관통, 유탄 사거리)은 줄을 내지 않는다. 연사는 실제 박자(점사 쉼, 모으기 포함), 재장전은 빈 총을 다
     * 채우는 시간(산탄총은 한 발씩 넣는 시간의 합). 사람 증강은 빼고 총끼리만 견줌
     */
    public List<Component> lore(Gun other) {
        Spec s = spec(), o = other == null ? null : other.spec();
        Spec same = o != null && other.kind == kind ? o : null;
        Guns.Fire fire = Guns.mode(kind).fire();
        List<Component> out = new ArrayList<>();
        String how = Guns.modeLine(s);
        if (how != null) out.add(Component.text(how, DIM).decoration(TextDecoration.ITALIC, false));
        out.add(line("화력", String.valueOf(Math.round(Guns.sustained(s))), Guns.sustained(s), o == null ? Double.NaN : Guns.sustained(o), true));
        out.add(line("피해", fmt(s.damage()) + (s.pellets() > 1 ? " x" + s.pellets() : ""), s.damage() * s.pellets(),
            same == null ? Double.NaN : same.damage() * same.pellets(), true));
        out.add(line("연사", fmt(Guns.shotsPerSecond(s)), Guns.shotsPerSecond(s), same == null ? Double.NaN : Guns.shotsPerSecond(same), true));
        out.add(line("탄창", String.valueOf(s.mag()), s.mag(), same == null ? Double.NaN : same.mag(), true));
        double rl = Guns.reloadTicks(s) / 20.0;
        out.add(line("재장전", fmt(rl) + "초", rl, same == null ? Double.NaN : Guns.reloadTicks(same) / 20.0, false));
        if (s.spread() > 0) out.add(line("퍼짐", fmt(s.spread()), s.spread(), same == null ? Double.NaN : same.spread(), false));
        if (pierces(fire) && s.pierce() > 0) out.add(line("관통", String.valueOf(s.pierce()), s.pierce(), same == null ? Double.NaN : same.pierce(), true));
        if (fire != Guns.Fire.LOB && fire != Guns.Fire.ROCKET)
            out.add(line("사거리", String.valueOf(Math.round(s.range())), s.range(), same == null ? Double.NaN : same.range(), true));
        for (Trait t : traits) out.add(Component.text(t.ko, TRAIT).decoration(TextDecoration.ITALIC, false));
        return out;
    }

    private static Component line(String label, String value, double v, double other, boolean higherBetter) {
        TextColor c = PLAIN;
        if (!Double.isNaN(other) && Math.abs(v - other) > 1e-6) c = (v > other) == higherBetter ? GOOD : BAD;
        return Component.text(label + " ", DIM).append(Component.text(value, c)).decoration(TextDecoration.ITALIC, false);
    }

    private static String fmt(double v) {
        if (Math.abs(v - Math.round(v)) < 0.05) return String.valueOf(Math.round(v));
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /** 시험 글: 종류 등급 단계 배율 특성 */
    public String info() {
        return String.format(Locale.ROOT, model + " %s %s +%d dmg=%.2f rate=%.2f mag=%.2f reload=%.2f spread=%.2f range=%.2f pierce=%d traits=%s", kind.name(),
            rarity.name(), level, damage, rate, mag, reload, spread, range, pierce, traits);
    }

    @Override
    public String toString() {
        return info();
    }
}
