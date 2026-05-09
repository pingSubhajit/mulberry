plugins {
    alias(libs.plugins.android.asset.pack)
}

assetPack {
    packName.set("reaction_pack")
    dynamicDelivery {
        deliveryType.set("fast-follow")
    }
}
