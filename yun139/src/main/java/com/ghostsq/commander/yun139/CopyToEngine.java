package com.ghostsq.commander.yun139;

import android.net.Uri;
import android.util.Log;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.utils.Utils;
import com.ghostsq.commander.yun139.Yun139Api.FileEntry;

import java.io.File;

/**
 * Uploads local files/folders (the "universal transport parcel" of receiveItems) into
 * the folder yun139 is currently showing. Existing-name conflicts go through the same
 * askOnFileExist() dialog every other plugin uses.
 */
class CopyToEngine extends Yun139EngineBase {
    private final Commander commander;
    private final String[] fileURIs;
    private final int moveMode;
    private final String destFolderFileId;
    private int filesDone = 0;

    CopyToEngine(Commander commander, Yun139Adapter a, String[] fileURIs, int moveMode) {
        super(a);
        setEngineName("Yun139.CopyToEngine");
        this.commander = commander;
        this.fileURIs = fileURIs;
        this.moveMode = moveMode;
        this.destFolderFileId = Yun139Adapter.fileIdFromUri(a.getUri());
    }

    public void run() {
        threadStartedAt = System.currentTimeMillis();
        boolean move = moveMode == CommanderAdapter.MODE_MOVE || moveMode == CommanderAdapter.MODE_MOVE_DEL_SRC_DIR;
        try {
            int total = Math.max(1, fileURIs.length);
            for (int i = 0; i < fileURIs.length; i++) {
                if (stop) break;
                progress = (int) (100.0 * i / total);
                File f = toFile(fileURIs[i]);
                boolean ok = copyPath(f, destFolderFileId);
                if (ok && move)
                    deleteLocalRecursively(f);
            }
        } catch (Exception e) {
            Log.e(TAG, "receive", e);
            error(e.getMessage() != null ? e.getMessage() : e.toString());
        }
        String report = Utils.getOpReport(adapter.ctx, filesDone, move ? Utils.RR.moved.r() : Utils.RR.copied.r());
        sendResult(report);
    }

    private boolean copyPath(File f, String destParentFileId) throws Exception {
        if (!f.exists()) {
            error(f.getName() + "：源文件不存在");
            return false;
        }
        String name = f.getName();
        if (f.isDirectory()) {
            FileEntry existing = findChild(destParentFileId, name);
            String childParentId;
            if (existing != null && existing.isDir) {
                childParentId = existing.fileId;
            } else if (existing != null) {
                int res = askOnFileExist(adapter.ctx.getString(Utils.RR.file_exist.r(), name), commander);
                if (res == Commander.ABORT) {
                    stop = true;
                    return false;
                }
                if (res == Commander.SKIP)
                    return false;
                api.delete(existing.fileId);
                childParentId = api.mkdir(destParentFileId, name);
            } else {
                childParentId = api.mkdir(destParentFileId, name);
            }
            boolean allOk = true;
            File[] kids = f.listFiles();
            if (kids != null)
                for (File k : kids) {
                    if (stop) break;
                    allOk &= copyPath(k, childParentId);
                }
            return allOk;
        } else {
            return uploadOneFile(f, destParentFileId, name);
        }
    }

    private boolean uploadOneFile(final File f, String destParentFileId, String name) throws Exception {
        FileEntry existing = findChild(destParentFileId, name);
        if (existing != null) {
            long destDate = existing.updatedAt > 0 ? existing.updatedAt : existing.createdAt;
            int res = askOnFileExist(commander, adapter.ctx.getString(Utils.RR.file_exist.r(), name),
                    f.lastModified(), destDate, f.length(), existing.size,
                    Commander.ABORT | Commander.REPLACE | Commander.SKIP | Commander.REPLACE_OLD);
            if (res == Commander.REPLACE_OLD)
                res = f.lastModified() > destDate ? Commander.REPLACE : Commander.SKIP;
            if (res == Commander.ABORT) {
                stop = true;
                return false;
            }
            if (res == Commander.SKIP)
                return false;
            if (res == Commander.REPLACE)
                api.delete(existing.fileId);
        }
        final long fsize = f.length();
        final int overallProgress = progress;
        api.uploadFile(destParentFileId, f, name, new Yun139Api.ProgressListener() {
            long done = 0;

            public void onBytes(long n) {
                done += n;
                if (fsize > 0)
                    sendProgress(f.getName(), overallProgress, (int) (100.0 * done / fsize));
            }

            public boolean isCancelled() {
                return stop;
            }
        });
        filesDone++;
        return true;
    }

    private void deleteLocalRecursively(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null)
                for (File k : kids)
                    deleteLocalRecursively(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static File toFile(String s) {
        if (s.startsWith("file://") || s.startsWith("file:/"))
            return new File(Uri.parse(s).getPath());
        return new File(s);
    }
}
