package ch.fitapp.uhr;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * FitApp auf der Galaxy Watch.
 * Aufbau nach Wear OS Material 3: Startseiten zum Wischen nach links und rechts (eine Aufgabe pro Seite, ohne Scrollen),
 * Unterseiten legen sich darüber und gehen mit einem Wisch nach rechts wieder zu. Schriftgrössen aus der offiziellen
 * Skala (Zahlen 40 bis 52 sp, Titel 16 bis 20 sp, Text mindestens 13 sp), Knöpfe 52 dp hoch, Ränder in Prozent.
 * Keine Bibliotheken ausser der Data Layer. Der Pulssensor läuft nur im Training.
 */
public class UhrActivity extends Activity implements SensorEventListener, DataClient.OnDataChangedListener {

    static final String PAKET = "/fitapp/paket";
    static final String VON_UHR = "/fitapp/uhr/";
    private static final int REQ_PULS = 7;
    private static final long SECHS_STUNDEN = 6L * 3600 * 1000;

    static final int BG = 0xFF000000, FLAECHE = 0xFF1E1E1E, FLAECHE2 = 0xFF2C2C2C, INK = 0xFFFAFAFA, MUTED = 0xFFB0B0B0, FAINT = 0xFF7A7A7A,
            TRAINING = 0xFFD55181, KCAL = 0xFFD95926, PROTEIN = 0xFF3987E5, PULS = 0xFFE66767, SCHLAF = 0xFF9085E9,
            FREI = 0xFF199E70, ARBEIT = 0xFF8A9BB0, PAUSE = 0xFFE6E6E6;

    private SharedPreferences prefs;
    private JSONObject paket, lauf, fertigInfo;
    private FrameLayout root;
    private final List<View> stapel = new ArrayList<>();
    private Pager home;
    private final List<Runnable> homeAnim = new ArrayList<>();
    private int W, H;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean erstesResume = true;

    /* Training */
    private WischRahmen trainingRahmen;
    private Pager trainingPager;
    private RandRingView rand;
    private FrameLayout satzSeite, pulsSeite;
    private TextView zeitView, pulsView, repView, kgView, pulsGross, pulsSchnitt, essenKopf;
    private LinieView pulsLinie;
    private int letzteSekunde = -1;
    private SensorManager sm;
    private boolean pulsAn;
    private int pulsJetzt;
    private long pulsZuletzt, verlaufZuletzt, beendenSeit;
    private final List<Integer> pulsLive = new ArrayList<>();
    private double wertW, wertR;
    private String wertFuer = "";

