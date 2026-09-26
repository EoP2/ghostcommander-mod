package com.ghostsq.commander.yun139;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.ServerForm;

/**
 * Adds or re-authenticates ONE 139 Yun account: an alias (a locally-chosen label, not
 * tied to the phone number) plus a pasted "Authorization" token. Editing several accounts
 * means opening this screen several times, once per alias - it never lists them.
 * <p>
 * Reachable two ways:
 * - automatically, from Yun139Adapter.readSource() (via launchAccountForm()) whenever the
 *   Uri being navigated to names no configured account at all, or one whose token is
 *   missing/no longer usable. Launched with commander.issue(..., REQUEST_CODE_SRV_FORM),
 *   exactly like the ftp/sftp/smb/webdav plugins' shared, core ServerForm is (see
 *   WebDAVAdapter.readSource() for the closest sibling). On success this Activity returns
 *   a Commander.NAVIGATE_ACTION result carrying the new/updated account's root Uri, and -
 *   if "add to favourites" was checked - the same ServerForm.ADD_FAVE_KEY/COMMENT_KEY
 *   extras ServerForm uses. FileCommander's own, already-scheme-agnostic
 *   REQUEST_CODE_SRV_FORM handling in onActivityResult() picks that up and does the
 *   favouriting + navigating - no :app changes needed for any of this to work, since that
 *   handling keys off the request code, not off which class was actually launched.
 * - long-press the "139 Yun" entry on GhostCommander's Home screen -> "prefs" (HomeAdapter
 *   looks up an explicit Intent to "<package>.Prefs" and launches it with a plain
 *   startActivity(), same as it does for every other plugin's own settings screen - so
 *   reached this way there is no requestCode and nothing collects the result above: this
 *   is simply "add a new account", the token still gets saved and the user switches to it
 *   via Favourites afterwards, exactly as with smb/webdav's OWN long-press "settings"
 *   entries, which are themselves unrelated to their per-connection ServerForm too).
 * <p>
 * Switching between already-configured accounts is NOT done here - exactly like
 * ftp/sftp/smb/webdav, that is Favourites' job (bookmark yun139://alias1/..., .../alias2/...
 * separately). This screen only ever edits one account at a time.
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

    /** Intent extra: the alias to re-authenticate. Absent/blank means "add a new account". */
    static final String EXTRA_ALIAS = "alias";
    /** Intent extra: set only by Yun139Adapter.launchAccountForm() - see its own doc comment. */
    static final String EXTRA_EXPECTS_RESULT = "expectsResult";

    private EditText aliasEdit;
    private EditText tokenEdit;
    private TextView statusText;
    private CheckBox addFaveCb;
    private View commentBlock;
    private EditText commentEdit;
    private Context appCtx;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_prefs);

        appCtx = resolveAppContext();

        aliasEdit = findViewById(R.id.alias_edit);
        tokenEdit = findViewById(R.id.token_edit);
        statusText = findViewById(R.id.status_text);
        addFaveCb = findViewById(R.id.add_fave);
        commentBlock = findViewById(R.id.comment_block);
        commentEdit = findViewById(R.id.comment_edit);
        View deleteBtn = findViewById(R.id.btn_delete);

        String presetAlias = getIntent().getStringExtra(EXTRA_ALIAS);
        if (presetAlias != null && presetAlias.length() > 0) {
            // Re-authenticating a specific, already-bookmarked account: lock the alias so
            // confirming here can't accidentally spawn a second, differently-named entry.
            aliasEdit.setText(presetAlias);
            aliasEdit.setEnabled(false);
            // This is the only place that explains *why* the form popped up on its own -
            // Yun139Adapter deliberately does not also raise a separate dialog for this
            // (see its launchAccountForm()'s doc comment), so this status line is it.
            statusText.setText(R.string.status_reauth);
            String existing = Yun139Api.loadAuthorization(appCtx, presetAlias);
            if (existing != null)
                tokenEdit.setText(existing);
            deleteBtn.setVisibility(View.VISIBLE);
        }
        if (!getIntent().getBooleanExtra(EXTRA_EXPECTS_RESULT, false)) {
            // Reached via Home's long-press "prefs" (plain startActivity(), ret=0): nothing
            // will ever collect the NAVIGATE/ADD_FAVE result connect() builds below, so a
            // checkbox offering to add to Favourites here would silently do nothing if
            // checked - see Yun139Adapter.launchAccountForm()'s own doc comment.
            addFaveCb.setVisibility(View.GONE);
        }

        addFaveCb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                commentBlock.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            }
        });

        findViewById(R.id.btn_connect).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                connect();
            }
        });
        findViewById(R.id.btn_test).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                test();
            }
        });
        findViewById(R.id.btn_cancel).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setResult(RESULT_CANCELED);
                finish();
            }
        });
        deleteBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                delete();
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

    private String aliasText() {
        return aliasEdit.getText().toString().trim();
    }

    private String tokenText() {
        return tokenEdit.getText().toString().trim();
    }

    /**
     * Saves the token, then returns a ServerForm-shaped Commander.NAVIGATE_ACTION result
     * and finishes - see this class' own doc comment for exactly who picks that result up,
     * and how, when this screen was reached via Yun139Adapter.launchAccountForm().
     */
    private void connect() {
        String alias = aliasText();
        if (alias.length() == 0) {
            statusText.setText(R.string.status_need_alias);
            return;
        }
        String token = tokenText();
        if (token.length() == 0) {
            statusText.setText(R.string.status_empty);
            return;
        }
        Yun139Api.saveAuthorization(appCtx, alias, token);

        Uri uri = Yun139Adapter.folderUri(alias, null);
        Intent result = new Intent(Commander.NAVIGATE_ACTION, uri);
        if (addFaveCb.isChecked()) {
            result.putExtra(ServerForm.ADD_FAVE_KEY, true);
            result.putExtra(ServerForm.COMMENT_KEY, commentEdit.getText().toString());
        }
        setResult(RESULT_OK, result);
        finish();
    }

    /** Forgets this account entirely (its token, phone number, and its slot in the alias list). */
    private void delete() {
        String alias = aliasText();
        if (alias.length() > 0)
            Yun139Api.deleteAlias(appCtx, alias);
        setResult(RESULT_CANCELED);
        finish();
    }

    private void test() {
        final String alias = aliasText();
        if (alias.length() == 0) {
            statusText.setText(R.string.status_need_alias);
            return;
        }
        final String token = tokenText();
        if (token.length() == 0) {
            statusText.setText(R.string.status_empty);
            return;
        }
        // Save first: this both lets a fresh, verified token stick around for real use
        // afterwards, and means Yun139Api tests the exact value shown in the box.
        Yun139Api.saveAuthorization(appCtx, alias, token);
        statusText.setText(R.string.status_testing);

        final Yun139Api api = new Yun139Api(appCtx, alias);
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
