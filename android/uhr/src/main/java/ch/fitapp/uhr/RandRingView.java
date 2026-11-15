package ch.fitapp.uhr;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Ring am Rand des runden Bildschirms: Fortschritt im Training, Countdown in Pause und Halten. */
public class RandRingView extends View {

    private float wert, von, ziel;
    private int farbe = 0xFFD55181;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private ValueAnimator anim;

    public RandRingView(Context c) {
        super(c);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(Masse.dp(c, 5));
    }

    public void setDicke(float dp) {
        p.setStrokeWidth(Masse.dp(getContext(), dp));
        invalidate();
    }

    public void setFarbe(int f) {
        farbe = f;
        invalidate();
    }

    /** Sanft zum neuen Wert (0 bis 1) */
    public void setWert(float neu, boolean animiert) {
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
        anim.setDuration(450);
        anim.setInterpolator(new DecelerateInterpolator());
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
        float d = p.getStrokeWidth();
        float inset = d / 2f + Masse.dp(getContext(), 2);
        oval.set(inset, inset, getWidth() - inset, getHeight() - inset);
        p.setColor(0xFF1E1E1E);
        c.drawArc(oval, 0, 360, false, p);
        float v = Math.max(0f, Math.min(1f, wert));
        if (v > 0.001f) {
            p.setColor(farbe);
            c.drawArc(oval, -90, 360f * v, false, p);
        }
    }
}
