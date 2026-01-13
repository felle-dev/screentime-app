package felle.screentime.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.CountDownTimer
import android.util.Log
import androidx.core.app.NotificationCompat

class NotificationTimerManager(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "TimerNotificationChannel"
        private const val NOTIFICATION_ID = 1001
    }

    private var countDownTimer: CountDownTimer? = null
    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private var timerId = ""
    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Timer Notifications",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Timer progress notifications"
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun startTimer(
        totalMillis: Long,
        isCountdown: Boolean = true,
        onTickCallback: ((Long) -> Unit)? = null,
        onFinishCallback: (() -> Unit)? = null,
        timerIdU: String = "focusMode"
    ) {
        if(timerId != timerIdU) {
            countDownTimer?.cancel()

            countDownTimer = object : CountDownTimer(totalMillis, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    val displayMillis =
                        if (isCountdown) millisUntilFinished else totalMillis - millisUntilFinished
                    updateNotificationUI("Timer", displayMillis) // Use default title
                    onTickCallback?.invoke(displayMillis)
                }

                override fun onFinish() {
                    notificationManager.cancel(NOTIFICATION_ID)
                    onFinishCallback?.invoke()
                    timerId = timerIdU
                }
            }.start()
            timerId = timerIdU
        }
    }

    private fun updateNotificationUI(title: String, remainingMillis: Long) {
        val hours = remainingMillis / 3600000
        val minutes = (remainingMillis % 3600000) / 60000
        val seconds = (remainingMillis % 60000) / 1000
        val timeString = String.format("%02d:%02d:%02d", hours, minutes, seconds)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title) // Dynamic Title (App Name)
            .setContentText(timeString)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Replace with your app icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // Crucial: Prevents flickering/noise on updates
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun stopTimer() {
        countDownTimer?.cancel()
        notificationManager.cancel(NOTIFICATION_ID)
        timerId = ""
        Log.d("notifications","cancelling notifications")
    }
}