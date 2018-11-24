package com.danikula.videocache;

import android.content.Context;
import android.os.Environment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

import static android.os.Environment.MEDIA_MOUNTED;

/**
 * Provides application storage paths
 * <p/>
 * See https://github.com/nostra13/Android-Universal-Image-Loader
 *
 * @author Sergey Tarasevich (nostra13[at]gmail[dot]com)
 * @since 1.0.0
 */
final class StorageUtils {

    private static final Logger LOG = LoggerFactory.getLogger("StorageUtils");
    private static final String INDIVIDUAL_DIR_NAME = "video-cache";

    /**
     * Returns individual application cache directory (for only video caching from Proxy). Cache directory will be
     * created on SD card <i>("/Android/data/[app_package_name]/cache/video-cache")</i> if card is mounted .
     * Else - Android defines cache directory on device's file system.
     * 返回 视频的缓存目录
     * @param context Application context
     * @return Cache {@link File directory}
     */
    public static File getIndividualCacheDirectory(Context context) {
        File cacheDir = getCacheDirectory(context, true);
        return new File(cacheDir, INDIVIDUAL_DIR_NAME);
    }

    /**
     * Returns application cache directory. Cache directory will be created on SD card
     * <i>("/Android/data/[app_package_name]/cache")</i> (if card is mounted and app has appropriate permission) or
     * on device's file system depending incoming parameters.
     * 返回可用的缓存目录
     * @param context        Application context
     * @param preferExternal Whether prefer external location for cache，是否使用外部缓存
     * @return Cache {@link File directory}.<br />
     * <b>NOTE:</b> Can be null in some unpredictable cases (if SD card is unmounted and
     * {@link android.content.Context#getCacheDir() Context.getCacheDir()} returns null).
     */
    private static File getCacheDirectory(Context context, boolean preferExternal) {
        File appCacheDir = null;
        String externalStorageState;

        // 获取sdcard卡的状态
        try {
            externalStorageState = Environment.getExternalStorageState();
        } catch (NullPointerException e) { // (sh)it happens
            externalStorageState = "";
        }

        // preferExternal = true : 使用外部存储
        // MEDIA_MOUNTED.equals(externalStorageState) ： 确认sdcard卡是否存在
        if (preferExternal && MEDIA_MOUNTED.equals(externalStorageState)) {
            // 创建 路径为 sdcard文件根目录/Android/data/packageName/cache 的缓存文件目录
            appCacheDir = getExternalCacheDir(context);
        }

        // 获取外部存储路径失败，获取应用缓存目录
        if (appCacheDir == null) {
            // getCacheDir：获取 /data/data/<application package>/cache目录
            appCacheDir = context.getCacheDir();
        }

        // 强制 返回 应用缓存的目录
        if (appCacheDir == null) {
            String cacheDirPath = "/data/data/" + context.getPackageName() + "/cache/";
            LOG.warn("Can't define system cache directory! '" + cacheDirPath + "%s' will be used.");
            appCacheDir = new File(cacheDirPath);
        }
        return appCacheDir;
    }

    /**
     * 获取 缓存的外部存储目录
     * sdcard文件根目录/Android/data/packageName/cache
     * @param context
     * @return
     */
    private static File getExternalCacheDir(Context context) {
        // Environment.getExternalStorageDirectory() : 获取扩展存储设备的文件目录
        // 创建文件目录：sdcard文件根目录/Android/data
        File dataDir = new File(new File(Environment.getExternalStorageDirectory(), "Android"), "data");
        //  创建应用cache目录：sdcard文件根目录/Android/data/packageName/cache
        File appCacheDir = new File(new File(dataDir, context.getPackageName()), "cache");
        if (!appCacheDir.exists()) {
            // 如果文件不存在，创建文件目录
            if (!appCacheDir.mkdirs()) {
                LOG.warn("Unable to create external cache directory");
                return null;
            }
        }
        return appCacheDir;
    }
}
