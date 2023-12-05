package com.close.hook.ads.hook;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import android.util.Log;

import com.close.hook.ads.hook.gc.DisableFlagSecure;
import com.close.hook.ads.hook.gc.DisableShakeAd;
import com.close.hook.ads.hook.gc.network.HideVPNStatus;
import com.close.hook.ads.hook.gc.network.HostHook;
import com.close.hook.ads.hook.gc.HideEnvi;
import com.close.hook.ads.hook.ha.AppAds;
import com.close.hook.ads.hook.ha.SDKHooks;
import com.close.hook.ads.hook.ha.Others;
import com.close.hook.ads.hook.preference.PreferencesHelper;
import com.close.hook.ads.ui.activity.MainActivity;

import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookInit implements IXposedHookLoadPackage {

	private static final String TAG = "com.close.hook.ads";

	@SuppressLint("SuspiciousIndentation")
	@Override
	public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {

		if (shouldIgnorePackage(lpparam)) {
			return;
		}


		Others.handle(lpparam); // 一些另适配的流氓


		if (TAG.equals(lpparam.packageName)) {
			XposedHelpers.findAndHookMethod(MainActivity.class.getName(), lpparam.classLoader, "isModuleActivated",
			XC_MethodReplacement.returnConstant(true));
		}


		PreferencesHelper prefsHelper = new PreferencesHelper("com.close.hook.ads", "com.close.hook.ads_preferences");
		SettingsManager settingsManager = new SettingsManager(prefsHelper, lpparam.packageName);

		if (settingsManager.isHostHookEnabled()) {
			HostHook.init();
		}

		if (settingsManager.isHideVPNStatusEnabled()) {
			HideVPNStatus.proxy();
		}

		if (settingsManager.isDisableFlagSecureEnabled()) {
			DisableFlagSecure.process();
		}

		if (settingsManager.IsHideEnivEnabled()) {
			HideEnvi.handle();
		}

		if (settingsManager.isDisableShakeAdEnabled()) {
			DisableShakeAd.handle(lpparam);
		}

		try {
		XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				Context context = (Context) param.args[0];
				ClassLoader classLoader = context.getClassLoader();
				XposedBridge.log("found classload is => " + classLoader.toString());

				String packageName = context.getPackageName();

                CharSequence appName = getAppName(context, packageName);
                XposedBridge.log("Application Name: " + appName);


							if (settingsManager.isHandlePlatformAdEnabled()) {
								SDKHooks.hookAds(classLoader);
							}

							if (settingsManager.isHandleAppsAdEnabled()) {
								AppAds.progress(classLoader, packageName);
							}

						}
					});
		} catch (Exception e) {
			XposedBridge.log(TAG + " Exception in handleLoadPackage: " + Log.getStackTraceString(e));
		}
	}

	private boolean shouldIgnorePackage(XC_LoadPackage.LoadPackageParam lpparam) {
		return lpparam.appInfo == null
				|| (lpparam.appInfo.flags
						& (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
				|| !lpparam.isFirstApplication;
	}
	
    private CharSequence getAppName(Context context, String packageName) {
        PackageManager packageManager = context.getPackageManager();
        try {
            ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
            return packageManager.getApplicationLabel(appInfo);
        } catch (PackageManager.NameNotFoundException e) {
            XposedBridge.log("Application Name Not Found for package: " + packageName);
            return null;
        }
    }
}
