package albelli.junit.synnefo.runtime

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

class SynnefoLoader(private val SynnefoProperties: SynnefoProperties, classLoader: ClassLoader) {
    val resourceLoader: ResourceLoader
    private val featureSupplier: FeaturePathFeatureSupplier
    val runtimeOptions: RuntimeOptions
    val eventBus: EventBus
    val filters: Filters
    val classFinder: ClassFinder

    private var cucumberFeatures: List<CucumberFeature>? = null

    val cucumberScenarios: Map<PickleLocation, CucumberFeature>
        get() = cucumberScenarios(cucumberFeatures)

    init {
        this.resourceLoader = MultiLoader(classLoader)
        val featureLoader = FeatureLoader(resourceLoader)
        this.runtimeOptions = createRuntimeOptions()
        this.featureSupplier = FeaturePathFeatureSupplier(featureLoader, runtimeOptions)
        this.eventBus = TimeServiceEventBus(TimeService.SYSTEM)
        this.filters = Filters(runtimeOptions)
        this.classFinder = ResourceLoaderClassFinder(resourceLoader, classLoader)
    }

    fun getCucumberFeatures(): List<CucumberFeature>? {
        cucumberFeatures = cucumberFeatures()
        return cucumberFeatures
    }

    private fun createRuntimeOptions(): RuntimeOptions {
        val synnefoRuntimeOptions = SynnefoRuntimeOptionsCreator(SynnefoProperties)

        val argv = ArrayList<String>()
        argv.addAll(synnefoRuntimeOptions.getRuntimeOptions())
        val features = SynnefoProperties.synnefoOptions.cucumberOptions.features
        argv.addAll(features)

        return RuntimeOptions(argv)
    }

    private fun cucumberFeatures(): List<CucumberFeature> {
        val loadedCucumberFeatures = featureSupplier.get()

        val matchedCucumberFeatures = ArrayList<CucumberFeature>()

        loadedCucumberFeatures.forEach { cucumberFeature ->
            val pickleMatcher = SynnefoPickleMatcher(cucumberFeature, filters)

            if (pickleMatcher.matches()) {
                matchedCucumberFeatures.add(cucumberFeature)
            }
        }
        return matchedCucumberFeatures
    }

    private fun cucumberScenarios(cucumberFeatures: List<CucumberFeature>?): Map<PickleLocation, CucumberFeature> {
        val scenarios = HashMap<PickleLocation, CucumberFeature>()

        cucumberFeatures?.forEach { cucumberFeature ->
            cucumberFeature.gherkinFeature.feature.children.forEach { scenario ->

                val lines = ArrayList<Int>()

                if (scenario is ScenarioOutline) {
                    val examples = scenario.examples

                    examples.forEach { example -> example.tableBody.forEach { tr -> lines.add(tr.location.line) } }
                } else {
                    lines.add(scenario.location.line)
                }

                lines.forEach { line ->
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