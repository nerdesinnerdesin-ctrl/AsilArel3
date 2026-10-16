package com.hapistavla.oyun;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Calendar;
import java.util.List;
import java.util.Random;

/**
 * Oyuncu profili: ad, oyuncu kodu, seviye/puan, istatistikler, başarımlar, günlük bonus ve arkadaşlar.
 * Telefonda saklanır (Store). Android'e bağımlı değildir.
 */
public final class Profile {
    public interface Store {
        String get(String key, String def);

        void put(String key, String value);
    }

    public static final class Friend {
        public final String id;
        public String name;

        public Friend(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** Bir oyunun sonunda kazanılanlar. */
    public static final class Reward {
        public int xp;
        public boolean levelUp;
        public int level;
        public final List<String> newAchievements = new ArrayList<String>();

        public String text() {
            StringBuilder sb = new StringBuilder("+" + xp + " puan");
            if (levelUp) {
                sb.append("  •  Seviye ").append(level).append("!");
            }
            for (String a : newAchievements) {
                sb.append("\nYeni başarım: ").append(a);
            }
            return sb.toString();
        }
    }

    public static final String[] ACH_NAMES = {
        "İlk Galibiyet", "Mars!", "Hapisçi", "Seri Galibiyet", "Online Savaşçı",
        "Usta", "Büyük Hamle", "Arkadaş Canlısı", "Sadık Oyuncu", "Anne Taş Avcısı"
    };
    public static final String[] ACH_DESC = {
        "Bir oyun kazan.",
        "Rakip hiç taş toplamadan kazan.",
        "Toplam 10 taş hapset.",
        "Üst üste 3 oyun kazan.",
        "Çevrimiçi bir oyun kazan.",
        "Toplam 25 oyun kazan.",
        "Tek taşla 10 ya da daha fazla zar toplamı oyna.",
        "İlk arkadaşını ekle.",
        "3 gün üst üste günlük bonusu al.",
        "Rakibin anne taşını hapsederek kazan."
    };

    public static final int AVATAR_COUNT = 8;
    public static final int[] AVATAR_COLORS = {
        0xFFC0392B, 0xFFD35400, 0xFFB7950B, 0xFF1E8449, 0xFF148F77, 0xFF2471A3, 0xFF7D3C98, 0xFF566573
    };

    private final Store store;
    public String id;
    public String name;
    public int avatar;
    public int xp;
    public int games;
    public int wins;
    public int losses;
    public int ties;
    public int mars;
    public int streak;
    public int bestStreak;
    public int onlineGames;
    public int onlineWins;
    public int pins;
    public int dailyStreak;
    public String dailyLast = "";
    public long ach;
    public final List<Friend> friends = new ArrayList<Friend>();

    public Profile(Store store) {
        this.store = store;
        load();
    }

    private int geti(String k, int def) {
        try {
            return Integer.parseInt(store.get(k, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void load() {
        id = normalizeId(store.get("id", ""));
        if (id.length() != 8) {
            id = newId();
        }
        name = cleanName(store.get("name", "Oyuncu"));
        avatar = Math.abs(geti("avatar", new Random().nextInt(AVATAR_COUNT))) % AVATAR_COUNT;
        xp = geti("xp", 0);
        games = geti("games", 0);
        wins = geti("wins", 0);
        losses = geti("losses", 0);
        ties = geti("ties", 0);
        mars = geti("mars", 0);
        streak = geti("streak", 0);
        bestStreak = geti("bestStreak", 0);
        onlineGames = geti("onlineGames", 0);
        onlineWins = geti("onlineWins", 0);
        pins = geti("pins", 0);
        dailyStreak = geti("dailyStreak", 0);
        dailyLast = store.get("dailyLast", "");
        try {
            ach = Long.parseLong(store.get("ach", "0"));
        } catch (NumberFormatException e) {
            ach = 0;
        }
        friends.clear();
        String fs = store.get("friends", "");
        if (!fs.isEmpty()) {
            for (String part : fs.split(";")) {
                String[] kv = part.split(":", 2);
                String fid = normalizeId(kv[0]);
                if (fid.length() == 8) {
                    friends.add(new Friend(fid, cleanName(kv.length > 1 ? kv[1] : "Arkadaş")));
                }
            }
        }
        save();
    }

    public void save() {
        store.put("id", id);
        store.put("name", name);
        store.put("avatar", String.valueOf(avatar));
        store.put("xp", String.valueOf(xp));
        store.put("games", String.valueOf(games));
        store.put("wins", String.valueOf(wins));
        store.put("losses", String.valueOf(losses));
        store.put("ties", String.valueOf(ties));
        store.put("mars", String.valueOf(mars));
        store.put("streak", String.valueOf(streak));
        store.put("bestStreak", String.valueOf(bestStreak));
        store.put("onlineGames", String.valueOf(onlineGames));
        store.put("onlineWins", String.valueOf(onlineWins));
        store.put("pins", String.valueOf(pins));
        store.put("dailyStreak", String.valueOf(dailyStreak));
        store.put("dailyLast", dailyLast);
        store.put("ach", String.valueOf(ach));
        StringBuilder sb = new StringBuilder();
        for (Friend f : friends) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(f.id).append(':').append(f.name);
        }
        store.put("friends", sb.toString());
    }

    private static String newId() {
        String hex = "0123456789ABCDEF";
        Random r = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(hex.charAt(r.nextInt(16)));
        }
        return sb.toString();
    }

    public static String normalizeId(String s) {
        StringBuilder sb = new StringBuilder();
        if (s != null) {
            for (int i = 0; i < s.length(); i++) {
                char c = Character.toUpperCase(s.charAt(i));
                if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F')) {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    public static String cleanName(String s) {
        StringBuilder sb = new StringBuilder();
        if (s != null) {
            for (int i = 0; i < s.length() && sb.length() < 14; i++) {
                char c = s.charAt(i);
                if (c == '|' || c == ':' || c == ';' || c < 32) {
                    continue;
                }
                sb.append(c);
            }
        }
        String r = sb.toString().trim();
        return r.isEmpty() ? "Oyuncu" : r;
    }

    /** "ABCD-1234" biçimi. */
    public String displayId() {
        return formatId(id);
    }

    public static String formatId(String id) {
        return id.length() == 8 ? id.substring(0, 4) + "-" + id.substring(4) : id;
    }

    public void setName(String n) {
        name = cleanName(n);
        save();
    }

    public void setAvatar(int a) {
        avatar = ((a % AVATAR_COUNT) + AVATAR_COUNT) % AVATAR_COUNT;
        save();
    }

    // ------------------------------------------------------------------
    // Seviye
    // ------------------------------------------------------------------

    public static int levelOf(int xp) {
        return 1 + (int) Math.sqrt(xp / 60.0);
    }

    public static int xpForLevel(int level) {
        return 60 * (level - 1) * (level - 1);
    }

    public int level() {
        return levelOf(xp);
    }

    /** Bir sonraki seviyeye giden yolun 0..1 arası oranı. */
    public float levelProgress() {
        int lv = level();
        int a = xpForLevel(lv);
        int b = xpForLevel(lv + 1);
        return b > a ? Math.min(1f, Math.max(0f, (xp - a) / (float) (b - a))) : 0f;
    }

    public String rankTitle() {
        int lv = level();
        if (lv < 3) {
            return "Çırak";
        }
        if (lv < 6) {
            return "Kalfa";
        }
        if (lv < 10) {
            return "Usta";
        }
        if (lv < 15) {
            return "Üstat";
        }
        return "Efsane";
    }

    // ------------------------------------------------------------------
    // Başarımlar
    // ------------------------------------------------------------------

    public boolean hasAch(int i) {
        return (ach & (1L << i)) != 0;
    }

    /** Başarım yeni açıldıysa true. */
    public boolean unlock(int i) {
        if (hasAch(i)) {
            return false;
        }
        ach |= (1L << i);
        return true;
    }

    public int achCount() {
        return Long.bitCount(ach);
    }

    // ------------------------------------------------------------------
    // Oyun sonu, günlük bonus, hapis sayacı
    // ------------------------------------------------------------------

    /**
     * Oyun sonucunu kaydeder.
     *
     * @param win       oyuncu kazandı mı
     * @param tie       berabere mi
     * @param points    kazananın puanı (2 = mars)
     * @param online    çevrimiçi oyun mu
     * @param motherWin rakibin anne taşını hapsederek mi kazandı
     */
    public Reward recordGame(boolean win, boolean tie, int points, boolean online, boolean motherWin) {
        Reward r = new Reward();
        int oldLevel = level();
        games++;
        if (online) {
            onlineGames++;
        }
        if (tie) {
            ties++;
            r.xp = 15;
        } else if (win) {
            wins++;
            streak++;
            bestStreak = Math.max(bestStreak, streak);
            r.xp = 30;
            if (points >= 2) {
                mars++;
                r.xp += 20;
            }
            if (online) {
                onlineWins++;
                r.xp += 10;
            }
        } else {
            losses++;
            streak = 0;
            r.xp = 10;
        }
        xp += r.xp;
        if (win && !tie) {
            check(r, 0, true);
            check(r, 1, points >= 2);
            check(r, 3, streak >= 3);
            check(r, 4, online);
            check(r, 5, wins >= 25);
            check(r, 9, motherWin);
        }
        check(r, 2, pins >= 10);
        int newLevel = level();
        r.level = newLevel;
        r.levelUp = newLevel > oldLevel;
        save();
        return r;
    }

    private void check(Reward r, int idx, boolean cond) {
        if (cond && unlock(idx)) {
            r.newAchievements.add(ACH_NAMES[idx]);
        }
    }

    /** Rakibin bir taşı hapsedildi. Yeni açılan başarım varsa adını döndürür. */
    public String addPin() {
        pins++;
        String res = null;
        if (pins >= 10 && unlock(2)) {
            res = ACH_NAMES[2];
        }
        save();
        return res;
    }

    /** Oyun boyunca hapsedilen taşlar toplu eklenir. */
    public void addPins(int n) {
        if (n <= 0) {
            return;
        }
        pins += n;
        save();
    }

    /** Büyük zar toplamı hamlesi oynandı. */
    public String bigMove(int total) {
        String res = null;
        if (total >= 10 && unlock(6)) {
            res = ACH_NAMES[6];
        }
        save();
        return res;
    }

    private static String dayKey(Calendar c) {
        return c.get(Calendar.YEAR) + "-" + (c.get(Calendar.MONTH) + 1) + "-" + c.get(Calendar.DAY_OF_MONTH);
    }

    /** Günlük bonusu alır. Bugün zaten alındıysa 0 döner. */
    public int claimDaily(long nowMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(nowMs);
        String today = dayKey(c);
        if (today.equals(dailyLast)) {
            return 0;
        }
        c.add(Calendar.DAY_OF_MONTH, -1);
        String yesterday = dayKey(c);
        dailyStreak = yesterday.equals(dailyLast) ? dailyStreak + 1 : 1;
        dailyLast = today;
        int bonus = 20 + 10 * Math.min(dailyStreak - 1, 6);
        xp += bonus;
        if (dailyStreak >= 3) {
            unlock(8);
        }
        save();
        return bonus;
    }

    // ------------------------------------------------------------------
    // Bulut eşitleme
    // ------------------------------------------------------------------

    /** Buluta yazılacak profil (arkadaş listesi dahil). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("name", name);
        m.put("avatar", (long) avatar);
        m.put("xp", (long) xp);
        m.put("games", (long) games);
        m.put("wins", (long) wins);
        m.put("losses", (long) losses);
        m.put("ties", (long) ties);
        m.put("mars", (long) mars);
        m.put("streak", (long) streak);
        m.put("bestStreak", (long) bestStreak);
        m.put("onlineGames", (long) onlineGames);
        m.put("onlineWins", (long) onlineWins);
        m.put("pins", (long) pins);
        m.put("dailyStreak", (long) dailyStreak);
        m.put("dailyLast", dailyLast);
        m.put("ach", ach);
        List<Object> fl = new ArrayList<Object>();
        for (Friend f : friends) {
            Map<String, Object> fm = new LinkedHashMap<String, Object>();
            fm.put("id", f.id);
            fm.put("name", f.name);
            fl.add(fm);
        }
        m.put("friends", fl);
        return m;
    }

    private static int mx(int a, Map<String, Object> m, String k) {
        long v = Json.num(m, k, 0);
        if (v < 0 || v > 100000000L) {
            v = 0;
        }
        return Math.max(a, (int) v);
    }

    /**
     * Buluttaki profili yerel profille birleştirir: sayaçlarda büyük olan, başarımlarda birleşim,
     * arkadaşlarda birleşim alınır. Hesaba bağlı oyuncu kodu (bulutta varsa) kullanılır.
     * Hiçbir ilerleme kaybolmaz.
     */
    public void mergeFrom(Map<String, Object> c) {
        if (c == null) {
            return;
        }
        String cid = normalizeId(Json.str(c, "id", ""));
        if (cid.length() == 8) {
            id = cid;
        }
        if ("Oyuncu".equals(name)) {
            name = cleanName(Json.str(c, "name", name));
        }
        xp = mx(xp, c, "xp");
        games = mx(games, c, "games");
        wins = mx(wins, c, "wins");
        losses = mx(losses, c, "losses");
        ties = mx(ties, c, "ties");
        mars = mx(mars, c, "mars");
        bestStreak = mx(bestStreak, c, "bestStreak");
        onlineGames = mx(onlineGames, c, "onlineGames");
        onlineWins = mx(onlineWins, c, "onlineWins");
        pins = mx(pins, c, "pins");
        games = Math.max(games, wins + losses + ties);
        long ca = Json.num(c, "ach", 0);
        if (ca > 0 && ca < (1L << 40)) {
            ach |= ca;
        }
        int cds = mx(0, c, "dailyStreak");
        if (cds > dailyStreak) {
            dailyStreak = cds;
            dailyLast = Json.str(c, "dailyLast", dailyLast);
        }
        Object fo = c.get("friends");
        if (fo instanceof List) {
            for (Object o : (List<?>) fo) {
                if (o instanceof Map && friends.size() < 50) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> fm = (Map<String, Object>) o;
                    String fid = normalizeId(Json.str(fm, "id", ""));
                    if (fid.length() == 8 && !fid.equals(id) && findFriend(fid) == null) {
                        friends.add(new Friend(fid, cleanName(Json.str(fm, "name", "Arkadaş"))));
                    }
                }
            }
        }
        save();
    }

    // ------------------------------------------------------------------
    // Arkadaşlar
    // ------------------------------------------------------------------

    public static final int FRIEND_OK = 0;
    public static final int FRIEND_INVALID = 1;
    public static final int FRIEND_SELF = 2;
    public static final int FRIEND_DUPLICATE = 3;
    public static final int FRIEND_LIMIT = 4;

    public int addFriend(String code, String friendName) {
        String fid = normalizeId(code);
        if (fid.length() != 8) {
            return FRIEND_INVALID;
        }
        if (fid.equals(id)) {
            return FRIEND_SELF;
        }
        if (findFriend(fid) != null) {
            return FRIEND_DUPLICATE;
        }
        if (friends.size() >= 50) {
            return FRIEND_LIMIT;
        }
        friends.add(new Friend(fid, cleanName(friendName == null || friendName.trim().isEmpty() ? "Arkadaş" : friendName)));
        unlock(7);
        save();
        return FRIEND_OK;
    }

    public Friend findFriend(String fid) {
        for (Friend f : friends) {
            if (f.id.equals(fid)) {
                return f;
            }
        }
        return null;
    }

    public void removeFriend(String fid) {
        Friend f = findFriend(fid);
        if (f != null) {
            friends.remove(f);
            save();
        }
    }

    public void renameFriend(String fid, String newName) {
        Friend f = findFriend(fid);
        if (f != null) {
            f.name = cleanName(newName);
            save();
        }
    }
}
