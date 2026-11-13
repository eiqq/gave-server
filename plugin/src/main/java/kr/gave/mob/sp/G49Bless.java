package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.mob.Species;
import kr.gave.player.PState;
import net.kyori.adventure.text.Component;

/**
 * G49 축복(Players.latent 의 kind "g49"). 축복을 준 게이브가 죽어도 축복(공격력, 재화)은 40초 끝까지 남고 변이만 풀리므로, 종 개체가 아닌
 * 플러그인 작업 하나가 매 틱 돈다(축복이 하나도 없으면 멈춤, G30Infest 와 같은 꼴).
 *
 * <p>축복 40초: 맞힌 탄마다 그 총 피해의 60% 를 덤으로 한 번 더(조준선 위 게이브에게), 번 재화는 두 배.
 * 변이(잠복이 살아 있는 동안): 얼굴에 게이브 홍조, 점 입, 실눈이 3단계로 드러나 남들에게 보이고(머리 칸 덧그림 gave:gabe/g49_mask),
 * 본인 화면 가장자리에 홍조 덮개(g49_bless1 .. 3), 머리 위에 분홍 글씨(시스템 글리프 ◇ + '같이 가자', 남은 초). 40초가 다 되면 그 사람은
 * 쓰러지고 그 자리에 가짜 동료(G49Fake)가 생긴다. 변이는 준 게이브를 잡거나, 정화, 동료의 짜내기(등 뒤 돕기 3초)로 풀린다(축복은 남음).
 * 웨이브가 끝나도 변이는 풀린다.
 *
 * <p>도감과 다른 점: 공격력은 총 배율을 종 쪽에서 걸 길이 없어 '맞힌 탄마다 덤 피해'로 냈다(요청: 사람별 공격력 배율 칸).
 */
final class G49Bless {

    static final String KIND = "g49";
    /** 축복 틱(40초), 변이 단계 하나의 틱. */
    static final int TICKS = 800, STAGE = 267;
    /** 맞힌 탄마다 덤 피해(그 총 한 발 피해의 몇 배). */
    static final double BOOST = 0.6;
    /** 머리 위 글 높이(발에서). */
    static final double TAG_UP = 2.55;

    private static G49Bless inst;

    final Game game;
    private final Map<UUID, B> list = new HashMap<>();
    private BukkitTask task;

    private static final class B {
        final UUID id;
        final long start;
        final G49 src;
        boolean mutation = true;
        int stage, coverStage, lastHits, tagSec = -1;
        long lastEarned;
        TextDisplay tag;

        B(UUID id, long start, G49 src) {
            this.id = id;
            this.start = start;
            this.src = src;
        }
    }

    private G49Bless(Game game) {
        this.game = game;
    }

    static G49Bless of(Game g) {
        if (inst == null || inst.game != g) inst = new G49Bless(g);
        return inst;
    }

    boolean blessed(Player p) {
        return list.containsKey(p.getUniqueId());
    }

    /** 축복을 건다(한 사람 하나, 잠복 하나, 판 전체 상한, 비옷이 막을 수 있음). 걸렸으면 true. */
    boolean bless(Player p, G49 src) {
        UUID id = p.getUniqueId();
        if (list.containsKey(id)) return false;
        B b = new B(id, game.now(), src);
        boolean ok = game.players.latent(p, KIND, TICKS, q -> pop(q, b), q -> cured(q, b));
        if (!ok) return false;
        Guns.Arms a = game.guns.arms(p);
        b.lastHits = a == null ? 0 : a.hits;
        b.lastEarned = game.players.get(p).earned;
        list.put(id, b);
        if (task == null || task.isCancelled()) task = game.plugin.getServer().getScheduler().runTaskTimer(game.plugin, this::tick, 1, 1);
        return true;
    }

    /** 축복을 준 게이브가 죽음: 변이만 풀린다(축복은 남음). */
    void sourceGone(G49 src) {
        for (B b : new ArrayList<>(list.values())) {
            if (b.src != src || !b.mutation) continue;
            Player p = Bukkit.getPlayer(b.id);
            if (p == null) continue;
            if (KIND.equals(game.players.get(p).latent)) game.players.cure(p);
            else cured(p, b);
        }
    }

    private void tick() {
        if (list.isEmpty() || game.state == Game.State.LOBBY || game.state == Game.State.OVER) {
            for (B b : new ArrayList<>(list.values())) end(b);
            list.clear();
            if (task != null) task.cancel();
            task = null;
            return;
        }
        long now = game.now();
        for (Iterator<B> it = new ArrayList<>(list.values()).iterator(); it.hasNext();) {
            B b = it.next();
            Player p = Bukkit.getPlayer(b.id);
            if (p == null || !p.isOnline()) {
                clearVisual(null, b);
                list.remove(b.id);
                continue;
            }
            PState s = game.players.get(p);
            long el = now - b.start;
            boolean buff = el < TICKS;
            if (b.mutation && !KIND.equals(s.latent)) {
                // 잠복이 사라짐(죽음, 갇힘으로 엔진이 지움): 변이도 끝
                b.mutation = false;
                clearVisual(p, b);
            }
            if (b.mutation && game.state != Game.State.WAVE) {
                // 웨이브가 끝나면 변이는 풀린다
                game.players.cure(p);
                b.mutation = false;
                clearVisual(p, b);
            }
            if (buff) boost(p, s, b);
            if (b.mutation && s.standing()) visual(p, s, b, el, now);
            else if (b.tag != null) tagMove(p, b);
            if (buff && now % 5 == 0) game.hud.meter(p, "g49", 1.0 - el / (double) TICKS, 10);
            if (!buff && !b.mutation) {
                clearVisual(p, b);
                list.remove(b.id);
            }
        }
    }

