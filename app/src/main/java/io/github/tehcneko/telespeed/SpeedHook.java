package io.github.tehcneko.telespeed;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.function.BiConsumer;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.annotations.AfterInvocation;
import io.github.libxposed.api.annotations.BeforeInvocation;
import io.github.libxposed.api.annotations.XposedHooker;

/**
 * @noinspection JavaReflectionMemberAccess, DataFlowIssue, DataFlowIssue, DataFlowIssue
 */
@SuppressLint({"PrivateApi", "BlockedPrivateApi", "DiscouragedPrivateApi"})
public class SpeedHook extends XposedModule {
    private final static String KEY_SPEED = "speed";
    public final static String BOOST_NONE = "none";
    public final static String BOOST_AVERAGE = "average";
    public final static String BOOST_EXTREME = "extreme";
    private final static int TYPE_ERROR_SUBTITLE = 4;
    private final static long DEFAULT_MAX_FILE_SIZE = 1024L * 1024L * 2000L;

    public SpeedHook(@NonNull XposedInterface base, @NonNull ModuleLoadedParam param) {
        super(base, param);
    }

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        if (!param.isFirstPackage()) return;

        var classLoader = param.getClassLoader();
        FileLoadOperationHook.preferences = getRemotePreferences("conf");

        try {
            loadBulletin(classLoader, getApplicationInfo().sourceDir);
        } catch (Throwable t) {
            log("loadBulletin failed", t);
        }

        try {
            hookFileLoadOperation(classLoader);
        } catch (Throwable t) {
            log("hook FileLoadOperation failed", t);
            try {
                hookToast();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * @noinspection deprecation
     */
    private void loadBulletin(ClassLoader classLoader, String modulePath) throws IllegalAccessException, InstantiationException, NoSuchMethodException, ClassNotFoundException, InvocationTargetException, NoSuchFieldException {
        var am = AssetManager.class.newInstance();
        var addAssetPath = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
        addAssetPath.setAccessible(true);

        if ((int) addAssetPath.invoke(am, modulePath) > 0) {
            FileLoadOperationHook.resources = new Resources(am, null, null);
        }

        var notificationCenterClazz = classLoader.loadClass("org.telegram.messenger.NotificationCenter");
        var showBulletin = (int) notificationCenterClazz.getDeclaredField("showBulletin").get(null);
        var globalInstance = notificationCenterClazz.getDeclaredMethod("getGlobalInstance").invoke(null);
        var postNotificationNameMethod = notificationCenterClazz.getDeclaredMethod("postNotificationName", int.class, Object[].class);

        FileLoadOperationHook.showBulletin = (title, subtitle) ->
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        postNotificationNameMethod.invoke(
                                globalInstance,
                                showBulletin,
                                new Object[]{
                                        TYPE_ERROR_SUBTITLE,
                                        title,
                                        subtitle});
                    } catch (Throwable ignored) {
                    }
                });
    }

    private void hookFileLoadOperation(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        var fileLoadOperationClazz = classLoader.loadClass("org.telegram.messenger.FileLoadOperation");
        var method = fileLoadOperationClazz.getDeclaredMethod("updateParams");
        hook(method, FileLoadOperationHook.class);
    }

    private void hookToast() throws NoSuchMethodException {
        var method = Activity.class.getDeclaredMethod("onResume");
        hook(method, ToastHooker.class);
    }

    @XposedHooker
    private static class FileLoadOperationHook implements Hooker {
        public static Resources resources;
        public static SharedPreferences preferences;
        public static BiConsumer<String, String> showBulletin;

        public static Field downloadChunkSizeBigField;
        public static Field maxDownloadRequestsField;
        public static Field maxDownloadRequestsBigField;
        public static Field maxCdnPartsField;
        public static Field totalBytesCountField;

        private static long speedUpShown = 0;

        @AfterInvocation
        public static void after(@NonNull AfterHookCallback callback) throws NoSuchFieldException, IllegalAccessException {
            int downloadChunkSizeBig;
            int maxDownloadRequests;
            int maxDownloadRequestsBig;
            var speed = preferences.getString(KEY_SPEED, BOOST_AVERAGE);
            if (BOOST_AVERAGE.equals(speed)) {
                downloadChunkSizeBig = 1024 * 512;
                maxDownloadRequests = 8;
                maxDownloadRequestsBig = 8;
            } else if (BOOST_EXTREME.equals(speed)) {
                downloadChunkSizeBig = 1024 * 1024;
                maxDownloadRequests = 12;
                maxDownloadRequestsBig = 12;
            } else {
                downloadChunkSizeBig = 1024 * 128;
                maxDownloadRequests = 4;
                maxDownloadRequestsBig = 4;
            }
            var maxCdnParts = (int) (DEFAULT_MAX_FILE_SIZE / downloadChunkSizeBig);

            var object = callback.getThisObject();
            assert object != null;
            var clazz = object.getClass();
            if (downloadChunkSizeBigField == null) {
                downloadChunkSizeBigField = clazz.getDeclaredField("downloadChunkSizeBig");
                downloadChunkSizeBigField.setAccessible(true);
                maxDownloadRequestsField = clazz.getDeclaredField("maxDownloadRequests");
                maxDownloadRequestsField.setAccessible(true);
                maxDownloadRequestsBigField = clazz.getDeclaredField("maxDownloadRequestsBig");
                maxDownloadRequestsBigField.setAccessible(true);
                maxCdnPartsField = clazz.getDeclaredField("maxCdnParts");
                maxCdnPartsField.setAccessible(true);
                totalBytesCountField = clazz.getDeclaredField("totalBytesCount");
                totalBytesCountField.setAccessible(true);
            }
            downloadChunkSizeBigField.set(object, downloadChunkSizeBig);
            maxDownloadRequestsField.set(object, maxDownloadRequests);
            maxDownloadRequestsBigField.set(object, maxDownloadRequestsBig);
            maxCdnPartsField.set(object, maxCdnParts);

            if (!BOOST_NONE.equals(speed) && resources != null && showBulletin != null) {
                var fileSize = totalBytesCountField.getLong(object);
                if (fileSize > 15 * 1024 * 1024 && System.currentTimeMillis() - speedUpShown > 1000 * 60 * 5) {
                    speedUpShown = System.currentTimeMillis();
                    String speedString;
                    if (BOOST_AVERAGE.equals(speed)) {
                        speedString = resources.getString(R.string.boost_average);
                    } else if (BOOST_EXTREME.equals(speed)) {
                        speedString = resources.getString(R.string.boost_extreme);
                    } else {
                        speedString = resources.getString(R.string.boost_none);
                    }
                    var title = resources.getString(R.string.speed_toast);
                    var subtitle = resources.getString(R.string.speed_toast_level, "Nekogram", speedString);
                    showBulletin.accept(title, subtitle);
                }
            }
        }
    }

    @XposedHooker
    private static class ToastHooker implements Hooker {
        @BeforeInvocation
        public static void before(@NonNull BeforeHookCallback callback) {
            var activity = (Activity) callback.getThisObject();
            assert activity != null;
            Toast.makeText(activity, "TeleSpeed: Unsupported Telegram version.", Toast.LENGTH_LONG).show();
            activity.finish();
        }
    }
}
