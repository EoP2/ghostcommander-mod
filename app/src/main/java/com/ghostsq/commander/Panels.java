
package com.ghostsq.commander;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.text.ClipboardManager;
import android.util.Log;
import android.util.Pair;
import android.util.SparseBooleanArray;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AbsListView.OnScrollListener;
import android.widget.Adapter;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.ghostsq.commander.adapters.CA;
import com.ghostsq.commander.adapters.ChecksumEngine;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.CommanderAdapter.Feature;
import com.ghostsq.commander.adapters.CommanderAdapterBase;
import com.ghostsq.commander.adapters.FSAdapter;
import com.ghostsq.commander.adapters.FavsAdapter;
import com.ghostsq.commander.adapters.HomeAdapter;
import com.ghostsq.commander.adapters.ReceiveEngine;
import com.ghostsq.commander.adapters.SAFAdapter;
import com.ghostsq.commander.adapters.ZipAdapter;
import com.ghostsq.commander.favorites.Favorite;
import com.ghostsq.commander.favorites.Favorites;
import com.ghostsq.commander.favorites.LocationBar;
import com.ghostsq.commander.root.RootAdapter;
import com.ghostsq.commander.toolbuttons.ToolButton;
import com.ghostsq.commander.toolbuttons.ToolButtons;
import com.ghostsq.commander.utils.AppInstaller;
import com.ghostsq.commander.utils.Credentials;
import com.ghostsq.commander.utils.ForwardCompat;
import com.ghostsq.commander.utils.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Panels implements AdapterView.OnItemSelectedListener,
                               AdapterView.OnItemClickListener,
                               ListView.OnScrollListener,
                               View.OnClickListener,
                               View.OnLongClickListener,
                               View.OnTouchListener,
                               View.OnFocusChangeListener,
                               View.OnKeyListener {
    private final static String TAG = "Panels";
    public static final String DEFAULT_LOC = Environment.getExternalStorageDirectory().getAbsolutePath();
    public final static int LEFT = 0, RIGHT = 1;
    private int current = LEFT;
    private final int[] titlesIds = {R.id.left_dir, R.id.right_dir};
    // The merged path+status container per panel. Click/long-click and the
    // focus-highlight background now target this instead of the bare path
    // text, while titlesIds above still locates the path TextView itself
    // for setPanelTitle()'s text/size updates.
    private final int[] headerIds = {R.id.left_stat, R.id.right_stat};
    private final ListHelper[] list = {null, null};
    public FileCommander c;
    public View mainView, toolbar = null, tbScroll = null;
    private final LockableScrollView hsv;
    public PanelsView panelsView = null;
    public boolean sxs, fingerFriendly = false;
    private boolean panels_sliding = true, arrowsLegacy = false, warnOnRoot = true, rootOnRoot = false, toolbarShown = false;
    public boolean volumeLegacy = false;
    private boolean selAtRight = true, disableOpenSelectOnly = false;
    private float selWidth = 0.5f, downX = 0, downY = 0, x_start = -1;
    public int scroll_back = 50, fnt_sz = 14;
    private StringBuffer quickSearchBuf = null;
    private Toast quickSearchTip = null;
    private final Favorites favorites;
    private final LocationBar locationBar;
    private CommanderAdapter destAdapter = null;
    public ColorsKeeper ck;
    private float density = 1;

    public Panels( FileCommander c_, boolean sxs_ ) {
        c = c_;
        density = c.getResources().getDisplayMetrics().density;
        ck = new ColorsKeeper( c );
        current = LEFT;
        c.setContentView( R.layout.picker_mode );
        mainView = c.findViewById( R.id.main );

        hsv = (LockableScrollView)c.findViewById( R.id.hrz_scroll );
        hsv.setHorizontalScrollBarEnabled( false );
        hsv.setSmoothScrollingEnabled( true );
        hsv.setOnTouchListener( this );
        hsv.setOverScrollMode( View.OVER_SCROLL_NEVER );

        panelsView = ( (PanelsView)c.findViewById( R.id.panels ) );
        panelsView.init( c.getWindowManager() );
        initList( LEFT );
        initList( RIGHT );

        favorites = new Favorites( c );
        locationBar = new LocationBar( c, this, favorites );

        setLayoutMode( sxs_ );
        // highlightCurrentTitle();

        View left_title = c.findViewById( headerIds[LEFT] );
        if( left_title != null ) {
            left_title.setOnClickListener( this );
            left_title.setOnLongClickListener( this );
        }
        View right_title = c.findViewById( headerIds[RIGHT] );
        if( right_title != null ) {
            right_title.setOnClickListener( this );
            right_title.setOnLongClickListener( this );
        }
        try {
            quickSearchBuf = new StringBuffer();
            quickSearchTip = Toast.makeText( c, "", Toast.LENGTH_SHORT );
        } catch( Exception e ) {
            c.showMessage( "Exception on creating quickSearchTip: " + e );
        }
        focus();
    }

    public final void showPickButton() {
        ImageButton pb = c.findViewById( R.id.pick );
        if( pb == null ) return;
        pb.setVisibility( View.VISIBLE );
        pb.setOnClickListener( this );
    }

    public final boolean getLayoutMode() {
        return sxs;
    }

    public final void setLayoutMode( boolean sxs_ ) {
        int cur_sort = -1, oth_sort = -1;
        CommanderAdapter cur_ca = getListAdapter( true );
        int sort_mode_mask = CommanderAdapter.MODE_SORT_DIR | CommanderAdapter.MODE_SORTING;
        if( cur_ca != null )
            cur_sort = cur_ca.getMode( sort_mode_mask );
        CommanderAdapter oth_ca = getListAdapter( false );
        if( oth_ca != null )
            oth_sort = oth_ca.getMode( sort_mode_mask );
        sxs = sxs_;
        SharedPreferences shared_pref = PreferenceManager.getDefaultSharedPreferences( c );
        applySettings( shared_pref, false );
        if( cur_sort != -1 )
            cur_ca.setMode( sort_mode_mask, cur_sort );
        if( oth_sort != -1 )
            oth_ca.setMode( sort_mode_mask, oth_sort );
        scroll_back = (int)( c.getWindowManager().getDefaultDisplay().getWidth() * 2. / 10 );
        if( panelsView != null )
            panelsView.setMode( sxs_ );
    }

    public final int getCurrent() {
        return current;
    }

    public final int getOpposite() {
        return opposite();
    }

    public final void showToolbar( boolean show ) {
        toolbarShown = show;
    }

    private final Drawable createButtonStates() {
        try {
            StateListDrawable states = new StateListDrawable();
            GradientDrawable bpd = new GradientDrawable();
            bpd.setColor( Utils.shiftBrightness( ck.btnColor, 0.7f ) );
            GradientDrawable bnd = new GradientDrawable();
            bnd.setColor( ck.btnColor );
            states.addState( new int[]{android.R.attr.state_pressed}, bpd );
            states.addState( new int[]{}, bnd );
            return states;
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
        return null;
    }

    public final void setToolbarButtons( CommanderAdapter ca, CommanderAdapter other ) {
        try {
            if( ca == null )
                return;
            if( toolbarShown ) {
                SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences( c );
                if( toolbar == null ) {
                    LayoutInflater inflater = (LayoutInflater)c.getSystemService( Context.LAYOUT_INFLATER_SERVICE );
                    View tb_scroll = inflater.inflate( R.layout.toolbar, (ViewGroup)mainView, false );
                    ViewGroup.LayoutParams tslp = tb_scroll.getLayoutParams();

                    String tb_height = sharedPref.getString( "tb_height", "0" );
                    if( tb_height.isEmpty() || "0".equals( tb_height ) )
                        tslp.height = LinearLayout.LayoutParams.WRAP_CONTENT;
                    else {
                        float tb_height_in = Utils.evalFrac( tb_height );
                        if( tb_height_in == 0f )
                            tslp.height = LinearLayout.LayoutParams.WRAP_CONTENT;
                        else
                            tslp.height = (int)( tb_height_in * c.getResources().getDisplayMetrics().ydpi );
                    }
                    ((ViewGroup)mainView).addView( tb_scroll );
                    toolbar = tb_scroll.findViewById( R.id.toolbar );
                    tbScroll = tb_scroll;
                    tbScroll.setBackgroundColor( ck.tbgColor );
                }
                if( toolbar == null ) {
                    Log.e( TAG, "Toolbar inflation has failed!" );
                    return;
                }
                toolbar.setVisibility( View.INVISIBLE );

                ViewGroup tb_holder = (ViewGroup)toolbar;
                tb_holder.removeAllViews();


                boolean show_hotkeys = sharedPref.getBoolean( "show_hotkeys", false );
                boolean show_icons   = sharedPref.getBoolean( "show_emoji", true );
                boolean show_caption = sharedPref.getBoolean( "show_caption", true );

                Utils.changeLanguage( c );
                ToolButtons tba = new ToolButtons();
                tba.restore( sharedPref, c, c.isActionBar() );
                //int bfs = fnt_sz + ( fingerFriendly ? 2 : 1 );
                int bfs = fnt_sz;
                for( int i = 0; i < tba.size(); i++ ) {
                    ToolButton tb = tba.get( i );
                    int bid = tb.getId();
                    if( !tb.isVisible() ) continue;
                    if( !ca.hasFeature( Tools.getFeature( bid ) ) ) continue;
                    if( bid == R.id.F5F6 && ( other == null || !other.hasFeature( Feature.REAL ) ) ) continue;
                    String caption = "";
                    if( show_hotkeys ) {
                        char ch = tb.getBoundKey();
                        if( ch != 0 )
                            caption = ch + " ";
                    }
                    if( show_icons ) caption += tb.getIcon() + " ";
                    if( show_caption ) caption += tb.getCaption();
                    LinearLayout.LayoutParams lllp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT );
                    Button b = null;
                    if( !ck.isButtonsDefault() ) {
                        b = new Button( c, null, fingerFriendly ? android.R.style.Widget_Holo_Button :
                                android.R.style.Widget_Button_Small );
                        int c_length = caption.length();
                        int hp = c_length <= 8 ? (int)( ( 15 - c_length ) ) : 4;
                        hp *= density;
                        int vp = fingerFriendly ? (int)( 13 * density ) : 6;
                        b.setPadding( hp, vp, hp, vp );
                        float bbb = Utils.getBrightness( ck.btnColor );
                        b.setTextColor( bbb > 0.8f ? 0xFF333333 : 0xFFF5F5F5 );
                        b.setTextSize( bfs );
                        Drawable bd = createButtonStates();
                        if( bd != null )
                            b.setBackgroundDrawable( bd );
                        else
                            b.setBackgroundResource( R.drawable.tool_button );
                        lllp.rightMargin = 2;
                    } else { // default
                        int style_id = fingerFriendly ? android.R.attr.buttonStyle :
                                android.R.attr.buttonStyleSmall;
                        b = new Button( c, null, style_id );
                        lllp.rightMargin = -2; // a button has invisible
                        // background around it
                    }
                    b.setLayoutParams( lllp );

                    b.setId( bid );
                    b.setFocusable( false );
                    b.setText( caption );
                    b.setOnClickListener( c );
                    tb_holder.addView( b );
                    if( !show_caption && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O )
                        b.setTooltipText( tb.getCaption() );

                }
                toolbar.setVisibility( View.VISIBLE );
            } else {
                if( toolbar != null )
                    toolbar.setVisibility( View.GONE );
            }
        } catch( Exception e ) {
            Log.e( TAG, "setToolbarButtons() exception", e );
        }
    }

    public final void focus() {
        list[current].focus();
    }

    // View.OnFocusChangeListener implementation
    @Override
    public void onFocusChange( View v, boolean f ) {
        //Log.v( TAG, "focus has changed " + (f?"to ":"from ") + v );
        ListView flv = list[opposite()].flv;
        boolean opp = flv == v;
        if( f && opp ) {
            setPanelCurrent( opposite(), true );
        }
    }

    public Favorites getFavorites() {
        return favorites;
    }

    public final boolean isCurrent( int q ) {
        return ( current == LEFT && q == LEFT ) || ( current == RIGHT && q == RIGHT );
    }

    private final void initList( int which ) {
        list[which] = new ListHelper( which, this );
        setPanelTitle( "", which );
    }

    public final void setPanelTitle( String s, int which ) {
        try {
            TextView title = (TextView)c.findViewById( titlesIds[which] );
            if( title != null ) {
                if( s == null ) {
                    title.setText( c.getString( R.string.fail ) );
                } else {
                    title.setText( Utils.unEscape( Favorite.screenPwd( s ) ) );
                    title.setTextSize( fnt_sz * 0.75f );
                }
            }
        } catch( Exception e ) {
            e.printStackTrace();
        }
    }

    private final void refreshPanelTitles() {
        try {
            CommanderAdapter cur_ca = getListAdapter( true );
            CommanderAdapter opp_ca = getListAdapter( false );
            if( cur_ca != null ) {
                setPanelTitle( cur_ca.toString(), current );
                list[current].updateSortingAndIcon();
            }
            if( opp_ca != null ) {
                setPanelTitle( opp_ca.toString(), opposite() );
                int sort = opp_ca.getMode( CommanderAdapter.MODE_SORTING | CommanderAdapter.MODE_SORT_DIR );
                list[opposite()].updateSortingAndIcon();
            }
            highlightCurrentTitle();
        } catch( Exception e ) {
            Log.e( TAG, "refreshPanelTitle()", e );
        }
    }

    private final void highlightCurrentTitle() {
        if( mainView == null )
            return;
        highlightTitle( opposite(), false );
        highlightTitle( current, true );
    }

    private final void highlightTitle( int which, boolean on ) {
        View  header = mainView.findViewById( headerIds[which] );
        TextView title = (TextView)mainView.findViewById( titlesIds[which] );
        if( header != null && title != null ) {
            int bg_color;
            if( on ) {
                bg_color = ck.selColor;
                String tt = title.getText().toString();
                if( tt.startsWith( "root:" ) )
                    bg_color = 0xFFFF0000;
            } else {
                // ttlColor is the user-configurable panel title background
                // (TTL_COLORS in prefs) — used directly, no blending.
                bg_color = ck.ttlColor;
            }
            // background now paints the whole merged header (path + icon +
            // description + sorting + status), not just the path text
            header.setBackgroundColor( bg_color );
            int text_color = Utils.getFocusTextColor( ck, on );
            title.setTextColor( text_color );
            // description/status/sorting live inside ListHelper; let it
            // apply the same focus-aware color to its own TextViews
            list[which].setFocused( on );
        } else
            Log.e( TAG, "header or title view was not found!" );
    }

    public final int getSingle( boolean touched ) {
        return list[current].getSingle( touched );
    }

    public final void setSelection( int i ) {
        setSelection( current, i, 0 );
    }

    public final void setSelection( int which, int i, int y_ ) {
        list[which].setSelection( i, y_ );
    }

    public final void setSelection( int which, String name ) {
        list[which].setSelection( name );
    }

    private final int opposite() {
        return 1 - current;
    }

    public final CommanderAdapter getListAdapter( boolean forCurrent ) {
        return list[forCurrent ? current : opposite()].getListAdapter();
    }

    public final int getWidth() {
        return mainView.getWidth();
    }

    public final void applyColors() {
        ck.restore();
        if( sxs ) {
            View div = mainView.findViewById( R.id.divider );
            if( div != null )
                div.setBackgroundColor( ck.divColor );
        }
        if( tbScroll != null )
            tbScroll.setBackgroundColor( ck.tbgColor );
        list[LEFT].applyColors( ck );
        list[RIGHT].applyColors( ck );

        ck.restoreTypeColors();
        CommanderAdapterBase.setTypeMaskColors( ck );
        highlightCurrentTitle();
    }

    public final void applySettings( SharedPreferences sharedPref, boolean init ) {
        try {
            applyColors();
            String fnt_sz_s = sharedPref.getString( "font_size", "14" );
            try {
                fnt_sz = Integer.parseInt( fnt_sz_s );
            } catch( NumberFormatException e ) {
            }

            String ffs = sharedPref.getString( "finger_friendly_a", "y" );
            boolean ff = false;
            if( "a".equals( ffs ) ) {
                Display disp = c.getWindowManager().getDefaultDisplay();
                Configuration config = c.getResources().getConfiguration();
                ff = config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_YES || disp.getWidth() < disp.getHeight();
            } else
                ff = "y".equals( ffs );

            setFingerFriendly( ff, fnt_sz );
            warnOnRoot = sharedPref.getBoolean( "prevent_root", true );
            rootOnRoot = sharedPref.getBoolean( "root_root", false );
            panels_sliding = sharedPref.getBoolean( "panels_sliding", true );
            hsv.setScrollable( panels_sliding );
            arrowsLegacy = sharedPref.getBoolean( "arrow_legc", false );
            volumeLegacy = sharedPref.getBoolean( "volume_legc", false );
            toolbarShown = sharedPref.getBoolean( "show_toolbar", true );
            selAtRight = sharedPref.getBoolean( Prefs.SEL_ZONE + "_right", false );
            selWidth = sharedPref.getInt( Prefs.SEL_ZONE + "_width", 20 ) / 100f;
            if( !init ) {
                list[LEFT].applySettings( sharedPref );
                list[RIGHT].applySettings( sharedPref );
                // setPanelCurrent( current );
            }
            setToolbarButtons( getListAdapter( true ), getListAdapter( false ) );
        } catch( Exception e ) {
            Log.e( TAG, "applySettings()", e );
        }
    }

    public int getSortingMode() {
        CommanderAdapter ca = getListAdapter( true );
        return ca != null ? ca.getMode( CommanderAdapter.MODE_SORTING ) : 0;
    }

    public void changeSorting( int criterion, boolean ascending ) {
        CommanderAdapter ca = getListAdapter( true );

        storeChosenItems();
        ca.setMode( CommanderAdapter.MODE_SORTING | CommanderAdapter.MODE_SORT_DIR,
                    criterion | ( ascending ? CommanderAdapter.SORT_ASC : CommanderAdapter.SORT_DSC ) );
        c.onPanelChange();
        reStoreChosenItems();
        list[current].adapterMode = ca.getMode() & ( CommanderAdapter.MODE_SORTING | CommanderAdapter.MODE_SORT_DIR );
        refreshPanelTitles();
    }

    public boolean areHiddenFilesShown() {
        CommanderAdapter ca = getListAdapter( true );
        int cur_mode = ca.setMode( 0, 0 );
        return ( cur_mode & CommanderAdapter.MODE_HIDDEN ) == CommanderAdapter.SHOW_MODE;
    }

    public void toggleHidden() {
        CommanderAdapter ca = getListAdapter( true );
        int cur_mode = ca.setMode( 0, 0 );
        int new_mode = ( cur_mode & CommanderAdapter.MODE_HIDDEN ) == CommanderAdapter.SHOW_MODE ?
                CommanderAdapter.HIDE_MODE : CommanderAdapter.SHOW_MODE;
        ca.setMode( CommanderAdapter.MODE_HIDDEN, new_mode );
        refreshList( current, true, null );
    }

    public void toggleDirSizes() {
        CommanderAdapter ca = getListAdapter( true );

        int cur_mode = ca.setMode( 0, 0 );
        int new_mode = ( cur_mode & CommanderAdapter.MODE_DIRSZ ) == CommanderAdapter.SHOW_DIRSZ ?
                CommanderAdapter.NO_DIRSZ : CommanderAdapter.SHOW_DIRSZ;
        ca.setMode( CommanderAdapter.MODE_DIRSZ, new_mode );
        refreshList( current, true, null );
    }

    public final void refreshLists( String posto ) {
        int was_current = current, was_opp = 1 - was_current;
        refreshList( current, true, posto );
        if( sxs )
            refreshList( was_opp, false, null );
        else
            list[was_opp].setNeedRefresh();
    }

    public final void refreshList( int which, boolean was_current, String posto ) {
        list[which].refreshList( was_current, posto );
    }

    public final void swapPanels() {
        ListAdapter left_a = list[LEFT].flv.getAdapter();
        ListAdapter right_a = list[RIGHT].flv.getAdapter();
        list[LEFT].flv.setAdapter( right_a );
        list[RIGHT].flv.setAdapter( left_a );
        boolean left_cur = current == LEFT;
        list[LEFT].refreshList( left_cur, null );
        list[RIGHT].refreshList( !left_cur, null );
    }

    public final void compareItems() {
        CommanderAdapter ca_cur, ca_ops;
        ca_cur = list[current].getListAdapter();
        ca_ops = list[opposite()].getListAdapter();
        if( !ca_cur.hasFeature( Feature.REAL ) ) return;
        if( !ca_ops.hasFeature( Feature.REAL ) ) return;

        SharedPreferences shared_pref = PreferenceManager.getDefaultSharedPreferences( c );
        boolean compare_content = shared_pref.getBoolean( "compare_content", false );
        boolean case_ignore = shared_pref.getBoolean( "case_ignore", false );

        FileComparer fc = new FileComparer( list[current].flv, list[opposite()].flv );
        fc.setOptions( compare_content, case_ignore );
        fc.execute( ca_cur, ca_ops );
    }

    public final void redrawLists() {
        list[current].askRedrawList();
        if( sxs )
            list[opposite()].askRedrawList();
        list[current].focus();
    }

    public void setFingerFriendly( boolean finger_friendly, int font_size ) {
        fingerFriendly = finger_friendly;
        try {
            for( int p = LEFT; p <= RIGHT; p++ ) {
                TextView title = (TextView)c.findViewById( titlesIds[p] );
                if( title != null ) {
                    title.setTextSize( font_size * 0.75f );
                    int vm = 0, hm = (int)( 6 * density );
                    if( finger_friendly )
                        vm = (int)( 2 * density );
                    else
                        vm = (int)( 4 * density );
                    title.setPadding( hm, vm, hm, vm );
                }
                if( list[p] != null )
                    list[p].setFingerFriendly( finger_friendly );
            }
            locationBar.setFingerFriendly( finger_friendly, font_size, density );
        } catch( Exception e ) {
            Log.e( TAG, null, e );
        }
    }

    public final void makeOtherAsCurrent() {
        CommanderAdapter ca = getListAdapter( true );
        NavigateInternal( opposite(), ca.getUri(), ca.getCredentials(), null );
    }

    public final void makeOtherAsCurDirItem() {
        CommanderAdapter ca = getListAdapter( true );
        int pos = list[current].getCurPos();
        Uri u = ca.getItemUri( pos );
        if( u != null )
            NavigateInternal( opposite(), u, ca.getCredentials(), null );
    }

    public final void togglePanelsMode() {
        setLayoutMode( !sxs );
    }

    public final void togglePanels( boolean refresh ) {
        // Log.v( TAG, "toggle" );
        setPanelCurrent( opposite() );
    }

    public final void setPanelCurrent( int which ) {
        setPanelCurrent( which, false );
    }

    public final void setPanelCurrent( int which, boolean dont_focus ) {
        //Log.v( TAG, "setPanelCurrent: " + which + " dnf:" + dont_focus );
        c.onPanelChange();
        if( !dont_focus && panelsView != null ) {
            panelsView.setMode( sxs );
        }
        current = which;
        if( !sxs ) {
            final int dir = current == LEFT ? HorizontalScrollView.FOCUS_LEFT : HorizontalScrollView.FOCUS_RIGHT;
            //Log.v( TAG, "do fullScroll: " + dir );
            if( dont_focus )
                hsv.fullScroll( dir );
            else {
                hsv.post( new Runnable() {
                    public void run() {
                        //Log.v( TAG, "async fullScroll: " + dir );
                        hsv.fullScroll( dir );
                    }
                } );
            }
        } else if( !dont_focus )
            list[current].focus();
        highlightCurrentTitle();
        setToolbarButtons( getListAdapter( true ), getListAdapter( false ) );
        if( list[current].needRefresh() )
            refreshList( current, false, null );
    }

    public final void showSizes( boolean touched ) {
        storeChosenItems();
        getListAdapter( true ).reqItemsSize( getMultiple( touched ) );
    }

    public final void showSums( int what ) {
        try {
            SparseBooleanArray cis = getMultiple( true );
            int cur_item = getSingle( true );
            if( cur_item <= 0 )
                return;
            CommanderAdapter ca = getListAdapter( true );
            Uri item_uri = ca.getItemUri( cur_item );
            if( item_uri == null )
                return;
            CommanderAdapter.Item item = (CommanderAdapter.Item)( (Adapter)ca ).getItem( cur_item );
            c.startEngine( new ChecksumEngine( c.getContext(), ca, item_uri, item, what ) );
        } catch( Exception e ) {
        }
    }

    public final void checkItems( boolean set, String mask, boolean dir, boolean file ) {
        list[current].checkItems( set, mask, dir, file );
    }

    public final void checkBetween() {
        if( !list[current].checkItemsBetween() )
            c.showError( c.getString( R.string.select2 ) );
    }

    class NavDialog implements OnClickListener {
        protected int which;
        protected String posTo, old_path;
        protected Uri uri, old_uri;

        NavDialog( Context c, int which_, Uri uri_, String posTo_, Uri old_uri_ ) {
            which = which_;
            uri = uri_;
            posTo = posTo_;
            old_uri = old_uri_;
            LayoutInflater factory = LayoutInflater.from( c );
            new AlertDialog.Builder( c )
                    .setTitle( R.string.confirm )
                    .setView( factory.inflate( R.layout.rootmpw, null ) )
                    // .setMessage( c.getString( R.string.nav_warn, uri ) )
                    .setPositiveButton( R.string.dialog_ok, this )
                    .setNeutralButton( R.string.dialog_cancel, this )
                    .setNegativeButton( R.string.home, this ).show();
        }

        @Override
        public void onClick( DialogInterface idialog, int whichButton ) {
            if( whichButton == DialogInterface.BUTTON_POSITIVE ) {
                Panels.this.warnOnRoot = false;
                proceedToRestricted( which, uri, posTo );
            } else if( whichButton == DialogInterface.BUTTON_NEUTRAL && old_uri != null ) {
                NavigateInternal( which, old_uri, null, null );
            } else {
                uri = Uri.parse( HomeAdapter.DEFAULT_LOC );
                NavigateInternal( which, uri, null, null );
            }
            idialog.dismiss();
        }
    }

    protected final void proceedToRestricted( int which, Uri uri, String posTo ) {
        try {
            if( rootOnRoot )
                uri = uri.buildUpon().scheme( "root" ).build();
            else {  // SAF
                String path = uri.getPath();
                if( path != null && path.contains( "/Android" ) ) {
                    Uri saf_uri = SAFAdapter.getBestUri( c, path );
                    if( saf_uri == null ) Uri.parse( HomeAdapter.DEFAULT_LOC );
                    c.Navigate( saf_uri, null, null );
                    return;
                }
            }
            NavigateInternal( which, uri, null, posTo );
        } catch( Exception e ) {
            Log.e( TAG, "URI: " + uri, e );
        }
    }

    protected final boolean isSafeLocation( String path ) {
        if( path == null ) return false;
        if( rootOnRoot ) return path.length() > 1;
        if( android.os.Build.VERSION.SDK_INT <= Build.VERSION_CODES.P )
            return path.startsWith( DEFAULT_LOC ) || path.startsWith( "/sdcard/" ) || path.startsWith( "/mnt/" ) || path.matches( "/storage/[-0-9A-F]{2,}" );
        else if( android.os.Build.VERSION.SDK_INT > Build.VERSION_CODES.S &&
                Utils.mbAddSl( path ).endsWith( "Android/data/" ) ) // simulation by enumerating apps
            return true;
        else {
            try {
                File fp = new File( path );
                if( !fp.canRead() ) return false;
                return fp.listFiles() != null;
            } catch( Exception e ) {
                return false;
            }
        }
    }

    public final void Navigate( int which, Uri uri, Credentials crd, String posTo ) {
        if( uri == null )
            return;
        String scheme = uri.getScheme(), path = uri.getPath();

        if( !( ( scheme == null || scheme.equals( "file" ) ) && ( path == null || !isSafeLocation( path ) ) ) ) {
            NavigateInternal( which, uri, crd, posTo );
            return;
        }
        if( warnOnRoot ) {
            CommanderAdapter ca = list[which].getListAdapter();
            Uri cur_uri = ca != null ? ca.getUri() : null;
            try {
                new NavDialog( c, which, uri, posTo, cur_uri );
            } catch( Exception e ) {
                Log.e( TAG, "Navigate()", e );
            }
            return;
        } else
            proceedToRestricted( which, uri, posTo );
    }

    private final void NavigateInternal( int which, Uri uri, Credentials crd, String posTo ) {
        ListHelper list_h = list[which];
        list_h.Navigate( uri, crd, posTo, which == current );
    }

    public final void recoverAfterRefresh( String item_name, int which ) {
        try {
            if( which >= 0 )
                list[which].recoverAfterRefresh( item_name );
            else
                list[current].recoverAfterRefresh( which == current );
            refreshPanelTitles();
            // setPanelCurrent( current, true ); the current panel is set by set
            // focus
        } catch( Exception e ) {
            Log.e( TAG, "refreshList()", e );
        }
    }

    public void login( Credentials crd, int which_panel ) {
        if( which_panel < 0 )
            which_panel = current;
        CommanderAdapter ca = list[which_panel].getListAdapter();
        if( ca != null ) {
            ca.setCredentials( crd );
            list[which_panel].refreshList( true, null );
        }
    }

    public void cancelLogin( int which_panel ) {
        if( which_panel < 0 )
            which_panel = current;
        list[which_panel].mbNavigate( Uri.parse( "home:" ), null, null, which_panel == current );
    }

    public final void terminateOperation() {
        CommanderAdapter a = getListAdapter( true );
        a.terminateOperation();
        if( a == destAdapter )
            destAdapter = null;
        CommanderAdapter p = getListAdapter( false );
        p.terminateOperation();
        if( p == destAdapter )
            destAdapter = null;
        if( null != destAdapter ) {
            destAdapter.terminateOperation();
            destAdapter = null;
        }
    }

    public final void Destroy() {
        Log.i( TAG, "Destroing adapters" );
        try {
            CommanderAdapter passive = getListAdapter( false );
            if( passive != null ) passive.prepareToDestroy();
            CommanderAdapter active = getListAdapter( true );
            if( active != null ) active.prepareToDestroy();
        } catch( Exception e ) {
            e.printStackTrace();
        }
    }

    Pair<Uri, String> getOpenableUri( boolean cur_panel, int pos, boolean to_open, boolean immediate ) {
        CommanderAdapter ca = getListAdapter( cur_panel );
        if( ca == null ) return null;
        return Utils.getOpenableUri( c.getContext(), ca, pos, to_open, immediate );
    }

    // called from context menu only
    public final void tryToSend() {
        CommanderAdapter ca = getListAdapter( true );
        if( ca == null ) return;
        SparseBooleanArray cis = getMultiple( true );
        int num = cis.size();
        if( num > 1 ) {
            if( !ca.hasFeature( Feature.SEND ) ) {
                c.showError( c.getString( R.string.on_fs_only ) );
                return;
            }
            ArrayList<Uri> uris = new ArrayList<Uri>();
            Intent in = new Intent();
            in.setAction( android.content.Intent.ACTION_SEND_MULTIPLE );
            in.setType( Utils.MIME_ALL );
            for( int i = 0; i < num; i++ ) {
                if( cis.valueAt( i ) ) {
                    int pos = cis.keyAt( i );
                    Pair<Uri, String> uri_name = getOpenableUri( true, pos, false, false );
                    if( uri_name == null ) {
                        Log.e( TAG, "Can't obtain an URI to send, pos=" + pos );
                        continue;
                    }
                    uris.add( uri_name.first );
                }
            }
            in.putParcelableArrayListExtra( Intent.EXTRA_STREAM, uris );
            in.addFlags( Intent.FLAG_GRANT_READ_URI_PERMISSION );
            c.startActivity( Intent.createChooser( in, c.getString( R.string.send_title ) ) );
        } else {
            int pos = getSingle( true );
            if( pos < 0 ) return;
            Pair<Uri, String> uri_name = getOpenableUri( true, pos, false, true );
            if( uri_name == null ) {
                Log.e( TAG, "Can't obtain an URI to send, pos=" + pos );
                c.showError( c.getContext().getString( R.string.cant_open ) );
                return;
            }
            Intent in = new Intent( Intent.ACTION_SEND );
            in.addFlags( Intent.FLAG_GRANT_READ_URI_PERMISSION );
            String fn = uri_name.second;
            String ext = Utils.getFileExt( fn );
            String mime = Utils.str( ext ) ? Utils.getMimeByExt( ext ) : Utils.MIME_ALL;
            in.setType( mime );
            in.putExtra( Intent.EXTRA_SUBJECT, fn );
            in.putExtra( Intent.EXTRA_STREAM, uri_name.first );
            c.startActivity( Intent.createChooser( in, c.getString( R.string.send_title ) ) );
        }
    }

    public final void tryToOpen() {
        int pos = getSingle( true );
        if( pos < 0 ) return;
        Pair<Uri, String> uri_name = getOpenableUri( true, pos, true, true );
        if( uri_name == null ) {
            Log.e( TAG, "Can't obtain an URI to open, pos=" + pos );
            c.showError( c.getContext().getString( R.string.cant_open ) );
            return;
        }
        final Intent intent = new Intent( Intent.ACTION_VIEW );
        intent.addFlags( Intent.FLAG_GRANT_READ_URI_PERMISSION
                       | Intent.FLAG_GRANT_WRITE_URI_PERMISSION );
        String mime = Utils.getMimeByExt( Utils.getFileExt( uri_name.second ) );
        if( mime != null && !Utils.MIME_ALL.equals( mime ) && !mime.startsWith( "application/" ) ) {
            intent.setDataAndType( uri_name.first, mime.substring( 0, mime.indexOf( '/' ) + 1 ) + "*" );
            Log.d( TAG, "Open intent: " + intent );
            c.startActivity( Intent.createChooser( intent, c.getString( R.string.open_title ) ) );
            return;
        }
        final Uri uri = uri_name.first;
        AlertDialog.Builder builder = new AlertDialog.Builder( c );
        builder.setTitle( c.getString( R.string.open_as ) );
        String[] categories = {"audio", "video", "image", "text", "???"};
        builder.setItems( categories, new DialogInterface.OnClickListener() {
            @Override
            public void onClick( DialogInterface dialog, int which ) {
                String mime;
                switch( which ) {
                    case 0: mime = "audio/*";       break;
                    case 1: mime = "video/*";       break;
                    case 2: mime = "image/*";       break;
                    case 3: mime = "text/*";        break;
                    default:mime = "*/*";
                }
                intent.setDataAndType( uri, mime );
                Log.d( TAG, "Open as intent: " + intent );
                c.startActivity( Intent.createChooser( intent, c.getString( R.string.open_title ) ) );
            }
        } );
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    public final void copyName() {
        try {
            CommanderAdapter ca = getListAdapter( true );
            if( ca == null )
                return;
            ClipboardManager clipboard = (ClipboardManager)c.getSystemService( Context.CLIPBOARD_SERVICE );
            int pos = getSingle( true );
            if( pos >= 0 ) {
                String in = ca.getItemName( pos, true );
                if( in != null ) {
                    if( in.startsWith( RootAdapter.DEFAULT_LOC ) )
                        in = Uri.parse( in ).getPath();
                    clipboard.setText( in );
                }
            }
        } catch( Exception e ) {
            e.printStackTrace();
        }
    }

    public final Uri getFolderUriWithAuth( boolean active ) {
        CommanderAdapter ca = getListAdapter( active );
        if( ca == null ) return null;
        Uri u = ca.getUri();
        if( u != null ) {
            Credentials crd = ca.getCredentials();
            if( crd != null )
                return Utils.getUriWithAuth( u, crd );
        }
        return u;
    }
    public final Credentials getCredentials( boolean active ) {
        CommanderAdapter ca = getListAdapter( active );
        if( ca == null ) return null;
        return ca.getCredentials();
    }

    public final void createDesktopShortcut() {
        int pos = getSingle( true );
        Intent on_tap_intent = new Intent( Intent.ACTION_VIEW );
        on_tap_intent.setFlags( Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP );
        on_tap_intent.addFlags( Intent.FLAG_GRANT_READ_URI_PERMISSION );
        Uri uri;
        String name;
        String mime;
        int    dr_id;

        CommanderAdapter ca = getListAdapter( true );
        CommanderAdapter.Item item = (CommanderAdapter.Item)((CommanderAdapterBase)ca).getItem( pos );
        if( item.dir ) {
            mime = "inode/directory";
            uri = item.getUri();
            name = item.name;
            dr_id = R.drawable.folder;
        } else {
            Pair<Uri, String> uri_name = getOpenableUri( true, pos, true, false );
            if( uri_name == null ) {
                Log.e( TAG, "Can't obtain an URI to be used in a shortcut, pos=" + pos );
                c.showError( c.getContext().getString( R.string.fail ) );
                return;
            }
            uri  = uri_name.first;
            name = uri_name.second;
            String ext = Utils.getFileExt( name );
            mime = Utils.getMimeByExt( ext );

            dr_id = CommanderAdapterBase.getIconId( name );
        }
        on_tap_intent.setDataAndType( uri, mime );

        if( android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ) {
            Parcelable ip = ForwardCompat.createIcon( c, dr_id );
            ForwardCompat.makeShortcut( c, on_tap_intent, name, ip );
            return;
        }

        Intent intent = new Intent();
        intent.putExtra( Intent.EXTRA_SHORTCUT_INTENT, on_tap_intent );
        intent.putExtra( Intent.EXTRA_SHORTCUT_NAME, name );

        Parcelable iconResource = Intent.ShortcutIconResource.fromContext( c, dr_id );
        intent.putExtra( Intent.EXTRA_SHORTCUT_ICON_RESOURCE, iconResource );
        intent.setAction( "com.android.launcher.action.INSTALL_SHORTCUT" );
        c.sendBroadcast( intent );
    }

    public final void addToFavorites( Uri u, Credentials crd, String comment ) {
        favorites.addToFavorites( u, crd, comment );
    }

    public final void addCurrentToFavorites() {
        CommanderAdapter ca = getListAdapter( true );
        if( ca == null )
            return;
        Uri u = ca.getUri();
        favorites.addToFavorites( u, ca.getCredentials(), null );
        c.showMessage( c.getString( R.string.fav_added, Favorite.screenPwd( u ) ) );
    }

    public final void faveSelected() {
        CommanderAdapter ca = getListAdapter( true );
        if( ca == null ) return;
        int pos = getSingle( true );
        if( pos < 0 ) return;
        CommanderAdapter.Item item = (CommanderAdapter.Item)( (ListAdapter)ca ).getItem( pos );
        Uri u = ca.getItemUri( pos );
        if( u == null ) return;
        favorites.addToFavorites( u, ca.getCredentials(), item.dir ? 0 : Favorite.FLG_FILE );
        c.showMessage( c.getString( R.string.fav_added, Favorite.screenPwd( u ) ) );
    }

    public final void openForEdit( String file_name, boolean touched ) {
        CommanderAdapter ca = getListAdapter( true );
        if( ca == null || !ca.hasFeature( Feature.F4 ) ) {
            c.showMessage( c.getString( R.string.edit_err ) );
            return;
        }
        if( ca instanceof FavsAdapter ) {
            FavsAdapter fa = (FavsAdapter)ca;
            int pos = getSingle( true );
            if( pos > 0 )
                fa.editItem( pos );
            return;
        }
        Uri u = null;
        try {
            long size = 0;
            if( !Utils.str( file_name ) ) {
                int pos = getSingle( touched );
                CommanderAdapter.Item item = (CommanderAdapter.Item)( (ListAdapter)ca ).getItem( pos );
                if( item == null ) {
                    c.showError( c.getString( R.string.cant_open ) );
                    return;
                }
                if( item.dir ) {
                    c.showError( c.getString( R.string.cant_open_dir, item.name ) );
                    return;
                }
                size = item.size;
                file_name = item.name;
                u = ca.getItemUri( pos );
            } else
                u = Uri.parse( file_name );
            if( u == null )
                return;
            String mime = Utils.getMimeByExt( Utils.getFileExt( file_name ) );
            if( !Utils.str( mime ) || Utils.MIME_ALL.equals( mime ) || mime.startsWith( "application/" ) )
                mime = "text/plain";

            u = u.buildUpon().encodedPath( u.getEncodedPath().replace( " ", "%20" ) ).build();

            Credentials crd = ca.getCredentials();
            if( crd != null ) {
                String username = crd.getUserName();
                StreamProvider.storeCredentials( c, crd, u );
                u = Utils.updateUserInfo( u, username );
            }
            Intent in = new Intent();
            Uri content_uri = null;
            if( ContentResolver.SCHEME_CONTENT.equals( u.getScheme() ) )
                content_uri = u;
            else if( ca instanceof FSAdapter ) {
                SharedPreferences shared_pref = PreferenceManager.getDefaultSharedPreferences( c );
                boolean use_content = shared_pref.getBoolean( "open_content",
                        android.os.Build.VERSION.SDK_INT > Build.VERSION_CODES.M );
                if( use_content )
                    content_uri = FileProvider.makeURI( u.getPath() );
                else if( "file".equals( u.getScheme() ) )
                    content_uri = u;
                else
                    content_uri = u.buildUpon().scheme( "file" ).authority( "" ).build();
            } else
                content_uri = StreamProvider.put( u, file_name, mime, size );
            in.setDataAndType( content_uri, mime );
            in.addFlags( Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                         Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS );
            in.putExtra( "filename", file_name );
            in.putExtra( "size", size );

            PackageManager pm = c.getPackageManager();
            List<ResolveInfo> res_infos = null;
            String[] actions = { Intent.ACTION_EDIT, Intent.ACTION_VIEW };
            boolean some_external = false;
            for( int at = 0; at < actions.length; at++ ) {
                in.setAction( actions[at] );
                res_infos = pm.queryIntentActivities( in, 0 );
                for( ResolveInfo ri : res_infos ) {
                    if( ri.activityInfo != null && !ri.activityInfo.name.startsWith( "com.ghostsq.commander" ) ) {
                        some_external = true;
                        break;
                    }
                }
                if( some_external ) break;
            }
            if( some_external ) {
                Log.d( TAG, "Open uri " + u.toString() + " intent: " + in.toString() );
                try {
                    c.startActivity( in );
                    return;
                } catch( Exception e ) {
                    Log.e( TAG, u.toString(), e );
                }
            }
/*
            if( res_infos.size() == 0 ) {
                c.showMessage( c.getString( R.string.edit_err ) );
                return;
            }
*/
            in.setAction( Intent.ACTION_EDIT );
            in.setDataAndType( u, "text/plain" );
            in.setClassName( c.getApplicationContext(), Editor.class.getName() );
            if( crd != null )
                in.putExtra( Credentials.KEY, crd );
            c.startActivity( in );
        } catch( Exception e ) {
            c.showMessage( c.getString( R.string.failed ) + e.getMessage() );
            Log.e( TAG, u.toString(), e );
        }
    }

    public final void openForView( boolean touched ) {
        int pos = getSingle( touched );
        if( pos < 0 )
            return;
        String name = null;
        try {
            CommanderAdapter ca = getListAdapter( true );
            Uri uri = ca.getItemUri( pos );
            if( uri == null )
                return;
            CommanderAdapter.Item item = (CommanderAdapter.Item)( (ListAdapter)ca ).getItem( pos );
            if( item.dir ) {
                showSizes( touched );
                return;
            }
            String mime = item.mime;
            if( !Utils.str( mime ) )
                mime = Utils.getMimeByExt( Utils.getFileExt( item.name ) );
            if( !Utils.str( mime ) )
                mime = "application/octet-stream";
            Intent in = createViewIntent( uri, item.name, mime, ca.getCredentials() );
            if( mime.startsWith( "image/" ) ) {
                in.setClass( c, PictureViewer.class );
                addImageViewExtras( in, ca, pos );
            } else
                in.setClass( c, TextViewer.class );
            c.startActivity( in );
        } catch( Exception e ) {
            Log.e( TAG, "Can't view the file " + name, e );
        }
    }

    public final Intent createViewIntent( Uri uri, String name, String mime, Credentials crd ) {
        Intent in = new Intent( Intent.ACTION_VIEW );
        in.setDataAndType( uri, mime );
        in.putExtra( "filename", name );
        if( crd != null )
            in.putExtra( Credentials.KEY, crd );
        return in;
    }

    private Intent addImageViewExtras( Intent in, CommanderAdapter ca, int pos ) {
        in.putExtra( "position", pos );
        in.putExtra( "mode", ca.getMode() );
        in.putExtra( "parentUri", ca.getUri() );
        FilterProps fp = ca.getFilter();
        if( fp != null )
            in.putExtra( "filter", fp );
        return in;
    }

    public final String getActiveItemsSummary( boolean touched, ArrayList<String> names ) {
        return list[current].getActiveItemsSummary( touched, names );
    }

    public final String getActiveItemsSummary( boolean touched ) {
        return list[current].getActiveItemsSummary( touched, null );
    }

    public final SparseBooleanArray getMultiple( boolean touch ) {
        return list[current].getMultiple( touch );
    }

    /**
     * @return 0 - nothing selected, 1 - a file, -1 - a folder, otherwise the
     *         number
     */
     /*  public final int getNumItemsSelectedOrChecked() { int
              checked = getNumItemsChecked(); return checked; }
     */
    public final String getSelectedItemName( boolean touched ) {
        return getSelectedItemName( false, touched );
    }

    public final String getSelectedItemName( boolean full, boolean touched ) {
        int pos = getSingle( touched );
        return pos < 0 ? null : getListAdapter( true ).getItemName( pos, full );
    }

    public final long getSelectedItemTime( boolean touched ) {
        int pos = getSingle( touched );
        if( pos <= 0 ) return -1;
        CommanderAdapter.Item item = (CommanderAdapter.Item)( (ListAdapter)getListAdapter( true ) ).getItem( pos );
        return item != null && item.date != null ? item.date.getTime() : -1;
    }

    public final void quickSearch( char ch ) {
        CommanderAdapter a = getListAdapter( true );
        if( a != null ) {
            quickSearchBuf.append( ch );
            String s = quickSearchBuf.toString();
            showTip( s );

            int n = ( (ListAdapter)a ).getCount();
            for( int i = 1; i < n; i++ ) {
                String name = a.getItemName( i, false );
                if( name == null )
                    continue;
                if( s.regionMatches( true, 0, name, 0, s.length() ) ) {
                    setSelection( i );
                    return;
                }
            }
        }
    }

    private final void showTip( String s ) {
        try {
            if( !sxs || current == LEFT )
                quickSearchTip.setGravity( Gravity.BOTTOM | Gravity.LEFT, 5, 10 );
            else
                quickSearchTip.setGravity( Gravity.BOTTOM, 10, 10 );
            quickSearchTip.setText( s );
            quickSearchTip.show();
        } catch( RuntimeException e ) {
            c.showMessage( "RuntimeException: " + e );
        }
    }

    public final void resetQuickSearch() {
        quickSearchBuf.delete( 0, quickSearchBuf.length() );
    }

    public final void openGoPanel() {
        locationBar.openGoPanel( current, getFolderUriWithAuth( true ) );
    }

    public final void operationFinished() {
        if( null != destAdapter )
            destAdapter = null;
    }

    public final void copyFiles( String dest, boolean move, boolean touch ) {
        try {
            final String SLS = File.separator;
            final char   SLC = SLS.charAt( 0 );
            if( dest == null )
                return;
            SparseBooleanArray items = getMultiple( touch );
            CommanderAdapter cur_adapter = getListAdapter( true );
            Uri dest_uri = Uri.parse( dest );
            if( Favorite.isPwdScreened( dest_uri ) ) {
                dest_uri = Favorite.borrowPassword( dest_uri, getFolderUriWithAuth( false ) );
                if( dest_uri == null ) {
                    c.showError( c.getString( R.string.inv_dest ) );
                    return;
                }
            }
            if( Utils.getCount( items ) == 1 && !"..".equals( dest ) ) {
                int pos = Utils.getPosition( items, 0 );
                if( pos <= 0 )
                    return;
                final boolean COPY = true;
                boolean make_copy = false;
                if( dest.indexOf( SLC ) < 0 )  // just a file name to copy to
                    make_copy = true;
                else if( cur_adapter.hasFeature( Feature.FS ) && dest.charAt( dest.length()-1 ) != SLC ) {
                    if( dest.charAt( 0 ) == SLC ) { // local FS
                        File dest_file = new File( dest );
                        if( !dest_file.exists() || !dest_file.isDirectory() )
                            make_copy = true;
                    }
                }
                if( make_copy ) {
                    cur_adapter.renameItem( pos, dest, COPY );
                    return;
                }
            }
            if( dest.charAt( 0 ) != SLC && dest.indexOf( ':' ) < 0 ) {
                // TODO: use the source adapter as dest
                Log.d( TAG, "copy to a subdir: " + dest );
                c.showError( c.getContext().getString( R.string.not_supported ) );
                return;
            } else {
                CommanderAdapter oth_adapter = getListAdapter( false );
                Uri oth_uri = null;
                boolean create_new_adapter = false;
                if( oth_adapter == null )
                    create_new_adapter = true;
                else {
                    oth_uri = oth_adapter.getUri();
                    create_new_adapter = oth_uri == null ||
                            !Utils.equals( oth_uri.getScheme(), dest_uri.getScheme() ) ||
                            !Utils.equals( oth_uri.getHost(), dest_uri.getHost() ) ||
                            !Utils.equals( Utils.mbAddSl( oth_uri.getPath() ), Utils.mbAddSl( dest_uri.getPath() ) );
                }
                if( create_new_adapter ) {
                    if( "..".equals( dest ) ) {
                        oth_adapter = CA.CreateAdapter( cur_adapter.getUri(), c );
                        Uri cur_uri = cur_adapter.getUri();
                        String p = cur_uri.getEncodedPath();
                        if( !Utils.str( p ) || "/".equals( p ) ) {
                            c.showError( c.getString( R.string.inv_dest ) );
                            return;
                        }
                        int len_ = p.length() - 1;
                        if( p.charAt( len_ ) == SLC )
                            p = p.substring( 0, len_ );
                        p = p.substring( 0, p.lastIndexOf( SLC ) );
                        if( p.length() == 0 )
                            p = "/";
                        oth_adapter.setUri( cur_uri.buildUpon().encodedPath( p ).build() );
                        oth_adapter.setCredentials( cur_adapter.getCredentials() );
                    } else {
                        if( dest_uri == null ) {
                            c.showError( c.getString( R.string.inv_dest ) );
                            return;
                        }
                        oth_adapter = CA.CreateAdapter( dest_uri, c );
                        if( oth_adapter == null ) {
                            c.showError( c.getString( R.string.inv_dest ) );
                            return;
                        }
                        if( oth_uri != null ) {
                            oth_adapter.setUri( oth_uri );  // let FTP adapter to copy the additional parameters
                            oth_adapter.setMode( CommanderAdapter.MODE_CLONE, CommanderAdapter.CLONE_MODE );
                        }
                        oth_adapter.setUri( dest_uri );
                    }
                }
                destAdapter = oth_adapter;
            }
            if( destAdapter == null || !destAdapter.hasFeature( Feature.REAL ) ) {
                c.showError( c.getString( R.string.canceled ) );
                return;
            }
            cur_adapter.copyItems( items, destAdapter, move );
            // TODO: getCheckedItemPositions() returns an empty array after a
            // failed operation. why?
            list[current].flv.clearChoices();
        } catch( Exception e ) {
            Log.e( TAG, "copyFiles()", e );
            c.showError( e.getLocalizedMessage() );
        }
    }

    public final void renameItem( String new_name, boolean touched ) {
        CommanderAdapter adapter = getListAdapter( true );
        int pos = getSingle( touched );
        if( pos >= 0 ) {
            adapter.renameItem( pos, new_name, false );
            list[current].setSelection( new_name );
        }
    }

    public final void renameItems( String pattern, String replace_to ) {
        SparseBooleanArray items = getMultiple( false );
        CommanderAdapter cur_adapter = getListAdapter( true );
        cur_adapter.renameItems( items, pattern, replace_to );
    }

    public final Intent prepareMultRenameIntent( Intent intent ) {
        CommanderAdapter ca = getListAdapter( true );
        SparseBooleanArray cis = getMultiple( false );
        int num = cis.size();
        ArrayList<String> names = new ArrayList<String>();
        for( int i = 0; i < num; i++ ) {
            if( cis.valueAt( i ) ) {
                names.add( ca.getItemName( cis.keyAt( i ), false ) );
            }
        }
        intent.putStringArrayListExtra( c.getPackageName() + ".TO_RENAME_LIST", names );
        return intent;
    }

    public boolean isFiltered() {
        CommanderAdapter ca = getListAdapter( true );
        return ca.getFilter() != null;
    }

    public boolean cancelFilter() {
        CommanderAdapter ca = getListAdapter( true );
        if( ca.getFilter() != null ) {
            ca.cancelFilter();
            list[current].refreshList( true, null );
            c.showInfo( c.getString( R.string.filter_canceled ) );
            return true;
        }
        return false;
    }

    public void setFilter( FilterProps filter ) {
        CommanderAdapter ca = getListAdapter( true );
        ca.setFilter( filter );
        list[current].refreshList( true, null );
    }

    public void createNewFile( String fileName ) {
        CommanderAdapter ca = getListAdapter( true );
        if( !ca.createFile( fileName ) ) return;
        boolean just_name = fileName.indexOf( '/' ) < 0;
        String file_name = just_name ? fileName :
               fileName.substring( fileName.lastIndexOf( '/' ) + 1 );
        refreshLists( file_name );
        setSelection( current, file_name );
        if( ca instanceof FSAdapter ) {
            String file_path;
            if( just_name ) {
                String dirName = ca.toString();
                file_path = Utils.mbAddSl( dirName ) + fileName;
            } else
                file_path = fileName;
            openForEdit( file_path, false );
        }
    }

    public final void createFolder( String new_name ) {
        getListAdapter( true ).createFolder( new_name );
        list[current].setSelection( new_name );
    }

    public final void createZip( String new_zip_name, boolean touch, String pw, String enc ) {
        if( new_zip_name == null || new_zip_name.length() == 0 ) return;
        if( !new_zip_name.startsWith( "/" ) ) {
            c.showError( c.getString( R.string.on_fs_only ) );
            return;
        }
        Log.d( TAG, "new ZIP path: " + new_zip_name );
        CommanderAdapter ca = getListAdapter( true );
        SparseBooleanArray cis = getMultiple( touch );

        if( cis == null || cis.size() == 0 ) {
            ca = getListAdapter( false );
            cis = list[opposite()].getMultiple( touch );
            if( cis == null || cis.size() == 0 ) {
                c.showError( c.getString( R.string.op_not_alwd ) );
                return;
            }
        }
        ZipAdapter za = new ZipAdapter( c );
        za.Init( c );
        za.prepNewZip( new_zip_name, pw, enc );
        ca.copyItems( cis, za, false );
    }

    public final void unpackZip() {
        CommanderAdapter ca = getListAdapter( true );
        if( ca instanceof FSAdapter ) {
            FSAdapter fsa = (FSAdapter)ca;
            SparseBooleanArray cis = getMultiple( true );
            if( cis == null || cis.size() == 0 ) return;
            File[] files = fsa.bitsToFiles( cis );
            if( files == null || files.length == 0 ) return;
            if( !".zip".equalsIgnoreCase( Utils.getFileExt( files[0].getName() ) ) ) return;
            ZipAdapter z = new ZipAdapter( c );
            z.Init( c );
            z.unpackZip( files[0] );
        }
    }

    public final void deleteItems( boolean touch, boolean to_trashcan ) {
        SparseBooleanArray cis = getMultiple( touch );
        CommanderAdapter ca = getListAdapter( true );
        ca.setMode( CommanderAdapter.MODE_TRASH, to_trashcan ? CommanderAdapter.TOTRASH_MODE : CommanderAdapter.DELETE_MODE );
        if( ca.deleteItems( cis ) ) {
            int lowest = Integer.MAX_VALUE;
            for( int i = 0; i < cis.size(); i++ ) {
                if( !cis.valueAt( i ) ) continue;
                int k = cis.keyAt( i );
                if( k < lowest ) lowest = k;
            }
            list[current].setSelection( lowest, 0 );
            list[current].flv.clearChoices();
        }
    }

    public final void setItemsDate( long time ) {
        SparseBooleanArray cis = getMultiple( true );
        getListAdapter( true ).setTimestamp( time, cis );
    }

    public final void installApks() {
        CommanderAdapter ca = getListAdapter( true );
        if( !ca.hasFeature( Feature.REAL ) ) {
            c.showError( c.getString( R.string.not_supported ) );
            return;
        }
        AppInstaller ai = new AppInstaller( c );
        if( ca.hasFeature( CommanderAdapter.Feature.FS ) ) {
            File[] ff = ((FSAdapter)ca).bitsToFiles( getMultiple( true ) );
            if( ff == null || ff.length == 0 ) return;
            ai.fromFiles( ff );
        } else {
            CommanderAdapter.Item[] items = ( (CommanderAdapterBase)ca ).bitsToItems( getMultiple( true ) );
            if( items == null || items.length == 0 ) return;
            ai.fromItems( ca, items );
        }
        c.startEngine( ai );
    }

    // /////////////////////////////////////////////////////////////////////////////////

    /**
     * An AdapterView.OnItemSelectedListener implementation
     */
    @Override
    public void onItemSelected( AdapterView<?> listView, View itemView, int pos, long id ) {
        // Log.v( TAG, "Selected item " + pos );
        locationBar.closeGoPanel();
        int which = list[current].id == listView.getId() ? current : opposite();
        list[which].setCurPos( pos );
        list[which].updateStatus();
    }

    @Override
    public void onNothingSelected( AdapterView<?> listView ) {
        // Log.v( TAG, "NothingSelected" );
        resetQuickSearch();
        int which = list[current].id == listView.getId() ? current : opposite();
        list[which].updateStatus();
    }

    /**
     * An AdapterView.OnItemClickListener implementation
     */
    @Override
    public void onItemClick( AdapterView<?> parent, View view, int position, long id ) {
        // Log.v( TAG, "onItemClick" );

        locationBar.closeGoPanel();
        resetQuickSearch();
        ListView flv = list[current].flv;
        if( flv != parent ) {
            togglePanels( false );
            Log.e( TAG, "onItemClick. current=" + current + ", parent=" + parent.getId() );
        }
        if( position == 0 )
            flv.setItemChecked( 0, false ); // parent item never selected
        list[current].setCurPos( position );
        CommanderAdapter ca = (CommanderAdapter)flv.getAdapter();
        if( disableOpenSelectOnly && ca.hasFeature( Feature.CHECKABLE ) ) {
            disableOpenSelectOnly = false;
            BaseAdapter ba = (BaseAdapter)ca;
            ba.notifyDataSetChanged();
        } else {
            openItem( position );
            flv.setItemChecked( position, false );
        }
        list[current].updateStatus();
    }

    public void openItem( int position ) {
        ListHelper l = list[current];
        l.setCurPos( position );
        CommanderAdapter ca = l.getListAdapter();
        // a hack to let the PictureViewer (if being chosen to handle the intent) be able to traverse other pictures in the dir
        if( !c.isPickMode()  ) {
            CommanderAdapter.Item item = (CommanderAdapter.Item)((Adapter)ca).getItem( position );
            if( item != null && !item.dir ) {
                if( item.mime == null )
                    item.mime = Utils.getMimeByExt( Utils.getFileExt( item.name ) );
                if( item.mime != null && item.mime.startsWith( "image" ) ) {
                    Pair<Uri, String> to_open = getOpenableUri( true, position, true, true );
                    if( to_open != null ) {
                        Intent in = createViewIntent( to_open.first, item.name, item.mime, null );
                        addImageViewExtras( in, ca, position );
                        in.addFlags( Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                     Intent.FLAG_GRANT_WRITE_URI_PERMISSION );
                        c.issue( in, 0 );
                        return;
                    }
                }
            }
        }
        ca.openItem( position );
    }

    public void goUp() {
        CommanderAdapter ca = getListAdapter( true );
        if( ca != null )
            ca.openItem( 0 );
    }

    public void goTop() {
        ListView flv = list[current].flv;
        int cnt = flv.getCount();
        if( cnt < 100 )
            flv.smoothScrollToPosition( 0 );
        else
            flv.setSelection( 0 );
    }

    public void goBot() {
        ListView flv = list[current].flv;
        int pos = flv.getCount() - 1;
        if( pos < 100 )
            flv.smoothScrollToPosition( pos );
        else
            flv.setSelection( pos );
    }

    /**
     * View.OnTouchListener implementation
     */
    @Override
    public boolean onTouch( View v, MotionEvent event ) {
        resetQuickSearch();
        if( panels_sliding && v == hsv ) {
            if( x_start < 0. && event.getAction() == MotionEvent.ACTION_MOVE )
                x_start = event.getX();
            else if( x_start >= 0. && event.getAction() == MotionEvent.ACTION_UP ) {
                float d = event.getX() - x_start;
                x_start = -1;
                final int to_which;
                if( Math.abs( d ) > scroll_back )
                    to_which = d > 0 ? LEFT : RIGHT;
                else
                    to_which = current == LEFT ? LEFT : RIGHT;
                setPanelCurrent( to_which );
                return true;
            }
        } else if( v instanceof ListView ) {
            if( v == list[opposite()].flv )
                togglePanels( false );

            locationBar.closeGoPanel();
            switch( event.getAction() ) {
            case MotionEvent.ACTION_DOWN: {
                downX = event.getX();
                downY = event.getY();
                disableOpenSelectOnly = event.getX() > v.getWidth() * selWidth;
                if( !selAtRight )
                    disableOpenSelectOnly = !disableOpenSelectOnly;
                break;
            }
            case MotionEvent.ACTION_UP: {
                int deltaX = (int)( event.getX() - downX );
                int deltaY = (int)( event.getY() - downY );
                int absDeltaX = Math.abs( deltaX );
                int absDeltaY = Math.abs( deltaY );
                int thldX = v.getWidth() / 50;
                int thldY = v.getHeight() / 50;
                if( thldX < 10 ) thldX = 10;
                if( thldY < 10 ) thldY = 10;
                if( absDeltaY > thldY || absDeltaX > thldX )
                    disableOpenSelectOnly = false;
                list[current].focus();
                break;
            }
            }
        }
        return false;
    }

    /*
     * View.OnKeyListener implementation
     */
    @Override
    public boolean onKey( View v, int keyCode, KeyEvent event ) {
        // Log.v( TAG, "panel key:" + keyCode + ", uchar:" +
        // event.getUnicodeChar() + ", shift: " + event.isShiftPressed() );

        if( !(v instanceof ListView) )
            return false;
        locationBar.closeGoPanel();
        if( event.getAction() == KeyEvent.ACTION_UP ) {
            if( keyCode == KeyEvent.KEYCODE_BACK ) {
                if( !c.backExit() )
                    goUp();
                return true;
            }
        }

        if( event.getAction() != KeyEvent.ACTION_DOWN )
            return false;

        int to_dispatch = -1;

        char ch = (char)event.getUnicodeChar();
        if( ch >= 'A' && ch <= 'z' || ch == '.' ) {
            quickSearch( ch );
            return true;
        }
        resetQuickSearch();
        switch( ch ) {
        case '(':
        case ')': {
            int which = ch == '(' ? LEFT : RIGHT;
            locationBar.openGoPanel( which, getFolderUriWithAuth( isCurrent( which ) ) );
        }
            return true;
        case '*':
            addCurrentToFavorites();
            return true;
        case '{':
        case '}':
            setPanelCurrent( ch == '{' ? Panels.LEFT : Panels.RIGHT );
            return true;
        case '#':
            setLayoutMode( !sxs );
            return true;
        case '~':
            swapPanels();
            return true;
        case '%':
            compareItems();
            return true;
        case '+':
        case '-':
            to_dispatch = R.id.sel_dlg;
            break;
        case '"':
            c.dispatchCommand( R.id.sz );
            return true;
        case '2':
            to_dispatch = R.id.F2;
            break;
        case '3':
            c.dispatchCommand( R.id.F3 );
            return true;
        case '4':
            c.dispatchCommand( R.id.F4 );
            return true;
        case '5':
        case '6':
            to_dispatch = R.id.F5F6;
            break;
        case '7':
            to_dispatch = R.id.new_item;
            break;
        case '8':
            c.dispatchCommand( R.id.F8 );
            return true;
        case ' ':
            list[current].checkItem( true );
            return true;
        }
        if( to_dispatch != -1 ) {
            final int _to_dispatch = to_dispatch;
            v.post( new Runnable() {
                @Override
                public void run() {
                    c.dispatchCommand( _to_dispatch );
                }
            } );
            return true;
        }
        switch( keyCode ) {
        case KeyEvent.KEYCODE_DEL:
            if( !c.backExit() )
                goUp();
            return true;
        case KeyEvent.KEYCODE_DPAD_UP:
        case KeyEvent.KEYCODE_DPAD_DOWN:
            resetQuickSearch();
            if( event.isShiftPressed() ) {
                list[current].checkItem( false );
                // ListView will not move to next item on Shift+DPAD, so
                // let's remove the Shift
                // bit from meta state and re-dispatch the event.
                KeyEvent shiftStrippedEvent = new KeyEvent( event.getDownTime(), event.getEventTime(), KeyEvent.ACTION_DOWN,
                        keyCode, event.getRepeatCount(), event.getMetaState()
                                & ~( KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON | KeyEvent.META_SHIFT_RIGHT_ON ) );
                return v.onKeyDown( keyCode, shiftStrippedEvent );
            }
            return false;
        case KeyEvent.KEYCODE_DPAD_RIGHT:
            if( arrowsLegacy ) {
                list[current].checkItem( true );
                return true;
            }
            break;
        case KeyEvent.KEYCODE_VOLUME_UP:
            if( volumeLegacy ) {
                list[current].checkItem( true );
                return true;
            }
            break;
        case KeyEvent.KEYCODE_DPAD_LEFT:
            if( arrowsLegacy ) {
                togglePanels( false );
                return true;
            }
        default:
            return false;
        }
        return false;
    }

    /*
     * View.OnClickListener and OnLongClickListener implementation for the
     * titles and history Go
     */
    @Override
    public void onClick( View v ) {
        resetQuickSearch();
        int view_id = v.getId();
        if( view_id == R.id.pick ) {
            useCurrentDirToReceive( c.getIntent() );
        }
        if( view_id != R.id.left_stat && view_id != R.id.right_stat )
            return;
        locationBar.closeGoPanel();
        int which = view_id == headerIds[LEFT] ? LEFT : RIGHT;
        if( which == current ) {
            focus();
            refreshList( current, true, null );
        } else
            togglePanels( true );
    }

    @Override
    public boolean onLongClick( View v ) {
        int which = v.getId() == headerIds[LEFT] ? LEFT : RIGHT;
        locationBar.openGoPanel( which, getFolderUriWithAuth( isCurrent( which ) ) );
        return true;
    }

    /*
     * ListView.OnScrollListener implementation
     */
    public void onScroll( AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount ) {
    }

    @Override
    public void onScrollStateChanged( AbsListView view, int scrollState ) {
        //Log.d( TAG, "onScrollStateChanged()" + scrollState );
        CommanderAdapter ca;
        try {
            ca = (CommanderAdapter)view.getAdapter();
        } catch( ClassCastException e ) {
            Log.e( TAG, "onScrollStateChanged()", e );
            return;
        }
        if( ca != null ) {
            switch( scrollState ) {
            case OnScrollListener.SCROLL_STATE_IDLE:
                ca.setMode( CommanderAdapter.LIST_STATE, CommanderAdapter.STATE_IDLE );
                view.invalidateViews();
                break;
            case OnScrollListener.SCROLL_STATE_TOUCH_SCROLL:
            case OnScrollListener.SCROLL_STATE_FLING:
                ca.setMode( CommanderAdapter.LIST_STATE, CommanderAdapter.STATE_BUSY );
                break;
            }
        }
    }

    /*
     * Persistent state
     */

    public void storeChosenItems() {
        list[current].storeChosenItems();
    }

    public void reStoreChosenItems() {
        list[current].reStoreChosenItems();
    }

    final static class State {
        private final static String LU = "LEFT_URI",  RU = "RIGHT_URI";
        private final static String LC = "LEFT_CRD",  RC = "RIGHT_CRD";
        private final static String LI = "LEFT_ITEM", RI = "RIGHT_ITEM";
        private final static String LM = "LEFT_MODE", RM = "RIGHT_MODE";
        private final static String CP = "LAST_PANEL";
        private int current = -1;
        private Context ctx;
        private Credentials leftCrd, rightCrd;
        private Uri         leftUri, rightUri;
        private String      leftItem,rightItem;
        private int         leftMode,rightMode;
        
        State( Context c ) {
            this.ctx = c;
        }
        
        public final int getCurrent() {
            return current;
        }

        public final void store( Bundle b ) {
            b.putInt( CP, current );
            b.putParcelable( LC, leftCrd );
            b.putParcelable( RC, rightCrd );
            b.putParcelable( LU, leftUri );
            b.putParcelable( RU, rightUri );
            b.putString( LI, leftItem );
            b.putString( RI, rightItem );
            b.putInt( LM, leftMode );
            b.putInt( RM, rightMode );
        }

        public final void restore( Bundle b ) {
            current   = b.getInt( CP );
            leftCrd   = b.getParcelable( LC );
            rightCrd  = b.getParcelable( RC );
            leftUri   = b.getParcelable( LU );
            rightUri  = b.getParcelable( RU );
            leftItem  = b.getString( LI );
            rightItem = b.getString( RI );
            leftMode  = b.getInt( LM );
            rightMode = b.getInt( RM );
        }

        public final void store( SharedPreferences.Editor e ) {
            e.putInt( CP, current );
            e.putString( LU,  leftUri != null ?  leftUri.toString() : "" );
            e.putString( RU, rightUri != null ? rightUri.toString() : "" );
            e.putString( LC,  leftCrd != null ?  leftCrd.toEncryptedString( ctx ) : "" );
            e.putString( RC, rightCrd != null ? rightCrd.toEncryptedString( ctx ) : "" );
            e.putString( LI,  leftItem );
            e.putString( RI, rightItem );
            e.putInt( LM,     leftMode );
            e.putInt( RM,    rightMode );
            e.remove( "FAVS" );
        }

        public final void restore( SharedPreferences p ) {
            String left_uri_s = p.getString( LU, null );
            if( Utils.str( left_uri_s ) )
                leftUri = Uri.parse( left_uri_s );
            String right_uri_s = p.getString( RU, null );
            if( Utils.str( right_uri_s ) )
               rightUri = Uri.parse( right_uri_s );

            String left_crd_s = p.getString( LC, null );
            if( Utils.str( left_crd_s ) )
                leftCrd = Credentials.fromEncryptedString( left_crd_s, ctx );
            String right_crd_s = p.getString( RC, null );
            if( Utils.str( right_crd_s ) )
               rightCrd = Credentials.fromEncryptedString( right_crd_s, ctx );
            leftItem  = p.getString( LI, null );
            rightItem = p.getString( RI, null );
            leftMode  = p.getInt( LM, 0 );
            rightMode = p.getInt( RM, 0 );
            current   = p.getInt( CP, LEFT );
        }
    }   // State

    public final State createEmptyStateObject( Context ctx ) {
        return new State( ctx );
    }

    public final State getState( Context ctx ) {
        //Log.v( TAG, "getState()" );
        CommanderAdapter left_adapter = (CommanderAdapter)list[LEFT].getListAdapter();
        if( left_adapter == null ) return null;
        CommanderAdapter right_adapter = (CommanderAdapter)list[RIGHT].getListAdapter();
        if( right_adapter == null ) return null;
        State s = createEmptyStateObject( ctx );
        s.current = current;
        try {
            s.leftUri  = left_adapter.getUri();
            s.leftCrd  = left_adapter.getCredentials();
            s.leftMode = left_adapter.getMode() & ( CommanderAdapter.MODE_SORTING | CommanderAdapter.MODE_SORT_DIR );
            int pos = list[LEFT].getCurPos();
            s.leftItem = pos >= 0 ? left_adapter.getItemName( pos, false ) : "";

            s.rightUri  = right_adapter.getUri();
            s.rightCrd  = right_adapter.getCredentials();
            s.rightMode = right_adapter.getMode() & ( CommanderAdapter.MODE_SORTING | CommanderAdapter.MODE_SORT_DIR );
            pos = list[RIGHT].getCurPos();
            s.rightItem = pos >= 0 ? right_adapter.getItemName( pos, false ) : "";
        } catch( Exception e ) {
            Log.e( TAG, "getState()", e );
        }
        return s;
    }

    public final void setState( State s, int dont_restore ) {
        //Log.v( TAG, "setState()" );
        if( s == null )
            return;
        resetQuickSearch();
        current = s.current;
        if( dont_restore != LEFT ) {
            ListHelper list_h = list[LEFT];
            CommanderAdapter ca = list_h.getListAdapter(); 
            if( ca == null ) {
                Uri lu = s.leftUri != null ? s.leftUri : Uri.parse( "home:" );
                list_h.adapterMode = s.leftMode;
                list_h.mbNavigate( lu, s.leftCrd, s.leftItem, s.current == LEFT );
            } else {
                if( !SearchProps.searchQueryParamsPresent( ca.getUri() ) )
                    list_h.mbRefreshList( s.current == LEFT, s.leftItem );
            }
        }
        if( dont_restore != RIGHT ) {
            ListHelper list_h = list[RIGHT];
            CommanderAdapter ca = list_h.getListAdapter(); 
            if( ca == null ) {
                Uri ru = s.rightUri != null ? s.rightUri : Uri.parse( "home:" );
                list_h.adapterMode = s.rightMode;
                list_h.mbNavigate( ru, s.rightCrd, s.rightItem, s.current == RIGHT );
            } else
                if( !SearchProps.searchQueryParamsPresent( ca.getUri() ) )
                    list_h.mbRefreshList( s.current == RIGHT, s.rightItem );
        }
        applyColors();
    }
    
    public final void storeFaves() {
        favorites.store();
    }
    
    public final void restoreFaves() {
        favorites.restore();
        if( !favorites.isEmpty() ) return;
        SharedPreferences p = c.getSharedPreferences( getClass().getSimpleName(), Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS );
        String favs = p.getString( "FAVS", "" );
        if( !favs.isEmpty() )
            favorites.setFromString( favs );
        else
            favorites.setDefaults();
    }

    public final void useCurrentDirToReceive( Intent in ) {
        if( in == null ) return;
        try {
            CommanderAdapter rcp = getListAdapter( true );
            if( !rcp.hasFeature( CommanderAdapter.Feature.RECEIVER ) ) {
                c.showError( c.getString( R.string.not_supported ) );
                return;
            }
            ReceiveEngine re = new ReceiveEngine( c, rcp );
            String text = in.getStringExtra( Intent.EXTRA_TEXT );
            if( Intent.ACTION_SEND_MULTIPLE.equals( in.getAction() ) ) {
                ArrayList<Uri> uris = in.getParcelableArrayListExtra( Intent.EXTRA_STREAM );
                re.setSourceUris( uris );
            } else {
                Uri uri = in.getParcelableExtra( Intent.EXTRA_STREAM );
                if( uri != null ) {
                    re.setSourceUri( uri );
                } else if( text == null ) {
                    c.showError( c.getString( R.string.copy_err ) );
                    return;
                }
            }
            re.setSourceText( in.getStringExtra( Intent.EXTRA_SUBJECT ), text );
            c.startEngine( re );
            ImageButton pb = c.findViewById( R.id.pick );
            pb.setVisibility( View.GONE );
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
    }
}
