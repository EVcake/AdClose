package com.close.hook.ads.hook.gc.network;

import android.app.AndroidAppHelper;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.Nullable;

import com.close.hook.ads.data.model.BlockedRequest;
import com.close.hook.ads.data.model.RequestDetails;
import com.close.hook.ads.data.model.Url;
import com.close.hook.ads.hook.util.HookUtil;
import com.close.hook.ads.hook.util.DexKitUtil;
import com.close.hook.ads.hook.util.StringFinderKit;
import com.close.hook.ads.provider.UrlContentProvider;

import org.luckypray.dexkit.result.MethodData;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

public class RequestHook {
    private static final String LOG_PREFIX = "[RequestHook] ";

    @Nullable
    private static String method;
    @Nullable
    private static String urlString;
    @Nullable
    private static String requestHeaders;
    private static int responseCode = -1;
    @Nullable
    private static String responseMessage;
    @Nullable
    private static String responseHeaders;
    @Nullable
    private static String stack;

    public static void init() {
        try {
            setupDNSRequestHook();
            setupHttpRequestHook();
            setupOkHttpRequestHook();
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Error while hooking: " + e.getMessage());
        }
    }

    private static void setupDNSRequestHook() {
        XposedHelpers.findAndHookMethod(InetAddress.class, "getByName", String.class, InetAddressHook);
        XposedHelpers.findAndHookMethod(InetAddress.class, "getAllByName", String.class, InetAddressHook);
    }

