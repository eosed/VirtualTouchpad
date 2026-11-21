package com.vtp.touchpad;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;

import java.util.List;

/**
 * 复刻华为 Mate X8「虚拟触控板」：
 * 展开的大屏内，拇指在右下角虚拟触控区滑动 → 移动屏幕光标；轻点 → 在光标处点击；长按 → 长按。
 * 纯软件实现，不依赖机型，靠无障碍服务绘制 overlay 并 dispatchGesture 注入点击。
 * 系统层面有遮挡时（锁屏、输入法、通知栏/控制中心、通话）自动把触控板与光标藏起来。
 */
public class PadAccessibilityService extends AccessibilityService {

    static volatile PadAccessibilityService instance;

    private static final long CURSOR_IDLE_HIDE_MS = 4000;
    /** 事件触发后等系统窗口状态稳定再判定。 */
    private static final long OBSTRUCTION_DELAY_MS = 120;
    /** 遮挡轮询间隔。 */
    private static final long POLL_MS = 700;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private WindowManager.LayoutParams cursorLp;
    private WindowManager.LayoutParams padLp;
    private FrameLayout cursorRoot;
    private CursorView cursorDot;
    private PadView pad;
    private boolean attached;
    /** 上次已知屏幕尺寸，用来忽略纯刷新率变化触发的 onDisplayChanged。 */
    private int lastW, lastH;
    /** 刚被「碰一下唤醒」的那次触摸，抬手时不要注入点击。 */
    private boolean suppressNextTap;
    /** 被系统界面（锁屏/输入法/通知栏）遮挡，处于临时隐藏状态。 */
    private boolean systemObstructed;
    /** 系统是否弹出了通知栏 / 控制中心。 */
    private boolean shadeOpen;
    /** 屏幕已熄灭（含锁屏），此时不显示。 */
    private boolean screenOff;
    /** 当前前台应用包名，用于「不显示摇杆的应用」名单比对。 */
    private String foregroundPkg = "";

    private float cursorX, cursorY;
    private int cursorSizePx;
    /** onMove 太频繁，日志每 25 次记一条。 */
    private int moveCount;

    private final Runnable hideCursor = () -> {
        if (cursorDot != null) cursorDot.setVisibility(View.INVISIBLE);
    };

    private final Runnable recheckObstruction = this::updateObstruction;

    /**
     * 全屏视频、游戏进入/退出沉浸模式时系统往往不发无障碍事件，只能靠轻量轮询补判。
     * 每次只读一次窗口列表，代价可以忽略。
     */
    private final Runnable pollObstruction = new Runnable() {
        @Override public void run() {
            if (!attached) return;
            updateObstruction();
            handler.postDelayed(this, POLL_MS);
        }
    };

