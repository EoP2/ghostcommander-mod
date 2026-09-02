package com.ghostsq.commander.adapters;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.text.format.Formatter;
import android.util.Log;

import com.ghostsq.commander.BuildConfig;
import com.ghostsq.commander.Commander;
import com.ghostsq.commander.R;
import com.ghostsq.commander.SearchProps;
import com.ghostsq.commander.utils.Utils;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.io.inputstream.ZipInputStream;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import net.lingala.zip4j.progress.ProgressMonitor;
import net.lingala.zip4j.util.Zip4jUtil;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

public final class ZipEngines {

    public static class ZipEngine extends Engine {

        protected ZipAdapter owner;
        protected ZipFile    zip;
        protected Context    ctx;

        public ZipEngine( ZipAdapter owner ) {
            this.owner = owner;
            ctx = owner.ctx;
            zip = owner.getZipFile();
        }

        protected ZipEngine( ZipAdapter owner, Handler h ) {
            this( owner );
            super.setHandler( h );
        }


        protected synchronized boolean waitCompletion() {
            return waitCompletion( 0 );
        }
        protected synchronized boolean waitCompletion( int op_res_id ) {
            try {
                ProgressMonitor pm = zip.getProgressMonitor();
                while( pm.getState() == ProgressMonitor.State.BUSY ) {
                    wait( 100 );
                    if( isStopReq() ) {
                        pm.setCancelAllTasks(true);
                        error( ctx.getString( R.string.canceled ) );
                        return false;
                    }
                    //Log.v( TAG, "Waiting " + pm.getFileName() + " %" + pm.getPercentDone() );
                    String rep_str;
                    if( op_res_id != 0 )
                        rep_str = ctx.getString( op_res_id, pm.getFileName() );
                    else
                        rep_str = pm.getFileName();
                    sendProgress( rep_str, pm.getPercentDone(), 0 );
                    wait( 500 );
                }
                ProgressMonitor.Result res = pm.getResult();
                if( res == ProgressMonitor.Result.SUCCESS )
                    return true;
                else if( res == ProgressMonitor.Result.ERROR ) {
                    Throwable e = pm.getException();
                    if( e != null ) {
                        String msg = e.getMessage();
                        if( msg != null )
                            error( msg.replaceAll("^.+:", "") );
                        Log.e( TAG, pm.getFileName(), e );
                    }
                    else
                        Log.e( TAG, "zip error" );
                }
            } catch( Exception e ) {
                Log.e( TAG, "zip exception!", e );
            }
            return false;
        }

        // see https://stackoverflow.com/questions/19244137/check-password-correction-in-zip-file-using-zip4j
        boolean isValid( FileHeader fh ) {
            try {
                ZipInputStream is = null;
                if( fh == null ) return false;
                is = zip.getInputStream( fh );
                byte[] b = new byte[4096];
                boolean ok = is.read(b) != -1;
                is.close( );
                return ok;
            } catch( Exception e ) {
                Log.w( TAG, fh.getFileName(), e );
            }
            return false;
        }
    }

    public static class EnumEngine extends ZipEngine {

        protected EnumEngine( ZipAdapter owner ) {
            super( owner );
        }

        protected EnumEngine( ZipAdapter owner, Handler h ) {
            super( owner, h );
        }

        protected final FileHeader[] GetFolderList( String fld_path ) {
            return GetFolderList( fld_path, false );
        }
        protected final FileHeader[] GetFolderList( String fld_path, boolean all_descendants ) {
            if( zip == null )
                return null;
            List<FileHeader> headers = null;
            try {
                headers = zip.getFileHeaders();
            } catch( ZipException e1 ) {
                Log.e( TAG, fld_path, e1 );
            }
            if( headers == null )
                return null;
            return GetFolderList( fld_path, headers, all_descendants );
        }

