package com.hapistavla.oyun;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Google / Facebook ile giriş (cihaz kodu yöntemi: SDK gerekmez) ve Firebase ile ilerleme eşitleme.
 * Tüm çağrılar engelleyicidir; ağ iş parçacığında çağırın. Android'e bağlı değildir.
 */
public final class Cloud {
    public static final String GOOGLE = "google";
    public static final String FACEBOOK = "facebook";

    /** Ayarlar (res/values/cloud.xml). Boşsa ilgili özellik kapalıdır ve oyun misafir olarak çalışır. */
    public static final class Config {
        public String googleClientId = "";
        public String googleClientSecret = "";
        public String facebookAppId = "";
        public String facebookClientToken = "";
        public String firebaseApiKey = "";
        public String databaseUrl = "";

        // Sınama için değiştirilebilir adresler
        public String googleBase = "https://oauth2.googleapis.com";
        public String facebookBase = "https://graph.facebook.com/v19.0";
        public String identityBase = "https://identitytoolkit.googleapis.com";
        public String tokenBase = "https://securetoken.googleapis.com";

        public boolean firebaseReady() {
            return !firebaseApiKey.isEmpty() && !databaseUrl.isEmpty();
        }

        public boolean googleReady() {
            return firebaseReady() && !googleClientId.isEmpty() && !googleClientSecret.isEmpty();
        }

        public boolean facebookReady() {
            return firebaseReady() && !facebookAppId.isEmpty() && !facebookClientToken.isEmpty();
        }

        public boolean anyReady() {
            return googleReady() || facebookReady();
        }
    }

    /** Kullanıcının tarayıcıda gireceği kod. */
    public static final class DeviceCode {
        public String provider;
        public String deviceCode;
        public String userCode;
        public String url;
        public int intervalSec = 5;
        public long expiresAtMs;
    }

    public static final class Cancel {
        public volatile boolean cancelled = false;
    }

    public static class CloudException extends IOException {
        public CloudException(String m) {
            super(m);
        }
    }

    private static final class Resp {
        int code;
        String body;
    }

    private final Config cfg;
    private final Profile.Store store;
    public String uid = "";
    public String provider = "";
    public String displayName = "";
    private String idToken = "";
    private String refreshToken = "";
    private long expiresAtMs = 0;

    public Cloud(Config cfg, Profile.Store store) {
        this.cfg = cfg;
        this.store = store;
        uid = store.get("c_uid", "");
        provider = store.get("c_provider", "");
        displayName = store.get("c_name", "");
        idToken = store.get("c_id", "");
        refreshToken = store.get("c_refresh", "");
        try {
            expiresAtMs = Long.parseLong(store.get("c_exp", "0"));
        } catch (NumberFormatException e) {
            expiresAtMs = 0;
        }
    }

    public Config config() {
        return cfg;
    }

    public boolean loggedIn() {
        return !uid.isEmpty() && !refreshToken.isEmpty();
    }

    public void logout() {
        uid = "";
        provider = "";
        displayName = "";
        idToken = "";
        refreshToken = "";
        expiresAtMs = 0;
        saveSession();
    }

    private void saveSession() {
        store.put("c_uid", uid);
        store.put("c_provider", provider);
        store.put("c_name", displayName);
        store.put("c_id", idToken);
        store.put("c_refresh", refreshToken);
        store.put("c_exp", String.valueOf(expiresAtMs));
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String form(String... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(enc(kv[i])).append('=').append(enc(kv[i + 1]));
        }
        return sb.toString();
    }

