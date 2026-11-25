package com.abhinavxt.debforge.domain

/** Library poster size: the minimum tile width of the adaptive grid. Names are persisted. */
enum class GridSize(val minDp: Int) {
    SMALL(84), MEDIUM(112), LARGE(160);

    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: MEDIUM
    }
}
