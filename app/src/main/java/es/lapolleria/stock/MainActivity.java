package es.lapolleria.stock;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int CREATE_BACKUP_REQUEST = 1002;
    private static final int OPEN_BACKUP_REQUEST = 1003;
    private static final int NOTIFICATION_REQUEST = 1004;
    private static final String CHANNEL_ID = "stock_bajo";

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingBackup;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        hideSystemUi();
        createNotificationChannel();

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setSupportZoom(false);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.addJavascriptInterface(new AndroidBridge(), "StockAndroid");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    return true;
                }
                return false;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                return true;
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Avisos de stock bajo", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Avisa cuando un producto llega al mínimo configurado");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void showNotification(String title, String text) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
            return;
        }
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification notification = new android.app.Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.app_icon)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify((int) System.currentTimeMillis(), notification);
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void shareWhatsApp(String phone, String message) {
            runOnUiThread(() -> {
                String digits = phone == null ? "" : phone.replaceAll("[^0-9]", "");
                Uri uri = digits.isEmpty()
                        ? Uri.parse("https://wa.me/?text=" + Uri.encode(message))
                        : Uri.parse("https://wa.me/" + digits + "?text=" + Uri.encode(message));
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            });
        }

        @JavascriptInterface
        public void searchImage(String query) {
            runOnUiThread(() -> {
                Uri uri = Uri.parse("https://www.google.com/search?tbm=isch&q=" + Uri.encode(query));
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            });
        }

        @JavascriptInterface
        public void exportBackup(String json) {
            pendingBackup = json;
            runOnUiThread(() -> {
                String date = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                intent.putExtra(Intent.EXTRA_TITLE, "copia-stock-polleria-" + date + ".json");
                startActivityForResult(intent, CREATE_BACKUP_REQUEST);
            });
        }

        @JavascriptInterface
        public void importBackup() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                startActivityForResult(intent, OPEN_BACKUP_REQUEST);
            });
        }

        @JavascriptInterface
        public void notifyLowStock(String title, String text) {
            runOnUiThread(() -> showNotification(title, text));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        try {
            if (requestCode == FILE_CHOOSER_REQUEST) {
                if (fileCallback == null) return;
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getData() != null) result = new Uri[]{data.getData()};
                    else if (data.getClipData() != null) {
                        ClipData clip = data.getClipData();
                        result = new Uri[clip.getItemCount()];
                        for (int i = 0; i < clip.getItemCount(); i++) result[i] = clip.getItemAt(i).getUri();
                    }
                }
                fileCallback.onReceiveValue(result);
                fileCallback = null;
            } else if (requestCode == CREATE_BACKUP_REQUEST && resultCode == RESULT_OK && data != null && pendingBackup != null) {
                try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
                    if (output != null) output.write(pendingBackup.getBytes(StandardCharsets.UTF_8));
                }
                pendingBackup = null;
                webView.evaluateJavascript("window.backupSaved && window.backupSaved()", null);
            } else if (requestCode == OPEN_BACKUP_REQUEST && resultCode == RESULT_OK && data != null) {
                String json;
                try (InputStream input = getContentResolver().openInputStream(data.getData());
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while (input != null && (read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                    json = output.toString(StandardCharsets.UTF_8.name());
                }
                String quoted = org.json.JSONObject.quote(json);
                webView.evaluateJavascript("window.restoreStockBackup(" + quoted + ")", null);
            }
        } catch (Exception exception) {
            webView.evaluateJavascript("window.nativeError && window.nativeError(" +
                    org.json.JSONObject.quote(exception.getMessage()) + ")", null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
