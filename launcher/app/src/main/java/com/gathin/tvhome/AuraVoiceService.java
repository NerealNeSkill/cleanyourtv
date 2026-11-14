package com.gathin.tvhome;

import android.service.voice.VoiceInteractionService;

/**
 * Lets AURA be picked as the TV's assistant (Settings, default apps), so the remote's microphone button opens AURA's
 * voice search instead of the Google Assistant. It does nothing by itself: no hotword, no recording; the work is
 * done by {@link AuraVoiceSession}, which only opens {@link AuraSearchActivity}.
 */
public class AuraVoiceService extends VoiceInteractionService {
}
