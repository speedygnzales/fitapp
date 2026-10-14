package ch.fitapp.privat;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** Gemeinsame Basis der sechs Streak Widgets. */
public abstract class BaseStreakWidget extends AppWidgetProvider {

    abstract int art();

    abstract boolean soft();

    abstract int layout();

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) {
            m.updateAppWidget(id, WidgetBau.views(c, art(), soft(), layout()));
        }
    }
}
