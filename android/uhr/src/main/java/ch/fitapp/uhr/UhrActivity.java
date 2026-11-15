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
 * FitApp auf der Galaxy Watch, gestaltet wie die Apps von Samsung: schwarzer Grund, runde Chips mit farbigen Symbolen,
 * Ringe, Wochendiagramme, Pulskurve, Ring am Rand im Training. Keine Bibliotheken ausser der Data Layer.
 * Animationen sind kurz und laufen nur, wenn sich etwas ändert. Der Pulssensor läuft nur im Training.
 */
public class UhrActivity extends Activity implements SensorEventListener, DataClient.OnDataChangedListener {

    static final String PAKET = "/fitapp/paket";
    static final String VON_UHR = "/fitapp/uhr/";
    private static final int REQ_PULS = 7;
    private static final long SECHS_STUNDEN = 6L * 3600 * 1000;

    /* Farben: schwarz für AMOLED, Chips dunkelgrau, Akzente wie in der Statistik der App */
    static final int BG = 0xFF000000, CHIP = 0xFF1F1F1F, INK = 0xFFFAFAFA, MUTED = 0xFFA3A3A3, FAINT = 0xFF6E6E6E,
            TRAINING = 0xFFD55181, KCAL = 0xFFD95926, PROTEIN = 0xFF3987E5, PULS = 0xFFE66767, SCHLAF = 0xFF9085E9,
            FREI = 0xFF199E70, ARBEIT = 0xFF8A9BB0, PAUSE = 0xFFE6E6E6;

    private SharedPreferences prefs;
    private JSONObject paket;
    private JSONObject lauf;
    private String screen = "home";
    private String letzterScreen = "";
    private FrameLayout root;
    private RandRingView rand;
    private ScrollView scroll;
    private LinearLayout box;
    private int breite;
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView zeitView, pulsView, repView, kgView;
    private LinieView pulsLinie;
    private int letzteSekunde = -1;
    private SensorManager sm;
    private boolean pulsAn;
    private int pulsJetzt;
    private long pulsZuletzt, verlaufZuletzt;
    private final List<Integer> pulsLive = new ArrayList<>();
    private double wertW, wertR;
    private String wertFuer = "";
    private long beendenSeit;
    private JSONObject fertigInfo;

    // ------------------------------------------------------------------ Lebenszyklus

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("uhr", MODE_PRIVATE);
        DisplayMetrics m = getResources().getDisplayMetrics();
        boolean rund = getResources().getConfiguration().isScreenRound();
        int seite = rund ? (int) (m.widthPixels * 0.11f) : px(10);
        breite = m.widthPixels - 2 * seite;

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        rand = new RandRingView(this);
        rand.setVisibility(View.GONE);
        root.addView(rand, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(seite, rund ? (int) (m.heightPixels * 0.06f) : px(8), seite, rund ? (int) (m.heightPixels * 0.2f) : px(16));
        scroll.addView(box, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);

        paket = json(prefs.getString("paket", null));
        lauf = json(prefs.getString("lauf", null));
        if (lauf != null && System.currentTimeMillis() - lauf.optLong("t0") > SECHS_STUNDEN) {
            lauf = null;
            prefs.edit().remove("lauf").apply();
        }
        if (lauf != null) {
            screen = "training";
            pulsStarten();
        }
        zeigen(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            Wearable.getDataClient(this).addListener(this);
        } catch (Exception ignored) {
        }
        paketLaden();
        if (!"training".equals(screen)) {
            zeigen(2);
        } else {
            h.post(tickRunner);
        }
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
        if ("optionen".equals(screen)) {
            gehe("training", -1);
        } else if (!"home".equals(screen)) {
            gehe("home", -1);
        } else {
            super.onBackPressed();
        }
    }

    private void gehe(String ziel, int richtung) {
        screen = ziel;
        zeigen(richtung);
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
        if (!"training".equals(screen) && !"optionen".equals(screen)) {
            zeigen(2);
        }
    }

    private boolean paketHeute() {
        return paket != null && heute().equals(paket.optString("tag"));
    }

    // ------------------------------------------------------------------ Anzeige und Übergänge

    /** richtung 1 von rechts, -1 von links, 0 nur einblenden, 2 ohne Animation (Aktualisieren) */
    private void zeigen(int richtung) {
        h.removeCallbacks(tickRunner);
        zeitView = null;
        pulsView = null;
        pulsLinie = null;
        repView = null;
        kgView = null;
        letzteSekunde = -1;
        box.removeAllViews();
        boolean imTraining = "training".equals(screen) && lauf != null;
        switch (screen) {
            case "plan":
                planZeigen();
                break;
            case "essen":
                essenZeigen();
                break;
            case "wahl":
                wahlZeigen();
                break;
            case "optionen":
                optionenZeigen();
                break;
            case "training":
                if (lauf == null) {
                    screen = "home";
                    homeZeigen();
                } else {
                    trainingZeigen();
                }
                break;
            case "fertig":
                fertigZeigen();
                break;
            default:
                screen = "home";
                homeZeigen();
        }
        if (!imTraining) {
            rand.setVisibility(View.GONE);
            bildschirmAn(false);
        }
        boolean neu = !screen.equals(letzterScreen);
        if (neu) {
            scroll.scrollTo(0, 0);
            letzterScreen = screen;
        }
        if (richtung != 2) {
            einblenden(richtung);
        }
        scroll.requestFocus();
    }

