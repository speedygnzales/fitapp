package ch.fitapp.privat;

public class WideWidget extends BaseStreakWidget {
    @Override
    int art() {
        return WidgetBau.WIDE;
    }

    @Override
    boolean soft() {
        return false;
    }

    @Override
    int layout() {
        return R.layout.widget_wide;
    }
}
