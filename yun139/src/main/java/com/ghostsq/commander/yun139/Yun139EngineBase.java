package com.ghostsq.commander.yun139;

import com.ghostsq.commander.adapters.Engine;
import com.ghostsq.commander.yun139.Yun139Api.FileEntry;

import java.io.IOException;
import java.util.List;

/**
 * Shared base for all yun139 background operations (list/mkdir/delete/rename/copy).
 * Mirrors the role GDriveEngineBase / WebDAV's per-op Engine subclasses play for
 * their respective plugins, but talks to Yun139Api instead of an SDK.
 */
class Yun139EngineBase extends Engine {
    protected final Yun139Adapter adapter;
    protected final Yun139Api api;

    /**
     * @param api the Yun139Api of the ONE account this operation targets, resolved by the
     *            caller from the relevant Uri at the moment the user issued the operation.
     *            Deliberately NOT read off the adapter here: Engines run on their own
     *            threads, possibly long after they were created, and the adapter may have
     *            navigated into a different account in the meantime. Capturing the instance
     *            at construction time pins an in-flight upload/delete to the account it
     *            was started against no matter what the UI does afterwards.
     */
    protected Yun139EngineBase(Yun139Adapter adapter, Yun139Api api) {
        super();
        this.adapter = adapter;
        this.api = api;
    }

    /** Looks up a direct child of parentFileId by exact name; null if there is none. */
    protected FileEntry findChild(String parentFileId, String name) throws IOException {
        if (name == null) return null;
        List<FileEntry> items = api.list(parentFileId);
        for (FileEntry fe : items)
            if (name.equals(fe.name))
                return fe;
        return null;
    }
}
