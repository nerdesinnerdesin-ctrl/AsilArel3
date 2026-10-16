package com.hapistavla.oyun;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Çevrimiçi oyun için MQTT istemcisi. Tek bir ortak sunucuya güvenmek yerine birkaç sunucuya BİRDEN
 * bağlanır: mesajlar hepsine gönderilir, hepsinden dinlenir ve çift gelenler ayıklanır. Böylece iki oyuncu
 * farklı sunuculara düşse bile (biri kapalı, biri engelli vb.) ortak bir sunucuda buluşurlar.
 */
public final class Mqtt {
    public interface Listener {
        /** Okuyucu iş parçacığında çağrılır; uzun iş yapmayın. */
        void onMessage(String topic, String payload);

        /** Tüm bağlantılar beklenmedik biçimde koptu (close() ile kapatınca çağrılmaz). */
        void onClosed(String reason);
    }

    public static final class Server {
        public final String group;
        public final String host;
        public final int port;
        public final boolean tls;

        public Server(String host, int port, boolean tls) {
            this(host, host, port, tls);
        }

        /** Aynı gruptaki sunucular sırayla denenir (biri bağlanınca diğerleri denenmez). */
        public Server(String group, String host, int port, boolean tls) {
            this.group = group;
            this.host = host;
            this.port = port;
            this.tls = tls;
        }

        @Override
        public String toString() {
            return host + ":" + port + (tls ? " (TLS)" : "");
        }
    }

    /** Kurulum gerektirmeyen, herkese açık MQTT sunucuları. Her ana makineye ayrı bağlanılır. */
    public static final Server[] PUBLIC_SERVERS = {
        new Server("broker.hivemq.com", 8883, true),
        new Server("broker.hivemq.com", 1883, false),
        new Server("broker.emqx.io", 8883, true),
        new Server("broker.emqx.io", 1883, false),
        new Server("test.mosquitto.org", 1883, false),
    };

    private static final char SEP = '\u0001';

