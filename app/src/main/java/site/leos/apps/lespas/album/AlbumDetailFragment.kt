/*
 *   Copyright 2019 Jeffrey Liu (scubajeffrey@criptext.com)
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package site.leos.apps.lespas.album

import android.content.*
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.*
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.TypedValue
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.SearchView
import androidx.core.app.SharedElementCallback
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.asLiveData
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.ItemKeyProvider
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.SelectionTracker.Builder
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.work.*
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialContainerTransform
import com.google.android.material.transition.MaterialElevationScale
import com.google.android.material.transition.MaterialSharedAxis
import kotlinx.coroutines.*
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.*
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoSlideFragment
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.search.PhotosInMapFragment
import site.leos.apps.lespas.settings.SettingsFragment
import site.leos.apps.lespas.sync.*
import java.io.File
import java.lang.Runnable
import java.text.Collator
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.*
import kotlin.math.abs

class AlbumDetailFragment : Fragment(), ActionMode.Callback {
    private lateinit var album: Album
    private var scrollTo = ""

    private var actionMode: ActionMode? = null

    private lateinit var dateIndicator: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var mAdapter: PhotoGridAdapter

    private lateinit var selectionTracker: SelectionTracker<String>
    private var sharedSelection = mutableSetOf<String>()
    private var lastSelection = mutableSetOf<String>()

    private val albumModel: AlbumViewModel by activityViewModels()
    private val actionModel: ActionViewModel by activityViewModels()
    private val imageLoaderModel: NCShareViewModel by activityViewModels()
    private val currentPhotoModel: PhotoSlideFragment.CurrentPhotoViewModel by activityViewModels()
    private val destinationViewModel: DestinationDialogFragment.DestinationViewModel by activityViewModels()

    private lateinit var sharedPhoto: Photo
    private lateinit var snapseedCatcher: BroadcastReceiver
    private lateinit var snapseedOutputObserver: ContentObserver
    private lateinit var removeOriginalBroadcastReceiver: RemoveOriginalBroadcastReceiver

    private val publishModel: NCShareViewModel by activityViewModels()
    private lateinit var sharedByMe: NCShareViewModel.ShareByMe

    // Update album meta only when fragment destroy
    private var saveSortOrderChanged = false

    private lateinit var addFileLauncher: ActivityResultLauncher<String>

    private var stripExif = "2"

    private var isSnapseedEnabled = false
    private var snapseedEditAction: MenuItem? = null
    private var mediaRenameAction: MenuItem? = null

    private var reuseUris = arrayListOf<Uri>()

    private var mapOptionMenu: MenuItem? = null

    private lateinit var lespasPath: String

    private lateinit var sp: SharedPreferences

    private var searchOptionMenu: MenuItem? = null
    private var currentQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setHasOptionsMenu(true)

        sp = PreferenceManager.getDefaultSharedPreferences(requireContext())
        album = requireArguments().getParcelable(KEY_ALBUM)!!
        sharedByMe = NCShareViewModel.ShareByMe(album.id, album.name, arrayListOf())
        lespasPath = getString(R.string.lespas_base_folder_name)

        // Must be restore here
        savedInstanceState?.let {
            lastSelection = it.getStringArray(KEY_SELECTION)?.toMutableSet() ?: mutableSetOf()
            sharedSelection = it.getStringArray(KEY_SHARED_SELECTION)?.toMutableSet() ?: mutableSetOf()
        } ?: run { requireArguments().getString(KEY_SCROLL_TO)?.apply { scrollTo = this }}

        mAdapter = PhotoGridAdapter(
            album.id,
            { view, position ->
                currentPhotoModel.run {
                    (if (currentQuery.isEmpty()) position else position + 1).let { pos ->
                        setCurrentPosition(pos)
                        setLastPosition(pos)
                    }
                }

                ViewCompat.setTransitionName(recyclerView, null)
                reenterTransition = MaterialElevationScale(true).apply { duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong() }
                exitTransition = MaterialElevationScale(false).apply {
                    duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
                    excludeTarget(view, true)
                    excludeTarget(android.R.id.statusBarBackground, true)
                    excludeTarget(android.R.id.navigationBarBackground, true)
                }

                parentFragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .addSharedElement(view, view.transitionName)
                    .replace(R.id.container_root, PhotoSlideFragment.newInstance(album), PhotoSlideFragment::class.java.canonicalName)
                    .addToBackStack(null)
                    .commit()
            },
            { photo, view, type -> imageLoaderModel.setImagePhoto(NCShareViewModel.RemotePhoto(photo, if (Tools.isRemoteAlbum(album) && photo.eTag != Album.ETAG_NOT_YET_UPLOADED) "${lespasPath}/${album.name}" else "", album.coverBaseline), view, type) { startPostponedEnterTransition() }},
            { view -> imageLoaderModel.cancelSetImagePhoto(view) }
        ).apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }

        sharedElementEnterTransition = MaterialContainerTransform().apply {
            duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
            scrimColor = Color.TRANSPARENT
        }

        // Adjusting the shared element mapping
        setExitSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(names: MutableList<String>?, sharedElements: MutableMap<String, View>?) {
                if (names?.isNotEmpty() == true) recyclerView.findViewHolderForAdapterPosition(currentPhotoModel.getCurrentPosition())?.let {
                   sharedElements?.put(names[0], it.itemView.findViewById(R.id.photo))
                }
            }
        })

        // Broadcast receiver listening on share destination
        snapseedCatcher = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent!!.getParcelableExtra<ComponentName>(Intent.EXTRA_CHOSEN_COMPONENT)?.packageName!!.substringAfterLast('.') == "snapseed") {
                    // Register content observer if integration with snapseed setting is on
                    if (sp.getBoolean(getString(R.string.snapseed_pref_key), false)) {
                        context!!.contentResolver.apply {
                            unregisterContentObserver(snapseedOutputObserver)
                            registerContentObserver(
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                true,
                                snapseedOutputObserver
                            )
                        }
                    }
                }
            }
        }
        requireContext().registerReceiver(snapseedCatcher, IntentFilter(CHOOSER_SPY_ACTION))

        // Content observer looking for Snapseed output
        snapseedOutputObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            private val workerName = "${AlbumDetailFragment::class.java.canonicalName}.SNAPSEED_WORKER"
            private var lastId = ""
            private lateinit var snapseedWork: OneTimeWorkRequest

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)

                // ContentObserver got called twice, once for itself, once for it's descendant, all with same last path segment
                if (uri?.lastPathSegment!! != lastId) {
                    lastId = uri.lastPathSegment!!

                    snapseedWork = OneTimeWorkRequestBuilder<SnapseedResultWorker>().setInputData(
                        workDataOf(SnapseedResultWorker.KEY_IMAGE_URI to uri.toString(), SnapseedResultWorker.KEY_SHARED_PHOTO to sharedPhoto.id, SnapseedResultWorker.KEY_ALBUM to album.id)).build()
                    with(WorkManager.getInstance(requireContext())) {
                        enqueueUniqueWork(workerName, ExistingWorkPolicy.KEEP, snapseedWork)

                        getWorkInfosForUniqueWorkLiveData(workerName).observe(parentFragmentManager.findFragmentById(R.id.container_root)!!) { workInfo ->
                            if (workInfo != null) {
                                // If replace original is on, remove old bitmaps from cache and take care of cover too
                                if (sp.getBoolean(requireContext().getString(R.string.snapseed_replace_pref_key), false)) {
                                    imageLoaderModel.invalidPhoto(sharedPhoto.id)
                                    // Update cover if needed, cover id can be found only in adapter
                                    mAdapter.updateCover(sharedPhoto)
                                }
                            }
                        }
                    }

                    requireContext().contentResolver.unregisterContentObserver(this)
                }
            }
        }

        removeOriginalBroadcastReceiver = RemoveOriginalBroadcastReceiver {
            if (it) {
                val photos = mutableListOf<Photo>()
                for (photoId in sharedSelection) mAdapter.getPhotoBy(photoId).run { if (id != album.cover) photos.add(this) }
                if (photos.isNotEmpty()) actionModel.deletePhotos(photos, album)
            }
            sharedSelection.clear()
        }

        addFileLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) {
                parentFragmentManager.findFragmentByTag(TAG_ACQUIRING_DIALOG) ?: run {
                    AcquiringDialogFragment.newInstance(uris as ArrayList<Uri>, album,false).show(parentFragmentManager, TAG_ACQUIRING_DIALOG)
                }
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Cancel EXIF stripping job if it's running
                shareOutJob?.let {
                    if (it.isActive) {
                        it.cancel(cause = null)
                        return
                    }
                }

                if (parentFragmentManager.backStackEntryCount == 0) requireActivity().finish()
                else parentFragmentManager.popBackStack()
            }
        })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_albumdetail, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dateIndicator = view.findViewById<TextView>(R.id.date_indicator).apply {
            doOnLayout {
                background = MaterialShapeDrawable().apply {
                    fillColor = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.color_error))
                    shapeAppearanceModel = ShapeAppearanceModel.builder().setTopLeftCorner(CornerFamily.CUT, dateIndicator.height.toFloat()).build()
                }
            }
        }
        recyclerView = view.findViewById(R.id.photo_grid)

        postponeEnterTransition()
        ViewCompat.setTransitionName(recyclerView, album.id)
        if (scrollTo.isEmpty()) recyclerView.doOnLayout { startPostponedEnterTransition() }

        with(recyclerView) {
            // Special span size to show cover at the top of the grid
            layoutManager = newLayoutManger()
            adapter = mAdapter

            selectionTracker = Builder(
                "photoSelection",
                this,
                PhotoGridAdapter.PhotoKeyProvider(mAdapter),
                PhotoGridAdapter.PhotoDetailsLookup(this),
                StorageStrategy.createStringStorage()
            ).withSelectionPredicate(object : SelectionTracker.SelectionPredicate<String>() {
                override fun canSetStateForKey(key: String, nextState: Boolean): Boolean = key != album.id
                override fun canSetStateAtPosition(position: Int, nextState: Boolean): Boolean = position != 0 || mAdapter.getPhotoAt(0).id != album.id
                override fun canSelectMultiple(): Boolean = true
            }).build().apply {
                addObserver(object : SelectionTracker.SelectionObserver<String>() {
                    override fun onSelectionChanged() {
                        super.onSelectionChanged()

                        val selectionSize = selectionTracker.selection.size()

                        snapseedEditAction?.isVisible = selectionSize == 1 && isSnapseedEnabled && !Tools.isMediaPlayable(mAdapter.getPhotoBy(selectionTracker.selection.first()).mimeType)
                        // Not allow to change name for not yet uploaded photo TODO make it possible
                        mediaRenameAction?.isVisible = selectionSize == 1 && mAdapter.getPhotoBy(selectionTracker.selection.first()).eTag != Photo.ETAG_NOT_YET_UPLOADED

                        if (selectionTracker.hasSelection() && actionMode == null) {
                            actionMode = (activity as? AppCompatActivity)?.startSupportActionMode(this@AlbumDetailFragment)
                            actionMode?.let { it.title = getString(R.string.selected_count, selectionSize) }
                        } else if (!(selectionTracker.hasSelection()) && actionMode != null) {
                            actionMode?.finish()
                            actionMode = null
                        } else actionMode?.title = getString(R.string.selected_count, selectionSize)
                    }

                    override fun onItemStateChanged(key: String, selected: Boolean) {
                        super.onItemStateChanged(key, selected)
                        if (selected) lastSelection.add(key)
                        else lastSelection.remove(key)
                    }
                })
            }
            mAdapter.setSelectionTracker(selectionTracker)

            // Get scroll position after scroll idle
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                private val hideHandler = Handler(Looper.getMainLooper())
                private val hideDateIndicator = Runnable {
                    TransitionManager.beginDelayedTransition(recyclerView.parent as ViewGroup, Fade().apply { duration = 800 })
                    dateIndicator.visibility = View.GONE
                }
                private val titleBar = (activity as? AppCompatActivity)?.supportActionBar
                // Title text use TextAppearance.MaterialComponents.Headline5 style, which has textSize of 24sp
                private val titleTextSizeInPixel = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f, requireContext().resources.displayMetrics).toInt()

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)

                    when(newState) {
                        RecyclerView.SCROLL_STATE_IDLE-> {
                            if ((recyclerView.layoutManager as GridLayoutManager).findFirstVisibleItemPosition() > 0) titleBar?.setDisplayShowTitleEnabled(true)
                        }
                    }
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)

                    if (dx == 0 && dy == 0) {
                        // First entry or fragment resume false call, by layout re calculation, hide dataIndicator and title
                        dateIndicator.isVisible = false
                        showTitleText()
                    } else {
                        (recyclerView.layoutManager as GridLayoutManager).run {
                            // Hints the date (or 1st character of the name if sorting order is by name) of last photo shown in the list
                            if ((findLastCompletelyVisibleItemPosition() < mAdapter.itemCount - 1) || (findFirstCompletelyVisibleItemPosition() > 0)) {
                                hideHandler.removeCallbacksAndMessages(null)
                                dateIndicator.let {
                                    it.text = if (album.sortOrder % 100 == Album.BY_NAME_ASC || album.sortOrder % 100 == Album.BY_NAME_DESC) mAdapter.getPhotoAt(findLastVisibleItemPosition()).name.take(1)
                                    else mAdapter.getPhotoAt(findLastVisibleItemPosition()).dateTaken.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

                                    it.isVisible = true
                                }
                                hideHandler.postDelayed(hideDateIndicator, 1500)
                            }

                            showTitleText()
                        }
                    }
                }

                private fun showTitleText() {
                    // Show/hide title text in titleBar base on visibility of cover view's title
                    try {
                        val rect = Rect()
                        (recyclerView.findViewHolderForAdapterPosition(0) as PhotoGridAdapter.CoverViewHolder).itemView.findViewById<TextView>(R.id.title).getGlobalVisibleRect(rect)

                        if (rect.bottom <= 0) titleBar?.setDisplayShowTitleEnabled(true)
                        else if (rect.bottom - rect.top > titleTextSizeInPixel) titleBar?.setDisplayShowTitleEnabled(false)
                    } catch (e: Exception) {}
                }
            })
        }

        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(removeOriginalBroadcastReceiver, IntentFilter(AcquiringDialogFragment.BROADCAST_REMOVE_ORIGINAL))

        currentQuery = currentPhotoModel.getCurrentQuery()
        albumModel.getAlbumDetail(album.id).observe(viewLifecycleOwner) {
            // Cover might changed, photo might be deleted, so get updates from latest here
            val oldListType = this.album.sortOrder
            this.album = it.album

            // If 'Show title' option toggled, must change Recyclerview layout and re-create adapter, and update search menu item visibility
            if (abs(oldListType - this.album.sortOrder) == 100) {
                recyclerView.adapter = null
                recyclerView.layoutManager = newLayoutManger()
                recyclerView.adapter = mAdapter

                updateSearchMenu()
            }

            mAdapter.setAlbum(it, currentQuery)
            (activity as? AppCompatActivity)?.supportActionBar?.title = it.album.name

            // Scroll to reveal the new position, e.g. the position where PhotoSliderFragment left
            if (currentPhotoModel.getCurrentPosition() != currentPhotoModel.getLastPosition()) {
                (recyclerView.layoutManager as GridLayoutManager).scrollToPosition(currentPhotoModel.getCurrentPosition())
                currentPhotoModel.setLastPosition(currentPhotoModel.getCurrentPosition())
            }

            // Scroll to designated photo at first run
            if (scrollTo.isNotEmpty()) {
                (recyclerView.layoutManager as GridLayoutManager).scrollToPosition(with(mAdapter.getPhotoPosition(scrollTo)) { if (this >= 0) this else 0 })
                scrollTo = ""
            }

            // Restore selection state
            if (lastSelection.isNotEmpty()) lastSelection.forEach { selected -> selectionTracker.select(selected) }

            lifecycleScope.launch {
                it.photos.forEach { photo ->
                    if (photo.mimeType.startsWith("image/") && photo.latitude != Photo.NO_GPS_DATA) {
                        mapOptionMenu?.isVisible = true
                        return@launch
                    }
                }
            }
        }

        publishModel.shareByMe.asLiveData().observe(viewLifecycleOwner) { shares ->
            sharedByMe = shares.find { it.fileId == album.id } ?: NCShareViewModel.ShareByMe(album.id, album.name, arrayListOf())
            mAdapter.setRecipient(sharedByMe)
        }

        destinationViewModel.getDestination().observe(viewLifecycleOwner) {
            // Acquire files
            it?.let { targetAlbum ->
                if (destinationViewModel.doOnServer()) {
                    val actionId = if (destinationViewModel.shouldRemoveOriginal()) Action.ACTION_MOVE_ON_SERVER else Action.ACTION_COPY_ON_SERVER
                    val targetFolder = if (targetAlbum.id != Album.JOINT_ALBUM_ID) "${lespasPath}/${targetAlbum.name}" else targetAlbum.coverFileName.substringBeforeLast('/')
                    val photoList = mutableListOf<Photo>()

                    var metaString: String
                    val actions = mutableListOf<Action>()
                    destinationViewModel.getRemotePhotos().forEach { remotePhoto ->
                        // No matter the photo is uploaded or not, add action to move or copy on server. If it's not yet uploaded, another Action.ACTION_ADD_FILES_ON_SERVER should be in the pending list by now
                        remotePhoto.photo.let { photo ->
                            metaString = "${targetAlbum.eTag}|${photo.dateTaken.toInstant(OffsetDateTime.now().offset).toEpochMilli()}|${photo.mimeType}|${photo.width}|${photo.height}|${photo.orientation}|${photo.caption}|${photo.latitude}|${photo.longitude}|${photo.altitude}|${photo.bearing}"
                            if (photo.id == album.cover) {
                                // Can't move cover photo
                                actions.add(Action(null, Action.ACTION_COPY_ON_SERVER, remotePhoto.remotePath, targetFolder, metaString, "${photo.name}|${targetAlbum.id == Album.JOINT_ALBUM_ID}", System.currentTimeMillis(), 1))
                            } else {
                                actions.add(Action(null, actionId, remotePhoto.remotePath, targetFolder, metaString, "${photo.name}|${targetAlbum.id == Album.JOINT_ALBUM_ID}", System.currentTimeMillis(), 1))
                                photoList.add(photo)
                            }
                        }
                    }

                    when(targetAlbum.id) {
                        // Create new album first, since this whole operations will be carried out on server, we don't have to worry about cover here, SyncAdapter will handle all the rest during next sync
                        "" -> actions.add(0, Action(null, Action.ACTION_ADD_DIRECTORY_ON_SERVER, "", targetAlbum.name, "", "", System.currentTimeMillis(), 1))
                        // Update Joint Album's content metadata
                        Album.JOINT_ALBUM_ID -> actions.add(Action(null, Action.ACTION_UPDATE_JOINT_ALBUM_PHOTO_META, targetAlbum.eTag, targetFolder, "", "", System.currentTimeMillis(), 1))
                    }

                    actionModel.addActions(actions)

                    // If this is a MOVE operation, show moving result in source album immediately, result in target album however can't be shown until the next sync finished
                    if (destinationViewModel.shouldRemoveOriginal()) actionModel.deletePhotosLocalRecord(photoList)

                    if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.msg_server_operation), null).show(parentFragmentManager, CONFIRM_DIALOG)
                }
                else if (parentFragmentManager.findFragmentByTag(TAG_ACQUIRING_DIALOG) == null) AcquiringDialogFragment.newInstance(reuseUris, targetAlbum, destinationViewModel.shouldRemoveOriginal()).show(parentFragmentManager, TAG_ACQUIRING_DIALOG)
            }
        }

        // Rename result handler
        parentFragmentManager.setFragmentResultListener(RenameDialogFragment.RESULT_KEY_NEW_NAME, viewLifecycleOwner) { _, bundle->
            bundle.getString(RenameDialogFragment.RESULT_KEY_NEW_NAME)?.let { newName->
                when(bundle.getInt(RenameDialogFragment.REQUEST_TYPE)) {
                    RenameDialogFragment.REQUEST_TYPE_ALBUM -> {
                        with(sharedByMe.with.isNotEmpty()) {
                            actionModel.renameAlbum(album.id, album.name, newName, this)

                            // Nextcloud server won't propagate folder name changes to shares for a reason, see https://github.com/nextcloud/server/issues/2063
                            // In our case, I think it's a better UX to do it because name is a key aspect of album, so...
                            // TODO What if sharedByMe is not available when working offline
                            if (this) publishModel.renameShare(sharedByMe, newName)
                        }

                        // Set title to new name
                        (activity as? AppCompatActivity)?.supportActionBar?.title = newName
                        album.name = newName
                    }
                    RenameDialogFragment.REQUEST_TYPE_PHOTO -> {
                        mAdapter.getPhotoBy(selectionTracker.selection.first()).let { photo ->
                            val newFileName = photo.name.substringAfterLast('.').let { ext ->
                                if (ext.isNotEmpty()) "${newName}.${ext}" else newName
                            }
                            actionModel.renamePhoto(photo, album, newFileName)

                            // Local database changes, parsing new name for updated date, etc., are handle in SyncAdapter after rename action successfully sync with server
                        }
                        selectionTracker.clearSelection()
                    }
                    else -> {}
                }
            } ?: run { selectionTracker.clearSelection() }
        }

        // Confirm dialog result handler
        parentFragmentManager.setFragmentResultListener(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            when(bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY)) {
                DELETE_REQUEST_KEY-> {
                    if (bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false)) {
                        val photos = mutableListOf<Photo>()
                        for (photoId in selectionTracker.selection) mAdapter.getPhotoBy(photoId).run { if (id != album.cover) photos.add(this) }
                        if (photos.isNotEmpty()) actionModel.deletePhotos(photos, album)
                    }
                    selectionTracker.clearSelection()
                }
                STRIP_REQUEST_KEY-> shareOut(bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, true))
            }
        }
    }

    override fun onResume() {
        super.onResume()

        (activity as? AppCompatActivity)?.supportActionBar?.setDisplayHomeAsUpEnabled(true)
        stripExif = sp.getString(getString(R.string.strip_exif_pref_key), getString(R.string.strip_ask_value))!!
        isSnapseedEnabled = sp.getBoolean(getString(R.string.snapseed_pref_key), false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArray(KEY_SELECTION, lastSelection.toTypedArray())
        outState.putStringArray(KEY_SHARED_SELECTION, sharedSelection.toTypedArray())
    }

    override fun onStop() {
        currentPhotoModel.setCurrentQuery(currentQuery)

        // Time to update album meta file if sort order changed in this session, if cover is not uploaded yet, meta will be maintained in SyncAdapter when cover fileId is available
        if (saveSortOrderChanged && !album.cover.contains('.')) actionModel.updateAlbumSortOrderInMeta(album)

        super.onStop()
    }

    override fun onDestroyView() {
        recyclerView.clearOnScrollListeners()
        recyclerView.adapter = null

        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(removeOriginalBroadcastReceiver)

        super.onDestroyView()
    }

    override fun onDestroy() {
        requireContext().apply {
            unregisterReceiver(snapseedCatcher)
            contentResolver.unregisterContentObserver(snapseedOutputObserver)
        }

        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.album_detail_menu, menu)
        mapOptionMenu = menu.findItem(R.id.option_menu_in_map)
        searchOptionMenu = menu.findItem(R.id.option_menu_search)

        run map@{
            mutableListOf<Photo>().apply { addAll(mAdapter.currentList) }.forEach {
                if (it.mimeType.startsWith("image/") && it.latitude != Photo.NO_GPS_DATA) {
                    mapOptionMenu?.isVisible = true

                    return@map
                }
            }
        }

        updateSearchMenu()
    }

    private fun updateSearchMenu() {
        searchOptionMenu?.let {
            if (Tools.isWideListAlbum(album.sortOrder)) {
                it.isVisible = true

                (it.actionView as SearchView).run {
                    if (currentQuery.isNotEmpty()) {
                        it.expandActionView()
                        setQuery(currentQuery, false)
                    }

                    queryHint = getString(R.string.option_menu_search)

                    setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                        override fun onQueryTextSubmit(query: String?): Boolean = false
                        override fun onQueryTextChange(newText: String?): Boolean {
                            (newText ?: "").let { query ->
                                mAdapter.filter(query)
                                currentQuery = query
                            }
                            return false
                        }
                    })
                }
            } else {
                it.isVisible = false
                it.collapseActionView()
                currentQuery = ""
            }
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.findItem(R.id.option_menu_sortbydateasc).isChecked = false
        menu.findItem(R.id.option_menu_sortbydatedesc).isChecked = false
        menu.findItem(R.id.option_menu_sortbynameasc).isChecked = false
        menu.findItem(R.id.option_menu_sortbynamedesc).isChecked = false
        when(album.sortOrder % 100) {
            Album.BY_DATE_TAKEN_ASC -> menu.findItem(R.id.option_menu_sortbydateasc).isChecked = true
            Album.BY_DATE_TAKEN_DESC -> menu.findItem(R.id.option_menu_sortbydatedesc).isChecked = true
            Album.BY_NAME_ASC -> menu.findItem(R.id.option_menu_sortbynameasc).isChecked = true
            Album.BY_NAME_DESC -> menu.findItem(R.id.option_menu_sortbynamedesc).isChecked = true
        }

        menu.findItem(R.id.option_menu_wide_list).isChecked = Tools.isWideListAlbum(album.sortOrder)

        // Disable publish function when this is a newly created album which does not exist on server yet
        if (album.eTag == Album.ETAG_NOT_YET_UPLOADED) menu.findItem(R.id.option_menu_publish).isEnabled = false

        super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when(item.itemId) {
            R.id.option_menu_add_photo-> {
                addFileLauncher.launch("*/*")
                true
            }
            R.id.option_menu_rename-> {
                lifecycleScope.launch(Dispatchers.IO) {
                    albumModel.getAllAlbumName().also {
                        val names = mutableListOf<String>()
                        // albumModel.getAllAlbumName return all album names including hidden ones, in case of name collision when user change name to an hidden one and later hide this album, existing
                        // name check should include hidden ones
                        it.forEach { name -> names.add(if (name.startsWith('.')) name.substring(1) else name) }
                        if (parentFragmentManager.findFragmentByTag(RENAME_DIALOG) == null) RenameDialogFragment.newInstance(album.name, names, RenameDialogFragment.REQUEST_TYPE_ALBUM).show(parentFragmentManager, RENAME_DIALOG)
                    }
                }
                true
            }
            R.id.option_menu_settings-> {
                exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
                reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
                parentFragmentManager.beginTransaction().replace(R.id.container_root, SettingsFragment()).addToBackStack(null).commit()
                true
            }
            R.id.option_menu_sortbydateasc-> {
                updateSortOrder(Album.BY_DATE_TAKEN_ASC)
                true
            }
            R.id.option_menu_sortbydatedesc-> {
                updateSortOrder(Album.BY_DATE_TAKEN_DESC)
                true
            }
            R.id.option_menu_sortbynameasc-> {
                updateSortOrder(Album.BY_NAME_ASC)
                true
            }
            R.id.option_menu_sortbynamedesc-> {
                updateSortOrder(Album.BY_NAME_DESC)
                true
            }
            R.id.option_menu_publish-> {
                // Get meaningful label for each recipient
                publishModel.sharees.value.let { sharees->
                    sharedByMe.with.forEach { recipient-> sharees.find { it.name == recipient.sharee.name && it.type == recipient.sharee.type}?.let { recipient.sharee.label = it.label }}
                }

                if (parentFragmentManager.findFragmentByTag(PUBLISH_DIALOG) == null) AlbumPublishDialogFragment.newInstance(sharedByMe).show(parentFragmentManager, PUBLISH_DIALOG)

                true
            }
            R.id.option_menu_in_map-> {
                reenterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false).apply { duration = resources.getInteger(android.R.integer.config_longAnimTime).toLong() }
                exitTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true).apply { duration = resources.getInteger(android.R.integer.config_longAnimTime).toLong() }
                ViewCompat.setTransitionName(recyclerView, null)
                parentFragmentManager.beginTransaction().replace(
                    R.id.container_root,
                    PhotosInMapFragment.newInstance(album, Tools.getPhotosWithCoordinate(mAdapter.currentList, sp.getBoolean(getString(R.string.nearby_convergence_pref_key), true), album.sortOrder)),
                    PhotosInMapFragment::class.java.canonicalName
                ).addToBackStack(null).commit()
                true
            }
            R.id.option_menu_bgm-> {
                if (parentFragmentManager.findFragmentByTag(BGM_DIALOG) == null) BGMDialogFragment.newInstance(album).show(parentFragmentManager, BGM_DIALOG)
                true
            }
            R.id.option_menu_wide_list-> {
                albumModel.setWideList(album.id, !Tools.isWideListAlbum(album.sortOrder))
                saveSortOrderChanged = true
                true
            }
            else-> false
        }
    }

    // On special Actions of this fragment
    override fun onCreateActionMode(mode: ActionMode?, menu: Menu): Boolean {
        mode?.menuInflater?.inflate(R.menu.album_detail_actions_mode, menu)

        snapseedEditAction = menu.findItem(R.id.snapseed_edit)
        mediaRenameAction = menu.findItem(R.id.rename_media)

        // Disable snapseed edit action menu if Snapseed is not installed, update snapseed action menu icon too
        isSnapseedEnabled = sp.getBoolean(getString(R.string.snapseed_pref_key), false)
        snapseedEditAction?.isVisible = isSnapseedEnabled

        if (isSnapseedEnabled) {
            if (sp.getBoolean(getString(R.string.snapseed_replace_pref_key), false)) {
                snapseedEditAction?.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_baseline_snapseed_24)
                snapseedEditAction?.title = getString(R.string.button_text_edit_in_snapseed_replace)
            } else {
                snapseedEditAction?.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_baseline_snapseed_add_24)
                snapseedEditAction?.title = getString(R.string.button_text_edit_in_snapseed_add)
            }
        }

        return true
    }

    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu): Boolean = false

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
        return when(item?.itemId) {
            R.id.remove -> {
                if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.confirm_delete), getString(R.string.yes_delete), true, DELETE_REQUEST_KEY).show(parentFragmentManager, CONFIRM_DIALOG)
                true
            }
            R.id.share -> {
                if (stripExif == getString(R.string.strip_ask_value)) {
                    if (hasExifInSelection()) {
                        if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) YesNoDialogFragment.newInstance(getString(R.string.strip_exif_msg, getString(R.string.strip_exif_title)), STRIP_REQUEST_KEY).show(parentFragmentManager, CONFIRM_DIALOG)
                    } else shareOut(false)
                }
                else shareOut(stripExif == getString(R.string.strip_on_value))

                true
            }
            R.id.select_all -> {
                for (i in 1 until mAdapter.itemCount) selectionTracker.select(mAdapter.getPhotoId(i))
                true
            }
            R.id.snapseed_edit-> {
                shareOut(false, SHARE_TO_SNAPSEED)
                true
            }
            R.id.lespas_reuse-> {
                if (Tools.isRemoteAlbum(album)) {
                    val rp = arrayListOf<NCShareViewModel.RemotePhoto>()
                    selectionTracker.selection.forEach {
                        mAdapter.getPhotoBy(it).let { photo ->
                            rp.add(NCShareViewModel.RemotePhoto(photo, "${lespasPath}/${album.name}", 0))
                        }
                    }
                    selectionTracker.clearSelection()

                    if (parentFragmentManager.findFragmentByTag(TAG_DESTINATION_DIALOG) == null) DestinationDialogFragment.newInstance(rp, album.id, true).show(parentFragmentManager, TAG_DESTINATION_DIALOG)
                } else shareOut(false, SHARE_TO_LESPAS)

                true
            }
            R.id.rename_media -> {
                mutableListOf<String>().let { names ->
                    mAdapter.currentList.let { photos -> for (i in 1 until photos.size) { names.add(photos[i].name.substringBeforeLast('.')) }}
                    if (parentFragmentManager.findFragmentByTag(RENAME_DIALOG) == null)
                        RenameDialogFragment.newInstance(mAdapter.getPhotoBy(selectionTracker.selection.first()).name.substringBeforeLast('.'), names, RenameDialogFragment.REQUEST_TYPE_PHOTO).show(parentFragmentManager, RENAME_DIALOG)
                }

                true
            }
            else -> false
        }
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        selectionTracker.clearSelection()
        actionMode = null
    }

    private fun updateSortOrder(newOrder: Int) {
        // Scroll to the top after sort, since cover photo has album's id as it's id, scroll to this id means scroll to the top
        scrollTo = album.id

        albumModel.setSortOrder(album.id, if (Tools.isWideListAlbum(album.sortOrder)) newOrder + 100 else newOrder)
        saveSortOrderChanged = true
    }

    private fun hasExifInSelection(): Boolean {
        for (photoId in selectionTracker.selection) {
            if (Tools.hasExif(mAdapter.getPhotoBy(photoId).mimeType)) return true
        }

        return false
    }

    private fun prepareShares(strip: Boolean, job: Job?): ArrayList<Uri> {
        val uris = arrayListOf<Uri>()
        var sourceFile: File
        var destFile: File
        val isRemote = Tools.isRemoteAlbum(album)
        val serverPath = "${getString(R.string.lespas_base_folder_name)}/${album.name}"

        sharedSelection.clear()
        for (photoId in selectionTracker.selection) sharedSelection.add(photoId)

        for (photoId in sharedSelection) {
            // Quit asap when job cancelled
            job?.let { if (it.isCancelled) return arrayListOf() }

            if (mAdapter.getPhotoBy(photoId).let { photo ->
                // Synced file is named after id, not yet synced file is named after file's name
                destFile = File(requireContext().cacheDir, if (strip) "${UUID.randomUUID()}.${photo.name.substringAfterLast('.')}" else photo.name)

                try {
                    if (isRemote && photo.eTag != Photo.ETAG_NOT_YET_UPLOADED) {
                        imageLoaderModel.downloadFile("${serverPath}/${photo.name}", destFile, strip && Tools.hasExif(photo.mimeType))
                    } else {
                        //sourceFile = File(Tools.getLocalRoot(requireContext()), if (eTag != Photo.ETAG_NOT_YET_UPLOADED) id else name)
                        sourceFile = File(Tools.getLocalRoot(requireContext()), photo.id)
                        // This TEMP_CACHE_FOLDER is created by MainActivity

                        // Copy the file from fileDir/id to cacheDir/name, strip EXIF base on setting
                        if (strip && Tools.hasExif(photo.mimeType)) BitmapFactory.decodeFile(sourceFile.canonicalPath)?.compress(Bitmap.CompressFormat.JPEG, 95, destFile.outputStream())
                        else sourceFile.copyTo(destFile, true, 4096)
                        true
                    }
                } catch (e: Exception) { false }
            }) uris.add(FileProvider.getUriForFile(requireContext(), getString(R.string.file_authority), destFile))
        }

        return uris
    }

    private var shareOutJob: Job? = null
    private fun shareOut(strip: Boolean, shareType: Int = GENERAL_SHARE) {
        val handler = Handler(Looper.getMainLooper())
        val waitingMsg = Tools.getPreparingSharesSnackBar(recyclerView, strip) { shareOutJob?.cancel(cause = null) }

        shareOutJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Temporarily prevent screen rotation
                requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED

                //sharedPhoto = mAdapter.getPhotoAt(selectionTracker.selection.first().toInt())
                sharedPhoto = mAdapter.getPhotoBy(selectionTracker.selection.first())

                // Show a SnackBar if it takes too long (more than 500ms) preparing shares
                withContext(Dispatchers.Main) {
                    handler.removeCallbacksAndMessages(null)
                    handler.postDelayed({ waitingMsg.show() }, 500)
                }

                val uris = prepareShares(strip, shareOutJob!!)

                withContext(Dispatchers.Main) {
                    if (uris.isNotEmpty()) {
                        when (shareType) {
                            GENERAL_SHARE -> {
                                // Call system share chooser
                                val cr = requireActivity().contentResolver
                                val clipData = ClipData.newUri(cr, "", uris[0])
                                for (i in 1 until uris.size) {
                                    if (isActive) {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) clipData.addItem(cr, ClipData.Item(uris[i]))
                                        else clipData.addItem(ClipData.Item(uris[i]))
                                    }
                                }

                                // Dismiss Snackbar before showing system share chooser, avoid unpleasant screen flicker
                                if (waitingMsg.isShownOrQueued) waitingMsg.dismiss()

                                if (isActive) startActivity(Intent.createChooser(Intent().apply {
                                    if (uris.size == 1) {
                                        // If sharing only one picture, use ACTION_SEND instead, so that other apps which won't accept ACTION_SEND_MULTIPLE will work
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_STREAM, uris[0])
                                    } else {
                                        action = Intent.ACTION_SEND_MULTIPLE
                                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                    }
                                    //type = sharedPhoto.mimeType
                                    type = if (sharedPhoto.mimeType.startsWith("image")) "image/*" else sharedPhoto.mimeType
                                    this.clipData = clipData
                                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    putExtra(ShareReceiverActivity.KEY_SHOW_REMOVE_OPTION, true)
                                }, null))
                            }
                            SHARE_TO_SNAPSEED -> {
                                startActivity(Intent().apply {
                                    action = Intent.ACTION_SEND
                                    data = uris[0]
                                    putExtra(Intent.EXTRA_STREAM, uris[0])
                                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    setClassName(SettingsFragment.SNAPSEED_PACKAGE_NAME, SettingsFragment.SNAPSEED_MAIN_ACTIVITY_CLASS_NAME)
                                })

                                // Send broadcast just like system share does when user chooses Snapseed, so that we can catch editing result
                                requireContext().sendBroadcast(Intent().apply {
                                    action = CHOOSER_SPY_ACTION
                                    putExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName(SettingsFragment.SNAPSEED_PACKAGE_NAME, SettingsFragment.SNAPSEED_MAIN_ACTIVITY_CLASS_NAME))
                                })
                            }
                            SHARE_TO_LESPAS -> {
                                reuseUris = uris
                                if (parentFragmentManager.findFragmentByTag(TAG_DESTINATION_DIALOG) == null) DestinationDialogFragment.newInstance(reuseUris, true, album.id).show(parentFragmentManager, TAG_DESTINATION_DIALOG)
                            }
                        }
                    } else {
                        var msg = getString(R.string.msg_error_preparing_share_out_files)
                        if (Tools.isRemoteAlbum(album)) msg += " ${getString(R.string.msg_check_network)}"
                        Snackbar.make(recyclerView, msg, Snackbar.LENGTH_LONG).show()
                    }
                }
            } catch (e: CancellationException) {
                e.printStackTrace()
            } finally {
                withContext(NonCancellable) {
                    withContext(Dispatchers.Main) { selectionTracker.clearSelection() }
                }
            }
        }

        shareOutJob?.invokeOnCompletion {
            // Make sure we dismiss waiting SnackBar
            handler.removeCallbacksAndMessages(null)
            if (waitingMsg.isShownOrQueued) waitingMsg.dismiss()

            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun newLayoutManger(): GridLayoutManager {
        val defaultSpanCount = resources.getInteger(if (Tools.isWideListAlbum(album.sortOrder)) R.integer.photo_grid_span_count_wide else R.integer.photo_grid_span_count)
        mAdapter.setPlayMarkDrawable(Tools.getPlayMarkDrawable(requireActivity(), (if (Tools.isWideListAlbum(album.sortOrder)) 0.24f else 0.32f) / defaultSpanCount))
        mAdapter.setSelectedMarkDrawable(Tools.getSelectedMarkDrawable(requireActivity(), (if (Tools.isWideListAlbum(album.sortOrder)) 0.16f else 0.25f) / defaultSpanCount))

        return GridLayoutManager(context, defaultSpanCount).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int { return if (position == 0 && mAdapter.getPhotoAt(0).id == album.id) defaultSpanCount else 1 }
            }
        }
    }

    // Adapter for photo grid
    class PhotoGridAdapter(albumId: String, private val clickListener: (View, Int) -> Unit, private val imageLoader: (Photo, ImageView, String) -> Unit, private val cancelLoader: (View) -> Unit
    ) : ListAdapter<Photo, RecyclerView.ViewHolder>(PhotoDiffCallback(albumId)) {
        private lateinit var album: Album
        protected lateinit var photos: List<Photo>
        private var isWideList = false
        private lateinit var selectionTracker: SelectionTracker<String>
        private val selectedFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.0f) })
        private var recipients = mutableListOf<NCShareViewModel.Recipient>()
        private var recipientText = ""
        private var playMark: Drawable? = null
        private var selectedMark: Drawable? = null

        inner class CoverViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private var currentCover = Photo(dateTaken = LocalDateTime.MIN, lastModified = LocalDateTime.MIN)
            private val ivCover = itemView.findViewById<ImageView>(R.id.photo)
            private val tvTitle = itemView.findViewById<TextView>(R.id.title)
            private val tvDuration = itemView.findViewById<TextView>(R.id.duration)
            private val tvTotal = itemView.findViewById<TextView>(R.id.total)
            private val tvRecipients = itemView.findViewById<TextView>(R.id.recipients)
            private val titleDrawableSize = tvTitle.textSize.toInt()

            fun bindViewItem(cover: Photo) {
                with(itemView) {
                    if (currentCover.name != cover.name || currentCover.eTag != cover.eTag || currentCover.bearing != cover.bearing) {
                        imageLoader(cover.copy(id = album.cover), ivCover, NCShareViewModel.TYPE_COVER)
                        currentCover = cover
                    }

                    tvTitle.apply {
                        text = album.name

                        setCompoundDrawables(
                            if (Tools.isRemoteAlbum(album)) ContextCompat.getDrawable(context, R.drawable.ic_baseline_wb_cloudy_24)?.apply { setBounds(0, 0, titleDrawableSize, titleDrawableSize) } else null,
                            null, null, null
                        )
                    }

                    val days = Duration.between(
                        album.startDate.atZone(ZoneId.systemDefault()).toInstant(),
                        album.endDate.atZone(ZoneId.systemDefault()).toInstant()
                    ).toDays().toInt()
                    tvDuration.text = when (days) {
                        in 0..21 -> resources.getString(R.string.duration_days, days + 1)
                        in 22..56 -> resources.getString(R.string.duration_weeks, days / 7)
                        in 57..365 -> resources.getString(R.string.duration_months, days / 30)
                        else -> resources.getString(R.string.duration_years, days / 365)
                    }

                    tvTotal.text = resources.getString(R.string.total_photo, currentList.size - 1)

                    if (recipients.size > 0) {
                        var names = recipients[0].sharee.label
                        for (i in 1 until recipients.size) names += ", ${recipients[i].sharee.label}"
                        tvRecipients.apply {
                            text = String.format(recipientText, names)
                            visibility = View.VISIBLE
                        }
                    } else tvRecipients.visibility = View.GONE
                }
            }
        }

        inner class PhotoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private var currentPhotoName = ""
            private val ivPhoto = itemView.findViewById<ImageView>(R.id.photo).apply { foregroundGravity = Gravity.CENTER }
            private var tvTitle: TextView? = if (isWideList) itemView.findViewById(R.id.title) else null

            fun bindViewItem(photo: Photo) {
                itemView.let {
                    it.isSelected = selectionTracker.isSelected(photo.id)

                    with(ivPhoto) {
                        if (currentPhotoName != photo.name) {
                            this.setImageResource(0)
                            imageLoader(photo, this, NCShareViewModel.TYPE_GRID)
                            ViewCompat.setTransitionName(this, photo.id)
                            currentPhotoName = photo.name
                        }

                        foreground = when {
                            it.isSelected -> selectedMark
                            Tools.isMediaPlayable(photo.mimeType) -> playMark
                            else -> null
                        }

                        if (it.isSelected) {
                            colorFilter = selectedFilter
                            tvTitle?.isVisible = false
                        } else {
                            clearColorFilter()
                            tvTitle?.isVisible = true
                        }

                        setOnClickListener { if (!selectionTracker.hasSelection()) clickListener(this, bindingAdapterPosition) }
                    }

                    tvTitle?.text = photo.name.substringBeforeLast('.')
                }
            }

            fun getItemDetails() = object : ItemDetailsLookup.ItemDetails<String>() {
                override fun getPosition(): Int = bindingAdapterPosition
                override fun getSelectionKey(): String = getPhotoId(bindingAdapterPosition)
            }
        }

        override fun getItemViewType(position: Int): Int = if (currentList[position].id == this.album.id) TYPE_COVER else TYPE_PHOTO

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            recipientText = parent.context.getString(R.string.published_to)
            return if (viewType == TYPE_COVER) {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_cover, parent, false)
                view.findViewById<TextView>(R.id.title)?.apply {
                    compoundDrawablePadding = 16
                    TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(currentTextColor))
                }
                CoverViewHolder(view)
            }
            else PhotoViewHolder(LayoutInflater.from(parent.context).inflate(if (isWideList) R.layout.recyclerview_item_photo_wide else R.layout.recyclerview_item_photo, parent, false))

        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is PhotoViewHolder) holder.bindViewItem(currentList[position])
            else (holder as CoverViewHolder).bindViewItem(currentList.first())  // List will never be empty, no need to check for NoSuchElementException
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            for (i in 0 until currentList.size) {
                recyclerView.findViewHolderForAdapterPosition(i)?.let { holder -> holder.itemView.findViewById<View>(R.id.photo)?.let { cancelLoader(it) }}
            }
            super.onDetachedFromRecyclerView(recyclerView)
        }

        internal fun setAlbum(album: AlbumWithPhotos, query: String = "") {
            // Find cover photo first, since after cover photo being renamed, several database changes will be triggered and a miss-match will happen
            album.photos.find { it.name == album.album.coverFileName }?.let { coverPhoto ->
                this.album = album.album
                isWideList = Tools.isWideListAlbum(this.album.sortOrder)

                mutableListOf<Photo>().let { photos ->
                    // Add album cover at the top of photo list, clear latitude property so that it would be included in map related function
                    // set id to album's id to avoid duplication with the photo itself and to facilitate scroll to top after sort
                    // set albumId to album's name, so that album name changes can be updated
                    album.album.run { photos.add(coverPhoto.copy(id = album.album.id, albumId = album.album.name, bearing = album.album.coverBaseline.toDouble(), latitude = Photo.NO_GPS_DATA)) }

                    this.photos = when (album.album.sortOrder % 100) {
                        Album.BY_DATE_TAKEN_ASC -> album.photos.sortedWith(compareBy { it.dateTaken })
                        Album.BY_DATE_TAKEN_DESC -> album.photos.sortedWith(compareByDescending { it.dateTaken })
                        Album.BY_NAME_ASC -> album.photos.sortedWith(compareBy(Collator.getInstance().apply { strength = Collator.PRIMARY }) { it.name })
                        Album.BY_NAME_DESC -> album.photos.sortedWith(compareByDescending(Collator.getInstance().apply { strength = Collator.PRIMARY }) { it.name })
                        else -> album.photos
                    }
                    photos.addAll(this.photos)

                    if (query.isNotEmpty()) filter(query)
                    else submitList(photos)
                }
            }
        }

        //internal fun getRecipient(): List<NCShareViewModel.Recipient> = recipients
        internal fun setRecipient(share: NCShareViewModel.ShareByMe) {
            this.recipients = share.with
            notifyItemChanged(0)
        }

        internal fun setPlayMarkDrawable(newDrawable: Drawable) { playMark = newDrawable }
        internal fun setSelectedMarkDrawable(newDrawable: Drawable) { selectedMark = newDrawable }

        internal fun getPhotoAt(position: Int): Photo = currentList[position]
        internal fun getPhotoBy(photoId: String): Photo = currentList.last { it.id == photoId }
        internal fun updateCover(sharedPhoto: Photo) {
            //notifyItemChanged(currentList.indexOfLast { it.id == sharedPhoto.id })
            if (sharedPhoto.id == currentList[0].id) notifyItemChanged(0)
        }

        internal fun setSelectionTracker(selectionTracker: SelectionTracker<String>) { this.selectionTracker = selectionTracker }
        internal fun getPhotoId(position: Int): String = currentList[position].id
        internal fun getPhotoPosition(photoId: String): Int = currentList.indexOfLast { it.id == photoId }
        internal fun filter(query: String) {
            if (query.isEmpty()) setAlbum(AlbumWithPhotos(this.album, this.photos))
            else {
                this.photos.filter { it.name.contains(query) }.let { filtered ->
                    submitList(filtered)
                }
            }
        }

        class PhotoKeyProvider(private val adapter: PhotoGridAdapter): ItemKeyProvider<String>(SCOPE_CACHED) {
            override fun getKey(position: Int): String = adapter.getPhotoId(position)
            override fun getPosition(key: String): Int = adapter.getPhotoPosition(key)
        }
        class PhotoDetailsLookup(private val recyclerView: RecyclerView): ItemDetailsLookup<String>() {
            override fun getItemDetails(e: MotionEvent): ItemDetails<String>? {
                recyclerView.findChildViewUnder(e.x, e.y)?.let {
                    val holder = recyclerView.getChildViewHolder(it)
                    return if (holder is PhotoViewHolder) holder.getItemDetails() else null
                }
                return null
            }
        }

        companion object {
            private const val TYPE_COVER = 0
            private const val TYPE_PHOTO = 1
        }
    }

    class PhotoDiffCallback(private val albumId: String): DiffUtil.ItemCallback<Photo>() {
        override fun areItemsTheSame(oldItem: Photo, newItem: Photo): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Photo, newItem: Photo): Boolean =
            if (oldItem.id == albumId) oldItem.name == newItem.name && oldItem.eTag == newItem.eTag && oldItem.bearing == newItem.bearing && oldItem.albumId == newItem.albumId
            else oldItem.name == newItem.name && oldItem.eTag == newItem.eTag
    }

    companion object {
        private const val RENAME_DIALOG = "RENAME_DIALOG"
        private const val PUBLISH_DIALOG = "PUBLISH_DIALOG"
        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val BGM_DIALOG = "BGM_DIALOG"

        private const val KEY_SELECTION = "KEY_SELECTION"
        private const val KEY_SHARED_SELECTION = "KEY_SHARED_SELECTION"

        private const val DELETE_REQUEST_KEY = "ALBUMDETAIL_DELETE_REQUEST_KEY"
        private const val STRIP_REQUEST_KEY = "ALBUMDETAIL_STRIP_REQUEST_KEY"

        private const val TAG_DESTINATION_DIALOG = "ALBUM_DETAIL_DESTINATION_DIALOG"
        private const val TAG_ACQUIRING_DIALOG = "ALBUM_DETAIL_ACQUIRING_DIALOG"

        private const val GENERAL_SHARE = 0
        private const val SHARE_TO_SNAPSEED = 1
        private const val SHARE_TO_LESPAS = 2

        const val CHOOSER_SPY_ACTION = "site.leos.apps.lespas.CHOOSER_ALBUMDETAIL"

        const val KEY_ALBUM = "ALBUM"
        const val KEY_SCROLL_TO = "KEY_SCROLL_TO"   // SearchResultFragment use this for scrolling to designed photo

        @JvmStatic
        fun newInstance(album: Album, photoId: String) = AlbumDetailFragment().apply {
            arguments = Bundle().apply {
                putParcelable(KEY_ALBUM, album)
                putString(KEY_SCROLL_TO, photoId)
            }
        }
    }
}