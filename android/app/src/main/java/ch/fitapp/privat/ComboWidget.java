package ch.fitapp.privat;

public class ComboWidget extends BaseStreakWidget {
    @Override
    int art() {
        return WidgetBau.COMBO;
    }

    @Override
    boolean soft() {
        return false;
    }

    @Override
    int layout() {
        return R.layout.widget_combo;
    }
}
