package com.drasite.mercedesobd

import java.util.Locale

data class EcuProfile(
    val software: String,
    val softwareAddress: Int,
    val ltftLowAddress: Int = 0x900C,
    val ltftHighAddress: Int = 0x9014,
    val additiveAddress: Int = 0x9020,
)

/** References and RAM layouts verified in the ten analysed original binaries.
 * A calibration edit may retain the software reference. This selects correction
 * addresses, not a different injection calibration or timer scale.
 */
object EcuProfiles {
    val all = listOf(
        EcuProfile("114.235034.34", 0xE2AC),
        EcuProfile("114.235153.52", 0xE492),
        EcuProfile("114.235035.52", 0xE492),
        EcuProfile("114.235355.56", 0xE492),
        EcuProfile("114.235404.04", 0xE496),
        EcuProfile("114.235516.16", 0xE49E),
        EcuProfile("114.235819.20", 0xE49E),
        EcuProfile("114.236338.39", 0xE4C6),
        EcuProfile("114.236438.40", 0xE4C6),
        EcuProfile("114.236643.43", 0xD4DC, 0x920C, 0x9214, 0x9220),
    )
    val bySoftware = all.associateBy { it.software }
}

internal fun addressHex(address: Int): String = "0x%04X".format(Locale.ROOT, address)
