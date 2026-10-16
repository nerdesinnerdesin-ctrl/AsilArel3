package com.hapistavla.oyun;

import java.util.Random;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Çevrimiçi oyunun iletişim katmanı: oda kurma, odaya katılma, otomatik eşleşme, arkadaş davetleri
 * ve oyun mesajları. Android'den bağımsızdır.
 *
 * Bütün durum değişiklikleri tek bir iş parçacığında (exec) yapılır; dinleyici (Listener)
 * metotları da bu iş parçacığından çağrılır, arayüz tarafı kendi iş parçacığına geçirmelidir.
 *
 * Kimlik: her oyuncunun kalıcı bir "oyuncu kodu" vardır. Konular:
 *   hapistavla/v1/lobby     : eşleşme arayanların herkese açık ilanı
 *   hapistavla/v1/u/KOD     : oyuncunun kişisel posta kutusu (eşleşme ve arkadaş davetleri)
 *   hapistavla/v1/r/ODA     : oda; iki oyuncu arasındaki oyun mesajları
 */
public final class Online {
    public interface Listener {
        void onStatus(String text);

        /** Oda kuruldu, rakip bekleniyor. */
        void onRoomCreated(String code);

        /** Oyun başlıyor. myColor: Board.WHITE (oda sahibi) ya da Board.BLACK. */
        void onMatched(int myColor, int starter, String oppCode, String oppName);

        void onRoll(int d1, int d2);

        void onMove(int from, int to, int die);

        void onEnd(String boardCode);

        void onRematch();

        void onEmote(int id);

        void onOpponentLeft(String text);

        void onError(String text);

        /** Bir arkadaş seni oyuna davet etti. */
        void onInvite(String fromCode, String fromName, String room);
    }

    public static final int IDLE = 0;
    public static final int CONNECTING = 1;
    public static final int HOST_WAIT = 2;
    public static final int JOIN_WAIT = 3;
    public static final int SEEK = 4;
    public static final int MATCH_WAIT = 5;
    public static final int PLAYING = 6;

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final String PREFIX = "hapistavla/v1/";

    private final String myCode;
    private volatile String myName;
    private final Listener listener;
    private final Mqtt.Server[] servers;
    private final Random rnd = new Random();
    private final ScheduledThreadPoolExecutor exec;

    private Mqtt mqtt;
    private volatile int mode = IDLE;
    private boolean presenceWanted = false;
    private boolean inboxSubscribed = false;
    private boolean lobbySubscribed = false;
    private String room = "";
    private String oppCode = "";
    private int myColor = Board.WHITE;
    private long lastHeard = 0;
    private int joinTries = 0;
    private String pendingInviteTo = "";
    private String pendingInviteRoom = "";
    private long pendingInviteExpire = 0;
    private int session = 0;

    private ScheduledFuture<?> seekTask;
    private ScheduledFuture<?> hbTask;
    private ScheduledFuture<?> joinTask;
    private ScheduledFuture<?> timeoutTask;
    private ScheduledFuture<?> reconnectTask;

    public void setName(String n) {
        myName = clean(n);
    }

