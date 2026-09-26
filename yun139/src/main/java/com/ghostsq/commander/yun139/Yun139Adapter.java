package com.ghostsq.commander.yun139;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.util.SparseBooleanArray;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.CommanderAdapterBase;
import com.ghostsq.commander.adapters.Engines;
import com.ghostsq.commander.adapters.IReceiver;
import com.ghostsq.commander.utils.Credentials;
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
import java.util.List;
import java.util.Stack;

/**
 * GhostCommander plugin adapter for China Mobile's 139 Yun (个人云 - 云盘, yun.139.com),
 * "personal cloud - new" flavour, token-based login only.
 * <p>
 * Structurally this mirrors the webdav (com.ghostsq.commander.https) and gdrive
 * (com.ghostsq.commander.gdrive) plugins that ship with GhostCommander: a separate
 * APK sharing android:sharedUserId/android:process with the host app, with an entry
 * point class (yun139.java) that the host discovers and loads via CA.java's plugin
 * mechanism - see that class for the exact package/class naming contract.
 */
public class Yun139Adapter extends CommanderAdapterBase {
    private final static String TAG = "Yun139Adapter";
    final static String SCHEME = "yun139";
    private final static String ROOT_ID = "/";
    // Must match AndroidManifest.xml's package="..." exactly - see launchAccountForm()'s
    // doc comment for why this can't just be new Intent(ctx, Prefs.class) instead.
    private final static String PLUGIN_PACKAGE = "com.ghostsq.commander.yun139";

    private Uri uri = Uri.parse(SCHEME + ":/");
    private Item[] items = new Item[0];

    /** Ids of ancestor folders of the one currently shown; used to resolve ".." (see openItem). */
    private final Stack<String> parentStack = new Stack<String>();

    /** The account currently active on this adapter instance; null until ensureAccount() resolves one. */
    private String alias;
    Yun139Api api;

    public Yun139Adapter(Context ctx_) {
        super(ctx_);
    }

    // ------------------------------------------------------------------
    // Uri <-> fileId helpers (package-visible: shared with the Engine classes)
    // ------------------------------------------------------------------

    // The Uri authority carries the *account alias* (see Yun139Api's per-alias storage),
    // exactly like ftp/sftp/smb/webdav use it to carry a host(+user): yun139://alias/path.
    // Never "" - building/round-tripping a Uri with an *empty* authority ("scheme:///path")
    // has historically been a source of Android Uri implementations disagreeing about
    // hierarchical-vs-opaque - see aliasFromUri()/ensureAccount() for how a Uri with no
    // authority is handled instead of ever building one.

    static String fileIdFromUri(Uri u) {
        // The framework hands the plugin a bare "yun139:" Uri the first time it
        // navigates in from the Home screen (HomeAdapter builds new items with
        // Uri.parse(scheme + ":"), nothing after the colon). With no scheme-specific
        // part at all, Android classifies that as an *opaque* Uri, and every
        // hierarchical-only Uri method (getQueryParameter, getPathSegments, ...)
        // throws UnsupportedOperationException on it - has to be checked before any
        // of those are called, not just before the ones this method itself uses.
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

    /**
     * The account alias a Uri names, or null if it names none at all (a bare "yun139:"/
     * "yun139:/" with no authority - the framework hands the plugin exactly that the first
     * time it navigates in from the Home screen). Does NOT check whether that alias
     * actually has a usable token saved - see ensureAccount().
     */
    private String aliasFromUri(Uri u) {
        if (u == null) return null;
        String a = u.getAuthority();
        return a != null && a.length() > 0 ? a : null;
    }

    /**
     * Resolves (and, if it differs from whatever is already active, switches) api/alias to
     * the account named by u, falling back to whatever is already active if u names none.
     * <p>
     * Called from every entry point that can run on a freshly (re)constructed adapter
     * instance - not just readSource(), but also setUri() (see ListHelper.Navigate() /
     * Panels.java in the host app: both call one or the other directly, and the framework
     * reuses ONE adapter instance across same-scheme navigation, so switching accounts
     * within one pane relies entirely on this running on every uri change, not just once at
     * construction) and getContent(Uri) (which a throwaway adapter instance can receive
     * with neither setUri() nor readSource() ever having been called first - see getItem(Uri)'s
     * own doc comment for that same case).
     *
     * @return the alias now active, or null if u (and whatever was already active) names
     * no usable account at all - callers then know to prompt via launchAccountForm().
     */
    private String ensureAccount(Uri u) {
        String requested = aliasFromUri(u);
        String target = requested != null ? requested : alias;
        if (target == null) return null;
        if (api == null || alias == null || !alias.equals(target)) {
            api = new Yun139Api(ctx, target);
            alias = target;
        }
        return alias;
    }

    static Uri folderUri(String alias, String fileId) {
        Uri.Builder b = new Uri.Builder().scheme(SCHEME).authority(alias);
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
        Uri.Builder b = new Uri.Builder().scheme(SCHEME).authority(alias).appendPath(name);
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
        if (uri_ == null) return;
        uri = uri_;
        ensureAccount(uri); // best-effort; a uri naming no usable account is left for
                             // readSource()/getContent() to report - see their own doc comments
    }

    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public String toString() {
        String fid = fileIdFromUri(uri);
        String a = alias != null ? alias : "";
        return ROOT_ID.equals(fid) ? SCHEME + "://" + a + "/" : SCHEME + "://" + a + "/" + fid;
    }

    @Override
    public boolean readSource(Uri uri_, String pass_back_on_done) {
        try {
            if (uri_ != null)
                uri = uri_;
            String a = ensureAccount(uri);
            if (a == null) {
                // Deliberately NOT paired with notify(...): notify(s, Commander.OPERATION_
                // FAILED) surfaces as a modal AlertDialog (see FileCommander.showError(),
                // reached via notifyMe()'s OPERATION_FAILED case) that the user has to
                // dismiss - stacking that on top of/behind the account form launched right
                // below is confusing (two things demanding attention at once, and the
                // dialog can end up appearing *after* the form if the form is cancelled).
                // The form appearing at all already says everything the dialog would have.
                launchAccountForm(null);
                return false;
            }
            if (!api.hasToken()) {
                launchAccountForm(a); // see the null-alias branch above for why no notify()
                return false;
            }
            if (reader != null)
                reader.interrupt();
            String fileId = fileIdFromUri(uri);
            ListEngine le = new ListEngine(readerHandler, this, fileId, pass_back_on_done);
            reader = le;
            le.start();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "readSource", e);
            notify("Exception: " + e.getMessage(), Commander.OPERATION_FAILED);
        }
        return false;
    }

