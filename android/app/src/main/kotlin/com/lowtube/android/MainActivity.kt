package com.lowtube.android

import android.os.Bundle
import android.os.Build
import android.content.res.Configuration
import android.app.PictureInPictureParams
import android.util.Rational
import android.content.Context
import android.content.Intent
import android.accounts.Account
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.lowtube.android.data.YouTubeDataApiRepository
import com.lowtube.android.model.YouTubeSubscription
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.lowtube.android.ui.theme.LowTubeTheme

class MainActivity : ComponentActivity() {
  private var darkTheme by mutableStateOf(true)
  private var externalYoutubeUrl by mutableStateOf<String?>(null)
  private var externalYoutubeRequestId by mutableIntStateOf(0)
  private var navigationBackHandler: (() -> Boolean)? = null
  private var googleAccountName by mutableStateOf("")
  private var googleAccountEmail by mutableStateOf("")
  private var googleAccountMessage by mutableStateOf("")
  private var googleSignInBusy by mutableStateOf(false)
  private var youtubeSyncBusy by mutableStateOf(false)
  private var pictureInPictureEligible = false
  private var inPictureInPictureMode by mutableStateOf(false)
  private var pendingYoutubeSync: ((Result<List<YouTubeSubscription>>) -> Unit)? = null
  private var pendingYoutubeAccountEmail = ""
  private var youtubeSyncGeneration = 0
  private val youtubeDataApi = YouTubeDataApiRepository()
  private val youtubeAuthorizationLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
    val completion = pendingYoutubeSync
    pendingYoutubeSync = null
    if (completion == null) return@registerForActivityResult
    if (result.resultCode != RESULT_OK) {
      youtubeSyncBusy = false
      completion(Result.failure(IllegalStateException("YouTube permission request was cancelled.")))
      return@registerForActivityResult
    }
    try {
      val data = result.data ?: throw IllegalStateException("YouTube authorization returned no result.")
      val authorization = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data)
      useYoutubeAuthorization(authorization, completion)
    } catch (_: Exception) {
      youtubeSyncBusy = false
      completion(Result.failure(IllegalStateException("YouTube authorization failed. Try syncing again.")))
    }
  }

  override fun attachBaseContext(newBase: Context) {
    super.attachBaseContext(AppLocale.wrap(newBase))
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    acceptExternalYoutubeIntent(intent)
    val preferences = getSharedPreferences("lowtube_settings", MODE_PRIVATE)
    darkTheme = preferences.getBoolean("dark_theme", true)
    googleAccountName = preferences.getString("google_account_name", "").orEmpty()
    googleAccountEmail = preferences.getString("google_account_email", "").orEmpty()

    enableEdgeToEdge()
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        if (navigationBackHandler?.invoke() != true) {
          isEnabled = false
          onBackPressedDispatcher.onBackPressed()
          isEnabled = true
        }
      }
    })
    setContent {
      SideEffect {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !darkTheme
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
      }
      LowTubeTheme(darkTheme = darkTheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          MainNavigation(
            googleAccountName = googleAccountName,
            googleAccountEmail = googleAccountEmail,
            googleAccountMessage = googleAccountMessage,
            googleSignInBusy = googleSignInBusy,
            youtubeSyncBusy = youtubeSyncBusy,
            onGoogleSignIn = ::signInWithGoogle,
            onGoogleSignOut = ::signOutGoogle,
            onRequestYouTubeSync = ::syncYouTubeSubscriptions,
            onDarkThemeChanged = { enabled ->
              darkTheme = enabled
              getSharedPreferences("lowtube_settings", MODE_PRIVATE).edit().putBoolean("dark_theme", enabled).apply()
            },
            onLanguageChanged = { language ->
              AppLocale.setLanguage(this, language)
              recreate()
            },
            onBackHandlerChanged = { handler -> navigationBackHandler = handler },
            inPictureInPictureMode = inPictureInPictureMode,
            onPictureInPictureEligibilityChanged = ::setPictureInPictureEligibility,
            externalYoutubeUrl = externalYoutubeUrl,
            externalYoutubeRequestId = externalYoutubeRequestId,
            onExternalYoutubeUrlConsumed = { requestId ->
              if (requestId == externalYoutubeRequestId) externalYoutubeUrl = null
            },
          )
        }
      }
    }
  }

  override fun onNewIntent(newIntent: Intent) {
    super.onNewIntent(newIntent)
    setIntent(newIntent)
    acceptExternalYoutubeIntent(newIntent)
  }

  override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && pictureInPictureEligible) {
      enterPictureInPictureMode(pictureInPictureParams())
    }
  }

  override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    inPictureInPictureMode = isInPictureInPictureMode
  }

  private fun setPictureInPictureEligibility(eligible: Boolean) {
    if (pictureInPictureEligible == eligible || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    pictureInPictureEligible = eligible
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      setPictureInPictureParams(
        PictureInPictureParams.Builder()
          .setAspectRatio(Rational(16, 9))
          .setAutoEnterEnabled(eligible)
          .build(),
      )
    }
  }

  private fun pictureInPictureParams(): PictureInPictureParams =
    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()

  internal fun acceptExternalYoutubeIntent(incoming: Intent?) {
    if (incoming == null) return
    val candidates = when (incoming.action) {
      Intent.ACTION_VIEW -> listOfNotNull(incoming.data)
      Intent.ACTION_SEND -> {
        if (incoming.type?.substringBefore(';') != "text/plain") return
        val sharedText = incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
          ?.take(MAX_SHARED_TEXT_LENGTH) ?: return
        SHARED_URL.findAll(sharedText).take(MAX_SHARED_URLS).mapNotNull { match ->
          val value = match.value.trimEnd('.', ',', ';', '!', ')', ']', '}', '"', '\'')
          runCatching { Uri.parse(value) }.getOrNull()
        }.toList()
      }
      else -> return
    }
    val accepted = candidates.firstNotNullOfOrNull { uri ->
      val scheme = uri.scheme?.lowercase() ?: return@firstNotNullOfOrNull null
      val host = uri.host?.lowercase() ?: return@firstNotNullOfOrNull null
      if (scheme !in setOf("https", "http")) return@firstNotNullOfOrNull null
      if (host !in YOUTUBE_LINK_HOSTS || uri.userInfo != null) return@firstNotNullOfOrNull null
      val authority = uri.authority?.lowercase() ?: return@firstNotNullOfOrNull null
      val defaultPort = if (scheme == "https") 443 else 80
      if (authority != host && authority != "$host:$defaultPort") return@firstNotNullOfOrNull null
      if (!isSupportedYoutubeLink(uri, host)) return@firstNotNullOfOrNull null
      uri.buildUpon().scheme("https").encodedAuthority(host).build().toString()
    } ?: return

    externalYoutubeUrl = accepted
    externalYoutubeRequestId++
  }

  private fun isSupportedYoutubeLink(uri: Uri, host: String): Boolean {
    val segments = uri.pathSegments
    val videoId = when {
      host == "youtu.be" -> uri.lastPathSegment
      uri.path == "/watch" -> uri.getQueryParameter("v")
      segments.size >= 2 && segments[0] in setOf("shorts", "live", "embed") -> segments[1]
      else -> null
    }
    if (videoId != null) return VIDEO_ID.matches(videoId)

    if (uri.path == "/playlist") {
      return PLAYLIST_ID.matches(uri.getQueryParameter("list").orEmpty())
    }
    return when {
      segments.size >= 2 && segments[0] == "channel" -> CHANNEL_ID.matches(segments[1])
      segments.size >= 2 && segments[0] in setOf("c", "user") -> segments[1].isNotBlank()
      segments.firstOrNull()?.startsWith("@") == true -> segments[0].length > 1
      else -> false
    }
  }

  private fun signInWithGoogle() {
    val webClientId = getString(R.string.google_web_client_id).trim()
    if (webClientId.isEmpty()) {
      googleAccountMessage = getString(R.string.account_oauth_not_configured)
      return
    }
    if (googleSignInBusy) return

    googleSignInBusy = true
    googleAccountMessage = ""
    lifecycleScope.launch {
      try {
        val option = GetSignInWithGoogleOption.Builder(webClientId).build()
        val request = GetCredentialRequest.Builder()
          .addCredentialOption(option)
          .build()
        val response = CredentialManager.create(this@MainActivity).getCredential(this@MainActivity, request)
        val credential = response.credential
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
          throw IllegalStateException("Unexpected Google credential type")
        }

        // Keep only display fields locally. The ID token is intentionally not persisted or logged.
        val account = GoogleIdTokenCredential.createFrom(credential.data)
        if (googleAccountEmail.isNotBlank() && !googleAccountEmail.equals(account.id, ignoreCase = true)) {
          youtubeSyncGeneration++
          pendingYoutubeSync = null
          pendingYoutubeAccountEmail = ""
          youtubeSyncBusy = false
        }
        googleAccountName = account.displayName.orEmpty()
        googleAccountEmail = account.id
        getSharedPreferences("lowtube_settings", MODE_PRIVATE).edit()
          .putString("google_account_name", googleAccountName)
          .putString("google_account_email", googleAccountEmail)
          .apply()
        googleAccountMessage = getString(R.string.account_profile_local_note)
      } catch (cancelled: GetCredentialCancellationException) {
        googleAccountMessage = getString(R.string.account_sign_in_cancelled)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: GetCredentialException) {
        googleAccountMessage = getString(R.string.account_sign_in_failed)
      } catch (_: GoogleIdTokenParsingException) {
        googleAccountMessage = getString(R.string.account_sign_in_failed)
      } catch (_: Exception) {
        googleAccountMessage = getString(R.string.account_sign_in_failed)
      } finally {
        googleSignInBusy = false
      }
    }
  }

  private fun signOutGoogle() {
    val email = googleAccountEmail
    googleAccountName = ""
    googleAccountEmail = ""
    googleAccountMessage = ""
    youtubeSyncBusy = false
    youtubeSyncGeneration++
    pendingYoutubeSync = null
    pendingYoutubeAccountEmail = ""
    getSharedPreferences("lowtube_settings", MODE_PRIVATE).edit()
      .remove("google_account_name")
      .remove("google_account_email")
      .apply()
    if (email.isNotBlank()) {
      val request = AuthorizationRequest.builder()
        .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
        .setRequestedScopes(listOf(Scope(YOUTUBE_READONLY_SCOPE)))
        .build()
      Identity.getAuthorizationClient(this).authorize(request)
        .addOnSuccessListener { authorization ->
          val token = authorization.accessToken.orEmpty()
          if (!authorization.hasResolution() && token.isNotBlank()) {
            lifecycleScope.launch(Dispatchers.IO) { runCatching { youtubeDataApi.revokeAccess(token) } }
          }
        }
    }
    val manager = CredentialManager.create(this)
    lifecycleScope.launch {
      runCatching { manager.clearCredentialState(ClearCredentialStateRequest()) }
    }
  }

  private fun syncYouTubeSubscriptions(completion: (Result<List<YouTubeSubscription>>) -> Unit) {
    val email = googleAccountEmail
    if (email.isBlank()) {
      completion(Result.failure(IllegalStateException("Sign in with Google before syncing YouTube subscriptions.")))
      return
    }
    if (youtubeSyncBusy) return
    youtubeSyncBusy = true
    val generation = ++youtubeSyncGeneration
    pendingYoutubeAccountEmail = email
    val guardedCompletion: (Result<List<YouTubeSubscription>>) -> Unit = { result ->
      if (generation == youtubeSyncGeneration) {
        youtubeSyncBusy = false
        pendingYoutubeAccountEmail = ""
        if (googleAccountEmail.equals(email, ignoreCase = true)) completion(result)
        else completion(Result.failure(IllegalStateException("The Google account changed before sync finished.")))
      }
    }
    val request = AuthorizationRequest.builder()
      .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
      .setRequestedScopes(listOf(Scope(YOUTUBE_READONLY_SCOPE)))
      .build()
    Identity.getAuthorizationClient(this).authorize(request)
      .addOnSuccessListener { authorization ->
        if (generation == youtubeSyncGeneration && googleAccountEmail.equals(email, ignoreCase = true)) {
          useYoutubeAuthorization(authorization, guardedCompletion)
        }
      }
      .addOnFailureListener {
        guardedCompletion(Result.failure(IllegalStateException("Google could not authorize YouTube access. Try again.")))
      }
  }

  private fun useYoutubeAuthorization(
    authorization: AuthorizationResult,
    completion: (Result<List<YouTubeSubscription>>) -> Unit,
  ) {
    if (pendingYoutubeAccountEmail.isBlank() || !googleAccountEmail.equals(pendingYoutubeAccountEmail, ignoreCase = true)) {
      completion(Result.failure(IllegalStateException("The Google account changed before sync finished.")))
      return
    }
    if (authorization.hasResolution()) {
      val pendingIntent = authorization.pendingIntent
      if (pendingIntent == null) {
        youtubeSyncBusy = false
        completion(Result.failure(IllegalStateException("Google did not return a YouTube permission request.")))
        return
      }
      pendingYoutubeSync = completion
      try {
        youtubeAuthorizationLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
      } catch (_: Exception) {
        pendingYoutubeSync = null
        youtubeSyncBusy = false
        completion(Result.failure(IllegalStateException("Could not open the YouTube permission request.")))
      }
      return
    }

    val accessToken = authorization.accessToken.orEmpty()
    if (accessToken.isBlank()) {
      youtubeSyncBusy = false
      completion(Result.failure(IllegalStateException("Google returned no YouTube access token.")))
      return
    }
    lifecycleScope.launch {
      val result = try {
        Result.success(withContext(Dispatchers.IO) { youtubeDataApi.subscriptions(accessToken) })
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        Result.failure(failure)
      } finally {
        youtubeSyncBusy = false
      }
      completion(result)
    }
  }

  companion object {
    private const val YOUTUBE_READONLY_SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
    private const val GOOGLE_ACCOUNT_TYPE = "com.google"
    private val YOUTUBE_LINK_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val PLAYLIST_ID = Regex("^[A-Za-z0-9_-]{10,200}$")
    private val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")
    private val SHARED_URL = Regex("""https?://[^\s<>\"']{1,2048}""", RegexOption.IGNORE_CASE)
    private const val MAX_SHARED_TEXT_LENGTH = 16 * 1024
    private const val MAX_SHARED_URLS = 8
  }
}
