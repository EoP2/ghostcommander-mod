package com.ghostsq.commander.yun139;

import android.util.Log;

import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.yun139.Yun139Api.FileEntry;

import java.util.List;

class RenEngine extends Yun139EngineBase {
    private final String parentFileId;
    private final CommanderAdapter.Item item;
    private final String newName;
    private final boolean copy;
    String resultFileId;

    RenEngine(Yun139Adapter a, String parentFileId, CommanderAdapter.Item item, String newName, boolean copy) {
        super(a);
        setEngineName("Yun139.RenEngine");
        this.parentFileId = parentFileId;
        this.item = item;
        this.newName = newName;
        this.copy = copy;
    }

    public void run() {
        threadStartedAt = System.currentTimeMillis();
        try {
            sendProgress();
            String fileId = Yun139Adapter.fileIdOf(item);
            if (copy) {
                // "duplicate, keep the old name too": copy into the same folder, then rename the copy.
                api.copy(fileId, parentFileId);
                FileEntry dup = findFreshCopy(fileId);
                if (dup != null) {
                    api.rename(dup.fileId, newName);
                    resultFileId = dup.fileId;
                } else {
                    error("已复制，但未能确认副本以完成改名，请在列表中手动重命名新副本");
                }
            } else {
                api.rename(fileId, newName);
                resultFileId = fileId;
            }
        } catch (Exception e) {
            Log.e(TAG, "rename", e);
            error(e.getMessage() != null ? e.getMessage() : e.toString());
        }
        sendResult("");
    }

    /** Best-effort: after batchCopy, find the newest same-named sibling that isn't the original. */
    private FileEntry findFreshCopy(String origFileId) throws java.io.IOException {
        List<FileEntry> list = api.list(parentFileId);
        FileEntry best = null;
        for (FileEntry fe : list) {
            if (fe.fileId == null || fe.fileId.equals(origFileId)) continue;
            if (item.name != null && item.name.equals(fe.name)) {
                if (best == null || fe.createdAt > best.createdAt || fe.updatedAt > best.updatedAt)
                    best = fe;
            }
        }
        return best;
    }
}
