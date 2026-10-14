package ch.fitapp.privat;

public class ComboWidgetOutline extends BaseStreakWidget {
    @Override
    int art() {
        return WidgetBau.COMBO;
    }

    @Override
    boolean soft() {
        return true;
    }

    @Override
    int layout() {
        return R.layout.widget_combo_soft;
    }
}
