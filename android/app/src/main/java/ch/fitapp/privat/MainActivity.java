package ch.fitapp.privat;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * FitApp: zeigt die App von GitHub Pages in einer WebView und ergänzt,
 * was eine Webseite nicht kann: Alarm bei gesperrtem Handy, Bildschirm an, Datei speichern.
 */
public class MainActivity extends Activity {

    static final String PREFS = "fitapp";
    static final String CHANNEL = "pausentimer_v1";
    private static final int REQ_FILE = 1;
    private static final int REQ_NOTIF = 2;
    private static final int REQ_CAM = 3;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest camRequest;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF0E0E0E);
        getWindow().setNavigationBarColor(0xFF0E0E0E);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0E0E0E);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.addJavascriptInterface(new Bridge(), "FitNative");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                Uri home = Uri.parse(getString(R.string.app_url));
                if (home.getHost() != null && home.getHost().equalsIgnoreCase(url.getHost())) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    view.loadDataWithBaseURL(null, offlineHtml(), "text/html", "utf-8", null);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Sicherung wählen"), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }

            /* Kein graues Play Symbol, bevor ein Video oder die Kamera läuft */
            @Override
            public Bitmap getDefaultVideoPoster() {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }

            /* Kamera für den Barcode Scanner: nur Video, nur für die eigene App Seite */
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean video = false;
                    for (String r : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                            video = true;
                        }
                    }
                    Uri home = Uri.parse(getString(R.string.app_url));
                    Uri von = request.getOrigin();
                    boolean eigene = von != null && home.getHost() != null && home.getHost().equalsIgnoreCase(von.getHost());
                    if (!video || !eigene) {
                        request.deny();
                        return;
                    }
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        if (camRequest != null) {
                            camRequest.deny();
                        }
                        camRequest = request;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
                    }
                });
            }
        });

        if (state != null) {
            web.restoreState(state);
        } else {
            web.loadUrl(getString(R.string.app_url));
        }

        createChannel(this);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == REQ_CAM && camRequest != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                camRequest.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            } else {
                camRequest.deny();
            }
            camRequest = null;
        }
    }

    private String offlineHtml() {
        String url = getString(R.string.app_url);
        return "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                + "<body style='background:#0E0E0E;color:#FAFAFA;font-family:sans-serif;padding:24px'>"
                + "<h2>Keine Verbindung</h2><p>Beim ersten Start braucht FitApp Internet. "
                + "Danach läuft sie auch offline.</p>"
                + "<p><a style='display:inline-block;padding:14px 20px;background:#FAFAFA;color:#0E0E0E;"
                + "border-radius:12px;text-decoration:none' href='" + url + "'>Nochmals versuchen</a></p></body></html>";
    }

    @Override
    protected void onStart() {
        super.onStart();
        cancelAlarm(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        scheduleAlarm(this);
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript(
                "(function(){var s=document.getElementById('sheet');"
                        + "if(s&&!s.hidden&&typeof closeSheet==='function'){closeSheet();return 'c';}return 'n';})()",
                value -> {
                    if (value == null || !value.contains("c")) {
                        moveTaskToBack(true);
                    }
                });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    // ------------------------------------------------------------------ Alarm

    static void createChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Pausentimer", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Meldet sich, wenn die Pause zwischen zwei Sätzen vorbei ist.");
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 400, 150, 400, 150, 400});
        ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
        nm.createNotificationChannel(ch);
    }

    static PendingIntent alarmIntent(Context c) {
        Intent i = new Intent(c, TimerReceiver.class);
        return PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void scheduleAlarm(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, MODE_PRIVATE);
        long endAt = p.getLong("endAt", 0);
        if (endAt <= System.currentTimeMillis() + 500) {
            return;
        }
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) {
            return;
        }
        PendingIntent pi = alarmIntent(c);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, pi);
        }
    }

    static void cancelAlarm(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) {
            am.cancel(alarmIntent(c));
        }
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.cancel(TimerReceiver.NOTIFICATION_ID);
        }
    }

    // ------------------------------------------------------------------ Brücke zur Webseite

    private class Bridge {

        @JavascriptInterface
        public boolean isNative() {
            return true;
        }

        /** endAt in Millisekunden seit 1970, 0 = kein Timer läuft. */
        @JavascriptInterface
        public void setTimer(double endAt, String label) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putLong("endAt", (long) endAt)
                    .putString("label", label == null ? "Pause" : label)
                    .apply();
        }

        /** Streak für das Widget. last und calc als Datum JJJJ-MM-TT, calc = Tag der Berechnung. */
        @JavascriptInterface
        public void setStreak(int streak, int freezes, int best, String last, String calc) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt("st_streak", streak)
                    .putInt("st_freezes", freezes)
                    .putInt("st_best", best)
                    .putString("st_last", last == null ? "" : last)
                    .putString("st_calc", calc == null ? "" : calc)
                    .apply();
            WidgetBau.updateAll(MainActivity.this);
        }

        /** Die letzten 7 Tage für das Wochen Widget, Format "JJJJ-MM-TT:o;JJJJ-MM-TT:f" (o geschafft, f Freeze, m verpasst). */
        @JavascriptInterface
        public void setStreakTage(String tage) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("st_tage", tage == null ? "" : tage).apply();
            WidgetBau.updateAll(MainActivity.this);
        }

        /** Trainingsplan pro Wochentag für das breite Widget, Format "1=Chest;2=Running" (0 Sonntag bis 6 Samstag). */
        @JavascriptInterface
        public void setPlanWoche(String plan) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("st_plan", plan == null ? "" : plan).apply();
            WidgetBau.updateAll(MainActivity.this);
        }

        @JavascriptInterface
        public void keepScreenOn(boolean on) {
            runOnUiThread(() -> {
                if (on) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            });
        }

        /** Speichert eine Sicherung im Ordner Downloads. Gibt den Ort zurück oder einen leeren Text. */
        @JavascriptInterface
        public String saveFile(String name, String content) {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) {
                        return "";
                    }
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os == null) {
                            return "";
                        }
                        os.write(bytes);
                    }
                    return "Downloads/" + name;
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) {
                        return "";
                    }
                    File f = new File(dir, name);
                    try (FileOutputStream os = new FileOutputStream(f)) {
                        os.write(bytes);
                    }
                    return f.getAbsolutePath();
                }
            } catch (Exception e) {
                return "";
            }
        }
    }
}
