package com.ghostsq.commander.adapters;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.SparseBooleanArray;
import android.view.ContextMenu;
import android.widget.AdapterView;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.FilterProps;
import com.ghostsq.commander.SearchProps;
import com.ghostsq.commander.utils.Credentials;
import com.ghostsq.commander.utils.LsItem;
import com.ghostsq.commander.utils.Utils;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;

/**
 * <code>CommanderAdapter</code> interface
 * @author Ghost Squared (ghost.sq2@gmail.com)
 * <p>
 * All the adapters should extend {@link CommanderAdapterBase}
 * which implements this interface.
 * <p>
 * Most of the methods are asynchronous.
 * They start a new thread which sends Message objects
 * which is routed to the {@link Commander#notifyMe Commander.notifyMe( Message m )}
 * 
 */
public interface CommanderAdapter {

    /**
     *   An instance of the following Item class to be returned by ListAdapter's getItem() override 
     *   @see android.widget.ListAdapter#getItem
     */
    public class Item {
        public  String    name = "";
        public  Date      date = null;
        public  long      size = -1;
        public  boolean   dir = false, sel = false;
        public  String    attr = "", mime, linkTarget = null;
        public  Uri       uri = null;
        public  Object    origin = null;
        private String    prefix = null;
        public  int       icon_id = -1;
        private Drawable  thumbnail;
        private long      thumbnailUsed;
        public  int       colorCache = 0, width = -1, height = -1, position = -1;
        public  boolean   need_thumb = false, no_thumb = false, thumb_is_icon = false, thumb_pending = false;
        public  Item() {}
        public  Item( String name_ )    { name = name_; }
        public        Uri      getUri()                   { return uri != null ? uri : ( origin instanceof Uri ? (Uri)origin : null ); }
        public        String   getDispName()              {
            if( linkTarget == null )
                return name;
            return name + LsItem.LINK_PTR + linkTarget;
        }
        public        void     setPrefix( String pfx )    { prefix = Utils.str( pfx ) ? Utils.mbAddSl( pfx ) : pfx; }
        public        String   getPath()  /* relative */  { return prefix == null ? name : prefix + name; }
        public  final boolean  isThumbNail()              { return thumbnail != null; }
        public  final Drawable getThumbNail()             { thumbnailUsed = System.currentTimeMillis(); return thumbnail; }
        public  final void     setThumbNail( Drawable t ) { thumbnailUsed = System.currentTimeMillis(); thumbnail = t; thumb_pending = false; }
        public  final void     setIcon( Drawable t ) { setThumbNail( t ); thumb_is_icon = true; }
        public  final boolean  remThumbnailIfOld( int ttl ) { 
            if( thumbnail != null && !need_thumb && System.currentTimeMillis() - thumbnailUsed > ttl ) {
                thumbnail = null;
                return true;
            }
            return false;
        }
        @Override
        public String toString() {
            return name;
        }
    }
    
    /**
     * To initialize an adapter, the adapter creator calls the Init() method.
     * This needed because only the default constructor can be called
     * during a creation of a foreign packaged class  
     * 
	 * @param c - the reference to commander
	 * 
	 * @see CA#CreateAdapter
	 *    
	 */
	public void Init( Commander c );

	/**
	 * @return the scheme the adapter implements
	 */
	public String getScheme();

	/**
	 * @return text describing the source
     * @param flags - reserved for future use
	 */
	public String getDescription( int flags );

    /**
     * Just passive set the current URI without an attempt to obtain the list or the similar
     * 
     * @param uri - the URI of the resource to connect to or work with  
     */
    public void setUri( Uri uri );
    
    /**
     * Retrieve the current URI from the adapter
     * 
     * @return current adapter URI
     *      Note, that the URI returned always without the credentials. 
     *      Use the {@link #getCredentials getCredentials()} method separately  
     */
    public Uri getUri();
	
