package kr.gave.gun;

/**
 * 사람 한 명의 총 배율(증강, 스탯을 모두 곱한 값). Augments 가 만들고 Guns 가 쏠 때마다 읽는다.
 * 굴린 총(Gun.mods())도 같은 꼴이라 유효 총 = 바탕 Spec x Gun.mods() x GunHost.mods(p) (Guns.effective).
 *
 * @param damage   피해 배율
 * @param rate     연사 배율(발/초에 곱함)
 * @param mag      탄창 배율
 * @param reload   재장전 시간 배율(작을수록 빠름)
 * @param spread   퍼짐 배율
 * @param recoil   반동 배율
 * @param range    사거리 배율
 * @param pierce   관통 더하기
 * @param pellets  산탄 알 더하기(산탄총에만 걸림, Guns.effective 가 한 알 총에서는 0 으로 둠)
 * @param crit     치명 확률(0..1, 치명은 피해 2배)
 * @param reserve  예비탄 배율
 */
public record GunMods(double damage, double rate, double mag, double reload, double spread, double recoil, double range,
        int pierce, int pellets, double crit, double reserve) {

    public static final GunMods NONE = new GunMods(1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 1);

    public GunMods times(GunMods o) {
        return new GunMods(damage * o.damage, rate * o.rate, mag * o.mag, reload * o.reload, spread * o.spread, recoil * o.recoil,
                range * o.range, pierce + o.pierce, pellets + o.pellets, Math.min(1, crit + o.crit), reserve * o.reserve);
    }

    // ------------------------------------------------------------------ 한 칸만 바꾼 사본(증강 정의 표가 씀)

    public GunMods mulDamage(double k) {
        return new GunMods(damage * k, rate, mag, reload, spread, recoil, range, pierce, pellets, crit, reserve);
    }

    public GunMods mulRate(double k) {
        return new GunMods(damage, rate * k, mag, reload, spread, recoil, range, pierce, pellets, crit, reserve);
    }

    public GunMods mulMag(double k) {
        return new GunMods(damage, rate, mag * k, reload, spread, recoil, range, pierce, pellets, crit, reserve);
    }

    public GunMods mulReload(double k) {
        return new GunMods(damage, rate, mag, reload * k, spread, recoil, range, pierce, pellets, crit, reserve);
    }

    public GunMods mulSpread(double k) {
        return new GunMods(damage, rate, mag, reload, spread * k, recoil, range, pierce, pellets, crit, reserve);
    }

    public GunMods mulRecoil(double k) {
        return new GunMods(damage, rate, mag, reload, spread, recoil * k, range, pierce, pellets, crit, reserve);
    }

    public GunMods mulRange(double k) {
        return new GunMods(damage, rate, mag, reload, spread, recoil, range * k, pierce, pellets, crit, reserve);
    }

    public GunMods mulReserve(double k) {
        return new GunMods(damage, rate, mag, reload, spread, recoil, range, pierce, pellets, crit, reserve * k);
    }

    public GunMods addPierce(int n) {
        return new GunMods(damage, rate, mag, reload, spread, recoil, range, pierce + n, pellets, crit, reserve);
    }

    public GunMods addPellets(int n) {
        return new GunMods(damage, rate, mag, reload, spread, recoil, range, pierce, pellets + n, crit, reserve);
    }

    public GunMods addCrit(double c) {
        return new GunMods(damage, rate, mag, reload, spread, recoil, range, pierce, pellets, Math.min(1, crit + c), reserve);
    }

    /** 산탄 알 더하기만 n 으로 바꾼 사본(한 알 총에는 0) */
    public GunMods withPellets(int n) {
        return new GunMods(damage, rate, mag, reload, spread, recoil, range, pierce, n, crit, reserve);
    }
}
