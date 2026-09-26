package com.ghostsq.commander.yun139;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal REST client for the "personal cloud - new" (个人云新版) flavour of the
 * China Mobile / 139 Yun (Yun139, formerly "和彩云"/"移动云盘") service, i.e. the
 * flavour reachable at https://yun.139.com after logging in with a phone number.
 * <p>
 * This is a hand port of the relevant logic in OpenList's drivers/139 Go driver
 * (MetaPersonalNew code paths only) to plain Java, using only what ships with the
 * Android SDK (org.json, javax crypto/MessageDigest, HttpURLConnection) so this
 * plugin needs no extra runtime dependency.
 * <p>
 * Authentication here is token-only: the user supplies the raw "Authorization"
 * value that the yun.139.com web app sends as a request header (without the
 * "Basic " prefix). There is no username/password/SMS login flow - that flow
 * additionally requires RSA/AES request encryption private to the official
 * clients and is out of scope for this plugin.
 */
public class Yun139Api {
    private final static String TAG = "Yun139Api";

    final static String PREFS_NAME = "yun139";
    // Multi-account storage: KEY_ALIASES is a JSON array of alias names (display/iteration
    // order); each alias's own token/phone number live under authKey(alias)/acctKey(alias).
    private final static String KEY_ALIASES = "aliases";

    private static String authKey(String alias) {
        return "auth:" + alias;
    }

    private static String acctKey(String alias) {
        return "acct:" + alias;
    }

    private final static String ROUTE_URL = "https://user-njs.yun.139.com/user/route/qryRoutePolicy";
    private final static String REFRESH_URL = "https://aas.caiyun.feixin.10086.cn:443/tellin/authTokenRefresh.do";

    private final static long MB = 1024L * 1024L;
    private final static long GB = 1024L * MB;
    private final static long PART_SIZE_SMALL = 100L * MB;
    private final static long PART_SIZE_LARGE = 512L * MB;
    private final static long BIG_FILE_THRESHOLD = 30L * GB;
    private final static int MAX_PARTS_PER_REQUEST = 100;

    private final static long REFRESH_MARGIN_MS = 15L * 24 * 3600 * 1000; // 15 days, matches OpenList

    private final Context appCtx;
    /** Which saved account this instance represents; never null once constructed. */
    public final String alias;
    private String authorization; // raw base64, WITHOUT "Basic " prefix
    private String account;       // phone number, decoded from the token
    private String personalCloudHost;

    public Yun139Api(Context appCtx, String alias) {
        this.appCtx = appCtx;
        this.alias = alias;
        SharedPreferences sp = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        authorization = sp.getString(authKey(alias), null);
        account = sp.getString(acctKey(alias), null);
    }

    /** All saved account aliases, in the order they were added. */
    public static String[] listAliases(Context appCtx) {
        SharedPreferences sp = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return aliasesFromJson(sp.getString(KEY_ALIASES, null));
    }

    public static boolean hasAlias(Context appCtx, String alias) {
        if (alias == null || alias.length() == 0) return false;
        SharedPreferences sp = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String t = sp.getString(authKey(alias), null);
        return t != null && t.trim().length() > 0;
    }

    public static void saveAuthorization(Context appCtx, String alias, String rawToken) {
        SharedPreferences.Editor ed = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        if (rawToken != null && rawToken.trim().length() > 0) {
            ed.putString(authKey(alias), rawToken.trim());
        } else {
            ed.remove(authKey(alias));
        }
        ed.remove(acctKey(alias)); // will be re-derived from the (new) token on next use
        List<String> aliases = new ArrayList<String>(Arrays.asList(listAliases(appCtx)));
        if (!aliases.contains(alias)) {
            aliases.add(alias);
            ed.putString(KEY_ALIASES, aliasesToJson(aliases.toArray(new String[0])));
        }
        ed.apply();
    }

    public static String loadAuthorization(Context appCtx, String alias) {
        return appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(authKey(alias), null);
    }

