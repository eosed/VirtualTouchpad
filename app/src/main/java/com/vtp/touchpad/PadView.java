package com.vtp.touchpad;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 虚拟触控板本体：两枚同心圆 —— 外层圆盘当底座，内层圆盘像摇杆帽，
 * 手指按在哪边它就朝哪边倾，松手回弹到中心；边缘完全羽化，不放任何图标。
 * 三种材质风格可切换（PadPrefs.style）：0 磨砂玻璃、1 拟物内凹、2 摇杆帽。
 * 交互：
 *   中心区按住不动 420ms 后松手 → 长按；
 *   外圈按住不动 3 秒（振动一下提示）→ 进入挪板模式，之后拖动即移动触控板位置；
 *   外圈落指后立刻移动 → 仍按普通规则处理，算移动光标；
 *   短促轻点（≤300ms、位移 ≤24dp）→ 单击，落在外圈也算；
 *   其余移动 → 移动光标。
 */
class PadView extends View {

    interface Listener {
        /** 手指相对位移，px。 */
        void onMove(float dx, float dy);

        void onClick();

        void onLongPress();

        /** 3 秒长按外圈确认，即将开始挪板（振动已在此发出）。 */
        void onPadDragStart();

        /** 挪板：相对按下点的累计位移。 */
        void onPadDrag(float totalDx, float totalDy);

        /** 挪板结束（此时才该把手势写进设置）。 */
        void onPadDragEnd();

        /** 落指（用于取消闲置收起，并让摇杆恢复亮度）。 */
        void onTouchDown();

        /** 连续 10 秒没有触摸：应收起到贴边状态。 */
        void onIdle();
    }

    static final int STYLE_GLASS = 0;
    static final int STYLE_NEUMO = 1;
    static final int STYLE_JOYSTICK = 2;

    private static final long LONG_PRESS_MS = 420;
    /** 按住外圈多久进入挪板模式（用户要求 3 秒以上，且振动提示）。 */
    private static final long EDGE_HOLD_MS = 3000;
    /** 开始移动光标的位移阈值，取手指自然抖动的两倍。 */
    private static final float SLOP_DP = 4f;
    /** 仍算单击的最大位移与时长。 */
    private static final float TAP_SLOP_DP = 24f;
    private static final long TAP_MAX_MS = 300;
    /** 边缘落指要真正开始挪板前，必须先移出的距离。 */
    private static final float DRAG_SLOP_DP = 6f;
    /** 内圆半径占外圆半径的比例。 */
    private static final float KNOB_RATIO = 0.62f;
    /** 内圆最大偏移占外圆半径的比例。 */
    private static final float KNOB_TRAVEL = 0.30f;

    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint socket = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 摇杆帽顶面：正中心那枚更小、略暗的小圆。 */
    private final Paint cap = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float slop, tapSlop, dragSlop, density;
    private Listener listener;
    private int alphaPercent;
    private int style;
    private float radius;
    /** 内盘相对中心的偏移（摇杆倾摆）。 */
    private float knobDx, knobDy;
    private ValueAnimator spring;
    /** 闲置多久收起贴边，由服务端统一处理。 */
    private static final long IDLE_MS = 10000;

    private final Runnable idleRunnable = () -> {
        if (listener != null) listener.onIdle();
    };

    private float downX, downY, lastX, lastY;
    /** 挪板用原始屏幕坐标：窗口自己在动，用窗口内坐标会形成反馈回路、抖成一团。 */
    private float downRawX, downRawY;
    private float maxDisp;
    private long downTime;
    private boolean moved, draggingPad, holdArmed;
    /** 外圈落指，正等待 3 秒长按转挪板。 */
    private boolean edgeHoldPending;
    /** 收起成贴边小点时禁止挪板：此时点一下只该唤醒，不该拖着走。 */
    private boolean lockDrag;

