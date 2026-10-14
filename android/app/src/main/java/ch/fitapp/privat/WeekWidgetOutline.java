package ch.fitapp.privat;

public class WeekWidgetOutline extends BaseStreakWidget {
    @Override
    int art() {
        return WidgetBau.WEEK;
    }

    @Override
    boolean soft() {
        return true;
    }

    @Override
    int layout() {
        return R.layout.widget_week_soft;
    }
}
