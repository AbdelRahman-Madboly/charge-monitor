package com.vinex.chargemonitor.stats

/**
 * Where a power bank's energy goes on its way into the phone battery, worked out per reading.
 *
 * The phone only measures the last link (power into its battery). Everything upstream is
 * modelled, and every loss depends on power, so one fixed "loss factor" is wrong at both ends: it
 * overstates the bank at the taper (1 W into the battery still costs the phone's own draw and the
 * converters' fixed losses) and understates it at full speed.
 *
 *     cells ──bank boost──▶ USB port ──cable + phone charger──▶ phone ──▶ battery
 *                                                                  └──▶ phone's own draw
 *
 * For one reading with battery power `Pb` (W, negative while draining):
 *
 *     phone draw   Ps   = idle + screen-on extra (when the screen is on)
 *     into phone   Pp   = max(0, Pb + Ps)                       what the charger IC puts out
 *     at the port  Pin  = Pp + phoneLoss(Pp)                    phone charger + cable
 *     from cells   Pc   = Pin + bankLoss(Pin)                   bank boost converter
 *
 * Each converter loss is `fixed + linear × P + quadratic × P²`: a fixed draw (controller,
 * indicator), switching loss that grows with power, and resistive loss that grows with current.
 * The bank defaults describe PB-1's 22.5 W five-port module (an SW6206: boost up to 95 %, 9 V/2 A
 * class boards measure 92 %) together with the pack's resistance (ten NCR18650B in parallel plus
 * wiring and contacts, about 20 mΩ, so 0.7 W at 20 W out). Together: about 91 % at 5 W, 90 % at
 * 10 W, 89 % at 18 W, and under 80 % below 0.5 W where the fixed draw dominates. The phone side
 * is the A54's charger plus a 1 m cable, about 91 % over most of the range.
 *
 * When the battery drains while plugged (bank switched off or too weak), [Flow.intoPhoneW] is 0
 * and nothing is charged to the bank.
 */
object EnergyModel {

    /** One converter's loss curve. */
    data class Loss(val fixedW: Double, val linear: Double, val quadraticPerW: Double) {
        fun at(outputW: Double): Double =
            if (outputW <= 0.0) 0.0 else fixedW + linear * outputW + quadraticPerW * outputW * outputW
    }

    data class Params(
        /** Phone draw with the screen off while plugged in (radios, the recording service). */
        val phoneIdleW: Double = DEFAULT_PHONE_IDLE_W,
        /** Extra draw with the screen on; measured on this phone at 1.2 to 1.4 W in light use. */
        val phoneScreenW: Double = DEFAULT_PHONE_SCREEN_W,
        val phoneCharger: Loss = PHONE_CHARGER,
        val bank: Loss = BANK_22W5,
    )

    /** One reading, every figure in W. */
    data class Flow(
        val batteryW: Double,
        val phoneUseW: Double,
        val intoPhoneW: Double,
        val portW: Double,
        val cellsW: Double,
    ) {
        val phoneLossW: Double get() = portW - intoPhoneW
        val bankLossW: Double get() = cellsW - portW
        val totalLossW: Double get() = cellsW - batteryW.coerceAtLeast(0.0)

        /** Battery power over cell power; null when the bank gives nothing. */
        val efficiency: Double? get() = if (cellsW > 0.0) batteryW.coerceAtLeast(0.0) / cellsW else null
    }

    const val DEFAULT_PHONE_IDLE_W = 0.30
    const val DEFAULT_PHONE_SCREEN_W = 1.30

    val PHONE_CHARGER = Loss(fixedW = 0.10, linear = 0.05, quadraticPerW = 0.0025)
    val BANK_22W5 = Loss(fixedW = 0.10, linear = 0.06, quadraticPerW = 0.0035)

    val DEFAULT = Params()

    fun flow(batteryW: Double, screenOn: Boolean, params: Params = DEFAULT): Flow {
        val use = params.phoneIdleW.coerceAtLeast(0.0) + if (screenOn) params.phoneScreenW.coerceAtLeast(0.0) else 0.0
        // When the battery drains, the source covers only part of the phone's draw: the rest
        // comes out of the battery. Whatever is left over is what the source gave.
        val intoPhone = (batteryW + use).coerceAtLeast(0.0)
        val phoneUse = if (batteryW >= 0.0) use else intoPhone
        val port = intoPhone + params.phoneCharger.at(intoPhone)
        val cells = port + params.bank.at(port)
        return Flow(batteryW, phoneUse, intoPhone, port, cells)
    }

    /**
     * Cell energy for [batteryWh] that went in over [hours] with no readings in between (a gap
     * bridged by the charge counter): the average power stands in for every reading.
     */
    fun bridged(batteryWh: Double, hours: Double, screenOn: Boolean, params: Params = DEFAULT): Flow? {
        if (hours <= 0.0) return null
        val f = flow(batteryWh / hours, screenOn, params)
        return Flow(f.batteryW * hours, f.phoneUseW * hours, f.intoPhoneW * hours, f.portW * hours, f.cellsW * hours)
    }

    /** End-to-end efficiency at a steady battery power, for the label promise. */
    fun efficiencyAt(batteryW: Double, params: Params = DEFAULT): Double =
        flow(batteryW, screenOn = false, params).efficiency ?: 0.0

    /**
     * Battery power the label promise is worked at: a typical full charge of this phone from a
     * 22.5 W bank averages about 9 W into the battery once the taper is included.
     */
    const val TYPICAL_BATTERY_W = 9.0
}
