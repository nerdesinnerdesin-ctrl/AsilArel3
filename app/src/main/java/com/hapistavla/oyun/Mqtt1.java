package com.hapistavla.oyun;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Tek sunucuya bağlanan MQTT 3.1.1 bağlantısı; Mqtt sınıfı bunlardan birkaçını birlikte kullanır. Çok küçük bir istemci (yalnızca QoS 0). Harici kütüphane gerektirmez ve
 * Android'den bağımsızdır. Çevrimiçi oyun, kurulum gerektirmeyen ortak MQTT sunucuları
 * üzerinden mesajlaşmak için bunu kullanır.
 */
final class Mqtt1 {
    public interface Listener {
        /** Okuyucu iş parçacığında çağrılır; uzun iş yapmayın. */
        void onMessage(String topic, String payload);

        /** Bağlantı beklenmedik biçimde koptu (close() ile kapatınca çağrılmaz). */
        void onClosed(String reason);
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final Listener listener;
    private final String clientId;
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private final Object writeLock = new Object();
    private volatile boolean connected = false;
    private volatile long lastRx = System.currentTimeMillis();
    private volatile boolean userClosed = false;
    private final ConcurrentHashMap<Integer, CountDownLatch> acks = new ConcurrentHashMap<Integer, CountDownLatch>();
    private final AtomicInteger packetId = new AtomicInteger(1);
    private volatile String lastError = "";
    private Mqtt.Server current;

    public Mqtt1(String clientId, Listener listener) {
        this.clientId = clientId;
        this.listener = listener;
    }

    public boolean isConnected() {
        return connected;
    }

    public String lastError() {
        return lastError;
    }

    public Mqtt.Server currentServer() {
        return current;
    }

    // ------------------------------------------------------------------
    // Bağlanma
    // ------------------------------------------------------------------

    /** Sunucuları sırayla dener; biri bağlanırsa true döner. Engelleyicidir, ağ iş parçacığında çağırın. */
    public boolean connect(Mqtt.Server[] servers, int timeoutMs) {
        userClosed = false;
        for (Mqtt.Server s : servers) {
            try {
                open(s, timeoutMs);
                sendRaw(connectPacket());
                int[] hdr = new int[1];
                byte[] body = readPacket(hdr);
                if ((hdr[0] >> 4) != 2 || body.length < 2) {
                    throw new IOException("CONNACK bekleniyordu");
                }
                if (body[1] != 0) {
                    throw new IOException("Sunucu bağlantıyı reddetti (kod " + body[1] + ")");
                }
                socket.setSoTimeout(0);
                lastRx = System.currentTimeMillis();
                connected = true;
                current = s;
                startThreads();
                return true;
            } catch (IOException e) {
                lastError = s + ": " + e.getMessage();
                closeSocket();
            }
        }
        return false;
    }

    private void open(Mqtt.Server s, int timeoutMs) throws IOException {
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(s.host, s.port), timeoutMs);
        raw.setTcpNoDelay(true);
        raw.setSoTimeout(timeoutMs);
        if (s.tls) {
            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket ss = (SSLSocket) f.createSocket(raw, s.host, s.port, true);
            SSLParameters pr = ss.getSSLParameters();
            pr.setEndpointIdentificationAlgorithm("HTTPS");
            ss.setSSLParameters(pr);
            ss.startHandshake();
            socket = ss;
        } else {
            socket = raw;
        }
        in = new BufferedInputStream(socket.getInputStream());
        out = socket.getOutputStream();
    }

