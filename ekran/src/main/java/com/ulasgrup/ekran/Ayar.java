package com.ulasgrup.ekran;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

/** Kayıtlı ekran adresi (eşleştirmeyle gelir) ve "ana ekran" seçeneği */
final class Ayar {
    static final String ALAN = "sistem.ulasgrup.com.tr";
    static final String ESLESTIR = "https://" + ALAN + "/ekran-eslestir";

    private Ayar() { }

    private static SharedPreferences t(Context c) { return c.getSharedPreferences("ulas_ekran", Context.MODE_PRIVATE); }

    static boolean gecerli(String a) {
        return a != null && a.matches("^https://" + ALAN.replace(".", "\\.") + "/ekran/ek_[A-Za-z0-9]{40}$");
    }

    static String adres(Context c) {
        String a = t(c).getString("adres", null);
        return gecerli(a) ? a : null;
    }

    /** Açılış: kayıtlı ekran ya da eşleştirme sayfası */
    static String acilis(Context c) {
        String a = adres(c);
        return a != null ? a : ESLESTIR;
    }

    static void kaydet(Context c, String a) {
        t(c).edit().putString("adres", a).apply();
        anaEkran(c, true);
    }

    static void unut(Context c) {
        t(c).edit().remove("adres").apply();
    }

    /** Eşleştikten sonra uygulama ana ekran olarak seçilebilir (kutu açılınca doğrudan ekran) */
    static void anaEkran(Context c, boolean ac) {
        try {
            c.getPackageManager().setComponentEnabledSetting(new ComponentName(c, "com.ulasgrup.ekran.AnaEkran"),
                ac ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) { }
    }
}