    public Online(String myCode, String myName, Listener listener, Mqtt.Server[] servers) {
        this.myCode = myCode;
        this.myName = clean(myName);
        this.listener = listener;
        this.servers = servers;
        this.exec = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "online");
                t.setDaemon(true);
                return t;
            }
        });
    }

    public int getMode() {
        return mode;
    }

    public String getMyCode() {
        return myCode;
    }

    public static String randomCode(int n, Random r) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Uygulama başında bir kez çağrılır: sunucuya bağlanır ve kişisel posta kutusunu dinler. */
    public void start() {
        run(new Runnable() {
            @Override
            public void run() {
                presenceWanted = true;
                ensurePresence();
            }
        });
    }

    public void createRoom() {
        run(new Runnable() {
            @Override
            public void run() {
                resetSession();
                mode = CONNECTING;
                listener.onStatus("Sunucuya bağlanılıyor…");
                if (!freshConnection()) {
                    fail("Sunucuya bağlanılamadı. İnternet bağlantını kontrol et.");
                    return;
                }
                room = randomCode(5, rnd);
                if (!mqtt.subscribe(roomTopic(room))) {
                    fail("Oda kurulamadı, tekrar dene.");
                    return;
                }
                mode = HOST_WAIT;
                listener.onRoomCreated(room);
            }
        });
    }

    public void joinRoom(final String code) {
        run(new Runnable() {
            @Override
            public void run() {
                resetSession();
                mode = CONNECTING;
                listener.onStatus("Sunucuya bağlanılıyor…");
                if (!freshConnection()) {
                    fail("Sunucuya bağlanılamadı. İnternet bağlantını kontrol et.");
                    return;
                }
                room = code.trim().toUpperCase();
                if (room.length() < 4 || !mqtt.subscribe(roomTopic(room))) {
                    fail("Odaya katılınamadı.");
                    return;
                }
                mode = JOIN_WAIT;
                joinTries = 0;
                listener.onStatus("Odaya katılınıyor…");
                final int s = session;
                joinTask = exec.scheduleWithFixedDelay(guard(new Runnable() {
                    @Override
                    public void run() {
                        if (s != session || mode != JOIN_WAIT) {
                            return;
                        }
                        if (joinTries >= 6) {
                            fail("Oda bulunamadı ya da dolu. Kodu doğru yazdığından ve arkadaşının odayı açık tuttuğundan emin ol." + serverInfo());
                            return;
                        }
                        joinTries++;
                        send(roomTopic(room), "join|" + myCode + "|" + myName);
                    }
                }), 0, 2000, TimeUnit.MILLISECONDS);
            }
        });
    }

    public void findMatch() {
        run(new Runnable() {
            @Override
            public void run() {
                resetSession();
                mode = CONNECTING;
                listener.onStatus("Sunucuya bağlanılıyor…");
                if (!freshConnection()) {
                    fail("Sunucuya bağlanılamadı. İnternet bağlantını kontrol et.");
                    return;
                }
                if (!mqtt.subscribe(lobbyTopic())) {
                    fail("Eşleşme başlatılamadı.");
                    return;
                }
                lobbySubscribed = true;
                mode = SEEK;
                listener.onStatus("Rakip aranıyor…");
                final int s = session;
                seekTask = exec.scheduleWithFixedDelay(guard(new Runnable() {
                    @Override
                    public void run() {
                        if (s == session && mode == SEEK) {
                            send(lobbyTopic(), "seek|" + myCode);
                        }
                    }
                }), 0, 1500, TimeUnit.MILLISECONDS);
                timeoutTask = exec.schedule(guard(new Runnable() {
                    @Override
                    public void run() {
                        if (s == session && mode == SEEK) {
                            fail("Şu an rakip bulunamadı. Biraz sonra tekrar dene ya da oda kurup arkadaşını çağır." + serverInfo());
                        }
                    }
                }), 90, TimeUnit.SECONDS);
            }
        });
    }

    /** Oda kurma, katılma ya da eşleşme aramayı iptal eder; bağlantı açık kalır. */
    public void cancel() {
        run(new Runnable() {
            @Override
            public void run() {
                if (mode != PLAYING) {
                    resetSession();
                }
            }
        });
    }

    /** Oyundan ayrılır (rakibe haber verilir). */
    public void leave() {
        run(new Runnable() {
            @Override
            public void run() {
                if (mode == PLAYING) {
                    send(roomTopic(room), "leave|" + myCode);
                }
                resetSession();
            }
        });
    }

    /** Tamamen kapatır. */
    public void shutdown() {
        run(new Runnable() {
            @Override
            public void run() {
                presenceWanted = false;
                if (mode == PLAYING) {
                    send(roomTopic(room), "leave|" + myCode);
                }
                resetSession();
                if (mqtt != null) {
                    mqtt.close();
                    mqtt = null;
                }
                inboxSubscribed = false;
                exec.shutdown();
            }
        });
    }

    /** Kurulmuş odaya bir arkadaşı davet eder (oda kurma ekranındayken). */
    public void inviteFriend(final String friendCode) {
        run(new Runnable() {
            @Override
            public void run() {
                if ((mode == HOST_WAIT) && mqtt != null) {
                    send(userTopic(friendCode), "finv|" + myCode + "|" + myName + "|" + room);
                }
            }
        });
    }

    public void sendRoll(final int d1, final int d2) {
        game("roll|" + myCode + "|" + d1 + "|" + d2);
    }

    public void sendMove(final int from, final int to, final int die) {
        game("move|" + myCode + "|" + from + "|" + to + "|" + die);
    }

    public void sendEnd(final String boardCode) {
        game("end|" + myCode + "|" + boardCode);
    }

    public void sendRematch() {
        game("rematch|" + myCode);
    }

    public void sendEmote(final int id) {
        game("emote|" + myCode + "|" + id);
    }

    private void game(final String msg) {
        run(new Runnable() {
            @Override
            public void run() {
                if (mode == PLAYING) {
                    send(roomTopic(room), msg);
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // İç işleyiş (hepsi exec iş parçacığında)
    // ------------------------------------------------------------------

    private void run(final Runnable r) {
        try {
            exec.execute(guard(r));
        } catch (RuntimeException e) {
            // kapatılmış
        }
    }

    private Runnable guard(final Runnable r) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    r.run();
                } catch (Throwable t) {
                    // tek bir hata zamanlayıcıları durdurmasın
                }
            }
        };
    }

    private static String clean(String s) {
        if (s == null) {
            return "Oyuncu";
        }
        String c = s.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        if (c.length() > 16) {
            c = c.substring(0, 16);
        }
        return c.isEmpty() ? "Oyuncu" : c;
    }

    private static String lobbyTopic() {
        return PREFIX + "lobby";
    }

    private static String userTopic(String code) {
        return PREFIX + "u/" + code;
    }

    private static String roomTopic(String code) {
        return PREFIX + "r/" + code;
    }

    private void send(String topic, String payload) {
        if (mqtt != null) {
            mqtt.publish(topic, payload);
        }
    }

    private void fail(String text) {
        resetSession();
        listener.onError(text);
    }

    private void cancelTasks() {
        ScheduledFuture<?>[] all = {seekTask, hbTask, joinTask, timeoutTask};
        for (ScheduledFuture<?> f : all) {
            if (f != null) {
                f.cancel(false);
            }
        }
        seekTask = null;
        hbTask = null;
        joinTask = null;
        timeoutTask = null;
    }

    /** Oturumu bırakır: bütün zamanlayıcılar durur, oda ve ilan aboneliği kalkar; bağlantı açık kalır. */
    private void resetSession() {
        session++;
        cancelTasks();
        if (mqtt != null && mqtt.isConnected()) {
            if (!room.isEmpty()) {
                mqtt.unsubscribe(roomTopic(room));
            }
            if (lobbySubscribed) {
                mqtt.unsubscribe(lobbyTopic());
            }
        }
        lobbySubscribed = false;
        room = "";
        oppCode = "";
        pendingInviteTo = "";
        pendingInviteRoom = "";
        mode = IDLE;
    }

    /** Oda işlemlerinden önce bağlantıyı tazeler: uzun süre açık kalmış (sessizce kopmuş) bağlantıya güvenmeyiz. */
    private boolean freshConnection() {
        if (mqtt != null) {
            mqtt.close();
            mqtt = null;
        }
        inboxSubscribed = false;
        return ensureConnected();
    }

    private String serverInfo() {
        return mqtt == null ? "" : " [" + mqtt.info() + "]";
    }

    private boolean ensureConnected() {
        if (mqtt != null && mqtt.isConnected()) {
            return ensureInbox();
        }
        mqtt = new Mqtt(myCode, new Mqtt.Listener() {
            @Override
            public void onMessage(final String topic, final String payload) {
                run(new Runnable() {
                    @Override
                    public void run() {
                        handle(topic, payload);
                    }
                });
            }

            @Override
            public void onClosed(final String reason) {
                run(new Runnable() {
                    @Override
                    public void run() {
                        connectionLost();
                    }
                });
            }
        });
        inboxSubscribed = false;
        if (!mqtt.connect(servers, 7000)) {
            return false;
        }
        return ensureInbox();
    }

    private boolean ensureInbox() {
        if (inboxSubscribed) {
            return true;
        }
        if (mqtt.subscribe(userTopic(myCode))) {
            inboxSubscribed = true;
            return true;
        }
        return false;
    }

    private void ensurePresence() {
        if (!presenceWanted) {
            return;
        }
        if (mode == IDLE && !ensureConnected()) {
            scheduleReconnect(8000);
        }
    }

    private void scheduleReconnect(long delayMs) {
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
        }
        reconnectTask = exec.schedule(guard(new Runnable() {
            @Override
            public void run() {
                ensurePresence();
            }
        }), delayMs, TimeUnit.MILLISECONDS);
    }

    private void connectionLost() {
        inboxSubscribed = false;
        lobbySubscribed = false;
        int old = mode;
        if (old == PLAYING) {
            resetSession();
            listener.onOpponentLeft("Bağlantı koptu.");
        } else if (old != IDLE) {
            resetSession();
            listener.onError("Bağlantı koptu.");
        }
        mode = IDLE;
        if (presenceWanted) {
            scheduleReconnect(4000);
        }
    }

    private void handle(String topic, String payload) {
        String[] f = payload.split("\\|", -1);
        if (f.length < 2) {
            return;
        }
        if (topic.equals(lobbyTopic())) {
            handleLobby(f);
        } else if (topic.equals(userTopic(myCode))) {
            handleInbox(f);
        } else if (!room.isEmpty() && topic.equals(roomTopic(room))) {
            handleRoom(f);
        }
    }

    private void handleLobby(String[] f) {
        if (!f[0].equals("seek") || mode != SEEK) {
            return;
        }
        String from = f[1];
        if (from.equals(myCode) || from.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (myCode.compareTo(from) < 0 && (pendingInviteTo.isEmpty() || now > pendingInviteExpire)) {
            pendingInviteTo = from;
            pendingInviteRoom = randomCode(5, rnd);
            pendingInviteExpire = now + 6000;
            send(userTopic(from), "invite|" + myCode + "|" + myName + "|" + pendingInviteRoom);
        }
    }

    private void handleInbox(String[] f) {
        String type = f[0];
        if (type.equals("invite") && f.length >= 4) {
            // eşleşme daveti: kabul et, odaya gir
            if (mode != SEEK) {
                return;
            }
            final String from = f[1];
            final String inviteRoom = f[3];
            cancelTasks();
            mode = MATCH_WAIT;
            room = inviteRoom;
            oppCode = from;
            if (!mqtt.subscribe(roomTopic(room))) {
                fail("Eşleşme başarısız, tekrar dene.");
                return;
            }
            send(userTopic(from), "accept|" + myCode + "|" + myName + "|" + inviteRoom);
            final int s = session;
            timeoutTask = exec.schedule(guard(new Runnable() {
                @Override
                public void run() {
                    if (s == session && mode == MATCH_WAIT) {
                        fail("Eşleşme tamamlanamadı, tekrar dene.");
                    }
                }
            }), 9, TimeUnit.SECONDS);
        } else if (type.equals("accept") && f.length >= 4) {
            String from = f[1];
            String guestName = f[2];
            String acceptedRoom = f[3];
            if (mode != SEEK || !from.equals(pendingInviteTo) || !acceptedRoom.equals(pendingInviteRoom)
                    || System.currentTimeMillis() > pendingInviteExpire + 3000) {
                return;
            }
            cancelTasks();
            room = acceptedRoom;
            if (!mqtt.subscribe(roomTopic(room))) {
                fail("Eşleşme başarısız, tekrar dene.");
                return;
            }
            becomeHost(from, guestName);
        } else if (type.equals("finv") && f.length >= 4) {
            // arkadaş daveti
            listener.onInvite(f[1], f[2], f[3]);
        }
    }

    private void becomeHost(String guestCode, String guestName) {
        oppCode = guestCode;
        myColor = Board.WHITE;
        int starter = rnd.nextInt(2);
        send(roomTopic(room), "start|" + myCode + "|" + guestCode + "|" + starter + "|" + myName);
        lastStarter = starter;
        beginPlaying();
        listener.onMatched(Board.WHITE, starter, guestCode, guestName);
    }

    private int lastStarter = 0;

    private void handleRoom(String[] f) {
        String type = f[0];
        String from = f[1];
        if (from.equals(myCode)) {
            return;
        }
        if (type.equals("join") && f.length >= 3) {
            if (mode == HOST_WAIT) {
                cancelTasks();
                becomeHost(from, f[2]);
            } else if (mode == PLAYING && from.equals(oppCode)) {
                send(roomTopic(room), "start|" + myCode + "|" + oppCode + "|" + lastStarter + "|" + myName);
            } else {
                send(roomTopic(room), "full|" + myCode + "|" + from);
            }
            return;
        }
        if (type.equals("start") && f.length >= 5) {
            String guest = f[2];
            if ((mode == JOIN_WAIT || mode == MATCH_WAIT) && guest.equals(myCode)) {
                cancelTasks();
                oppCode = from;
                myColor = Board.BLACK;
                int starter = "1".equals(f[3]) ? 1 : 0;
                beginPlaying();
                listener.onMatched(Board.BLACK, starter, from, f[4]);
            }
            return;
        }
        if (type.equals("full") && f.length >= 3) {
            if (mode == JOIN_WAIT && f[2].equals(myCode)) {
                fail("Oda dolu.");
            }
            return;
        }
        if (mode != PLAYING || !from.equals(oppCode)) {
            return;
        }
        lastHeard = System.currentTimeMillis();
        try {
            if (type.equals("roll") && f.length >= 4) {
                listener.onRoll(Integer.parseInt(f[2]), Integer.parseInt(f[3]));
            } else if (type.equals("move") && f.length >= 5) {
                listener.onMove(Integer.parseInt(f[2]), Integer.parseInt(f[3]), Integer.parseInt(f[4]));
            } else if (type.equals("end") && f.length >= 3) {
                listener.onEnd(f[2]);
            } else if (type.equals("rematch")) {
                listener.onRematch();
            } else if (type.equals("emote") && f.length >= 3) {
                listener.onEmote(Integer.parseInt(f[2]));
            } else if (type.equals("leave")) {
                resetSession();
                listener.onOpponentLeft("Rakip oyundan ayrıldı.");
            }
        } catch (NumberFormatException e) {
            // bozuk mesajı yok say
        }
    }

    private void beginPlaying() {
        mode = PLAYING;
        lastHeard = System.currentTimeMillis();
        if (mqtt != null && lobbySubscribed) {
            mqtt.unsubscribe(lobbyTopic());
            lobbySubscribed = false;
        }
        final int s = session;
        hbTask = exec.scheduleWithFixedDelay(guard(new Runnable() {
            @Override
            public void run() {
                if (s != session || mode != PLAYING) {
                    return;
                }
                send(roomTopic(room), "hb|" + myCode);
                if (System.currentTimeMillis() - lastHeard > 25000) {
                    resetSession();
                    listener.onOpponentLeft("Rakibin bağlantısı koptu.");
                }
            }
        }), 3000, 4000, TimeUnit.MILLISECONDS);
    }
}
