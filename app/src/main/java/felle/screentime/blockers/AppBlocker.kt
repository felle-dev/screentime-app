package felle.screentime.blockers

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import felle.screentime.ui.activity.AppUsageConfig
import felle.screentime.ui.activity.TimedActionActivity
import felle.screentime.utils.TimeTools
import felle.screentime.utils.UsageStatsHelper
import java.util.Calendar

class AppBlocker(private val context: Context) : BaseBlocker() {

    private val prefs: SharedPreferences = context.getSharedPreferences("app_blocker_prefs", Context.MODE_PRIVATE)

    // package-name -> end-time-in-real-time-millis
    private var cooldownAppsList: MutableMap<String, Long> = mutableMapOf()

    // package-name -> [(start-time, end-time), ...]
    private var cheatHours: MutableMap<String, List<Pair<Int, Int>>> = mutableMapOf()

    // package-name -> AppUsageConfig
    var blockedAppsList: HashMap<String, AppUsageConfig> = hashMapOf()


    private var usageStats = UsageStatsHelper(context)

    init {
        loadPersistedData()
    }

    /**
     * Load persisted data from SharedPreferences
     */
    private fun loadPersistedData() {
        // Load cooldowns
        val cooldownKeys = prefs.getStringSet("cooldown_keys", setOf()) ?: setOf()
        cooldownKeys.forEach { packageName ->
            val endTime = prefs.getLong("cooldown_$packageName", 0L)
            if (endTime > System.currentTimeMillis()) {
                cooldownAppsList[packageName] = endTime
            }
        }
    }

    /**
     * Persist cooldown data
     */
    private fun persistCooldownData() {
        val editor = prefs.edit()
        editor.putStringSet("cooldown_keys", cooldownAppsList.keys)
        cooldownAppsList.forEach { (packageName, endTime) ->
            editor.putLong("cooldown_$packageName", endTime)
        }
        editor.apply()
    }


    /**
     * Check if app needs to be blocked
     *
     * @param packageName
     * @return
     */
    fun doesAppNeedToBeBlocked(packageName: String): AppBlockerResult {

        if (cooldownAppsList.containsKey(packageName)) {
            // check if app has surpassed the cooldown period (using real time)
            if (cooldownAppsList[packageName]!! < System.currentTimeMillis()) {
                removeCooldownFrom(packageName)
            } else {
                // app is still under cooldown
                return AppBlockerResult(
                    isBlocked = false,
                    cooldownEndTime = cooldownAppsList[packageName]!!
                )
            }
        }

        // check if app is under cheat-hours
        val endCheatMillis = getEndTimeInMillis(packageName)
        if (endCheatMillis != null) {
            return AppBlockerResult(isBlocked = false, cheatHoursEndTime = endCheatMillis)
        }

        // Check if app is in blocked list and has exceeded usage limit
        if (blockedAppsList.contains(packageName)) {

            val config = blockedAppsList[packageName]!!
            val currentUsage = usageStats.getForegroundStatsByRelativeDay(0)
                .firstOrNull { it.packageName == packageName }?.totalTime ?: 0L
            val usageLimit = getUsageLimitForToday(config) * 60_000L

            val remainingUsage = ((usageLimit - currentUsage))
            if ( currentUsage >= usageLimit) {
                // App has exceeded its usage limit
                return AppBlockerResult(
                    isBlocked = true,
                    usageLimitReached = true,
                    remainingUsage = remainingUsage,
                    usageLimit = usageLimit
                )
            } else {
                // App is in blocked list but hasn't exceeded usage limit yet
                return AppBlockerResult(
                    isBlocked = false,
                    usageLimitReached = false,
                    remainingUsage = remainingUsage,
                    usageLimit = usageLimit
                )
            }
        }

        return AppBlockerResult(isBlocked = false)
    }

    /**
     * Get the usage limit for today based on config
     */
    private fun getUsageLimitForToday(config: AppUsageConfig): Long {
        return if (config.isDailyUniform) {
            config.uniformLimit
        } else {
            val calendar = Calendar.getInstance()
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1 // 0=Sunday
            config.dailyLimits[dayOfWeek]
        }
    }



