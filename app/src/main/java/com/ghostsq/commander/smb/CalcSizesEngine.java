package com.ghostsq.commander.smb;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.text.format.DateFormat;
import android.text.format.Formatter;

import com.ghostsq.commander.R;
import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.Engine;
import com.ghostsq.commander.utils.Utils;

import java.util.Date;
import java.util.Locale;

import jcifs.CIFSContext;
import jcifs.netbios.NameServiceClientImpl;
import jcifs.netbios.NbtAddress;
import jcifs.netbios.UniAddress;
import jcifs.smb.SmbException;
import jcifs.smb.SmbFile;
import jcifs.util.Hexdump;

class CalcSizesEngine extends Engine {
    private SmbFile[] mList;
    private int       num = 0, dirs = 0, depth = 0;
    private Context   ctx;
    private CIFSContext cifs_context;
    private NameServiceClientImpl name_svc;

    CalcSizesEngine( Context ctx_, SmbFile[] list, CIFSContext cifs_context ) {
        ctx   = ctx_;
        mList = list;
        this.cifs_context = cifs_context;
    }

    private final void addMaster( StringBuffer result, String group_name, int type, String title ) {
        NbtAddress[] addrs;
        try {
            addrs = name_svc.getNbtAllByName( group_name, type, null, null );
            if( addrs != null && addrs.length > 0 ) {
                String mb_ip = addrs[0].getHostAddress();
                if( mb_ip != null ) {
                    addrs = name_svc.getNbtAllByAddress( mb_ip );
                    if( addrs != null ) 
                        for( int i = 0; i < addrs.length; i++ ) {
                            if( addrs[i].getNameType() == 00 && !addrs[i].isGroupAddress( cifs_context ) ) {
                                result.append( title )
                                      .append( addrs[0].getHostName() )
                                      .append( " (" + mb_ip + ")" );
                                break;
                            }
                        }
                }
            }
        }
        catch( Exception e ) {
            e.printStackTrace();
        }
    }
    
