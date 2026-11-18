/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Ported from NTIA's Irregular Terrain Model (https://github.com/NTIA/itm), a public domain
 * work of the U.S. Government. Changed 2026: translated from C++ to Kotlin.
 */
package com.vyntric.offset.propagation.itm

/** One reference P2P case: NTIA's inputs plus the expected outputs (null where not published). */
internal class ItmCase(
    val id: String,
    val profile: DoubleArray,
    val hTx: Double,
    val hRx: Double,
    val climate: Int,
    val n0: Double,
    val fMhz: Double,
    val pol: Int,
    val epsilon: Double,
    val sigma: Double,
    val mdvar: Int,
    val time: Double,
    val location: Double,
    val situation: Double,
    val expectedDb: Double?,
    val expectedWarnings: Int?,
    val expectedMode: Int?,
    /** NTIA return code: 0 or 1 for success, 1000 and up for an error. */
    val expectedRtn: Int?,
) {
    fun run(): ItmResult = Itm.pointToPointRaw(
        hTx, hRx, profile, climate, n0, fMhz, pol, epsilon, sigma, mdvar, time, location, situation,
    )
}

/** Loads the CSV reference sets under src/test/resources/itm/ (see the comments at the top of each file). */
internal object ItmReferenceData {
    /** Results NTIA publishes in its repository. */
    val ntiaPublished: List<ItmCase> by lazy { load("ntia_published_cases.csv", "ntia_profiles.csv") }

    /** Results of NTIA's C++ built locally, over a varied set of paths. */
    val localReference: List<ItmCase> by lazy { load("reference_cases.csv", "reference_profiles.csv") }

    val all: List<ItmCase> get() = ntiaPublished + localReference

    private fun lines(name: String): List<String> {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("itm/$name")) { "missing itm/$name" }
        return stream.bufferedReader().use { r -> r.readLines().filter { it.isNotBlank() && !it.startsWith("#") } }
    }

    private fun load(casesFile: String, profilesFile: String): List<ItmCase> {
        val profiles = lines(profilesFile).associate { line ->
            val f = line.split(',')
            f[0] to DoubleArray(f.size - 1) { f[it + 1].toDouble() }
        }
        val all = lines(casesFile)
        val header = all.first().split(',')
        fun col(name: String) = header.indexOf(name).also { check(it >= 0) { "$casesFile has no $name column" } }
        fun optCol(name: String) = header.indexOf(name)
        val iWarn = optCol("warnings")
        val iMode = optCol("mode")
        val iRtn = optCol("rtn")
        return all.drop(1).map { line ->
            val f = line.split(',')
            fun d(name: String) = f[col(name)].toDouble()
            fun i(name: String) = f[col(name)].toInt()
            fun opt(idx: Int) = if (idx < 0 || idx >= f.size || f[idx].isBlank()) null else f[idx].toInt()
            val rtn = opt(iRtn)
            val isError = rtn != null && rtn >= 1000
            ItmCase(
                id = f[col("id")],
                profile = checkNotNull(profiles[f[col("profile")]]) { "unknown profile in $line" },
                hTx = d("h_tx"), hRx = d("h_rx"), climate = i("climate"), n0 = d("N_0"), fMhz = d("f_mhz"),
                pol = i("pol"), epsilon = d("epsilon"), sigma = d("sigma"), mdvar = i("mdvar"),
                time = d("time"), location = d("location"), situation = d("situation"),
                expectedDb = if (isError) null else d("A_db"),
                expectedWarnings = if (isError) null else opt(iWarn),
                expectedMode = if (isError) null else opt(iMode),
                expectedRtn = rtn,
            )
        }
    }
}
