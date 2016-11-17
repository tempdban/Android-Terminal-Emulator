package jackpal.androidterm;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.DocumentsContract;

/**
 * Receives MEDIA_MOUNTED and MEDIA_REMOVED actions in response to external storage devices changing state
 */
public class MediaAvailabilityBroadcastReceiver extends BroadcastReceiver {
    @TargetApi(Build.VERSION_CODES.KITKAT)
    @Override
    public void onReceive(final Context context, final Intent intent) {
        // Update the root URI to ensure that only available storage directories appear
        String documents_authority = jackpal.androidterm.BuildConfig.APPLICATION_ID.concat(".localstorage.documents");
        context.getContentResolver().notifyChange(
                DocumentsContract.buildRootsUri(documents_authority), null);
    }
}
