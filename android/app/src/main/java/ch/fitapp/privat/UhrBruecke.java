package ch.fitapp.privat;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONArray;

/**
 * Verbindung zur Galaxy Watch über die Wearable Data Layer.
 * Handy zur Uhr: ein Paket unter /fitapp/paket. Uhr zum Handy: je ein Eintrag unter /fitapp/uhr/ID,
 * den UhrEmpfang in eine Warteschlange legt. Die Webseite holt die Warteschlange mit uhrEingang ab.
 */
final class UhrBruecke {

    static final String PAKET = "/fitapp/paket";
    static final String VON_UHR = "/fitapp/uhr/";
    private static final String KEY = "uhr_eingang";

    private UhrBruecke() {
    }

    static void paketSenden(Context c, String json) {
        if (json == null || json.length() > 90000) {
            return;
        }
        try {
            PutDataMapRequest r = PutDataMapRequest.create(PAKET);
            r.getDataMap().putString("json", json);
            PutDataRequest p = r.asPutDataRequest();
            p.setUrgent();
            Wearable.getDataClient(c.getApplicationContext()).putDataItem(p);
        } catch (Exception ignored) {
            /* Keine Uhr oder keine Google Play Dienste: dann eben nicht */
        }
    }

    static synchronized void eingangAnhaengen(Context c, String json) {
        SharedPreferences p = c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        JSONArray a;
        try {
            a = new JSONArray(p.getString(KEY, "[]"));
        } catch (Exception e) {
            a = new JSONArray();
        }
        if (a.length() < 200) {
            a.put(json);
        }
        p.edit().putString(KEY, a.toString()).apply();
    }

    static synchronized String eingangHolen(Context c) {
        SharedPreferences p = c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String s = p.getString(KEY, "[]");
        p.edit().remove(KEY).apply();
        return s;
    }
}
