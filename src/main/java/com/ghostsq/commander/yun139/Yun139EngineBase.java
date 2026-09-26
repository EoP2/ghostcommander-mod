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

    protected Yun139EngineBase(Yun139Adapter adapter) {
        super();
        this.adapter = adapter;
        this.api = adapter.api;
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
