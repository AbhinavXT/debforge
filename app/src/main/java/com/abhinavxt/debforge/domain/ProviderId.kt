package com.abhinavxt.debforge.domain

/**
 * Every debrid service the app can talk to. The enum NAME is persisted (Room
 * column `downloads.provider`, DataStore `active_provider`), so never rename an
 * existing entry — only add new ones.
 *
 * Adding a service = add an entry here + implement DebridProvider + bind it
 * in ProviderModule. Nothing else in the app needs to change.
 */
enum class ProviderId {
    REAL_DEBRID,
    TORBOX,
    ALL_DEBRID,
    PREMIUMIZE,
    DEBRID_LINK;

    companion object {
        fun fromName(name: String?): ProviderId? = entries.firstOrNull { it.name == name }
    }
}