    private final Listener listener;
    private final String clientId;
    private final List<Mqtt1> links = new CopyOnWriteArrayList<Mqtt1>();
    private final Set<String> topics = new LinkedHashSet<String>();
    private final Map<String, Boolean> seen = new LinkedHashMap<String, Boolean>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
            return size() > 600;
        }
    };
    private final AtomicInteger counter = new AtomicInteger(1);
    private final String session;
    private volatile boolean userClosed = false;
    private volatile boolean notifiedClosed = false;
    private volatile String lastError = "";
    private volatile String connectedInfo = "";

    public Mqtt(String clientId, Listener listener) {
        this.clientId = clientId;
        this.listener = listener;
        this.session = Integer.toHexString(new Random().nextInt(0x7FFFFFFF));
    }

    public boolean isConnected() {
        for (Mqtt1 l : links) {
            if (l.isConnected()) {
                return true;
            }
        }
        return false;
    }

    public String lastError() {
        return lastError;
    }

    /** Bağlanılan sunucuların kısa özeti (hata ayıklama için). */
    public String info() {
        return connectedInfo;
    }

    // ------------------------------------------------------------------
    // Bağlanma
    // ------------------------------------------------------------------

    /**
     * Her sunucu grubuna paralel bağlanır. En az biri bağlanınca döner (diğerlerine kısa bir süre daha
     * şans verilir); geç bağlananlar sonradan katılır ve mevcut aboneliklerini alır.
     */
    public boolean connect(Server[] servers, final int timeoutMs) {
        userClosed = false;
        notifiedClosed = false;
        links.clear();
        LinkedHashMap<String, List<Server>> groups = new LinkedHashMap<String, List<Server>>();
        for (Server s : servers) {
            List<Server> g = groups.get(s.group);
            if (g == null) {
                g = new ArrayList<Server>();
                groups.put(s.group, g);
            }
            g.add(s);
        }
        final CountDownLatch first = new CountDownLatch(1);
        final CountDownLatch all = new CountDownLatch(groups.size());
        final StringBuilder errors = new StringBuilder();
        for (final List<Server> g : groups.values()) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        final Mqtt1 link = new Mqtt1(clientId + "-" + session.substring(0, Math.min(4, session.length())),
                                new Mqtt1.Listener() {
                                    @Override
                                    public void onMessage(String topic, String payload) {
                                        deliver(topic, payload);
                                    }

                                    @Override
                                    public void onClosed(String reason) {
                                        linkClosed(reason);
                                    }
                                });
                        if (link.connect(g.toArray(new Server[0]), timeoutMs)) {
                            if (userClosed) {
                                link.close();
                            } else {
                                attach(link);
                                first.countDown();
                            }
                        } else {
                            synchronized (errors) {
                                errors.append(link.lastError()).append("; ");
                            }
                        }
                    } finally {
                        all.countDown();
                    }
                }
            }, "mqtt-connect");
            t.setDaemon(true);
            t.start();
        }
        try {
            long deadline = System.currentTimeMillis() + timeoutMs * 3L;
            while (first.getCount() > 0 && all.getCount() > 0 && System.currentTimeMillis() < deadline) {
                first.await(200, TimeUnit.MILLISECONDS);
            }
            if (first.getCount() == 0) {
                all.await(2500, TimeUnit.MILLISECONDS); // diğerlerine kısa bir şans
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (links.isEmpty()) {
            synchronized (errors) {
                lastError = errors.toString();
            }
            return false;
        }
        return true;
    }

    private void attach(Mqtt1 link) {
        List<String> snapshot;
        synchronized (topics) {
            snapshot = new ArrayList<String>(topics);
        }
        links.add(link);
        for (String t : snapshot) {
            link.subscribe(t);
        }
        updateInfo();
    }

    private void updateInfo() {
        StringBuilder sb = new StringBuilder();
        for (Mqtt1 l : links) {
            if (l.isConnected() && l.currentServer() != null) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(l.currentServer().host);
            }
        }
        connectedInfo = sb.toString();
    }

    private void linkClosed(String reason) {
        lastError = reason == null ? "bağlantı koptu" : reason;
        updateInfo();
        if (!userClosed && !isConnected() && !notifiedClosed) {
            notifiedClosed = true;
            listener.onClosed(lastError);
        }
    }

    private void deliver(String topic, String payload) {
        int i = payload.indexOf(SEP);
        if (i <= 0) {
            return; // başka bir istemciden gelen biçimsiz mesaj
        }
        String id = payload.substring(0, i);
        synchronized (seen) {
            if (seen.containsKey(id)) {
                return;
            }
            seen.put(id, Boolean.TRUE);
        }
        listener.onMessage(topic, payload.substring(i + 1));
    }

    // ------------------------------------------------------------------
    // Genel işlemler
    // ------------------------------------------------------------------

    /** Konuya tüm bağlantılardan abone olur; en az birinde başarılıysa true. */
    public boolean subscribe(String topic) {
        synchronized (topics) {
            topics.add(topic);
        }
        boolean ok = false;
        for (Mqtt1 l : links) {
            if (l.isConnected() && l.subscribe(topic)) {
                ok = true;
            }
        }
        return ok;
    }

    public boolean unsubscribe(String topic) {
        synchronized (topics) {
            topics.remove(topic);
        }
        boolean ok = false;
        for (Mqtt1 l : links) {
            if (l.isConnected() && l.unsubscribe(topic)) {
                ok = true;
            }
        }
        return ok;
    }

    public boolean publish(String topic, String payload) {
        String wrapped = session + "-" + counter.getAndIncrement() + SEP + payload;
        boolean ok = false;
        for (Mqtt1 l : links) {
            if (l.isConnected() && l.publish(topic, wrapped)) {
                ok = true;
            }
        }
        return ok;
    }

    /** Tüm bağlantıları kapatır; onClosed çağrılmaz. */
    public void close() {
        userClosed = true;
        for (Mqtt1 l : links) {
            l.close();
        }
        links.clear();
    }
}
