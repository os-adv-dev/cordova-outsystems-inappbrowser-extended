package com.outsystems.plugins.inappbrowser.osinappbrowser

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.outsystems.plugins.inappbrowser.osinappbrowserlib.models.OSIABWebViewOptions
import java.util.concurrent.ConcurrentHashMap

/**
 * Manager for hidden InAppBrowser instances used for background authentication flows
 */
object OSIABHiddenBrowserManager {
    private val hiddenBrowsers = ConcurrentHashMap<String, HiddenBrowserInstance>()

    /**
     * Hidden browser instance that runs in the background
     */
    class HiddenBrowserInstance(
        val browserId: String,
        context: Context,
        url: String,
        options: OSIABWebViewOptions,
        customHeaders: Map<String, String>?,
        timeout: Int?,
        val completionHandler: (OSIABEventType, Any?) -> Unit
    ) {
        // Handler MUST be initialized first, before anything else
        private val mainHandler: Handler = Handler(Looper.getMainLooper())

        private var timeoutRunnable: Runnable? = null
        private var navigationCompletedRunnable: Runnable? = null
        private var firstLoadDone = false
        @Volatile private var isDestroyed = false
        private val originalUrl = url
        private val navigationCompletedDelayMs: Long = options.navigationCompletedDelayMs.toLong()

        // WebView is initialized last, after all other properties
        private val webView: WebView

        /**
         * Helper function to extract the domain from a URL
         */
        private fun extractDomain(url: String?): String? {
            if (url == null) return null
            return try {
                val uri = Uri.parse(url)
                uri.host
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Helper function to check if a URL matches the original domain
         */
        private fun matchesOriginalDomain(url: String?): Boolean {
            val originalDomain = extractDomain(originalUrl)
            val currentDomain = extractDomain(url)
            return originalDomain != null && currentDomain != null && originalDomain == currentDomain
        }

        init {
            webView = WebView(context).apply {
                // Configure WebView with options
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = options.mediaPlaybackRequiresUserAction
                    allowFileAccess = true
                    allowContentAccess = true

                    // Set custom user agent if provided
                    options.customUserAgent?.let { userAgentString = it }
                }

                // Clear cache if needed
                if (options.clearCache) {
                    clearCache(true)
                    clearFormData()
                    clearHistory()
                }
                if (options.clearSessionCache) {
                    clearCache(false)
                }

                // Set WebViewClient to handle page events
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        if (isDestroyed) return
                        // Cancel any pending navigation completed events since a new navigation has started
                        navigationCompletedRunnable?.let { mainHandler.removeCallbacks(it) }
                        navigationCompletedRunnable = null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (isDestroyed) return
                        // Only fire BROWSER_PAGE_LOADED when we return to the original domain after potential SSO redirects
                        if (!firstLoadDone && matchesOriginalDomain(url)) {
                            firstLoadDone = true
                            completionHandler(OSIABEventType.BROWSER_PAGE_LOADED, null)
                        } else {
                            // Debounce the navigation completed event to handle redirect chains
                            // Cancel any pending event first
                            navigationCompletedRunnable?.let { mainHandler.removeCallbacks(it) }

                            // Schedule new event to fire after delay (configurable)
                            navigationCompletedRunnable = Runnable {
                                if (!isDestroyed) {
                                    completionHandler(OSIABEventType.BROWSER_PAGE_NAVIGATION_COMPLETED, url)
                                }
                                navigationCompletedRunnable = null
                            }
                            mainHandler.postDelayed(navigationCompletedRunnable!!, navigationCompletedDelayMs)
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        if (isDestroyed) return
                        val errorData = mapOf("error" to (error?.description?.toString() ?: "Unknown error"))
                        completionHandler(OSIABEventType.BROWSER_FINISHED, errorData)
                    }
                }

                // Load URL with custom headers if provided
                if (customHeaders != null && customHeaders.isNotEmpty()) {
                    loadUrl(url, customHeaders)
                } else {
                    loadUrl(url)
                }
            }

            // Set up timeout if specified
            timeout?.let { timeoutSeconds ->
                if (timeoutSeconds > 0) {
                    timeoutRunnable = Runnable {
                        if (!isDestroyed) {
                            val timeoutData = mapOf("reason" to "timeout")
                            completionHandler(OSIABEventType.BROWSER_FINISHED, timeoutData)
                        }
                    }
                    mainHandler.postDelayed(timeoutRunnable!!, (timeoutSeconds * 1000).toLong())
                }
            }
        }

        fun cleanup() {
            isDestroyed = true
            timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            navigationCompletedRunnable?.let { mainHandler.removeCallbacks(it) }
            webView.stopLoading()
            webView.destroy()
        }
    }

    /**
     * Creates and stores a hidden browser instance
     */
    fun create(
        browserId: String,
        context: Context,
        url: String,
        options: OSIABWebViewOptions,
        customHeaders: Map<String, String>?,
        timeout: Int?,
        completionHandler: (OSIABEventType, Any?) -> Unit
    ) {
        val instance = HiddenBrowserInstance(
            browserId,
            context,
            url,
            options,
            customHeaders,
            timeout,
            completionHandler
        )
        hiddenBrowsers[browserId] = instance
    }

    /**
     * Removes a hidden browser instance
     */
    fun remove(browserId: String) {
        hiddenBrowsers[browserId]?.let { instance ->
            instance.cleanup()
            hiddenBrowsers.remove(browserId)
        }
    }

    /**
     * Gets a hidden browser instance
     */
    fun get(browserId: String): HiddenBrowserInstance? {
        return hiddenBrowsers[browserId]
    }

    /**
     * Removes all hidden browsers
     */
    fun removeAll() {
        hiddenBrowsers.keys.forEach { browserId ->
            remove(browserId)
        }
    }
}
