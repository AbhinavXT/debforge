package com.abhinavxt.debforge.domain

/**
 * Which signed-in service the Add dialog sends to. Pure: unit-tested in
 * ServicePickerTest.
 *
 * Order of preference:
 *  1. what the user tapped, as long as that service can take everything in
 *     the dialog;
 *  1b. [preferred] when it can take the input (direct download for
 *     Pixeldrain / Google Drive / plain file links);
 *  2. the active service (the one the Library shows), if it has everything
 *     cached, or if no other service does;
 *  3. another service that has everything cached (ready instantly);
 *  4. otherwise the active service if it can take the input, else the first
 *     one that can (e.g. magnets while Real-Debrid, which can't take them,
 *     is active).
 */
object ServicePicker {

    data class Option(
        val id: ProviderId,
        /** Can take every kind of item in the dialog (magnet / .torrent / link). */
        val supportsInput: Boolean,
        /** Checked, and every torrent in the dialog is cached there. */
        val allCached: Boolean
    )

    fun pick(
        active: ProviderId,
        options: List<Option>,
        current: ProviderId?,
        userPicked: Boolean,
        preferred: ProviderId? = null
    ): ProviderId {
        val usable = options.filter { it.supportsInput }
        if (userPicked && usable.any { it.id == current }) return current!!
        if (preferred != null && usable.any { it.id == preferred }) return preferred
        if (usable.isEmpty()) return active // nothing fits; submitting explains why
        val activeOption = usable.firstOrNull { it.id == active }
        if (activeOption?.allCached == true) return active
        usable.firstOrNull { it.allCached }?.let { return it.id }
        return activeOption?.id ?: usable.first().id
    }
}
