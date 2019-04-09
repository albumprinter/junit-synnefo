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

        try {
            for (pickle in compiler.compile(cucumberFeature.gherkinFeature)) {

                matched.set(filters.matchesFilters(PickleEvent(cucumberFeature.uri.schemeSpecificPart, pickle)))
                if (matched.get()) {
                    throw ConditionSatisfiedException()
                }
            }
        }
        catch (ignored: ConditionSatisfiedException) {
        }

        return matched.get()
    }

    fun matchLocation(pickleLocationLine: Int): PickleLocation? {
        var location: PickleLocation? = null

        val pickles = compiler.compile(cucumberFeature.gherkinFeature)

        try {
            for (pickle in pickles) {
                val pickleLocation = pickle.locations.firstOrNull { it.line == pickleLocationLine }

                if (pickleLocation != null) {
                    if (filters.matchesFilters(PickleEvent(cucumberFeature.uri.schemeSpecificPart, pickle))) {
                        location = pickleLocation
                        throw ConditionSatisfiedException()
                    }
                }
            }
        }
        catch (ignored: ConditionSatisfiedException) {}

        return location
    }

    private inner class ConditionSatisfiedException : RuntimeException()
}