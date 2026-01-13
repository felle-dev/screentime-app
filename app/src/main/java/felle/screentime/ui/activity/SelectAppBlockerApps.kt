package felle.screentime.ui.activity

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import felle.screentime.R
import felle.screentime.data.blockers.PackageWand
import felle.screentime.databinding.ActivitySelectAppsBinding
import felle.screentime.databinding.DialogAddKeywordBinding
import felle.screentime.databinding.DialogSetBlockedAppsTimerangeBinding
import felle.screentime.utils.SavedPreferencesLoader
import java.util.*
import kotlin.collections.HashMap
import kotlin.collections.HashSet

class SelectAppBlockerApps : AppCompatActivity() {

    private lateinit var binding: ActivitySelectAppsBinding
    private lateinit var selectedAppList: HashSet<String>
    private var appUsageConfigs: HashMap<String, AppUsageConfig> = HashMap()
    private var appItemList: MutableList<AppItem> = mutableListOf()

    private val colorPrimary by lazy { ContextCompat.getColor(this, R.color.md_theme_primary) }
    private val colorOnPrimary by lazy { ContextCompat.getColor(this, R.color.md_theme_onPrimary) }

    @SuppressLint("NotifyDataSetChanged")
    private var allAppsSelected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySelectAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if(!hasUsageStatsPermission(this)) requestUsageStatsPermission(this)
        // Unpack Intent
        val sp = SavedPreferencesLoader(this)
        appUsageConfigs = sp.loadBlockedApps()
        selectedAppList = appUsageConfigs.keys.toHashSet()

        binding.appList.layoutManager = LinearLayoutManager(this)