    private static final XC_MethodHook InetAddressHook = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            String host = (String) param.args[0];
            if (host != null && shouldBlockDnsRequest(host)) {
                if ("getByName".equals(param.method.getName())) {
                    param.setResult(null);
                } else if ("getAllByName".equals(param.method.getName())) {
                    param.setResult(new InetAddress[0]);
                }
                return;
            }
        }
    };

    private static boolean shouldBlockDnsRequest(String host) {
        return checkShouldBlockRequest(host, null, " DNS", "host");
    }

    private static boolean shouldBlockHttpsRequest(final URL url, final RequestDetails details) {
        String formattedUrl = formatUrlWithoutQuery(url);
        return checkShouldBlockRequest(formattedUrl, details, " HTTP", "url");
    }

    private static boolean shouldBlockOkHttpsRequest(final URL url, final RequestDetails details) {
        String formattedUrl = formatUrlWithoutQuery(url);
        return checkShouldBlockRequest(formattedUrl, details, " OKHTTP", "url");
    }

    private static boolean checkShouldBlockRequest(final String queryValue, final RequestDetails details, final String requestType, final String queryType) {
        Pair<Boolean, String> pair = queryContentProvider(queryType, queryValue);
        boolean shouldBlock = pair.first;
        String blockType = pair.second;
    
        sendBroadcast(requestType, shouldBlock, blockType, queryValue, details);
        return shouldBlock;
    }

    private static String formatUrlWithoutQuery(URL url) {
        try {
            URL formattedUrl = new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getPath());
            return formattedUrl.toExternalForm();
        } catch (MalformedURLException e) {
            XposedBridge.log(LOG_PREFIX + "Malformed URL: " + e.getMessage());
            return null;
        }
    }

    private static Pair<Boolean, String> queryContentProvider(String queryType, String queryValue) {
        Context context = AndroidAppHelper.currentApplication();
        if (context != null) {
            ContentResolver contentResolver = context.getContentResolver();
            Uri uri = Uri.parse("content://" + UrlContentProvider.AUTHORITY + "/" + UrlContentProvider.URL_TABLE_NAME);
            String[] projection = new String[]{Url.Companion.getURL_TYPE(), Url.Companion.getURL_ADDRESS()};
            String selection = Url.Companion.getURL_TYPE() + " = ?";
            String[] selectionArgs = new String[]{queryType};

            try (Cursor cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int urlTypeIndex = cursor.getColumnIndex(Url.Companion.getURL_TYPE());
                    int urlValueIndex = cursor.getColumnIndex(Url.Companion.getURL_ADDRESS());

                    do {
                        String urlType = cursor.getString(urlTypeIndex);
                        String urlValue = cursor.getString(urlValueIndex);

                        if ("host".equals(queryType) && urlValue.equals(queryValue)) {
                            return new Pair<>(true, "Domain");
                        } else if (("url".equals(queryType) || "keyword".equals(queryType)) && queryValue.contains(urlValue)) {
                            return new Pair<>(true, formatUrlType(urlType));
                        }
                    } while (cursor.moveToNext());
                }
            }
        }
        return new Pair<>(false, null);
    }

    private static String formatUrlType(String urlType) {
        return urlType.replace("url", "URL").replace("keyword", "KeyWord");
    }

    private static void setupHttpRequestHook() {
        try {
            Class<?> httpURLConnectionImpl = Class.forName("com.android.okhttp.internal.huc.HttpURLConnectionImpl");

            XposedHelpers.findAndHookMethod(httpURLConnectionImpl, "execute", boolean.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object httpEngine = XposedHelpers.getObjectField(param.thisObject, "httpEngine");

                    boolean isResponseAvailable = (boolean) XposedHelpers.callMethod(httpEngine, "hasResponse");
                    if (!isResponseAvailable) {
                        return;
                    }

                    XposedHelpers.callMethod(httpEngine, "readResponse");
                    Object response = XposedHelpers.callMethod(httpEngine, "getResponse");

                    Object request = XposedHelpers.callMethod(httpEngine, "getRequest");
                    Object httpUrl = XposedHelpers.callMethod(request, "urlString");
                    URL url = new URL(httpUrl.toString());

                    String stackTrace = HookUtil.getFormattedStackTrace();

                    RequestDetails details = processHttpRequest(request, response, url, stackTrace);

                    if (details != null && shouldBlockHttpsRequest(url, details)) {
                        XposedHelpers.findAndHookMethod(param.thisObject.getClass(), "getInputStream", new XC_MethodReplacement() {
                            @Override
                            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                                return new ByteArrayInputStream(new byte[0]);
                            }
                        });
                    }
                }
            });
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Error setting up HTTP connection hook: " + e.getMessage());
        }
    }

    public static void setupOkHttpRequestHook() {
        String cacheKey = DexKitUtil.INSTANCE.getContext().getPackageName() + ":setupOkHttpRequestHook";
        List<MethodData> foundMethods = StringFinderKit.INSTANCE.findMethodsWithString(cacheKey, "Already Executed", "execute");

        if (foundMethods != null) {
            for (MethodData methodData : foundMethods) {
                try {
                    Method method = methodData.getMethodInstance(DexKitUtil.INSTANCE.getContext().getClassLoader());
  //                XposedBridge.log("hook " + methodData); // okhttp3.Call.execute -overload method
                    XposedBridge.hookMethod(method, new XC_MethodHook() {

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object response = param.getResult();

                            if (response == null) {
                                return;
                            }

                            Object request = XposedHelpers.callMethod(response, "request");
                            Object okhttpUrl = XposedHelpers.callMethod(request, "url");
                            URL url = new URL(okhttpUrl.toString());

                            String stackTrace = HookUtil.getFormattedStackTrace();

                            RequestDetails details = processOkHttpRequest(request, response, url, stackTrace);
                            if (details != null && shouldBlockOkHttpsRequest(url, details)) {
                                Object emptyResponse = createEmptyResponseForOkHttp(response);
                                param.setResult(emptyResponse);
                                return;
                            }
                        }
                    });
                } catch (Exception e) {
                    XposedBridge.log("Error hooking method: " + methodData);
                }
            }
        }
    }

    private static Object createEmptyResponseForOkHttp(Object response) throws Exception {
        if (response instanceof okhttp3.Response) {
            okhttp3.Response originalResponse = (okhttp3.Response) response;
            okhttp3.Request request = originalResponse.request();

            return new okhttp3.Response.Builder()
                    .request(request)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(204) // 204 No Content
                    .message("No Content")
                    .build();
        }
        return null;
    }

    private static RequestDetails processHttpRequest(Object request, Object response, URL url, String stack) {
        try {
            String method = (String) XposedHelpers.callMethod(request, "method");
            String urlString = url.toString();
            Object requestHeaders = XposedHelpers.callMethod(request, "headers");

            int code = (int) XposedHelpers.callMethod(response, "code");
            String message = (String) XposedHelpers.callMethod(response, "message");
            Object responseHeaders = XposedHelpers.callMethod(response, "headers");

            return new RequestDetails(method, urlString, requestHeaders, code, message, responseHeaders, stack);
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Exception in processing Http request: " + e.getMessage());
            return null;
        }
    }

    private static RequestDetails processOkHttpRequest(Object request, Object response, URL url, String stack) {
        try {
            String method = (String) XposedHelpers.callMethod(request, "method");
            String urlString = url.toString();
            Object requestHeaders = XposedHelpers.callMethod(request, "headers");

            int code = (int) XposedHelpers.callMethod(response, "code");
            String message = (String) XposedHelpers.callMethod(response, "message");
            Object responseHeaders = XposedHelpers.callMethod(response, "headers");

            return new RequestDetails(method, urlString, requestHeaders, code, message, responseHeaders, stack);
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Exception in processing OkHttp request: " + e.getMessage());
            return null;
        }
    }

    private static void sendBroadcast(String requestType, boolean shouldBlock, String blockType, String url, RequestDetails details) {
        sendBlockedRequestBroadcast("all", requestType, shouldBlock, blockType, url, details);
        if (shouldBlock) {
            sendBlockedRequestBroadcast("block", requestType, true, blockType, url, details);
        } else {
            sendBlockedRequestBroadcast("pass", requestType, false, blockType, url, details);
        }
    }

    private static void sendBlockedRequestBroadcast(String type, @Nullable String requestType,
                                                @Nullable Boolean isBlocked, @Nullable String blockType, String request, RequestDetails details) {
        Intent intent;
        switch (type) {
            case "all":
                intent = new Intent("com.rikkati.ALL_REQUEST");
                break;
            case "block":
                intent = new Intent("com.rikkati.BLOCKED_REQUEST");
                break;
            default:
                intent = new Intent("com.rikkati.PASS_REQUEST");
                break;
        }

        Context currentContext = AndroidAppHelper.currentApplication();
        if (currentContext != null) {
            PackageManager pm = currentContext.getPackageManager();
            String appName;
            try {
                appName = pm.getApplicationLabel(
                                pm.getApplicationInfo(currentContext.getPackageName(), PackageManager.GET_META_DATA))
                        .toString();
            } catch (PackageManager.NameNotFoundException e) {
                appName = currentContext.getPackageName();
            }
            appName += requestType;
            String packageName = currentContext.getPackageName();
            BlockedRequest blockedRequest;

            String method = details != null ? details.getMethod() : null;
            String urlString = details != null ? details.getUrlString() : null;
            String requestHeaders = details != null && details.getRequestHeaders() != null ? details.getRequestHeaders().toString() : null;
            int responseCode = details != null ? details.getResponseCode() : -1;
            String responseMessage = details != null ? details.getResponseMessage() : null;
            String responseHeaders = details != null && details.getResponseHeaders() != null ? details.getResponseHeaders().toString() : null;
            String stackTrace = details != null ? details.getStack() : null;

            blockedRequest = new BlockedRequest(
                appName, 
                packageName, 
                request,
                System.currentTimeMillis(), 
                type, 
                isBlocked, 
                blockType, 
                method, 
                urlString, 
                requestHeaders,
                responseCode, 
                responseMessage, 
                responseHeaders, 
                stackTrace
            );

            intent.putExtra("request", blockedRequest);
            currentContext.sendBroadcast(intent);
        } else {
            Log.w("RequestHook", "sendBlockedRequestBroadcast: currentContext is null");
        }
    }

}
