package com.vtp.touchpad;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private TextView tvStatus;
    private Switch swMaster;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        tvStatus = findViewById(R.id.tvStatus);
        swMaster = findViewById(R.id.swMaster);

        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        findViewById(R.id.btnTile).setOnClickListener(v -> requestAddTile());

        bindSeek(R.id.sbSpeed, 0, 100, PadPrefs.speedProgress(this),
                p -> PadPrefs.setSpeedProgress(this, p), null);

        bindSeek(R.id.sbSize, 0, 120, PadPrefs.sizeDp(this) - 100,
                p -> PadPrefs.setSizeDp(this, 100 + p),
                () -> PadAccessibilityService.applyEnabled(this));

        bindSeek(R.id.sbAlpha, 0, 70, PadPrefs.alphaPercent(this) - 20,
                p -> PadPrefs.setAlphaPercent(this, 20 + p),
                () -> PadAccessibilityService.applyEnabled(this));

        // 边拖边预览：每变更一档就把摇杆收起来贴边重排，用户直接看到屏幕上只剩多少
        bindSeek(R.id.sbReveal, 10, 100, PadPrefs.edgeRevealPercent(this) - 10,
                p -> {
                    PadPrefs.setEdgeRevealPercent(this, 10 + p);
                    PadAccessibilityService.previewEdgeReveal(this);
                },
                null);

        swMaster.setChecked(PadPrefs.enabled(this));
        swMaster.setOnCheckedChangeListener((v, checked) -> {
            PadPrefs.setEnabled(this, checked);
            PadAccessibilityService.applyEnabled(this);
            if (checked && !PadAccessibilityService.serviceEnabled(this)) promptEnableAccessibility();
            refresh();
        });

        CheckBox cb = findViewById(R.id.cbUnfolded);
        cb.setChecked(PadPrefs.unfoldedOnly(this));
        cb.setOnCheckedChangeListener((v, checked) -> {
            PadPrefs.setUnfoldedOnly(this, checked);
            PadAccessibilityService.applyEnabled(this);
            refresh();
        });

        CheckBox cbF = findViewById(R.id.cbFullscreen);
        cbF.setChecked(PadPrefs.hideInFullscreen(this));
        cbF.setOnCheckedChangeListener((v, checked) -> {
            PadPrefs.setHideInFullscreen(this, checked);
            PadAccessibilityService.applyEnabled(this);
        });

        CheckBox cbR = findViewById(R.id.cbRecents);
        cbR.setChecked(PadPrefs.excludeRecents(this));
        applyExcludeFromRecents();
        cbR.setOnCheckedChangeListener((v, checked) -> {
            PadPrefs.setExcludeRecents(this, checked);
            applyExcludeFromRecents();
            if (!checked) {
                Toast.makeText(this, "已恢复显示在任务列表，建议仍保持后台锁定",
                        Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.btnAutostart).setOnClickListener(v -> openAppInfoSettings());

        findViewById(R.id.btnGithub).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.github_url))));
            } catch (Exception e) {
                Toast.makeText(this, R.string.github_url, Toast.LENGTH_LONG).show();
            }
        });
        findViewById(R.id.btnLog).setOnClickListener(v -> showLog());
        findViewById(R.id.btnHiddenApps).setOnClickListener(v -> chooseHiddenApps());

        bindStyleSpinner();
        guideOnFirstLaunch();
    }

    /** 首次打开引导：总开关默认开，但没给无障碍权限时先讲清为什么要给。 */
    private void guideOnFirstLaunch() {
        if (PadPrefs.guided(this)) {
            if (!PadAccessibilityService.serviceEnabled(this)) refresh();
            return;
        }
        PadPrefs.setGuided(this, true);
        if (!PadAccessibilityService.serviceEnabled(this)) promptEnableAccessibility();
    }

    /** 明确告诉用户缺什么权限、去哪里开，并给出直达入口。 */
    private void promptEnableAccessibility() {
        new AlertDialog.Builder(this)
                .setTitle("还需要开启无障碍权限")
                .setMessage("虚拟触控板要在其它应用之上绘制摇杆、并把单击注入到其它 App，"
                        + "必须借助系统无障碍服务。\n\n"
                        + "点「去开启」→ 在列表里找到「虚拟触控板」→ 打开开关，再回到这里即可。")
                .setPositiveButton("去开启", (d, w) ->
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton("稍后", null)
                .show();
    }

    /** 运行日志：先展示最近一段，再整份分享出去，方便贴到 GitHub Issues。 */
    private void showLog() {
        final String all = Logs.read(this);
        if (all.isEmpty()) {
            Toast.makeText(this, "暂无日志，先用一会儿再来看", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] lines = all.split("\n");
        StringBuilder tail = new StringBuilder();
        int from = Math.max(0, lines.length - 300);
        if (from > 0) tail.append("（只显示最近 300 行，分享时包含全部 ").append(lines.length).append(" 行）\n");
        for (int i = from; i < lines.length; i++) tail.append(lines[i]).append('\n');
        new AlertDialog.Builder(this)
                .setTitle("运行日志")
                .setMessage(tail.toString())
                .setPositiveButton("分享", (d, w) -> shareLog(all))
                .setNeutralButton("清空", (d, w) -> {
                    Logs.clear(this);
                    Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void shareLog(String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "虚拟触控板运行日志（" + Build.MODEL + "）");
        i.putExtra(Intent.EXTRA_TEXT, "机型：" + Build.MODEL + " / Android " + Build.VERSION.RELEASE
                + " / " + getString(R.string.app_name) + "\n\n" + text);
        try {
            startActivity(Intent.createChooser(i, "分享日志"));
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的分享应用", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 拿名单前先确认「看得到别的应用」。Android 11 起默认只可见本进程，
     * MIUI / 澎湃还多一道「获取应用列表」隐私开关；任一没开，getInstalledApplications()
     * 都只剩自己，名单会是空的。缺权限就先带去授权，而不是让用户对着空列表发愣。
     * @return true 表示已能看到别的应用，可以往下选
     */
    private boolean ensureAppListAccess() {
        if (launchableCount() >= 3) return true;
        new AlertDialog.Builder(this)
                .setTitle("需要「获取应用列表」权限")
                .setMessage("要列出手机上装了哪些应用，系统必须允许本应用读取应用列表。\n\n"
                        + "澎湃 / MIUI：应用设置 → 应用管理 → 虚拟触控板 → 权限管理 → 获取应用列表，打开后再回来。")
                .setPositiveButton("去授权", (d, w) -> openAppListPermissionSettings())
                .setNegativeButton("稍后", null)
                .show();
        return false;
    }

    /** 能启动的应用数量；权限被挡时只剩自己。 */
    private int launchableCount() {
        android.content.pm.PackageManager pm = getPackageManager();
        int n = 0;
        for (android.content.pm.ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (ai.packageName.equals(getPackageName())) continue;
            if (pm.getLaunchIntentForPackage(ai.packageName) != null) n++;
        }
        return n;
    }

    /** 「获取应用列表」在 MIUI / 澎湃的权限页里，没有公开 API，按已知组件名逐个试，都失败退应用信息页。 */
    private void openAppListPermissionSettings() {
        String[][] candidates = {
                {"com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"},
                {"com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity"},
                {"com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorTabActivity"},
        };
        for (String[] c : candidates) {
            Intent i = new Intent();
            i.setComponent(new ComponentName(c[0], c[1]));
            i.putExtra("extra_pkgname", getPackageName());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                if (getPackageManager().resolveActivity(i, 0) != null) {
                    startActivity(i);
                    return;
                }
            } catch (Exception ignored) {
                // 试下一个候选组件
            }
        }
        openAppInfoSettings();
    }

    /**
     * 勾选「在这些应用里不显示摇杆」。游戏、全屏视频没有统一的全屏信号，
     * 用用户名单是最可靠的判据；这几个应用里进入即隐藏，退出即恢复。
     */
    private void chooseHiddenApps() {
        if (!ensureAppListAccess()) return;
        java.util.List<android.content.pm.ApplicationInfo> launchable = new java.util.ArrayList<>();
        android.content.pm.PackageManager pm = getPackageManager();
        java.util.List<android.content.pm.ApplicationInfo> all = pm.getInstalledApplications(0);
        for (android.content.pm.ApplicationInfo ai : all) {
            if (ai.packageName.equals(getPackageName())) continue;
            if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue;
            launchable.add(ai);
        }
        java.util.Collections.sort(launchable, (x, y) ->
                String.valueOf(x.loadLabel(pm)).compareTo(String.valueOf(y.loadLabel(pm))));

        CharSequence[] labels = new CharSequence[launchable.size()];
        for (int i = 0; i < launchable.size(); i++) labels[i] = launchable.get(i).loadLabel(pm);
        java.util.Set<String> hidden = PadPrefs.hiddenApps(this);
        boolean[] checked = new boolean[launchable.size()];
        for (int i = 0; i < launchable.size(); i++) checked[i] = hidden.contains(launchable.get(i).packageName);

        new AlertDialog.Builder(this)
                .setTitle("不在这些应用里显示摇杆")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("保存", (d, w) -> {
                    java.util.Set<String> sel = new java.util.LinkedHashSet<>();
                    for (int i = 0; i < launchable.size(); i++) {
                        if (checked[i]) sel.add(launchable.get(i).packageName);
                    }
                    PadPrefs.setHiddenApps(this, sel);
                    PadAccessibilityService.applyEnabled(this);
                    Toast.makeText(this, sel.isEmpty() ? "已清空隐藏名单"
                            : "已在 " + sel.size() + " 个应用里隐藏摇杆", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 外观风格下拉框：0 磨砂玻璃 / 1 拟物内凹 / 2 摇杆帽，选完立即重建窗口。 */
    private void bindStyleSpinner() {
        Spinner sp = findViewById(R.id.spStyle);
        String[] items = {"磨砂玻璃", "拟物内凹", "摇杆帽"};
        android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, items);
        sp.setAdapter(ad);
        sp.setSelection(PadPrefs.style(this));
        sp.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (PadPrefs.style(MainActivity.this) == pos) return;
                PadPrefs.setStyle(MainActivity.this, pos);
                PadAccessibilityService.applyEnabled(MainActivity.this);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });
    }

    /**
     * 让本应用从最近任务列表里消失。Activity 本身没有运行时开关，只能对本任务调用
     * ActivityManager.AppTask.setExcludeFromRecents；它只影响任务列表的展示，不会杀掉无障碍服务。
     */
    private void applyExcludeFromRecents() {
        boolean exclude = PadPrefs.excludeRecents(this);
        try {
            android.app.ActivityManager am = getSystemService(android.app.ActivityManager.class);
            int myTask = getTaskId();
            for (android.app.ActivityManager.AppTask t : am.getAppTasks()) {
                android.app.ActivityManager.RecentTaskInfo info = t.getTaskInfo();
                if (info != null && info.id == myTask) {
                    t.setExcludeFromRecents(exclude);
                    return;
                }
            }
        } catch (Exception ignored) { }
    }

    /**
     * 各家 ROM 的自启动页组件名不公开且逐年变化，靠猜组件名跳过去经常落空。
     * 直接进系统「应用信息」页，让用户在同一页里把省电策略改成「无限制」、顺手允许自启动。
     */
    private void openAppInfoSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Toast.makeText(this, "请手动到系统设置 → 应用管理 → 虚拟触控板 → 应用信息",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this,
                "在应用信息页里把「省电策略」改为「无限制」，再把「自启动」「后台弹出界面」都允许，"
                        + "后台就不会被系统清掉。",
                Toast.LENGTH_LONG).show();
    }


    /**
     * 把快捷开关加到控制中心。Android 13+ 的官方接口在 AOSP 上会弹系统确认框，
     * 但 MIUI / 澎湃常常只回调「未添加」或干脆不响应；加不上时补一份手动路径，
     * 那是澎湃上唯一能保证成功的办法。
     */
    private void requestAddTile() {
        String manual = "澎湃 / MIUI 手动添加：\n"
                + "1. 下拉打开控制中心，点右上角的「编辑」（铅笔图标）\n"
                + "2. 在下方「未添加的开关」里往下翻，找到「应用快捷开关」分组\n"
                + "3. 里面有两个本应用的开关：「开关触控板」和「触控板设置」，点一下即可加上\n\n"
                + "如果这个分组里也没有，说明系统没刷新：先把本应用打开一次，再重进控制中心编辑页。";
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                // AOSP 上系统会弹「添加功能块」确认框；MIUI / 澎湃常常直接回调失败或干脆不响应，
                // 所以只有明确没加上时才补手动路径，免得两家都弹、用户看两遍。
                StatusBarManager sbm = getSystemService(StatusBarManager.class);
                ComponentName cn = new ComponentName(this, PadTileService.class);
                sbm.requestAddTileService(cn, getString(R.string.app_name),
                        android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_pad),
                        getMainExecutor(),
                        result -> {
                            if (result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) showManualTile(manual);
                        });
                return;
            } catch (Exception ignored) {
                // 部分 ROM 未实现该接口，落到下面的手动指引
            }
        }
        showManualTile(manual);
    }

    /** 系统没帮我们加上时，把各家 ROM 手动添加的具体路径摊开讲清楚。 */
    private void showManualTile(String manual) {
        new AlertDialog.Builder(this)
                .setTitle("添加控制中心快捷开关")
                .setMessage(manual)
                .setPositiveButton("知道了", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean svc = PadAccessibilityService.serviceEnabled(this);
        String s;
        if (!svc) {
            s = "状态：无障碍服务未开启，请先完成第 1 步";
        } else if (!PadPrefs.enabled(this)) {
            s = "状态：已就绪，但控制中心开关处于关闭";
        } else if (PadPrefs.unfoldedOnly(this) && !isScreenLarge()) {
            s = "状态：已就绪（当前为折叠态，展开大屏后自动出现）";
        } else {
            s = "状态：已就绪，虚拟触控板已显示在屏幕右下角";
        }
        tvStatus.setText(s);
    }

    /** 与 PadAccessibilityService 的展开态判据保持一致：短边 >= 600dp。 */
    private boolean isScreenLarge() {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        float density = getResources().getDisplayMetrics().density;
        return Math.min(w, h) / density >= 600f;
    }

    private interface Setter { void set(int progress); }

    private interface Commit { void run(); }

    private void bindSeek(int id, int min, int max, int progressZeroBased, Setter setter, Commit commit) {
        SeekBar sb = findViewById(id);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(progressZeroBased, max - min)));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) setter.set(p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) {
                // 拖动过程中只存值，松手才重建窗口，避免连续重建闪烁
                if (commit != null) commit.run();
            }
        });
    }
}