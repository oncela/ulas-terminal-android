package com.ulasgrup.ekran;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Cihaz açılınca (ya da uygulama güncellenince) bilgi ekranını başlatır */
public class AcilisAlici extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i == null ? null : i.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !"android.intent.action.QUICKBOOT_POWERON".equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        try {
            c.startActivity(new Intent(c, EkranActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (Exception ignored) { }
    }
}
