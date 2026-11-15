package ch.fitapp.uhr;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Konzentrische Fortschrittsringe wie in Samsung Health, mit Symbol, grosser Zahl und Text in der Mitte. */
public class RingView extends View {

    private int[] farben = new int[0];
    private float[] ziel = new float[0];
    private float[] von = new float[0];
    private float t = 1f;
    private float dicke, luecke;
    private Drawable icon;
    private int iconFarbe;
    private String gross = "", klein = "";
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint schrift = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private ValueAnimator anim;

    public RingView(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        schrift.setTextAlign(Paint.Align.CENTER);
        schrift.setColor(0xFFFAFAFA);
        dicke = Masse.dp(c, 9);
        luecke = Masse.dp(c, 3);
    }

    public void setDicke(float dickeDp, float lueckeDp) {
        dicke = Masse.dp(getContext(), dickeDp);
        luecke = Masse.dp(getContext(), lueckeDp);
        invalidate();
    }

    /** werte von 0 bis 1 (mehr als 1 wird als voll gezeichnet). Animiert vom aktuellen Stand aus. */
    public void setRinge(int[] f, float[] werte, boolean animiert) {
        float[] jetzt = aktuell();
        farben = f;
        von = new float[werte.length];
        for (int i = 0; i < werte.length; i++) {
            von[i] = i < jetzt.length ? jetzt[i] : 0f;
        }
        ziel = werte.clone();
        if (anim != null) {
            anim.cancel();
        }
        if (!animiert) {
            t = 1f;
            invalidate();
            return;
        }
        t = 0f;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(800);
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    private float[] aktuell() {
        float[] r = new float[ziel.length];
        for (int i = 0; i < ziel.length; i++) {
            float v0 = i < von.length ? von[i] : 0f;
            r[i] = v0 + (ziel[i] - v0) * t;
        }
        return r;
    }

    public void setMitte(Drawable d, int farbe, String g, String k) {
        icon = d;
        iconFarbe = farbe;
        gross = g == null ? "" : g;
        klein = k == null ? "" : k;
        invalidate();
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
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f - dicke / 2f;
        float[] wert = aktuell();
        ring.setStrokeWidth(dicke);
        for (int i = 0; i < farben.length && r > dicke; i++) {
            oval.set(cx - r, cy - r, cx + r, cy + r);
            ring.setColor(Masse.alpha(farben[i], 0x40));
            c.drawArc(oval, 0, 360, false, ring);
            float v = i < wert.length ? Math.max(0f, Math.min(1f, wert[i])) : 0f;
            if (v > 0.002f) {
                ring.setColor(farben[i]);
                c.drawArc(oval, -90, 360f * v, false, ring);
            }
            r -= dicke + luecke;
        }
        /* Mitte: Symbol, Zahl, Text */
        float innen = r + dicke / 2f;
        float gs = Math.max(Masse.sp(getContext(), 14), innen * 0.46f);
        float ks = Math.max(Masse.sp(getContext(), 9), innen * 0.2f);
        float is = innen * (gross.isEmpty() ? 0.9f : 0.42f);
        float gesamt = (icon != null ? is + innen * 0.04f : 0) + (gross.isEmpty() ? 0 : gs) + (klein.isEmpty() ? 0 : ks * 1.3f);
        float y = cy - gesamt / 2f;
        if (icon != null) {
            icon.setBounds((int) (cx - is / 2f), (int) y, (int) (cx + is / 2f), (int) (y + is));
            icon.setTint(iconFarbe);
            icon.draw(c);
            y += is + innen * 0.04f;
        }
        if (!gross.isEmpty()) {
            schrift.setTextSize(gs);
            schrift.setTypeface(Typeface.DEFAULT_BOLD);
            schrift.setColor(0xFFFAFAFA);
            c.drawText(gross, cx, y + gs * 0.86f, schrift);
            y += gs;
        }
        if (!klein.isEmpty()) {
            schrift.setTextSize(ks);
            schrift.setTypeface(Typeface.DEFAULT);
            schrift.setColor(0xFFA3A3A3);
            c.drawText(klein, cx, y + ks * 1.15f, schrift);
        }
    }
}
