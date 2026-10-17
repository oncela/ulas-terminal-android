package com.ulasgrup.terminal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Cihaz açılınca: ekran modundaysa bilgi ekranını, kiosk modundaysa el terminali uygulamasını başlatır */
public class AcilisAlici extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (i == null || !(EkranModu.acik(c) || Kiosk.acik(c))) return;
        String a = i.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !"android.intent.action.QUICKBOOT_POWERON".equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        try {
            c.startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (Exception ignored) { }
    }
}
