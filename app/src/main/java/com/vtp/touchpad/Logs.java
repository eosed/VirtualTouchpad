package com.vtp.touchpad;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 轻量运行日志：写进应用私有目录 pad.log，用户反馈问题时可直接导出。
 * 只记状态变化与关键动作，不刷高频输出；文件超过上限时保留最近一半。
 */
public final class Logs {

    private static final String FILE_NAME = "pad.log";
    private static final long MAX_BYTES = 256 * 1024;
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static final Object LOCK = new Object();

    private Logs() { }

    public static void w(Context c, String tag, String msg) {
        if (c == null) return;
        String line = FMT.format(new Date()) + " [" + tag + "] " + msg + "\n";
        synchronized (LOCK) {
            File f = new File(c.getFilesDir(), FILE_NAME);
            try {
                if (f.length() > MAX_BYTES) trim(f);
                FileWriter fw = new FileWriter(f, true);
                try { fw.write(line); } finally { fw.close(); }
            } catch (Exception ignored) { }
        }
    }

    public static String read(Context c) {
        if (c == null) return "";
        synchronized (LOCK) {
            File f = new File(c.getFilesDir(), FILE_NAME);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String s;
                while ((s = r.readLine()) != null) sb.append(s).append('\n');
            } catch (Exception e) {
                return "";
            }
            return sb.toString();
        }
    }

    public static void clear(Context c) {
        if (c == null) return;
        synchronized (LOCK) {
            //noinspection ResultOfMethodCallIgnored
            new File(c.getFilesDir(), FILE_NAME).delete();
        }
    }

    /** 超过上限时只留后半段，避免文件无限增长。 */
    private static void trim(File f) {
        List<String> keep = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String s;
            while ((s = r.readLine()) != null) keep.add(s);
        } catch (Exception e) {
            return;
        }
        try (FileWriter fw = new FileWriter(f, false)) {
            for (int i = keep.size() / 2; i < keep.size(); i++) fw.write(keep.get(i) + "\n");
        } catch (Exception ignored) { }
    }
}