        private final FileHeader[] GetFolderList( String fld_path, List<FileHeader> headers, boolean all_descendants ) {
            if( headers == null ) return null;
            if( fld_path == null )
                fld_path = "";
            else if( fld_path.length() > 0 && fld_path.charAt( 0 ) == File.separatorChar )
                fld_path = fld_path.substring( 1 );
            int fld_path_len = fld_path.length();
            if( fld_path_len > 0 && fld_path.charAt( fld_path_len - 1 ) != File.separatorChar ) {
                fld_path = fld_path + File.separatorChar;
                fld_path_len++;
            }

            ArrayList<FileHeader> array = new ArrayList<FileHeader>();
            for( FileHeader e : headers ) {
                if( isStopReq() )
                    return null;
                if( e == null )
                    continue;
                String entry_name = owner.fixName( e );
                if( BuildConfig.DEBUG ) Log.v( TAG, "Found an Entry: " + entry_name );
                if( entry_name == null || fld_path.compareToIgnoreCase( entry_name ) == 0 )
                    continue;
                /*
                  There are at least two kinds of zips - with dedicated folder
                  entry and without one. The code below should process both. Do
                  not change unless you fully understand how it works.
                 */
                if( !fld_path.regionMatches( true, 0, entry_name, 0, fld_path_len ) )
                    continue;
                if( !all_descendants ) {
                    int sl_pos = entry_name.indexOf( File.separatorChar, fld_path_len );
                    if( sl_pos > 0 ) {
                        String sub_dir = entry_name.substring( fld_path_len, sl_pos + 1 );
                        int sub_dir_len = sub_dir.length();
                        boolean not_yet = true;
                        for( int i = 0; i < array.size(); i++ ) {
                            String a_name = owner.fixName( array.get( i ) );
                            if( a_name.regionMatches( fld_path_len, sub_dir, 0, sub_dir_len ) ) {
                                not_yet = false;
                                break;
                            }
                        }
                        if( not_yet ) { // a folder
                            FileHeader sur_fld = new FileHeader();
                            sur_fld.setFileName( entry_name.substring( 0, sl_pos + 1 ) );
                            sur_fld.setDirectory( true );
                            /*
                             * TODO byte[] eb = { 1, 2 }; sur_fld.setExtra( eb );
                             */
                            array.add( sur_fld );
                        }
                        continue;
                    }
                }
                array.add( e ); // a leaf
            }
            return array.toArray( new FileHeader[array.size()] );
        }
    }

    public static class ListEngine extends EnumEngine {
        private FileHeader[] items_tmp = null;
        private SearchProps  sq;
        private String       pass_back_on_done;

        ListEngine( ZipAdapter owner, SearchProps sq, Handler h, String pass_back_on_done_) {
            super( owner, h );
            this.sq = sq;
            pass_back_on_done = pass_back_on_done_;
        }

        public FileHeader[] getItems() {
            return items_tmp;
        }

