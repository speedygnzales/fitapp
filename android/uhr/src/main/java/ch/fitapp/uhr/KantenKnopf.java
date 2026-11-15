package ch.fitapp.uhr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * Hauptknopf am unteren Rand, dessen Unterseite dem runden Bildschirm folgt (Edge Button aus Wear OS Material 3).
 * Liegt in einer Ansicht über die ganze Breite, unten bündig.
 */
public class KantenKnopf extends View {

    private final int bildschirm;
    private String text = "";
    private int farbe = 0xFFD55181, textFarbe = 0xFFFFFFFF;
    private Drawable icon;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path form = new Path(), kreis = new Path(), oben = new Path();
    private final RectF r = new RectF();

    public KantenKnopf(Context c, int bildschirmPx) {
        super(c);
        bildschirm = bildschirmPx;
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        p.setTextSize(Masse.sp(c, 16));
    }

    public void setInhalt(String t, Drawable d, int f, int tf) {
        text = t == null ? "" : t;
        icon = d;
        farbe = f;
        textFarbe = tf;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float R = bildschirm / 2f - Masse.dp(getContext(), 3);
        float cy = h - bildschirm / 2f;
        kreis.reset();
        kreis.addCircle(w / 2f, cy, R, Path.Direction.CW);
        /* Breite oben so, dass die Ecken noch im Kreis liegen */
        float abstand = Math.abs(cy);
        float halb = (float) Math.sqrt(Math.max(0, R * R - abstand * abstand)) - Masse.dp(getContext(), 6);
        float rad = Masse.dp(getContext(), 26);
        oben.reset();
        r.set(w / 2f - halb, 0, w / 2f + halb, h + rad * 2);
        oben.addRoundRect(r, rad, rad, Path.Direction.CW);
        form.reset();
        form.op(oben, kreis, Path.Op.INTERSECT);
    }

    @Override
    protected void onDraw(Canvas c) {
        p.setColor(farbe);
        c.drawPath(form, p);
        float w = getWidth(), h = getHeight();
        float y = h * 0.44f;
        p.setColor(textFarbe);
        float tw = text.isEmpty() ? 0 : p.measureText(text);
        float is = Masse.dp(getContext(), 22);
        float gap = icon != null && !text.isEmpty() ? Masse.dp(getContext(), 6) : 0;
        float gesamt = (icon != null ? is : 0) + gap + tw;
        float x = w / 2f - gesamt / 2f;
        if (icon != null) {
            icon.setBounds((int) x, (int) (y - is / 2f), (int) (x + is), (int) (y + is / 2f));
            icon.setTint(textFarbe);
            icon.draw(c);
            x += is + gap;
        }
        if (!text.isEmpty()) {
            c.drawText(text, x + tw / 2f, y - (p.descent() + p.ascent()) / 2f, p);
        }
    }
}
