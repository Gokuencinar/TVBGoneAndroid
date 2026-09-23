package com.gokuencinar.iruniversal.ir

object IrCodeCatalog {
    fun codes(category: DeviceCategory, region: TvRegion): List<IrCode> {
        return when (category) {
            DeviceCategory.TELEVISION -> buildList {
                addAll(UniversalPowerCodes.codes)
                addAll(
                    when (region) {
                        TvRegion.EUROPE -> GeneratedTvBGoneDatabase.europe
                        TvRegion.NORTH_AMERICA -> GeneratedTvBGoneDatabase.northAmerica
                    }
                )
                addAll(GeneratedFlipperPowerDatabase.televisions)
            }
            DeviceCategory.AIR_CONDITIONER ->
                GeneratedFlipperPowerDatabase.airConditioners
            DeviceCategory.PROJECTOR ->
                GeneratedFlipperPowerDatabase.projectors
        }
    }

    fun find(id: String, category: DeviceCategory, region: TvRegion): IrCode? =
        codes(category, region).firstOrNull { it.id == id }
}
