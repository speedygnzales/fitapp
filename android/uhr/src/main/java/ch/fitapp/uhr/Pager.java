package ch.fitapp.uhr;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * Seiten nebeneinander, zum Wischen nach links und rechts wie bei den Kacheln der Uhr.
 * Die Seite folgt dem Finger und rastet weich ein. Punkte am unteren Rand zeigen, wo man ist.
 * Wischt man auf der ersten Seite nach rechts, meldet der Pager "zurück". Die Lünette blättert auch.
 */
public class Pager extends ViewGroup {

    public interface Hoerer {
        void seite(int i);

        void zurueck();
    }

    private final List<View> seiten = new ArrayList<>();
    private int aktuell;
    private float startX, startY, letzteX;
    private boolean ziehen, unten;
    private final int slop;
    private VelocityTracker vt;
    private ValueAnimator anim;
    private Hoerer hoerer;
    private boolean punkte = true, oben;
    private final Paint punkt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float drehSumme;
    private long drehZuletzt;

    public Pager(Context c) {
        super(c);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setWillNotDraw(false);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    public void setHoerer(Hoerer h) {
        hoerer = h;
    }

    public void setPunkte(boolean p) {
        punkte = p;
        invalidate();
    }

    /** Punkte oben statt unten, wenn unten ein Knopf am Rand liegt */
    public void setPunkteOben(boolean o) {
        oben = o;
        invalidate();
    }

    public void addSeite(View v) {
        seiten.add(v);
        addView(v);
    }

    public int getAktuell() {
        return aktuell;
    }

    public int anzahl() {
        return seiten.size();
    }

    public View seite(int i) {
        return i >= 0 && i < seiten.size() ? seiten.get(i) : null;
    }

    public void geheZu(int i, boolean animiert) {
        i = Math.max(0, Math.min(seiten.size() - 1, i));
        boolean neu = i != aktuell;
        aktuell = i;
        int ziel = i * Math.max(1, getWidth());
        if (getWidth() == 0 || !animiert) {
            if (anim != null) {
                anim.cancel();
            }
            scrollTo(ziel, 0);
            if (neu && hoerer != null) {
                hoerer.seite(i);
            }
            return;
        }
        laufZu(ziel, neu);
    }

    private void laufZu(int ziel, boolean neu) {
        if (anim != null) {
            anim.cancel();
        }
        final int von = getScrollX();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(Math.min(320, 140 + Math.abs(ziel - von) / 3));
        anim.setInterpolator(new DecelerateInterpolator(1.8f));
        anim.addUpdateListener(a -> {
            scrollTo(Math.round(von + (ziel - von) * (float) a.getAnimatedValue()), 0);
            invalidate();
        });
        anim.start();
        if (neu) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            if (hoerer != null) {
                hoerer.seite(aktuell);
            }
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        for (View v : seiten) {
            v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        for (int i = 0; i < seiten.size(); i++) {
            seiten.get(i).layout(i * w, 0, (i + 1) * w, h);
        }
        if (anim == null || !anim.isRunning()) {
            scrollTo(aktuell * w, 0);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = letzteX = e.getX();
                startY = e.getY();
                ziehen = false;
                unten = true;
                if (anim != null && anim.isRunning()) {
                    anim.cancel();
                    ziehen = true;
                }
                tracken(e);
                return ziehen;
            case MotionEvent.ACTION_MOVE:
                tracken(e);
                if (!ziehen && pruefeStart(e)) {
                    return true;
                }
                return ziehen;
            default:
                return false;
        }
    }

    private boolean pruefeStart(MotionEvent e) {
        float dx = e.getX() - startX, dy = e.getY() - startY;
        if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
            ziehen = true;
            letzteX = e.getX();
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            return true;
        }
        return false;
    }

    private void tracken(MotionEvent e) {
        if (vt == null) {
            vt = VelocityTracker.obtain();
        }
        vt.addMovement(e);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        tracken(e);
        int w = Math.max(1, getWidth());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!unten) {
                    startX = letzteX = e.getX();
                    startY = e.getY();
                }
                unten = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!ziehen && !pruefeStart(e)) {
                    return true;
                }
                float delta = letzteX - e.getX();
                letzteX = e.getX();
                int max = (seiten.size() - 1) * w;
                float neu = getScrollX() + delta;
                /* Am Rand leistet die Seite Widerstand */
                if (neu < 0 || neu > max) {
                    neu = getScrollX() + delta * 0.35f;
                }
                scrollTo(Math.round(neu), 0);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                float vx = 0;
                if (vt != null) {
                    vt.computeCurrentVelocity(1000);
                    vx = vt.getXVelocity();
                    vt.recycle();
                    vt = null;
                }
                if (ziehen) {
                    int pos = getScrollX();
                    if (pos < -w * 0.18f && aktuell == 0 && hoerer != null) {
                        hoerer.zurueck();
                        laufZu(0, false);
                    } else {
                        int ziel = Math.round(pos / (float) w);
                        if (vx < -700 && pos > aktuell * w) {
                            ziel = aktuell + 1;
                        } else if (vx > 700 && pos < aktuell * w) {
                            ziel = aktuell - 1;
                        }
                        ziel = Math.max(0, Math.min(seiten.size() - 1, ziel));
                        boolean neuSeite = ziel != aktuell;
                        aktuell = ziel;
                        laufZu(ziel * w, neuSeite);
                    }
                }
                ziehen = false;
                return true;
            default:
                return true;
        }
    }

    /* Lünette oder Drehrad: eine Raste blättert eine Seite */
    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_SCROLL && e.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            drehSumme += -e.getAxisValue(MotionEvent.AXIS_SCROLL);
            long jetzt = System.currentTimeMillis();
            if (jetzt - drehZuletzt > 260 && Math.abs(drehSumme) >= 0.8f) {
                drehZuletzt = jetzt;
                geheZu(aktuell + (drehSumme > 0 ? 1 : -1), true);
                drehSumme = 0;
            }
            return true;
        }
        return super.onGenericMotionEvent(e);
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        int n = seiten.size();
        if (!punkte || n < 2) {
            return;
        }
        /* Punkte auf einem Bogen am unteren Rand, der helle Punkt wandert mit dem Finger */
        float w = getWidth(), h = getHeight();
        float cx = getScrollX() + w / 2f, cy = h / 2f;
        float radius = Math.min(w, h) / 2f - Masse.dp(getContext(), 15);
        float r = Masse.dp(getContext(), 3);
        float schritt = (float) Math.toDegrees(Masse.dp(getContext(), 11) / radius);
        float mitte = (n - 1) / 2f;
        punkt.setColor(0x4DFFFFFF);
        for (int i = 0; i < n; i++) {
            double a = Math.toRadians(oben ? 270 - (mitte - i) * schritt : 90 + (mitte - i) * schritt);
            c.drawCircle(cx + radius * (float) Math.cos(a), cy + radius * (float) Math.sin(a), r, punkt);
        }
        float pos = Math.max(0, Math.min(n - 1, getScrollX() / Math.max(1f, w)));
        double a = Math.toRadians(oben ? 270 - (mitte - pos) * schritt : 90 + (mitte - pos) * schritt);
        punkt.setColor(0xFFFFFFFF);
        c.drawCircle(cx + radius * (float) Math.cos(a), cy + radius * (float) Math.sin(a), r * 1.25f, punkt);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) {
            anim.cancel();
        }
        super.onDetachedFromWindow();
    }
}
