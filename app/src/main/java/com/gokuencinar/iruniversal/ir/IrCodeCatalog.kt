package com.gokuencinar.iruniversal.ir

object IrCodeCatalog {
    fun scanCodes(category: DeviceCategory, region: TvRegion): List<IrCode> =
        dedupe(
            when (category) {
                DeviceCategory.TELEVISION -> buildList {
                    addAll(UniversalPowerCodes.codes)
                    addAll(
                        when (region) {
                            TvRegion.EUROPE -> GeneratedTvBGoneDatabase.europe
                            TvRegion.NORTH_AMERICA -> GeneratedTvBGoneDatabase.northAmerica
                        }
                    )
                    addAll(GeneratedFlipperPowerDatabase.televisions)
                    addAll(GeneratedExtendedIrDatabase.televisionPowerExtras)
                }

                DeviceCategory.AIR_CONDITIONER ->
                    GeneratedFlipperPowerDatabase.airConditioners

                DeviceCategory.PROJECTOR -> buildList {
                    addAll(GeneratedFlipperPowerDatabase.projectors)
                    addAll(GeneratedExtendedIrDatabase.projectorPowerExtras)
                }
            }
        )

    fun codes(category: DeviceCategory, region: TvRegion): List<IrCode> =
        dedupe(
            buildList {
                addAll(scanCodes(category, region))
                when (category) {
                    DeviceCategory.TELEVISION ->
                        addAll(GeneratedExtendedIrDatabase.televisionLibrary)

                    DeviceCategory.AIR_CONDITIONER ->
                        addAll(GeneratedExtendedIrDatabase.airConditionerLibrary)

                    DeviceCategory.PROJECTOR ->
                        addAll(GeneratedExtendedIrDatabase.projectorLibrary)
                }
            }
        )

    fun find(id: String, category: DeviceCategory, region: TvRegion): IrCode? =
        codes(category, region).firstOrNull { it.id == id }

    private fun dedupe(codes: List<IrCode>): List<IrCode> =
        codes.distinctBy { code ->
            code.effectiveCarrierHz to code.durationsMicros
        }
}
