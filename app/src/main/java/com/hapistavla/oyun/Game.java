package com.hapistavla.oyun;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Oyun akışı: sıra, zar, hamle, bilgisayar, çevrimiçi senkronizasyon. Android'den bağımsızdır;
 * çizim ve dokunma işini TahtaView yapar, zamanlayıcıları ve iş parçacığı geçişini Host sağlar.
 */
public final class Game {
    // evreler
    public static final int PH_ROLL = 0;     // zar atılmayı bekliyor
    public static final int PH_ROLLING = 1;  // zar dönüyor
    public static final int PH_MOVE = 2;     // insan hamle yapıyor
    public static final int PH_AI = 3;       // bilgisayar oynuyor
    public static final int PH_PASS = 4;     // sıra geçiyor
    public static final int PH_OVER = 5;     // oyun bitti
    public static final int PH_WAIT = 6;     // çevrimiçi: rakip oynuyor

    // modlar
    public static final int MODE_AI = 0;
    public static final int MODE_TWO = 1;
    public static final int MODE_ONLINE = 2;

    // Host.onlineEvent türleri
    public static final int EV_ROOM = 0;     // a = oda kodu
    public static final int EV_STATUS = 1;   // a = metin
    public static final int EV_MATCHED = 2;  // a = rakip adı
    public static final int EV_ERROR = 3;    // a = metin
    public static final int EV_LEFT = 4;     // a = metin
    public static final int EV_INVITE = 5;   // a = "kod|ad", b = oda
    public static final int EV_EMOTE = 6;    // a = mesaj, b = kim

    // ses türleri
    public static final int SND_ROLL = 0;
    public static final int SND_MOVE = 1;
    public static final int SND_PIN = 2;
    public static final int SND_WIN = 3;
    public static final int SND_LOSE = 4;

    public static final String[] EMOTES = {"Güzel hamle!", "Hadi oynayalım!", "Eyvah!", "İyi oyun!", "Şanslısın :)", "Bekliyorum…"};

    public interface Host {
        /** Arayüz iş parçacığında, gecikmeli çalıştırır; cancelTimers() ile iptal edilebilir. */
        void postTimer(Runnable r, long delayMs);

        void cancelTimers();

        /** Herhangi bir iş parçacığından arayüz iş parçacığına geçirir (iptal edilemez). */
        void postUi(Runnable r);

        void changed();

        void onlineEvent(int type, String a, String b);

        void gameOver(Result r);

        void sound(int kind);

        void haptic();
    }

    /** Bir oyunun sonucu (profil ilerlemesi için). */
    public static final class Result {
        public int mode;
        public int aiLevel;
        public int winner;      // WHITE / BLACK / TIE
        public int points;
        public int myColor;
        public boolean iWon;
        public boolean tie;
        public boolean mars;
        public boolean mother;   // kaybeden anne taşı yüzünden kaybetti
        public int pins;
        public int combos;
        public int maxCombo;
        public String oppCode = "";
        public String oppName = "";
    }

    /** Seçili taş için gösterilen hedef. */
    public static final class Dest {
        public final int to;
        public final int label;
        public final boolean pin;
        public final boolean combo;

        Dest(int to, int label, boolean pin, boolean combo) {
            this.to = to;
            this.label = label;
            this.pin = pin;
            this.combo = combo;
        }
    }

    private static final class Snapshot {
        final Board board;
        final List<Integer> remaining;
        final int lastFrom;
        final int lastTo;
        final String eventMsg;
        final int pins;
        final int combos;
        final int maxCombo;

        Snapshot(Board board, List<Integer> remaining, int lastFrom, int lastTo, String eventMsg,
                 int pins, int combos, int maxCombo) {
            this.board = board;
            this.remaining = remaining;
            this.lastFrom = lastFrom;
            this.lastTo = lastTo;
            this.eventMsg = eventMsg;
            this.pins = pins;
            this.combos = combos;
            this.maxCombo = maxCombo;
        }
    }

    private final Host host;
    private final Random rnd = new Random();
    private Online online;