    PadView(Context c, int alphaPercent, int style) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        slop = SLOP_DP * density;
        tapSlop = TAP_SLOP_DP * density;
        dragSlop = DRAG_SLOP_DP * density;
        this.alphaPercent = Math.max(20, Math.min(90, alphaPercent));
        this.style = style;
        shadow.setStyle(Paint.Style.FILL);
        gloss.setStyle(Paint.Style.FILL);
        buildShaders();
    }

    void setListener(Listener l) { listener = l; }

    void setLockDrag(boolean v) { lockDrag = v; }

    private void buildShaders() {
        radius = Math.min(getWidth(), getHeight()) / 2f;
        if (radius <= 0) return;
        float a = alphaPercent / 100f;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = radius, kr = r * KNOB_RATIO;

        base.setShader(null);
        socket.setShader(null);
        body.setShader(null);
        shadow.setShader(null);
        gloss.setShader(null);
        cap.setShader(null);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(density, r * 0.010f));

        switch (style) {
            case STYLE_GLASS: {
                // 原版观感：近乎平涂的灰白半透明外盘（边缘一道浅亮描边）
                //            + 一枚边缘硬朗、左上受光的凸起摇杆帽
                // 外盘：实机量出来近乎平涂的灰（220.9），只有极缓的上下明暗
                base.setShader(new RadialGradient(cx - r * 0.10f, cy - r * 0.14f, r * 1.18f,
                        new int[]{
                                Color.argb((int) (255 * a), 244, 245, 248),
                                Color.argb((int) (255 * a), 240, 241, 244),
                                Color.argb((int) (255 * a), 236, 237, 241),
                                Color.argb((int) (255 * a), 234, 235, 239),
                                Color.argb((int) (255 * a), 240, 241, 245),
                        }, new float[]{0f, 0.42f, 0.72f, 0.94f, 1f}, Shader.TileMode.CLAMP));
                // 凹槽：帽外一圈暗环，明确分出内外两个圆
                socket.setShader(new RadialGradient(cx, cy, kr * 1.20f,
                        new int[]{
                                Color.argb(0, 74, 76, 88),
                                Color.argb(0, 74, 76, 88),
                                Color.argb((int) (255 * 0.26f), 66, 68, 80),
                                Color.argb(0, 74, 76, 88),
                        }, new float[]{0f, 0.84f, 0.95f, 1f}, Shader.TileMode.CLAMP));
                // 帽下投影：让帽子浮在外盘上
                shadow.setShader(new RadialGradient(cx, cy + kr * 0.18f, kr * 1.02f,
                        new int[]{
                                Color.argb((int) (255 * 0.22f * a), 70, 72, 86),
                                Color.argb((int) (255 * 0.10f * a), 70, 72, 86),
                                Color.argb(0, 70, 72, 86),
                        }, new float[]{0f, 0.72f, 1f}, Shader.TileMode.CLAMP));
                // 帽体：左上亮、右下暗，边缘仍保持较亮，像镀了一层釉
                // 帽体：实机内圈比外盘暗约 15 级（206.2 对 220.9），是"凹进去的圆"
                body.setShader(new RadialGradient(cx - kr * 0.24f, cy - kr * 0.28f, kr * 1.34f,
                        new int[]{
                                Color.argb((int) (255 * (0.55f + 0.45f * a)), 214, 215, 220),
                                Color.argb((int) (255 * (0.55f + 0.45f * a)), 210, 211, 216),
                                Color.argb((int) (255 * (0.55f + 0.45f * a)), 205, 206, 212),
                                Color.argb((int) (255 * (0.55f + 0.45f * a)), 202, 203, 209),
                                Color.argb((int) (255 * (0.55f + 0.45f * a)), 211, 212, 218),
                        }, new float[]{0f, 0.40f, 0.72f, 0.94f, 1f}, Shader.TileMode.CLAMP));
                // 顶面：正中心略暗的小圆，做出摇杆帽的凹顶
                cap.setShader(new RadialGradient(cx - kr * 0.08f, cy - kr * 0.10f, kr * 0.44f,
                        new int[]{
                                Color.argb((int) (255 * (0.55f + 0.45f * a)), 206, 207, 212),
                                Color.argb((int) (255 * (0.55f + 0.45f * a)), 209, 210, 215),
                                Color.argb((int) (255 * (0.55f + 0.45f * a)), 201, 202, 208),
                        }, new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP));
                rim.setColor(Color.argb((int) (255 * 0.22f * a), 248, 249, 252));
                // 高光点：实机帽子上那一点左上反光
                gloss.setShader(new RadialGradient(cx - kr * 0.34f, cy - kr * 0.36f, kr * 0.42f,
                        new int[]{
                                Color.argb((int) (255 * 0.18f * a), 255, 255, 255),
                                Color.argb((int) (255 * 0.05f * a), 255, 255, 255),
                                Color.argb(0, 255, 255, 255),
                        }, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
                break;
            }
            case STYLE_NEUMO: {
                // 拟物内凹：外盘左上受光、右下压暗（一条斜向明暗），
                // 内盘是"按下去"的凹面 —— 左上暗、右下亮，与外盘明暗相反，这样一眼能看出凹陷方向。
                base.setShader(new RadialGradient(cx - r * 0.40f, cy - r * 0.46f, r * 1.55f,
                        new int[]{
                                Color.argb((int) (255 * 0.94f * a), 250, 251, 253),
                                Color.argb((int) (255 * 0.94f * a), 236, 237, 241),
                                Color.argb((int) (255 * 0.94f * a), 216, 217, 223),
                                Color.argb((int) (255 * 0.94f * a), 196, 197, 205),
                        }, new float[]{0f, 0.40f, 0.74f, 1f}, Shader.TileMode.CLAMP));
                // 凹槽：帽外一圈内阴影，左上加重的暗环把两圆分开
                socket.setShader(new RadialGradient(cx - kr * 0.16f, cy - kr * 0.20f, kr * 1.34f,
                        new int[]{
                                Color.argb(0, 58, 60, 72),
                                Color.argb(0, 58, 60, 72),
                                Color.argb((int) (255 * 0.34f * a), 58, 60, 72),
                                Color.argb(0, 58, 60, 72),
                        }, new float[]{0f, 0.70f, 0.93f, 1f}, Shader.TileMode.CLAMP));
                // 帽下的暗影很轻（凹不是凸）
                shadow.setShader(new RadialGradient(cx, cy + kr * 0.06f, kr * 1.04f,
                        new int[]{
                                Color.argb((int) (255 * 0.14f * a), 64, 66, 78),
                                Color.argb(0, 64, 66, 78),
                        }, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
                // 凹面本体：左上暗 → 右下亮
                body.setShader(new RadialGradient(cx - kr * 0.42f, cy - kr * 0.46f, kr * 1.46f,
                        new int[]{
                                Color.argb((int) (255 * (0.60f + 0.40f * a)), 189, 190, 197),
                                Color.argb((int) (255 * (0.60f + 0.40f * a)), 203, 204, 210),
                                Color.argb((int) (255 * (0.60f + 0.40f * a)), 226, 227, 232),
                                Color.argb((int) (255 * (0.60f + 0.40f * a)), 243, 244, 247),
                        }, new float[]{0f, 0.38f, 0.80f, 1f}, Shader.TileMode.CLAMP));
                // 凹底中心：压得更暗一点，做出"坑底"
                cap.setShader(new RadialGradient(cx - kr * 0.10f, cy - kr * 0.12f, kr * 0.52f,
                        new int[]{
                                Color.argb((int) (255 * (0.55f + 0.45f * a)), 186, 187, 194),
                                Color.argb((int) (255 * (0.55f + 0.45f * a)), 199, 200, 207),
                                Color.argb((int) (255 * (0.55f + 0.45f * a)), 214, 215, 221),
                        }, new float[]{0f, 0.66f, 1f}, Shader.TileMode.CLAMP));
                // 凹口右下那道亮边（受光的对侧）
                rim.setColor(Color.argb((int) (255 * 0.20f * a), 255, 255, 255));
                gloss.setShader(new RadialGradient(cx + kr * 0.40f, cy + kr * 0.44f, kr * 0.60f,
                        new int[]{
                                Color.argb((int) (255 * 0.16f * a), 255, 255, 255),
                                Color.argb(0, 255, 255, 255),
                        }, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
                break;
            }
            default: {
                // 摇杆帽：外盘是哑光深色底座 + 环形开孔（开孔内壁一圈暗），
                // 帽是橡胶穹顶：左上一片柔光、右下压到近黑，边缘留一道薄亮环当反光。
                base.setShader(new RadialGradient(cx - r * 0.22f, cy - r * 0.30f, r * 1.40f,
                        new int[]{
                                Color.argb((int) (255 * 0.90f * a), 96, 97, 107),
                                Color.argb((int) (255 * 0.90f * a), 74, 75, 84),
                                Color.argb((int) (255 * 0.90f * a), 52, 53, 61),
                                Color.argb((int) (255 * 0.90f * a), 37, 38, 45),
                        }, new float[]{0f, 0.44f, 0.80f, 1f}, Shader.TileMode.CLAMP));
                // 开孔内壁：帽外一圈明显暗环，下方最重
                socket.setShader(new RadialGradient(cx, cy + kr * 0.10f, kr * 1.30f,
                        new int[]{
                                Color.argb(0, 12, 13, 17),
                                Color.argb(0, 12, 13, 17),
                                Color.argb((int) (255 * 0.55f * a), 12, 13, 17),
                                Color.argb((int) (255 * 0.30f * a), 12, 13, 17),
                        }, new float[]{0f, 0.68f, 0.92f, 1f}, Shader.TileMode.CLAMP));
                // 穹顶投影：垂直下方偏移，边缘留一点余光
                shadow.setShader(new RadialGradient(cx, cy + kr * 0.20f, kr * 1.16f,
                        new int[]{
                                Color.argb((int) (255 * 0.34f * a), 8, 9, 12),
                                Color.argb((int) (255 * 0.16f * a), 8, 9, 12),
                                Color.argb(0, 8, 9, 12),
                        }, new float[]{0f, 0.74f, 1f}, Shader.TileMode.CLAMP));
                // 帽体：左上 250 灰一路压到右下 58 灰，明暗跨度大才有"橡胶凸起"的体积感
                body.setShader(new RadialGradient(cx - kr * 0.34f, cy - kr * 0.40f, kr * 1.58f,
                        new int[]{
                                Color.argb((int) (255 * 0.94f * a), 246, 247, 250),
                                Color.argb((int) (255 * 0.94f * a), 206, 207, 214),
                                Color.argb((int) (255 * 0.90f * a), 138, 139, 149),
                                Color.argb((int) (255 * 0.88f * a), 72, 73, 83),
                                Color.argb((int) (255 * 0.88f * a), 54, 55, 64),
                        }, new float[]{0f, 0.34f, 0.64f, 0.88f, 1f}, Shader.TileMode.CLAMP));
                // 亮边：只画一圈细薄的 255 白，强度压在 0.20，避免发白塑料感
                rim.setColor(Color.argb((int) (255 * 0.20f * a), 255, 255, 255));
                // 顶部柔光：一片大而淡的白，落在左上
                gloss.setShader(new RadialGradient(cx - kr * 0.36f, cy - kr * 0.44f, kr * 0.86f,
                        new int[]{
                                Color.argb((int) (255 * 0.34f * a), 255, 255, 255),
                                Color.argb((int) (255 * 0.10f * a), 255, 255, 255),
                                Color.argb(0, 255, 255, 255),
                        }, new float[]{0f, 0.48f, 1f}, Shader.TileMode.CLAMP));
                break;
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        buildShaders();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float r = radius;
        if (r <= 0) {
            r = Math.min(getWidth(), getHeight()) / 2f;
            radius = r;
        }
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float kr = r * KNOB_RATIO;

        // 1. 底座
        canvas.drawCircle(cx, cy, r - 1f, base);
        canvas.drawCircle(cx, cy, r - 1f, socket);

        // 2. 内盘整体随倾摆平移，shader 跟着画布走，所以光感一起动
        canvas.save();
        canvas.translate(knobDx, knobDy);
        if (shadow.getShader() != null) {
            canvas.drawCircle(cx, cy + kr * 0.12f, kr * 1.08f, shadow);
        }
        canvas.drawCircle(cx, cy, kr, body);
        canvas.drawCircle(cx, cy, kr, rim);
        if (cap.getShader() != null) canvas.drawCircle(cx, cy, kr * 0.40f, cap);
        if (gloss.getShader() != null) {
            canvas.drawCircle(cx - kr * 0.30f, cy - kr * 0.40f, kr * 0.70f, gloss);
        }
        canvas.restore();
    }

    // ---------------------------------------------------------------- 倾摆

    /** 把内盘往手指所在方向倾摆，最多偏到 KNOB_TRAVEL。 */
    private void leanTo(float x, float y) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float max = Math.min(getWidth(), getHeight()) / 2f * KNOB_TRAVEL;
        float dx = x - cx, dy = y - cy;
        float d = (float) Math.hypot(dx, dy);
        if (d > max && d > 0) {
            dx = dx / d * max;
            dy = dy / d * max;
        }
        knobDx = dx;
        knobDy = dy;
        invalidate();
    }

    private void springBack() {
        if (knobDx == 0f && knobDy == 0f) return;
        if (spring != null) spring.cancel();
        float fromX = knobDx, fromY = knobDy;
        spring = ValueAnimator.ofFloat(0f, 1f);
        spring.setDuration(220);
        spring.setInterpolator(new DecelerateInterpolator());
        spring.addUpdateListener(an -> {
            float t = 1f - an.getAnimatedFraction();
            knobDx = fromX * t;
            knobDy = fromY * t;
            invalidate();
        });
        spring.start();
    }

    /** 到点只进入「按住态」，原地松手才在松手时注入长按；期间移出 slop 就取消。 */
    private final Runnable holdRunnable = () -> {
        if (!moved && !draggingPad) holdArmed = true;
    };

    /** 外圈按住到 3 秒：振动一下告知可以挪板，之后拖动即移动触控板。 */
    private final Runnable edgeHoldRunnable = () -> {
        if (moved || draggingPad) return;
        edgeHoldPending = false;
        draggingPad = true;
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        if (listener != null) listener.onPadDragStart();
    };

    private boolean insideCircle(float x, float y) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy);
        float dx = x - cx, dy = y - cy;
        return dx * dx + dy * dy <= r * r;
    }

    /** 落指点是否在边缘环（可挪板区）。 */
    private boolean onEdgeRing(float x, float y) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy);
        float dx = x - cx, dy = y - cy;
        return (float) Math.hypot(dx, dy) > r * KNOB_RATIO;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (!insideCircle(e.getX(), e.getY())) return false;
                if (listener != null) listener.onTouchDown();
                resetIdle();
                if (spring != null) spring.cancel();
                downX = lastX = e.getX();
                downY = lastY = e.getY();
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                downTime = e.getEventTime();
                maxDisp = 0f;
                moved = false;
                draggingPad = false;
                holdArmed = false;
                boolean onEdge = !lockDrag && onEdgeRing(e.getX(), e.getY());
                edgeHoldPending = onEdge;
                if (onEdge) {
                    // 外圈：按住 3 秒 + 振动才进入挪板，短按仍算一次单击
                    handler.postDelayed(edgeHoldRunnable, EDGE_HOLD_MS);
                } else {
                    leanTo(e.getX(), e.getY());
                    handler.postDelayed(holdRunnable, LONG_PRESS_MS);
                }
                return true;
            }

            case MotionEvent.ACTION_POINTER_DOWN:
                // 第二根手指落下 → 同样进入挪板，但也要多按一会儿，避免误触
                handler.removeCallbacks(holdRunnable);
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (holdArmed) return true;      // 长按已成立，忽略系统送来的微小 MOVE
                if (draggingPad) {
                    // 挪板时帽子归中，否则帽子会一直贴着落指的那一侧
                    if (knobDx != 0f || knobDy != 0f) {
                        if (spring != null) spring.cancel();
                        knobDx = 0f;
                        knobDy = 0f;
                        invalidate();
                    }
                    if (listener != null) {
                        listener.onPadDrag(e.getRawX() - downRawX, e.getRawY() - downRawY);
                    }
                    return true;
                }
                float x = e.getX(), y = e.getY();
                float dx = x - lastX, dy = y - lastY;
                lastX = x;
                lastY = y;
                maxDisp = Math.max(maxDisp, (float) Math.hypot(x - downX, y - downY));
                if (edgeHoldPending) {
                    // 外圈还没到 3 秒就移动 → 不挪板，按普通滑动推光标
                    if (maxDisp > slop) {
                        edgeHoldPending = false;
                        handler.removeCallbacks(edgeHoldRunnable);
                        moved = true;
                        lastX = x;
                        lastY = y;
                        return true;
                    }
                    return true;
                }
                leanTo(x, y);
                if (maxDisp > slop) {
                    if (!moved) {
                        moved = true;
                        handler.removeCallbacks(holdRunnable);
                        // 越过阈值的第一帧只对齐基准，不推光标，避免落指瞬间跳一下
                        lastX = x;
                        lastY = y;
                        return true;
                    }
                    if (listener != null) listener.onMove(dx, dy);
                }
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP:
                holdArmed = false;
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                handler.removeCallbacks(holdRunnable);
                handler.removeCallbacks(edgeHoldRunnable);
                boolean canceled = e.getActionMasked() == MotionEvent.ACTION_CANCEL;
                boolean wasDragging = draggingPad, wasArmed = holdArmed, wasEdgePending = edgeHoldPending;
                draggingPad = false;
                holdArmed = false;
                edgeHoldPending = false;
                springBack();
                if (canceled) return true;
                if (wasDragging) {
                    if (listener != null) listener.onPadDragEnd();
                } else if (wasArmed) {
                    if (listener != null) listener.onLongPress();
                } else if (wasEdgePending) {
                    // 外圈短按：不挪板、也不长按，算一次普通单击
                    if (maxDisp <= tapSlop && e.getEventTime() - downTime <= TAP_MAX_MS) {
                        if (listener != null) listener.onClick();
                    }
                } else if (!moved && maxDisp <= tapSlop
                        && e.getEventTime() - downTime <= TAP_MAX_MS) {
                    if (listener != null) listener.onClick();
                }
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    private void resetIdle() {
        handler.removeCallbacks(idleRunnable);
        handler.postDelayed(idleRunnable, IDLE_MS);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        resetIdle();
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        if (spring != null) spring.cancel();
        super.onDetachedFromWindow();
    }
}
