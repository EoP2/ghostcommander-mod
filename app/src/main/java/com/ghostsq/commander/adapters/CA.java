package com.ghostsq.commander.adapters;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.R;
import com.ghostsq.commander.root.MountAdapter;
import com.ghostsq.commander.root.RootAdapter;
import com.ghostsq.commander.sftp.SFTPAdapter;
import com.ghostsq.commander.smb.SMBAdapter;
import com.ghostsq.commander.utils.ForwardCompat;
import com.ghostsq.commander.utils.Utils;

import java.io.File;
import java.lang.reflect.Method;

import dalvik.system.DexClassLoader;
import dalvik.system.PathClassLoader;

/**
 * <code>CA</code> class
 * @author Ghost Squared (ghost.sq2@gmail.com)
 * 
 * This class is a "database" of all implemented adapters.
 * It keeps all the properties, hashes etc, to let the right adapter be instantiated
 * 
 */
final public class CA {
    public static final String TAG = "CA";

    // URI schemes hash codes
     public static final int    home_schema_h =   "home".hashCode();
     public static final int     zip_schema_h =    "zip".hashCode();
     public static final int     ftp_schema_h =    "ftp".hashCode();
     public static final int    sftp_schema_h =   "sftp".hashCode();
     public static final int     smb_schema_h =    "smb".hashCode();
     public static final int    root_schema_h =   "root".hashCode();
     public static final int     mnt_schema_h =  "mount".hashCode();
     public static final int    apps_schema_h =   "apps".hashCode();
     public static final int    favs_schema_h =   "favs".hashCode();
     public static final int    file_schema_h =   "file".hashCode();
     public static final int      ms_schema_h =     "ms".hashCode();
     public static final int content_schema_h ="content".hashCode();
     public static final int     saf_schema_h =    "saf".hashCode();
     public static final int     dav_schema_h =  "https".hashCode();

    public final static boolean isLocal( String scheme ) {
        return scheme == null || scheme.isEmpty() || "file".equals( scheme );
    }

    public static CommanderAdapter CreateAdapter( Uri uri, Commander c ) {
        CommanderAdapter ca = CreateAdapterInstance( uri, c.getContext() );
        if( ca != null )
            ca.Init( c );
        return ca;
    }

    public static CommanderAdapter CreateAdapterInstance( Uri uri, Context c ) {
        return CreateAdapterInstance( uri, c, null );
    }

    /**
     * @param uri    - the URI to have access to
     * @param ctx      - current context
     * @param pcl    - has to be a PathClassLoader !
     * @return an instance of the adapter.
     *   ??? should it return FSAdapter or null on a failure???
     */
    public static CommanderAdapter CreateAdapterInstance( Uri uri, Context ctx, ClassLoader pcl ) {
        String scheme = uri.getScheme();
        if( !Utils.str( scheme ) ) return new FSAdapter( ctx );
        final int scheme_h = scheme.hashCode();
        if(   file_schema_h == scheme_h ) return new FSAdapter( ctx );
        if(   home_schema_h == scheme_h ) return new HomeAdapter( ctx );
        if(    zip_schema_h == scheme_h ) return new ZipAdapter( ctx );
        if(    ftp_schema_h == scheme_h ) return new FTPAdapter( ctx );
        if(   sftp_schema_h == scheme_h ) return new SFTPAdapter( ctx );
        if(    smb_schema_h == scheme_h ) return new SMBAdapter( ctx );
        if(   root_schema_h == scheme_h ) return new RootAdapter( ctx );
        if(    mnt_schema_h == scheme_h ) return new MountAdapter( ctx );
        if(   apps_schema_h == scheme_h ) return new AppsAdapter( ctx );
        if(   favs_schema_h == scheme_h ) return new FavsAdapter( ctx );
        if(     ms_schema_h == scheme_h ) return new MSAdapter( ctx );
        if(    saf_schema_h == scheme_h ) return new SAFAdapter( ctx );
        if(content_schema_h == scheme_h ) {
            if( SAFAdapter.isTreeUri( uri ) )
                return new SAFAdapter( ctx );
            else
                return new ContentAdapter( ctx );
        }
        CommanderAdapter ca = null;
        ca = CreateExternalAdapter( ctx, scheme, null, pcl );
        return ca == null ? new FSAdapter( ctx ) : ca;
    }

