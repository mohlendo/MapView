package com.qozix.mapview.tiles;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicInteger;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.ImageLoader;
import com.android.volley.toolbox.Volley;
import com.qozix.layouts.FixedLayout;
import com.qozix.layouts.ScalingLayout;
import com.qozix.mapview.zoom.ZoomLevel;
import com.qozix.mapview.zoom.ZoomListener;
import com.qozix.mapview.zoom.ZoomManager;

public class TileManager extends ScalingLayout implements ZoomListener {

	private static final String TAG = TileManager.class.getSimpleName();

	private static final int RENDER_FLAG = 1;
	private static final int RENDER_BUFFER = 250;

	private LinkedList<MapTile> scheduledToRender = new LinkedList<MapTile>();
	private LinkedList<MapTile> alreadyRendered = new LinkedList<MapTile>();

    private MapTileEnhancer enhancer;
	private HashMap<Integer, ScalingLayout> tileGroups = new HashMap<Integer, ScalingLayout>();

	private TileRenderListener renderListener;
	
	private MapTileCache cache;
	private ZoomLevel zoomLevelToRender;
	private ScalingLayout currentTileGroup;
	private ZoomManager zoomManager;

	private int lastRenderedZoom = -1;

	private boolean renderIsCancelled = false;
	private boolean renderIsSuppressed = false;
	private boolean isRendering = false;
	
	private TileRenderHandler handler;

    private RequestQueue requestQueue;
    private ImageLoader imageLoader;

	public TileManager( Context context, ZoomManager zm ) {
		super( context );
        requestQueue = Volley.newRequestQueue(context);
        imageLoader = new ImageLoader(requestQueue, new ImageLoader.ImageCache() {
            @Override
            public Bitmap getBitmap(String url) {
                return cache.getBitmap(url);
            }

            @Override
            public void putBitmap(String url, Bitmap bitmap) {
                cache.addBitmap(url, bitmap);
            }
        });
        zoomManager = zm;
		zoomManager.addZoomListener( this );		
		handler = new TileRenderHandler( this );
	}

    public void setEnhancer( MapTileEnhancer e) {
        enhancer = e;
    }
	
	public void setCacheEnabled( boolean shouldCache ) {
		if ( shouldCache ){
			if ( cache == null ){
				cache = new MapTileCache( getContext() );
			}
		} else {
			if ( cache != null ) {
				cache.destroy();
			}
			cache = null;
		}
	}
	
	public void setTileRenderListener( TileRenderListener listener ){
		renderListener = listener;
	}

	public void requestRender() {
		// if we're requesting it, we must really want one
		renderIsCancelled = false;
		renderIsSuppressed = false;
		// if there's no data about the current zoom level, don't bother
		if ( zoomLevelToRender == null ) {
			return;
		}
		// throttle requests
		if ( handler.hasMessages( RENDER_FLAG ) ) {
			handler.removeMessages( RENDER_FLAG );
		}
		// give it enough buffer that (generally) successive calls will be captured
		handler.sendEmptyMessageDelayed( RENDER_FLAG, RENDER_BUFFER );
	}

	public void cancelRender() {
		// hard cancel - this applies to *all* tasks, not just the currently executing task
		renderIsCancelled = true;
        requestQueue.cancelAll(new RequestQueue.RequestFilter() {
            @Override
            public boolean apply(Request<?> request) {
                return true;
            }
        });
	}

	public void suppressRender() {
		// this will prevent new tasks from starting, but won't actually cancel the currently executing task
		renderIsSuppressed = true;
	}

	public void updateTileSet() {
		// fast fail if there aren't any zoom levels registered
		int numZoomLevels = zoomManager.getNumZoomLevels();
		if ( numZoomLevels == 0 ) {
			return;
		}
		// what zoom level should we be showing?
		int zoom = zoomManager.getZoom();
		// fast-fail if there's no change
		if ( zoom == lastRenderedZoom ) {
			return;
		}
		// save it so we can detect change next time
		lastRenderedZoom = zoom;
		// grab reference to this zoom level, so we can get it's tile set for comparison to viewport
		zoomLevelToRender = zoomManager.getCurrentZoomLevel();
		// fetch appropriate child
		currentTileGroup = getCurrentTileGroup();
		// made it this far, so currentTileGroup should be valid, so update clipping
		updateViewClip( currentTileGroup );
		// get the appropriate zoom
		double scale = zoomManager.getInvertedScale();
		// scale the group
		currentTileGroup.setScale( scale );
		// show it
		currentTileGroup.setVisibility( View.VISIBLE );
		// bring it to top of stack
		currentTileGroup.bringToFront();
	}

	public boolean getIsRendering() {
		return isRendering;
	}
	
	public void clear() {
		// suppress and cancel renders
		suppressRender();
		cancelRender();		
		// destroy all tiles
		for ( MapTile m : scheduledToRender ) {
			m.destroy();
		}
		scheduledToRender.clear();
		for ( MapTile m : alreadyRendered ) {
			m.destroy();
		}
		alreadyRendered.clear();
		// the above should clear everything, but let's be redundant
		for ( ScalingLayout tileGroup : tileGroups.values() ) {
			int totalChildren = tileGroup.getChildCount();
			for ( int i = 0; i < totalChildren; i++ ) {
				View child = tileGroup.getChildAt( i );
				if ( child instanceof ImageView ) {
					ImageView imageView = (ImageView) child;
					imageView.setImageBitmap( null );
				}
			}
			tileGroup.removeAllViews();
		}
	}

