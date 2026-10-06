package com.ghostsq.commander.yun139;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseBooleanArray;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.CommanderAdapterBase;
import com.ghostsq.commander.adapters.Engines;
import com.ghostsq.commander.adapters.IReceiver;
import com.ghostsq.commander.utils.Credentials;
import com.ghostsq.commander.utils.Utils;
import com.ghostsq.commander.yun139.Yun139Api.FileEntry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Stack;

/**
 * GhostCommander plugin adapter for China Mobile's 139 Yun (个人云 - 云盘, yun.139.com),
 * "personal cloud - new" flavour, token-based login only, any number of accounts.
 * <p>
 * Structurally this mirrors the webdav (com.ghostsq.commander.https) and gdrive
 * (com.ghostsq.commander.gdrive) plugins that ship with GhostCommander: a separate
 * APK sharing android:sharedUserId/android:process with the host app, with an entry
 * point class (yun139.java) that the host discovers and loads via CA.java's plugin
 * mechanism - see that class for the exact package/class naming contract.
 * <p>
 * Uri layout. The account is the Uri <i>authority</i> (the way FTPAdapter uses it for
 * "which server"), everything after it is location inside that account:
 * <pre>
 *   yun139:                           the account list - the top level of this plugin
 *   yun139://&lt;alias&gt;                  the drive root of the account saved as &lt;alias&gt;
 *   yun139://&lt;alias&gt;/&lt;fileId&gt;?d=1     a folder of that account   (see folderUri())
 *   yun139://&lt;alias&gt;/&lt;name&gt;?id=..   a file of that account     (see fileUri())
 * </pre>
 * &lt;alias&gt; is the name the user typed when adding the account (Prefs), used as the
 * account's identity.
 * <p>
 * Nothing here caches "the current account" in a field that background work could later
 * read: every operation resolves the account from the Uri it was issued for, at the moment
 * it is issued, and hands that specific Yun139Api to the Engine/Receiver. See
 * Yun139EngineBase for why.
 */
public class Yun139Adapter extends CommanderAdapterBase {
    private final static String TAG = "Yun139Adapter";
    final static String SCHEME = "yun139";
    private final static String ROOT_ID = "/";
    private final static String HOME_URI = "home:";

    private final static String MSG_AT_ACCOUNT_LIST =
            "这里是账号列表，请先进入某个账号再操作。账号的添加/移除请到“设置”里进行。";

    private Uri uri = Uri.parse(SCHEME + ":");
    private Item[] items = new Item[0];

    /**
     * Per account (keyed by alias): ids of the ancestor folders of the folder currently shown,
     * used to resolve ".." (see openItem). Kept per account so that leaving an account for the
     * account list and coming back later does not mix up two accounts' breadcrumbs.
     */
    private final Map<String, Stack<String>> parentStacks = new HashMap<String, Stack<String>>();

    public Yun139Adapter(Context ctx_) {
        super(ctx_);
    }

    private Stack<String> parentStackFor(String alias) {
        Stack<String> s = parentStacks.get(alias);
        if (s == null) {
            s = new Stack<String>();
            parentStacks.put(alias, s);
        }
        return s;
    }

    // ------------------------------------------------------------------
    // Uri <-> account / fileId helpers (package-visible: shared with the Engine classes)
    // ------------------------------------------------------------------

    /**
     * @return the alias of the account this Uri points into, or null if it points at no account
     *         in particular, i.e. at the account list.
     */
    static String accountIdFromUri(Uri u) {
        // A bare "yun139:" (which is what HomeAdapter hands over the first time the user
        // enters the plugin - it builds new items with Uri.parse(scheme + ":"), nothing
        // after the colon) has no scheme-specific part at all, which makes Android
        // classify it as an *opaque* Uri; every hierarchical-only accessor (getAuthority,
        // getQueryParameter, getPathSegments, ...) throws UnsupportedOperationException on
        // those, so this has to be checked before any of them is touched.
        if (u == null || !u.isHierarchical())
            return null;
        // getAuthority() is the DECODED form, i.e. the alias exactly as the user typed it -
        // see accountBuilder() for how it gets in there.
        String a = u.getAuthority();
        return (a == null || a.length() == 0) ? null : a;
    }

    static Uri accountListUri() {
        return Uri.parse(SCHEME + ":");
    }

