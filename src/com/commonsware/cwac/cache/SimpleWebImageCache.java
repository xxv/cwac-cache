/***
	Copyright (c) 2008-2009 CommonsWare, LLC
	
	Licensed under the Apache License, Version 2.0 (the "License"); you may
	not use this file except in compliance with the License. You may obtain
	a copy of the License at
		http://www.apache.org/licenses/LICENSE-2.0
	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.
*/

package com.commonsware.cwac.cache;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.security.MessageDigest;

import android.R.dimen;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.BitmapFactory.Options;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

import com.commonsware.cwac.bus.AbstractBus;
import com.commonsware.cwac.task.AsyncTaskEx;

public class SimpleWebImageCache<B extends AbstractBus, M>
	extends CacheBase<String, Drawable> {
	private static final String TAG="SimpleWebImageCache";
	private B bus=null;
	
	private int mMaxWidth, mMaxHeight;
	private boolean mScale = false;

	static public File buildCachedImagePath(File cacheRoot, String url)
		throws Exception {
		return(new File(cacheRoot, md5(url)));
	}
	
	static protected String md5(String s) throws Exception {
		MessageDigest md=MessageDigest.getInstance("MD5");
		
		md.update(s.getBytes());
		
		byte digest[]=md.digest();
		StringBuffer result=new StringBuffer();
		
		for (int i=0; i<digest.length; i++) {
			result.append(Integer.toHexString(0xFF & digest[i]));
		}
				
		return(result.toString());
	}
	
	public SimpleWebImageCache(File cacheRoot,
														 AsyncCache.DiskCachePolicy policy,
														 int maxSize,
														 B bus) {
		super(cacheRoot, policy, maxSize);
		
		this.bus=bus;
	}
	
	@Override
	public int getStatus(String key) {
		int result=super.getStatus(key);
		
		if (result==CACHE_NONE && getCacheRoot()!=null) {
			try {
				File cache=buildCachedImagePath(key);
				
				if (cache.exists()) {
					result=CACHE_DISK;
				}
			}
			catch (Throwable t) {
				Log.e(TAG, "Exception getting cache status", t);
			}
		}
		
		return(result);
	}
	
	public File buildCachedImagePath(String url)
		throws Exception {
		if (getCacheRoot()==null) {
			return(null);
		}
		
		return(buildCachedImagePath(getCacheRoot(), url));
	}
	
	public void notify(String key, M message)
		throws Exception {
		int status=getStatus(key);
		
		if (status==CACHE_NONE) {
			new FetchImageTask().execute(message, key,
																	 buildCachedImagePath(key));
		}
		else if (status==CACHE_DISK) {
			new LoadImageTask().execute(message, key,
																	 buildCachedImagePath(key));
		}
		else {
			bus.send(message);
		}
	}
	
	public B getBus() {
		return(bus);
	}
	
	/**
	 * Set the maximum allowed size for images in the cache and 
	 * enables image scaling. 
	 * 
	 * Scaling preserves aspect ratio.
	 * 
	 * @param width maximum width
	 * @param height maximum height
	 * @see setScaleImage()
	 */
	public void setMaxSize(int width, int height){
		mMaxWidth = width;
		mMaxHeight = height;
		mScale = true;
	}
	
	/**
	 * sets whether or not the image gets scaled.
	 * 
	 * @param scaleImages true if you want the images to be scaled.
	 */
	public void setScaleImage(boolean scaleImages){
		mScale = scaleImages;
	}
	
	/**
	 * Scale a bitmap, preserving its aspect ratio.
	 * 
	 * @param bmap
	 * @return
	 */
	private Bitmap scaleBitmapPreserveAspect(Bitmap bmap){
        	if (bmap == null){
        		return null;
        	}
        	
        	int origWidth = bmap.getWidth();
        	int origHeight = bmap.getHeight();
        	float scaleWidth = (float)mMaxWidth / origWidth;
        	float scaleHeight = (float)mMaxHeight / origHeight;
        	float scale = Math.min(scaleWidth, scaleHeight);
        	
    		bmap = Bitmap.createScaledBitmap(bmap, (int)((float)origWidth * scale), (int)((float)origHeight * scale), true);
        
    		return bmap;
	}
	
	class FetchImageTask
		extends AsyncTaskEx<Object, Void, Void> {
		@Override
		protected Void doInBackground(Object... params) {
			String url=params[1].toString();
			File cache=(File)params[2];
			
			try {
				URLConnection connection=new URL(url).openConnection();
				InputStream stream=connection.getInputStream();
				BufferedInputStream in=new BufferedInputStream(stream);
				ByteArrayOutputStream out=new ByteArrayOutputStream(10240);
				int read;
				byte[] b=new byte[4096];
				
				while ((read = in.read(b)) != -1) {
						out.write(b, 0, read);
				}
				
				out.flush();
				out.close();
				
				byte[] raw=out.toByteArray();
				
                Options opts = new Options();
                opts.inPurgeable = true;
                Bitmap bmap = BitmapFactory.decodeStream(new ByteArrayInputStream(raw), null, opts);
                if (mScale){
                	bmap = scaleBitmapPreserveAspect(bmap);
                }

				put(url, new BitmapDrawable(bmap));
				
				M message=(M)params[0];
				
				if (message!=null) {
					bus.send(message);
				}
				
				if (cache!=null) {
					FileOutputStream file=new FileOutputStream(cache);
					
					file.write(raw);
					file.flush();
					file.close();
				}
			}
			catch (Throwable t) {
				Log.e(TAG, "Exception downloading image", t);
			}
			
			return(null);
		}
	}

	class LoadImageTask extends AsyncTaskEx<Object, Void, Void> {
		@Override
		protected Void doInBackground(Object... params) {
			String url=params[1].toString();
			File cache=(File)params[2];
			
			try {
                Options opts = new Options();
                opts.inPurgeable = true;
                Bitmap b = BitmapFactory.decodeFile(cache.getAbsolutePath(), opts);
                if (mScale){
                	b = scaleBitmapPreserveAspect(b);
                }
				put(url, new BitmapDrawable(b));
				
//				M message=(M)params[0];
				
				if (params[0]!=null) {
					bus.send(params[0]);
				}
			}
			catch (Throwable t) {
				Log.e(TAG, "Exception downloading image", t);
			}
			
			return(null);
		}
	}
}
