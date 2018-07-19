/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.browser

import android.arch.lifecycle.Observer
import android.os.Bundle
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.ContextMenu
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlinx.android.synthetic.main.fragment_browser.*
import kotlinx.android.synthetic.main.fragment_browser.view.*
import kotlinx.coroutines.experimental.CancellationException
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import org.mozilla.focus.MainActivity
import org.mozilla.focus.R
import org.mozilla.focus.ScreenController
import org.mozilla.focus.browser.BrowserFragment.Companion.APP_URL_HOME
import org.mozilla.focus.ext.isVisible
import org.mozilla.focus.ext.toUri
import org.mozilla.focus.home.BundledTilesManager
import org.mozilla.focus.home.CustomTilesManager
import org.mozilla.focus.home.HomeTilesManager
import org.mozilla.focus.iwebview.IWebView
import org.mozilla.focus.iwebview.IWebViewLifecycleFragment
import org.mozilla.focus.session.NullSession
import org.mozilla.focus.session.Session
import org.mozilla.focus.session.SessionCallbackProxy
import org.mozilla.focus.session.SessionManager
import org.mozilla.focus.telemetry.NonFatalAssertionException
import org.mozilla.focus.telemetry.SentryWrapper
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.telemetry.UrlTextInputLocation
import org.mozilla.focus.toolbar.ToolbarStateProvider
import org.mozilla.focus.toolbar.NavigationEvent
import org.mozilla.focus.utils.ViewUtils.showCenteredTopToast
import mozilla.components.ui.autocomplete.InlineAutocompleteEditText

private const val ARGUMENT_SESSION_UUID = "sessionUUID"

private const val TOAST_Y_OFFSET = 200

/** An interface expected to be implemented by the Activities that create a BrowserFragment. */
interface BrowserFragmentCallbacks {
    fun onHomeVisibilityChange(isHomeVisible: Boolean, isFirstHomescreenInStack: Boolean)
    fun onFullScreenChange(isFullscreen: Boolean)
}

/**
 * Fragment for displaying the browser UI.
 */
class BrowserFragment : IWebViewLifecycleFragment() {
    companion object {
        const val FRAGMENT_TAG = "browser"
        const val APP_URL_PREFIX = "firefox:"
        const val APP_URL_HOME = "${APP_URL_PREFIX}home"

        @JvmStatic
        fun createForSession(session: Session) = BrowserFragment().apply {
            arguments = Bundle().apply { putString(ARGUMENT_SESSION_UUID, session.uuid) }
        }
    }

    // IWebViewLifecycleFragment expects a value for these properties before onViewCreated. We use a getter
    // for the properties that reference session because it is lateinit.
    override lateinit var session: Session
    override val initialUrl get() = session.url.value
    override lateinit var iWebViewCallback: IWebView.Callback

    internal val callbacks: BrowserFragmentCallbacks? get() = activity as BrowserFragmentCallbacks?
    val toolbarStateProvider = BrowserToolbarStateProvider()
    var onUrlUpdate: ((url: String?) -> Unit)? = null
    var onSessionProgressUpdate: ((value: Int) -> Unit)? = null

    /**
     * The current URL.
     *
     * Use this instead of the WebView's URL which can return null, return a null URL, or return
     * data: URLs (for error pages).
     */
    var url: String? = null
        private set(value) {
            field = value
            onUrlUpdate?.invoke(url)
        }

    val isUrlEqualToHomepage: Boolean get() = url == APP_URL_HOME

    private val sessionManager = SessionManager.getInstance()