    /**
     * Starts a Uri inside the given account. The alias is user-typed free text, so it is
     * percent-encoded HERE, by the same encodeURIComponent the request signing already relies
     * on, and stored with encodedAuthority() rather than authority():
     * <ul>
     * <li>An authority has an inner structure - [userinfo@]host[:port] - and generic host code
     *     does read it: Favorite.screenPwd() takes getUserInfo() apart on every navigation, and
     *     Panels compares getHost() of the two panels' locations. Fully escaped, the authority
     *     contains only unreserved characters and %XX, so whatever the alias contains ('@', ':',
     *     '[', ...) that code never sees any structure in it, and this does not depend on how a
     *     particular Android version's authority(String) treats such characters.</li>
     * <li>'/', '?', '#' and whitespace in an alias can't corrupt the Uri, and the Uri survives
     *     being stringified and re-parsed (history, favourites) unchanged.</li>
     * </ul>
     * Passing already-escaped text to the plain authority() setter would escape the '%' signs a
     * second time, hence encodedAuthority(). Reading it back needs no work: getAuthority() is
     * documented to return the decoded form (see accountIdFromUri()).
     */
    private static Uri.Builder accountBuilder(String alias) {
        return new Uri.Builder().scheme(SCHEME).encodedAuthority(Yun139Api.encodeURIComponent(alias));
    }

    static String fileIdFromUri(Uri u) {
        // See accountIdFromUri() for why the opaque case has to be handled first.
        if (u == null || !u.isHierarchical())
            return ROOT_ID;
        // File Uris (see fileUri()) carry the fileId as a query parameter so that the
        // path itself can be a real, clean file name - FileCommander.Open() derives
        // BOTH the display name AND the mime-type-by-extension straight from
        // uri.getPath() with no hook for an adapter to override that, so the path has
        // to already look like a normal "name.ext", not an opaque id.
        String qid = u.getQueryParameter("id");
        if (qid != null && qid.length() > 0)
            return qid;
        String p = u.getPath();
        if (p == null || p.length() == 0 || p.equals("/"))
            return ROOT_ID;
        // getPath() already returns the decoded form; decoding again would be wrong
        // for any fileId that happens to contain a literal '%'.
        return p.substring(1);
    }

    /** A folder of account {@code alias}; ROOT_ID is that account's own drive root. */
    static Uri folderUri(String alias, String fileId) {
        Uri.Builder b = accountBuilder(alias);
        if (fileId != null && fileId.length() > 0 && !fileId.equals(ROOT_ID))
            b.appendPath(fileId);
        b.appendQueryParameter("d", "1");
        return b.build();
    }

    /**
     * Builds a file Uri whose *path* is the real file name (see fileIdFromUri() for
     * why), with the fileId/size/mtime needed to act on it and to rebuild a usable
     * Item with no listing cache and no network round-trip (see getItem(Uri) /
     * itemFromUri()) carried as query parameters instead.
     */
    static Uri fileUri(String alias, FileEntry fe) {
        String name = fe.name != null && fe.name.length() > 0 ? fe.name : fe.fileId;
        Uri.Builder b = accountBuilder(alias).appendPath(name);
        b.appendQueryParameter("id", fe.fileId);
        b.appendQueryParameter("s", String.valueOf(fe.size));
        long ts = fe.updatedAt > 0 ? fe.updatedAt : fe.createdAt;
        if (ts > 0)
            b.appendQueryParameter("t", String.valueOf(ts));
        return b.build();
    }

    static String fileIdOf(Item it) {
        if (it == null) return ROOT_ID;
        if (it.origin instanceof FileEntry)
            return ((FileEntry) it.origin).fileId;
        return fileIdFromUri(it.uri);
    }

    /** Rebuilds an Item purely from a fileUri()'s own path/query parameters; see fileUri(). */
    private static Item itemFromUri(Uri u) {
        if (u == null) return null;
        String fid = fileIdFromUri(u);
        if (ROOT_ID.equals(fid)) return null;
        Item it = new Item();
        it.dir = "1".equals(u.getQueryParameter("d"));
        it.uri = u;
        if (it.dir) {
            it.name = fid; // folderUri() doesn't carry a name - not needed for navigation
            it.size = -1;
        } else {
            String p = u.getPath();
            it.name = (p != null && p.length() > 1) ? p.substring(1) : fid;
            it.size = parseLongOr(u.getQueryParameter("s"), -1);
            long ts = parseLongOr(u.getQueryParameter("t"), 0);
            it.date = ts > 0 ? new Date(ts) : null;
        }
        return it;
    }

