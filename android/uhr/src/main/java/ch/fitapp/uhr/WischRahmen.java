package ch.fitapp.uhr;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * Bildschirm, den man wie überall auf der Uhr mit einem Wisch nach rechts schliesst.
 * Er folgt dem Finger, darunter wird der vorherige Bildschirm sichtbar.
 */
public class WischRahmen extends FrameLayout {

    public interface Hoerer {
        void zug(float anteil);

        void zu();
    }

    private Hoerer hoerer;
    private Pager innen;
    private float startX, startY;
    private boolean ziehen, unten;
    private final int slop;
    private VelocityTracker vt;
    private ValueAnimator anim;

    public WischRahmen(Context c) {
        super(c);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setClickable(true);
    }

    public void setHoerer(Hoerer h) {
        hoerer = h;
    }

    /** Hat der Bildschirm eigene Seiten, gilt der Wisch nur auf der ersten Seite als Zurück */
    public void setInnen(Pager p) {
        innen = p;
    }

    private boolean darf() {
        return innen == null || innen.getAktuell() == 0;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = e.getX();
                startY = e.getY();
                ziehen = false;
                unten = true;
                tracken(e);
                return false;
            case MotionEvent.ACTION_MOVE:
                tracken(e);
                return pruefe(e);
            default:
                return false;
        }
    }

    private boolean pruefe(MotionEvent e) {
        if (ziehen) {
            return true;
        }
        float dx = e.getX() - startX, dy = e.getY() - startY;
        if (darf() && dx > slop && dx > Math.abs(dy) * 1.3f) {
            ziehen = true;
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
        }
        return ziehen;
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
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!unten) {
                    startX = e.getX();
                    startY = e.getY();
                }
                unten = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!pruefe(e)) {
                    return true;
                }
                float dx = Math.max(0, e.getX() - startX);
                setTranslationX(dx);
                if (hoerer != null) {
                    hoerer.zug(dx / Math.max(1f, getWidth()));
                }
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
                    if (getTranslationX() > getWidth() * 0.32f || vx > 900) {
                        schliessen();
                    } else {
                        zurueckFedern();
                    }
                }
                ziehen = false;
                return true;
            default:
                return true;
        }
    }

    public void schliessen() {
        laufe(getWidth(), true);
    }

    private void zurueckFedern() {
        laufe(0, false);
    }

    private void laufe(float ziel, boolean zu) {
        if (anim != null) {
            anim.cancel();
        }
        final float von = getTranslationX();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(zu ? 200 : 240);
        anim.setInterpolator(new DecelerateInterpolator(1.5f));
        anim.addUpdateListener(a -> {
            float x = von + (ziel - von) * (float) a.getAnimatedValue();
            setTranslationX(x);
            if (hoerer != null) {
                hoerer.zug(x / Math.max(1f, getWidth()));
            }
            if (zu && (float) a.getAnimatedValue() >= 1f && hoerer != null) {
                Hoerer hh = hoerer;
                hoerer = null;
                hh.zu();
            }
        });
        anim.start();
    }
}
