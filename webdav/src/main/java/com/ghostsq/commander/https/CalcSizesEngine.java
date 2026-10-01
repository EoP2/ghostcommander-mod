package com.ghostsq.commander.https;

import android.content.Context;
import android.text.format.Formatter;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.CommanderAdapter.Item;
import com.ghostsq.commander.utils.Utils;

class CalcSizesEngine extends PropFinder {
    private int num = 0, dirs = 0, depth = 0;
    private Item[] list;

    CalcSizesEngine( WebDAVAdapter a, Item[] list_ ) {
        super( a );
        list = list_;
    }

    @Override
    public void run() {
        try {
            super.getClient();
            long sum = getSizes( list );
            Context ctx = owner.ctx;
            StringBuffer result = new StringBuffer();
            if( list.length == 1 ) {
                Item item = list[0];
                if( item.dir ) {
                    result.append( ctx.getString( Utils.RR.sz_folder.r(), item.name, num ) );
                    if( dirs > 0 )
                        result.append( ctx.getString( Utils.RR.sz_dirnum.r(), dirs, ( dirs > 1 ? ctx.getString( Utils.RR.sz_dirsfx_p.r() ) : ctx.getString( Utils.RR.sz_dirsfx_s.r() ) ) ) );
                }
                else {
                    result.append( ctx.getString( Utils.RR.sz_file.r(), item.name ) );
                }
            } else
                result.append( ctx.getString( Utils.RR.sz_files.r(), num ) );
            if( sum > 0 )
                result.append( ctx.getString( Utils.RR.sz_Nbytes.r(), Formatter.formatFileSize( ctx, sum ).trim() ) );
            if( sum > 1024 )
                result.append( ctx.getString( Utils.RR.sz_bytes.r(), sum ) );
            if( list.length == 1 ) {
                Item item = list[0];
                
                result.append( ctx.getString( Utils.RR.sz_lastmod.r() ) );
                result.append( " <small>" );
                result.append( item.date );
                result.append( "</small>" );

                result.append( "\n<b>URL:</b>\n<small>" );
                result.append( ((DavItem)item).getURI( sBaseUri ) );
                result.append( "</small>" );
            }
            sendReport( result.toString() );            
        } catch( Exception e ) {
            sendProgress( e.getMessage(), Commander.OPERATION_FAILED );
        } finally {
        }
    }

    protected final long getSizes( Item[] sublist ) throws Exception {
        long count = 0;
        for( int i = 0; i < sublist.length; i++ ) {
            if( isStopReq() ) return -1;
            DavItem item = (DavItem)sublist[i];
            if( item.dir ) {
                Item[] subItems = super.getItems( item.getURI( sBaseUri ) );
                if( subItems == null ) break;
                depth++;
                count += getSizes( subItems );
                depth--;
            } else {
                this.num++;
                count += item.size; 
            }
        }
        return count;
    }
}
