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
        val matched = AtomicBoolean()

        for (pickle in compiler.compile(cucumberFeature.gherkinFeature)) {

            matched.set(filters.matchesFilters(PickleEvent(cucumberFeature.uri.schemeSpecificPart, pickle)))
            if (matched.get()) {
                return true
            }
        }

        return false
    }

    fun matchLocation(pickleLocationLine: Int): PickleLocation? {
        val pickles = compiler.compile(cucumberFeature.gherkinFeature)

        for (pickle in pickles) {
            val pickleLocation = pickle.locations.firstOrNull { it.line == pickleLocationLine }

            if (pickleLocation != null) {
                if (filters.matchesFilters(PickleEvent(cucumberFeature.uri.schemeSpecificPart, pickle))) {
                    return pickleLocation
                }
            }
        }

        return null
    }

}