    private static long parseLongOr(String s, long fallback) {
        if (s == null) return fallback;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------
    // Identity / navigation
    // ------------------------------------------------------------------

    @Override
    public String getScheme() {
        return SCHEME;
    }

    @Override
    public void setUri(Uri uri_) {
        if (uri_ != null)
            uri = uri_;
    }

    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public String toString() {
        String alias = accountIdFromUri(uri);
        if (alias == null)
            return SCHEME + ":";
        String fid = fileIdFromUri(uri);
        return ROOT_ID.equals(fid) ? SCHEME + "://" + alias : SCHEME + "://" + alias + "/" + fid;
    }

    @Override
    public boolean readSource(Uri uri_, String pass_back_on_done) {
        // ListHelper.Navigate() ignores the return value of readSource() and, when nothing is
        // started, simply keeps showing the previous listing - so on a failure the location has
        // to go back to what that previous listing is, or ".." and every write operation
        // would act on a location that is not the one on screen.
        Uri prevUri = uri;
        try {
            if (uri_ != null)
                uri = uri_;
            String alias = accountIdFromUri(uri);

            if (alias == null) {
                // Top level of the plugin: the saved accounts. Purely local, no network.
                // No accounts yet just means this list comes back empty (still with the ".."
                // row) - browse it like any other empty folder instead of interrupting with
                // a popup and jumping to the Settings screen.
                if (reader != null)
                    reader.interrupt();
                AccountListEngine ale = new AccountListEngine(readerHandler, this, pass_back_on_done);
                reader = ale;
                ale.start();
                return true;
            }

            Yun139Api api = Yun139Api.getInstance(ctx, alias);
            if (!api.hasToken()) {
                // An alias only exists together with its token, so this means the account
                // was removed while a bookmark/history entry/panel still pointed into it.
                uri = prevUri;
                notify("账号“" + alias + "”不存在或已被移除，请返回账号列表重新选择。", Commander.OPERATION_FAILED);
                return false;
            }
            if (reader != null)
                reader.interrupt();
            ListEngine le = new ListEngine(readerHandler, this, api, fileIdFromUri(uri), pass_back_on_done);
            reader = le;
            le.start();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "readSource", e);
            notify("Exception: " + e.getMessage(), Commander.OPERATION_FAILED);
        }
        return false;
    }

    @Override
    public void onReadComplete() {
        if (reader instanceof AccountListEngine) {
            String[] aliases = ((AccountListEngine) reader).result;
            if (aliases == null)
                aliases = new String[0];
            Item[] newItems = new Item[aliases.length];
            for (int i = 0; i < aliases.length; i++) {
                String alias = aliases[i];
                Item it = new Item();
                it.name = alias;
                it.dir = true;
                it.size = -1;
                String phone = Yun139Api.getMaskedPhone(ctx, alias);
                it.attr = phone != null ? phone : "";
                it.uri = folderUri(alias, ROOT_ID);
                it.origin = alias; // a String origin is what marks a row as an account (see openItem)
                newItems[i] = it;
            }
            items = newItems;
            numItems = items.length + 1; // + the ".." row, which the framework expects at 0 everywhere
            notifyDataSetChanged();
            return;
        }

        List<FileEntry> list = new ArrayList<FileEntry>();
        // The account these entries came from is taken from the Engine that fetched them,
        // not from the current uri: by the time the result is delivered the user may
        // already have navigated somewhere else.
        String alias = null;
        if (reader instanceof ListEngine) {
            ListEngine le = (ListEngine) reader;
            if (le.result != null) {
                list = le.result;
                alias = le.api.getAlias();
            }
        }
        Collections.sort(list, new Comparator<FileEntry>() {
            public int compare(FileEntry a, FileEntry b) {
                if (a.isDir != b.isDir)
                    return a.isDir ? -1 : 1;
                String an = a.name != null ? a.name : "";
                String bn = b.name != null ? b.name : "";
                return an.compareToIgnoreCase(bn);
            }
        });
        Item[] newItems = new Item[alias != null ? list.size() : 0];
        for (int i = 0; i < newItems.length; i++) {
            FileEntry fe = list.get(i);
            Item it = new Item();
            it.name = fe.name;
            it.dir = fe.isDir;
            it.size = fe.isDir ? -1 : fe.size;
            long ts = fe.updatedAt > 0 ? fe.updatedAt : fe.createdAt;
            it.date = ts > 0 ? new Date(ts) : null;
            it.uri = fe.isDir ? folderUri(alias, fe.fileId) : fileUri(alias, fe);
            it.origin = fe;
            newItems[i] = it;
        }
        items = newItems;
        numItems = items.length + 1;
        notifyDataSetChanged();
    }

