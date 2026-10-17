package com.ulasgrup.terminal;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.nordicid.nurapi.AccBarcodeResult;
import com.nordicid.nurapi.AccBarcodeResultListener;
import com.nordicid.nurapi.AccessoryExtension;
import com.nordicid.nurapi.NurApi;
import com.nordicid.nurapi.NurApiAutoConnectTransport;
import com.nordicid.nurapi.NurApiErrors;
import com.nordicid.nurapi.NurApiListener;
import com.nordicid.nurapi.NurDeviceListActivity;
import com.nordicid.nurapi.NurDeviceSpec;
import com.nordicid.nurapi.NurEventAutotune;
import com.nordicid.nurapi.NurEventClientInfo;
import com.nordicid.nurapi.NurEventDeviceInfo;
import com.nordicid.nurapi.NurEventEpcEnum;
import com.nordicid.nurapi.NurEventFrequencyHop;
import com.nordicid.nurapi.NurEventIOChange;
import com.nordicid.nurapi.NurEventInventory;
import com.nordicid.nurapi.NurEventNxpAlarm;
import com.nordicid.nurapi.NurEventProgrammingProgress;
import com.nordicid.nurapi.NurEventTagTrackingChange;
import com.nordicid.nurapi.NurEventTagTrackingData;
import com.nordicid.nurapi.NurEventTraceTag;
import com.nordicid.nurapi.NurEventTriggeredRead;
import com.nordicid.nurapi.NurInventoryExtended;
import com.nordicid.nurapi.NurInventoryExtendedFilter;
import com.nordicid.nurapi.NurTag;
import com.nordicid.nurapi.NurTagStorage;

import java.util.HashSet;
import java.util.Set;

/**
 * Nordic ID okuyucusu (HH83/HH85 tümleşik ya da EXA). NUR API ile bağlanır; tetik tuşu basılınca
 * barkod modunda 2D okuyucuyu, RFID modunda envanter akışını çalıştırır. Okunan değerler Dinleyici'ye gider.
 * Okuyucu seçimi bir kez yapılır ve hatırlanır (cihaz servisini elle açmak gerekmez).
 */
public class NurOkuyucu {
    private static final String TAG = "UlasOkuyucu";
    private static final String TERCIH = "ulas_okuyucu";
    private static final int TETIK = 100;

    public interface Dinleyici {
        void okundu(String kod, String tur, int rssi);
        void durumDegisti();
        void yazmaSonucu(boolean ok, String epc, String eski, String hata);
        void bulSinyal(String epc, int rssi);
        /** Uygulama arka plandayken tetiğe basıldı: ekran açılıp uygulama öne gelmeli */
        void tetikArkaplanda();
    }

    private final Context ctx;
    private final Dinleyici dinleyici;
    private final NurApi api = new NurApi();
    private final AccessoryExtension acc;
    private NurApiAutoConnectTransport baglanti;
    private volatile boolean bagli = false;
    private volatile boolean barkodVar = false;
    private volatile String cihazAdi = "";
    private volatile String mod = "barkod"; // barkod | rfid
    private volatile boolean tetikBasili = false;
    private volatile boolean barkodOkunuyor = false;
    private volatile int rfidSayac = 0;
    /** Etikete yazılırken tetik / ikinci yazma başlamasın */
    private volatile boolean yaziliyor = false;
    private volatile boolean titresimVar = false;
    /** Teşhis: okuyucu seri no / yazılım sürümü / bölge */
    private volatile String okuyucuBilgi = "";
    public String getOkuyucuBilgi() { return okuyucuBilgi; }
    private volatile String okuyucuSeri = "", okuyucuYazilim = "", bolgeAd = "";
    private volatile int bolgeId = -1;
    public String getOkuyucuSeri() { return okuyucuSeri; }
    public String getOkuyucuYazilim() { return okuyucuYazilim; }
    public String getBolgeAd() { return bolgeAd; }
    public int getBolgeId() { return bolgeId; }
    private volatile int pilYuzde = -1;
    private volatile long baslangic = 0;
    private volatile boolean pilSarj = false;
    private final Set<String> rfidGorulen = java.util.Collections.synchronizedSet(new HashSet<>());
    /** Etiket bul: aranan ürünün EPC öneki (sistemin yazdığı etiketler) ve bağlı etiketler */
    private volatile String bulOnek = null;
    private volatile Set<String> bulEpcler = new HashSet<>();
    private volatile String bulOncekiMod = null;
    private volatile boolean arkaplan = false;
    private volatile boolean arkadaTutuldu = false;

