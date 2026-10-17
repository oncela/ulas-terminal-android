package com.ulasgrup.terminal;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;

/**
 * Kiosk modu (el terminali): panelden açılır (Cihazlar → cihaz ayarı). Açıkken:
 *  - uygulama "ana ekran" olarak seçilebilir (TerminalAnaEkran takma adı): Ana ekran tuşu ve açılış Ulaş Terminal'e döner,
 *  - cihaz açılınca uygulama kendiliğinden başlar,
 *  - uygulama cihaz sahibi (device owner) yapılmışsa ekran sabitlenir (lock task): durum çubuğu / son uygulamalar kapalı.
 * Geçici çıkış yalnız yöneticinin kullanıcı adı + PIN'iyle (sunucu doğrular) ya da Wi-Fi için kısa süre.
 */
final class Kiosk {
    private static final String DOSYA = "ulas_kiosk";

    private Kiosk() { }

    private static SharedPreferences t(Context c) { return c.getSharedPreferences(DOSYA, Context.MODE_PRIVATE); }

    static boolean acik(Context c) { return t(c).getBoolean("acik", false); }

    /** Geçici çıkış süresi içinde mi (yönetici çıkışı / Wi-Fi) */
    static boolean arada(Context c) { return System.currentTimeMillis() < t(c).getLong("ara_bitis", 0); }

    static void ara(Context c, int dakika) {
        t(c).edit().putLong("ara_bitis", System.currentTimeMillis() + Math.max(1, Math.min(dakika, 30)) * 60_000L).apply();
    }

    /** Açık/kapalı; değiştiyse true */
    static boolean ayarla(Context c, boolean a) {
        if (acik(c) == a) return false;
        t(c).edit().putBoolean("acik", a).remove("ara_bitis").apply();
        anaEkran(c, a);
        return true;
    }

    static void anaEkran(Context c, boolean ac) {
        try {
            c.getPackageManager().setComponentEnabledSetting(new ComponentName(c, "com.ulasgrup.terminal.TerminalAnaEkran"),
                ac ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) { }
    }

    /** Varsayılan ana ekran uygulaması bu mu */
    static boolean varsayilanAnaEkran(Context c) {
        try {
            ResolveInfo r = c.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
            return r != null && r.activityInfo != null && c.getPackageName().equals(r.activityInfo.packageName);
        } catch (Exception e) {
            return false;
        }
    }

    /** Ekran sabitleme: yalnız uygulama cihaz sahibiyse (aksi hâlde Android her seferinde onay ister; kullanılmaz) */
    static void sabitle(Activity a, boolean sabit) {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) a.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ActivityManager am = (ActivityManager) a.getSystemService(Context.ACTIVITY_SERVICE);
            boolean kilitli = am != null && am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
            if (sabit && !kilitli && dpm != null && dpm.isLockTaskPermitted(a.getPackageName())) a.startLockTask();
            else if (!sabit && kilitli) a.stopLockTask();
        } catch (Exception ignored) { }
    }
}
