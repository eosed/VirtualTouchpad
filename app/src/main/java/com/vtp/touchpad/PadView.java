package com.vtp.touchpad;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.VelocityTracker;
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
 *   单指快速甩动（速度 ≥900dp/s 且位移 ≥40dp）→ 甩动，在光标处注入同向滑动（翻页 / 滚动）；
 *   其余移动 → 移动光标。
 */
class PadView extends View {

    interface Listener {
        /** 手指相对位移，px。 */
        void onMove(float dx, float dy);

        void onClick();

        void onLongPress();

        /** 单指快速甩动：axis 0 横向 / 1 纵向，sign -1 与手指方向相反侧 / +1 同向。 */
        void onFlick(int axis, int sign);

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
    /**
     * 甩动翻页的判定门槛：松手瞬间速度 ≥ 900dp/s 且本次位移 ≥ 40dp 才算 flick。
     * 正常推光标一般只有 200~400dp/s，两者区分得很开。
     * ponytail: 阈值写死成常量，真机手感不合再调；不做设置项。
     */
    private static final float FLICK_VEL_DP = 900f;
    private static final float FLICK_MIN_DISP_DP = 40f;

    /** 当前外观的全部材质层，按叠加顺序排列；换风格时整体重建。 */
    private final java.util.List<Layer> layers = new java.util.ArrayList<>();
    /** 整块板的绘制范围：任何一层都只能画在这个圆里，画到圆外就会被窗口矩形切出方角。 */
    private final Path clip = new Path();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float slop, tapSlop, dragSlop, density;
    private Listener listener;
    private int alphaPercent;
    private int style;
    private float radius;
    /** 内盘相对中心的偏移（摇杆倾摆）。 */
    private float knobDx, knobDy;
    /** 回弹起点，供复用的动画读取。 */
    private float springFromX, springFromY;
    private static final DecelerateInterpolator DECEL = new DecelerateInterpolator();
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
    /** 本次手势落过第二根手指 → 不算甩动（多指是挪板/误触）。 */
    private boolean multiFinger;
    private VelocityTracker vt;
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
        // 整块板的不透明度交给 View 自己，材质内部一律按素材原值画
        setAlpha(this.alphaPercent / 100f);
        buildShaders();
    }

    void setListener(Listener l) { listener = l; }

    void setLockDrag(boolean v) { lockDrag = v; }

    /** 收起态压暗、展开态恢复用户设定值。 */
    void setAlphaPercent(int v) {
        int a = Math.max(20, Math.min(90, v));
        // 收起态要比展开态的设定值更淡，与 alphaPercent 的钳制区间无关
        if (a == alphaPercent) return;
        alphaPercent = a;
        setAlpha(a / 100f);
        buildShaders();
        invalidate();
    }

