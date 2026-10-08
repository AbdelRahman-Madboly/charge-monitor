package com.vinex.chargemonitor.data

/**
 * Power banks and chargers added once, on the first launch of a build that knows about them, so
 * hardware that was built or bought for testing arrives with its label figures instead of being
 * typed in on the phone.
 *
 * Never touches an existing source: a preset whose name is already taken (by any source,
 * archived or not) is skipped, so a user who added it by hand keeps their own row and sessions.
 */
object SourcePresets {

    data class Preset(
        val name: String,
        val kind: SourceKind,
        val ratedMah: Int? = null,
        val nominalV: Double = 3.7,
        val ratedW: Double? = null,
    )

    /**
     * PB-1 v1, serial 0001: ten new Panasonic NCR18650B in parallel (1S10P) behind a 22.5 W
     * QC4+/PD3.0 five-port module. Rated as the product label states it: 34,000 mAh at 3.6 V,
     * about 122 Wh of cell energy (datasheet typical 3,350 mAh a cell, minimum 3,200).
     */
    val PB1_V1_SN0001 = Preset(
        name = "PB-1 v1 SN0001",
        kind = SourceKind.POWER_BANK,
        ratedMah = 34_000,
        nominalV = 3.6,
    )

    /** Anker Zolo 1C 30 W USB-C wall charger with the Anker A8060 240 W C-to-C cable. */
    val ANKER_ZOLO_30W = Preset(name = "Anker Zolo 30W (A8060 cable)", kind = SourceKind.CHARGER, ratedW = 30.0)

    val ALL: List<Preset> = listOf(PB1_V1_SN0001, ANKER_ZOLO_30W)

    /** Bumped when [ALL] gains a preset, so the new one is offered once to existing installs. */
    const val VERSION = 3

    /** The phone's main charger: the one the "Connected to?" prompt offers by default. */
    val MAIN_CHARGER: Preset = ANKER_ZOLO_30W

    /**
     * Rows to insert: presets whose name no existing source uses (case-insensitive), each given
     * the first palette colour no active user source already has, so it stands apart on charts.
     */
    fun toAdd(existing: List<PowerSource>, palette: List<Int>): List<PowerSource> {
        val taken = existing.map { it.name.trim().lowercase() }.toMutableSet()
        val used = existing.filter { !it.builtIn && !it.archived }.map { it.colorArgb }.toMutableSet()
        return ALL.mapNotNull { p ->
            if (!taken.add(p.name.lowercase())) return@mapNotNull null
            val color = palette.firstOrNull { it !in used } ?: palette.first()
            used += color
            PowerSource(
                name = p.name,
                kind = p.kind,
                ratedMah = p.ratedMah,
                nominalV = p.nominalV,
                colorArgb = color,
                ratedW = p.ratedW,
            )
        }
    }
}
