package com.app.newspaperss.epubsink;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import java.io.InputStream;

/**
 * Stands in for the app an edition is shared to. It reads the stream and logs what it got, so a
 * device test can tell whether the grant reached another process -- which is the part no test on
 * the app's own side can show.
 */
public class SinkActivity extends Activity {
    public static final String TAG = "EpubSink";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Uri uri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) {
            Log.e(TAG, "RESULT no-stream");
            finish();
            return;
        }
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            byte[] head = new byte[2];
            int first = in.read(head);
            long bytes = Math.max(first, 0);
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) bytes += n;
            // An EPUB is a zip, so the first two bytes say whether a whole file arrived.
            boolean zip = first == 2 && head[0] == 'P' && head[1] == 'K';
            Log.i(TAG, "RESULT bytes=" + bytes + " zip=" + zip + " name=" + uri.getLastPathSegment());
        } catch (Exception e) {
            Log.e(TAG, "RESULT failed " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
        finish();
    }
}