    @Override
    public void run() {
        try {
            sendProgress();
            name_svc = new NameServiceClientImpl( cifs_context );
            StringBuffer result = new StringBuffer();
            if( mList != null && mList.length > 0 ) {
                sendProgress();
                SmbFile smb_f = mList[0];
                int type = smb_f.getType();
                if( type == SmbFile.TYPE_WORKGROUP ) {
                    String group_name = smb_f.getServer();
                    addMaster( result, group_name, 0x1C, "\nDomain controller:\n\t" );
                    addMaster( result, group_name, 0x1B, "\nDomain Master Browser:\n\t" );
                    addMaster( result, group_name, 0x1D, "\nMaster Browser:\n\t" );
                    sendReport( result.toString() );
                    return;
                }
                if( type == SmbFile.TYPE_SERVER ) {
                    String name = smb_f.getServer(), ip = null;
                    UniAddress addr = name_svc.getByName( name );
                    if( addr != null )
                        ip = addr.getHostAddress();
                    if( ip == null )
                        ip = name;
                    NbtAddress[] addrs = name_svc.getNbtAllByAddress( ip );
                    if( addrs != null )
                        for( int i = 0; i < addrs.length; i++ ) {
                            int    name_type = addrs[i].getNameType();
                            String host_name = addrs[i].getHostName();
                            switch( name_type ) {
                            case 0x00:
                                if( addrs[i].isGroupAddress( cifs_context ) ) {
                                    result.append( "\n<00> Member of: " + host_name );
                                } else {
                                    if( addrs[i].isActive( cifs_context ) ) {
                                        result.append( "\nActive" );
                                        if( addrs[i].isPermanent( cifs_context ) )
                                            result.append( ", Permanent" );
                                    }
                                    result.append( "\n<00> Node: " + host_name + " (" + addrs[i].getHostAddress() + ")" );
                                    int node_type = addrs[i].getNodeType( cifs_context );
                                    switch( node_type ) {
                                    case NbtAddress.B_NODE:
                                        result.append( "\n Broadcasts (B) node type." );
                                        break;
                                    case NbtAddress.P_NODE:
                                        result.append( "\n Point-to-Point (P) node (uses the nameserver)" );
                                        break;
                                    case NbtAddress.M_NODE:
                                        result.append( "\n Broadcasts-then-nameserver (M) node" );
                                        break;
                                    case NbtAddress.H_NODE:
                                        result.append( "\n Hybrid (H) node (Nameserver-then-broadcast)" );
                                        break;
                                    }
                                    byte[] mac = addrs[i].getMacAddress( cifs_context );
                                    if( mac != null )
                                        result.append( "\n MAC: " ).append( Utils.toHexString( mac, ":" ) );
                                }
                                break;
                            case 0x03:
                                result.append( "\n<03> Messenger: " + host_name );
                                break;
                            case 0x20:
                                result.append( "\n<20> Fileserver: " + host_name );
                                break;
                            case 0x1B:
                                result.append( "\n<1B> Domain MB of: " + host_name );
                                break;
                            case 0x1C:
                                result.append( "\n<1C> Domain controller of: " + host_name );
                                break;
                            case 0x1D:
                                result.append( "\n<1B> Master Browser of: " + host_name );
                                break;
                            case 0x1E:
                                result.append( "\n<1E> Can be elected for: " + host_name );
                                break;
                            case 0x1F:
                                result.append( "\n<1F> NetDDE: " + host_name );
                                break;
                            default:
                                result.append( "\n<" )
                                      .append( Hexdump.toHexString( name_type, 2 ) )
                                      .append( "> : " )
                                      .append( host_name );
                            }
                        }
                    sendReport( result.toString() );
                    return;
                }
                long sum = getSizes( mList );
                if( sum < 0 ) {
                    sendProgress( "Interrupted", Commander.OPERATION_FAILED );
                    return;
                }
                if( mList.length == 1 ) {
                    SmbFile f = mList[0];
                    if( f.isDirectory() ) {
                        result.append( ctx.getString( Utils.RR.sz_folder.r(), f.getName(), num ) );
                        if( dirs > 0 )
                            result.append( ctx.getString( Utils.RR.sz_dirnum.r(), dirs, ( dirs > 1 ? ctx.getString( Utils.RR.sz_dirsfx_p.r() ) : ctx.getString( Utils.RR.sz_dirsfx_s.r() ) ) ) );
                    }
                    else
                        result.append( ctx.getString( Utils.RR.sz_file.r(), f.getName() ) );
                } else
                    result.append( ctx.getString( Utils.RR.sz_files.r(), num ) );
                if( sum > 0 )
                    result.append( ctx.getString( Utils.RR.sz_Nbytes.r(), Formatter.formatFileSize( ctx, sum ).trim() ) );
                if( sum > 1024 )
                    result.append( ctx.getString( Utils.RR.sz_bytes.r(), sum ) );
                if( mList.length == 1 ) {
                    long last_mod = mList[0].lastModified();
                    if( last_mod > 0 ) {
                        result.append( ctx.getString( Utils.RR.sz_lastmod.r() ) );
                        result.append( " " );
                        String date_s;
                        Date date = new Date( last_mod );
                        if( Locale.getDefault().getLanguage().compareTo( "en" ) != 0 ) {
                            java.text.DateFormat locale_date_format = DateFormat.getDateFormat( ctx );
                            java.text.DateFormat locale_time_format = DateFormat.getTimeFormat( ctx );
                            date_s = locale_date_format.format( date ) + " " + locale_time_format.format( date );
                        } else 
                            date_s = (String)DateFormat.format( "MMM dd yyyy hh:mm:ss", date );
                        result.append( date_s );
                    }
                }
                long totl = 0, free = 0;
                SmbFile shr_f = null;
                if( type == SmbFile.TYPE_SHARE )
                    shr_f = smb_f; 
                else {
                    String share_s  = smb_f.getShare();
                    String server_s = smb_f.getServer();
                    if( server_s != null && share_s != null )
                        shr_f = new SmbFile( "smb://" + server_s + "/" + share_s, cifs_context );
                }
                if( shr_f != null ) {
                    try {
                        free = shr_f.getDiskFreeSpace();
                        totl = shr_f.length();
                    } catch( SmbException e ) {
                        e.printStackTrace();
                    }
                }
                if( free > 0 && totl > 0 ) {
                    result.append( "\n\n" );
                    result.append( ctx.getString( Utils.RR.sz_total.r(), Formatter.formatFileSize( ctx, totl ),
                                                                         Formatter.formatFileSize( ctx, free ) ) );
                }
            }
            sendReport( result.toString() );
        } catch( Exception e ) {
            String err_msg = "Error";
            try {
                PackageManager pm = ctx.getPackageManager();
                Resources smb_res = pm.getResourcesForApplication( "com.ghostsq.commander.smb" );
                err_msg = smb_res.getString( R.string.connect_err, e.getMessage() );
            } catch( Exception e1 ) {
                e1.printStackTrace();
            }
            sendProgress( err_msg, Commander.OPERATION_FAILED );
        }
    }

    protected final long getSizes( SmbFile[] list ) throws Exception {
        long count = 0;
        for( int i = 0; i < list.length; i++ ) {
            if( isStopReq() ) return -1;
            SmbFile f = list[i];
            if( f.isDirectory() ) {
                dirs++;
                if( depth++ > 20 )
                    throw new Exception( ctx.getString( Utils.RR.too_deep_hierarchy.r() ) );
                SmbFile[] subfiles = f.listFiles();
                if( subfiles != null ) {
                    long sz = getSizes( subfiles );
                    if( sz < 0 ) return -1;
                    count += sz;
                }
                depth--;
            }
            else {
                num++;
                count += f.length();
            }
        }
        return count;
    }
}
