package dev.spinner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Choreographer;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

/**
 * Live fidget spinner shown inside the home-screen widget, drawn 1:1 like the Nothing Playground
 * "Spinner" widget: flat dark rounded SQUARE background + grey three-lobe spinner.
 * The square is fitted into the launcher's visual widget area (the same box vivo uses for its own
 * 2x2 folders / the resize frame): the host cell minus side / top / bottom (label) insets.
 * <ul>
 *   <li>grab the spinner and drag around: it follows the finger; on release it keeps the flick speed and
 *       slows down by friction (inertia);</li>
 *   <li>tap: a quick push;</li>
 *   <li>long-press without moving: handed to the launcher, so move / resize / remove work like with any widget;</li>
 *   <li>touches outside the spinner go to the launcher as usual.</li>
 * </ul>
 * Animation runs on Choreographer only while the spinner moves AND is really on screen: on another home-screen
 * page, with the launcher in the background or the screen off it draws nothing; when it shows up again the physics
 * is advanced by the hidden time, so it looks like it kept turning. At low speed it asks for 60 / 30 Hz, not the panel max.
 * "Hidden" also covers screen off / AOD (display not ON) and a faded-out page (app drawer, -1 screen, folder).
 * While hidden nothing runs except a cheap pre-draw check, and only until the spin would have stopped anyway:
 * then the physics is finished in one go and the view goes completely idle (no listener, no frame callbacks).
 */
final class SpinnerView extends View implements Choreographer.FrameCallback {
    // Colours and proportions measured from the original widget (fractions of the square side).
    private static final int BG = 0xFF191919;
    private static final int BODY = 0xFF3F3F3F;        // arms + outer part of the lobes
    private static final int LOBE_RING = 0xFF545454;
    private static final int HUB = 0xFF545454;
    private static final int HUB_RING = 0xFF656565;
    private static final int HOLE = 0xFF292929;
    private static final float ORBIT = 0.295f;         // hub centre -> lobe centre
    private static final float OUTER = 0.1392f;        // lobe / hub radius
    private static final float RING = 0.0838f;         // inner ring radius
    private static final float HOLE_R = 0.0425f;       // centre hole radius
    private static final float ARM_HALF = 0.0592f;     // half width of an arm
    private static final float CORNER = 0.14f;         // corner radius of the square (matches vivo frame)

    // vivo launcher visual widget box inside the host cell (measured on OriginOS: 20 / 23 / 64 px @3.5x)
    private static final float INSET_SIDE_DP = 5.7f;
    private static final float INSET_TOP_DP = 6.6f;
    private static final float INSET_BOTTOM_DP = 18.3f;

    private static final float MAX_SPEED = 3600f;       // deg/s
    private static final float TAP_SPEED = 1100f;       // deg/s
    private static final float STOP_SPEED = 6f;         // deg/s
    private static final float SLOW_SPEED = 400f;       // deg/s: below this 60 Hz looks the same as 120 Hz
    private static final float CRAWL_SPEED = 60f;       // deg/s: below this 30 Hz is enough
    private static final float DECAY_30 = (float) Math.exp(-0.55f / 30f);
    private static final float CATCH_UP_MAX_S = 30f;    // a spin never lasts longer than this
    private static final float STEP_S = 1f / 120f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean interactive;
    private final float density;
    private final int touchSlop;

    private float angle;          // degrees
    private float speed;          // deg/s, signed
    private boolean running;
    private long lastFrame;

    private float cx, cy, side, radius, downX, downY, lastX, lastY;
    private long lastMove;
    private boolean tracking, spinGesture, handedOff;
    private float grabSpeed;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable longPress = this::handOffLongPress;
    private final Runnable expire = this::expireHidden;

    private final Rect visRect = new Rect();
    private long hiddenSince;          // System.nanoTime() when a spin was suspended because the view is hidden; 0 = not
    private ViewTreeObserver watched;
    private float requestedRate = Float.NaN;
    /** Only registered while a spin is suspended: the launcher draws -> check whether we are back on screen. */
    private final ViewTreeObserver.OnPreDrawListener showWatcher = () -> {
        if (hiddenSince != 0 && onScreen()) resumeHidden();
        return true;
    };

    SpinnerView(Context c, boolean interactive) {
        super(c);
        this.interactive = interactive;
        density = c.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        fill.setStyle(Paint.Style.FILL);
        setContentDescription("Spinner");
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float l = getPaddingLeft() + INSET_SIDE_DP * density;
        float r = w - getPaddingRight() - INSET_SIDE_DP * density;
        float t = getPaddingTop() + INSET_TOP_DP * density;
        float b = h - getPaddingBottom() - INSET_BOTTOM_DP * density;
        if (r - l <= 0f || b - t <= 0f) {           // very small host: no insets
            l = getPaddingLeft(); r = w - getPaddingRight();
            t = getPaddingTop(); b = h - getPaddingBottom();
        }
        cx = (l + r) / 2f;
        cy = (t + b) / 2f;
        side = Math.max(0f, Math.min(r - l, b - t));  // 1:1 square inside the launcher's visual box
        radius = side * (ORBIT + OUTER);              // outer extent of the spinner = touch area
    }

