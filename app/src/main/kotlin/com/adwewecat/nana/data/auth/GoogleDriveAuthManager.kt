package com.adwewecat.nana.data.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.adwewecat.nana.BuildConfig
import com.adwewecat.nana.data.local.EncryptedApiKeyVault
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse
import org.json.JSONException

/** Observable sign-in and re-authentication state for Google Drive backup & restore. */
data class DriveAuthStatus(
    val isSignedIn: Boolean = false,
    val email: String? = null,
    val needsReauth: Boolean = false,
    val isOAuthConfigured: Boolean = false,
    val lastErrorMessage: String? = null,
)

/**
 * Manages Google OAuth 2.0 PKCE authentication via AppAuth and persists `AuthState`
 * inside [EncryptedApiKeyVault].
 */
class GoogleDriveAuthManager(
    context: Context,
    private val vault: EncryptedApiKeyVault,
    private val clientId: String = BuildConfig.GOOGLE_OAUTH_CLIENT_ID,
    appAuthConfiguration: AppAuthConfiguration = AppAuthConfiguration.DEFAULT,
) {
    private val appContext = context.applicationContext
    private val authService = AuthorizationService(appContext, appAuthConfiguration)
    private val tokenMutex = Mutex()
    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse(GOOGLE_AUTH_ENDPOINT),
        Uri.parse(GOOGLE_TOKEN_ENDPOINT),
    )

    private var authState: AuthState? = loadPersistedAuthState()
    private val _authStateFlow = MutableStateFlow(buildCurrentStatus())

    /** Live authentication state observed by UI and sync diagnostics. */
    val authStateFlow: StateFlow<DriveAuthStatus> = _authStateFlow.asStateFlow()

    /** Returns `true` when a non-placeholder Google OAuth client ID is configured. */
    fun isOAuthConfigured(): Boolean {
        val trimmed = clientId.trim()
        return trimmed.isNotEmpty() && !trimmed.startsWith("REPLACE_WITH", ignoreCase = true)
    }

    /**
     * Builds the AppAuth PKCE authorization intent for Google Drive (`drive.file`),
     * returning `null` when [isOAuthConfigured] is `false`.
     */
    fun buildAuthIntent(): Intent? {
        if (!isOAuthConfigured()) return null
        val request = AuthorizationRequest.Builder(
            serviceConfig,
            clientId.trim(),
            ResponseTypeValues.CODE,
            redirectUriFor(clientId),
        )
            .setScopes(SCOPE_OPENID, SCOPE_EMAIL, SCOPE_PROFILE, SCOPE_DRIVE_FILE)
            .setPromptValues(
                AuthorizationRequest.Prompt.SELECT_ACCOUNT,
                AuthorizationRequest.Prompt.CONSENT,
            )
            .setAdditionalParameters(mapOf("access_type" to "offline"))
            .build()
        return authService.getAuthorizationRequestIntent(request)
    }

    /**
     * Completes an AppAuth authorization result intent by exchanging the authorization code
     * for access/refresh tokens via PKCE.
     */
    suspend fun handleAuthorizationIntent(data: Intent): Boolean = tokenMutex.withLock {
        val authResponse = AuthorizationResponse.fromIntent(data)
        val authException = AuthorizationException.fromIntent(data)
        if (authResponse == null) {
            val detail = formatAuthException(authException)
            if (isInvalidGrant(authException)) {
                markNeedsReauthLocked(lastErrorMessage = detail)
            } else {
                _authStateFlow.value = buildCurrentStatus(lastErrorMessage = detail)
            }
            return@withLock false
        }

        val updatedState = AuthState(authResponse, authException)
        val (tokenResponse, tokenException) = performTokenExchange(authResponse)
        updatedState.update(tokenResponse, tokenException)

        if (tokenException != null || tokenResponse == null) {
            val detail = formatAuthException(tokenException) ?: "Không đổi được mã xác thực Google Drive."
            if (isInvalidGrant(tokenException)) {
                authState = updatedState
                vault.saveAuthStateJson(updatedState.jsonSerializeString())
                markNeedsReauthLocked(lastErrorMessage = detail)
            } else {
                _authStateFlow.value = buildCurrentStatus(lastErrorMessage = detail)
            }
            return@withLock false
        }

        authState = updatedState
        vault.saveNeedsReauth(false)
        vault.saveAuthStateJson(updatedState.jsonSerializeString())
        _authStateFlow.value = buildCurrentStatus(lastErrorMessage = null)
        true
    }

    /**
     * Returns a valid access token, refreshing it via AppAuth if expired.
     * Flags `needsReauth = true` on `invalid_grant` without clearing vault keys or settings.
     */
    suspend fun getFreshAccessToken(): String? = tokenMutex.withLock {
        if (vault.loadNeedsReauth()) {
            _authStateFlow.value = buildCurrentStatus()
            return@withLock null
        }

        val state = authState ?: loadPersistedAuthState()?.also { authState = it } ?: run {
            _authStateFlow.value = buildCurrentStatus()
            return@withLock null
        }

        if (!state.isAuthorized) {
            if (isInvalidGrant(state.authorizationException)) {
                markNeedsReauthLocked()
            } else {
                _authStateFlow.value = buildCurrentStatus()
            }
            return@withLock null
        }

        if (state.needsTokenRefresh && state.refreshToken.isNullOrBlank()) {
            markNeedsReauthLocked()
            return@withLock null
        }

        val result = performFreshTokenAction(state)
        vault.saveAuthStateJson(state.jsonSerializeString())

        val ex = result.exception
        if (ex != null || result.accessToken.isNullOrBlank()) {
            if (isInvalidGrant(ex) || !state.isAuthorized) {
                markNeedsReauthLocked()
            }
            return@withLock null
        }

        vault.saveNeedsReauth(false)
        _authStateFlow.value = buildCurrentStatus()
        result.accessToken
    }

    /**
     * Flags the current session as requiring interactive re-authentication (e.g. after 7-day
     * Testing token expiry or HTTP 401) while keeping all local Room data and vault keys intact.
     */
    fun markNeedsReauth() {
        markNeedsReauthLocked()
    }

    /** Clears persisted Google Drive OAuth credentials while leaving API keys and settings intact. */
    fun signOut() {
        authState = null
        vault.saveAuthStateJson(null)
        vault.saveNeedsReauth(false)
        _authStateFlow.value = DriveAuthStatus(
            isSignedIn = false,
            email = null,
            needsReauth = false,
            isOAuthConfigured = isOAuthConfigured(),
        )
    }

    /** Releases underlying AppAuth Custom Tabs bindings when this manager is no longer needed. */
    fun dispose() {
        authService.dispose()
    }

    private fun markNeedsReauthLocked(lastErrorMessage: String? = null) {
        vault.saveNeedsReauth(true)
        val preservedEmail = extractEmail(authState) ?: _authStateFlow.value.email
        _authStateFlow.value = DriveAuthStatus(
            isSignedIn = false,
            email = preservedEmail,
            needsReauth = true,
            isOAuthConfigured = isOAuthConfigured(),
            lastErrorMessage = lastErrorMessage,
        )
    }

    private fun formatAuthException(ex: AuthorizationException?): String? {
        if (ex == null) return null
        return listOfNotNull(ex.error, ex.errorDescription)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(": ")
            .ifBlank { ex.message?.trim() }
            ?.take(220)
    }

    private suspend fun performTokenExchange(
        authResponse: AuthorizationResponse,
    ): Pair<TokenResponse?, AuthorizationException?> = suspendCancellableCoroutine { cont ->
        authService.performTokenRequest(authResponse.createTokenExchangeRequest()) { response, exception ->
            if (cont.isActive) {
                cont.resume(response to exception)
            }
        }
    }

    private suspend fun performFreshTokenAction(state: AuthState): FreshTokenResult =
        suspendCancellableCoroutine { cont ->
            state.performActionWithFreshTokens(authService) { accessToken, idToken, ex ->
                if (cont.isActive) {
                    cont.resume(FreshTokenResult(accessToken, idToken, ex))
                }
            }
        }

    private fun loadPersistedAuthState(): AuthState? {
        val raw = vault.loadAuthStateJson() ?: return null
        return try {
            AuthState.jsonDeserialize(raw)
        } catch (_: JSONException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun buildCurrentStatus(lastErrorMessage: String? = null): DriveAuthStatus {
        val state = authState
        val needsReauth = vault.loadNeedsReauth() || isInvalidGrant(state?.authorizationException)
        val signedIn = !needsReauth && state?.isAuthorized == true
        return DriveAuthStatus(
            isSignedIn = signedIn,
            email = extractEmail(state),
            needsReauth = needsReauth,
            isOAuthConfigured = isOAuthConfigured(),
            lastErrorMessage = lastErrorMessage,
        )
    }

    private fun isInvalidGrant(ex: AuthorizationException?): Boolean {
        if (ex == null) return false
        if (ex.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR &&
            ex.code == AuthorizationException.TokenRequestErrors.INVALID_GRANT.code
        ) {
            return true
        }
        if (ex.error.equals("invalid_grant", ignoreCase = true)) {
            return true
        }
        return ex.type == AuthorizationException.TYPE_OAUTH_AUTHORIZATION_ERROR &&
            ex.code == AuthorizationException.AuthorizationRequestErrors.CLIENT_ERROR.code
    }

    private fun extractEmail(state: AuthState?): String? {
        val idToken = state?.idToken ?: state?.lastTokenResponse?.idToken ?: return null
        val parts = idToken.split('.')
        if (parts.size < 2) return null
        return try {
            val decodedBytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val payload = json.parseToJsonElement(decodedBytes.toString(Charsets.UTF_8)).jsonObject
            payload["email"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun redirectUriFor(rawClientId: String): Uri {
        val trimmed = rawClientId.trim()
        val scheme = if (trimmed.endsWith(".apps.googleusercontent.com")) {
            trimmed.split(".").reversed().joinToString(".")
        } else {
            DEFAULT_REDIRECT_SCHEME
        }
        return Uri.parse("$scheme:/oauth2redirect")
    }

    private data class FreshTokenResult(
        val accessToken: String?,
        val idToken: String?,
        val exception: AuthorizationException?,
    )

    private companion object {
        const val GOOGLE_AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        const val GOOGLE_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        const val SCOPE_OPENID = "openid"
        const val SCOPE_EMAIL = "email"
        const val SCOPE_PROFILE = "profile"
        const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
        const val DEFAULT_REDIRECT_SCHEME = "com.adwewecat.nana.oauth"

        val json = Json { ignoreUnknownKeys = true }
    }
}
