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
        val outputMah: Int? = null,
        val cellsMah: Int? = null,
        val cells: String? = null,
        val module: String? = null,
        val cutoffV: Double? = null,
        val notes: String? = null,
    )

    /**
     * PB-1 v1, serial 0001: ten new Panasonic NCR18650B in parallel (1S10P) behind a 22.5 W
     * QC4+/PD3.0 five-port module. Advertised as the product label states it: 34,000 mAh at
     * 3.6 V, and 20,000 mAh at 5 V of output under ideal conditions. The cells' datasheet
     * typical is 3,350 mAh each (minimum 3,200), 33,500 mAh together.
     */
    val PB1_V1_SN0001 = Preset(
        name = "PB-1 v1 SN0001",
        kind = SourceKind.POWER_BANK,
        ratedMah = 34_000,
        nominalV = 3.6,
        ratedW = 22.5,
        outputMah = 20_000,
        cellsMah = 33_500,
        cells = "10 × Panasonic NCR18650B, 1S10P",
        module = "22.5 W QC4+/PD3.0/PPS, 5 ports (SW6206 class)",
        cutoffV = 2.9,
        notes = "Under ideal conditions charges 20,000 mAh at 5 V.",
    )

    /** Anker Zolo 1C 30 W USB-C wall charger with the Anker A8060 240 W C-to-C cable. */
    val ANKER_ZOLO_30W = Preset(name = "Anker Zolo 30W (A8060 cable)", kind = SourceKind.CHARGER, ratedW = 30.0)

    val ALL: List<Preset> = listOf(PB1_V1_SN0001, ANKER_ZOLO_30W)

    /** Bumped when [ALL] gains a preset or new details, so they reach existing installs once. */
    const val VERSION = 4

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
                outputMah = p.outputMah,
                cellsMah = p.cellsMah,
                cells = p.cells,
                module = p.module,
                cutoffV = p.cutoffV,
                notes = p.notes,
            )
        }
    }

    /**
     * Existing rows that match a preset by name, with the preset's product details filled into
     * the fields still empty. A field the user already set is never overwritten.
     */
    fun detailsToFill(existing: List<PowerSource>): List<PowerSource> = existing.mapNotNull { s ->
        val p = ALL.firstOrNull { it.name.equals(s.name.trim(), ignoreCase = true) && it.kind == s.kind } ?: return@mapNotNull null
        val filled = s.copy(
            ratedW = s.ratedW ?: p.ratedW,
            outputMah = s.outputMah ?: p.outputMah,
            cellsMah = s.cellsMah ?: p.cellsMah,
            cells = s.cells ?: p.cells,
            module = s.module ?: p.module,
            cutoffV = s.cutoffV ?: p.cutoffV,
            notes = s.notes ?: p.notes,
        )
        filled.takeIf { it != s }
    }
}