    @Override protected void onDraw(Canvas c) {
        float s = side;
        if (s <= 0f) return;

        // square background (does not rotate)
        float half = s / 2f, rr = s * CORNER;
        fill.setColor(BG);
        c.drawRoundRect(cx - half, cy - half, cx + half, cy + half, rr, rr, fill);

        float orbit = s * ORBIT, outer = s * OUTER, ring = s * RING, hole = s * HOLE_R, armH = s * ARM_HALF;
        c.save();
        c.rotate(angle, cx, cy);
        fill.setColor(BODY);
        for (int i = 0; i < 3; i++) {
            c.save();
            c.rotate(i * 120f, cx, cy);
            c.drawRect(cx, cy - armH, cx + orbit, cy + armH, fill);
            c.restore();
        }
        for (int i = 0; i < 3; i++) {
            double a = Math.toRadians(i * 120);
            float lx = cx + (float) Math.cos(a) * orbit, ly = cy + (float) Math.sin(a) * orbit;
            fill.setColor(BODY);
            c.drawCircle(lx, ly, outer, fill);
            fill.setColor(LOBE_RING);
            c.drawCircle(lx, ly, ring, fill);
            fill.setColor(HOLE);
            c.drawCircle(lx, ly, hole, fill);
        }
        fill.setColor(HUB);
        c.drawCircle(cx, cy, outer, fill);
        fill.setColor(HUB_RING);
        c.drawCircle(cx, cy, ring, fill);
        fill.setColor(HOLE);
        c.drawCircle(cx, cy, hole, fill);
        c.restore();
    }

