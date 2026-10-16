# Ulaş Terminal (Android)

Nordic ID el terminalleri (HH83, HH85, EXA) için uygulama. Ulaş Grup sisteminin terminal ekranlarını
(https://sistem.ulasgrup.com.tr/terminal) tam ekran açar ve cihazın okuyucusunu NUR API ile doğrudan kullanır:

- **Tetik tuşu → barkod:** 2D okuyucu tetikle çalışır, okunan kod açık ekrandaki tarama kutusuna düşer
  (cihazın okuma servisini elle açmak gerekmez).
- **RFID:** Ekranda RFID modu seçilince tetik basılı tutulduğu sürece etiketler okunur; aynı etiket bir kez gönderilir.
- **Kamera, ses, titreşim:** Cihazın kendi bip ve titreşimi kullanılır; kamerayla okuma da çalışır.

## İlk kurulum (cihazda)
1. APK'yı cihaza kopyalayıp açın (bilinmeyen kaynaklardan kuruluma izin verin).
2. İlk açılışta okuyucu seçme ekranı çıkar: HH85'te **Integrated reader** / tümleşik okuyucuyu seçin. Seçim hatırlanır.
3. term1 / term2 / term3 hesabıyla girin.

## Ekran modu (banko / depo TV kutusu)
TV kutusunda (Android TV ya da dokunmatiksiz cihaz) uygulama el terminali yerine **bilgi ekranı** olarak açılır:
eşleştirme kodu gösterir (`/ekran-eslestir`), kod panelde **Bilgi ekranları → TV'ye bağla** ile girilir, ekran adresi
cihaza kaydedilir. Ekran hep açık, tam ekran; cihaz açılınca kendiliğinden başlar (ana ekran olarak seçilebilir).
Kumandada Geri / Menü: Yenile, başka ekrana bağla, ekran modundan çık, Android ayarları.

## Derleme
GitHub Actions her gönderimde derler (`.github/workflows/apk.yml`). APK: Actions → son çalıştırma → **UlasTerminal**.
Elle: `./gradlew assembleRelease` (JDK 17, Android SDK 34).

## Sayfa ile köprü
- Cihaz → sayfa: `window` üzerinde `ulas-okuma` (`{kod, tur: 'barkod'|'rfid'}`) ve `ulas-cihaz` (okuyucu durumu) olayları.
- Sayfa → cihaz: `window.UlasCihaz.durum()`, `.okuyucuSec()`, `.mod('barkod'|'rfid')`, `.rfidTemizle()`, `.bip(ms)`, `.oku()`, `.durdur()`.

## İmza
`imza/` klasöründeki anahtar tüm sürümleri aynı imzayla derler (güncelleme eski sürümün üstüne kurulur).
Bu depo **özel** kalmalıdır.

## Lisans
Nordic ID NUR API kütüphaneleri MIT lisanslıdır (`third_party/NordicID-LICENSE.txt`).
