package app.lawnchair.allapps

import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.lawnchair.data.folder.FolderEntry
import app.lawnchair.data.folder.model.FolderViewModel
import app.lawnchair.launcher
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.util.categorizeAppsWithSystemAndGoogle
import app.lawnchair.util.observeOnce
import com.android.launcher3.InvariantDeviceProfile.OnIDPChangeListener
import com.android.launcher3.allapps.AllAppsStore
import com.android.launcher3.allapps.AlphabeticalAppsList
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem
import com.android.launcher3.allapps.PrivateProfileManager
import com.android.launcher3.allapps.WorkProfileManager
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.views.ActivityContext
import java.util.function.Predicate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

@Suppress("SYNTHETIC_PROPERTY_WITHOUT_JAVA_ORIGIN")
class LawnchairAlphabeticalAppsList<T>(
    private val context: T,
    private val appsStore: AllAppsStore<T>,
    workProfileManager: WorkProfileManager?,
    privateProfileManager: PrivateProfileManager?,
) : AlphabeticalAppsList<T>(context, appsStore, workProfileManager, privateProfileManager),
    OnIDPChangeListener,
    DefaultLifecycleObserver
    where T : Context, T : ActivityContext {

    private val prefs2 = PreferenceManager2.getInstance(context)
    private val prefs = PreferenceManager.getInstance(context)

    // Seeded from the cache so the first list build already excludes hidden apps and the
    // collector's initial emission (normally the same set) does not rebuild the lists again.
    private var hiddenApps: Set<String> = prefs2.hiddenApps.firstCached(prefs2)

    private val viewModel = FolderViewModel(
        (context as? ComponentActivity)?.application ?: context.launcher.application,
    )
    private val folderList = mutableListOf<FolderEntry>()
    private val filteredList = mutableListOf<AppInfo>()

    init {
        context.launcher.deviceProfile.inv.addOnChangeListener(this)
        (context as? LifecycleOwner)?.lifecycle?.addObserver(this)
        try {
            // The flow re-emits the same set on writes to any other preference; only a real
            // change may rebuild the lists.
            prefs2.hiddenApps.get()
                .filter { it != hiddenApps }
                .onEach {
                    hiddenApps = it
                    onAppsUpdated()
                }
                .launchIn(context.launcher.lifecycleScope)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to initialize hidden apps", t)
        }
        observeFolders()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        context.launcher.deviceProfile.inv.removeOnChangeListener(this)
    }

    private fun observeFolders() {
        viewModel.folders.observeOnce(context as LifecycleOwner) { folders ->
            if (folders != null) {
                folderList.clear()
                folderList.addAll(folders)
                updateAdapterItems()
            }
        }
    }

    override fun updateItemFilter(itemFilter: Predicate<ItemInfo>?) {
        mItemFilter = Predicate { info ->
            require(info is AppInfo) { "`info` must be an instance of `AppInfo`." }
            val componentKey = info.toComponentKey().toString()
            (itemFilter?.test(info) != false) && !hiddenApps.contains(componentKey)
        }
        onAppsUpdated()
    }

    override fun addAppsWithSections(appList: List<AppInfo?>?, startPosition: Int): Int {
        if (appList.isNullOrEmpty()) return startPosition
        val drawerListDefault = prefs.drawerList.get()
        filteredList.clear()
        var position = startPosition

        // Show app drawer folders only on main profile, to prevent state complexity
        if (isWorkOrPrivateSpace(appList)) return super.addAppsWithSections(appList, position)

        if (!drawerListDefault) {
            val validApps = appList.mapNotNull { it }
            val finalCategorizedApps = categorizeAppsWithSystemAndGoogle(validApps, context)

            finalCategorizedApps.forEach { (category, apps) ->
                if (apps.size == 1) {
                    mAdapterItems.add(AdapterItem.asApp(apps.first()))
                } else {
                    val folderInfo = FolderInfo().apply {
                        title = category
                        apps.forEach { add(it) }
                    }
                    mAdapterItems.add(AdapterItem.asFolder(folderInfo))
                }
                position++
            }
        } else {
            folderList.forEach { folderEntry ->
                val resolvedApps = folderEntry.itemComponentKeys.mapNotNull { keyString ->
                    val componentKey = ComponentKey.fromString(keyString) ?: return@mapNotNull null
                    appsStore.getApp(componentKey) as? AppInfo
                }

                if (resolvedApps.size > 1) {
                    val folderInfo = FolderInfo().apply {
                        id = folderEntry.id
                        title = folderEntry.title
                        resolvedApps.forEach { add(it) }
                    }
                    mAdapterItems.add(AdapterItem.asFolder(folderInfo))
                    position++

                    if (prefs.folderApps.get()) {
                        filteredList.addAll(resolvedApps)
                    }
                }
            }
            val remainingApps = appList.filterNot { app -> filteredList.contains(app) && prefs.folderApps.get() }
            position = super.addAppsWithSections(remainingApps, position)
        }

        return position
    }

    override fun onIdpChanged(modelPropertiesChanged: Boolean) {
        onAppsUpdated()
    }
}
