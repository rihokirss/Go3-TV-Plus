package ee.local.go3tvplus.tclredirect

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

class SetupActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var startButton: Button
    private lateinit var restoreButton: Button
    private val profile by lazy { RedirectProfile.current() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        setContentView(createContent())

        val configured = AdbRedirectManager(this).isConfigured
        status.text = if (configured) {
            getString(R.string.status_configured)
        } else {
            getString(R.string.status_ready)
        }
        startButton.requestFocus()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun createContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(112), dp(72), dp(112), dp(72))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(4, 19, 39), Color.rgb(10, 53, 96)),
            )
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.brand_name)
            textSize = 20f
            setTextColor(Color.rgb(98, 168, 255))
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = profile.title
            textSize = 38f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10), 0, 0)
        })
        root.addView(TextView(this).apply {
            text = profile.description
            textSize = 19f
            setTextColor(Color.rgb(203, 220, 239))
            setPadding(0, dp(14), 0, 0)
        })

        status = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = roundedBackground(Color.rgb(9, 35, 64), dp(14).toFloat())
        }
        root.addView(
            status,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(32)
            },
        )

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        startButton = actionButton(getString(R.string.action_setup)).apply {
            setOnClickListener { startRedirect() }
        }
        restoreButton = actionButton(getString(R.string.action_restore)).apply {
            text = getString(R.string.action_restore, profile.sourceButtonName)
            setOnClickListener { restoreRedirect() }
        }
        buttons.addView(startButton)
        buttons.addView(
            restoreButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(58)).apply {
                marginStart = dp(16)
            },
        )
        root.addView(
            buttons,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(24)
            },
        )
        return root
    }

    private fun actionButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 17f
        isAllCaps = false
        minHeight = dp(58)
        setTextColor(Color.WHITE)
        setPadding(dp(26), 0, dp(26), 0)
        backgroundTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(Color.rgb(22, 117, 209), Color.rgb(11, 49, 92)),
        )
    }

    private fun startRedirect() {
        setBusy(true, getString(R.string.status_connecting))
        executor.execute {
            val result = runCatching { AdbRedirectManager(this).ensureRedirect(90) }
            runOnUiThread {
                result.onSuccess {
                    RedirectScheduler.schedule(this)
                    status.text = getString(R.string.status_running, profile.sourceButtonName)
                }.onFailure {
                    status.text = getString(R.string.status_setup_failed, it.message ?: it.javaClass.simpleName)
                }
                setBusy(false)
            }
        }
    }

    private fun restoreRedirect() {
        setBusy(true, getString(R.string.status_restoring, profile.deviceName, profile.sourceButtonName))
        executor.execute {
            val result = runCatching { AdbRedirectManager(this).restoreOriginalButton(20) }
            runOnUiThread {
                result.onSuccess {
                    RedirectScheduler.cancel(this)
                    status.text = getString(R.string.status_restored, profile.deviceName, profile.sourceButtonName)
                }.onFailure {
                    status.text = getString(R.string.status_restore_failed, it.message ?: it.javaClass.simpleName)
                }
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean, message: String? = null) {
        startButton.isEnabled = !busy
        restoreButton.isEnabled = !busy
        if (message != null) status.text = message
    }

    private fun roundedBackground(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
