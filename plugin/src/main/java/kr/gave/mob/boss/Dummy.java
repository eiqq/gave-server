package kr.gave.mob.boss;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Species;

/**
 * 시험용 빈 보스(boss_infra, 2026-11-12): 아무 공격도 저절로 안 하는 큰 얼굴 하나(G01 모델 x7, 얼굴 폭 5.6블록 + 펄럭이는 막대기 둘). 보스 다섯
 * (kr.gave.mob.sp.B1 .. B5)이 아직 없는 웨이브에서 시험 훅 '보스 x 빈보스 on' 을 켰을 때만 그 웨이브 보스 자리(Species.Bx)로 나온다. 바탕 흐름
 * (등장, 보스 틀, 단계, 죽음, 무너짐 물결, 보상, 쉬는 시간, 엔딩)을 게임 안에서 돌려 보는 것이 목적이다.
 *
 * <p>공격은 모두 auto = false(시험 훅 '공격 <이름>' 으로만): slam(빨간 원, 맞은 자리가 10초 꺼짐), line(빨간 띠), ring(흰 파도), drop(검은 그림자),
 * call(졸개), safe(초록 고리), finger(맵 연출: 뼈 기둥 셋이 솟고 그 뒤에 숨지 않은 사람이 맞음). 보스 구현자가 공격을 짤 때의 본보기이기도 하다.
 */
final class Dummy extends Boss {

    static final float S = 7f;
    static final double HOVER = 4.6, WALK = 0.05, INTRO_WALK = 0.12, KEEP = 14;

    private final Region face, rodL, rodR, nose;

    Dummy(Game game, Species sp) {
        super(game, sp, new double[] {0.60, 0.25});
        flying = false;
        hover = HOVER;
        baseSpeed = WALK;
        radius = 3.0;
        ragHold = 500;
        face = main;
        rodL = region("rod_l", 1.0);
        rodR = region("rod_r", 1.0);
        rodL.hpFrac = rodR.hpFrac = 0.04;
        rodL.onCut = r -> cut("cut_l");
        rodR.onCut = r -> cut("cut_r");
        nose = region("nose", 3.0);
        nose.weak = true;
        attacks.add(slam());
        attacks.add(line());
        attacks.add(ring());
        attacks.add(drop());
        attacks.add(call());
        attacks.add(safeRing());
        attacks.add(finger());
        for (Attack a : attacks) a.auto = false;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g01", 3.2f * S, 1.6f * S);
        body.scale(S, 0);
        body.viewRange(3.0f);
    }

    // 이름과 큰 제목은 그 자리 보스 것(name_b1, t_b1: make_hud 가 도감 이름으로 그림)을 그대로 쓴다(HUD 폭 확인). 부제만 '시험용 빈 보스'
    @Override
    public String subKey() {
        return "bs_dummy_sub";
    }

    @Override
    public double shadowRadius() {
        return 7;
    }

    private void cut(String part) {
        if (body != null) body.look.on.add(part);
        quake(pos, 24, 0.6);
        sfx("sfx.stomp", Sound.ENTITY_RAVAGER_STEP, 1f, 0.5f, 96);
    }

    // ------------------------------------------------------------------ 구(얼굴 하나 + 막대기 둘 + 코 약점)

    @Override
    protected void refreshSpheres() {
        sphere(face, pos.getX(), pos.getY(), pos.getZ(), 0.42 * S);
        if (!rodL.cut) {
            double[] a = world(0.75, 0, -0.1, S), b = world(1.15, 0, -0.15, S);
            sphere(rodL, a[0], a[1], a[2], 0.22 * S);
            sphere(rodL, b[0], b[1], b[2], 0.2 * S);
        }
        if (!rodR.cut) {
            double[] a = world(-0.75, 0, -0.1, S), b = world(-1.15, 0, -0.15, S);
            sphere(rodR, a[0], a[1], a[2], 0.22 * S);
            sphere(rodR, b[0], b[1], b[2], 0.2 * S);
        }
        double[] n = world(0, -0.05, 0.4, S);
        sphere(nose, n[0], n[1], n[2], 0.09 * S);
    }

    // ------------------------------------------------------------------ 등장, 싸움, 죽음

    @Override
    protected void introStep(int t) {
        // 흙먼지 속에서 가운데로 걸어 나옴(땅울림 걸음은 감독이 이미 침)
        Vector c = kr.gave.world.Plaza.center(game.world).toVector();
        face(c.clone().setY(pos.getY()));
        seek(c, INTRO_WALK);
        if (t % 30 == 0) {
            quake(pos, 40, 0.5);
            sfx("sfx.stomp", Sound.ENTITY_RAVAGER_STEP, 1f, 0.45f, 96);
        }
        if (body != null) body.look.open = t >= 70 && t < 100;
    }

