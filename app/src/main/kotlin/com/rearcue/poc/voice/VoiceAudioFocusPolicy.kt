package com.rearcue.poc.voice

import android.media.AudioManager

internal fun interruptedAfterAudioFocusChange(change: Int, interrupted: Boolean): Boolean = when (change) {
    AudioManager.AUDIOFOCUS_LOSS,
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
    -> true
    AudioManager.AUDIOFOCUS_GAIN -> false
    else -> interrupted
}
