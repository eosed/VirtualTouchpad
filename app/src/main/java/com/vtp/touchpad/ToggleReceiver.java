package com.vtp.touchpad;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** 静态快捷方式 / 其它应用发来的开关请求。 */
public class ToggleReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        toggle(c);
    }

    static void toggle(Context c) {
        boolean now = !PadPrefs.enabled(c);
        PadPrefs.setEnabled(c, now);
        PadAccessibilityService.applyEnabled(c);
        Toast.makeText(c, now ? "虚拟触控板已开启" : "虚拟触控板已关闭", Toast.LENGTH_SHORT).show();
    }
}
