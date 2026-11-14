package com.example.notes

import android.app.Activity
import androidx.credentials.*
import androidx.credentials.exceptions.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Sign-in against your own server: passkeys (Credential Manager) or username + password. Each returns (username, session token). */
object Auth {
    private suspend fun post(path: String, body: JSONObject, token: String? = null) =
        withContext(Dispatchers.IO) { Api.json("POST", path, token, body) }

    /** Creates an account with a passkey, or adds a passkey to the signed-in account when [token] is given. */
    suspend fun passkeyRegister(act: Activity, username: String, token: String? = null): Pair<String, String> {
        val opts = post("/api/register/options", JSONObject().put("username", username), token)
        val res = CredentialManager.create(act).createCredential(act, CreatePublicKeyCredentialRequest(opts.toString())) as CreatePublicKeyCredentialResponse
        val out = post("/api/register/verify", JSONObject().put("username", username).put("credential", JSONObject(res.registrationResponseJson)), token)
        return out.getString("username") to out.getString("token")
    }

    suspend fun passkeyLogin(act: Activity): Pair<String, String> {
        val opts = post("/api/login/options", JSONObject())
        val res = CredentialManager.create(act).getCredential(act, GetCredentialRequest(listOf(GetPublicKeyCredentialOption(opts.toString()))))
        val cred = res.credential as? PublicKeyCredential ?: error("No passkey was returned")
        val out = post("/api/login/verify", JSONObject().put("credential", JSONObject(cred.authenticationResponseJson)))
        return out.getString("username") to out.getString("token")
    }

    suspend fun passwordRegister(user: String, pw: String) = post("/api/password/register", JSONObject().put("username", user).put("password", pw)).let { it.getString("username") to it.getString("token") }
    suspend fun passwordLogin(user: String, pw: String) = post("/api/password/login", JSONObject().put("username", user).put("password", pw)).let { it.getString("username") to it.getString("token") }

    fun message(e: Exception): String = when (e) {
        is CreateCredentialCancellationException, is GetCredentialCancellationException -> "Cancelled"
        is NoCredentialException -> "No passkey found on this device. Create an account, or use a password."
        is CreateCredentialException -> "Couldn't create the passkey: ${e.errorMessage ?: e.type}"
        is GetCredentialException -> "Couldn't use the passkey: ${e.errorMessage ?: e.type}"
        is ApiError -> e.message ?: "Server error"
        is java.io.IOException -> "Can't reach the server. Check your connection and the address in Config.kt."
        else -> e.message ?: "Something went wrong"
    }
}
