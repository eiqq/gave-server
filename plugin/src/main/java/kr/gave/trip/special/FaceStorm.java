package kr.gave.trip.special;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.Props;
import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.LookState;
import kr.gave.mob.Role;
import kr.gave.player.PState;
import kr.gave.trip.Rounds;
import kr.gave.trip.Trip;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * 특수 18 얼굴 폭풍(3~4막, specials_draft facestorm): 재 폭풍에 작은 얼굴이 섞여 날린다. 바람은 {@link LateTune#STORM_TURN} 틱마다 90도 돈다
 * (돌기 3초 전 바람 쪽 화살촉). 바람을 마주 보는 동안 화면에 얼굴이 하나씩 붙는다(덮개: 1~3 은 g17_lump1..3 의 얼굴 무더기, 넷이면 g35_face 온 얼굴).
 * 트럭 뒤(바람 그늘)는 붙지 않는다. 좌클릭 {@link LateTune#STORM_CLICKS} 번이면 하나 떼고, 동료를 보고 좌클릭하면 그 동료 것을 하나 뗀다.
 * 넷이 붙은 사람은 남들 눈에 머리가 게이브 얼굴로 보인다(Players.attachHead: 아군 사격을 부름). 바람 쪽에서 오는 게이브는 빠르다. 끝: 다 잡기.
 *
 * <p>HUD: 덮개는 Players.cover(한 번에 하나: 다른 종의 덮개가 걸려 있으면 그것이 걷힐 때까지 기다림). 조각 덮개(fs_face1..4)는 HUD 요청.
 */
public final class FaceStorm extends Late {

    private static final String[] COVER = {null, "g17_lump1", "g17_lump2", "g17_lump3", "g35_face"};
    private static final String HEAD_MODEL = "gabe/g01", HOOK = "trip_facestorm";

    private double windA;
    private long nextTurn = -1, turnWarn = -1;
    private int turnDir = 1;
    private final Map<UUID, int[]> state = new HashMap<>();
    private final Deque<ItemDisplay> flyers = new ArrayDeque<>();
    private int stuck, cleared, turns;

    public FaceStorm(Game game, Trip trip, Rounds rounds) {
        super(game, trip, rounds);
    }

    /** 사람마다 [얼굴 수, 마주 본 틱, 좌클릭 수, 보낸 덮개 단계]. */
    private int[] st(Player p) {
        return state.computeIfAbsent(p.getUniqueId(), k -> new int[4]);
    }

    @Override
    protected boolean begin() {
        state.clear();
        stuck = cleared = turns = 0;
        windA = rnd.nextDouble() * Math.PI * 2;
        turnDir = rnd.nextBoolean() ? 1 : -1;
        nextTurn = game.now() + LateTune.STORM_TURN;
        turnWarn = nextTurn - 60;
        fog(LateTune.STORM_FOG);
        darken(act() >= 4 ? 0.75 : 0.55);
        game.hud.waveFrom("sp_facestorm_wind", 80, yawFrom(windA));
        // 좌클릭: 일으키기, 동료 떼기(엔진 detach: 덮개와 머리를 걷음 = 얼굴 하나) 뒤의 돕기 갈고리 차례
        game.players.addHelpHook(HOOK, this::hook);
        return true;
    }

    @Override
    protected void end() {
        game.players.removeHelpHook(HOOK);
        for (Player p : game.players.inPlay()) {
            int[] s = state.get(p.getUniqueId());
            if (s == null) continue;
            setFaces(p, s, 0);
        }
        state.clear();
        for (ItemDisplay d : flyers) if (d.isValid()) d.remove();
        flyers.clear();
    }

    @Override
    public void plan(Director.Plan p) {
        scaleCounts(p, LateTune.STORM_UNITS);
    }

    @Override
    protected double mulFor(String k) {
        return k.equals("dmg") ? LateTune.STORM_DMG : 1;
    }

    /** 바람이 오는 쪽 단위 벡터(수평). */
    private Vector from() {
        return new Vector(Math.cos(windA), 0, Math.sin(windA));
    }

    /** 가운데에서 본 각 a(라디안) 쪽을 마크 yaw 로(0 남, 90 서, 180 북, 270 동). */
    private static double yawFrom(double a) {
        return Math.toDegrees(Math.atan2(-Math.cos(a), Math.sin(a)));
    }

    @Override
    protected void step(long t) {
        long now = game.now();
        if (now == turnWarn) game.hud.waveFrom("sp_facestorm_wind", 60, yawFrom(windA + turnDir * Math.PI / 2));
        if (now >= nextTurn) {
            windA += turnDir * Math.PI / 2;
            if (rnd.nextDouble() < 0.3) turnDir = -turnDir;
            nextTurn = now + LateTune.STORM_TURN;
            turnWarn = nextTurn - 60;
            turns++;
            Props.soundAround(loc(new Vector(Plaza.CX + 0.5, 0, Plaza.CZ + 0.5), 2), "sfx.wind", Sound.ITEM_ELYTRA_FLYING, 1.4f, 0.8f, 0.6f, 200, -1);
        }
        Vector from = from();
        // 사람: 바람을 마주 보면 얼굴이 붙음, 바람에 밀림
        for (Player p : game.players.standing()) {
            int[] s = st(p);
            Location l = p.getLocation();
            boolean lee = lee(l, from);
            if (!lee) game.players.push(p, from.clone().multiply(-LateTune.STORM_PUSH));
            Vector look = l.getDirection().setY(0);
            if (look.lengthSquared() > 1e-6) look.normalize();
            if (!lee && look.dot(from) >= LateTune.STORM_COS) {
                s[1]++;
                if (s[1] >= LateTune.STORM_FACE_TICKS && s[0] < 4) {
                    s[1] = 0;
                    setFaces(p, s, s[0] + 1);
                    stuck++;
                    Props.soundTo(p, p.getEyeLocation(), "gabe.g17.heart", Sound.ENTITY_SLIME_SQUISH, 1f, 1f, 0.8f, -1);
                }
            } else s[1] = Math.max(0, s[1] - 1);
            if (now % 10 == 0 && s[0] > 0) refresh(p, s);
            if (now % 4 == 0) p.spawnParticle(Particle.WHITE_ASH, l.getX() + from.getX() * 6, l.getY() + 1.5, l.getZ() + from.getZ() * 6, 40, 5, 2, 5, 0);
        }
        // 바람 쪽에서 오는 게이브는 빠름
        for (Gabe g : game.mobs.all()) {
            if (g.dead || g.removed || g.sp.is(Role.BOSS)) continue;
            double dx = g.pos.getX() - Plaza.CX - 0.5, dz = g.pos.getZ() - Plaza.CZ - 0.5;
            if (dx * from.getX() + dz * from.getZ() > 0) g.speedBuff *= LateTune.STORM_SPEED;
        }
        flyTick(now, from);
        if (now % 80 == 0) Props.soundAround(loc(new Vector(Plaza.CX + 0.5, 0, Plaza.CZ + 0.5), 2), "sfx.wind", Sound.ITEM_ELYTRA_FLYING, 0.9f, 1f, 0.7f, 200, -1);
    }

    /** 바람 그늘: 트럭에서 STORM_LEE 안이고 트럭보다 바람 아래쪽. */
    private boolean lee(Location l, Vector from) {
        double dx = l.getX() - Truck.OX, dz = l.getZ() - Truck.OZ;
        if (truck() == null || dx * dx + dz * dz > LateTune.STORM_LEE * LateTune.STORM_LEE) return false;
        return dx * from.getX() + dz * from.getZ() < -1.2;
    }

    /** 바람에 날리는 작은 얼굴(사람 둘레를 바람 위에서 아래로 스쳐 감). */
    private void flyTick(long now, Vector from) {
        while (!flyers.isEmpty() && !flyers.peekFirst().isValid()) flyers.pollFirst();
        if (now % 6 != 0 || flyers.size() >= 14) return;
        List<Player> ps = game.players.standing();
        if (ps.isEmpty()) return;
        Player p = ps.get(rnd.nextInt(ps.size()));
        Location l = p.getLocation();
        Vector side = new Vector(-from.getZ(), 0, from.getX()).multiply((rnd.nextDouble() - 0.5) * 16);
        Location a = l.clone().add(from.clone().multiply(22)).add(side).add(0, 1 + rnd.nextDouble() * 3, 0);
        Location b = l.clone().add(from.clone().multiply(-22)).add(side).add(0, 0.5 + rnd.nextDouble() * 3, 0);
        LookState ls = new LookState();
        ls.rate = 3;
        ls.phase = rnd.nextInt(80);
        String model = Look.has("gabe/g02") ? "gabe/g02" : HEAD_MODEL;
        ItemStack it = Body.item(model, model.equals("gabe/g02") ? "lod" : "", ls, game.worldTime());
        float sc = (float) (0.5 + rnd.nextDouble() * 0.5) * Look.scale(model);
        ItemDisplay d = game.world.spawn(a, ItemDisplay.class, e -> {
            e.setPersistent(false);
            e.setItemStack(it);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setBillboard(Display.Billboard.CENTER);
            e.setBrightness(new Display.Brightness(8, 8));
            e.setViewRange(1.2f);
            e.setTransformationMatrix(new Matrix4f().scale(sc));
            e.setTeleportDuration(30);
        });
        flyers.addLast(d);
        keep(d);
        game.plugin.getServer().getScheduler().runTaskLater(game.plugin, () -> {
            if (d.isValid()) d.teleport(b);
        }, 2);
        game.plugin.getServer().getScheduler().runTaskLater(game.plugin, () -> {
            if (d.isValid()) d.remove();
        }, 34);
    }

    /** 얼굴 수를 n 으로(덮개, 머리). */
    private void setFaces(Player p, int[] s, int n) {
        n = Math.max(0, Math.min(4, n));
        PState ps = game.players.get(p);
        String want = COVER[n];
        boolean mine = ps.cover != null && isMine(ps.cover);
        if (want == null) {
            if (mine) game.players.uncover(p);
            s[3] = 0;
        } else if (ps.cover == null || mine || ps.coverUntil <= game.now()) {
            if (mine) game.players.uncover(p);
            s[3] = game.players.cover(p, want, 20 * 30) ? n : 0;
        } else s[3] = 0;
        if (n >= 4 && s[0] < 4) game.players.attachHead(p, Body.item(HEAD_MODEL, "", new LookState(), game.worldTime()));
        if (n < 4 && s[0] >= 4 && ps.headBy == null) game.players.attachHead(p, null);
        s[0] = n;
    }

    private static boolean isMine(String cover) {
        for (String c : COVER) if (c != null && c.equals(cover)) return true;
        return false;
    }

    /**
     * 덮개 살피기: 동료가 떼어 주면(엔진 Players.detach 가 덮개와 머리를 한꺼번에 걷음) 얼굴 하나를 뗀 것으로 치고 한 단계 낮은 덮개를 다시 씌운다.
     * 다른 종의 덮개가 걸려 있으면 그것이 걷힐 때까지 기다린다. 덮개가 다 되어 가면 다시 씌운다.
     */
    private void refresh(Player p, int[] s) {
        PState ps = game.players.get(p);
        if (ps.cover == null && s[3] > 0) {
            s[3] = 0;
            cleared++;
            setFaces(p, s, s[0] - 1);
            return;
        }
        boolean mine = ps.cover != null && isMine(ps.cover);
        if (ps.cover == null || mine && (!ps.cover.equals(COVER[s[0]]) || ps.coverUntil - game.now() < 100)) setFaces(p, s, s[0]);
    }

    /** 돕기 갈고리: 동료를 보고 누르면 그 동료 얼굴 하나, 아니면 내 얼굴(STORM_CLICKS 번에 하나). */
    private boolean hook(Player p, Player mate) {
        if (!running || ended() || game.state != Game.State.WAVE) return false;
        if (mate != null) {
            int[] m = st(mate);
            if (m[0] > 0) {
                setFaces(mate, m, m[0] - 1);
                cleared++;
                Props.soundTo(mate, mate.getEyeLocation(), null, Sound.ENTITY_SLIME_JUMP_SMALL, 1f, 1.2f, 1.2f, -1);
                return true;
            }
            return false;
        }
        int[] s = st(p);
        if (s[0] <= 0) return false;
        if (++s[2] >= LateTune.STORM_CLICKS) {
            s[2] = 0;
            setFaces(p, s, s[0] - 1);
            cleared++;
            Props.soundTo(p, p.getEyeLocation(), null, Sound.ENTITY_SLIME_JUMP_SMALL, 1f, 1f, 1f, -1);
        }
        return true;
    }

    @Override
    protected void hudMore(Player p, Map<String, Object> out) {
        int[] s = state.get(p.getUniqueId());
        out.put("sp.faces", s == null ? 0 : s[0]);
        out.put("sp.wind", yawFrom(windA));
    }

    @Override
    protected String statusMore() {
        int sum = 0, four = 0;
        for (int[] s : state.values()) {
            sum += s[0];
            if (s[0] >= 4) four++;
        }
        return String.format(Locale.ROOT, "wind=%.0f faces=%d full=%d stuck=%d cleared=%d turns=%d flyers=%d", yawFrom(windA), sum, four, stuck, cleared, turns, flyers.size());
    }

    @Override
    protected String testMore(Player p, String sub, String[] a) {
        if (p == null) return null;
        switch (sub) {
            case "얼굴" -> {
                int n = a.length > 4 ? Integer.parseInt(a[4]) : st(p)[0] + 1;
                setFaces(p, st(p), n);
                return "얼굴 " + st(p)[0] + " cover=" + game.players.get(p).cover;
            }
            case "바람보기" -> {
                Location l = p.getLocation();
                l.setYaw((float) yawFrom(windA));
                l.setPitch(0);
                p.teleport(l);
                return "바람 쪽을 봄 " + statusMore();
            }
            case "떼기" -> {
                return "떼기=" + hook(p, null) + " 얼굴 " + st(p)[0];
            }
            default -> {
                return null;
            }
        }
    }
}
