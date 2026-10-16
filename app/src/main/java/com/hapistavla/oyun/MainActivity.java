package com.hapistavla.oyun;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.initialization.InitializationStatus;
import com.google.android.gms.ads.initialization.OnInitializationCompleteListener;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.List;

public class MainActivity extends Activity implements TahtaView.Callbacks {
    private AdView adView;
    private TahtaView view;
    private Online online;
    private AlertDialog waitDialog;
    private Profile profile;
    private Sfx sfx;
    private Cloud cloud;
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private Cloud.Cancel loginCancel;
    private AlertDialog loginDialog;
    private SharedPreferences prefs;

    private static final String RULES_TEXT =
        "Hapis tavla (Mahbus / Plakoto) iki kişilik bir tavla çeşididir.\n\n"
        + "• Her oyuncunun 15 taşı vardır. Oyun başında taşların hepsi rakibin başlangıç noktasındadır: "
        + "Beyaz 24'te, Siyah 1'de.\n"
        + "• Beyaz 24'ten 1'e, Siyah 1'den 24'e doğru ilerler. Evin sağ altta (ya da çevrimiçi oyunda her zaman "
        + "senin tarafın sağ altta).\n"
        + "• Taş vurulmaz, hapsedilir. Rakibin TEK taşının durduğu noktaya inersen o taşı hapsedersin. "
        + "Hapsedilen taş, senin taşların o noktadan tamamen ayrılana kadar oynayamaz.\n"
        + "• İki ya da daha fazla taşın olduğu nokta kapalıdır, rakip oraya inemez. "
        + "Hapis yaptığın nokta da rakibe kapalıdır.\n"
        + "• İki zar ayrı ayrı oynanır. Çift gelirse dört kez oynanır. Mümkünse tüm zarlar kullanılmak "
        + "zorundadır; yalnızca biri oynanabiliyorsa büyük olanı oynarsın.\n"
        + "• İLK EL: Her oyuncu ilk elinde en az 2 farklı taş oynamak zorundadır.\n"
        + "• İlk elden sonra zarların toplamını tek taşla oynayabilirsin: taşı seç, mavi işaretli yere dokun "
        + "(ara nokta açık olmalı).\n"
        + "• 15 taşın hepsi evine girince taş toplamaya başlarsın. İlk toplayan kazanır.\n"
        + "• Rakip hiç taş toplayamadıysa MARS: 2 puan. Aksi halde 1 puan.\n"
        + "• Anne taş: başlangıç noktandaki SON taşın hapsedilirse ve rakibin başlangıç noktası "
        + "boşalmışsa oyunu mars ile kaybedersin. Rakibin başlangıç noktasında hâlâ taş varsa oyun sürer. "
        + "İki anne taş da hapisteyse oyun berabere biter.\n\n"
        + "Oynarken: önce taşına, sonra gideceği noktaya dokun. Yeşil: normal hamle, mavi: zar toplamı, "
        + "turuncu: hapis yapan hamle. Kırmızı halkalı taşlar hapistedir.";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        prefs = getSharedPreferences("hapis", MODE_PRIVATE);
        profile = new Profile(new Profile.Store() {
            @Override
            public String get(String key, String def) {
                return prefs.getString("p_" + key, def);
            }

            @Override
            public void put(String key, String value) {
                prefs.edit().putString("p_" + key, value).apply();
            }
        });
        Cloud.Config cc = new Cloud.Config();
        cc.firebaseApiKey = getString(R.string.firebase_api_key).trim();
        cc.databaseUrl = getString(R.string.firebase_database_url).trim();
        cc.googleClientId = getString(R.string.google_client_id).trim();
        cc.googleClientSecret = getString(R.string.google_client_secret).trim();
        cc.facebookAppId = getString(R.string.facebook_app_id).trim();
        cc.facebookClientToken = getString(R.string.facebook_client_token).trim();
        cloud = new Cloud(cc, new Profile.Store() {
            @Override
            public String get(String key, String def) {
                return prefs.getString("x_" + key, def);
            }

            @Override
            public void put(String key, String value) {
                prefs.edit().putString("x_" + key, value).apply();
            }
        });
        sfx = new Sfx();
        sfx.setEnabled(!"0".equals(prefs.getString("sound", "1")));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF120D0A);

        view = new TahtaView(this);
        view.setCallbacks(this);
        view.setSfx(sfx);
        int th = 0;
        try {
            th = Integer.parseInt(prefs.getString("theme", "0"));
        } catch (NumberFormatException e) {
            th = 0;
        }
        if (th >= 0 && th < Gfx.THEMES.length && profile.level() >= Gfx.THEMES[th].unlockLevel) {
            view.setTheme(th);
        }
        root.addView(view, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // AdMob banner: en altta (varsayılan olarak Google'ın TEST kimliği)
        adView = new AdView(this);
        adView.setAdSize(AdSize.BANNER);
        adView.setAdUnitId(getString(R.string.admob_banner_unit_id));
        LinearLayout.LayoutParams adParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        adParams.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(adView, adParams);

        setContentView(root);
        hideSystemUi();

        MobileAds.initialize(this, new OnInitializationCompleteListener() {
            @Override
            public void onInitializationComplete(InitializationStatus initializationStatus) {
                // başlatma bitti
            }
        });
        adView.loadAd(new AdRequest.Builder().build());

        online = new Online(profile.id, profile.name, view.game.onlineListener(), Mqtt.PUBLIC_SERVERS);
        view.game.setOnline(online);
        online.start();
        showPage(PG_MAIN);
        syncNow(false);

        int bonus = profile.claimDaily(System.currentTimeMillis());
        if (bonus > 0) {
            Toast.makeText(this, "Günlük bonus: +" + bonus + " puan (üst üste " + profile.dailyStreak + ". gün)",
                    Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------
    // Menü ve kurallar
    // ------------------------------------------------------------------

    private AlertDialog.Builder builder() {
        return new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK);
    }

    @Override
    public void onRules() {
        builder().setTitle("Hapis Tavla Kuralları").setMessage(RULES_TEXT).setPositiveButton("Tamam", null).show();
    }

    // Menü sayfaları
    private static final int PG_MAIN = 0;
    private static final int PG_AI = 1;
    private static final int PG_ONLINE = 2;
    private static final int PG_SETTINGS = 3;
    private static final int PG_ACCOUNT = 4;

    // Menü düğme kimlikleri
    private static final int M_RESUME = 1;
    private static final int M_AI = 2;
    private static final int M_TWO = 3;
    private static final int M_ONLINE = 4;
    private static final int M_PROFILE = 5;
    private static final int M_FRIENDS = 6;
    private static final int M_SETTINGS = 7;
    private static final int M_RULES = 8;
    private static final int M_BACK = 9;
    private static final int M_EASY = 10;
    private static final int M_NORMAL = 11;
    private static final int M_HARD = 12;
    private static final int M_CREATE = 13;
    private static final int M_JOIN = 14;
    private static final int M_FIND = 15;
    private static final int M_THEME = 16;
    private static final int M_SOUND = 17;
    private static final int M_LEAVE = 18;
    private static final int M_EMOTE = 19;
    private static final int M_ADDOPP = 20;
    private static final int M_ACCOUNT = 21;
    private static final int M_GOOGLE = 22;
    private static final int M_FACEBOOK = 23;
    private static final int M_SYNC = 24;
    private static final int M_LOGOUT = 25;
    private static final int M_INSTA = 26;
    private static final int M_INFO = 27;

    private int menuPage = PG_MAIN;
    private boolean gameStarted = false;

    private static void add(List<TahtaView.MenuItem> l, int id, String label, boolean primary) {
        l.add(new TahtaView.MenuItem(id, label, primary));
    }

    private boolean canAddOpponent() {
        String oc = view.game.oppCode;
        return view.game.mode == Game.MODE_ONLINE && oc != null && oc.length() == 8
                && profile.findFriend(oc) == null && !oc.equals(profile.id);
    }

    private void showPage(int page) {
        menuPage = page;
        List<TahtaView.MenuItem> l = new ArrayList<TahtaView.MenuItem>();
        String title = "HAPİS TAVLA";
        String sub = "Seviye " + profile.level() + "  •  " + profile.rankTitle() + "  •  " + profile.name;
        boolean inOnline = view.game.mode == Game.MODE_ONLINE;
        if (page == PG_AI) {
            sub = "Zorluk seç";
            add(l, M_EASY, "KOLAY", false);
            add(l, M_NORMAL, "ORTA", true);
            add(l, M_HARD, "ZOR", false);
            add(l, M_BACK, "‹ GERİ", false);
        } else if (page == PG_ONLINE) {
            sub = "Çevrimiçi oyun";
            add(l, M_CREATE, "ODA KUR (ARKADAŞINLA)", true);
            add(l, M_JOIN, "ODAYA KATIL (KOD)", false);
            add(l, M_FIND, "RAKİP BUL (EŞLEŞME)", false);
            add(l, M_BACK, "‹ GERİ", false);
        } else if (page == PG_ACCOUNT) {
            sub = "Hesap ve ilerleme";
            if (cloud.loggedIn()) {
                String who = cloud.displayName.isEmpty() ? "HESAP BAĞLI" : cloud.displayName.toUpperCase();
                add(l, M_INFO, "✔ " + who + (Cloud.GOOGLE.equals(cloud.provider) ? " (GOOGLE)" : " (FACEBOOK)"), true);
                add(l, M_SYNC, "ŞİMDİ EŞİTLE", false);
                add(l, M_LOGOUT, "ÇIKIŞ YAP", false);
            } else if (cloud.config().anyReady()) {
                if (cloud.config().googleReady()) {
                    add(l, M_GOOGLE, "GOOGLE İLE GİR", true);
                }
                if (cloud.config().facebookReady()) {
                    add(l, M_FACEBOOK, "FACEBOOK İLE GİR", !cloud.config().googleReady());
                }
            } else {
                add(l, M_INFO, "GİRİŞ HENÜZ KURULMADI", false);
            }
            add(l, M_INSTA, "ARKADAŞLARINI DAVET ET (INSTAGRAM, WHATSAPP…)", false);
            add(l, M_BACK, "‹ GERİ", false);
        } else if (page == PG_SETTINGS) {
            sub = "Ayarlar";
            add(l, M_THEME, "TAHTA TEMASI: " + Gfx.THEMES[view.getTheme()].name.toUpperCase(), false);
            add(l, M_SOUND, sfx.isEnabled() ? "SES: AÇIK" : "SES: KAPALI", false);
            add(l, M_BACK, "‹ GERİ", false);
        } else {
            if (gameStarted) {
                add(l, M_RESUME, "OYUNA DÖN", true);
            }
            if (inOnline) {
                add(l, M_LEAVE, "OYUNDAN ÇIK", false);
                add(l, M_EMOTE, "HIZLI MESAJ", false);
                if (canAddOpponent()) {
                    add(l, M_ADDOPP, "RAKİBİ ARKADAŞ EKLE", false);
                }
            } else {
                add(l, M_AI, "BİLGİSAYARA KARŞI", !gameStarted);
                add(l, M_TWO, "İKİ KİŞİ (AYNI TELEFON)", false);
                add(l, M_ONLINE, "ÇEVRİMİÇİ OYNA", false);
            }
            add(l, M_PROFILE, "PROFİLİM", false);
            add(l, M_FRIENDS, "ARKADAŞLAR", false);
            if (!inOnline) {
                add(l, M_ACCOUNT, cloud.loggedIn() ? "HESABIM ✔" : "GİRİŞ YAP / HESAP", false);
            }
            add(l, M_SETTINGS, "AYARLAR", false);
            add(l, M_RULES, "KURALLAR", false);
        }
        view.showMenu(title, sub, l);
    }

    private void startGame() {
        gameStarted = true;
        view.hideMenu();
    }

    @Override
    public void onMenu() {
        showPage(PG_MAIN);
    }

    @Override
    public void onMenuItem(int id) {
        switch (id) {
            case M_RESUME:
                view.hideMenu();
                break;
            case M_AI:
                showPage(PG_AI);
                break;
            case M_ONLINE:
                showPage(PG_ONLINE);
                break;
            case M_SETTINGS:
                showPage(PG_SETTINGS);
                break;
            case M_ACCOUNT:
                showPage(PG_ACCOUNT);
                break;
            case M_GOOGLE:
                startLogin(Cloud.GOOGLE);
                break;
            case M_FACEBOOK:
                startLogin(Cloud.FACEBOOK);
                break;
            case M_SYNC:
                syncNow(true);
                break;
            case M_LOGOUT:
                cloud.logout();
                Toast.makeText(this, "Çıkış yapıldı. İlerlemen bu telefonda duruyor.", Toast.LENGTH_LONG).show();
                showPage(PG_ACCOUNT);
                break;
            case M_INSTA:
                share("Davet et", "Hapis Tavla oynayalım! Oyuncu kodum: " + profile.displayId()
                        + "  (Uygulamada Menü > Arkadaşlar > Arkadaş ekle)");
                break;
            case M_INFO:
                if (!cloud.config().anyReady() && !cloud.loggedIn()) {
                    builder().setTitle("Giriş henüz kurulmadı").setMessage(
                            "Google/Facebook girişi için oyunun yapımcısının bir kerelik ayar yapması gerekir "
                            + "(KURULUM_GIRIS.md dosyasında anlatılıyor). O zamana kadar oyun misafir olarak "
                            + "çalışır; ilerlemen bu telefonda saklanır.").setPositiveButton("Tamam", null).show();
                }
                break;
            case M_BACK:
                showPage(PG_MAIN);
                break;
            case M_TWO:
                view.game.setMode(Game.MODE_TWO, Ai.NORMAL);
                startGame();
                break;
            case M_EASY:
                view.game.setMode(Game.MODE_AI, Ai.EASY);
                startGame();
                break;
            case M_NORMAL:
                view.game.setMode(Game.MODE_AI, Ai.NORMAL);
                startGame();
                break;
            case M_HARD:
                view.game.setMode(Game.MODE_AI, Ai.HARD);
                startGame();
                break;
            case M_CREATE:
                showWait("Oda kuruluyor…", false, "");
                online.createRoom();
                break;
            case M_JOIN:
                askRoomCode();
                break;
            case M_FIND:
                showWait("Rakip aranıyor…", false, "");
                online.findMatch();
                break;
            case M_PROFILE:
                showProfile();
                break;
            case M_FRIENDS:
                showFriends();
                break;
            case M_THEME:
                showThemes();
                break;
            case M_SOUND:
                sfx.setEnabled(!sfx.isEnabled());
                prefs.edit().putString("sound", sfx.isEnabled() ? "1" : "0").apply();
                showPage(PG_SETTINGS);
                break;
            case M_RULES:
                onRules();
                break;
            case M_LEAVE:
                view.game.exitOnline();
                showPage(PG_MAIN);
                break;
            case M_EMOTE:
                showEmotes();
                break;
            case M_ADDOPP:
                int res = profile.addFriend(view.game.oppCode, view.game.oppName);
                Toast.makeText(this, res == Profile.FRIEND_OK ? "Arkadaş eklendi" : "Eklenemedi",
                        Toast.LENGTH_SHORT).show();
                showPage(PG_MAIN);
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------
    // Hesap: giriş ve bulut eşitleme
    // ------------------------------------------------------------------

    private void toastUi(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void startLogin(final String prov) {
        if (loginCancel != null) {
            loginCancel.cancelled = true;
        }
        final Cloud.Cancel cancel = new Cloud.Cancel();
        loginCancel = cancel;
        Toast.makeText(this, "Bağlanılıyor…", Toast.LENGTH_SHORT).show();
        net.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Cloud.DeviceCode dc = cloud.beginLogin(prov);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showLoginCode(dc, cancel);
                        }
                    });
                    cloud.finishLogin(dc, cancel);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            dismissLogin();
                        }
                    });
                    boolean idChanged = cloud.sync(profile);
                    final boolean ch = idChanged;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (ch) {
                                rebuildOnline();
                            }
                            Toast.makeText(MainActivity.this, "Giriş yapıldı: " + cloud.displayName
                                    + (ch ? " (oyuncu kodun hesabına göre güncellendi)" : ""), Toast.LENGTH_LONG).show();
                            if (view.isMenuOpen()) {
                                showPage(PG_ACCOUNT);
                            }
                        }
                    });
                } catch (final java.io.IOException e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            dismissLogin();
                            if (!cancel.cancelled) {
                                builder().setTitle("Giriş yapılamadı").setMessage(String.valueOf(e.getMessage()))
                                        .setPositiveButton("Tamam", null).show();
                            }
                            if (view.isMenuOpen()) {
                                showPage(PG_ACCOUNT);
                            }
                        }
                    });
                }
            }
        });
    }

    private void showLoginCode(final Cloud.DeviceCode dc, final Cloud.Cancel cancel) {
        dismissLogin();
        String name = Cloud.GOOGLE.equals(dc.provider) ? "Google" : "Facebook";
        loginDialog = builder().setTitle(name + " ile giriş")
                .setMessage("1) Telefonunda ya da bilgisayarda şu adresi aç:\n" + dc.url
                        + "\n\n2) Şu kodu gir:\n\n      " + dc.userCode
                        + "\n\n3) Hesabını onayla. Bu ekran kendiliğinden kapanır.")
                .setCancelable(false)
                .setPositiveButton("Siteyi aç", null)
                .setNegativeButton("İptal", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        cancel.cancelled = true;
                    }
                }).show();
        loginDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(dc.url)));
                } catch (RuntimeException e) {
                    Toast.makeText(MainActivity.this, "Tarayıcı açılamadı; adresi elle gir.", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void dismissLogin() {
        if (loginDialog != null) {
            try {
                loginDialog.dismiss();
            } catch (RuntimeException e) {
                // pencere kapanmış olabilir
            }
            loginDialog = null;
        }
    }

    private void rebuildOnline() {
        if (view.game.mode == Game.MODE_ONLINE) {
            return;
        }
        online.shutdown();
        online = new Online(profile.id, profile.name, view.game.onlineListener(), Mqtt.PUBLIC_SERVERS);
        view.game.setOnline(online);
        online.start();
    }

    /** Arka planda eşitler. manual=true ise sonucu kullanıcıya bildirir. */
    private void syncNow(final boolean manual) {
        if (!cloud.loggedIn()) {
            return;
        }
        net.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final boolean ch = cloud.sync(profile);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (ch) {
                                rebuildOnline();
                            }
                            if (manual) {
                                Toast.makeText(MainActivity.this, "Eşitlendi", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                } catch (java.io.IOException e) {
                    if (manual) {
                        toastUi("Eşitlenemedi: " + e.getMessage());
                    }
                }
            }
        });
    }

    private void uploadQuiet() {
        if (!cloud.loggedIn()) {
            return;
        }
        net.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    cloud.upload(profile);
                } catch (java.io.IOException e) {
                    // çevrimdışı olabilir; bir sonraki eşitlemede yüklenir
                }
            }
        });
    }

    /** Arkadaş eklenince herkese açık karttan gerçek adını ve seviyesini getirir. */
    private void lookupFriend(final String code) {
        if (!cloud.config().firebaseReady()) {
            return;
        }
        net.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Map<String, Object> m = cloud.lookup(code);
                    if (m != null) {
                        final String n = Json.str(m, "name", "");
                        if (!n.isEmpty()) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    profile.renameFriend(code, n);
                                }
                            });
                        }
                    }
                } catch (java.io.IOException e) {
                    // bulunamadıysa "Arkadaş" adıyla kalır
                }
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (view.isMenuOpen()) {
            if (menuPage != PG_MAIN) {
                showPage(PG_MAIN);
            } else if (gameStarted) {
                view.hideMenu();
            } else {
                super.onBackPressed();
            }
        } else {
            showPage(PG_MAIN);
        }
    }

    // ------------------------------------------------------------------
    // Profil, arkadaşlar, tema, hızlı mesaj
    // ------------------------------------------------------------------

    private void share(String title, String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(i, title));
    }

    private void showProfile() {
        StringBuilder sb = new StringBuilder();
        int lv = profile.level();
        sb.append(profile.name).append("  •  ").append(profile.rankTitle()).append("\n");
        sb.append("Oyuncu kodu: ").append(profile.displayId()).append("\n");
        sb.append("Seviye ").append(lv).append("   (").append(profile.xp).append(" / ")
                .append(Profile.xpForLevel(lv + 1)).append(" puan)\n\n");
        sb.append("Oyun: ").append(profile.games).append("   Galibiyet: ").append(profile.wins)
                .append("   Mağlubiyet: ").append(profile.losses).append("   Beraberlik: ").append(profile.ties).append("\n");
        sb.append("Mars: ").append(profile.mars).append("   Hapis: ").append(profile.pins)
                .append("   En iyi seri: ").append(profile.bestStreak).append("\n");
        sb.append("Çevrimiçi: ").append(profile.onlineWins).append(" / ").append(profile.onlineGames).append("\n");
        sb.append("Günlük seri: ").append(profile.dailyStreak).append(" gün\n\n");
        sb.append("Başarımlar (").append(profile.achCount()).append("/").append(Profile.ACH_NAMES.length).append(")\n");
        for (int i = 0; i < Profile.ACH_NAMES.length; i++) {
            sb.append(profile.hasAch(i) ? "✔ " : "○ ").append(Profile.ACH_NAMES[i])
                    .append(" – ").append(Profile.ACH_DESC[i]).append("\n");
        }
        builder().setTitle("Profilim").setMessage(sb.toString())
                .setPositiveButton("Tamam", null)
                .setNegativeButton("Adı değiştir", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        askName();
                    }
                })
                .setNeutralButton("Kodumu paylaş", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        share("Kodumu paylaş", "Hapis Tavla'da arkadaşım ol! Oyuncu kodum: " + profile.displayId()
                                + "  (Uygulamada Menü > Arkadaşlar > Arkadaş ekle)");
                    }
                }).show();
    }

    private void askName() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("Yeni adın (en fazla 14 harf)");
        builder().setTitle("Adını değiştir").setView(input)
                .setPositiveButton("Kaydet", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        profile.setName(input.getText().toString());
                        online.setName(profile.name);
                        Toast.makeText(MainActivity.this, "Adın: " + profile.name, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Vazgeç", null).show();
    }

    private void showFriends() {
        final List<String> labels = new ArrayList<String>();
        labels.add("+ Arkadaş ekle (kodla)");
        labels.add("Kodumu paylaş (WhatsApp, Instagram…)");
        for (Profile.Friend f : profile.friends) {
            labels.add(f.name + "  (" + Profile.formatId(f.id) + ")");
        }
        builder().setTitle("Arkadaşlar").setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                if (which == 0) {
                    askFriendCode();
                } else if (which == 1) {
                    share("Kodumu paylaş", "Hapis Tavla'da arkadaşım ol! Oyuncu kodum: " + profile.displayId()
                            + "  (Uygulamada Menü > Arkadaşlar > Arkadaş ekle)");
                } else if (which - 2 < profile.friends.size()) {
                    friendActions(profile.friends.get(which - 2));
                }
            }
        }).setPositiveButton("Kapat", null).show();
    }

    private void askFriendCode() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setHint("Arkadaşının kodu (8 harf/rakam)");
        builder().setTitle("Arkadaş ekle").setView(input)
                .setPositiveButton("Ekle", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        int res = profile.addFriend(input.getText().toString(), "Arkadaş");
                        String msg;
                        switch (res) {
                            case Profile.FRIEND_OK:
                                msg = "Arkadaş eklendi";
                                break;
                            case Profile.FRIEND_SELF:
                                msg = "Bu senin kodun";
                                break;
                            case Profile.FRIEND_DUPLICATE:
                                msg = "Zaten arkadaşsınız";
                                break;
                            case Profile.FRIEND_LIMIT:
                                msg = "Arkadaş listesi dolu";
                                break;
                            default:
                                msg = "Kod geçersiz (8 harf/rakam, 0-9 ve A-F)";
                                break;
                        }
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                        if (res == Profile.FRIEND_OK) {
                            lookupFriend(Profile.normalizeId(input.getText().toString()));
                            showFriends();
                        }
                    }
                })
                .setNegativeButton("Vazgeç", null).show();
    }

    private void friendActions(final Profile.Friend f) {
        String[] acts = {"Oyuna davet et", "Yeniden adlandır", "Sil"};
        builder().setTitle(f.name).setItems(acts, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                if (which == 0) {
                    if (view.game.mode == Game.MODE_ONLINE) {
                        Toast.makeText(MainActivity.this, "Önce çevrimiçi oyundan çık", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    showWait("Davet gönderiliyor…\n(" + f.name + " şu an uygulamayı açıksa davet ona ulaşır.)", false, "");
                    online.inviteFriend(f.id);
                } else if (which == 1) {
                    askRename(f);
                } else {
                    profile.removeFriend(f.id);
                    showFriends();
                }
            }
        }).show();
    }

    private void askRename(final Profile.Friend f) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("Yeni ad");
        builder().setTitle("Yeniden adlandır").setView(input)
                .setPositiveButton("Kaydet", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        profile.renameFriend(f.id, input.getText().toString());
                        showFriends();
                    }
                })
                .setNegativeButton("Vazgeç", null).show();
    }

    private void showThemes() {
        final int lv = profile.level();
        String[] names = new String[Gfx.THEMES.length];
        for (int i = 0; i < names.length; i++) {
            Gfx.Theme t = Gfx.THEMES[i];
            names[i] = t.name + (lv >= t.unlockLevel ? (view.getTheme() == i ? "  ✔" : "")
                    : "  (Seviye " + t.unlockLevel + " gerekir)");
        }
        builder().setTitle("Tahta teması").setItems(names, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                if (which >= 0 && which < Gfx.THEMES.length) {
                    if (lv >= Gfx.THEMES[which].unlockLevel) {
                        view.setTheme(which);
                        prefs.edit().putString("theme", String.valueOf(which)).apply();
                        if (view.isMenuOpen() && menuPage == PG_SETTINGS) {
                            showPage(PG_SETTINGS);
                        }
                    } else {
                        Toast.makeText(MainActivity.this, "Seviye " + Gfx.THEMES[which].unlockLevel
                                + " olunca açılır", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        }).show();
    }

    private void showEmotes() {
        builder().setTitle("Hızlı mesaj").setItems(Game.EMOTES, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                view.game.sendEmote(which);
            }
        }).show();
    }

    private void askRoomCode() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setHint("Oda kodu (5 harf/rakam)");
        builder().setTitle("Odaya katıl").setView(input)
                .setPositiveButton("Katıl", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String code = input.getText().toString().trim();
                        if (code.length() < 4) {
                            Toast.makeText(MainActivity.this, "Kod çok kısa", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        showWait("Odaya katılınıyor…", false, "");
                        online.joinRoom(code);
                    }
                })
                .setNegativeButton("Vazgeç", null).show();
    }

    // ------------------------------------------------------------------
    // Çevrimiçi olaylar
    // ------------------------------------------------------------------

    private void dismissWait() {
        if (waitDialog != null) {
            try {
                waitDialog.dismiss();
            } catch (RuntimeException e) {
                // pencere kapanmış olabilir
            }
            waitDialog = null;
        }
    }

    private void showWait(String msg, boolean share, final String code) {
        dismissWait();
        AlertDialog.Builder b = builder().setTitle("Çevrimiçi").setMessage(msg).setCancelable(false);
        b.setNegativeButton("İptal", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                online.cancel();
            }
        });
        if (share) {
            b.setPositiveButton("Kodu paylaş", null);
        }
        waitDialog = b.show();
        if (share) {
            // "Paylaş" düğmesi pencereyi kapatmasın
            waitDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, "Hapis Tavla'da seninle oynamak istiyorum! Oda kodu: " + code
                            + "  (Uygulamada Menü > Çevrimiçi: Odaya katıl)");
                    startActivity(Intent.createChooser(i, "Kodu paylaş"));
                }
            });
        }
    }

    @Override
    public void onOnlineEvent(int type, String a, String b) {
        switch (type) {
            case Game.EV_ROOM:
                showWait("Oda kodun: " + a + "\n\nArkadaşına bu kodu ver; o Menü > \"Odaya katıl\" ile girsin.\n"
                        + "Rakip bekleniyor…", true, a);
                break;
            case Game.EV_STATUS:
                if (waitDialog != null && a != null && a.length() > 0 && !a.startsWith("Oda kodun")) {
                    waitDialog.setMessage(a);
                }
                break;
            case Game.EV_MATCHED:
                dismissWait();
                gameStarted = true;
                view.hideMenu();
                Toast.makeText(this, "Oyun başladı! Rakip: " + a, Toast.LENGTH_LONG).show();
                break;
            case Game.EV_ERROR:
                dismissWait();
                builder().setTitle("Çevrimiçi").setMessage(a).setPositiveButton("Tamam", null).show();
                break;
            case Game.EV_LEFT:
                builder().setTitle("Çevrimiçi").setMessage(a).setCancelable(false)
                        .setPositiveButton("Tamam", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                view.game.exitOnline();
                                showPage(PG_MAIN);
                            }
                        }).show();
                break;
            case Game.EV_INVITE:
                showInvite(a, b);
                break;
            case Game.EV_EMOTE:
                Toast.makeText(this, b + ": " + a, Toast.LENGTH_SHORT).show();
                break;
            default:
                break;
        }
    }

    private void showInvite(String codeAndName, final String room) {
        if (view.game.mode == Game.MODE_ONLINE || waitDialog != null) {
            return;
        }
        String[] parts = codeAndName.split("\\|", 2);
        String name = parts.length > 1 ? parts[1] : "Bir arkadaşın";
        builder().setTitle("Oyun daveti").setMessage(name + " seni Hapis Tavla oynamaya davet ediyor.")
                .setPositiveButton("Katıl", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        showWait("Odaya katılınıyor…", false, "");
                        online.joinRoom(room);
                    }
                })
                .setNegativeButton("Reddet", null).show();
    }

    @Override
    public String onGameOver(Game.Result r) {
        if (r.mode == Game.MODE_TWO) {
            return "";
        }
        profile.addPins(r.pins);
        String big = r.maxCombo >= 10 ? profile.bigMove(r.maxCombo) : null;
        boolean online = r.mode == Game.MODE_ONLINE;
        Profile.Reward rw = profile.recordGame(r.iWon, r.tie, r.points, online, r.iWon && r.mother);
        String t = rw.text();
        uploadQuiet();
        if (big != null) {
            t += "\nYeni başarım: " + big;
        }
        return t;
    }

    // ------------------------------------------------------------------
    // Yaşam döngüsü
    // ------------------------------------------------------------------

    @Override
    protected void onPause() {
        if (adView != null) {
            adView.pause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adView != null) {
            adView.resume();
        }
    }

    @Override
    protected void onDestroy() {
        if (online != null) {
            online.shutdown();
        }
        if (sfx != null) {
            sfx.release();
        }
        if (loginCancel != null) {
            loginCancel.cancelled = true;
        }
        net.shutdown();
        if (adView != null) {
            adView.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }
}