        @Override
        public void run() {
            try {
                this.zip = owner.createZipFileInstance( owner.getUri() );
                owner.setZipFile( zip );

                if( zip.isEncrypted() && !owner.isPassword() ) {
                    sendLoginReq( owner.uri.toString(), owner.getCredentials(), pass_back_on_done, true );
/*
    GC gives impression to people who don't know better
    that FILENAMES & SIZES inside encrypted zip are also
    secret; in fact they aren't, and it can be dangerous to
    give such impression.
*/
//                    return;
                }
                if( zip != null ) {
                    //Log.d( TAG, "Valid? " + zip.isValidZipFile() );

                    String cur_path = null;
                    try {
                        cur_path = owner.getUri().getFragment();
                    } catch( NullPointerException e ) {
                        // it happens only when the Uri is built by Uri.Builder
                        Log.e( TAG, "uri.getFragment()", e );
                    }

                    List<FileHeader> headers = null;
                    headers = zip.getFileHeaders();
                    if( headers == null ) {
                        sendProgress( ctx.getString( R.string.cant_open ), Commander.OPERATION_FAILED, pass_back_on_done );
                        return;
                    }
                    if( owner.isPassword() && !owner.password_validated ) {
                        for( FileHeader fh : headers ) {
                            if( !fh.isDirectory() ) {
                                if( !isValid( fh ) ) {
                                    sendLoginReq( owner.uri.toString(), owner.getCredentials(), pass_back_on_done, true );
                                    return;
                                }
                            }
                            owner.password_validated = true;
                            break;
                        }
                    }

                    if( sq != null ) {
                        try {
                            items_tmp = GetFolderList( cur_path, true );
                            if( items_tmp != null ) {
                                ArrayList<FileHeader> matched = new ArrayList<FileHeader>();
                                for( FileHeader fh : items_tmp ) {
                                    if( match( fh ) )
                                        matched.add( fh );
                                }
                                items_tmp = new FileHeader[matched.size()];
                                matched.toArray( items_tmp );
                                sendProgress( null, Commander.OPERATION_COMPLETED, pass_back_on_done );
                                return;
                            }
                        } catch( Exception e ) {
                            Log.e( TAG, cur_path, e );
                        }
                        return;
                    }
                    items_tmp = GetFolderList( cur_path );
                    if( items_tmp != null ) {
                        ZipAdapter.ZipItemPropComparator comp = new ZipAdapter.ZipItemPropComparator( owner.mode & ZipAdapter.MODE_SORTING,
                                ( owner.mode & ZipAdapter.MODE_CASE ) != 0, owner.ascending );
                        Arrays.sort( items_tmp, comp );
                        sendProgress( null, Commander.OPERATION_COMPLETED, pass_back_on_done );
                        return;
                    }
                }
            } catch( Exception e ) {
                Log.e( TAG, owner.getUri().toString(), e );
                sendProgress( ctx.getString( R.string.error ), Commander.OPERATION_FAILED, pass_back_on_done );
                return;
            } finally {
                super.run();
            }
            sendProgress( ctx.getString( R.string.cant_open ), Commander.OPERATION_FAILED, pass_back_on_done );
        }

        private final boolean match( FileHeader fh ) {
            if( fh == null ) return false;
            try {
                boolean dir = fh.isDirectory();
                if( dir ) {
                    if( !sq.dirs  ) return false;
                } else {
                    if( !sq.files ) return false;
                }
                if( sq.mod_after != null || sq.mod_after != null ) {
                    long modified = Zip4jUtil.dosToExtendedEpochTme( fh.getLastModifiedTime() );
                    if( sq.mod_after  != null && modified < sq.mod_after.getTime()  ) return false;
                    if( sq.mod_before != null && modified > sq.mod_before.getTime() ) return false;
                }
                long size = fh.getUncompressedSize();
                if( size < sq.larger_than || size > sq.smaller_than )
                    return false;
                String name = fh.getFileName();
                if( !sq.match( name ) )
                    return false;
                if( sq.content != null && !"".equals( sq.content ) ) {
                    InputStream is = zip.getInputStream( fh );
                    if( is == null || !searchInContent( is, size, name, sq.content ) )
                        return false;
                }
                return true;
            }
            catch( Exception e ) {
                Log.e( TAG, fh.getFileName(), e );
            }
            return false;
        }
    }

    public static class CalcSizesEngine extends EnumEngine {
        private FileHeader[] mList = null;
        private long totalSize = 0, totalCompressed = 0;
        private int  dirs = 0;

        CalcSizesEngine( ZipAdapter owner, FileHeader[] list ) {
            super( owner );
            mList = list;
        }

