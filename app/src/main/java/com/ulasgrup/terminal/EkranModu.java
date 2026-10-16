package com.ulasgrup.terminal;

import android.app.UiModeManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;

/**
 * Ekran modu (banko / depo TV'si): uygulama el terminali yerine bilgi ekranını tam ekran açar.
 * Adres eşleştirmeyle gelir (/ekran-eslestir: TV kod gösterir, kod panelde girilir) ve cihazda saklanır.
 * TV kutusunda (Android TV / dokunmatiksiz) ekran modu kendiliğinden açılır; elle kapatılırsa bir daha zorlanmaz.
 * Açılışta başlama: uygulama "ana ekran" (launcher) olarak seçilebilir (EkranAnaEkran takma adı) ve açılış alıcısı.
 */
final class EkranModu {
    static final String ALAN = "sistem.ulasgrup.com.tr";
    static final String ESLESTIR = "https://" + ALAN + "/ekran-eslestir";
    private static final String DOSYA = "ulas_ekran";

    private EkranModu() { }

    private static SharedPreferences t(Context c) { return c.getSharedPreferences(DOSYA, Context.MODE_PRIVATE); }

    /** TV kutusu mu: Android TV arayüzü ya da dokunmatik ekranı yok */
    static boolean tv(Context c) {
        try {
            UiModeManager u = (UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
            if (u != null && u.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) return true;
        } catch (Exception ignored) { }
        PackageManager pm = c.getPackageManager();
        return pm.hasSystemFeature("android.software.leanback") || !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
    }

    /** Ekran modu açık mı: adres kayıtlı ya da TV kutusu (elle kapatılmadıysa) */
    static boolean acik(Context c) {
        return adres(c) != null || (tv(c) && !t(c).getBoolean("kapatildi", false));
    }

    static String adres(Context c) {
        String a = t(c).getString("adres", null);
        return gecerli(a) ? a : null;
    }

    static boolean gecerli(String a) {
        return a != null && a.matches("^https://" + ALAN.replace(".", "\\.") + "/ekran/ek_[A-Za-z0-9]{40}$");
    }

    /** Açılış adresi: kayıtlı ekran ya da eşleştirme sayfası */
    static String acilis(Context c) {
        String a = adres(c);
        return a != null ? a : ESLESTIR;
    }

    static void kaydet(Context c, String adres) {
        t(c).edit().putString("adres", adres).putBoolean("kapatildi", false).apply();
        if (tv(c)) anaEkran(c, true);
    }

    /** Ekran adresi silinir (ekran silindi / yeniden eşleştirilecek) */
    static void unut(Context c) {
        t(c).edit().remove("adres").apply();
    }

    /** Ekran modundan çık: el terminali gibi açılır, ana ekran seçimi kaldırılır */
    static void kapat(Context c) {
        t(c).edit().remove("adres").putBoolean("kapatildi", true).apply();
        anaEkran(c, false);
    }

    /** "Ana ekran" takma adı: açıkken Android, Ana ekran tuşunda / açılışta bu uygulamayı başlatabilir */
    static void anaEkran(Context c, boolean ac) {
        try {
            c.getPackageManager().setComponentEnabledSetting(new ComponentName(c, "com.ulasgrup.terminal.EkranAnaEkran"),
                ac ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) { }
    }
}