    // herkese açık durum (çizim için)
    public Board board = Board.initial();
    public int turn = Board.WHITE;
    public int mode = MODE_AI;
    public int aiLevel = Ai.NORMAL;
    public int phase = PH_ROLL;
    public final int[] dice = new int[2];
    public boolean diceShown = false;
    public int diceOwner = Board.WHITE;
    public List<Integer> remaining = new ArrayList<Integer>();
    public List<Board.Move> allowed = new ArrayList<Board.Move>();
    public int selected = 0;
    public List<Dest> dests = new ArrayList<Dest>();
    public int lastFrom = 0;
    public int lastTo = 0;
    public final int[] score = new int[2];
    public String message = "";
    public String overTitle = "";
    public String overSub = "";
    public String overExtra = "";
    public int myColor = Board.WHITE;
    public boolean myRematch = false;
    public boolean oppRematch = false;
    public String oppName = "Rakip";
    public String oppCode = "";
    public Board.Move hintMove = null;

    // iç durum
    private final int[] finalDice = new int[2];
    private int rollCount = 0;
    private boolean rollDone = false;
    private final List<Snapshot> history = new ArrayList<Snapshot>();
    private String eventMsg = "";
    private List<Board.Move> aiPlan = new ArrayList<Board.Move>();
    private int aiIndex = 0;
    private int nextStarter = 0;
    private int sessionPins = 0;
    private int sessionCombos = 0;
    private int sessionMaxCombo = 0;
    private final ArrayDeque<String[]> oppQueue = new ArrayDeque<String[]>();

    private final Runnable rollStep = new Runnable() {
        @Override
        public void run() {
            if (phase != PH_ROLLING) {
                return;
            }
            if (rollCount < 9) {
                dice[0] = 1 + rnd.nextInt(6);
                dice[1] = 1 + rnd.nextInt(6);
                rollCount++;
                host.changed();
                host.postTimer(this, 70);
            } else {
                dice[0] = finalDice[0];
                dice[1] = finalDice[1];
                afterRoll();
            }
        }
    };

    private final Runnable startRollRunnable = new Runnable() {
        @Override
        public void run() {
            if (phase == PH_ROLL && isAiTurn()) {
                beginRollAnim(1 + rnd.nextInt(6), 1 + rnd.nextInt(6));
            }
        }
    };

    private final Runnable endTurnRunnable = new Runnable() {
        @Override
        public void run() {
            endTurn();
        }
    };

    private final Runnable aiStepRunnable = new Runnable() {
        @Override
        public void run() {
            if (phase != PH_AI || aiIndex >= aiPlan.size()) {
                return;
            }
            Board.Move m = aiPlan.get(aiIndex++);
            playMove(m, false);
            if (board.winner() != Board.NONE) {
                finishGame();
                return;
            }
            if (aiIndex < aiPlan.size()) {
                host.postTimer(this, 650);
            } else {
                phase = PH_PASS;
                host.postTimer(endTurnRunnable, 700);
            }
            host.changed();
        }
    };

    public Game(Host host) {
        this.host = host;
        nextStarter = rnd.nextInt(2);
        newGame();
    }

    // ------------------------------------------------------------------
    // Bilgi
    // ------------------------------------------------------------------

    public boolean isRemoteTurn() {
        return mode == MODE_ONLINE && turn != myColor;
    }

    public boolean isAiTurn() {
        return mode == MODE_AI && turn == Board.BLACK;
    }

    public boolean isHumanTurn() {
        return !isRemoteTurn() && !isAiTurn();
    }

    /** Tahtayı hangi oyuncunun gözünden çizeriz (ev altta sağda). */
    public int viewColor() {
        return mode == MODE_ONLINE ? myColor : Board.WHITE;
    }

    public static String colorName(int p) {
        return p == Board.WHITE ? "Beyaz" : "Siyah";
    }

    public String playerName(int p) {
        if (mode == MODE_AI) {
            return p == Board.WHITE ? "Sen" : "Bilgisayar";
        }
        if (mode == MODE_ONLINE) {
            return p == myColor ? "Sen" : oppName;
        }
        return colorName(p);
    }

    public boolean canRoll() {
        return phase == PH_ROLL && isHumanTurn();
    }

