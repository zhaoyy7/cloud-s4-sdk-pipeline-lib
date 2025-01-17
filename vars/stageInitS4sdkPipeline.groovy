def call(Map parameters) {
    def script = parameters.script
    loadPiper script: script

    /*
    In order to avoid the trust issues between the build server and the git server in a distributed setup,
    the init stage always executes on the master node. The underlying assumption here is that, Jenkins
    server has a ssh key and it has been added to the git server. This is necessary if Jenkins has to push
    code changes to the git server.
    */

    node('master'){
        deleteDir()
        checkoutAndInitLibrary(script: script, customDefaults: parameters.customDefaults)
    }

    runAsStage(stageName: 'initS4sdkPipeline', script: script) {
        initS4sdkPipeline script:script
    }
}
