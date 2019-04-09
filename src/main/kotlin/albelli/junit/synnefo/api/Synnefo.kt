package albelli.junit.synnefo.api

import albelli.junit.synnefo.runtime.*
import albelli.junit.synnefo.runtime.exceptions.SynnefoException
import cucumber.runner.ThreadLocalRunnerSupplier
import cucumber.runtime.BackendModuleBackendSupplier
import cucumber.runtime.junit.FeatureRunner
import cucumber.runtime.junit.JUnitOptions
import cucumber.runtime.model.CucumberFeature
import org.junit.runner.Description
import org.junit.runner.notification.RunNotifier
import org.junit.runners.ParentRunner
import org.junit.runners.model.InitializationError
import java.io.File
import java.net.URISyntaxException
import java.util.*

@Suppress("unused")
class Synnefo @Throws(InitializationError::class, URISyntaxException::class)
constructor(clazz: Class<*>) : ParentRunner<FeatureRunner>(clazz) {
    private val synnefoLoader: SynnefoLoader
    private val synnefoProperties: SynnefoProperties
    private val cucumberFeatures: List<CucumberFeature>?
    private val runnerInfoList: MutableList<SynnefoRunnerInfo>
    private val callbacks: SynnefoCallbacks

    init {
        val opt  =
                (clazz.declaredAnnotations
                        .filter { it is SynnefoOptions }
                        .firstOrNull() ?: throw SynnefoException("Runner class is not annotated with @SynnefoOptions")
                        ) as SynnefoOptions

        val classPath = File(clazz.protectionDomain.codeSource.location.toURI()).path
        synnefoProperties = SynnefoProperties(opt, classPath)

        callbacks = SynnefoCallbacks(clazz)

        synnefoLoader = SynnefoLoader(synnefoProperties, clazz.classLoader)
        cucumberFeatures = synnefoLoader.getCucumberFeatures()

        runnerInfoList = ArrayList()

        if (opt.runLevel == SynnefoRunLevel.FEATURE) {
            cucumberFeatures!!.forEach { feature -> runnerInfoList.add(SynnefoRunnerInfo(synnefoProperties, feature, null)) }
        } else {
            val scenarios = synnefoLoader.cucumberScenarios
            scenarios
                    .keys
                    .forEach { location -> runnerInfoList.add(SynnefoRunnerInfo(synnefoProperties, scenarios.getValue(location), location.line)) }
        }
    }

    public override fun getChildren(): List<FeatureRunner> {
        val runtimeOptions = synnefoLoader.runtimeOptions
        val eventBus = synnefoLoader.eventBus
        val resourceLoader = synnefoLoader.resourceLoader
        val classFinder = synnefoLoader.classFinder
        val filters = synnefoLoader.filters

        val jUnitOptions = JUnitOptions(runtimeOptions.isStrict, runtimeOptions.junitOptions)
        val backendSupplier = BackendModuleBackendSupplier(resourceLoader, classFinder, runtimeOptions)
        val runnerSupplier = ThreadLocalRunnerSupplier(runtimeOptions, eventBus, backendSupplier)

        val children = ArrayList<FeatureRunner>()
        this.cucumberFeatures!!.forEach { cucumberFeature ->
            try {
                val runner = FeatureRunner(cucumberFeature, filters, runnerSupplier, jUnitOptions)
                runner.description
                children.add(runner)
            } catch (error: InitializationError) {
                error.printStackTrace()
            }
        }
        return children
    }

    override fun describeChild(child: FeatureRunner): Description {
        return child.description
    }

    override fun runChild(child: FeatureRunner, notifier: RunNotifier) {
        child.run(notifier)
    }

    override fun run(notifier: RunNotifier) {
        val synnefoRunner = SynnefoRunner(runnerInfoList, synnefoProperties, cucumberFeatures!!, notifier)

        try {
            callbacks.beforeAll()
            synnefoRunner.run()
        } finally {
            callbacks.afterAll()
        }
    }
}