    /** 축복: 맞힌 탄마다 덤 피해, 번 재화 두 배. 서 있지 않을 때는 기준만 맞춰 둔다. */
    private void boost(Player p, PState s, B b) {
        Guns.Arms a = game.guns.arms(p);
        if (a != null) {
            int dh = a.hits - b.lastHits;
            b.lastHits = a.hits;
            if (dh > 0 && s.standing()) {
                Guns.Spec g = game.guns.held(p);
                Gabe t = game.mobs.looked(p, 96);
                if (g != null && t != null && !t.dead) {
                    Vector dir = p.getEyeLocation().getDirection();
                    game.mobs.damage(t, new Damage(g.damage() * BOOST * Math.min(dh, 12), DamageType.OTHER, p, t.pos.clone(), dir, "bless", 0));
                }
            }
        }
        long e = s.earned;
        if (e > b.lastEarned && s.standing()) {
            game.players.addMoneyRaw(p, (int) Math.min(100000, e - b.lastEarned)); // 번 돈(이미 돈 배율 든 값)만큼 한 번 더: 배율을 또 곱하지 않음
        }
        b.lastEarned = s.earned;
    }

    private ItemStack mask(int stage) {
        return Body.item("gabe/g49_mask", "m" + stage, new LookState(), game.worldTime());
    }

    /** 변이가 드러남: 머리 칸 덧그림(남들에게), 화면 가장자리 홍조(본인), 머리 위 분홍 글과 남은 초. */
    private void visual(Player p, PState s, B b, long el, long now) {
        int st = Math.max(1, Math.min(3, 1 + (int) (el / STAGE)));
        if (st != b.stage || (now % 40 == 0 && s.headItem == null)) {
            if (s.headItem == null || s.headBy == b.src) {
                b.stage = st;
                game.players.attachHead(p, mask(st), b.src);
            }
        }
        if (st != b.coverStage || s.cover == null) {
            if (s.cover != null && s.cover.startsWith("g49_bless") && st != b.coverStage) game.players.uncover(p);
            if (s.cover == null && game.players.cover(p, "g49_bless" + st, TICKS)) {
                s.coverDetach = false;
                b.coverStage = st;
            }
        }
        if (b.tag == null || !b.tag.isValid()) {
            b.tag = G49Fake.tag(p.getLocation().add(0, TAG_UP, 0), 0f, 1.0f);
            p.hideEntity(game.plugin, b.tag);
            b.tagSec = -1;
        }
        tagMove(p, b);
        int sec = (int) Math.max(0, (TICKS - el + 19) / 20);
        if (sec != b.tagSec) {
            b.tagSec = sec;
            b.tag.text(Component.text(G49Fake.WORDS, G49Fake.PINK).append(Component.newline()).append(Component.text(Integer.toString(sec), G49Fake.PINK)));
        }
    }

    private void tagMove(Player p, B b) {
        if (b.tag == null || !b.tag.isValid()) return;
        Location l = p.getLocation();
        b.tag.teleport(new Location(l.getWorld(), l.getX(), l.getY() + TAG_UP, l.getZ()));
    }

    /** 변이 겉모습을 지운다(덧그림, 덮개, 머리 위 글). */
    private void clearVisual(Player p, B b) {
        if (b.tag != null) b.tag.remove();
        b.tag = null;
        if (p == null) return;
        PState s = game.players.get(p);
        if (s.headItem != null && s.headBy == b.src) game.players.attachHead(p, null);
        if (s.cover != null && s.cover.startsWith("g49_bless")) game.players.uncover(p);
        b.stage = b.coverStage = 0;
    }

    /** 40초가 다 됨(잠복이 터짐): 그 사람은 쓰러지고 그 자리에 가짜 동료가 생긴다. */
    private void pop(Player p, B b) {
        b.mutation = false;
        clearVisual(p, b);
        list.remove(b.id);
        PState s = game.players.get(p);
        Location at = p.getLocation();
        if (s.standing()) {
            if (b.src != null && !b.src.dead) b.src.hit(p, 1000, "cause_g49");
            else game.players.hurt(p, 1000, null, "cause_g49");
        }
        Player like = s.noLikeness ? null : p;
        Vector v = at.toVector();
        try {
            v.setY(game.mobs.flow.floor(v.getX(), v.getZ()) + 0.9);
        } catch (RuntimeException e) {
            v.setY(at.getY() + 0.9);
        }
        G49Fake fake = new G49Fake(game, Species.G49, like);
        fake.yaw = at.getYaw();
        if (game.mobs.place(fake, v) != null) game.hud.notice("notice_g49_fake", Species.G49, 60);
    }

    /** 잠복이 풀림(정화, 짜내기, 준 게이브가 죽음): 변이만 걷힌다. */
    private void cured(Player p, B b) {
        if (!b.mutation) return;
        b.mutation = false;
        clearVisual(p, b);
    }

    private void end(B b) {
        Player p = Bukkit.getPlayer(b.id);
        if (p != null && b.mutation && KIND.equals(game.players.get(p).latent)) game.players.cure(p);
        b.mutation = false;
        clearVisual(p, b);
    }

    /** 시험, 기록용: 지금 축복 수. */
    int count() {
        return list.size();
    }

    List<UUID> ids() {
        return new ArrayList<>(list.keySet());
    }
}
