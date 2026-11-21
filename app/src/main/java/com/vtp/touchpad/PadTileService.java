package com.vtp.touchpad;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** 控制中心快捷开关：一键开关虚拟触控板。 */
public class PadTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        refresh();
    }

    @Override
    public void onClick() {
        super.onClick();
        boolean next = !PadPrefs.enabled(this);
        PadPrefs.setEnabled(this, next);
        PadAccessibilityService.applyEnabled(this);
        refresh();

        if (next && !PadAccessibilityService.serviceEnabled(this)) {
            guideToAccessibility();
        }
    }

    /** 无障碍服务还没开时，折叠通知栏并跳到本应用（页面里有一键跳设置的按钮）。 */
    private void guideToAccessibility() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                PendingIntent pi = PendingIntent.getActivity(
                        this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                startActivityAndCollapse(pi);
            } else {
                startActivityAndCollapse(i);
            }
        } catch (Exception e) {
            // 部分 ROM 限制了通知栏跳转，静默降级：用户下次手动打开应用即可看到引导
        }
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(PadPrefs.enabled(this) ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) {
            t.setSubtitle(PadAccessibilityService.serviceEnabled(this) ? "已就绪" : "需开启无障碍");
        }
        t.updateTile();
    }
}