    public boolean canUndo() {
        return !history.isEmpty() && isHumanTurn() && mode != MODE_ONLINE && (phase == PH_MOVE || phase == PH_PASS);
    }

    public boolean canHint() {
        return phase == PH_MOVE && isHumanTurn() && !allowed.isEmpty();
    }

    private boolean isMine(int p) {
        if (mode == MODE_AI) {
            return p == Board.WHITE;
        }
        return mode == MODE_ONLINE && p == myColor;
    }

    private int[] remArray() {
        int[] r = new int[remaining.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = remaining.get(i);
        }
        return r;
    }

    // ------------------------------------------------------------------
    // Oyun akışı
    // ------------------------------------------------------------------

    public void newGame() {
        host.cancelTimers();
        board = Board.initial();
        history.clear();
        oppQueue.clear();
        remaining = new ArrayList<Integer>();
        allowed = new ArrayList<Board.Move>();
        dests = new ArrayList<Dest>();
        aiPlan = new ArrayList<Board.Move>();
        selected = 0;
        hintMove = null;
        lastFrom = 0;
        lastTo = 0;
        diceShown = false;
        eventMsg = "";
        overTitle = "";
        overSub = "";
        overExtra = "";
        myRematch = false;
        oppRematch = false;
        sessionPins = 0;
        sessionCombos = 0;
        sessionMaxCombo = 0;
        turn = nextStarter;
        startTurn();
    }

    private void startTurn() {
        phase = PH_ROLL;
        rollDone = false;
        selected = 0;
        hintMove = null;
        dests = new ArrayList<Dest>();
        remaining = new ArrayList<Integer>();
        allowed = new ArrayList<Board.Move>();
        String prefix = eventMsg.isEmpty() ? "" : eventMsg + " ";
        eventMsg = "";
        if (isRemoteTurn()) {
            phase = PH_WAIT;
            message = prefix + oppName + " zar atıyor…";
            host.changed();
            processOpp();
            return;
        }
        if (isAiTurn()) {
            message = prefix + "Bilgisayar zar atıyor…";
            host.postTimer(startRollRunnable, 800);
        } else if (mode == MODE_ONLINE) {
            message = prefix + "Sıra sende: zar atmak için dokun.";
        } else {
            message = prefix + colorName(turn) + ": zar atmak için dokun.";
        }
        host.changed();
    }

    /** İnsan oyuncunun zar atması. */
    public void tapRoll() {
        if (!canRoll()) {
            return;
        }
        int d1 = 1 + rnd.nextInt(6);
        int d2 = 1 + rnd.nextInt(6);
        if (mode == MODE_ONLINE && online != null) {
            online.sendRoll(d1, d2);
        }
        beginRollAnim(d1, d2);
    }

    private void beginRollAnim(int d1, int d2) {
        finalDice[0] = d1;
        finalDice[1] = d2;
        phase = PH_ROLLING;
        diceShown = true;
        diceOwner = turn;
        rollCount = 0;
        selected = 0;
        hintMove = null;
        dests = new ArrayList<Dest>();
        message = "Zar atılıyor…";
        host.sound(SND_ROLL);
        host.postTimer(rollStep, 0);
        host.changed();
    }

    private void afterRoll() {
        remaining = new ArrayList<Integer>();
        if (dice[0] == dice[1]) {
            for (int i = 0; i < 4; i++) {
                remaining.add(dice[0]);
            }
        } else {
            remaining.add(dice[0]);
            remaining.add(dice[1]);
        }
        history.clear();
        selected = 0;
        rollDone = true;
        if (isRemoteTurn()) {
            phase = PH_WAIT;
            message = oppName + " oynuyor…";
            host.changed();
            processOpp();
            return;
        }
        if (isAiTurn()) {
            runAi();
            return;
        }
        computeAllowed();
        if (allowed.isEmpty()) {
            message = playerName(turn) + " oynayamıyor, sıra geçiyor.";
            if (mode != MODE_ONLINE) {
                message = colorName(turn) + " oynayamıyor, sıra geçiyor.";
            }
            phase = PH_PASS;
            host.postTimer(endTurnRunnable, 1500);
        } else {
            phase = PH_MOVE;
            message = moveHint();
        }
        host.changed();
    }