    /**
     * Where ".." leads from the current location: up the current account's folders, then from
     * the account's own drive root to the account list, and from the account list to the Home
     * screen (the same thing the webdav plugin does at its root). The framework's "up" (the Back
     * key, the toolbar button) calls openItem(0) on every adapter unconditionally, so row 0 has
     * to be a ".." row on the account list too.
     *
     * @param pop true to consume the breadcrumb entry (actually navigating), false to only look
     */
    private Uri parentUri(boolean pop) {
        String alias = accountIdFromUri(uri);
        if (alias == null)
            return Uri.parse(HOME_URI);
        Stack<String> ps = parentStackFor(alias);
        if (ps.isEmpty())
            return accountListUri();
        return folderUri(alias, pop ? ps.pop() : ps.peek());
    }

    @Override
    public void openItem(int position) {
        if (position == 0) {
            commander.Navigate(parentUri(true), null, null);
            return;
        }
        Item it = itemAt(position);
        if (it == null) return;
        if (it.origin instanceof String) {
            // An account row: enter that account at its drive root. Always start with an empty
            // breadcrumb - whatever is left from an earlier visit (the framework's own history /
            // bookmarks can leave an account without unwinding it via "..") describes ancestors
            // of a folder we are no longer in, and ".." would wrongly jump back into it.
            parentStackFor((String) it.origin).clear();
            commander.Navigate(it.uri, null, null);
        } else if (it.dir) {
            String alias = accountIdFromUri(uri);
            if (alias == null) return; // a stale row of a listing we have already left
            parentStackFor(alias).push(fileIdFromUri(uri));
            commander.Navigate(it.uri, null, null);
        } else {
            commander.Open(it.uri, null);
        }
    }

    // ------------------------------------------------------------------
    // BaseAdapter / listing accessors
    // ------------------------------------------------------------------

    @Override
    public Object getItem(int position) {
        if (position == 0) {
            Item up = new Item();
            up.name = PLS;
            up.dir = true;
            return up;
        }
        int idx = position - 1;
        return idx >= 0 && idx < items.length ? items[idx] : null;
    }

    private Item itemAt(int position) {
        Object o = getItem(position);
        return o instanceof Item ? (Item) o : null;
    }

    @Override
    public Item getItem(Uri fileURI) {
        if (fileURI == null) return null;
        String wantAlias = accountIdFromUri(fileURI);
        String fid = fileIdFromUri(fileURI);
        for (Item it : items) {
            if (it.origin instanceof String) {
                // account row: only the account-root Uri of that very alias refers to it
                if (wantAlias != null && wantAlias.equals(it.origin) && ROOT_ID.equals(fid))
                    return it;
            } else if (fid.equals(fileIdOf(it)) && wantAlias != null && wantAlias.equals(accountIdFromUri(it.uri))) {
                return it;
            }
        }
        // Not in the current listing - either a different (throwaway) adapter instance
        // asked, e.g. via StreamProvider/DataProxy when opening a file externally, which
        // never called readSource() so items[] is still empty, or the user navigated
        // straight to a bookmarked/favourited file. Either way fileUri() embedded enough
        // to answer from the Uri alone.
        return itemFromUri(fileURI);
    }

    @Override
    public String getItemName(int position, boolean full) {
        Item it = itemAt(position);
        return it != null ? it.name : null;
    }

    @Override
    public Uri getItemUri(int position) {
        if (position == 0)
            return parentUri(false);
        Item it = itemAt(position);
        return it != null ? it.uri : null;
    }

    private Item[] itemsFromSelection(SparseBooleanArray cis) {
        ArrayList<Item> list = new ArrayList<Item>();
        for (int i = 0; i < cis.size(); i++) {
            if (!cis.valueAt(i)) continue;
            int pos = cis.keyAt(i);
            if (pos == 0) continue;
            Item it = itemAt(pos);
            if (it != null) list.add(it);
        }
        return list.toArray(new Item[0]);
    }

