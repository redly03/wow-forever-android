package app.gamenative.externaldisplay

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.winlator.renderer.VulkanRenderer
import com.winlator.widget.TouchpadView
import com.winlator.widget.XServerView
import com.winlator.xserver.Pointer
import com.winlator.xserver.XServer
import kotlin.math.abs

/**
 * Dual-screen split for handhelds like the AYN Thor. The X screen is taller than the top display:
 * the top display shows rows [0, topHeight) and the second display shows the band below it at 1:1,
 * so a game that spreads its UI over the whole window (WoW with an addon like Offhand) gets a real,
 * touchable second screen. Both displays render the same X server; the second one is a mirror
 * renderer that receives every presented frame from the main one.
 */
class SplitScreenController(
    private val context: Context,
    private val xServer: XServer,
    private val mainRenderer: VulkanRenderer,
    private val topHeight: Int,
    private val touchpadViewProvider: () -> TouchpadView?,
) {
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private var presentation: SplitPresentation? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = updatePresentation()

        override fun onDisplayRemoved(displayId: Int) {
            if (presentation?.display?.displayId == displayId) dismissPresentation()
            updatePresentation()
        }

        override fun onDisplayChanged(displayId: Int) = Unit
    }

    fun start() {
        mainRenderer.setSourceRegion(0, 0, xServer.screenInfo.width.toInt(), topHeight)
        touchpadViewProvider()?.refreshXform()
        displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        updatePresentation()
    }

    fun stop() {
        dismissPresentation()
        try {
            displayManager?.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {
        }
    }

    private fun updatePresentation() {
        if (presentation != null) return
        val display = findPresentationDisplay() ?: return
        presentation = SplitPresentation(context, display, xServer, topHeight).also {
            it.show()
            mainRenderer.setMirror(it.renderer)
        }
    }

    private fun dismissPresentation() {
        mainRenderer.setMirror(null)
        presentation?.let {
            it.renderer?.detach()
            it.dismiss()
        }
        presentation = null
    }

    private fun findPresentationDisplay(): Display? {
        val currentDisplay = context.display ?: return null
        return displayManager
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.firstOrNull { it.displayId != currentDisplay.displayId && it.name != "HiddenDisplay" }
    }

    companion object {
        /** Size of the band for the second display: its real size, so the band shows at 1:1. */
        fun secondDisplaySize(context: Context): Pair<Int, Int>? {
            val displayManager = context.getSystemService(DisplayManager::class.java) ?: return null
            val display = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.name != "HiddenDisplay" }
                ?: return null
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            return size.x to size.y
        }
    }
}

private class SplitPresentation(
    outerContext: Context,
    display: Display,
    private val xServer: XServer,
    private val topHeight: Int,
) : Presentation(outerContext, display) {
    var renderer: VulkanRenderer? = null
        private set

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        )
        val view = XServerView(context, xServer, "vulkan")
        val vr = view.renderer as VulkanRenderer
        renderer = vr
        val root = FrameLayout(context)
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val width = right - left
            val height = bottom - top
            if (width > 0 && height > 0) {
                vr.setSourceRegion(0, topHeight, minOf(width, xServer.screenInfo.width.toInt()), minOf(height, xServer.screenInfo.height - topHeight))
            }
        }
        root.setOnTouchListener(BandTouchHandler(root, xServer, topHeight))
        setContentView(root)
    }
}

/**
 * Touch on the second display drives the X pointer inside the band: tap is a left click, a
 * long press is a right click (use an item, open a context menu), and dragging holds the left
 * button (move items between bags and the character sheet).
 */
private class BandTouchHandler(
    private val root: ViewGroup,
    private val xServer: XServer,
    private val topHeight: Int,
) : android.view.View.OnTouchListener {
    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(root.context).scaledTouchSlop
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var longPressed = false

    private val longPress = Runnable {
        longPressed = true
        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT)
        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT)
    }

    private fun moveTo(event: MotionEvent) {
        val bandWidth = minOf(root.width, xServer.screenInfo.width.toInt())
        val bandHeight = minOf(root.height, xServer.screenInfo.height - topHeight)
        val x = (event.x / root.width * bandWidth).toInt()
        val y = topHeight + (event.y / root.height * bandHeight).toInt()
        xServer.injectPointerMove(x, y)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: android.view.View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                longPressed = false
                moveTo(event)
                handler.postDelayed(longPress, longPressMs)
            }
            MotionEvent.ACTION_MOVE -> {
                moveTo(event)
                if (!dragging && !longPressed && (abs(event.x - downX) > slop || abs(event.y - downY) > slop)) {
                    handler.removeCallbacks(longPress)
                    dragging = true
                    xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                moveTo(event)
                when {
                    longPressed -> Unit
                    dragging -> xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
                    else -> {
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                if (dragging) xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
            }
        }
        return true
    }
}