	/**
	 *  Output modes.  
	 */
	public final static int MODE_WIDTH = 0x000001, NARROW_MODE = 0x000000,     WIDE_MODE = 0x000001,
	                      MODE_DETAILS = 0x000002, SIMPLE_MODE = 0x000000, DETAILED_MODE = 0x000002,
	                      MODE_FINGERF = 0x000004,   SLIM_MODE = 0x000000,      FAT_MODE = 0x000004,
                          MODE_HIDDEN  = 0x000008,   SHOW_MODE = 0x000000,     HIDE_MODE = 0x000008,
                        MODE_SORT_DIR  = 0x000040,    SORT_ASC = 0x000000,      SORT_DSC = 0x000040,
                            MODE_CASE  = 0x000080,   CASE_SENS = 0x000000,   CASE_IGNORE = 0x000080,
                             MODE_ATTR = 0x000300,     NO_ATTR = 0x000000,     SHOW_ATTR = 0x000100, ATTR_ONLY = 0x0200,
                             MODE_ROOT = 0x000400,  BASIC_MODE = 0x000000,     ROOT_MODE = 0x000400,
                            MODE_TRASH = 0x000800, DELETE_MODE = 0x000000,  TOTRASH_MODE = 0x000800,
                            MODE_ICONS = 0x003000,   TEXT_MODE = 0x000000,     ICON_MODE = 0x001000, ICON_TINY = 0x2000,
                            LIST_STATE = 0x010000,  STATE_IDLE = 0x000000,    STATE_BUSY = 0x010000,
                            MODE_CLONE = 0x020000, NORMAL_MODE = 0x000000,    CLONE_MODE = 0x020000,
                            MODE_DIRSZ = 0x040000,    NO_DIRSZ = 0x000000,    SHOW_DIRSZ = 0x040000,
                          MODE_SORTING = 0x700000,   SORT_NAME = 0x000000,
                                                     SORT_EXT  = 0x100000,
                                                     SORT_SIZE = 0x200000,
                                                     SORT_DATE = 0x400000,
                                                     SORT_ACCD = 0x500000,
                          SET_TBN_SIZE = 0x01000000,
                         SET_FONT_SIZE = 0x02000000;

	/**
     * To set the desired adapter mode or pass some extra data.
     * <p>The mode is about how the adapter outputs the entries
     * @param mask - could be one of the following  
     *  <p>     {@code MODE_WIDTH}    wide when there are enough space, narrow to output the data in two line to save the space
     *  <p>     {@code MODE_DETAILS}  to output the item details besides just the name
     *  <p>     {@code MODE_FINGERF}  to enlarge the item view 
     *  <p>     {@code MODE_HIDDEN}   to show hidden files
     *  <p>     {@code MODE_SORTING}  rules how the entries are sorted
     *  <p>     {@code MODE_SORT_DIR} direction of the sorting  
     *  <p>     {@code MODE_CASE}     to honor the case in the sorting     
     *  <p>     {@code MODE_ATTR}     to show additional attributes  
     *  <p>     {@code MODE_ROOT}     to show the root and mount in the home adapter  
     *  <p>     {@code MODE_ICONS}    to show the file icons  
     *  <p>     {@code LIST_STATE}    set the current state taken from {@link AbsListView.OnScrollListener#onScrollStateChanged}
     *  <p>     {@code SET_TBN_SIZE}  to pass the integer - the size of the thubnails  
     *  <p>     {@code SET_FONT_SIZE} to pass the font size     
     * @param mode - the real value. See the bits above 
     * @return the current mode 
     */
    public int setMode( int mask, int mode );
    
    /**
     * @return current adapter's mode bits
     */
    public int getMode();
    
    /**
     * @return current adapter's mode bits
     */
    public int getMode( int mask );


    /**
     *   Called when the user taps and holds on an item
     *   
     *   @param menu - to call the method .add()
     *   @param acmi - to know which item is processed
     *   @param num  - current mode
     */
    public void populateContextMenu( ContextMenu menu, AdapterView.AdapterContextMenuInfo acmi, int num );

