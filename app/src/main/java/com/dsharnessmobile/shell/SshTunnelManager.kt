package com.dsharnessmobile.shell

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Properties

/**
 * Manages SSH Connection and Port Forwarding to the remote DSH VPS.
 * Provides transparent local tunnel (127.0.0.1:3080 -> VPS:3080)
 * and retrieves active DSH authentication tokens automatically without pairing.
 */
object SshTunnelManager {

  private const val TAG = "dsh-ssh"
  private const val PREFS = "dsh_vps_ssh"
  private const val KEY_HOST = "ssh_host"
  private const val KEY_PORT = "ssh_port"
  private const val KEY_USER = "ssh_user"
  private const val KEY_PASS = "ssh_pass"

  private var jschSession: Session? = null
  private val mainHandler = Handler(Looper.getMainLooper())

  @Volatile
  var isConnected = false
    private set

  @Volatile
  var lastError: String? = null
    private set

  fun loadConfig(context: Context): Triple<String, String, String> {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val host = prefs.getString(KEY_HOST, "") ?: ""
    val user = prefs.getString(KEY_USER, "root") ?: "root"
    val pass = prefs.getString(KEY_PASS, "") ?: ""
    return Triple(host, user, pass)
  }

  fun saveConfig(context: Context, host: String, user: String, pass: String) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    prefs.edit()
      .putString(KEY_HOST, host.trim())
      .putString(KEY_USER, user.trim())
      .putString(KEY_PASS, pass)
      .apply()
  }

  /**
   * Connects SSH in background thread, opens local port forward 3080 -> 127.0.0.1:3080,
   * queries the active token from remote DSH instance, and invokes onResult.
   */
  fun connect(
    context: Context,
    host: String,
    port: Int = 22,
    user: String,
    pass: String,
    onResult: (success: Boolean, token: String?, errorMsg: String?) -> Unit
  ) {
    disconnect()
    lastError = null

    Thread {
      try {
        saveConfig(context, host, user, pass)
        val jsch = JSch()
        val session = jsch.getSession(user, host, port)
        session.setPassword(pass)

        val config = Properties()
        config["StrictHostKeyChecking"] = "no"
        config["PreferredAuthentications"] = "password,keyboard-interactive"
        session.setConfig(config)
        session.timeout = 15000

        Log.i(TAG, "Connecting SSH to $host:$port as $user...")
        session.connect(15000)

        // Setup local port forwarding 3080 -> VPS 127.0.0.1:3080
        val localPort = 3080
        val remoteHost = "127.0.0.1"
        val remotePort = 3080

        try {
          session.delPortForwardingL(localPort)
        } catch (_: Exception) {}

        session.setPortForwardingL(localPort, remoteHost, remotePort)
        jschSession = session
        isConnected = true
        Log.i(TAG, "SSH Tunnel connected and forwarded port $localPort -> $remoteHost:$remotePort")

        // Try reading DSH token from remote VPS
        val token = fetchRemoteDshToken(session)
        Log.i(TAG, "Remote DSH token fetched: ${if (token != null) "Found" else "None"}")

        mainHandler.post {
          onResult(true, token, null)
        }
      } catch (e: Exception) {
        Log.e(TAG, "SSH connection error: ${e.message}", e)
        disconnect()
        lastError = e.message ?: "SSH connection failed"
        mainHandler.post {
          onResult(false, null, lastError)
        }
      }
    }.apply { isDaemon = true; name = "ssh-tunnel-worker" }.start()
  }

  /**
   * Remote command execution to find active DSH token on VPS:
   * 1. Inspect ~/.dsh/engine.log or ~/.dsh/logs
   * 2. Inspect ~/.dsh/.credentials.yaml
   */
  private fun fetchRemoteDshToken(session: Session): String? {
    val cmd = """
      if [ -f ~/.dsh/engine.log ]; then
        grep -oE 'token=[a-zA-Z0-9_-]+' ~/.dsh/engine.log | tail -n 1 | cut -d= -f2
      elif [ -f ~/.dsh/.credentials.yaml ]; then
        grep -E 'secret:' ~/.dsh/.credentials.yaml | head -n 1 | awk '{print ${'$'}2}'
      fi
    """.trimIndent()

    return try {
      val channel = session.openChannel("exec") as ChannelExec
      channel.setCommand(cmd)
      val outStream = ByteArrayOutputStream()
      channel.outputStream = outStream
      channel.connect(5000)

      val start = System.currentTimeMillis()
      while (!channel.isClosed && System.currentTimeMillis() - start < 5000) {
        Thread.sleep(100)
      }
      channel.disconnect()
      val output = outStream.toString(StandardCharsets.UTF_8.name()).trim()
      output.ifEmpty { null }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to exec remote token query: ${e.message}")
      null
    }
  }

  fun disconnect() {
    try {
      jschSession?.delPortForwardingL(3080)
    } catch (_: Exception) {}
    try {
      jschSession?.disconnect()
    } catch (_: Exception) {}
    jschSession = null
    isConnected = false
  }
}
