/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.app.ui.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.network.protocol.ReleaseClass
import me.him188.ani.app.tools.TimeFormatter
import me.him188.ani.utils.ktor.getPlatformKtorEngine
import me.him188.ani.utils.platform.Arch
import me.him188.ani.utils.platform.Platform
import me.him188.ani.utils.platform.currentPlatform
import kotlin.time.Instant

/** 更新检查、发布说明和开发构建共同使用的分发仓库. */
object ForkUpdateRepository {
    const val NAME = "BrokenMyth/animeko"
    const val HOME = "https://github.com/$NAME"
    const val RELEASE_PREFIX = "$HOME/releases/tag/v"

    fun releaseUrl(version: String): String = RELEASE_PREFIX + version.removePrefix("v")
}

/** 公开 Releases 无需帐号凭据; 没有发布或没有本机安装包时不会提示更新. */
internal class GitHubReleaseUpdateSource(
    private val repository: String = ForkUpdateRepository.NAME,
    private val apiBaseUrl: String = "https://api.github.com",
) {
    suspend fun checkLatestVersion(releaseClass: ReleaseClass, currentVersion: String): NewVersion? {
        val current = ReleaseVersion.parse(currentVersion) ?: return null
        val releases = HttpClient(getPlatformKtorEngine()) {
            expectSuccess = true
        }.use { client ->
            val response = client.get("$apiBaseUrl/repos/$repository/releases") {
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
                parameter("per_page", 100)
            }
            json.decodeFromString(ListSerializer(GitHubRelease.serializer()), response.bodyAsText())
        }
        val platform = currentPlatform()
        val available = releases.asSequence()
            .filterNot { it.draft }
            .mapNotNull { release -> ReleaseVersion.parse(release.tagName)?.let { release to it } }
            .filter { (release, version) ->
                version > current && version.releaseClass.ordinal >= releaseClass.ordinal &&
                        (releaseClass != ReleaseClass.STABLE || !release.prerelease)
            }
            .sortedByDescending { it.second }
            .toList()
        val latest = available.firstOrNull { (release, _) -> release.packageUrl(platform) != null }?.first
            ?: return null
        return NewVersion(
            name = latest.versionName,
            changelogs = available.dropWhile { it.first != latest }.take(10).map { (release, _) ->
                Changelog(release.versionName, release.formattedPublishTime(), release.body.orEmpty())
            },
            downloadUrlAlternatives = listOfNotNull(latest.packageUrl(platform)),
            publishedAt = latest.formattedPublishTime(),
            releasePageUrl = latest.htmlUrl,
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("published_at") val publishedAt: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubReleaseAsset> = emptyList(),
) {
    val versionName: String get() = tagName.removePrefix("v")

    fun formattedPublishTime(): String = publishedAt?.let { value ->
        runCatching { TimeFormatter().format(Instant.parse(value).toEpochMilliseconds()) }.getOrDefault(value)
    }.orEmpty()

    fun packageUrl(platform: Platform): String? = releasePackageNames(versionName, platform).firstNotNullOfOrNull { name ->
        assets.firstOrNull { it.name.equals(name, ignoreCase = true) && it.state == "uploaded" }
            ?.downloadUrl?.takeIf { it.startsWith("https://") }
    }
}

@Serializable
private data class GitHubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val state: String = "uploaded",
)

/** 按发布任务的文件名选择架构专用包, Android 支持 universal 回退. */
private fun releasePackageNames(version: String, platform: Platform): List<String> = when (platform) {
    is Platform.Android -> {
        val abi = when (platform.arch) {
            Arch.AARCH64, Arch.ARMV8A -> "arm64-v8a"
            Arch.ARMV7A -> "armeabi-v7a"
            Arch.X86_64 -> "x86_64"
        }
        val prefix = if (platform.isTv) "ani-tv" else "ani"
        listOf("$prefix-$version-$abi.apk", "$prefix-$version-universal.apk")
    }

    is Platform.Desktop -> {
        val os = platform.name.lowercase()
        val archs = when (platform.arch) {
            Arch.X86_64 -> listOf("x86_64", "x64")
            Arch.AARCH64, Arch.ARMV8A -> listOf("aarch64", "arm64")
            Arch.ARMV7A -> emptyList()
        }
        val extensions = when (platform) {
            is Platform.Windows -> listOf("zip")
            is Platform.MacOS -> listOf("dmg", "zip")
            is Platform.Linux -> listOf("AppImage")
        }
        extensions.flatMap { extension -> archs.map { arch -> "ani-$version-$os-$arch.$extension" } }
    }

    Platform.Ios -> listOf("ani-$version.ipa")
}

/** 数值版本及预发布序号参与比较, 构建元数据不影响更新顺序. */
private data class ReleaseVersion(
    val numbers: List<Long>,
    val prerelease: String?,
) : Comparable<ReleaseVersion> {
    val releaseClass: ReleaseClass
        get() = when {
            prerelease == null -> ReleaseClass.STABLE
            prerelease.startsWith("rc", ignoreCase = true) -> ReleaseClass.RC
            prerelease.startsWith("beta", ignoreCase = true) -> ReleaseClass.BETA
            else -> ReleaseClass.ALPHA
        }

    override fun compareTo(other: ReleaseVersion): Int {
        numbers.zip(other.numbers).forEach { (left, right) ->
            left.compareTo(right).takeIf { it != 0 }?.let { return it }
        }
        if (prerelease == null) return if (other.prerelease == null) 0 else 1
        if (other.prerelease == null) return -1
        val left = componentPattern.findAll(prerelease).map { it.value.lowercase() }.toList()
        val right = componentPattern.findAll(other.prerelease).map { it.value.lowercase() }.toList()
        left.zip(right).forEach { (a, b) ->
            val aNumber = a.toLongOrNull()
            val bNumber = b.toLongOrNull()
            val comparison = when {
                aNumber != null && bNumber != null -> aNumber.compareTo(bNumber)
                aNumber != null -> -1
                bNumber != null -> 1
                else -> a.compareTo(b)
            }
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }

    companion object {
        private val pattern = Regex("""^[vV]?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")
        private val componentPattern = Regex("[0-9]+|[A-Za-z]+")

        fun parse(value: String): ReleaseVersion? {
            val match = pattern.matchEntire(value) ?: return null
            val numbers = (1..3).map { match.groupValues[it].toLongOrNull() ?: return null }
            return ReleaseVersion(numbers, match.groupValues[4].ifEmpty { null })
        }
    }
}
