package com.ulasgrup.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;
import android.webkit.WebView;
import android.view.View;
import android.view.ViewGroup;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Emülatörde (okuyucusuz) uygulama sağlamlık testi: açılır, sistem sayfası yüklenir, UlasCihaz köprüsü sayfada
 * çalışır, arka plana alıp geri getirme (ekran kapanıp açılması) ve yeniden oluşturma çökme yapmaz.
 */
@RunWith(AndroidJUnit4.class)
public class UygulamaTesti {

    private static WebView webBul(View v) {
        if (v instanceof WebView) return (WebView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                WebView w = webBul(g.getChildAt(i));
                if (w != null) return w;
            }
        }
        return null;
    }

    private static String js(ActivityScenario<MainActivity> s, String kod) throws Exception {
        AtomicReference<String> sonuc = new AtomicReference<>();
        CountDownLatch bitti = new CountDownLatch(1);
        s.onActivity(a -> {
            WebView w = webBul(a.getWindow().getDecorView());
            assertNotNull("WebView yok", w);
            w.evaluateJavascript(kod, v -> { sonuc.set(v); bitti.countDown(); });
        });
        assertTrue("JS zaman aşımı", bitti.await(10, TimeUnit.SECONDS));
        return sonuc.get();
    }

    @Test
    public void acilir_sayfa_yuklenir_kopru_calisir_ve_cokmez() throws Exception {
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            // Sayfa (giriş ekranı) yüklenene kadar en fazla 40 sn
            String hazir = "\"\"";
            for (int i = 0; i < 80 && !"\"complete\"".equals(hazir); i++) {
                SystemClock.sleep(500);
                hazir = js(s, "document.readyState");
            }
            assertEquals("\"complete\"", hazir);
            assertEquals("\"object\"", js(s, "typeof window.UlasCihaz"));
            String durum = js(s, "window.UlasCihaz.durum()");
            JSONObject d = new JSONObject(new JSONObject("{\"v\":" + durum + "}").getString("v"));
            assertEquals(BuildConfig.VERSION_NAME, d.getString("uygulama"));
            assertTrue(d.has("bagli"));
            // Köprü yöntemleri okuyucu yokken çökmemeli
            js(s, "UlasCihaz.mod('rfid');UlasCihaz.mod('barkod');UlasCihaz.bip(20);UlasCihaz.oku();UlasCihaz.durdur();UlasCihaz.rfidTemizle();UlasCihaz.ekranAcik(true);UlasCihaz.ekranAcik(false);UlasCihaz.okutmaEkrani(true);UlasCihaz.geriBildirim(false,false);UlasCihaz.tasarruf(true);UlasCihaz.uyandirma(true);UlasCihaz.konus('deneme');UlasCihaz.yaz('zz');'ok'");
            // Ekran kapanıp açılması (arka plan → ön plan) ve yeniden oluşturma
            s.moveToState(Lifecycle.State.CREATED);
            SystemClock.sleep(1500);
            s.moveToState(Lifecycle.State.RESUMED);
            SystemClock.sleep(1000);
            s.recreate();
            SystemClock.sleep(3000);
            assertEquals("\"object\"", js(s, "typeof window.UlasCihaz"));
        }
    }
}
