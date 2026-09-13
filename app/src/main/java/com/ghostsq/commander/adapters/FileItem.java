package com.ghostsq.commander.adapters;

import android.net.Uri;

import com.ghostsq.commander.utils.ForwardCompat;

import java.io.File;
import java.util.Date;

import static com.ghostsq.commander.adapters.CommanderAdapter.MODE_SORTING;
import static com.ghostsq.commander.adapters.CommanderAdapter.SORT_DATE;
import static com.ghostsq.commander.adapters.CommanderAdapter.SORT_SIZE;

public class FileItem extends CommanderAdapter.Item implements FSEngines.IFileItem {
    public boolean needAttrs = false;
    private Date access_date;

    public FileItem( File f ) {
        this( f, 0, false );
    }
    public FileItem( File f, int mode, boolean deferred ) {
        origin = f;
        name = f.getName();
        /*
        On newer devices it actually accesses the file system on every file property request
        An attempt is made to prevent reading the values which are not immediately used
         */
        if( deferred ) {
            dir  = f.isDirectory();
            int sm = mode & MODE_SORTING;
            if( sm == SORT_SIZE )
                size = f.length();
            else if( sm == SORT_DATE )
                getModifyDate();
            this.needAttrs = true;
            return;
        }
        dir  = f.isDirectory();
        if( !dir )
            size = f.length();
        long msFileDate = f.lastModified();
        if( msFileDate != 0 )
            date = new Date( msFileDate );
    }
    @Override
    public File f() {
        return origin != null ? (File)origin : null;
    }
    @Override
    public Uri getUri() {
        if( !(origin instanceof File) )
            return null;
        Uri.Builder ub = new Uri.Builder();
        return ub.scheme( "file" ).path( ( (File)origin).getAbsolutePath() ).build();
    }

    public long getSize() {
        if( size < 0 )
            size = f().length();
        return size;
    }

    public Date getModifyDate() {
        if( date == null ) {
            long msFileDate = f().lastModified();
            if( msFileDate != 0 )
                date = new Date( msFileDate );
        }
        return date;
    }

    public Date getAccessDate() {
        if( access_date == null )
            access_date = ForwardCompat.getFileTime( f(), 1 );
        return access_date;
    }

    public static class Comparator extends ItemComparator {
        boolean by_access_time;
        public Comparator( int type_, boolean case_ignore_, boolean ascending_ ) {
            super( type_ & MODE_SORTING, case_ignore_, ascending_);
            by_access_time = ( type_ & MODE_SORTING ) == CommanderAdapter.SORT_ACCD;
        }
        @Override
        public int compare( CommanderAdapter.Item f1, CommanderAdapter.Item f2 ) {
            if( f1.dir != f2.dir )
                return super.compare( f1, f2 );
            return compare( (FileItem)f1, (FileItem)f2 );
        }
        private int compare( FileItem f1, FileItem f2 ) {
            if( !by_access_time ) {
                if( type == CommanderAdapter.SORT_SIZE ) {
                    f1.getSize();
                    f2.getSize();
                } else
                if( type == SORT_DATE ) {
                    f1.getModifyDate();
                    f2.getModifyDate();
                }
                return super.compare( f1, f2 );
            }
            Date d1 = f1.getAccessDate();
            Date d2 = f2.getAccessDate();
            int ext_cmp;
        	if( d1 != null )
                ext_cmp = d2 == null ? 1 : d1.compareTo( d2 );
            else
                ext_cmp = d2 == null ? 0 : -1;
            return ascending ? ext_cmp : -ext_cmp;
        }
    }

}