    /**
     * 建材质：每一层都是「一枚圆 + 一道以某点为圆心的径向渐变」，
     * 环带层再加一个环装裁剪（斜面、环线都这么做）。
     * 全局禁止线性渐变 —— 它会在圆上切出横平竖直的硬边。
     */
    private void buildShaders() {
        radius = Math.min(getWidth(), getHeight()) / 2f;
        if (radius <= 0) return;
        layers.clear();
        // 磨砂玻璃把用户设定的不透明度直接烘进每一层：滑杆就是唯一的不透明度来源，
        // 不再有「画到一半就到底」的固定暗底。帽体按 1.5.0 的帽体/外盘对比度（70% 时
        // 是 0.865 对 0.70）等比放大，所以默认档位与 1.5 观感一致，往低拉才是真的变淡。
        // 另外两种材质按素材原值（0.94）写死，再由 View.setAlpha 统一压到用户设定值。
        final float a = alphaPercent / 100f;
        final boolean bake = style == STYLE_GLASS;
        setAlpha(bake ? 1f : a);
        final float A = bake ? a : 0.94f;
        // 1.5.0 在 70% 档位时帽体 alpha 0.865、外盘 0.70，比值即帽体的相对厚度
        final float bodyRatio = 0.865f / 0.70f;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = radius, kr = r * KNOB_RATIO;

        switch (style) {
            case STYLE_GLASS: {
                // 1.5.0 的观感：近乎平涂的冷白玻璃外盘 + 帽外一圈暗环（凹槽）
                //              + 左上受光的玻璃帽 + 帽沿一道细亮边 + 左上一点反光。
                // 1.5.0 正中心那枚小圆不再画 —— 那就是「中间多了一个圆点」。
                // 全部渐变的圆周都落在各自遮罩之外：CLAMP 边界一旦被画到，
                // 硬件光栅器会沿那圈采出一串 1px 暗点。视觉半径不变，stops 等比压缩。
                fill(cx - r * .10f, cy - r * .14f, r * 1.18f, r - 1f, false,
                        new int[]{g(244, 245, 248, A), g(240, 241, 244, A), g(236, 237, 241, A),
                                g(234, 235, 239, A), g(240, 241, 245, A)},
                        new float[]{0f, .42f, .72f, .94f, 1f});
                // 帽外一圈暗环：原半径 1.20kr（圆心在盘心），放大到 1.15r 后 stops 压到 .647
                fill(cx, cy, r * 1.15f, r - 1f, false,
                        new int[]{g(74, 76, 88, 0f), g(74, 76, 88, 0f),
                                g(66, 68, 80, .26f), g(74, 76, 88, 0f)},
                        new float[]{0f, .543f, .615f, .647f});
                // 帽下投影：让帽子浮在外盘上（1.5 里是一枚偏下的暗盘，被帽体压住大半）
                fill(cx, cy + kr * .12f, kr * 1.08f, cx, cy + kr * .18f, kr * 1.58f, true,
                        new int[]{g(70, 72, 86, .22f * a), g(70, 72, 86, .10f * a), g(70, 72, 86, 0f)},
                        new float[]{0f, .465f, .646f});
                // 帽体：左上亮、右下暗，帽沿回亮一道，像镀了一层釉
                final float bodyA = Math.min(.98f, a * bodyRatio);
                fill(cx - kr * .24f, cy - kr * .28f, kr * 1.34f, kr, true,
                        new int[]{g(214, 215, 220, bodyA), g(210, 211, 216, bodyA), g(205, 206, 212, bodyA),
                                g(202, 203, 209, bodyA), g(211, 212, 218, bodyA)},
                        new float[]{0f, .40f, .72f, .94f, 1f});
                // 帽沿细亮边
                ring(cx, cy, kr, Math.max(density, r * .010f), g(248, 249, 252, .22f * a), true);
                // 左上那一点反光：渐变半径放大到遮罩之外，再等比压 stops，光斑大小不变
                fill(cx - kr * .30f, cy - kr * .40f, kr * .70f,
                        cx - kr * .34f, cy - kr * .36f, kr * 1.02f, true,
                        new int[]{g(255, 255, 255, .18f * a), g(255, 255, 255, .05f * a),
                                g(255, 255, 255, 0f)},
                        new float[]{0f, .226f, .412f});
                break;
            }
            case STYLE_NEUMO: {
                // 只用阴影：整块平涂，再贴边压一圈暗；中间小圆底色暗一档、贴边更深，
                // 于是一个是浅盘、一个是盘上的坑。整块圆内不存在任何比底色更亮的像素。
                fill(cx, cy, r - 2f, r - 2f, false,
                        new int[]{g(236, 237, 242, A), g(236, 237, 242, A)},
                        new float[]{0f, 1f});
                fill(cx, cy, r - 2f, cx, cy, r * 1.10f, false,
                        new int[]{g(0, 0, 0, 0f), g(0, 0, 0, .11f)},
                        new float[]{.818f, 1f});
                // 小圆：平涂面
                fill(cx, cy, kr * .995f, kr * .995f, true,
                        new int[]{g(206, 208, 215, A), g(206, 208, 215, A)},
                        new float[]{0f, 1f});
                // 小圆的贴边内阴影
                fill(cx, cy, kr * .995f, cx, cy, kr * 1.0945f, true,
                        new int[]{g(0, 0, 0, 0f), g(0, 0, 0, .30f)},
                        new float[]{.7818f, 1f});
                // 沿口一道窄接触阴影，让坑口有厚度
                ring(cx, cy, kr * .976f, kr * .038f, g(0, 0, 0, .13f), true);
                break;
            }
            default: {
                // 同样只用阴影：深灰底座 + 更深的开孔与小圆，全靠压暗表达层次，
                // 没有任何反光点、亮边或高光。
                fill(cx, cy, r - 2f, r - 2f, false,
                        new int[]{g(80, 82, 91, A), g(80, 82, 91, A)},
                        new float[]{0f, 1f});
                fill(cx, cy, r - 2f, cx, cy, r * 1.10f, false,
                        new int[]{g(0, 0, 0, 0f), g(0, 0, 0, .16f)},
                        new float[]{.818f, 1f});
                // 小圆：平涂面
                fill(cx, cy, kr * .995f, kr * .995f, true,
                        new int[]{g(46, 48, 56, A), g(46, 48, 56, A)},
                        new float[]{0f, 1f});
                // 小圆的贴边内阴影
                fill(cx, cy, kr * .995f, cx, cy, kr * 1.0945f, true,
                        new int[]{g(0, 0, 0, 0f), g(0, 0, 0, .36f)},
                        new float[]{.7636f, 1f});
                // 沿口一道窄接触阴影
                ring(cx, cy, kr * .9725f, kr * .045f, g(0, 0, 0, .18f), true);
                break;
            }
        }
    }