    /**
     * Opens the add/re-authenticate-one-account screen, exactly like the ftp/sftp/smb/webdav
     * plugins open their (shared, core) ServerForm when navigated to with no host: launched
     * via commander.issue(..., Commander.REQUEST_CODE_SRV_FORM) rather than a plain
     * startActivity(), so that if the user saves an account there, the host app's existing,
     * scheme-agnostic handling of that request code (FileCommander.onActivityResult(); no
     * :app changes needed for this to work) adds it to Favourites (if the user asked to) and
     * Navigate()s to it - Prefs.java returns the same shape of result Intent ServerForm does.
     * <p>
     * The Intent is built with an explicit ComponentName(PLUGIN_PACKAGE, ...), NOT
     * new Intent(ctx, Prefs.class): ctx here is the HOST app's Context (CA.java hands every
     * external adapter the host's own Context, not a createPackageContext() for the
     * plugin's package - see CA.CreateExternalAdapter()), so Intent(Context, Class) would
     * resolve to ComponentName(ctx.getPackageName(), Prefs.class.getName()) = ("com.ghostsq.
     * commander", "com.ghostsq.commander.yun139.Prefs") - a package+class combination that
     * doesn't exist (Prefs actually lives in the separately-installed "com.ghostsq.commander.
     * yun139" package), so it would never resolve and startActivity would throw
     * ActivityNotFoundException. HomeAdapter.getPluginPrefIntent() (the host's own, working
     * code for launching a plugin's Prefs screen) sidesteps exactly this by building the
     * ComponentName explicitly instead - this mirrors that.
     *
     * @param presetAlias the specific account to re-authenticate (its token is missing or was
     *                     just found to be unusable), or null to prompt for a brand new one.
     *                     Deliberately never paired with notify(...) here or at any call
     *                     site - see readSource()'s null-alias branch for why: the form
     *                     itself is the message, a separate modal on top of/behind it is
     *                     just visual noise fighting the form for attention. Prefs.java
     *                     shows its own in-form status text instead when presetAlias != null.
     */
    private void launchAccountForm(String presetAlias) {
        try {
            Intent in = new Intent();
            in.setComponent(new ComponentName(PLUGIN_PACKAGE, PLUGIN_PACKAGE + ".Prefs"));
            if (presetAlias != null)
                in.putExtra(Prefs.EXTRA_ALIAS, presetAlias);
            // Only set when launched from here, via issue(..., REQUEST_CODE_SRV_FORM) - as
            // opposed to HomeAdapter's own long-press "prefs" entry, which launches this
            // same Activity with a plain startActivity() (ret=0) that nothing ever collects
            // a result from. Prefs.java uses this to hide "add to favourites" in that case,
            // rather than show a checkbox that would silently do nothing if checked.
            in.putExtra(Prefs.EXTRA_EXPECTS_RESULT, true);
            commander.issue(in, Commander.REQUEST_CODE_SRV_FORM);
        } catch (Exception e) {
            Log.e(TAG, "launchAccountForm", e);
        }
    }

