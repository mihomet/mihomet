package org.mihomet.app.ui.theme

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

enum class HapticType {
    CLICK,
    TICK,
    SUCCESS,
    HEAVY
}

object AppHaptics {
    fun perform(context: Context, type: HapticType = HapticType.CLICK, view: View? = null) {
        runCatching {
            if (view != null && view.isHapticFeedbackEnabled) {
                val constant = when (type) {
                    HapticType.CLICK -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
                    HapticType.TICK -> if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
                    HapticType.SUCCESS -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
                    HapticType.HEAVY -> HapticFeedbackConstants.LONG_PRESS
                }
                if (view.performHapticFeedback(constant)) return
            }

            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator?.hasVibrator() != true) return

            val effect = when (type) {
                HapticType.CLICK -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                HapticType.TICK -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                HapticType.SUCCESS -> if (Build.VERSION.SDK_INT >= 31) {
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                } else {
                    VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                HapticType.HEAVY -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            }

            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(
                    effect,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
        }
    }
}
