package ch.fitapp.uhr;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Wochendiagramm Mo bis So: Balken wachsen beim Öffnen, Ziel als gestrichelte Linie, heute hervorgehoben. */
public class BalkenView extends View {

    private static final String[] TAGE = {"M", "D", "M", "D", "F", "S", "S"};
    private float[] werte = new float[7];
    private float[] soll;
    private float ziel;
    private int farbe = 0xFFFAFAFA;
    private int heute = -1;
    private float t = 1f;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linie = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint schrift = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private ValueAnimator anim;

    public BalkenView(Context c) {
        super(c);
        linie.setStyle(Paint.Style.STROKE);
        linie.setStrokeWidth(Masse.dp(c, 1));
        linie.setColor(0xFF6E6E6E);
        linie.setPathEffect(new DashPathEffect(new float[]{Masse.dp(c, 3), Masse.dp(c, 3)}, 0));
        schrift.setTextAlign(Paint.Align.CENTER);
        schrift.setTextSize(Masse.sp(c, 10));
    }

    /** werte: Float.NaN heisst noch kein Wert (Zukunft). soll darf null sein. ziel 0 heisst keine Linie. */
    public void setDaten(float[] w, float[] s, float z, int f, int heuteIndex) {
        werte = w;
        soll = s;
        ziel = z;
        farbe = f;
        heute = heuteIndex;
        if (anim != null) {
            anim.cancel();
        }
        t = 0f;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(650);
        anim.setStartDelay(120);
        anim.setInterpolator(new DecelerateInterpolator(1.4f));
        anim.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
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
        float w = getWidth(), h = getHeight();
        float labelH = Masse.sp(getContext(), 14);
        float oben = Masse.dp(getContext(), 2);
        float hoehe = h - labelH - oben;
        float max = ziel;
        for (int i = 0; i < 7; i++) {
            if (i < werte.length && !Float.isNaN(werte[i])) {
                max = Math.max(max, werte[i]);
            }
            if (soll != null && i < soll.length) {
                max = Math.max(max, soll[i]);
            }
        }
        if (max <= 0) {
            max = 1;
        }
        max *= 1.08f;
        float spalte = w / 7f;
        float bw = Math.min(spalte * 0.46f, Masse.dp(getContext(), 12));
        for (int i = 0; i < 7; i++) {
            float x = spalte * (i + 0.5f);
            /* Spur: geplant oder ganze Höhe */
            float spur = soll != null && i < soll.length ? soll[i] / max * hoehe : hoehe;
            if (spur > 0) {
                p.setColor(0xFF242424);
                r.set(x - bw / 2f, oben + hoehe - spur, x + bw / 2f, oben + hoehe);
                c.drawRoundRect(r, bw / 2f, bw / 2f, p);
            }
            float v = i < werte.length ? werte[i] : Float.NaN;
            if (!Float.isNaN(v) && v > 0) {
                float bh = Math.max(bw, v / max * hoehe * t);
                p.setColor(i == heute ? farbe : Masse.alpha(farbe, 0xC8));
                r.set(x - bw / 2f, oben + hoehe - bh, x + bw / 2f, oben + hoehe);
                c.drawRoundRect(r, bw / 2f, bw / 2f, p);
            }
            schrift.setColor(i == heute ? 0xFFFAFAFA : 0xFF8A8A8A);
            schrift.setTypeface(i == heute ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            c.drawText(TAGE[i], x, h - Masse.sp(getContext(), 2), schrift);
        }
        if (ziel > 0) {
            float y = oben + hoehe - ziel / max * hoehe;
            c.drawLine(0, y, w, y, linie);
        }
    }
}
