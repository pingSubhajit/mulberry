plugins {
    alias(libs.plugins.android.asset.pack)
}

assetPack {
    packName.set("wallpaper_pack")
    dynamicDelivery {
        deliveryType.set("fast-follow")
    }
}
