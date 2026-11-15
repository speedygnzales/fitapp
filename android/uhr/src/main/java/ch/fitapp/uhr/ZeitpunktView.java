package ch.fitapp.uhr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Linie mit Punkt für den Tagesplan als Zeitleiste */
public class ZeitpunktView extends View {

    private int farbe = 0xFF6E6E6E;
    private boolean jetzt, vorbei, erster, letzter;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ZeitpunktView(Context c) {
        super(c);
    }

    public void setZustand(int f, boolean istJetzt, boolean istVorbei, boolean istErster, boolean istLetzter) {
        farbe = f;
        jetzt = istJetzt;
        vorbei = istVorbei;
        erster = istErster;
        letzter = istLetzter;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f;
        float cy = Masse.dp(getContext(), 15);
        p.setStrokeWidth(Masse.dp(getContext(), 2));
        p.setColor(0xFF2A2A2A);
        if (!erster) {
            c.drawLine(cx, 0, cx, cy, p);
        }
        if (!letzter) {
            p.setColor(vorbei || jetzt ? 0xFF3A3A3A : 0xFF2A2A2A);
            c.drawLine(cx, cy, cx, getHeight(), p);
        }
        if (jetzt) {
            p.setColor(Masse.alpha(farbe, 0x40));
            c.drawCircle(cx, cy, Masse.dp(getContext(), 9), p);
            p.setColor(farbe);
            c.drawCircle(cx, cy, Masse.dp(getContext(), 5), p);
        } else {
            p.setColor(vorbei ? 0xFF4A4A4A : farbe);
            c.drawCircle(cx, cy, Masse.dp(getContext(), 3.5f), p);
        }
    }
}