    @Override
    protected void act() {
        Player p = nearest(200);
        Vector c = crowdCenter();
        if (p != null) {
            double d = horiz(p.getLocation().toVector(), pos);
            if (d > KEEP) seek(c.clone().setY(pos.getY()), speed());
            else hold();
            face(chest(p));
        } else hold();
        runAttacks();
    }

    @Override
    protected void onPhase(int n) {
        // 그 자리 보스의 단계 글귀(bs_b1_p2 ..)가 있으면 바탕이 이미 알렸다. 없을 때만 시험 글귀
        String key = "bs_dummy_p" + n;
        if (!kr.gave.game.Hud.hasPhrase("bs_" + sp.key() + "_p" + n) && kr.gave.game.Hud.hasPhrase(key)) game.hud.alert(key, 4, 60);
        sfx("sfx.boss_roar", Sound.ENTITY_RAVAGER_ROAR, 1f, 0.5f, 192);
        quake(pos, 48, 0.8);
    }

    @Override
    public int dyingLength() {
        return 60;
    }

    @Override
    protected void dyingStep(int t) {
        hold();
        if (body != null) {
            body.look.open = false;
            body.look.freeze = t > 20;
        }
        // 머리가 뒤로 넘어가며 내려앉음
        pitch = (float) (-70 * Math.min(1, t / 40.0));
        hover = HOVER - 2.0 * Math.min(1, t / 50.0);
        if (body != null) body.pose(3);
        if (t == 1) sfx("sfx.boss_roar", Sound.ENTITY_RAVAGER_DEATH, 1f, 0.5f, 192);
        if (t == 45) {
            sfx("sfx.wipe", Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.5f, 160);
            quake(pos, 40, 1.0);
        }
    }

    // ------------------------------------------------------------------ 시험용 공격(본보기)

    /** 빨간 원: 표적 자리에 반지름 5, 차오르면 25 + 넘어짐 20틱 + 바깥으로 밀침. */
    private Attack slam() {
        return new Attack("slam", 1, 3, 180, 32, Pick.CLUSTER, true) {
            Tele tl;

            @Override
            protected void start() {
                tl = disc(aim, 5, tellTicks);
                sfx("sfx.stomp", Sound.ENTITY_WARDEN_SONIC_CHARGE, 0.8f, 0.6f, 64);
            }

            @Override
            protected boolean step() {
                if (!told()) return false;
                for (Player p : caught(tl, 0.3)) {
                    hitBoss(p, 25, "cause_dummy");
                    game.players.root(p, 20, "boss");
                    Vector out = p.getLocation().toVector().subtract(tl.at).setY(0);
                    knock(p, out.lengthSquared() < 1e-6 ? new Vector(1, 0, 0) : out, 0.6, 0.3);
                }
                quake(tl.at, 24, 0.9);
                sfx("sfx.stomp", Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.5f, 128);
                game.shotFx.explosion(tl.at.toLocation(game.world));
                // 맵 연출 본보기: 둘레가 그을리고(먼저: 그을음은 지금 땅 윗면에 그려짐) 맞은 자리가 6틱에 걸쳐 1블록 꺼진다. 200틱 뒤 30틱에 걸쳐 메워짐
                mend(scorch(tl.at, 6), 0, 230);
                mend(pit(tl.at, 4, 1, 6), 30, 200);
                return true;
            }
        };
    }

