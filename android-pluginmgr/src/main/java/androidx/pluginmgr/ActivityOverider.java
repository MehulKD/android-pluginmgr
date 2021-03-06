/**
 * 
 */
package androidx.pluginmgr;

import java.io.File;
import java.lang.reflect.Field;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Window;

/**
 * 提供公共方法供自动生成的Activity调用
 * 
 * @author HouKangxi
 */
public class ActivityOverider {
	private static final String tag = "ActivityOverider";
	/**
	 * 自动生成的 Activity 的全类名
	 */
	static final String targetClassName = "androidx.pluginmgr.PluginActivity";

	/**
	 * 处理 Intent 跳转
	 * <p>
	 * 供插件中的 startActivity 调用
	 * 
	 * @param intent
	 *            - 启动其他Activity的Intent请求
	 * @param requestCode
	 * @param options
	 * @param pluginId
	 *            - 插件id
	 * @param fromAct
	 *            - 发出请求的Activity
	 * @return 修改后的 Intent
	 */
	public static Intent newIntent(Intent intent, int requestCode,
			Bundle options, String pluginId, Activity fromAct) {
		// 主要做以下工作：
		// 1 、修改Intent的跳转目标
		// 2 、帮助插件类加载器决定使用哪个activity类加载器
		PluginManager mgr = PluginManager.getInstance();
		// 优先判断类名，若类名为空再判断 Action
		if (intent.getComponent() != null
				&& intent.getComponent().getClassName() != null) {
			// action 为空，但是指定了包名和 activity类名
			ComponentName compname = intent.getComponent();
			String pkg = compname.getPackageName();
			String toActName = compname.getClassName();
			PlugInfo thisPlugin = mgr.getPluginById(pluginId);
			ActivityInfo actInThisApk = null;
			if (pkg != null) {
				if (pkg.equals(thisPlugin.getPackageName())) {
					actInThisApk = thisPlugin
							.findActivityByClassName(toActName);
				}
			} else {
				actInThisApk = thisPlugin.findActivityByClassName(toActName);
			}
			if (actInThisApk != null) {
				setPluginIntent(intent, thisPlugin, actInThisApk.name);
			} else {
				for (PlugInfo plugInfo : mgr.getPlugins()) {
					if (plugInfo == thisPlugin) {
						continue;
					}
					ActivityInfo otherAct = plugInfo
							.findActivityByClassName(toActName);
					if (otherAct != null) {
						setPluginIntent(intent, plugInfo, otherAct.name);
						break;
					}
				}
			}
		} else if (intent.getAction() != null) {
			String action = intent.getAction();
			//
			// 开始处理 action
			// 先判断activity所在的插件有没有对应action,因为绝大多数情况下应用都是在其内部界面之间跳转
			PlugInfo thisPlugin = mgr.getPluginById(pluginId);
			ActivityInfo actInThisApk = thisPlugin.findActivityByAction(action);
			if (actInThisApk != null) {
				setPluginIntent(intent, thisPlugin, actInThisApk.name);
			} else {
				for (PlugInfo plugInfo : mgr.getPlugins()) {
					if (plugInfo == thisPlugin) {
						continue;
					}
					ActivityInfo otherAct = plugInfo
							.findActivityByAction(action);
					if (otherAct != null) {
						setPluginIntent(intent, plugInfo, otherAct.name);
						break;
					}
				}
			}
		}

		return intent;
	}

	private static void setPluginIntent(Intent intent, PlugInfo plugin,
			String actName) {
		String pkgName = null;
		if (intent.getComponent() != null) {
			pkgName = intent.getComponent().getPackageName();
		}
		PluginManager mgr = PluginManager.getInstance();
		String pluginId = plugin.getId();
		createProxyDex(plugin, actName);
		String act = mgr.getFrameworkClassLoader().newActivityClassName(
				pluginId, actName);
		ComponentName compname = null;
		if (pkgName != null) {
			compname = new ComponentName(pkgName, act);
		} else {
			compname = new ComponentName(mgr.getContext(), act);
		}
		intent.setComponent(compname);
	}

	static File getPorxyActivityDexPath(PlugInfo plugin, String activity) {
		String actName = activity;
		String pluginPath = PluginManager.getInstance()
				.getDexInternalStoragePath().getAbsolutePath();
		String pluginDir = pluginPath + '/' + plugin.getId() + ".acts/";
		File folder = new File(pluginDir);
		folder.mkdirs();
		File saveDir = new File(folder, actName + ".dex");
		return saveDir;
	}

	static void createProxyDex(PlugInfo plugin, String activity) {
		createProxyDex(plugin, activity, true);
	}

	static void createProxyDex(PlugInfo plugin, String activity, boolean lazy) {
		File saveDir = getPorxyActivityDexPath(plugin, activity);
		createProxyDex(plugin, activity, saveDir, lazy);
	}

