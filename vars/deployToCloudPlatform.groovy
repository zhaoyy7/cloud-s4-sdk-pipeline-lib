import com.cloudbees.groovy.cps.NonCPS
import com.sap.cloud.sdk.s4hana.pipeline.BuildToolEnvironment
import com.sap.cloud.sdk.s4hana.pipeline.CloudPlatform
import com.sap.cloud.sdk.s4hana.pipeline.DeploymentType
import com.sap.piper.k8s.ContainerMap

def call(Map parameters = [:]) {
    handleStepErrors(stepName: 'deployToCloudPlatform', stepParameters: parameters) {
        def index = 1
        def deployments = [:]
        def stageName = parameters.stage
        def script = parameters.script
        def enableZeroDowntimeDeployment = parameters.enableZeroDowntimeDeployment

        if (parameters.cfTargets) {
            for (int i = 0; i < parameters.cfTargets.size(); i++) {
                def target = parameters.cfTargets[i]
                Closure deployment = {
                    unstashFiles script: script, stage: stageName

                    String deploymentType
                    if (enableZeroDowntimeDeployment) {
                        deploymentType = DeploymentType.CF_BLUE_GREEN.toString()
                    } else {
                        deploymentType = DeploymentType.selectFor(
                            CloudPlatform.CLOUD_FOUNDRY,
                            parameters.isProduction.asBoolean()
                        ).toString()
                    }

                    Map cloudFoundryDeploymentParameters = [script      : parameters.script,
                                                            deployType  : deploymentType,
                                                            cloudFoundry: target,
                                                            mtaPath     : script.commonPipelineEnvironment.mtarFilePath]

                    if (BuildToolEnvironment.instance.isMta()) {
                        cloudFoundryDeploymentParameters.deployTool = 'mtaDeployPlugin'
                        if (target.mtaExtensionDescriptor) {
                            if (!fileExists(target.mtaExtensionDescriptor)) {
                                error "The mta descriptor has defined an extension file ${target.mtaExtensionDescriptor}. But the file is not available."
                            }
                            cloudFoundryDeploymentParameters.mtaExtensionDescriptor = target.mtaExtensionDescriptor
                            if (target.mtaExtensionCredentials) {
                                echo "Modifying ${cloudFoundryDeploymentParameters.mtaExtensionDescriptor}. Adding credential values from Jenkins."
                                sh "cp ${cloudFoundryDeploymentParameters.mtaExtensionDescriptor} ${cloudFoundryDeploymentParameters.mtaExtensionDescriptor}.original"

                                Map mtaExtensionCredentilas = target.mtaExtensionCredentials

                                String fileContent = ''
                                Map binding = [:]

                                try {
                                    fileContent = readFile target.mtaExtensionDescriptor
                                } catch (Exception e) {
                                    error("Unable to read mta extension file ${target.mtaExtensionDescriptor}. If this should not happen, please open an issue at https://github.com/sap/cloud-s4-sdk-pipeline/issues and describe your project setup.")
                                }

                                mtaExtensionCredentilas.each { key, credentialsId ->
                                    withCredentials([string(credentialsId: credentialsId, variable: 'mtaExtensionCredential')]) {
                                        binding["${key}"] = "${mtaExtensionCredential}"
                                    }
                                }

                                try {
                                    writeFile file: target.mtaExtensionDescriptor, text: fillTemplate(fileContent, binding)
                                } catch (Exception e) {
                                    error("Unable to write credentials values to the mta extension file ${target.mtaExtensionDescriptor}\n. \n Kindly refer to the manual at https://github.com/SAP/cloud-s4-sdk-pipeline/blob/master/configuration.md#productiondeployment. \nIf this should not happen, please open an issue at https://github.com/sap/cloud-s4-sdk-pipeline/issues and describe your project setup.")
                                }
                            }
                        }
                    } else {
                        cloudFoundryDeploymentParameters.deployTool = 'cf_native'
                    }
                    try {
                        cloudFoundryDeploy(cloudFoundryDeploymentParameters)
                    } finally {
                        if (target.mtaExtensionCredentials && cloudFoundryDeploymentParameters.mtaExtensionDescriptor && fileExists(cloudFoundryDeploymentParameters.mtaExtensionDescriptor)) {
                            sh "mv --force ${cloudFoundryDeploymentParameters.mtaExtensionDescriptor}.original ${cloudFoundryDeploymentParameters.mtaExtensionDescriptor} || echo 'The file ${cloudFoundryDeploymentParameters.mtaExtensionDescriptor}.original couldnot be renamed. \n" + " Kindly refer to the manual at https://github.com/SAP/cloud-s4-sdk-pipeline/blob/master/configuration.md#productiondeployment. \nIf this should not happen, please create an issue at https://github.com/SAP/cloud-s4-sdk-pipeline/issues'"
                        }
                    }

                    stashFiles script: script, stage: stageName
                }
                deployments["Deployment ${index > 1 ? index : ''}"] = {
                    if (env.POD_NAME) {
                        dockerExecuteOnKubernetes(script: script, containerMap: ContainerMap.instance.getMap().get(stageName) ?: [:]) {
                            deployment.call()
                        }
                    } else {
                        node(env.NODE_NAME) {
                            deployment.call()
                        }
                    }
                }
                index++
            }
            runClosures deployments, script
        } else if (parameters.neoTargets) {

            if (BuildToolEnvironment.instance.isMta()) {
                error("MTA projects can be deployed only to the Cloud Foundry environment.")
            }

            def pom = readMavenPom file: 'application/pom.xml'
            def source = "application/target/${pom.getArtifactId()}.${pom.getPackaging()}"
            for (int i = 0; i < parameters.neoTargets.size(); i++) {
                def target = parameters.neoTargets[i]

                Closure deployment = {
                    unstashFiles script: script, stage: stageName

                    DeploymentType deploymentType
                    if (enableZeroDowntimeDeployment) {
                        deploymentType = DeploymentType.NEO_ROLLING_UPDATE
                    } else {
                        deploymentType = DeploymentType.selectFor(CloudPlatform.NEO, parameters.isProduction.asBoolean())
                    }

                    neoDeploy(
                        script: parameters.script,
                        warAction: deploymentType.toString(),
                        source: source,
                        neo: target
                    )

                    stashFiles script: script, stage: stageName
                }
                deployments["Deployment ${index > 1 ? index : ''}"] = {
                    if (env.POD_NAME) {
                        dockerExecuteOnKubernetes(script: script, containerMap: ContainerMap.instance.getMap().get(stageName) ?: [:]) {
                            deployment.call()
                        }
                    } else {
                        node(env.NODE_NAME) {
                            deployment.call()
                        }
                    }
                }
                index++
            }
            runClosures deployments, script
        } else {
            currentBuild.result = 'FAILURE'
            error("Deployment skipped because no targets defined!")
            if (stageName == "productionDeployment") {
                echo "For more information, please refer to https://github.com/SAP/cloud-s4-sdk-pipeline/blob/master/configuration.md#productiondeployment"
            } else if (stageName == "performanceTests") {
                echo "For more information, please refer to https://github.com/SAP/cloud-s4-sdk-pipeline/blob/master/configuration.md#performancetests"
            } else {
                echo "For more information, please refer to https://github.com/SAP/cloud-s4-sdk-pipeline/blob/master/configuration.md#endtoendtests"
            }
        }
    }
}

@NonCPS
String fillTemplate(String input, Map binding) {
    try {
        return new groovy.text.GStringTemplateEngine()
            .createTemplate(input)
            .make(binding)
            .toString()
    } catch (Exception e) {
        error("Error replacing the password placeholder with a password from Jenkins." + e.getMessage() + "\nKindly refer to the manual at https://github.com/SAP/cloud-s4-sdk-pipeline/blob/master/configuration.md#productiondeployment")
    }
    return ''
}
