package com.ulasgrup.terminal;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Açılış ekranı: uygulama ikonu "nefes alır", arkasından kırmızı dalgalar yayılır; sayfa yüklenince yumuşakça kaybolur.
 * En az 5 sn görünür, en fazla 10 sn (bağlantı yoksa hata sayfası görünsün).
 */
public class AcilisEkrani extends FrameLayout {
    private static final int KIRMIZI = Color.rgb(215, 38, 30);
    private final long baslangic = SystemClock.uptimeMillis();
    private boolean kapaniyor = false;
    /** En az gösterim süresi (ms) */
    private static final long EN_AZ = 5000;

    public AcilisEkrani(Context c) {
        super(c);
        setBackgroundColor(Color.WHITE);
        setClickable(true);

        FrameLayout logoAlani = new FrameLayout(c);
        int alan = dp(220);
        Dalga dalga = new Dalga(c);
        logoAlani.addView(dalga, new LayoutParams(alan, alan, Gravity.CENTER));
        ImageView logo = new ImageView(c);
        logo.setImageResource(R.mipmap.ic_launcher);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logoAlani.addView(logo, new LayoutParams(dp(112), dp(112), Gravity.CENTER));

        TextView ad = new TextView(c);
        ad.setText("Ulaş Grup");
        ad.setTextColor(Color.rgb(17, 24, 39));
        ad.setTypeface(Typeface.DEFAULT_BOLD);
        ad.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        ad.setGravity(Gravity.CENTER);
        TextView alt = new TextView(c);
        alt.setText("El Terminali");
        alt.setTextColor(KIRMIZI);
        alt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        alt.setGravity(Gravity.CENTER);
        alt.setLetterSpacing(0.15f);

        LinearLayout orta = new LinearLayout(c);
        orta.setOrientation(LinearLayout.VERTICAL);
        orta.setGravity(Gravity.CENTER_HORIZONTAL);
        orta.addView(logoAlani, new LinearLayout.LayoutParams(alan, alan));
        orta.addView(ad);
        orta.addView(alt);
        addView(orta, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        TextView imza = new TextView(c);
        imza.setText("Uygulama geliştirici: Öncel Akgül");
        imza.setTextColor(Color.rgb(156, 163, 175));
        imza.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LayoutParams ip = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ip.bottomMargin = dp(28);
        addView(imza, ip);

        // Logo içeri süzülür, sonra nefes alır
        logo.setScaleX(0.6f); logo.setScaleY(0.6f); logo.setAlpha(0f);
        orta.setTranslationY(dp(16));
        logo.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(450).setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).withEndAction(() -> {
            ObjectAnimator x = ObjectAnimator.ofFloat(logo, "scaleX", 1f, 1.07f);
            ObjectAnimator y = ObjectAnimator.ofFloat(logo, "scaleY", 1f, 1.07f);
            for (ObjectAnimator a : new ObjectAnimator[]{x, y}) {
                a.setRepeatCount(ValueAnimator.INFINITE);
                a.setRepeatMode(ValueAnimator.REVERSE);
                a.setDuration(700);
                a.setInterpolator(new AccelerateDecelerateInterpolator());
            }
            AnimatorSet s = new AnimatorSet();
            s.playTogether(x, y);
            s.start();
        }).start();
        orta.animate().translationY(0).setDuration(500).start();
        ad.setAlpha(0f); alt.setAlpha(0f); imza.setAlpha(0f);
        ad.animate().alpha(1f).setStartDelay(250).setDuration(400).start();
        alt.animate().alpha(1f).setStartDelay(400).setDuration(400).start();
        imza.animate().alpha(1f).setStartDelay(600).setDuration(500).start();

        postDelayed(this::kapat, 10000);
    }

    public void kapat() {
        if (kapaniyor) return;
        long gecen = SystemClock.uptimeMillis() - baslangic;
        if (gecen < EN_AZ) { postDelayed(this::kapat, EN_AZ - gecen); return; }
        kapaniyor = true;
        animate().alpha(0f).scaleX(1.04f).scaleY(1.04f).setDuration(350).withEndAction(() -> {
            setVisibility(View.GONE);
            if (getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) getParent()).removeView(this);
        }).start();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Logodan dışa yayılan, solarak kaybolan kırmızı halkalar */
    private static class Dalga extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final long t0 = SystemClock.uptimeMillis();

        Dalga(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(KIRMIZI);
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float ic = getWidth() * 0.26f, dis = getWidth() * 0.5f;
            long t = SystemClock.uptimeMillis() - t0;
            for (int i = 0; i < 3; i++) {
                float f = ((t + i * 600) % 1800) / 1800f;
                p.setStrokeWidth(getWidth() * 0.02f * (1 - f) + 1);
                p.setAlpha((int) (110 * (1 - f)));
                c.drawCircle(cx, cy, ic + (dis - ic) * f, p);
            }
            postInvalidateOnAnimation();
        }
    }
}
