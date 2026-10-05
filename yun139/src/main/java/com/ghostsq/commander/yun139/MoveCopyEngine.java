package com.ghostsq.commander.yun139;

import android.util.Log;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.utils.Utils;
import com.ghostsq.commander.yun139.Yun139Api.FileEntry;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Copies or moves items to another folder of the SAME 139 account, entirely on the server.
 * <p>
 * Source and destination are on one and the same cloud drive, so there is no reason to pull the
 * bytes through the phone. CopyFromEngine is the generic "out of this plugin" path: it downloads
 * every file, buffers it in a temp file (Receiver) and uploads it again, and for a move it then
 * deletes the original. This engine instead asks the server to do the work with
 * /file/batchMove and /file/batchCopy (Yun139Api.move / copy): one request per selected item -
 * for a folder, one request for its whole subtree - with no traffic and no local storage, no matter
 * how big the files are.
 * <p>
 * It is only for the same account: those two calls act inside one account's drive. Copying
 * between two different accounts, or to/from any other kind of location, still has to move the
 * bytes and stays with CopyFromEngine / CopyToEngine (see Yun139Adapter.copyItems).
 * <p>
 * Name clashes are settled here, BEFORE the server is asked, rather than leaving it to whatever
 * batchMove/batchCopy would do with them (nothing this plugin can see documents that), and so that
 * the user gets the very same questions as with every other adapter:
 * <ul>
 * <li>a file onto a file of the same name: the usual "file exists" dialog; "replace" sends the
 *     old file to the recycle bin first, exactly as Receiver/CopyToEngine do;</li>
 * <li>a folder onto a folder of the same name: the two are merged, as handleDirOnReceiver() does
 *     for the other adapters - the children are carried over one by one with these same rules;</li>
 * <li>a file onto a folder or a folder onto a file: skipped with an error, nothing is deleted.</li>
 * </ul>
 */
class MoveCopyEngine extends Yun139EngineBase {
    private final Commander commander;
    private final CommanderAdapter.Item[] items;
    private final String destFolderId;
    private final boolean move;
    private int done = 0;

    MoveCopyEngine(Commander commander, Yun139Adapter a, Yun139Api api,
                   CommanderAdapter.Item[] items, String destFolderId, boolean move) {
        super(a, api);
        setEngineName("Yun139.MoveCopyEngine");
        this.commander = commander;
        this.items = items;
        this.destFolderId = destFolderId;
        this.move = move;
    }

    public void run() {
        threadStartedAt = System.currentTimeMillis();
        try {
            sendProgress();
            // One listing of the destination answers every "is there one with that name already?" below.
            Map<String, FileEntry> destNames = indexByName(api.list(destFolderId));
            int total = Math.max(1, items.length);
            for (int i = 0; i < items.length; i++) {
                if (stop) break;
                CommanderAdapter.Item it = items[i];
                progress = (int) (100.0 * i / total);
                sendProgress(it.name, progress);
                if (transfer(entryOf(it), destFolderId, destNames))
                    done++;
            }
        } catch (InterruptedException e) {
            Log.w(TAG, "interrupted while waiting for the user", e);
        } catch (Exception e) {
            Log.e(TAG, "move/copy", e);
            error(e.getMessage() != null ? e.getMessage() : e.toString());
        }
        sendResult(Utils.getOpReport(adapter.ctx, done,
                move ? Utils.RR.moved.r() : Utils.RR.copied.r()));
    }

    /**
     * Moves/copies one item into destParentId. A server failure is reported against the item's
     * name and costs only that item - the rest of the selection carries on, like in CopyFromEngine.
     *
     * @param destNames the entries of destParentId by name, as listed before the operation began
     * @return true if the item is completely in place at the destination (for a move: and gone
     *         from the source), false if it was skipped or failed
     */
    private boolean transfer(FileEntry src, String destParentId, Map<String, FileEntry> destNames)
            throws InterruptedException {
        try {
            FileEntry clash = src.name != null ? destNames.get(src.name) : null;
            if (clash == null) {
                serverOp(src.fileId, destParentId);
                return true;
            }
            if (src.isDir != clash.isDir) {
                error(src.name + "：目标位置已有同名的" + (clash.isDir ? "文件夹" : "文件") + "，已跳过");
                return false;
            }
            if (src.isDir)
                return mergeFolder(src, clash);

            long srcDate = dateOf(src);
            long dstDate = dateOf(clash);
            int res = askOnFileExist(commander,
                    adapter.ctx.getString(Utils.RR.file_exist.r(),
                            "<small>" + src.name.replace("&", "&amp;") + "</small>"),
                    srcDate, dstDate, src.size, clash.size,
                    Commander.ABORT | Commander.REPLACE | Commander.SKIP | Commander.REPLACE_OLD);
            if (res == Commander.REPLACE_OLD)
                res = srcDate > dstDate ? Commander.REPLACE : Commander.SKIP;
            if (res == Commander.ABORT) {
                stop = true;
                return false;
            }
            if (res != Commander.REPLACE)
                return false; // SKIP
            api.delete(clash.fileId); // into the recycle bin, like every other delete in this plugin
            serverOp(src.fileId, destParentId);
            return true;
        } catch (IOException e) {
            Log.e(TAG, String.valueOf(src.name), e);
            error(src.name + "：" + (e.getMessage() != null ? e.getMessage() : e.toString()));
            return false;
        }
    }

    /**
     * A folder is moved/copied onto an existing folder of the same name: carry the source's
     * children over into the existing one, each under the same rules as transfer().
     */
    private boolean mergeFolder(FileEntry srcDir, FileEntry destDir)
            throws IOException, InterruptedException {
        List<FileEntry> kids = api.list(srcDir.fileId);
        Map<String, FileEntry> destKids = indexByName(api.list(destDir.fileId));
        boolean allOk = true;
        for (FileEntry k : kids) {
            if (stop)
                return false;
            allOk &= transfer(k, destDir.fileId, destKids);
        }
        if (allOk && move) {
            // Everything was carried out of it, so what is left of the source folder is an empty shell.
            try {
                api.delete(srcDir.fileId);
            } catch (IOException e) {
                Log.w(TAG, "delete emptied " + srcDir.name, e);
                error(srcDir.name + "：内容已合并，但未能删除原文件夹：" + e.getMessage());
                return false;
            }
        }
        return allOk;
    }

    private void serverOp(String srcFileId, String destParentId) throws IOException {
        if (move)
            api.move(srcFileId, destParentId);
        else
            api.copy(srcFileId, destParentId);
    }

    private static long dateOf(FileEntry fe) {
        return fe.updatedAt > 0 ? fe.updatedAt : fe.createdAt;
    }

    private static Map<String, FileEntry> indexByName(List<FileEntry> list) {
        Map<String, FileEntry> m = new HashMap<String, FileEntry>();
        for (FileEntry fe : list)
            if (fe.name != null)
                m.put(fe.name, fe);
        return m;
    }

    /** What a listing row stands for, as the FileEntry the rest of this class works with. */
    private static FileEntry entryOf(CommanderAdapter.Item it) {
        if (it.origin instanceof FileEntry)
            return (FileEntry) it.origin;
        FileEntry fe = new FileEntry();
        fe.fileId = Yun139Adapter.fileIdOf(it);
        fe.name = it.name;
        fe.isDir = it.dir;
        fe.size = it.size;
        fe.updatedAt = it.date != null ? it.date.getTime() : 0;
        return fe;
    }
}