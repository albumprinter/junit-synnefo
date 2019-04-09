package albelli.junit.synnefo.runtime

import cucumber.runtime.filter.Filters
import cucumber.runtime.model.CucumberFeature
import gherkin.events.PickleEvent
import gherkin.pickles.Compiler
import gherkin.pickles.PickleLocation
import java.util.concurrent.atomic.AtomicBoolean

class SynnefoPickleMatcher(private val cucumberFeature: CucumberFeature, private val filters: Filters) {
    private val compiler: Compiler = Compiler()

    fun matches(): Boolean {
        for (pickle in compiler.compile(cucumberFeature.gherkinFeature)) {
            if (filters.matchesFilters(PickleEvent(cucumberFeature.uri.schemeSpecificPart, pickle))) {
                return true
            }
        }
        return false
    }

    fun matches(pickleLocationLine: Int): Boolean {
        for (pickle in compiler.compile(cucumberFeature.gherkinFeature)) {
            val pickleLocation = pickle.locations.firstOrNull { it.line == pickleLocationLine }

            if (pickleLocation != null) {
                if (filters.matchesFilters(PickleEvent(cucumberFeature.uri.schemeSpecificPart, pickle))) {
                    return true
                }
            }
        }

        return false
    }
}