        @Override
        public void run() {
            Context c = owner.ctx;
            sendProgress( c.getString( R.string.wait ), 0, 0 );
            int num = calcSizes( mList );
            StringBuilder result = new StringBuilder( 128 );
            if( mList.length == 1 ) {
                FileHeader f = mList[0];
                String name_fixed = owner.fixName( f );
                if( f.isDirectory() ) {
                    result.append( c.getString( R.string.sz_folder, name_fixed, num ) );
                    if( dirs > 0 )
                        result.append( c.getString( R.string.sz_dirnum, dirs, ( dirs > 1 ? c.getString( R.string.sz_dirsfx_p ) : c.getString( R.string.sz_dirsfx_s ) ) ) );
                }
                else
                    result.append( c.getString( R.string.sz_file, name_fixed ) );
            } else
                result.append( c.getString( R.string.sz_files, num ) );
            if( totalSize > 0 )
                result.append( c.getString( R.string.sz_Nbytes, Formatter.formatFileSize( c, totalSize ).trim() ) );
            if( totalSize > 1024 )
                result.append( c.getString( R.string.sz_bytes, totalSize ) );
            result.append( "\n<b>Compressed: </b>" );
            result.append( Formatter.formatFileSize( c, totalCompressed ).trim() );
            if( mList.length == 1 ) {
                FileHeader f = mList[0];
                String name_fixed = owner.fixName( f );
                result.append( c.getString( R.string.sz_lastmod ) );
                result.append( "&#xA0;" );
                String date_s = Utils.formatDate( new Date( Zip4jUtil.dosToExtendedEpochTme( f.getLastModifiedTime() ) ), c );
                result.append( date_s );
                if( !f.isDirectory() ) {
                    String ext  = Utils.getFileExt( name_fixed );
                    String mime = Utils.getMimeByExt( ext );
                    if( mime != null && !"*/*".equals( mime ) ) {
                        result.append( "\nMIME:&#xA0;" );
                        result.append( mime );
                    }
                    result.append( "\n<b>CRC32:</b> " );
                    result.append( Long.toHexString( f.getCrc() ) );
                    result.append( "\n\n" );
                    result.append( f.getFileComment() );
                }
            }
            sendReport( result.toString() );
        }

        private final int calcSizes( FileHeader[] list ) {
            int  counter = 0;
            try {
                for( int i = 0; i < list.length; i++ ) {
                    FileHeader f = list[i];
                    String name_fixed = owner.fixName( f );
                    if( f.isDirectory() ) {
                        dirs++;
                        FileHeader[] subItems = GetFolderList( name_fixed );
                        if( subItems == null ) {
                            error( "Failed to get the file list of the subfolder '" + name_fixed + "'.\n" );
                            break;
                        }
                        counter += calcSizes( subItems );
                    } else {
                        totalSize += f.getUncompressedSize();
                        totalCompressed += f.getCompressedSize();
                        counter++;
                    }
                }
            } catch( Exception e ) {
                Log.e( TAG, "", e );
                error( e.getMessage() );
            }
            return counter;
        }
    }

    public static class ExtractAllEngine extends ZipEngine {
        private File zipFile, destFolder;

        ExtractAllEngine( ZipAdapter owner, File zip_f, File dest ) {
            super( owner );
            zipFile = zip_f;
            destFolder = dest;
        }

        @Override
        public void run() {
            try {
                sendProgress( owner.ctx.getString( R.string.wait ), 0, 0 );
                synchronized( owner ) {
                    if( zip == null )
                        zip = new ZipFile( zipFile.getAbsolutePath() );
                    zip.setRunInThread( true );
                    zip.extractAll( destFolder.getAbsolutePath() );
                    waitCompletion( R.string.unpacking );
                    zip.setRunInThread( false );
                }
                if( !noErrors() ) {
                    sendResult( ctx.getString( R.string.failed ) );
                    return;
                }
                sendResult( ctx.getString( R.string.unpacked ) + " " + zipFile.getName() );
                SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences( ctx );
                MediaScanEngine mse = new MediaScanEngine( ctx, destFolder, sp.getBoolean( "scan_all", true ), true );
                mse.setHandler( thread_handler );
                mse.run();
            } catch( ZipException e ) {
                Log.e( TAG, "Can't extract " + zipFile.getAbsolutePath() );
            }
            super.run();
        }
    }

    public static class CopyFromEngine extends EnumEngine {
        private File dest_folder;
        private FileHeader[] mList = null;
        private String base_pfx;
        private int base_len;
        private ArrayList<String> to_scan;

