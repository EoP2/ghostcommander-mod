package com.ghostsq.commander.yun139;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Settings screen: paste/test/save/clear the 139 Yun "Authorization" token.
 * <p>
 * Reachable two ways, exactly like the webdav/box plugins' own Prefs activities:
 * - long-press the "139 Yun" entry on GhostCommander's Home screen -> "prefs"
 *   (HomeAdapter looks up an explicit Intent to "<package>.Prefs")
 * - automatically, from Yun139Adapter.readSource() the first time there is no token yet
 * <p>
 * Reads/writes the SAME SharedPreferences file Yun139Adapter uses, by reaching into the
 * host app's package context - the adapter itself already runs WITH that context (it is
 * handed the host's own Context in yun139.createInstance()), but this Activity is a
 * normal, separately-declared component of the yun139 APK, so left alone its Context
 * would point at this plugin's own (different) package storage instead.
 */
public class Prefs extends Activity {
    private static final String TAG = "Yun139Prefs";
    private static final String HOST_PACKAGE = "com.ghostsq.commander";

    private EditText tokenEdit;
    private TextView statusText;
    private Context appCtx;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_prefs);

        appCtx = resolveAppContext();

        tokenEdit = findViewById(R.id.token_edit);
        statusText = findViewById(R.id.status_text);

        String existing = Yun139Api.loadAuthorization(appCtx);
        if (existing != null)
            tokenEdit.setText(existing);

        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                save();
            }
        });
        findViewById(R.id.btn_test).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                test();
            }
        });
        findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                clear();
            }
        });
    }

    private Context resolveAppContext() {
        try {
            return createPackageContext(HOST_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "host package not found", e);
            return this; // shouldn't happen: this plugin can't be reached without the host app
        }
    }

    private String tokenText() {
        return tokenEdit.getText().toString().trim();
    }

    private void save() {
        String token = tokenText();
        if (token.length() == 0) {
            statusText.setText(R.string.status_empty);
            return;
        }
        Yun139Api.saveAuthorization(appCtx, token);
        statusText.setText(R.string.status_saved);
        Toast.makeText(this, R.string.status_saved, Toast.LENGTH_SHORT).show();
    }

    private void clear() {
        Yun139Api.saveAuthorization(appCtx, null);
        tokenEdit.setText("");
        statusText.setText(R.string.status_cleared);
    }

    private void test() {
        final String token = tokenText();
        if (token.length() == 0) {
            statusText.setText(R.string.status_empty);
            return;
        }
        // Save first: this both lets a fresh, verified token stick around for real use
        // afterwards, and means Yun139Api tests the exact value shown in the box.
        Yun139Api.saveAuthorization(appCtx, token);
        statusText.setText(R.string.status_testing);

        final Yun139Api api = new Yun139Api(appCtx);
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            public void run() {
                try {
                    final String masked = api.testConnection();
                    ui.post(new Runnable() {
                        public void run() {
                            statusText.setText(getString(R.string.status_ok, masked));
                        }
                    });
                } catch (final Exception e) {
                    Log.e(TAG, "test", e);
                    ui.post(new Runnable() {
                        public void run() {
                            statusText.setText(getString(R.string.status_fail, String.valueOf(e.getMessage())));
                        }
                    });
                }
            }
        }).start();
    }
}
