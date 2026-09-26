package com.ghostsq.commander.yun139;

import android.os.Handler;
import android.util.Log;

import com.ghostsq.commander.yun139.Yun139Api.FileEntry;

import java.util.List;

/**
 * Fetches the children of one folder. Started directly (reader.start()), not via
 * commander.startEngine(), exactly like the webdav/gdrive plugins' own ListEngine -
 * see Yun139Adapter.readSource().
 */
class ListEngine extends Yun139EngineBase {
    private final String parentFileId;
    private final String passBack;
    List<FileEntry> result;

    ListEngine(Handler h, Yun139Adapter a, String parentFileId, String passBack) {
        super(a);
        setHandler(h);
        setEngineName("Yun139.ListEngine");
        this.parentFileId = parentFileId;
        this.passBack = passBack;
    }

    public void run() {
        threadStartedAt = System.currentTimeMillis();
        Log.i(TAG, "run() parentFileId=" + parentFileId);
        try {
            sendProgress();
            result = api.list(parentFileId);
            Log.i(TAG, "run() done, " + (result != null ? result.size() : -1) + " items");
        } catch (Exception e) {
            Log.e(TAG, "run() failed for parentFileId=" + parentFileId, e);
            error(e.getMessage() != null ? e.getMessage() : e.toString());
        }
        doneReading(null, passBack);
    }
}
