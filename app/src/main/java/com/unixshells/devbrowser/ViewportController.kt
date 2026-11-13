package com.unixshells.devbrowser

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * タブごとのCDP接続とviewport previewの状態を保持する。
 *
 * CDPのDeviceMetricsOverrideだけではAndroid側のWebView表示枠は狭くならないため、
 * CSS viewportをCDPで固定しつつ、WebView自体のレイアウト幅もdevicePixelRatioに合わせて
 * 変更する。親コンテナより大きい場合はAndroidのtransformで表示だけ縮小する。
 */
class ViewportController(private val port: Int) {
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    private val sessions = mutableMapOf<Int, Session>()
    private val busy = mutableSetOf<Int>()
    private val discoveries = mutableMapOf<Int, () -> Unit>()
    private val viewportWidths = mutableMapOf<Int, Int>()
    private var closed = false

    fun apply(tab: Tab, width: Int, callback: (String?) -> Unit) {
        require(width in 1..ViewportWidth.MAX)

        val params = JSONObject()
            .put("width", width)
            .put("height", 0)
            .put("deviceScaleFactor", 0)
            .put("mobile", false)

        execute(tab, "Emulation.setDeviceMetricsOverride", params) { error ->
            if (error != null) {
                callback(error)
                return@execute
            }

            viewportWidths[tab.id] = width
            refreshVisual(tab) { visualError ->
                if (visualError != null) {
                    callback(visualError)
                    return@refreshVisual
                }
                verifyWidth(tab, width, callback)
            }
        }
    }

    fun reset(tab: Tab, callback: (String?) -> Unit) {
        execute(tab, "Emulation.clearDeviceMetricsOverride", JSONObject()) { error ->
            if (error == null) {
                viewportWidths.remove(tab.id)
                resetVisual(tab)
            }
            callback(error)
        }
    }

    /**
     * タブがbrowserContainerへ付け直された時や、DevToolsのdock幅が変わった時に呼ぶ。
     * CDP overrideはtarget側に残るので、ここではAndroid側の見た目だけ再構成する。
     */
    fun onLayoutChanged(tab: Tab) {
        if (tab.id in viewportWidths) {
            refreshVisual(tab)
        } else {
            resetVisual(tab)
        }
    }

    /**
     * 現在タブを一時的なdocument.titleで特定する。
     * URLが同じタブが複数あっても別タブへ誤接続しない。
     */
    fun resolveTarget(tab: Tab, done: (String?, String?) -> Unit) {
        discover(tab, done)
    }

    private fun execute(tab: Tab, method: String, params: JSONObject, callback: (String?) -> Unit) {
        if (closed || !busy.add(tab.id)) {
            callback("Another viewport operation is in progress.")
            return
        }

        val finish: (String?) -> Unit = { error ->
            busy.remove(tab.id)
            if (!closed) callback(error)
        }

        val existing = sessions[tab.id]
        if (existing != null) {
            existing.send(method, params, finish)
            return
        }

        discover(tab) { target, error ->
            if (closed || tab.id !in busy) return@discover
            if (target == null) {
                finish(error ?: "Could not identify this tab.")
            } else {
                val session = Session(tab.id)
                sessions[tab.id] = session
                session.connect(target, method, params, finish)
            }
        }
    }

    private fun refreshVisual(tab: Tab, callback: ((String?) -> Unit)? = null) {
        val width = viewportWidths[tab.id]
        if (width == null) {
            resetVisual(tab)
            callback?.invoke(null)
            return
        }

        val view = tab.webView
        val parent = view.parent as? ViewGroup
        if (parent == null) {
            callback?.invoke("Viewport preview requires the tab to be visible.")
            return
        }

        if (parent.width <= 0 || parent.height <= 0) {
            parent.post {
                if (closed) return@post
                refreshVisual(tab, callback)
            }
            return
        }

        view.evaluateJavascript("window.devicePixelRatio") { raw ->
            if (closed) return@evaluateJavascript

            val dpr = raw
                ?.trim()
                ?.trim('"')
                ?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?: view.resources.displayMetrics.density.toDouble()

            val layoutWidth = (width * dpr)
                .roundToInt()
                .coerceAtLeast(1)

            val parentWidth = parent.width.coerceAtLeast(1)
            val parentHeight = parent.height.coerceAtLeast(1)
            val visualScale = min(1f, parentWidth.toFloat() / layoutWidth.toFloat())
            val layoutHeight = ceil(parentHeight / visualScale.toDouble())
                .roundToInt()
                .coerceAtLeast(parentHeight)

            val params = FrameLayout.LayoutParams(
                layoutWidth,
                layoutHeight,
                Gravity.TOP or Gravity.START
            )
            view.layoutParams = params
            view.pivotX = 0f
            view.pivotY = 0f
            view.scaleX = visualScale
            view.scaleY = visualScale
            view.translationX = ((parentWidth - layoutWidth * visualScale) / 2f).coerceAtLeast(0f)
            view.translationY = 0f
            view.requestLayout()

            view.post {
                callback?.invoke(null)
            }
        }
    }

    private fun resetVisual(tab: Tab) {
        val view = tab.webView
        val params = view.layoutParams ?: return
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = ViewGroup.LayoutParams.MATCH_PARENT
        if (params is FrameLayout.LayoutParams) {
            params.gravity = Gravity.TOP or Gravity.START
        }
        view.layoutParams = params
        view.pivotX = 0f
        view.pivotY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.requestLayout()
    }

