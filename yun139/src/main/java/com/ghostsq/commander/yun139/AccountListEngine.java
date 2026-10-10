package com.ghostsq.commander.yun139;

import android.os.Handler;

import com.ghostsq.commander.adapters.Engine;

/**
 * Lists the saved account aliases - the top level of the yun139: Uri space, above any
 * individual account's drive. This is a purely local SharedPreferences read, no network
 * involved, so it finishes almost instantly; it still goes through a real Engine thread
 * (started directly like ListEngine, see Yun139Adapter.readSource()) rather than filling
 * in the listing synchronously, so that the framework's reader/spinner/onReadComplete()
 * contract behaves identically for both kinds of listing and nothing has to special-case
 * "this readSource() call never produced an asynchronous completion".
 * <p>
 * Deliberately extends Engine directly instead of Yun139EngineBase: there is no single
 * Yun139Api to bind here - this level sits above every account.
 */
class AccountListEngine extends Engine {
    private final Yun139Adapter adapter;
    private final String passBack;
    String[] result;

    AccountListEngine(Handler h, Yun139Adapter a, String passBack) {
        super();
        this.adapter = a;
        setHandler(h);
        setEngineName("Yun139.AccountListEngine");
        this.passBack = passBack;
    }

    public void run() {
        threadStartedAt = System.currentTimeMillis();
        try {
            sendProgress();
            result = Yun139Api.listAccounts(adapter.ctx);
        } catch (Exception e) {
            error(e.getMessage() != null ? e.getMessage() : e.toString());
        }
        doneReading(null, passBack);
    }
}