        binding.selectAll.visibility = View.GONE
        setupMagicWand()
        loadInstalledApps()
        setupSearch()
        setupConfirmButton()
    }

    private fun setupConfirmButton() {
        binding.confirmSelection.setOnClickListener {
            val gson = Gson()
            val resultIntent = intent.apply {
                putExtra("USAGE_CONFIGS", gson.toJson(appUsageConfigs))
            }

            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun setupMagicWand() {
        binding.selectAppsMagic.setOnClickListener {
            val popupMenu = PopupMenu(this, binding.selectAppsMagic)
            popupMenu.menuInflater.inflate(R.menu.app_wand, popupMenu.menu)
            popupMenu.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.select_social_media -> {
                        askForUsageTime("Social Media Apps") { config ->
                            config?.let { batchSelectApps(PackageWand.SOCIAL_MEDIA_APPS, it) }
                        }
                        true
                    }
                    R.id.select_productive_apps -> {
                        askForUsageTime("Productive Apps") { config ->
                            config?.let { batchSelectApps(PackageWand.PRODUCTIVE_APPS, it) }
                        }
                        true
                    }
                    R.id.add_a_custom_package -> {
                        makeAddCustomPackageDialog()
                        true
                    }
                    else -> false
                }
            }
            popupMenu.show()
        }
    }


    private fun updateSelectAllButton() {
        val adapter = binding.appList.adapter as? ApplicationAdapter
        allAppsSelected = adapter?.apps?.all { selectedAppList.contains(it.packageName) } == true
    }
    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOpsManager.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOpsManager.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun requestUsageStatsPermission(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }
    private fun askForUsageTime(
        title: String,
        existingConfig: AppUsageConfig? = null,
        onResult: (AppUsageConfig?) -> Unit
    ) {
        val dialogBinding = DialogSetBlockedAppsTimerangeBinding.inflate(layoutInflater)
        val draftConfig = existingConfig?.copy(dailyLimits = existingConfig.dailyLimits.clone()) ?: AppUsageConfig()

        if (draftConfig.isDailyUniform && draftConfig.uniformLimit > 0) {
            Arrays.fill(draftConfig.dailyLimits, draftConfig.uniformLimit)
        }

        var currentEditingDayIndex = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1 // Start on today
        if (currentEditingDayIndex < 0) currentEditingDayIndex = 0

        val dayButtons = mutableListOf<MaterialButton>()
        val dayLabels = listOf("S", "M", "T", "W", "T", "F", "S")

        dialogBinding.chipContainer.visibility = View.GONE // REMOVED THE PRESET CHIPS
        val scrollContainer = HorizontalScrollView(this)
        scrollContainer.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        scrollContainer.isHorizontalScrollBarEnabled = false

        val daysLayout = LinearLayout(this)
        daysLayout.orientation = LinearLayout.HORIZONTAL
        daysLayout.setPadding(16, 0, 16, 20)
        scrollContainer.addView(daysLayout)

        (dialogBinding.daySelectorContainer.parent as? ViewGroup)?.let { parent ->
            val index = parent.indexOfChild(dialogBinding.daySelectorContainer)
            parent.removeView(dialogBinding.daySelectorContainer)
            parent.addView(scrollContainer, index)
        }
        val daySelectorView = scrollContainer

        fun refreshDayButtons() {
            dayButtons.forEachIndexed { index, btn ->
                val isActive = (index == currentEditingDayIndex)
                val hasLimit = draftConfig.dailyLimits[index] > 0
                btn.setTextColor(colorPrimary)
                if (isActive) {
                    btn.setBackgroundColor(colorPrimary)
                    btn.strokeWidth = 0
                    btn.setTextColor(colorOnPrimary)
                    btn.elevation = 6f
                } else if (hasLimit) {
                    btn.setBackgroundColor(Color.TRANSPARENT)
                    btn.strokeColor = ColorStateList.valueOf(colorPrimary)
                    btn.strokeWidth = 3
                    btn.elevation = 0f
                } else {
                    btn.setBackgroundColor(Color.TRANSPARENT)
                    btn.strokeColor = ColorStateList.valueOf(Color.LTGRAY)
                    btn.strokeWidth = 2
                    btn.elevation = 0f
                }
            }
        }

        fun loadDayIntoInput(dayIndex: Int) {
            dialogBinding.hoursInput.tag = "programmatic" // Lock watcher
            dialogBinding.minutesInput.tag = "programmatic"

            if (draftConfig.isDailyUniform) {
                val t = draftConfig.uniformLimit
                dialogBinding.hoursInput.setText(if (t > 0) (t / 60).toString() else "")
                dialogBinding.minutesInput.setText(if (t > 0) (t % 60).toString() else "")
            } else {
                // Custom Mode: Load specific day
                val t = draftConfig.dailyLimits[dayIndex]
                dialogBinding.hoursInput.setText(if (t > 0) (t / 60).toString() else "")
                dialogBinding.minutesInput.setText(if (t > 0) (t % 60).toString() else "")
            }

            dialogBinding.hoursInput.tag = null // Unlock watcher
            dialogBinding.minutesInput.tag = null
        }

        fun saveInputToState() {
            val h = dialogBinding.hoursInput.text.toString().toLongOrNull() ?: 0L
            val m = dialogBinding.minutesInput.text.toString().toLongOrNull() ?: 0L
            val total = (h * 60) + m

            if (draftConfig.isDailyUniform) {
                draftConfig.uniformLimit = total
                Arrays.fill(draftConfig.dailyLimits, total) // Keep array in sync
            } else {
                draftConfig.dailyLimits[currentEditingDayIndex] = total
                refreshDayButtons() // Update outline if data changed
            }
        }

        dayLabels.forEachIndexed { index, label ->
            val btn = MaterialButton(this).apply {
                text = label
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                insetTop = 0; insetBottom = 0
                minWidth = 0; minimumWidth = 0
                cornerRadius = 100 // Circle/Pill shape

                // Fixed Size Buttons
                layoutParams = LinearLayout.LayoutParams(100, 100).apply { marginEnd = 12 }

                setOnClickListener {
                    saveInputToState()
                    currentEditingDayIndex = index
                    loadDayIntoInput(currentEditingDayIndex)
                    refreshDayButtons()
                }
            }
            daysLayout.addView(btn)
            dayButtons.add(btn)
        }

        val textWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (dialogBinding.hoursInput.tag == null) saveInputToState()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        dialogBinding.hoursInput.addTextChangedListener(textWatcher)
        dialogBinding.minutesInput.addTextChangedListener(textWatcher)

        dialogBinding.customDaysSwitch.setOnCheckedChangeListener { _, isChecked ->
            draftConfig.isDailyUniform = !isChecked // If checked, it's NOT uniform (it's custom)

            if (isChecked) {
                // -> Custom Mode
                daySelectorView.visibility = View.VISIBLE
                dialogBinding.inputLabel.text = "Set individual limits:"
                refreshDayButtons()
                loadDayIntoInput(currentEditingDayIndex)
            } else {
                // -> Uniform Mode
                daySelectorView.visibility = View.GONE
                dialogBinding.inputLabel.text = "Set daily limit:"

                // If switching to uniform, assume the limit of the currently selected day
                // or the max of existing days to be helpful
                val max = draftConfig.dailyLimits.maxOrNull() ?: 0L
                if (max > 0) draftConfig.uniformLimit = max
                loadDayIntoInput(0) // Index doesn't matter for uniform load
            }
        }

        // Init UI State
        dialogBinding.customDaysSwitch.isChecked = !draftConfig.isDailyUniform
        if (!draftConfig.isDailyUniform) {
            daySelectorView.visibility = View.VISIBLE
            refreshDayButtons()
        } else {
            daySelectorView.visibility = View.GONE
        }
        loadDayIntoInput(currentEditingDayIndex)

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .setPositiveButton("Close") { _, _ ->
                saveInputToState() // Final save to catch pending text
                onResult(draftConfig)
            }
            .show()
    }

    private fun loadInstalledApps() {
        if (intent.hasExtra("APP_LIST")) {
            intent.getStringArrayListExtra("APP_LIST")?.forEach { pName ->
                appItemList.add(getAppItem(pName))
            }
        } else {
            val launcherApps = getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
            val profiles = launcherApps.profiles
            val installedSet = mutableSetOf<String>()

            for (profile in profiles) {
                val apps = launcherApps.getActivityList(null, profile)
                apps.forEach {
                    if(it.applicationInfo.packageName != packageName) {
                        installedSet.add(it.applicationInfo.packageName)
                        val label = it.label.toString()
                        val pName = it.applicationInfo.packageName
                        val display = if (profile == Process.myUserHandle()) label else "$label (Work)"
                        appItemList.add(AppItem(pName, it.applicationInfo, display))
                    }
                }
            }
            // Add pre-selected apps if not found in launcher
            selectedAppList.forEach { pName ->
                if (!installedSet.contains(pName)) appItemList.add(getAppItem(pName))
            }
            appItemList.sortBy { it.displayName.lowercase() }
        }
        setupAdapter()
    }

    private fun getAppItem(packageName: String): AppItem {
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            AppItem(packageName, info, info.loadLabel(packageManager).toString())
        } catch (e: Exception) {
            AppItem(packageName)
        }
    }

    private fun setupAdapter() {
        val sorted = sortSelectedItemsToTop(appItemList)
        binding.appList.adapter = ApplicationAdapter(sorted, selectedAppList)
        updateSelectAllButton()
    }

    private fun setupSearch() {
        val adapter = binding.appList.adapter as? ApplicationAdapter ?: return
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                val query = newText?.trim() ?: ""
                val filtered = appItemList.filter { it.displayName.contains(query, ignoreCase = true) }
                adapter.updateData(filtered)
                return true
            }
        })
    }

    private fun batchSelectApps(packageList: Set<String>, config: AppUsageConfig) {
        appItemList.forEach { item ->
            if (packageList.contains(item.packageName)) {
                selectedAppList.add(item.packageName)
                appUsageConfigs[item.packageName] = config.copy(dailyLimits = config.dailyLimits.clone())
            }
        }
        val sorted = sortSelectedItemsToTop(appItemList)
        (binding.appList.adapter as ApplicationAdapter).updateData(sorted)
        updateSelectAllButton()
    }

    fun sortSelectedItemsToTop(list: List<AppItem>): List<AppItem> {
        return list.sortedWith(compareBy<AppItem> { !selectedAppList.contains(it.packageName) }
            .thenBy { it.displayName.lowercase() })
    }

    private fun makeAddCustomPackageDialog() {
        val dBind = DialogAddKeywordBinding.inflate(layoutInflater)
        dBind.wHint.hint = "com.example.app"

        MaterialAlertDialogBuilder(this)
            .setTitle("Add Package")
            .setView(dBind.root)
            .setPositiveButton("Add") { _, _ ->
                val pName = dBind.keywordInput.text.toString().trim()
                if (pName.isNotEmpty()) {
                    if (appItemList.none { it.packageName == pName }) {
                        appItemList.add(getAppItem(pName))
                        val adapter = binding.appList.adapter as ApplicationAdapter
                        adapter.updateData(sortSelectedItemsToTop(appItemList))

                        askForUsageTime(pName) { config ->
                            if(config != null) {
                                selectedAppList.add(pName)
                                appUsageConfigs[pName] = config
                                adapter.notifyDataSetChanged()
                            }
                        }
                    } else {
                        Toast.makeText(this, "Already exists", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class ApplicationAdapter(var apps: List<AppItem>, private val selectedSet: HashSet<String>)
        : RecyclerView.Adapter<ApplicationViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ApplicationViewHolder {
            return ApplicationViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.select_apps_item, parent, false))
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: ApplicationViewHolder, position: Int) {
            val item = apps[position]
            holder.appIcon.setImageDrawable(null)

            if (item.appInfo != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val icon = item.appInfo.loadIcon(packageManager)
                    withContext(Dispatchers.Main) {
                        if (holder.adapterPosition == position) holder.appIcon.setImageDrawable(icon)
                    }
                }
            } else {
                holder.appIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            val isSelected = selectedSet.contains(item.packageName)
            holder.checkbox.setOnCheckedChangeListener(null)
            holder.checkbox.isChecked = isSelected

            if (isSelected && appUsageConfigs.containsKey(item.packageName)) {
                val config = appUsageConfigs[item.packageName]!!
                holder.appName.setTextColor(colorPrimary)

                if (config.isDailyUniform) {
                    val h = config.uniformLimit / 60
                    val m = config.uniformLimit % 60
                    holder.appName.text = "${item.displayName} • ${if (h > 0) "${h}h ${m}m" else "${m}m"} / day"
                } else {
                    val totalMinutes = config.dailyLimits.sum()
                    holder.appName.text = "${item.displayName} • Custom Schedule"
                }
            } else {
                holder.appName.text = item.displayName
            }

            holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
                if (holder.itemView.isPressed || holder.checkbox.isPressed) {
                    if (isChecked) {
                        askForUsageTime(item.displayName, appUsageConfigs[item.packageName]) { config ->
                            if (config != null) {
                                selectedSet.add(item.packageName)
                                appUsageConfigs[item.packageName] = config
                                notifyItemChanged(position)
                                updateSelectAllButton()
                            } else {
                                holder.checkbox.isChecked = false
                            }
                        }
                    } else {
                        selectedSet.remove(item.packageName)
                        appUsageConfigs.remove(item.packageName)
                        notifyItemChanged(position)
                        updateSelectAllButton()
                    }
                }
            }
            holder.itemView.setOnClickListener { holder.checkbox.performClick() }
        }

        override fun getItemCount(): Int = apps.size
        fun updateData(newApps: List<AppItem>) { apps = newApps; notifyDataSetChanged() }
    }

    inner class ApplicationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val appIcon: ImageView = itemView.findViewById(R.id.app_icon)
        val appName: TextView = itemView.findViewById(R.id.app_name)
        val checkbox: CheckBox = itemView.findViewById(R.id.checkbox)
    }

    data class AppItem(
        val packageName: String,
        val appInfo: ApplicationInfo? = null,
        val displayName: String = packageName
    )
}

data class AppUsageConfig(
    var isDailyUniform: Boolean = true,
    var uniformLimit: Long = 0,
    val dailyLimits: LongArray = LongArray(7) { 0 } // 0=Sunday
)