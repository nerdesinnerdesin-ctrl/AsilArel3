package com.hapistavla.oyun;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hapis tavla (Mahbus / Plakoto) tahtası ve kuralları. Android'den bağımsızdır.
 *
 * Noktalar 1..24. BEYAZ 24'ten 1'e, SİYAH 1'den 24'e doğru ilerler.
 * Beyaz'ın evi 1..6, Siyah'ın evi 19..24'tür. Oyun başında her oyuncunun
 * 15 taşı, rakibin başlangıç noktasındadır (Beyaz 24'te, Siyah 1'de).
 *
 * Taş vurulmaz: rakibin tek taşının olduğu noktaya inen taş onu hapseder.
 * Hapsedilen taş, hapseden taşlar o noktadan tamamen ayrılana kadar oynayamaz.
 *
 * İlk el kuralı: her oyuncunun ilk elinde en az 2 farklı taş oynanmak zorundadır
 * (mümkünse). İlk elde bütün taşlar başlangıç noktasında olduğundan, bu "başlangıç
 * noktasından en az 2 hamle" demektir.
 */
public final class Board {
    public static final int WHITE = 0;
    public static final int BLACK = 1;
    public static final int NONE = -1;
    public static final int TIE = 2;
    /** Hamlenin hedefi: taş toplama. */
    public static final int OFF = -1;

    /** cnt[oyuncu][nokta]: noktadaki taş sayısı (hapsedilen taş dahil). */
    public final int[][] cnt = new int[2][25];
    /** pinned[nokta]: o noktada taşı hapsedilmiş oyuncu, yoksa NONE. Hapsedilenin orada tam 1 taşı vardır. */
    public final int[] pinned = new int[25];
    /** Toplanan (tahtadan çıkan) taş sayısı. */
    public final int[] off = new int[2];
    /** Oyuncunun ilk eli henüz bitmedi mi? */
    public final boolean[] firstPending = {true, true};
    /** Sıradaki oyuncunun ilk eliyse, bu elde başlangıç noktasından yaptığı hamle sayısı. */
    public int firstStartMoves = 0;

    /** Tek bir zarla yapılan tek bir hamle. */
    public static final class Move {
        public final int from;
        public final int to; // 1..24 ya da OFF
        public final int die;

        public Move(int from, int to, int die) {
            this.from = from;
            this.to = to;
            this.die = die;
        }

        @Override
        public String toString() {
            return from + "->" + (to == OFF ? "off" : String.valueOf(to)) + " (" + die + ")";
        }
    }

    /** Bir zar atışının tamamı: hamleler ve sonuçta oluşan tahta. */
    public static final class Turn {
        public final List<Move> moves;
        public final Board result;

        public Turn(List<Move> moves, Board result) {
            this.moves = moves;
            this.result = result;
        }
    }

    /** Tek taşla, iki ya da daha fazla zarı art arda kullanarak yapılan hamle (zar toplamı). */
    public static final class Combo {
        public final int to;
        public final List<Move> moves;
        public final int pins;

        public Combo(int to, List<Move> moves, int pins) {
            this.to = to;
            this.moves = moves;
            this.pins = pins;
        }

        /** Kat edilen toplam mesafe. */
        public int total() {
            int s = 0;
            for (Move m : moves) {
                s += m.die;
            }
            return s;
        }
    }

    public Board() {
        Arrays.fill(pinned, NONE);
    }

    public static Board initial() {
        Board b = new Board();
        b.cnt[WHITE][24] = 15;
        b.cnt[BLACK][1] = 15;
        return b;
    }

    public Board copy() {
        Board b = new Board();
        for (int p = 0; p < 2; p++) {
            System.arraycopy(cnt[p], 0, b.cnt[p], 0, 25);
            b.off[p] = off[p];
            b.firstPending[p] = firstPending[p];
        }
        System.arraycopy(pinned, 0, b.pinned, 0, 25);
        b.firstStartMoves = firstStartMoves;
        return b;
    }

    public static int opp(int p) {
        return 1 - p;
    }

