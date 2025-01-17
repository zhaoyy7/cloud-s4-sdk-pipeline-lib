import com.sap.cloud.sdk.s4hana.pipeline.Analytics
import org.jenkinsci.plugins.workflow.libs.GlobalLibraries
import org.jenkinsci.plugins.workflow.libs.LibraryConfiguration

def call(Map parameters = [:]) {
    Script script = parameters.script

    // If you change the version please also the corresponding jar file. They must always be at the same commit/tag/version.
    String piperOsVersion = '0f3436d1a5a3b4ec7ec45b9a76ad74a5725b573a'

    String piperIdentifier = 'None'

    if(isLibraryConfigured("piper-library-os")){
        piperIdentifier = "piper-library-os"
    }
    else if(isLibraryConfigured("piper-lib-os")){
        piperIdentifier = "piper-lib-os"
    }
    else {
        error("Configuration missing for required libraries. Please setup Jenkins as described here: https://github.com/SAP/cloud-s4-sdk-pipeline/blob/master/README.md")
    }

    library "${piperIdentifier}@${piperOsVersion}"
    Analytics.instance.setPiperIdentifier(piperIdentifier)
}

private boolean isLibraryConfigured(String libName){
    GlobalLibraries globalLibraries = GlobalLibraries.get()
    List libs = globalLibraries.getLibraries()

    for (LibraryConfiguration libConfig : libs) {
        if (libConfig.getName() == libName) {
            return true
        }
    }

    return false
}
