package com.ghostsq.commander;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.ghostsq.commander.utils.Utils;

import java.text.SimpleDateFormat;
import java.util.Hashtable;
import java.util.Map;
import java.util.Set;

public class SearchProps extends Query
{
    private   final static String TAG = "SearchProps";
    private   final static String QUERY = "q", CONTENT = "c", FILES = "f", DIRS = "d", OLO = "o", LARGER = "l", SMALLER = "s", AFTER_DATE = "ad", BEFORE_DATE = "bd";
    public  String  content;
    public  Boolean olo = null;

    public SearchProps( String file_mask ) {
        super( file_mask );
        dirs = true;
        files = true;
        smaller_than = Long.MAX_VALUE; 
        this.file_mask = file_mask;
    }

    @Override
    public boolean isValid() {
        return olo != null || super.isValid() || Utils.str( this.content );
    }

    public final Uri updateUri( Context ctx, Uri u ) {
        try {
            if( u == null ) return null;
            Set<String> qpns = u.getQueryParameterNames();
            Hashtable<String, String> pp = new Hashtable<>( qpns.size() );
            for( String qpn : qpns ) {
                String qpv = u.getQueryParameter( qpn );
                pp.put( qpn, qpv );
            }
            if( file_mask != null && !"*".equals( file_mask ) )
                pp.put( QUERY, file_mask ); else pp.remove( QUERY );
            if( content != null )   pp.put( CONTENT, content ); else pp.remove( CONTENT );
            if( !files )            pp.put( FILES, "0" ); else pp.remove( FILES );
            if( !dirs  )            pp.put( DIRS,  "0" ); else pp.remove( DIRS );
            if( dirs != files )
                pp.put( dirs ? DIRS : FILES, "1" );
            if( olo != null ) pp.put( OLO, olo ? "1" : "0" ); else pp.remove( OLO );
            if( larger_than > 0 ) pp.put( LARGER, String.valueOf( larger_than ) ); else pp.remove( LARGER );
            if( smaller_than != Long.MAX_VALUE ) pp.put( SMALLER, String.valueOf( smaller_than ) ); else pp.remove( SMALLER );
            java.text.DateFormat df = null;
            if( mod_after != null ) {
                if( df == null ) df = new SimpleDateFormat( "yyyy-MM-dd" );
                pp.put( AFTER_DATE, df.format( mod_after ) );
            } else
                pp.remove( AFTER_DATE );
            if( mod_before != null ) {
                if( df == null ) df = new SimpleDateFormat( "yyyy-MM-dd" );
                pp.put( BEFORE_DATE, df.format( mod_before ) );
            } else
                pp.remove( BEFORE_DATE );
            return u.buildUpon().encodedQuery( toParamsString( pp ) ).build();
        } catch( Exception e ) {
            Log.e( TAG, u.toString(), e );
        }
        return u;
    }

    public static Uri removeQueryParams( Uri u ) {
        try {
            if( u == null ) return null;
            Set<String> qpns = u.getQueryParameterNames();
            Hashtable<String, String> pp = new Hashtable<>( qpns.size() );
            for( String qpn : qpns ) {
                if( QUERY.equals( qpn ) ) continue;
                if( CONTENT.equals( qpn ) ) continue;
                if( FILES.equals( qpn ) ) continue;
                if( DIRS.equals( qpn ) ) continue;
                if( OLO.equals( qpn ) ) continue;
                if( LARGER.equals( qpn ) ) continue;
                if( SMALLER.equals( qpn ) ) continue;
                if( AFTER_DATE.equals( qpn ) ) continue;
                if( BEFORE_DATE.equals( qpn ) ) continue;
                String qpv = u.getQueryParameter( qpn );
                pp.put( qpn, qpv );
            }
            return u.buildUpon().encodedQuery( toParamsString( pp ) ).build();
        } catch( Exception e ) {
            Log.e( TAG, u.toString(), e );
        }
        return u;
    }

    private static String toParamsString( Map<String, String> pp ) {
        if( pp == null ) return null;
        StringBuilder sb = new StringBuilder();
        for( Map.Entry<String,String> pe : pp.entrySet() ) {
            sb.append( pe.getKey() );
            sb.append( "=" );
            sb.append( Uri.encode( pe.getValue() ) );
            sb.append( "&" );
        }
        return sb.toString();
    }

    public static boolean searchQueryParamsPresent( Uri uri ) {
        if( uri == null || uri.getEncodedQuery() == null ) return false;
        if( null != uri.getQueryParameter( QUERY ) ) return true;
        if( null != uri.getQueryParameter( LARGER ) ) return true;
        if( null != uri.getQueryParameter( SMALLER ) ) return true;
        if( null != uri.getQueryParameter( CONTENT ) ) return true;
        if( null != uri.getQueryParameter( AFTER_DATE ) ) return true;
        if( null != uri.getQueryParameter( BEFORE_DATE ) ) return true;
        return false;
    }

    public static SearchProps parseSearchQueryParams( Context ctx, Uri uri ) {
        try {
            if( uri == null || uri.isOpaque() )
                return null;
            SearchProps sp = null;
            String query = uri.getQueryParameter( QUERY );
            sp = new SearchProps( query );
            sp.content = uri.getQueryParameter( CONTENT );
            sp.larger_than = Utils.parseHumanSize( uri.getQueryParameter( LARGER ) );
            long st = Utils.parseHumanSize( uri.getQueryParameter( SMALLER ) );
            if( st > 0 )
                sp.smaller_than = st;
            java.text.DateFormat df = new SimpleDateFormat( "yyyy-MM-dd" );
            try {
                sp.mod_after = df.parse( uri.getQueryParameter( AFTER_DATE ) );
            } catch( Exception e ) {}
            try {
                sp.mod_before = df.parse( uri.getQueryParameter( BEFORE_DATE ) );
            } catch( Exception e ) {}

            String  dirs_s  = uri.getQueryParameter( DIRS );
            String  files_s = uri.getQueryParameter( FILES );
            boolean dirs  = "1".equals(  dirs_s );
            boolean files = "1".equals( files_s );
            if( dirs != files )
                sp.setTypes( files );
            String olo_s = uri.getQueryParameter( OLO );
            if( olo_s != null )
                sp.olo = "1".equals( olo_s );
            return sp.isValid() ? sp : null;
        } catch( Exception e ) {
            Log.e( TAG, uri.toString(), e );
        }
        return null;
    }

    public final void setTypes( boolean files_only ) {
        if( files_only ) {
            files = true;
            dirs = false;
        } else {
            files = false;
            dirs = true;
        }
    }

}