    /** Cihaz ayarları: okuyucunun kendi bipi ve titreşimi de "Ses" / "Titreşim" ayarına uyar */
    private boolean sesAcik() { return tercihler().getBoolean("ses", true); }
    private boolean titresimAcik() { return tercihler().getBoolean("titresim", true); }
    public void setGeriBildirim(boolean ses, boolean titresim) { tercihler().edit().putBoolean("ses", ses).putBoolean("titresim", titresim).apply(); }
    private void sesVer(int ms) { try { if (sesAcik()) acc.beepAsync(ms); } catch (Exception ignored) { } }
    private void titret(int ms) { try { if (titresimAcik() && titresimVar) acc.vibrate(ms); } catch (Exception ignored) { } }
    /** Sayfa okutma ekranında mı (ürün bul, sayım, toplama…): yalnız o zaman arka planda tetik beklenir */
    private volatile boolean okutmaEkrani = false;

    public void setOkutmaEkrani(boolean a) { okutmaEkrani = a; }
    /** Arka planda tetik beklenecek mi */
    public boolean arkadaBekle() { return isUyandir() && okutmaEkrani && bagli; }

    /** Uzun süre (30 dk) tetiğe basılmadı: arka plandaki bağlantı bırakılır */
    public void arkaplaniBirak() {
        arkaplan = false;
        arkadaTutuldu = false;
        try { if (baglanti != null) baglanti.onPause(); } catch (Exception ignored) { }
    }

    public void setArkaplan(boolean a) { arkaplan = a; }
    public boolean isUyandir() { return tercihler().getBoolean("uyandir", true); }
    public void setUyandir(boolean a) { tercihler().edit().putBoolean("uyandir", a).apply(); }

    public NurOkuyucu(Context ctx, Dinleyici dinleyici) {
        this.ctx = ctx.getApplicationContext();
        this.dinleyici = dinleyici;
        acc = new AccessoryExtension(api);
        try {
            api.setListener(olaylar);
            acc.registerBarcodeResultListener(barkodSonucu);
        } catch (Exception e) {
            Log.e(TAG, "dinleyici kurulamadı", e);
        }
    }

    /** Nordic ID el terminallerinin (HH83/HH85) dahili okuyucusu: seçim ekranı açmadan buna bağlanılır */
    private static final String DAHILI = "type=INT;addr=integrated_reader;name=Integrated Reader";

    public static boolean nordicCihaz() {
        String m = (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL).toUpperCase(java.util.Locale.ROOT);
        return m.contains("NORDIC") || m.matches(".*\\bHH\\d{2}.*");
    }

    /** Kayıtlı okuyucuya, yoksa Nordic cihazında dahili okuyucuya bağlanır; ikisi de yoksa false (ayarlardan seçilir) */
    /** Teşhis: son arka plan tetiği, bağlantı denemesi sayısı (sunucuya durum ile gider) */
    private volatile long sonArkaTetik = 0;
    private volatile int arkaTetikSayisi = 0;
    private volatile int yenidenDeneme = 0;
    public long getSonArkaTetik() { return sonArkaTetik; }
    public int getArkaTetikSayisi() { return arkaTetikSayisi; }
    public int getYenidenDeneme() { return yenidenDeneme; }
    public String getSpecTuru() { String s = tercihler().getString("spec", null); return s == null ? (nordicCihaz() ? "dahili" : "yok") : (s.equals(DAHILI) ? "dahili" : "secilen"); }

    /**
     * Bağlantı bekçisi (10 sn'de bir, ekran açıkken): bağlı değilse bağlantı yeniden kurulur. Kayıtlı (elle seçilmiş)
     * okuyucu iki kez tutmazsa Nordic cihazında dahili okuyucuya dönülür — "okuyucu seç" gerekmesin.
     */
    public void bekci() {
        if (bagli || !isSecildi()) { if (bagli) yenidenDeneme = 0; return; }
        yenidenDeneme++;
        if (yenidenDeneme >= 2 && nordicCihaz() && !DAHILI.equals(tercihler().getString("spec", DAHILI))) {
            Log.w(TAG, "kayıtlı okuyucu bağlanmıyor; dahili okuyucuya dönülüyor");
            tercihler().edit().remove("spec").apply();
        }
        birak();
        baslat();
        dinleyici.durumDegisti();
    }

