package org.mariotaku.qcloudpublisherplugin

import com.android.apksig.ApkSigner
import com.android.build.gradle.api.ApplicationVariant
import com.android.build.gradle.api.BaseVariantOutput
import com.android.builder.model.SigningConfig
import com.android.tools.build.bundletool.model.SigningConfiguration
import com.android.tools.build.bundletool.utils.flags.Flag
import com.qcloud.cos.COSClient
import com.qcloud.cos.ClientConfig
import com.qcloud.cos.auth.BasicCOSCredentials
import com.qcloud.cos.http.HttpMethodName
import com.qcloud.cos.region.Region
import com.tencentcloudapi.common.Credential
import com.tencentcloudapi.ms.v20180408.MsClient
import com.tencentcloudapi.ms.v20180408.models.*
import okio.ByteString
import okio.HashingSink
import okio.Okio
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.LogLevel

import java.util.concurrent.TimeUnit

class QCloudPublisherPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        if (!project.hasProperty("android")) {
            throw IllegalArgumentException("Project ${project.name} is not an Android project")
        }
        def config = project.extensions.create("qcloudPublish", QCloudPublisherExtensions)
        project.afterEvaluate { p ->
            p.android.applicationVariants.forEach { ApplicationVariant variant ->
                BaseVariantOutput output = variant.outputs.first()

                // Bundle task name for variant
                def publishTaskName = "qcloudPublish${variant.name.capitalize()}"

                p.task(publishTaskName) {
                    group = "qcloud-publish"
                    description = "Publish ${variant.name} apk to QCloud."

                    doLast {
                        if (!config.secretId || !config.secretKey) throw new Exception("QCloud credentials required")
                        if (!config.bucket) throw new Exception("Bucket required")
                        if (!config.region) throw new Exception("Region required")
                        def cosCred = new BasicCOSCredentials(config.secretId, config.secretKey)
                        def cosConf = new ClientConfig(new Region(config.region))
                        def cosClient = new COSClient(cosCred, cosConf)

                        def origApk = output.outputFile
                        def origApkKey = apkKey(config, origApk)
                        def mappingFile = variant.mappingFile
                        try {
                            cosClient.putObject(config.bucket, origApkKey, origApk)
                            p.logger.log(LogLevel.LIFECYCLE, "Uploaded APK: $origApkKey")
                        } catch (e) {
                            p.logger.error("Failed to upload APK", e)
                            throw e
                        }

                        if (config.uploadMapping && mappingFile.exists()) {
                            try {
                                cosClient.putObject(config.bucket, mappingKey(config, mappingFile), mappingFile)
                            } catch (e) {
                                p.logger.error("Failed to upload mapping", e)
                            }
                        }

                        if (config.reinforceForVariant?.get(variant.name) ?: config.reinforceEnabled) {
                            println("Submitting reinforce request...")
                            def expiration = new Date(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1))
                            URL apkDownloadUrl = cosClient.generatePresignedUrl(config.bucket, origApkKey, expiration, HttpMethodName.GET)

                            def msCredential = new Credential(config.secretId, config.secretKey)
                            def msClient = new MsClient(msCredential, config.region)
                            CreateShieldInstanceResponse reinforceResp = createShieldInstance(msClient,
                                    config, variant, origApk, apkDownloadUrl)
                            try {
                                def queryReq = new DescribeShieldResultRequest().with {
                                    itemId = reinforceResp.itemId
                                    return it
                                }
                                DescribeShieldResultResponse shieldResult
                                while ((shieldResult = msClient.DescribeShieldResult(queryReq)).taskStatus == 2) {
                                    println("Checking reinforce result...")
                                    sleep(2000)
                                }
                                if (shieldResult.taskStatus != 1) {
                                    throw Exception("Unable to reinforce apk. Code: ${shieldResult.shieldInfo.shieldCode}")
                                }

                                def reinforcedApk = File.createTempFile("qcloud_reinforce_", ".apk", project.buildDir)

                                new URL(shieldResult.shieldInfo.appUrl).withInputStream { is ->
                                    reinforcedApk.withOutputStream { os ->
                                        os << is
                                        os.flush()
                                    }
                                }

                                def signingConfig = variant.signingConfig
                                def signedReinforcedApk = new File(origApk.parentFile, "${nameWithoutExtension(origApk)}_reinforced_signed.apk")

                                def signer = new ApkSigner.Builder(createSignerConfigs(signingConfig))
                                        .setInputApk(reinforcedApk)
                                        .setOutputApk(signedReinforcedApk)
                                        .setV1SigningEnabled(signingConfig.v1SigningEnabled)
                                        .setV2SigningEnabled(signingConfig.v2SigningEnabled)
                                        .build()
                                signer.sign()

                                p.logger.log(LogLevel.LIFECYCLE, "Reinforced APK signed: ${signedReinforcedApk}")

                                try {
                                    def reinforcedApkKey = apkKey(config, signedReinforcedApk)
                                    cosClient.putObject(config.bucket, reinforcedApkKey, signedReinforcedApk)
                                    p.logger.log(LogLevel.LIFECYCLE, "Uploaded reinforced APK: ${reinforcedApkKey}")
                                } catch (e) {
                                    p.logger.error("Failed to upload reinforced APK", e)
                                    throw e
                                }
                            } finally {
                                p.logger.log(LogLevel.INFO, "Cleaning up")
                                msClient.DeleteShieldInstances(new DeleteShieldInstancesRequest().with {
                                    it.itemIds = [reinforceResp.itemId]
                                    return it
                                })
                            }
                        }
                    }

                    dependsOn(variant.assemble)
                }
            }
        }
    }

    static List<ApkSigner.SignerConfig> createSignerConfigs(SigningConfig config) {
        def extracted = SigningConfiguration.extractFromKeystore(config.storeFile.toPath(), config.keyAlias,
                passwordFromString(config.storePassword), passwordFromString(config.keyPassword))
        return [new ApkSigner.SignerConfig.Builder("cert", extracted.privateKey, extracted.certificates).build()]
    }

    static Optional<Flag.Password> passwordFromString(String str) {
        if (str == null) return Optional.empty()
        return Optional.of(Flag.Password.createFromFlagValue("pass:$str"))
    }

    static CreateShieldInstanceResponse createShieldInstance(MsClient msClient,
                                                             QCloudPublisherExtensions config,
                                                             ApplicationVariant variant,
                                                             File apkFile, URL apkUrl) {
        return msClient.CreateShieldInstance(new CreateShieldInstanceRequest().with { req ->
            req.appInfo = new AppInfo().with { info ->
                info.fileName = apkFile.name
                info.appUrl = apkUrl.toString()
                info.appPkgName = variant.applicationId
                info.appVersion = variant.versionName
                info.appSize = apkFile.size()
                info.appMd5 = md5(apkFile).hex()
                return info
            }
            req.serviceInfo = new ServiceInfo().with { info ->
                info.serviceEdition = config.reinforceEdition
                info.submitSource = "MC"
                return info
            }
            return req
        })
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

    static ByteString md5(File body) {
        def sink = HashingSink.md5(Okio.blackhole())
        def buffer = Okio.buffer(sink)
        buffer.writeAll(Okio.source(body))
        buffer.flush()
        return sink.hash()
    }

}