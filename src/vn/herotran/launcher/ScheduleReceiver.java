package vn.herotran.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/** Nhận báo thức hẹn giờ (AlarmManager) và chuyển cho RemoteService. */
public class ScheduleReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        RemoteService s = RemoteService.get();
        if (s != null) {
            s.requestFireSchedule();
            return;
        }
        try {
            Intent si = new Intent(context, RemoteService.class);
            si.setAction(RemoteService.ACTION_FIRE_SCHEDULE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(si);
            } else {
                context.startService(si);
            }
        } catch (Throwable t) {
            Log.w("ScheduleReceiver", "Không đánh thức được RemoteService", t);
        }
    }
}
