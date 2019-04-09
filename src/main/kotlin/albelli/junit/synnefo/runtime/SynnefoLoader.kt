package albelli.junit.synnefo.runtime

import albelli.junit.synnefo.api.SynnefoOptions
import cucumber.runtime.ClassFinder
import cucumber.runtime.FeaturePathFeatureSupplier
import cucumber.runtime.RuntimeOptions
import cucumber.runtime.filter.Filters
import cucumber.runtime.io.MultiLoader
import cucumber.runtime.io.ResourceLoader
import cucumber.runtime.io.ResourceLoaderClassFinder
import cucumber.runtime.model.CucumberFeature
import cucumber.runtime.model.FeatureLoader
import gherkin.ast.ScenarioDefinition
import gherkin.ast.ScenarioOutline

import java.util.*

class SynnefoLoader(private val synnefoProperties: SynnefoOptions, classLoader: ClassLoader) {
    private val resourceLoader: ResourceLoader
    private val featureSupplier: FeaturePathFeatureSupplier
    private val runtimeOptions: RuntimeOptions
    private val filters: Filters
    private val classFinder: ClassFinder

    private var cucumberFeatures: List<CucumberFeature>

    init {
        this.resourceLoader = MultiLoader(classLoader)
        this.runtimeOptions = createRuntimeOptions()
        this.featureSupplier = FeaturePathFeatureSupplier(FeatureLoader(resourceLoader), runtimeOptions)
        this.filters = Filters(runtimeOptions)
        this.classFinder = ResourceLoaderClassFinder(resourceLoader, classLoader)

        cucumberFeatures = cucumberFeatures()
    }

    fun getCucumberScenarios(): Map<Int, CucumberFeature>{
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
        return featureSupplier
                .get()
                .filter { SynnefoPickleFilter(it, filters).matches() }
    }

    private fun cucumberScenarios(cucumberFeatures: List<CucumberFeature>): Map<Int, CucumberFeature> {
        val scenarios = HashMap<Int, CucumberFeature>()
        for (cucumberFeature in cucumberFeatures) {
            for (scenario in cucumberFeature.gherkinFeature.feature.children) {
                for (line in scenario.getAllLines()) {
                    if (SynnefoPickleFilter(cucumberFeature, filters).matches(line)) {
                        scenarios[line] = cucumberFeature
                    }
                }
            }
        }
        return scenarios
    }

    private fun ScenarioDefinition.getAllLines() : List<Int> {
        return if (this is ScenarioOutline) {
            this.examples
                    .flatMap {
                        it.tableBody.map { tableRow -> tableRow.location.line }
                    }
        }
        else {
            listOf(this.location.line)
        }
    }
}
