package com.nuvio.tv.domain.model.locallibrary

enum class SourceKind {
    LOCAL_FILE;

    companion object {
        fun fromString(value: String?): SourceKind? = when (value?.trim()?.uppercase()) {
            "LOCAL_FILE" -> LOCAL_FILE
            else -> null
        }
    }
}
