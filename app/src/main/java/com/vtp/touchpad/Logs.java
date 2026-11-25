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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 轻量运行日志：写进应用私有目录 pad.log，用户反馈问题时可直接导出。
 * 只记状态变化与关键动作，不刷高频输出；文件超过上限时保留最近一半。
 *
 * 写盘放到单线程后台队列：调用点多在拖动手势与轮询里，同步 open/write/close 会把主线程
 * 卡在存储 IO 上。读日志前先排空队列，保证读到的内容包含刚发生的记录。
 */
public final class Logs {

    private static final String FILE_NAME = "pad.log";
    private static final long MAX_BYTES = 256 * 1024;
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    /** 单线程串行写，日志顺序不丢；线程设为后台优先级，不跟界面抢 CPU。 */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pad-log");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        return t;
    });

    private Logs() { }

    public static void w(Context c, String tag, String msg) {
        if (c == null) return;
        final String line;
        synchronized (FMT) {
            line = FMT.format(new Date()) + " [" + tag + "] " + msg + "\n";
        }
        final File dir = c.getApplicationContext().getFilesDir();
        IO.execute(() -> append(dir, line));
    }

    public static String read(Context c) {
        if (c == null) return "";
        File dir = c.getApplicationContext().getFilesDir();
        drain();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(new File(dir, FILE_NAME)))) {
            String s;
            while ((s = r.readLine()) != null) sb.append(s).append('\n');
        } catch (Exception e) {
            return "";
        }
        return sb.toString();
    }

    public static void clear(Context c) {
        if (c == null) return;
        final File dir = c.getApplicationContext().getFilesDir();
        drain();
        //noinspection ResultOfMethodCallIgnored
        new File(dir, FILE_NAME).delete();
    }

    /** 等队列里已排的记录都落盘；只在读/清空时调用，不会卡在主线程的常见路径上。 */
    private static void drain() {
        try {
            IO.submit(() -> { }).get();
        } catch (Exception ignored) { }
    }

    private static void append(File dir, String line) {
        File f = new File(dir, FILE_NAME);
        try {
            if (f.length() > MAX_BYTES) trim(f);
            FileWriter fw = new FileWriter(f, true);
            try { fw.write(line); } finally { fw.close(); }
        } catch (Exception ignored) { }
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