    // Cache the overlay visibility state to persist in fragment back stack
    private var overlayVisibleCached: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = initSession()
        webView?.setBlockingEnabled(session.isBlockingEnabled)
        iWebViewCallback = SessionCallbackProxy(session, BrowserIWebViewCallback(this))
    }

    private fun initSession(): Session {
        val sessionUUID = arguments?.getString(ARGUMENT_SESSION_UUID)
                ?: throw IllegalAccessError("No session exists")
        val session = if (sessionManager.hasSessionWithUUID(sessionUUID))
            sessionManager.getSessionByUUID(sessionUUID)
        else
            NullSession()

        session.url.observe(this, Observer { url -> this@BrowserFragment.url = url })
        session.progress.observe(this, Observer { value ->
            // We need to set this separately because the webView does some loading
            // to load the home screen, thus leaving a little bit of residual progress
            // bar active. We set to 0 to reset the state of the bar.
            if (url == APP_URL_HOME) {
                onSessionProgressUpdate?.invoke(0)
            } else if (value != null) {
                if (value == 99) {
                    // The max progress value is 99 (see comment in onProgress() in SessionCallbackProxy),
                    // thus we send 100 to the UrlBoxProgressView to complete its animation.
                    onSessionProgressUpdate?.invoke(100)
                } else {
                    onSessionProgressUpdate?.invoke(value)
                }
            }
        })
        return session
    }

    val onNavigationEvent = { event: NavigationEvent, value: String?,
            autocompleteResult: InlineAutocompleteEditText.AutocompleteResult? ->
        val context = context!!

        if (event == NavigationEvent.BACK || event == NavigationEvent.FORWARD) {
            // The new URL will be set internally after this point;
            // if we're going back to the home page, it will override our hide here.
            homeScreen.visibility = View.GONE
        }

        when (event) {
            NavigationEvent.BACK -> if (webView?.canGoBack() ?: false) webView?.goBack()
            NavigationEvent.FORWARD -> if (webView?.canGoForward() ?: false) webView?.goForward()
            NavigationEvent.TURBO -> {
                when (value) {
                    NavigationEvent.VAL_CHECKED -> {
                        showCenteredTopToast(context, R.string.turbo_mode_enabled_toast,
                                0, TOAST_Y_OFFSET)
                    }
                    NavigationEvent.VAL_UNCHECKED -> {
                        showCenteredTopToast(context, R.string.turbo_mode_disabled_toast,
                            0, TOAST_Y_OFFSET)
                    }
                }
                webView?.reload()
            }
            NavigationEvent.RELOAD -> webView?.reload()
            NavigationEvent.SETTINGS -> ScreenController.showSettingsScreen(fragmentManager!!)
            NavigationEvent.LOAD_URL ->
                (activity as MainActivity).onTextInputUrlEntered(value!!, autocompleteResult!!, UrlTextInputLocation.MENU)
            NavigationEvent.LOAD_TILE -> (activity as MainActivity).onNonTextInputUrlEntered(value!!)
            NavigationEvent.PIN_ACTION -> {
                this@BrowserFragment.url?.let { url ->
                    val brandName = context.getString(R.string.firefox_brand_name)
                    when (value) {
                        NavigationEvent.VAL_CHECKED -> {
                            CustomTilesManager.getInstance(context).pinSite(context, url,
                                    webView?.takeScreenshot())
                            homeScreen.refreshTilesForInsertion()
                            showCenteredTopToast(context, context.getString(
                                    R.string.notification_pinned_general2, brandName),
                                    0, TOAST_Y_OFFSET)
                        }
                        NavigationEvent.VAL_UNCHECKED -> {
                            url.toUri()?.let {
                                val tileId = BundledTilesManager.getInstance(context).unpinSite(context, it)
                                        ?: CustomTilesManager.getInstance(context).unpinSite(context, url)
                                // tileId should never be null, unless, for some reason we don't
                                // have a reference to the tile/the tile isn't a Bundled or Custom tile
                                if (tileId != null && !tileId.isEmpty()) {
                                    homeScreen.removePinnedSiteFromTiles(tileId)
                                    showCenteredTopToast(context, context.getString(
                                            R.string.notification_unpinned_general2, brandName),
                                            0, TOAST_Y_OFFSET)
                                }
                            }
                        }
                        else -> throw IllegalArgumentException("Unexpected value for PIN_ACTION: " + value)
                    }
                }
            }
            NavigationEvent.HOME -> if (!homeScreen.isVisible) { setOverlayVisibleByUser(true, toAnimate = true) }
        }
        Unit
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val layout = inflater.inflate(R.layout.fragment_browser, container, false)

        with (layout.homeScreen) {
            onNavigationEvent = this@BrowserFragment.onNavigationEvent
            visibility = overlayVisibleCached ?: View.GONE
            onPreSetVisibilityListener = {
                webView!!.onOverlayPreSetVisibility(it)
                callbacks?.onHomeVisibilityChange(it, isUrlEqualToHomepage)
            }

            openHomeTileContextMenu = {
                activity?.openContextMenu(this)
            }
            registerForContextMenu(this)
        }

        return layout
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.remove -> {
                val homeTileAdapter = homeScreen.adapter as HomeTileAdapter
                val tileToRemove = homeTileAdapter.getItemAtPosition(homeScreen.getFocusedTilePosition())
                        ?: return false

                // This assumes that since we're deleting from a Home Tile object that we created
                // that the Uri is valid, so we do not do error handling here.
                HomeTilesManager.removeHomeTile(tileToRemove, context!!)
                homeTileAdapter.removeItemAtPosition(homeScreen.getFocusedTilePosition())
                TelemetryWrapper.homeTileRemovedEvent(tileToRemove)
                return true
            }
            else -> return false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        overlayVisibleCached = homeScreen.visibility
        // Since we start the async jobs in View.init and Android is inflating the view for us,
        // there's no good way to pass in the uiLifecycleJob. We could consider other solutions
        // but it'll add complexity that I don't think is probably worth it.
        homeScreen.uiLifecycleCancelJob.cancel(CancellationException("Parent lifecycle has ended"))
    }

    override fun onCreateContextMenu(menu: ContextMenu?, v: View?, menuInfo: ContextMenu.ContextMenuInfo?) {
        activity!!.menuInflater.inflate(R.menu.menu_context_hometile, menu)
    }

    fun onBackPressed(): Boolean {
        when {
            homeScreen.isVisible && !isUrlEqualToHomepage -> setOverlayVisibleByUser(false)
            webView?.canGoBack() ?: false -> {
                webView?.goBack()
                TelemetryWrapper.browserBackControllerEvent()
            }
            else -> {
                SessionManager.getInstance().removeCurrentSession()
                // Delete session, but we allow the parent to handle back behavior.
                return false
            }
        }
        return true
    }

    fun loadUrl(url: String) {
        // Intents can trigger loadUrl, and we need to make sure the homescreen is always hidden.
        homeScreen.visibility = View.GONE
        val webView = webView
        if (webView != null && !TextUtils.isEmpty(url)) {
            webView.loadUrl(url)
        }
    }

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        /**
         * Key handling order:
         * - Menu to control overlay
         * - Youtube remap of BACK to ESC
         * - Return false, as unhandled
         */
        return handleSpecialKeyEvent(event)
    }

    private fun handleSpecialKeyEvent(event: KeyEvent): Boolean {
        if (!homeScreen.isVisible && webView!!.isYoutubeTV &&
                event.keyCode == KeyEvent.KEYCODE_BACK) {
            val escKeyEvent = KeyEvent(event.action, KeyEvent.KEYCODE_ESCAPE)
            activity?.dispatchKeyEvent(escKeyEvent)
            return true
        }
        return false
    }

    /**
     * Changes the overlay visibility: this should be called instead of changing
     * [HomeTileGridNavigation.isVisible] directly.
     *
     * It's important this is only called for user actions because our Telemetry
     * is dependent on it.
     */
    fun setOverlayVisibleByUser(toShow: Boolean, toAnimate: Boolean = false) {
        homeScreen.setVisibility(if (toShow) View.VISIBLE else View.GONE, toAnimate)
        TelemetryWrapper.drawerShowHideEvent(toShow)
    }

    inner class BrowserToolbarStateProvider : ToolbarStateProvider {
        override fun isBackEnabled() = webView?.canGoBack() ?: false
        override fun isForwardEnabled() = webView?.canGoForward() ?: false
        override fun isPinEnabled() = !isUrlEqualToHomepage
        override fun isRefreshEnabled() = !isUrlEqualToHomepage
        override fun getCurrentUrl() = url
        override fun isURLPinned() = url.toUri()?.let {
            // TODO: #569 fix CustomTilesManager to use Uri too
            CustomTilesManager.getInstance(context!!).isURLPinned(it.toString()) ||
                    BundledTilesManager.getInstance(context!!).isURLPinned(it) } ?: false
    }
}