    private static Resp http(String method, String url, String contentType, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(12000);
            c.setReadTimeout(15000);
            c.setRequestMethod(method);
            c.setRequestProperty("Accept", "application/json");
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", contentType);
                byte[] b = body.getBytes("UTF-8");
                OutputStream o = c.getOutputStream();
                o.write(b);
                o.close();
            }
            Resp r = new Resp();
            r.code = c.getResponseCode();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && bo.size() < 2000000) {
                    bo.write(buf, 0, n);
                }
                in.close();
            }
            r.body = bo.toString("UTF-8");
            return r;
        } finally {
            c.disconnect();
        }
    }

    private static Resp postForm(String url, String... kv) throws IOException {
        return http("POST", url, "application/x-www-form-urlencoded", form(kv));
    }

    // ------------------------------------------------------------------
    // Giriş: cihaz kodu
    // ------------------------------------------------------------------

    public DeviceCode beginLogin(String prov) throws IOException {
        DeviceCode d = new DeviceCode();
        d.provider = prov;
        Resp r;
        if (GOOGLE.equals(prov)) {
            if (!cfg.googleReady()) {
                throw new CloudException("Google girişi kurulmamış.");
            }
            r = postForm(cfg.googleBase + "/device/code", "client_id", cfg.googleClientId,
                    "scope", "openid email profile");
            Map<String, Object> m = Json.parseObject(r.body);
            if (r.code != 200 || m == null || Json.str(m, "device_code", "").isEmpty()) {
                throw new CloudException("Google kod alınamadı (" + r.code + "): " + shortBody(r.body));
            }
            d.deviceCode = Json.str(m, "device_code", "");
            d.userCode = Json.str(m, "user_code", "");
            d.url = Json.str(m, "verification_url", Json.str(m, "verification_uri", "https://www.google.com/device"));
            d.intervalSec = (int) Math.max(2, Json.num(m, "interval", 5));
            d.expiresAtMs = System.currentTimeMillis() + Json.num(m, "expires_in", 600) * 1000L;
        } else if (FACEBOOK.equals(prov)) {
            if (!cfg.facebookReady()) {
                throw new CloudException("Facebook girişi kurulmamış.");
            }
            r = postForm(cfg.facebookBase + "/device/login",
                    "access_token", cfg.facebookAppId + "|" + cfg.facebookClientToken,
                    "scope", "public_profile");
            Map<String, Object> m = Json.parseObject(r.body);
            if (r.code != 200 || m == null || Json.str(m, "code", "").isEmpty()) {
                throw new CloudException("Facebook kod alınamadı (" + r.code + "): " + shortBody(r.body));
            }
            d.deviceCode = Json.str(m, "code", "");
            d.userCode = Json.str(m, "user_code", "");
            d.url = Json.str(m, "verification_uri", "https://www.facebook.com/device");
            d.intervalSec = (int) Math.max(2, Json.num(m, "interval", 5));
            d.expiresAtMs = System.currentTimeMillis() + Json.num(m, "expires_in", 420) * 1000L;
        } else {
            throw new CloudException("Bilinmeyen giriş yöntemi.");
        }
        return d;
    }

    private static String shortBody(String b) {
        if (b == null) {
            return "";
        }
        String t = b.replace('\n', ' ');
        return t.length() > 160 ? t.substring(0, 160) : t;
    }

    /** Kullanıcı onaylayana kadar bekler; başarılıysa oturumu açar. İptal için cancel.cancelled=true yapın. */
    public void finishLogin(DeviceCode d, Cancel cancel) throws IOException {
        int interval = d.intervalSec;
        String providerToken = null;
        String postBodyKey = null;
        while (providerToken == null) {
            if (cancel != null && cancel.cancelled) {
                throw new CloudException("İptal edildi.");
            }
            if (System.currentTimeMillis() > d.expiresAtMs) {
                throw new CloudException("Kodun süresi doldu. Tekrar dene.");
            }
            sleep(interval * 1000L, cancel);
            if (cancel != null && cancel.cancelled) {
                throw new CloudException("İptal edildi.");
            }
            if (GOOGLE.equals(d.provider)) {
                Resp r = postForm(cfg.googleBase + "/token", "client_id", cfg.googleClientId,
                        "client_secret", cfg.googleClientSecret, "device_code", d.deviceCode,
                        "grant_type", "urn:ietf:params:oauth:grant-type:device_code");
                Map<String, Object> m = Json.parseObject(r.body);
                String err = Json.str(m, "error", "");
                if (m != null && !Json.str(m, "id_token", "").isEmpty()) {
                    providerToken = Json.str(m, "id_token", "");
                    postBodyKey = "id_token";
                } else if ("authorization_pending".equals(err)) {
                    continue;
                } else if ("slow_down".equals(err)) {
                    interval += 5;
                } else if ("access_denied".equals(err)) {
                    throw new CloudException("Giriş reddedildi.");
                } else if ("expired_token".equals(err)) {
                    throw new CloudException("Kodun süresi doldu. Tekrar dene.");
                } else {
                    throw new CloudException("Google girişi başarısız (" + r.code + "): " + shortBody(r.body));
                }
            } else {
                Resp r = postForm(cfg.facebookBase + "/device/login_status",
                        "access_token", cfg.facebookAppId + "|" + cfg.facebookClientToken,
                        "code", d.deviceCode);
                Map<String, Object> m = Json.parseObject(r.body);
                if (m != null && !Json.str(m, "access_token", "").isEmpty()) {
                    providerToken = Json.str(m, "access_token", "");
                    postBodyKey = "access_token";
                } else {
                    Map<String, Object> e = Json.obj(m, "error");
                    long code = Json.num(e, "code", 0);
                    long sub = Json.num(e, "error_subcode", 0);
                    if (code == 31 || sub == 1349174) {
                        continue; // henüz onaylanmadı
                    } else if (sub == 1349172) {
                        interval += 5; // çok sık sorduk
                    } else if (sub == 1349152) {
                        throw new CloudException("Kodun süresi doldu. Tekrar dene.");
                    } else {
                        throw new CloudException("Facebook girişi başarısız (" + r.code + "): " + shortBody(r.body));
                    }
                }
            }
        }
        firebaseSignIn(d.provider, postBodyKey, providerToken);
    }

    private static void sleep(long ms, Cancel cancel) throws IOException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (cancel != null && cancel.cancelled) {
                return;
            }
            try {
                Thread.sleep(Math.min(250, Math.max(1, end - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CloudException("Kesildi.");
            }
        }
    }

    private void firebaseSignIn(String prov, String key, String token) throws IOException {
        String providerId = GOOGLE.equals(prov) ? "google.com" : "facebook.com";
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("postBody", key + "=" + enc(token) + "&providerId=" + providerId);
        body.put("requestUri", "http://localhost");
        body.put("returnIdpCredential", Boolean.TRUE);
        body.put("returnSecureToken", Boolean.TRUE);
        Resp r = http("POST", cfg.identityBase + "/v1/accounts:signInWithIdp?key=" + enc(cfg.firebaseApiKey),
                "application/json", Json.write(body));
        Map<String, Object> m = Json.parseObject(r.body);
        if (r.code != 200 || m == null || Json.str(m, "idToken", "").isEmpty()) {
            throw new CloudException("Hesap doğrulanamadı (" + r.code + "): " + shortBody(r.body));
        }
        uid = Json.str(m, "localId", "");
        idToken = Json.str(m, "idToken", "");
        refreshToken = Json.str(m, "refreshToken", "");
        expiresAtMs = System.currentTimeMillis() + Json.num(m, "expiresIn", 3600) * 1000L;
        provider = prov;
        String n = Json.str(m, "displayName", "");
        displayName = n.isEmpty() ? Json.str(m, "email", "") : n;
        if (uid.isEmpty() || refreshToken.isEmpty()) {
            throw new CloudException("Hesap bilgisi eksik geldi.");
        }
        saveSession();
    }

    /** Gerekirse oturum anahtarını yeniler. */
    private void ensureToken() throws IOException {
        if (!loggedIn()) {
            throw new CloudException("Giriş yapılmamış.");
        }
        if (System.currentTimeMillis() < expiresAtMs - 60000L && !idToken.isEmpty()) {
            return;
        }
        Resp r = postForm(cfg.tokenBase + "/v1/token?key=" + enc(cfg.firebaseApiKey),
                "grant_type", "refresh_token", "refresh_token", refreshToken);
        Map<String, Object> m = Json.parseObject(r.body);
        if (r.code != 200 || m == null || Json.str(m, "id_token", "").isEmpty()) {
            if (r.code == 400 || r.code == 401) {
                logout(); // oturum geçersiz
                throw new CloudException("Oturum süresi dolmuş, tekrar giriş yap.");
            }
            throw new CloudException("Oturum yenilenemedi (" + r.code + ").");
        }
        idToken = Json.str(m, "id_token", "");
        String nr = Json.str(m, "refresh_token", "");
        if (!nr.isEmpty()) {
            refreshToken = nr;
        }
        expiresAtMs = System.currentTimeMillis() + Json.num(m, "expires_in", 3600) * 1000L;
        saveSession();
    }

    // ------------------------------------------------------------------
    // Veri tabanı (Firebase Realtime Database REST)
    // ------------------------------------------------------------------

    private String dbUrl(String path, boolean auth) {
        String base = cfg.databaseUrl.endsWith("/") ? cfg.databaseUrl.substring(0, cfg.databaseUrl.length() - 1)
                : cfg.databaseUrl;
        return base + "/" + path + ".json" + (auth ? "?auth=" + enc(idToken) : "");
    }

    /** Profili buluta yazar (kendi alanına ve herkese açık özet kartına). */
    public void upload(Profile p) throws IOException {
        ensureToken();
        Resp r = http("PUT", dbUrl("users/" + uid, true), "application/json", Json.write(p.toMap()));
        if (r.code != 200) {
            throw new CloudException("Kaydedilemedi (" + r.code + "): " + shortBody(r.body));
        }
        Map<String, Object> pub = new LinkedHashMap<String, Object>();
        pub.put("uid", uid);
        pub.put("name", p.name);
        pub.put("level", (long) p.level());
        pub.put("wins", (long) p.wins);
        Resp r2 = http("PUT", dbUrl("public/" + p.id, true), "application/json", Json.write(pub));
        if (r2.code != 200) {
            throw new CloudException("Herkese açık kart kaydedilemedi (" + r2.code + "): " + shortBody(r2.body));
        }
    }

    /** Buluttaki profili döndürür; hiç yoksa null. */
    public Map<String, Object> download() throws IOException {
        ensureToken();
        Resp r = http("GET", dbUrl("users/" + uid, true), null, null);
        if (r.code != 200) {
            throw new CloudException("Okunamadı (" + r.code + "): " + shortBody(r.body));
        }
        return Json.parseObject(r.body);
    }

    /** Oyuncu kodunun herkese açık kartını getirir (ad, seviye); yoksa null. */
    public Map<String, Object> lookup(String code) throws IOException {
        if (!cfg.firebaseReady()) {
            return null;
        }
        Resp r = http("GET", dbUrl("public/" + code, false), null, null);
        if (r.code != 200) {
            return null;
        }
        return Json.parseObject(r.body);
    }

    /** İndir, birleştir, geri yükle. Oyuncu kodu değiştiyse true döner. */
    public boolean sync(Profile p) throws IOException {
        String before = p.id;
        Map<String, Object> remote = download();
        p.mergeFrom(remote);
        upload(p);
        return !before.equals(p.id);
    }
}
