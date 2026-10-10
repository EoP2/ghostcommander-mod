package com.ghostsq.commander.sftp;

import android.net.Uri;
import android.os.Handler;
import android.util.Log;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.FilterProps;
import com.ghostsq.commander.R;
import com.ghostsq.commander.SearchProps;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.CommanderAdapter.Item;
import com.ghostsq.commander.adapters.Engine;
import com.ghostsq.commander.adapters.ItemComparator;
import com.ghostsq.commander.utils.Credentials;
import com.ghostsq.commander.utils.Utils;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;

import java.io.File;
import java.io.InputStream;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Vector;

class ListEngine extends Engine implements ChannelSftp.LsEntrySelector {
    private SFTPAdapter  adapter;
    private SearchProps  sq;
    private FilterProps  fp;
    private ChannelSftp  sftp, sftp2;
    private Item[]       tmpItems = null;
    private String       passBackOnDone;
    private boolean      hideHidden;
    private ArrayList<ChannelSftp.LsEntry> dirList;
    private ArrayList<Item> resList;
    private int          depth = 0, pathLen;
    private String       path, curPath;

    ListEngine( Handler h, SFTPAdapter a, SearchProps sq, String pass_back_on_done_ ) {
        super.setHandler( h );
        adapter = a;
        this.sq = sq;
        this.fp = a.getFilter();
        passBackOnDone = pass_back_on_done_;
    }
    public Item[] getItems() {
        return tmpItems;
    }       
    @Override
    public void run() {
        try {
            Log.i( TAG, "ListEngine started" );
            threadStartedAt = System.currentTimeMillis();
            Credentials crd = adapter.getCredentials();
            int cl_res = adapter.connectAndLogin();
            Log.d( TAG, "connectAndLogin() returned " + cl_res );
            String descr = adapter.getHost(), algrm = adapter.getAlgorithm(), fngpr = adapter.getFingerPrint();
            if( algrm != null || fngpr != null )
                descr += ",\n<small>\"" + algrm + " " + fngpr + "</small>";
            if( cl_res < 0 ) {
                if( cl_res == SFTPConnection.NO_LOGIN ) {
                    sendLoginReq( descr, crd, passBackOnDone );
                } else
                    sendProgress( null, Commander.OPERATION_FAILED, passBackOnDone );
                return;
            }
            if( cl_res == SFTPConnection.LOGGED_IN /*&& !adapter.isHostKnown()*/ ) {
                sendProgress( adapter.ctx.getString( Utils.RR.ftp_connected.r(), descr, crd.getUserName() ), Commander.OPERATION_STARTED );
            }
            sftp = adapter.getChannel();
            if( sftp == null )
                throw new Exception( "No SFTP channel" );

            Uri u = adapter.getUri();
            path = Utils.mbAddSl( u.getPath() );
            if( !Utils.str( path ) ) path = File.separator;
            pathLen = path.length();
            int mode = adapter.getMode();
            hideHidden    = ( mode & CommanderAdapter.MODE_HIDDEN ) == CommanderAdapter.HIDE_MODE;
            boolean dirsz = ( mode & CommanderAdapter.MODE_DIRSZ  ) == CommanderAdapter.SHOW_DIRSZ;
            resList = new ArrayList<Item>();
            if( sq == null ) {
                Log.d( TAG, "ls " + path );
                sftp.ls( path, this );
            } else {
                searchInDirectory( path );
            }
            int num = resList.size();
            tmpItems = new Item[num];
            resList.toArray( tmpItems );
            resList = null;
            if( dirsz ) {
                final double conv = 100. / num;
                sendProgress( "", 0 );
                for( int i = 0; i < num; i++ ) {
                    Item item = tmpItems[i];
                    if( item.dir ) {
                        sendProgress( item.name, (int)(i * conv) );
                        item.size = getSizes( path + item.name );
                    }
                    if( isStopReq() )
                        break;
                }
            }
            ItemComparator cmpr = new ItemComparator(
                         mode & CommanderAdapter.MODE_SORTING,
                        (mode & CommanderAdapter.MODE_CASE) != 0,
                        (mode & CommanderAdapter.MODE_SORT_DIR) == 0 );
            Arrays.sort( tmpItems, cmpr );
            sendProgress( null, Commander.OPERATION_COMPLETED, passBackOnDone );
            return;
        }
        catch( Throwable e ) {
            //class com.jcraft.jsch.JSchException
            Log.e( TAG, "Can't get the items list", e );
            Throwable cause = e.getCause();
            if( cause instanceof UnknownHostException ) {
                error( adapter.ctx.getString( R.string.unkhost, adapter.getHost() ) );
            } else {
                if( e.getCause() instanceof NoRouteToHostException )
                    e = e.getCause();
                String em = e.getMessage();
                if( em != null ) {
                    if( !"Auth cancel".equals( em ) ) {
                        int cp = em.indexOf( ':' );
                        if( cp >= 0 && ( em.contains( "jsch" ) || em.contains( "Exception" ) ) )
                            error( em.substring( cp + 1 ) );
                        else
                            error( em );
                    }
                }
            }
        }
        finally {
            if( sftp  != null ) sftp.disconnect();
            if( sftp2 != null ) sftp2.disconnect();
            super.run();
        }
        adapter.disconnect();
        sendProgress( super.errMsg, Commander.OPERATION_FAILED, passBackOnDone );
    }

