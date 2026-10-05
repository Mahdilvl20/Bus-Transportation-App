package com.example.util

import androidx.compose.ui.graphics.Color
import java.util.Locale
import kotlin.math.abs

sealed class EtaDisplay {
    data class Active(val minutes: String, val rawText: String) : EtaDisplay()
    data class OutOfService(val serviceStartTime: String) : EtaDisplay()

    /** The bus is at (or one minute from) the stop: no number, only the words. */
    object Arriving : EtaDisplay()

    object None : EtaDisplay()
}

object PersianUtils {

    private val PERSIAN_DIGITS = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
    private val ARABIC_DIGITS = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')

    /**
     * Converts any ASCII or Arabic-Indic digits to Persian digits
     */
    fun toPersianDigits(input: Any?): String {
        if (input == null) return ""
        val str = input.toString()
        val builder = StringBuilder(str.length)
        for (ch in str) {
            when (ch) {
                in '0'..'9' -> builder.append(PERSIAN_DIGITS[ch - '0'])
                in '٠'..'٩' -> builder.append(PERSIAN_DIGITS[ch - '٠'])
                else -> builder.append(ch)
            }
        }
        return builder.toString()
    }

    /**
     * Formats distance with meter / kilometer in Persian
     */
    fun formatDistance(meters: Double): String {
        return if (meters < 1000) {
            val m = meters.toInt()
            "${toPersianDigits(m)} متر"
        } else {
            val km = meters / 1000.0
            val formatted = String.format(Locale.US, "%.1f", km)
            "${toPersianDigits(formatted)} کیلومتر"
        }
    }

    /**
     * Normalizes text for search:
     * - unify 'ي' -> 'ی', 'ك' -> 'ک'
     * - unify 'ۀ' -> 'ه'
     * - strip diacritics (َ, ِ, ُ, ً, ٍ, ٌ, ْ, ّ)
     * - strip tatweel (ـ)
     * - ignore all spaces and punctuation
     */
    fun normalizeForSearch(text: String): String {
        var result = text
            .replace('ي', 'ی')
            .replace('ك', 'ک')
            .replace('ۀ', 'ه')
            .replace('ة', 'ه')
            .replace('آ', 'ا')
            .replace('إ', 'ا')
            .replace('أ', 'ا')
            .replace("ـ", "") // tatweel
            .replace(Regex("[\u064B-\u065F\u0670]"), "") // Arabic diacritics

        // Remove all whitespace and dashes/punctuations so "مسجدالمهدی" matches "مسجد المهدی"
        result = result.replace(Regex("[\\s\\-_/(),.]"), "")
        return result.lowercase()
    }

    /**
     * Extracts station code from pname: e.g. "مسجدالمهدی - کد ایستگاه : 2229"
     */
    fun extractStationCode(pname: String?): String? {
        if (pname.isNullOrBlank()) return null
        val regex = Regex("کد\\s*ایستگاه\\s*[:：]\\s*(\\d+)")
        val match = regex.find(pname)
        return match?.groupValues?.get(1)
    }

    /**
     * Parses ETA string according to requirements:
     * - If contains digits: extracts number e.g. "6 دقیقه" -> Active("6")
     * - If contains "شروع سرویس از XX:XX" or no digits -> OutOfService("۰۶:۰۰")
     */
    fun parseEta(rawEta: String?): EtaDisplay {
        if (rawEta.isNullOrBlank()) return EtaDisplay.None

        // Check for out-of-service time pattern like "شروع سرویس از 06:00" or time "06:00"
        val timeRegex = Regex("(\\d{1,2}:\\d{2})")
        val timeMatch = timeRegex.find(rawEta)
        if (rawEta.contains("شروع") || rawEta.contains("سرویس") && timeMatch != null) {
            val time = timeMatch?.value ?: "06:00"
            return EtaDisplay.OutOfService(toPersianDigits(time))
        }

        // Extract Latin digits for active arrival
        val digitRegex = Regex("\\d+")
        val match = digitRegex.find(rawEta)
        return if (match != null) {
            val minutes = match.value
            // Zero minutes is not a number the user can act on — it is "now arriving".
            if (minutes.toIntOrNull() == 0) EtaDisplay.Arriving
            else EtaDisplay.Active(minutes = toPersianDigits(minutes), rawText = rawEta)
        } else {
            EtaDisplay.OutOfService(toPersianDigits("06:00"))
        }
    }

    /**
     * Generates a stable unique color for a transit line based on lineId
     */
    fun getLineColor(lineId: Int?): Color {
        if (lineId == null) return Color(0xFF2563EB)
        // Hash to hue between 0 and 360
        val hue = (abs(lineId.hashCode()) % 360).toFloat()
        return Color.hsl(hue = hue, saturation = 0.72f, lightness = 0.45f)
    }
}
