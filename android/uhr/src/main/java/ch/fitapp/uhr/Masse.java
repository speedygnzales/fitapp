package ch.fitapp.uhr;

import android.content.Context;
import android.util.TypedValue;

/** Umrechnung dp und sp in Pixel */
final class Masse {
    private Masse() {
    }

    static float dp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    static float sp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, c.getResources().getDisplayMetrics());
    }

    /** Farbe mit neuer Deckkraft, a von 0 bis 255 */
    static int alpha(int farbe, int a) {
        return (farbe & 0x00FFFFFF) | (a << 24);
    }
}
