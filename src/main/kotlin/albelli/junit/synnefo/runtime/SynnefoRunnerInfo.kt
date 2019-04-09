package albelli.junit.synnefo.runtime

import albelli.junit.synnefo.api.SynnefoOptions
import albelli.junit.synnefo.api.SynnefoRunLevel
import cucumber.runtime.model.CucumberFeature

class SynnefoRunnerInfo(
        synnefoOptions: SynnefoOptions,
        private val cucumberFeature: CucumberFeature,
        private val lineId: Int?) {
    private val synnefoRuntimeOptions: SynnefoRuntimeOptionsCreator = SynnefoRuntimeOptionsCreator(synnefoOptions)
    private val synnefoRunLevel: SynnefoRunLevel = synnefoOptions.runLevel

    val runtimeOptions: Map<String, List<String>>
        get() = synnefoRuntimeOptions.mapRuntimeOptions()

    val cucumberFeatureLocation: String
        get() {
            val featureLocation = this.cucumberFeature.uri.schemeSpecificPart

            return if (synnefoRunLevel == SynnefoRunLevel.SCENARIO && lineId != null) {
                String.format("%s:%s", featureLocation, lineId)
            } else {
                featureLocation
            }
        }
}
