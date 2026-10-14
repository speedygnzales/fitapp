package ch.fitapp.privat;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * Baut alle Streak Widgets. Vier Layouts (SQUARE, WEEK, WIDE, COMBO = alle drei zusammen), je dunkel oder transparent mit Rahmen (soft).
 * Die App schreibt Streak, Freezes, letzten geschafften Tag, Tag der Berechnung und die letzten 7 Tage.
 * Vergehen Tage ohne App, rechnet das Widget selbst weiter: verpasste Tage fressen Freezes.
 */
final class WidgetBau {

    static final int SQUARE = 0, WEEK = 1, WIDE = 2, COMBO = 3;
    static final Class<?>[] ALLE = {StreakWidget.class, StreakWidgetOutline.class, WeekWidget.class,
            WeekWidgetOutline.class, WideWidget.class, WideWidgetOutline.class, ComboWidget.class, ComboWidgetOutline.class};
    private static final String WOCHENTAG = "MTWTFSS";

    private WidgetBau() {
    }

    static void updateAll(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            for (Class<?> k : ALLE) {
                int[] ids = m.getAppWidgetIds(new ComponentName(c, k));
                if (ids == null) {
                    continue;
                }
                for (int id : ids) {
                    BaseStreakWidget w = (BaseStreakWidget) k.getDeclaredConstructor().newInstance();
                    m.updateAppWidget(id, views(c, w.art(), w.soft(), w.layout()));
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** streak, freezes, geschafft (0/1), Anzahl verpasster Tage seit der Berechnung. */
    static int[] rechne(int streak, int freezes, String last, String calc, LocalDate heute) {
        boolean geschafft = last != null && last.equals(heute.toString());
        int verpasst = 0;
        if (!geschafft && calc != null && !calc.isEmpty()) {
            try {
                LocalDate ab = LocalDate.parse(calc);
                if (calc.equals(last)) {
                    ab = ab.plusDays(1);
                }
                verpasst = (int) Math.max(0, ChronoUnit.DAYS.between(ab, heute));
            } catch (Exception ignored) {
            }
        }
        int f = freezes;
        if (streak > 0 && verpasst > 0) {
            if (verpasst > freezes) {
                streak = 0;
                f = 0;
            } else {
                f = freezes - verpasst;
            }
        }
        return new int[]{streak, f, geschafft ? 1 : 0, verpasst};
    }

    /** Die letzten 7 Tage bis heute: o geschafft, f Freeze, m verpasst, x heute offen. */
    static char[] woche(String tage, int streak, int freezes, String last, String calc, LocalDate heute) {
        Map<String, Character> bekannt = new HashMap<>();
        if (tage != null) {
            for (String teil : tage.split(";")) {
                String[] kv = teil.split(":");
                if (kv.length == 2 && kv[1].length() == 1) {
                    bekannt.put(kv[0], kv[1].charAt(0));
                }
            }
        }
        int[] r = rechne(streak, freezes, last, calc, heute);
        int verpasst = r[3];
        char[] out = new char[7];
        for (int i = 0; i < 7; i++) {
            LocalDate d = heute.minusDays(6 - i);
            long vorHeute = ChronoUnit.DAYS.between(d, heute);
            if (vorHeute == 0) {
                out[i] = r[2] == 1 ? 'o' : 'x';
            } else if (vorHeute <= verpasst) {
                int k = verpasst - (int) vorHeute;
                out[i] = (streak > 0 && k < freezes) ? 'f' : 'm';
            } else {
                Character v = bekannt.get(d.toString());
                out[i] = v != null ? v : (d.toString().equals(last) ? 'o' : 'm');
            }
        }
        return out;
    }

    /** Name des Trainings an diesem Tag aus "1=Chest;2=Running" (0 Sonntag bis 6 Samstag), sonst leer. */
    static String planFuer(String plan, LocalDate d) {
        if (plan == null || plan.isEmpty()) {
            return "";
        }
        String tag = String.valueOf(d.getDayOfWeek().getValue() % 7);
        for (String teil : plan.split(";")) {
            int i = teil.indexOf('=');
            if (i > 0 && teil.substring(0, i).equals(tag)) {
                return teil.substring(i + 1);
            }
        }
        return "";
    }

    static RemoteViews views(Context c, int art, boolean soft, int layout) {
        SharedPreferences p = c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String last = p.getString("st_last", "");
        String calc = p.getString("st_calc", "");
        int st = p.getInt("st_streak", 0), fr = p.getInt("st_freezes", 0);
        LocalDate heute = LocalDate.now();
        int[] r = rechne(st, fr, last, calc, heute);
        int streak = r[0], freezes = r[1];
        boolean geschafft = r[2] == 1;

        RemoteViews v = new RemoteViews(c.getPackageName(), layout);
        v.setTextViewText(R.id.streak_num, String.valueOf(streak));

        String status;
        if (geschafft) {
            status = "Done today";
        } else if (streak > 0) {
            status = "Open today";
        } else if (last == null || last.isEmpty()) {
            status = "Start today";
        } else {
            status = "Start again";
        }

        int flamme;
        if (soft) {
            flamme = streak > 0 || geschafft ? R.drawable.ic_flame_soft : R.drawable.ic_flame_softdim;
        } else {
            flamme = streak > 0 || geschafft ? R.drawable.ic_flame_light : R.drawable.ic_flame_dim;
        }
        int frzAn = soft ? R.drawable.ic_freeze_soft_on : R.drawable.ic_freeze_on;
        int frzAus = soft ? R.drawable.ic_freeze_soft_off : R.drawable.ic_freeze_off;

        // Welche Teile hat dieses Layout?
        boolean mitPlan = art == WIDE || art == COMBO;
        boolean mitStatus = art == SQUARE || art == WIDE || art == COMBO;
        boolean zweiFreezes = art == SQUARE || art == WIDE || art == COMBO;
        boolean mitBox = art == WIDE || art == COMBO;
        boolean mitPunkt = art == SQUARE || art == COMBO;
        boolean mitWoche = art == WEEK || art == COMBO;

        if (mitPlan) {
            String plan = p.getString("st_plan", "");
            if (geschafft) {
                String morgen = planFuer(plan, heute.plusDays(1));
                if (!morgen.isEmpty()) {
                    status += " · Tomorrow: " + morgen;
                }
            } else {
                String jetzt = planFuer(plan, heute);
                if (!jetzt.isEmpty()) {
                    status += " · " + jetzt;
                }
            }
        }
        if (mitStatus) {
            v.setTextViewText(R.id.streak_status, status);
        }
        if (zweiFreezes) {
            v.setImageViewResource(R.id.freeze1, freezes >= 1 ? frzAn : frzAus);
            v.setImageViewResource(R.id.freeze2, freezes >= 2 ? frzAn : frzAus);
        } else {
            v.setTextViewText(R.id.freeze_count, freezes + "/2");
            v.setImageViewResource(R.id.freeze1, freezes >= 1 ? frzAn : frzAus);
        }
        if (mitBox) {
            if (geschafft) {
                v.setInt(R.id.flame_box, "setBackgroundResource", soft ? R.drawable.flame_on_s : R.drawable.widget_flame_on);
                v.setImageViewResource(R.id.flame_icon, R.drawable.ic_flame_dark);
            } else {
                v.setInt(R.id.flame_box, "setBackgroundResource", soft ? R.drawable.flame_off_s : R.drawable.widget_flame_off);
                v.setImageViewResource(R.id.flame_icon, flamme);
            }
        } else {
            v.setImageViewResource(R.id.flame_icon, flamme);
        }
        if (mitPunkt) {
            v.setInt(R.id.status_dot, "setBackgroundResource",
                    geschafft ? (soft ? R.drawable.sq_ok_s : R.drawable.sq_ok) : (soft ? R.drawable.sq_open_s : R.drawable.sq_open));
        }
        if (art == WEEK) {
            v.setTextViewText(R.id.streak_unit, streak == 1 ? "day" : "days");
        }
        if (mitWoche) {
            char[] w = woche(p.getString("st_tage", ""), st, fr, last, calc, heute);
            int[] kasten = {R.id.s0, R.id.s1, R.id.s2, R.id.s3, R.id.s4, R.id.s5, R.id.s6};
            int[] buchst = {R.id.d0, R.id.d1, R.id.d2, R.id.d3, R.id.d4, R.id.d5, R.id.d6};
            for (int i = 0; i < 7; i++) {
                LocalDate d = heute.minusDays(6 - i);
                v.setTextViewText(buchst[i], String.valueOf(WOCHENTAG.charAt(d.getDayOfWeek().getValue() - 1)));
                int bg;
                int icon = R.drawable.leer;
                switch (w[i]) {
                    case 'o':
                        bg = soft ? R.drawable.sq_ok_s : R.drawable.sq_ok;
                        break;
                    case 'f':
                        bg = soft ? R.drawable.sq_frz_s : R.drawable.sq_frz;
                        icon = frzAn;
                        break;
                    case 'x':
                        bg = soft ? R.drawable.sq_open_s : R.drawable.sq_open;
                        break;
                    default:
                        bg = soft ? R.drawable.sq_miss_s : R.drawable.sq_miss;
                }
                v.setInt(kasten[i], "setBackgroundResource", bg);
                v.setImageViewResource(kasten[i], icon);
            }
        }

        Intent open = new Intent(c, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 3, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.widget_root, pi);
        return v;
    }
}
