package io.buildthisplease.core

import android.content.Context
import android.os.Build

data class BuildThisPleaseConfiguration(
    val baseUrl: String,
    val projectKey: String,
    val environment: Environment,
    val subscriptionStatus: SubscriptionStatus = SubscriptionStatus.UNKNOWN,
    val revenueCatAppUserId: String? = null,
    val userEmail: String? = null,
    val packageName: String? = null,
    val appVersion: String? = null,
    val osVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    val identityNamespace: String? = null,
) {
    enum class Environment(val wireValue: String) {
        DEVELOPMENT("development"),
        PRODUCTION("production"),
    }

    internal fun resolve(context: Context): BuildThisPleaseConfiguration {
        require(projectKey.isNotBlank()) { "A BuildThisPlease project key is required." }
        require(baseUrl.startsWith("https://") || environment == Environment.DEVELOPMENT) {
            "Production BuildThisPlease URLs must use HTTPS."
        }
        return copy(
            baseUrl = baseUrl.trimEnd('/'),
            packageName = packageName ?: context.packageName,
            appVersion = appVersion ?: runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull(),
            revenueCatAppUserId = revenueCatAppUserId.normalized(),
            userEmail = userEmail.normalized()?.lowercase(),
        )
    }
}

private fun String?.normalized(): String? = this?.trim()?.takeIf(String::isNotEmpty)