    private fun verifyWidth(tab: Tab, expected: Int, callback: (String?) -> Unit) {
        tab.webView.postDelayed({
            if (closed) return@postDelayed
            tab.webView.evaluateJavascript("window.innerWidth") { raw ->
                val actual = raw
                    ?.trim()
                    ?.trim('"')
                    ?.toDoubleOrNull()
                    ?.roundToInt()

                if (actual == expected) {
                    callback(null)
                } else {
                    callback(
                        "Viewport command completed, but window.innerWidth is " +
                            (actual?.toString() ?: "unknown") +
                            " instead of $expected."
                    )
                }
            }
        }, 120)
    }

    // ページURLが重複しても別タブを選ばないよう、一時的なタイトルで特定する。
    private fun discover(tab: Tab, done: (String?, String?) -> Unit) {
        if (closed) {
            done(null, "Viewport controller is closed.")
            return
        }
        if (discoveries.containsKey(tab.id)) {
            done(null, "CDP target discovery is already in progress.")
            return
        }

        val view = tab.webView
        val marker = "__viewport_" + UUID.randomUUID().toString()
        val quoted = JSONObject.quote(marker)
        val setup =
            "(function(){window[$quoted]=document.title;document.title=$quoted;return document.title===$quoted})()"
        val restore =
            "(function(){if(document.title===$quoted)document.title=window[$quoted]||'';delete window[$quoted]})()"

        var finished = false
        lateinit var timeout: Runnable

        fun finish(target: String?, error: String?, notify: Boolean = true) {
            if (finished) return
            finished = true
            main.removeCallbacks(timeout)
            discoveries.remove(tab.id)
            view.evaluateJavascript(restore, null)
            if (notify) done(target, error)
        }

        timeout = Runnable { finish(null, "CDP tab discovery timed out.") }
        discoveries[tab.id] = { finish(null, null, false) }
        main.postDelayed(timeout, 8000)

        view.evaluateJavascript(setup) { result ->
            if (finished) return@evaluateJavascript
            if (result != "true") {
                finish(null, "Could not identify this tab; check that JavaScript is enabled.")
                return@evaluateJavascript
            }

            main.postDelayed({
                if (finished) return@postDelayed
                val request = Request.Builder().url("http://127.0.0.1:$port/json").build()
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        main.post {
                            finish(null, "CDP discovery failed: ${e.message}")
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = try {
                            response.use {
                                check(it.isSuccessful) { "HTTP ${it.code}" }
                                val entries = JSONArray(it.body?.string() ?: "[]")
                                val matches = (0 until entries.length())
                                    .map { index -> entries.getJSONObject(index) }
                                    .filter { entry ->
                                        entry.optString("title") == marker &&
                                            entry.optString("type") == "page"
                                    }
                                check(matches.size == 1) {
                                    "Could not uniquely identify this tab"
                                }
                                matches.single().getString("id") to null
                            }
                        } catch (e: Exception) {
                            null to (e.message ?: "CDP discovery failed")
                        }

                        main.post {
                            finish(result.first, result.second)
                        }
                    }
                })
            }, 250)
        }
    }

    fun closeTab(tab: Tab) {
        discoveries[tab.id]?.invoke()
        busy.remove(tab.id)
        viewportWidths.remove(tab.id)
        sessions.remove(tab.id)?.close()
    }

    fun close() {
        closed = true
        discoveries.values.toList().forEach { it() }
        discoveries.clear()
        busy.clear()
        viewportWidths.clear()
        sessions.values.toList().forEach { it.close() }
        sessions.clear()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    private inner class Session(private val tabId: Int) : WebSocketListener() {
        private var socket: WebSocket? = null
        private var nextId = 0
        private var pending: ((String?) -> Unit)? = null
        private var pendingId = 0
        private var firstCommand: Pair<String, JSONObject>? = null
        private val timeout = Runnable { fail("CDP request timed out.") }

        fun connect(target: String, method: String, params: JSONObject, callback: (String?) -> Unit) {
            pending = callback
            firstCommand = method to params
            main.postDelayed(timeout, 8000)
            val request = Request.Builder().url("ws://127.0.0.1:$port/devtools/page/$target").build()
            socket = client.newWebSocket(request, this)
        }

        fun send(method: String, params: JSONObject, callback: (String?) -> Unit) {
            pending = callback
            main.postDelayed(timeout, 8000)
            sendCommand(method, params)
        }

        private fun sendCommand(method: String, params: JSONObject) {
            pendingId = ++nextId
            val message = JSONObject()
                .put("id", pendingId)
                .put("method", method)
                .put("params", params)
            if (socket?.send(message.toString()) != true) {
                fail("CDP connection is closed.")
            }
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            main.post {
                if (sessions[tabId] !== this) return@post
                firstCommand?.let { (method, params) -> sendCommand(method, params) }
                firstCommand = null
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            main.post {
                try {
                    val message = JSONObject(text)
                    if (message.optInt("id", -1) != pendingId || pending == null) {
                        return@post
                    }
                    val error = message.optJSONObject("error")
                        ?.optString("message", "CDP error")
                    complete(error)
                } catch (_: Exception) {
                    fail("Invalid CDP response.")
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            main.post { fail("CDP connection failed: ${t.message}") }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            main.post { fail("CDP connection closed.") }
        }

        private fun complete(error: String?) {
            main.removeCallbacks(timeout)
            val callback = pending
            pending = null
            callback?.invoke(error)
        }

        private fun fail(error: String) {
            if (sessions[tabId] !== this) return
            sessions.remove(tabId)
            socket?.cancel()
            complete(error)
        }

        fun close() {
            main.removeCallbacks(timeout)
            pending = null
            socket?.cancel()
        }
    }
}