    public static String[] getInstalled( Context ctx ) {
        try {
            final String this_package = ctx.getPackageName();
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo( this_package, 0 );
            return pm.getPackagesForUid( ai.uid );
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
        return null;
    }

    public static String getPackageName( Context ctx, String scheme ) {
        String[] pkgs = getInstalled( ctx );
        for( String pkg : pkgs ) {
            if( pkg.indexOf( scheme ) > 0 ) {
                return pkg;
            }
        }
        return null;
    }

    /**
     * @param ctx - current context
     * @param scheme - the suffix of the plugin's package name
     * @param pkg_name if null, it searches one with the scheme as a substring
     * @return an instance of the adapter or null on failure
     */
//    @SuppressLint("NewApi")     // all of the sudden, lint considers the DexClassLoader.loadClass() as from a higher API, but according to the docs, the method belongs to API level 1
    private static CommanderAdapter CreateExternalAdapter( Context ctx, String scheme, String pkg_name, ClassLoader pcl ) {
        try {
            if( pkg_name == null ) {
                pkg_name = getPackageName( ctx, scheme );
            }
            if( pkg_name == null )
                return null;
            String cls_name = pkg_name + "." + scheme;
            Class<?> creator_class = null;
            File dex_f = null;
            if( true || android.os.Build.VERSION.SDK_INT < Build.VERSION_CODES.O ) {
                if( android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP )
                    dex_f = ForwardCompat.getCodeCacheDir( ctx );
                else
                    dex_f = ctx.getDir( scheme, Context.MODE_PRIVATE );
                if( dex_f == null || !dex_f.exists() ) {
                    Log.w( TAG, "app.data storage is not accessible, trying to use the SD card" );
                    File sd = Environment.getExternalStorageDirectory();
                    if( sd == null ) return null; // nowhere to store the dex :(
                    dex_f = new File( sd, "temp" );
                    if( !dex_f.exists() )
                        dex_f.mkdir();
                }
            }
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo( pkg_name, 0 );

            Log.i( TAG, scheme + " package is " + ai.sourceDir );

            if( pcl == null || !(pcl instanceof PathClassLoader) )
                pcl = ctx.getClass().getClassLoader();
            DexClassLoader cl = new DexClassLoader( ai.sourceDir, dex_f != null ? dex_f.getAbsolutePath() : null, null, pcl );
            //
            creator_class = cl.loadClass( cls_name );
            if( dex_f != null ) {
                try {
                    File[] list = dex_f.listFiles();
                    for( int i = 0; i < list.length; i++ )
                        list[i].delete();
                } catch( Exception e ) {
                    Log.w( TAG, "Can't remove the plugin's .dex: ", e );
                }
            }
            Log.i( TAG, "Class has been loaded " + creator_class );
            
            Method cim = creator_class.getMethod( "createInstance", Context.class );
            if( cim == null ) {
                Log.e( TAG, "Can't get the createInstance() static method of " + creator_class );
                return null;
            }
            Object ca_obj = cim.invoke( null, ctx );
            if( ca_obj instanceof CommanderAdapter )
                return (CommanderAdapter)ca_obj;
            Log.e( TAG, "Unknown object was created: " + ca_obj );
        }
        catch( Throwable e ) {
            Log.e( TAG, "This class can't be created: " + scheme, e );
        }
        return null;
    }    

    public static int getDrawableIconId( String scheme ) {
        if( !Utils.str( scheme ) ) return R.drawable.folder;
        final int scheme_h = scheme.hashCode();

        if(content_schema_h == scheme_h )  return R.drawable.saf;
        if(   home_schema_h == scheme_h )  return R.mipmap.icon;
        if(    zip_schema_h == scheme_h )  return R.drawable.zip;
        if(    ftp_schema_h == scheme_h )  return R.drawable.ftp;
        if(   sftp_schema_h == scheme_h )  return R.drawable.sftp;
        if(    smb_schema_h == scheme_h )  return R.drawable.smb;
        if(   root_schema_h == scheme_h )  return R.drawable.root;
        if(    mnt_schema_h == scheme_h )  return R.drawable.mount;
        if(   apps_schema_h == scheme_h )  return R.drawable.android;
        if(   favs_schema_h == scheme_h )  return R.drawable.favs;
        return R.drawable.folder;
    }

    public static Drawable getDrawable( Context ctx, String scheme ) {
        try {
            String pkg_name = getPackageName( ctx, scheme );
            if( pkg_name == null )
                return null;
            PackageManager  pm = ctx.getPackageManager();
            ApplicationInfo pai = pm.getApplicationInfo( pkg_name, 0 );
            return pai.logo == 0 ? null : pm.getApplicationLogo( pai );
        } catch( PackageManager.NameNotFoundException e ) {
            Log.e( TAG, scheme, e );
        }
        return null;
    }
}