    private byte[] connectPacket() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        putStr(b, "MQTT");
        b.write(4);          // protokol düzeyi 3.1.1
        b.write(0x02);       // temiz oturum
        b.write(0);
        b.write(45);         // keep-alive 45 sn
        putStr(b, clientId);
        return packet(0x10, b.toByteArray());
    }

    private void startThreads() {
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "mqtt-reader");
        reader.setDaemon(true);
        reader.start();
        Thread ping = new Thread(new Runnable() {
            @Override
            public void run() {
                while (connected) {
                    try {
                        Thread.sleep(15000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!connected) {
                        return;
                    }
                    if (System.currentTimeMillis() - lastRx > 40000) {
                        failed("sunucu yanıt vermiyor");
                        return;
                    }
                    try {
                        sendRaw(new byte[] {(byte) 0xC0, 0});
                    } catch (IOException e) {
                        failed(e.getMessage());
                        return;
                    }
                }
            }
        }, "mqtt-ping");
        ping.setDaemon(true);
        ping.start();
    }

    private void readLoop() {
        try {
            while (connected) {
                int[] hdr = new int[1];
                byte[] body = readPacket(hdr);
                int type = hdr[0] >> 4;
                int flags = hdr[0] & 0x0F;
                if (type == 3) {
                    handlePublish(flags, body);
                } else if (type == 9 || type == 11) { // SUBACK, UNSUBACK
                    if (body.length >= 2) {
                        int id = ((body[0] & 255) << 8) | (body[1] & 255);
                        CountDownLatch l = acks.remove(id);
                        if (l != null) {
                            l.countDown();
                        }
                    }
                }
                // PINGRESP (13) ve diğerleri yok sayılır
            }
        } catch (IOException e) {
            failed(e.getMessage());
        }
    }

    private void handlePublish(int flags, byte[] body) throws IOException {
        if (body.length < 2) {
            return;
        }
        int tl = ((body[0] & 255) << 8) | (body[1] & 255);
        if (2 + tl > body.length) {
            return;
        }
        String topic = new String(body, 2, tl, UTF8);
        int off = 2 + tl;
        int qos = (flags >> 1) & 3;
        if (qos > 0) {
            if (off + 2 > body.length) {
                return;
            }
            if (qos == 1) {
                sendRaw(new byte[] {0x40, 2, body[off], body[off + 1]});
            }
            off += 2;
        }
        String payload = new String(body, off, body.length - off, UTF8);
        try {
            listener.onMessage(topic, payload);
        } catch (RuntimeException e) {
            // dinleyicideki hata bağlantıyı bozmasın
        }
    }

    private void failed(String why) {
        boolean wasConnected = connected;
        connected = false;
        closeSocket();
        if (wasConnected && !userClosed) {
            lastError = why == null ? "bağlantı koptu" : why;
            listener.onClosed(lastError);
        }
    }

    private void closeSocket() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            // yoksay
        }
    }

    // ------------------------------------------------------------------
    // Paketler
    // ------------------------------------------------------------------

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException("bağlantı kapandı");
        }
        return b;
    }

    private byte[] readPacket(int[] headerOut) throws IOException {
        int h = readByte();
        lastRx = System.currentTimeMillis();
        headerOut[0] = h;
        int mult = 1;
        int len = 0;
        int e;
        int guard = 0;
        do {
            e = readByte();
            len += (e & 127) * mult;
            mult *= 128;
            if (++guard > 4) {
                throw new IOException("bozuk paket");
            }
        } while ((e & 128) != 0);
        if (len > 1000000) {
            throw new IOException("çok büyük paket");
        }
        byte[] body = new byte[len];
        int got = 0;
        while (got < len) {
            int n = in.read(body, got, len - got);
            if (n < 0) {
                throw new EOFException("bağlantı kapandı");
            }
            got += n;
        }
        return body;
    }

    private static void putStr(ByteArrayOutputStream o, String s) {
        byte[] b = s.getBytes(UTF8);
        o.write((b.length >> 8) & 255);
        o.write(b.length & 255);
        o.write(b, 0, b.length);
    }

    private static byte[] packet(int header, byte[] body) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(body.length + 5);
        o.write(header);
        int len = body.length;
        do {
            int d = len % 128;
            len /= 128;
            if (len > 0) {
                d |= 128;
            }
            o.write(d);
        } while (len > 0);
        o.write(body, 0, body.length);
        return o.toByteArray();
    }

    private void sendRaw(byte[] data) throws IOException {
        synchronized (writeLock) {
            if (out == null) {
                throw new IOException("bağlı değil");
            }
            out.write(data);
            out.flush();
        }
    }

    private int nextId() {
        int id = packetId.getAndIncrement();
        if (id > 65535) {
            packetId.set(2);
            id = 1;
        }
        return id;
    }

    // ------------------------------------------------------------------
    // Genel işlemler
    // ------------------------------------------------------------------

    /** Konuya abone olur ve onay gelene kadar bekler (en fazla 6 sn). */
    public boolean subscribe(String topic) {
        return sendAcked(0x82, topic, true);
    }

    public boolean unsubscribe(String topic) {
        return sendAcked(0xA2, topic, false);
    }

    private boolean sendAcked(int header, String topic, boolean withQos) {
        if (!connected) {
            return false;
        }
        int id = nextId();
        CountDownLatch latch = new CountDownLatch(1);
        acks.put(id, latch);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write((id >> 8) & 255);
        b.write(id & 255);
        putStr(b, topic);
        if (withQos) {
            b.write(0);
        }
        try {
            sendRaw(packet(header, b.toByteArray()));
            return latch.await(6, TimeUnit.SECONDS);
        } catch (IOException e) {
            failed(e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            acks.remove(id);
        }
    }

    public boolean publish(String topic, String payload) {
        if (!connected) {
            return false;
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        putStr(b, topic);
        byte[] p = payload.getBytes(UTF8);
        b.write(p, 0, p.length);
        try {
            sendRaw(packet(0x30, b.toByteArray()));
            return true;
        } catch (IOException e) {
            failed(e.getMessage());
            return false;
        }
    }

    /** Bağlantıyı kapatır; onClosed çağrılmaz. */
    public void close() {
        userClosed = true;
        if (connected) {
            try {
                sendRaw(new byte[] {(byte) 0xE0, 0});
            } catch (IOException e) {
                // yoksay
            }
        }
        connected = false;
        closeSocket();
    }
}
