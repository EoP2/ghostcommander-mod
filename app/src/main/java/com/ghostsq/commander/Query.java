package com.ghostsq.commander;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.format.DateFormat;
import android.util.Log;

import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.utils.Utils;

import java.util.Date;

public class Query implements Parcelable {
    protected final String TAG = getClass().getSimpleName();
    public String  file_mask;
    public boolean dirs, files;
    public long    larger_than, smaller_than = Long.MAX_VALUE; 
    public Date    mod_after, mod_before;
    
    protected String[] cards;
    protected boolean cs = false;

    public Query() {}

    public Query( String file_mask ) {
        if( file_mask != null )
            cs = !file_mask.equals( file_mask.toLowerCase() );
    }

    protected Query( Parcel in ) {
        file_mask = in.readString();
        dirs = in.readByte() != 0;
        files = in.readByte() != 0;
        larger_than = in.readLong();
        smaller_than = in.readLong();
        cards = in.createStringArray();
        cs = in.readByte() != 0;
    }

    @Override
    public void writeToParcel( Parcel dest, int flags ) {
        dest.writeString( file_mask );
        dest.writeByte( (byte)( dirs ? 1 : 0 ) );
        dest.writeByte( (byte)( files ? 1 : 0 ) );
        dest.writeLong( larger_than );
        dest.writeLong( smaller_than );
        dest.writeStringArray( cards );
        dest.writeByte( (byte)( cs ? 1 : 0 ) );
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<Query> CREATOR = new Creator<Query>() {
        @Override
        public Query createFromParcel( Parcel in ) {
            return new Query( in );
        }

        @Override
        public Query[] newArray( int size ) {
            return new Query[size];
        }
    };

    public boolean isValid() {
        return Utils.str( this.file_mask ) || larger_than > 0 || smaller_than < Long.MAX_VALUE || mod_after != null || mod_before != null;
    }

    public final static String[] prepareWildcard( String wc ) {
        if( !Utils.str(wc) ) wc = "*";
        return ( "\02" + wc.toLowerCase() + "\03" ).split( "\\*" );
    }

    public final static boolean match( String text, String[] cards_ ) {
        int pos = 0;
        String lc_text = "\02" + text.toLowerCase() + "\03";
        for( String card : cards_ ) {
            int idx = lc_text.indexOf( card, pos );
            if( idx < 0 )
                return false;
            pos = idx + card.length();
        }
        return true;
    }

    public String getString( Context ctx ) {
        StringBuilder sb = new StringBuilder();
        if( !( dirs && files ) ) {
            if( dirs )  sb.append( ctx.getString( R.string.for_dirs ) );  else
            if( files ) sb.append( ctx.getString( R.string.for_files) ); else
            sb.append( "Nothing!!!" );
        }
        if( Utils.str( file_mask ) && !"*".equals( file_mask ) && !"*.*".equals( file_mask ) ) {
            if( sb.length() > 0 ) sb.append( ", " );
            sb.append( file_mask );
        }
        java.text.DateFormat df = null;
        if( mod_after != null ) {
            df = DateFormat.getDateFormat( ctx );
            if( sb.length() > 0 ) sb.append( ", " );
            sb.append( ctx.getString( R.string.mod_after ) );
            sb.append( " " );
            sb.append( df.format( mod_after ) );
        }
        if( mod_before != null ) {
            if( df == null ) df = DateFormat.getDateFormat( ctx );
            if( sb.length() > 0 ) sb.append( ", " );
            sb.append( ctx.getString( R.string.mod_before ) );
            sb.append( " " );
            sb.append( df.format( mod_before ) );
        }
        if( larger_than > 0 ) {
            if( sb.length() > 0 ) sb.append( ", " );
            String pr = ctx.getString( R.string.bigger_than );
            sb.append( pr.replace( '\n', ' ' ).replace( ':', ' ' ) );
            sb.append( Utils.getHumanSize( larger_than ).replace( " ", "" ) );
        }
        if( smaller_than < Long.MAX_VALUE ) {
            if( sb.length() > 0 ) sb.append( ", " );
            String pr = ctx.getString( R.string.smaller_than );
            sb.append( pr.replace( '\n', ' ' ).replace( ':', ' ' ) );
            sb.append( Utils.getHumanSize( smaller_than ).replace( " ", "" ) );
        }
        if( sb.length() == 0 )
            return "";
        sb.append( " " );
        return sb.toString();
    }

    public final String[] getCards() {
        if( cards != null )
            return cards;
        if( file_mask == null || file_mask.indexOf( '*' ) < 0 )
            return null;
        cards = prepareWildcard( file_mask );
        return cards;
    }

    public boolean match( String text ) {
        if( text == null ) return false;
        if( getCards() != null )
            return match( text, cards );
        else if( file_mask != null ) {
            if( !cs )
                text = text.toLowerCase();
            return text.contains( file_mask );
        } else
            return true; // any string matches
    }

    public boolean match( CommanderAdapter.Item item ) {
        if( item == null ) return false;
        return match( item.dir, item.name, item.date != null ? item.date.getTime() : 0, item.size );
    }

    public boolean match( boolean dir, String name, long modified, long size ) {
        try {
            if( dir ) {
                if( !dirs  ) return false;
            } else {
                if( !files ) return false;
            }
            if( modified != 0 ) {
                if( mod_after  != null && modified <  mod_after.getTime() ) return false;
                if( mod_before != null && modified > mod_before.getTime() ) return false;
            }
            if( larger_than > 0 && dir )
                return false;
            if( size != -1L && ( size < larger_than || size > smaller_than ) )
                return false;
            if( name == null )
                return true;
            return match( name );
        }
        catch( Exception e ) {
            Log.e( TAG, name, e );
        }
        return false;
    }
}
