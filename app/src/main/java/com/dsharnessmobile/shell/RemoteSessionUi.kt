package com.dsharnessmobile.shell

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * Controller for:
 * 1. SSH VPS Connection Dialog (IP, Port, User, Password) with Auto-Token Extraction.
 * 2. Floating Zoom In / Zoom Out and Mobile/Desktop toggle toolbar overlay.
 */
class RemoteSessionUi(private val activity: MainActivity) {

  private var zoomLevel = 1.0f
  private var isDesktopMode = false
  private var controlBar: LinearLayout? = null

  private val originalUserAgent: String by lazy {
    activity.webView.settings.userAgentString
  }

  /**
   * Initializes the floating Zoom & Desktop/Mobile switcher toolbar over the WebView.
   */
  fun setupFloatingControls(root: ViewGroup) {
    if (controlBar != null) return

    val density = activity.resources.displayMetrics.density
    fun dp(px: Float): Int = (px * density).toInt()

    val bar = LinearLayout(activity).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      val bg = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = dp(20f).toFloat()
        setColor(Color.parseColor("#CC121513")) // Semitransparent dark capsule
        setStroke(dp(1f), Color.parseColor("#33FFFFFF"))
      }
      background = bg
      setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
      elevation = dp(8f).toFloat()
    }

    // Zoom Out Button (-)
    val btnZoomOut = createBarButton("-") {
      adjustZoom(-0.1f)
    }

    // Zoom Indicator Label (100%)
    val zoomLabel = TextView(activity).apply {
      text = "100%"
      setTextColor(Color.WHITE)
      textSize = 12f
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      setPadding(dp(4f), 0, dp(4f), 0)
      tag = "zoom_label"
    }

    // Zoom In Button (+)
    val btnZoomIn = createBarButton("+") {
      adjustZoom(0.1f)
    }

    // Reset Zoom Button
    val btnZoomReset = createBarButton("1:1") {
      resetZoom()
    }

    // Desktop/Mobile Mode Toggle Button
    val btnMode = createBarButton("💻") {
      toggleDesktopMode()
    }.apply {
      tag = "mode_button"
    }

    // SSH Dialog Button
    val btnSsh = createBarButton("⚡ SSH") {
      showSshDialog()
    }

    bar.addView(btnZoomOut)
    bar.addView(zoomLabel)
    bar.addView(btnZoomIn)
    bar.addView(btnZoomReset)
    bar.addView(btnMode)
    bar.addView(btnSsh)

    val lp = FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.WRAP_CONTENT,
      ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
      gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
      bottomMargin = dp(16f)
    }

    root.addView(bar, lp)
    controlBar = bar
  }

  private fun createBarButton(title: String, onClick: () -> Unit): TextView {
    val density = activity.resources.displayMetrics.density
    fun dp(px: Float): Int = (px * density).toInt()

    return TextView(activity).apply {
      text = title
      setTextColor(Color.WHITE)
      textSize = 12f
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      val bg = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = dp(14f).toFloat()
        setColor(Color.parseColor("#33FFFFFF"))
      }
      background = bg
      setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
      val marginLp = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
      ).apply {
        leftMargin = dp(3f)
        rightMargin = dp(3f)
      }
      layoutParams = marginLp
      setOnClickListener { onClick() }
    }
  }

  /**
   * Scales the entire webview viewport (ALL elements: layout, text, images, containers)
   * using CSS transform scale + dynamic viewport width.
   */
  fun adjustZoom(delta: Float) {
    val newZoom = (zoomLevel + delta).coerceIn(0.4f, 2.5f)
    applyZoom(newZoom)
  }

  fun resetZoom() {
    applyZoom(1.0f)
  }

  private fun applyZoom(scale: Float) {
    zoomLevel = (scale * 10f).toInt() / 10f // round to 1 decimal
    val percent = (zoomLevel * 100).toInt()

    val label = controlBar?.findViewWithTag<TextView>("zoom_label")
    label?.text = "$percent%"

    // Inject CSS transform scaling to scale ALL elements uniformly
    val js = """
      (function() {
        var style = document.getElementById('__dsh_zoom_style__');
        if (!style) {
          style = document.createElement('style');
          style.id = '__dsh_zoom_style__';
          document.head.appendChild(style);
        }
        style.innerHTML = 'html, body { transform-origin: top center; transform: scale(' + $zoomLevel + '); width: ' + (100 / $zoomLevel) + '% !important; height: ' + (100 / $zoomLevel) + '% !important; }';
      })();
    """.trimIndent()

    activity.runOnUiThread {
      activity.webView.evaluateJavascript(js, null)
    }
  }

  /**
   * Toggles between Desktop View and Mobile View.
   * Desktop Mode: Uses desktop Chrome User-Agent, enables wide viewport & overview mode.
   * Mobile Mode: Restores mobile User-Agent and normal responsive rendering.
   */
  fun toggleDesktopMode() {
    isDesktopMode = !isDesktopMode
    val modeBtn = controlBar?.findViewWithTag<TextView>("mode_button")

    val settings = activity.webView.settings
    if (isDesktopMode) {
      modeBtn?.text = "📱" // Button offers switch to Mobile
      // Desktop User Agent
      settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
      settings.useWideViewPort = true
      settings.loadWithOverviewMode = true

      // Inject desktop viewport meta tag
      val js = """
        (function() {
          var meta = document.querySelector('meta[name="viewport"]');
          if (meta) {
            meta.setAttribute('content', 'width=1280, initial-scale=0.8, user-scalable=yes');
          }
        })();
      """.trimIndent()
      activity.webView.evaluateJavascript(js, null)
      Toast.makeText(activity, "Desktop Mode", Toast.LENGTH_SHORT).show()
    } else {
      modeBtn?.text = "💻" // Button offers switch to Desktop
      settings.userAgentString = originalUserAgent
      settings.useWideViewPort = false
      settings.loadWithOverviewMode = false

      val js = """
        (function() {
          var meta = document.querySelector('meta[name="viewport"]');
          if (meta) {
            meta.setAttribute('content', 'width=device-width, initial-scale=1, maximum-scale=5, user-scalable=yes');
          }
        })();
      """.trimIndent()
      activity.webView.evaluateJavascript(js, null)
      Toast.makeText(activity, "Mobile Mode", Toast.LENGTH_SHORT).show()
    }

    // Refresh display
    applyZoom(zoomLevel)
  }

  /**
   * Displays SSH VPS Dialog:
   * Input IP, Port (default 22), Username (default root), and Password.
   * On submit: Establishes SSH tunnel to VPS, auto-extracts DSH token,
   * injects cookie into WebView, and opens Dashboard directly!
   */
  fun showSshDialog() {
    val density = activity.resources.displayMetrics.density
    fun dp(px: Float): Int = (px * density).toInt()

    val (savedHost, savedUser, savedPass) = SshTunnelManager.loadConfig(activity)

    val layout = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(20f), dp(16f), dp(20f), dp(16f))
    }

    val etHost = EditText(activity).apply {
      hint = activity.getString(R.string.ds_vps_ip)
      setText(savedHost)
      setSingleLine(true)
    }

    val etPort = EditText(activity).apply {
      hint = activity.getString(R.string.ds_vps_port) + " (default: 22)"
      setText("22")
      inputType = android.text.InputType.TYPE_CLASS_NUMBER
      setSingleLine(true)
    }

    val etUser = EditText(activity).apply {
      hint = activity.getString(R.string.ds_vps_user)
      setText(savedUser)
      setSingleLine(true)
    }

    val etPass = EditText(activity).apply {
      hint = activity.getString(R.string.ds_vps_pass)
      setText(savedPass)
      inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
      setSingleLine(true)
    }

    val statusText = TextView(activity).apply {
      textSize = 12f
      setTextColor(activity.getColor(R.color.ds_text_secondary))
      setPadding(0, dp(8f), 0, dp(8f))
    }

    val progressBar = ProgressBar(activity).apply {
      isIndeterminate = true
      visibility = View.GONE
    }

    layout.addView(etHost)
    layout.addView(etPort)
    layout.addView(etUser)
    layout.addView(etPass)
    layout.addView(progressBar)
    layout.addView(statusText)

    val dialog = AlertDialog.Builder(activity)
      .setTitle(activity.getString(R.string.ds_connect_vps))
      .setView(layout)
      .setPositiveButton(activity.getString(R.string.ds_vps_connect_btn), null)
      .setNegativeButton(android.R.string.cancel, null)
      .create()

    dialog.show()

    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
      val host = etHost.text.toString().trim()
      val port = etPort.text.toString().trim().toIntOrNull() ?: 22
      val user = etUser.text.toString().trim()
      val pass = etPass.text.toString()

      if (host.isEmpty()) {
        etHost.error = "IP/Host wajib diisi"
        return@setOnClickListener
      }

      progressBar.visibility = View.VISIBLE
      statusText.text = activity.getString(R.string.ds_vps_connecting)
      etHost.isEnabled = false
      etPort.isEnabled = false
      etUser.isEnabled = false
      etPass.isEnabled = false

      SshTunnelManager.connect(activity, host, port, user, pass) { success, token, errorMsg ->
        progressBar.visibility = View.GONE
        etHost.isEnabled = true
        etPort.isEnabled = true
        etUser.isEnabled = true
        etPass.isEnabled = true

        if (success) {
          dialog.dismiss()
          Toast.makeText(activity, "SSH Terhubung! Membuka DSH VPS...", Toast.LENGTH_SHORT).show()

          // Launch DSH WebView directly with the token or active tunnel
          openRemoteDashboard(token)
        } else {
          statusText.setTextColor(activity.getColor(R.color.ds_danger))
          statusText.text = "Gagal: $errorMsg"
        }
      }
    }
  }

  /**
   * Injects token if available and opens the DSH web interface.
   * Auto syncs chat and sessions in real-time from VPS.
   */
  fun openRemoteDashboard(token: String?) {
    activity.runOnUiThread {
      val targetUrl = if (!token.isNullOrBlank()) {
        "${EngineProbe.ENGINE_URL}/?token=$token"
      } else {
        EngineProbe.ENGINE_URL
      }

      // If token is found, cookie can also be set directly
      if (!token.isNullOrBlank()) {
        try {
          CookieManager.getInstance().setCookie(EngineProbe.ENGINE_URL, "dsh-token=$token")
        } catch (_: Exception) {}
      }

      activity.showWeb()
      activity.webView.loadUrl(targetUrl)
    }
  }
}
