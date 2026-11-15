package ch.fitapp.uhr;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Pulskurve: weiche Linie mit Verlauf darunter, zeichnet sich beim Öffnen von links nach rechts. */
public class LinieView extends View {

    private int[] werte = new int[0];
    private int farbe = 0xFFE66767;
    private float t = 1f;
    private final Paint strich = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flaeche = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint punkt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path pfad = new Path();
    private final Path fuell = new Path();
    private ValueAnimator anim;

    public LinieView(Context c) {
        super(c);
        strich.setStyle(Paint.Style.STROKE);
        strich.setStrokeWidth(Masse.dp(c, 2));
        strich.setStrokeCap(Paint.Cap.ROUND);
        strich.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setWerte(int[] w, int f, boolean animiert) {
        werte = w == null ? new int[0] : w;
        farbe = f;
        strich.setColor(f);
        punkt.setColor(f);
        flaeche.setShader(null);
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
        anim.setDuration(900);
        anim.setInterpolator(new DecelerateInterpolator());
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
        int n = werte.length;
        if (n < 2) {
            return;
        }
        float w = getWidth(), h = getHeight();
        float rand = Masse.dp(getContext(), 4);
        int min = Integer.MAX_VALUE, max = 0;
        for (int v : werte) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        if (max - min < 10) {
            max = min + 10;
        }
        if (flaeche.getShader() == null) {
            flaeche.setShader(new LinearGradient(0, 0, 0, h, Masse.alpha(farbe, 0x55), Masse.alpha(farbe, 0x00), Shader.TileMode.CLAMP));
        }
        pfad.reset();
        float letzteX = 0, letzteY = 0;
        int bis = Math.max(2, Math.round(n * t));
        for (int i = 0; i < bis; i++) {
            float x = rand + (w - 2 * rand) * i / (n - 1f);
            float y = rand + (h - 2 * rand) * (1f - (werte[i] - min) / (float) (max - min));
            if (i == 0) {
                pfad.moveTo(x, y);
            } else {
                float mx = (letzteX + x) / 2f;
                pfad.cubicTo(mx, letzteY, mx, y, x, y);
            }
            letzteX = x;
            letzteY = y;
        }
        fuell.set(pfad);
        fuell.lineTo(letzteX, h);
        fuell.lineTo(rand, h);
        fuell.close();
        c.drawPath(fuell, flaeche);
        c.drawPath(pfad, strich);
        c.drawCircle(letzteX, letzteY, Masse.dp(getContext(), 3), punkt);
    }
}
