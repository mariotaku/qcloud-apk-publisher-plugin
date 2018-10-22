package org.mariotaku.qcloudpublisherplugin

import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.api.BaseVariantOutput
import com.qcloud.cos.COSClient
import com.qcloud.cos.ClientConfig
import com.qcloud.cos.auth.BasicCOSCredentials
import com.qcloud.cos.http.HttpMethodName
import com.qcloud.cos.region.Region
import com.tencentcloudapi.common.Credential
import com.tencentcloudapi.ms.v20180408.MsClient
import com.tencentcloudapi.ms.v20180408.models.AppInfo
import com.tencentcloudapi.ms.v20180408.models.CreateShieldInstanceRequest
import com.tencentcloudapi.ms.v20180408.models.DescribeShieldResultRequest
import com.tencentcloudapi.ms.v20180408.models.ServiceInfo
import org.gradle.api.Plugin
import org.gradle.api.Project

import java.util.concurrent.TimeUnit

class QCloudPublisherPlugin implements Plugin<Project> {


    @Override
    void apply(Project project) {
        if (!project.hasProperty("android")) {
            throw IllegalArgumentException("Project ${project.name} is not an Android project")
        }
        def config = project.extensions.create("qcloudPublish", QCloudPublisherExtensions)
        project.afterEvaluate { p ->
            p.android.applicationVariants.forEach { BaseVariant variant ->
                BaseVariantOutput output = variant.outputs.first()

                // Bundle task name for variant
                def publishTaskName = "qcloudPublish${variant.name.capitalize()}"

                p.task(publishTaskName) {
                    group = "qcloud-publish"
                    description = "Publish ${variant.name} apk to QCloud."

                    doLast {
                        if (!config.secretId || !config.secretKey) throw Exception("QCloud credentials required")
                        if (!config.bucket) throw Exception("Bucket required")
                        def cosCred = new BasicCOSCredentials(config.secretId, config.secretKey)
                        def cosConf = new ClientConfig(new Region(config.region))
                        def cosClient = new COSClient(cosCred, cosConf)
                        def apkKey = apkKey(config, output.outputFile)
                        try {
                            cosClient.putObject(config.bucket, apkKey, output.outputFile)
                        } catch (e) {
                            it.logger.error("Failed to upload APK", e)
                        }

                        if (config.uploadMapping && mappingFile.exists()) {
                            try {
                                cosClient.putObject(config.bucket, mappingKey(config, variant.mappingFile), variant.mappingFile)
                            } catch (e) {
                                it.logger.error("Failed to upload mapping", e)
                            }
                        }


                        def expiration = new Date(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1))
                        URL apkDownloadUrl = cosClient.generatePresignedUrl(config.bucket, apkKey, expiration, HttpMethodName.GET)

                        def msCredential = new Credential(config.secretId, config.secretKey)
                        def msClient = new MsClient(msCredential, config.region)
                        def reinforceResp = msClient.CreateShieldInstance(new CreateShieldInstanceRequest().with {
                            appInfo = new AppInfo().with {
                                appUrl = apkDownloadUrl.toString()
                                appPkgName = variant.applicationId
                                appVersion = variant.versionName
                                fileName = output.name
                                return it
                            }
                            it.serviceInfo = new ServiceInfo().with {
                                serviceEdition = config.edition
                                submitSource = "MC"
                                return it
                            }
                            return it
                        })
                        println("Resp: ${reinforceResp.requestId}")
                        msClient.DescribeShieldResult(new DescribeShieldResultRequest().with {
                            itemId = reinforceResp.itemId
                            return it
                        })
                    }

                    dependsOn(variant.assemble)
                }
            }
        }
    }


    static String apkKey(QCloudPublisherExtensions config, File file) {
        def uploadName = config.overrideKey
        if (uploadName != null) return uploadName
        def prefix = config.keyPrefix ?: ""
        def suffix = config.keySuffix ?: ""
        return "$prefix${nameWithoutExtension(file)}$suffix.${extension(file)}"
    }

    static String mappingKey(QCloudPublisherExtensions config, File file) {
        def uploadName = config.overrideMappingKey
        if (uploadName != null) return uploadName
        def prefix = config.keyPrefix ?: ""
        def suffix = config.keySuffix ?: ""
        return "${prefix}mapping-${nameWithoutExtension(file)}$uploadName$suffix.txt"
    }

    static String mediaType(File file) {
        switch (extension(file).toLowerCase(Locale.US)) {
            case "apk": return "application/vnd.android.package-archive"
            case "txt": return "text/plain"
            default: return "application/octet-stream"
        }
    }

    static String extension(File file) {
        def index = file.name.lastIndexOf('.')
        if (index < 0) return ''
        return file.name.substring(index + 1)
    }

    static String nameWithoutExtension(File file) {
        def index = file.name.lastIndexOf('.')
        if (index < 0) return file.name
        return file.name.substring(0, index)
    }

}