    private void runAi() {
        aiPlan = Ai.chooseTurn(board, turn, dice[0], dice[1], aiLevel);
        aiIndex = 0;
        if (aiPlan.isEmpty()) {
            message = "Bilgisayar oynayamıyor, sıra sende.";
            phase = PH_PASS;
            host.postTimer(endTurnRunnable, 1500);
        } else {
            message = "Bilgisayar oynuyor…";
            phase = PH_AI;
            host.postTimer(aiStepRunnable, 700);
        }
        host.changed();
    }

    private String moveHint() {
        String prefix = eventMsg.isEmpty() ? "" : eventMsg + " ";
        if (mode == MODE_ONLINE) {
            return prefix + "Sıra sende: bir taşa, sonra gideceği yere dokun.";
        }
        return prefix + colorName(turn) + ": bir taşa, sonra gideceği yere dokun.";
    }

    private void computeAllowed() {
        int[] rem = remArray();
        allowed = rem.length == 0 ? new ArrayList<Board.Move>() : Board.allowedMoves(board, turn, rem);
    }

    /** Hamleyi tahtaya işler (insan, bilgisayar ve uzaktaki rakip için ortak). */
    private void playMove(Board.Move m, boolean publish) {
        int opp = 1 - turn;
        boolean pins = m.to != Board.OFF && board.pinned[m.to] != opp && board.cnt[opp][m.to] == 1;
        board.apply(turn, m);
        lastFrom = m.from;
        lastTo = m.to;
        remaining.remove(Integer.valueOf(m.die));
        eventMsg = "";
        if (pins) {
            eventMsg = colorName(turn) + " bir taşı hapsetti!";
            host.sound(SND_PIN);
            host.haptic();
            if (isMine(turn)) {
                sessionPins++;
            }
        } else {
            host.sound(SND_MOVE);
        }
        if (publish && mode == MODE_ONLINE && turn == myColor && online != null) {
            online.sendMove(m.from, m.to, m.die);
        }
    }

    private void endTurn() {
        if (phase == PH_OVER) {
            return;
        }
        board.endTurn(turn);
        if (mode == MODE_ONLINE && turn == myColor && online != null) {
            online.sendEnd(board.encode());
        }
        turn = 1 - turn;
        history.clear();
        startTurn();
    }

    private void finishGame() {
        host.cancelTimers();
        oppQueue.clear();
        phase = PH_OVER;
        selected = 0;
        hintMove = null;
        dests = new ArrayList<Dest>();
        allowed = new ArrayList<Board.Move>();
        int w = board.winner();
        Result r = new Result();
        r.mode = mode;
        r.aiLevel = aiLevel;
        r.winner = w;
        r.myColor = viewColor();
        r.pins = sessionPins;
        r.combos = sessionCombos;
        r.maxCombo = sessionMaxCombo;
        r.oppCode = oppCode;
        r.oppName = oppName;
        if (w == Board.TIE) {
            overTitle = "Beraberlik";
            overSub = "İki anne taş da hapiste.";
            nextStarter = 1 - nextStarter;
            r.tie = true;
        } else {
            int pts = board.winPoints();
            int loser = 1 - w;
            boolean motherLoss = (loser == Board.WHITE && board.pinned[24] == Board.WHITE)
                    || (loser == Board.BLACK && board.pinned[1] == Board.BLACK);
            score[w] += pts;
            r.points = pts;
            r.mars = pts == 2;
            r.mother = motherLoss;
            r.iWon = isMine(w);
            if (mode == MODE_AI) {
                overTitle = (w == Board.WHITE) ? "Kazandın!" : "Bilgisayar kazandı";
            } else if (mode == MODE_ONLINE) {
                overTitle = (w == myColor) ? "Kazandın!" : oppName + " kazandı";
            } else {
                overTitle = colorName(w) + " kazandı!";
            }
            if (motherLoss) {
                overSub = "Anne taş hapsedildi. MARS! (+2 puan)";
            } else if (pts == 2) {
                overSub = "MARS! (+2 puan)";
            } else {
                overSub = "+1 puan";
            }
            nextStarter = w;
        }
        message = overTitle + " " + overSub;
        host.sound(r.tie ? SND_MOVE : (isMine(w) || mode == MODE_TWO ? SND_WIN : SND_LOSE));
        host.changed();
        host.gameOver(r);
        host.changed();
    }