    public boolean baslat() {
        String spec = tercihler().getString("spec", null);
        if (spec == null && nordicCihaz()) spec = DAHILI;
        if (spec == null) return false;
        baslangic = System.currentTimeMillis();
        try {
            NurDeviceSpec s = new NurDeviceSpec(spec);
            baglanti = NurDeviceSpec.createAutoConnectTransport(ctx, api, s);
            baglanti.setAddress(s.getAddress());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Bağlantı kurulamadı", e);
            return false;
        }
    }

    public void secimEkraniAc(Activity a) {
        try { NurDeviceListActivity.startDeviceRequest(a, api); } catch (Exception e) { Log.e(TAG, "seçim ekranı açılamadı", e); }
    }

    /** Seçim ekranından dönen sonuç: okuyucu kaydedilir ve bağlanılır */
    public boolean secimSonucu(int istek, int sonuc, Intent veri) {
        if (istek != NurDeviceListActivity.REQUEST_SELECT_DEVICE) return false;
        if (veri == null || sonuc != NurDeviceListActivity.RESULT_OK) return true;
        String spec = veri.getStringExtra(NurDeviceListActivity.SPECSTR);
        // Seçilen cihaz bilgisi hatırlanır; bir sonraki açılışta doğrudan bağlanılır
        if (spec != null) {
            tercihler().edit().putString("spec", spec).apply();
            birak();
            baslat();
        }
        return true;
    }

    /** Uzaktan komut: bağlantıyı bırakıp yeniden kurar (okuyucu takıldığında) */
    public void yenidenBaglan() {
        durdur();
        birak();
        bagli = false;
        dinleyici.durumDegisti();
        baslat();
    }

    public void unut() {
        tercihler().edit().remove("spec").apply();
        birak();
    }

    public void setMod(String m) {
        mod = "rfid".equals(m) ? "rfid" : "barkod";
        rfidGorulen.clear();
        dinleyici.durumDegisti();
    }

    public void rfidTemizle() {
        rfidGorulen.clear();
        try { api.clearIdBuffer(); } catch (Exception ignored) { }
        // Okuyucunun belleği de boşalır; yoksa sonraki okumada eski etiketler geri gelir
        try { NurTagStorage d = api.getStorage(); synchronized (d) { d.clear(); } } catch (Exception ignored) { }
        rfidSayac = 0;
    }

    /** RFID okuma gücü: yakin | orta | uzak (yakın: yalnız dibindeki etiketler; raf sayımında komşu rafları okumamak için) */
    public void setGuc(String g) {
        tercihler().edit().putString("guc", g).apply();
        gucUygula();
        dinleyici.durumDegisti();
    }

    public String getGuc() { return tercihler().getString("guc", "orta"); }

    /** Pil tasarrufu: RFID taraması yarı görev çevriminde (NUR AutoPeriod %50); okuma biraz yavaşlar, tüketim düşer */
    public void setTasarruf(boolean a) {
        tercihler().edit().putBoolean("tasarruf", a).apply();
        gucUygula();
    }

    private void gucUygula() {
        // NUR: 0 = en yüksek güç, her adım 1 dB düşürür (0..19)
        String g = getGuc();
        int seviye = "yakin".equals(g) ? 14 : "uzak".equals(g) ? 0 : 6;
        try { if (bagli) api.setSetupTxLevel(seviye); } catch (Exception e) { Log.w(TAG, "güç ayarlanamadı", e); }
        // AutoPeriod: 0 = kapalı, 3 = %50 (NUR_AUTOPER_50)
        try { if (bagli) api.setSetupAutoPeriod(tercihler().getBoolean("tasarruf", false) ? 3 : 0); } catch (Exception e) { Log.w(TAG, "tasarruf ayarlanamadı", e); }
    }