    /**
     * To be used in the group parameter of ContextMenu.add()
     */
    public final static int CM_NAVIGATION = 0, CM_OPERATION = 1, CM_INFO = 2, CM_SPECIAL = 3, CM_INTEGRATION = 4, CM_INTERACTION = 5;
    
    /**
     *  features to be probed by calling the {@code hasFeature()} 
     */
    public enum Feature {
        FS,     // native file system
        LOCAL,  // located at the device
        REAL,   // not virtual/UI, etc.
        F1,     // show tool buttons 1..0 ...
        F2,
        F3,
        F4,
        SF4,
        F5,
        F6,
        F7,
        F8,
        F9,
        F10,
        EQ,     // show tool button "Show same location in other panel"
        TGL,    // show tool button "Toggle"
        SZ,     // able to show info on an item
        SORTING,// can sort
        BY_NAME,// show sorting mode tool buttons...
        BY_EXT,
        BY_SIZE,
        BY_DATE,
        SEL_UNS,// can select and unselect items
        ENTER,  // can open an item
        ADD_FAV,// an be added to faves
        REMOUNT,
        HOME,
        FAVS,
        SDCARD,
        ROOT,
        MOUNT,
        HIDDEN,
        REFRESH,
        SOFTKBD,
        SEARCH,
        MENU,
        SEND,
        CHECKABLE,
        SCROLL,
        MULT_RENAME,    // can rename multiple items
        FILTER,         // can filter
        RECEIVER,       // implements the new receiver 
        DIRSIZES,       // can show directory sizes
        MKZIP,          // can make a zip
        TOUCH,          // can change a timestamp
        SHOWS_PERM,     // shows UNIX style file permission
        RENAME,         // item rename
        DELETE,         // item delete
        BY_ACCESS_DATE
    }
    
    /**
     * queries an implemented feature
     * @return true if the feature  
     */
    public boolean hasFeature( Feature feature );

    /**
     * Pass the user credentials to be used later
     * 
     * @param crd credentials
     */
    public void setCredentials( Credentials crd );

    /**
     * Obtain the current used credentials
     * 
     * @return user credentials
     */
    public Credentials getCredentials();
    
    /**
     * The "main" method to obtain the current adapter's content
     * 
     * @param uri - a folder's URI to initialize. If null passed, just refresh
     * @param pass_back_on_done - the file name to select
     */
	public boolean readSource( Uri uri, String pass_back_on_done );

	/**
     * Tries to do something with the item 
     * <p>Outside of an adapter we don't know how to process it.
     * But an adapter knows, is it a folder and can be opened (it calls Commander.Navigate() in this case)
     * or processed as default action (then it calls Commander.Open() )
	 * 
	 * @param position index of the item to action
	 * 
	 */
	public void openItem( int position );
	
    /**
     * Return the name of an item at the specified position
     * 
     * @param position - numer in the list. 
     * Starts from 1, because 0 is the link to the parent!
     * @param full     - true - to return the absolute path, false - only the local name
     * @return string representation of the item
     */
    public String getItemName( int position, boolean full );    

    /**
     * Return the URI of an item at the specified position
     * 
     * @param position
     * @return full URI to access the item without the credentials!
     */
    public Uri getItemUri( int position );    

	/**
	 * Starts the occupied size calculation procedure, or just some info
	 * 
	 * @param  cis selected item (files or directories)
	 *         will call Commander.NotifyMe( "requested size info", Commander.OPERATION_COMPLETED ) when done  
	 */
	public void reqItemsSize( SparseBooleanArray cis );
    
    /**
     * @param position in the list
     * @param newName for the item
     * @param copy file (preserve old name)
     * @return true if success
     */
    public boolean renameItem( int position, String newName, boolean copy );
    
    /**
     * @param cis   entries to rename
     * @param pattern regular expression pattern
     * @param replace_to mask of new names
     * @return true if success
     */
    public boolean renameItems( SparseBooleanArray cis, String pattern, String replace_to );
	