	static void createProxyDex(PlugInfo plugin, String activity, File saveDir,
			boolean lazy) {
		// Log.d(tag + ":createProxyDex", "plugin=" + plugin + "\n, activity="
		// + activity);
		if (lazy && saveDir.exists()) {
			// Log.d(tag, "dex alreay exists: " + saveDir);
			// 已经存在就不创建了，直接返回
			return;
		}
		// Log.d(tag, "actName=" + actName + ", saveDir=" + saveDir);
		try {
			String pkgName = plugin.getPackageName();
			ActivityClassGenerator.createActivityDex(activity, targetClassName,
					saveDir, plugin.getId(), pkgName);
		} catch (Throwable e) {
			Log.e(tag, Log.getStackTraceString(e));
		}
	}

	/**
	 * 按照pluginId寻找AssetManager
	 * <p>
	 * 供插件中的 onCreate()方法内 (super.onCreate()之前)调用 <br/>
	 * 到了这里可以说框架已经成功创建了activity
	 * 
	 * @param pluginId
	 *            -插件Id
	 * @param fromAct
	 *            - 发出请求的Activity
	 * @return
	 */
	public static AssetManager getAssetManager(String pluginId, Activity fromAct) {
		PlugInfo rsinfo = PluginManager.getInstance().getPluginById(pluginId);
		// fromAct.getApplicationContext();
		try {
			Field f = ContextWrapper.class.getDeclaredField("mBase");
			f.setAccessible(true);
			f.set(fromAct, rsinfo.getApplication());
		} catch (Exception e) {
			Log.e(tag, Log.getStackTraceString(e));
		}
		// 如果是三星Galaxy S4 手机，则使用包装的LayoutInflater替换原LayoutInflater
		// 这款手机在解析内置的布局文件时有各种错误
		if (android.os.Build.MODEL.equals("GT-I9500")) {
			Window window = fromAct.getWindow();// 得到 PhoneWindow 实例
			try {
				ReflectionUtils.setFieldValue(window, "mLayoutInflater",
						new LayoutInflaterWrapper(window.getLayoutInflater()));
			} catch (Exception e) {
				Log.e(tag, Log.getStackTraceString(e));
			}
		}
		return rsinfo.getAssetManager();
	}

	/**
	 * 按下back键的方法调用
	 * 
	 * @param pluginId
	 * @param fromAct
	 * @return 是否调用父类的onBackPressed()方法
	 */
	public static boolean overideOnbackPressed(String pluginId, Activity fromAct) {
		PlugInfo plinfo = PluginManager.getInstance().getPluginById(pluginId);
		String actName = fromAct.getClass().getSuperclass().getSimpleName();
		ActivityInfo actInfo = plinfo.findActivityByClassName(actName);
		boolean finish = plinfo.isFinishActivityOnbackPressed(actInfo);
		if (finish) {
			fromAct.finish();
		}
		boolean ivsuper = plinfo.isInvokeSuperOnbackPressed(actInfo);
		Log.d(tag, "finish? " + finish + ", ivsuper? " + ivsuper);
		return ivsuper;
	}

	//
	// =================== Activity 生命周期回调方法 ==================
	//
	public static void callback_onCreate(String pluginId, Activity fromAct) {
		PluginManager con = PluginManager.getInstance();
		PluginActivityLifeCycleCallback callback = con
				.getPluginActivityLifeCycleCallback();
		if (callback != null) {
			callback.onCreate(pluginId, fromAct);
		}
	}

	public static void callback_onResume(String pluginId, Activity fromAct) {
		PluginActivityLifeCycleCallback callback = PluginManager.getInstance()
				.getPluginActivityLifeCycleCallback();
		if (callback != null) {
			callback.onResume(pluginId, fromAct);
		}
	}

	public static void callback_onStart(String pluginId, Activity fromAct) {
		PluginActivityLifeCycleCallback callback = PluginManager.getInstance()
				.getPluginActivityLifeCycleCallback();
		if (callback != null) {
			callback.onStart(pluginId, fromAct);
		}
	}

	public static void callback_onRestart(String pluginId, Activity fromAct) {
		PluginActivityLifeCycleCallback callback = PluginManager.getInstance()
				.getPluginActivityLifeCycleCallback();
		if (callback != null) {
			callback.onRestart(pluginId, fromAct);
		}
	}

	public static void callback_onPause(String pluginId, Activity fromAct) {
		PluginActivityLifeCycleCallback callback = PluginManager.getInstance()
				.getPluginActivityLifeCycleCallback();
		if (callback != null) {
			callback.onPause(pluginId, fromAct);
		}
	}

	public static void callback_onStop(String pluginId, Activity fromAct) {
		PluginActivityLifeCycleCallback callback = PluginManager.getInstance()
				.getPluginActivityLifeCycleCallback();
		if (callback != null) {
			callback.onStop(pluginId, fromAct);
		}
	}

	public static void callback_onDestroy(String pluginId, Activity fromAct) {
		PluginActivityLifeCycleCallback callback = PluginManager.getInstance()
				.getPluginActivityLifeCycleCallback();
		if (callback != null) {
			callback.onDestroy(pluginId, fromAct);
		}
	}
}
