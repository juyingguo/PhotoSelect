package com.zhy.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.support.v4.util.LruCache;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ViewGroup.LayoutParams;
import android.widget.ImageView;

import java.io.File;
import java.lang.reflect.Field;
import java.util.LinkedList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * 该类区别{@link ImageLoader}<br/>
 * 直接使用后台线程handler即HandleThread
 */
public class ImageLoader2
{
	private static String TAG = ImageLoader2.class.getSimpleName();
	/**
	 * 图片缓存的核心类
	 */
	private LruCache<String, Bitmap> mLruCache;
	/**
	 * 线程池
	 */
	private ExecutorService mThreadPool;
	/**
	 * 线程池的线程数量，默认为1
	 */
	private int mThreadCount = 1;
	/**
	 * 队列的调度方式
	 */
	private Type mType = Type.LIFO;
	/**
	 * 任务队列  LinkedList从头和尾直接取数据。使用链表，不需要连续的内存
	 */
	private LinkedList<Runnable> mTasks;
	/**
	 * 轮询的线程
	 */
	private Thread mPoolThread;
	private Handler mPoolThreadHander;

	/**
	 * 运行在UI线程的handler，用于给ImageView设置图片
	 */
	private Handler mHandler;

	/**
	 * 引入一个值为1的信号量，防止mPoolThreadHander未初始化完成;作用同步线程
	 */
	//private volatile Semaphore mSemaphore = new Semaphore(0); // TODO: 2017/10/8 不再使用
	/**
	 * 引入一个值为构造时传递的值的信号量，由于线程池内部也有一个阻塞线程，防止加入任务的速度过快，使LIFO效果不明显
	 */
	private volatile Semaphore mPoolSemaphore;

	private static ImageLoader2 mInstance;

	private HandlerThread mHandlerThread;

	/**
	 * 队列的调度方式
	 *
	 * @author zhy
	 *
	 */
	public enum Type
	{
		FIFO, LIFO
	}


	/**
	 * 单例获得该实例对象 双检索式单例
	 *
	 * @return
	 */
	public static ImageLoader2 getInstance()
	{

		if (mInstance == null)
		{
			//这一步可能有多个线程进来
			synchronized (ImageLoader2.class)
			{
				if (mInstance == null)
				{
					mInstance = new ImageLoader2(1, Type.LIFO);
				}
			}
		}
		return mInstance;
	}

	private ImageLoader2(int threadCount, Type type)
	{
		init(threadCount, type);
	}

	private void init(int threadCount, Type type)
	{
		//方式1.自己创建后台handler loop thread
		/*mPoolThread = new Thread()
		{
			@Override  
			public void run()
			{
				Looper.prepare();
				Log.d(TAG ,TAG + ">>>init()>>>>>Looper.prepare():");
				mPoolThreadHander = new Handler()
				{
					@Override
					public void handleMessage(Message msg)
					{
						Log.d(TAG ,TAG + ">>>init()>>>mPoolThreadHander>>handleMessage>>thread-name:" + Thread.currentThread().getName());
						mThreadPool.execute(getTask());
						try
						{
							mPoolSemaphore.acquire();
						} catch (InterruptedException e)
						{
						}
					}
				};
				// 释放一个信号量
				mSemaphore.release();
				Looper.loop();
			}
		};
		mPoolThread.start();*/

		//方式2.直接使用后台线程handler即HandleThread
		mHandlerThread = new HandlerThread("myHandlerThread");
		mHandlerThread.start();
		mPoolThreadHander = new Handler(mHandlerThread.getLooper()){
			@Override
			public void handleMessage(Message msg) {
				super.handleMessage(msg);
				Log.d(TAG ,TAG + ">>>init()>>mPoolThreadHander>>handleMessage>>thread-name:" + Thread.currentThread().getName());

				Runnable task = getTask();
				if (task != null){
					mThreadPool.execute(task);
				}
				//SystemClock.sleep(3 * 1000);
				/*try
				{
					Log.d(TAG ,TAG + ">>>init()>>mPoolThreadHander>>mPoolSemaphore.acquire():");
					mPoolSemaphore.acquire();
				} catch (InterruptedException e)
				{
					e.printStackTrace();
				}*/
			}
		};

		// 获取应用程序最大可用内存
		int maxMemory = (int) Runtime.getRuntime().maxMemory();
		int cacheSize = maxMemory / 8; // TODO: 2017/10/7 8-->>4
		mLruCache = new LruCache<String, Bitmap>(cacheSize)
		{
			@Override
			protected int sizeOf(String key, Bitmap value)
			{
				return value.getRowBytes() * value.getHeight();
			};
		};

		mThreadPool = Executors.newFixedThreadPool(threadCount);
		mPoolSemaphore = new Semaphore(3);
		mTasks = new LinkedList<Runnable>();
		mType = type == null ? Type.LIFO : type;

	}