    // ------------------------------------------------------------------
    // Write operations - all handed off to background Engines
    // ------------------------------------------------------------------

    /**
     * The alias of the account being browsed. At the account list there is none, and none of the
     * write operations mean anything there (the accounts themselves are managed in Prefs, not with
     * file operations), so the user is told so and null is returned. notify(String, int) goes via
     * the SimpleHandler, i.e. it only shows the message and does not touch the listing.
     */
    private String currentAccountOrNotify() {
        String alias = accountIdFromUri(uri);
        if (alias == null)
            notify(MSG_AT_ACCOUNT_LIST, Commander.OPERATION_FAILED);
        return alias;
    }

    @Override
    public void createFolder(String name) {
        String alias = currentAccountOrNotify();
        if (alias == null) return;
        commander.startEngine(new MkDirEngine(this, Yun139Api.getInstance(ctx, alias), fileIdFromUri(uri), name));
    }

    @Override
    public boolean createFile(String name) {
        // "New empty file" isn't implemented: every 139 upload call needs a size + SHA-256
        // hash up front, which doesn't map cleanly onto a fire-and-forget boolean-returning
        // call. Use "new folder" or upload/copy a real file instead.
        return false;
    }

    @Override
    public boolean deleteItems(SparseBooleanArray cis) {
        String alias = currentAccountOrNotify();
        if (alias == null) return false;
        Item[] toDelete = itemsFromSelection(cis);
        if (toDelete.length == 0) return false;
        commander.startEngine(new DelEngine(this, Yun139Api.getInstance(ctx, alias), toDelete));
        return true;
    }

    @Override
    public boolean renameItem(int position, String newName, boolean copy) {
        String alias = currentAccountOrNotify();
        if (alias == null) return false;
        Item it = itemAt(position);
        if (it == null) return false;
        commander.startEngine(new RenEngine(this, Yun139Api.getInstance(ctx, alias), fileIdFromUri(uri), it, newName, copy));
        return true;
    }

    @Override
    public boolean copyItems(SparseBooleanArray cis, CommanderAdapter to, boolean move) {
        String alias = currentAccountOrNotify();
        if (alias == null) return false;
        Item[] toCopy = itemsFromSelection(cis);
        if (toCopy.length == 0) return false;
        Yun139Api api = Yun139Api.getInstance(ctx, alias);

        // The destination is a folder of the SAME account: both ends are on one and the same
        // server-side drive, so let the server move/copy there (MoveCopyEngine) instead of
        // downloading every file to the phone just to upload it again (CopyFromEngine + Receiver).
        String destFolderId = sameAccountFolderId(alias, to);
        if (destFolderId != null) {
            if (destFolderId.equals(fileIdFromUri(uri))) {
                notify("源目录与目标目录相同", Commander.OPERATION_FAILED);
                return false;
            }
            for (Item it : toCopy) {
                if (it.dir && destFolderId.equals(fileIdOf(it))) {
                    notify("不能把文件夹“" + it.name + "”" + (move ? "移动" : "复制") + "到它自己里面",
                            Commander.OPERATION_FAILED);
                    return false;
                }
            }
            commander.startEngine(new MoveCopyEngine(commander, this, api, toCopy, destFolderId, move));
            return true;
        }

        // Another account, or some other kind of location: the bytes really do have to travel.
        commander.startEngine(new CopyFromEngine(commander, this, api, toCopy, move, to));
        return true;
    }

    /**
     * @return the file id of the destination folder if {@code to} is a panel of this plugin that
     *         is showing a folder of account {@code alias}; null for anything else.
     * <p>
     * Deliberately goes by getScheme()/getUri() and not by instanceof Yun139Adapter: CA.java
     * loads the plugin through a brand-new DexClassLoader for every adapter it creates, so the
     * other panel's adapter is an instance of a <i>different</i> Yun139Adapter class (same name,
     * other class loader) - instanceof is false for it and a cast would throw. android.net.Uri
     * and CommanderAdapter come from the host, so those are shared and safe to use.
     */
    private static String sameAccountFolderId(String alias, CommanderAdapter to) {
        if (to == null || !SCHEME.equals(to.getScheme()))
            return null;
        Uri dest = to.getUri();
        if (!alias.equals(accountIdFromUri(dest)))
            return null;
        return fileIdFromUri(dest);
    }

