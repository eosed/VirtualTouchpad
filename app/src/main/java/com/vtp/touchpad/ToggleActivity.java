package com.vtp.touchpad;

import android.app.Activity;
import android.os.Bundle;

/**
 * 静态快捷方式的跳板。快捷方式的 intent 必须落在 Activity 上，
 * 指向 BroadcastReceiver 是无效声明，整份 shortcuts.xml 都会被系统判为非法，
 * 结果就是控制中心/长按菜单里一条都看不到。
 */
public class ToggleActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ToggleReceiver.toggle(this);
        finish();
    }
}
