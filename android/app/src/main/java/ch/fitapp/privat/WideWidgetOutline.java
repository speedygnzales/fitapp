package ch.fitapp.privat;

public class WideWidgetOutline extends BaseStreakWidget {
    @Override
    int art() {
        return WidgetBau.WIDE;
    }

    @Override
    boolean soft() {
        return true;
    }

    @Override
    int layout() {
        return R.layout.widget_wide_soft;
    }
}
