/*
 *     This file is part of Lawnchair Launcher.
 *
 *     Lawnchair Launcher is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Lawnchair Launcher is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with Lawnchair Launcher.  If not, see <https://www.gnu.org/licenses/>.
 */

package app.lawnchair.allapps

import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import app.lawnchair.LawnchairLauncher
import app.lawnchair.data.folder.service.FolderService
import app.lawnchair.preferences.PreferenceManager
import com.android.app.animation.Interpolators
import com.android.launcher3.DropTarget
import com.android.launcher3.LauncherState
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.allapps.AllAppsRecyclerView
import com.android.launcher3.allapps.BaseAllAppsAdapter
import com.android.launcher3.dragndrop.DragController
import com.android.launcher3.dragndrop.DragLayer
import com.android.launcher3.dragndrop.DragOptions
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.views.BaseDragLayer
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Lets an app icon be dragged onto one of the app drawer folders to add it to that folder.
 *
 * Dragging an icon inside the drawer normally drops straight into the spring loaded home screen,
 * which pulls the drawer -- and with it every folder -- off the screen before the icon can be
 * dropped anywhere useful. While this controller is active the drawer is kept open instead,
 * folders light up as the icon passes over them, and a chip at the top of the screen hands the
 * drag back over to the home screen for people who wanted that all along.
 *
 * The controller only takes over when the drawer actually shows folders, so a drawer without any
 * keeps behaving exactly like it did before.
 */
