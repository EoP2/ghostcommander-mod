package com.ghostsq.commander.adapters;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.StatFs;
import android.preference.PreferenceManager;
import android.text.format.Formatter;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.ContextMenu;
import android.widget.AdapterView;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.R;
import com.ghostsq.commander.SearchProps;
import com.ghostsq.commander.adapters.Engines.IReciever;
import com.ghostsq.commander.adapters.FSEngines.AskEngine;
import com.ghostsq.commander.adapters.FSEngines.CopyEngine;
import com.ghostsq.commander.adapters.FSEngines.DeleteEngine;
import com.ghostsq.commander.adapters.FSEngines.ListEngine;
import com.ghostsq.commander.utils.ForwardCompat;
import com.ghostsq.commander.utils.Replacer;
import com.ghostsq.commander.utils.Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Date;
import java.util.Objects;

public class FSAdapter extends CommanderAdapterBase implements Engines.IReciever {
    private   final static String TAG = "FSAdapter";
    private   Uri        uri;
    protected FileItem[] items;
    
    ThumbnailsThread tht = null;
    
    public FSAdapter( Context ctx_ ) {
        super( ctx_ );
    }

    @Override
    public String getScheme() {
        return "";
    }

    @Override
    public String getDescription( int flags ) {
        if( uri == null ) return "";
        String path = uri.getPath();
        if( path == null )  return "";
        String info = getVolumeInfo( false );
        return info != null ? info : "";
    }

	public final String getVolumeInfo( boolean as_html ) {
         return getVolumeInfo( ctx, uri, as_html );
	}

	public static String getVolumeInfo( Context ctx, Uri u, boolean as_html ) {
        try {
            StatFs stat = new StatFs( u.getPath() );
            long block_size = stat.getBlockSizeLong();
            String res = ctx.getString( R.string.sz_total,
                    Formatter.formatFileSize( ctx, block_size * stat.getBlockCountLong() ),
                    Formatter.formatFileSize( ctx, block_size * stat.getAvailableBlocksLong() ) );
            if( !as_html ) {
                res = res.replaceAll( "(</?\\w+?>)", "" ).replace( "\n", ", " );
            }
            return res;
        } catch( Exception e ) {
            Log.e( TAG, "URI: " + u, e );
        }
        return null;
    }

    @Override
    public boolean hasFeature( Feature feature ) {
        switch( feature ) {
        case FS:
        case LOCAL:
        case REAL:
        case SF4:
        case MKZIP:
        case SEARCH:
        case SEND:
        case MULT_RENAME:
        case FILTER:
        case RECEIVER:
        case DIRSIZES:
        case BY_ACCESS_DATE:
            return true;
        case TOUCH:
            return android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
        default: return super.hasFeature( feature );
        }
    }
    
    @Override
    public String toString() {
        return uri != null ? uri.toString() : "";
    }

    public String getDir() {
        return uri.getPath();
    }

    /*
     * CommanderAdapter implementation
     */

