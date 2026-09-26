package com.ghostsq.commander.yun139;

import android.util.Log;

import com.ghostsq.commander.adapters.CommanderAdapter;

class DelEngine extends Yun139EngineBase {
    private final CommanderAdapter.Item[] toDelete;

    DelEngine(Yun139Adapter a, CommanderAdapter.Item[] toDelete) {
        super(a);
        setEngineName("Yun139.DelEngine");
        this.toDelete = toDelete;
    }

    public void run() {
        threadStartedAt = System.currentTimeMillis();
        int n = toDelete.length;
        for (int i = 0; i < n; i++) {
            if (stop) break;
            CommanderAdapter.Item it = toDelete[i];
            try {
                sendProgress(it.name, (int) (100.0 * i / n));
                api.delete(Yun139Adapter.fileIdOf(it));
            } catch (Exception e) {
                Log.e(TAG, "delete " + it.name, e);
                error(it.name + ": " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            }
        }
        sendResult("");
    }
}