	/**
	 * @param cis	entries to copy
	 * @param to    an adapter, which method receiveItems() to be called
	 * @param move  move instead of copy
	 * @return      true if succeeded
	 * 
	 * @see #receiveItems
	 */
	public boolean copyItems( SparseBooleanArray cis, CommanderAdapter to, boolean move );
	
	/**
	 *  To be used in receiveItems()
	 *  @see #receiveItems
	 */
    public final static int MODE_COPY = 0;
    public final static int MODE_MOVE = 1;
    public final static int MODE_DEL_SRC_DIR = 2;
    public final static int MODE_MOVE_DEL_SRC_DIR = 3;
    public final static int MODE_REPORT_AS_MOVE = 4;
	/**
	 * This method receives the files from another adapter 
	 * 
	 * @param fileURIs  list of files as universal transport parcel. All kind of adapters (network, etc.)
	 * 					accepts data as files. It should be called from the current list's adapter
	 * @param move_mode move mode
	 * @return          true if succeeded
	 */
	public boolean receiveItems( String[] fileURIs, int move_mode );

	/**
	 * A cast method since at this moment an adapter is also the implementation of the IReciever
	 * @return interface with receiveItems()
	 */
	@Deprecated
	public Engines.IReciever getReceiver();

    /**
     * Create a new IReceiver object which able to receive entries to the specific directory
     * @param dir - Uri of the the directory to receive the entries
     * @return IReceiver object
     */
	public IReceiver getReceiver( Uri dir );
	
    /**
     * @param fileURI - the location of the file  
     * @return        - the Item with all the information in it
     * This method is not supposed to be called from the UI thread.
     */
    public Item getItem( Uri fileURI );

    /**
     * @param item_URI - the location of the item
     * @param skip    - tells the data provider to start from a middle point  
     * @return        - the content of the file
     * The caller has to call closeStream() after it's done working with the content
     */
    public InputStream getContent( Uri item_URI, long skip );

    /**
     *  same as getContent( fileURI, 0 );
     */
    public InputStream getContent( Uri item_URI );

    /**
     * @param item_URI - the location of the file
     * @return  stream to data be written 
     * The caller has to call closeStream() after it's done working with the content
     */
    public OutputStream saveContent( Uri item_URI );
    
    /**
     * @param s - the stream obtained by the getContent() or saveContent() methods to be closed by calling this  
     */
    public void closeStream( Closeable s );

    /**
     * @param name - file name to create  
     */
    public boolean createFile( String name );

    /**
     * @param name - the location of the folder (directory) to create
     */
	public void createFolder( String name );

    /**
     * @param  cis selected item (files or directories)
     *         will call Commander.NotifyMe( "requested size info", Commander.OPERATION_COMPLETED ) when done  
     */
	public boolean deleteItems( SparseBooleanArray cis );
    
    /**
     * @param command_id - command id to execute
     * @param cis - selected or checked entries to work with
     */
    public void doIt( int command_id, SparseBooleanArray cis );

    /**
     * this method is called when the Commander can't find what to do with an activity result
     */
    public boolean handleActivityResult( int requestCode, int resultCode, Intent data );
    
    /**
     * to be called before the adapter is going to be destroyed
     */
	public void terminateOperation();
	public void prepareToDestroy();
	
	/*
	 * If an adapter supports entries filtering (Feature.FILTER),
	 * the filter could be controlled through the following methods
	 */
    public FilterProps getFilter();
    public void setFilter( FilterProps filter );
    public void cancelFilter();

    public SearchProps getSearch();

    /**
     * set the specifed time to the selected items
     * @param time time to set
     * @param cis - selected or checked entries to work with
     *            see Feature.TOUCH
     */
    public void setTimestamp( long time, SparseBooleanArray cis );

	/**
	 * Returns an error text of the latest operation
	 */
	public String getLatestError();
}
