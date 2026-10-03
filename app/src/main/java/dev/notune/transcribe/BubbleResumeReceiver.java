package dev.notune.transcribe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** One-shot receiver: restarts dictation in the BubbleService after mic grant. */
public class BubbleResumeReceiver extends BroadcastReceiver {
    public static final String ACTION = "dev.notune.transcribe.BUBBLE_RESUME";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        Intent i = new Intent(context, BubbleService.class);
        i.setAction(ACTION);
        context.startService(i);
    }
}
