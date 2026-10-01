package com.ghostsq.commander.adapters;

public interface Manipulator {
    public boolean renameItem( CommanderAdapter.Item item, String newName, boolean copy );
    public boolean deleteItem( CommanderAdapter.Item item  );
}