    /** Oyuncunun kendi yönüne göre nokta numarası: 24 = başlangıç, 1 = evin son noktası. */
    public static int rel(int p, int pt) {
        return p == WHITE ? pt : 25 - pt;
    }

    public static int startPoint(int p) {
        return p == WHITE ? 24 : 1;
    }

    /** Kalan toplam mesafe (pip). */
    public int pips(int p) {
        int sum = 0;
        for (int s = 1; s <= 24; s++) {
            sum += cnt[p][s] * rel(p, s);
        }
        return sum;
    }

    public boolean allHome(int p) {
        for (int s = 1; s <= 24; s++) {
            if (cnt[p][s] > 0 && rel(p, s) > 6) {
                return false;
            }
        }
        return true;
    }

    /** p oyuncusu t noktasına inebilir mi? */
    public boolean canLand(int p, int t) {
        int o = 1 - p;
        if (pinned[t] == o) {
            return true; // rakibin taşını ben hapsetmişim: burası benim noktam
        }
        if (pinned[t] == p) {
            return false; // benim taşım hapiste: burası rakibin kalesi
        }
        return cnt[o][t] < 2;
    }

    private boolean hasCheckerFurther(int p, int r) {
        for (int s = 1; s <= 24; s++) {
            if (cnt[p][s] > 0 && rel(p, s) > r) {
                return true;
            }
        }
        return false;
    }

    /** Tek bir zar değeri için yapılabilecek tüm hamleler (zorunlu-zar kuralları hariç). */
    public List<Move> legalMoves(int p, int die) {
        List<Move> res = new ArrayList<Move>();
        boolean home = allHome(p);
        for (int s = 1; s <= 24; s++) {
            if (cnt[p][s] == 0 || pinned[s] == p) {
                continue;
            }
            int t = (p == WHITE) ? s - die : s + die;
            if (t >= 1 && t <= 24) {
                if (canLand(p, t)) {
                    res.add(new Move(s, t, die));
                }
            } else if (home) {
                int r = rel(p, s);
                if (r == die || (r < die && !hasCheckerFurther(p, r))) {
                    res.add(new Move(s, OFF, die));
                }
            }
        }
        return res;
    }

    /** Hamleyi uygular (yasallık denetlenmez). */
    public void apply(int p, Move m) {
        int o = 1 - p;
        cnt[p][m.from]--;
        if (cnt[p][m.from] == 0 && pinned[m.from] == o) {
            pinned[m.from] = NONE; // hapseden ayrıldı, tutsak serbest
        }
        if (firstPending[p] && m.from == startPoint(p)) {
            firstStartMoves++;
        }
        if (m.to == OFF) {
            off[p]++;
            return;
        }
        int t = m.to;
        if (pinned[t] != o && cnt[o][t] == 1) {
            pinned[t] = o; // tek taşı hapset
        }
        cnt[p][t]++;
    }

    /** p oyuncusunun eli bitti: ilk el kuralı artık geçerli değil. */
    public void endTurn(int p) {
        firstPending[p] = false;
        firstStartMoves = 0;
    }

    /** Bu eldeki hamleler ilk el kuralını sağlıyor mu? (İlk el değilse her zaman evet.) */
    public boolean firstOk(int p) {
        return !firstPending[p] || firstStartMoves >= 2;
    }

    /** NONE: oyun sürüyor. WHITE/BLACK: kazanan. TIE: berabere. */
    public int winner() {
        boolean whiteMotherPinned = pinned[24] == WHITE;
        boolean blackMotherPinned = pinned[1] == BLACK;
        if (whiteMotherPinned && blackMotherPinned) {
            return TIE;
        }
        if (whiteMotherPinned && cnt[BLACK][1] == 0) {
            return BLACK;
        }
        if (blackMotherPinned && cnt[WHITE][24] == 0) {
            return WHITE;
        }
        if (off[WHITE] == 15) {
            return WHITE;
        }
        if (off[BLACK] == 15) {
            return BLACK;
        }
        return NONE;
    }

