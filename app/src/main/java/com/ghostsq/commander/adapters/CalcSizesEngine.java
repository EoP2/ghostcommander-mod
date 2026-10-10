package com.ghostsq.commander.adapters;

import android.content.Context;
import android.os.Build;
import android.text.format.Formatter;
import android.util.Log;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.R;
import com.ghostsq.commander.utils.ForwardCompat;
import com.ghostsq.commander.utils.ImageInfo;
import com.ghostsq.commander.utils.Utils;

import java.io.File;
import java.util.Date;

public class CalcSizesEngine extends Engine {
    private   FSEngines.IFileItem[] mList;
    protected CommanderAdapterBase cab;
    protected int  num = 0, dirs = 0, depth = 0;

    CalcSizesEngine( CommanderAdapterBase cab, FSEngines.IFileItem[] list ) {
        this.cab = cab;
        mList = list;
        setName( ".CalcSizesEngine" );
    }
    @Override
    public void run() {
        try {
            cab.Init( null );
            Context c = cab.ctx;
            StringBuffer result = new StringBuffer( );
            if( mList != null && mList.length > 0 ) {
                sendProgress();
                long sum = getSizes( mList );
                if( sum < 0 ) {
                    sendProgress( "Interrupted", Commander.OPERATION_FAILED );
                    return;
                }
                if( ( cab.mode & CommanderAdapter.MODE_SORTING) == CommanderAdapter.SORT_SIZE )
                    cab.reSort();
                if( mList.length == 1 ) {
                    CommanderAdapter.Item item = (CommanderAdapter.Item)mList[0];
                    File f = mList[0].f();
                    if( item.dir ) {
                        result.append( "<small>" ).append( c.getString( R.string.sz_folder ) ).append( "</small>\n" ).append( f.getAbsolutePath() ).append( "\n" ).append( c.getString( R.string.sz_folder_count, num ) );
                        if( dirs > 0 )
                            result.append( c.getString( R.string.sz_dirnum, dirs, ( dirs > 1 ? c.getString( R.string.sz_dirsfx_p ) : c.getString( R.string.sz_dirsfx_s ) ) ) ).append( "\n" );
                    }
                    else
                        result.append( "<small>" ).append( c.getString( R.string.sz_file ) ).append( "</small>\n" ).append( f.getAbsolutePath() ).append( "\n" );
                } else
                    result.append( "<small>" ).append( c.getString( R.string.sz_files ) ).append( "</small>\n" ).append( num ).append( "\n" );
                if( sum > 0 )
                    result.append( "\n<small>" ).append( c.getString( R.string.sz_Nbytes ) ).append( "</small>\n" ).append( Formatter.formatFileSize( c, sum ).trim() );
                if( sum > 1024 )
                    result.append( c.getString( R.string.sz_bytes, sum ) ).append( "\n" );
                if( mList.length == 1 ) {
                    CommanderAdapter.Item item = (CommanderAdapter.Item)mList[0];
                    result.append( "\n<small>" ).append( c.getString( R.string.sz_lastmod ) ).append( "</small>\n" );
                    String date_s = Utils.formatDate( item.date, c );
                    result.append( date_s );
                    File f = mList[0].f();
                    if( android.os.Build.VERSION.SDK_INT > Build.VERSION_CODES.O ) {
                        Date ct = ForwardCompat.getFileTime( f, 0 );
                        if( ct != null ) {
                            result.append( "\n<small>" ).append( c.getString( R.string.sz_created ) ).append( "</small>\n" );
                            date_s = Utils.formatDate( ct, c );
                            result.append( date_s );
                        }
                        Date at = ForwardCompat.getFileTime( f, 1 );
                        if( at != null ) {
                            result.append( "\n<small>" ).append( c.getString( R.string.sz_access ) ).append( "</small>\n" );
                            date_s = Utils.formatDate( at, c );
                            result.append( date_s );
                        }
                    }
                    if( f.isFile() ) {
                        String ext  = Utils.getFileExt( item.name );
                        String mime = Utils.getMimeByExt( ext );
                        result.append( "\n" );
                        if( mime != null && !"*/*".equals( mime ) )
                            result.append( "\n<small>MIME</small>\n" + mime + "\n" );
                        if( Utils.getCategoryByExt( Utils.getFileExt( item.name ) ) == Utils.C_IMAGE ) {
                            result.append( "\n" );
                            result.append( ImageInfo.getImageFileInfoHTML( f.getAbsolutePath() ) );
                        }
                    }
                }
            }
            try {
                if( cab instanceof FSAdapter ) {
                    result.append( "\n\n<hr/>" );
                    result.append( ((FSAdapter)cab).getVolumeInfo( true ) );
                } else
                if( cab instanceof SAFAdapter ) {
                    result.append( "\n\n<hr/>" );
                    result.append( SAFAdapter.getVolumeInfo( c, cab.getUri(), true ) );
                }
            } catch( Exception e ) {
            }
            String str = result.toString();
            sendReport( str );
        } catch( Exception e ) {
            Log.e( TAG, "", e );
            sendProgress( e.getMessage(), Commander.OPERATION_FAILED );
        }
    }
    protected final long getSizes( FSEngines.IFileItem[] list ) throws Exception {
        long count = 0;
        for( int i = 0; i < list.length; i++ ) {
            if( isStopReq() ) return -1;
            FSEngines.IFileItem f = list[i];
            CommanderAdapter.Item item = (CommanderAdapter.Item)f;
            if( item.dir ) {
                dirs++;
                if( depth++ > 30 )
                    throw new Exception( cab.s( R.string.too_deep_hierarchy ) );
                File[] subfiles = f.f().listFiles();
                if( subfiles != null ) {
                    int l = subfiles.length;
                    FileItem[] subfiles_ex = new FileItem[l];
                    for( int j = 0; j < l; j++ )
                        subfiles_ex[j] = new FileItem( subfiles[j] );
                    long sz = getSizes( subfiles_ex );
                    if( sz < 0 ) return -1;
                    item.size = sz;
                    count += item.size;
                }
                depth--;
            }
            else {
                num++;
                if( android.os.Build.VERSION.SDK_INT > Build.VERSION_CODES.Q && item instanceof FileItem && ((FileItem)item).needAttrs ) {
                    item.size = f.f().length();
                }
                count += item.size;
            }
        }
        return count;
    }
}
