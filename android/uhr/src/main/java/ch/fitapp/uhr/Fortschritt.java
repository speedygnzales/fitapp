package ch.fitapp.uhr;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Schmaler Balken mit runden Enden, füllt sich animiert */
public class Fortschritt extends View {

    private float wert, von, ziel;
    private int farbe = 0xFFFAFAFA;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private ValueAnimator anim;

    public Fortschritt(Context c) {
        super(c);
    }

    public void setWert(float neu, int f, boolean animiert) {
        farbe = f;
        if (anim != null) {
            anim.cancel();
        }
        if (!animiert) {
            wert = neu;
            invalidate();
            return;
        }
        von = wert;
        ziel = neu;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(700);
        anim.setStartDelay(100);
        anim.setInterpolator(new DecelerateInterpolator(1.5f));
        anim.addUpdateListener(a -> {
            wert = von + (ziel - von) * (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) {
            anim.cancel();
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        float h = getHeight(), w = getWidth();
        r.set(0, 0, w, h);
        p.setColor(Masse.alpha(farbe, 0x38));
        c.drawRoundRect(r, h / 2f, h / 2f, p);
        float v = Math.max(0f, Math.min(1f, wert));
        if (v > 0.001f) {
            r.set(0, 0, Math.max(h, w * v), h);
            p.setColor(farbe);
            c.drawRoundRect(r, h / 2f, h / 2f, p);
        }
    }
}
