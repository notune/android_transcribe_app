plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("model_assets")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}
