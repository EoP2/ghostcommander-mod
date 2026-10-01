package com.ghostsq.commander.sftp;

import android.net.Uri;
import android.util.Log;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.CommanderAdapter.Item;
import com.ghostsq.commander.utils.Utils;
import com.jcraft.jsch.SftpATTRS;

class RenEngine extends SFTPEngineBase {
    private Commander    commander;
    private String       newName;
    
    public RenEngine( Commander commander_, SFTPAdapter a, Item[] list, String to_name_or_path ) {
        super( a, list );
        commander = commander_;
        ctx = commander.getContext();
        newName = to_name_or_path;
    }
    @Override
    public void run() {
        try {
            sftp = adapter.getChannel();
            if( sftp == null ) throw new Exception( "Not connected" );
            Uri u = adapter.getUri();
            String base_path = Utils.mbAddSl( u.getPath() );
            boolean dest_is_dir = newName.indexOf( '/' ) >= 0;
            int n = move( mList, base_path, newName, dest_is_dir );
            if( n > 0 ) {
                String rep = Utils.getOpReport( ctx, n, Utils.RR.moved.r() );
                sendResult( rep );
                return;
            }
        } catch( Exception e ) {
            error( ctx.getString( Utils.RR.failed.r() ) + e.getLocalizedMessage() );
        } finally {
            if( sftp != null )
                sftp.disconnect();
        }
        sendResult( "" );
    }

    public int move( Item[] origList, String base_path, String new_name, boolean dest_is_dir ) {
        int ok_cnt = 0;
        for( int i = 0; i < origList.length; i++ ) {
            String old_path = null, new_path = null;
            try {
                String old_name = origList[i].getPath();
                if( ".".equals( old_name ) || "..".equals( old_name ) )
                    continue;
                old_path = base_path + old_name;
                new_path = dest_is_dir ? new_name + old_name : base_path + new_name;
                if( old_path.equals( new_path ) )
                    continue;
                if( new_path.startsWith( old_path ) ) {
                    error( ctx.getString( Utils.RR.rename_err.r() ) + "\n" + old_path );
                    continue;
                }
                try {
                    SftpATTRS fa = sftp.lstat( new_path );
                    int res = askOnFileExist( ctx.getString( Utils.RR.file_exist.r(), new_path ), commander );
                    if( res == Commander.ABORT ) break;
                    if( res == Commander.SKIP )  continue;
                    if( res == Commander.REPLACE ) {
                        if( !dest_is_dir && fa.isDir() )
                            sftp.rmdir( new_path ); // removing all the destination tree!!!
                        else if( dest_is_dir && fa.isDir() && origList[i].dir ) {
                            Item[] sub_items = getItems( old_path );
                            if( move( sub_items, Utils.mbAddSl( old_path ), Utils.mbAddSl( new_path ), true ) > 0 ) {
                                sftp.rmdir( old_path );
                                ok_cnt++;
                                continue;
                            }
                        } else
                            sftp.rm( new_path );
                    }
                } catch( Exception e1 ) {}
                sftp.rename( old_path, new_path );
                ok_cnt++;
            } catch( Exception e ) {
                Log.e( TAG, "Moving from " + old_path + " to " + new_path, e );
                error( ctx.getString( Utils.RR.failed.r() ) + e.getLocalizedMessage() );
            }
        }
        return ok_cnt;
    }

}