    // ---------------------------------------------------------------- 材质

    /** 一层材质：一枚圆（实心或圆环）+ 一道径向渐变。 */
    private static final class Layer {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float cx, cy, radius;
        /** 是否属于内盘：内盘整组随倾摆平移。 */
        boolean knob;
    }

    /** 颜色 + 材质自身透明度，整层不透明度由 View.setAlpha 统一施加。 */
    private static int g(int red, int green, int blue, float alpha) {
        float a = Math.max(0f, Math.min(1f, alpha));
        return Color.argb((int) (255 * a + .5f), red, green, blue);
    }

    /**
     * 铺一个圆面：以 view 中心为圆心、maskR 为半径画圆，圆里填一道以 (scx, scy) 为圆心、
     * sradius 为半径的径向渐变 —— 亮暗从某点向四周摊开，不会切出横平竖直的硬边。
     * 渐变在最外圈已经收到全透明，所以硬裁剪不会露边。
     */
    private void fill(float scx, float scy, float sradius, float maskR, boolean knob,
                      int[] colors, float[] stops) {
        fill(getWidth() / 2f, getHeight() / 2f, maskR, scx, scy, sradius, knob, colors, stops);
    }

    /** 同上，但遮罩圆可以不在盘心 —— 高光这类小圆盘要画在左上。 */
    private void fill(float mcx, float mcy, float maskR,
                      float scx, float scy, float sradius, boolean knob,
                      int[] colors, float[] stops) {
        Layer l = new Layer();
        l.paint.setShader(new RadialGradient(scx, scy, sradius, colors, stops, Shader.TileMode.CLAMP));
        l.cx = mcx;
        l.cy = mcy;
        l.radius = maskR;
        l.knob = knob;
        layers.add(l);
    }