private class BrowserIWebViewCallback(
        private val browserFragment: BrowserFragment
) : IWebView.Callback {

    private var fullscreenCallback: IWebView.FullscreenCallback? = null

    override fun onPageStarted(url: String) {}

    override fun onPageFinished(isSecure: Boolean) {}
    override fun onProgress(progress: Int) {}

    override fun onURLChanged(url: String) {}
    override fun onRequest(isTriggeredByUserGesture: Boolean) {}

    override fun onBlockingStateChanged(isBlockingEnabled: Boolean) {}

    override fun onLongPress(hitTarget: IWebView.HitTarget) {}
    override fun onShouldInterceptRequest(url: String) {
        // This might not be called from the UI thread but needs to be, so we use launch.
        launch(UI) {
            when (url) {
                APP_URL_HOME -> browserFragment.homeScreen?.visibility = View.VISIBLE
            }
        }
    }

    override fun onEnterFullScreen(callback: IWebView.FullscreenCallback, view: View?) {
        fullscreenCallback = callback
        if (view == null) return

        with (browserFragment) {
            callbacks?.onFullScreenChange(true)

            // Hide browser UI and web content
            browserContainer.visibility = View.INVISIBLE

            val activity = this.activity
            val height = if (activity != null) {
                val displayMetrics = DisplayMetrics()
                activity.windowManager.defaultDisplay.getMetrics(displayMetrics)
                displayMetrics.heightPixels
            } else {
                SentryWrapper.capture(NonFatalAssertionException("activity null when entering fullscreen"))
                ViewGroup.LayoutParams.MATCH_PARENT
            }

            val params = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, height)
            videoContainer.addView(view, params)
            videoContainer.visibility = View.VISIBLE
        }
    }

    override fun onExitFullScreen() {
        with (browserFragment) {
            callbacks?.onFullScreenChange(false)

            videoContainer.removeAllViews()
            videoContainer.visibility = View.GONE

            browserContainer.visibility = View.VISIBLE
        }

        fullscreenCallback?.fullScreenExited()
        fullscreenCallback = null
    }
}
