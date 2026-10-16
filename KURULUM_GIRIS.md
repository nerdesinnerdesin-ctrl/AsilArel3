# Google / Facebook ile giriş ve bulut kaydı: kurulum rehberi

Bu kurulum **tek seferliktir** ve **senin hesaplarınla** yapılır (oyunu yayınlayan kişi sensin). Hiçbir şey kurmadan, tarayıcıdan yapılır. İşin sonunda 6 değeri `cloud.xml` dosyasına yapıştıracaksın. **Hiçbirini doldurmazsan oyun misafir olarak çalışmaya devam eder**; bozulmaz.

Ne elde edeceksin: oyuncular Google/Facebook ile girer, seviyeleri/istatistikleri/arkadaşları buluta kaydolur, telefon değişse bile geri gelir.

İpucu: Önce yalnızca **Google**'ı kur (A ve B bölümleri). Facebook (C) daha zordur, istersen sonra yaparsın.

---

## A) Firebase projesi (ilerlemeyi saklayan yer)

1. https://console.firebase.google.com adresine Google hesabınla gir. **Proje ekle** de, ad: `hapis-tavla`. Analytics'i kapatabilirsin. **Projeyi oluştur**.
2. Sol menü **Derleme (Build) > Authentication > Başlayın**. **Oturum açma yöntemi** sekmesinde **Google**'ı seç, **Etkinleştir**, destek e-postası olarak kendi adresini seç, **Kaydet**.
3. Sol menü **Derleme > Realtime Database > Veritabanı oluştur**. Konum olarak sana yakın olanı seç, **Kilitli modda başlat** de.
4. Veritabanı sayfasında **Kurallar** sekmesine gir. İçindekileri silip aşağıdakini yapıştır ve **Yayınla**'ya bas:

```json
{
  "rules": {
    "users": {
      "$uid": {
        ".read": "auth != null && auth.uid === $uid",
        ".write": "auth != null && auth.uid === $uid"
      }
    },
    "public": {
      ".read": true,
      "$code": {
        ".write": "auth != null && newData.child('uid').val() === auth.uid && (!data.exists() || data.child('uid').val() === auth.uid)",
        ".validate": "newData.hasChildren(['uid', 'name'])"
      }
    }
  }
}
```

Bu kurallar şunu sağlar: herkes yalnızca **kendi** ilerlemesini okuyup yazabilir; arkadaş eklerken görünen ad ve seviye kartı herkese açıktır.

5. Aynı sayfanın üstündeki **Veri** sekmesinde adres yazar (örn. `https://hapis-tavla-default-rtdb.firebaseio.com`). Bunu kopyala → **`firebase_database_url`** değeri.
6. Sol üstte dişli simgesi **Proje ayarları > Genel** sayfasında **Web API Anahtarı**'nı kopyala → **`firebase_api_key`** değeri.

## B) Google girişi

1. https://console.cloud.google.com adresine aynı hesapla gir. Üstteki proje seçiciden **hapis-tavla** projesini seç.
2. **APIs & Services > OAuth consent screen** (OAuth izin ekranı). Kullanıcı türü **External**. Uygulama adı `Hapis Tavla`, destek e-postası kendi adresin. Kaydet ve ilerle. Kapsamları (scopes) boş bırakabilirsin. En sonda **Publish app** (Uygulamayı yayınla) de; yayınlamazsan sadece listelediğin "test kullanıcıları" girebilir.
3. **APIs & Services > Credentials > Create credentials > OAuth client ID**. **Application type: "TVs and Limited Input devices"**. Ad `Hapis Tavla`. **Create**.
4. Açılan pencerede **Client ID** → **`google_client_id`**, **Client secret** → **`google_client_secret`** değeridir.
   (Bu tür uygulamalarda secret'ın uygulamanın içinde bulunması normaldir; Google böyle tasarlamıştır. Verilerini asıl koruyan, A bölümündeki kurallardır.)
5. Firebase'e dön: **Authentication > Oturum açma yöntemi > Google** satırını aç. **Web SDK yapılandırması / "Harici projelerden istemci kimliklerine izin ver (Whitelist client IDs)"** alanına 4. adımdaki **Client ID**'yi ekle ve kaydet.

## C) Facebook girişi (isteğe bağlı, daha uzun)

1. https://developers.facebook.com adresinde hesabınla gir, **Uygulamalarım > Uygulama oluştur**. Kullanım amacı olarak Facebook Login / "Kimlik doğrulama" seç, ad `Hapis Tavla`.
2. **Uygulama ayarları > Temel**: **Uygulama kimliği** → **`facebook_app_id`**. **Uygulama gizli anahtarı**'nı (Göster) bir kenara kopyala (Firebase'de lazım). Aynı sayfada gizlilik politikası adresi iste: kısa bir sayfa yapıp bağlantısını gir (ör. ücretsiz bir site ya da GitHub Pages).
3. **Ayarlar > Gelişmiş > İstemci belirteci (Client token)** → **`facebook_client_token`**.
4. Ürün ekle: **Facebook Login**. Onun **Ayarlar** sayfasında **"Cihazlardan giriş (Login from Devices)"** seçeneğini **Evet** yap.
5. Firebase **Authentication > Oturum açma yöntemi > Facebook**: Etkinleştir, Uygulama kimliği ve gizli anahtarı yapıştır. Orada görünen **OAuth yönlendirme URI**'sini kopyala ve Facebook Login ayarlarındaki **Geçerli OAuth yönlendirme URI'leri** alanına yapıştır.
6. Uygulamayı **Canlı (Live)** moda al (üst çubuktaki anahtar); aksi halde yalnızca senin eklediğin test kullanıcıları girebilir.

## D) Değerleri oyuna ver

GitHub'da depona gir, `app/src/main/res/values/cloud.xml` dosyasını aç, **kalem (Edit)** simgesine bas ve tırnaklar arasına değerleri yapıştır:

```xml
<string name="firebase_api_key" translatable="false">BURAYA</string>
<string name="firebase_database_url" translatable="false">BURAYA</string>
<string name="google_client_id" translatable="false">BURAYA</string>
<string name="google_client_secret" translatable="false">BURAYA</string>
<string name="facebook_app_id" translatable="false">BURAYA</string>
<string name="facebook_client_token" translatable="false">BURAYA</string>
```

**Commit changes** de. Actions yeni APK'yı kendiliğinden derler.

Oyunda **Menü > Giriş yap / Hesap**: Google ya da Facebook'u seçince ekranda bir kod ve adres çıkar. Telefondan (ya da bilgisayardan) adresi açıp kodu girer, onaylarsın. Oyun kendiliğinden bağlanır.

## Notlar

- Facebook ayarlarını boş bırakırsan yalnızca "Google ile gir" görünür; Google'ı boş bırakırsan yalnızca Facebook görünür; ikisi de boşsa hesap sayfası "henüz kurulmadı" der.
- **Instagram:** Instagram, uygulamalara giriş yöntemi sunmuyor. Bunun yerine hesap sayfasındaki **Arkadaşlarını davet et** düğmesi, oyuncu kodunu Instagram DM'i, WhatsApp vb. ile paylaştırır.
- Gizlilik: buluta yalnızca oyun ilerlemesi (ad, seviye, istatistik, arkadaş kodları) kaydolur. Yayına çıkarsan bir gizlilik politikası sayfası hazırlamalısın.
- Oyuncu başka telefonda giriş yapınca ilerlemeler **birleşir** (hiçbir şey silinmez) ve oyuncu kodu hesabındaki koda döner.