    /** Inhalt gleitet sanft herein, die Elemente kurz nacheinander */
    private void einblenden(int richtung) {
        box.setAlpha(0f);
        box.setTranslationX(richtung * px(28));
        box.animate().alpha(1f).translationX(0).setDuration(240).setInterpolator(new DecelerateInterpolator(1.6f)).start();
        int n = Math.min(box.getChildCount(), 10);
        for (int i = 0; i < n; i++) {
            View v = box.getChildAt(i);
            v.setAlpha(0f);
            v.setTranslationY(px(10));
            v.animate().alpha(1f).translationY(0).setStartDelay(40L * i).setDuration(260).setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    // ------------------------------------------------------------------ Start

    private void homeZeigen() {
        box.addView(uhrzeit());
        if (paket == null) {
            box.addView(abstand(14));
            box.addView(iconKreis(R.drawable.ic_hantel, TRAINING, 56));
            box.addView(abstand(10));
            box.addView(text("FitApp", 18, INK, 2));
            box.addView(abstand(6));
            box.addView(text("Öffne FitApp auf dem Handy. Dann kommen Plan, Training und Essen hierher.", 13, MUTED, 0));
            return;
        }
        boolean frisch = paketHeute();
        JSONObject st = paket.optJSONObject("streak");
        JSONObject es = paket.optJSONObject("essen");
        JSONObject stat = paket.optJSONObject("stat");
        int heuteI = wochentagIndex();
        int[] summe = essenSumme();
        JSONObject t = trainingHeute();
        boolean erledigt = t != null && trainingErledigt(t);

        /* Ringe: Kalorien, Protein, Training. In der Mitte die Streak. */
        int sz = stat != null && frisch ? zahlAus(stat.optJSONArray("sz"), heuteI) : 0;
        int soll = stat != null ? zahlAus(stat.optJSONArray("soll"), heuteI) : 0;
        float trainingWert = erledigt ? 1f : soll > 0 ? sz / (float) soll : 0f;
        int kz = es != null ? es.optInt("kz") : 0, prz = es != null ? es.optInt("prz") : 0;
        RingView ringe = new RingView(this);
        int rg = (int) (breite * 0.78f);
        ringe.setLayoutParams(new LinearLayout.LayoutParams(rg, rg));
        ringe.setDicke(8.5f, 3f);
        int streak = st != null ? st.optInt("n") : 0;
        ringe.setMitte(icon(R.drawable.ic_flamme), streak > 0 ? KCAL : FAINT, String.valueOf(streak), streak == 1 ? "Tag Streak" : "Tage Streak");
        ringe.setRinge(new int[]{KCAL, PROTEIN, TRAINING},
                new float[]{kz > 0 ? summe[0] / (float) kz : 0, prz > 0 ? summe[1] / (float) prz : 0, trainingWert}, true);
        box.addView(ringe);
        box.addView(abstand(6));

        /* Legende wie in Samsung Health: Punkt, Wert, Bezeichnung */
        LinearLayout leg = new LinearLayout(this);
        leg.setOrientation(LinearLayout.HORIZONTAL);
        leg.addView(legende(KCAL, String.valueOf(summe[0]), "kcal"));
        leg.addView(legende(PROTEIN, summe[1] + " g", "Protein"));
        leg.addView(legende(TRAINING, erledigt ? "✓" : soll > 0 ? sz + "/" + soll : "frei", "Training"));
        box.addView(leg, volleBreite());
        box.addView(abstand(10));

        WocheView woche = new WocheView(this);
        String[] codes = new String[7];
        JSONArray w = st != null ? st.optJSONArray("w") : null;
        for (int i = 0; i < 7; i++) {
            codes[i] = w != null && i < w.length() ? w.optString(i) : "-";
            if ("h".equals(codes[i]) && !frisch) {
                codes[i] = "-";
            }
        }
        woche.setTage(codes, heuteI, TRAINING, PROTEIN);
        box.addView(woche, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(38)));
        box.addView(abstand(8));

        jetztChip();

        if (lauf != null) {
            box.addView(chip(R.drawable.ic_play, TRAINING, "Training läuft", lauf.optString("n") + " · " + satzFertig() + " von " + satzGesamt() + " Sätzen",
                    knopfKlein(R.drawable.ic_play, TRAINING), v -> gehe("training", 1)));
        } else if (t == null) {
            box.addView(chip(R.drawable.ic_hantel, FAINT, "Kein Pflicht Training", "Freiwillig trainieren", null, v -> gehe("wahl", 1)));
        } else {
            int anzahl = t.optJSONArray("ex") != null ? t.optJSONArray("ex").length() : 0;
            String unter = erledigt ? "Erledigt. Stark." : "etwa " + t.optInt("min") + " Min · " + anzahl + " Übungen";
            box.addView(chip(R.drawable.ic_hantel, TRAINING, t.optString("n"), unter,
                    erledigt ? iconKreis(R.drawable.ic_check, FREI, 30) : knopfKlein(R.drawable.ic_play, TRAINING), v -> starten(t)));
            JSONObject e = trainingVon(t.optString("er"));
            if (e != null && !erledigt) {
                box.addView(chip(R.drawable.ic_haus, MUTED, "Zuhause: " + e.optString("n"), "Nicht im Gym? Zählt genauso · " + e.optInt("min") + " Min", null, v -> starten(e)));
            }
        }

        if (es != null) {
            LinearLayout c = chip(R.drawable.ic_essen, KCAL, "Essen eintragen", summe[0] + " von " + kz + " kcal", null, v -> gehe("essen", 1));
            Fortschritt f = new Fortschritt(this);
            f.setWert(kz > 0 ? summe[0] / (float) kz : 0, KCAL, true);
            ((LinearLayout) c.getChildAt(1)).addView(f, balkenParams(4));
            box.addView(c);
        }

        if (stat != null) {
            box.addView(abstand(6));
            box.addView(diagrammKarte("Kalorien", "Ø " + schnitt(stat.optJSONArray("kcal"), heuteI, frisch) + " kcal",
                    werte(stat.optJSONArray("kcal")), null, kz, KCAL, heuteI));
            box.addView(diagrammKarte("Training", summeBis(stat.optJSONArray("sz"), heuteI) + " Sätze",
                    werte(stat.optJSONArray("sz")), werte(stat.optJSONArray("soll")), 0, TRAINING, heuteI));
        }
        box.addView(abstand(4));
        box.addView(chip(R.drawable.ic_uhrzeit, SCHLAF, "Tagesplan", heuteArt(), null, v -> gehe("plan", 1)));
        box.addView(chip(R.drawable.ic_liste, MUTED, "Anderes Training", "Alle Trainings dieser Woche", null, v -> gehe("wahl", 1)));
        if (!frisch) {
            box.addView(abstand(10));
            box.addView(text("Stand vom " + datumKurz(paket.optString("tag")) + ". Öffne FitApp kurz auf dem Handy.", 11, FAINT, 0));
        }
    }

    private void jetztChip() {
        JSONArray items = heuteItems();
        if (items == null || items.length() == 0) {
            return;
        }
        int cur = aktuellerEintrag(items);
        JSONArray c = cur >= 0 ? items.optJSONArray(cur) : null;
        JSONArray n = cur + 1 < items.length() ? items.optJSONArray(cur + 1) : null;
        String art = c != null ? c.optString(2) : "schlaf";
        String unter;
        float anteil = 0;
        if (c != null && n != null) {
            int jetzt = minutenJetzt(), von = zuMinRel(items, cur), bis = zuMinRel(items, cur + 1);
            unter = "noch " + dauerText(bis - jetzt) + " · dann " + n.optString(0);
            anteil = bis > von ? (jetzt - von) / (float) (bis - von) : 0;
        } else {
            unter = n != null ? "ab " + n.optString(0) + ": " + n.optString(1) : "";
        }
        LinearLayout ch = chip(artIcon(art), artFarbe(art), c != null ? c.optString(1) : "Noch Nacht. Schlaf weiter", unter, null, v -> gehe("plan", 1));
        ((TextView) ((LinearLayout) ch.getChildAt(1)).getChildAt(0)).setMaxLines(3);
        if (anteil > 0) {
            Fortschritt f = new Fortschritt(this);
            f.setWert(anteil, artFarbe(art), true);
            ((LinearLayout) ch.getChildAt(1)).addView(f, balkenParams(3));
        }
        box.addView(ch);
    }

    private View diagrammKarte(String titel, String wert, float[] werte, float[] soll, float ziel, int farbe, int heuteI) {
        LinearLayout k = karte();
        LinearLayout kopf = new LinearLayout(this);
        kopf.setOrientation(LinearLayout.HORIZONTAL);
        kopf.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(titel, 13, INK, 1);
        t.setGravity(Gravity.START);
        kopf.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView w = text(wert, 12, farbe, 2);
        w.setGravity(Gravity.END);
        kopf.addView(w, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        k.addView(kopf);
        TextView u = text("Diese Woche", 10, FAINT, 0);
        u.setGravity(Gravity.START);
        k.addView(u);
        BalkenView b = new BalkenView(this);
        b.setDaten(werte, soll, ziel, farbe, heuteI);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(74));
        lp.topMargin = px(8);
        k.addView(b, lp);
        return k;
    }

    // ------------------------------------------------------------------ Tagesplan als Zeitleiste

    private void planZeigen() {
        box.addView(uhrzeit());
        box.addView(titel("Tagesplan", SCHLAF));
        box.addView(text(heuteArt(), 11, MUTED, 0));
        box.addView(abstand(10));
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
                TextView z = text(it.optString(0), 11, jetzt ? INK : FAINT, jetzt ? 2 : 0);
                z.setGravity(Gravity.END);
                z.setPadding(0, px(8), 0, 0);
                zeile.addView(z, new LinearLayout.LayoutParams(px(38), LinearLayout.LayoutParams.WRAP_CONTENT));
                ZeitpunktView zp = new ZeitpunktView(this);
                zp.setZustand(farbe, jetzt, vorbei, i == 0, i == items.length() - 1);
                zeile.addView(zp, new LinearLayout.LayoutParams(px(22), LinearLayout.LayoutParams.MATCH_PARENT));
                LinearLayout spalte = new LinearLayout(this);
                spalte.setOrientation(LinearLayout.VERTICAL);
                spalte.setPadding(0, px(6), 0, px(10));
                if (jetzt) {
                    TextView j = text("JETZT", 9, farbe, 2);
                    j.setGravity(Gravity.START);
                    j.setLetterSpacing(0.12f);
                    spalte.addView(j);
                }
                TextView tx = text(it.optString(1), 13, vorbei ? FAINT : INK, jetzt ? 1 : 0);
                tx.setGravity(Gravity.START);
                spalte.addView(tx);
                zeile.addView(spalte, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                box.addView(zeile, volleBreite());
                if (jetzt) {
                    ziel = zeile;
                }
            }
        }
        if (ziel != null) {
            final View zz = ziel;
            scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, zz.getTop() - px(50))));
        }
    }

    // ------------------------------------------------------------------ Training wählen

    private void wahlZeigen() {
        box.addView(uhrzeit());
        box.addView(titel("Training wählen", TRAINING));
        box.addView(abstand(6));
        JSONObject heute = trainingHeute();
        JSONArray ts = paket != null ? paket.optJSONArray("trainings") : null;
        for (int i = 0; ts != null && i < ts.length(); i++) {
            JSONObject t = ts.optJSONObject(i);
            if (t == null) {
                continue;
            }
            boolean istHeute = heute != null && heute.optString("id").equals(t.optString("id"));
            box.addView(chip(R.drawable.ic_hantel, istHeute ? TRAINING : MUTED, t.optString("n"), t.optString("ti") + " · " + t.optInt("min") + " Min",
                    istHeute ? abzeichen("Heute", TRAINING) : null, v -> starten(t)));
        }
    }

    // ------------------------------------------------------------------ Training

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
        screen = "training";
        pulsStarten();
        zeigen(1);
    }

    private JSONObject aktUebung() {
        JSONArray ex = lauf.optJSONArray("ex");
        int i = lauf.optInt("i");
        return ex != null && i < ex.length() ? ex.optJSONObject(i) : null;
    }

    private int satzGesamt() {
        return lauf == null ? 0 : satzGesamtVon(lauf);
    }

    private int satzFertig() {
        return lauf == null ? 0 : satzZaehlen(lauf);
    }

    private void trainingZeigen() {
        JSONObject x = aktUebung();
        if (x == null) {
            beenden();
            return;
        }
        String phase = lauf.optString("phase", "satz");
        rand.setVisibility(View.VISIBLE);
        int gesamt = Math.max(1, satzGesamt());
        boolean timer = "pause".equals(phase) || "halten".equals(phase) || "bereit".equals(phase);
        if (!timer) {
            rand.setFarbe(TRAINING);
            rand.setWert(satzFertig() / (float) gesamt, true);
        }

        if (timer) {
            bildschirmAn(true);
            box.addView(abstand(14));
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
            TextView l = text(label, 12, farbe, 2);
            l.setLetterSpacing(0.14f);
            box.addView(l);
            zeitView = text("", 50, INK, 2);
            zeitView.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            box.addView(zeitView);
            if ("pause".equals(phase)) {
                box.addView(text("Als Nächstes", 10, FAINT, 0));
                TextView nx = text(x.optString("n"), 14, INK, 1);
                nx.setMaxLines(2);
                box.addView(nx);
                box.addView(text("Satz " + (lauf.optInt("j") + 1) + " von " + x.optInt("s"), 11, TRAINING, 1));
                box.addView(abstand(6));
                box.addView(pulsZeile());
                LinearLayout r = reihe();
                r.addView(knopfMitText(R.drawable.ic_plus, CHIP, INK, "15 s", v -> {
                    try {
                        lauf.put("bis", lauf.optLong("bis") + 15000);
                        lauf.put("dauer", lauf.optLong("dauer") + 15000);
                    } catch (Exception ignored) {
                    }
                    laufSpeichern();
                }));
                r.addView(knopfMitText(R.drawable.ic_weiter, CHIP, INK, "Weiter", v -> phaseSatz()));
                box.addView(r);
            } else {
                TextView nx = text(x.optString("n"), 12, MUTED, 0);
                nx.setMaxLines(2);
                box.addView(nx);
                box.addView(abstand(4));
                box.addView(pulsZeile());
                LinearLayout r = reihe();
                r.addView(knopfMitText(R.drawable.ic_stopp, CHIP, INK, "Stopp", v -> phaseSatz()));
                box.addView(r);
            }
            h.post(tickRunner);
            return;
        }

        bildschirmAn(false);
        box.addView(abstand(10));
        box.addView(pulsZeile());
        JSONArray alle = lauf.optJSONArray("ex");
        box.addView(text("Übung " + (lauf.optInt("i") + 1) + " von " + (alle != null ? alle.length() : 1), 10, FAINT, 0));
        TextView name = text(x.optString("n"), 17, INK, 2);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(name);
        int j = lauf.optInt("j"), s = x.optInt("s");
        box.addView(text("Satz " + (j + 1) + " von " + s + (x.optString("z").length() > 0 ? " · " + x.optString("z") : ""), 11, TRAINING, 1));
        box.addView(abstand(6));
        String einheit = x.optString("e");
        if ("Wdh".equals(einheit)) {
            werteVorbereiten(x, j);
            repView = text(fmt(wertR), 38, INK, 2);
            box.addView(stepper(repView, "Wdh", 42, () -> {
                wertR = Math.max(0, wertR - 1);
                repView.setText(fmt(wertR));
            }, () -> {
                wertR = Math.min(200, wertR + 1);
                repView.setText(fmt(wertR));
            }));
            if (x.optInt("kg") == 1) {
                kgView = text(fmt(wertW) + " kg", 17, INK, 2);
                box.addView(stepper(kgView, null, 34, () -> {
                    wertW = Math.max(0, wertW - (wertW > 20 ? 2.5 : 1));
                    kgView.setText(fmt(wertW) + " kg");
                }, () -> {
                    wertW = Math.min(300, wertW + (wertW >= 20 ? 2.5 : 1));
                    kgView.setText(fmt(wertW) + " kg");
                }));
            }
            box.addView(abstand(8));
            LinearLayout r = reihe();
            r.addView(rundKnopf(R.drawable.ic_mehr, CHIP, MUTED, 36, v -> gehe("optionen", 1)));
            View ok = rundKnopf(R.drawable.ic_check, TRAINING, INK, 60, v -> satzErledigt(x.optInt("kg") == 1 ? fmt(wertW) : "", fmt(wertR)));
            ((LinearLayout.LayoutParams) ok.getLayoutParams()).leftMargin = px(14);
            ((LinearLayout.LayoutParams) ok.getLayoutParams()).rightMargin = px(14);
            r.addView(ok);
            /* Platzhalter rechts, damit der Haken genau in der Mitte steht */
            r.addView(new View(this), new LinearLayout.LayoutParams(px(36), px(36)));
            box.addView(r);
        } else {
            int sek = haltenSekunden(x, 0);
            TextView z = text(dauer(sek), 38, INK, 2);
            box.addView(z);
            int teile = teileAnzahl(x);
            box.addView(text(teile > 1 ? teile + " Teile, je " + dauer(sek) : "halten", 10, FAINT, 0));
            box.addView(abstand(8));
            LinearLayout r = reihe();
            r.addView(rundKnopf(R.drawable.ic_mehr, CHIP, MUTED, 36, v -> gehe("optionen", 1)));
            View go = rundKnopf(R.drawable.ic_play, TRAINING, INK, 60, v -> haltenStarten());
            ((LinearLayout.LayoutParams) go.getLayoutParams()).leftMargin = px(14);
            ((LinearLayout.LayoutParams) go.getLayoutParams()).rightMargin = px(14);
            r.addView(go);
            r.addView(rundKnopf(R.drawable.ic_check, CHIP, INK, 36, v -> satzErledigt("", String.valueOf(x.optInt("so")))));
            box.addView(r);
        }
    }

    private View pulsZeile() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER);
        ImageView herz = icon(R.drawable.ic_herz, PULS, 13);
        r.addView(herz);
        pulsView = text(pulsJetzt > 0 ? " " + pulsJetzt : " ...", 13, PULS, 2);
        pulsView.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        r.addView(pulsView);
        if (pulsJetzt > 0) {
            herz.animate().scaleX(1.25f).scaleY(1.25f).setDuration(180).withEndAction(() -> herz.animate().scaleX(1f).scaleY(1f).setDuration(220).start()).start();
        }
        if (!"satz".equals(lauf.optString("phase")) && pulsLive.size() >= 3) {
            LinearLayout spalte = new LinearLayout(this);
            spalte.setOrientation(LinearLayout.VERTICAL);
            spalte.setGravity(Gravity.CENTER_HORIZONTAL);
            spalte.addView(r);
            pulsLinie = new LinieView(this);
            pulsLinie.setWerte(liste(pulsLive), PULS, false);
            spalte.addView(pulsLinie, new LinearLayout.LayoutParams((int) (breite * 0.62f), px(26)));
            return spalte;
        }
        return r;
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
        /* Im selben Training gilt der letzte Satz dieser Übung als Vorschlag für das Gewicht */
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
        zeigen(0);
    }

    /* Zehnmal pro Sekunde, damit der Ring am Rand weich läuft. Text nur bei neuer Sekunde. */
    private final Runnable tickRunner = new Runnable() {
        @Override
        public void run() {
            if (lauf == null || zeitView == null) {
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
                zeitView.setText("bereit".equals(lauf.optString("phase")) ? String.valueOf(s) : dauer(s));
                if ("bereit".equals(lauf.optString("phase"))) {
                    zeitView.setScaleX(1.35f);
                    zeitView.setScaleY(1.35f);
                    zeitView.animate().scaleX(1f).scaleY(1f).setDuration(350).setInterpolator(new DecelerateInterpolator()).start();
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
                    /* 5 Sekunden zum Wechseln, zum Beispiel die Seite */
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
        zeigen(0);
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
        zeigen(0);
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
            JSONArray ex = lauf.optJSONArray("ex");
            if (i >= ex.length()) {
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
            zeigen(1);
        }
    }

    private void optionenZeigen() {
        box.addView(uhrzeit());
        box.addView(titel("Training", TRAINING));
        if (lauf != null) {
            box.addView(text(lauf.optString("n") + " · " + satzFertig() + " von " + satzGesamt() + " Sätzen", 11, MUTED, 0));
        }
        box.addView(abstand(6));
        box.addView(chip(R.drawable.ic_play, TRAINING, "Weiter trainieren", null, null, v -> gehe("training", -1)));
        box.addView(chip(R.drawable.ic_weiter, MUTED, "Übung überspringen", "Zur nächsten Übung", null, v -> {
            uebungUeberspringen();
        }));
        boolean sicher = System.currentTimeMillis() - beendenSeit < 4000;
        box.addView(chip(R.drawable.ic_ende, PULS, sicher ? "Nochmals tippen" : "Training beenden", sicher ? "Dann wird gespeichert, was du hast" : "Speichert die gemachten Sätze", null, v -> {
            if (System.currentTimeMillis() - beendenSeit < 4000) {
                beenden();
            } else {
                beendenSeit = System.currentTimeMillis();
                zeigen(2);
            }
        }));
        box.addView(chip(R.drawable.ic_haus, MUTED, "Zur Übersicht", "Training läuft weiter", null, v -> gehe("home", -1)));
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
            gehe("training", -1);
        }
    }

    private void beenden() {
        pulsStoppen();
        bildschirmAn(false);
        JSONObject l = lauf;
        lauf = null;
        prefs.edit().remove("lauf").apply();
        int fertig = satzZaehlen(l);
        if (l == null || fertig == 0) {
            gehe("home", -1);
            return;
        }
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
                puls = new JSONObject().put("avg", Math.round(p.optDouble("sum") / p.optInt("n")))
                        .put("max", p.optInt("max")).put("min", p.optInt("min"));
                ev.put("puls", puls);
            }
            senden(ev);
            String erledigt = prefs.getString("erledigt", "");
            prefs.edit().putString("erledigt", heute() + ":" + l.optString("day") + ";" + (erledigt.startsWith(heute()) ? erledigt : "")).apply();
            fertigInfo = new JSONObject().put("n", l.optString("n")).put("f", fertig).put("s", satzGesamtVon(l))
                    .put("min", ev.optInt("min"));
            if (puls != null) {
                fertigInfo.put("avg", puls.optInt("avg")).put("max", puls.optInt("max"));
            }
            fertigInfo.put("hr", l.optJSONArray("hr") != null ? l.optJSONArray("hr") : new JSONArray());
            vibrieren(new long[]{0, 120, 80, 120, 80, 300});
        } catch (Exception ignored) {
        }
        gehe("fertig", 1);
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
        JSONArray ex = l.optJSONArray("ex");
        int n = 0;
        for (int i = 0; ex != null && i < ex.length(); i++) {
            JSONObject e = ex.optJSONObject(i);
            n += e != null ? e.optInt("s") : 0;
        }
        return n;
    }

    private void fertigZeigen() {
        JSONObject f = fertigInfo;
        box.addView(uhrzeit());
        box.addView(abstand(6));
        float anteil = f != null && f.optInt("s") > 0 ? f.optInt("f") / (float) f.optInt("s") : 1f;
        RingView r = new RingView(this);
        int g = (int) (breite * 0.5f);
        r.setLayoutParams(new LinearLayout.LayoutParams(g, g));
        r.setDicke(8, 0);
        r.setMitte(icon(R.drawable.ic_check), anteil >= 1f ? INK : MUTED, "", "");
        r.setRinge(new int[]{TRAINING}, new float[]{anteil}, true);
        box.addView(r);
        box.addView(abstand(8));
        box.addView(text(anteil >= 1f ? "Geschafft" : "Gespeichert", 19, INK, 2));
        if (f == null) {
            return;
        }
        box.addView(text(f.optString("n"), 12, MUTED, 0));
        box.addView(abstand(8));
        LinearLayout k = karte();
        k.addView(tabellenZeile("Sätze", f.optInt("f") + " / " + f.optInt("s"), INK));
        k.addView(tabellenZeile("Dauer", f.optInt("min") + " Min", INK));
        if (f.optInt("avg") > 0) {
            k.addView(tabellenZeile("Puls Ø", f.optInt("avg") + " bpm", PULS));
            k.addView(tabellenZeile("Puls max", f.optInt("max") + " bpm", PULS));
        }
        JSONArray hr = f.optJSONArray("hr");
        if (hr != null && hr.length() >= 3) {
            TextView t = text("Pulsverlauf", 10, FAINT, 0);
            t.setGravity(Gravity.START);
            t.setPadding(0, px(8), 0, px(2));
            k.addView(t);
            LinieView lv = new LinieView(this);
            int[] w = new int[hr.length()];
            for (int i = 0; i < w.length; i++) {
                w[i] = hr.optInt(i);
            }
            lv.setWerte(w, PULS, true);
            k.addView(lv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(46)));
        }
        box.addView(k);
        box.addView(text("Geht ans Handy, sobald es in der Nähe ist.", 10, FAINT, 0));
        box.addView(pille("Fertig", TRAINING, v -> gehe("home", -1)));
    }

    private void laufSpeichern() {
        if (lauf != null) {
            prefs.edit().putString("lauf", lauf.toString()).apply();
        }
    }

    // ------------------------------------------------------------------ Essen

    private RingView essenRing;
    private Fortschritt proteinBalken;
    private TextView essenZahl, proteinText;

    private void essenZeigen() {
        box.addView(uhrzeit());
        box.addView(titel("Essen", KCAL));
        JSONObject es = paket != null ? paket.optJSONObject("essen") : null;
        if (es == null) {
            box.addView(abstand(8));
            box.addView(text("Noch keine Daten vom Handy", 13, MUTED, 0));
            return;
        }
        box.addView(abstand(4));
        essenRing = new RingView(this);
        int g = (int) (breite * 0.56f);
        essenRing.setLayoutParams(new LinearLayout.LayoutParams(g, g));
        essenRing.setDicke(8, 0);
        box.addView(essenRing);
        LinearLayout pr = new LinearLayout(this);
        pr.setOrientation(LinearLayout.VERTICAL);
        pr.setPadding(px(18), px(8), px(18), px(4));
        proteinText = text("", 11, PROTEIN, 1);
        pr.addView(proteinText);
        proteinBalken = new Fortschritt(this);
        pr.addView(proteinBalken, balkenParams(5));
        box.addView(pr, volleBreite());
        essenAktualisieren(true);
        box.addView(abstand(4));
        JSONArray fav = es.optJSONArray("fav");
        if (fav == null || fav.length() == 0) {
            box.addView(text("Trag auf dem Handy etwas ein, dann erscheint es hier.", 12, MUTED, 0));
        }
        for (int i = 0; fav != null && i < fav.length(); i++) {
            JSONObject f = fav.optJSONObject(i);
            if (f == null) {
                continue;
            }
            TextView kc = text(f.optInt("kc") + "", 13, INK, 2);
            kc.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            box.addView(chip(f.optInt("fav") == 1 ? R.drawable.ic_stern : R.drawable.ic_essen, KCAL, f.optString("n"), f.optString("m"), kc, v -> essenEintragen(f, v)));
        }
    }

    private void essenAktualisieren(boolean animiert) {
        JSONObject es = paket != null ? paket.optJSONObject("essen") : null;
        if (es == null || essenRing == null) {
            return;
        }
        int[] s = essenSumme();
        int kz = es.optInt("kz"), prz = es.optInt("prz");
        essenRing.setMitte(null, 0, String.valueOf(s[0]), "von " + kz + " kcal");
        essenRing.setRinge(new int[]{KCAL}, new float[]{kz > 0 ? s[0] / (float) kz : 0}, animiert);
        proteinText.setText(s[1] + " / " + prz + " g Protein");
        proteinBalken.setWert(prz > 0 ? s[1] / (float) prz : 0, PROTEIN, animiert);
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
        /* Symbol wird zum Haken, der Ring wächst */
        if (chipView instanceof LinearLayout && ((LinearLayout) chipView).getChildAt(0) instanceof ImageView) {
            ImageView ic = (ImageView) ((LinearLayout) chipView).getChildAt(0);
            ic.setImageDrawable(icon(R.drawable.ic_check));
            ic.setColorFilter(FREI, PorterDuff.Mode.SRC_IN);
            ic.setBackground(oval(Masse.alpha(FREI, 0x38)));
            ic.setScaleX(0.6f);
            ic.setScaleY(0.6f);
            ic.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(new OvershootInterpolator(2.2f)).start();
        }
        essenAktualisieren(true);
        h.postDelayed(() -> {
            if ("essen".equals(screen)) {
                chipView.setEnabled(true);
            }
        }, 1500);
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

    /** Schnitt der abgeschlossenen Tage mit Werten, heute nur wenn schon etwas da ist */
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

    // ------------------------------------------------------------------ Bausteine im Stil von One UI Watch

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
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        c.setTextColor(INK);
        c.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setLayoutParams(volleBreite());
        c.setPadding(0, 0, 0, px(4));
        return c;
    }

    private View titel(String s, int farbe) {
        TextView t = text(s, 15, farbe, 2);
        t.setPadding(0, px(2), 0, px(2));
        return t;
    }

    private View abstand(int d) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, px(d)));
        return v;
    }

    private LinearLayout.LayoutParams volleBreite() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams balkenParams(int hoeheDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(hoeheDp));
        lp.topMargin = px(6);
        return lp;
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

    /** Farbiges Symbol auf getöntem Kreis, wie in den Listen von Samsung */
    private ImageView iconKreis(int res, int farbe, int groesseDp) {
        ImageView i = icon(res, farbe, groesseDp);
        i.setBackground(oval(Masse.alpha(farbe, 0x33)));
        int pad = Math.round(px(groesseDp) * 0.24f);
        i.setPadding(pad, pad, pad, pad);
        return i;
    }

    /** Listeneintrag: Symbol links, Titel und Untertitel, optional etwas rechts */
    private LinearLayout chip(int iconRes, int farbe, String titel, String unter, View rechts, View.OnClickListener klick) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setMinimumHeight(px(54));
        c.setPadding(px(10), px(9), px(12), px(9));
        c.setBackground(rund(CHIP, 27));
        c.addView(iconKreis(iconRes, farbe, 32));
        LinearLayout spalte = new LinearLayout(this);
        spalte.setOrientation(LinearLayout.VERTICAL);
        spalte.setPadding(px(10), 0, px(4), 0);
        TextView t = text(titel, 14, INK, 1);
        t.setGravity(Gravity.START);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        spalte.addView(t);
        if (unter != null && unter.length() > 0) {
            TextView u = text(unter, 11, MUTED, 0);
            u.setGravity(Gravity.START);
            u.setMaxLines(2);
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
        k.setPadding(px(14), px(11), px(14), px(11));
        k.setBackground(rund(CHIP, 22));
        LinearLayout.LayoutParams lp = volleBreite();
        lp.topMargin = px(6);
        lp.bottomMargin = px(6);
        k.setLayoutParams(lp);
        return k;
    }

    private View tabellenZeile(String label, String wert, int farbe) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, px(4), 0, px(4));
        TextView l = text(label, 12, MUTED, 0);
        l.setGravity(Gravity.START);
        r.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView w = text(wert, 13, farbe, 2);
        w.setGravity(Gravity.END);
        r.addView(w, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private View legende(int farbe, String wert, String label) {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout oben = new LinearLayout(this);
        oben.setOrientation(LinearLayout.HORIZONTAL);
        oben.setGravity(Gravity.CENTER);
        View punkt = new View(this);
        punkt.setBackground(oval(farbe));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(px(6), px(6));
        pp.rightMargin = px(4);
        oben.addView(punkt, pp);
        TextView w = text(wert, 13, INK, 2);
        w.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        oben.addView(w);
        s.addView(oben);
        s.addView(text(label, 10, FAINT, 0));
        s.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return s;
    }

    private View abzeichen(String s, int farbe) {
        TextView t = text(s, 10, farbe, 2);
        t.setBackground(rund(Masse.alpha(farbe, 0x30), 10));
        t.setPadding(px(8), px(3), px(8), px(3));
        t.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private View pille(String s, int farbe, View.OnClickListener klick) {
        TextView t = text(s, 15, INK, 2);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(px(48));
        t.setBackground(rund(farbe, 24));
        klickbar(t, klick);
        LinearLayout.LayoutParams lp = volleBreite();
        lp.topMargin = px(10);
        lp.leftMargin = px(14);
        lp.rightMargin = px(14);
        t.setLayoutParams(lp);
        return t;
    }

    private View knopfKlein(int res, int farbe) {
        ImageView i = icon(res, INK, 30);
        i.setBackground(oval(farbe));
        int pad = px(8);
        i.setPadding(pad, pad, pad, pad);
        return i;
    }

    private View rundKnopf(int res, int bg, int fg, int groesseDp, View.OnClickListener klick) {
        ImageView i = icon(res, fg, groesseDp);
        i.setBackground(oval(bg));
        int pad = Math.round(px(groesseDp) * 0.27f);
        i.setPadding(pad, pad, pad, pad);
        klickbar(i, klick);
        return i;
    }

    private View knopfMitText(int res, int bg, int fg, String label, View.OnClickListener klick) {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setGravity(Gravity.CENTER_HORIZONTAL);
        s.addView(rundKnopf(res, bg, fg, 44, klick));
        TextView t = text(label, 10, MUTED, 0);
        t.setPadding(0, px(4), 0, 0);
        s.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(70), LinearLayout.LayoutParams.WRAP_CONTENT);
        s.setLayoutParams(lp);
        return s;
    }

    private LinearLayout reihe() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = volleBreite();
        lp.topMargin = px(10);
        r.setLayoutParams(lp);
        return r;
    }

    private View stepper(TextView wert, String einheit, int knopfDp, Runnable minus, Runnable plus) {
        LinearLayout r = reihe();
        r.addView(rundKnopf(R.drawable.ic_minus, CHIP, INK, knopfDp, v -> {
            minus.run();
            huepfen(wert);
        }));
        LinearLayout mitte = new LinearLayout(this);
        mitte.setOrientation(LinearLayout.VERTICAL);
        mitte.setGravity(Gravity.CENTER);
        mitte.addView(wert);
        if (einheit != null) {
            mitte.addView(text(einheit, 10, FAINT, 0));
        }
        r.addView(mitte, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        r.addView(rundKnopf(R.drawable.ic_plus, CHIP, INK, knopfDp, v -> {
            plus.run();
            huepfen(wert);
        }));
        return r;
    }

    private void huepfen(View v) {
        v.setScaleX(1.14f);
        v.setScaleY(1.14f);
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