class DrawerFolderDragController(private val launcher: LawnchairLauncher) :
    DropTarget,
    DragController.DragListener {

    private val prefs = PreferenceManager.getInstance(launcher)
    private val folderService = FolderService.INSTANCE.get(launcher)

    /** The drag object [active] was computed for, so each drag is only evaluated once. */
    private var evaluatedFor: DropTarget.DragObject? = null
    private var active = false

    /** Set once the drag has been handed over to the home screen, which ends our involvement. */
    private var handedOff = false

    private var hoveredFolder: FolderIcon? = null
    private var hintView: View? = null
    private var handoffArmed = false

    private val tmpRect = Rect()
    private val tmpCoord = FloatArray(2)

    private val hintMarginPx = Utilities.dpToPx(HINT_MARGIN_DP)
    private val autoScrollZonePx = Utilities.dpToPx(AUTO_SCROLL_ZONE_DP)
    private val autoScrollStepPx = Utilities.dpToPx(AUTO_SCROLL_STEP_DP)

    private var autoScrollDelta = 0
    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            if (autoScrollDelta == 0) return
            val recyclerView = activeRecyclerView ?: return
            recyclerView.scrollBy(0, autoScrollDelta)
            recyclerView.postOnAnimation(this)
        }
    }

    private val handoffRunnable = Runnable { handOffToHomeScreen() }

    private val activeRecyclerView: AllAppsRecyclerView?
        get() = launcher.appsView?.activeRecyclerView

    fun attach() {
        launcher.dragController.addDragListener(this)
    }

    /**
     * Whether the app drawer should stay open for [dragObject] instead of collapsing into the
     * spring loaded home screen. Called from `Workspace#onDragStart`, before the state change.
     */
    fun shouldKeepAppDrawerOpen(dragObject: DropTarget.DragObject): Boolean {
        if (evaluatedFor !== dragObject) {
            evaluatedFor = dragObject
            active = isEligible(dragObject)
        }
        return active && !handedOff
    }

    private fun isEligible(dragObject: DropTarget.DragObject): Boolean {
        if (!prefs.drawerFolderDragAndDrop.get()) return false
        // Only the user defined folder list can be edited. The automatic categories are derived
        // from the installed apps and would be rebuilt on the next reload anyway.
        if (!prefs.drawerList.get()) return false
        if (dragObject.dragInfo !is AppInfo) return false
        // Accessibility drags are driven by the "Add to home screen" action, which has nothing to
        // do with the drawer folders and expects the home screen to take the drop.
        if (launcher.dragController.mOptions?.isAccessibleDrag == true) return false
        val appsView = launcher.appsView ?: return false
        if (dragObject.dragSource !== appsView) return false
        if (appsView.isSearching) return false
        if (!launcher.isInState(LauncherState.ALL_APPS)) return false
        val adapterItems = activeRecyclerView?.apps?.adapterItems ?: return false
        return adapterItems.any { it.viewType == BaseAllAppsAdapter.VIEW_TYPE_FOLDER }
    }

    override fun onDragStart(dragObject: DropTarget.DragObject, options: DragOptions) {
        if (!shouldKeepAppDrawerOpen(dragObject)) return
        launcher.dragController.addDropTarget(this)
        showHint()
    }

    override fun onDragEnd() {
        launcher.dragController.removeDropTarget(this)
        setHoveredFolder(null)
        stopAutoScroll()
        setHandoffArmed(false)
        hideHint()
        evaluatedFor = null
        active = false
        handedOff = false
    }

    override fun isDropEnabled(): Boolean = active && !handedOff

    /**
     * The drawer covers the whole screen while it is open, so every drop belongs to us. Claiming
     * the full drag layer also means a drop on empty drawer space is handled here -- by sending
     * the icon back where it came from -- instead of falling through to the workspace, which would
     * dismiss the drawer.
     */
    override fun getHitRectRelativeToDragLayer(outRect: Rect) {
        outRect.set(0, 0, launcher.dragLayer.width, launcher.dragLayer.height)
    }

    /** Keeps the drag object coordinates in drag layer space, which is what the lookups below use. */
    override fun getDropView(): View? = null

    override fun onDragEnter(dragObject: DropTarget.DragObject) = Unit

    override fun onDragOver(dragObject: DropTarget.DragObject) {
        if (!isDropEnabled) return
        val folderIcon = findFolderIconUnder(dragObject.x, dragObject.y)
        // A folder always wins over the handoff chip, so a folder near the top of the list stays
        // reachable.
        setHandoffArmed(folderIcon == null && isOverHandoffZone(dragObject.y))
        setHoveredFolder(folderIcon)
        updateAutoScroll(dragObject.y)
    }

    override fun onDragExit(dragObject: DropTarget.DragObject) {
        setHoveredFolder(null)
        stopAutoScroll()
        setHandoffArmed(false)
    }

    /**
     * Always accepts while the drawer is held open: a drop that missed every folder is answered
     * with the icon flying back to its spot, which is friendlier than letting the drag fall
     * through to the home screen.
     */
    override fun acceptDrop(dragObject: DropTarget.DragObject): Boolean = isDropEnabled

    override fun onDrop(dragObject: DropTarget.DragObject, options: DragOptions) {
        val folderIcon = findFolderIconUnder(dragObject.x, dragObject.y)
        setHoveredFolder(null)
        stopAutoScroll()
        setHandoffArmed(false)

        val appInfo = dragObject.dragInfo as? AppInfo
        val folderId = folderIcon?.mInfo?.id ?: ItemInfo.NO_ID
        if (folderIcon == null || appInfo == null || folderId == ItemInfo.NO_ID) {
            animateDragViewBack(dragObject)
            return
        }

        val componentKey = appInfo.toComponentKey().toString()
        launcher.lifecycleScope.launch {
            folderService.addItemToFolder(folderId, componentKey)
        }
        animateDragViewIntoFolder(dragObject, folderIcon)
    }

    override fun prepareAccessibilityDrop() = Unit

    // Finding and highlighting the folder under the finger

    private fun findFolderIconUnder(x: Int, y: Int): FolderIcon? {
        val recyclerView = activeRecyclerView ?: return null
        for (i in 0 until recyclerView.childCount) {
            val folderIcon = recyclerView.getChildAt(i).findFolderIcon() ?: continue
            launcher.dragLayer.getDescendantRectRelativeToSelf(folderIcon, tmpRect)
            if (tmpRect.contains(x, y)) return folderIcon
        }
        return null
    }

    private fun View.findFolderIcon(): FolderIcon? = when (this) {
        is FolderIcon -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it) as? FolderIcon }
        else -> null
    }

    private fun setHoveredFolder(folderIcon: FolderIcon?) {
        if (hoveredFolder === folderIcon) return
        hoveredFolder?.setDrawerDragAccepting(false)
        folderIcon?.setDrawerDragAccepting(true)
        hoveredFolder = folderIcon
    }

    // Scrolling the drawer while dragging, so folders stay reachable from anywhere in the list

    private fun updateAutoScroll(y: Int) {
        val recyclerView = activeRecyclerView ?: return
        launcher.dragLayer.getDescendantRectRelativeToSelf(recyclerView, tmpRect)
        // Start scrolling up below the handoff chip, otherwise the two would fight over the same
        // strip of screen.
        val scrollUpTop = maxOf(tmpRect.top, handoffZoneBottom())
        val delta = when {
            y in scrollUpTop..(scrollUpTop + autoScrollZonePx) -> -autoScrollStepPx
            y > tmpRect.bottom - autoScrollZonePx -> autoScrollStepPx
            else -> 0
        }
        if (delta == autoScrollDelta) return
        val wasScrolling = autoScrollDelta != 0
        autoScrollDelta = delta
        if (delta == 0) {
            recyclerView.removeCallbacks(autoScrollRunnable)
        } else if (!wasScrolling) {
            recyclerView.postOnAnimation(autoScrollRunnable)
        }
    }

    private fun stopAutoScroll() {
        autoScrollDelta = 0
        activeRecyclerView?.removeCallbacks(autoScrollRunnable)
    }

    // Handing the drag back to the home screen

    /** Bottom of the strip that arms the handoff, or 0 while the chip has not been laid out. */
    private fun handoffZoneBottom(): Int {
        val hint = hintView ?: return 0
        return if (hint.height == 0) 0 else hint.bottom
    }

    private fun isOverHandoffZone(y: Int): Boolean {
        val bottom = handoffZoneBottom()
        return bottom > 0 && y <= bottom
    }

    /**
     * The handoff only fires after the icon rests on the chip for a moment, so passing through the
     * top of the drawer on the way to a folder does not throw the drawer away.
     */
    private fun setHandoffArmed(armed: Boolean) {
        if (armed == handoffArmed) return
        handoffArmed = armed
        val hint = hintView
        if (armed) {
            hint?.animate()?.scaleX(HINT_ARMED_SCALE)?.scaleY(HINT_ARMED_SCALE)
                ?.setDuration(HINT_FADE_DURATION)?.start()
            launcher.dragLayer.postDelayed(handoffRunnable, HANDOFF_DWELL_MS)
        } else {
            hint?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(HINT_FADE_DURATION)?.start()
            launcher.dragLayer.removeCallbacks(handoffRunnable)
        }
    }

    private fun handOffToHomeScreen() {
        if (handedOff || !active) return
        handedOff = true
        handoffArmed = false
        launcher.dragController.removeDropTarget(this)
        setHoveredFolder(null)
        stopAutoScroll()
        hideHint()
        launcher.stateManager.goToState(LauncherState.SPRING_LOADED)
    }

    // The home screen chip

    private fun showHint() {
        if (hintView != null) return
        val dragLayer = launcher.dragLayer
        val hint = launcher.layoutInflater
            .inflate(R.layout.drawer_folder_drag_hint, dragLayer, false)
        val lp = BaseDragLayer.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.topMargin = hintMarginPx
        hint.layoutParams = lp
        hint.alpha = 0f
        dragLayer.addView(hint)
        hint.animate().alpha(1f).setDuration(HINT_FADE_DURATION).start()
        hintView = hint
    }

    private fun hideHint() {
        val hint = hintView ?: return
        hintView = null
        launcher.dragLayer.removeCallbacks(handoffRunnable)
        hint.animate()
            .alpha(0f)
            .setDuration(HINT_FADE_DURATION)
            .withEndAction { launcher.dragLayer.removeView(hint) }
            .start()
    }

    // Drop animations

    private fun animateDragViewIntoFolder(dragObject: DropTarget.DragObject, folderIcon: FolderIcon) {
        folderIcon.getPreviewBounds(tmpRect)
        tmpCoord[0] = tmpRect.exactCenterX()
        tmpCoord[1] = tmpRect.exactCenterY()
        launcher.dragLayer.getDescendantCoordRelativeToSelf(folderIcon, tmpCoord)
        animateDragViewTo(
            dragObject = dragObject,
            centerX = tmpCoord[0],
            centerY = tmpCoord[1],
            finalAlpha = 0f,
            finalScale = DROP_IN_FOLDER_SCALE,
        )
    }

    private fun animateDragViewBack(dragObject: DropTarget.DragObject) {
        val originalView = dragObject.originalView as? View
        if (originalView == null || !originalView.isAttachedToWindow) {
            // Nothing left to fly back to, just take the icon off the screen.
            dragObject.deferDragViewCleanupPostAnimation = false
            return
        }
        launcher.dragLayer.getDescendantRectRelativeToSelf(originalView, tmpRect)
        animateDragViewTo(
            dragObject = dragObject,
            centerX = tmpRect.exactCenterX(),
            centerY = tmpRect.exactCenterY(),
            finalAlpha = 1f,
            finalScale = 1f,
        )
    }

    private fun animateDragViewTo(
        dragObject: DropTarget.DragObject,
        centerX: Float,
        centerY: Float,
        finalAlpha: Float,
        finalScale: Float,
    ) {
        val dragView = dragObject.dragView
        if (dragView == null) {
            dragObject.deferDragViewCleanupPostAnimation = false
            return
        }
        val left = (centerX - dragView.measuredWidth / 2f).roundToInt()
        val top = (centerY - dragView.measuredHeight / 2f).roundToInt()
        val to = Rect(left, top, left + dragView.measuredWidth, top + dragView.measuredHeight)
        launcher.dragLayer.animateView(
            dragView,
            to,
            finalAlpha,
            finalScale,
            finalScale,
            DROP_ANIMATION_DURATION,
            Interpolators.DECELERATE_2,
            null,
            DragLayer.ANIMATION_END_DISAPPEAR,
            null,
        )
    }

    companion object {

        private const val HINT_MARGIN_DP = 16f
        private const val AUTO_SCROLL_ZONE_DP = 72f
        private const val AUTO_SCROLL_STEP_DP = 8f
        private const val HINT_FADE_DURATION = 150L
        private const val HINT_ARMED_SCALE = 1.08f
        private const val HANDOFF_DWELL_MS = 350L
        private const val DROP_ANIMATION_DURATION = 250
        private const val DROP_IN_FOLDER_SCALE = 0.4f

        /**
         * Whether the app drawer should be kept open for [dragObject] rather than collapsing into
         * the spring loaded home screen. Safe to call for any launcher and any drag.
         */
        @JvmStatic
        fun shouldKeepAppDrawerOpen(launcher: Any?, dragObject: DropTarget.DragObject): Boolean {
            val controller = (launcher as? LawnchairLauncher)?.drawerFolderDragController
            return controller?.shouldKeepAppDrawerOpen(dragObject) == true
        }
    }
}
