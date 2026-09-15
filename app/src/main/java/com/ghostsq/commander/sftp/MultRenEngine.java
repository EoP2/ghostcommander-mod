package com.ghostsq.commander.sftp;

import android.content.Context;
import android.util.Log;

import com.ghostsq.commander.adapters.CommanderAdapter.Item;
import com.ghostsq.commander.utils.Replacer;
import com.ghostsq.commander.utils.Utils;

class MultRenEngine extends SFTPEngineBase {
    private final Context ctx;
    private final String pattern_str;
    private final String replace_to;
    private final SFTPReplacer r;

    public MultRenEngine( Context ctx_, SFTPAdapter a, Item[] list, String pattern_str, String replace_to ) {
        super( a, list );
        ctx = ctx_;
        this.pattern_str = pattern_str;
        this.replace_to = replace_to;
        this.r = new SFTPReplacer( list, adapter.getUri().getPath() );
    }
    @Override
    public void run() {
        try {
            sftp = adapter.getChannel();
            if( sftp == null ) throw new Exception( "Not connected" );
            r.replace( pattern_str, replace_to );
        } catch( Exception e ) {
            error( ctx.getString( Utils.RR.failed.r() ) + e.getLocalizedMessage() );
        } finally {
            if( sftp != null )
                sftp.disconnect();
        }
        sendResult( "" );
    }

    class SFTPReplacer extends Replacer {
        private Item[] origList;
        private String base_path;
        SFTPReplacer( Item[] origList, String base_path ) {
            this.origList = origList;
            this.base_path = base_path;
        }
        @Override
        protected int getNumberOfOriginalStrings() {
            return origList.length;
        }
        @Override
        protected String getOriginalString( int i ) {
            return origList[i].name;
        }
        @Override
        protected void setReplacedString( int i, String replaced ) {
            try {
                Item item = origList[i];
                String path = item.getPath();
                int lsp = path.lastIndexOf( '/', path.length() - 2 );
                String parent_path, new_path;
                if( lsp < 0 ) {
                    parent_path = base_path;
                    path = Utils.mbAddSl( parent_path ) + path;
                } else {
                    parent_path = path.substring( 0, lsp + 1 );
                }
                new_path = parent_path + replaced;
                sftp.rename( path, new_path );
            } catch( Exception e ) {
                Log.e( TAG, "Can't rename item " + i + " to " + replaced );
            }
        }
    }
}
