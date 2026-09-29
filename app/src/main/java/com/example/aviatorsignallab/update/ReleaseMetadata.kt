package com.example.aviatorsignallab.update

import com.google.gson.annotations.SerializedName

data class ReleaseMetadata(
    @SerializedName("versionCode")
    val versionCode: Int,

    @SerializedName("versionName")
    val versionName: String,

    @SerializedName("releaseNotes")
    val releaseNotes: String?,

    @SerializedName("apkUrl")
    val apkUrl: String,

    @SerializedName("publishedAt")
    val publishedAt: String?
)
