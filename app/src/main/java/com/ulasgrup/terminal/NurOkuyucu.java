package com.ulasgrup.terminal;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.nordicid.nurapi.AccBarcodeResult;
import com.nordicid.nurapi.AccBarcodeResultListener;
import com.nordicid.nurapi.AccessoryExtension;
import com.nordicid.nurapi.NurApi;
import com.nordicid.nurapi.NurApiAutoConnectTransport;
import com.nordicid.nurapi.NurApiErrors;
import com.nordicid.nurapi.NurApiListener;
import com.nordicid.nurapi.NurDeviceListActivity;
import com.nordicid.nurapi.NurDeviceSpec;
import com.nordicid.nurapi.NurEventAutotune;
import com.nordicid.nurapi.NurEventClientInfo;
import com.nordicid.nurapi.NurEventDeviceInfo;
import com.nordicid.nurapi.NurEventEpcEnum;
import com.nordicid.nurapi.NurEventFrequencyHop;
import com.nordicid.nurapi.NurEventIOChange;
import com.nordicid.nurapi.NurEventInventory;
import com.nordicid.nurapi.NurEventNxpAlarm;
import com.nordicid.nurapi.NurEventProgrammingProgress;
import com.nordicid.nurapi.NurEventTagTrackingChange;
import com.nordicid.nurapi.NurEventTagTrackingData;
import com.nordicid.nurapi.NurEventTraceTag;
import com.nordicid.nurapi.NurEventTriggeredRead;
import com.nordicid.nurapi.NurTag;
import com.nordicid.nurapi.NurTagStorage;

import java.util.HashSet;
import java.util.Set;

/**
 * Nordic ID okuyucusu (HH83/HH85 tümleşik ya da EXA). NUR API ile bağlanır; tetik tuşu basılınca
 * barkod modunda 2D okuyucuyu, RFID modunda envanter akışını çalıştırır. Okunan değerler Dinleyici'ye gider.
 * Okuyucu seçimi bir kez yapılır ve hatırlanır (cihaz servisini elle açmak gerekmez).
 */
public class NurOkuyucu {
    private static final String TAG = "UlasOkuyucu";
    private static final String TERCIH = "ulas_okuyucu";
    private static final int TETIK = 100;

    public interface Dinleyici {
        void okundu(String kod, String tur);
        void durumDegisti();
    }

    private final Context ctx;
    private final Dinleyici dinleyici;
    private final NurApi api = new NurApi();
    private final AccessoryExtension acc;
    private NurApiAutoConnectTransport baglanti;
    private volatile boolean bagli = false;
    private volatile boolean barkodVar = false;
    private volatile String cihazAdi = "";
    private volatile String mod = "barkod"; // barkod | rfid
    private volatile boolean tetikBasili = false;
    private volatile boolean barkodOkunuyor = false;
    private int rfidSayac = 0;
    private final Set<String> rfidGorulen = new HashSet<>();

    public NurOkuyucu(Context ctx, Dinleyici dinleyici) {
        this.ctx = ctx.getApplicationContext();
        this.dinleyici = dinleyici;
        acc = new AccessoryExtension(api);
        api.setListener(olaylar);
        acc.registerBarcodeResultListener(barkodSonucu);
    }

    /** Daha önce seçilen okuyucuya bağlanır; seçilmemişse false (seçim ekranı açılmalı) */
    public boolean baslat() {
        String spec = tercihler().getString("spec", null);
        if (spec == null) return false;
        try {
            NurDeviceSpec s = new NurDeviceSpec(spec);
            baglanti = NurDeviceSpec.createAutoConnectTransport(ctx, api, s);
            baglanti.setAddress(s.getAddress());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Bağlantı kurulamadı", e);
            return false;
        }
    }

    public void secimEkraniAc(Activity a) {
        NurDeviceListActivity.startDeviceRequest(a, api);
    }

    /** Seçim ekranından dönen sonuç: okuyucu kaydedilir ve bağlanılır */
    public boolean secimSonucu(int istek, int sonuc, Intent veri) {
        if (istek != NurDeviceListActivity.REQUEST_SELECT_DEVICE) return false;
        if (veri == null || sonuc != NurDeviceListActivity.RESULT_OK) return true;
        String spec = veri.getStringExtra(NurDeviceListActivity.SPECSTR);
        if (spec != null) {
            tercihler().edit().putString("spec", spec).apply();
            if (baglanti != null) baglanti.dispose();
            baslat();
        }
        return true;
    }

    public void unut() {
        tercihler().edit().remove("spec").apply();
        if (baglanti != null) { baglanti.dispose(); baglanti = null; }
    }

    public void setMod(String m) {
        mod = "rfid".equals(m) ? "rfid" : "barkod";
        rfidGorulen.clear();
        dinleyici.durumDegisti();
    }

    public void rfidTemizle() {
        rfidGorulen.clear();
        try { api.clearIdBuffer(); } catch (Exception ignored) { }
        rfidSayac = 0;
    }

    public void bip(int ms) {
        try { if (bagli && acc.isSupported()) acc.beepAsync(ms); } catch (Exception ignored) { }
    }

    /** Ekrandaki "okut" düğmesi için: tetik tuşu gibi tek okuma */
    public void tekOkuma() {
        try {
            if (!bagli) return;
            if ("rfid".equals(mod)) { rfidBaslat(); } else if (barkodVar && !barkodOkunuyor) { barkodOkunuyor = true; acc.readBarcodeAsync(5000); }
        } catch (Exception e) { Log.w(TAG, "okuma başlatılamadı", e); }
    }

