package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;

/** The assistant button was pressed: open AURA's search listening, show no window of its own. */
public class AuraVoiceSession extends VoiceInteractionSession {
    public AuraVoiceSession(Context context) {
        super(context);
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        Intent i = new Intent(getContext(), AuraSearchActivity.class).putExtra(AuraSearchActivity.EXTRA_VOICE, true);
        try {
            startAssistantActivity(i);
        } catch (RuntimeException e) {
            try { getContext().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (RuntimeException ignored) { }
        }
        hide();
    }
}