	/**
	 * 加载图片
	 * 
	 * @param path
	 * @param imageView
	 */
	public void loadImage(final String path, final ImageView imageView)
	{
		Log.d(TAG, "loadImage>>path:" + path);
		File file = new File(path);
		if (file.isDirectory())
		{
			return;
		}
		// set tag
		imageView.setTag(path);
		// UI线程
		if (mHandler == null)
		{
			mHandler = new Handler(Looper.getMainLooper())
			{
				@Override
				public void handleMessage(Message msg)
				{
					ImgBeanHolder holder = (ImgBeanHolder) msg.obj;
					ImageView imageView = holder.imageView;
					Bitmap bm = holder.bitmap;
					String path = holder.path;
					if (imageView.getTag().toString().equals(path))
					{
						imageView.setImageBitmap(bm);
					}
				}
			};
		}

		Bitmap bm = getBitmapFromLruCache(path);
		if (bm != null)
		{
			ImgBeanHolder holder = new ImgBeanHolder();
			holder.bitmap = bm;
			holder.imageView = imageView;
			holder.path = path;
			Message message = Message.obtain();
			message.obj = holder;
			mHandler.sendMessage(message);
		} else
		{
			addTask(new Runnable()
			{
				@Override
				public void run()
				{
					Log.d(TAG, "loadImage>>run>>开始执行.....");
					try
					{
						Log.d(TAG, "loadImage>>run>>mPoolSemaphore.acquire():");
						mPoolSemaphore.acquire();
					} catch (InterruptedException e)
					{
						e.printStackTrace();
					}
					ImageSize imageSize = getImageViewWidth(imageView);

					int reqWidth = imageSize.width;
					int reqHeight = imageSize.height;

					Bitmap bm = decodeSampledBitmapFromResource(path, reqWidth,
							reqHeight);
					addBitmapToLruCache(path, bm);
					ImgBeanHolder holder = new ImgBeanHolder();
					holder.bitmap = getBitmapFromLruCache(path);
					holder.imageView = imageView;
					holder.path = path;
					Message message = Message.obtain();
					message.obj = holder;
					// Log.e("TAG", "mHandler.sendMessage(message);");
					mHandler.sendMessage(message);

					Log.d(TAG, "loadImage>>run>>执行完成准备释放信号量mPoolSemaphore.release()");
					Log.d(TAG, "loadImage>>run>>thread-name:" + Thread.currentThread().getName());
					mPoolSemaphore.release();//任务执行完成释放一个信号量 // TODO: 2017/10/7
				}
			});
		}

	}
	
	/**
	 * 添加一个任务
	 * 
	 * @param runnable
	 */
	private synchronized void addTask(Runnable runnable)
	{
		/*try
		{
			// 请求信号量，防止mPoolThreadHander为null
			if (mPoolThreadHander == null){
				mSemaphore.acquire();
			}
		} catch (InterruptedException e)
		{
		}*/
		mTasks.add(runnable);
		
		mPoolThreadHander.sendEmptyMessage(0x110);
	}

	/**
	 * 取出一个任务
	 * 
	 * @return
	 */
	private synchronized Runnable getTask()
	{
		if (mType == Type.FIFO)
		{
			return mTasks.removeFirst();
		} else if (mType == Type.LIFO)
		{
			return mTasks.removeLast();
		}
		return null;
	}
	
	/**
	 * 单例获得该实例对象
	 * 
	 * @return
	 */
	public static ImageLoader2 getInstance(int threadCount, Type type)
	{

		if (mInstance == null)
		{
			synchronized (ImageLoader2.class)
			{
				if (mInstance == null)
				{
					mInstance = new ImageLoader2(threadCount, type);
				}
			}
		}
		return mInstance;
	}