        CopyFromEngine( ZipAdapter owner, FileHeader[] list, File dest, Engines.IReciever recipient_ ) {
            super( owner );
            recipient = recipient_; // member of a superclass
            mList = list;
            dest_folder = dest;
            try {
                base_pfx = owner.getUri().getFragment();
                if( base_pfx == null )
                    base_pfx = "";
                base_len = base_pfx.length();
                to_scan = new ArrayList<String>();
            } catch( NullPointerException e ) {
                Log.e( TAG, "", e );
            }
        }

        @Override
        public void run() {
            sendProgress( ctx.getString( R.string.wait ), 0, 0 );
            synchronized( owner ) {
                zip.setRunInThread( true );
                int total = copyFiles( mList, "" );
                zip.setRunInThread( false );
                if(  !noErrors() ) {
                    sendResult( ctx.getString( R.string.failed ) );
                    return;
                }
                if( recipient != null ) {
                    sendReceiveReq( dest_folder );
                    return;
                }
                sendResult( Utils.getOpReport( ctx, total, R.string.unpacked ) );
                if( to_scan != null && to_scan.size() > 0 ) {
                    String[] to_scan_a = new String[to_scan.size()];
                    to_scan.toArray( to_scan_a );
                    MediaScanEngine.scanMedia( ctx, to_scan_a );
                }
            }
            super.run();
        }

        
        // FIXME: folder could be ref-cycled which causes a stack overflow!!!! check the depth? or check a loop?
        
        private final int copyFiles( FileHeader[] list, String path ) {
            int counter = 0;
            try {
                long dir_size = 0, byte_count = 0;
                for( int i = 0; i < list.length; i++ ) {
                    FileHeader f = list[i];
                    if( !f.isDirectory() )
                        dir_size += f.getUncompressedSize();
                }
                double conv = 100. / (double)dir_size;
                for( int i = 0; i < list.length; i++ ) {
                    FileHeader entry = list[i];
                    if( entry == null )
                        continue;
                    String entry_name_fixed = owner.fixName( entry );
                    if( entry_name_fixed == null )
                        continue;
                    String file_path = path + new File( entry_name_fixed ).getName();
                    File dest_file = new File( dest_folder, file_path );
                    String rel_name = entry_name_fixed.substring( base_len );

                    if( entry.isDirectory() ) {
                        if( !dest_file.mkdir() ) {
                            if( !dest_file.exists() || !dest_file.isDirectory() ) {
                                error( "Can't create folder \"" + dest_file.getAbsolutePath() + "\"", true );
                                break;
                            }
                        }
                        FileHeader[] subItems = GetFolderList( entry_name_fixed );
                        if( subItems == null ) {
                            error( "Failed to get the file list of the subfolder '" + rel_name + "'.\n", true );
                            break;
                        }
                        counter += copyFiles( subItems, rel_name );
                        if( !noErrors() )
                            break;
                    } else {
                        if( dest_file.exists() ) {
                            int res = askOnFileExist( ctx.getString( R.string.file_exist, dest_file.getAbsolutePath() ), owner.commander );
                            if( res == Commander.ABORT )
                                break;
                            if( res == Commander.SKIP )
                                continue;
                            if( res == Commander.REPLACE ) {
                                if( !dest_file.delete() ) {
                                    error( ctx.getString( R.string.cant_del, dest_file.getAbsoluteFile() ) );
                                    break;
                                }
                            }
                        }
                        int n = 0;
                        int so_far = (int)( byte_count * conv );
                        int fnl = rel_name.length();
                        zip.extractFile( entry, dest_folder.getAbsolutePath(), rel_name );
                        if( !waitCompletion( R.string.unpacking ) ) {
                            File dest_f = new File( dest_folder.getAbsolutePath(), entry.getFileName() );
                            if( dest_f.exists() )
                                dest_f.delete();
                            return counter;
                        }
                        if( to_scan != null )
                            to_scan.add( dest_file.getAbsolutePath() );
                    }
                    Utils.setFullPermissions( dest_file );
                    long entry_time = entry.getLastModifiedTimeEpoch();
                    if( entry_time > 0 )
                        dest_file.setLastModified( entry_time );
                    if( stop || isInterrupted() ) {
                        error( ctx.getString( R.string.canceled ) );
                        break;
                    }
                    if( i >= list.length - 1 )
                        sendProgress( ctx.getString( R.string.unpacked_p, rel_name ), (int)( byte_count * conv ) );
                    counter++;
                }
            } catch( Exception e ) {
                Log.e( TAG, "copyFiles()", e );
                error( "Exception: " + e.getMessage() );
            }
            return counter;
        }
    }

