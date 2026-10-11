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
    /** Bağlantı yok sayfası açıksa: ağ gelince ya da 8 sn'de bir sistem yeniden açılır (elle "Tekrar dene" gerekmez) */
    private volatile boolean hataSayfasi = false;
    private final Runnable yenidenDene = new Runnable() {
        @Override public void run() {
            if (!hataSayfasi) return;
            web.loadUrl(ADRES);
            zamanlayici.postDelayed(this, 8000);
        }
    };
    private final android.os.Handler zamanlayici = new android.os.Handler(android.os.Looper.getMainLooper());
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
        // Ekran sürekli açık tutulmaz (pil): okutma ekranları çalışılırken UlasCihaz.ekranAcik(true) ile ister, boşta bırakır

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

            @Override
            public void onPageFinished(WebView v, String url) {
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
        try { com.nordicid.nurapi.BleScanner.init(this); } catch (Throwable ignored) { }
        if (!guvenli) {
            try {
                okuyucu = new NurOkuyucu(this, this);
                // Nordic cihazında dahili okuyucuya kendiliğinden bağlanır; olmazsa ayarlardan "Okuyucu seç"
                okuyucu.baslat();
                zamanlayici.postDelayed(this::durumDegisti, 13000);
            } catch (Throwable e) {
                okuyucu = null;
            }
        }
        web.loadUrl(ADRES);
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
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (okuyucu != null) okuyucu.onResume();
        web.onResume();
        zamanlayici.removeCallbacks(pilDongusu);
        zamanlayici.postDelayed(pilDongusu, 3000);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    protected void onPause() {
        if (okuyucu != null) okuyucu.onPause();
        zamanlayici.removeCallbacks(pilDongusu);
        web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (okuyucu != null) okuyucu.kapat();
        web.destroy();
        super.onDestroy();
    }
}