    /** Kazananın aldığı puan: 1 ya da mars için 2. Oyun bitmediyse ya da beraberlikte 0. */
    public int winPoints() {
        int w = winner();
        if (w == NONE || w == TIE) {
            return 0;
        }
        int l = 1 - w;
        boolean motherLoss = (l == WHITE && pinned[24] == WHITE) || (l == BLACK && pinned[1] == BLACK);
        if (motherLoss) {
            return 2;
        }
        return off[l] == 0 ? 2 : 1;
    }

    /** Konumun kısa, tekil metni (önbellek anahtarı). */
    public String key() {
        StringBuilder sb = new StringBuilder(100);
        for (int t = 1; t <= 24; t++) {
            sb.append((char) ('A' + cnt[0][t]));
            sb.append((char) ('A' + cnt[1][t]));
            sb.append((char) ('a' + pinned[t] + 1));
        }
        sb.append(off[0]).append(',').append(off[1]);
        sb.append(firstPending[0] ? '1' : '0').append(firstPending[1] ? '1' : '0').append(firstStartMoves);
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Ağ üzerinden göndermek için kodlama
    // ------------------------------------------------------------------

    /** 77 karakterlik metin: 24 nokta x (beyaz, siyah, hapis) + toplananlar + ilk el bilgisi. */
    public String encode() {
        StringBuilder sb = new StringBuilder(80);
        for (int t = 1; t <= 24; t++) {
            sb.append(Character.forDigit(cnt[0][t], 16));
            sb.append(Character.forDigit(cnt[1][t], 16));
            sb.append(pinned[t] == NONE ? '-' : (char) ('0' + pinned[t]));
        }
        sb.append(Character.forDigit(off[0], 16));
        sb.append(Character.forDigit(off[1], 16));
        sb.append(firstPending[0] ? '1' : '0');
        sb.append(firstPending[1] ? '1' : '0');
        sb.append(Character.forDigit(firstStartMoves, 10));
        return sb.toString();
    }

    /** encode() çıktısını çözer; geçersizse null döner. */
    public static Board decode(String s) {
        if (s == null || s.length() != 77) {
            return null;
        }
        Board b = new Board();
        int[] tot = new int[2];
        for (int t = 1; t <= 24; t++) {
            int i = (t - 1) * 3;
            int c0 = Character.digit(s.charAt(i), 16);
            int c1 = Character.digit(s.charAt(i + 1), 16);
            char pc = s.charAt(i + 2);
            if (c0 < 0 || c1 < 0) {
                return null;
            }
            b.cnt[0][t] = c0;
            b.cnt[1][t] = c1;
            tot[0] += c0;
            tot[1] += c1;
            if (pc == '-') {
                b.pinned[t] = NONE;
            } else if (pc == '0' || pc == '1') {
                b.pinned[t] = pc - '0';
            } else {
                return null;
            }
            int pin = b.pinned[t];
            if (pin == NONE) {
                if (c0 > 0 && c1 > 0) {
                    return null;
                }
            } else if (b.cnt[pin][t] != 1 || b.cnt[1 - pin][t] < 1) {
                return null;
            }
        }
        int o0 = Character.digit(s.charAt(72), 16);
        int o1 = Character.digit(s.charAt(73), 16);
        if (o0 < 0 || o1 < 0) {
            return null;
        }
        b.off[0] = o0;
        b.off[1] = o1;
        tot[0] += o0;
        tot[1] += o1;
        if (tot[0] != 15 || tot[1] != 15) {
            return null;
        }
        char f0 = s.charAt(74);
        char f1 = s.charAt(75);
        if ((f0 != '0' && f0 != '1') || (f1 != '0' && f1 != '1')) {
            return null;
        }
        b.firstPending[0] = f0 == '1';
        b.firstPending[1] = f1 == '1';
        int fs = Character.digit(s.charAt(76), 10);
        if (fs < 0) {
            return null;
        }
        b.firstStartMoves = fs;
        return b;
    }

    // ------------------------------------------------------------------
    // Zarların zorunlu kullanımı
    // ------------------------------------------------------------------

    private static int[] removeAt(int[] a, int idx) {
        int[] r = new int[a.length - 1];
        int k = 0;
        for (int i = 0; i < a.length; i++) {
            if (i != idx) {
                r[k++] = a[i];
            }
        }
        return r;
    }

    private static int[] removeValue(int[] a, int v) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] == v) {
                return removeAt(a, i);
            }
        }
        return a;
    }

    private static boolean allEqual(int[] a) {
        for (int i = 1; i < a.length; i++) {
            if (a[i] != a[0]) {
                return false;
            }
        }
        return true;
    }

    private static String restKey(int[] rest) {
        int[] c = rest.clone();
        Arrays.sort(c);
        StringBuilder sb = new StringBuilder();
        for (int v : c) {
            sb.append(v);
        }
        return sb.toString();
    }

    /**
     * Bu konumdan, kalan zarlarla ulaşılabilecek en iyi sonucun puanı: 2 x oynanan hamle sayısı
     * + (ilk el kuralı sağlanıyorsa 1). Önce hamle sayısı, sonra ilk el kuralı önemlidir.
     */
    private static int best(Board b, int p, int[] rest, Map<String, Integer> memo) {
        if (rest.length == 0 || b.winner() != NONE) {
            return b.firstOk(p) ? 1 : 0;
        }
        String k = b.key() + "|" + restKey(rest);
        Integer cached = memo.get(k);
        if (cached != null) {
            return cached;
        }
        int res = -1;
        boolean[] tried = new boolean[7];
        for (int i = 0; i < rest.length; i++) {
            int d = rest[i];
            if (tried[d]) {
                continue;
            }
            tried[d] = true;
            int[] nr = removeAt(rest, i);
            for (Move m : b.legalMoves(p, d)) {
                Board nb = b.copy();
                nb.apply(p, m);
                int s = 2 + best(nb, p, nr, memo);
                if (s > res) {
                    res = s;
                }
            }
        }
        if (res < 0) {
            res = b.firstOk(p) ? 1 : 0;
        }
        memo.put(k, res);
        return res;
    }

    /**
     * Kalan zarlarla şu anda oynanması serbest olan ilk hamleler. Şu kuralları uygular:
     * mümkünse tüm zarlar kullanılır; yalnızca biri oynanabiliyorsa büyük olanı oynanır;
     * ilk elde mümkünse en az 2 farklı taş oynanır.
     */
    public static List<Move> allowedMoves(Board b, int p, int[] remaining) {
        Map<String, Integer> memo = new HashMap<String, Integer>();
        List<Move> cands = new ArrayList<Move>();
        List<Integer> scores = new ArrayList<Integer>();
        boolean[] tried = new boolean[7];
        int top = -1;
        for (int i = 0; i < remaining.length; i++) {
            int d = remaining[i];
            if (tried[d]) {
                continue;
            }
            tried[d] = true;
            int[] rest = removeAt(remaining, i);
            for (Move m : b.legalMoves(p, d)) {
                Board nb = b.copy();
                nb.apply(p, m);
                int s = 2 + best(nb, p, rest, memo);
                cands.add(m);
                scores.add(s);
                if (s > top) {
                    top = s;
                }
            }
        }
        List<Move> res = new ArrayList<Move>();
        for (int i = 0; i < cands.size(); i++) {
            if (scores.get(i) == top) {
                res.add(cands.get(i));
            }
        }
        if (top >= 0 && (top >> 1) == 1 && remaining.length >= 2 && !allEqual(remaining)) {
            int larger = 0;
            for (int v : remaining) {
                larger = Math.max(larger, v);
            }
            List<Move> big = new ArrayList<Move>();
            for (Move m : res) {
                if (m.die == larger) {
                    big.add(m);
                }
            }
            if (!big.isEmpty()) {
                res = big;
            }
        }
        return res;
    }

    // ------------------------------------------------------------------
    // Zar toplamı: tek taşla art arda hamle
    // ------------------------------------------------------------------

    /**
     * 'from' noktasındaki bir taşla, kalan zarların ikisini ya da daha fazlasını art arda
     * kullanarak ulaşılabilecek hedefler (örn. 5 ve 6 geldiyse 11 ileri). Her adım,
     * allowedMoves kurallarına uyar. Aynı hedefe birden fazla yol varsa hapis yapmayan seçilir.
     */
    public static List<Combo> comboTargets(Board b, int p, int[] remaining, int from) {
        Map<Integer, Combo> out = new HashMap<Integer, Combo>();
        comboDfs(b, p, remaining, from, new ArrayList<Move>(), 0, out);
        return new ArrayList<Combo>(out.values());
    }

    private static void comboDfs(Board b, int p, int[] rest, int cur, List<Move> path, int pins,
                                 Map<Integer, Combo> out) {
        if (rest.length == 0 || b.winner() != NONE) {
            return;
        }
        int o = 1 - p;
        for (Move m : allowedMoves(b, p, rest)) {
            if (m.from != cur) {
                continue;
            }
            Board nb = b.copy();
            boolean pin = m.to != OFF && nb.pinned[m.to] != o && nb.cnt[o][m.to] == 1;
            nb.apply(p, m);
            path.add(m);
            int np = pins + (pin ? 1 : 0);
            if (path.size() >= 2) {
                Combo old = out.get(m.to);
                if (old == null || np < old.pins || (np == old.pins && path.size() < old.moves.size())) {
                    out.put(m.to, new Combo(m.to, new ArrayList<Move>(path), np));
                }
            }
            if (m.to != OFF) {
                comboDfs(nb, p, removeValue(rest, m.die), m.to, path, np, out);
            }
            path.remove(path.size() - 1);
        }
    }

    // ------------------------------------------------------------------
    // Bilgisayar için: bir atışın tüm geçerli hamle dizileri
    // ------------------------------------------------------------------

    private static int turnScore(Turn t, int p) {
        return 2 * t.moves.size() + (t.result.firstOk(p) ? 1 : 0);
    }

    public static List<Turn> allTurns(Board start, int p, int d1, int d2) {
        int[] dice = (d1 == d2) ? new int[] {d1, d1, d1, d1} : new int[] {d1, d2};
        List<Turn> terminals = new ArrayList<Turn>();
        Set<String> seen = new HashSet<String>();
        dfs(start, p, dice, new ArrayList<Move>(), terminals, seen);
        int top = -1;
        for (Turn t : terminals) {
            top = Math.max(top, turnScore(t, p));
        }
        List<Turn> res = new ArrayList<Turn>();
        if (top < 2) {
            return res; // hiç hamle yok
        }
        for (Turn t : terminals) {
            if (turnScore(t, p) == top) {
                res.add(t);
            }
        }
        if ((top >> 1) == 1 && d1 != d2) {
            int larger = Math.max(d1, d2);
            List<Turn> big = new ArrayList<Turn>();
            for (Turn t : res) {
                if (t.moves.get(0).die == larger) {
                    big.add(t);
                }
            }
            if (!big.isEmpty()) {
                res = big;
            }
        }
        List<Turn> unique = new ArrayList<Turn>();
        Set<String> keys = new HashSet<String>();
        for (Turn t : res) {
            if (keys.add(t.result.key())) {
                unique.add(t);
            }
        }
        return unique;
    }

    private static void dfs(Board b, int p, int[] rest, List<Move> cur, List<Turn> out, Set<String> seen) {
        boolean any = false;
        if (rest.length > 0 && b.winner() == NONE) {
            boolean[] tried = new boolean[7];
            for (int i = 0; i < rest.length; i++) {
                int d = rest[i];
                if (tried[d]) {
                    continue;
                }
                tried[d] = true;
                int[] nr = removeAt(rest, i);
                for (Move m : b.legalMoves(p, d)) {
                    any = true;
                    Board nb = b.copy();
                    nb.apply(p, m);
                    if (!seen.add(nb.key() + "|" + restKey(nr))) {
                        continue;
                    }
                    cur.add(m);
                    dfs(nb, p, nr, cur, out, seen);
                    cur.remove(cur.size() - 1);
                }
            }
        }
        if (!any) {
            out.add(new Turn(new ArrayList<Move>(cur), b));
        }
    }
}
