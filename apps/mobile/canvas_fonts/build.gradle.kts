plugins {
    alias(libs.plugins.android.asset.pack)
}

assetPack {
    packName.set("canvas_fonts")
    dynamicDelivery {
        deliveryType.set("fast-follow")
    }
}
