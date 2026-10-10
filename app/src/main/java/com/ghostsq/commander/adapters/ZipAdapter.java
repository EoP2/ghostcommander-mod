package com.ghostsq.commander.adapters;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.util.SparseBooleanArray;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.Panels;
import com.ghostsq.commander.R;
import com.ghostsq.commander.SearchProps;
import com.ghostsq.commander.adapters.Engines.IReciever;
import com.ghostsq.commander.utils.Credentials;
import com.ghostsq.commander.utils.Utils;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import net.lingala.zip4j.util.Zip4jUtil;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;

//import net.lingala.zip4j.util.Zip4jConstants;

public class ZipAdapter extends CommanderAdapterBase implements Engines.IReciever {
    public static final String TAG = "ZipAdapter";
    protected static final int BLOCK_SIZE = 100000;
    // Java compiler creates a thunk function to access to the private owner
    // class member from a subclass
    // to avoid that all the member accessible from the subclasses are public
    public  Uri uri = null;
    private ZipFile zip = null;
    public  FileHeader[] entries = null;
    public  boolean password_validated = false;
    private char[]  password = null;
    public  String  encoding = null;

    public ZipAdapter(Context ctx_) {
        super( ctx_ );
        parentLink = PLS;
    }

    @Override
    public String getScheme() {
        return "zip";
    }

    @Override
    public boolean hasFeature( Feature feature ) {
        switch( feature ) {
        case LOCAL:
        case REAL:
        case SZ:
        case SEARCH:
            return true;
        case F4:
            return false;
        default:
            return super.hasFeature( feature );
        }
    }

