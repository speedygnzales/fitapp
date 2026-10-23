package ch.fitapp.privat;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Health Connect zeigt diese Seite, wenn du bei den Berechtigungen auf "Datenschutz" tippst.
 * Ohne sie lässt Health Connect die App gar nicht erst fragen.
 */
public class PermissionsRationaleActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF0E0E0E);
        getWindow().setNavigationBarColor(0xFF0E0E0E);
        int p = (int) (24 * getResources().getDisplayMetrics().density);
        TextView t = new TextView(this);
        t.setText("FitApp und deine Gesundheitsdaten\n\n"
                + "FitApp liest aus Health Connect nur diese Werte: Schritte, Schlaf, Puls, Ruhepuls, aktive Kalorien und Trainings.\n\n"
                + "Die Werte bleiben auf diesem Handy und werden nur in FitApp gespeichert, für deine Statistik. "
                + "FitApp schickt sie an niemanden und schreibt nichts in Health Connect zurück.\n\n"
                + "Den Zugriff kannst du jederzeit in Health Connect wieder entfernen.");
        t.setTextColor(0xFFFAFAFA);
        t.setTextSize(16);
        t.setLineSpacing(0, 1.3f);
        t.setPadding(p, p * 2, p, p);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF0E0E0E);
        sv.addView(t);
        setContentView(sv);
    }
}
