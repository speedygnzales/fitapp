package ch.fitapp.privat;

public class WeekWidget extends BaseStreakWidget {
    @Override
    int art() {
        return WidgetBau.WEEK;
    }

    @Override
    boolean soft() {
        return false;
    }

    @Override
    int layout() {
        return R.layout.widget_week;
    }
}
