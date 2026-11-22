package com.boxtools.adbrescue;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Thread-safe in-memory ring buffer of timestamped log lines (last N). */
public final class RingLog {
    private static final int CAP = 100;
    private static final String TAG = "adb-rescue";
    private final ArrayDeque<String> lines = new ArrayDeque<>(CAP);
    private final SimpleDateFormat fmt =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    public synchronized void add(String msg) {
        String line = fmt.format(new Date()) + " " + msg;
        if (lines.size() >= CAP) {
            lines.pollFirst();
        }
        lines.addLast(line);
        Log.i(TAG, msg);
    }

    public synchronized List<String> snapshot() {
        return new ArrayList<>(lines);
    }
}
