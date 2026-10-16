package com.ulasgrup.ekran;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Ulaş Ekran: banko / depo TV kutusunda bilgi ekranını tam ekran açar. Kimse kullanmaz; kumandayla açılır ve kendi çalışır.
 *  - İlk açılış: eşleştirme kodu (/ekran-eslestir); kod panelde "TV'ye bağla" ile girilince ekran adresi cihaza kaydedilir.
 *  - Ekran hep açık, sistem çubukları gizli; bağlantı koparsa kendisi yeniden dener; ekran silinirse koda döner.
 *  - Kumandada Geri / Menü: küçük menü (yenile, başka ekrana bağla, ayarlar).
 */
public class EkranActivity extends Activity {
    private static final long YENILE = 6 * 3600 * 1000L;
    private WebView web;
    private final Handler zaman = new Handler(Looper.getMainLooper());
    private volatile boolean hata = false;

    /** Bağlantı yoksa 10 sn'de bir yeniden dener */
    private final Runnable yenidenDene = new Runnable() {
        @Override public void run() {
            if (!hata) return;
            web.loadUrl(Ayar.acilis(EkranActivity.this));
            zaman.postDelayed(this, 10000);
        }
    };
    /** 6 saatte bir baştan yükler: sistem güncellemeleri ekrana da gelsin */
    private final Runnable periyodik = new Runnable() {
        @Override public void run() {
            if (!hata) web.loadUrl(Ayar.acilis(EkranActivity.this));
            zaman.postDelayed(this, YENILE);
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        web.setBackgroundColor(0xFFF1F5F9);
        setContentView(web);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        // Uyarı sesi ve sesli duyuru dokunmadan çalsın
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setUserAgentString(ws.getUserAgentString() + " UlasEkran/" + BuildConfig.VERSION_NAME + " (" + Build.MODEL + ")");
        web.addJavascriptInterface(new Kopru(), "UlasCihaz");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                // Sistem dışına çıkılmaz (TV'de tarayıcı yok)
                return !Ayar.ALAN.equals(r.getUrl().getHost());
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (!r.isForMainFrame()) return;
                hata = true;
                zaman.removeCallbacks(yenidenDene);
                zaman.postDelayed(yenidenDene, 10000);
                v.loadDataWithBaseURL(null, "<html><body style='margin:0;height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;"
                    + "font-family:sans-serif;background:#f1f5f9;color:#0f172a'><h1 style='font-size:6vh;margin:0'>" + getString(R.string.baglanti_yok)
                    + "</h1><p style='font-size:3vh;color:#475569'>" + getString(R.string.baglanti_aciklama) + "</p></body></html>", "text/html", "UTF-8", null);
            }

            /** Ekran silindi ya da yeni adres verildi (404): eşleştirmeye dönülür */
            @Override
            public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse y) {
                String yol = r.getUrl().getPath();
                if (r.isForMainFrame() && y != null && y.getStatusCode() == 404 && yol != null && yol.startsWith("/ekran/ek_")) {
                    Ayar.unut(EkranActivity.this);
                    v.loadUrl(Ayar.ESLESTIR);
                }
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (url != null && url.startsWith("https://" + Ayar.ALAN)) hata = false;
            }
        });
        web.loadUrl(Ayar.acilis(this));
        zaman.postDelayed(periyodik, YENILE);
        try {
            ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) {
                    runOnUiThread(() -> { if (hata) { zaman.removeCallbacks(yenidenDene); zaman.postDelayed(yenidenDene, 1500); } });
                }
            });
        } catch (Throwable ignored) { }
    }

    /** Sayfadan: eşleştirme tamamlandı (adres kaydedilir), ekranı açık tut (zaten hep açık) */
    private class Kopru {
        @JavascriptInterface public void ekranModu(String adres) {
            if (!Ayar.gecerli(adres)) return;
            Ayar.kaydet(EkranActivity.this, adres);
            runOnUiThread(() -> web.loadUrl(adres));
        }
        @JavascriptInterface public void ekranAcik(boolean a) { }
        @JavascriptInterface public boolean ekranModundaMi() { return true; }
        @JavascriptInterface public String surum() { return BuildConfig.VERSION_NAME; }
    }

    private void menu() {
        String[] s = {getString(R.string.yenile), getString(R.string.yeniden_eslestir), getString(R.string.ayarlar), getString(R.string.kapat_uygulama), getString(R.string.iptal)};
        new AlertDialog.Builder(this).setTitle(getString(R.string.menu) + " " + BuildConfig.VERSION_NAME).setItems(s, (d, i) -> {
            if (i == 0) web.loadUrl(Ayar.acilis(this));
            else if (i == 1) { Ayar.unut(this); web.loadUrl(Ayar.ESLESTIR); }
            else if (i == 2) { try { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); } catch (Exception ignored) { } }
            else if (i == 3) finishAndRemoveTask();
        }).show();
    }

    @Override
    public void onBackPressed() { menu(); }

    @Override
    public boolean onKeyDown(int kod, KeyEvent e) {
        if (kod == KeyEvent.KEYCODE_MENU || kod == KeyEvent.KEYCODE_SETTINGS || kod == KeyEvent.KEYCODE_INFO) { menu(); return true; }
        return super.onKeyDown(kod, e);
    }

    private void tamEkran() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean odak) {
        super.onWindowFocusChanged(odak);
        if (odak) tamEkran();
    }

    @Override
    protected void onResume() { super.onResume(); web.onResume(); tamEkran(); }

    @Override
    protected void onPause() { web.onPause(); super.onPause(); }

    @Override
    protected void onDestroy() {
        zaman.removeCallbacksAndMessages(null);
        web.destroy();
        super.onDestroy();
    }
}