    // ---------- physics ----------
    private void start() {
        if (running || speed == 0f) return;
        if (!onScreen()) {
            suspendHidden();
            return;
        }
        running = true;
        lastFrame = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    /** Stops the frame loop but keeps the current speed (used when grabbed). */
    private void pause() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    /**
     * Really visible: window shown, screen on (not off / AOD), all parents visible and not faded out,
     * and not on another page / outside the screen.
     */
    private boolean onScreen() {
        if (!isAttachedToWindow() || getWindowVisibility() != VISIBLE || !isShown()) return false;
        Display d = getDisplay();
        if (d != null) {
            int st = d.getState();
            if (st == Display.STATE_OFF || st == Display.STATE_DOZE || st == Display.STATE_DOZE_SUSPEND) return false;
        }
        for (View v = this; ; ) {
            if (v.getAlpha() <= 0.01f) return false;
            ViewParent p = v.getParent();
            if (!(p instanceof View)) break;
            v = (View) p;
        }
        return getGlobalVisibleRect(visRect);
    }

    /** Hidden while spinning: stop drawing, remember when, wait for the launcher to show us again. */
    private void suspendHidden() {
        pause();
        frameRate(Float.NaN);
        if (speed == 0f) return;
        long now = System.nanoTime();
        if (hiddenSince == 0) hiddenSince = now;
        watch(true);
        // stop even the pre-draw check once the spin would have ended on its own
        long left = spinMillisLeft() - (now - hiddenSince) / 1_000_000L;
        handler.removeCallbacks(expire);
        handler.postDelayed(expire, Math.max(0L, left) + 100L);
    }

    /** How long the current spin keeps turning without a touch (coarse 30 Hz simulation, cheap). */
    private long spinMillisLeft() {
        float v = Math.abs(speed), t = 0f;
        while (v > STOP_SPEED && t < CATCH_UP_MAX_S) {
            v = v * DECAY_30 - 22f / 30f;
            t += 1f / 30f;
        }
        return (long) (t * 1000f);
    }

    /** Still hidden when the spin is over: finish the physics now and go fully idle. */
    private void expireHidden() {
        if (hiddenSince == 0 || running) return;
        catchUp();
        if (speed != 0f) start();       // rounding left a little speed: start() suspends again if still hidden
    }

    /** Back on screen: advance the physics by the hidden time (no frames were drawn meanwhile) and continue. */
    private void resumeHidden() {
        if (hiddenSince == 0) return;
        catchUp();
        start();
    }

    /** Advances the physics by the hidden time and drops the hidden-state watchers. */
    private void catchUp() {
        float t = Math.min((System.nanoTime() - hiddenSince) / 1e9f, CATCH_UP_MAX_S);
        hiddenSince = 0;
        watch(false);
        handler.removeCallbacks(expire);
        for (float done = 0f; done < t && speed != 0f; done += STEP_S) step(STEP_S);
        invalidate();
    }

    private void watch(boolean on) {
        ViewTreeObserver cur = on && isAttachedToWindow() ? getViewTreeObserver() : null;
        if (cur == watched) return;
        if (watched != null && watched.isAlive()) watched.removeOnPreDrawListener(showWatcher);
        watched = cur;
        if (cur != null) cur.addOnPreDrawListener(showWatcher);
    }

    private void step(float dt) {
        angle = (angle + speed * dt) % 360f;
        // bearing friction: proportional (air) + constant (bearing) part
        speed *= (float) Math.exp(-0.55f * dt);
        float s = Math.abs(speed) - 22f * dt;
        speed = s <= STOP_SPEED ? 0f : Math.copySign(s, speed);
    }

    /** Android 15+: a slowly turning spinner does not need 120 Hz; lets the panel drop to 60 Hz. */
    private void frameRate(float r) {
        if (Build.VERSION.SDK_INT < 35 || Float.compare(r, requestedRate) == 0) return;
        requestedRate = r;
        try {
            setRequestedFrameRate(r);
        } catch (Throwable ignored) { }
    }

    @Override public void doFrame(long now) {
        if (!running) return;
        if (!onScreen()) {
            suspendHidden();
            return;
        }
        if (lastFrame == 0) lastFrame = now;
        float dt = Math.min((now - lastFrame) / 1e9f, 0.05f);
        lastFrame = now;
        step(dt);
        float a = Math.abs(speed);
        frameRate(a == 0f ? Float.NaN : a < CRAWL_SPEED ? 30f : a < SLOW_SPEED ? 60f : Float.NaN);
        invalidate();
        if (speed == 0f) running = false;
        else Choreographer.getInstance().postFrameCallback(this);
    }

    // ---------- touch ----------
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!interactive) return false;
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (Math.hypot(x - cx, y - cy) > radius) return false;   // outside the spinner: launcher handles it
                if (hiddenSince != 0) resumeHidden();
                tracking = true;
                spinGesture = false;
                handedOff = false;
                grabSpeed = 0f;
                downX = lastX = x;
                downY = lastY = y;
                lastMove = System.nanoTime();
                disallowParents(true);      // keep page swipes from stealing the flick
                cancelParentsLongPress();   // our own long-press decides when the launcher gets it
                handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!tracking || handedOff) return true;
                long now = System.nanoTime();
                float dt = Math.max((now - lastMove) / 1e9f, 1f / 240f);
                if (!spinGesture && Math.hypot(x - downX, y - downY) > touchSlop) {
                    spinGesture = true;            // finger moved: a flick, not a long-press
                    handler.removeCallbacks(longPress);
                    cancelParentsLongPress();
                    pause();                       // grab the spinner
                    speed = 0f;
                }
                if (spinGesture) {
                    double a0 = Math.atan2(lastY - cy, lastX - cx);
                    double a1 = Math.atan2(y - cy, x - cx);
                    double d = a1 - a0;
                    if (d > Math.PI) d -= 2 * Math.PI;
                    if (d < -Math.PI) d += 2 * Math.PI;
                    float deg = (float) Math.toDegrees(d);
                    angle = (angle + deg) % 360f;
                    grabSpeed = 0.6f * grabSpeed + 0.4f * (deg / dt);
                    invalidate();
                }
                lastX = x;
                lastY = y;
                lastMove = now;
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                handler.removeCallbacks(longPress);
                boolean mine = tracking && !handedOff;
                tracking = false;
                if (!mine) return true;
                disallowParents(false);
                cancelParentsLongPress();
                if (spinGesture) {
                    // finger stopped before release -> no fling
                    if ((System.nanoTime() - lastMove) > 80_000_000L) grabSpeed = 0f;
                    speed = Math.max(-MAX_SPEED, Math.min(MAX_SPEED, grabSpeed));
                    if (Math.abs(speed) <= STOP_SPEED) speed = 0f;
                    start();
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    // tap: add a push in the current direction
                    float dir = speed < 0 ? -1f : 1f;
                    speed = Math.max(-MAX_SPEED, Math.min(MAX_SPEED, speed + dir * TAP_SPEED));
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    start();
                }
                return true;
            }
        }
        return true;
    }

    /** Finger held still: give the gesture to the launcher (move / resize / remove the widget). */
    private void handOffLongPress() {
        if (!tracking || spinGesture) return;
        handedOff = true;
        disallowParents(false);
        int depth = 0;
        for (ViewParent p = getParent(); p instanceof View && depth < 8; p = p.getParent(), depth++) {
            View v = (View) p;
            if (v.isLongClickable() && v.performLongClick()) return;
        }
    }

    private void disallowParents(boolean disallow) {
        ViewParent p = getParent();
        if (p != null) p.requestDisallowInterceptTouchEvent(disallow);
    }

    private void cancelParentsLongPress() {
        int depth = 0;
        for (ViewParent p = getParent(); p instanceof View && depth < 3; p = p.getParent(), depth++) {
            ((View) p).cancelLongPress();
        }
    }

    // ---------- lifecycle ----------
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (hiddenSince != 0) resumeHidden();
        else start();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            if (hiddenSince != 0) resumeHidden();
            else start();
        } else if (running) {
            suspendHidden();
        }
    }

    @Override public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (!isVisible && running) suspendHidden();
        else if (isVisible && hiddenSince != 0) resumeHidden();
    }

    @Override protected void onDetachedFromWindow() {
        if (speed != 0f && hiddenSince == 0) hiddenSince = System.nanoTime();
        pause();
        watch(false);
        handler.removeCallbacks(longPress);
        handler.removeCallbacks(expire);
        super.onDetachedFromWindow();
    }
}