    @Override
    public void onReadComplete() {
        if (reader instanceof ListEngine && ((ListEngine) reader).authFailed) {
            launchAccountForm(alias); // see readSource()'s null-alias branch for why no notify()
            return;
        }
        List<FileEntry> list = new ArrayList<FileEntry>();
        if (reader instanceof ListEngine) {
            ListEngine le = (ListEngine) reader;
            if (le.result != null)
                list = le.result;
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
        Item[] newItems = new Item[list.size()];
        for (int i = 0; i < list.size(); i++) {
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

    @Override
    public void openItem(int position) {
        if (position == 0) {
            String target = parentStack.isEmpty() ? ROOT_ID : parentStack.pop();
            commander.Navigate(folderUri(alias, target), null, null);
            return;
        }
        Item it = itemAt(position);
        if (it == null) return;
        if (it.dir) {
            parentStack.push(fileIdFromUri(uri));
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
        String fid = fileIdFromUri(fileURI);
        for (Item it : items)
            if (fid.equals(fileIdOf(it)))
                return it;
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
        if (position == 0) {
            String target = parentStack.isEmpty() ? ROOT_ID : parentStack.peek();
            return folderUri(alias, target);
        }
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

    @Override
    public void createFolder(String name) {
        commander.startEngine(new MkDirEngine(this, fileIdFromUri(uri), name));
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
        Item[] toDelete = itemsFromSelection(cis);
        if (toDelete.length == 0) return false;
        commander.startEngine(new DelEngine(this, toDelete));
        return true;
    }

    @Override
    public boolean renameItem(int position, String newName, boolean copy) {
        Item it = itemAt(position);
        if (it == null) return false;
        commander.startEngine(new RenEngine(this, fileIdFromUri(uri), it, newName, copy));
        return true;
    }

    @Override
    public boolean copyItems(SparseBooleanArray cis, CommanderAdapter to, boolean move) {
        Item[] toCopy = itemsFromSelection(cis);
        if (toCopy.length == 0) return false;
        commander.startEngine(new CopyFromEngine(commander, this, toCopy, move, to));
        return true;
    }

    @Override
    public boolean receiveItems(String[] fileURIs, int move_mode) {
        if (fileURIs == null || fileURIs.length == 0) return false;
        commander.startEngine(new CopyToEngine(commander, this, fileURIs, move_mode));
        return true;
    }

    @Override
    public void reqItemsSize(SparseBooleanArray cis) {
        // Cheap best-effort: sum what the current listing already knows (file sizes are
        // returned by file/list; folder sizes are not, and recursively walking the whole
        // subtree just to report a number is not implemented).
        long total = 0;
        for (int i = 0; i < cis.size(); i++) {
            if (!cis.valueAt(i)) continue;
            Item it = itemAt(cis.keyAt(i));
            if (it != null && !it.dir && it.size > 0)
                total += it.size;
        }
        notify(com.ghostsq.commander.utils.Utils.getHumanSize(total), Commander.OPERATION_COMPLETED);
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
        return new Receiver(this, dir);
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
        // u's authority may name a *different* account than whatever is currently active -
        // e.g. a throwaway adapter instance that StreamProvider/DataProxy constructed and
        // is calling this on directly, having never called setUri()/readSource() first (see
        // ensureAccount()'s own doc comment). Harmless no-op when it already matches.
        if (ensureAccount(u) == null || !api.hasToken())
            return null;
        String fileId = fileIdFromUri(u);
        try {
            // RemoteSeekableStream's skip() re-issues a Range request at the new offset
            // instead of downloading-and-discarding - see its own doc comment for why
            // that specifically matters for DataProxy's forward-seek handling.
            return new RemoteSeekableStream(api, fileId, skip);
        } catch (IOException e) {
            Log.e(TAG, "getContent fileId=" + fileId + " skip=" + skip, e);
            return null;
        }
    }

    @Override
    public OutputStream saveContent(Uri u) {
        try {
            ensureAccount(u); // see getContent()'s comment on why this matters even here
            final String parentId = fileIdFromUri(uri); // the folder currently being shown
            final Yun139Api a = api;
            Item known = getItem(u);
            final String name = known != null && known.name != null ? known.name : nameFromUri(u);
            final File tmp = File.createTempFile("yun139sv", ".tmp", ctx.getCacheDir());
            return new FileOutputStream(tmp) {
                @Override
                public void close() throws IOException {
                    super.close();
                    try {
                        a.uploadFile(parentId, tmp, name, null);
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
        // Auth is a single pasted token stored by Yun139Api/Prefs, not the generic
        // Credentials (user/password) flow the framework offers to ftp/sftp/smb/webdav.
    }

    @Override
    public Credentials getCredentials() {
        return null;
    }
}
