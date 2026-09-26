package com.ghostsq.commander.yun139;

import android.net.Uri;
import android.util.Log;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.IReceiver;
import com.ghostsq.commander.utils.Utils;
import com.ghostsq.commander.yun139.Yun139Api.FileEntry;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Date;
import java.util.List;

/**
 * Copies (or moves) the selected items OUT of yun139 into another adapter ("to"),
 * which may be local storage, another network adapter, or another cloud plugin.
 * Follows the same handleDirOnReceiver/handleItemOnReceiver/conveyStreamToReceiver
 * pattern the framework already provides in Engine, exactly like the webdav/gdrive
 * plugins' own CopyFromEngine.
 */
class CopyFromEngine extends Yun139EngineBase {
    private final Commander commander;
    private final CommanderAdapter.Item[] toCopy;
    private final boolean move;
    private CommanderAdapter to;

    CopyFromEngine(Commander commander, Yun139Adapter a, CommanderAdapter.Item[] toCopy, boolean move, CommanderAdapter to) {
        super(a);
        setEngineName("Yun139.CopyFromEngine");
        this.commander = commander;
        this.toCopy = toCopy;
        this.move = move;
        this.to = substituteReceiver(a.ctx, to);
    }

    public void run() {
        threadStartedAt = System.currentTimeMillis();
        int done = 0;
        try {
            IReceiver receiver = to.getReceiver(to.getUri());
            if (receiver == null) {
                error("目标不支持接收文件");
            } else {
                int total = Math.max(1, toCopy.length);
                for (CommanderAdapter.Item it : toCopy) {
                    if (stop) break;
                    progress = (int) (100.0 * done / total);
                    boolean itemOk = it.dir
                            ? copyFolder(receiver, Yun139Adapter.fileIdOf(it), it.name)
                            : copyFile(receiver, Yun139Adapter.fileIdOf(it), it.name, it.size, it.date);
                    if (itemOk && move && noErrors()) {
                        try {
                            api.delete(Yun139Adapter.fileIdOf(it));
                        } catch (Exception e) {
                            Log.w(TAG, "post-move delete " + it.name, e);
                        }
                    }
                    done++;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "copyFrom", e);
            error(e.getMessage() != null ? e.getMessage() : e.toString());
        }
        String report = Utils.getOpReport(adapter.ctx, done,
                move ? Utils.RR.moved.r() : Utils.RR.copied.r());
        sendResult(report);
    }

    /** @return true if the file was copied (or intentionally skipped without error) */
    private boolean copyFile(IReceiver receiver, String fileId, String name, long size, Date date) {
        try {
            Uri existing = receiver.getItemURI(name, false);
            int res = handleItemOnReceiver(commander, receiver, existing, name,
                    date != null ? date.getTime() : -1, size);
            if (res == Commander.ABORT) {
                stop = true;
                return false;
            }
            if (res == Commander.SKIP)
                return false;

            String url = api.getDownloadUrl(fileId);
            if (url == null || url.length() == 0) {
                error(name + "：无法获取下载地址");
                return false;
            }
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("Referer", "https://yun.139.com/");
            try {
                int code = conn.getResponseCode();
                if (code != 200 && code != 206) {
                    error(name + "：下载失败（HTTP " + code + "）");
                    return false;
                }
                InputStream is = conn.getInputStream();
                long len = size >= 0 ? size : conn.getContentLength();
                boolean ok = conveyStreamToReceiver(adapter.ctx, receiver, is, name, len, date);
                if (!ok && noErrors())
                    error(name + "：复制失败");
                return ok;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.e(TAG, name, e);
            error(name + "：" + (e.getMessage() != null ? e.getMessage() : e.toString()));
            return false;
        }
    }

    private boolean copyFolder(IReceiver receiver, String folderFileId, String name) {
        Uri destDir = handleDirOnReceiver(commander, receiver, name);
        if (destDir == null)
            return false;
        try {
            IReceiver childReceiver = sameReceiverForDir(receiver, destDir);
            List<FileEntry> children = api.list(folderFileId);
            boolean allOk = true;
            for (FileEntry fe : children) {
                if (stop) break;
                boolean ok = fe.isDir
                        ? copyFolder(childReceiver, fe.fileId, fe.name)
                        : copyFile(childReceiver, fe.fileId, fe.name,
                            fe.size, fe.updatedAt > 0 ? new Date(fe.updatedAt) : null);
                allOk &= ok;
            }
            return allOk;
        } catch (IOException e) {
            Log.e(TAG, name, e);
            error(name + "：" + e.getMessage());
            return false;
        }
    }

    /**
     * Most IReceiver implementations are directory-scoped (constructed for one destination
     * folder), so descending into a subfolder means asking the destination adapter for a
     * fresh receiver pointed at destDir instead of reusing the parent's.
     */
    private IReceiver sameReceiverForDir(IReceiver parentReceiver, Uri destDir) {
        IReceiver r = to.getReceiver(destDir);
        return r != null ? r : parentReceiver;
    }
}
