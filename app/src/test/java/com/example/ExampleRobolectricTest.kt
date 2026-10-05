package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.util.EtaDisplay
import com.example.util.PersianUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("اتوبوس اصفهان", appName)
  }

  @Test
  fun `test persian digit conversion`() {
    val result = PersianUtils.toPersianDigits(1394)
    assertEquals("۱۳۹۴", result)
  }

  @Test
  fun `test distance formatting`() {
    val underKm = PersianUtils.formatDistance(450.0)
    assertEquals("۴۵۰ متر", underKm)

    val overKm = PersianUtils.formatDistance(2500.0)
    assertTrue(overKm.contains("کیلومتر"))
  }

  @Test
  fun `test persian search normalization`() {
    val name1 = PersianUtils.normalizeForSearch("مسجدالمهدی")
    val name2 = PersianUtils.normalizeForSearch("مسجد المهدی")
    assertEquals(name1, name2)

    val arabicYeh = PersianUtils.normalizeForSearch("خيابان آزادي")
    val persianYeh = PersianUtils.normalizeForSearch("خیابان آزادی")
    assertEquals(arabicYeh, persianYeh)
  }

  @Test
  fun `test eta parsing`() {
    val activeEta = PersianUtils.parseEta("6 دقیقه ")
    assertTrue(activeEta is EtaDisplay.Active)
    assertEquals("۶", (activeEta as EtaDisplay.Active).minutes)

    val outOfService = PersianUtils.parseEta("شروع سرویس از 06:00")
    assertTrue(outOfService is EtaDisplay.OutOfService)
    assertEquals("۰۶:۰۰", (outOfService as EtaDisplay.OutOfService).serviceStartTime)
  }

  @Test
  fun `zero minute eta reads as arriving`() {
    assertTrue(PersianUtils.parseEta("0 دقیقه") is EtaDisplay.Arriving)
    assertTrue(PersianUtils.parseEta("0.5 دقیقه") is EtaDisplay.Arriving)
    assertTrue(PersianUtils.parseEta("7 دقیقه") is EtaDisplay.Active)
  }

  @Test
  fun `test station code extraction`() {
    val pname = "مسجدالمهدی - کد ایستگاه : 2229"
    val code = PersianUtils.extractStationCode(pname)
    assertEquals("2229", code)
  }
}
