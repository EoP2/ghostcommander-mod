package com.ghostsq.commander.yun139;

import android.util.Log;

class MkDirEngine extends Yun139EngineBase {
    private final String parentFileId;
    private final String name;
    String newFileId;

    MkDirEngine(Yun139Adapter a, String parentFileId, String name) {
        super(a);
        setEngineName("Yun139.MkDirEngine");
        this.parentFileId = parentFileId;
        this.name = name;
    }

    public void run() {
        threadStartedAt = System.currentTimeMillis();
        try {
            sendProgress();
            newFileId = api.mkdir(parentFileId, name);
        } catch (Exception e) {
            Log.e(TAG, "mkdir", e);
            error(e.getMessage() != null ? e.getMessage() : e.toString());
        }
        sendResult("");
    }
}
