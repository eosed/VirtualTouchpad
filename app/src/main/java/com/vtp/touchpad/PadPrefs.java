package com.vtp.touchpad;

import android.content.Context;
import android.content.SharedPreferences;

/** 唯一的设置存储：光标速度、触控区尺寸/透明度/位置、是否仅在展开态显示、总开关。 */
public final class PadPrefs {

    private static final String FILE = "pad";

    private PadPrefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 光标相对手指的倍率，1.0 ~ 4.0。 */
    public static float speed(Context c) {
        return 1.0f + sp(c).getInt("speed", 34) / 100f * 3.0f;
    }

    public static int speedProgress(Context c) { return sp(c).getInt("speed", 34); }

    public static void setSpeedProgress(Context c, int v) {
        sp(c).edit().putInt("speed", v).apply();
    }

    /** 摇杆（触控圈）直径，dp。 */
    public static int sizeDp(Context c) { return sp(c).getInt("size", 140); }

    public static void setSizeDp(Context c, int v) { sp(c).edit().putInt("size", v).apply(); }

    /** 触控区不透明度，20 ~ 90（百分比）。 */
    public static int alphaPercent(Context c) { return sp(c).getInt("alpha", 92); }

    public static void setAlphaPercent(Context c, int v) {
        sp(c).edit().putInt("alpha", v).apply();
    }

    /** 收起成贴边小点后，屏幕里还露出多少（百分比，10 ~ 100）。 */
    public static int edgeRevealPercent(Context c) { return sp(c).getInt("edge_reveal", 25); }

    public static void setEdgeRevealPercent(Context c, int v) {
        sp(c).edit().putInt("edge_reveal", v).apply();
    }

    /** 仅在展开（宽屏）状态显示。 */
    public static boolean unfoldedOnly(Context c) { return sp(c).getBoolean("unfolded_only", true); }

    public static void setUnfoldedOnly(Context c, boolean v) {
        sp(c).edit().putBoolean("unfolded_only", v).apply();
    }

    /** 触控区右/下边距，px。 */
    public static int padX(Context c) { return sp(c).getInt("pad_x", Integer.MIN_VALUE); }

    public static int padY(Context c) { return sp(c).getInt("pad_y", Integer.MIN_VALUE); }

    public static void setPadPos(Context c, int x, int y) {
        sp(c).edit().putInt("pad_x", x).putInt("pad_y", y).apply();
    }

    /** 外观风格：0 磨砂玻璃（默认），1 拟物内凹，2 摇杆帽。 */
    public static int style(Context c) { return sp(c).getInt("style", 0); }

    public static void setStyle(Context c, int v) { sp(c).edit().putInt("style", v).apply(); }

    /** 是否在最近任务（安卓任务列表）里隐藏本应用。 */
    public static boolean excludeRecents(Context c) { return sp(c).getBoolean("exclude_recents", true); }

    public static void setExcludeRecents(Context c, boolean v) {
        sp(c).edit().putBoolean("exclude_recents", v).apply();
    }

    /** 是否已完成首次引导（只在第一次打开时提示开无障碍）。 */
    public static boolean guided(Context c) { return sp(c).getBoolean("guided", false); }

    public static void setGuided(Context c, boolean v) { sp(c).edit().putBoolean("guided", v).apply(); }

    /** 全屏视频 / 游戏时自动隐藏。 */
    public static boolean hideInFullscreen(Context c) { return sp(c).getBoolean("hide_fullscreen", true); }

    public static void setHideInFullscreen(Context c, boolean v) {
        sp(c).edit().putBoolean("hide_fullscreen", v).apply();
    }

    /** 用户勾选「在这些应用里不显示摇杆」的包名（逗号分隔）。 */
    public static java.util.Set<String> hiddenApps(Context c) {
        String raw = sp(c).getString("hidden_apps", "");
        java.util.Set<String> s = new java.util.LinkedHashSet<>();
        if (raw != null && !raw.isEmpty()) {
            for (String p : raw.split(",")) if (!p.trim().isEmpty()) s.add(p.trim());
        }
        return s;
    }

    public static void setHiddenApps(Context c, java.util.Set<String> apps) {
        sp(c).edit().putString("hidden_apps", String.join(",", apps)).apply();
    }

    /** 总开关，供控制中心磁贴读写。 */
    public static boolean enabled(Context c) { return sp(c).getBoolean("enabled", true); }

    public static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("enabled", v).apply();
    }
}
