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

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        setContentView(web);
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
                    v.loadData("<html><body style='font-family:sans-serif;padding:32px;text-align:center'><h2>Bağlantı yok</h2><p>"
                        + getString(R.string.baglanti_yok) + "</p><p><a href='" + ADRES + "' style='font-size:18px'>" + getString(R.string.tekrar_dene) + "</a></p></body></html>", "text/html; charset=utf-8", "UTF-8");
                }
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                durumDegisti();
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
                if (!okuyucu.baslat()) {
                    // İlk açılış: okuyucu seçilir (HH85'te "Integrated reader"), bir daha sorulmaz
                    okuyucu.secimEkraniAc(this);
                }
            } catch (Throwable e) {
                okuyucu = null;
            }
        }
        web.loadUrl(ADRES);
    }

    @Override
    public void okundu(String kod, String tur) {
        final String js = "window.dispatchEvent(new CustomEvent('ulas-okuma',{detail:{kod:" + JSONObject.quote(kod) + ",tur:" + JSONObject.quote(tur) + "}}))";
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
            o.put("okuyucu", okuyucu != null ? okuyucu.getCihazAdi() : "");
            o.put("barkod", okuyucu != null && okuyucu.isBarkodVar());
            o.put("rfid", okuyucu != null && okuyucu.isBagli());
            o.put("mod", okuyucu != null ? okuyucu.getMod() : "barkod");
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
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    protected void onPause() {
        if (okuyucu != null) okuyucu.onPause();
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