    public static class DelEngine extends EnumEngine {
        private String[] flat_names_to_delete = null;
        private FileHeader[] list;

        DelEngine( ZipAdapter owner, FileHeader[] list ) {
            super( owner );
            this.list = list;
        }

        @Override
        public void run() {
            if( zip == null )
                return;
            sendProgress( owner.ctx.getString( R.string.wait ), 1, 1 );
            synchronized( owner ) {
                //???Init( null );
                try {
                    zip.setRunInThread( true );
                    int num_deleted = deleteFiles( list );
                    zip.setRunInThread( false );
                    zip = null;
                    sendResult( Utils.getOpReport( ctx, num_deleted, R.string.deleted ) );
                    return;
                } catch( Exception e ) {
                    error( e.getMessage() );
                }
                super.run();
            }
        }

        int tot_pp = 0;
        private int deleteFiles( FileHeader[] to_delete ) throws ZipException {
            int e_i = 0, tot_i = 0;
            for( FileHeader h : to_delete ) {
                int pp = e_i++ * 100 / to_delete.length;
                if( to_delete == list )
                    tot_pp = pp;
                sendProgress( h.getFileName(), tot_pp, pp );
                String fn = h.getFileName();
                if( !h.isDirectory() || zip.getFileHeader( fn ) != null ) {
                    zip.removeFile( h );
                    if( !waitCompletion( R.string.deleting ) ) {
                        Log.e( TAG, "Breaking on file " + fn );
                        break;
                    }
                    tot_i++;
                } else {
                    tot_i += deleteFiles( GetFolderList( fn ) );
                    if( !noErrors() )
                        break;
                }
            }
            return tot_i;
        }
    }

    public static class CopyToEngine extends ZipEngine {
        private File[] topList;
        private long totalSize = 0;
        private int basePathLen;
        private String destPath;
        private boolean newZip = false;
        private boolean move = false;
        private boolean del_src_dir = false;
        private String prep;

        /**
         * Add files to existing zip
         */
        CopyToEngine( ZipAdapter owner, File[] list, String dest_sub, int move_mode_ ) {
            super( owner );
            topList = list;
            if( dest_sub != null )
                destPath = dest_sub.endsWith( ZipAdapter.SLS ) ? dest_sub : dest_sub + ZipAdapter.SLS;
            else
                destPath = "";
            basePathLen = list.length > 0 ? list[0].getParent().length() + 1 : 0;
            move = ( move_mode_ & ZipAdapter.MODE_MOVE ) != 0;
            del_src_dir = ( move_mode_ & CommanderAdapter.MODE_DEL_SRC_DIR ) != 0;
        }

