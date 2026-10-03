package com.rushtify.app.data.playlist

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64 as AndroidBase64
import com.rushtify.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface SpotifyLoginEvent {
    data class Success(val playlists: List<SpotifyAccountPlaylist>) : SpotifyLoginEvent
    data class Failure(val message: String) : SpotifyLoginEvent
}

/** Spotify Authorization Code + PKCE and authenticated playlist-library API access. */
@Singleton
class SpotifyLibraryApi @Inject constructor(
    @ApplicationContext context: Context,
    okHttpClient: OkHttpClient,
) {
    private val preferences = context.getSharedPreferences(PENDING_AUTH_PREFS, Context.MODE_PRIVATE)
    private val tokenPreferences = context.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)
    private val client = okHttpClient.newBuilder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val tokenMutex = Mutex()
    private var activeToken: Token? = null

    private val _loginEvent = MutableStateFlow<SpotifyLoginEvent?>(null)
    val loginEvent = _loginEvent.asStateFlow()

    /** Starts a browser-based Spotify login using only playlist-read permissions. */
    fun createAuthorizationUrl(): String {
        val clientId = configuredClientId()
        check(clientId.isNotBlank()) {
            "Enter your Spotify Developer app Client ID to enable Spotify sign-in."
        }
        val verifier = randomUrlSafeString(64)
        val state = randomUrlSafeString(32)
        check(
            preferences.edit()
                .putString(KEY_CODE_VERIFIER, verifier)
                .putString(KEY_STATE, state)
                .commit(),
        ) { "Could not prepare Spotify sign-in. Please try again." }
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        return HttpUrl.Builder()
            .scheme("https")
            .host("accounts.spotify.com")
            .addPathSegment("authorize")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", REDIRECT_URI)
            .addQueryParameter("scope", SCOPES)
            .addQueryParameter("show_dialog", "true")
            .addQueryParameter("state", state)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", challenge)
            .build()
            .toString()
    }

    /** Completes a Spotify redirect, exchanges its one-time code, then loads all library pages. */
    suspend fun handleRedirect(uri: Uri?) {
        if (uri?.scheme != CALLBACK_SCHEME || uri.host != CALLBACK_HOST || uri.path != CALLBACK_PATH) return
        try {
            val playlists = withContext(Dispatchers.IO) { completeAuthorization(uri) }
            _loginEvent.value = SpotifyLoginEvent.Success(playlists)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _loginEvent.value = SpotifyLoginEvent.Failure(e.localizedMessage ?: "Spotify sign-in failed.")
        }
    }

    fun consumeLoginEvent() {
        _loginEvent.value = null
    }

    fun configuredClientId(): String = BuildConfig.SPOTIFY_CLIENT_ID.trim()

    fun disconnect() {
        activeToken = null
        _loginEvent.value = null
        preferences.edit().clear().apply()
        tokenPreferences.edit().remove(KEY_ENCRYPTED_TOKEN).apply()
    }

    /** Read a playlist's full track list with the account's token, including private/collaborative playlists. */
    suspend fun fetchPlaylistTracks(playlist: SpotifyAccountPlaylist): ExternalPlaylistResult =
        withContext(Dispatchers.IO) {
            val rows = mutableListOf<ExternalTrackRow>()
            var offset = 0
            var hasNext: Boolean
            do {
                val url = apiUrl("v1", "playlists", playlist.id, "items")
                    .newBuilder()
                    .addQueryParameter("limit", PAGE_SIZE.toString())
                    .addQueryParameter("offset", offset.toString())
                    .build()
                val page = getAuthorizedJson(url)
                val items = page["items"] as? JsonArray ?: JsonArray(emptyList())
                items.forEach { value ->
                    val wrapper = value as? JsonObject ?: return@forEach
                    val track = (wrapper["item"] as? JsonObject)
                        ?: (wrapper["track"] as? JsonObject)
                        ?: return@forEach
                    if (track.string("type") == "episode") return@forEach
                    val title = track.string("name")?.trim().orEmpty()
                    if (title.isBlank()) return@forEach
                    val artists = (track["artists"] as? JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonObject)?.string("name")?.trim()?.takeIf(String::isNotBlank) }
                    val album = (track["album"] as? JsonObject)?.string("name")?.trim()?.takeIf(String::isNotBlank)
                    rows += ExternalTrackRow(title = title, artist = artists.joinToString(", "), album = album)
                }
                offset += items.size
                hasNext = page.string("next") != null && items.isNotEmpty() && offset < MAX_OFFSET
            } while (hasNext)

            ExternalPlaylistResult(
                source = ExternalPlaylistSource.SPOTIFY,
                title = playlist.title,
                author = playlist.author,
                rows = rows,
            )
        }

    private suspend fun completeAuthorization(uri: Uri): List<SpotifyAccountPlaylist> {
        val expectedState = preferences.getString(KEY_STATE, null)
            ?: throw IOException("Spotify sign-in expired. Please try again.")
        val actualState = uri.getQueryParameter("state")
        if (actualState != expectedState) {
            preferences.edit().clear().apply()
            throw IOException("Spotify sign-in could not be verified. Please try again.")
        }
        val error = uri.getQueryParameter("error")
        if (error != null) {
            preferences.edit().clear().apply()
            throw IOException(if (error == "access_denied") "Spotify access was not approved." else "Spotify sign-in failed: $error")
        }
        val code = uri.getQueryParameter("code")
            ?: throw IOException("Spotify did not return an authorization code.")
        val verifier = preferences.getString(KEY_CODE_VERIFIER, null)
            ?: throw IOException("Spotify sign-in expired. Please try again.")
        preferences.edit().clear().apply()
        activeToken = requestToken(
            FormBody.Builder()
                .add("client_id", configuredClientId())
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("code_verifier", verifier)
                .build(),
        )
        persistToken(activeToken!!)
        return fetchAccountPlaylists()
    }

    private suspend fun fetchAccountPlaylists(): List<SpotifyAccountPlaylist> {
        val found = linkedMapOf<String, SpotifyAccountPlaylist>()
        var offset = 0
        var hasNext: Boolean
        do {
            val url = apiUrl("v1", "me", "playlists")
                .newBuilder()
                .addQueryParameter("limit", PAGE_SIZE.toString())
                .addQueryParameter("offset", offset.toString())
                .build()
            val page = getAuthorizedJson(url)
            val items = page["items"] as? JsonArray ?: JsonArray(emptyList())
            items.forEach { value ->
                val item = value as? JsonObject ?: return@forEach
                val id = item.string("id")?.takeIf(String::isNotBlank) ?: return@forEach
                val title = item.string("name")?.trim().orEmpty().ifBlank { "Spotify playlist" }
                val owner = item["owner"] as? JsonObject
                val author = owner?.string("display_name")?.takeIf(String::isNotBlank)
                    ?: owner?.string("id")?.takeIf(String::isNotBlank)
                found.putIfAbsent(
                    id,
                    SpotifyAccountPlaylist(
                        id = id,
                        title = title,
                        author = author,
                        kind = SpotifyPlaylistKind.IN_LIBRARY,
                    ),
                )
            }
            offset += items.size
            hasNext = page.string("next") != null && items.isNotEmpty() && offset < MAX_OFFSET
        } while (hasNext)
        return found.values.toList()
    }

    private suspend fun getAuthorizedJson(url: HttpUrl): JsonObject {
        val accessToken = currentAccessToken()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .get()
            .build()
        val body = client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val payload = runCatching { json.parseToJsonElement(responseBody) as? JsonObject }.getOrNull()
                val nestedError = payload?.get("error") as? JsonObject
                val detail = payload?.string("error_description")
                    ?: payload?.string("message")
                    ?: nestedError?.string("message")
                val message = when (response.code) {
                    401 -> "Spotify sign-in expired. Tap Connect Spotify and approve the playlist permissions again."
                    403 -> detail?.let { "Spotify denied access to this playlist library: $it" }
                        ?: "Spotify denied playlist access. Reconnect and approve playlist access; if it persists, check the app's allowed users and Spotify Developer app requirements."
                    404 -> "This Spotify playlist could not be found or is no longer accessible."
                    else -> detail ?: "Spotify couldn't complete the playlist request. Please try again."
                }
                throw IOException(message)
            }
            responseBody
        }
        return json.parseToJsonElement(body) as? JsonObject
            ?: throw IOException("Spotify returned an unreadable response.")
    }

    private suspend fun currentAccessToken(): String = tokenMutex.withLock {
        val current = activeToken ?: loadToken()?.also { activeToken = it }
            ?: throw IOException("Connect to Spotify before importing this playlist.")
        if (current.expiresAtMillis > System.currentTimeMillis() + TOKEN_REFRESH_MARGIN_MS) {
            return@withLock current.accessToken
        }
        val refreshToken = current.refreshToken
            ?: throw IOException("Spotify sign-in expired. Please connect again.")
        val refreshed = requestToken(
            FormBody.Builder()
                .add("client_id", configuredClientId())
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .build(),
        )
        activeToken = refreshed.copy(refreshToken = refreshed.refreshToken ?: refreshToken)
        persistToken(activeToken!!)
        activeToken!!.accessToken
    }

    private fun persistToken(token: Token) {
        val payload = listOf(token.accessToken, token.refreshToken.orEmpty(), token.expiresAtMillis.toString())
            .joinToString("\n")
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKeystoreKey())
        val encrypted = cipher.doFinal(payload)
        val blob = cipher.iv + encrypted
        check(tokenPreferences.edit().putString(KEY_ENCRYPTED_TOKEN, AndroidBase64.encodeToString(blob, AndroidBase64.NO_WRAP)).commit()) {
            "Could not securely store Spotify sign-in. Please try again."
        }
    }

    private fun loadToken(): Token? {
        val encoded = tokenPreferences.getString(KEY_ENCRYPTED_TOKEN, null) ?: return null
        return runCatching {
            val blob = AndroidBase64.decode(encoded, AndroidBase64.NO_WRAP)
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
                ?: throw IOException("Spotify token encryption key is unavailable.")
            val ivSize = GCM_IV_BYTES
            require(blob.size > ivSize)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, blob.copyOfRange(0, ivSize)))
            val values = cipher.doFinal(blob.copyOfRange(ivSize, blob.size)).toString(Charsets.UTF_8).split('\n')
            require(values.size == 3 && values[0].isNotBlank())
            Token(
                accessToken = values[0],
                refreshToken = values[1].takeIf(String::isNotBlank),
                expiresAtMillis = values[2].toLong(),
            )
        }.getOrElse {
            tokenPreferences.edit().remove(KEY_ENCRYPTED_TOKEN).apply()
            null
        }
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun requestToken(form: FormBody): Token {
        val request = Request.Builder()
            .url(TOKEN_URL)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(form)
            .build()
        val responseBody = client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val error = runCatching {
                    val parsed = json.parseToJsonElement(body) as? JsonObject
                    parsed?.string("error_description") ?: parsed?.string("error")
                }.getOrNull()
                throw IOException(error ?: "Spotify token request failed (HTTP ${response.code}).")
            }
            body
        }
        val payload = json.parseToJsonElement(responseBody) as? JsonObject
            ?: throw IOException("Spotify returned an unreadable token response.")
        val accessToken = payload.string("access_token")
            ?: throw IOException("Spotify did not return an access token.")
        return Token(
            accessToken = accessToken,
            refreshToken = payload.string("refresh_token"),
            expiresAtMillis = System.currentTimeMillis() + (payload.int("expires_in") ?: 3600) * 1000L,
        )
    }

    private fun apiUrl(vararg path: String): HttpUrl = HttpUrl.Builder()
        .scheme("https")
        .host("api.spotify.com")
        .apply { path.forEach(::addPathSegment) }
        .build()

    private fun randomUrlSafeString(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    private data class Token(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAtMillis: Long,
    )

    private companion object {
        const val REDIRECT_URI = "rushtify://auth-callback/spotify"
        const val CALLBACK_SCHEME = "rushtify"
        const val CALLBACK_HOST = "auth-callback"
        const val CALLBACK_PATH = "/spotify"
        const val PENDING_AUTH_PREFS = "spotify_pending_auth"
        const val TOKEN_PREFS = "spotify_secure_tokens"
        const val KEY_CODE_VERIFIER = "code_verifier"
        const val KEY_STATE = "state"
        const val KEY_ENCRYPTED_TOKEN = "encrypted_token"
        const val KEY_ALIAS = "rushtify_spotify_oauth"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val SCOPES = "playlist-read-private playlist-read-collaborative"
        const val PAGE_SIZE = 50
        const val MAX_OFFSET = 100_000
        const val TOKEN_REFRESH_MARGIN_MS = 60_000L
        const val TOKEN_URL = "https://accounts.spotify.com/api/token"
    }
}
