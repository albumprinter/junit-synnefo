package albelli.junit.synnefo.runtime

import albelli.junit.synnefo.api.SynnefoRunLevel
import cucumber.runtime.model.CucumberFeature

internal class SynnefoRunnerInfo(
        synnefoOptions: SynnefoProperties,
        private val cucumberFeature: CucumberFeature,
        private val lineId: Int?) {
    private val synnefoRuntimeOptions: SynnefoRuntimeOptionsCreator = SynnefoRuntimeOptionsCreator(synnefoOptions)
    private val synnefoRunLevel: SynnefoRunLevel = synnefoOptions.runLevel

    val runtimeOptions: List<String> = synnefoRuntimeOptions.getRuntimeOptions()

    val cucumberFeatureLocation: String
        get() {
            val featureLocation = this.cucumberFeature.uri
                    .toString()
                    .replace("classpath:/","classpath:") // hack for the cucumber loader

            return if (synnefoRunLevel == SynnefoRunLevel.SCENARIO && lineId != null) {
                String.format("%s:%s", featureLocation, lineId)
            } else {
                featureLocation
            }
        }
}
