package ch.fitapp.uhr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

/** Sieben Tage als Kreise: geschafft (weiss mit Haken), Freeze (blau), verpasst (dunkel), heute offen (Rand). */
public class WocheView extends View {

    private static final String[] TAGE = {"M", "D", "M", "D", "F", "S", "S"};
    private String[] codes = {"-", "-", "-", "-", "-", "-", "-"};
    private int heute = -1;
    private int akzent = 0xFFD55181, freeze = 0xFF3987E5;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint s = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path haken = new Path();

    public WocheView(Context c) {
        super(c);
        s.setTextAlign(Paint.Align.CENTER);
        s.setTextSize(Masse.sp(c, 10));
    }

    public void setTage(String[] c, int heuteIndex, int akzentFarbe, int freezeFarbe) {
        codes = c;
        heute = heuteIndex;
        akzent = akzentFarbe;
        freeze = freezeFarbe;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float spalte = w / 7f;
        float r = Math.min(spalte * 0.36f, Masse.dp(getContext(), 10));
        float cy = r + Masse.dp(getContext(), 2);
        for (int i = 0; i < 7; i++) {
            float cx = spalte * (i + 0.5f);
            String code = i < codes.length ? codes[i] : "-";
            p.setStyle(Paint.Style.FILL);
            if ("o".equals(code)) {
                p.setColor(0xFFFAFAFA);
                c.drawCircle(cx, cy, r, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Masse.dp(getContext(), 1.8f));
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                p.setColor(0xFF000000);
                haken.reset();
                haken.moveTo(cx - r * 0.45f, cy + r * 0.02f);
                haken.lineTo(cx - r * 0.1f, cy + r * 0.36f);
                haken.lineTo(cx + r * 0.48f, cy - r * 0.3f);
                c.drawPath(haken, p);
            } else if ("f".equals(code)) {
                p.setColor(Masse.alpha(freeze, 0x40));
                c.drawCircle(cx, cy, r, p);
                p.setColor(freeze);
                c.drawCircle(cx, cy, r * 0.32f, p);
            } else if ("m".equals(code)) {
                p.setColor(0xFF333333);
                c.drawCircle(cx, cy, r, p);
            } else if ("h".equals(code)) {
                p.setColor(Masse.alpha(akzent, 0x30));
                c.drawCircle(cx, cy, r, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Masse.dp(getContext(), 2));
                p.setColor(akzent);
                c.drawCircle(cx, cy, r - Masse.dp(getContext(), 1), p);
            } else {
                p.setColor(0xFF1A1A1A);
                c.drawCircle(cx, cy, r, p);
            }
            s.setColor(i == heute ? 0xFFFAFAFA : 0xFF7A7A7A);
            s.setTypeface(i == heute ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            c.drawText(TAGE[i], cx, h - Masse.sp(getContext(), 2), s);
        }
    }
}