	private ScalingLayout getCurrentTileGroup() {
		int zoom = zoomManager.getZoom();
		// if a tile group has already been created and registered, return it
		if ( tileGroups.containsKey( zoom ) ) {
			return tileGroups.get( zoom );
		}
		// otherwise create one, register it, and add it to the view tree
		ScalingLayout tileGroup = new ScalingLayout( getContext() );
		tileGroups.put( zoom, tileGroup );
		addView( tileGroup );
		return tileGroup;
	}

	

	// access omitted deliberately - need package level access for the TileRenderHandler
	void renderTiles() {
		// has it been canceled since it was requested?
		if ( renderIsCancelled ) {
			return;
		}
		// can we keep rending existing tasks, but not start new ones?
		if ( renderIsSuppressed ) {
			return;
		}
		// fast-fail if there's no available data
		if ( zoomLevelToRender == null ) {
			return;
		}
		// decode and render the bitmaps asynchronously
		beginRenderTask();
	}

	private void updateViewClip( View view ) {
		LayoutParams lp = (LayoutParams) view.getLayoutParams();
		lp.width = zoomManager.getComputedCurrentWidth();
		lp.height = zoomManager.getComputedCurrentHeight();
		view.setLayoutParams( lp );
	}

	private void beginRenderTask() {
		// find all matching tiles
		LinkedList<MapTile> intersections = zoomLevelToRender.getIntersections();
		// if it's the same list, don't bother
		if ( scheduledToRender.equals( intersections ) ) {
			return;
		}
		// if we made it here, then replace the old list with the new list
		scheduledToRender = intersections;

        final AtomicInteger numberOfTilesToRender = new AtomicInteger();

        for (final MapTile tile : scheduledToRender) {
            numberOfTilesToRender.incrementAndGet();

            imageLoader.get(tile.getFileName(), new ImageLoader.ImageListener() {
                @Override
                public void onResponse(ImageLoader.ImageContainer imageContainer, boolean b) {
                    Bitmap bitmap = imageContainer.getBitmap();
                    if(bitmap == null) {
                        return;
                    }

                    tile.setBitmap(bitmap);

                    // run the enhancer
                    (new AsyncTask<MapTile,Void,MapTile>() {

                        @Override
                        protected MapTile doInBackground(MapTile... params) {
                            MapTile tile = params[0];
                            tile.enhanceBitmap(enhancer);
                            return tile;
                        }

                        @Override
                        protected void onPostExecute(MapTile mapTile) {
                            renderIndividualTile(mapTile);

                            //when we have finished every render task, inform the manager
                            if(numberOfTilesToRender.decrementAndGet() == 0) {
                                onRenderTaskPostExecute();
                            }
                        }
                    }).execute(tile);
                }

                @Override
                public void onErrorResponse(VolleyError volleyError) {
                    Log.e(TAG, "error: " + volleyError);

                }
            }, tile.getWidth(), tile.getHeight());
        }
	}

	private FixedLayout.LayoutParams getLayoutFromTile( MapTile m ) {
		int w = m.getWidth();
		int h = m.getHeight();
		int x = m.getLeft();
		int y = m.getTop();
		return new FixedLayout.LayoutParams( w, h, x, y );
	}

	private void cleanup() {
		// start with all rendered tiles...
		LinkedList<MapTile> condemned = new LinkedList<MapTile>( alreadyRendered );
		// now remove all those that were just qualified
		condemned.removeAll( scheduledToRender );
		// for whatever's left, destroy and remove from list
		for ( MapTile m : condemned ) {
			m.destroy();
			alreadyRendered.remove( m );
		}
		// hide all other groups
		for ( ScalingLayout tileGroup : tileGroups.values() ) {
			if ( currentTileGroup == tileGroup ) {
				continue;
			}
			tileGroup.setVisibility( View.GONE );
		}
	}

	/*
	 *  render tasks (invoked in asynctask's thread)
	 */
	
	void onRenderTaskPreExecute(){
		// set a flag that we're working
		isRendering = true;
		// notify anybody interested
		if ( renderListener != null ) {
			renderListener.onRenderStart();
		}
	}
	
	void onRenderTaskCancelled() {
		if ( renderListener != null ) {
			renderListener.onRenderCancelled();
		}
		isRendering = false;
	}
	
	void onRenderTaskPostExecute() {
		// set flag that we're done
		isRendering = false;
		// everything's been rendered, so get rid of the old tiles
		cleanup();
		// recurse - request another round of render - if the same intersections are discovered, recursion will end anyways
		requestRender();
		// notify anybody interested
		if ( renderListener != null ) {
			renderListener.onRenderComplete();
		}
	}
	
	LinkedList<MapTile> getRenderList(){
		return new LinkedList<MapTile>( scheduledToRender );
	}

	void renderIndividualTile( MapTile m ) {
		if ( alreadyRendered.contains( m ) ) {
			return;
		}
		m.render( getContext() );
		alreadyRendered.add( m );
		ImageView i = m.getImageView();
		LayoutParams l = getLayoutFromTile( m );
		currentTileGroup.addView( i, l );
	}
	
	boolean getRenderIsCancelled() {
		return renderIsCancelled;
	}
	
	@Override
	public void onZoomLevelChanged( int oldZoom, int newZoom ) {
		updateTileSet();
	}

	@Override
	public void onZoomScaleChanged( double scale ) {
		setScale( scale );
	}

}
