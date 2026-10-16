package com.hapistavla.oyun;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ses dosyası gerektirmeyen küçük ses efektleri: sesler kodla üretilir ve AudioTrack ile çalınır.
 */
public final class Sfx {
    private static final int RATE = 22050;
    private final short[][] sounds = new short[5][];
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private volatile boolean enabled = true;

    public Sfx() {
        // zar: kısa, tok vuruşlar
        sounds[Game.SND_ROLL] = concat(thud(0, 70, 190), thud(60, 60, 230), thud(120, 70, 170), thud(190, 50, 260));
        // taş: tek tok "tık"
        sounds[Game.SND_MOVE] = thud(0, 90, 330);
        // hapis: iki tonlu vuruş
        sounds[Game.SND_PIN] = concat(thud(0, 80, 220), tone(0, 160, 520, 0.35));
        // kazanma: yükselen üç nota
        sounds[Game.SND_WIN] = concat(tone(0, 140, 523, 0.5), tone(0, 140, 659, 0.5), tone(0, 260, 784, 0.5));
        // kaybetme: alçalan iki nota
        sounds[Game.SND_LOSE] = concat(tone(0, 200, 392, 0.5), tone(0, 320, 294, 0.5));
    }

    public void setEnabled(boolean e) {
        enabled = e;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void play(final int kind) {
        if (!enabled || kind < 0 || kind >= sounds.length) {
            return;
        }
        final short[] data = sounds[kind];
        pool.execute(new Runnable() {
            @Override
            public void run() {
                AudioTrack t = null;
                try {
                    t = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, data.length * 2, AudioTrack.MODE_STATIC);
                    t.write(data, 0, data.length);
                    t.play();
                    Thread.sleep(data.length * 1000L / RATE + 60);
                } catch (Exception e) {
                    // ses çalınamazsa oyun etkilenmesin
                } finally {
                    if (t != null) {
                        try {
                            t.release();
                        } catch (Exception e) {
                            // yoksay
                        }
                    }
                }
            }
        });
    }

    public void release() {
        pool.shutdownNow();
    }

    // ------------------------------------------------------------------
    // Ses üretimi
    // ------------------------------------------------------------------

    private static short[] concat(short[]... parts) {
        int n = 0;
        for (short[] p : parts) {
            n += p.length;
        }
        short[] r = new short[n];
        int o = 0;
        for (short[] p : parts) {
            System.arraycopy(p, 0, r, o, p.length);
            o += p.length;
        }
        return r;
    }

    /** Saf nota (hızlı başlangıç, yumuşak sönüm). */
    private static short[] tone(int delayMs, int lenMs, double freq, double vol) {
        int d = RATE * delayMs / 1000;
        int n = RATE * lenMs / 1000;
        short[] r = new short[d + n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double env = Math.min(1.0, i / (RATE * 0.005)) * Math.exp(-4.0 * i / n);
            double v = Math.sin(2 * Math.PI * freq * t) + 0.3 * Math.sin(4 * Math.PI * freq * t);
            r[d + i] = (short) (v * env * vol * 14000);
        }
        return r;
    }

    /** Tahta üstüne düşen sert cisim sesi: kısa gürültü + alçak ton. */
    private static short[] thud(int delayMs, int lenMs, double freq) {
        int d = RATE * delayMs / 1000;
        int n = RATE * lenMs / 1000;
        short[] r = new short[d + n];
        long seed = 12345L + delayMs * 31L + (long) freq;
        java.util.Random rnd = new java.util.Random(seed);
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double env = Math.exp(-9.0 * i / n);
            double noise = (rnd.nextDouble() * 2 - 1) * Math.exp(-30.0 * i / n);
            double v = 0.8 * Math.sin(2 * Math.PI * freq * t) * env + 0.5 * noise;
            r[d + i] = (short) (v * 16000);
        }
        return r;
    }
}
