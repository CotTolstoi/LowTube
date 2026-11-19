package com.lowtube.android

import android.os.Bundle
import android.os.Build
import android.content.res.Configuration
import android.app.PictureInPictureParams
import android.util.Rational
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.accounts.Account
import android.accounts.AccountManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.lowtube.android.data.GoogleAccountProfile
import com.lowtube.android.data.GoogleAccountMessageStore
import com.lowtube.android.data.GoogleAuthorizationDiagnostics
import com.lowtube.android.data.GoogleProfileAvatarPolicy
import com.lowtube.android.data.YouTubeDataApiRepository
import com.lowtube.android.model.YouTubeSubscription
import com.lowtube.android.model.YouTubePlaylist
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
  private data class YoutubeSubscriptionChange(
    val accountEmail: String,
    val channelId: String,
    val subscribe: Boolean,
    val generation: Int,
    val completion: (Result<Unit>) -> Unit,
  )

  private var darkTheme by mutableStateOf(true)
  private var externalYoutubeUrl by mutableStateOf<String?>(null)
  private var externalYoutubeRequestId by mutableIntStateOf(0)
  private var navigationBackHandler: (() -> Boolean)? = null
  private var googleAccountName by mutableStateOf("")
  private var googleAccountEmail by mutableStateOf("")
  private var googleAccountAvatarUrl by mutableStateOf("")
  private var googleAccountMessage by mutableStateOf("")
  private var googleAccountMessageStore: GoogleAccountMessageStore? = null
  private var googleSignInBusy by mutableStateOf(false)
  private var youtubeSyncBusy by mutableStateOf(false)
  private var pictureInPictureEligible = false
  private var inPictureInPictureMode by mutableStateOf(false)
  private var pendingYoutubeSync: ((Result<List<YouTubeSubscription>>) -> Unit)? = null
  private var pendingYoutubeReadAuthorization: ((Result<AuthorizationResult>) -> Unit)? = null
  private var pendingYoutubeSubscriptionChange: YoutubeSubscriptionChange? = null
  private var pendingGoogleProfileSignIn = false
  private var pendingYoutubeAccountEmail = ""
  private var youtubeSyncGeneration = 0
  private val youtubeDataApi = YouTubeDataApiRepository()
  private val googleAccountSelectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    if (result.resultCode != RESULT_OK) {
      googleSignInBusy = false
      updateGoogleAccountMessage(googleAccountSelectionCancelledMessage(result.resultCode))
      return@registerForActivityResult
    }
    val email = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME).orEmpty().trim()
    if (email.isBlank() || email.length > 320 || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
      googleSignInBusy = false
      updateGoogleAccountMessage(getString(R.string.account_sign_in_failed))
      return@registerForActivityResult
    }
    requestGoogleProfileAuthorization(email)
  }
  private val youtubeAuthorizationLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
    if (pendingGoogleProfileSignIn) {
      pendingGoogleProfileSignIn = false
      if (result.resultCode != RESULT_OK) {
        val authorizationFailure = result.data?.let { data ->
          runCatching { Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data) }
        }
        val authorization = authorizationFailure?.getOrNull()
        if (authorization?.accessToken?.isNotBlank() == true) {
          useGoogleProfileAuthorization(authorization)
          return@registerForActivityResult
        }
        googleSignInBusy = false
        val failure = authorizationFailure?.exceptionOrNull()
        updateGoogleAccountMessage(if (failure != null &&
          (failure !is ApiException || failure.statusCode != CommonStatusCodes.CANCELED)
        ) accountAuthorizationError(failure) else googleAuthorizationCancelledMessage(failure))
        return@registerForActivityResult
      }
      try {
        val data = result.data ?: throw IllegalStateException("Google authorization returned no result.")
        val authorization = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data)
        useGoogleProfileAuthorization(authorization)
      } catch (failure: Exception) {
        googleSignInBusy = false
        updateGoogleAccountMessage(accountAuthorizationError(failure))
      }
      return@registerForActivityResult
    }
    val subscriptionChange = pendingYoutubeSubscriptionChange
    if (subscriptionChange != null) {
      pendingYoutubeSubscriptionChange = null
      if (result.resultCode != RESULT_OK) {
        youtubeSyncBusy = false
        subscriptionChange.completion(Result.failure(IllegalStateException("YouTube permission request was cancelled.")))
        return@registerForActivityResult
      }
      try {
        val data = result.data ?: throw IllegalStateException("YouTube authorization returned no result.")
        val authorization = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data)
        useYoutubeSubscriptionAuthorization(authorization, subscriptionChange)
      } catch (failure: Exception) {
        youtubeSyncBusy = false
        subscriptionChange.completion(Result.failure(IllegalStateException(accountAuthorizationError(failure))))
      }
      return@registerForActivityResult
    }
    val readAuthorization = pendingYoutubeReadAuthorization
    if (readAuthorization != null) {
      pendingYoutubeReadAuthorization = null
      if (result.resultCode != RESULT_OK) {
        readAuthorization(Result.failure(IllegalStateException("YouTube permission request was cancelled.")))
      } else {
        try {
          val data = result.data ?: throw IllegalStateException("YouTube authorization returned no result.")
          readAuthorization(Result.success(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data)))
        } catch (failure: Exception) {
          readAuthorization(Result.failure(IllegalStateException(accountAuthorizationError(failure))))
        }
      }
      return@registerForActivityResult
    }
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
    } catch (failure: Exception) {
      youtubeSyncBusy = false
      completion(Result.failure(IllegalStateException(accountAuthorizationError(failure))))
    }
  }

  override fun attachBaseContext(newBase: Context) {
    super.attachBaseContext(AppLocale.wrap(newBase))
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    acceptExternalYoutubeIntent(intent)
    val preferences = getSharedPreferences("lowtube_settings", MODE_PRIVATE)
    googleAccountMessageStore = GoogleAccountMessageStore(this)
    googleAccountMessage = googleAccountMessageStore?.load().orEmpty()
    darkTheme = preferences.getBoolean("dark_theme", true)
    googleAccountName = preferences.getString("google_account_name", "").orEmpty()
    googleAccountEmail = preferences.getString("google_account_email", "").orEmpty()
    googleAccountAvatarUrl = GoogleProfileAvatarPolicy.validate(preferences.getString("google_account_avatar_url", "").orEmpty())

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
            googleAccountAvatarUrl = googleAccountAvatarUrl,
            googleAccountMessage = googleAccountMessage,
            googleSignInBusy = googleSignInBusy,
            youtubeSyncBusy = youtubeSyncBusy,
            onGoogleSignIn = ::signInWithGoogle,
            onGoogleSignOut = ::signOutGoogle,
            onRequestYouTubeSync = ::syncYouTubeSubscriptions,
            onRequestYouTubePlaylists = ::loadYouTubePlaylists,
            onRequestYouTubePlaylistVideos = ::loadYouTubePlaylistVideos,
            onChangeYouTubeSubscription = ::changeYouTubeSubscription,
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
    if (googleSignInBusy) return

    googleSignInBusy = true
    updateGoogleAccountMessage("")
    val chooser = AccountManager.newChooseAccountIntent(
      null,
      null,
      arrayOf(GOOGLE_ACCOUNT_TYPE),
      false,
      null,
      null,
      null,
      null,
    )
    try {
      googleAccountSelectionLauncher.launch(chooser)
    } catch (failure: Exception) {
      googleSignInBusy = false
      updateGoogleAccountMessage(accountAuthorizationError(failure))
    }
  }

  private fun requestGoogleProfileAuthorization(email: String) {
    val request = AuthorizationRequest.builder()
      .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
      .setRequestedScopes(listOf(Scope(OPENID_SCOPE), Scope(EMAIL_SCOPE), Scope(PROFILE_SCOPE)))
      .build()
    Identity.getAuthorizationClient(this).authorize(request)
      .addOnSuccessListener(::useGoogleProfileAuthorization)
      .addOnFailureListener { failure ->
        googleSignInBusy = false
        updateGoogleAccountMessage(accountAuthorizationError(failure))
      }
  }

  private fun useGoogleProfileAuthorization(authorization: AuthorizationResult) {
    if (authorization.hasResolution()) {
      val pendingIntent = authorization.pendingIntent
      if (pendingIntent == null) {
        googleSignInBusy = false
        updateGoogleAccountMessage(getString(R.string.account_sign_in_failed))
        return
      }
      pendingGoogleProfileSignIn = true
      try {
        youtubeAuthorizationLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
      } catch (failure: Exception) {
        pendingGoogleProfileSignIn = false
        googleSignInBusy = false
        updateGoogleAccountMessage(accountAuthorizationError(failure))
      }
      return
    }
    val accessToken = authorization.accessToken.orEmpty()
    if (accessToken.isBlank()) {
      googleSignInBusy = false
      updateGoogleAccountMessage(getString(R.string.account_sign_in_failed))
      return
    }
    lifecycleScope.launch {
      try {
        val profile = withContext(Dispatchers.IO) { youtubeDataApi.userProfile(accessToken) }
        applyGoogleProfile(profile)
      } catch (failure: Exception) {
        updateGoogleAccountMessage(accountAuthorizationError(failure))
      } finally {
        googleSignInBusy = false
      }
    }
  }

  private fun applyGoogleProfile(profile: GoogleAccountProfile) {
    if (googleAccountEmail.isNotBlank() && !googleAccountEmail.equals(profile.email, ignoreCase = true)) {
      youtubeSyncGeneration++
      pendingYoutubeSync = null
      pendingYoutubeReadAuthorization = null
      pendingYoutubeAccountEmail = ""
      youtubeSyncBusy = false
    }
    googleAccountName = profile.displayName
    googleAccountEmail = profile.email
    googleAccountAvatarUrl = GoogleProfileAvatarPolicy.validate(profile.pictureUrl)
    getSharedPreferences("lowtube_settings", MODE_PRIVATE).edit()
      .putString("google_account_name", googleAccountName)
      .putString("google_account_email", googleAccountEmail)
      .putString("google_account_avatar_url", googleAccountAvatarUrl)
      .apply()
    updateGoogleAccountMessage(getString(R.string.account_profile_local_note))
  }

  private fun updateGoogleAccountMessage(message: String) {
    googleAccountMessage = message
    googleAccountMessageStore?.save(message)
  }

  private fun accountAuthorizationError(failure: Throwable): String {
    val apiFailure = failure as? ApiException
    return when (apiFailure?.statusCode) {
      CommonStatusCodes.DEVELOPER_ERROR -> getString(R.string.account_oauth_not_configured)
      CommonStatusCodes.CANCELED -> googleAuthorizationCancelledMessage(failure)
      else -> getString(R.string.account_sign_in_failed)
    }
  }

  private fun googleAuthorizationCancelledMessage(failure: Throwable?): String {
    val details = GoogleAuthorizationDiagnostics.collect(this)
    val result = (failure as? ApiException)?.let { "ApiException ${it.statusCode}" } ?: "RESULT_CANCELED"
    val message = if (details.provider.equals("microG", ignoreCase = true)) {
      R.string.account_authorization_cancelled_microg_diagnostic
    } else {
      R.string.account_authorization_cancelled_diagnostic
    }
    return getString(
      message,
      result,
      details.provider,
      details.providerVersion.ifBlank { "unknown" },
      getString(if (details.authorizationApiAvailable) R.string.account_authorization_api_available else R.string.account_authorization_api_not_detected),
      details.packageName,
      details.signingSha1,
    )
  }

  private fun googleAccountSelectionCancelledMessage(resultCode: Int): String {
    val details = GoogleAuthorizationDiagnostics.collect(this)
    return getString(
      R.string.account_picker_cancelled_diagnostic,
      resultCode,
      details.provider,
      details.providerVersion.ifBlank { "unknown" },
      getString(if (details.authorizationApiAvailable) R.string.account_authorization_api_available else R.string.account_authorization_api_not_detected),
    )
  }

  private fun signOutGoogle() {
    val email = googleAccountEmail
    googleAccountName = ""
    googleAccountEmail = ""
    googleAccountAvatarUrl = ""
    updateGoogleAccountMessage("")
    youtubeSyncBusy = false
    youtubeSyncGeneration++
    pendingYoutubeSync = null
    pendingYoutubeReadAuthorization?.invoke(Result.failure(IllegalStateException("Google account signed out during YouTube data access.")))
    pendingYoutubeReadAuthorization = null
    pendingYoutubeSubscriptionChange?.completion(Result.failure(IllegalStateException("Google account changed during the subscription update.")))
    pendingYoutubeSubscriptionChange = null
    pendingYoutubeAccountEmail = ""
    getSharedPreferences("lowtube_settings", MODE_PRIVATE).edit()
      .remove("google_account_name")
      .remove("google_account_email")
      .remove("google_account_avatar_url")
      .apply()
    if (email.isNotBlank()) {
      val request = AuthorizationRequest.builder()
        .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
        .setRequestedScopes(listOf(Scope(OPENID_SCOPE), Scope(EMAIL_SCOPE), Scope(PROFILE_SCOPE), Scope(YOUTUBE_READONLY_SCOPE)))
        .build()
      Identity.getAuthorizationClient(this).authorize(request)
        .addOnSuccessListener { authorization ->
          val token = authorization.accessToken.orEmpty()
          if (!authorization.hasResolution() && token.isNotBlank()) {
            lifecycleScope.launch(Dispatchers.IO) { runCatching { youtubeDataApi.revokeAccess(token) } }
          }
        }
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

  private fun loadYouTubePlaylists(completion: (Result<List<YouTubePlaylist>>) -> Unit) {
    requestYouTubeReadData(youtubeDataApi::userPlaylists, completion)
  }

  private fun loadYouTubePlaylistVideos(
    playlistId: String,
    completion: (Result<List<com.lowtube.android.model.VideoItem>>) -> Unit,
  ) {
    requestYouTubeReadData({ accessToken -> youtubeDataApi.playlistVideos(accessToken, playlistId) }, completion)
  }

  private fun <T> requestYouTubeReadData(
    fetch: suspend (String) -> T,
    completion: (Result<T>) -> Unit,
  ) {
    val email = googleAccountEmail
    if (email.isBlank()) {
      completion(Result.failure(IllegalStateException("Sign in with Google before accessing YouTube account data.")))
      return
    }
    if (youtubeSyncBusy) {
      completion(Result.failure(IllegalStateException("Another YouTube account request is already in progress.")))
      return
    }
    youtubeSyncBusy = true
    val generation = ++youtubeSyncGeneration
    pendingYoutubeAccountEmail = email
    val guardedCompletion: (Result<T>) -> Unit = { result ->
      if (generation == youtubeSyncGeneration) {
        youtubeSyncBusy = false
        pendingYoutubeAccountEmail = ""
        if (googleAccountEmail.equals(email, ignoreCase = true)) completion(result)
        else completion(Result.failure(IllegalStateException("The Google account changed during YouTube data access.")))
      }
    }

    fun useAuthorization(authorization: AuthorizationResult) {
      if (generation != youtubeSyncGeneration || !googleAccountEmail.equals(email, ignoreCase = true)) return
      if (authorization.hasResolution()) {
        val pendingIntent = authorization.pendingIntent
        if (pendingIntent == null) {
          guardedCompletion(Result.failure(IllegalStateException("Google did not return a YouTube permission request.")))
          return
        }
        pendingYoutubeReadAuthorization = { result ->
          result.onSuccess(::useAuthorization)
            .onFailure { failure -> guardedCompletion(Result.failure(failure)) }
        }
        try {
          youtubeAuthorizationLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
        } catch (_: Exception) {
          pendingYoutubeReadAuthorization = null
          guardedCompletion(Result.failure(IllegalStateException("Could not open the YouTube permission request.")))
        }
        return
      }

      val accessToken = authorization.accessToken.orEmpty()
      if (accessToken.isBlank()) {
        guardedCompletion(Result.failure(IllegalStateException("Google returned no YouTube access token.")))
        return
      }
      lifecycleScope.launch {
        val result = try {
          Result.success(withContext(Dispatchers.IO) { fetch(accessToken) })
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (failure: Exception) {
          Result.failure(failure)
        }
        guardedCompletion(result)
      }
    }

    val request = AuthorizationRequest.builder()
      .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
      .setRequestedScopes(listOf(Scope(YOUTUBE_READONLY_SCOPE)))
      .build()
    Identity.getAuthorizationClient(this).authorize(request)
      .addOnSuccessListener(::useAuthorization)
      .addOnFailureListener { failure -> guardedCompletion(Result.failure(IllegalStateException(accountAuthorizationError(failure)))) }
  }

  private fun changeYouTubeSubscription(
    channelId: String,
    subscribe: Boolean,
    completion: (Result<Unit>) -> Unit,
  ) {
    val email = googleAccountEmail
    if (email.isBlank()) {
      completion(Result.failure(IllegalStateException("Sign in with Google before changing YouTube subscriptions.")))
      return
    }
    if (youtubeSyncBusy) {
      completion(Result.failure(IllegalStateException("A YouTube account request is already in progress.")))
      return
    }
    youtubeSyncBusy = true
    val generation = ++youtubeSyncGeneration
    pendingYoutubeAccountEmail = email
    val change = YoutubeSubscriptionChange(email, channelId, subscribe, generation, completion)
    val request = AuthorizationRequest.builder()
      .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
      .setRequestedScopes(listOf(Scope(YOUTUBE_MANAGE_SCOPE)))
      .build()
    Identity.getAuthorizationClient(this).authorize(request)
      .addOnSuccessListener { authorization ->
        if (generation == youtubeSyncGeneration && googleAccountEmail.equals(email, ignoreCase = true)) {
          useYoutubeSubscriptionAuthorization(authorization, change)
        } else {
          youtubeSyncBusy = false
          completion(Result.failure(IllegalStateException("The Google account changed before the subscription update.")))
        }
      }
      .addOnFailureListener { failure ->
        if (generation == youtubeSyncGeneration) youtubeSyncBusy = false
        completion(Result.failure(IllegalStateException(accountAuthorizationError(failure))))
      }
  }

  private fun useYoutubeSubscriptionAuthorization(
    authorization: AuthorizationResult,
    change: YoutubeSubscriptionChange,
  ) {
    if (change.generation != youtubeSyncGeneration || !googleAccountEmail.equals(change.accountEmail, ignoreCase = true)) {
      youtubeSyncBusy = false
      change.completion(Result.failure(IllegalStateException("The Google account changed before the subscription update.")))
      return
    }
    if (authorization.hasResolution()) {
      val pendingIntent = authorization.pendingIntent
      if (pendingIntent == null) {
        youtubeSyncBusy = false
        change.completion(Result.failure(IllegalStateException("Google did not return a YouTube permission request.")))
        return
      }
      pendingYoutubeSubscriptionChange = change
      try {
        youtubeAuthorizationLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
      } catch (_: Exception) {
        pendingYoutubeSubscriptionChange = null
        youtubeSyncBusy = false
        change.completion(Result.failure(IllegalStateException("Could not open the YouTube permission request.")))
      }
      return
    }
    val accessToken = authorization.accessToken.orEmpty()
    if (accessToken.isBlank()) {
      youtubeSyncBusy = false
      change.completion(Result.failure(IllegalStateException("Google returned no YouTube access token.")))
      return
    }
    lifecycleScope.launch {
      val result = try {
        Result.success(withContext(Dispatchers.IO) {
          youtubeDataApi.setSubscription(accessToken, change.channelId, change.subscribe)
          Unit
        })
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        Result.failure(failure)
      } finally {
        youtubeSyncBusy = false
      }
      if (change.generation == youtubeSyncGeneration && googleAccountEmail.equals(change.accountEmail, ignoreCase = true)) {
        change.completion(result)
      } else {
        change.completion(Result.failure(IllegalStateException("The Google account changed before the subscription update completed.")))
      }
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
    private const val OPENID_SCOPE = "openid"
    private const val EMAIL_SCOPE = "email"
    private const val PROFILE_SCOPE = "profile"
    private const val YOUTUBE_READONLY_SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
    private const val YOUTUBE_MANAGE_SCOPE = "https://www.googleapis.com/auth/youtube"
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
