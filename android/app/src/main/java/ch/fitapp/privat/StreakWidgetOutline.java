package ch.fitapp.privat;

public class StreakWidgetOutline extends BaseStreakWidget {
    @Override
    int art() {
        return WidgetBau.SQUARE;
    }

    @Override
    boolean soft() {
        return true;
    }

    @Override
    int layout() {
        return R.layout.widget_square_soft;
    }
}