    // ------------------------------------------------------------------
    // İnsan hamleleri
    // ------------------------------------------------------------------

    private boolean hasMoveFrom(int from) {
        for (Board.Move m : allowed) {
            if (m.from == from) {
                return true;
            }
        }
        return false;
    }

    private Dest destFor(int to) {
        for (Dest d : dests) {
            if (d.to == to) {
                return d;
            }
        }
        return null;
    }

    private void computeDests(int from) {
        dests = new ArrayList<Dest>();
        if (from == 0) {
            return;
        }
        int o = 1 - turn;
        List<Board.Move> sorted = new ArrayList<Board.Move>(allowed);
        Collections.sort(sorted, new Comparator<Board.Move>() {
            @Override
            public int compare(Board.Move a, Board.Move b) {
                return a.die - b.die;
            }
        });
        Set<Integer> seen = new HashSet<Integer>();
        for (Board.Move m : sorted) {
            if (m.from == from && seen.add(m.to)) {
                boolean pin = m.to != Board.OFF && board.pinned[m.to] != o && board.cnt[o][m.to] == 1;
                dests.add(new Dest(m.to, m.die, pin, false));
            }
        }
        for (Board.Combo cb : Board.comboTargets(board, turn, remArray(), from)) {
            if (seen.add(cb.to)) {
                dests.add(new Dest(cb.to, cb.total(), cb.pins > 0, true));
            }
        }
    }

    /** Tahtada (mantıksal) noktaya dokunuldu. */
    public void tapPoint(int pt) {
        if (phase != PH_MOVE || !isHumanTurn()) {
            return;
        }
        if (selected != 0 && pt != selected && destFor(pt) != null) {
            executeMove(selected, pt);
            return;
        }
        if (pt == selected) {
            selected = 0;
        } else if (hasMoveFrom(pt)) {
            selected = pt;
        } else {
            selected = 0;
        }
        hintMove = null;
        computeDests(selected);
        host.changed();
    }

    /** Taş toplama alanına dokunuldu. */
    public void tapTray() {
        if (phase == PH_MOVE && isHumanTurn() && selected != 0 && destFor(Board.OFF) != null) {
            executeMove(selected, Board.OFF);
        }
    }

    private List<Board.Move> routeFor(int from, int to) {
        Board.Move single = null;
        for (Board.Move m : allowed) {
            if (m.from == from && m.to == to && (single == null || m.die < single.die)) {
                single = m;
            }
        }
        if (single != null) {
            List<Board.Move> r = new ArrayList<Board.Move>();
            r.add(single);
            return r;
        }
        for (Board.Combo cb : Board.comboTargets(board, turn, remArray(), from)) {
            if (cb.to == to) {
                return cb.moves;
            }
        }
        return null;
    }

    private void executeMove(int from, int to) {
        List<Board.Move> route = routeFor(from, to);
        if (route == null) {
            return;
        }
        history.add(new Snapshot(board.copy(), new ArrayList<Integer>(remaining), lastFrom, lastTo, eventMsg,
                sessionPins, sessionCombos, sessionMaxCombo));
        selected = 0;
        hintMove = null;
        dests = new ArrayList<Dest>();
        int total = 0;
        for (Board.Move m : route) {
            playMove(m, true);
            total += m.die;
        }
        if (route.size() >= 2 && isMine(turn)) {
            sessionCombos++;
            sessionMaxCombo = Math.max(sessionMaxCombo, total);
        }
        if (board.winner() != Board.NONE) {
            finishGame();
            return;
        }
        computeAllowed();
        if (allowed.isEmpty()) {
            message = (eventMsg.isEmpty() ? "" : eventMsg + " ") + (remaining.isEmpty() ? "" : "Başka oynanabilir hamle yok.");
            phase = PH_PASS;
            host.postTimer(endTurnRunnable, 900);
        } else {
            message = moveHint();
        }
        host.changed();
    }

