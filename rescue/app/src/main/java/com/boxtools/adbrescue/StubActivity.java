package com.boxtools.adbrescue;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/**
 * Invisible, zero-UI activity. Its only job is to let the PC un-stop the
 * package (`am start -n com.boxtools.adbrescue/.StubActivity`) and kick the
 * service. It finishes immediately so nothing is ever shown.
 */
public class StubActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Intent svc = new Intent(this, RescueService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Throwable t) {
            // ignore
        }
        finish();
    }
}
