package com.ghostsq.commander.yun139;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * An InputStream over a 139 Yun file's direct download URL whose skip() re-seeks via a
 * fresh HTTP Range request instead of reading-and-discarding.
 * <p>
 * This exists specifically because of how DataProxy.onRead() (part of the host app,
 * not this plugin - see FileCommander/DataProxy) implements a *forward* seek within an
 * already-open stream: it calls exactly InputStream.skip(d), and for whatever skip()
 * doesn't account for in its return value, reads (and discards) the remainder itself in
 * 1MB chunks. For a large seek in a large remote file - a common combination for video -
 * that fallback silently downloads and throws away potentially gigabytes of data before
 * the seek can complete, indistinguishable from "seeking doesn't work" to whoever is
 * waiting on it. A skip() that actually reissues the request at the new offset (139's
 * download links do honour Range - confirmed from a real device log, HTTP 206 with a
 * correct Content-Range) makes a forward seek anywhere in the file just as cheap as a
 * backward one, which DataProxy.onRead() already handles well (close + a fresh
 * getContent(uri, newOffset) call).
 */
class RemoteSeekableStream extends InputStream {
    private final static String TAG = "Yun139.Seek";

    private final Yun139Api api;
    private final String fileId;
    private InputStream in;
    private HttpURLConnection conn;
    private long pos;

    RemoteSeekableStream(Yun139Api api, String fileId, long startPos) throws IOException {
        this.api = api;
        this.fileId = fileId;
        openAt(startPos);
    }

    private void openAt(long offset) throws IOException {
        String url = api.getDownloadUrl(fileId);
        if (url == null || url.length() == 0)
            throw new IOException("无法获取下载地址");

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Referer", "https://yun.139.com/");
        if (offset > 0)
            c.setRequestProperty("Range", "bytes=" + offset + "-");

        long t0 = System.currentTimeMillis();
        int code = c.getResponseCode();
        Log.i(TAG, "fileId=" + fileId + " offset=" + offset + " -> HTTP " + code +
                " (" + (System.currentTimeMillis() - t0) + "ms) acceptRanges=" +
                c.getHeaderField("Accept-Ranges") + " contentRange=" + c.getHeaderField("Content-Range"));
        if (code != 200 && code != 206) {
            c.disconnect();
            throw new IOException("下载失败（HTTP " + code + "）");
        }

        InputStream newIn = c.getInputStream();
        if (offset > 0 && code == 200) {
            // Server accepted the request but ignored Range (sent the whole file from
            // byte 0) - fall back to a local skip. Best effort only: for a server that
            // behaves this way, forward seeks stay expensive no matter what this class
            // does, since there is no way to ask it for a specific offset at all.
            Log.w(TAG, "fileId=" + fileId + ": server ignored Range at offset " + offset + ", skipping locally");
            skipFully(newIn, offset);
        }

        // Swap in the new connection, closing whatever was open before.
        InputStream oldIn = this.in;
        HttpURLConnection oldConn = this.conn;
        this.in = newIn;
        this.conn = c;
        this.pos = offset;
        if (oldIn != null) {
            try {
                oldIn.close();
            } catch (IOException ignored) {
            }
        }
        if (oldConn != null)
            oldConn.disconnect();
    }

    @Override
    public int read() throws IOException {
        int b = in.read();
        if (b >= 0) pos++;
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = in.read(b, off, len);
        if (n > 0) pos += n;
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        if (n <= 0) return 0;
        long target = pos + n;
        Log.i(TAG, "fileId=" + fileId + ": skip(" + n + ") at pos=" + pos + " -> re-seeking to " + target);
        openAt(target);
        return n;
    }

    @Override
    public int available() throws IOException {
        return in.available();
    }

    @Override
    public void close() throws IOException {
        try {
            in.close();
        } finally {
            conn.disconnect();
        }
    }

    private static void skipFully(InputStream is, long n) throws IOException {
        long remaining = n;
        byte[] buf = null;
        while (remaining > 0) {
            long skipped = is.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            if (buf == null) buf = new byte[64 * 1024];
            int r = is.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (r < 0) break;
            remaining -= r;
        }
    }
}
