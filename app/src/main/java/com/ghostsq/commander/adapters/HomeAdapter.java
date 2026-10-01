package com.ghostsq.commander.adapters;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.ContextMenu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;

import com.ghostsq.commander.BuildConfig;
import com.ghostsq.commander.ColorsKeeper;
import com.ghostsq.commander.Commander;
import com.ghostsq.commander.R;
import com.ghostsq.commander.root.MountAdapter;
import com.ghostsq.commander.root.RootAdapter;
import com.ghostsq.commander.utils.ForwardCompat;
import com.ghostsq.commander.utils.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HomeAdapter extends CommanderAdapterBase {
    private final static String TAG = "HomeAdapter";
    private final static String PACKAGE_PREFIX = "com.ghostsq.commander";
    public static final String DEFAULT_LOC = "home:";
    private final static int FORGET_CMD = 4945, PREFS_CMD = 4342, OPEN_SAF = 9036;
    private boolean root = false;
    private static final int[] FAVS     = { R.string.favs,    R.string.favs_descr,    R.drawable.favs    };
    public  static final int[] LOCAL    = { R.string.local,   R.string.local_descr,   R.drawable.fs      };
    private static final int[] TEMP     = { R.string.temp,    R.string.temp_descr,    R.drawable.fs      };
    public  static final int[] EXTERNAL = { R.string.external,R.string.external_descr,R.drawable.usd     };
    private static final int[] MEDIA    = { R.string.media,   R.string.media_descr,   R.drawable.ms      };
    private static final int[] SAF      = { R.string.saf  ,   R.string.saf_descr,     R.drawable.saf     };
    private static final int[] FTP      = { R.string.ftp,     R.string.ftp_descr,     R.drawable.ftp     };
    private static final int[] SFTP     = { R.string.sftp,     R.string.sftp_descr,     R.drawable.sftp     };
    private static final int[] SMB      = { R.string.smb,     R.string.smb_descr,     R.drawable.smb     };
    private static final int[] PLUGINS  = { R.string.plugins, R.string.plugins_descr, R.drawable.plugins };
    private static final int[] ROOT     = { R.string.root,    R.string.root_descr,    R.drawable.root    };
    private static final int[] MOUNT    = { R.string.mount,   R.string.mount_descr,   R.drawable.mount   };
    private static final int[] APPS     = { R.string.apps,    R.string.apps_descr,    R.drawable.android };
    private static final int[] EXIT     = { R.string.exit,    R.string.exit_descr,    R.drawable.exit    };
    
    private Item[] items = null;

    private Item makeItem( int[] resources, String scheme ) {
        return HomeAdapter.makeItem( ctx, resources, scheme, null, null, null );
    }

    private Item makeItem( int[] resources, Uri u ) {
        return HomeAdapter.makeItem( ctx, resources, null, u, null, null );
    }

    private Item makeItem( int[] resources, String scheme, Uri u, String name_ext ) {
        return HomeAdapter.makeItem( ctx, resources, scheme, u, null, name_ext );
    }

    public static Item makeItem( Context ctx, int[] resources, String scheme, Uri u, String title, String name_ext ) {
        Item item = new Item();
//PP
        item.name = Utils.str(title) ? title : ctx.getString( resources[0] );
        if( Utils.str(name_ext) )
            item.name += ": " + name_ext;
        item.attr = ctx.getString( resources[1] );
        item.icon_id = resources[2];
        item.origin = scheme;
        item.uri = u;
        return item;
    }
    
    public HomeAdapter( Context ctx_ ) {
        super( ctx_, DETAILED_MODE | NARROW_MODE | SHOW_ATTR | ATTR_ONLY );
        setCount( getNumItems() );
    }

    @Override
    public String getScheme() {
        return "home";
    }

    @Override
    public int setMode( int mask, int val ) {
        if( ( mask & ( MODE_WIDTH | MODE_DETAILS | MODE_ATTR ) ) == 0 )
            super.setMode( mask, val );
        mode &= ~ICON_TINY;
        if( ( mask & MODE_ROOT ) != 0 ) {
            root = ( mode & MODE_ROOT ) != 0;
            setCount( getNumItems() );
            notifyDataSetChanged();
        }
        return mode;
    }    
    
    @Override
    public boolean hasFeature( Feature feature ) {
        switch( feature ) {
        case MOUNT:
            return true;
        case HOME:
        case SORTING:
        case SCROLL:
            return false;
        default: return super.hasFeature( feature );
        }
    }
    
    @Override
    public String toString() {
        return "home:";
    }
    /*
     * CommanderAdapter implementation
     */
    @Override
    public Uri getUri() {
        return Uri.parse( toString() );
    }
    @Override
    public void setUri( Uri uri ) {
    }
    
    @Override
    public boolean readSource( Uri tmp_uri, String pbod ) {
        try {
            String[] dirs = null;
            items = null;
            ArrayList<Item> ia = new ArrayList<Item>();
            Utils.changeLanguage( ctx );

            ia.add( makeItem( FAVS, Uri.parse( "favs:" ) ) );
            if( android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ) {
                ArrayList<String> ext_paths = new ArrayList<String>();
                ForwardCompat.getVolumeItems( ctx, ia, ext_paths );
                if( ext_paths.size() > 0 ) {
                    dirs = new String[ext_paths.size()];
                    ext_paths.toArray( dirs );
                }
            } else {
                File esd_f = Environment.getExternalStorageDirectory();
                String fs = esd_f.getAbsolutePath();
                if( esd_f.canRead() ) {
                    ia.add( makeItem( LOCAL, "fs", Uri.parse( fs ), null ) );
                } else {
                    File temp_dir = Utils.getTempDir( ctx );
                    ia.add( makeItem( TEMP, Uri.parse( temp_dir.getAbsolutePath() ) ) );
                }
                fs = Utils.mbAddSl( fs );
                dirs = getStorageDirs( ctx );
                if( dirs != null ) {
                    for( int i = 0; i < dirs.length; i++ ) {
                        String path = dirs[i];
                        if( !Utils.str( path ) || fs.equals( path ) ) continue;
                        File dir_file = new File( path );
                        if( !dir_file.canRead() ) continue;
                        Item item = makeItem( EXTERNAL, "fs", Uri.parse( path ), path );
                        ia.add( item );
                    }
                }
            }
            // SAF
            if( !"play".equals( BuildConfig.FLAVOR ) ) {
                Item item = makeItem( SAF, SAFAdapter.ORG_SCHEME );
                item.dir = true;
                if( SAFAdapter.hasPersisted( ctx ) )
                    item.uri = SAFAdapter.ENT_URI;
                ia.add( item );
            }
            
            ia.add( makeItem( FTP, Uri.parse( "ftp:" ) ) );
            ia.add( makeItem( SFTP,Uri.parse( "sftp:" ) ) );
            ia.add( makeItem( SMB, Uri.parse( "smb:" ) ) );

            Utils.changeLanguage( ctx );            

            String[] ghosts = CA.getInstalled( ctx );
            if( ghosts != null &&  ghosts.length > 1 ) {
                Arrays.sort( ghosts, ( i1, i2 ) -> {
                    if( i1.indexOf( "sftp"  ) > 0 ) return -1;
                    if( i1.indexOf( "samba" ) > 0 ) return i2.indexOf( "sftp" ) > 0 ? 1 : -1;
                    return 0;
                } );
                final String this_package = ctx.getPackageName();
                PackageManager  pm = ctx.getPackageManager();
                for( String pkgn : ghosts ) {
                    if( this_package.equals( pkgn ) ) continue;
                    Log.d( TAG, pkgn );
                    final int sfx_pos = pkgn.lastIndexOf( '.' ) + 1;
                    String pkg_sfx = pkgn.substring( sfx_pos );
                    if( "donate".equals( pkg_sfx ) )
                        continue;
                    if( "donate".equals( BuildConfig.FLAVOR ) && PACKAGE_PREFIX.equals( pkgn ) )
                        continue;
                    ApplicationInfo pai = null;
                    Resources pre = null;
                    try {
                        pai = pm.getApplicationInfo( pkgn, 0 );
                        pre = pm.getResourcesForApplication( pai );
                    } catch( Exception e ) {
                        continue; //  v4.4
                    }
                    if( "samba".equals( pkg_sfx ) || "smb".equals( pkg_sfx ) || "sftp".equals( pkg_sfx ) )
                        continue;
                    Item item = new Item();
                    ia.add( item );
                    if( "sftp".equals( pkg_sfx ) ) {
                        item.name = s( R.string.sftp );
                        item.attr = s( R.string.sftp_descr );
                        item.icon_id = R.drawable.sftp;
                        item.dir = true;
                        item.uri = Uri.parse( "sftp:" );
                    } else {                        
                        Utils.changeLanguage( ctx, pre );
                        if( pai.descriptionRes != 0 ) {
                            String descr = pre.getString( pai.descriptionRes );
                            if( Utils.str( descr ) ) {
                                int cp = descr.indexOf( ':' );
                                if( cp > 0 ) {
                                    item.name = descr.substring( 0, cp ); 
                                    item.attr = descr.substring( cp+1 ); 
                                }
                            }
                        }
                        if( !Utils.str( item.name ) )
                            item.name = pre.getString( pai.labelRes );
                        Drawable logo = getLogo( pm, pai );
                        item.setIcon( logo != null ? logo : pm.getApplicationIcon( pai ) );
                        item.dir = true;    // to be used in the "forget" feature
                        item.uri = Uri.parse( pkg_sfx + ":" );
                    }
                    item.origin = pkgn;

                }
            }
//            ia.add( makeItem( PLUGINS, "pl" ) );  // ?how to show only plugins, not other apps???
            if( root ) {
                ia.add( makeItem( ROOT,  Uri.parse(  RootAdapter.DEFAULT_LOC ) ) );
                ia.add( makeItem( MOUNT, Uri.parse( MountAdapter.DEFAULT_LOC ) ) );
                ia.add( makeItem( MEDIA, Uri.parse( MSAdapter.ORG_SCHEME + ":" ) ) );
            }
            ia.add( makeItem( APPS, Uri.parse( "apps:" ) ) );
            ia.add( makeItem( EXIT, "exit" ) );
            
            items = new Item[ia.size()];
            ia.toArray( items );
            setCount( getNumItems() );
            if( BuildConfig.DEBUG ) {
                for( Item item : items )
                    Log.d( TAG, "item:" + item + ", origin:" + item.origin + ", URI:" + item.uri );
            }
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        } 
        notify( pbod );
        return true;
    }

    public static Drawable getLogo( PackageManager pm, ApplicationInfo pai ) {
        return pai.logo == 0 ? null : pm.getApplicationLogo( pai );
    }
    
    @Override
    public void reqItemsSize( SparseBooleanArray cis ) {
        Item item = bitsToItem( cis );
        if( item == null )
            return;
        if( !(item.origin instanceof String) ) {
            notErr();
            return;
        }
        StringBuilder sb = new StringBuilder();
        String schema = (String)item.origin;
        if( "fs".equals( schema ) ) {
            String path = item.uri.toString();
            if( android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.N )
                sb.append( ForwardCompat.getVolumeInfo( ctx, path ) );
            sb.append( "\n<b>" );
            sb.append( ctx.getString( R.string.path ) );
            sb.append( "</b> " );
            sb.append( path );
            sb.append( "\n" );
            sb.append( FSAdapter.getVolumeInfo( ctx, item.uri, true ) );
            sb.append( "\n" );
            notify( sb.toString(), Commander.OPERATION_COMPLETED );
            return;
        }
        if( schema.startsWith( SAFAdapter.ORG_SCHEME ) ) {
/* TODO
            if( item.uri == null ) {
                if( SAFAdapter.ORG_SCHEME.equals( item.origin ) )
                    item.uri = SAFAdapter.loadURI( ctx );
                else
                    item.uri = SAFAdapter.makeUriFromUuid( ctx, item.origin.toString().substring( SAFAdapter.ORG_SCHEME.length() ) );
            }
            if( item.uri == null ) {
                notify( ctx.getString( R.string.saf_pick ), Commander.OPERATION_FAILED );
                return;
            }
            sb.append( "<b>URI:</b> <small>" );
            sb.append( item.uri.toString() );
            sb.append( "</small>\n" );
            sb.append( SAFAdapter.getVolumeInfo( ctx, item.uri, true ) );
            sb.append( "\n" );
            notify( sb.toString(), Commander.OPERATION_COMPLETED );
 */
            return;
        }
        notErr();
    }
    @Override
    public boolean copyItems( SparseBooleanArray cis, CommanderAdapter to, boolean move ) {
        return notErr();
    }
        
    @Override
    public boolean createFile( String fileURI ) {
        return notErr();
    }

    @Override
    public void createFolder( String new_name ) {
        notErr();
    }

    @Override
    public boolean deleteItems( SparseBooleanArray cis ) {
        return notErr();
    }
    
    @Override
    public void openItem( int position ) {
        Item item = (Item)getItem( position );
        if( item.uri != null ) {
            commander.Navigate( item.uri, null, null );
            return;
        }
        if( item.origin == null ) {
            Log.e( TAG, "Unknown item " + item );
            return;
        }
        if( SAFAdapter.ORG_SCHEME.equals( item.origin.toString() ) ) {
            commander.issue( SAFAdapter.getDocTreeIntent(), Commander.REQUEST_OPEN_DOCUMENT_TREE );
            return;
        }
        if( "pl".equals( item.origin ) ) {
            Intent i = new Intent( Intent.ACTION_VIEW );
            i.setData( Uri.parse( s( R.string.plugins_uri ) ) );
            commander.issue( i, 0 );
            return; 
        }
        if( "exit".equals( item.origin ) ) { 
            commander.dispatchCommand( R.id.exit ); 
            return; 
        }
        item.uri = Uri.parse( item.origin + ":" );
        commander.Navigate( item.uri, null, null );
    }

    @Override
    public boolean receiveItems( String[] full_names, int move_mode ) {
        return notErr();
    }
    
    @Override
    public boolean renameItem( int position, String newName, boolean c ) {
        return notErr();
    }

    private int getNumItems() {
        return items == null ? 0 : items.length;
    }
   
    @Override
    public String getItemName( int position, boolean full ) {
        return items == null || position >= items.length ? "" : items[position].name;
    }
    
    /*
     * BaseAdapter implementation
     */
    @Override
    public Object getItem( int position ) {
        return items[position];
    }
    @Override
    public View getView( int position, View convertView, ViewGroup parent ) {
        Item item = (Item)getItem( position );
        if( item == null ) return null;
        return getView( convertView, parent, item );
    }

    @Override
    public void populateContextMenu( ContextMenu menu, AdapterView.AdapterContextMenuInfo acmi, int num ) {
        Item item = (Item)getItem( acmi.position );
        if( item == null  ) return;
        String scheme = item.origin instanceof String ? (String)item.origin : item.uri != null ? item.uri.getScheme() : null;
        if( !Utils.str( scheme ) ) return;
        if( scheme.startsWith( PACKAGE_PREFIX ) )
            scheme = scheme.substring( PACKAGE_PREFIX.length() + 1 );

        if( scheme.startsWith( MSAdapter.ORG_SCHEME ) ) {
                 MSAdapter.populateHomeContextMenu( ctx, menu );
            ContentAdapter.populateHomeContextMenu( ctx, menu );
            return;
        }
        if( "fs".equals( scheme ) ) {
            menu.add( 0, R.id.szt,   0, R.string.show_size );
            return;
        }
        if( scheme.startsWith( SAFAdapter.ORG_SCHEME ) ) {
            menu.add( 0, R.id.szt,   0, R.string.show_size );
            return;
        }
        if( scheme.startsWith( "smb" ) ) {
            menu.add( 0, PREFS_CMD, 0, R.string.prefs );
            return;
        }
        if( scheme.startsWith( "sftp" ) ) {
            menu.add( 0, PREFS_CMD, 0, R.string.prefs );
            return;
        }
        if( item.dir ) {
            File plugin_prefs_f = getPluginPrefsFile( item.uri.getScheme() );
            if( plugin_prefs_f.exists() )
                menu.add( 0, FORGET_CMD, 0, R.string.forget );
            String package_name = (String)item.origin;
            Intent intent = getPluginPrefIntent( package_name );
            PackageManager pm = ctx.getPackageManager();
            List<ResolveInfo> list = pm.queryIntentActivities( intent, PackageManager.MATCH_DEFAULT_ONLY );
            if( list.size() > 0 )
                menu.add( 0, PREFS_CMD, 0, R.string.prefs );
            return;
        }
        if( RootAdapter.DEFAULT_LOC.startsWith( scheme ) )
            RootAdapter.populateHomeContextMenu( ctx, menu );
    }

    private final File getPluginPrefsFile( String schema ) {
        File shared_prefs_f = new File( ctx.getFilesDir().getParentFile(), "shared_prefs" );
        File plugin_prefs_f = new File( shared_prefs_f, schema + ".xml" );
        return plugin_prefs_f;
    }
    
    private final Intent getPluginPrefIntent( String package_n ) {
        String class_n    = package_n + ".Prefs";
        Intent intent = new Intent();
        intent.setComponent( new ComponentName( package_n, class_n ) );
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences( ctx );
        intent.putExtra( "theme", ColorsKeeper.getTheme( ctx ) );
        intent.putExtra( "language", sp.getString( "language", "" ) );
        return intent;
    }        
   
    @Override
    public void doIt( int command_id, SparseBooleanArray cis ) {
        try {
            Item item = bitsToItem( cis );
            if( item == null  ) return;
            String scheme = item.origin instanceof String ? (String)item.origin : item.uri != null ? item.uri.getScheme() : null;
            if( !Utils.str( scheme ) ) return;
            if( scheme.startsWith( PACKAGE_PREFIX ) )
                scheme = scheme.substring( PACKAGE_PREFIX.length() + 1 );
            if( scheme.startsWith( MSAdapter.ORG_SCHEME ) ) {
                Uri u = MSAdapter.getUri( command_id );
                if( u != null ) {
                    commander.Navigate( u, null, null );
                    return;
                }
                Uri content_uri = ContentAdapter.getBaseUri( command_id );
                if( content_uri != null ) {
                    commander.Navigate( content_uri, null, null );
                    return;
                }
            }
            if( item.dir ) {    // a plugin
                if( FORGET_CMD == command_id ) {
                    if( "gdrive".equals( scheme ) ) {
                        forgetGDrive();
                        return;
                    }
                    File plugin_prefs_f = getPluginPrefsFile( scheme );
                    if( plugin_prefs_f.exists() ) {
                        plugin_prefs_f.delete();
                        commander.dispatchCommand( R.id.exit );
                    }
                    return;
                }
                if( PREFS_CMD == command_id ) {
                    String package_name = (String)item.origin;
                    Intent intent = getPluginPrefIntent( package_name );
                    Log.d( TAG, "Starting Activity" );
                    commander.issue( intent, 0 );
                    return;
                }
            }
            if( PREFS_CMD == command_id && scheme.startsWith( "smb" ) ) {
                Intent intent = new Intent();
                intent.setClass( ctx, com.ghostsq.commander.smb.Prefs.class );
                SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences( ctx );
                intent.putExtra( "theme", ColorsKeeper.getTheme( ctx ) );
                intent.putExtra( "language", sp.getString( "language", "" ) );
                commander.issue( intent, 0 );
                return;
            }
            if( PREFS_CMD == command_id && scheme.startsWith( "sftp" ) ) {
                Intent intent = new Intent();
                intent.setClass( ctx, com.ghostsq.commander.sftp.Prefs.class );
                SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences( ctx );
                intent.putExtra( "theme", ColorsKeeper.getTheme( ctx ) );
                intent.putExtra( "language", sp.getString( "language", "" ) );
                commander.issue( intent, 0 );
                return;
            }
            if( RootAdapter.DEFAULT_LOC.startsWith( scheme ) ) {
                RootAdapter ra = new RootAdapter( ctx );
                ra.Init( commander );
                ra.doIt( command_id, cis );
            }
            if( SAFAdapter.ORG_SCHEME.startsWith( scheme ) && OPEN_SAF == command_id ) {
                if( SAFAdapter.ORG_SCHEME.equals( scheme ) )
                    SAFAdapter.setPickingGenericModeOn();
                commander.issue( SAFAdapter.getDocTreeIntent(), Commander.REQUEST_OPEN_DOCUMENT_TREE );
                return;
            }
        } catch( Exception e ) {
            Log.e( TAG, "" + command_id, e );
            notify( e.getLocalizedMessage(), Commander.OPERATION_FAILED );
        }
    }

    // FIXME move to an adapter's interface!
    private void forgetGDrive() {
        SharedPreferences sp = ctx.getSharedPreferences( "gdrive", Activity.MODE_PRIVATE );
        SharedPreferences.Editor spe = sp.edit();
        spe.remove( "account" );
        spe.remove( "acctype" );
        spe.commit();
    }

    private final Item bitsToItem( SparseBooleanArray cis ) {
        try {
            for( int i = 0; i < cis.size(); i++ )
                if( cis.valueAt( i ) )
                    return items[ cis.keyAt( i ) ];
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
        return null;
    }

    private static String[] getStorageDirs( Context ctx ) {
        List<String> sdal = new ArrayList<>();
        File[] ff = ctx.getExternalFilesDirs( "external" );
        if( ff != null ) {
            for( int i = 0; i < ff.length; i++ ) {
                if( ff[i] == null ) continue;
                String path = ff[i].getAbsolutePath();
                if( path == null ) continue;
                Log.d( "getStorageDirs", path );
                int pos = path.indexOf( "Android" );
                if( pos < 0 ) {
                    Log.e( "getStorageDirs", "Unknown path " + path );
                    continue;
                }
                sdal.add( path.substring( 0, pos ) );
            }
        }
        File parent = new File( "/storage" );
        try {
            File[] subs = parent.listFiles();
            for( File f : subs ) {
                try {
                    if( f.exists() && f.getName().toLowerCase().contains( "usb" ) && f.canExecute() )
                        sdal.add( f.getAbsolutePath() );
                } catch( Exception e ) {
                    Log.e( TAG, f.getName(), e );
                }
            }
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
        String[] res = new String[sdal.size()];
        sdal.toArray( res );
        return res;
    }
}
