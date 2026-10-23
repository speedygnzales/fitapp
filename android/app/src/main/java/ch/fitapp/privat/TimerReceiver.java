package ch.fitapp.privat;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Meldet das Ende der Pause, wenn die App im Hintergrund oder das Handy gesperrt ist. */
public class TimerReceiver extends BroadcastReceiver {

    static final int NOTIFICATION_ID = 1;

    @Override
    public void onReceive(Context c, Intent intent) {
        SharedPreferences p = c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String label = p.getString("label", "Pause");
        /* Die App schickt "Titel\nText", zum Beispiel "Pause vorbei\nJetzt: Liegestütze, Satz 2" */
        String titel = "Pause vorbei";
        String text = label + ". Nächster Satz.";
        int nl = label.indexOf('\n');
        if (nl >= 0) {
            titel = label.substring(0, nl);
            text = label.substring(nl + 1);
        }
        MainActivity.createChannel(c);

        Intent open = new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(c, MainActivity.CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(titel)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();

        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) {
            try {
                nm.notify(NOTIFICATION_ID, n);
            } catch (SecurityException ignored) {
                // Benachrichtigungen nicht erlaubt
            }
        }
    }
}