    @Override
    public Uri getUri() {
        try {
            return uri;
        } catch( Exception e ) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public void setUri( Uri uri_ ) {
        String schm = uri_.getScheme();
        if( Utils.str( schm ) && !"file".equals( schm ) ) return;
        uri = uri_;
    }

    @Override
    public boolean readSource( Uri uri_, String pass_back_on_done ) {
    	try {
            if( uri_ != null )
                setUri( uri_ );
            if( uri == null ) {
                notify( s( R.string.inv_path ) + ": " + ( uri_ == null ? "null" : uri_.toString() ), Commander.OPERATION_FAILED );
                return false;
            }
            search = SearchProps.parseSearchQueryParams( ctx, uri );
            reader = new ListEngine( this, search, readerHandler, pass_back_on_done );
            return commander.startEngine( reader );
        } catch( Exception e ) {
            Log.e( TAG, "readSource() exception", e );
        } catch( OutOfMemoryError err ) {
            Log.e( TAG, "Out Of Memory", err );
            notify( s( R.string.oom_err ), Commander.OPERATION_FAILED );
		}
		return false;
    }

    @Override
    protected void onReadComplete() {
        if( !(reader instanceof ListEngine) ) return;
        ListEngine le = (ListEngine)reader;
        File act_dir = le.getDirFile();
        if( act_dir == null ) return;
        String act_path = act_dir.getAbsolutePath();
        if( !Utils.mbAddSl( uri.getPath() ).equals( Utils.mbAddSl( act_path ) ) ) {
            setUri( uri.buildUpon().path( act_path ).build() );
        }
        items = le.getFileItems();
        reSort( items );

        parentLink = act_dir.getParent() == null ? SLS : PLS;
        notifyDataSetChanged();
        startThumbnailCreation();
    }

    private static class ThumbnailsThreadHandler extends Handler {
        private FSAdapter owner;
        public ThumbnailsThreadHandler( FSAdapter owner ) {
            super( Objects.requireNonNull( Looper.myLooper() ) );
            this.owner = owner;
        }
        public void handleMessage( Message msg ) {
            owner.notifyDataSetChanged();
        }
    }

    protected void startThumbnailCreation() {
        if( thumbnail_size_perc > 0 ) {
            //Log.i( TAG, "thumbnails " + thumbnail_size_perc );
            if( tht != null )
                tht.interrupt();
            tht = new ThumbnailsThread( this, new ThumbnailsThreadHandler( this ), uri.getPath(), items );
            tht.start();
        }
    }

    @Override
    public void populateContextMenu( ContextMenu menu, AdapterView.AdapterContextMenuInfo acmi, int num ) {
        try {
            Item item = (Item)getItem( acmi.position );
            if( item.dir && num == 1 ) {
                String path = acmi.position == 0 ? uri.toString() : ( (FileItem)item ).f().getAbsolutePath();
                menu.add( CM_NAVIGATION, R.id.open_via_SAF, CM_NAVIGATION, R.string.saf );
            }
            if( acmi.position != 0 && !item.dir ) {
                String ext = Utils.getFileExt( item.name );
                if( ".zip".equals( ext ) ) {
                    menu.add( CM_OPERATION, R.id.open_zip, CM_OPERATION, R.string.open_as );
                    menu.add( CM_OPERATION, R.id.extract,  CM_OPERATION, R.string.extract_zip );
                } else
                if( ".apk".equals( ext ) ||
                   ".apks".equals( ext ) ||
                   ".xapk".equals( ext ) ||
                   ".xslx".equals( ext ) ||
                   ".docx".equals( ext ) ||
                   ".pptx".equals( ext ) ||
                    ".odg".equals( ext ) ||
                    ".odt".equals( ext ) ||
                    ".ods".equals( ext ) ||
                    ".jar".equals( ext ) ||
                    ".war".equals( ext ) ||
                    ".ear".equals( ext ) ||
                   ".epub".equals( ext ) ||
                    ".xpi".equals( ext ) ||
                    ".cbz".equals( ext ) ||
                    ".cbr".equals( ext ) ) {
                    menu.add( CM_OPERATION, R.id.open_zip, CM_OPERATION, R.string.open_zip );
                }
                if( ".apks".equals( ext ) ||
                    ".xapk".equals( ext ) )
                    menu.add( CM_SPECIAL, R.id.install, CM_SPECIAL, R.string.install );
            }
            if( item.dir && num == 1 ) {
                menu.add( CM_SPECIAL, R.id.rescan_dir, CM_SPECIAL, R.string.rescan );
            }
            super.populateContextMenu( menu, acmi, num );
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
    }

    @Override
    public void doIt( int command_id, SparseBooleanArray cis ) {
        boolean item0 = cis.get( 0 );
        FileItem[] list = bitsToFilesEx( cis );
        if ( !item0 && ( list == null || list.length == 0 ) ) return;
        if( R.id.rescan_dir == command_id ) {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences( ctx );
            File path_f = item0 ? new File( uri.toString() ) : list[0].f().getAbsoluteFile();
            MediaScanEngine mse = new MediaScanEngine( ctx, path_f, sp.getBoolean( "scan_all", true ), true );
            mse.setHandler( new SimpleHandler( commander ) );
            commander.startEngine( mse );
            return;
        }
    }

    @Override
    public void setTimestamp( long time, SparseBooleanArray cis ) {
        FileItem[] list = bitsToFilesEx( cis );
        boolean ok = true;
        for( FileItem f : list ) {
            try {
                f.f().setLastModified( time );
            } catch( Exception e ) {
                Log.e( TAG, f.name, e );
                ok = false;
            }
        }
        notify( s( R.string.done ), ok ? Commander.OPERATION_COMPLETED_REFRESH_REQUIRED : Commander.OPERATION_FAILED_REFRESH_REQUIRED );
    }

    @Override
    public void openItem( int position ) {
        if( position == 0 ) {
            if( search != null ) {
                Uri u = uri.buildUpon().clearQuery().build();
                commander.Navigate( u, null, null );
                return;
            }
            if( parentLink == SLS )
                commander.Navigate( Uri.parse( HomeAdapter.DEFAULT_LOC ), null, null );
            else {
                if( uri == null ) return;
                File cur_dir_file = new File( uri.getPath() );
                String parent_dir = cur_dir_file.getParent();
                commander.Navigate( Uri.parse( Utils.escapePath( parent_dir != null ? parent_dir : DEFAULT_DIR ) ), null,
                                    cur_dir_file.getName() );
            }
        }
        else {
            File file = items[position - 1].f();
            if( file == null ) return;
            Uri open_uri = Uri.parse( Utils.escapePath( file.getAbsolutePath() ) );
            if( file.isDirectory() )
                commander.Navigate( open_uri, null, null );
            else
                commander.Open( open_uri, null );
        }
    }

    @Override
    public Uri getItemUri( int position ) {
        try {
            String item_name = getItemName( position, true );
            if( item_name == null ) return null;
            return Uri.parse( Utils.escapePath( item_name ) );
        } catch( Exception e ) {
            Log.e( TAG, "No item in the position " + position, e );
        }
        return null;
    }
    @Override
    public String getItemName( int position, boolean full ) {
        if( position < 0 || items == null || position > items.length )
            return position == 0 ? parentLink : null;
        if( full ) {
            if( position == 0 ) {
                return uri != null ? ( new File( uri.getPath() ) ).getParent() : null;
            } else {
                File f = items[position - 1].f();
                return f != null ? f.getAbsolutePath() : null;
            }
        } else {
            if( position == 0 ) return parentLink;
            FileItem item = items[position - 1];
            String name = item.name;
            if( name != null && item.dir ) {
                return name.replace( "/", "" );
            } else
                return name;
        }
    }
	@Override
	public void reqItemsSize( SparseBooleanArray cis ) {
        try {
        	FileItem[] list = bitsToFilesEx( cis );
    		notify( Commander.OPERATION_STARTED );
    		commander.startEngine( new CalcSizesEngine( this, list ) );
		}
        catch(Exception e) {
		}
	}

	@Override
    public boolean renameItem( int position, String newName, boolean copy ) {
        if( position <= 0 || position > items.length )
            return false;
        try {
            if( copy ) {
                // newName could be just name
                notify( Commander.OPERATION_STARTED );
                File[] list = { items[position - 1].f() };
                String dest_name;
                if( newName.indexOf( SLC ) < 0 ) {
                    dest_name = uri.getPath();
                    if( dest_name.charAt( dest_name.length()-1 ) != SLC )
                        dest_name += SLS;
                    dest_name += newName;
                }
                else
                    dest_name = newName;
                commander.startEngine( new CopyEngine( this, list, dest_name, MODE_COPY, true ) );
                return true;
            }
            boolean ok = false;
            File f = items[position - 1].f();
            File new_file = new File( uri.getPath(), newName );
            if( new_file.exists() ) {
                if( f.equals( new_file ) ) {
                    commander.showError( s( R.string.rename_err ) );
                    return false;
                }
                String old_ap =        f.getAbsolutePath();
                String new_ap = new_file.getAbsolutePath();
                if( old_ap.equalsIgnoreCase( new_ap ) ) {
                    File tmp_file = new File( uri.getPath(), newName + "_TMP_" );
                    ok = f.renameTo( tmp_file );
                    ok = tmp_file.renameTo( new_file );
                } else {
                    AskEngine ae = new AskEngine( this, simpleHandler, ctx.getString( R.string.file_exist, newName ), f, new_file );
                    commander.startEngine( ae );
                    //commander.showError( s( R.string.rename_err ) );
                    return true;
                }
            }
            else 
                ok = f.renameTo( new_file );
            if( ok ) {
                notifyRefr( newName );
                if( new_file.isDirectory() ) {
                    SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences( ctx );
                    MediaScanEngine mse = new MediaScanEngine( ctx, new_file, sp.getBoolean( "scan_all", true ), true );
                    mse.start();
                } else {
                    String[] to_scan = new String[] { f.getAbsolutePath(), new_file.getAbsolutePath() };
                    MediaScanEngine.scanMedia( ctx, to_scan );
                }
            } else
                notify( s( R.string.error ), Commander.OPERATION_FAILED );
            return ok;
        }
        catch( SecurityException e ) {
            commander.showError( ctx.getString( R.string.sec_err, e.getMessage() ) );
            return false;
        }
    }

	class FSReplacer extends Replacer {
        public  String last_file_name = null;
        private File[] ff;
        FSReplacer( File[] ff ) {
            this.ff = ff;
        }
        protected int getNumberOfOriginalStrings() {
            return ff.length;
        }
        protected String getOriginalString( int i ) {
            return ff[i].getName();
        }
        protected void setReplacedString( int i, String replaced ) {
            File f = ff[i];
            if( f.getName().equals( replaced ) )
                return;
            File new_file = new File( uri.getPath(), replaced );
            if( !new_file.exists() ) {
                if( !f.renameTo( new_file ) ) {
                    String err_msg = FSAdapter.this.s( R.string.rename_err ) + " " + f.getName();
                    FSAdapter.this.notify( err_msg, Commander.OPERATION_FAILED );
                }
            }
            last_file_name = new_file.getName();
        }
    }	
	
    @Override
    public boolean renameItems( SparseBooleanArray cis, String pattern_str, String replace_to ) {
        FSReplacer r = new FSReplacer( bitsToFiles( cis ) );
        r.replace( pattern_str, replace_to );
        notifyRefr( r.last_file_name );
        return false;
    }
	
    @Override
    public Item getItem( Uri u ) {
        return FSAdapter.getItemByUri( u );
    }

    public static Item getItemByUri( Uri u ) {
        try {
            File f = new File( u.getPath() );
            if( f.exists() ) {
                Item item = new Item( f.getName() );
                item.size = f.length();
                item.date = new Date( f.lastModified() );
                item.dir = f.isDirectory();
                return item;
            }
        } catch( Throwable e ) {
            Log.e( TAG, u != null ? u.toString() : null, e );
        }
        return null;
    }

    @Override
    public InputStream getContent( Uri u, long skip ) {
        try {
            String path = u.getPath();
            File f = new File( path );
            if( f.exists() && f.isFile() ) {
                FileInputStream fis = new FileInputStream( f );
                if( skip > 0 )
                    fis.skip( skip );
                return fis;
            }
        } catch( Throwable e ) {
            Log.e( TAG, u != null ? u.toString() : null, e );
        }
        return null;
    }
    
    @Override
    public OutputStream saveContent( Uri u ) {
        if( u != null ) {
            File f = new File( u.getPath() );
            try {
                return new FileOutputStream( f );
            } catch( FileNotFoundException e ) {
                Log.e( TAG, u.getPath(), e );
            }
        }
        return null;
    }
    
	@Override
	public boolean createFile( String name ) {
		try {
		    String file_path;
            if( name.charAt( 0 ) != '/' )
                file_path = Utils.mbAddSl( uri.getPath() ) + name;
            else
                file_path = name;
			File f = new File( file_path );
			boolean ok = f.createNewFile();
			notify( null, ok ? Commander.OPERATION_COMPLETED_REFRESH_REQUIRED : Commander.OPERATION_FAILED );
			return ok;     
		} catch( Exception e ) {
		    commander.showError( ctx.getString( R.string.cant_create, name, e.getMessage() ) );
		}
		return false;
	}
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Override
    public void createFolder( String new_name ) {
        String expl = "";
        try {
            boolean ok = false;
            if( android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ) {
                 expl = ForwardCompat.createFolder( ctx, uri.getPath(), new_name );
                 ok = expl == null;
            } else
                ok = (new File( uri.getPath(), new_name )).mkdir();
            if( ok ) {
                notifyRefr( new_name );
                return;
            }
        } catch( Exception e ) {
            Log.e( TAG, "createFolder", e );
        }
        notify( ctx.getString( R.string.cant_md, new_name ) + ".\n" + expl, Commander.OPERATION_FAILED );
    }

    @Override
    public boolean deleteItems( SparseBooleanArray cis ) {
    	try {
        	FileItem[] list = bitsToFilesEx( cis );
        	if( list != null ) {
        		notify( Commander.OPERATION_STARTED );
        		commander.startEngine( new DeleteEngine( this, list ) );
        		return true;
        	}
		} catch( Exception e ) {
		    notify( e.getMessage(), Commander.OPERATION_FAILED );
		}
        return false;
    }


    @Override
    public boolean copyItems( SparseBooleanArray cis, CommanderAdapter to, boolean move ) {
        boolean ok = to.receiveItems( bitsToNames( cis ), move ? MODE_MOVE : MODE_COPY );
        if( !ok ) notify( Commander.OPERATION_FAILED );
        return ok;
    }

    @Override
    public boolean receiveItems( String[] uris, int move_mode ) {
    	try {
            if( uris == null || uris.length == 0 )
            	return false;
            File dest_file = new File( uri.getPath() );
            if( dest_file.exists() ) {
                if( !dest_file.isDirectory() )
                    return false;
            }
            else {
                if( !dest_file.mkdirs() )
                    return false;
            }
            File[] list = Utils.getListOfFiles( uris );
            if( list != null ) {
                notify( Commander.OPERATION_STARTED );
                commander.startEngine( new CopyEngine( this, list, uri.getPath(), move_mode, false ) );
	            return true;
            }
		} catch( Exception e ) {
		    e.printStackTrace();
		}
		return false;
    }
    @Override
	public void prepareToDestroy() {
        super.prepareToDestroy();
        if( tht != null ) {
            tht.interrupt();
            tht = null;
        }
		items = null;
	}

    private final FileItem[] bitsToFilesEx( SparseBooleanArray cis ) {
        try {
            int counter = 0;
            for( int i = 0; i < cis.size(); i++ )
                if( cis.valueAt( i ) && cis.keyAt( i ) > 0)
                    counter++;
            FileItem[] res = new FileItem[counter];
            int j = 0;
            for( int i = 0; i < cis.size(); i++ )
                if( cis.valueAt( i ) ) {
                    int k = cis.keyAt( i );
                    if( k > 0 )
                        res[j++] = items[ k - 1 ];
                }
            return res;
        } catch( Exception e ) {
            Log.e( TAG, "bitsToFilesEx()", e );
        }
        return null;
    }

    public final File[] bitsToFiles( SparseBooleanArray cis ) {
        try {
            int counter = 0;
            for( int i = 0; i < cis.size(); i++ )
                if( cis.valueAt( i ) && cis.keyAt( i ) > 0)
                    counter++;
            File[] res = new File[counter];
            int j = 0;
            for( int i = 0; i < cis.size(); i++ )
                if( cis.valueAt( i ) ) {
                    int k = cis.keyAt( i );
                    if( k > 0 )
                        res[j++] = items[ k - 1 ].f();
                }
            return res;
        } catch( Exception e ) {
            Log.e( TAG, "bitsToFiles()", e );
        }
        return null;
    }

    @Override
    protected int getPredictedAttributesLength() {
        return 10;   // "1024x1024"
    }
    
    /*
     *  ListAdapter implementation
     */

    @Override
    public int getCount() {
        if( items == null )
            return 1;
        return items.length + 1;
    }

    @Override
    public Object getItem( int position ) {
        Item item = null;
        if( position == 0 ) {
            item = new Item();
            item.name = parentLink;
            item.dir = true;
        }
        else {
            if( items != null && position <= items.length ) {
                synchronized( items ) {
                    try {
                        FileItem fi = items[position - 1];
                        if( fi.needAttrs )
                            ForwardCompat.fillFileItem( fi );
                        return fi;
                    } catch( Exception e ) {
                        Log.e( TAG, "getItem(" + position + ")", e );
                    }
                }
            }
            else {
                item = new Item();
                item.name = "???";
            }
        }
        return item;
    }

    @Override
    protected void reSort() {
        if( items == null ) return;
        synchronized( items ) {
            reSort( items );
        }
    }
    private void reSort( FileItem[] items_ ) {
        if( items_ == null ) return;
        ItemComparator comp = new FileItem.Comparator( mode & MODE_SORTING, (mode & MODE_CASE) != 0, ascending );
        Arrays.sort( items_, comp );
    }

    @Override
    public IReciever getReceiver() {
        return this;
    }   // deprecated

    @Override
    public IReceiver getReceiver( Uri dir )  {
        return new FSEngines.Receiver( dir );
    }
}
