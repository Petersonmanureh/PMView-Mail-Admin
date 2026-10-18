package com.pmviewtech.mailadmin;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.net.Uri;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Android admin console. All credentials stay server-side, not in APK. */
public final class AdminActivity extends Activity {
    private static final String API = "https://mailapi.pmviewtech.com/index.php?route=";
    private WebView web;
    private final ExecutorService network = Executors.newFixedThreadPool(2);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(9,21,40));
        getWindow().setNavigationBarColor(Color.rgb(9,21,40));
        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        web.addJavascriptInterface(new SafeApi(), "PMViewNative");
        web.setWebViewClient(new WebViewClient() {
            private boolean allowed(String url) {
                return url.equals("file:///android_asset/index.html") ||
                       url.equals("about:blank");
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v,String url){return !allowed(url);}
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest req){return !allowed(req.getUrl().toString());}
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }
    private class SafeApi {
        @JavascriptInterface public void request(String raw){
            // Safe bridge: page source is bundled locally, no external navigation,
            // and only fixed-host, allowlisted admin API routes are permitted.
            if(raw==null || raw.length()>120000)return;
            network.submit(() -> {
                String id="";String response;
                try {
                    JSONObject request = new JSONObject(raw);
                    id = request.optString("id", "");
                    String route = request.optString("route", "");
                    String method = request.optString("method", "GET");
                    if(!route.matches("admin/[a-z0-9/\\-]{2,90}") ||
                       (!method.equals("GET") && !method.equals("POST")))
                        throw new Exception("Unsupported API request");
                    String token = request.optString("token", "");
                    if(!token.isEmpty() && !token.matches("[a-f0-9]{64}"))throw new Exception("Invalid admin session");
                    String query = request.optString("query", "");
                    if(!query.isEmpty() && !query.matches("[a-z_]+=\\d{1,10}"))throw new Exception("Unsupported query");
                    String endpoint=API+route+(query.isEmpty()?"":"&"+query);
                    HttpURLConnection conn=(HttpURLConnection)new URL(endpoint).openConnection();
                    conn.setRequestMethod(method);
                    conn.setConnectTimeout(12000);conn.setReadTimeout(18000);
                    conn.setInstanceFollowRedirects(false);
                    conn.setRequestProperty("Accept","application/json");
                    if(!token.isEmpty())conn.setRequestProperty("Authorization","Bearer "+token);
                    if(method.equals("POST")) {
                        String body=request.optJSONObject("body")==null?"{}":request.getJSONObject("body").toString();
                        if(body.length()>16000)throw new Exception("Request too large");
                        conn.setDoOutput(true);
                        conn.setRequestProperty("Content-Type","application/json; charset=utf-8");
                        try(OutputStream output=conn.getOutputStream()){
                            output.write(body.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                    int code=conn.getResponseCode();
                    InputStream stream = code>=400?conn.getErrorStream():conn.getInputStream();
                    String json="";
                    if(stream!=null)try(InputStream input=stream; ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                        byte[] buffer=new byte[4096];int n;
                        while((n=input.read(buffer))!=-1){if(bytes.size()+n>1048576)throw new Exception("Response too large");bytes.write(buffer,0,n);}
                        json=bytes.toString("UTF-8");
                    }
                    JSONObject result;
                    try {result=new JSONObject(json);}catch(Exception ex){result=new JSONObject();result.put("error","Unexpected server response");}
                    result.put("_http",code);
                    response=result.toString();conn.disconnect();
                }catch(Exception e){
                    try{JSONObject result=new JSONObject();result.put("error","Connection failed: "+e.getMessage());result.put("_http",0);response=result.toString();}
                    catch(Exception ignored){response="{\"error\":\"Connection failed\",\"_http\":0}";}
                }
                final String callbackId=id;final String callbackResult=response;
                runOnUiThread(()->{
                    if(web!=null)web.evaluateJavascript("window.__pmviewDone("+JSONObject.quote(callbackId)+","+JSONObject.quote(callbackResult)+")",null);
                });
            });
        }
    }
    @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
    @Override protected void onDestroy(){network.shutdownNow();if(web!=null){web.removeJavascriptInterface("PMViewNative");web.destroy();web=null;}super.onDestroy();}
}
