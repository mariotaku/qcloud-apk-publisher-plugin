package org.mariotaku.qcloudpublisherplugin

class QCloudPublisherExtensions {

    String secretId = ""
    String secretKey = ""
    String region = ""

    String bucket = ""

    String overrideKey = null
    String overrideMappingKey = null
    String keyPrefix = null
    String keySuffix = null

    boolean uploadMapping = false

    boolean reinforceEnabled = false
    String reinforceEdition = "basic"

    Map<String, Boolean> reinforceForVariant = null
}
