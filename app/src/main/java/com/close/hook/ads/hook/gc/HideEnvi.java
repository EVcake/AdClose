package com.close.hook.ads.hook.gc;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Arrays;

import com.close.hook.ads.hook.util.HookUtil;

import de.robv.android.xposed.XposedBridge;

/*
 * 2023.12.8-10:14
 * 参考 https://d0nuts33.github.io/2023/04/29/加固防护总结
 */

public class HideEnvi {

    private static final Set<String> XPOSED_MAGISK_PATHS = Set.of(
            "/sbin/.magisk",
            "/system/bin/magisk",
            "/data/data/com.topjohnwu.magisk",
            "/system/lib/libriruloader.so",
            "/system/bin/su",
            "/system/xbin/su",
            "/system/sbin/su",
            "/sbin/su",
            "/vendor/bin/su"
    );

    private static final String[] XPOSED_FEATURES = {
            "xposed.installer",
            "app_process_xposed",
            "libriru_",
            "/data/misc/edxp_",
            "libxposed_art.so",
            "libriruloader.so",
            "app_process_zposed",
            "liblspd.so",
            "libriru_edxp.so"
    };

    private static final String[] MAGISK_FEATURES = {"/.magisk", "MAGISK_INJ_"};

    private static final ReentrantLock lock = new ReentrantLock();

    public static void handle() {
        hideXposedMagiskPaths();
        hideXposedMagiskInExec();
        hideSystemProperties();
        AntiEmulatorDetection.handle();
    }

    private static void hideXposedMagiskPaths() {
        HookUtil.hookAllMethods(File.class, "exists", "before", param -> {
            File file = (File) param.thisObject;
            lock.lock();
            try {
                if (isXposedMagiskPath(file.getAbsolutePath())) {
                    param.setResult(false);
                }
            } finally {
                lock.unlock();
            }
        });
    }

    private static boolean isXposedMagiskPath(String path) {
        return XPOSED_MAGISK_PATHS.stream().anyMatch(path::startsWith);
    }

    private static void hideXposedMagiskInExec() {
        HookUtil.hookAllMethods(Runtime.class, "exec", "before", param -> {
            lock.lock();
            try {
                if (detectXposedOrMagiskInMemory()) {
                    param.setResult(null);
                }
            } finally {
                lock.unlock();
            }
        });
    }

    private static boolean detectXposedOrMagiskInMemory() {
        lock.lock();
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (lineContainsXposedMagiskFeatures(line)) {
                    return true;
                }
            }
        } catch (Exception e) {
            XposedBridge.log("HideEnvi Error while reading maps: " + e.getMessage());
        } finally {
            lock.unlock();
        }
        return false;
    }

    private static boolean lineContainsXposedMagiskFeatures(String line) {
        return matchesAnyFeature(line, XPOSED_FEATURES) || matchesAnyFeature(line, MAGISK_FEATURES, "r-xp") || line.length() > 8192;
    }

    private static boolean matchesAnyFeature(String line, String[] features) {
        return Arrays.stream(features).anyMatch(line::contains);
    }

    private static boolean matchesAnyFeature(String line, String[] features, String additionalCondition) {
        return Arrays.stream(features).anyMatch(feature -> line.contains(feature) && line.contains(additionalCondition));
    }

    private static void hideSystemProperties() {
        HookUtil.hookAllMethods("android.os.SystemProperties", "get", "before", param -> {
            String key = (String) param.args[0];
            lock.lock();
            try {
                if (isXposedMagiskProperty(key)) {
                    param.setResult("");
                }
            } finally {
                lock.unlock();
            }
        });
    }

    private static boolean isXposedMagiskProperty(String key) {
        String lowerKey = key.toLowerCase();
        return lowerKey.contains("xposed") || lowerKey.contains("magisk");
    }
}
