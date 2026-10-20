package br.dev.vitormelo.marquise;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** The installer's answer to an update (Updater.install): ask the person to confirm, or report what went wrong. */
public class UpdateReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
            // Android's dialog takes over from here and says nothing if it is dismissed (e.g. at "install unknown
            // apps"): the row goes back to "version X available" so OK can start again.
            Updater.progress = -1;
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            // Includes "cancelled" (STATUS_FAILURE_ABORTED): the row can be pressed again.
            Updater.failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
    }
}