    private DisplayManager displayManager;

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { }
        @Override public void onDisplayRemoved(int displayId) { }
        // 只按尺寸变化重建。可变刷新率机型（LTPO）切换帧率时也会发 onDisplayChanged，
        // 无条件 rebuild 会不断重挂窗口：光标回到屏幕中心且重新隐藏、闲置计时反复归零，收边永远等不到。
        @Override public void onDisplayChanged(int displayId) { rebuildIfResized(); }
    };

    /** 锁屏/亮灭屏时立即重判，不等无障碍事件。 */
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String a = intent == null ? null : intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                shadeOpen = false;
                screenOff = true;          // 熄屏即隐藏，不依赖各 ROM 的 keyguard 回调
            } else {
                screenOff = false;
            }
            scheduleObstructionCheck();
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        lastW = screenW();
        lastH = screenH();
        Logs.w(this, "svc", "connected " + Build.MODEL + " / Android " + Build.VERSION.RELEASE
                + " / screen=" + lastW + "x" + lastH);
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        try {
            displayManager.registerDisplayListener(displayListener, handler);
        } catch (Exception ignored) { }
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        try { registerReceiver(screenReceiver, f); } catch (Exception ignored) { }
        attach();
    }

    /** 尺寸/形态变化后按当前状态重新挂载（内部会重新判定是否应显示）。 */
    private void rebuild() {
        Logs.w(this, "svc", "rebuild w=" + screenW() + " h=" + screenH());
        detach();
        attach();
    }

    /**
     * 只有屏幕尺寸真的变了才重建。刷新率、色彩模式等变化也会回调 onDisplayChanged，
     * 那类回调不该动窗口。
     */
    private void rebuildIfResized() {
        int w = screenW(), h = screenH();
        if (w == lastW && h == lastH) return;
        lastW = w;
        lastH = h;
        rebuild();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence cn = event.getClassName();
        String cls = cn == null ? "" : cn.toString();
        CharSequence pkg = event.getPackageName();
        // 通知栏 / 控制中心展开或收起
        if ("com.android.systemui".equals(pkg == null ? "" : pkg.toString())
                && (cls.contains("Shade") || cls.contains("ControlCenter") || cls.contains("QuickSettings"))) {
            shadeOpen = event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            scheduleObstructionCheck();
            return;
        }
        // 其它窗口变化（比如输入法弹出/收起、回到桌面）都重判一次；
        // 通知栏收起后通常由前台应用拿到焦点，借此把 shadeOpen 清零。
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            scheduleObstructionCheck();
            return;
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String p = pkg == null ? "" : pkg.toString();
            if (shadeOpen && !"com.android.systemui".equals(p)) shadeOpen = false;
            // 自己的设置页不算「别的应用」，否则一打开本应用摇杆就被名单规则藏掉
            if (!p.isEmpty() && !p.equals(getPackageName())) foregroundPkg = p;
            scheduleObstructionCheck();
        }
    }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        detach();
        if (displayManager != null) {
            try { displayManager.unregisterDisplayListener(displayListener); } catch (Exception ignored) { }
        }
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) { }
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        detach();
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 折叠 / 展开时屏幕尺寸变化，重建并重新钳制位置（折叠态未挂载，展开后需补挂）
        rebuildIfResized();
    }

    // ---------------------------------------------------------------- windows

    private int screenW() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowMetrics m = wm.getCurrentWindowMetrics();
            return m.getBounds().width();
        }
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm.widthPixels;
    }

    private int screenH() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowMetrics m = wm.getCurrentWindowMetrics();
            return m.getBounds().height();
        }
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm.heightPixels;
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    /** 是否处于展开（大屏）状态：短边 >= 600dp。折叠屏合起时通常 360dp 左右。 */
    private boolean isUnfolded() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int shortSide = Math.min(screenW(), screenH());
        return shortSide / dm.density >= 600f;
    }

    private void attach() {
        if (!PadPrefs.enabled(this)) return;
        if (PadPrefs.unfoldedOnly(this) && !isUnfolded()) return;
        systemObstructed = false;
        addCursorWindow();
        addPadWindow();
        attached = true;
        scheduleObstructionCheck();
        handler.removeCallbacks(pollObstruction);
        handler.postDelayed(pollObstruction, POLL_MS);
    }

    private void detach() {
        handler.removeCallbacksAndMessages(null);
        handler.removeCallbacks(pollObstruction);
        if (cursorRoot != null) {
            try { wm.removeView(cursorRoot); } catch (Exception ignored) { }
            cursorRoot = null;
            cursorDot = null;
        }
        if (pad != null) {
            try { wm.removeView(pad); } catch (Exception ignored) { }
            pad = null;
        }
        attached = false;
        systemObstructed = false;
        padDragging = false;
    }

    private void addCursorWindow() {
        cursorRoot = new FrameLayout(this);
        cursorRoot.setClipChildren(false);
        cursorDot = new CursorView(this);
        Logs.w(this, "svc", "addCursor screen=" + screenW() + "x" + screenH());
        // 光标是触控区那根"拇指"的投影，随触控区等比缩放，不单独调
        int size = Math.max((int) dp(10), (int) (dp(PadPrefs.sizeDp(this)) * 0.15f));
        cursorSizePx = size;
        cursorRoot.addView(cursorDot, new FrameLayout.LayoutParams(size, size));

        // 窗口就做成光标那么大、用窗口坐标定位：整屏容器会被系统按状态栏 inset 下推，
        // View 的 y=0 就不在屏幕顶上了，而注入手势用的是物理屏幕坐标，两者会错开一截。
        cursorLp = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        cursorLp.gravity = Gravity.TOP | Gravity.START;
        fitToFullScreen(cursorLp);
        wm.addView(cursorRoot, cursorLp);

        cursorX = screenW() / 2f;
        cursorY = screenH() / 2f;
        cursorDot.setVisibility(View.INVISIBLE);
        positionCursor();
    }

    private void addPadWindow() {
        Logs.w(this, "svc", "addPad collapsed=" + collapsed);
        int sizePx = (int) dp(PadPrefs.sizeDp(this));
        pad = new PadView(this,
                collapsed ? COLLAPSED_ALPHA : PadPrefs.alphaPercent(this),
                PadPrefs.style(this));
        pad.setLockDrag(collapsed);
        pad.setListener(padListener);

        // 收起态直接按小一号建窗，免得先铺满再缩，闪一下
        int winPx = collapsed ? Math.max(1, Math.round(sizePx * COLLAPSED_SCALE)) : sizePx;
        padLp = new WindowManager.LayoutParams(
                winPx, winPx,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        // 没有它系统会把窗口钳回屏幕内（实测 mAttrs 写 2016，实际 frame 被拉到 1833），
                        // 收起贴边就永远收不进去，只能看到完整的圆。
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        padLp.gravity = Gravity.TOP | Gravity.START;
        fitToFullScreen(padLp);
        padLp.x = defaultPadX(winPx);
        padLp.y = defaultPadY(winPx);
        int sx = PadPrefs.padX(this), sy = PadPrefs.padY(this);
        if (sx != Integer.MIN_VALUE) padLp.x = sx;
        if (sy != Integer.MIN_VALUE) padLp.y = sy;
        if (collapsed) collapsedPos(sizePx);
        clampPad(false);
        wm.addView(pad, padLp);
    }

    /**
     * 收起：整块板滑到最近的一条竖边外面，让屏幕边界把圆裁掉大部分，
     * 屏幕上只剩贴着边的一小截圆弧（露出多少见设置页的「收起后露出比例」）。点它即恢复。
     * ponytail: 只贴左右竖边；要贴上下边再补。
     */
    private void collapsedPos(int fullPx) {
        int w = screenW(), h = screenH();
        int box = padLp.width;
        boolean left = padLp.x + box / 2 < w / 2;
        // 露出量按**屏幕上实际那个圆**的直径算（收起态自己已经缩到 0.707 倍），
        // 圆内切于方形窗口，屏幕里留下的宽度就是窗口越界量的反向值。
        int visible = Math.round(box * (PadPrefs.edgeRevealPercent(this) / 100f));
        padLp.x = left ? visible - box : w - visible;
        padLp.y = Math.max((int) dp(80), Math.min(padLp.y, h - box - (int) dp(80)));
    }

    /** 展开 / 收起紧凑态：重建小窗口并保持锚点，且只有在恢复时才写回用户位置。 */
    private void setCollapsed(boolean v) {
        if (collapsed == v) return;
        if (v && padLp != null) {
            // 收起前的位置要留着，唤醒时回到原处而不是贴着边的手感位置
            padRestoreX = padLp.x;
            padRestoreY = padLp.y;
        }
        collapsed = v;
        if (!attached) return;
        Logs.w(this, "svc", "collapsed=" + v);
        if (pad == null || padLp == null) { addPadWindow(); return; }
        // 只挪窗口，不重建 View：重建会丢掉闲置计时与倾摆状态，
        // 真机上 removeView/addView 偶发失败还会让摇杆整个消失。
        // 基准永远是展开态尺寸，不能读 padLp.width —— 收起时它已经被改成小窗了
        int sizePx = (int) dp(PadPrefs.sizeDp(this));
        if (v) {
            // 收起态整体缩小一半：窗口和 View 一起变小，视觉与手感都是小一号的圆
            int small = Math.max(1, Math.round(sizePx * COLLAPSED_SCALE));
            padLp.width = small;
            padLp.height = small;
            collapsedPos(sizePx);
        } else {
            padLp.width = sizePx;
            padLp.height = sizePx;
            padLp.x = padRestoreX != Integer.MIN_VALUE ? padRestoreX : defaultPadX(sizePx);
            padLp.y = padRestoreY != Integer.MIN_VALUE ? padRestoreY : defaultPadY(sizePx);
        }
        clampPad(false);
        pad.setLockDrag(v);
        pad.setAlphaPercent(v ? COLLAPSED_ALPHA : PadPrefs.alphaPercent(this));
        try { wm.updateViewLayout(pad, padLp); } catch (Exception ignored) { }
    }

    /**
     * 覆盖层窗口默认会被状态栏 inset 下推一截，View 坐标的 0 就落在状态栏下方；
     * 而注入手势用的是物理屏幕坐标，两者差一个状态栏高度，光标和实际点击点就会错开。
     * 关掉 inset 拟合，窗口原点贴回物理屏幕左上角。
     */
    private void fitToFullScreen(WindowManager.LayoutParams lp) {
        if (Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0);
    }

    private int defaultPadX(int sizePx) { return screenW() - sizePx - (int) dp(16); }

    private int defaultPadY(int sizePx) { return screenH() - sizePx - (int) dp(48); }

    /** 触控区必须留在屏幕内，且避开底部手势条；persist=true 时写回设置。收起态允许停在屏幕外。 */
    private void clampPad(boolean persist) {
        if (padLp == null) return;
        int w = screenW(), h = screenH();
        if (!collapsed) padLp.x = Math.max(0, Math.min(padLp.x, w - padLp.width));
        padLp.y = Math.max(0, Math.min(padLp.y, h - padLp.height - (int) dp(8)));
        if (pad != null) {
            try { wm.updateViewLayout(pad, padLp); } catch (Exception ignored) { }
        }
        if (persist) PadPrefs.setPadPos(this, padLp.x, padLp.y);
    }

    private void positionCursor() {
        if (cursorLp == null) return;
        float r = cursorSizePx / 2f;
        int x = Math.round(cursorX - r), y = Math.round(cursorY - r);
        if (cursorLp.x == x && cursorLp.y == y) return;
        cursorLp.x = x;
        cursorLp.y = y;
        if (cursorRoot != null) {
            try { wm.updateViewLayout(cursorRoot, cursorLp); } catch (Exception ignored) { }
        }
    }

    private void showCursor() {
        if (cursorDot == null || systemObstructed) return;
        if (cursorDot.getVisibility() != View.VISIBLE) {
            Logs.w(this, "svc", "cursor show at " + (int) cursorX + "," + (int) cursorY);
        }
        cursorDot.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideCursor);
        handler.postDelayed(hideCursor, CURSOR_IDLE_HIDE_MS);
        positionCursor();
    }

    // ---------------------------------------------------------------- 系统遮挡

    private void scheduleObstructionCheck() {
        handler.removeCallbacks(recheckObstruction);
        handler.postDelayed(recheckObstruction, OBSTRUCTION_DELAY_MS);
    }

    /** 锁屏、输入法、通知栏/控制中心出现时把触控板与光标一起藏起来。 */
    private void updateObstruction() {
        if (!attached) return;
        boolean hide = shouldHideForSystemUi();

        if (hide == systemObstructed) return;
        systemObstructed = hide;
        Logs.w(this, "svc", "obstruct=" + hide + " reasons=" + hideReasons());
        if (pad != null) pad.setVisibility(hide ? View.INVISIBLE : View.VISIBLE);
        if (hide) {
            handler.removeCallbacks(hideCursor);
            if (cursorDot != null) cursorDot.setVisibility(View.INVISIBLE);
        }
    }

    private boolean shouldHideForSystemUi() {
        if (screenOff) return true;
        if (keyguardLocked()) return true;
        if (shadeVisible()) return true;
        if (immersiveFullscreen()) return true;
        if (PadPrefs.hiddenApps(this).contains(foregroundPkg)) return true;
        return imeVisible();
    }

    /** 逐条判据的结果，只在遮挡状态变化时记一次，便于按日志定位是哪条误判。 */
    private String hideReasons() {
        return "screenOff=" + screenOff
                + " keyguard=" + keyguardLocked()
                + " shade=" + shadeVisible()
                + " full=" + immersiveFullscreen()
                + " ime=" + imeVisible()
                + " fg=" + foregroundPkg
                + " hidden=" + PadPrefs.hiddenApps(this).contains(foregroundPkg);
    }

    private boolean keyguardLocked() {
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isKeyguardLocked();
    }

    /**
     * 通知栏 / 控制中心是否展开：展开时状态栏会被下拉层接管，TYPE_SYSTEM 窗口高度
     * 从一条状态栏撑到接近整屏。只认窗口尺寸，不依赖无障碍事件类名（各家 ROM 不一致）。
     */
    private boolean shadeVisible() {
        int h = screenH();
        for (AccessibilityWindowInfo w : safeWindows()) {
            if (w == null || w.getType() != AccessibilityWindowInfo.TYPE_SYSTEM) continue;
            android.graphics.Rect r = new android.graphics.Rect();
            w.getBoundsInScreen(r);
            if (r.height() > h * 0.6f) return true;
        }
        return false;
    }

    /**
     * 输入法是否盖在屏幕上：getWindows 需要 flagRetrieveInteractiveWindows +
     * canRetrieveWindowContent，缺任一项都会返回空列表。
     */
    private boolean imeVisible() {
        int h = screenH();
        for (AccessibilityWindowInfo w : safeWindows()) {
            if (w == null || w.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            android.graphics.Rect r = new android.graphics.Rect();
            w.getBoundsInScreen(r);
            if (r.height() > h * 0.05f) return true;
        }
        return false;
    }

    /**
     * 全屏沉浸（全屏视频、游戏）：状态栏窗口被系统收走或压成 0 高时认为进入全屏。
     * 这是普通应用拿得到的唯一通用信号，不依赖具体 App 名单。
     */
    private boolean immersiveFullscreen() {
        if (!PadPrefs.hideInFullscreen(this)) return false;
        boolean gotsSystemUi = false;
        for (AccessibilityWindowInfo w : safeWindows()) {
            if (w == null || w.getType() != AccessibilityWindowInfo.TYPE_SYSTEM) continue;
            gotsSystemUi = true;
            android.graphics.Rect r = new android.graphics.Rect();
            w.getBoundsInScreen(r);
            if (r.height() > 0) return false;   // 状态栏还在 → 不是全屏
        }
        // 一个 TYPE_SYSTEM 窗口都没有也算全屏（部分 ROM 直接把状态栏移除）
        return !gotsSystemUi;
    }

    private List<AccessibilityWindowInfo> safeWindows() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            return ws != null ? ws : java.util.Collections.<AccessibilityWindowInfo>emptyList();
        } catch (Exception e) {
            return java.util.Collections.<AccessibilityWindowInfo>emptyList();
        }
    }

    // ---------------------------------------------------------------- input

    private boolean padDragging;
    private int padDragStartX, padDragStartY;

    /** 闲置 10 秒后收起成贴边小点，点一下恢复。 */
    private boolean collapsed;
    private int padRestoreX = Integer.MIN_VALUE, padRestoreY = Integer.MIN_VALUE;
    /** 收起后屏幕里还留多少（按收起态直径的比例），由设置页的「收起后露出比例」决定。 */
    /**
     * 收起态的窗口直径是展开态的多少倍。1/√2 ≈ 0.707 正好让**面积**减半，
     * 这与「缩小 50% 占用面积」对得上；直径直接减半会让面积掉到 1/4，太小不好按。
     */
    private static final float COLLAPSED_SCALE = 0.7071f;
    private static final int COLLAPSED_ALPHA = 35;

    private final PadView.Listener padListener = new PadView.Listener() {
        @Override public void onPadDragStart() {
            // 3 秒长按已确认、振动已发出，这里只需保证起点是当前窗口位置
            padDragging = false;
        }

        @Override public void onTouchDown() {
            // 收起成贴边半圆时，一碰就恢复并立即可用，不用先点一下唤醒再滑。
            // 唤醒这最后一次抬手不该注入点击：否则点到的是光标位置，不是手指位置。
            if (collapsed) {
                suppressNextTap = true;
                setCollapsed(false);
            }
        }

        @Override public void onIdle() {
            Logs.w(PadAccessibilityService.this, "svc", "idle 10s");
            if (!collapsed) setCollapsed(true);
        }

        @Override public void onMove(float dx, float dy) {
            if (systemObstructed) { Logs.w(PadAccessibilityService.this, "svc", "move blocked by obstruct"); return; }
            if (moveCount++ % 25 == 0) {
                Logs.w(PadAccessibilityService.this, "svc", "move d=" + (int) dx + "," + (int) dy);
            }
            float k = PadPrefs.speed(PadAccessibilityService.this);
            cursorX += dx * k;
            cursorY += dy * k;
            int w = screenW(), h = screenH();
            float half = cursorSizePx / 2f;
            cursorX = Math.max(half, Math.min(cursorX, w - half));
            cursorY = Math.max(half, Math.min(cursorY, h - half));
            showCursor();
        }

        @Override public void onClick() {
            if (suppressNextTap) {
                suppressNextTap = false;
                Logs.w(PadAccessibilityService.this, "svc", "tap skipped (wake-up)");
                return;
            }
            Logs.w(PadAccessibilityService.this, "svc", "tap at " + (int) cursorX + "," + (int) cursorY
                    + (systemObstructed ? " (obstructed)" : ""));
            if (systemObstructed) return;
            showCursor();
            dispatchTap(cursorX, cursorY, 60);
        }

        @Override public void onLongPress() {
            if (systemObstructed) return;
            dispatchLongPress(cursorX, cursorY);
        }

        @Override public void onPadDrag(float totalDx, float totalDy) {
            if (padLp == null) return;
            if (!padDragging) {
                padDragging = true;
                padDragStartX = padLp.x;
                padDragStartY = padLp.y;
            }
            // 用最初位置 + 累计位移定位，手指与窗口之间不会形成反馈回路，拖动不再抖
            padLp.x = padDragStartX + (int) totalDx;
            padLp.y = padDragStartY + (int) totalDy;
            clampPad(false);
        }

        @Override public void onPadDragEnd() {
            padDragging = false;
            clampPad(true);
        }
    };

    // ---------------------------------------------------------------- gestures

    private void dispatchTap(float x, float y, long durationMs) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription s = new GestureDescription.StrokeDescription(p, 0, durationMs);
        GestureDescription g = new GestureDescription.Builder().addStroke(s).build();
        dispatchGesture(g, null, null);
    }

    private void dispatchLongPress(float x, float y) {
        Path p = new Path(); p.moveTo(x, y);
        GestureDescription.StrokeDescription s = new GestureDescription.StrokeDescription(p, 0, 700);
        GestureDescription g = new GestureDescription.Builder().addStroke(s).build();
        dispatchGesture(g, null, null);
    }

    // ---------------------------------------------------------------- api

    /**
     * 设置页拖动「收起后露出比例」时实时预览：立刻把摇杆收到贴边并按当前比例重排，
     * 用户松手前就能看到实际露出多少，不用等闲置 10 秒。
     */
    static void previewEdgeReveal(Context c) {
        PadAccessibilityService s = instance;
        if (s == null || !s.attached || s.padLp == null) return;
        boolean wasCollapsed = s.collapsed;
        s.setCollapsed(true);
        // 已经是收起态时 setCollapsed 会提前返回，这里再重排一次让新比例立刻生效
        if (wasCollapsed && s.padLp != null) {
            s.collapsedPos((int) s.dp(PadPrefs.sizeDp(s)));
            s.clampPad(false);
        }
    }

    /** 供控制中心磁贴 / 主界面调用：开关变化后立即生效。 */
    static void applyEnabled(Context c) {
        PadAccessibilityService s = instance;
        if (s == null) return;
        s.detach();
        s.attach();
    }

    private static boolean isServiceOn(Context c) {
        android.content.ContentResolver cr = c.getContentResolver();
        String flat = android.provider.Settings.Secure.getString(
                cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (flat == null) return false;
        String me = c.getPackageName() + "/" + PadAccessibilityService.class.getName();
        return flat.contains(me);
    }

    static boolean serviceEnabled(Context c) {
        return isServiceOn(c) || instance != null;
    }
}
