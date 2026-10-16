package com.hapistavla.oyun;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Bilgisayar rakibi. Her zar atışının tüm sonuçlarını puanlayıp seçer.
 * Seviyeler arasındaki fark, hata payıdır: kolayda sık sık rastgele oynar,
 * ortada yakın hamleler arasında şaşırır, zorda en iyi puanlı hamleyi oynar.
 */
public final class Ai {
    public static final int EASY = 0;
    public static final int NORMAL = 1;
    public static final int HARD = 2;

    private static final Random RND = new Random();

    private Ai() {
    }

    public static List<Board.Move> chooseTurn(Board b, int p, int d1, int d2) {
        return chooseTurn(b, p, d1, d2, HARD);
    }

    /** Verilen zarlarla oynanacak hamle listesi. Oynanabilecek hamle yoksa boş liste döner. */
    public static List<Board.Move> chooseTurn(Board b, int p, int d1, int d2, int level) {
        List<Board.Turn> turns = Board.allTurns(b, p, d1, d2);
        if (turns.isEmpty()) {
            return new ArrayList<Board.Move>();
        }
        if (level <= EASY && RND.nextDouble() < 0.6) {
            return turns.get(RND.nextInt(turns.size())).moves;
        }
        double noise = level >= HARD ? 0.6 : (level == NORMAL ? 30.0 : 90.0);
        double best = -1e18;
        Board.Turn bestTurn = turns.get(0);
        for (Board.Turn t : turns) {
            double s = eval(t.result, p) + RND.nextDouble() * noise;
            if (s > best) {
                best = s;
                bestTurn = t;
            }
        }
        return bestTurn.moves;
    }

    /** İpucu: sıradaki oyuncu için en iyi görünen ilk hamle (yoksa null). */
    public static Board.Move hint(Board b, int p, int[] remaining) {
        List<Board.Move> allowed = Board.allowedMoves(b, p, remaining);
        Board.Move best = null;
        double bestScore = -1e18;
        for (Board.Move m : allowed) {
            Board nb = b.copy();
            nb.apply(p, m);
            double s = eval(nb, p);
            if (s > bestScore) {
                bestScore = s;
                best = m;
            }
        }
        return best;
    }

    /** Konumu, me oyuncusu açısından puanlar (büyük = iyi). */
    static double eval(Board b, int me) {
        int o = 1 - me;
        int w = b.winner();
        if (w == me) {
            return 1e6 + b.winPoints();
        }
        if (w == o) {
            return -1e6;
        }
        if (w == Board.TIE) {
            return 0;
        }
        double s = 0;
        // yarış: rakipten ne kadar önde olduğumuz
        s += b.pips(o) - b.pips(me);
        // toplanan taşlar
        s += 5.0 * (b.off[me] - b.off[o]);

        for (int t = 1; t <= 24; t++) {
            // hapisler
            if (b.pinned[t] == o) {
                int r = Board.rel(o, t);
                double v = 12 + r;
                if (r == 24) {
                    v += 50; // rakibin anne taşı
                }
                s += v;
            } else if (b.pinned[t] == me) {
                int r = Board.rel(me, t);
                double v = 12 + r;
                if (r == 24) {
                    v += 50;
                }
                s -= v;
            }
            // kapalı noktalar rakibi engeller
            if (b.cnt[me][t] >= 2) {
                s += 2.0;
            }
            // tek başına kalan serbest taş: hapsedilme riski
            if (b.cnt[me][t] == 1 && b.pinned[t] == Board.NONE) {
                double shots = 0;
                for (int u = 1; u <= 24; u++) {
                    if (b.cnt[o][u] == 0 || b.pinned[u] == o) {
                        continue;
                    }
                    int d = (o == Board.WHITE) ? u - t : t - u;
                    if (d >= 1 && d <= 6) {
                        shots += 1.0;
                    } else if (d >= 7 && d <= 12) {
                        shots += 0.35;
                    }
                }
                if (shots > 3) {
                    shots = 3;
                }
                double cost = 4 + 0.35 * Board.rel(me, t);
                if (Board.rel(me, t) == 24) {
                    cost += 15; // anne taş
                }
                s -= shots * cost;
            }
        }
        return s;
    }
}