    public void undo() {
        if (!canUndo()) {
            return;
        }
        host.cancelTimers();
        Snapshot s = history.remove(history.size() - 1);
        board = s.board;
        remaining = new ArrayList<Integer>(s.remaining);
        lastFrom = s.lastFrom;
        lastTo = s.lastTo;
        eventMsg = s.eventMsg;
        sessionPins = s.pins;
        sessionCombos = s.combos;
        sessionMaxCombo = s.maxCombo;
        selected = 0;
        hintMove = null;
        dests = new ArrayList<Dest>();
        phase = PH_MOVE;
        computeAllowed();
        message = moveHint();
        host.changed();
    }

    /** İpucu: en iyi görünen taşı seçer ve hedefini gösterir. */
    public void requestHint() {
        if (!canHint()) {
            return;
        }
        Board.Move m = Ai.hint(board, turn, remArray());
        if (m == null) {
            return;
        }
        hintMove = m;
        selected = m.from;
        computeDests(selected);
        message = "İpucu: yeşil işaretli yeri dene.";
        host.changed();
    }

    // ------------------------------------------------------------------
    // Modlar, yeni oyun, tekrar oyna
    // ------------------------------------------------------------------

    /** "Yeni Oyun" / "Tekrar Oyna" düğmesi. */
    public void newGameRequest() {
        if (mode == MODE_ONLINE) {
            if (phase == PH_OVER && !myRematch) {
                myRematch = true;
                if (online != null) {
                    online.sendRematch();
                }
                message = "Rakibin onayı bekleniyor…";
                if (oppRematch) {
                    startRematch();
                    return;
                }
                host.changed();
            }
            return;
        }
        nextStarter = rnd.nextInt(2);
        newGame();
    }

    private void startRematch() {
        myRematch = false;
        oppRematch = false;
        newGame();
    }

    public void setOnline(Online o) {
        this.online = o;
    }

    /** Bilgisayara karşı ya da aynı telefonda iki kişi moduna geç (skorlar sıfırlanır). */
    public void setMode(int newMode, int level) {
        if (mode == MODE_ONLINE && online != null) {
            online.leave();
        }
        mode = newMode;
        aiLevel = level;
        score[0] = 0;
        score[1] = 0;
        myColor = Board.WHITE;
        oppName = "Rakip";
        oppCode = "";
        nextStarter = rnd.nextInt(2);
        newGame();
    }

    /** Çevrimiçi oyundan çık, bilgisayara karşı yeni oyuna dön. */
    public void exitOnline() {
        if (online != null && mode == MODE_ONLINE) {
            online.leave();
        }
        setMode(MODE_AI, aiLevel);
    }

    public void sendEmote(int id) {
        if (mode == MODE_ONLINE && online != null && id >= 0 && id < EMOTES.length) {
            online.sendEmote(id);
            host.onlineEvent(EV_EMOTE, EMOTES[id], "Sen");
        }
    }

    private void startOnlineMatch(int color, int starter, String code, String name) {
        mode = MODE_ONLINE;
        myColor = color;
        oppCode = code;
        oppName = (name == null || name.isEmpty()) ? "Rakip" : name;
        score[0] = 0;
        score[1] = 0;
        nextStarter = starter;
        newGame();
        host.onlineEvent(EV_MATCHED, oppName, "");
    }

    // ------------------------------------------------------------------
    // Çevrimiçi: gelen olaylar (arayüz iş parçacığında işlenir)
    // ------------------------------------------------------------------

