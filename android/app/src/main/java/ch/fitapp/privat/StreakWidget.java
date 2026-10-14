package ch.fitapp.privat;

public class StreakWidget extends BaseStreakWidget {
    @Override
    int art() {
        return WidgetBau.SQUARE;
    }

    @Override
    boolean soft() {
        return false;
    }

    @Override
    int layout() {
        return R.layout.widget_square;
    }
}