    /**
     * 刷一道环带斜面：环心在 view 中心，半径 ringR、厚度 t，渐变圆心放在受光方向外侧，
     * 于是环的受光侧亮、背光侧暗。「凸起 / 凹陷」全靠这一道环带的明暗方向，不用任何线性渐变。
     */
    private void bevel(float cx, float cy, float ringR, float t,
                       float lcx, float lcy, float gradR, boolean knob, int... colors) {
        float[] stops = new float[colors.length];
        for (int i = 0; i < colors.length; i++) {
            stops[i] = i / (float) (colors.length - 1);
        }
        Layer l = new Layer();
        l.paint.setShader(new RadialGradient(lcx, lcy, gradR, colors, stops, Shader.TileMode.CLAMP));
        // 圆环用描边画：clipPath 在硬件画布上只能用二值掩膜，细环会被切成虚线
        l.paint.setStyle(Paint.Style.STROKE);
        l.paint.setStrokeWidth(t);
        l.cx = cx;
        l.cy = cy;
        l.radius = ringR;
        l.knob = knob;
        layers.add(l);
    }

    /** 一圈实心环带（接触线、勾边这类没有明暗变化的线）。 */
    private void ring(float cx, float cy, float ringR, float t, int color, boolean knob) {
        Layer l = new Layer();
        l.paint.setColor(color);
        l.paint.setStyle(Paint.Style.STROKE);
        l.paint.setStrokeWidth(t);
        l.cx = cx;
        l.cy = cy;
        l.radius = ringR;
        l.knob = knob;
        layers.add(l);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float rr = Math.min(w, h) / 2f;
        clip.reset();
        clip.addCircle(w / 2f, h / 2f, rr, Path.Direction.CW);
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

        // 材质层按 buildShaders 里排好的顺序叠出来；内盘那几层随倾摆平移
        // 先裁到外圆：径向渐变会一直铺到它的渐变半径，哪怕遮罩圆更小；
        // 多出来的部分被窗口矩形一刀切，就变成四边平直、四角带弧的「方光晕」。
        canvas.save();
        canvas.clipPath(clip);
        for (int i = 0; i < layers.size(); i++) {
            Layer l = layers.get(i);
            canvas.save();
            if (l.knob) canvas.translate(knobDx, knobDy);
            canvas.drawCircle(l.cx, l.cy, l.radius, l.paint);
            canvas.restore();
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

    /** 动画与插值器只建一次：每次抬手都 new 一套只是给 GC 添活。 */
    private void springBack() {
        if (knobDx == 0f && knobDy == 0f) return;
        if (spring == null) {
            spring = ValueAnimator.ofFloat(0f, 1f);
            spring.setDuration(220);
            spring.setInterpolator(DECEL);
            spring.addUpdateListener(an -> {
                float t = 1f - an.getAnimatedFraction();
                knobDx = springFromX * t;
                knobDy = springFromY * t;
                invalidate();
            });
        }
        spring.cancel();
        springFromX = knobDx;
        springFromY = knobDy;
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
        if (vt != null) vt.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (!insideCircle(e.getX(), e.getY())) return false;
                if (vt == null) vt = VelocityTracker.obtain();
                vt.clear();
                vt.addMovement(e);
                multiFinger = false;
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
                multiFinger = true;
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
                float vx = 0f, vy = 0f;
                if (vt != null) {
                    vt.computeCurrentVelocity(1000);
                    vx = vt.getXVelocity();
                    vy = vt.getYVelocity();
                    vt.recycle();
                    vt = null;
                }
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
                } else if (moved) {
                    flickIfAny(vx, vy);
                } else if (!moved && maxDisp <= tapSlop
                        && e.getEventTime() - downTime <= TAP_MAX_MS) {
                    if (listener != null) listener.onClick();
                }
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    /** 位移与速度同时达标才算甩动；否则这次手势只当作移动光标。 */
    private void flickIfAny(float vx, float vy) {
        if (multiFinger || maxDisp < FLICK_MIN_DISP_DP * density) return;
        if ((float) Math.hypot(vx, vy) / density < FLICK_VEL_DP) return;
        int axis = Math.abs(vx) >= Math.abs(vy) ? 0 : 1;
        int sign = (axis == 0 ? vx : vy) > 0f ? 1 : -1;
        if (listener != null) listener.onFlick(axis, sign);
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
