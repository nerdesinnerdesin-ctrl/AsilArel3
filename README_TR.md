# Hapis Tavla (Android)

Hapis tavla (Mahbus / Plakoto) oyunu. Bilgisayara karşı ya da aynı telefonda iki kişi oynanır. Arayüz Türkçedir. Oyun yatay (landscape) çalışır, en altta AdMob banner reklamı bulunur.

## APK'yı almanın en kolay yolu (GitHub, bilgisayara hiçbir şey kurmadan)

1. github.com'da ücretsiz hesap aç, **New repository** ile boş bir depo oluştur.
2. Zip'i bilgisayarda **Klasöre çıkar**. Klasörün **içindeki her şeyi** (`.github`, `app`, `gradle` klasörleri ve dosyalar) depoya yükle: **Add file > Upload files**, sürükle-bırak, **Commit changes**.
   - Klasörler yüklenmezse: Windows'ta `.github` ve `app` klasörlerini tek tek sürükle ya da zip'teki dosyaları GitHub'da **Create new file** ile yol yazarak ekle.
   - Depoda daha önce boş bir `.github/workflows/main.yml` varsa: dosyayı aç, sağ üstteki **kalem (Edit)** simgesine bas, içindekileri sil, zip'teki `.github/workflows/main.yml` içeriğini yapıştır ve **Commit changes** de.
3. **Actions** sekmesine gir. "APK derle" çalışır (3-6 dakika). Başlamazsa soldan "APK derle" > **Run workflow**.
4. Yeşil tikten sonra çalışmaya tıkla, en alttaki **Artifacts** bölümünden `hapis-tavla-apk` dosyasını indir. İçinden `app-debug.apk` çıkar.
5. APK'yı telefona aktar ve aç (bilinmeyen kaynaklardan yükleme iznini ver).

## Çevrimiçi oynama

Menü > **Çevrimiçi: oda kur** bir oda kodu verir; arkadaşın **Odaya katıl** ile kodu girer. **Rakip bul** ise sistem eşleştirmesidir. Sunucu kurmak gerekmez: oyun, herkese açık ücretsiz MQTT sunucularının (HiveMQ, EMQX, Mosquitto) hepsine birden bağlanıp mesajları bunlar üzerinden iletir (şifreli bağlantı denenir). Mesajlar yalnızca oyun hamlelerini içerir; ancak bu ortak sunucular güvence altında değildir, ciddi bir yayın için kendi sunucunuza geçmeniz önerilir.

## Profil, arkadaşlar ve diğer özellikler

- **Açılış menüsü:** uygulama menü ekranıyla açılır; oyun modunu seçince tahta gelir. Oyundaki MENÜ düğmesi aynı ekrana döner.
- **Profil:** puan/seviye (Çırak → Efsane), istatistikler, 10 başarım, günlük bonus (üst üste girdikçe artar). Yalnızca bilgisayara ve çevrimiçi oyunlar sayılır. Veriler telefonda saklanır.
- **Arkadaşlar:** Menü > Arkadaşlar. Herkesin 8 haneli bir oyuncu kodu vardır; kodu WhatsApp, Instagram vb. ile paylaşabilirsin. Arkadaşını oyuna davet edebilirsin (davet, uygulaması açık olan arkadaşa ulaşır). Çevrimiçi maçtan sonra rakibi tek dokunuşla ekleyebilirsin.
- **Hızlı mesajlar** (çevrimiçi oyunda), **ses efektleri** (menüden kapatılabilir) ve **3 tahta teması** (Ceviz, Abanoz: seviye 5, Zümrüt: seviye 10).
- **Google/Facebook ile giriş ve bulutta kayıt:** `KURULUM_GIRIS.md` dosyasındaki tek seferlik kurulumdan sonra çalışır. Kurulum yapılmazsa oyun misafir olarak çalışır. Instagram giriş sunmaz; onun yerine oyuncu kodunu paylaşma düğmesi vardır.

## Oyun kuralları (özet)

- Her oyuncunun 15 taşı vardır; taş vurulmaz, tek taşın üstüne basınca o taş **hapsolur**.
- İlk elde her oyuncu en az 2 farklı taş oynar.
- Sonraki ellerde bir taşla iki zarın toplamı tek dokunuşla oynanabilir (ör. 11).

## Android Studio ile derlemek

1. Android Studio'yu kur, **Open** ile bu klasörü aç, Gradle eşitlemesinin bitmesini bekle.
2. Telefonu USB hata ayıklama açıkken bağlayıp **Run** de ya da **Build > Build APK(s)** ile APK üret
   (`app/build/outputs/apk/debug/app-debug.apk`).

## AdMob reklam kimlikleri

Proje şu an Google'ın **test kimlikleriyle** çalışır; test reklamları görünür, gelir olmaz.
Gerçek kimliklere geçmek için `app/src/main/res/values/strings.xml` dosyasında iki değeri değiştir:

- `admob_app_id`: AdMob'daki **uygulama** kimliği (`ca-app-pub-XXXX~YYYY`)
- `admob_banner_unit_id`: AdMob'daki **banner reklam birimi** kimliği (`ca-app-pub-XXXX/ZZZZ`)

Geliştirirken gerçek kimlikle kendi reklamına tıklama; hesabın askıya alınabilir. Yayına çıkmadan önce AB/Birleşik Krallık kullanıcıları için onay (UMP/consent) ekranı ve Play Console'daki reklam kimliği beyanı gerekebilir.

## Dosyalar

- `app/src/main/java/com/hapistavla/oyun/Board.java`: oyunun kuralları (Android'den bağımsız)
- `Ai.java`: bilgisayar rakibi
- `Game.java`: oyun akışı; `Online.java`/`Mqtt.java`: çevrimiçi oyun
- `Cloud.java`/`Json.java`: hesap girişi ve bulut eşitleme
- `Profile.java`: profil/arkadaşlar; `Sfx.java`: ses efektleri
- `Gfx.java`: ahşap tahta ve taş çizimi; `TahtaView.java`: ekran ve dokunma
- `MainActivity.java`: ekran, AdMob banner'ı
