package ch.fitapp.uhr;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
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
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * FitApp auf der Galaxy Watch. Eine einzige Activity ohne Bibliotheken ausser der Data Layer, damit sie klein bleibt.
 * Das Handy schickt das Paket (Plan, Streak, Trainings, Lieblingsessen), die Uhr schickt fertige Trainings und Essen zurück.
 * Der Pulssensor läuft nur während eines Trainings. Der Bildschirm bleibt nur während Pause und Halten an.
 */
public class UhrActivity extends Activity implements SensorEventListener, DataClient.OnDataChangedListener {

    static final String PAKET = "/fitapp/paket";
    static final String VON_UHR = "/fitapp/uhr/";
    private static final int REQ_PULS = 7;
    private static final long SECHS_STUNDEN = 6L * 3600 * 1000;

    /* Farben wie in der App: schwarz für den AMOLED Bildschirm, Akzente aus der Statistik */
    private static final int BG = 0xFF000000, KARTE = 0xFF161616, LINIE = 0xFF2A2A2A, INK = 0xFFFAFAFA, MUTED = 0xFFA3A3A3,
            FAINT = 0xFF6E6E6E, TRAINING = 0xFFD55181, KCAL = 0xFFD95926, PROTEIN = 0xFF3987E5, PULS = 0xFFE66767;

