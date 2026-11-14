/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.domain

/**
 * US states, DC and Puerto Rico with FIPS codes (RepeaterBook's state_id) and an approximate
 * bounding box used only to decide which states a "near me" circle can reach.
 */
enum class UsState(
    val abbr: String,
    val displayName: String,
    val fips: String,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
) {
    AL("AL", "Alabama", "01", 30.14, 35.01, -88.47, -84.89),
    AK("AK", "Alaska", "02", 51.20, 71.40, -179.15, -129.98),
    AZ("AZ", "Arizona", "04", 31.33, 37.00, -114.82, -109.04),
    AR("AR", "Arkansas", "05", 33.00, 36.50, -94.62, -89.64),
    CA("CA", "California", "06", 32.53, 42.01, -124.41, -114.13),
    CO("CO", "Colorado", "08", 36.99, 41.00, -109.06, -102.04),
    CT("CT", "Connecticut", "09", 40.98, 42.05, -73.73, -71.79),
    DE("DE", "Delaware", "10", 38.45, 39.84, -75.79, -75.05),
    DC("DC", "District of Columbia", "11", 38.79, 38.99, -77.12, -76.91),
    FL("FL", "Florida", "12", 24.52, 31.00, -87.63, -80.03),
    GA("GA", "Georgia", "13", 30.36, 35.00, -85.61, -80.84),
    HI("HI", "Hawaii", "15", 18.91, 22.24, -160.25, -154.81),
    ID("ID", "Idaho", "16", 41.99, 49.00, -117.24, -111.04),
    IL("IL", "Illinois", "17", 36.97, 42.51, -91.51, -87.49),
    IN("IN", "Indiana", "18", 37.77, 41.76, -88.10, -84.78),
    IA("IA", "Iowa", "19", 40.38, 43.50, -96.64, -90.14),
    KS("KS", "Kansas", "20", 36.99, 40.00, -102.05, -94.59),
    KY("KY", "Kentucky", "21", 36.50, 39.15, -89.57, -81.96),
    LA("LA", "Louisiana", "22", 28.93, 33.02, -94.04, -88.82),
    ME("ME", "Maine", "23", 43.06, 47.46, -71.08, -66.95),
    MD("MD", "Maryland", "24", 37.91, 39.72, -79.49, -75.05),
    MA("MA", "Massachusetts", "25", 41.24, 42.89, -73.51, -69.93),
    MI("MI", "Michigan", "26", 41.70, 48.31, -90.42, -82.41),
    MN("MN", "Minnesota", "27", 43.50, 49.38, -97.24, -89.49),
    MS("MS", "Mississippi", "28", 30.17, 35.00, -91.66, -88.10),
    MO("MO", "Missouri", "29", 35.99, 40.61, -95.77, -89.10),
    MT("MT", "Montana", "30", 44.36, 49.00, -116.05, -104.04),
    NE("NE", "Nebraska", "31", 40.00, 43.00, -104.05, -95.31),
    NV("NV", "Nevada", "32", 35.00, 42.00, -120.01, -114.04),
    NH("NH", "New Hampshire", "33", 42.70, 45.31, -72.56, -70.61),
    NJ("NJ", "New Jersey", "34", 38.93, 41.36, -75.56, -73.89),
    NM("NM", "New Mexico", "35", 31.33, 37.00, -109.05, -103.00),
    NY("NY", "New York", "36", 40.50, 45.02, -79.76, -71.86),
    NC("NC", "North Carolina", "37", 33.84, 36.59, -84.32, -75.46),
    ND("ND", "North Dakota", "38", 45.94, 49.00, -104.05, -96.55),
    OH("OH", "Ohio", "39", 38.40, 41.98, -84.82, -80.52),
    OK("OK", "Oklahoma", "40", 33.62, 37.00, -103.00, -94.43),
    OR("OR", "Oregon", "41", 41.99, 46.29, -124.57, -116.46),
    PA("PA", "Pennsylvania", "42", 39.72, 42.27, -80.52, -74.69),
    RI("RI", "Rhode Island", "44", 41.15, 42.02, -71.86, -71.12),
    SC("SC", "South Carolina", "45", 32.03, 35.22, -83.35, -78.54),
    SD("SD", "South Dakota", "46", 42.48, 45.95, -104.06, -96.44),
    TN("TN", "Tennessee", "47", 34.98, 36.68, -90.31, -81.65),
    TX("TX", "Texas", "48", 25.84, 36.50, -106.65, -93.51),
    UT("UT", "Utah", "49", 37.00, 42.00, -114.05, -109.04),
    VT("VT", "Vermont", "50", 42.73, 45.02, -73.44, -71.46),
    VA("VA", "Virginia", "51", 36.54, 39.47, -83.68, -75.24),
    WA("WA", "Washington", "53", 45.54, 49.00, -124.76, -116.92),
    WV("WV", "West Virginia", "54", 37.20, 40.64, -82.64, -77.72),
    WI("WI", "Wisconsin", "55", 42.49, 47.08, -92.89, -86.25),
    WY("WY", "Wyoming", "56", 40.99, 45.01, -111.06, -104.05),
    PR("PR", "Puerto Rico", "72", 17.88, 18.52, -67.27, -65.22);

    /** True when a circle of [radiusKm] around the point can overlap this state's box. */
    fun reachableFrom(lat: Double, lon: Double, radiusKm: Double): Boolean {
        val dLat = radiusKm / 111.0
        val dLon = radiusKm / (111.0 * kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.05))
        return lat + dLat >= minLat && lat - dLat <= maxLat && lon + dLon >= minLon && lon - dLon <= maxLon
    }

    companion object {
        private val byName = entries.associateBy { it.displayName.lowercase() }
        private val byAbbr = entries.associateBy { it.abbr }
        private val COUNTRY_SUFFIX = Regex("\\s+(USA|US|United States)$", RegexOption.IGNORE_CASE)

        fun fromAbbr(abbr: String): UsState? = byAbbr[abbr.trim().uppercase()]

        /** Matches "TX", "Texas" or "texas". */
        fun parse(value: String?): UsState? {
            val v = value?.trim().orEmpty()
            if (v.isEmpty()) return null
            return byAbbr[v.uppercase()] ?: byName[v.lowercase()]
        }

        /**
         * Extracts a state from free text locations like "Allen, TX", "Merced, California",
         * "Parker, CO USA" or "Vancouver, BC Canada" (the last returns null).
         */
        fun fromLocationText(text: String?): UsState? {
            if (text.isNullOrBlank()) return null
            val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            for (part in parts.drop(1).reversed()) {
                if (part.contains("Canada", ignoreCase = true)) return null
                parse(part.replace(COUNTRY_SUFFIX, "").trim())?.let { return it }
            }
            // "Edinburg TX" style: a trailing two letter abbreviation without a comma.
            if (text.contains("Canada", ignoreCase = true)) return null
            val last = text.replace(COUNTRY_SUFFIX, "").trim().substringAfterLast(' ')
            if (last.length == 2) fromAbbr(last)?.let { return it }
            return null
        }
    }
}