    @Override
    public void setCredentials( Credentials crd ) {
        try {
            String password_s = crd == null ? null : crd.getPassword();
            password = password_s != null ? password_s.toCharArray() : null;
            if( zip != null )
                zip.setPassword( password );
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
    }
    @Override
    public Credentials getCredentials() {
        return password != null ? new Credentials( null, new String( password ) ) : null;
    }

    public final boolean isPassword() {
        return password != null;
    }

    public final ZipFile createZipFileInstance( Uri u ) {
        try {
            if( u == null ) return null;
            String zip_path = u.getPath();
            if( zip_path == null ) return null;
            ZipFile zip_file = new ZipFile( zip_path );

            String enc = encoding;
            if( enc == null )
                enc = u.getQueryParameter( "e" );
            if( enc == null )
                enc = "UTF-8";
            zip_file.setCharset( Charset.forName(enc) );
            if( password != null )
                zip_file.setPassword( password );
            return zip_file;
        } catch( Exception e ) {
            Log.e( TAG, "Can't create zip file instance for " + u.toString(), e );
        }
        return null;
    }

    public synchronized final void setZipFile( ZipFile zf ) {
        this.zip = zf;
    }

    public synchronized final ZipFile getZipFile() {
        return this.zip;
    }
        
    @Override
    public boolean readSource( Uri tmp_uri, String pass_back_on_done ) {
        try {
            zip = null;
            if( tmp_uri != null )
                uri = tmp_uri;
            if( uri == null )
                return false;
            if( reader != null ) { // that's not good.
                if( reader.isAlive() ) {
                    commander.showInfo( ctx.getString( R.string.busy ) );
                    reader.interrupt();
                    Thread.sleep( 500 );
                    if( reader.isAlive() )
                        return false;
                }
            }
            Log.v( TAG, "reading " + uri );
            notify( Commander.OPERATION_STARTED );
            search = SearchProps.parseSearchQueryParams( ctx, uri );
            reader = new ZipEngines.ListEngine( this, search, readerHandler, pass_back_on_done );
            return commander.startEngine( reader );
        } catch( Exception e ) {
            commander.showError( "Exception: " + e );
            Log.e( TAG, String.valueOf( tmp_uri ), e );
        }
        notify( "Fail", Commander.OPERATION_FAILED );
        return false;
    }

    @Override
    protected void onReadComplete() {
        if( reader instanceof ZipEngines.ListEngine ) {
            ZipEngines.ListEngine list_engine = (ZipEngines.ListEngine)reader;
            FileHeader[] tmp_items = list_engine.getItems();
            if( tmp_items != null && ( mode & MODE_HIDDEN ) == HIDE_MODE ) {
                int cnt = 0;
                for( int i = 0; i < tmp_items.length; i++ )
                    if( tmp_items[i].getFileName().charAt( 0 ) != '.' )
                        cnt++;
                entries = new FileHeader[cnt];
                int j = 0;
                for( int i = 0; i < tmp_items.length; i++ )
                    if( tmp_items[i].getFileName().charAt( 0 ) != '.' )
                        entries[j++] = tmp_items[i];
            } else
                entries = tmp_items;
            numItems = entries != null ? entries.length + 1 : 1;
            notifyDataSetChanged();
        }
    }

    @Override
    public String toString() {
        return uri != null ? Uri.decode( uri.toString() ) : "";
    }

    /*
     * CommanderAdapter implementation
     */
    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void setUri( Uri uri_ ) {
        uri = uri_;
    }

    @Override
    public void reqItemsSize( SparseBooleanArray cis ) {
        FileHeader[] fhs = bitsToFileHeaders( cis );
        if( fhs == null ) return;
        notify( Commander.OPERATION_STARTED );
        commander.startEngine( new ZipEngines.CalcSizesEngine( this, fhs ) );
    }


    public boolean unpackZip( File zip_file ) { // to the same folder
        try {
            if( !checkReadyness() )
                return false;
            notify( Commander.OPERATION_STARTED );
            commander.startEngine( new ZipEngines.ExtractAllEngine( this, zip_file, zip_file.getParentFile() ) );
            return true;
        } catch( Exception e ) {
            notify( "Exception: " + e.getMessage(), Commander.OPERATION_FAILED );
        }
        return false;
    }

    @Override
    public boolean copyItems( SparseBooleanArray cis, CommanderAdapter to, boolean move ) {
        try {
            if( zip == null )
                throw new RuntimeException( "Invalid ZIP" );
            FileHeader[] subItems = bitsToFileHeaders( cis );
            if( subItems == null )
                throw new RuntimeException( "Nothing to extract" );
            if( !checkReadyness() )
                return false;
            Engines.IReciever recipient = null;
            File dest = null;
            if( to instanceof FSAdapter ) {
                dest = new File( to.toString() );
                if( !dest.exists() )
                    dest.mkdirs();
                if( !dest.isDirectory() )
                    throw new RuntimeException( ctx.getString( R.string.inv_dest ) );
            } else {
                dest = new File( createTempDir() );
                recipient = to.getReceiver();
            }
            notify( Commander.OPERATION_STARTED );
            commander.startEngine( new ZipEngines.CopyFromEngine( this, subItems, dest, recipient ) );
            return true;
        } catch( Exception e ) {
            commander.showError( "Exception: " + e.getMessage() );
        }
        return false;
    }


    @Override
    public boolean createFile( String name ) {
        try {
            File tmp_ctr = Utils.getTempDir( ctx );
            File tmp_file = new File( tmp_ctr, name );
            tmp_file.createNewFile();
            if( createItem( tmp_file, tmp_ctr.getAbsolutePath() ) ) {
                notifyRefr( name );
                tmp_file.deleteOnExit();
                return true;
            }
        } catch( IOException e ) {
            Log.e( TAG, "Can't create " + name, e );
        }
        notify( ctx.getString( R.string.cant_create, name, "" ), Commander.OPERATION_FAILED );
        return false;
    }

    @Override
    public void createFolder( String fld_name ) {
        File tmp_ctr = Utils.getTempDir( ctx );
        File tmp_fld = new File( tmp_ctr, fld_name );
        tmp_fld.mkdir();
        if( createItem( tmp_fld, tmp_ctr.getAbsolutePath() ) )
            notifyRefr( fld_name );
        else
            notify( ctx.getString( R.string.cant_md, fld_name ), Commander.OPERATION_FAILED );
        tmp_fld.deleteOnExit();
    }

    public boolean createItem( File item, String folder_path ) {
        try {
            ZipParameters parameters = new ZipParameters();
            parameters.setCompressionMethod( CompressionMethod.DEFLATE );
            parameters.setCompressionLevel( CompressionLevel.MAXIMUM );
            parameters.setDefaultFolderPath( folder_path );
            String dest_path = uri.getFragment();
            if( Utils.str( dest_path ) && !"/".equals( dest_path ) )
                parameters.setRootFolderNameInZip( dest_path );
            if( ZipAdapter.this.password != null ) {
                parameters.setEncryptFiles( true );
                parameters.setEncryptionMethod( EncryptionMethod.AES );
                parameters.setAesKeyStrength( AesKeyStrength.KEY_STRENGTH_256 );
//                parameters.setPassword( password.toCharArray() );
            }
            if( ZipAdapter.this.encoding != null ) {
            
            }
            if( ZipAdapter.this.zip == null )
                ZipAdapter.this.zip = createZipFileInstance( ZipAdapter.this.uri );
            ArrayList<File> f_list = new ArrayList<File>( 1 );
            f_list.add( item );
            zip.addFiles( f_list, parameters );
            return true;
        } catch( ZipException e ) {
            Log.e( TAG, "Creating folder " + item.getName(), e );
        }
        return false;
    }    
    
    @Override
    public boolean deleteItems( SparseBooleanArray cis ) {
        try {
            if( !checkReadyness() )
                return false;
            FileHeader[] to_delete = bitsToFileHeaders( cis );
            if( to_delete != null && zip != null && uri != null ) {
                notify( Commander.OPERATION_STARTED );
                commander.startEngine( new ZipEngines.DelEngine( this, to_delete ) );
                return true;
            }
        } catch( Exception e ) {
            Log.e( TAG, "deleteItems()", e );
        }
        notify( null, Commander.OPERATION_FAILED );
        return false;
    }


    @Override
    public Uri getItemUri( int position ) {
        if( uri == null )
            return null;
        if( entries == null || position > entries.length )
            return null;
        String enc_path = Utils.escapeName( fixName( entries[position - 1] ) );
        return uri.buildUpon().encodedFragment( enc_path ).build();
    }

    @Override
    public String getItemName( int position, boolean full ) {
        if( entries != null && position > 0 && position <= entries.length ) {
            if( full ) {
                if( uri != null ) {
                    Uri item_uri = getItemUri( position );
                    if( item_uri != null )
                        return item_uri.toString();
                }
            } else
                return new File( fixName( entries[position - 1] ) ).getName();
        }
        return null;
    }

    @Override
    public void openItem( int position ) {
        if( position == 0 ) { // ..
            if( uri == null )
                return;
            if( search != null ) {
                Uri u = uri.buildUpon().clearQuery().build();
                commander.Navigate( u, null, null );
                return;
            }
            String cur = null;
            try {
                cur = uri.getFragment();
            } catch( Exception e ) {
            }
            if( cur == null || cur.length() == 0 || ( cur.length() == 1 && cur.charAt( 0 ) == SLC ) ) {
                File zip_file = new File( uri.getPath() );
                String parent_dir = Utils.escapePath( zip_file.getParent() );
                commander.Navigate( Uri.parse( parent_dir != null ? parent_dir : Panels.DEFAULT_LOC ), null, zip_file.getName() );
            } else {
                File cur_f = new File( cur );
                String parent_dir = cur_f.getParent();
                commander.Navigate( uri.buildUpon().fragment( parent_dir != null ? parent_dir : "" ).build(), null, cur_f.getName() );
            }
            return;
        }
        if( entries == null || position < 0 || position > entries.length )
            return;
        FileHeader item = entries[position - 1];

        if( item.isDirectory() ) {
            commander.Navigate( uri.buildUpon().fragment( fixName( item ) ).build(), null, null );
        } else {
            commander.Open( uri.buildUpon().fragment( fixName( item ) ).build(), null );
        }
    }

    @Override
    public boolean receiveItems( String[] uris, int move_mode ) {
        try {
            if( !checkReadyness() )
                return false;
            if( uris == null || uris.length == 0 ) {
                notify( s( R.string.copy_err ), Commander.OPERATION_FAILED );
                return false;
            }
            File[] list = Utils.getListOfFiles( uris );
            if( list == null ) {
                notify( "Something wrong with the files", Commander.OPERATION_FAILED );
                return false;
            }
            notify( Commander.OPERATION_STARTED );

            entries = null;

            commander.startEngine( new ZipEngines.CopyToEngine( this, list, uri.getFragment(), move_mode ) );
            return true;
        } catch( Exception e ) {
            notify( "Exception: " + e.getMessage(), Commander.OPERATION_FAILED );
        }
        return false;
    }

    @Override
    public IReciever getReceiver() {
        return this;
    }
    
    public boolean prepNewZip( String zip_fn, String pw, String enc ) {
        if( !checkReadyness() )
            return false;
        Uri.Builder ub = new Uri.Builder();
        ub.scheme( getScheme() ).path( zip_fn );
        setUri( ub.build() );
        this.password = pw != null ? pw.toCharArray() : null;
        this.encoding = enc;
        return true;
    }

    @Override
    public boolean renameItem( int position, String new_name, boolean copy ) {
        FileHeader to_rename = entries[position - 1];
        if( to_rename != null && zip != null && Utils.str( new_name ) ) {
            notify( Commander.OPERATION_STARTED );
            Engine eng = new ZipEngines.RenameEngine( this, to_rename, new_name, copy );
            commander.startEngine( eng );
            return true;
        }
        return false;
    }


    @Override
    public void prepareToDestroy() {
        super.prepareToDestroy();
        entries = null;
    }

    /*
     * BaseAdapter implementation
     */

    @Override
    protected int getPredictedAttributesLength() {
        return 20;
    }

    @Override
    public Object getItem( int position ) {
        Item item = new Item();
        item.name = "";
        {
            if( position == 0 ) {
                item.name = parentLink;
            } else {
                if( entries != null && position > 0 && position <= entries.length ) {
                    FileHeader zip_entry = entries[position - 1];
                    item.dir = zip_entry.isDirectory();
                    String name = fixName( zip_entry );

                    int lsp = name.lastIndexOf( SLC, item.dir ? name.length() - 2 : name.length() );
                    item.name = lsp > 0 ? name.substring( lsp + 1 ) : name;
                    if( !item.dir )
                        item.size = zip_entry.getUncompressedSize();
                    long item_time = zip_entry.getLastModifiedTime();
                    item.date = item_time > 0 ? new Date( Zip4jUtil.dosToExtendedEpochTme( item_time ) ) : null;
                    if( search != null ) {
                        if( lsp > 0 )
                            item.attr = name.substring( 0, lsp + 1 );
                    } else
                        item.attr = zip_entry.getFileComment();
                }
            }
        }
        return item;
    }

    public final String fixName( FileHeader entry ) {

        try {
            return entry.getFileName();
        } catch( Exception e ) {
            e.printStackTrace();
        }
        return null;
    }

    private final FileHeader[] bitsToFileHeaders( SparseBooleanArray cis ) {
        try {
            int counter = 0;
            for( int i = 0; i < cis.size(); i++ )
                if( cis.valueAt( i ) )
                    counter++;
            FileHeader[] subItems = new FileHeader[counter];
            int j = 0;
            for( int i = 0; i < cis.size(); i++ )
                if( cis.valueAt( i ) )
                    subItems[j++] = entries[cis.keyAt( i ) - 1];
            return subItems;
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
        return null;
    }

    private final boolean checkReadyness() {
        // FIXME check that the zip file is processed by some other engine!!!!!!!!!!!!
        if( false /*How?*/ ) { 
            notify( ctx.getString( R.string.busy ), Commander.OPERATION_FAILED ); 
            return false; 
        }
        return true;
    }

    public static class ZipItemPropComparator implements Comparator<FileHeader> {
        int type;
        boolean case_ignore, ascending;

        public ZipItemPropComparator(int type_, boolean case_ignore_, boolean ascending_) {
            type = type_;
            case_ignore = case_ignore_;
            ascending = ascending_;
        }

        @Override
        public int compare( FileHeader f1, FileHeader f2 ) {
            boolean f1IsDir = f1.isDirectory();
            boolean f2IsDir = f2.isDirectory();
            if( f1IsDir != f2IsDir )
                return f1IsDir ? -1 : 1;
            int ext_cmp = 0;
            switch( type ) {
            case CommanderAdapter.SORT_EXT:
                ext_cmp = case_ignore ? Utils.getFileExt( f1.getFileName() ).compareToIgnoreCase(
                        Utils.getFileExt( f2.getFileName() ) ) : Utils.getFileExt( f1.getFileName() ).compareTo(
                        Utils.getFileExt( f2.getFileName() ) );
                break;
            case CommanderAdapter.SORT_SIZE:
                ext_cmp = f1.getUncompressedSize() - f2.getUncompressedSize() < 0 ? -1 : 1;
                break;
            case CommanderAdapter.SORT_DATE:
                ext_cmp = f1.getLastModifiedTime() - f2.getLastModifiedTime() < 0 ? -1 : 1;
                break;
            }
            if( ext_cmp == 0 )
                ext_cmp = case_ignore ? f1.getFileName().compareToIgnoreCase( f2.getFileName() ) : f1.getFileName().compareTo(
                        f2.getFileName() );
            return ascending ? ext_cmp : -ext_cmp;
        }
    }

    @Override
    protected void reSort() {
        if( entries == null )
            return;
        ZipItemPropComparator comp = new ZipItemPropComparator( mode & MODE_SORTING, ( mode & MODE_CASE ) != 0, ascending );
        Arrays.sort( entries, comp );
    }

    @Override
    public Item getItem( Uri u ) {
        try {
            String zip_path = u.getPath();
            if( zip_path == null )
                return null;
            if( zip == null )
                zip = createZipFileInstance( u );
            String entry_name = u.getFragment();
            if( entry_name != null ) {
                FileHeader zip_entry = zip.getFileHeader( entry_name );
                if( zip_entry != null ) {
                    String name = fixName( zip_entry );
                    Item item = new Item();
                    item.dir = zip_entry.isDirectory();
                    int lsp = name.lastIndexOf( SLC, item.dir ? name.length() - 2 : name.length() );
                    item.name = lsp > 0 ? name.substring( lsp + 1 ) : name;
                    item.size = zip_entry.getUncompressedSize();
                    long item_time = zip_entry.getLastModifiedTimeEpoch();
                    item.date = item_time > 0 ? new Date( item_time ) : null;
                    return item;
                }
            }
        } catch( Throwable e ) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public InputStream getContent( Uri u, long offset ) {
        try {
            String zip_path = u.getPath();
            if( zip_path == null )
                return null;
            String opened_zip_path = uri != null ? uri.getPath() : null;
            if( opened_zip_path == null || zip == null )
                zip = createZipFileInstance( u );
            else if( !zip_path.equalsIgnoreCase( opened_zip_path ) )
                return null; // do not want to reopen the current zip to
                             // something else!
            String entry_name = u.getFragment();
            if( entry_name != null ) {
                FileHeader cachedEntry = zip.getFileHeader( entry_name );
                if( cachedEntry != null ) {
                    InputStream is = zip.getInputStream( cachedEntry );
                    if( offset > 0 )
                        is.skip( offset );
                    return is;
                }
            }
        } catch( Throwable e ) {
            Log.e( TAG, "Uri:" + u, e );
        }
        return null;
    }

    @Override
    public void closeStream( Closeable is ) {
        if( is != null ) {
            try {
                is.close();
            } catch( IOException e ) {
                e.printStackTrace();
            }
        }
    }
}