    public Online.Listener onlineListener() {
        return new Online.Listener() {
            @Override
            public void onStatus(final String text) {
                host.postUi(new Runnable() {
                    @Override
                    public void run() {
                        host.onlineEvent(EV_STATUS, text, "");
                    }
                });
            }

            @Override
            public void onRoomCreated(final String code) {
                host.postUi(new Runnable() {
                    @Override
                    public void run() {
                        host.onlineEvent(EV_ROOM, code, "");
                    }
                });
            }

            @Override
            public void onMatched(final int color, final int starter, final String code, final String name) {
                host.postUi(new Runnable() {
                    @Override
                    public void run() {
                        startOnlineMatch(color, starter, code, name);
                    }
                });
            }

            @Override
            public void onRoll(final int d1, final int d2) {
                queueOpp(new String[] {"roll", String.valueOf(d1), String.valueOf(d2)});
            }

            @Override
            public void onMove(final int from, final int to, final int die) {
                queueOpp(new String[] {"move", String.valueOf(from), String.valueOf(to), String.valueOf(die)});
            }

            @Override
            public void onEnd(final String code) {
                queueOpp(new String[] {"end", code});
            }

            @Override
            public void onRematch() {
                host.postUi(new Runnable() {
                    @Override
                    public void run() {
                        if (mode != MODE_ONLINE) {
                            return;
                        }
                        oppRematch = true;
                        if (myRematch && phase == PH_OVER) {
                            startRematch();
                        } else if (phase == PH_OVER) {
                            message = oppName + " tekrar oynamak istiyor.";
                            host.changed();
                        }
                    }
                });
            }

            @Override
            public void onEmote(final int id) {
                host.postUi(new Runnable() {
                    @Override
                    public void run() {
                        if (mode == MODE_ONLINE && id >= 0 && id < EMOTES.length) {
                            host.onlineEvent(EV_EMOTE, EMOTES[id], oppName);
                        }
                    }
                });
            }

            @Override
            public void onOpponentLeft(final String text) {
                host.postUi(new Runnable() {
                    @Override
                    public void run() {
                        if (mode == MODE_ONLINE) {
                            host.onlineEvent(EV_LEFT, text, "");
                        }
                    }
                });
            }

            @Override
            public void onError(final String text) {
                host.postUi(new Runnable() {
                    @Override
                    public void run() {
                        host.onlineEvent(EV_ERROR, text, "");
                    }
                });
            }

            @Override
            public void onInvite(final String fromCode, final String fromName, final String room) {
                host.postUi(new Runnable() {
                    @Override
                    public void run() {
                        host.onlineEvent(EV_INVITE, fromCode + "|" + fromName, room);
                    }
                });
            }
        };
    }

    private void queueOpp(final String[] ev) {
        host.postUi(new Runnable() {
            @Override
            public void run() {
                if (mode != MODE_ONLINE) {
                    return;
                }
                oppQueue.add(ev);
                processOpp();
            }
        });
    }

    private boolean oppMoveAllowed(int from, int to, int die) {
        for (Board.Move m : Board.allowedMoves(board, turn, remArray())) {
            if (m.from == from && m.to == to && m.die == die) {
                return true;
            }
        }
        return false;
    }

    private void processOpp() {
        while (mode == MODE_ONLINE && !oppQueue.isEmpty()) {
            if (phase == PH_ROLLING) {
                return; // animasyon bitince tekrar çağrılır
            }
            String[] ev = oppQueue.peek();
            String t = ev[0];
            boolean waiting = phase == PH_WAIT && isRemoteTurn();
            if (t.equals("roll")) {
                oppQueue.poll();
                if (waiting && !rollDone) {
                    beginRollAnim(Integer.parseInt(ev[1]), Integer.parseInt(ev[2]));
                    return;
                }
            } else if (t.equals("move")) {
                oppQueue.poll();
                if (waiting && rollDone) {
                    int from = Integer.parseInt(ev[1]);
                    int to = Integer.parseInt(ev[2]);
                    int die = Integer.parseInt(ev[3]);
                    if (remaining.contains(die) && oppMoveAllowed(from, to, die)) {
                        playMove(new Board.Move(from, to, die), false);
                        if (board.winner() != Board.NONE) {
                            finishGame();
                            return;
                        }
                        host.changed();
                    }
                }
            } else if (t.equals("end")) {
                oppQueue.poll();
                if (waiting && rollDone) {
                    Board nb = Board.decode(ev[1]);
                    if (nb != null) {
                        board = nb;
                    } else {
                        board.endTurn(turn);
                    }
                    if (board.winner() != Board.NONE) {
                        finishGame();
                        return;
                    }
                    turn = 1 - turn;
                    history.clear();
                    startTurn();
                    return;
                }
            } else {
                oppQueue.poll();
            }
        }
    }
}
