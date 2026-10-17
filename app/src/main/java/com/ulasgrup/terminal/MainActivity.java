package com.ulasgrup.terminal;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

/**
 * Ulaş Terminal: sistemin terminal ekranlarını tam ekran açar ve cihazın okuyucusunu (NUR API) ekranlara bağlar.
 * Okunan barkod / RFID etiketi sayfaya "ulas-okuma" olayıyla gider (terminal ekranları bunu tarama kutusuna düşürür).
 * Sayfa cihaza window.UlasCihaz üzerinden ulaşır (okuyucu durumu, okuyucu seçimi, mod, bip).
 */
public class MainActivity extends AppCompatActivity implements NurOkuyucu.Dinleyici {
    private static final String ADRES = "https://sistem.ulasgrup.com.tr/terminal?kaynak=uygulama";
    private static final String ALAN = "sistem.ulasgrup.com.tr";
    private static final int KAMERA_IZNI = 7;

    private WebView web;
    private NurOkuyucu okuyucu;
    private PermissionRequest bekleyenKameraIstegi;
    private boolean guvenli = false;
    private AcilisEkrani acilis;
    /** Ekran modu: banko / depo TV'si (okuyucu yok, tam ekran, ekran hep açık, kumandada menü) */
    private boolean ekran = false;
    private static final long EKRAN_YENILE = 6 * 3600 * 1000L;
    /** Ekran sayfası 6 saatte bir baştan yüklenir (sistem güncellemeleri ekrana da gelsin) */
    private final Runnable ekranYenile = new Runnable() {
        @Override public void run() {
            if (ekran && !hataSayfasi && web != null) web.loadUrl(EkranModu.acilis(MainActivity.this));
            zamanlayici.postDelayed(this, EKRAN_YENILE);
        }
    };

    /**
     * Kaldığı yerden devam: son açık sayfa (sistem içi, 12 saatten yeni) saklanır; uygulama yeniden açılınca
     * (ekran kapanıp Android uygulamayı kapatmışsa, bağlantı gelince) ana ekran yerine oraya dönülür.
     */
    private String acilisAdresi() {
        if (ekran) return EkranModu.acilis(this);
        android.content.SharedPreferences t = getSharedPreferences("ulas_sayfa", MODE_PRIVATE);
        String son = t.getString("adres", null);
        long zaman = t.getLong("zaman", 0);
        if (son != null && son.startsWith("https://" + ALAN + "/") && !son.contains("/login") && !son.contains("/logout")
            && System.currentTimeMillis() - zaman < 12 * 3600 * 1000L) return son;
        return ADRES;
    }

    private void sayfaKaydet() {
        if (ekran) return;
        try {
            String u = web.getUrl();
            if (u != null && u.startsWith("https://" + ALAN + "/") && !u.contains("/login")) {
                getSharedPreferences("ulas_sayfa", MODE_PRIVATE).edit().putString("adres", u).putLong("zaman", System.currentTimeMillis()).apply();
            }
        } catch (Exception ignored) { }
    }
    private static final long ARKAPLAN_SURE = 30 * 60 * 1000L;
    /** 30 dk tetiğe basılmadı: okuyucu bırakılır, "hazır" bildirimi kalkar (pil) */
    private final Runnable arkaplanBitir = () -> {
        if (okuyucu != null) okuyucu.arkaplaniBirak();
        try { stopService(new Intent(this, HazirServis.class)); } catch (Exception ignored) { }
    };
    private android.speech.tts.TextToSpeech ses;
    private boolean sesHazir = false;
    /** Metin okuma durumu: bekliyor | hazir | dil_yok | motor_yok | hata */
    private volatile String sesDurum = "bekliyor";
    private String sesBekleyen = null;

