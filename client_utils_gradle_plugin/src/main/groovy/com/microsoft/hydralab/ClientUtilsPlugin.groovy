package com.microsoft.hydralab

import com.microsoft.hydralab.utils.HydraLabClientUtils
import org.gradle.api.Plugin
import org.gradle.api.Project


class ClientUtilsPlugin implements Plugin<Project> {
    @Override
    void apply(Project target) {
        target.task("triggerDeviceLabTest"){
            doFirst {
                // try run with params:
                // -PappApkPath=path/to/apk -PtestApkPath=path/to/apk -PbuildFlavor=flavor -PtestSuiteName=SuiteFullName -PinstrumentationArgs="a=b,c=d"
                // to ignore a case use -PinstrumentationArgs="ignores=testA|testB"
                if (!project.appApkPath || !project.runningType || !project.pkgName || !project.deviceIdentifier || !project.timeOutSeconds || !project.authToken) {
                    throw 'Required params not provided! Make sure the following params are all provided: authToken, appApkPath, pkgName, runningType, deviceIdentifier, timeOutSeconds.'
                }
                // running type specified params
                if (project.runningType == "APPIUM") {
                    if (!project.testApkPath) {
                        throw 'Required param testApkPath not provided!'
                    }
                    if (!project.testSuiteName) {
                        throw 'Required param testSuiteName not provided!'
                    }
                } else {
                    if (project.runningType == "INSTRUMENTATION") {
                        if (!project.testApkPath) {
                            throw 'Required param testApkPath not provided!'
                        }
                        if (!project.testPkgName) {
                            throw 'Required param testPkgName not provided!'
                        }
                        if (!project.testSuiteName) {
                            throw 'Required param testSuiteName not provided!'
                        }
                    } else if (project.runningType == "SMART") {
                        if (!project.maxStepCount) {
                            throw 'Required param maxStepCount not provided!'
                        }
                        if (!project.deviceTestCount) {
                            throw 'Required param deviceTestCount not provided!'
                        }
                    }
                }

                println("Param appApkPath: ${project.appApkPath}")
                println("Param testApkPath: ${project.testApkPath}")
                if (!project.file(project.appApkPath).exists()) {
                    throw "${project.appApkPath} file not exist!"
                }
                if (!project.file(project.testApkPath).exists()) {
                    throw "${project.testApkPath} file not exist!"
                }

                def buildFlavorValue = "UNKNOWN"
                if (project.hasProperty('buildFlavor')) {
                    buildFlavorValue = project.buildFlavor
                }
                def reportDir = new File(project.buildDir, "outputs/androidTest-results/connected/flavors/${buildFlavorValue}")
                if (!reportDir.exists()) reportDir.mkdirs()

                def argsMap = null
                if (project.hasProperty('instrumentationArgs')) {
                    argsMap = [:]
                    // quotation marks not support
                    def argLines = project.instrumentationArgs.replace("\"", "").split(",")
                    for (i in 0..<argLines.size()) {
                        String[] kv = argLines[i].split("=")
                        // use | to represent comma to avoid conflicts
                        argsMap.put(kv[0], kv[1].replace("|", ","))
                    }
                }

                def extraArgsMap = null
                if (project.hasProperty('extraArgs')) {
                    extraArgsMap = [:]
                    // quotation marks not support
                    def argLines = project.extraArgs.replace("\"", "").split(",")
                    for (i in 0..<argLines.size()) {
                        String[] kv = argLines[i].split("=")
                        // use | to represent comma to avoid conflicts
                        extraArgsMap.put(kv[0], kv[1].replace("|", ","))
                    }
                }

                HydraLabClientUtils.HydraLabAPIConfig apiConfig = HydraLabClientUtils.HydraLabAPIConfig.defaultAPI()

                if (project.hasProperty('deviceLabProtocal')) {
                    apiConfig.schema = project.deviceLabProtocal
                }
                if (project.hasProperty('deviceLabHost')) {
                    apiConfig.host = project.deviceLabHost
                }

                if (project.hasProperty('authToken')) {
                    apiConfig.authToken = project.authToken
                }
                if (project.hasProperty('onlyAuthPost')) {
                    apiConfig.onlyAuthPost = Boolean.parseBoolean(project.onlyAuthPost)
                }

                if (project.hasProperty('pkgName')) {
                    apiConfig.pkgName = project.pkgName
                }
                if (project.hasProperty('testPkgName')) {
                    apiConfig.testPkgName = project.testPkgName
                }
                if (project.hasProperty('groupTestType')) {
                    apiConfig.groupTestType = project.groupTestType
                }
                if (project.hasProperty('runningType')) {
                    apiConfig.runningType = project.runningType
                }
                if (project.hasProperty('frameworkType')) {
                    apiConfig.frameworkType = project.frameworkType
                }

                def deviceIdentifierArg = null
                if (project.hasProperty('deviceIdentifier')) {
                    deviceIdentifierArg = project.deviceIdentifier
                }

                def reportAudienceArg = null
                if (project.hasProperty('reportAudience')) {
                    reportAudienceArg = project.reportAudience
                }

                println "##[section]All args: appApkPath: ${project.appApkPath}, testApkPath: ${project.testApkPath}, buildFlavor: ${buildFlavorValue}, " +
                        "\n##[section]\ttestSuiteName: ${project.testSuiteName}, timeOutSeconds: ${project.timeOutSeconds}, argsMap: ${argsMap}" +
                        "\n##[section]\treportAudience: ${reportAudienceArg}, deviceIdentifier: ${deviceIdentifierArg}, extraArgsMap: ${extraArgsMap}, apiConfig: ${apiConfig}"

                HydraLabClientUtils.runTestOnDeviceWithAPK(
                        project.file(project.appApkPath).absolutePath, project.file(project.testApkPath).absolutePath,
                        buildFlavorValue, project.testSuiteName,
                        deviceIdentifierArg, reportAudienceArg, Integer.parseInt(project.timeOutSeconds),
                        reportDir.absolutePath, argsMap, extraArgsMap,
                        apiConfig
                )
            }

            doLast {
                println('Plugin completed running')
            }
        }
    }
}