    /** 빨간 띠: 몸에서 표적 쪽 22블록, 폭 4. 차면 20 + 옆으로 밀침. */
    private Attack line() {
        return new Attack("line", 1, 3, 240, 24, Pick.TOP, false) {
            Tele tl;

            @Override
            protected void start() {
                Vector from = pos.clone().setY(0), dir = aim.clone().subtract(pos).setY(0);
                if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1);
                dir.normalize();
                tl = strip(from, from.clone().add(dir.multiply(22)), 4, tellTicks);
            }

            @Override
            protected boolean step() {
                if (!told()) return false;
                Vector d = tl.to.clone().subtract(tl.at).setY(0).normalize();
                for (Player p : caught(tl, 0.3)) {
                    hitBoss(p, 20, "cause_dummy");
                    knock(p, new Vector(-d.getZ(), 0, d.getX()), 1.0, 0.4);
                }
                sfx("sfx.stomp", Sound.ENTITY_RAVAGER_STEP, 1f, 0.4f, 96);
                return true;
            }
        };
    }

    /** 흰 파도 고리: 발밑 반지름 4 에서 22 까지 틱당 0.6. 지나는 순간 땅에 있으면 15 + 위로 0.5. */
    private Attack ring() {
        return new Attack("ring", 1, 3, 220, 20, Pick.NONE, true) {
            Tele tl;
            final java.util.Set<java.util.UUID> hitSet = new java.util.HashSet<>();

            @Override
            protected void start() {
                hitSet.clear();
                tl = null;
                hintAll("hint_bs_jump", 60);
            }

            @Override
            protected boolean step() {
                if (t == tellTicks) {
                    tl = wave(pos, 4, 22, 0.6);
                    sfx("sfx.quake", Sound.ENTITY_WARDEN_SONIC_BOOM, 1f, 0.6f, 128);
                    quake(pos, 30, 0.7);
                }
                if (tl == null) return false;
                for (Player p : caught(tl, 0)) {
                    if (!hitSet.add(p.getUniqueId())) continue;
                    hitBoss(p, 15, "cause_dummy");
                    knock(p, new Vector(0, 0, 0), 0, 0.5);
                }
                return tl.gone();
            }
        };
    }

    /** 검은 그림자: 표적 자리 반지름 4, 20틱 뒤 30. */
    private Attack drop() {
        return new Attack("drop", 1, 3, 160, 20, Pick.TOP, false) {
            Tele tl;

            @Override
            protected void start() {
                tl = shadow(aim, 4, tellTicks);
            }

            @Override
            protected boolean step() {
                if (!told()) return false;
                for (Player p : caught(tl, 0.2)) hitBoss(p, 30, "cause_dummy");
                quake(tl.at, 20, 0.6);
                sfx("sfx.stomp", Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.6f, 96);
                return true;
            }
        };
    }

    /** 졸개 부르기: 몸 앞에 G01 셋(회차만큼 더). */
    private Attack call() {
        return new Attack("call", 1, 3, 400, 12, Pick.NONE, false) {
            @Override
            protected void start() {
                sfx("sfx.horde_cry", Sound.ENTITY_ZOMBIE_VILLAGER_AMBIENT, 1f, 0.6f, 96);
            }

            @Override
            protected boolean step() {
                if (!told()) return false;
                int n = minions(3);
                for (int i = 0; i < n; i++) {
                    double a = Math.toRadians(-yaw) + (i - (n - 1) / 2.0) * 0.5;
                    summon(Species.G01, pos.clone().add(new Vector(Math.sin(a) * 6, -HOVER + 1, Math.cos(a) * 6)));
                }
                return true;
            }
        };
    }

    /**
     * 맵 연출 본보기(지형이 숨을 곳): 표적 둘레 5블록에 뼈 기둥 셋(반지름 1.2, 높이 6)이 16틱에 걸쳐 솟고 보스가 눈을 뜬다. 예고가 끝나고 30틱 뒤
     * 보스 눈에서 기둥에 가려지지 않은 사람은 20. 기둥은 그 뒤 170틱 서 있다가 20틱에 걸쳐 도로 꺼진다.
     */
    private Attack finger() {
        return new Attack("finger", 1, 3, 300, 30, Pick.TOP, false) {
            final int[] g = new int[3];

            @Override
            protected void start() {
                for (int i = 0; i < g.length; i++) {
                    double a = i * Math.PI * 2 / g.length + 0.4;
                    g[i] = pillar(aim.clone().add(new Vector(Math.cos(a) * 5, 0, Math.sin(a) * 5)), 1.2, 6, org.bukkit.Material.BONE_BLOCK, 16);
                }
                if (body != null) body.look.open = true;
                hintAll("hint_bs_shade", 60);
            }

            @Override
            protected boolean step() {
                if (t == tellTicks + 30) {
                    Vector eye = pos.clone();
                    for (Player p : game.mobs.standing()) if (!hidden(eye, p)) hitBoss(p, 20, "cause_dummy");
                    sfx("sfx.boss_roar", Sound.ENTITY_RAVAGER_ROAR, 0.8f, 0.6f, 128);
                    if (body != null) body.look.open = false;
                }
                return t >= tellTicks + 200;
            }

            @Override
            protected void end() {
                if (body != null) body.look.open = false;
                for (int x : g) mend(x, 20);
            }
        };
    }

    /** 초록 안전 고리: 사람들 가운데 반지름 4, 60틱. */
    private Attack safeRing() {
        return new Attack("safe", 1, 3, 400, 12, Pick.NONE, false) {
            @Override
            protected void start() {
                safe(crowdCenter(), 4, 60);
                hintAll("hint_bs_safe", 60);
            }

            @Override
            protected boolean step() {
                return t >= 60;
            }
        };
    }
}
