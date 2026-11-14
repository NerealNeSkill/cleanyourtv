package com.gathin.tvhome;

import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/** Creates the session the system shows when the assistant button is pressed. */
public class AuraVoiceSessionService extends VoiceInteractionSessionService {
    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        return new AuraVoiceSession(this);
    }
}