    private long getSizes( String dir_path ) {
        Thread.yield();
        try {
            Vector<ChannelSftp.LsEntry> dir_entries = sftp.ls( dir_path );
            if( dir_entries == null )
                return 0;
            long count = 0;
            int i = 0, num = dir_entries.size();
            for( ChannelSftp.LsEntry e : dir_entries ) {
                if( isStopReq() )
                    return -1;
                SftpATTRS at = e.getAttrs();
                if( at.isDir() ) {
                    String name = e.getFilename();
                    if( ".".equals( name ) || "..".equals( name ) )
                        continue;
                    if( depth++ > 60 ) {
                        Log.e( TAG, "Too deep: " + dir_path );
                        error( adapter.ctx.getString( Utils.RR.too_deep_hierarchy.r() ) );
                    } else {
                        long sz = getSizes( Utils.mbAddSl( dir_path ) + name );
                        if( sz < 0 )
                            return -1;
                        count += sz;
                    }
                    depth--;
                } else {
                       count += at.getSize();
                }
            }
            return count;
        } catch( Exception e ) {
            Log.e( TAG, dir_path, e );
            return 0;
        }
    }

    private void searchInDirectory( String dir_path ) throws Exception {
        long cur_time = System.currentTimeMillis();
        if( cur_time - progress_last_sent > DELAY ) {
            progress_last_sent = cur_time;
            sendProgress( dir_path, progress = 0 );
        }
        dirList = new ArrayList<ChannelSftp.LsEntry>();
        curPath = Utils.mbAddSl( dir_path );
        sftp.ls( dir_path, this );
        int num = dirList.size();
        if( num == 0 ) {
            dirList = null;
            return;
        }
        ChannelSftp.LsEntry[] tmpArr = new ChannelSftp.LsEntry[num];
        dirList.toArray( tmpArr );
        dirList = null;
        final double conv = 100. / num;
        for( int i = 0; i < num; i++ ) {
            ChannelSftp.LsEntry e = tmpArr[i];
            if( isStopReq() ) return;
            String name = e.getFilename();
            if( ".".equals( name ) || "..".equals( name ) )
                continue;
            int np = (int)(i * conv);
            if( np - conv + 1 >= progress ) {
                cur_time = System.currentTimeMillis();
                if( sq.content != null || cur_time - progress_last_sent > DELAY ) {
                    progress_last_sent = cur_time;
                    sendProgress( dir_path, progress = np );
                }
            }
            if( depth++ > 30 ) {
                Log.e( TAG, "Too deep: " + dir_path );
                error( adapter.ctx.getString( Utils.RR.too_deep_hierarchy.r() ) );
            } else
                searchInDirectory( Utils.mbAddSl( dir_path ) + name );
            depth--;
        }
        sendProgress( dir_path, 100 );
        return;
    }

    protected final boolean toShow( ChannelSftp.LsEntry entry ) {
        String fn = entry.getFilename();
        if( Utils.str( fn ) ) {
            if( hideHidden && fn.charAt( 0 ) == '.' ) return false;
            if(  ".".equals( fn ) ||
                "..".equals( fn ) ) return false;
        }
        if( sq != null )
            return isMatched( entry );
        return true;
    }

    private final boolean isMatched( ChannelSftp.LsEntry entry ) {
        if( entry == null ) return false;
        try {
            SftpATTRS at = entry.getAttrs();
            boolean dir = at.isDir();
            if( dir ) {
                if( !sq.dirs  ) return false;
            } else {
                if( !sq.files ) return false;
            }
            long modified = at.getMTime() * 1000;
            if(  sq.mod_after != null && modified <  sq.mod_after.getTime() ) return false;
            if( sq.mod_before != null && modified > sq.mod_before.getTime() ) return false;
            long size = at.getSize();
            if( size < sq.larger_than || size > sq.smaller_than )
                return false;
            String name = entry.getFilename();
            if( !sq.match( name ) )
                return false;
            if( sq.content != null && !dir ) {
                String fpath = curPath + entry.getFilename();
                try( InputStream is = sftp.get( fpath ) ) {
                    if( !searchInContent( is, size, name, sq.content ) )
                        return false;
                }
                catch( Exception e ) {
                    Log.e( TAG, "File: " + name + ", str=" + sq.content, e );
                    return false;
                }

            }
            return true;
        }
        catch( Exception e ) {
            Log.e( TAG, entry.getLongname(), e );
        }
        return false;
    }

    // --- ChannelSftp.LsEntrySelector ---

    @Override
    public int select( ChannelSftp.LsEntry entry ) {
        String name = entry.getFilename();
        if( toShow( entry ) ) {
            String fpath = path + name;
            SftpATTRS attrs = entry.getAttrs();
            Item item = adapter.makeItem( name, attrs );
            if( attrs.isLink() ) {
                if( sftp2 == null )
                    sftp2 = adapter.getChannel();
                adapter.updateLinkItem( item, sftp2, fpath );
            }
            if( fp == null || fp.match( item ) ) {
                if( sq != null ) {
                    if( curPath.length() > pathLen ) {
                        String pfx = curPath.substring( pathLen );
                        item.setPrefix( pfx );
                        item.attr = pfx;
                    }
                    else
                        item.attr = "./";
                }
                resList.add( item );
            }
        }
        if( sq != null && !sq.olo && entry.getAttrs().isDir() ) {
            if( !( ".".equals( name ) || "..".equals( name ) ) )
                dirList.add( entry );
        }
        return stop ? BREAK : CONTINUE;
    }
}