    public void durdur() {
        try { if (api.isInventoryStreamRunning()) api.stopInventoryStream(); } catch (Exception ignored) { }
        try { if (barkodOkunuyor) acc.cancelBarcodeAsync(); } catch (Exception ignored) { }
        barkodOkunuyor = false;
        tetikBasili = false;
    }

    public void kapat() {
        durdur();
        if (baglanti != null) { baglanti.onDestroy(); baglanti = null; }
    }

    public void onResume() { if (baglanti != null) baglanti.onResume(); }
    public void onPause() { durdur(); if (baglanti != null) baglanti.onPause(); }

    public boolean isBagli() { return bagli; }
    public boolean isBarkodVar() { return barkodVar; }
    public String getCihazAdi() { return cihazAdi; }
    public String getMod() { return mod; }
    public boolean isSecildi() { return tercihler().contains("spec"); }

    private SharedPreferences tercihler() {
        return ctx.getSharedPreferences(TERCIH, Context.MODE_PRIVATE);
    }

    private void rfidBaslat() throws Exception {
        rfidSayac = 0;
        api.clearIdBuffer();
        api.startInventoryStream();
    }

    private final AccBarcodeResultListener barkodSonucu = new AccBarcodeResultListener() {
        public void onBarcodeResult(AccBarcodeResult r) {
            barkodOkunuyor = false;
            if (r.status == NurApiErrors.NUR_SUCCESS && r.strBarcode != null && r.strBarcode.trim().length() > 0) {
                try { acc.beepAsync(60); if (acc.getConfig().hasVibrator()) acc.vibrate(80); } catch (Exception ignored) { }
                dinleyici.okundu(r.strBarcode.trim(), "barkod");
            }
        }
    };

    private final NurApiListener olaylar = new NurApiListener() {
        public void connectedEvent() {
            bagli = true;
            try {
                if (acc.isSupported()) {
                    cihazAdi = acc.getConfig().name;
                    barkodVar = acc.getConfig().hasImagerScanner();
                } else {
                    cihazAdi = api.getReaderInfo().name;
                    barkodVar = false;
                }
            } catch (Exception e) {
                Log.w(TAG, "cihaz bilgisi okunamadı", e);
            }
            dinleyici.durumDegisti();
        }

        public void disconnectedEvent() {
            bagli = false;
            barkodVar = false;
            dinleyici.durumDegisti();
        }

        public void IOChangeEvent(NurEventIOChange e) {
            if (e.source != TETIK) return;
            try {
                if (e.direction == 1) {
                    tetikBasili = true;
                    if ("rfid".equals(mod)) rfidBaslat();
                    else if (barkodVar) acc.imagerAIM(true);
                } else {
                    tetikBasili = false;
                    if ("rfid".equals(mod)) {
                        if (api.isInventoryStreamRunning()) api.stopInventoryStream();
                    } else if (barkodVar && !barkodOkunuyor) {
                        acc.imagerAIM(false);
                        barkodOkunuyor = true;
                        acc.readBarcodeAsync(5000);
                    }
                }
            } catch (Exception ex) {
                Log.w(TAG, "tetik işlenemedi", ex);
            }
        }

        public void inventoryStreamEvent(NurEventInventory e) {
            try {
                if (e.stopped) {
                    // Akış ~20 sn sonra kendiliğinden durur; tetik basılıysa sürdürülür
                    if (tetikBasili && "rfid".equals(mod)) api.startInventoryStream();
                    return;
                }
                if (e.tagsAdded <= 0) return;
                NurTagStorage depo = api.getStorage();
                synchronized (depo) {
                    for (int i = rfidSayac; i < depo.size(); i++) {
                        NurTag t = depo.get(i);
                        String epc = NurApi.byteArrayToHexString(t.getEpc());
                        if (rfidGorulen.add(epc)) dinleyici.okundu(epc, "rfid");
                    }
                    rfidSayac = depo.size();
                }
                acc.beepAsync(20);
            } catch (Exception ex) {
                Log.w(TAG, "envanter işlenemedi", ex);
            }
        }

        public void triggeredReadEvent(NurEventTriggeredRead e) { }
        public void traceTagEvent(NurEventTraceTag e) { }
        public void programmingProgressEvent(NurEventProgrammingProgress e) { }
        public void nxpEasAlarmEvent(NurEventNxpAlarm e) { }
        public void logEvent(int level, String txt) { }
        public void inventoryExtendedStreamEvent(NurEventInventory e) { }
        public void frequencyHopEvent(NurEventFrequencyHop e) { }
        public void epcEnumEvent(NurEventEpcEnum e) { }
        public void deviceSearchEvent(NurEventDeviceInfo e) { }
        public void debugMessageEvent(String e) { }
        public void clientDisconnectedEvent(NurEventClientInfo e) { }
        public void clientConnectedEvent(NurEventClientInfo e) { }
        public void bootEvent(String e) { }
        public void autotuneEvent(NurEventAutotune e) { }
        public void tagTrackingScanEvent(NurEventTagTrackingData e) { }
        public void tagTrackingChangeEvent(NurEventTagTrackingChange e) { }
    };
}
