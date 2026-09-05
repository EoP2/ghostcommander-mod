package com.ghostsq.commander;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.Display;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.ghostsq.commander.adapters.CA;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.CommanderAdapter.Item;
import com.ghostsq.commander.adapters.FSAdapter;
import com.ghostsq.commander.adapters.FavsAdapter;
import com.ghostsq.commander.adapters.HomeAdapter;
import com.ghostsq.commander.favorites.Favorite;
import com.ghostsq.commander.utils.Credentials;
import com.ghostsq.commander.utils.Utils;

import java.util.ArrayDeque;
import java.util.ArrayList;


public class ListHelper {
    private final String TAG;
    public  final int  which, id;
    public  ListView   flv = null;
    private final TextView status, sorting;
    private int        currentPosition = -1;
    public  int        adapterMode = 0;
    private String[]   listOfItemsChecked = null;
    private final Panels p;
    private boolean    needRefresh, was_current;

    private final static int HIST_CAP = 50;
    private final ArrayDeque<HistEntry> backStack = new ArrayDeque<HistEntry>();
    private final ArrayDeque<HistEntry> fwdStack  = new ArrayDeque<HistEntry>();

    public static final class HistEntry implements Parcelable {
        public final Uri         uri;
        public final Credentials crd;
        public final String      label;

        public HistEntry( Uri uri, Credentials crd, String label ) {
            this.uri   = uri;
            this.crd   = crd;
            this.label = label;
        }

        private HistEntry( Parcel in ) {
            uri   = in.readParcelable( Uri.class.getClassLoader() );
            crd   = in.readParcelable( Credentials.class.getClassLoader() );
            label = in.readString();
        }

        public static final Parcelable.Creator<HistEntry> CREATOR = new Parcelable.Creator<HistEntry>() {
            @Override public HistEntry createFromParcel( Parcel in ) { return new HistEntry( in ); }
            @Override public HistEntry[] newArray( int size ) { return new HistEntry[size]; }
        };

        @Override public int describeContents() { return 0; }

        @Override public void writeToParcel( Parcel dest, int flags ) {
            dest.writeParcelable( uri, flags );
            dest.writeParcelable( crd, flags );
            dest.writeString( label );
        }
    }

    ListHelper( int which_, Panels p_ ) {
        needRefresh = false;
        was_current = false;
        which = which_;
        TAG = "ListHelper" + which;
        p = p_;
        id = which == Panels.LEFT ? R.id.left_list : R.id.right_list;
        flv = (ListView)p.c.findViewById( id );
        if( flv != null ) {
            flv.setItemsCanFocus( false );
            flv.setFocusableInTouchMode( true );
            flv.setOnItemSelectedListener( p );
            flv.setChoiceMode( ListView.CHOICE_MODE_MULTIPLE );
            flv.setOnItemClickListener( p );
            flv.setOnFocusChangeListener( p );
            flv.setOnTouchListener( p );
            flv.setOnKeyListener( p );
            flv.setOnScrollListener( p );
            p.c.registerForContextMenu( flv );
        }
        status   = (TextView)p.c.findViewById( which == Panels.LEFT ? R.id.left_stat_text : R.id.right_stat_text );
        sorting  = (TextView)p.c.findViewById( which == Panels.LEFT ? R.id.left_sorting   : R.id.right_sorting );
    }

    public final CommanderAdapter getListAdapter() {
        return (CommanderAdapter)flv.getAdapter();
    }

