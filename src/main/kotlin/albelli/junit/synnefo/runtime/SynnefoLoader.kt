package albelli.junit.synnefo.runtime

import albelli.junit.synnefo.api.SynnefoOptions
import cucumber.runner.EventBus
import cucumber.runner.TimeService
import cucumber.runner.TimeServiceEventBus
import cucumber.runtime.ClassFinder
import cucumber.runtime.FeaturePathFeatureSupplier
import cucumber.runtime.RuntimeOptions
import cucumber.runtime.filter.Filters
import cucumber.runtime.io.MultiLoader
import cucumber.runtime.io.ResourceLoader
import cucumber.runtime.io.ResourceLoaderClassFinder
import cucumber.runtime.model.CucumberFeature
import cucumber.runtime.model.FeatureLoader
import gherkin.ast.ScenarioOutline
import gherkin.pickles.PickleLocation

import java.util.*

class SynnefoLoader(private val synnefoProperties: SynnefoOptions, classLoader: ClassLoader) {
    private val resourceLoader: ResourceLoader
    private val featureSupplier: FeaturePathFeatureSupplier
    private val runtimeOptions: RuntimeOptions
    private val eventBus: EventBus
    private val filters: Filters
    private val classFinder: ClassFinder

    private var cucumberFeatures: List<CucumberFeature>


    init {
        this.resourceLoader = MultiLoader(classLoader)
        this.runtimeOptions = createRuntimeOptions()
        this.featureSupplier = FeaturePathFeatureSupplier(FeatureLoader(resourceLoader), runtimeOptions)
        this.eventBus = TimeServiceEventBus(TimeService.SYSTEM)
        this.filters = Filters(runtimeOptions)
        this.classFinder = ResourceLoaderClassFinder(resourceLoader, classLoader)

        cucumberFeatures = cucumberFeatures()
    }

    fun getCucumberScenarios(): Map<PickleLocation, CucumberFeature>{
        return cucumberScenarios(cucumberFeatures)
    }

    fun getCucumberFeatures(): List<CucumberFeature> {
        return cucumberFeatures
    }

    private fun createRuntimeOptions(): RuntimeOptions {
        val synnefoRuntimeOptions = SynnefoRuntimeOptionsCreator(synnefoProperties)

        val argv = ArrayList<String>()
        argv.addAll(synnefoRuntimeOptions.getRuntimeOptions())
        val features = synnefoProperties.cucumberOptions.features
        argv.addAll(features)

        return RuntimeOptions(argv)
    }

    private fun cucumberFeatures(): List<CucumberFeature> {
        val loadedCucumberFeatures = featureSupplier.get()

        val matchedCucumberFeatures = ArrayList<CucumberFeature>()

        for (cucumberFeature in loadedCucumberFeatures) {
            val pickleMatcher = SynnefoPickleMatcher(cucumberFeature, filters)

            if (pickleMatcher.matches()) {
                matchedCucumberFeatures.add(cucumberFeature)
            }
        }
        return matchedCucumberFeatures
    }

    private fun cucumberScenarios(cucumberFeatures: List<CucumberFeature>): Map<PickleLocation, CucumberFeature> {
        val scenarios = HashMap<PickleLocation, CucumberFeature>()

        for (cucumberFeature in cucumberFeatures) {
            for (scenario in cucumberFeature.gherkinFeature.feature.children) {
                val lines = ArrayList<Int>()

                if (scenario is ScenarioOutline) {
                    val allLinesForScenario = scenario.examples.flatMap { it.tableBody.map { tableRow -> tableRow.location.line } }
                    lines.addAll(allLinesForScenario)
                }
                else {
                    lines.add(scenario.location.line)
                }

                for (line in lines) {
                    val pickleMatcher = SynnefoPickleMatcher(cucumberFeature, filters)

                    val pickleLocation = pickleMatcher.matchLocation(line)

                    if (pickleLocation != null) {
                        scenarios[pickleLocation] = cucumberFeature
                    }
                }
            }
        }
        return scenarios
    }
}