        @Override
        public void run() {
            int num_files = 0;
            try {
                if( topList.length == 0 ) return;
                sendProgress( prep, 1 );
                synchronized( owner ) {
                    //Init( null );
                    ArrayList<File> full_list = new ArrayList<File>( topList.length );
                    addToList( topList, full_list );
                    ZipParameters parameters = new ZipParameters();
                    parameters.setCompressionMethod( CompressionMethod.DEFLATE );
                    parameters.setCompressionLevel( CompressionLevel.MAXIMUM );
                    parameters.setDefaultFolderPath( topList[0].getParent() );
                    if( Utils.str( destPath ) && !"/".equals( destPath ) )
                        parameters.setRootFolderNameInZip( destPath );
                    if( owner.isPassword()) {
                        parameters.setEncryptFiles( true );
                        parameters.setEncryptionMethod( EncryptionMethod.AES );
                        parameters.setAesKeyStrength( AesKeyStrength.KEY_STRENGTH_256 );
                    }
                    if( owner.encoding != null ) {

                    }
                    if( zip == null )
                        zip = owner.createZipFileInstance( owner.uri );
                    zip.setRunInThread( true );
                    zip.addFiles( full_list, parameters );
                    if( !waitCompletion( R.string.packing ) ) {
                        sendResult( ctx.getString( R.string.failed ) );
                        return;
                    }
                    sendProgress( prep, 100 );
                    zip.setRunInThread( false );
                    num_files = full_list.size();
                    if( del_src_dir ) {
                        File src_dir = topList[0].getParentFile();
                        if( src_dir != null ) {
                            Utils.deleteDirContent( src_dir );
                            src_dir.delete();
                        }
                    }
                }
            } catch( Exception e ) {
                error( "Exception: " + e.getMessage() );
            }
            sendResult( Utils.getOpReport( ctx, num_files, R.string.packed ) );
            super.run();
        }

        // adds files to the global full_list, and returns the total size
        private final long addToList( File[] sub_list, ArrayList<File> full_list ) {
            long total_size = 0;
            try {
                for( int i = 0; i < sub_list.length; i++ ) {
                    if( stop || isInterrupted() ) {
                        error( "Canceled", true );
                        break;
                    }
                    File f = sub_list[i];
                    if( f != null && f.exists() ) {
                        if( f.isFile() ) {
                            total_size += f.length();
                            full_list.add( f );
                        } else if( f.isDirectory() ) {
                            long dir_sz = addToList( f.listFiles(), full_list );
                            if( errMsg != null )
                                break;
                            if( dir_sz == 0 )
                                full_list.add( f );
                            else
                                total_size += dir_sz;
                        }
                    }
                }
            } catch( Exception e ) {
                Log.e( TAG, "addToList()", e );
                error( "Exception: " + e.getMessage(), true );
            }
            return total_size;
        }
    }

    public static class RenameEngine extends ZipEngine {
        private FileHeader ren_entry;
        private String new_name;
        private boolean copy = false;

        RenameEngine( ZipAdapter owner, FileHeader ren_entry, String new_name, boolean copy ) {
            super( owner );
            this.ren_entry = ren_entry;
            this.new_name = new_name;
            this.copy = copy;
        }

        @Override
        public void run() {
            if( zip == null )
                return;
            sendProgress( owner.ctx.getString( R.string.wait ), 1, 1 );
            synchronized( owner ) {
                //Init( null );
                try {
                    String inner_path = owner.getUri().getFragment();
                    if( Utils.str( inner_path ) )
                        inner_path = Utils.mbAddSl( inner_path );
                    if( inner_path != null )
                        inner_path += new_name;
                    else
                        inner_path = new_name;
                    ZipParameters parameters = new ZipParameters();
                    parameters.setCompressionMethod( CompressionMethod.DEFLATE );
                    parameters.setCompressionLevel( CompressionLevel.MAXIMUM );
                    parameters.setFileNameInZip( inner_path );
                  //parameters.setSourceExternalStream( true );
                    ZipInputStream zis = zip.getInputStream( ren_entry );
                    if( zis == null ) {
                        Log.e( TAG, "Can't get input stream of " + ren_entry.getFileName() );
                        sendResult( ctx.getString( R.string.fail ) );
                        return;
                    }
                    zip.addStream( zis, parameters );
                    zip.removeFile( ren_entry );
                    if( waitCompletion() ) {
                        sendResult( ctx.getString( R.string.done ) );
                        return;
                    }
                } catch( Exception e ) {
                    error( e.getMessage() );
                } finally {
                    zip = null;
                }
                sendResult( ctx.getString( R.string.fail ) );
                super.run();
            }
        }
    }

}
