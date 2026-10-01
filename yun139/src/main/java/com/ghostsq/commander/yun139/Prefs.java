package com.ghostsq.commander.yun139;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Settings screen: the list of saved 139 Yun accounts, with add / test / remove.
 * This is the ONLY place accounts are managed - the file manager's own gestures (delete,
 * rename, ...) are deliberately not overloaded for it, see Yun139Adapter.
 * <p>
 * Each account is a user-chosen alias (which becomes the Uri authority, so it shows up in
 * paths - see Yun139Adapter) plus the pasted "Authorization" token. The alias is the
 * account's identity and is fixed once added; to change it, remove the account and add it
 * again.
 * <p>
 * Reachable two ways, exactly like the webdav/box plugins' own Prefs activities:
 * - long-press the "139云盘" entry on GhostCommander's Home screen -> "prefs"
 *   (HomeAdapter looks up an explicit Intent to "<package>.Prefs")
 * - automatically, from Yun139Adapter.readSource() when there is no account at all yet
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

    private Context appCtx;
    private AccountsAdapter listAdapter;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_prefs);

        appCtx = resolveAppContext();

        ListView list = findViewById(R.id.account_list);
        list.setEmptyView(findViewById(R.id.empty_text));
        listAdapter = new AccountsAdapter();
        list.setAdapter(listAdapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                showAccountDialog(listAdapter.getItem(position));
            }
        });

        findViewById(R.id.btn_add).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showAddDialog();
            }
        });

        reload();
    }

    private Context resolveAppContext() {
        try {
            return createPackageContext(HOST_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "host package not found", e);
            return this; // shouldn't happen: this plugin can't be reached without the host app
        }
    }

    private void reload() {
        listAdapter.setData(new ArrayList<String>(Arrays.asList(Yun139Api.listAccounts(appCtx))));
    }

    // ------------------------------------------------------------------
    // Add an account
    // ------------------------------------------------------------------

    private void showAddDialog() {
        View form = LayoutInflater.from(this).inflate(R.layout.dialog_add_account, null);
        final EditText aliasEdit = form.findViewById(R.id.alias_edit);
        final EditText tokenEdit = form.findViewById(R.id.token_edit);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.add_account_title)
                .setView(form)
                .setPositiveButton(R.string.btn_add, null) // real handler is set in onShow, see below
                .setNegativeButton(R.string.btn_cancel, null)
                .create();

        // AlertDialog closes itself after ANY button click, including from a listener passed
        // to setPositiveButton(). Rejecting bad input has to leave the dialog open, or the
        // (long, pasted) token would be lost on every typo - hence the handler is attached to
        // the button itself once the dialog has been shown.
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface d) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        String err = Yun139Api.addAccount(appCtx,
                                aliasEdit.getText().toString(), tokenEdit.getText().toString());
                        if (err != null) {
                            Toast.makeText(Prefs.this, err, Toast.LENGTH_LONG).show();
                            return;
                        }
                        Toast.makeText(Prefs.this, R.string.status_added, Toast.LENGTH_SHORT).show();
                        reload();
                        dlg.dismiss();
                    }
                });
            }
        });
        dlg.show();
    }

    // ------------------------------------------------------------------
    // An existing account: test it / remove it
    // ------------------------------------------------------------------

    private void showAccountDialog(final String alias) {
        String phone = Yun139Api.getMaskedPhone(appCtx, alias);
        new AlertDialog.Builder(this)
                .setTitle(alias)
                .setMessage(phone != null ? getString(R.string.account_phone, phone) : "")
                .setPositiveButton(R.string.btn_test, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        testAccount(alias);
                    }
                })
                .setNegativeButton(R.string.btn_remove, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        confirmRemove(alias);
                    }
                })
                .setNeutralButton(R.string.btn_cancel, null)
                .show();
    }

    private void confirmRemove(final String alias) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.confirm_remove, alias))
                .setPositiveButton(R.string.btn_remove, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        Yun139Api.removeAccount(appCtx, alias);
                        reload();
                    }
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private void testAccount(final String alias) {
        Toast.makeText(this, R.string.status_testing, Toast.LENGTH_SHORT).show();
        final Yun139Api api = Yun139Api.getInstance(appCtx, alias);
        new Thread(new Runnable() {
            public void run() {
                try {
                    final String masked = api.testConnection();
                    ui.post(new Runnable() {
                        public void run() {
                            Toast.makeText(Prefs.this, getString(R.string.status_ok, masked), Toast.LENGTH_LONG).show();
                            reload(); // testing may have refreshed the stored token/phone
                        }
                    });
                } catch (final Exception e) {
                    Log.e(TAG, "test", e);
                    ui.post(new Runnable() {
                        public void run() {
                            Toast.makeText(Prefs.this,
                                    getString(R.string.status_fail, String.valueOf(e.getMessage())),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    // ------------------------------------------------------------------
    // Two-line list rows (alias / masked phone), on the system's own row layout
    // ------------------------------------------------------------------

    private class AccountsAdapter extends BaseAdapter {
        private List<String> aliases = new ArrayList<String>();

        void setData(List<String> a) {
            aliases = a;
            notifyDataSetChanged();
        }

        public int getCount() {
            return aliases.size();
        }

        public String getItem(int position) {
            return aliases.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(Prefs.this).inflate(android.R.layout.simple_list_item_2, parent, false);
            String alias = getItem(position);
            String phone = Yun139Api.getMaskedPhone(appCtx, alias);
            TextView text1 = v.findViewById(android.R.id.text1);
            TextView text2 = v.findViewById(android.R.id.text2);
            text1.setText(alias);
            text2.setText(phone != null ? phone : "");
            return v;
        }
    }
}