    /** Metin okuma motoru açılışta hazırlanır; Türkçe yoksa durum sayfaya bildirilir (ayarlarda kurulum düğmesi) */
    private void sesHazirla() {
        if (ses != null) return;
        try {
            ses = new android.speech.tts.TextToSpeech(this, durum -> {
                if (durum != android.speech.tts.TextToSpeech.SUCCESS) { sesDurum = ses != null && ses.getEngines().isEmpty() ? "motor_yok" : "hata"; durumDegisti(); return; }
                int r = ses.setLanguage(new java.util.Locale("tr", "TR"));
                sesDurum = (r == android.speech.tts.TextToSpeech.LANG_MISSING_DATA || r == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) ? "dil_yok" : "hazir";
                ses.setSpeechRate(1.05f);
                sesHazir = true;
                durumDegisti();
                if (sesBekleyen != null) { ses.speak(sesBekleyen, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "ulas"); sesBekleyen = null; }
            });
        } catch (Throwable e) {
            sesDurum = "motor_yok";
        }
    }
    /** Bağlantı yok sayfası açıksa: ağ gelince ya da 8 sn'de bir sistem yeniden açılır (elle "Tekrar dene" gerekmez) */
    private volatile boolean hataSayfasi = false;
    private final Runnable yenidenDene = new Runnable() {
        @Override public void run() {
            if (!hataSayfasi) return;
            web.loadUrl(acilisAdresi());
            zamanlayici.postDelayed(this, 8000);
        }
    };
    private final android.os.Handler zamanlayici = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable bekciDongusu = new Runnable() {
        @Override public void run() {
            if (okuyucu != null) okuyucu.bekci();
            zamanlayici.postDelayed(this, 10000);
        }
    };
    private final Runnable pilDongusu = new Runnable() {
        @Override public void run() {
            if (okuyucu != null) {
                new Thread(() -> { okuyucu.pilOku(); durumDegisti(); }).start();
            }
            zamanlayici.postDelayed(this, 60000);
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Ekran sürekli açık tutulmaz (pil): okutma ekranları çalışılırken UlasCihaz.ekranAcik(true) ile ister, boşta bırakır.
        // Ekran modunda (TV) ekran hep açık, yön ekranınki; el terminalinde dikey.
        ekran = EkranModu.acik(this);
        setRequestedOrientation(ekran ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        if (ekran) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        android.widget.FrameLayout kok = new android.widget.FrameLayout(this);
        kok.addView(web);
        acilis = new AcilisEkrani(this);
        kok.addView(acilis);
        setContentView(kok);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        // Sunucu bu uygulamayı tanısın (Cihazlar sayfası, terminal hesabı kontrolü: "Android" ve "Mobile" içerir)
        ws.setUserAgentString(ws.getUserAgentString() + " UlasTerminal/" + BuildConfig.VERSION_NAME + " (" + Build.MODEL + ")");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);

        web.addJavascriptInterface(new Kopru(), "UlasCihaz");
        // Yeni sürüm indirme (/uygulama/indir): oturum çereziyle Android indiricisine verilir; bitince kurulum açılır
        web.setDownloadListener((url, ua, icerik, tur, boyut) -> {
            try {
                android.app.DownloadManager.Request r = new android.app.DownloadManager.Request(Uri.parse(url));
                r.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
                r.addRequestHeader("User-Agent", ua);
                r.setMimeType("application/vnd.android.package-archive");
                r.setTitle("Ulaş Terminal");
                r.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setDestinationInExternalFilesDir(this, android.os.Environment.DIRECTORY_DOWNLOADS, "UlasTerminal-" + System.currentTimeMillis() + ".apk");
                final android.app.DownloadManager dm = (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                final long no = dm.enqueue(r);
                android.widget.Toast.makeText(this, R.string.indiriliyor, android.widget.Toast.LENGTH_LONG).show();
                ContextCompat.registerReceiver(this, new android.content.BroadcastReceiver() {
                    @Override public void onReceive(android.content.Context c, Intent i) {
                        if (i.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1) != no) return;
                        try { unregisterReceiver(this); } catch (Exception ignored) { }
                        Uri dosya = dm.getUriForDownloadedFile(no);
                        if (dosya == null) return;
                        Intent kur = new Intent(Intent.ACTION_VIEW);
                        kur.setDataAndType(dosya, "application/vnd.android.package-archive");
                        kur.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        try { startActivity(kur); } catch (Exception ignored) { }
                    }
                }, new android.content.IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED);
            } catch (Exception e) {
                android.widget.Toast.makeText(this, R.string.indirilemedi, android.widget.Toast.LENGTH_LONG).show();
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (ALAN.equals(u.getHost())) return false;
                // Sistem dışı bağlantılar telefonun tarayıcısında açılır
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    hataSayfasi = true;
                    zamanlayici.removeCallbacks(yenidenDene);
                    zamanlayici.postDelayed(yenidenDene, 8000);
                    v.loadData("<html><body style='font-family:sans-serif;padding:32px;text-align:center'><h2>Bağlantı yok</h2><p>"
                        + getString(R.string.baglanti_yok) + "</p><p><a href='" + ADRES + "' style='font-size:18px'>" + getString(R.string.tekrar_dene) + "</a></p></body></html>", "text/html; charset=utf-8", "UTF-8");
                }
            }

            /** Ekran silindi ya da yeni adres verildi (404): eşleştirmeye dönülür */
            @Override
            public void onReceivedHttpError(WebView v, WebResourceRequest r, android.webkit.WebResourceResponse y) {
                if (ekran && r.isForMainFrame() && y != null && y.getStatusCode() == 404 && r.getUrl().getPath() != null && r.getUrl().getPath().startsWith("/ekran/ek_")) {
                    EkranModu.unut(MainActivity.this);
                    v.loadUrl(EkranModu.ESLESTIR);
                }
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (url != null && url.contains("/login")) getSharedPreferences("ulas_sayfa", MODE_PRIVATE).edit().remove("adres").apply();
                if (url != null && url.startsWith("https://" + ALAN)) hataSayfasi = false;
                durumDegisti();
                if (acilis != null) { acilis.kapat(); acilis = null; }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest istek) {
                runOnUiThread(() -> {
                    if (!ALAN.equals(istek.getOrigin().getHost())) { istek.deny(); return; }
                    if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        istek.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        bekleyenKameraIstegi = istek;
                        ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.CAMERA}, KAMERA_IZNI);
                    }
                });
            }
        });

        // Önceki açılış çöktüyse kaydı gönderilir ve okuyucu bu sefer başlatılmaz (ekran yine açılır)
        guvenli = Uygulama.oncekiHatayiGonder(this);
        // Okuyucu seçme ekranı (NurDeviceListActivity) Bluetooth tarayıcısının hazırlanmış olmasını bekler
        if (!ekran) { try { com.nordicid.nurapi.BleScanner.init(this); } catch (Throwable ignored) { } }
        if (!guvenli && !ekran) {
            try {
                okuyucu = new NurOkuyucu(this, this);
                // Nordic cihazında dahili okuyucuya kendiliğinden bağlanır; olmazsa ayarlardan "Okuyucu seç"
                okuyucu.baslat();
                zamanlayici.postDelayed(this::durumDegisti, 13000);
            } catch (Throwable e) {
                okuyucu = null;
            }
        }
        web.loadUrl(acilisAdresi());
        if (ekran) zamanlayici.postDelayed(ekranYenile, EKRAN_YENILE);
        else { tetikleAcildiysa(getIntent()); sesHazirla(); }
        if (!ekran && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{"android.permission.POST_NOTIFICATIONS"}, 8);
        }
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            cm.registerDefaultNetworkCallback(new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(android.net.Network n) {
                    runOnUiThread(() -> { if (hataSayfasi) { zamanlayici.removeCallbacks(yenidenDene); zamanlayici.postDelayed(yenidenDene, 1500); } });
                }
            });
        } catch (Exception ignored) { }
    }

    @Override
    public void okundu(String kod, String tur, int rssi) {
        final String js = "window.dispatchEvent(new CustomEvent('ulas-okuma',{detail:{kod:" + JSONObject.quote(kod) + ",tur:" + JSONObject.quote(tur) + ",rssi:" + rssi + "}}))";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    @Override
    public void tetikArkaplanda() {
        // Önce ekran doğrudan açılır (kilitliyken tam ekran bildirim tek başına her cihazda ekranı yakmıyor)
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            @SuppressWarnings("deprecation")
            android.os.PowerManager.WakeLock wl = pm.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP | android.os.PowerManager.ON_AFTER_RELEASE, "UlasTerminal:tetik");
            wl.acquire(4000);
        } catch (Exception ignored) { }
        runOnUiThread(() -> {
            zamanlayici.removeCallbacks(arkaplanBitir);
            try { HazirServis.uyandir(this); } catch (Exception ignored) { }
            // Ekran açıldıysa ve kilit yoksa doğrudan öne gelmeyi de dene (bildirim kayarsa)
            try {
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tetik", true));
            } catch (Exception ignored) { }
        });
    }

    /** Tetikle açıldıysa: kilit ekranının üstünde ve ekranı açarak gelir, okuma kendiliğinden başlar */
    private void tetikleAcildiysa(Intent i) {
        if (i == null || !i.getBooleanExtra("tetik", false)) return;
        i.removeExtra("tetik");
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        // Şifresiz kilit ekranı kaldırılır (şifreliyse uygulama kilidin üstünde gösterilir)
        try {
            android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && km != null && km.isKeyguardLocked() && !km.isKeyguardSecure()) km.requestDismissKeyguard(this, null);
        } catch (Exception ignored) { }
        try { ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(HazirServis.NO_TETIK); } catch (Exception ignored) { }
        // Barkod modunda uyanınca tek okuma başlar (tetik bırakılmış olabilir); RFID'de tetik basılı tutulunca taranır
        zamanlayici.postDelayed(() -> { if (okuyucu != null && "barkod".equals(okuyucu.getMod())) okuyucu.tekOkuma(); }, 700);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        tetikleAcildiysa(i);
    }

    @Override
    public void bulSinyal(String epc, int rssi) {
        final String js = "window.dispatchEvent(new CustomEvent('ulas-rfid-bul',{detail:{epc:" + JSONObject.quote(epc) + ",rssi:" + rssi + "}}))";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    @Override
    public void yazmaSonucu(boolean ok, String epc, String eski, String hata) {
        final String js = "window.dispatchEvent(new CustomEvent('ulas-rfid-yazma',{detail:{ok:" + ok + ",epc:" + JSONObject.quote(epc) + ",eski:" + JSONObject.quote(eski == null ? "" : eski)
            + ",hata:" + (hata == null ? "null" : JSONObject.quote(hata)) + "}}))";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    @Override
    public void durumDegisti() {
        final String js = "window.dispatchEvent(new CustomEvent('ulas-cihaz',{detail:" + durumJson() + "}))";
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    private String durumJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("uygulama", BuildConfig.VERSION_NAME);
            o.put("model", Build.MODEL);
            o.put("guvenli", guvenli);
            o.put("bagli", okuyucu != null && okuyucu.isBagli());
            o.put("secildi", okuyucu != null && okuyucu.isSecildi());
            o.put("baglanamadi", okuyucu == null || okuyucu.isBaglanamadi());
            o.put("okuyucu", okuyucu != null ? okuyucu.getCihazAdi() : "");
            o.put("barkod", okuyucu != null && okuyucu.isBarkodVar());
            o.put("rfid", okuyucu != null && okuyucu.isBagli());
            o.put("mod", okuyucu != null ? okuyucu.getMod() : "barkod");
            o.put("guc", okuyucu != null ? okuyucu.getGuc() : "orta");
            o.put("uyandir", okuyucu != null && okuyucu.isUyandir());
            o.put("tts", sesDurum);
            if (okuyucu != null) {
                o.put("okuyucuBilgi", okuyucu.getOkuyucuBilgi());
                o.put("okuyucuSeri", okuyucu.getOkuyucuSeri());
                o.put("okuyucuYazilim", okuyucu.getOkuyucuYazilim());
                o.put("bolge", okuyucu.getBolgeAd());
                o.put("bolgeId", okuyucu.getBolgeId());
                o.put("filtre", okuyucu.isFiltre());
                o.put("filtreHatasi", okuyucu.isFiltreHatasi());
            }
            if (okuyucu != null) {
                o.put("tetikSon", okuyucu.getSonArkaTetik());
                o.put("tetikSayi", okuyucu.getArkaTetikSayisi());
                o.put("deneme", okuyucu.getYenidenDeneme());
                o.put("okuyucuTuru", okuyucu.getSpecTuru());
            }
            o.put("pil", okuyucu != null ? okuyucu.getPilYuzde() : -1);
            o.put("sarj", okuyucu != null && okuyucu.isPilSarj());
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Sayfadan çağrılır: window.UlasCihaz.durum(), .okuyucuSec(), .mod('rfid'|'barkod'), .rfidTemizle(), .bip(ms), .oku() */
    private class Kopru {
        @JavascriptInterface public String durum() { return durumJson(); }
        @JavascriptInterface public void okuyucuSec() {
            runOnUiThread(() -> {
                try {
                    if (okuyucu == null) okuyucu = new NurOkuyucu(MainActivity.this, MainActivity.this);
                    okuyucu.secimEkraniAc(MainActivity.this);
                } catch (Throwable e) { okuyucu = null; }
            });
        }
        @JavascriptInterface public void mod(String m) { if (okuyucu != null) okuyucu.setMod(m); }
        @JavascriptInterface public void rfidTemizle() { if (okuyucu != null) okuyucu.rfidTemizle(); }
        @JavascriptInterface public void bip(int ms) { if (okuyucu != null) okuyucu.bip(Math.max(10, Math.min(ms, 1000))); }
        @JavascriptInterface public void oku() { if (okuyucu != null) okuyucu.tekOkuma(); }
        @JavascriptInterface public void durdur() { if (okuyucu != null) okuyucu.durdur(); }
        @JavascriptInterface public void guc(String g) { if (okuyucu != null) okuyucu.setGuc(g); }
        @JavascriptInterface public void ekranAcik(boolean a) {
            runOnUiThread(() -> {
                if (a) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }
        /** Etiket bul: hedef = {"onek":"554C0100000123","epcler":["E280..."]} */
        @JavascriptInterface public void bulBasla(String hedef) {
            if (okuyucu == null) return;
            try {
                JSONObject o = new JSONObject(hedef);
                java.util.Set<String> s = new java.util.HashSet<>();
                org.json.JSONArray a = o.optJSONArray("epcler");
                if (a != null) for (int i = 0; i < a.length(); i++) s.add(a.getString(i).toUpperCase());
                okuyucu.bulBasla(o.optString("onek", ""), s);
            } catch (Exception ignored) { }
        }
        @JavascriptInterface public void filtre(boolean a) { if (okuyucu != null) okuyucu.setFiltre(a); }
        @JavascriptInterface public void geriBildirim(boolean ses, boolean titresim) { if (okuyucu != null) okuyucu.setGeriBildirim(ses, titresim); }
        @JavascriptInterface public void okutmaEkrani(boolean a) { if (okuyucu != null) okuyucu.setOkutmaEkrani(a); }
        /** Sesli yönlendirme: Android'in Türkçe sesi */
        @JavascriptInterface public void konus(String metin) {
            if (metin == null || metin.isEmpty()) return;
            final String m = metin.length() > 300 ? metin.substring(0, 300) : metin;
            runOnUiThread(() -> {
                if (ses == null) sesHazirla();
                if (sesHazir) ses.speak(m, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "ulas");
                else sesBekleyen = m;
            });
        }
        /** Türkçe ses paketi kurulumu / metin okuma ayarları */
        @JavascriptInterface public void sesPaketi() {
            runOnUiThread(() -> {
                try { startActivity(new Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)); }
                catch (Exception e) { sesAyarlari(); }
            });
        }
        @JavascriptInterface public void sesAyarlari() {
            runOnUiThread(() -> {
                try { startActivity(new Intent("com.android.settings.TTS_SETTINGS")); }
                catch (Exception e) { try { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); } catch (Exception ignored) { } }
            });
        }
        @JavascriptInterface public void uyandirma(boolean a) { if (okuyucu != null) okuyucu.setUyandir(a); }
        @JavascriptInterface public void bulDurdur() { if (okuyucu != null) okuyucu.bulDurdur(); }
        @JavascriptInterface public void tasarruf(boolean a) { if (okuyucu != null) okuyucu.setTasarruf(a); }
        @JavascriptInterface public void yenidenBaglan() {
            runOnUiThread(() -> {
                try {
                    if (okuyucu == null) { okuyucu = new NurOkuyucu(MainActivity.this, MainActivity.this); okuyucu.baslat(); }
                    else okuyucu.yenidenBaglan();
                } catch (Throwable e) { okuyucu = null; }
            });
        }
        @JavascriptInterface public void yaz(String epc) {
            if (okuyucu != null && epc != null && epc.matches("^[0-9A-Fa-f]{24,64}$")) okuyucu.yaz(epc);
            else yazmaSonucu(false, epc, "", "Geçersiz istek");
        }
        @JavascriptInterface public void sonuc(boolean hata) { if (okuyucu != null) okuyucu.sonuc(hata); }
        /** Eşleştirme sayfası: ekran adresi geldi → cihaza kaydedilir, ekran modunda açılır */
        @JavascriptInterface public void ekranModu(String adres) {
            if (!EkranModu.gecerli(adres)) return;
            EkranModu.kaydet(MainActivity.this, adres);
            runOnUiThread(() -> {
                if (!ekran) { ekran = true; recreate(); return; }
                web.loadUrl(adres);
            });
        }
        @JavascriptInterface public boolean ekranModundaMi() { return ekran; }

        /** Ayırt edilebilir tonlar: ok / uyari / hata (okuyucunun ya da cihazın hoparlörü + titreşim) */
        @JavascriptInterface public void ton(String tur) { runOnUiThread(() -> tonCal(tur)); }

        /** Kiosk modu yalnız panelden (cihaz ayarı) gelir */
        @JavascriptInterface public void kiosk(boolean a) {
            runOnUiThread(() -> {
                boolean degisti = Kiosk.ayarla(MainActivity.this, a);
                kioskUygula();
                // İlk açılışta Android'e "ana ekran: Ulaş Terminal" seçtirilir (cihaz sahibi değilse tek yol)
                if (degisti && a && !Kiosk.varsayilanAnaEkran(MainActivity.this)) {
                    android.widget.Toast.makeText(MainActivity.this, R.string.kiosk_ana_ekran_sec, android.widget.Toast.LENGTH_LONG).show();
                    try { startActivity(new Intent(android.provider.Settings.ACTION_HOME_SETTINGS)); } catch (Exception ignored) { }
                }
            });
        }

        /** Yönetici çıkışı (sunucu PIN'i doğruladıktan sonra sayfa çağırır): süre boyunca Android ayarları açık */
        @JavascriptInterface public void kioskAra(int dakika) {
            runOnUiThread(() -> {
                Kiosk.ara(MainActivity.this, dakika);
                Kiosk.sabitle(MainActivity.this, false);
                try { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); } catch (Exception ignored) { }
            });
        }

        /** Wi-Fi ayarı (bağlantı koparsa PIN'siz): 3 dakika */
        @JavascriptInterface public void wifi() {
            runOnUiThread(() -> {
                if (Kiosk.acik(MainActivity.this)) { Kiosk.ara(MainActivity.this, 3); Kiosk.sabitle(MainActivity.this, false); }
                try { startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)); } catch (Exception ignored) { }
            });
        }
    }

    private android.media.ToneGenerator tonUretici;

    /** Okuyucu bağlı değilse cihazın hoparlörü (ToneGenerator) ve titreşim motoru */
    private void tonCal(String tur) {
        if (okuyucu != null && okuyucu.ton(tur)) return;
        android.content.SharedPreferences p = getSharedPreferences("ulas_okuyucu", MODE_PRIVATE);
        boolean sesAcik = okuyucu != null ? okuyucu.sesTercihi() : p.getBoolean("ses", true);
        boolean titresimAcik = okuyucu != null ? okuyucu.titresimTercihi() : p.getBoolean("titresim", true);
        boolean hata = "hata".equals(tur), uyari = "uyari".equals(tur);
        try {
            if (sesAcik) {
                if (tonUretici == null) tonUretici = new android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100);
                tonUretici.startTone(hata ? android.media.ToneGenerator.TONE_CDMA_ABBR_REORDER : uyari ? android.media.ToneGenerator.TONE_PROP_BEEP2 : android.media.ToneGenerator.TONE_PROP_BEEP, hata ? 450 : uyari ? 300 : 120);
            }
        } catch (Exception ignored) { }
        try {
            if (titresimAcik && (hata || uyari)) {
                android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
                long[] desen = hata ? new long[]{0, 120, 80, 120} : new long[]{0, 70, 90, 70};
                if (v != null && v.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createWaveform(desen, -1));
                    else v.vibrate(desen, -1);
                }
            }
        } catch (Exception ignored) { }
    }

    /** Kiosk açıksa (ve geçici çıkışta değilse) ekran sabitlenir; kapalıysa bırakılır. Ekran (TV) modunda uygulanmaz. */
    private void kioskUygula() {
        boolean sabit = !ekran && Kiosk.acik(this) && !Kiosk.arada(this);
        Kiosk.sabitle(this, sabit);
    }

    /** Kumandada Geri / Menü: ekran modunda uygulama kapanmaz, küçük bir menü açılır */
    private void ekranMenusu() {
        String[] secenekler = {getString(R.string.ekran_yenile), getString(R.string.ekran_yeniden_eslestir), getString(R.string.ekran_modundan_cik), getString(R.string.ekran_ayarlar), getString(R.string.ekran_kapat)};
        new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.ekran_menu)
            .setItems(secenekler, (d, i) -> {
                if (i == 0) web.reload();
                else if (i == 1) { EkranModu.unut(this); web.loadUrl(EkranModu.ESLESTIR); }
                else if (i == 2) {
                    new android.app.AlertDialog.Builder(this).setMessage(R.string.ekran_cik_onay)
                        .setPositiveButton(R.string.ekran_modundan_cik, (d2, w) -> { EkranModu.kapat(this); ekran = false; recreate(); })
                        .setNegativeButton(android.R.string.cancel, null).show();
                }
                else if (i == 3) { try { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); } catch (Exception ignored) { } }
            })
            .show();
    }

    @Override
    public boolean onKeyDown(int kod, android.view.KeyEvent e) {
        if (ekran && (kod == android.view.KeyEvent.KEYCODE_MENU || kod == android.view.KeyEvent.KEYCODE_SETTINGS || kod == android.view.KeyEvent.KEYCODE_INFO)) {
            ekranMenusu();
            return true;
        }
        return super.onKeyDown(kod, e);
    }

    /** Ekran modunda sistem çubukları gizli (TV'de tam ekran) */
    private void tamEkran() {
        if (!ekran) { getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE); return; }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean odak) {
        super.onWindowFocusChanged(odak);
        if (odak) tamEkran();
    }

    @Override
    protected void onActivityResult(int istek, int sonuc, Intent veri) {
        super.onActivityResult(istek, sonuc, veri);
        if (okuyucu != null && okuyucu.secimSonucu(istek, sonuc, veri)) durumDegisti();
    }

    @Override
    public void onRequestPermissionsResult(int istek, String[] izinler, int[] sonuclar) {
        super.onRequestPermissionsResult(istek, izinler, sonuclar);
        if (istek == KAMERA_IZNI && bekleyenKameraIstegi != null) {
            if (sonuclar.length > 0 && sonuclar[0] == PackageManager.PERMISSION_GRANTED) bekleyenKameraIstegi.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            else bekleyenKameraIstegi.deny();
            bekleyenKameraIstegi = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (ekran) { ekranMenusu(); return; }
        if (web.canGoBack()) web.goBack();
        else if (Kiosk.acik(this)) { /* kiosk: uygulamadan çıkılmaz */ }
        else super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (okuyucu != null) okuyucu.onResume();
        try { stopService(new Intent(this, HazirServis.class)); } catch (Exception ignored) { }
        zamanlayici.removeCallbacks(arkaplanBitir);
        web.onResume();
        zamanlayici.removeCallbacks(pilDongusu);
        zamanlayici.postDelayed(pilDongusu, 3000);
        zamanlayici.removeCallbacks(bekciDongusu);
        zamanlayici.postDelayed(bekciDongusu, 12000);
        tamEkran();
        kioskUygula();
    }

    @Override
    protected void onPause() {
        // Kilit ekranının üstünde gösterme yalnız tetikle açılış içindir; arkaya geçince geri alınır
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(false); setTurnScreenOn(false); }
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        sayfaKaydet();
        if (okuyucu != null) {
            boolean bekle = okuyucu.arkadaBekle();
            okuyucu.onPause();
            if (bekle) {
                try { ContextCompat.startForegroundService(this, new Intent(this, HazirServis.class)); } catch (Exception ignored) { }
                zamanlayici.removeCallbacks(arkaplanBitir);
                zamanlayici.postDelayed(arkaplanBitir, ARKAPLAN_SURE);
            }
        }
        zamanlayici.removeCallbacks(pilDongusu);
        zamanlayici.removeCallbacks(bekciDongusu);
        web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (okuyucu != null) okuyucu.kapat();
        try { stopService(new Intent(this, HazirServis.class)); } catch (Exception ignored) { }
        if (ses != null) { try { ses.shutdown(); } catch (Exception ignored) { } }
        if (tonUretici != null) { try { tonUretici.release(); } catch (Exception ignored) { } }
        zamanlayici.removeCallbacksAndMessages(null);
        web.destroy();
        super.onDestroy();
    }
}
