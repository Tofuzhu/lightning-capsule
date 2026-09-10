package com.lightningcapsule.wear

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Uploads a recorded capsule to the Lightning Capsule backend.
 *
 * POST {CAPSULE_API_URL}/api/capture   (multipart/form-data)
 *   audio  -> the .m4a file  (content-type audio/mp4)
 *   source -> "wear"
 * Authorization: Bearer <token>
 */
class CapsuleUploader {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    sealed interface Outcome {
        /** HTTP 201 (new) or 200 (duplicate). */
        data object Success : Outcome

        /** HTTP 401/403 — the token is invalid. Never retry / never queue this. */
        data object AuthError : Outcome

        /** Any other HTTP status or a network/timeout failure — safe to queue and retry. */
        data class Failure(val reason: String) : Outcome
    }

    suspend fun upload(file: File, token: String): Outcome = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "audio",
                file.name,
                file.asRequestBody("audio/mp4".toMediaType()),
            )
            .addFormDataPart("source", "wear")
            .build()

        val request = Request.Builder()
            .url("${BuildConfig.CAPSULE_API_URL}/api/capture")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200, 201 -> Outcome.Success
                    401, 403 -> Outcome.AuthError
                    else -> Outcome.Failure("HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            Outcome.Failure("网络错误")
        }
    }

    // 自建部署：不要在源码里写死域名（本仓库公开）。在 wear/local.properties 里加一行
    //     capsule.api.url=https://你的-worker.workers.dev
    // 构建时会注入到 BuildConfig.CAPSULE_API_URL；未设置时回落到占位符。
    // 见 docs/SELF-HOSTING.md 第 E 节。
}
