package com.ghostsq.commander.yun139;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.IDestination;
import com.ghostsq.commander.adapters.IReceiver;
import com.ghostsq.commander.yun139.Yun139Api.FileEntry;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Date;
import java.util.List;

/**
 * Receives files/folders being copied INTO a yun139 folder from some other adapter
 * (local storage, ftp, sftp, smb, webdav, another cloud, ...). Mirrors the pattern
 * documented directly in IReceiver.java's own commented-out "implementation template".
 * <p>
 * Because the 139 "personal cloud - new" upload API needs the file's total size and a
 * SHA-256 hash *before* the upload starts, an incoming stream of unknown length can't be
 * piped straight to the network: it is buffered to a temp file first (receive()), and the
 * actual upload happens once the temp file is complete (closeStream()).
 */
class Receiver implements IReceiver, IDestination {
    private final static String TAG = "Yun139.Receiver";

    private final Context ctx;
    private final Yun139Api api;
    private final String destFolderFileId;

    private File pendingTmp;
    private String pendingName;

    Receiver(Yun139Adapter adapter, Uri destDir) {
        this.ctx = adapter.ctx;
        this.api = adapter.api;
        this.destFolderFileId = Yun139Adapter.fileIdFromUri(destDir);
    }

    @Override
    public OutputStream receive(String file_name) {
        try {
            File dir = ctx.getCacheDir();
            File tmp = File.createTempFile("yun139up", ".tmp", dir);
            pendingTmp = tmp;
            pendingName = file_name;
            return new FileOutputStream(tmp);
        } catch (IOException e) {
            Log.e(TAG, file_name, e);
            return null;
        }
    }

    @Override
    public void closeStream(Closeable s) {
        try {
            if (s != null) s.close();
        } catch (IOException e) {
            Log.e(TAG, "close", e);
        }
        File tmp = pendingTmp;
        String name = pendingName;
        pendingTmp = null;
        pendingName = null;
        if (tmp == null) return;
        try {
            if (tmp.length() > 0 || name != null)
                api.uploadFile(destFolderFileId, tmp, name, null);
        } catch (IOException e) {
            Log.e(TAG, "upload " + name, e);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    @Override
    public Uri getItemURI(String name, boolean dir) {
        try {
            FileEntry fe = findChild(name);
            if (fe == null) return null;
            if (dir && !fe.isDir) return null;
            return fe.isDir ? Yun139Adapter.folderUri(fe.fileId) : Yun139Adapter.fileUri(fe);
        } catch (IOException e) {
            Log.e(TAG, name, e);
            return null;
        }
    }

    @Override
    public boolean isDirectory(Uri item_uri) {
        return item_uri != null && item_uri.isHierarchical() && "1".equals(item_uri.getQueryParameter("d"));
    }

    @Override
    public Uri makeDirectory(String new_dir_name) {
        try {
            String fid = api.mkdir(destFolderFileId, new_dir_name);
            return Yun139Adapter.folderUri(fid);
        } catch (IOException e) {
            Log.e(TAG, new_dir_name, e);
            return null;
        }
    }

    @Override
    public boolean delete(Uri item_uri) {
        try {
            api.delete(Yun139Adapter.fileIdFromUri(item_uri));
            return true;
        } catch (IOException e) {
            Log.e(TAG, String.valueOf(item_uri), e);
            return false;
        }
    }

    @Override
    public boolean setDate(Uri item_uri, Date timestamp) {
        return false; // the API has no "set modified time" call
    }

    @Override
    public boolean done() {
        return true;
    }

    @Override
    public CommanderAdapter.Item getItem(Uri item_uri) {
        try {
            String fid = Yun139Adapter.fileIdFromUri(item_uri);
            List<FileEntry> list = api.list(destFolderFileId);
            for (FileEntry fe : list) {
                if (fe.fileId != null && fe.fileId.equals(fid)) {
                    CommanderAdapter.Item it = new CommanderAdapter.Item();
                    it.name = fe.name;
                    it.dir = fe.isDir;
                    it.size = fe.size;
                    it.date = fe.updatedAt > 0 ? new Date(fe.updatedAt) : null;
                    it.uri = item_uri;
                    it.origin = fe;
                    return it;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "getItem", e);
        }
        return null;
    }

    private FileEntry findChild(String name) throws IOException {
        if (name == null) return null;
        List<FileEntry> list = api.list(destFolderFileId);
        for (FileEntry fe : list)
            if (name.equals(fe.name))
                return fe;
        return null;
    }
}