    @Override
    public boolean receiveItems(String[] fileURIs, int move_mode) {
        if (fileURIs == null || fileURIs.length == 0) return false;
        String alias = currentAccountOrNotify();
        if (alias == null) return false;
        commander.startEngine(new CopyToEngine(commander, this, Yun139Api.getInstance(ctx, alias), fileURIs, move_mode));
        return true;
    }

    @Override
    public void reqItemsSize(SparseBooleanArray cis) {
        // The "Properties" command. Everything it shows is already in the listing - file/list
        // returns the size, the times and the content hash of every file - so it is answered on
        // the spot: no request, no Engine. (A folder's size is not in the listing and walking its
        // subtree just to report a number is not implemented.)
        //
        // The report goes out with OPERATION_REPORT_IMPORTANT, which makes the host open its
        // "Info" dialog. A plain OPERATION_COMPLETED message - what this used to send - is only
        // shown when the "Confirmations" preference is on, and that is off by default (see
        // FileCommander.notifyMe()), which is why the command appeared to do nothing.
        Item[] sel = itemsFromSelection(cis);
        if (sel.length == 0) {
            notify("请先选择文件或文件夹", Commander.OPERATION_FAILED);
            return;
        }
        String report;
        if (sel[0].origin instanceof String)
            report = propsOfAccounts(sel); // rows of the account list
        else if (sel.length == 1)
            report = propsOfOne(sel[0]);
        else
            report = propsOfMany(sel);
        notify(report, Commander.OPERATION_COMPLETED, Commander.OPERATION_REPORT_IMPORTANT);
    }

    /** Properties of one row of the listing, a file or a folder. */
    private String propsOfOne(Item it) {
        StringBuilder sb = new StringBuilder();
        addProp(sb, it.dir ? "文件夹" : "文件", it.name, false);
        if (!it.dir && it.size >= 0)
            addProp(sb, "大小", sizeText(it.size), false);
        String modified = Utils.formatDate(it.date, ctx);
        if (modified != null)
            addProp(sb, "修改时间", modified, false);
        if (!it.dir) {
            // The digest travels in the FileEntry the row carries (see Yun139Api.list()).
            FileEntry fe = it.origin instanceof FileEntry ? (FileEntry) it.origin : null;
            if (fe != null && fe.contentHash != null)
                addProp(sb, hashLabel(fe.contentHashAlgorithm), fe.contentHash, true);
            else
                addProp(sb, "SHA-256", "服务器未提供", false);
        }
        return sb.toString().trim();
    }

    /** Properties of several rows: how many, and the combined size of the files among them. */
    private String propsOfMany(Item[] sel) {
        int files = 0, folders = 0;
        long total = 0;
        for (Item it : sel) {
            if (it.dir) {
                folders++;
            } else {
                files++;
                if (it.size > 0)
                    total += it.size;
            }
        }
        String count;
        if (files > 0 && folders > 0)
            count = files + " 个文件，" + folders + " 个文件夹";
        else if (files > 0)
            count = files + " 个文件";
        else
            count = folders + " 个文件夹";
        StringBuilder sb = new StringBuilder();
        addProp(sb, "数量", count, false);
        if (files > 0) {
            // Folder sizes are unknown, so don't present the sum as the size of everything selected.
            addProp(sb, "大小", sizeText(total) + (folders > 0 ? "（不含文件夹内的内容）" : ""), false);
        }
        return sb.toString().trim();
    }

    /** Properties of rows of the account list: the alias and the masked phone number. */
    private String propsOfAccounts(Item[] sel) {
        StringBuilder sb = new StringBuilder();
        for (Item it : sel) {
            if (!(it.origin instanceof String))
                continue;
            addProp(sb, "账号", it.name, false);
            if (it.attr != null && it.attr.length() > 0)
                addProp(sb, "手机号", it.attr, false);
        }
        return sb.toString().trim();
    }

    /**
     * Appends one "label / value" block of the properties report, laid out like the host's own
     * file-properties report: a small label, the value under it, a blank line between blocks.
     * The Info dialog renders HTML, so the value is escaped - a file name may contain '<' or '&'.
     */
    private static void addProp(StringBuilder sb, String label, String value, boolean mono) {
        if (sb.length() > 0)
            sb.append('\n');
        String v = TextUtils.htmlEncode(value != null ? value : "");
        sb.append("<small>").append(label).append("</small>\n")
                .append(mono ? "<tt>" + v + "</tt>" : v).append('\n');
    }

