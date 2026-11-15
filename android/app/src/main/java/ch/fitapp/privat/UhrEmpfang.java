package ch.fitapp.privat;

import android.net.Uri;

import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

/** Nimmt fertige Trainings und Essen von der Uhr an, auch wenn die App gerade zu ist. */
public class UhrEmpfang extends WearableListenerService {

    @Override
    public void onDataChanged(DataEventBuffer events) {
        boolean neu = false;
        for (DataEvent ev : events) {
            if (ev.getType() != DataEvent.TYPE_CHANGED) {
                continue;
            }
            DataItem item = ev.getDataItem();
            Uri uri = item.getUri();
            String path = uri.getPath();
            if (path == null || !path.startsWith(UhrBruecke.VON_UHR)) {
                continue;
            }
            String json = DataMapItem.fromDataItem(item).getDataMap().getString("json");
            if (json != null && json.length() < 60000) {
                UhrBruecke.eingangAnhaengen(this, json);
                neu = true;
            }
            /* Angenommen: auf beiden Geräten löschen, damit nichts doppelt kommt */
            Wearable.getDataClient(this).deleteDataItems(uri);
        }
        if (neu) {
            MainActivity.uhrMelden();
        }
    }
}
