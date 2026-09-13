package com.ghostsq.commander.utils;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.VectorDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Parcelable;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.widget.Toast;

import com.ghostsq.commander.R;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.FileItem;
import com.ghostsq.commander.adapters.HomeAdapter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class ForwardCompat
{
    // to remove in the future. Left here to be compatible with old plugins
    public static void setFullPermissions( File file ) {
        file.setWritable( true, false );
        file.setReadable( true, false );
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static String readlink( String path ) {
        try {
            return Os.readlink( path );
        } catch( ErrnoException e ) {}
        return null;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static File getCodeCacheDir( Context ctx ) {
        return ctx.getCodeCacheDir();
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static String createFolder( Context ctx, String parent_dir, String new_dir ) {
        try {
            android.system.Os.mkdir( Utils.mbAddSl( parent_dir ) + new_dir, 0777 );
            return null;
        } catch( ErrnoException e ) {
            Log.e( "createFolder()", "errno", e );
            if( e.errno == OsConstants.EACCES || e.errno == OsConstants.EROFS )
                return ctx.getString( R.string.dest_wrtble, parent_dir );
            if( e.errno == OsConstants.EEXIST )
                return ctx.getString( R.string.file_exist, new_dir );
            return e.getLocalizedMessage();
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    public static boolean requestPermission( Activity act, String[] perms, int rpc ) {
        ArrayList<String> al = new ArrayList<String>( perms.length );
        for( int i = 0; i < perms.length; i++ ) {
            int cp = act.checkPermission( perms[i], android.os.Process.myPid(), android.os.Process.myUid() );
            if( cp != PackageManager.PERMISSION_GRANTED ) {
                Log.w( "requestPermission", "Was not granted: " + perms[i] );
                al.add( perms[i] );
            }
        }
        if( !al.isEmpty() ) {
            act.requestPermissions( al.toArray(perms), rpc );
            return false;
        }
        return true;
    }

    @TargetApi(Build.VERSION_CODES.M)
    public static Parcelable createIcon( Context ctx, int icon_res_id ) {
        return Icon.createWithResource( ctx, icon_res_id );
    }

    @TargetApi(Build.VERSION_CODES.M)
    public static Parcelable createIcon( Bitmap bmp ) {
        return Icon.createWithBitmap( bmp );
    }

    @TargetApi(Build.VERSION_CODES.N)
    public static ExifInterface getExifInterfaceFromStream( InputStream is ) {
        try {
            return new ExifInterface( is );
        } catch( IOException e ) {
            Log.e( "getExifI.F.S.()", "", e );
        }
        return null;
    }

    @TargetApi(Build.VERSION_CODES.N)
    public static File getVolumeDirectory( StorageVolume volume ) {
        try {
            Field f = StorageVolume.class.getDeclaredField( "mPath" );
            f.setAccessible( true );
            return (File)f.get( volume );
        } catch( Exception e ) {
            // This shouldn't fail, as mPath has been there in every version
            throw new RuntimeException( e );
        }
    }

    @TargetApi(Build.VERSION_CODES.N)
    public static void getVolumeItems( Context ctx, ArrayList<CommanderAdapter.Item> ia, ArrayList<String> ext_paths ) {
        StorageManager sm = ctx.getSystemService( StorageManager.class );
        List<StorageVolume> volumes = sm.getStorageVolumes();
        for( StorageVolume volume : volumes ) {
            String state = volume.getState();
            if( !Environment.MEDIA_MOUNTED.equalsIgnoreCase( state ) )
                continue;
            File path_file = getVolumeDirectory( volume );
            if( path_file == null )
                continue;
            String path = path_file.getAbsolutePath();
            String ext = null;
            int[] proto = null;
            if( volume.isRemovable() ) {
                proto = HomeAdapter.EXTERNAL;
                ext = path;
                ext_paths.add( path );
            } else {
                proto = HomeAdapter.LOCAL;
            }
            String title = Utils.isLanguageChanged() ? null : volume.getDescription( ctx );
            CommanderAdapter.Item item = HomeAdapter.makeItem( ctx, proto, "fs", Uri.parse( path ), title, ext );
            ia.add( item );
        }
    }

    @TargetApi(Build.VERSION_CODES.N)
    public static String getVolumeName( Context ctx, String path ) {
        Utils.changeLanguage( ctx );
        StorageManager sm = ctx.getSystemService( StorageManager.class );
        StorageVolume volume = sm.getStorageVolume( new File( path ) );
        return volume != null ? volume.getDescription( ctx ) : null;
    }

    @TargetApi(Build.VERSION_CODES.N)
    public static String getVolumeInfo( Context ctx, String path ) {
        StorageManager sm = ctx.getSystemService( StorageManager.class );
        StorageVolume volume = sm.getStorageVolume( new File( path ) );
        if( volume == null )
            return null;
        StringBuilder sb = new StringBuilder();
        sb.append( "<h3>" );
        sb.append( volume.getDescription( ctx ) );
        sb.append( "</h3>" );
        String uuid = volume.getUuid();
        if( uuid != null ) {
            sb.append( "<b>ID:</b> " );
            sb.append( uuid );
        }
        sb.append( "\n<b>State:</b> " );
        sb.append( volume.getState() );
        sb.append( "\n<b>Primary:</b> " );
        sb.append( volume.isPrimary()   ? ctx.getString( R.string.dialog_yes ) : ctx.getString( R.string.dialog_no ) );
        sb.append( "\n<b>Emulated:</b> " );
        sb.append( volume.isEmulated()  ? ctx.getString( R.string.dialog_yes ) : ctx.getString( R.string.dialog_no ) );
        sb.append( "\n<b>Removable:</b> " );
        sb.append( volume.isRemovable() ? ctx.getString( R.string.dialog_yes ) : ctx.getString( R.string.dialog_no ) );
        return sb.toString();
    }

    @TargetApi(Build.VERSION_CODES.N)
    public static int getMinSdk( ApplicationInfo ai ) {
        return ai.minSdkVersion;
    }

    @TargetApi(Build.VERSION_CODES.N)
    public static void setIcon( Context ctx, Notification.Builder nb ) {
        nb.setLargeIcon( Icon.createWithResource( ctx, R.mipmap.icon ) );
    }

    @TargetApi(Build.VERSION_CODES.O)
    public  static void createNotificationChannel( NotificationManager n_m, String c_id, String ch_name, String ch_descr, int ch_importance ) {
        NotificationChannel channel = new NotificationChannel( c_id, ch_name, ch_importance );
        channel.setDescription( ch_descr );
        if( ch_importance > NotificationManager.IMPORTANCE_DEFAULT )
            channel.setLockscreenVisibility( Notification.VISIBILITY_PUBLIC );
        n_m.createNotificationChannel( channel );
    }

    @TargetApi(Build.VERSION_CODES.O)
    public static Bitmap getBitmap( Drawable drawable ) {
        try {
            Drawable d2b = null;
            if( drawable instanceof BitmapDrawable ) {
                return ( (BitmapDrawable)drawable ).getBitmap();
            } else if( drawable instanceof VectorDrawable ) {
                d2b = drawable;
            } else if( drawable instanceof AdaptiveIconDrawable ) {
                Drawable backgroundDr = ( (AdaptiveIconDrawable)drawable ).getBackground();
                Drawable foregroundDr = ( (AdaptiveIconDrawable)drawable ).getForeground();
                Drawable[] drr = new Drawable[2];
                drr[0] = backgroundDr;
                drr[1] = foregroundDr;
                d2b = new LayerDrawable( drr );
            }
            int width  = d2b.getIntrinsicWidth();
            int height = d2b.getIntrinsicHeight();
            Bitmap bitmap = Bitmap.createBitmap( width, height, Bitmap.Config.ARGB_8888 );
            Canvas canvas = new Canvas( bitmap );
            d2b.setBounds( 0, 0, canvas.getWidth(), canvas.getHeight() );
            d2b.draw( canvas );
            return bitmap;
        } catch( Exception e ) {
            Log.e( "getAppIcon()", "", e );
        }
        return null;
    }
    
    @TargetApi(Build.VERSION_CODES.O)
    public static boolean makeShortcut( Context ctx, Intent shortcut_intent, String label, Parcelable icon_p ) {
        try {
            ShortcutManager sm = ctx.getSystemService( ShortcutManager.class );
            if( !sm.isRequestPinShortcutSupported() ) {
                Toast.makeText( ctx, "Your home screen application does not support the Pin Shortcut feature.", Toast.LENGTH_LONG ).show();
                Log.w( "makeShortcut()", "ShortcutManager.isRequestPinShortcutSupported() returned false" );
            }
            Icon icon = (Icon)icon_p;
            ShortcutInfo si = new ShortcutInfo.Builder( ctx, label )
                .setIntent( shortcut_intent )
                .setShortLabel( label )
                .setIcon( icon )
                .build();
            return sm.requestPinShortcut( si, null );
        } catch( Exception e ) {
            Log.e( "GC.ForwardCompat", shortcut_intent.getDataString(), e );
        }
        return false;
    }

    @TargetApi(Build.VERSION_CODES.O)
    public static Date getFileTime( File file, String what ) {
        try {
            if( file == null ) return null;
            FileTime ft = (FileTime)Files.getAttribute( file.toPath(), what );
            if( ft == null ) return null;
            return new Date( ft.toMillis() );
        } catch( IOException e ) {
            Log.e( "GC.ForwardCompat", file.toString(), e );
        }
        return null;
    }

    @TargetApi(Build.VERSION_CODES.O)
    public static Date getFileTime( File file, int what ) {
        try {
            if( file == null ) return null;
            BasicFileAttributes attrs = Files.readAttributes( file.toPath(), BasicFileAttributes.class );

            Log.d( "CT", "equal? " + attrs.creationTime().equals( attrs.lastModifiedTime() ) );

            FileTime ft = null;
            switch( what ) {
                case 0: ft = attrs.creationTime();     break;
                case 1: ft = attrs.lastAccessTime();   break;
                case 2: ft = attrs.lastModifiedTime(); break;
            }
            if( ft == null ) return null;
            return new Date( ft.toMillis() );
        } catch( IOException e ) {
            Log.e( "GC.ForwardCompat", file.toString(), e );
        }
        return null;
    }

    @TargetApi(Build.VERSION_CODES.O)
    public static boolean fillFileItem( FileItem fi ) {
        File file = fi.f();
        try {
            if( file == null ) return false;
            BasicFileAttributes attrs = Files.readAttributes( file.toPath(), BasicFileAttributes.class);
            fi.dir  = attrs.isDirectory();
            if( !fi.dir )
                fi.size = attrs.size();
            FileTime ft = attrs.lastModifiedTime();
            if( ft != null )
                fi.date = new Date( ft.toMillis() );
            fi.needAttrs = false;
            return true;
        } catch( IOException e ) {
            Log.e( "GC.ForwardCompat", file.toString(), e );
        }
        return false;
    }

    @TargetApi(Build.VERSION_CODES.O)
    public static Intent getAppNotificationsIntent( Context ctx ) {
        return new Intent( Settings.ACTION_APP_NOTIFICATION_SETTINGS )
            .addFlags( Intent.FLAG_ACTIVITY_NEW_TASK )
            .putExtra( Settings.EXTRA_APP_PACKAGE, ctx.getPackageName() );
    }

    @TargetApi(Build.VERSION_CODES.Q)
    public static Intent getDocTreeIntent( Context ctx, String doc_id ) {
        final String TAG = "getDocTreeIntent";
        StorageManager sm = (StorageManager)ctx.getSystemService( Context.STORAGE_SERVICE );
        Intent in = sm.getPrimaryStorageVolume().createOpenDocumentTreeIntent();

        Uri uri = in.getParcelableExtra( DocumentsContract.EXTRA_INITIAL_URI );
        if( uri == null )
            return null;
        uri = uri.buildUpon().path( null ).appendPath( "document" ).appendPath( doc_id ).build();
        in.putExtra( DocumentsContract.EXTRA_INITIAL_URI, uri );
        Log.d( TAG, "uri: " + uri.toString() );
        in.addFlags( Intent.FLAG_GRANT_READ_URI_PERMISSION
                   | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                   | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                   | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        return in;
    }
}