    /**
     * Ayırt edilebilir tonlar (sunucu cevabı / sayfa uyarısı): uyarı = iki kısa, hata = uzun. Okuyucu bağlıysa okuyucunun
     * hoparlöründen; değilse false döner, uygulama telefonun hoparlörünü kullanır. Ses / titreşim tercihine uyulur.
     */
    public boolean ton(String tur) {
        if (!bagli) return false;
        try {
            if ("hata".equals(tur)) { sesVer(400); titret(300); }
            else if ("uyari".equals(tur)) {
                sesVer(80); titret(80);
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> { try { sesVer(80); titret(80); } catch (Exception ignored) { } }, 180);
            } else { sesVer(40); }
        } catch (Exception ignored) { }
        return true;
    }

    public boolean sesTercihi() { return sesAcik(); }
    public boolean titresimTercihi() { return titresimAcik(); }

    /** Sunucunun cevabına göre geri bildirim: hatada uzun bip ve çift titreşim (okuma bipinden ayırt edilsin) */
    public void sonuc(boolean hata) {
        if (!bagli || !hata) return;
        try {
            sesVer(350);
            titret(250);
        } catch (Exception ignored) { }
    }

    /** Pil durumu (yüzde, şarjda mı); bağlantı yoksa -1 */
    public void pilOku() {
        try {
            if (bagli && acc.isSupported()) {
                com.nordicid.nurapi.AccBattery b = acc.getBatteryInfo();
                pilYuzde = b.percentage;
                pilSarj = b.charging;
            }
        } catch (Exception e) {
            pilYuzde = -1;
        }
    }

    public int getPilYuzde() { return pilYuzde; }
    public boolean isPilSarj() { return pilSarj; }

    /**
     * En yakındaki etikete yeni EPC yazar. Okuma alanında yakın birden fazla etiket varsa yazmaz (yanlış etikete yazılmasın).
     * Sonuç dinleyiciye gider (sayfada "ulas-rfid-yazma" olayı).
     */
    public void yaz(final String yeniEpc) {
        if (yaziliyor) { dinleyici.yazmaSonucu(false, yeniEpc, "", "Önceki yazma sürüyor"); return; }
        yaziliyor = true;
        new Thread(() -> {
            String eski = "";
            try {
                if (!bagli) throw new Exception("Okuyucu bağlı değil");
                durdur();
                byte[] yeni = NurApi.hexStringToByteArray(yeniEpc);
                api.clearIdBuffer();
                // Önceki okumalardan kalan etiketler seçilmesin: yalnız şu an alanda olanlar
                NurTagStorage onceki = api.getStorage();
                synchronized (onceki) { onceki.clear(); }
                api.inventory();
                api.fetchTags();
                NurTagStorage depo = api.getStorage();
                NurTag en = null, ikinci = null;
                synchronized (depo) {
                    for (int i = 0; i < depo.size(); i++) {
                        NurTag t = depo.get(i);
                        if (en == null || t.getRssi() > en.getRssi()) { ikinci = en; en = t; }
                        else if (ikinci == null || t.getRssi() > ikinci.getRssi()) ikinci = t;
                    }
                }
                if (en == null) throw new Exception("Etiket bulunamadı; etiketi okuyucuya yaklaştırın");
                if (ikinci != null && en.getRssi() - ikinci.getRssi() < 8) throw new Exception("Yakında birden fazla etiket var; yazılacak etiketi tek başına tutun");
                byte[] mevcut = en.getEpc();
                eski = NurApi.byteArrayToHexString(mevcut);
                api.writeEpcByEpc(mevcut, mevcut.length, yeni.length, yeni);
                sesVer(120);
                dinleyici.yazmaSonucu(true, yeniEpc, eski, null);
            } catch (Exception e) {
                sesVer(400);
                dinleyici.yazmaSonucu(false, yeniEpc, eski, e.getMessage() != null ? e.getMessage() : e.toString());
            } finally {
                try { api.clearIdBuffer(); } catch (Exception ignored) { }
                rfidGorulen.clear();
                yaziliyor = false;
            }
        }).start();
    }

    /** Etiket bul modunu açar: tetik basılıyken sürekli tarar, yalnız aranan etiketlerin sinyal gücünü bildirir */
    public void bulBasla(String onek, Set<String> epcler) {
        bulOnek = onek == null || onek.isEmpty() ? null : onek.toUpperCase();
        bulEpcler = epcler;
        if (!"bul".equals(mod)) bulOncekiMod = mod;
        mod = "bul";
        dinleyici.durumDegisti();
    }

    public void bulDurdur() {
        durdur();
        bulOnek = null;
        bulEpcler = new HashSet<>();
        mod = bulOncekiMod != null ? bulOncekiMod : "barkod";
        bulOncekiMod = null;
        dinleyici.durumDegisti();
    }

    private boolean aranan(String epc) {
        return (bulOnek != null && epc.startsWith(bulOnek)) || bulEpcler.contains(epc);
    }

    public void bip(int ms) {
        if (bagli && acc.isSupported()) sesVer(ms);
    }

    /** Ekrandaki "okut" düğmesi için: tetik tuşu gibi tek okuma */
    public void tekOkuma() {
        try {
            if (!bagli) return;
            if (yaziliyor) return;
            if ("rfid".equals(mod) || "bul".equals(mod)) { rfidBaslat(); } else if (barkodVar && !barkodOkunuyor) { barkodOkunuyor = true; acc.readBarcodeAsync(5000); }
        } catch (Exception e) { barkodOkunuyor = false; Log.w(TAG, "okuma başlatılamadı", e); }
    }

    public void durdur() {
        akisDurdur();
        try { if (barkodOkunuyor) acc.cancelBarcodeAsync(); } catch (Exception ignored) { }
        barkodOkunuyor = false;
        tetikBasili = false;
    }

    public void kapat() {
        durdur();
        try { if (baglanti != null) baglanti.onDestroy(); } catch (Exception ignored) { }
        baglanti = null;
    }

    private void birak() {
        try { if (baglanti != null) baglanti.dispose(); } catch (Exception ignored) { }
        baglanti = null;
    }

    public void onResume() {
        arkaplan = false;
        // Bağlantı arka planda açık tutulduysa dokunulmaz (yeniden kurmak okuyucuyu birkaç saniye koparıyordu)
        if (arkadaTutuldu && bagli) { arkadaTutuldu = false; return; }
        arkadaTutuldu = false;
        try { if (baglanti != null) baglanti.onResume(); } catch (Exception ignored) { }
    }
    public void onPause() {
        durdur();
        // "Tetikle aç" açıkken bağlantı arka planda da açık kalır (tetik olayı gelsin)
        if (arkadaBekle()) { arkaplan = true; arkadaTutuldu = true; return; }
        try { if (baglanti != null) baglanti.onPause(); } catch (Exception ignored) { }
    }

    public boolean isBagli() { return bagli; }
    public boolean isBarkodVar() { return barkodVar; }
    public String getCihazAdi() { return cihazAdi; }
    public String getMod() { return mod; }
    public boolean isSecildi() { return tercihler().contains("spec") || nordicCihaz(); }
    /** Bağlanmaya çalışılıp 12 sn içinde bağlanamadıysa true (sayfa uyarı gösterir) */
    public boolean isBaglanamadi() { return !bagli && baslangic > 0 && System.currentTimeMillis() - baslangic > 12000; }

    private SharedPreferences tercihler() {
        return ctx.getSharedPreferences(TERCIH, Context.MODE_PRIVATE);
    }

    private void rfidBaslat() throws Exception {
        // Her tetikte yeniden: aynı etiketin iki kez sayılmasını sayfa önler (ürün bulda aynı etiket tekrar okunabilsin)
        rfidGorulen.clear();
        rfidSayac = 0;
        // Önceki basıştan kalan etiketler yeniden gönderilmesin (ürün bulda artık orada olmayan etiket seçilmesin)
        NurTagStorage d = api.getStorage();
        synchronized (d) { d.clear(); }
        api.clearIdBuffer();
        akisBaslat();
    }

    /**
     * RFID taraması. "Yalnız Ulaş etiketleri" açıksa Gen2 Select ile yalnız sistemin yazdığı etiketler (EPC 554C01…)
     * cevap verir (ambalaj / başka firma etiketleri okunmaz, yoğun rafta daha hızlı). Komut desteklenmezse normal taramaya dönülür.
     */
    private volatile boolean genisletilmis = false;
    private void akisBaslat() throws Exception {
        if (isFiltre() && !"bul".equals(mod)) {
            try {
                NurInventoryExtended inv = new NurInventoryExtended();
                inv.inventorySelState = 3; // NUR_SELSTATE_SL: yalnız seçilen (SL) etiketler
                inv.session = 0;
                inv.inventoryTarget = 0;
                NurInventoryExtendedFilter f = new NurInventoryExtendedFilter();
                f.action = NurInventoryExtendedFilter.FILTER_ACTION_0; // eşleşen SL'i ayarlar, eşleşmeyen kaldırır
                f.targetSession = NurInventoryExtendedFilter.SESSION_SL;
                f.bank = NurInventoryExtendedFilter.BANK_EPC;
                f.address = 32; // EPC belleğinde CRC + PC'den sonra (bit)
                f.maskdata = NurApi.hexStringToByteArray("554C01");
                f.maskBitLength = 24;
                f.truncate = false;
                api.startInventoryExtendedStream(inv, new NurInventoryExtendedFilter[]{f});
                genisletilmis = true;
                return;
            } catch (Exception e) {
                Log.w(TAG, "filtreli tarama desteklenmedi; normal taramaya dönülüyor", e);
                filtreHatasi = true;
            }
        }
        genisletilmis = false;
        api.startInventoryStream();
    }

    private void akisDurdur() {
        try { if (api.isInventoryExtendedStreamRunning()) api.stopInventoryExtendedStream(); } catch (Exception ignored) { }
        try { if (api.isInventoryStreamRunning()) api.stopInventoryStream(); } catch (Exception ignored) { }
    }

    private volatile boolean filtreHatasi = false;
    public boolean isFiltre() { return tercihler().getBoolean("filtre", false); }
    public boolean isFiltreHatasi() { return filtreHatasi; }
    public void setFiltre(boolean a) { tercihler().edit().putBoolean("filtre", a).apply(); filtreHatasi = false; }

    private final AccBarcodeResultListener barkodSonucu = new AccBarcodeResultListener() {
        public void onBarcodeResult(AccBarcodeResult r) {
            barkodOkunuyor = false;
            if (r.status == NurApiErrors.NUR_SUCCESS && r.strBarcode != null && r.strBarcode.trim().length() > 0) {
                sesVer(60);
                titret(80);
                dinleyici.okundu(r.strBarcode.trim(), "barkod", 0);
            }
        }
    };

    private final NurApiListener olaylar = new NurApiListener() {
        public void connectedEvent() {
            bagli = true;
            yenidenDeneme = 0;
            // Okuyucuya giden komutlar ayrı iş parçacığında (olay iş parçacığı / ekran bloklanmasın)
            new Thread(() -> {
                try {
                    if (acc.isSupported()) {
                        com.nordicid.nurapi.AccConfig cfg = acc.getConfig();
                        cihazAdi = cfg.name;
                        barkodVar = cfg.hasImagerScanner();
                        titresimVar = cfg.hasVibrator();
                    } else {
                        cihazAdi = api.getReaderInfo().name;
                        barkodVar = false;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "cihaz bilgisi okunamadı", e);
                }
                try {
                    com.nordicid.nurapi.NurRespReaderInfo ri = api.getReaderInfo();
                    okuyucuSeri = ri.serial != null ? ri.serial : "";
                    okuyucuYazilim = ri.swVersion != null ? ri.swVersion : "";
                    bolgeId = api.getSetupRegionId();
                    try { bolgeAd = api.getRegionInfo().name; } catch (Exception ignored) { }
                    okuyucuBilgi = "seri " + okuyucuSeri + ", yazılım " + okuyucuYazilim + ", bölge " + bolgeAd + " (" + bolgeId + ")";
                } catch (Exception ignored) { }
                gucUygula();
                pilOku();
                dinleyici.durumDegisti();
            }).start();
        }

        public void disconnectedEvent() {
            bagli = false;
            // "Bağlanılamadı" uyarısı kopuştan 12 sn sonra (kısa kopmalarda uyarı çıkmasın)
            baslangic = System.currentTimeMillis();
            barkodVar = false;
            dinleyici.durumDegisti();
        }

        public void IOChangeEvent(NurEventIOChange e) {
            if (e.source != TETIK) { Log.i(TAG, "IO kaynağı " + e.source + " yön " + e.direction); return; }
            if (yaziliyor) return;
            if (arkaplan) {
                // Ekran kapalı / uygulama arkada: okumadan önce uygulama öne gelir, okuma orada başlar
                if (e.direction == 1) {
                    sonArkaTetik = System.currentTimeMillis();
                    arkaTetikSayisi++;
                    dinleyici.tetikArkaplanda();
                }
                return;
            }
            try {
                if (e.direction == 1) {
                    tetikBasili = true;
                    if ("rfid".equals(mod) || "bul".equals(mod)) rfidBaslat();
                    else if (barkodVar) acc.imagerAIM(true);
                } else {
                    tetikBasili = false;
                    if ("rfid".equals(mod) || "bul".equals(mod)) {
                        akisDurdur();
                    } else if (barkodVar && !barkodOkunuyor) {
                        acc.imagerAIM(false);
                        barkodOkunuyor = true;
                        acc.readBarcodeAsync(5000);
                    }
                }
            } catch (Exception ex) {
                // Barkod okuması başlayamadıysa kilit kalmasın
                barkodOkunuyor = false;
                Log.w(TAG, "tetik işlenemedi", ex);
            }
        }

        public void inventoryStreamEvent(NurEventInventory e) {
            try {
                if (e.stopped) {
                    // Akış ~20 sn sonra kendiliğinden durur; tetik basılıysa sürdürülür
                    if (tetikBasili && ("rfid".equals(mod) || "bul".equals(mod))) akisBaslat();
                    return;
                }
                if ("bul".equals(mod)) {
                    // Her turda depo boşaltılır ki aynı etiketin güncel sinyali gelsin; yalnız aranan etiketler bildirilir
                    NurTagStorage d = api.getStorage();
                    String enEpc = null; int enRssi = -999;
                    synchronized (d) {
                        for (int i = 0; i < d.size(); i++) {
                            NurTag t = d.get(i);
                            String epc = NurApi.byteArrayToHexString(t.getEpc());
                            if (aranan(epc) && t.getRssi() > enRssi) { enRssi = t.getRssi(); enEpc = epc; }
                        }
                        d.clear();
                    }
                    api.clearIdBuffer();
                    if (enEpc != null) dinleyici.bulSinyal(enEpc, enRssi);
                    return;
                }
                if (e.tagsAdded <= 0) return;
                NurTagStorage depo = api.getStorage();
                synchronized (depo) {
                    for (int i = rfidSayac; i < depo.size(); i++) {
                        NurTag t = depo.get(i);
                        String epc = NurApi.byteArrayToHexString(t.getEpc());
                        if (rfidGorulen.add(epc)) dinleyici.okundu(epc, "rfid", t.getRssi());
                    }
                    rfidSayac = depo.size();
                }
                sesVer(20);
            } catch (Exception ex) {
                Log.w(TAG, "envanter işlenemedi", ex);
            }
        }

        public void triggeredReadEvent(NurEventTriggeredRead e) { }
        public void traceTagEvent(NurEventTraceTag e) { }
        public void programmingProgressEvent(NurEventProgrammingProgress e) { }
        public void nxpEasAlarmEvent(NurEventNxpAlarm e) { }
        public void logEvent(int level, String txt) { }
        /** Filtreli tarama olayları normal tarama ile aynı işlenir */
        public void inventoryExtendedStreamEvent(NurEventInventory e) { inventoryStreamEvent(e); }
        public void frequencyHopEvent(NurEventFrequencyHop e) { }
        public void epcEnumEvent(NurEventEpcEnum e) { }
        public void deviceSearchEvent(NurEventDeviceInfo e) { }
        public void debugMessageEvent(String e) { }
        public void clientDisconnectedEvent(NurEventClientInfo e) { }
        public void clientConnectedEvent(NurEventClientInfo e) { }
        public void bootEvent(String e) { }
        public void autotuneEvent(NurEventAutotune e) { }
        public void tagTrackingScanEvent(NurEventTagTrackingData e) { }
        public void tagTrackingChangeEvent(NurEventTagTrackingChange e) { }
    };
}
