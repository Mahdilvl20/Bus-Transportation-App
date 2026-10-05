package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FavoritesStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences("favorites", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `a fresh favorite falls back to the official name`() {
        store().add(116929)
        assertEquals("مسجدالمهدی", store().labelFor(116929, "مسجدالمهدی"))
        assertTrue(store().isFavorite(116929))
    }

    @Test
    fun `adding twice keeps a single entry`() {
        val s = store()
        s.add(116929)
        s.add(116929)
        assertEquals(1, s.list().size)
    }

    @Test
    fun `rename survives a new store instance`() {
        store().add(116929)
        assertTrue(store().rename(116929, "خانه"))
        assertEquals("خانه", store().labelFor(116929, "مسجدالمهدی"))
    }

    @Test
    fun `blank rename is rejected and keeps the official name`() {
        val s = store()
        s.add(116929)
        s.rename(116929, "خانه")
        assertFalse(s.rename(116929, "   "))
        assertEquals("خانه", s.labelFor(116929, "مسجدالمهدی"))
    }

    @Test
    fun `over long rename is rejected`() {
        val s = store()
        s.add(116929)
        assertFalse(s.rename(116929, "a".repeat(FavoritesStore.MAX_LABEL_LENGTH + 1)))
        assertTrue(s.rename(116929, "a".repeat(FavoritesStore.MAX_LABEL_LENGTH)))
    }

    @Test
    fun `rename of an unknown stop does nothing`() {
        assertFalse(store().rename(424242, "خانه"))
    }

    @Test
    fun `removing one stop leaves the others`() {
        val s = store()
        s.add(1)
        s.add(2)
        s.add(3)
        s.rename(2, "مدرسه")
        s.remove(2)
        assertEquals(listOf(1L, 3L), s.list().map { it.id })
        assertFalse(s.isFavorite(2))
    }

    @Test
    fun `insertion order is the tile order`() {
        val s = store()
        s.add(30)
        s.add(10)
        s.add(20)
        assertEquals(listOf(30L, 10L, 20L), s.list().map { it.id })
    }

    @Test
    fun `corrupt preference decodes to an empty list`() {
        assertEquals(emptyList<FavoriteStop>(), FavoritesCodec.decode("{not json"))
        assertEquals(emptyList<FavoriteStop>(), FavoritesCodec.decode(null))
        assertEquals(emptyList<FavoriteStop>(), FavoritesCodec.decode(""))
    }

    @Test
    fun `codec round trip keeps ids labels and order`() {
        val items = listOf(FavoriteStop(116929, "خانه"), FavoriteStop(116930, ""))
        assertEquals(items, FavoritesCodec.decode(FavoritesCodec.encode(items)))
    }

    private fun store() = FavoritesStore(context)
}