	/**
	 * 根据ImageView获得适当的压缩的宽和高
	 * 
	 * @param imageView
	 * @return
	 */
	private ImageSize getImageViewWidth(ImageView imageView)
	{
		ImageSize imageSize = new ImageSize();
		final DisplayMetrics displayMetrics = imageView.getContext()
				.getResources().getDisplayMetrics();
		final LayoutParams params = imageView.getLayoutParams();
		Log.d(TAG ,TAG + ">>>getImageViewWidth()>>params.width:" +  params.width
		+ ",params.height:"+  params.height);
		/**
		 * params.width 的到的是px,dp转px和密度有关
		 */
		int width = params.width == LayoutParams.WRAP_CONTENT ? 0 : imageView
				.getWidth(); // Get actual image width；刚创建时imageView.getWidth() 可能为0
		Log.d(TAG ,TAG + ">>>getImageViewWidth()>>111111>>>width:" +  width);
		if (width <= 0){
			width = params.width; // Get layout width parameter
		}
		Log.d(TAG ,TAG + ">>>getImageViewWidth()>>222222>>>width:" +  width);
		if (width <= 0){
			width = getImageViewFieldValue(imageView, "mMaxWidth"); // Check maxWidth
		}
		Log.d(TAG ,TAG + ">>>getImageViewWidth()>>444444>>>width:" +  width);															// parameter
		if (width <= 0){
			width = displayMetrics.widthPixels;
		}
		Log.d(TAG ,TAG + ">>>getImageViewWidth()>>555555>>>width:" +  width);
		int height = params.height == LayoutParams.WRAP_CONTENT ? 0 : imageView
				.getHeight(); // Get actual image height
		if (height <= 0){
			height = params.height; // Get layout height parameter
		}
		if (height <= 0){
			height = getImageViewFieldValue(imageView, "mMaxHeight"); // Check maxHeight parameter
		}
		if (height <= 0){
			height = displayMetrics.heightPixels;
		}
		imageSize.width = width;
		imageSize.height = height;
		return imageSize;

	}

	/**
	 * 从LruCache中获取一张图片，如果不存在就返回null。
	 */
	private Bitmap getBitmapFromLruCache(String key)
	{
		return mLruCache.get(key);
	}

	/**
	 * 往LruCache中添加一张图片
	 * 
	 * @param key
	 * @param bitmap
	 */
	private void addBitmapToLruCache(String key, Bitmap bitmap)
	{
		if (getBitmapFromLruCache(key) == null)
		{
			if (bitmap != null)
				mLruCache.put(key, bitmap);
		}
	}

	/**
	 * 计算inSampleSize，用于压缩图片
	 * 
	 * @param options
	 * @param reqWidth
	 * @param reqHeight
	 * @return
	 */
	private int calculateInSampleSize(BitmapFactory.Options options,
			int reqWidth, int reqHeight)
	{
		// 源图片的宽度
		int width = options.outWidth;
		int height = options.outHeight;
		int inSampleSize = 1;

		if (width > reqWidth && height > reqHeight)
		{
			// 计算出实际宽度和目标宽度的比率
			int widthRatio = Math.round((float) width / (float) reqWidth);//四舍五入
			int heightRatio = Math.round((float) height / (float) reqWidth);
			inSampleSize = Math.max(widthRatio, heightRatio);
		}
		return inSampleSize;
	}

	/**
	 * 根据计算的inSampleSize，得到压缩后图片
	 * 
	 * @param pathName
	 * @param reqWidth
	 * @param reqHeight
	 * @return
	 */
	private Bitmap decodeSampledBitmapFromResource(String pathName,
			int reqWidth, int reqHeight)
	{
		// 第一次解析将inJustDecodeBounds设置为true，来获取图片大小
		final BitmapFactory.Options options = new BitmapFactory.Options();
		options.inJustDecodeBounds = true;
		BitmapFactory.decodeFile(pathName, options);
		// 调用上面定义的方法计算inSampleSize值
		options.inSampleSize = calculateInSampleSize(options, reqWidth,
				reqHeight);
		// 使用获取到的inSampleSize值再次解析图片
		options.inJustDecodeBounds = false;
		Bitmap bitmap = BitmapFactory.decodeFile(pathName, options);

		return bitmap;
	}

	private class ImgBeanHolder
	{
		Bitmap bitmap;
		ImageView imageView;
		String path;
	}

	private class ImageSize
	{
		int width;
		int height;
	}

	/**
	 * 反射获得ImageView设置的最大宽度和高度
	 * 
	 * @param object
	 * @param fieldName
	 * @return
	 */
	private static int getImageViewFieldValue(Object object, String fieldName)
	{
		int value = 0;
		try
		{
			Field field = ImageView.class.getDeclaredField(fieldName);
			field.setAccessible(true);
			int fieldValue = (Integer) field.get(object);
			if (fieldValue > 0 && fieldValue < Integer.MAX_VALUE)
			{
				value = fieldValue;

				Log.e("TAG", value + "");
			}
		} catch (Exception e)
		{
		}
		return value;
	}
}

