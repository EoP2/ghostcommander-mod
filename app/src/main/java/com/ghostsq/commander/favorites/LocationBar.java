package com.ghostsq.commander.favorites;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnKeyListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AutoCompleteTextView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ListView;
import android.widget.TextView;

import com.ghostsq.commander.FileCommander;
import com.ghostsq.commander.Panels;
import com.ghostsq.commander.R;
import com.ghostsq.commander.adapters.SAFAdapter;
import com.ghostsq.commander.utils.Credentials;
import com.ghostsq.commander.utils.Utils;

public class LocationBar extends BaseAdapter implements Filterable, OnKeyListener, OnClickListener, TextWatcher {
    private final String TAG = getClass().getName();
	private FileCommander c;
	private Panels        p;
	private int  toChange = -1;
	private View goPanel;
	private Favorites favorites;
    private float density = 1;
    private LayoutInflater inflater;
    private int font_size;
	
	public LocationBar( FileCommander c_, Panels p_, Favorites shortcuts_list ) {
		super();
		c = c_;
		p = p_;
		favorites = shortcuts_list;
		goPanel = c.findViewById( R.id.uri_edit_panel );
		inflater = (LayoutInflater)c.getContext().getSystemService( Context.LAYOUT_INFLATER_SERVICE );
		
        try {
            AutoCompleteTextView textView = goPanel.findViewById( R.id.uri_edit );
            if( textView != null ) {
	            textView.setAdapter( this );
	            textView.setOnKeyListener( this );
	            textView.addTextChangedListener( this );
	            textView.setOnItemClickListener( new AdapterView.OnItemClickListener() {
	                @Override
	                public void onItemClick( AdapterView<?> parent, View view, int position, long id ) {
	                    applyGoPanel();
	                }
	            } );
            }
            Button go = goPanel.findViewById( R.id.go_button );
            if( go != null ) {
            	go.setOnClickListener( this );
            }
            View star = goPanel.findViewById( R.id.star );
            if( star != null )
            	star.setOnClickListener( this );
            density = c.getContext().getResources().getDisplayMetrics().density;
        } catch( Exception e ) {
			c.showMessage( "Exception on setup history dropdown: " + e );
		}
	}

	public void setFingerFriendly( boolean finger_friendly, int font_size, float density ) {
		this.font_size = font_size;
        Button go = (Button)goPanel.findViewById( R.id.go_button );
        if( go != null ) {
            int pv = 0;//go.getPaddingTop();
            int ph = (int)( finger_friendly ? 20 * density : 8 * density );
            go.setPadding( ph, pv, ph, pv );
        }
	}
	
	@Override
	 public Filter getFilter() {
	  Filter nameFilter = new Filter() {
		   @Override
		   public String convertResultToString( Object resultValue ) {
		      return resultValue != null ? resultValue.toString() : "?";
		   }
	
		   @Override
		   protected FilterResults performFiltering(CharSequence constraint) {
			    FilterResults filterResults = new FilterResults();
				if(constraint != null) {
				   filterResults.values = new Object();
				   filterResults.count = 1;
				}
			    return filterResults;
		   }
		   @Override
		   protected void publishResults( CharSequence constraint, FilterResults results ) {
		    if( results != null && results.count > 0 )
		    	notifyDataSetChanged();
		   }
	   };
	   return nameFilter;
	 }

	@Override
	public int getCount() {
		return favorites.size();
	}

	@Override
	public Object getItem( int position ) {
		return favorites.get( position ).getUriString( true );
	}

	@Override
	public long getItemId( int position ) {
		return position;
	}

