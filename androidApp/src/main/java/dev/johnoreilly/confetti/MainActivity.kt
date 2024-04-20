@file:OptIn(ExperimentalMaterial3WindowSizeClassApi::class)

package dev.johnoreilly.confetti

import android.app.Activity
import android.app.TaskStackBuilder
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.os.bundleOf
import androidx.core.view.WindowCompat
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.arkivanov.decompose.defaultComponentContext
import dev.johnoreilly.confetti.account.signIn
import dev.johnoreilly.confetti.auth.Authentication
import dev.johnoreilly.confetti.decompose.DefaultAppComponent
import dev.johnoreilly.confetti.decompose.SettingsComponent
import dev.johnoreilly.confetti.ui.ConfettiApp
import dev.johnoreilly.confetti.ui.ConfettiTheme
import dev.johnoreilly.confetti.ui.component.ConfettiBackground
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

fun <T : Any> ComponentActivity.withDeepLink(block: (deepLink: Uri?) -> T): T? {
    if (restartIfNeeded()) {
        return null
    }

    val savedState: Bundle? = savedStateRegistry.consumeRestoredStateForKey(key = KEY_SAVED_DEEP_LINK_STATE)
    val lastDeepLink = savedState?.getParcelable(KEY_LAST_DEEP_LINK) as Uri?
    val deepLink = intent.data

    savedStateRegistry.registerSavedStateProvider(key = KEY_SAVED_DEEP_LINK_STATE) {
        bundleOf(KEY_LAST_DEEP_LINK to deepLink)
    }

    return block(deepLink.takeUnless { it == lastDeepLink })
}

// Derived from https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:navigation/navigation-runtime/src/main/java/androidx/navigation/NavController.kt;l=1487-1504?q=Intent.flags&ss=androidx%2Fplatform%2Fframeworks%2Fsupport:navigation%2F
private fun Activity.restartIfNeeded(): Boolean {
    if ((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK == 0) || (intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)) {
        return false
    }

    // Someone called us with NEW_TASK, but we don't know what state our whole
    // task stack is in, so we need to manually restart the whole stack to
    // ensure we're in a predictably good state.

    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
    TaskStackBuilder.create(this).addNextIntentWithParentStack(intent).startActivities()
    finish()
    // Disable second animation in case where the Activity is created twice.
    @Suppress("DEPRECATION")
    overridePendingTransition(0, 0)

    return true
}

private const val KEY_SAVED_DEEP_LINK_STATE = "SAVED_DEEP_LINK_STATE"
private const val KEY_LAST_DEEP_LINK = "LAST_DEEP_LINK"

class MainActivity : ComponentActivity() {

    private var isDeepLinkHandledPreviously = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settingsComponent: SettingsComponent by inject()
        var userEditableSettings by mutableStateOf<UserEditableSettings?>(null)
        val credentialManager: CredentialManager by inject()
        val authentication: Authentication by inject()

        // Update the theme settings
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsComponent.userEditableSettings.collect {
                    userEditableSettings = it
                }
            }
        }


        // Turn off the decor fitting system windows, which allows us to handle insets,
        // including IME animations
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val appComponent =
            withDeepLink { deepLink: Uri? ->
                val conferenceId = deepLink?.extractConferenceIdOrNull()

                DefaultAppComponent(
                    componentContext = defaultComponentContext(
                        discardSavedState = conferenceId != null,
                    ),
                    initialConferenceId = conferenceId,
                    onSignOut = {
                        lifecycleScope.launch {
                            credentialManager.clearCredentialState(ClearCredentialStateRequest())
                        }
                    },
                    onSignIn = {
                        lifecycleScope.launch {
                            signIn(this@MainActivity, authentication)
                        }
                    }
                )
            } ?: return

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)

            ConfettiTheme(
                darkTheme = shouldUseDarkTheme(userEditableSettings?.darkThemeConfig),
                androidTheme = shouldUseAndroidTheme(userEditableSettings?.brand),
                disableDynamicTheming = shouldDisableDynamicTheming(userEditableSettings?.useDynamicColor)
            ) {
                ConfettiBackground {
                    ConfettiApp(
                        component = appComponent,
                        windowSizeClass = windowSizeClass,
                    )
                }
            }
        }
    }

    /**
     * From a deep link like `https://confetti-app.dev/conference/devfeststockholm2023` extracts `devfeststockholm2023`
     * In case we were asked to create a new task with [Intent.FLAG_ACTIVITY_NEW_TASK] it clears the entire activity
     * and starts a new one to be in a predictably good state
     */
    private fun Uri.extractConferenceIdOrNull(): String? {
        if (host != "confetti-app.dev") return null
        val path = path ?: return null
        if (path.firstOrNull() != '/') return null
        val parts = path.substring(1).split('/')
        if (parts.size != 2) return null
        if (parts[0] != "conference") return null
        val conferenceId = parts[1]
        if (!conferenceId.all { it.isLetterOrDigit() }) return null
        return conferenceId
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_DEEP_LINK_HANDLED, isDeepLinkHandledPreviously)
    }

    companion object {
        const val KEY_DEEP_LINK_HANDLED: String = "dev.johnoreilly.confetti.KEY_DEEP_LINK_HANDLED"
    }
}

@Composable
private fun shouldUseDarkTheme(
    darkThemeConfig: DarkThemeConfig?,
): Boolean = when (darkThemeConfig) {
    DarkThemeConfig.FOLLOW_SYSTEM, null -> isSystemInDarkTheme()
    DarkThemeConfig.LIGHT -> false
    DarkThemeConfig.DARK -> true
}

@Composable
private fun shouldUseAndroidTheme(
    themeBrand: ThemeBrand?,
): Boolean = when (themeBrand) {
    ThemeBrand.DEFAULT, null -> false
    ThemeBrand.ANDROID -> true
}

@Composable
private fun shouldDisableDynamicTheming(
    useDynamicColor: Boolean?
): Boolean = useDynamicColor?.not() ?: true