    /** "182.5M (191378548 字节)", the same way the host's properties report words a size. */
    private static String sizeText(long bytes) {
        if (bytes > 1024)
            return Utils.getHumanSize(bytes, false).trim() + " (" + bytes + " 字节)";
        return bytes + " 字节";
    }

    /** "sha256" -> "SHA-256"; any other algorithm is labelled with its own name as the server gives it. */
    private static String hashLabel(String algorithm) {
        if (algorithm == null || algorithm.length() == 0)
            return "哈希";
        if (algorithm.replace("-", "").equalsIgnoreCase("sha256"))
            return "SHA-256";
        return algorithm.toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // Receiver (used when some OTHER adapter copies files INTO this folder)
    // ------------------------------------------------------------------

    @Override
    @Deprecated
    public Engines.IReciever getReceiver() {
        return null; // superseded by getReceiver(Uri); see Receiver.java
    }

    @Override
    public IReceiver getReceiver(Uri dir) {
        // The destination folder's own Uri says which account it is in.
        String alias = accountIdFromUri(dir);
        if (alias == null)
            return null; // the account list can't receive files
        return new Receiver(ctx, Yun139Api.getInstance(ctx, alias), dir);
    }

    // ------------------------------------------------------------------
    // Content streaming (preview / open-with / text editor)
    // ------------------------------------------------------------------

    @Override
    public InputStream getContent(Uri u) {
        return getContent(u, 0);
    }

    @Override
    public InputStream getContent(Uri u, long skip) {
        // The file's own Uri says which account it is in - it may have been reached through a
        // bookmark, or by a throwaway adapter instance, not through the folder now on screen.
        String alias = accountIdFromUri(u);
        if (alias == null)
            return null;
        String fileId = fileIdFromUri(u);
        try {
            // RemoteSeekableStream's skip() re-issues a Range request at the new offset
            // instead of downloading-and-discarding - see its own doc comment for why
            // that specifically matters for DataProxy's forward-seek handling.
            return new RemoteSeekableStream(Yun139Api.getInstance(ctx, alias), fileId, skip);
        } catch (IOException e) {
            Log.e(TAG, "getContent fileId=" + fileId + " skip=" + skip, e);
            return null;
        }
    }

    @Override
    public OutputStream saveContent(Uri u) {
        // Saved into the folder currently being shown, so the file must be in that same account.
        String curAlias = accountIdFromUri(uri);
        String alias = accountIdFromUri(u);
        if (alias == null)
            alias = curAlias;
        if (alias == null || !alias.equals(curAlias))
            return null;
        final Yun139Api api = Yun139Api.getInstance(ctx, alias);
        try {
            final String parentId = fileIdFromUri(uri); // the folder currently being shown
            Item known = getItem(u);
            final String name = known != null && known.name != null ? known.name : nameFromUri(u);
            final File tmp = File.createTempFile("yun139sv", ".tmp", ctx.getCacheDir());
            return new FileOutputStream(tmp) {
                @Override
                public void close() throws IOException {
                    super.close();
                    try {
                        api.uploadFile(parentId, tmp, name, null);
                    } finally {
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                    }
                }
            };
        } catch (IOException e) {
            Log.e(TAG, "saveContent", e);
            return null;
        }
    }

    private static String nameFromUri(Uri u) {
        if (u != null) {
            String p = u.getPath();
            if (p != null && p.length() > 1)
                return p.substring(1);
        }
        String fid = fileIdFromUri(u);
        return fid != null ? fid : "file";
    }

    // ------------------------------------------------------------------
    // Misc CommanderAdapter contract
    // ------------------------------------------------------------------

    @Override
    public boolean hasFeature(Feature feature) {
        switch (feature) {
            case REAL:
            case RENAME:
            case DELETE:
            case RECEIVER:
                return true;
            default:
                return super.hasFeature(feature);
        }
    }

    @Override
    public void setCredentials(Credentials crd) {
        // Auth is a pasted token per account, stored by Yun139Api/Prefs, not the generic
        // Credentials (user/password) flow the framework offers to ftp/sftp/smb/webdav.
    }

    @Override
    public Credentials getCredentials() {
        return null;
    }
}
