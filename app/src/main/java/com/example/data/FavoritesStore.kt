package com.example.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A saved stop. [label] is what the user typed; blank means "use the official
 * name from the bundled stop list".
 */
data class FavoriteStop(val id: Long, val label: String)

/**
 * Local-only favourites. The ids and labels never leave the device: there is no
 * request that carries them, and nothing here talks to the network.
 *
 * Stored as a JSON *array* because order decides the tile order, which an
 * object would not preserve.
 */
class FavoritesStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun list(): List<FavoriteStop> = read()

    fun isFavorite(id: Long): Boolean = read().any { it.id == id }

    /** The user's label if one exists, otherwise the official bundled name. */
    fun labelFor(id: Long, officialName: String): String =
        read().firstOrNull { it.id == id }?.label?.takeIf { it.isNotBlank() } ?: officialName

    fun add(id: Long) {
        val items = read()
        if (items.any { it.id == id }) return
        write(items + FavoriteStop(id, ""))
    }

    fun remove(id: Long) {
        val items = read()
        if (items.none { it.id == id }) return
        write(items.filterNot { it.id == id })
    }

    /**
     * Blank or over-long labels are refused so the official name keeps showing —
     * an empty tile label would be worse than no customization at all.
     */
    fun rename(id: Long, label: String): Boolean {
        val clean = label.trim()
        if (clean.isEmpty() || clean.length > MAX_LABEL_LENGTH) return false
        val items = read()
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        write(items.mapIndexed { i, item -> if (i == index) item.copy(label = clean) else item })
        return true
    }

    // Re-read on every call instead of caching: two screens can hold their own
    // store instance, and a stale cache would silently drop a rename.
    private fun read(): List<FavoriteStop> = FavoritesCodec.decode(prefs.getString(KEY, null))

    private fun write(items: List<FavoriteStop>) {
        prefs.edit().putString(KEY, FavoritesCodec.encode(items)).apply()
    }

    companion object {
        const val MAX_LABEL_LENGTH = 24
        private const val PREFS_NAME = "favorites"
        private const val KEY = "stops"
    }
}

/** JSON (de)serialization, separate from the store so it stays unit-testable. */
object FavoritesCodec {

    fun encode(items: List<FavoriteStop>): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().put("id", item.id).put("label", item.label))
        }
        return array.toString()
    }

    /** A corrupt or absent value degrades to an empty list, never to a crash. */
    fun decode(raw: String?): List<FavoriteStop> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optLong("id", -1L)
                if (id < 0) null else FavoriteStop(id, obj.optString("label", ""))
            }
        }.getOrDefault(emptyList())
    }
}