    private SharedPreferences prefs;
    private JSONObject paket;
    private JSONObject lauf;
    private String screen = "home";
    private String letzterScreen = "";
    private ScrollView scroll;
    private LinearLayout box;
    private float dp;
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView zeitView, pulsView;
    private SensorManager sm;
    private boolean pulsAn;
    private int pulsJetzt;
    private long pulsZuletzt;
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
        dp = m.density;
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        boolean rund = getResources().getConfiguration().isScreenRound();
        int seite = rund ? (int) (m.widthPixels * 0.13f) : px(10);
        box.setPadding(seite, rund ? (int) (m.heightPixels * 0.12f) : px(10), seite, rund ? (int) (m.heightPixels * 0.22f) : px(16));
        scroll.addView(box, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
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
        zeigen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            Wearable.getDataClient(this).addListener(this);
        } catch (Exception ignored) {
        }
        paketLaden();
        zeigen();
    }

    @Override
    protected void onPause() {
        try {
            Wearable.getDataClient(this).removeListener(this);
        } catch (Exception ignored) {
        }
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
        if (!"home".equals(screen)) {
            screen = "home";
            zeigen();
        } else {
            super.onBackPressed();
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
        if (p == null) {
            return;
        }
        if (paket != null && p.optLong("ts") <= paket.optLong("ts")) {
            return;
        }
        paket = p;
        prefs.edit().putString("paket", j).apply();
        if (!"training".equals(screen)) {
            zeigen();
        }
    }

    private boolean paketHeute() {
        return paket != null && heute().equals(paket.optString("tag"));
    }

    // ------------------------------------------------------------------ Anzeige

    private void zeigen() {
        h.removeCallbacks(tickRunner);
        zeitView = null;
        pulsView = null;
        box.removeAllViews();
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
                homeZeigen();
        }
        if (!screen.equals(letzterScreen)) {
            scroll.scrollTo(0, 0);
            letzterScreen = screen;
        }
        scroll.requestFocus();
    }

    private void homeZeigen() {
        if (paket == null) {
            box.addView(text("FitApp", 22, INK, true));
            box.addView(abstand(8));
            box.addView(text("Öffne FitApp auf dem Handy. Dann kommen Plan, Training und Essen hierher.", 14, MUTED, false));
            return;
        }
        boolean frisch = paketHeute();
        JSONObject st = paket.optJSONObject("streak");
        if (st != null) {
            box.addView(text(String.valueOf(st.optInt("n")), 40, INK, true));
            box.addView(text(st.optInt("n") == 1 ? "Tag Streak" : "Tage Streak", 13, MUTED, false));
            box.addView(abstand(6));
            box.addView(wocheReihe(st.optJSONArray("w"), frisch));
        }
        box.addView(abstand(12));
        jetztKarte();

        if (lauf != null) {
            box.addView(knopf("Training fortsetzen", lauf.optString("n"), TRAINING, true, v -> {
                screen = "training";
                zeigen();
            }));
        } else {
            JSONObject t = trainingHeute();
            if (t == null) {
                box.addView(text("Heute kein Pflicht Training", 13, MUTED, false));
            } else if (trainingErledigt(t)) {
                box.addView(knopf("✓ " + t.optString("n") + " erledigt", "Nochmals machen", 0, false, v -> starten(t)));
            } else {
                box.addView(knopf(t.optString("n") + " starten", "etwa " + t.optInt("min") + " Min", TRAINING, true, v -> starten(t)));
                JSONObject e = trainingVon(t.optString("er"));
                if (e != null) {
                    box.addView(knopf("Zuhause: " + e.optString("n"), "etwa " + e.optInt("min") + " Min", 0, false, v -> starten(e)));
                }
            }
        }
        JSONObject es = paket.optJSONObject("essen");
        if (es != null) {
            int[] s = essenSumme();
            box.addView(knopf("Essen eintragen", s[0] + " von " + es.optInt("kz") + " kcal", 0, false, v -> {
                screen = "essen";
                zeigen();
            }));
        }
        box.addView(knopf("Tagesplan", null, 0, false, v -> {
            screen = "plan";
            zeigen();
        }));
        box.addView(knopf("Anderes Training", null, 0, false, v -> {
            screen = "wahl";
            zeigen();
        }));
        if (!frisch) {
            box.addView(abstand(10));
            box.addView(text("Stand vom " + paket.optString("tag") + ". Öffne FitApp kurz auf dem Handy.", 12, FAINT, false));
        }
    }

    private View wocheReihe(JSONArray w, boolean frisch) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER);
        String[] tage = {"M", "D", "M", "D", "F", "S", "S"};
        int heuteI = wochentagIndex();
        for (int i = 0; i < 7; i++) {
            String c = w != null && i < w.length() ? w.optString(i) : "-";
            LinearLayout spalte = new LinearLayout(this);
            spalte.setOrientation(LinearLayout.VERTICAL);
            spalte.setGravity(Gravity.CENTER_HORIZONTAL);
            View punkt = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            if ("o".equals(c)) {
                g.setColor(INK);
            } else if ("f".equals(c)) {
                g.setColor(Color.TRANSPARENT);
                g.setStroke(px(2), PROTEIN);
            } else if ("m".equals(c)) {
                g.setColor(0xFF3A3A3A);
            } else if ("h".equals(c) && frisch) {
                g.setColor(Color.TRANSPARENT);
                g.setStroke(px(2), TRAINING);
            } else {
                g.setColor(0xFF1E1E1E);
            }
            punkt.setBackground(g);
            spalte.addView(punkt, new LinearLayout.LayoutParams(px(13), px(13)));
            TextView l = text(tage[i], 10, i == heuteI ? INK : FAINT, i == heuteI);
            l.setPadding(0, px(3), 0, 0);
            spalte.addView(l);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(19), LinearLayout.LayoutParams.WRAP_CONTENT);
            r.addView(spalte, lp);
        }
        return r;
    }

    private void jetztKarte() {
        JSONArray items = heuteItems();
        if (items == null || items.length() == 0) {
            return;
        }
        int cur = aktuellerEintrag(items);
        LinearLayout k = karte();
        k.addView(text("JETZT", 11, MUTED, true));
        JSONArray c = cur >= 0 ? items.optJSONArray(cur) : null;
        TextView t = text(c != null ? c.optString(1) : "Noch Nacht. Schlaf weiter", 14, INK, false);
        t.setMaxLines(4);
        t.setEllipsize(TextUtils.TruncateAt.END);
        k.addView(t);
        JSONArray n = cur + 1 < items.length() ? items.optJSONArray(cur + 1) : null;
        if (n != null) {
            TextView d = text("dann " + n.optString(0) + ": " + n.optString(1), 12, MUTED, false);
            d.setMaxLines(2);
            d.setEllipsize(TextUtils.TruncateAt.END);
            d.setPadding(0, px(4), 0, 0);
            k.addView(d);
        }
        box.addView(k);
    }

    private void planZeigen() {
        box.addView(text("Heute", 18, INK, true));
        JSONArray plan = paket != null ? paket.optJSONArray("plan") : null;
        JSONObject tag = plan != null ? plan.optJSONObject(wochentagIndex()) : null;
        if (tag != null && tag.optString("art").length() > 0) {
            box.addView(text(tag.optString("art"), 12, MUTED, false));
        }
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
                LinearLayout zeile = new LinearLayout(this);
                zeile.setOrientation(LinearLayout.VERTICAL);
                zeile.setPadding(px(10), px(7), px(10), px(7));
                if (jetzt) {
                    zeile.setBackground(rund(KARTE, LINIE, 14));
                }
                TextView z = text(it.optString(0), 11, "training".equals(it.optString(2)) ? TRAINING : jetzt ? INK : FAINT, true);
                z.setGravity(Gravity.START);
                TextView tx = text(it.optString(1), 13, vorbei ? FAINT : INK, jetzt);
                tx.setGravity(Gravity.START);
                zeile.addView(z);
                zeile.addView(tx);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = px(2);
                box.addView(zeile, lp);
                if (jetzt) {
                    ziel = zeile;
                }
            }
        }
        box.addView(knopf("Zurück", null, 0, false, v -> {
            screen = "home";
            zeigen();
        }));
        if (ziel != null) {
            final View zz = ziel;
            scroll.post(() -> scroll.scrollTo(0, Math.max(0, zz.getTop() - px(40))));
        }
    }

    private void wahlZeigen() {
        box.addView(text("Training wählen", 16, INK, true));
        box.addView(abstand(6));
        JSONArray ts = paket != null ? paket.optJSONArray("trainings") : null;
        if (ts != null) {
            for (int i = 0; i < ts.length(); i++) {
                JSONObject t = ts.optJSONObject(i);
                if (t != null) {
                    box.addView(knopf(t.optString("n"), t.optString("ti") + " · " + t.optInt("min") + " Min", 0, false, v -> starten(t)));
                }
            }
        }
        box.addView(knopf("Zurück", null, 0, false, v -> {
            screen = "home";
            zeigen();
        }));
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
            lauf.put("puls", new JSONObject().put("sum", 0).put("n", 0).put("max", 0).put("min", 0));
        } catch (Exception e) {
            return;
        }
        laufSpeichern();
        screen = "training";
        pulsStarten();
        zeigen();
    }

    private JSONObject aktUebung() {
        JSONArray ex = lauf.optJSONArray("ex");
        int i = lauf.optInt("i");
        return ex != null && i < ex.length() ? ex.optJSONObject(i) : null;
    }

    private int satzGesamt() {
        JSONArray ex = lauf.optJSONArray("ex");
        int n = 0;
        for (int i = 0; ex != null && i < ex.length(); i++) {
            n += ex.optJSONObject(i).optInt("s");
        }
        return n;
    }

    private int satzFertig() {
        JSONObject s = lauf.optJSONObject("sets");
        int n = 0;
        if (s != null) {
            JSONArray names = s.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                JSONArray a = s.optJSONArray(names.optString(i));
                n += a != null ? a.length() : 0;
            }
        }
        return n;
    }

    private void trainingZeigen() {
        JSONObject x = aktUebung();
        if (x == null) {
            beenden();
            return;
        }
        String phase = lauf.optString("phase", "satz");
        LinearLayout kopf = new LinearLayout(this);
        kopf.setOrientation(LinearLayout.HORIZONTAL);
        kopf.setGravity(Gravity.CENTER);
        pulsView = text(pulsJetzt > 0 ? "♥ " + pulsJetzt : "♥ ...", 13, PULS, true);
        pulsView.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        kopf.addView(pulsView);
        TextView zahl = text("   " + satzFertig() + "/" + satzGesamt() + " Sätze", 13, MUTED, false);
        zahl.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        kopf.addView(zahl);
        box.addView(kopf);
        box.addView(abstand(4));

        if ("pause".equals(phase) || "halten".equals(phase) || "bereit".equals(phase)) {
            String label;
            if ("pause".equals(phase)) {
                label = "PAUSE";
            } else if ("bereit".equals(phase)) {
                label = "BEREIT";
            } else {
                String teil = lauf.optString("teil");
                label = teil.length() > 0 ? teil.toUpperCase(Locale.GERMAN) : "HALTEN";
            }
            box.addView(text(label, 12, "pause".equals(phase) ? MUTED : TRAINING, true));
            zeitView = text("", 44, INK, true);
            zeitView.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            box.addView(zeitView);
            if ("pause".equals(phase)) {
                box.addView(text("Dann: " + x.optString("n") + ", Satz " + (lauf.optInt("j") + 1), 12, MUTED, false));
                LinearLayout r = reihe();
                r.addView(kleinKnopf("+15 s", v -> {
                    try {
                        lauf.put("bis", lauf.optLong("bis") + 15000);
                    } catch (Exception ignored) {
                    }
                    laufSpeichern();
                }));
                r.addView(kleinKnopf("Weiter", v -> phaseSatz()));
                box.addView(r);
            } else {
                box.addView(text(x.optString("n"), 13, MUTED, false));
                box.addView(knopf("Stopp", null, 0, false, v -> phaseSatz()));
            }
            bildschirmAn(true);
            h.post(tickRunner);
            return;
        }

        bildschirmAn(false);
        TextView name = text(x.optString("n"), 18, INK, true);
        name.setMaxLines(3);
        box.addView(name);
        int j = lauf.optInt("j"), s = x.optInt("s");
        box.addView(text("Satz " + (j + 1) + " von " + s, 13, TRAINING, true));
        if (x.optString("z").length() > 0) {
            TextView z = text(x.optString("z"), 12, MUTED, false);
            z.setMaxLines(2);
            box.addView(z);
        }
        box.addView(abstand(6));
        String einheit = x.optString("e");
        if ("Wdh".equals(einheit)) {
            werteVorbereiten(x, j);
            box.addView(stepper(fmt(wertR), "Wdh", () -> wertR = Math.max(0, wertR - 1), () -> wertR = Math.min(200, wertR + 1)));
            if (x.optInt("kg") == 1) {
                box.addView(stepper(fmt(wertW) + " kg", null,
                        () -> wertW = Math.max(0, wertW - (wertW > 20 ? 2.5 : 1)),
                        () -> wertW = Math.min(300, wertW + (wertW >= 20 ? 2.5 : 1))));
            }
            box.addView(knopf("Satz fertig", null, TRAINING, true, v -> satzErledigt(x.optInt("kg") == 1 ? fmt(wertW) : "", fmt(wertR))));
        } else {
            int sek = haltenSekunden(x, 0);
            box.addView(text(dauer(sek), 26, INK, true));
            box.addView(knopf("Start", null, TRAINING, true, v -> haltenStarten()));
            box.addView(knopf("Schon gemacht ✓", null, 0, false, v -> satzErledigt("", String.valueOf(x.optInt("so")))));
        }
        box.addView(abstand(6));
        box.addView(kleinZeile("Übung überspringen", v -> uebungUeberspringen()));
        box.addView(kleinZeile(System.currentTimeMillis() - beendenSeit < 4000 ? "Wirklich beenden? Nochmals tippen" : "Training beenden", v -> {
            if (System.currentTimeMillis() - beendenSeit < 4000) {
                beenden();
            } else {
                beendenSeit = System.currentTimeMillis();
                zeigen();
            }
        }));
    }

    private void werteVorbereiten(JSONObject x, int j) {
        String key = lauf.optInt("i") + ":" + j;
        if (key.equals(wertFuer)) {
            return;
        }
        wertFuer = key;
        String w = "", r = "";
        JSONArray v = x.optJSONArray("v");
        JSONArray sv = v != null ? v.optJSONArray(Math.min(j, v.length() - 1)) : null;
        if (sv != null) {
            w = sv.optString(0);
            r = sv.optString(1);
        }
        /* Im selben Training gilt der letzte Satz dieser Übung als Vorschlag für das Gewicht */
        JSONArray schon = lauf.optJSONObject("sets") != null ? lauf.optJSONObject("sets").optJSONArray(x.optString("id")) : null;
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

    private void haltenStarten() {
        try {
            lauf.put("phase", "bereit");
            lauf.put("k", 0);
            lauf.put("teil", "");
            lauf.put("bis", System.currentTimeMillis() + 3000);
        } catch (Exception ignored) {
        }
        laufSpeichern();
        zeigen();
    }

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
            int s = (int) Math.ceil(rest / 1000.0);
            zeitView.setText(dauer(s));
            if (!"pause".equals(lauf.optString("phase")) && s <= 3 && s != lauf.optInt("piep", -1)) {
                try {
                    lauf.put("piep", s);
                } catch (Exception ignored) {
                }
                vibrieren(new long[]{0, 40});
            }
            h.postDelayed(this, Math.min(500, rest % 1000 + 20));
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
                lauf.put("phase", "halten");
                lauf.put("teil", teilName(x, 0));
                lauf.put("bis", System.currentTimeMillis() + haltenSekunden(x, 0) * 1000L);
                vibrieren(new long[]{0, 200});
            } else if ("halten".equals(phase)) {
                int k = lauf.optInt("k") + 1;
                if (k < teileAnzahl(x)) {
                    lauf.put("k", k);
                    lauf.put("teil", teilName(x, k));
                    /* 5 Sekunden zum Wechseln, zum Beispiel die Seite */
                    lauf.put("bis", System.currentTimeMillis() + (5 + haltenSekunden(x, k)) * 1000L);
                    lauf.put("phase", "halten");
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
        zeigen();
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
        zeigen();
    }

    private void satzErledigt(String w, String r) {
        JSONObject x = aktUebung();
        if (x == null) {
            return;
        }
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
                laufSpeichern();
                beenden();
                return;
            }
            if (pause > 0) {
                lauf.put("phase", "pause");
                lauf.put("bis", System.currentTimeMillis() + pause * 1000L);
            } else {
                lauf.put("phase", "satz");
            }
        } catch (Exception ignored) {
        }
        laufSpeichern();
        zeigen();
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
            zeigen();
        }
    }

    private void beenden() {
        pulsStoppen();
        bildschirmAn(false);
        JSONObject l = lauf;
        lauf = null;
        prefs.edit().remove("lauf").apply();
        int fertig = 0;
        try {
            fertig = satzZaehlen(l);
        } catch (Exception ignored) {
        }
        if (l == null || fertig == 0) {
            screen = "home";
            zeigen();
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
            vibrieren(new long[]{0, 120, 80, 120, 80, 300});
        } catch (Exception ignored) {
        }
        screen = "fertig";
        zeigen();
    }

    private int satzZaehlen(JSONObject l) {
        JSONObject s = l == null ? null : l.optJSONObject("sets");
        int n = 0;
        JSONArray names = s != null ? s.names() : null;
        for (int i = 0; names != null && i < names.length(); i++) {
            n += s.optJSONArray(names.optString(i)).length();
        }
        return n;
    }

    private int satzGesamtVon(JSONObject l) {
        JSONArray ex = l.optJSONArray("ex");
        int n = 0;
        for (int i = 0; ex != null && i < ex.length(); i++) {
            n += ex.optJSONObject(i).optInt("s");
        }
        return n;
    }

    private void fertigZeigen() {
        JSONObject f = fertigInfo;
        box.addView(text("Gespeichert", 12, TRAINING, true));
        if (f != null) {
            box.addView(text(f.optString("n"), 20, INK, true));
            box.addView(text(f.optInt("f") + " von " + f.optInt("s") + " Sätzen · " + f.optInt("min") + " Min", 13, MUTED, false));
            if (f.optInt("avg") > 0) {
                box.addView(abstand(8));
                box.addView(text("♥ Ø " + f.optInt("avg") + " · max " + f.optInt("max"), 15, PULS, true));
            }
        }
        box.addView(abstand(6));
        box.addView(text("Geht ans Handy, sobald es in der Nähe ist.", 12, FAINT, false));
        box.addView(knopf("OK", null, 0, true, v -> {
            screen = "home";
            zeigen();
        }));
    }

    private void laufSpeichern() {
        if (lauf != null) {
            prefs.edit().putString("lauf", lauf.toString()).apply();
        }
    }

    // ------------------------------------------------------------------ Essen

    private void essenZeigen() {
        JSONObject es = paket != null ? paket.optJSONObject("essen") : null;
        if (es == null) {
            box.addView(text("Noch keine Daten vom Handy", 13, MUTED, false));
        } else {
            int[] s = essenSumme();
            box.addView(text(s[0] + " / " + es.optInt("kz") + " kcal", 17, KCAL, true));
            box.addView(text(s[1] + " / " + es.optInt("prz") + " g Protein", 13, PROTEIN, true));
            box.addView(abstand(8));
            JSONArray fav = es.optJSONArray("fav");
            if (fav == null || fav.length() == 0) {
                box.addView(text("Trag auf dem Handy etwas ein, dann erscheint es hier.", 12, MUTED, false));
            }
            for (int i = 0; fav != null && i < fav.length(); i++) {
                JSONObject f = fav.optJSONObject(i);
                if (f == null) {
                    continue;
                }
                box.addView(knopf((f.optInt("fav") == 1 ? "★ " : "") + f.optString("n"), f.optString("m") + " · " + f.optInt("kc") + " kcal", 0, false, v -> essenEintragen(f, v)));
            }
        }
        box.addView(knopf("Zurück", null, 0, false, v -> {
            screen = "home";
            zeigen();
        }));
    }

    private void essenEintragen(JSONObject f, View v) {
        if (!v.isEnabled()) {
            return;
        }
        v.setEnabled(false);
        v.setAlpha(0.5f);
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
        Toast.makeText(this, "Eingetragen: " + f.optString("n"), Toast.LENGTH_SHORT).show();
        h.postDelayed(this::zeigen, 900);
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
        if (s == null) {
            return;
        }
        pulsAn = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL);
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
            pulsView.setText("♥ " + v);
        }
        long jetzt = System.currentTimeMillis();
        /* Höchstens alle 2 Sekunden ein Wert für den Schnitt */
        if (lauf != null && jetzt - pulsZuletzt >= 2000) {
            pulsZuletzt = jetzt;
            JSONObject p = lauf.optJSONObject("puls");
            if (p != null) {
                try {
                    p.put("sum", p.optDouble("sum") + v);
                    p.put("n", p.optInt("n") + 1);
                    p.put("max", Math.max(p.optInt("max"), v));
                    p.put("min", p.optInt("min") == 0 ? v : Math.min(p.optInt("min"), v));
                } catch (Exception ignored) {
                }
                if (p.optInt("n") % 15 == 0) {
                    laufSpeichern();
                }
            }
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

    // ------------------------------------------------------------------ Hilfen: Plan und Datum

    private JSONObject trainingVon(String id) {
        JSONArray ts = paket != null ? paket.optJSONArray("trainings") : null;
        for (int i = 0; ts != null && i < ts.length(); i++) {
            JSONObject t = ts.optJSONObject(i);
            if (t != null && id != null && id.equals(t.optString("id"))) {
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
            if (t != null && t.optJSONArray("wds") != null) {
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
        return er != null && e.contains(heute() + ":" + er.optString("id") + ";");
    }

    private JSONArray heuteItems() {
        JSONArray plan = paket != null ? paket.optJSONArray("plan") : null;
        JSONObject tag = plan != null ? plan.optJSONObject(wochentagIndex()) : null;
        return tag != null ? tag.optJSONArray("items") : null;
    }

    /** Wie in der App: jeder Eintrag gilt, bis der nächste beginnt. Zeiten nach Mitternacht gehören zum Abend davor. */
    private int aktuellerEintrag(JSONArray items) {
        Calendar c = Calendar.getInstance();
        int jetzt = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int start = zuMin(items.optJSONArray(0).optString(0));
        int cur = -1;
        for (int i = 0; i < items.length(); i++) {
            int m = zuMin(items.optJSONArray(i).optString(0));
            if (m < 0) {
                continue;
            }
            if (m < start) {
                m += 1440;
            }
            if (m <= jetzt) {
                cur = i;
            }
        }
        return cur;
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

    // ------------------------------------------------------------------ Hilfen: Ansicht

    private int px(float d) {
        return Math.round(d * dp);
    }

    private TextView text(String s, float sp, int farbe, boolean fett) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(farbe);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        if (fett) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        t.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private View abstand(int d) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, px(d)));
        return v;
    }

    private GradientDrawable rund(int farbe, int rand, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(farbe);
        g.setCornerRadius(px(radius));
        if (rand != 0) {
            g.setStroke(px(1), rand);
        }
        return g;
    }

    private LinearLayout karte() {
        LinearLayout k = new LinearLayout(this);
        k.setOrientation(LinearLayout.VERTICAL);
        k.setPadding(px(12), px(10), px(12), px(10));
        k.setBackground(rund(KARTE, LINIE, 18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(8);
        k.setLayoutParams(lp);
        return k;
    }

    private LinearLayout reihe() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(8);
        r.setLayoutParams(lp);
        return r;
    }

    /** Knopf mit optionaler zweiter Zeile. akzent != 0 färbt den Rand, primaer macht ihn hell. */
    private View knopf(String titel, String unter, int akzent, boolean primaer, View.OnClickListener klick) {
        LinearLayout k = new LinearLayout(this);
        k.setOrientation(LinearLayout.VERTICAL);
        k.setGravity(Gravity.CENTER);
        k.setMinimumHeight(px(50));
        k.setPadding(px(14), px(8), px(14), px(8));
        int bg = primaer ? (akzent != 0 ? akzent : INK) : 0xFF1C1C1C;
        k.setBackground(rund(bg, primaer ? 0 : LINIE, 26));
        TextView t = text(titel, 15, primaer && akzent == 0 ? BG : INK, true);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        k.addView(t);
        if (unter != null && unter.length() > 0) {
            TextView u = text(unter, 12, primaer ? (akzent == 0 ? 0xFF333333 : 0xFFF5D6E2) : MUTED, false);
            u.setMaxLines(2);
            u.setEllipsize(TextUtils.TruncateAt.END);
            k.addView(u);
        }
        k.setClickable(true);
        k.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            klick.onClick(v);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(8);
        k.setLayoutParams(lp);
        return k;
    }

    private View kleinKnopf(String titel, View.OnClickListener klick) {
        TextView t = text(titel, 14, INK, true);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(px(46));
        t.setBackground(rund(0xFF1C1C1C, LINIE, 23));
        t.setClickable(true);
        t.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            klick.onClick(v);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = px(4);
        lp.rightMargin = px(4);
        t.setLayoutParams(lp);
        return t;
    }

    private View kleinZeile(String titel, View.OnClickListener klick) {
        TextView t = text(titel, 13, MUTED, false);
        t.setMinHeight(px(40));
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        t.setOnClickListener(klick);
        return t;
    }

    private View stepper(String wert, String einheit, Runnable minus, Runnable plus) {
        LinearLayout r = reihe();
        TextView m = rundKnopf("−");
        TextView w = text(wert + (einheit != null ? " " + einheit : ""), 22, INK, true);
        w.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        w.setGravity(Gravity.CENTER);
        TextView p = rundKnopf("+");
        m.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            minus.run();
            zeigen();
        });
        p.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            plus.run();
            zeigen();
        });
        r.addView(m);
        r.addView(w);
        r.addView(p);
        return r;
    }

    private TextView rundKnopf(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        t.setTextColor(INK);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xFF1C1C1C);
        g.setStroke(px(1), LINIE);
        t.setBackground(g);
        t.setClickable(true);
        t.setLayoutParams(new LinearLayout.LayoutParams(px(46), px(46)));
        return t;
    }

    private void bildschirmAn(boolean an) {
        if (an) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @SuppressWarnings("deprecation")
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