	@SuppressLint("ResourceType")
	@Override
	public View getView( int position, View convertView, ViewGroup parent ) {
		try {
			Favorite f = favorites.get( position );
			if( f == null ) return null;
            View v = convertView != null ? convertView : inflater.inflate( R.layout.favitem, parent, false );
            TextView nv = v.findViewById( R.id.name );
            nv.setTextSize( font_size );
            nv.setTextAppearance( c, android.R.attr.textAppearanceInverse );
            TextView dv = v.findViewById( R.id.desc );
            View d = v.findViewById( R.id.divider );
            d.setVisibility( position == 0 ? View.GONE : View.VISIBLE );
            int vp = (int)( ( p.fingerFriendly ? 4 : 1 ) * density );
            int hp_name = (int)( 8 * density );
            int hp_desc = (int)( 8 * density );
            nv.setPadding( hp_name, vp, hp_name, 0 );
            dv.setPadding( hp_desc, 0, hp_name, vp );

			String name = f.getComment();
			Uri uri = f.getUri();
			String uri_s = f.getUriString( true );
			if( SAFAdapter.isTreeUri( uri ) )
				uri_s = SAFAdapter.getUserFriendlyURI( c.getContext(), uri );
			boolean name_exists = Utils.str( name );
            nv.setText( name_exists ? name : uri_s );
            nv.setTypeface( null, name_exists ? Typeface.BOLD : Typeface.NORMAL );

            dv.setTextSize( font_size * 0.75f );
            dv.setText( name_exists ? uri_s : "" );
            return v;
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
		return null;
	}
	
    public static int getThemeResourceId(Context context, int attr) {
            TypedValue typedvalueattr = new TypedValue();
            context.getTheme().resolveAttribute(attr, typedvalueattr, true);
            return typedvalueattr.resourceId;
    }

	// --- inner functions ---
	
    public final void openGoPanel( int which, Uri uri ) {
		try {
			goPanel.setVisibility( View.VISIBLE );
			toChange = which;
			AutoCompleteTextView edit = (AutoCompleteTextView)c.findViewById( R.id.uri_edit );
			if( edit != null ) {
				edit.setText( Favorite.screenPwd( uri ) );
				edit.showDropDown();
				edit.setSelection( edit.length() );
				edit.requestFocus();
			}
			CheckBox star = (CheckBox)c.findViewById( R.id.star );
            if( star != null )
            	star.setChecked( favorites.findIgnoreAuth( uri ) >= 0 );
		}
		catch( Exception e ) {
			c.showMessage( "Error: " + e );
		}
    }
    public final void closeGoPanel() {
		View go_panel = c.findViewById( R.id.uri_edit_panel );
		if( go_panel != null )
			go_panel.setVisibility( View.GONE );
    }
    public final void applyGoPanel() {
    	closeGoPanel();
		TextView edit = (TextView)goPanel.findViewById( R.id.uri_edit );
		String new_dir = edit.getText().toString().trim();
		if( toChange >= 0 && new_dir.length() > 0 ) {
            Uri u = Uri.parse( new_dir );
            Credentials crd = null;
            if( Favorite.isPwdScreened( u ) ) {
                crd = favorites.searchForPassword( u );
            } else {
                String user_info = u.getUserInfo();
                if( Utils.str( user_info ) )
                    crd = new Credentials( user_info );
            }
            u = Utils.updateUserInfo( u, null );
			if( toChange != p.getCurrent() )
				p.togglePanels( false );
			p.Navigate( toChange, u, crd, null );
		}
		toChange = -1;
		p.focus();
    }    
    
	@Override
	public boolean onKey( View v, int keyCode, KeyEvent event ) {
	    int v_id = v.getId();
	    if( v_id == R.id.uri_edit ) {
	    	switch( keyCode ) {
			case KeyEvent.KEYCODE_BACK:
				closeGoPanel();
	            return true;
			case KeyEvent.KEYCODE_DPAD_CENTER:
			case KeyEvent.KEYCODE_ENTER:
				try {
					AutoCompleteTextView actv = (AutoCompleteTextView)v;
					if( actv.getListSelection() == ListView.INVALID_POSITION ) { // !actv.isPopupShowing()
						applyGoPanel();
						return true;
					}
				} catch( ClassCastException e ) {
				}
				return false;
/*				
			case KeyEvent.KEYCODE_DPAD_DOWN:
				try {
					AutoCompleteTextView actv = (AutoCompleteTextView)v;
					actv.showDropDown();
				} catch( ClassCastException e ) {
				}
				return false;
*/
			case KeyEvent.KEYCODE_TAB:
				return true;
			}
	    }
		return false;
	}

	@Override
	public void onClick( View v ) {
		final int id = v.getId();
		if( id == R.id.star ) {
			try {
				if( toChange < 0 ) return;
				TextView edit = goPanel.findViewById( R.id.uri_edit );
				String uri_s = edit.getText().toString().trim();
				CheckBox star_cb = (CheckBox)v;
				Uri u = Uri.parse( uri_s );
				favorites.removeFromFavorites( u );
				if( star_cb.isChecked() ) {
					Credentials crd = null;
					if( Favorite.isPwdScreened( u ) ) {
						crd = p.getCredentials( true );
						if( crd == null )
							crd = favorites.searchForPassword( u );
					}
					favorites.addToFavorites( u, crd );
				}
				notifyDataSetChanged();
				star_cb.setChecked( favorites.findIgnoreAuth( u ) >= 0 );
				AutoCompleteTextView actv = (AutoCompleteTextView)edit;
				actv.showDropDown();
				actv.requestFocus();
			} catch( Exception e ) {
				Log.w( TAG, "", e );
			}
			return;
		}
		if( id == R.id.go_button )
		    applyGoPanel();
	}

	// TextWatcher implementation
	
	@Override
	public void afterTextChanged( Editable s ) {
		try {
			TextView edit = (TextView)goPanel.findViewById( R.id.uri_edit );
			CheckBox star = (CheckBox)goPanel.findViewById( R.id.star );
			String   addr = edit.getText().toString().trim();
			Uri       uri = Uri.parse( addr );
			star.setChecked( favorites.findIgnoreAuth( uri ) >= 0 );
		}
		catch( Exception e ) {
		}
	}
	@Override
	public void beforeTextChanged( CharSequence s, int start, int count, int after ) {
	}
	@Override
	public void onTextChanged( CharSequence s, int start, int before, int count ) {
	}        
}