    // ------------------------------------------------------------------ Lebenszyklus

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("uhr", MODE_PRIVATE);
        DisplayMetrics m = getResources().getDisplayMetrics();
        W = m.widthPixels;
        H = m.heightPixels;
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);

        paket = json(prefs.getString("paket", null));
        lauf = json(prefs.getString("lauf", null));
        if (lauf != null && System.currentTimeMillis() - lauf.optLong("t0") > SECHS_STUNDEN) {
            lauf = null;
            prefs.edit().remove("lauf").apply();
        }
        homeBauen(0, true);
        if (lauf != null) {
            trainingOeffnen(false);
            pulsStarten();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            Wearable.getDataClient(this).addListener(this);
        } catch (Exception ignored) {
        }
        paketLaden();
        if (trainingRahmen != null) {
            h.post(tickRunner);
        } else if (stapel.size() == 1 && !erstesResume) {
            homeBauen(home != null ? home.getAktuell() : 0, false);
        }
        erstesResume = false;
    }

    @Override
    protected void onPause() {
        try {
            Wearable.getDataClient(this).removeListener(this);
        } catch (Exception ignored) {
        }
        h.removeCallbacks(tickRunner);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        pulsStoppen();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (stapel.size() > 1) {
            View oben = stapel.get(stapel.size() - 1);
            if (oben instanceof WischRahmen) {
                ((WischRahmen) oben).schliessen();
                return;
            }
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ Stapel: Unterseiten legen sich darüber

    private WischRahmen oeffnen(View inhalt) {
        final WischRahmen w = new WischRahmen(this);
        w.setBackgroundColor(BG);
        w.addView(inhalt, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        final View unten = stapel.isEmpty() ? null : stapel.get(stapel.size() - 1);
        w.setHoerer(new WischRahmen.Hoerer() {
            @Override
            public void zug(float a) {
                if (unten != null) {
                    float s = 0.92f + 0.08f * a;
                    unten.setScaleX(s);
                    unten.setScaleY(s);
                    unten.setAlpha(0.35f + 0.65f * a);
                }
            }

            @Override
            public void zu() {
                schliessen(w);
            }
        });
        root.addView(w, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        stapel.add(w);
        w.setTranslationX(W);
        w.animate().translationX(0).setDuration(300).setInterpolator(new DecelerateInterpolator(2f)).start();
        if (unten != null) {
            unten.animate().scaleX(0.92f).scaleY(0.92f).alpha(0.35f).setDuration(300).setInterpolator(new DecelerateInterpolator(2f)).start();
        }
        w.requestFocus();
        return w;
    }

    private void schliessen(WischRahmen w) {
        root.removeView(w);
        stapel.remove(w);
        if (w == trainingRahmen) {
            trainingRahmen = null;
            trainingPager = null;
            satzSeite = null;
            pulsSeite = null;
            h.removeCallbacks(tickRunner);
            bildschirmAn(false);
        }
        if (stapel.size() == 1) {
            /* Zurück auf den Startseiten: frisch aufbauen, damit Ringe und Zahlen stimmen */
            homeBauen(home != null ? home.getAktuell() : 0, false);
        } else if (!stapel.isEmpty()) {
            View unten = stapel.get(stapel.size() - 1);
            unten.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).start();
            unten.requestFocus();
        }
    }

    // ------------------------------------------------------------------ Paket vom Handy

    private void paketLaden() {
        try {
            Wearable.getDataClient(this).getDataItems().addOnSuccessListener(buf -> {
                try {
                    for (DataItem item : buf) {
                        if (PAKET.equals(item.getUri().getPath())) {
                            paketNeu(DataMapItem.fromDataItem(item).getDataMap().getString("json"));
                        }
                    }
                } finally {
                    buf.release();
                }
            });
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDataChanged(DataEventBuffer events) {
        for (DataEvent ev : events) {
            if (ev.getType() == DataEvent.TYPE_CHANGED && PAKET.equals(ev.getDataItem().getUri().getPath())) {
                final String j = DataMapItem.fromDataItem(ev.getDataItem()).getDataMap().getString("json");
                runOnUiThread(() -> paketNeu(j));
            }
        }
    }

    private void paketNeu(String j) {
        JSONObject p = json(j);
        if (p == null || (paket != null && p.optLong("ts") <= paket.optLong("ts"))) {
            return;
        }
        paket = p;
        prefs.edit().putString("paket", j).apply();
        if (stapel.size() <= 1) {
            homeBauen(home != null ? home.getAktuell() : 0, false);
        }
    }

    private boolean paketHeute() {
        return paket != null && heute().equals(paket.optString("tag"));
    }

    // ------------------------------------------------------------------ Startseiten

    private void homeBauen(int seite, boolean animiert) {
        Pager alt = home;
        home = new Pager(this);
        homeAnim.clear();
        if (paket == null) {
            home.addSeite(seiteWillkommen());
        } else {
            home.addSeite(seiteHeute());
            home.addSeite(seiteJetzt());
            home.addSeite(seiteTraining());
            home.addSeite(seiteEssen());
            home.addSeite(seiteKalorien());
            home.addSeite(seiteSaetze());
        }
        home.setHoerer(new Pager.Hoerer() {
            @Override
            public void seite(int i) {
                animiereSeite(i);
            }

            @Override
            public void zurueck() {
                finish();
            }
        });
        if (alt != null) {
            root.removeView(alt);
            stapel.remove(alt);
        }
        root.addView(home, 0, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        stapel.add(0, home);
        final int s = Math.max(0, Math.min(seite, home.anzahl() - 1));
        home.geheZu(s, false);
        if (stapel.size() == 1) {
            home.requestFocus();
        }
        if (animiert) {
            home.setAlpha(0f);
            home.animate().alpha(1f).setDuration(260).start();
        }
        home.post(() -> animiereSeite(s));
    }

    private void animiereSeite(int i) {
        if (i >= 0 && i < homeAnim.size() && homeAnim.get(i) != null) {
            homeAnim.get(i).run();
        }
    }

    /** Grundgerüst einer Seite ohne Scrollen: Inhalt mittig, Ränder in Prozent des Bildschirms */
    private FrameLayout seite(LinearLayout inhalt, boolean mitUhrzeit) {
        FrameLayout f = new FrameLayout(this);
        inhalt.setOrientation(LinearLayout.VERTICAL);
        inhalt.setGravity(Gravity.CENTER);
        inhalt.setPadding((int) (W * 0.12f), (int) (H * 0.11f), (int) (W * 0.12f), (int) (H * 0.11f));
        f.addView(inhalt, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        if (mitUhrzeit) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
            lp.topMargin = (int) (H * 0.045f);
            f.addView(uhrzeit(), lp);
        }
        return f;
    }

    private View seiteWillkommen() {
        LinearLayout c = new LinearLayout(this);
        c.addView(iconKreis(R.drawable.ic_hantel, TRAINING, 52));
        c.addView(abstand(12));
        c.addView(text("FitApp", 20, INK, 2));
        c.addView(abstand(6));
        c.addView(text("Öffne FitApp auf dem Handy. Dann kommen Plan, Training und Essen hierher.", 14, MUTED, 0));
        homeAnim.add(null);
        return seite(c, true);
    }

    /** Seite 1: drei Ringe wie Samsung Health, in der Mitte die Streak */
    private View seiteHeute() {
        FrameLayout f = new FrameLayout(this);
        JSONObject st = paket.optJSONObject("streak"), es = paket.optJSONObject("essen"), stat = paket.optJSONObject("stat");
        boolean frisch = paketHeute();
        int heuteI = wochentagIndex();
        int[] summe = essenSumme();
        JSONObject t = trainingHeute();
        boolean erledigt = t != null && trainingErledigt(t);
        int sz = stat != null && frisch ? zahlAus(stat.optJSONArray("sz"), heuteI) : 0;
        int soll = stat != null ? zahlAus(stat.optJSONArray("soll"), heuteI) : 0;
        int kz = es != null ? es.optInt("kz") : 0, prz = es != null ? es.optInt("prz") : 0;
        int streak = st != null ? st.optInt("n") : 0;

        final RingView ringe = new RingView(this);
        int g = (int) (Math.min(W, H) * 0.78f);
        ringe.setDicke(12f, 4f);
        ringe.setSchrift(42, 14, 24);
        ringe.setMitte(icon(R.drawable.ic_flamme), streak > 0 ? KCAL : FAINT, String.valueOf(streak),
                !frisch ? "Stand " + datumKurz(paket.optString("tag")) : streak == 1 ? "Tag Streak" : "Tage Streak");
        final float[] werte = {kz > 0 ? summe[0] / (float) kz : 0, prz > 0 ? summe[1] / (float) prz : 0, erledigt ? 1f : soll > 0 ? sz / (float) soll : 0f};
        final int[] farben = {KCAL, PROTEIN, TRAINING};
        ringe.setRinge(farben, new float[]{0, 0, 0}, false);
        f.addView(ringe, new FrameLayout.LayoutParams(g, g, Gravity.CENTER));
        klickbar(ringe, v -> home.geheZu(1, true));
        homeAnim.add(() -> {
            ringe.setRinge(farben, new float[]{0, 0, 0}, false);
            ringe.setRinge(farben, werte, true);
        });
        FrameLayout.LayoutParams ul = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        ul.topMargin = (int) (H * 0.025f);
        f.addView(uhrzeit(), ul);
        return f;
    }

    /** Seite 2: was gerade dran ist, mit Fortschritt und dem nächsten Punkt */
    private View seiteJetzt() {
        LinearLayout c = new LinearLayout(this);
        JSONArray items = heuteItems();
        int cur = items != null && items.length() > 0 ? aktuellerEintrag(items) : -1;
        JSONArray it = cur >= 0 ? items.optJSONArray(cur) : null;
        JSONArray n = items != null && cur + 1 < items.length() ? items.optJSONArray(cur + 1) : null;
        String art = it != null ? it.optString(2) : "schlaf";
        final int farbe = artFarbe(art);
        c.addView(iconKreis(artIcon(art), farbe, 32));
        c.addView(abstand(6));
        TextView titel = text(it != null ? it.optString(1) : "Noch Nacht. Schlaf weiter", 17, INK, 1);
        titel.setMaxLines(2);
        titel.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(titel);
        if (it != null && n != null) {
            int jetzt = minutenJetzt(), von = zuMinRel(items, cur), bis = zuMinRel(items, cur + 1);
            c.addView(abstand(4));
            c.addView(text("noch " + dauerText(bis - jetzt), 14, farbe, 2));
            final Fortschritt ff = new Fortschritt(this);
            LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams((int) (W * 0.42f), px(6));
            fl.topMargin = px(6);
            c.addView(ff, fl);
            final float anteil = bis > von ? (jetzt - von) / (float) (bis - von) : 0;
            ff.setWert(0, farbe, false);
            homeAnim.add(() -> {
                ff.setWert(0, farbe, false);
                ff.setWert(anteil, farbe, true);
            });
            c.addView(abstand(6));
            TextView d = text("Dann " + n.optString(0) + "  " + n.optString(1), 13, MUTED, 0);
            d.setMaxLines(1);
            d.setEllipsize(TextUtils.TruncateAt.END);
            c.addView(d);
        } else {
            homeAnim.add(null);
        }
        c.addView(abstand(6));
        c.addView(kompakt("Ganzer Tag", R.drawable.ic_uhrzeit, SCHLAF, v -> planOeffnen()));
        return seite(c, true);
    }

    /** Seite 3: das Training von heute mit grossem Start Knopf */
    private View seiteTraining() {
        LinearLayout c = new LinearLayout(this);
        homeAnim.add(null);
        if (lauf != null) {
            TextView k = text("LÄUFT", 12, TRAINING, 2);
            k.setLetterSpacing(0.1f);
            c.addView(k);
            c.addView(text("Training", 22, INK, 2));
            c.addView(text(lauf.optString("n") + " · " + satzZaehlen(lauf) + " von " + satzGesamtVon(lauf) + " Sätzen", 14, MUTED, 0));
            c.addView(abstand(12));
            c.addView(pille("Weiter", R.drawable.ic_play, TRAINING, v -> trainingOeffnen(true)));
            return seite(c, true);
        }
        JSONObject t = trainingHeute();
        if (t == null) {
            TextView k = text("HEUTE", 12, FAINT, 2);
            k.setLetterSpacing(0.1f);
            c.addView(k);
            c.addView(text("Frei", 22, INK, 2));
            c.addView(text("Kein Pflicht Training", 14, MUTED, 0));
            c.addView(abstand(12));
            c.addView(pille("Training wählen", R.drawable.ic_liste, FLAECHE2, v -> wahlOeffnen()));
            return seite(c, true);
        }
        boolean erledigt = trainingErledigt(t);
        int anzahl = t.optJSONArray("ex") != null ? t.optJSONArray("ex").length() : 0;
        TextView kopf = text(erledigt ? "✓ ERLEDIGT" : "TRAINING HEUTE", 12, erledigt ? FREI : TRAINING, 2);
        kopf.setLetterSpacing(0.1f);
        c.addView(kopf);
        c.addView(abstand(2));
        TextView n = text(t.optString("n"), 22, INK, 2);
        n.setMaxLines(1);
        c.addView(n);
        c.addView(text("etwa " + t.optInt("min") + " Min · " + anzahl + " Übungen", 14, MUTED, 0));
        c.addView(abstand(10));
        c.addView(pille(erledigt ? "Nochmals" : "Starten", R.drawable.ic_play, erledigt ? FLAECHE2 : TRAINING, v -> starten(t)));
        c.addView(abstand(6));
        JSONObject e = trainingVon(t.optString("er"));
        if (e != null && !erledigt) {
            c.addView(kompakt("Zuhause: " + e.optString("n"), R.drawable.ic_haus, MUTED, v -> starten(e)));
        } else {
            c.addView(kompakt("Andere", R.drawable.ic_liste, MUTED, v -> wahlOeffnen()));
        }
        return seite(c, true);
    }

    /** Seite 4: Kalorien als Ring am Rand, Protein als Balken, grosser Knopf zum Eintragen */
    private View seiteEssen() {
        FrameLayout f = new FrameLayout(this);
        JSONObject es = paket.optJSONObject("essen");
        int[] s = essenSumme();
        int kz = es != null ? es.optInt("kz") : 0, prz = es != null ? es.optInt("prz") : 0;
        final RandRingView ring = new RandRingView(this);
        ring.setFarbe(KCAL);
        ring.setDicke(8);
        f.addView(ring, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout c = new LinearLayout(this);
        c.addView(text("Kalorien", 13, KCAL, 1));
        c.addView(text(String.valueOf(s[0]), 42, INK, 2));
        c.addView(text("von " + kz + " kcal", 13, MUTED, 0));
        c.addView(abstand(6));
        final Fortschritt pb = new Fortschritt(this);
        c.addView(pb, new LinearLayout.LayoutParams((int) (W * 0.4f), px(6)));
        c.addView(abstand(4));
        c.addView(text(s[1] + " / " + prz + " g Protein", 13, PROTEIN, 1));
        c.addView(abstand(8));
        c.addView(pille("Eintragen", R.drawable.ic_plus, KCAL, v -> essenOeffnen()));
        final float kw = kz > 0 ? s[0] / (float) kz : 0, pw = prz > 0 ? s[1] / (float) prz : 0;
        pb.setWert(0, PROTEIN, false);
        homeAnim.add(() -> {
            ring.setWert(0, false);
            ring.setWert(kw, true);
            pb.setWert(0, PROTEIN, false);
            pb.setWert(pw, PROTEIN, true);
        });
        f.addView(seite(c, false), new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return f;
    }

    /** Seite 5: Kalorien der Woche */
    private View seiteKalorien() {
        JSONObject stat = paket.optJSONObject("stat"), es = paket.optJSONObject("essen");
        final int heuteI = wochentagIndex();
        LinearLayout c = new LinearLayout(this);
        c.addView(text("Kalorien Woche", 14, KCAL, 1));
        int avg = stat != null ? schnitt(stat.optJSONArray("kcal"), heuteI, paketHeute()) : 0;
        c.addView(text(avg > 0 ? String.valueOf(avg) : "noch offen", avg > 0 ? 34 : 20, INK, 2));
        c.addView(text("Ø kcal pro Tag · Ziel gestrichelt", 12, MUTED, 0));
        c.addView(abstand(8));
        final BalkenView b = new BalkenView(this);
        c.addView(b, new LinearLayout.LayoutParams((int) (W * 0.72f), (int) (H * 0.3f)));
        final float[] w = werte(stat != null ? stat.optJSONArray("kcal") : null);
        final int kz = es != null ? es.optInt("kz") : 0;
        homeAnim.add(() -> b.setDaten(w, null, kz, KCAL, heuteI));
        return seite(c, false);
    }

    /** Seite 6: Sätze der Woche gegen den Plan */
    private View seiteSaetze() {
        JSONObject stat = paket.optJSONObject("stat"), st = paket.optJSONObject("streak");
        final int heuteI = wochentagIndex();
        LinearLayout c = new LinearLayout(this);
        c.addView(text("Training Woche", 14, TRAINING, 1));
        int summe = stat != null ? summeBis(stat.optJSONArray("sz"), heuteI) : 0;
        c.addView(text(String.valueOf(summe), 34, INK, 2));
        c.addView(text("Sätze · grau ist der Plan", 12, MUTED, 0));
        c.addView(abstand(8));
        final BalkenView b = new BalkenView(this);
        c.addView(b, new LinearLayout.LayoutParams((int) (W * 0.72f), (int) (H * 0.3f)));
        final float[] w = werte(stat != null ? stat.optJSONArray("sz") : null);
        final float[] soll = werte(stat != null ? stat.optJSONArray("soll") : null);
        for (int i = 0; i < 7; i++) {
            if (Float.isNaN(soll[i])) {
                soll[i] = 0;
            }
        }
        homeAnim.add(() -> b.setDaten(w, soll, 0, TRAINING, heuteI));
        if (st != null && st.optInt("frz") > 0) {
            c.addView(abstand(4));
            c.addView(text(st.optInt("frz") + (st.optInt("frz") == 1 ? " Freeze" : " Freezes") + " auf Vorrat", 12, PROTEIN, 1));
        }
        return seite(c, false);
    }

    // ------------------------------------------------------------------ Unterseiten mit Liste

    /** Liste zum Scrollen: Uhrzeit und Titel oben, unten nur so viel Rand wie nötig, kein Nachfedern */
    private LinearLayout liste(String titel, int farbe, ScrollView[] aussen) {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setFocusable(true);
        sv.setFocusableInTouchMode(true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding((int) (W * 0.07f), (int) (H * 0.05f), (int) (W * 0.07f), (int) (H * 0.14f));
        box.addView(uhrzeit());
        if (titel != null) {
            TextView t = text(titel, 16, farbe, 2);
            t.setPadding(0, px(4), 0, px(6));
            box.addView(t);
        }
        sv.addView(box, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        aussen[0] = sv;
        return box;
    }

    private void listeOeffnen(ScrollView sv, LinearLayout box) {
        oeffnen(sv);
        sv.post(sv::requestFocus);
        int n = Math.min(box.getChildCount(), 9);
        for (int i = 0; i < n; i++) {
            View v = box.getChildAt(i);
            v.setAlpha(0f);
            v.setTranslationY(px(12));
            v.animate().alpha(1f).translationY(0).setStartDelay(90 + 35L * i).setDuration(260).setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    private void planOeffnen() {
        ScrollView[] sv = new ScrollView[1];
        LinearLayout box = liste("Tagesplan", SCHLAF, sv);
        box.addView(text(heuteArt(), 13, MUTED, 0));
        box.addView(abstand(8));
        JSONArray items = heuteItems();
        View ziel = null;
        if (items != null) {
            int cur = aktuellerEintrag(items);
            for (int i = 0; i < items.length(); i++) {
                JSONArray it = items.optJSONArray(i);
                if (it == null) {
                    continue;
                }
                boolean jetzt = i == cur, vorbei = i < cur;
                int farbe = artFarbe(it.optString(2));
                LinearLayout zeile = new LinearLayout(this);
                zeile.setOrientation(LinearLayout.HORIZONTAL);
                TextView z = text(it.optString(0), 13, jetzt ? INK : FAINT, jetzt ? 2 : 1);
                z.setGravity(Gravity.END);
                z.setPadding(0, px(7), 0, 0);
                zeile.addView(z, new LinearLayout.LayoutParams(px(44), LinearLayout.LayoutParams.WRAP_CONTENT));
                ZeitpunktView zp = new ZeitpunktView(this);
                zp.setZustand(farbe, jetzt, vorbei, i == 0, i == items.length() - 1);
                zeile.addView(zp, new LinearLayout.LayoutParams(px(24), LinearLayout.LayoutParams.MATCH_PARENT));
                LinearLayout spalte = new LinearLayout(this);
                spalte.setOrientation(LinearLayout.VERTICAL);
                spalte.setPadding(0, px(5), 0, px(12));
                if (jetzt) {
                    TextView j = text("JETZT", 11, farbe, 2);
                    j.setGravity(Gravity.START);
                    j.setLetterSpacing(0.1f);
                    spalte.addView(j);
                }
                TextView tx = text(it.optString(1), 14, vorbei ? FAINT : INK, jetzt ? 1 : 0);
                tx.setGravity(Gravity.START);
                spalte.addView(tx);
                zeile.addView(spalte, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                box.addView(zeile, volleBreite());
                if (jetzt) {
                    ziel = zeile;
                }
            }
        }
        listeOeffnen(sv[0], box);
        if (ziel != null) {
            final View zz = ziel;
            final ScrollView s = sv[0];
            s.postDelayed(() -> s.smoothScrollTo(0, Math.max(0, zz.getTop() - (int) (H * 0.3f))), 320);
        }
    }

    private void wahlOeffnen() {
        ScrollView[] sv = new ScrollView[1];
        LinearLayout box = liste("Training wählen", TRAINING, sv);
        JSONObject heute = trainingHeute();
        JSONArray ts = paket != null ? paket.optJSONArray("trainings") : null;
        for (int i = 0; ts != null && i < ts.length(); i++) {
            JSONObject t = ts.optJSONObject(i);
            if (t == null) {
                continue;
            }
            boolean istHeute = heute != null && heute.optString("id").equals(t.optString("id"));
            box.addView(chip(R.drawable.ic_hantel, istHeute ? TRAINING : MUTED, t.optString("n"), (istHeute ? "Heute · " : "") + t.optInt("min") + " Min", null, v -> starten(t)));
        }
        listeOeffnen(sv[0], box);
    }

    private void essenOeffnen() {
        ScrollView[] sv = new ScrollView[1];
        LinearLayout box = liste("Eintragen", KCAL, sv);
        essenKopf = text("", 14, MUTED, 0);
        box.addView(essenKopf);
        essenKopfSetzen();
        box.addView(abstand(4));
        JSONObject es = paket != null ? paket.optJSONObject("essen") : null;
        JSONArray fav = es != null ? es.optJSONArray("fav") : null;
        if (fav == null || fav.length() == 0) {
            box.addView(abstand(10));
            box.addView(text("Trag auf dem Handy etwas ein. Dann erscheint es hier.", 14, MUTED, 0));
        }
        for (int i = 0; fav != null && i < fav.length(); i++) {
            JSONObject f = fav.optJSONObject(i);
            if (f == null) {
                continue;
            }
            TextView kc = text(String.valueOf(f.optInt("kc")), 15, INK, 2);
            kc.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            box.addView(chip(f.optInt("fav") == 1 ? R.drawable.ic_stern : R.drawable.ic_essen, KCAL, f.optString("n"), f.optString("m"), kc, v -> essenEintragen(f, v)));
        }
        listeOeffnen(sv[0], box);
    }

    private void essenKopfSetzen() {
        JSONObject es = paket != null ? paket.optJSONObject("essen") : null;
        if (essenKopf == null || es == null) {
            return;
        }
        int[] s = essenSumme();
        essenKopf.setText(s[0] + " von " + es.optInt("kz") + " kcal · " + s[1] + " g Protein");
    }

    private void essenEintragen(JSONObject f, View chipView) {
        if (!chipView.isEnabled()) {
            return;
        }
        chipView.setEnabled(false);
        try {
            JSONObject ev = new JSONObject();
            ev.put("typ", "essen");
            ev.put("id", UUID.randomUUID().toString());
            ev.put("k", f.optString("k"));
            ev.put("m", mahlzeit());
            ev.put("tag", heute());
            senden(ev);
            JSONArray offen = offenHeute();
            offen.put(new JSONObject().put("t", System.currentTimeMillis()).put("kc", f.optInt("kc")).put("pr", f.optInt("pr")));
            prefs.edit().putString("offen", new JSONObject().put("tag", heute()).put("l", offen).toString()).apply();
        } catch (Exception ignored) {
        }
        vibrieren(new long[]{0, 30});
        /* Symbol wird zum Haken */
        if (chipView instanceof LinearLayout && ((LinearLayout) chipView).getChildAt(0) instanceof ImageView) {
            ImageView ic = (ImageView) ((LinearLayout) chipView).getChildAt(0);
            ic.setImageDrawable(icon(R.drawable.ic_check));
            ic.setColorFilter(FREI, PorterDuff.Mode.SRC_IN);
            ic.setBackground(oval(Masse.alpha(FREI, 0x38)));
            ic.setScaleX(0.5f);
            ic.setScaleY(0.5f);
            ic.animate().scaleX(1f).scaleY(1f).setDuration(340).setInterpolator(new OvershootInterpolator(2.4f)).start();
        }
        essenKopfSetzen();
        h.postDelayed(() -> chipView.setEnabled(true), 1500);
    }

    private JSONArray offenHeute() {
        JSONObject o = json(prefs.getString("offen", null));
        if (o == null || !heute().equals(o.optString("tag"))) {
            return new JSONArray();
        }
        JSONArray l = o.optJSONArray("l");
        return l != null ? l : new JSONArray();
    }

    /** Summe von heute: was das Handy kennt, plus was auf der Uhr seither eingetragen wurde. */
    private int[] essenSumme() {
        JSONObject es = paket != null ? paket.optJSONObject("essen") : null;
        boolean frisch = paketHeute();
        int k = frisch && es != null ? es.optInt("k") : 0, pr = frisch && es != null ? es.optInt("pr") : 0;
        long ts = frisch ? paket.optLong("ts") : 0;
        JSONArray o = offenHeute();
        for (int i = 0; i < o.length(); i++) {
            JSONObject e = o.optJSONObject(i);
            if (e != null && e.optLong("t") > ts) {
                k += e.optInt("kc");
                pr += e.optInt("pr");
            }
        }
        return new int[]{k, pr};
    }

    private String mahlzeit() {
        Calendar c = Calendar.getInstance();
        double s = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0;
        return s < 10 ? "morgen" : s < 14.5 ? "mittag" : s < 17 ? "snack" : s < 22 ? "abend" : "snack";
    }

    // ------------------------------------------------------------------ Training: eigene Seiten Satz, Puls, Optionen

    private void starten(JSONObject t) {
        if (t == null) {
            return;
        }
        try {
            lauf = new JSONObject();
            lauf.put("day", t.optString("id"));
            lauf.put("n", t.optString("n"));
            lauf.put("t0", System.currentTimeMillis());
            lauf.put("ex", t.optJSONArray("ex") != null ? t.optJSONArray("ex") : new JSONArray());
            lauf.put("i", 0);
            lauf.put("j", 0);
            lauf.put("sets", new JSONObject());
            lauf.put("phase", "satz");
            lauf.put("hr", new JSONArray());
            lauf.put("puls", new JSONObject().put("sum", 0).put("n", 0).put("max", 0).put("min", 0));
        } catch (Exception e) {
            return;
        }
        pulsLive.clear();
        laufSpeichern();
        /* Offene Auswahl zuerst schliessen, das Training legt sich direkt über die Startseiten */
        while (stapel.size() > 1) {
            View v = stapel.remove(stapel.size() - 1);
            root.removeView(v);
        }
        home.setScaleX(1f);
        home.setScaleY(1f);
        home.setAlpha(1f);
        pulsStarten();
        trainingOeffnen(true);
    }

    private void trainingOeffnen(boolean animiert) {
        if (lauf == null || trainingRahmen != null) {
            return;
        }
        FrameLayout f = new FrameLayout(this);
        rand = new RandRingView(this);
        rand.setDicke(6);
        f.addView(rand, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        trainingPager = new Pager(this);
        trainingPager.setPunkteOben(true);
        satzSeite = new FrameLayout(this);
        pulsSeite = new FrameLayout(this);
        trainingPager.addSeite(satzSeite);
        trainingPager.addSeite(pulsSeite);
        trainingPager.addSeite(optionenSeite());
        trainingPager.setHoerer(new Pager.Hoerer() {
            @Override
            public void seite(int i) {
                if (i == 1) {
                    pulsSeiteBauen();
                }
            }

            @Override
            public void zurueck() {
            }
        });
        f.addView(trainingPager, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        trainingRahmen = oeffnen(f);
        trainingRahmen.setInnen(trainingPager);
        if (!animiert) {
            trainingRahmen.animate().cancel();
            trainingRahmen.setTranslationX(0);
        }
        satzSeiteBauen(0);
        pulsSeiteBauen();
        trainingPager.requestFocus();
    }

    private JSONObject aktUebung() {
        JSONArray ex = lauf.optJSONArray("ex");
        int i = lauf.optInt("i");
        return ex != null && i < ex.length() ? ex.optJSONObject(i) : null;
    }

    /** richtung 1 von rechts, 0 einblenden */
    private void satzSeiteBauen(int richtung) {
        if (lauf == null || satzSeite == null) {
            return;
        }
        h.removeCallbacks(tickRunner);
        zeitView = null;
        pulsView = null;
        repView = null;
        kgView = null;
        letzteSekunde = -1;
        satzSeite.removeAllViews();
        JSONObject x = aktUebung();
        if (x == null) {
            beenden();
            return;
        }
        String phase = lauf.optString("phase", "satz");
        boolean timer = "pause".equals(phase) || "halten".equals(phase) || "bereit".equals(phase);
        int gesamt = Math.max(1, satzGesamtVon(lauf));
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding((int) (W * 0.1f), (int) (H * 0.1f), (int) (W * 0.1f), 0);
        satzSeite.addView(c, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        if (timer) {
            bildschirmAn(true);
            String label;
            int farbe;
            if ("pause".equals(phase)) {
                label = "PAUSE";
                farbe = PAUSE;
            } else if ("bereit".equals(phase)) {
                label = "BEREIT";
                farbe = TRAINING;
            } else {
                String teil = lauf.optString("teil");
                label = teil.length() > 0 ? teil.toUpperCase(Locale.GERMAN) : "HALTEN";
                farbe = TRAINING;
            }
            rand.setFarbe(farbe);
            c.addView(abstand(4));
            TextView l = text(label, 14, farbe, 2);
            l.setLetterSpacing(0.12f);
            c.addView(l);
            zeitView = text("", 50, INK, 2);
            zeitView.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            c.addView(zeitView);
            if ("pause".equals(phase)) {
                TextView nx = text("Dann: " + x.optString("n"), 15, INK, 1);
                nx.setMaxLines(1);
                nx.setEllipsize(TextUtils.TruncateAt.END);
                c.addView(nx);
                c.addView(abstand(6));
                c.addView(kompakt("+15 s", R.drawable.ic_plus, MUTED, v -> {
                    try {
                        lauf.put("bis", lauf.optLong("bis") + 15000);
                        lauf.put("dauer", lauf.optLong("dauer") + 15000);
                    } catch (Exception ignored) {
                    }
                    laufSpeichern();
                }));
                kante(satzSeite, "Weiter", R.drawable.ic_weiter, FLAECHE2, v -> phaseSatz());
            } else {
                TextView nx = text(x.optString("n"), 15, MUTED, 0);
                nx.setMaxLines(2);
                c.addView(nx);
                kante(satzSeite, "Stopp", R.drawable.ic_stopp, FLAECHE2, v -> phaseSatz());
            }
            h.post(tickRunner);
        } else {
            bildschirmAn(false);
            rand.setFarbe(TRAINING);
            rand.setWert(satzZaehlen(lauf) / (float) gesamt, true);
            int j = lauf.optInt("j"), s = x.optInt("s");
            /* Kopfzeile: Puls und Satz */
            LinearLayout kopf = new LinearLayout(this);
            kopf.setOrientation(LinearLayout.HORIZONTAL);
            kopf.setGravity(Gravity.CENTER);
            kopf.addView(icon(R.drawable.ic_herz, PULS, 14));
            pulsView = text(pulsJetzt > 0 ? " " + pulsJetzt : " ...", 14, PULS, 2);
            pulsView.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            kopf.addView(pulsView);
            TextView satz = text("    Satz " + (j + 1) + "/" + s, 14, TRAINING, 2);
            satz.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            kopf.addView(satz);
            c.addView(abstand(4));
            c.addView(kopf);
            final boolean kg = x.optInt("kg") == 1 && "Wdh".equals(x.optString("e"));
            TextView name = text(x.optString("n"), 17, INK, 2);
            name.setMaxLines(kg || !"Wdh".equals(x.optString("e")) ? 1 : 2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            c.addView(name);
            if (x.optString("z").length() > 0 && !kg && "Wdh".equals(x.optString("e"))) {
                TextView z = text(x.optString("z"), 13, MUTED, 0);
                z.setMaxLines(1);
                z.setEllipsize(TextUtils.TruncateAt.END);
                c.addView(z);
            }
            if ("Wdh".equals(x.optString("e"))) {
                werteVorbereiten(x, j);
                repView = text(fmt(wertR), 42, INK, 2);
                c.addView(stepper(repView, kg ? null : "Wdh", 48, () -> {
                    wertR = Math.max(0, wertR - 1);
                    repView.setText(fmt(wertR));
                }, () -> {
                    wertR = Math.min(200, wertR + 1);
                    repView.setText(fmt(wertR));
                }));
                if (kg) {
                    kgView = text(fmt(wertW) + " kg", 20, INK, 2);
                    c.addView(stepper(kgView, null, 40, () -> {
                        wertW = Math.max(0, wertW - (wertW > 20 ? 2.5 : 1));
                        kgView.setText(fmt(wertW) + " kg");
                    }, () -> {
                        wertW = Math.min(300, wertW + (wertW >= 20 ? 2.5 : 1));
                        kgView.setText(fmt(wertW) + " kg");
                    }));
                }
                kante(satzSeite, "Satz fertig", R.drawable.ic_check, TRAINING, v -> satzErledigt(kg ? fmt(wertW) : "", fmt(wertR)));
            } else {
                int sek = haltenSekunden(x, 0);
                c.addView(abstand(4));
                c.addView(text(dauer(sek), 42, INK, 2));
                int teile = teileAnzahl(x);
                c.addView(text(teile > 1 ? teile + " Teile, je " + dauer(sek) : "halten", 13, MUTED, 0));
                c.addView(abstand(4));
                c.addView(kompakt("Schon gemacht", R.drawable.ic_check, MUTED, v -> satzErledigt("", String.valueOf(x.optInt("so")))));
                kante(satzSeite, "Start", R.drawable.ic_play, TRAINING, v -> haltenStarten());
            }
        }
        /* Neuer Satz kommt sanft von rechts */
        c.setAlpha(0f);
        c.setTranslationX(richtung * px(24));
        c.animate().alpha(1f).translationX(0).setDuration(240).setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    private void pulsSeiteBauen() {
        if (pulsSeite == null || lauf == null) {
            return;
        }
        pulsSeite.removeAllViews();
        LinearLayout c = new LinearLayout(this);
        c.addView(text("Puls", 14, PULS, 1));
        pulsGross = text(pulsJetzt > 0 ? String.valueOf(pulsJetzt) : "...", 48, INK, 2);
        c.addView(pulsGross);
        c.addView(text("bpm", 13, MUTED, 0));
        c.addView(abstand(6));
        pulsLinie = new LinieView(this);
        pulsLinie.setWerte(liste(pulsLive), PULS, true);
        c.addView(pulsLinie, new LinearLayout.LayoutParams((int) (W * 0.66f), (int) (H * 0.2f)));
        c.addView(abstand(6));
        pulsSchnitt = text(pulsText(), 13, MUTED, 0);
        c.addView(pulsSchnitt);
        pulsSeite.addView(seite(c, false), new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private String pulsText() {
        JSONObject p = lauf != null ? lauf.optJSONObject("puls") : null;
        if (p == null || p.optInt("n") == 0) {
            return "Sensor misst, Uhr eng tragen";
        }
        return "Ø " + Math.round(p.optDouble("sum") / p.optInt("n")) + "  ·  max " + p.optInt("max");
    }

    private View optionenSeite() {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding((int) (W * 0.08f), (int) (H * 0.13f), (int) (W * 0.08f), (int) (H * 0.14f));
        box.addView(text("Training", 16, TRAINING, 2));
        box.addView(abstand(4));
        box.addView(chip(R.drawable.ic_weiter, MUTED, "Übung überspringen", null, null, v -> uebungUeberspringen()));
        final LinearLayout[] ende = new LinearLayout[1];
        ende[0] = chip(R.drawable.ic_ende, PULS, "Training beenden", "Speichert, was du hast", null, v -> {
            if (System.currentTimeMillis() - beendenSeit < 4000) {
                beenden();
            } else {
                beendenSeit = System.currentTimeMillis();
                TextView t = (TextView) ((LinearLayout) ende[0].getChildAt(1)).getChildAt(0);
                t.setText("Nochmals tippen");
                vibrieren(new long[]{0, 30});
            }
        });
        box.addView(ende[0]);
        box.addView(chip(R.drawable.ic_haus, MUTED, "Zur Übersicht", "Training läuft weiter", null, v -> {
            if (trainingRahmen != null) {
                trainingRahmen.schliessen();
            }
        }));
        sv.addView(box, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    private void werteVorbereiten(JSONObject x, int j) {
        String key = lauf.optInt("i") + ":" + j;
        if (key.equals(wertFuer)) {
            return;
        }
        wertFuer = key;
        String w = "", r = "";
        JSONArray v = x.optJSONArray("v");
        JSONArray sv = v != null && v.length() > 0 ? v.optJSONArray(Math.min(j, v.length() - 1)) : null;
        if (sv != null) {
            w = sv.optString(0);
            r = sv.optString(1);
        }
        JSONObject sets = lauf.optJSONObject("sets");
        JSONArray schon = sets != null ? sets.optJSONArray(x.optString("id")) : null;
        if (schon != null && schon.length() > 0 && schon.optJSONArray(schon.length() - 1) != null) {
            String lw = schon.optJSONArray(schon.length() - 1).optString(0);
            if (lw.length() > 0) {
                w = lw;
            }
        }
        wertW = zahl(w, 0);
        wertR = zahl(r, x.optInt("so", 10));
    }

    private int haltenSekunden(JSONObject x, int teil) {
        if ("Min".equals(x.optString("e"))) {
            return Math.max(1, x.optInt("so", 1)) * 60;
        }
        JSONArray t = x.optJSONArray("t");
        JSONArray tt = t != null && teil < t.length() ? t.optJSONArray(teil) : null;
        int s = tt != null ? tt.optInt(1) : 0;
        return s > 0 ? s : Math.max(1, x.optInt("so", 30));
    }

    private int teileAnzahl(JSONObject x) {
        if ("Min".equals(x.optString("e"))) {
            return 1;
        }
        JSONArray t = x.optJSONArray("t");
        return t != null && t.length() > 0 ? t.length() : 1;
    }

    private void timerSetzen(String phase, long ms) {
        try {
            lauf.put("phase", phase);
            lauf.put("bis", System.currentTimeMillis() + ms);
            lauf.put("dauer", ms);
            lauf.put("piep", -1);
        } catch (Exception ignored) {
        }
    }

    private void haltenStarten() {
        try {
            lauf.put("k", 0);
            lauf.put("teil", "");
        } catch (Exception ignored) {
        }
        timerSetzen("bereit", 3000);
        laufSpeichern();
        satzSeiteBauen(0);
    }

    /* Zehnmal pro Sekunde, damit der Ring am Rand weich läuft. Der Text ändert sich nur bei neuer Sekunde. */
    private final Runnable tickRunner = new Runnable() {
        @Override
        public void run() {
            if (lauf == null || zeitView == null || rand == null) {
                return;
            }
            long rest = lauf.optLong("bis") - System.currentTimeMillis();
            if (rest <= 0) {
                timerEnde();
                return;
            }
            long dauer = Math.max(1, lauf.optLong("dauer", rest));
            rand.setWert(rest / (float) dauer, false);
            int s = (int) Math.ceil(rest / 1000.0);
            if (s != letzteSekunde) {
                letzteSekunde = s;
                boolean bereit = "bereit".equals(lauf.optString("phase"));
                zeitView.setText(bereit ? String.valueOf(s) : dauer(s));
                if (bereit) {
                    zeitView.setScaleX(1.4f);
                    zeitView.setScaleY(1.4f);
                    zeitView.animate().scaleX(1f).scaleY(1f).setDuration(360).setInterpolator(new DecelerateInterpolator()).start();
                }
                if (!"pause".equals(lauf.optString("phase")) && s <= 3 && s != lauf.optInt("piep", -1)) {
                    try {
                        lauf.put("piep", s);
                    } catch (Exception ignored) {
                    }
                    vibrieren(new long[]{0, 40});
                }
            }
            h.postDelayed(this, 100);
        }
    };

    private void timerEnde() {
        String phase = lauf.optString("phase");
        JSONObject x = aktUebung();
        if (x == null) {
            beenden();
            return;
        }
        try {
            if ("bereit".equals(phase)) {
                lauf.put("teil", teilName(x, 0));
                timerSetzen("halten", haltenSekunden(x, 0) * 1000L);
                vibrieren(new long[]{0, 200});
            } else if ("halten".equals(phase)) {
                int k = lauf.optInt("k") + 1;
                if (k < teileAnzahl(x)) {
                    lauf.put("k", k);
                    lauf.put("teil", teilName(x, k));
                    timerSetzen("halten", (5 + haltenSekunden(x, k)) * 1000L);
                    vibrieren(new long[]{0, 200, 120, 200});
                } else {
                    laufSpeichern();
                    satzErledigt("", String.valueOf(x.optInt("so")));
                    return;
                }
            } else {
                vibrieren(new long[]{0, 350, 150, 350, 150, 350});
                lauf.put("phase", "satz");
            }
        } catch (Exception ignored) {
        }
        laufSpeichern();
        satzSeiteBauen(0);
    }

    private String teilName(JSONObject x, int k) {
        JSONArray t = x.optJSONArray("t");
        JSONArray tt = t != null && k < t.length() ? t.optJSONArray(k) : null;
        return tt != null ? tt.optString(0) : "";
    }

    private void phaseSatz() {
        try {
            lauf.put("phase", "satz");
        } catch (Exception ignored) {
        }
        laufSpeichern();
        satzSeiteBauen(1);
    }

    private void satzErledigt(String w, String r) {
        JSONObject x = aktUebung();
        if (x == null) {
            return;
        }
        boolean fertig = false;
        try {
            JSONObject sets = lauf.optJSONObject("sets");
            JSONArray a = sets.optJSONArray(x.optString("id"));
            if (a == null) {
                a = new JSONArray();
                sets.put(x.optString("id"), a);
            }
            a.put(new JSONArray().put(w).put(r));
            vibrieren(new long[]{0, 30});
            int j = lauf.optInt("j") + 1, i = lauf.optInt("i");
            int pause = x.optInt("p");
            if (j >= x.optInt("s")) {
                i++;
                j = 0;
            }
            lauf.put("i", i);
            lauf.put("j", j);
            if (i >= lauf.optJSONArray("ex").length()) {
                fertig = true;
            } else if (pause > 0) {
                timerSetzen("pause", pause * 1000L);
            } else {
                lauf.put("phase", "satz");
            }
        } catch (Exception ignored) {
        }
        laufSpeichern();
        if (fertig) {
            beenden();
        } else {
            satzSeiteBauen(1);
        }
    }

    private void uebungUeberspringen() {
        try {
            lauf.put("i", lauf.optInt("i") + 1);
            lauf.put("j", 0);
            lauf.put("phase", "satz");
        } catch (Exception ignored) {
        }
        laufSpeichern();
        if (aktUebung() == null) {
            beenden();
        } else {
            satzSeiteBauen(1);
            if (trainingPager != null) {
                trainingPager.geheZu(0, true);
            }
        }
    }

    private void beenden() {
        pulsStoppen();
        bildschirmAn(false);
        h.removeCallbacks(tickRunner);
        JSONObject l = lauf;
        lauf = null;
        prefs.edit().remove("lauf").apply();
        int fertig = satzZaehlen(l);
        /* Training Bildschirm weg, darunter frische Startseiten auf der Seite Training */
        if (trainingRahmen != null) {
            root.removeView(trainingRahmen);
            stapel.remove(trainingRahmen);
            trainingRahmen = null;
            trainingPager = null;
            satzSeite = null;
            pulsSeite = null;
        }
        if (l != null && fertig > 0) {
            try {
                long t0 = l.optLong("t0"), jetzt = System.currentTimeMillis();
                JSONObject ev = new JSONObject();
                ev.put("typ", "training");
                ev.put("id", UUID.randomUUID().toString());
                ev.put("day", l.optString("day"));
                ev.put("start", iso(t0));
                ev.put("ende", iso(jetzt));
                ev.put("min", Math.max(1, Math.round((jetzt - t0) / 60000.0)));
                JSONObject sets = new JSONObject();
                JSONObject ls = l.optJSONObject("sets");
                JSONArray names = ls != null ? ls.names() : null;
                for (int i = 0; names != null && i < names.length(); i++) {
                    JSONArray a = ls.optJSONArray(names.optString(i));
                    JSONArray b = new JSONArray();
                    for (int k = 0; a != null && k < a.length(); k++) {
                        JSONArray st = a.optJSONArray(k);
                        b.put(new JSONObject().put("w", st.optString(0)).put("r", st.optString(1)));
                    }
                    sets.put(names.optString(i), b);
                }
                ev.put("sets", sets);
                JSONObject p = l.optJSONObject("puls");
                JSONObject puls = null;
                if (p != null && p.optInt("n") > 0) {
                    puls = new JSONObject().put("avg", Math.round(p.optDouble("sum") / p.optInt("n"))).put("max", p.optInt("max")).put("min", p.optInt("min"));
                    ev.put("puls", puls);
                }
                senden(ev);
                String erledigt = prefs.getString("erledigt", "");
                prefs.edit().putString("erledigt", heute() + ":" + l.optString("day") + ";" + (erledigt.startsWith(heute()) ? erledigt : "")).apply();
                fertigInfo = new JSONObject().put("n", l.optString("n")).put("f", fertig).put("s", satzGesamtVon(l)).put("min", ev.optInt("min"));
                if (puls != null) {
                    fertigInfo.put("avg", puls.optInt("avg")).put("max", puls.optInt("max"));
                }
                fertigInfo.put("hr", l.optJSONArray("hr") != null ? l.optJSONArray("hr") : new JSONArray());
                vibrieren(new long[]{0, 120, 80, 120, 80, 300});
            } catch (Exception ignored) {
            }
        }
        homeBauen(2, false);
        home.setScaleX(1f);
        home.setScaleY(1f);
        home.setAlpha(1f);
        if (l != null && fertig > 0) {
            fertigOeffnen();
        }
    }

    private void fertigOeffnen() {
        JSONObject f = fertigInfo;
        ScrollView[] sv = new ScrollView[1];
        LinearLayout box = liste(null, INK, sv);
        float anteil = f != null && f.optInt("s") > 0 ? f.optInt("f") / (float) f.optInt("s") : 1f;
        final RingView r = new RingView(this);
        int g = (int) (W * 0.42f);
        r.setLayoutParams(new LinearLayout.LayoutParams(g, g));
        r.setDicke(9, 0);
        r.setSchrift(0, 0, 36);
        r.setMitte(icon(R.drawable.ic_check), anteil >= 1f ? INK : MUTED, "", "");
        r.setRinge(new int[]{TRAINING}, new float[]{0}, false);
        box.addView(r);
        box.addView(abstand(6));
        box.addView(text(anteil >= 1f ? "Geschafft" : "Gespeichert", 20, INK, 2));
        if (f != null) {
            box.addView(text(f.optString("n"), 14, MUTED, 0));
            LinearLayout k = karte();
            k.addView(tabellenZeile("Sätze", f.optInt("f") + " / " + f.optInt("s"), INK));
            k.addView(tabellenZeile("Dauer", f.optInt("min") + " Min", INK));
            if (f.optInt("avg") > 0) {
                k.addView(tabellenZeile("Puls Ø", f.optInt("avg") + " bpm", PULS));
                k.addView(tabellenZeile("Puls max", f.optInt("max") + " bpm", PULS));
            }
            JSONArray hr = f.optJSONArray("hr");
            if (hr != null && hr.length() >= 3) {
                TextView t = text("Pulsverlauf", 12, FAINT, 0);
                t.setGravity(Gravity.START);
                t.setPadding(0, px(8), 0, px(2));
                k.addView(t);
                LinieView lv = new LinieView(this);
                int[] w = new int[hr.length()];
                for (int i = 0; i < w.length; i++) {
                    w[i] = hr.optInt(i);
                }
                lv.setWerte(w, PULS, true);
                k.addView(lv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(52)));
            }
            box.addView(k);
            box.addView(text("Geht ans Handy, sobald es in der Nähe ist.", 12, FAINT, 0));
        }
        box.addView(abstand(6));
        box.addView(pille("Fertig", R.drawable.ic_check, TRAINING, v -> {
            View oben = stapel.get(stapel.size() - 1);
            if (oben instanceof WischRahmen) {
                ((WischRahmen) oben).schliessen();
            }
        }));
        listeOeffnen(sv[0], box);
        final float a = anteil;
        r.postDelayed(() -> r.setRinge(new int[]{TRAINING}, new float[]{a}, true), 280);
    }

    private void laufSpeichern() {
        if (lauf != null) {
            prefs.edit().putString("lauf", lauf.toString()).apply();
        }
    }

    private int satzZaehlen(JSONObject l) {
        JSONObject s = l == null ? null : l.optJSONObject("sets");
        int n = 0;
        JSONArray names = s != null ? s.names() : null;
        for (int i = 0; names != null && i < names.length(); i++) {
            JSONArray a = s.optJSONArray(names.optString(i));
            n += a != null ? a.length() : 0;
        }
        return n;
    }

    private int satzGesamtVon(JSONObject l) {
        JSONArray ex = l == null ? null : l.optJSONArray("ex");
        int n = 0;
        for (int i = 0; ex != null && i < ex.length(); i++) {
            JSONObject e = ex.optJSONObject(i);
            n += e != null ? e.optInt("s") : 0;
        }
        return n;
    }

    // ------------------------------------------------------------------ Puls, nur während des Trainings

    private void pulsStarten() {
        if (pulsAn || sm == null) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BODY_SENSORS}, REQ_PULS);
            return;
        }
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_HEART_RATE);
        if (s != null) {
            pulsAn = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    private void pulsStoppen() {
        if (pulsAn && sm != null) {
            sm.unregisterListener(this);
        }
        pulsAn = false;
        pulsJetzt = 0;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == REQ_PULS && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && lauf != null) {
            pulsStarten();
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() != Sensor.TYPE_HEART_RATE || e.values.length == 0) {
            return;
        }
        int v = Math.round(e.values[0]);
        if (v < 30 || v > 230 || e.accuracy < SensorManager.SENSOR_STATUS_ACCURACY_LOW) {
            return;
        }
        pulsJetzt = v;
        if (pulsView != null) {
            pulsView.setText(" " + v);
        }
        if (pulsGross != null) {
            pulsGross.setText(String.valueOf(v));
        }
        long jetzt = System.currentTimeMillis();
        if (lauf == null || jetzt - pulsZuletzt < 2000) {
            return;
        }
        /* Höchstens alle 2 Sekunden ein Wert für Schnitt und Live Kurve, alle 10 Sekunden einer für den Verlauf */
        pulsZuletzt = jetzt;
        pulsLive.add(v);
        if (pulsLive.size() > 90) {
            pulsLive.remove(0);
        }
        if (pulsLinie != null) {
            pulsLinie.setWerte(liste(pulsLive), PULS, false);
        }
        JSONObject p = lauf.optJSONObject("puls");
        try {
            if (p != null) {
                p.put("sum", p.optDouble("sum") + v);
                p.put("n", p.optInt("n") + 1);
                p.put("max", Math.max(p.optInt("max"), v));
                p.put("min", p.optInt("min") == 0 ? v : Math.min(p.optInt("min"), v));
            }
            JSONArray hr = lauf.optJSONArray("hr");
            if (hr != null && jetzt - verlaufZuletzt >= 10000 && hr.length() < 400) {
                verlaufZuletzt = jetzt;
                hr.put(v);
            }
        } catch (Exception ignored) {
        }
        if (pulsSchnitt != null) {
            pulsSchnitt.setText(pulsText());
        }
        if (p != null && p.optInt("n") % 15 == 0) {
            laufSpeichern();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    // ------------------------------------------------------------------ Senden an das Handy

    private void senden(JSONObject ev) {
        try {
            PutDataMapRequest r = PutDataMapRequest.create(VON_UHR + ev.getString("id"));
            r.getDataMap().putString("json", ev.toString());
            r.getDataMap().putLong("t", System.currentTimeMillis());
            PutDataRequest p = r.asPutDataRequest();
            p.setUrgent();
            Wearable.getDataClient(this).putDataItem(p);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ Plan und Datum

    private JSONObject trainingVon(String id) {
        JSONArray ts = paket != null ? paket.optJSONArray("trainings") : null;
        for (int i = 0; ts != null && i < ts.length(); i++) {
            JSONObject t = ts.optJSONObject(i);
            if (t != null && id != null && id.length() > 0 && id.equals(t.optString("id"))) {
                return t;
            }
        }
        return null;
    }

    /** Das Training laut Plan. Ist das Paket von heute, gilt die Wahl des Handys (auch Nachholen am Sonntag). */
    private JSONObject trainingHeute() {
        if (paket == null) {
            return null;
        }
        if (paketHeute()) {
            JSONObject t = trainingVon(paket.optString("pid"));
            if (t != null) {
                return t;
            }
        }
        int wd = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1;
        JSONArray ts = paket.optJSONArray("trainings");
        for (int i = 0; ts != null && i < ts.length(); i++) {
            JSONObject t = ts.optJSONObject(i);
            JSONArray w = t != null ? t.optJSONArray("wds") : null;
            for (int k = 0; w != null && k < w.length(); k++) {
                if (w.optInt(k) == wd) {
                    return t;
                }
            }
        }
        return null;
    }

    private boolean trainingErledigt(JSONObject t) {
        String id = t.optString("id");
        if (paketHeute() && t.optBoolean("ok")) {
            return true;
        }
        String e = prefs.getString("erledigt", "");
        if (e.contains(heute() + ":" + id + ";")) {
            return true;
        }
        JSONObject er = trainingVon(t.optString("er"));
        return er != null && (e.contains(heute() + ":" + er.optString("id") + ";") || (paketHeute() && er.optBoolean("ok")));
    }

    private JSONArray heuteItems() {
        JSONArray plan = paket != null ? paket.optJSONArray("plan") : null;
        JSONObject tag = plan != null ? plan.optJSONObject(wochentagIndex()) : null;
        return tag != null ? tag.optJSONArray("items") : null;
    }

    private String heuteArt() {
        JSONArray plan = paket != null ? paket.optJSONArray("plan") : null;
        JSONObject tag = plan != null ? plan.optJSONObject(wochentagIndex()) : null;
        String[] namen = {"Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag"};
        String art = tag != null ? tag.optString("art") : "";
        return namen[wochentagIndex()] + (art.length() > 0 ? " · " + art : "");
    }

    /** Wie in der App: jeder Eintrag gilt, bis der nächste beginnt. Zeiten nach Mitternacht gehören zum Abend davor. */
    private int aktuellerEintrag(JSONArray items) {
        int jetzt = minutenJetzt();
        int cur = -1;
        for (int i = 0; i < items.length(); i++) {
            int m = zuMinRel(items, i);
            if (m >= 0 && m <= jetzt) {
                cur = i;
            }
        }
        return cur;
    }

    private int zuMinRel(JSONArray items, int i) {
        int start = zuMin(items.optJSONArray(0).optString(0));
        int m = zuMin(items.optJSONArray(i).optString(0));
        if (m >= 0 && m < start) {
            m += 1440;
        }
        return m;
    }

    private static int minutenJetzt() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    private static int zuMin(String t) {
        if (t == null || t.length() != 5 || t.charAt(2) != ':') {
            return -1;
        }
        try {
            return Integer.parseInt(t.substring(0, 2)) * 60 + Integer.parseInt(t.substring(3));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Montag 0 bis Sonntag 6 */
    private static int wochentagIndex() {
        int d = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        return (d + 5) % 7;
    }

    private static String heute() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.ROOT, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    private static String datumKurz(String k) {
        return k != null && k.length() == 10 ? k.substring(8) + "." + k.substring(5, 7) + "." : "";
    }

    private static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    private static JSONObject json(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static double zahl(String s, double sonst) {
        try {
            return s == null || s.isEmpty() ? sonst : Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            return sonst;
        }
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 10) / 10.0);
    }

    private static String dauer(int s) {
        return s >= 60 ? (s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60) : s + " s";
    }

    private static String dauerText(int min) {
        if (min < 60) {
            return Math.max(1, min) + " Min";
        }
        return (min / 60) + " h" + (min % 60 > 0 ? " " + (min % 60) + " Min" : "");
    }

    private static int zahlAus(JSONArray a, int i) {
        return a != null && i < a.length() && !a.isNull(i) ? a.optInt(i) : 0;
    }

    private static float[] werte(JSONArray a) {
        float[] r = new float[7];
        for (int i = 0; i < 7; i++) {
            r[i] = a != null && i < a.length() && !a.isNull(i) ? (float) a.optDouble(i) : Float.NaN;
        }
        return r;
    }

    /** Schnitt der Tage mit Werten bis heute, heute nur wenn das Paket von heute ist */
    private static int schnitt(JSONArray a, int heuteI, boolean frisch) {
        int s = 0, n = 0;
        for (int i = 0; a != null && i < a.length() && i <= heuteI; i++) {
            if (!a.isNull(i) && a.optInt(i) > 0 && (i < heuteI || frisch)) {
                s += a.optInt(i);
                n++;
            }
        }
        return n > 0 ? Math.round(s / (float) n) : 0;
    }

    private static int summeBis(JSONArray a, int heuteI) {
        int s = 0;
        for (int i = 0; a != null && i < a.length() && i <= heuteI; i++) {
            s += a.isNull(i) ? 0 : a.optInt(i);
        }
        return s;
    }

    private static int[] liste(List<Integer> l) {
        int[] r = new int[l.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = l.get(i);
        }
        return r;
    }

    private static int artIcon(String art) {
        switch (art) {
            case "training":
                return R.drawable.ic_hantel;
            case "essen":
                return R.drawable.ic_essen;
            case "arbeit":
                return R.drawable.ic_arbeit;
            case "frei":
                return R.drawable.ic_frei;
            case "pflege":
                return R.drawable.ic_pflege;
            default:
                return R.drawable.ic_schlaf;
        }
    }

    private static int artFarbe(String art) {
        switch (art) {
            case "training":
                return TRAINING;
            case "essen":
                return KCAL;
            case "arbeit":
                return ARBEIT;
            case "frei":
                return FREI;
            case "pflege":
                return PROTEIN;
            default:
                return SCHLAF;
        }
    }

    // ------------------------------------------------------------------ Bausteine

    private int px(float d) {
        return Math.round(Masse.dp(this, d));
    }

    /** stil: 0 normal, 1 mittel, 2 fett */
    private TextView text(String s, float sp, int farbe, int stil) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(farbe);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(0, 1.12f);
        if (stil == 1) {
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        } else if (stil == 2) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        t.setLayoutParams(volleBreite());
        return t;
    }

    private View uhrzeit() {
        TextClock c = new TextClock(this);
        c.setFormat24Hour("HH:mm");
        c.setFormat12Hour("HH:mm");
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        c.setTextColor(INK);
        c.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setLayoutParams(volleBreite());
        return c;
    }

    private View abstand(int d) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, px(d)));
        return v;
    }

    private LinearLayout.LayoutParams volleBreite() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private Drawable icon(int res) {
        Drawable d = getDrawable(res);
        return d != null ? d.mutate() : null;
    }

    private ImageView icon(int res, int farbe, int groesseDp) {
        ImageView i = new ImageView(this);
        i.setImageDrawable(icon(res));
        i.setColorFilter(farbe, PorterDuff.Mode.SRC_IN);
        i.setLayoutParams(new LinearLayout.LayoutParams(px(groesseDp), px(groesseDp)));
        return i;
    }

    private GradientDrawable oval(int farbe) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(farbe);
        return g;
    }

    private GradientDrawable rund(int farbe, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(farbe);
        g.setCornerRadius(Masse.dp(this, radiusDp));
        return g;
    }

    /** Farbiges Symbol auf getöntem Kreis */
    private ImageView iconKreis(int res, int farbe, int groesseDp) {
        ImageView i = icon(res, farbe, groesseDp);
        i.setBackground(oval(Masse.alpha(farbe, 0x33)));
        int pad = Math.round(px(groesseDp) * 0.24f);
        i.setPadding(pad, pad, pad, pad);
        return i;
    }

    /** Listeneintrag nach Wear OS: mindestens 58 dp hoch, Symbol 36 dp, Titel 15 sp, Untertitel 13 sp */
    private LinearLayout chip(int iconRes, int farbe, String titel, String unter, View rechts, View.OnClickListener klick) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setMinimumHeight(px(58));
        c.setPadding(px(10), px(10), px(14), px(10));
        c.setBackground(rund(FLAECHE, 29));
        c.addView(iconKreis(iconRes, farbe, 36));
        LinearLayout spalte = new LinearLayout(this);
        spalte.setOrientation(LinearLayout.VERTICAL);
        spalte.setPadding(px(10), 0, px(4), 0);
        TextView t = text(titel, 15, INK, 1);
        t.setGravity(Gravity.START);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        spalte.addView(t);
        if (unter != null && unter.length() > 0) {
            TextView u = text(unter, 13, MUTED, 0);
            u.setGravity(Gravity.START);
            u.setMaxLines(1);
            u.setEllipsize(TextUtils.TruncateAt.END);
            u.setPadding(0, px(2), 0, 0);
            spalte.addView(u);
        }
        c.addView(spalte, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (rechts != null) {
            c.addView(rechts);
        }
        klickbar(c, klick);
        LinearLayout.LayoutParams lp = volleBreite();
        lp.topMargin = px(6);
        c.setLayoutParams(lp);
        return c;
    }

    private LinearLayout karte() {
        LinearLayout k = new LinearLayout(this);
        k.setOrientation(LinearLayout.VERTICAL);
        k.setPadding(px(16), px(12), px(16), px(12));
        k.setBackground(rund(FLAECHE, 24));
        LinearLayout.LayoutParams lp = volleBreite();
        lp.topMargin = px(10);
        lp.bottomMargin = px(8);
        k.setLayoutParams(lp);
        return k;
    }

    private View tabellenZeile(String label, String wert, int farbe) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, px(5), 0, px(5));
        TextView l = text(label, 14, MUTED, 0);
        l.setGravity(Gravity.START);
        r.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView w = text(wert, 15, farbe, 2);
        w.setGravity(Gravity.END);
        r.addView(w, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return r;
    }

    /** Hauptknopf: 52 dp hoch, ganz rund, Symbol und Text */
    private View pille(String s, int iconRes, int farbe, View.OnClickListener klick) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER);
        p.setMinimumHeight(px(48));
        p.setPadding(px(16), 0, px(18), 0);
        p.setBackground(rund(farbe, 24));
        if (iconRes != 0) {
            ImageView i = icon(iconRes, INK, 20);
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(px(20), px(20));
            il.rightMargin = px(8);
            p.addView(i, il);
        }
        TextView t = text(s, 16, INK, 1);
        t.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        p.addView(t);
        klickbar(p, klick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (W * 0.56f), px(48));
        lp.topMargin = px(4);
        p.setLayoutParams(lp);
        return p;
    }

    /** Kleiner Knopf (40 dp) für Nebenaktionen */
    private View kompakt(String s, int iconRes, int farbe, View.OnClickListener klick) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER);
        p.setPadding(px(14), 0, px(16), 0);
        p.setBackground(rund(FLAECHE, 20));
        if (iconRes != 0) {
            ImageView i = icon(iconRes, farbe, 16);
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(px(16), px(16));
            il.rightMargin = px(6);
            p.addView(i, il);
        }
        TextView t = text(s, 14, INK, 1);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        p.addView(t);
        klickbar(p, klick);
        p.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(38)));
        return p;
    }

    /** Knopf am unteren Rand, folgt der Rundung */
    private void kante(FrameLayout seite, String s, int iconRes, int farbe, View.OnClickListener klick) {
        KantenKnopf k = new KantenKnopf(this, Math.min(W, H));
        k.setInhalt(s, icon(iconRes), farbe, INK);
        klickbar(k, klick);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, px(58), Gravity.BOTTOM);
        seite.addView(k, lp);
    }

    private View stepper(TextView wert, String einheit, int knopfDp, Runnable minus, Runnable plus) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rl = volleBreite();
        rl.topMargin = px(4);
        r.setLayoutParams(rl);
        r.addView(rundKnopf(R.drawable.ic_minus, FLAECHE, INK, knopfDp, v -> {
            minus.run();
            huepfen(wert);
        }));
        LinearLayout mitte = new LinearLayout(this);
        mitte.setOrientation(LinearLayout.VERTICAL);
        mitte.setGravity(Gravity.CENTER);
        mitte.addView(wert);
        if (einheit != null) {
            mitte.addView(text(einheit, 13, FAINT, 0));
        }
        r.addView(mitte, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        r.addView(rundKnopf(R.drawable.ic_plus, FLAECHE, INK, knopfDp, v -> {
            plus.run();
            huepfen(wert);
        }));
        return r;
    }

    private View rundKnopf(int res, int bg, int fg, int groesseDp, View.OnClickListener klick) {
        ImageView i = icon(res, fg, groesseDp);
        i.setBackground(oval(bg));
        int pad = Math.round(px(groesseDp) * 0.28f);
        i.setPadding(pad, pad, pad, pad);
        klickbar(i, klick);
        return i;
    }

    private void huepfen(View v) {
        v.setScaleX(1.16f);
        v.setScaleY(1.16f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
    }

    /** Antippen: kurz kleiner werden, leichte Vibration, dann die Aktion */
    private void klickbar(View v, View.OnClickListener klick) {
        v.setClickable(true);
        v.setOnTouchListener((vv, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                vv.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).start();
            } else if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                vv.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
            }
            return false;
        });
        v.setOnClickListener(x -> {
            x.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            klick.onClick(x);
        });
    }

    private void bildschirmAn(boolean an) {
        if (an) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void vibrieren(long[] muster) {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createWaveform(muster, -1));
            }
        } catch (Exception ignored) {
        }
    }
}