    /** Forgets one saved account entirely: its token, phone number, and slot in the alias list. */
    public static void deleteAlias(Context appCtx, String alias) {
        SharedPreferences.Editor ed = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        ed.remove(authKey(alias));
        ed.remove(acctKey(alias));
        List<String> aliases = new ArrayList<String>(Arrays.asList(listAliases(appCtx)));
        aliases.remove(alias);
        ed.putString(KEY_ALIASES, aliasesToJson(aliases.toArray(new String[0])));
        ed.apply();
    }

    private static String aliasesToJson(String[] aliases) {
        JSONArray arr = new JSONArray();
        for (String a : aliases) arr.put(a);
        return arr.toString();
    }

    private static String[] aliasesFromJson(String json) {
        if (json == null || json.length() == 0) return new String[0];
        try {
            JSONArray arr = new JSONArray(json);
            String[] out = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++)
                out[i] = arr.optString(i);
            return out;
        } catch (JSONException e) {
            return new String[0];
        }
    }

    public boolean hasToken() {
        return authorization != null && authorization.trim().length() > 0;
    }

    public String getAccount() {
        return account;
    }

    /** Logs in (if needed), refreshes the token (if needed) and resolves the personal cloud host. */
    public synchronized void ensureReady() throws IOException {
        if (!hasToken())
            throw new AuthException("尚未配置 139 云盘授权令牌");
        refreshTokenIfNeeded();
        if (personalCloudHost == null || personalCloudHost.length() == 0)
            queryRoutePolicy();
    }

    /** Runs ensureReady() and returns a masked phone number on success, for the settings screen. */
    public String testConnection() throws IOException {
        ensureReady();
        if (account != null && account.length() >= 7)
            return account.substring(0, 3) + "****" + account.substring(account.length() - 4);
        return account != null ? account : "";
    }

    // ------------------------------------------------------------------
    // Token handling
    // ------------------------------------------------------------------

    private void persist() {
        SharedPreferences.Editor ed = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        ed.putString(authKey(alias), authorization);
        ed.putString(acctKey(alias), account);
        ed.apply();
    }

    private void refreshTokenIfNeeded() throws IOException {
        byte[] decoded;
        try {
            // matches Go's base64.StdEncoding: standard alphabet, with padding
            decoded = Base64.decode(authorization.trim(), Base64.DEFAULT);
        } catch (Exception e) {
            throw new AuthException("授权令牌不是合法的 Base64 文本，请重新粘贴");
        }
        String decodedStr;
        try {
            decodedStr = new String(decoded, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AuthException("授权令牌解码失败");
        }
        String[] splits = decodedStr.split(":");
        if (splits.length < 3)
            throw new AuthException("授权令牌格式不正确（应形如 appId:手机号:token|...）");
        account = splits[1];
        String[] strs = splits[2].split("\\|");
        if (strs.length < 4)
            throw new AuthException("授权令牌格式不正确（token 段缺少过期时间）");
        long expiration;
        try {
            expiration = Long.parseLong(strs[3]);
        } catch (NumberFormatException e) {
            throw new AuthException("授权令牌格式不正确（过期时间不是数字）");
        }
        long remain = expiration - System.currentTimeMillis();
        if (remain > REFRESH_MARGIN_MS) {
            persist();
            return;
        }

        // Close to- or past expiry: ask the server for a fresh token via the same plain
        // XML endpoint the official web/app clients use. If the token is too old for the
        // server to refresh it will simply reject this call, and that error message (not
        // a locally-guessed one) is what gets surfaced to the user below.
        String reqBody = "<root><token>" + xmlEscape(splits[2]) + "</token><account>" +
                xmlEscape(splits[1]) + "</account><clienttype>656</clienttype></root>";
        String respXml = httpPostXml(REFRESH_URL, reqBody);
        String ret = xmlTag(respXml, "return");
        if (!"0".equals(ret)) {
            String desc = xmlTag(respXml, "desc");
            throw new AuthException("令牌刷新失败：" + (desc != null && desc.length() > 0 ? desc : ("code=" + ret)));
        }
        String newToken = xmlTag(respXml, "token");
        if (newToken == null || newToken.length() == 0)
            throw new AuthException("令牌刷新失败：服务器未返回新令牌");
        try {
            authorization = Base64.encodeToString((splits[0] + ":" + splits[1] + ":" + newToken).getBytes("UTF-8"), Base64.NO_WRAP);
        } catch (UnsupportedEncodingException e) {
            throw new AuthException("令牌刷新失败：编码错误");
        }
        persist();
    }

    private void queryRoutePolicy() throws IOException {
        try {
            JSONObject userInfo = new JSONObject();
            userInfo.put("userType", 1);
            userInfo.put("accountType", 1);
            userInfo.put("accountName", account);
            JSONObject body = new JSONObject();
            body.put("userInfo", userInfo);
            body.put("modAddrType", 1);

            JSONObject resp = signAndSend(ROUTE_URL, body, routeHeaders());
            JSONObject data = resp.getJSONObject("data");
            JSONArray list = data.getJSONArray("routePolicyList");
            String host = null;
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.getJSONObject(i);
                Log.i(TAG, "route[" + i + "] modName=" + item.optString("modName") + " httpsUrl=" + item.optString("httpsUrl"));
                if ("personal".equals(item.optString("modName"))) {
                    host = item.optString("httpsUrl");
                    break;
                }
            }
            if (host == null || host.length() == 0)
                throw new Yun139Exception("未能从路由信息中取得个人云地址");
            personalCloudHost = host;
            Log.i(TAG, "personalCloudHost = " + personalCloudHost);
        } catch (JSONException e) {
            throw new Yun139Exception("解析路由信息失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // High level "personal cloud - new" API
    // ------------------------------------------------------------------

    private final static int LIST_PAGE_SIZE = 100;

    public List<FileEntry> list(String parentFileId) throws IOException {
        Log.i(TAG, "list(parentFileId=" + parentFileId + ") host=" + personalCloudHost);
        List<FileEntry> result = new ArrayList<FileEntry>();
        Set<String> seenIds = new HashSet<String>();
        String cursor = "";
        int guard = 0;
        while (guard++ < 500) { // outer sanity bound; the two checks below are what
                                 // actually stop the failure mode this guards against
            try {
                JSONArray thumbs = new JSONArray();
                thumbs.put("Small");
                thumbs.put("Large");
                JSONObject pageInfo = new JSONObject();
                pageInfo.put("pageCursor", cursor);
                pageInfo.put("pageSize", LIST_PAGE_SIZE);
                JSONObject body = new JSONObject();
                body.put("imageThumbnailStyleList", thumbs);
                body.put("orderBy", "updated_at");
                body.put("orderDirection", "DESC");
                body.put("pageInfo", pageInfo);
                body.put("parentFileId", parentFileId);

                JSONObject resp = personalPost("/file/list", body);
                JSONObject data = resp.getJSONObject("data");
                JSONArray items = data.optJSONArray("items");
                int itemCount = items != null ? items.length() : 0;
                int newCount = 0;
                if (items != null) {
                    for (int i = 0; i < itemCount; i++) {
                        JSONObject it = items.getJSONObject(i);
                        FileEntry fe = new FileEntry();
                        fe.fileId = it.optString("fileId");
                        fe.name = it.optString("name");
                        fe.size = it.optLong("size", 0);
                        fe.isDir = "folder".equals(it.optString("type"));
                        fe.createdAt = parsePersonalTime(it.optString("createdAt", null));
                        fe.updatedAt = parsePersonalTime(it.optString("updatedAt", null));
                        if (fe.fileId == null || fe.fileId.length() == 0 || seenIds.add(fe.fileId)) {
                            result.add(fe);
                            newCount++;
                        }
                    }
                }
                cursor = data.optString("nextPageCursor", "");
                Log.i(TAG, "list page " + guard + ": " + itemCount + " items (" + newCount + " new), total=" +
                        result.size() + ", nextPageCursor=" + (cursor.length() == 0 ? "(empty)" : "present"));

                // A page smaller than what we asked for means the server has nothing
                // more to give, no matter what nextPageCursor says: this account/folder
                // has been observed to keep returning a non-empty cursor forever, that
                // on every following "page" re-serves a handful of items that do not
                // actually belong to this folder at all (verified against the real
                // item count shown in the official app) instead of ever properly
                // signalling the end of the listing. The itemCount check below is what
                // actually stops that; newCount==0 and the empty-cursor check are just
                // extra safety nets for whatever this server does next.
                if (itemCount < LIST_PAGE_SIZE || cursor.length() == 0 || newCount == 0)
                    break;
            } catch (JSONException e) {
                throw new Yun139Exception("解析文件列表失败：" + e.getMessage());
            }
        }
        return result;
    }

    public String getDownloadUrl(String fileId) throws IOException {
        try {
            JSONObject body = new JSONObject();
            body.put("fileId", fileId);
            JSONObject resp = personalPost("/file/getDownloadUrl", body);
            JSONObject data = resp.getJSONObject("data");
            if (data.optBoolean("cdnSwitch", false)) {
                String cdn = data.optString("cdnUrl", "");
                if (cdn.length() > 0)
                    return cdn;
            }
            return data.optString("url", "");
        } catch (JSONException e) {
            throw new Yun139Exception("解析下载地址失败：" + e.getMessage());
        }
    }

    public String mkdir(String parentFileId, String name) throws IOException {
        try {
            JSONObject body = new JSONObject();
            body.put("parentFileId", parentFileId);
            body.put("name", name);
            body.put("description", "");
            body.put("type", "folder");
            body.put("fileRenameMode", "force_rename");
            JSONObject resp = personalPost("/file/create", body);
            return resp.getJSONObject("data").optString("fileId");
        } catch (JSONException e) {
            throw new Yun139Exception("解析新建文件夹结果失败：" + e.getMessage());
        }
    }

    public void rename(String fileId, String newName) throws IOException {
        try {
            JSONObject body = new JSONObject();
            body.put("fileId", fileId);
            body.put("name", newName);
            body.put("description", "");
            personalPost("/file/update", body);
        } catch (JSONException e) {
            throw new Yun139Exception(e.getMessage());
        }
    }

    public void move(String fileId, String toParentFileId) throws IOException {
        batchOp("/file/batchMove", fileId, toParentFileId);
    }

    public void copy(String fileId, String toParentFileId) throws IOException {
        batchOp("/file/batchCopy", fileId, toParentFileId);
    }

    private void batchOp(String pathname, String fileId, String toParentFileId) throws IOException {
        try {
            JSONArray ids = new JSONArray();
            ids.put(fileId);
            JSONObject body = new JSONObject();
            body.put("fileIds", ids);
            body.put("toParentFileId", toParentFileId);
            personalPost(pathname, body);
        } catch (JSONException e) {
            throw new Yun139Exception(e.getMessage());
        }
    }

    public void delete(String fileId) throws IOException {
        try {
            JSONArray ids = new JSONArray();
            ids.put(fileId);
            JSONObject body = new JSONObject();
            body.put("fileIds", ids);
            personalPost("/recyclebin/batchTrash", body);
        } catch (JSONException e) {
            throw new Yun139Exception(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Upload (chunked, with upfront SHA-256 for server-side de-dup)
    // ------------------------------------------------------------------

    public interface ProgressListener {
        /** Called after each chunk write with the number of bytes just written. */
        void onBytes(long n);

        /** Polled periodically; return true to abort the upload. */
        boolean isCancelled();
    }

    public static class FileEntry {
        public String fileId;
        public String name;
        public long size;
        public boolean isDir;
        public long createdAt; // epoch millis, 0 if unknown
        public long updatedAt; // epoch millis, 0 if unknown
    }

    private static class PartSpec {
        int partNumber;
        long offset;
        long size;
    }

    private static class PartTarget {
        int partNumber;
        String uploadUrl;
    }

    public FileEntry uploadFile(String parentFileId, File localFile, String displayName, ProgressListener pl) throws IOException {
        long size = localFile.length();
        String hash = sha256Hex(localFile);

        long partSize = size > BIG_FILE_THRESHOLD ? PART_SIZE_LARGE : PART_SIZE_SMALL;
        int partCount = (int) Math.max(1, (size + partSize - 1) / partSize);
        List<PartSpec> allParts = new ArrayList<PartSpec>(partCount);
        for (int i = 0; i < partCount; i++) {
            long start = (long) i * partSize;
            long thisSize = Math.min(size - start, partSize);
            PartSpec p = new PartSpec();
            p.partNumber = i + 1;
            p.offset = start;
            p.size = thisSize;
            allParts.add(p);
        }

        try {
            JSONArray firstBatch = new JSONArray();
            int firstCount = Math.min(allParts.size(), MAX_PARTS_PER_REQUEST);
            for (int i = 0; i < firstCount; i++)
                firstBatch.put(partSpecJson(allParts.get(i)));

            JSONObject body = new JSONObject();
            body.put("contentHash", hash);
            body.put("contentHashAlgorithm", "SHA256");
            body.put("contentType", "application/octet-stream");
            body.put("parallelUpload", false);
            body.put("partInfos", firstBatch);
            body.put("size", size);
            body.put("parentFileId", parentFileId);
            body.put("name", displayName);
            body.put("type", "file");
            body.put("fileRenameMode", "auto_rename");

            JSONObject resp = personalPost("/file/create", body);
            JSONObject data = resp.getJSONObject("data");

            FileEntry fe = new FileEntry();
            fe.fileId = data.optString("fileId");
            fe.name = data.optString("fileName", displayName);
            fe.size = size;
            fe.isDir = false;

            if (data.optBoolean("exist", false)) {
                if (pl != null) pl.onBytes(size); // server already has this content, report as fully done
                return fe; // rapid/dedup upload, no bytes to send
            }

            JSONArray partInfos = data.optJSONArray("partInfos");
            if (partInfos != null && partInfos.length() > 0) {
                String uploadId = data.optString("uploadId");
                uploadParts(allParts, parsePartTargets(partInfos), localFile, pl);

                int next = MAX_PARTS_PER_REQUEST;
                while (next < allParts.size()) {
                    if (pl != null && pl.isCancelled())
                        throw new Yun139Exception("已取消");
                    int end = Math.min(next + MAX_PARTS_PER_REQUEST, allParts.size());
                    List<PartSpec> batch = allParts.subList(next, end);
                    JSONArray batchJson = new JSONArray();
                    for (PartSpec p : batch)
                        batchJson.put(partSpecJson(p));
                    JSONObject moreBody = new JSONObject();
                    moreBody.put("fileId", fe.fileId);
                    moreBody.put("uploadId", uploadId);
                    moreBody.put("partInfos", batchJson);
                    JSONObject moreResp = personalPost("/file/getUploadUrl", moreBody);
                    JSONArray moreTargets = moreResp.getJSONObject("data").optJSONArray("partInfos");
                    uploadParts(allParts, parsePartTargets(moreTargets), localFile, pl);
                    next = end;
                }

                JSONObject completeBody = new JSONObject();
                completeBody.put("contentHash", hash);
                completeBody.put("contentHashAlgorithm", "SHA256");
                completeBody.put("fileId", fe.fileId);
                completeBody.put("uploadId", uploadId);
                personalPost("/file/complete", completeBody);
            }
            return fe;
        } catch (JSONException e) {
            throw new Yun139Exception("上传请求构造/解析失败：" + e.getMessage());
        }
    }

    private JSONObject partSpecJson(PartSpec p) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("partNumber", p.partNumber);
        o.put("partSize", p.size);
        JSONObject hc = new JSONObject();
        hc.put("partOffset", p.offset);
        o.put("parallelHashCtx", hc);
        return o;
    }

    private List<PartTarget> parsePartTargets(JSONArray arr) throws JSONException {
        List<PartTarget> list = new ArrayList<PartTarget>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            PartTarget t = new PartTarget();
            t.partNumber = o.optInt("partNumber");
            t.uploadUrl = o.optString("uploadUrl");
            list.add(t);
        }
        return list;
    }

    private void uploadParts(List<PartSpec> allParts, List<PartTarget> targets, File localFile, ProgressListener pl) throws IOException {
        List<PartTarget> sorted = new ArrayList<PartTarget>(targets);
        Collections.sort(sorted, new Comparator<PartTarget>() {
            public int compare(PartTarget a, PartTarget b) {
                return a.partNumber - b.partNumber;
            }
        });
        for (PartTarget t : sorted) {
            if (pl != null && pl.isCancelled())
                throw new Yun139Exception("已取消");
            int idx = t.partNumber - 1;
            if (idx < 0 || idx >= allParts.size())
                throw new Yun139Exception("服务器返回的分片编号异常：" + t.partNumber);
            PartSpec spec = allParts.get(idx);
            IOException lastErr = null;
            boolean ok = false;
            for (int attempt = 0; attempt < 3 && !ok; attempt++) {
                if (attempt > 0) {
                    try {
                        Thread.sleep(800L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new Yun139Exception("已中断");
                    }
                }
                try {
                    uploadPart(t.uploadUrl, localFile, spec.offset, spec.size, pl);
                    ok = true;
                } catch (IOException e) {
                    lastErr = e;
                    Log.w(TAG, "part " + t.partNumber + " attempt " + attempt + " failed", e);
                }
            }
            if (!ok)
                throw lastErr != null ? lastErr : new Yun139Exception("分片上传失败");
        }
    }

    private void uploadPart(String uploadUrl, File file, long offset, long length, ProgressListener pl) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(uploadUrl).openConnection();
        RandomAccessFile raf = null;
        try {
            conn.setRequestMethod("PUT");
            conn.setDoOutput(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(120000);
            conn.setFixedLengthStreamingMode(length);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setRequestProperty("Origin", "https://yun.139.com");
            conn.setRequestProperty("Referer", "https://yun.139.com/");

            raf = new RandomAccessFile(file, "r");
            raf.seek(offset);
            OutputStream os = conn.getOutputStream();
            byte[] buf = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int toRead = (int) Math.min(buf.length, remaining);
                int n = raf.read(buf, 0, toRead);
                if (n < 0) throw new IOException("读取本地文件时提前结束");
                os.write(buf, 0, n);
                remaining -= n;
                if (pl != null) pl.onBytes(n);
            }
            os.flush();

            int code = conn.getResponseCode();
            if (code != 200) {
                String err = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
                throw new Yun139Exception("分片上传失败（HTTP " + code + "）" + (err.length() > 0 ? "：" + truncate(err, 200) : ""));
            }
        } finally {
            if (raf != null) try { raf.close(); } catch (IOException ignored) { }
            conn.disconnect();
        }
    }

    public static String sha256Hex(File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            FileInputStream fis = new FileInputStream(f);
            try {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = fis.read(buf)) > 0)
                    md.update(buf, 0, n);
            } finally {
                fis.close();
            }
            return toHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    // ------------------------------------------------------------------
    // Low level HTTP + signing
    // ------------------------------------------------------------------

    private JSONObject personalPost(String pathname, JSONObject body) throws IOException {
        ensureReady();
        return signAndSend(personalCloudHost + pathname, body, personalHeaders());
    }

    private JSONObject signAndSend(String url, JSONObject body, Map<String, String> headers) throws IOException {
        String bodyStr = body != null ? body.toString() : "{}";
        String randStr = randomString(16);
        String ts = nowTsShanghai();
        String sign = calSign(bodyStr, ts, randStr);

        long t0 = System.currentTimeMillis();
        Log.i(TAG, "-> POST " + url);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            for (Map.Entry<String, String> e : headers.entrySet())
                conn.setRequestProperty(e.getKey(), e.getValue());
            conn.setRequestProperty("Mcloud-Sign", ts + "," + randStr + "," + sign);

            byte[] bytes = bodyStr.getBytes("UTF-8");
            conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
            OutputStream os = conn.getOutputStream();
            os.write(bytes);
            os.close();

            int code = conn.getResponseCode();
            String respStr = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            Log.i(TAG, "<- " + code + " " + url + " (" + (System.currentTimeMillis() - t0) + "ms) " + truncate(respStr, 300));

            JSONObject resp;
            try {
                resp = new JSONObject(respStr);
            } catch (JSONException e) {
                throw new Yun139Exception("服务器返回了无法解析的数据（HTTP " + code + "）：" + truncate(respStr, 200));
            }
            boolean success = resp.optBoolean("success", false);
            if (!success) {
                String msg = resp.optString("message", null);
                if (msg == null || msg.length() == 0)
                    msg = "请求失败（code=" + resp.optString("code", "?") + ", HTTP " + code + "）";
                throw new Yun139Exception(msg);
            }
            return resp;
        } catch (IOException e) {
            Log.e(TAG, "xx " + url + " (" + (System.currentTimeMillis() - t0) + "ms): " + e, e);
            throw e;
        } finally {
            conn.disconnect();
        }
    }

    private Map<String, String> personalHeaders() {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Accept", "application/json, text/plain, */*");
        h.put("Authorization", "Basic " + authorization);
        h.put("Caller", "web");
        h.put("Cms-Device", "default");
        h.put("Mcloud-Channel", "1000101");
        h.put("Mcloud-Client", "10701");
        h.put("Mcloud-Route", "001");
        h.put("Mcloud-Version", "7.14.0");
        h.put("x-DeviceInfo", "||9|7.14.0|chrome|120.0.0.0|||windows 10||zh-CN|||");
        h.put("x-huawei-channelSrc", "10000034");
        h.put("x-inner-ntwk", "2");
        h.put("x-m4c-caller", "PC");
        h.put("x-m4c-src", "10002");
        h.put("x-SvcType", "1");
        h.put("X-Yun-Api-Version", "v1");
        h.put("X-Yun-App-Channel", "10000034");
        h.put("X-Yun-Channel-Source", "10000034");
        h.put("X-Yun-Client-Info", "||9|7.14.0|chrome|120.0.0.0|||windows 10||zh-CN|||dW5kZWZpbmVk||");
        h.put("X-Yun-Module-Type", "100");
        h.put("X-Yun-Svc-Type", "1");
        return h;
    }

    private Map<String, String> routeHeaders() {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Accept", "application/json, text/plain, */*");
        h.put("CMS-DEVICE", "default");
        h.put("Authorization", "Basic " + authorization);
        h.put("mcloud-channel", "1000101");
        h.put("mcloud-client", "10701");
        h.put("mcloud-version", "7.14.0");
        h.put("Origin", "https://yun.139.com");
        h.put("Referer", "https://yun.139.com/w/");
        h.put("x-DeviceInfo", "||9|7.14.0|chrome|120.0.0.0|||windows 10||zh-CN|||");
        h.put("x-huawei-channelSrc", "10000034");
        h.put("x-inner-ntwk", "2");
        h.put("x-m4c-caller", "PC");
        h.put("x-m4c-src", "10002");
        h.put("x-SvcType", "1");
        h.put("Inner-Hcy-Router-Https", "1");
        return h;
    }

    /**
     * Faithful re-implementation of the 139/Yun139 "calSign" request-signing scheme
     * (see OpenList drivers/139/util.go calSign): percent-encode the JSON body the
     * same way JavaScript's encodeURIComponent would, sort the resulting characters,
     * base64 the sorted string, then combine two MD5 digests and upper-case the result.
     */
    static String calSign(String body, String ts, String randStr) {
        try {
            String enc = encodeURIComponent(body);
            char[] chars = enc.toCharArray();
            java.util.Arrays.sort(chars);
            String sorted = new String(chars);
            String b64 = Base64.encodeToString(sorted.getBytes("UTF-8"), Base64.NO_WRAP);
            String res = md5Hex(b64) + md5Hex(ts + ":" + randStr);
            return md5Hex(res).toUpperCase(Locale.US);
        } catch (Exception e) {
            Log.e(TAG, "calSign", e);
            return "";
        }
    }

    private final static String URI_UNRESERVED =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()";
    private final static char[] HEX_UPPER = "0123456789ABCDEF".toCharArray();

    /** Mirrors JavaScript's encodeURIComponent exactly (unlike java.net.URLEncoder). */
    static String encodeURIComponent(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c < 128 && URI_UNRESERVED.indexOf(c) >= 0) {
                sb.append(c);
                i++;
            } else {
                int cp = s.codePointAt(i);
                int charCount = Character.charCount(cp);
                byte[] bytes = new String(Character.toChars(cp)).getBytes(Charset.forName("UTF-8"));
                for (byte b : bytes) {
                    sb.append('%');
                    sb.append(HEX_UPPER[(b >> 4) & 0xF]);
                    sb.append(HEX_UPPER[b & 0xF]);
                }
                i += charCount;
            }
        }
        return sb.toString();
    }

    private static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return toHex(md.digest(s.getBytes("UTF-8")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String toHex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private final static char[] RAND_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private static String randomString(int len) {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++)
            sb.append(RAND_CHARS[r.nextInt(RAND_CHARS.length)]);
        return sb.toString();
    }

    private static String nowTsShanghai() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return sdf.format(new java.util.Date());
    }

    // ------------------------------------------------------------------
    // Small helpers: XML (token refresh), time parsing, HTTP body reading
    // ------------------------------------------------------------------

    private static String httpPostXml(String url, String xmlBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Content-Type", "text/xml;charset=UTF-8");
            byte[] bytes = xmlBody.getBytes("UTF-8");
            conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
            OutputStream os = conn.getOutputStream();
            os.write(bytes);
            os.close();
            int code = conn.getResponseCode();
            return readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        } finally {
            conn.disconnect();
        }
    }

    private static String xmlTag(String xml, String tag) {
        if (xml == null) return null;
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new StringReader(xml));
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && tag.equals(p.getName()))
                    return p.nextText();
                ev = p.next();
            }
        } catch (Exception e) {
            Log.e(TAG, "xmlTag(" + tag + ")", e);
        }
        return null;
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private final static Pattern TIME_PATTERN = Pattern.compile(
            "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d+))?(Z|[+-]\\d{2}:\\d{2})?$");

    /** Parses the "2006-01-02T15:04:05.999-07:00"-style timestamps the API returns, to epoch millis. */
    static long parsePersonalTime(String s) {
        if (s == null || s.length() == 0) return 0;
        try {
            Matcher m = TIME_PATTERN.matcher(s);
            if (!m.matches()) return 0;
            int year = Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2));
            int day = Integer.parseInt(m.group(3));
            int hour = Integer.parseInt(m.group(4));
            int min = Integer.parseInt(m.group(5));
            int sec = Integer.parseInt(m.group(6));
            int millis = 0;
            String frac = m.group(7);
            if (frac != null && frac.length() > 0) {
                String f3 = (frac + "000").substring(0, 3);
                millis = Integer.parseInt(f3);
            }
            String off = m.group(8);
            int offMinutes = 0;
            if (off != null && !"Z".equals(off)) {
                int sign = off.charAt(0) == '-' ? -1 : 1;
                int offH = Integer.parseInt(off.substring(1, 3));
                int offM = Integer.parseInt(off.substring(4, 6));
                offMinutes = sign * (offH * 60 + offM);
            }
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            cal.clear();
            cal.set(year, month - 1, day, hour, min, sec);
            cal.set(Calendar.MILLISECOND, millis);
            return cal.getTimeInMillis() - offMinutes * 60000L;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0)
                bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } finally {
            try { is.close(); } catch (IOException ignored) { }
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "\u2026" : s;
    }

    /** Thrown for any Yun139-specific failure; message is meant to be shown to the user as-is. */
    public static class Yun139Exception extends IOException {
        public Yun139Exception(String msg) {
            super(msg);
        }
    }

    /**
     * Thrown specifically when the stored token itself is unusable (missing, not valid
     * Base64/UTF-8, wrong shape, or the refresh endpoint rejected it) as opposed to an
     * ordinary network/API failure - see refreshTokenIfNeeded() and ensureReady(). Callers
     * that can offer "please re-paste the token for this account" (Yun139Adapter, via
     * ListEngine) catch this specifically to trigger that instead of a plain error.
     */
    public static class AuthException extends Yun139Exception {
        public AuthException(String msg) {
            super(msg);
        }
    }
}