    fun putCooldownTo(packageName: String, endTime: Long) {
        // Store as real time (System.currentTimeMillis() + duration)
        val realTimeEnd = System.currentTimeMillis() + endTime
        cooldownAppsList[packageName] = realTimeEnd
        persistCooldownData()
        Log.d("cooldownAppsList", cooldownAppsList.toString())
    }

    fun removeCooldownFrom(packageName: String) {
        cooldownAppsList.remove(packageName)
        val editor = prefs.edit()
        editor.remove("cooldown_$packageName")
        val cooldownKeys = prefs.getStringSet("cooldown_keys", mutableSetOf())?.toMutableSet()
        cooldownKeys?.remove(packageName)
        editor.putStringSet("cooldown_keys", cooldownKeys)
        editor.apply()
    }

    /**
     * Check if the package is currently under cheat hours.
     *
     * @param packageName The app package name.
     * @return Returns null if the app is not under cheat hours, or the timestamp (real time millis) when it ends.
     */
    private fun getEndTimeInMillis(packageName: String): Long? {
        if (cheatHours[packageName] == null) return null

        val currentTime = Calendar.getInstance()
        val currentHour = currentTime.get(Calendar.HOUR_OF_DAY)
        val currentMinute = currentTime.get(Calendar.MINUTE)

        val currentMinutes = TimeTools.convertToMinutesFromMidnight(currentHour, currentMinute)
        val realTimeNow = System.currentTimeMillis()

        cheatHours[packageName]?.forEach { (startMinutes, endMinutes) ->
            if ((startMinutes <= endMinutes && currentMinutes in startMinutes until endMinutes) ||
                (startMinutes > endMinutes && (currentMinutes >= startMinutes || currentMinutes < endMinutes))
            ) {
                var dayOffsetMinutes = 0

                // if cheat hours cross midnight and it is still the first day treat the end time as tomorrow
                if (startMinutes > endMinutes && currentMinutes > endMinutes) {
                    dayOffsetMinutes = 1440
                }

                // Convert endMinutes to real time millis
                val diffMinutes = endMinutes + dayOffsetMinutes - currentMinutes

                Log.d("AppBlocker", "$packageName cheat-hour ends after $diffMinutes minutes")
                val endTimeMillis = realTimeNow + (diffMinutes * 60 * 1000)

                return endTimeMillis
            }
        }
        return null
    }

    fun refreshCheatHoursData(cheatList: List<TimedActionActivity.AutoTimedActionItem>) {
        cheatHours.clear()
        cheatList.forEach { item ->
            val startTime = item.startTimeInMins
            val endTime = item.endTimeInMins
            val packageNames: ArrayList<String> = item.packages

            packageNames.forEach { packageName ->
                Log.d(
                    "AppBlocker",
                    "added cheat-hour data for $packageName : $startTime to $endTime"
                )

                if (cheatHours.containsKey(packageName)) {
                    val cheatHourTimeData: List<Pair<Int, Int>>? = cheatHours[packageName]
                    val cheatHourNewTimeData: MutableList<Pair<Int, Int>> =
                        cheatHourTimeData!!.toMutableList()

                    cheatHourNewTimeData.add(Pair(startTime, endTime))
                    cheatHours[packageName] = cheatHourNewTimeData
                } else {
                    cheatHours[packageName] = listOf(Pair(startTime, endTime))
                }
            }
        }
    }

    /**
     * App blocker check result
     *
     * @property isBlocked
     * @property cheatHoursEndTime specifies when cheat-hour ends (real time). returns -1 if not in cheat-hour
     * @property cooldownEndTime specifies when cooldown ends (real time). returns -1 if not in cooldown
     * @property usageLimitReached indicates if the app has reached its usage limit
     * @property remainingUsage remaining usage time in milliseconds
     * @property usageLimit usage limit in milliseconds
     */
    data class AppBlockerResult(
        val isBlocked: Boolean,
        val cheatHoursEndTime: Long = -1L,
        val cooldownEndTime: Long = -1L,
        val usageLimitReached: Boolean = false,
        val remainingUsage: Long = 0L,
        val usageLimit: Long = 0L
    )
}