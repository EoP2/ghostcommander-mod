package com.ghostsq.commander.adapters;

import android.net.Uri;

/**
 * Supplementary interface to IReceiver in order not to break a compatibility with the plugins which already implemented IReceiver
 */
public interface IDestination {

        /**
         * Returns an item by an URI
         * @param item_uri uri of an item to check
         * @return  item if it exists, null if it does not
         */
        CommanderAdapter.Item getItem( Uri item_uri );

}