    public final void mbNavigate( Uri uri, Credentials crd, String posTo, boolean was_current_ ) {
        if( SearchProps.searchQueryParamsPresent( uri ) ) {
            // closure
            final Uri         _uri = uri; 
            final Credentials _crd = crd;
            final String      _posTo = posTo;
            final boolean     _was_current_ = was_current_;
            Context c = p.c.getContext();
            new AlertDialog.Builder( c )
                .setTitle( R.string.refresh )
                .setMessage( R.string.rescan_q )
                .setPositiveButton( R.string.dialog_ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog,int id) {
                            Navigate( _uri, _crd, _posTo, _was_current_ );
                        }
                    })
                .setNegativeButton( R.string.dialog_cancel, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog,int id) {
                            Uri u = SearchProps.removeQueryParams( _uri );
                            Navigate( u == null ? Uri.parse( "home:" ) : u, null, null, _was_current_ );
                        }
                    } )
                .show();
        } else
            Navigate( uri, crd, posTo, was_current_ );
    }
    
    public final void Navigate( Uri uri, Credentials crd, String posTo, boolean was_current_ ) {
        Navigate( uri, crd, posTo, was_current_, false );
    }

    public final void Navigate( Uri uri, Credentials crd, String posTo, boolean was_current_, boolean fromHistory ) {
        try {
            if( !fromHistory )
                pushHistory( uri );
            // Log.v( TAG, "Navigate to " + Favorite.screenPwd( uri ) );
            was_current = was_current_;
            currentPosition = -1;
            flv.clearChoices();
            flv.invalidateViews();
            CommanderAdapter ca_new = null, ca = (CommanderAdapter)flv.getAdapter();
            String scheme = uri.getScheme();
            if( scheme == null ) scheme = "";
            if( ca == null || !scheme.equals( ca.getScheme() ) ) {
                ca_new = CA.CreateAdapter( uri, p.c );
                if( ca_new == null ) {
                    Log.e( TAG, "Can't create adapter of type '" + scheme + "'" );
                    if( ca != null )
                        return;
                    ca_new = CA.CreateAdapter( null, p.c );
                }
                if( ca != null )
                    ca.prepareToDestroy();
                if( ca_new instanceof FavsAdapter ) {
                    FavsAdapter fav_a = (FavsAdapter)ca_new;
                    fav_a.setFavorites( p.getFavorites() );
                }
                flv.setAdapter( (ListAdapter)ca_new );
                flv.setOnKeyListener( p );
                SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences( p.c );
                applySettings( sharedPref );
                ca = ca_new;
            }
            //p.applyColors();
            p.setPanelTitle( !ca.hasFeature( CommanderAdapter.Feature.HOME ) ? "" : p.c.getString( R.string.wait ), which );
          //p.setToolbarButtons( ca );
            if( crd != null )
                ca.setCredentials( crd );
            ca.setMode( CommanderAdapter.MODE_SORTING | CommanderAdapter.MODE_SORT_DIR, adapterMode );
            ca.readSource( uri, "" + which + ( posTo == null ? "" : posTo ) );
        } catch( Exception e ) {
            Log.e( TAG, "NavigateInternal()", e );
        }
    }

    private HistEntry captureCurrentEntry() {
        CommanderAdapter ca = (CommanderAdapter)flv.getAdapter();
        if( ca == null ) return null;
        Uri cur = ca.getUri();
        if( cur == null ) return null;
        return new HistEntry( cur, ca.getCredentials(), Favorite.screenPwd( cur ) );
    }

    private void pushHistory( Uri target ) {
        try {
            HistEntry cur = captureCurrentEntry();
            if( cur == null ) return;
            if( target != null && Utils.addTrailingSlash( cur.uri ).equals( Utils.addTrailingSlash( target ) ) )
                return;
            backStack.push( cur );
            while( backStack.size() > HIST_CAP )
                backStack.removeLast();
            fwdStack.clear();
        } catch( Exception e ) {
            Log.e( TAG, "pushHistory()", e );
        }
    }

    private void pushOpposite( ArrayDeque<HistEntry> stack ) {
        try {
            HistEntry cur = captureCurrentEntry();
            if( cur != null )
                stack.push( cur );
        } catch( Exception e ) {
            Log.e( TAG, "pushOpposite()", e );
        }
    }

    public final HistEntry histBack() {
        if( backStack.isEmpty() ) return null;
        HistEntry e = backStack.pop();
        pushOpposite( fwdStack );
        Navigate( e.uri, e.crd, null, was_current, true );
        return e;
    }

    public final HistEntry histForward() {
        if( fwdStack.isEmpty() ) return null;
        HistEntry e = fwdStack.pop();
        pushOpposite( backStack );
        Navigate( e.uri, e.crd, null, was_current, true );
        return e;
    }

    public final HistEntry jumpHistory( int steps ) {
        try {
            if( steps == 0 ) return null;
            HistEntry oldCurrent = captureCurrentEntry();
            if( oldCurrent == null ) return null;
            HistEntry target;
            if( steps > 0 ) {
                if( steps > fwdStack.size() ) return null;
                ArrayList<HistEntry> fwdAsList = new ArrayList<HistEntry>( fwdStack );
                target = fwdAsList.get( steps - 1 );
                backStack.push( oldCurrent );
                for( int i = 0; i < steps - 1; i++ )
                    backStack.push( fwdAsList.get( i ) );
                for( int i = 0; i < steps; i++ )
                    fwdStack.pop();
            } else {
                int n = -steps;
                if( n > backStack.size() ) return null;
                ArrayList<HistEntry> backAsList = new ArrayList<HistEntry>( backStack );
                target = backAsList.get( n - 1 );
                fwdStack.push( oldCurrent );
                for( int i = 0; i < n - 1; i++ )
                    fwdStack.push( backAsList.get( i ) );
                for( int i = 0; i < n; i++ )
                    backStack.pop();
            }
            Navigate( target.uri, target.crd, null, was_current, true );
            return target;
        } catch( Exception e ) {
            Log.e( TAG, "jumpHistory()", e );
            return null;
        }
    }

    public final boolean hasHistBack()    { return !backStack.isEmpty(); }
    public final boolean hasHistForward() { return !fwdStack.isEmpty();  }

    public final ArrayList<HistEntry> getHistoryList() {
        return new ArrayList<HistEntry>( backStack );
    }

    public final ArrayList<HistEntry> getForwardHistoryList() {
        return new ArrayList<HistEntry>( fwdStack );
    }

    public static final class HistoryView {
        public final ArrayList<HistEntry> entries;
        public final int currentIndex;
        HistoryView( ArrayList<HistEntry> entries, int currentIndex ) {
            this.entries = entries;
            this.currentIndex = currentIndex;
        }
    }

    public final HistoryView getHistoryView() {
        ArrayList<HistEntry> entries = new ArrayList<HistEntry>();
        ArrayList<HistEntry> fwdAsList = new ArrayList<HistEntry>( fwdStack );
        for( int i = fwdAsList.size() - 1; i >= 0; i-- )
            entries.add( fwdAsList.get( i ) );
        int currentIndex = entries.size();
        HistEntry cur = captureCurrentEntry();
        if( cur != null )
            entries.add( cur );
        else
            currentIndex = -1;
        entries.addAll( backStack );
        return new HistoryView( entries, currentIndex );
    }

    public final void restoreHistory( ArrayList<HistEntry> back, ArrayList<HistEntry> fwd ) {
        if( back == null && fwd == null )
            return;
        backStack.clear();
        if( back != null ) backStack.addAll( back );
        fwdStack.clear();
        if( fwd != null ) fwdStack.addAll( fwd );
    }

    public final void focus() {
        //Log.v( TAG, "set focus for panel " + which );
        if( flv == null ) return;
        if( BuildConfig.DEBUG ) {
          boolean focusable = flv.isFocusable(); 
          boolean focusable_tm = flv.isFocusableInTouchMode(); 
          boolean focused = flv.isFocused();
          boolean item_focus = flv.getItemsCanFocus(); 
          Log.v( TAG, "wants focus. " + focusable + ", " + focusable_tm + ", " + focused + ", " + item_focus );
        }
        flv.requestFocus();
        flv.requestFocusFromTouch();
    }

    private void hideSelectionZone( int bg_color ) {
        flv.setBackgroundColor( bg_color );
        flv.setCacheColorHint(  bg_color );
    }

    private void showSelectionZone( boolean right, float width, int bg_color ) {
        if( flv == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M ) {
            Log.w( TAG, "Selection zone highlight is not possible!" );
            hideSelectionZone( bg_color );
            return;
        }
        final int lw = flv.getWidth();
        if( lw == 0 ) {
            Log.e( TAG, "Invalid width!" );
            return;
        }
        final int bw = (int)(lw / 80);
        final float hw = lw * width;
        Log.d( TAG, "Creating selection highlight background. Width=" + lw );
        ShapeDrawable lsd = new ShapeDrawable();
        ShapeDrawable rsd = new ShapeDrawable();
        int oc, sc, lc, rc;
        oc = bg_color;
        sc = Utils.altBrightness( bg_color, 0.04f );
        lc = right ? oc : sc;
        rc = right ? sc : oc;
        lsd.getPaint().setColor( lc );
        rsd.getPaint().setColor( rc );
        GradientDrawable gd = new GradientDrawable( GradientDrawable.Orientation.LEFT_RIGHT, new int[] { lc, rc } );
        LayerDrawable ld = new LayerDrawable( new Drawable[] { lsd, gd, rsd } );
        ld.setLayerWidth( 0, (int)hw - bw );
        ld.setLayerWidth( 1, bw * 2 );
        ld.setLayerWidth( 2, (int)(flv.getWidth() - hw - bw) );
        ld.setLayerInsetLeft( 1, (int)hw - bw );
        ld.setLayerInsetLeft( 2, (int)hw + bw );
        flv.setBackground( ld );
        flv.setCacheColorHint( bg_color );
    }

    public final void applyColors( ColorsKeeper ck ) {
        if( flv == null ) return;
//        flv.setCacheColorHint( ck.bgrColor );
        if( !ck.isFocusDefault() ) {
            Drawable selector_drawable = Utils.getShadingEx( ck.curColor, 0.9f );
            if( selector_drawable == null )
                selector_drawable = new ColorDrawable( ck.curColor );
            StateListDrawable sld = new StateListDrawable();
            sld.addState( new int[] { -android.R.attr.state_window_focused }, 
                    new ColorDrawable( Color.TRANSPARENT ) );
            sld.addState( new int[] { android.R.attr.state_focused, -android.R.attr.state_enabled, 
                    android.R.attr.state_pressed  }, 
                    selector_drawable );
            sld.addState( new int[] { android.R.attr.state_focused, -android.R.attr.state_enabled }, 
                    selector_drawable );
            sld.addState( new int[] { android.R.attr.state_focused, android.R.attr.state_pressed  }, 
                    selector_drawable );
            sld.addState( new int[] { -android.R.attr.state_focused, android.R.attr.state_pressed  }, 
                    selector_drawable );
            sld.addState( new int[] { android.R.attr.state_focused }, 
                    selector_drawable );
            // http://alvinalexander.com/java/jwarehouse/android-examples/platforms/android-2/data/res/drawable/list_selector_background_transition.xml.shtml
            // Drawable d = p.c.getResources().getDrawable( android.R.drawable.list_selector_background );
            flv.setSelector( sld );
        }
    }

    public final void setFocused( boolean on ) {
        int color = Utils.getFocusTextColor( p.ck, on );
        status.setTextColor( color );
        sorting.setTextColor( color );
    }

    public final void applySettings( SharedPreferences sp ) {
        try {
            boolean scroll_enabled = sp.getBoolean( "show_scrollbars", true );
            flv.setVerticalScrollBarEnabled( scroll_enabled );
            flv.setFastScrollEnabled( scroll_enabled );

            CommanderAdapter ca = (CommanderAdapter)flv.getAdapter();
            if( ca == null )
                return;

            DisplayMetrics dm = new DisplayMetrics();
            p.c.getWindowManager().getDefaultDisplay().getMetrics( dm );
            float density = dm.density;            
            
            Display disp = p.c.getWindowManager().getDefaultDisplay();
            int w = (int)( disp.getWidth()  / density );
            int h = (int)( disp.getHeight() / density );
            final int WIDTH_THRESHOLD = 480;
            
            int m = ca.setMode( CommanderAdapter.MODE_WIDTH,
                    ( p.sxs && w/2 < WIDTH_THRESHOLD ) || sp.getBoolean( "two_lines", true ) ? CommanderAdapter.NARROW_MODE
                            : CommanderAdapter.WIDE_MODE );
            flv.getWidth();
            ca.setMode( CommanderAdapter.SET_FONT_SIZE, p.fnt_sz );
              status.setTextSize( p.fnt_sz * 0.75f );
             sorting.setTextSize( p.fnt_sz * 0.75f );

            String sfx = p.sxs ? "_SbS" : "_Ovr";
            boolean detail_mode = sp.getBoolean( which == Panels.LEFT ? "left_detailed" + sfx : "right_detailed" + sfx, true );
            
            boolean show_icons = sp.getBoolean( "show_icons", true );
            boolean same_line = ( m & CommanderAdapter.MODE_WIDTH ) == CommanderAdapter.WIDE_MODE;
            int icon_mode;
            if( show_icons ) {
                icon_mode = CommanderAdapter.ICON_MODE;
                if( p.fnt_sz < 18 && !p.fingerFriendly ) {
                    if( p.fnt_sz <= 10 || ( h * w <= 480 * 854 && ( p.sxs || same_line ) ) )
                      icon_mode |= CommanderAdapter.ICON_TINY;
                }
            }
            else
                icon_mode = CommanderAdapter.TEXT_MODE;
            ca.setMode( CommanderAdapter.MODE_ICONS, icon_mode );

            ca.setMode( CommanderAdapter.MODE_CASE, sp.getBoolean( "case_ignore", true ) ? CommanderAdapter.CASE_IGNORE
                    : CommanderAdapter.CASE_SENS );

            ca.setMode( CommanderAdapter.MODE_DETAILS, detail_mode ? CommanderAdapter.DETAILED_MODE : CommanderAdapter.SIMPLE_MODE );
            String sort = sp.getString( which == Panels.LEFT ? "left_sorting" : "right_sorting", "n" );
            ca.setMode( CommanderAdapter.MODE_SORTING,
                    sort.compareTo( "s" ) == 0 ? CommanderAdapter.SORT_SIZE :
                    sort.compareTo( "e" ) == 0 ? CommanderAdapter.SORT_EXT :
                    sort.compareTo( "d" ) == 0 ? CommanderAdapter.SORT_DATE : CommanderAdapter.SORT_NAME );
            ca.setMode( CommanderAdapter.MODE_FINGERF, p.fingerFriendly ? CommanderAdapter.FAT_MODE : CommanderAdapter.SLIM_MODE );

            boolean hidden_mode = sp.getBoolean( ( which == Panels.LEFT ? "left" : "right" ) + "_show_hidden", true );
            ca.setMode( CommanderAdapter.MODE_HIDDEN, hidden_mode ? CommanderAdapter.SHOW_MODE : CommanderAdapter.HIDE_MODE );

            int thubnails_size = 0;
            if( show_icons && sp.getBoolean( "show_thumbnails", true ) )
                thubnails_size = Integer.parseInt( sp.getString( "thumbnails_size", "125" ) );
            ca.setMode( CommanderAdapter.SET_TBN_SIZE, thubnails_size );

            if( ca instanceof HomeAdapter )
                ca.setMode( CommanderAdapter.MODE_ROOT, sp.getBoolean( "show_root", false ) ? CommanderAdapter.ROOT_MODE
                        : CommanderAdapter.BASIC_MODE );
            boolean highlight_sel_zone = sp.getBoolean( Prefs.SEL_ZONE + "_ht", false );
            hideSelectionZone( p.ck.bgrColor );
            if( highlight_sel_zone && ca.hasFeature( CommanderAdapter.Feature.CHECKABLE ) ) {
                boolean at_right = sp.getBoolean( Prefs.SEL_ZONE + "_right", false );
                float sel_width = sp.getInt( Prefs.SEL_ZONE + "_width", 20 ) / 100f;
                flv.post( () ->
                    showSelectionZone( at_right, sel_width, p.ck.bgrColor )
                );
            }
        } catch( Exception e ) {
            Log.e( TAG, "applySettings() inner", e );
        }
    }

    public void setFingerFriendly( boolean fat ) {
        try {
            CommanderAdapter ca = (CommanderAdapter)flv.getAdapter();
            if( ca != null ) {
                int mode = fat ? CommanderAdapter.FAT_MODE : CommanderAdapter.SLIM_MODE;
                ca.setMode( CommanderAdapter.MODE_FINGERF, mode );
                flv.invalidate();
            }
        } catch( Exception e ) {
            Log.e( TAG, null, e );
        }
    }

    public final void setNeedRefresh() {
        needRefresh = true;
    }
    public final boolean needRefresh() {
        return needRefresh;
    }

    public final void mbRefreshList( boolean was_current_, String posto ) {
        CommanderAdapter ca = (CommanderAdapter)flv.getAdapter();
        if( ca == null )
            return;
        final Uri _uri = ca.getUri();
        if( !SearchProps.searchQueryParamsPresent( _uri ) ) {
            if( ca.getMode( CommanderAdapter.LIST_STATE ) == CommanderAdapter.STATE_BUSY )
                return;
            refreshList( was_current_, posto );
            return;
        }
        // closure
        final String _posto = posto;
        final boolean _was_current = was_current_;
        Context c = p.c.getContext();
        new AlertDialog.Builder( c )
            .setTitle( R.string.refresh )
            .setMessage( R.string.rescan_q )
            .setPositiveButton( R.string.dialog_ok, new DialogInterface.OnClickListener() {
                public void onClick( DialogInterface dialog, int id ) {
                    ListHelper.this.refreshList( _was_current, _posto );
                }
            } )
            .setNegativeButton( R.string.dialog_cancel, new DialogInterface.OnClickListener() {
                public void onClick( DialogInterface dialog, int id ) {
                    Uri u = SearchProps.removeQueryParams( _uri );
                    Navigate( u == null ? Uri.parse( "home:" ) : u, null, null, _was_current );
                }
            } )
            .show();
    }

    public final void refreshList( boolean was_current_, String posto ) {
        try {
            was_current = was_current_;
            CommanderAdapter ca = (CommanderAdapter)flv.getAdapter();
            if( ca == null )
                return;
            storeChosenItems();
            flv.clearChoices();
            String cookie = "" + which;
            if( posto != null )
                cookie += posto;
            p.setPanelTitle( !ca.hasFeature( CommanderAdapter.Feature.HOME ) ? "" : p.c.getString( R.string.wait ), which );
            ca.readSource( null, cookie );
            flv.invalidateViews();
            needRefresh = false;
        } catch( Exception e ) {
            Log.e( TAG, "refreshList()", e );
        }
    }

    public final void askRedrawList() {
        flv.invalidateViews();
    }

    // --- Selection and Items Checking ---

    public int getCurPos() {
        return currentPosition;
    }

    public void setCurPos( int pos ) {
        currentPosition = pos;
    }

    public final void checkItem( boolean next ) {
        final int pos = getSelected();
        if( pos <= 0 ) return;
        SparseBooleanArray cis = flv.getCheckedItemPositions();
        flv.setItemChecked( pos, !cis.get( pos ) );
        if( next )
            flv.setSelectionFromTop( pos + 1, flv.getHeight() / 2 );
    }

    public final boolean checkItemsBetween() {
        SparseBooleanArray cis = flv.getCheckedItemPositions();
        int first = -1, last = -1;
        for( int i = 0; i < cis.size(); i++ ) {
            if( cis.valueAt( i ) ) {
                first = cis.keyAt( i );
                break;
            }
        }
        for( int i = cis.size()-1; i >= 0; i-- ) {
            if( cis.valueAt( i ) ) {
                last = cis.keyAt( i );
                break;
            }
        }
        if( first < 0 || last < 0 || first == last ) {
            Log.e( TAG, "No checked items bracket" );
            return false;
        }
        for( int i = first; i <= last; i++ )
            flv.setItemChecked( i, true );
        updateStatus();
        return true;
    }

    private interface ItemAction {
        void apply( int pos );
    }

    private final void forEachMatching( String mask, boolean dir, boolean file, ItemAction action ) {
        if( !dir && !file ) return;
        String[] cards = Query.prepareWildcard( mask );
        ListAdapter la = flv.getAdapter();
        CommanderAdapter ca = (CommanderAdapter)la;
        if( la == null || cards == null ) return;
        for( int i = 1; i < flv.getCount(); i++ ) {
            if( dir != file ) {
                CommanderAdapter.Item cai = (CommanderAdapter.Item)la.getItem( i );
                if( cai == null ) continue;
                if( cai.dir ) {
                    if( !dir )  continue;
                } else {
                    if( !file ) continue;
                }
            }
            if( Query.match( ca.getItemName( i, false ), cards ) )
                action.apply( i );
        }
        updateStatus();
    }

    public final void checkItems( boolean set, String mask, boolean dir, boolean file ) {
        try {
            forEachMatching( mask, dir, file, i -> flv.setItemChecked( i, set ) );
        } catch( Exception e ) {
            Log.e( TAG, mask, e );
        }
    }

    public final void invertItems( String mask, boolean dir, boolean file ) {
        try {
            final SparseBooleanArray cis = flv.getCheckedItemPositions();
            forEachMatching( mask, dir, file, i -> flv.setItemChecked( i, !cis.get( i ) ) );
        } catch( Exception e ) {
            Log.e( TAG, mask, e );
        }
    }

    public final void setSelection( int i, int y ) {
        final ListView _flv = flv;
        final int _position = i, _y = y;
        flv.post( new Runnable() {
            public void run() {
                _flv.setSelectionFromTop( _position, _y > 0 ? _y : _flv.getHeight() / 2 );
            }
        } );
        currentPosition = i;
    }

    public final void setSelection( String name ) {
        if( name == null ) return;
        CommanderAdapter ca = (CommanderAdapter)flv.getAdapter();
        if( ca != null ) {
            int i, num = ( (ListAdapter)ca ).getCount();
            for( i = 0; i < num; i++ ) {
                String item_name = ca.getItemName( i, false );
                if( item_name != null && item_name.compareTo( name ) == 0 ) {
                    //Log.v( TAG, "trying to set panel " + which + " selection to '" + name + "', pos: " + i + ", ph: " + flv.getHeight() );
                    setSelection( i, flv.getHeight() / 2 );
                    break;
                }
            }
        }
    }
    
    public final String getFilterSearchSummary() {
        CommanderAdapter adapter = (CommanderAdapter)flv.getAdapter();
        StringBuilder filter_info = new StringBuilder();
        Query search = adapter.getSearch();
        if( search != null ) {
            filter_info.append( p.c.getString( R.string.search_file ) );
            filter_info.append( ": " );
            filter_info.append( search.getString( p.c ) );
            filter_info.append( " \u00B7 " );
        }
        Query filter = adapter.getFilter();
        if( filter != null ) {
            filter_info.append( p.c.getString( R.string.filter ) );
            filter_info.append( ": " );
            filter_info.append( filter.getString( p.c ) );
            filter_info.append( " \u00B7 " );
        }
        return filter_info.length() > 0 ? filter_info.toString() : null;
    }
    public final String getActiveItemsSummary( boolean touched, ArrayList<String> names ) {
        ListAdapter la = flv.getAdapter();
        CommanderAdapter ca = (CommanderAdapter)la;
        SparseBooleanArray cis = getMultiple( touched );
        int counter = Utils.getCount( cis );
        long total_size = 0;
        for( int i = 0; i < cis.size(); i++ ) {
            if( cis.valueAt( i ) ) {
                int pos = cis.keyAt( i );
                Object o = la.getItem( pos );
                String item_name;
                CommanderAdapter.Item item = null;
                if( o instanceof CommanderAdapter.Item ) {
                    item = (CommanderAdapter.Item)o;
                    item_name = item.name + (item.dir ? "/" : "");
                } else
                    item_name = ca.getItemName( pos, false );
                if( names != null )
                    names.add( item_name );
                if( counter == 1 ) {
                    if( !Utils.str( item_name ) )
                        item_name = ca.getItemName( pos, true );   // when that works?
                    if( item_name == null ) item_name = "";
                    return item_name;
                }
                if( ca instanceof FSAdapter ) {
                    if( item == null )
                        item = ca.getItem( ca.getItemUri( pos ) );
                    if( item != null && !item.dir )
                        total_size += item.size;
                }
            }
        }
        String res = Utils.getNItems( p.c, counter );
        if( total_size > 0 )
            res += " (" + Utils.getHumanSize( total_size, false ) + "b)";
        return res;
    }

    /**
     * Get the checked/selected position for a single file operation (such as F2,F3,F4)
     * @param touched - the operation was initiated by the context menu
     * @return
     *  when touched = true
     *      Always returns the selected item
     *  when touched = false
     *      If only ONE checked then returns a checked one, otherwise returns all that selected 
     */
    public final int getSingle( boolean touched ) {
        if( touched ) {
            return getSelected();
        } else {
            SparseBooleanArray cis = flv.getCheckedItemPositions();
            int sel_pos = AdapterView.INVALID_POSITION;
            for( int i = 0; i < cis.size(); i++ ) {
                if( cis.valueAt( i ) ) {
                    if( sel_pos >= 0 )
                        return getSelected();
                    sel_pos = cis.keyAt( i );
                }
            }
            return sel_pos >= 0 ? sel_pos : getSelected();
        }
    }
    
    /**
     * Get the checked/selected files positions for a multifile operation (such as F5,F6,F8,sz) 
     * @param touched - the operation was initiated by the context menu
     * @return
     *  when touched = true
     *      Returns selected if it does not belong to checked, returns all checked otherwise
     *  when touched = false
     *      If none checked then returns a selected one, else returns all that checked 
     */
    public final SparseBooleanArray getMultiple( boolean touched ) {
        int pos = getSelected();
        SparseBooleanArray cis = flv.getCheckedItemPositions();
        if( AdapterView.INVALID_POSITION == pos /*|| pos == 0*/ )
            return cis;
        for( int i = 0; i < cis.size(); i++ )
            if( cis.valueAt( i ) && ( !touched || cis.keyAt( i ) == pos ) )
                return cis;
        return wrapToSparceArray( pos );
    }
    
    public final SparseBooleanArray wrapToSparceArray( int pos ) {
        SparseBooleanArray cis = new SparseBooleanArray( 1 );
        cis.put( pos, true );
        return cis;
    }

    public final int getSelected() {
        int pos = flv.getSelectedItemPosition();
        if( pos != AdapterView.INVALID_POSITION )
            return currentPosition = pos;
        return currentPosition;
    }
    
    // --- end new methods    
    
    public final void recoverAfterRefresh( String item_name ) {
        try {
            //Log.v( TAG, "restoring panel " + which + " item: " + item_name );
            reStoreChosenItems();
            if( Utils.str( item_name ) )
                setSelection( item_name );
            else
                setSelection( currentPosition > 0 ? currentPosition : 0, 0 );
            if( was_current ) {
                p.setPanelCurrent( which, false );
                was_current = false;
            }
        } catch( Exception e ) {
            Log.e( TAG, "recoverAfterRefresh()", e );
        }
    }

    public final void recoverAfterRefresh( boolean this_current ) { // to be called for the current panel
        try {
            reStoreChosenItems();
            flv.invalidateViews();
            if( this_current && !flv.isInTouchMode() && currentPosition > 0 ) {
                //Log.v( TAG, "restoring pos: " + currentPosition );
                setSelection( currentPosition, flv.getHeight() / 2 );
            }
        } catch( Exception e ) {
            Log.e( TAG, "recoverAfterRefresh()", e );
        }
    }

    public void storeChosenItems() {
        try {
            SparseBooleanArray cis = flv.getCheckedItemPositions();
            CommanderAdapter ca = (CommanderAdapter)flv.getAdapter();
            int counter = 0;
            for( int i = 0; i < cis.size(); i++ )
                if( cis.valueAt( i ) && cis.keyAt( i ) > 0 )
                    counter++;
            listOfItemsChecked = null;
            if( counter > 0 ) {
                listOfItemsChecked = new String[counter];
                int j = 0;
                for( int i = 0; i < cis.size(); i++ )
                    if( cis.valueAt( i ) ) {
                        int k = cis.keyAt( i );
                        if( k > 0 )
                            listOfItemsChecked[j++] = ca.getItemName( k, false );
                    }
            }
        } catch( Exception e ) {
            Log.e( TAG, "storeChosenItems()", e );
        }
    }

    public void reStoreChosenItems() {
        try {
            if( listOfItemsChecked == null || listOfItemsChecked.length == 0 )
                return;
            ListAdapter la = flv.getAdapter();
            if( la != null ) {
                CommanderAdapter ca = (CommanderAdapter)la;
                int n_items = la.getCount();
                for( int i = 1; i < n_items; i++ ) {
                    String item_name = ca.getItemName( i, false );
                    boolean set = false;
                    for( int j = 0; j < listOfItemsChecked.length; j++ ) {
                        String ci = listOfItemsChecked[j];
                        if( ci != null && ci.compareTo( item_name ) == 0 ) {
                            set = true;
                            break;
                        }
                    }
                    flv.setItemChecked( i, set );
                }
                if( currentPosition >= 0 )
                    setSelection( currentPosition, 0 );
            }
        } catch( Exception e ) {
            Log.e( TAG, "reStoreChosenItems()", e );
        }
        finally {
            listOfItemsChecked = null;
            updateStatus();
        }
    }
    public void updateStatus() {
        if( status == null ) return;
        String fss = getFilterSearchSummary();
        String ais = getActiveItemsSummary( false, null );
        String stt = ( fss != null ? fss + " " : "" ) + ( ais == null ? "" : ais );
        status.setText( stt );
    }
    public void updateSortingAndIcon() {
        String ss = "";
        CommanderAdapter ca = getListAdapter();
        if( ca != null ) {
            if( ca.hasFeature( CommanderAdapter.Feature.SORTING ) ) {
                int sort_bits = ca.getMode( CommanderAdapter.MODE_SORTING | CommanderAdapter.MODE_SORT_DIR );
                switch( sort_bits & CommanderAdapter.MODE_SORTING ) {
                    case CommanderAdapter.SORT_NAME: ss = "N";  break;
                    case CommanderAdapter.SORT_SIZE: ss = "S";  break;
                    case CommanderAdapter.SORT_EXT:  ss = "E";  break;
                    case CommanderAdapter.SORT_DATE: ss = "D";  break;
                    case CommanderAdapter.SORT_ACCD: ss = "A";  break;
                    default: ss = "?";
                }
                if( ( sort_bits & CommanderAdapter.SORT_DSC  ) != 0 ) ss += "\u2191"; else ss += "\u2193";
            }
        }
        sorting.setText( ss );
    }
}
