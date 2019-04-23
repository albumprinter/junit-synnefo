package albelli.junit.synnefo.api

import albelli.junit.synnefo.runtime.*
import albelli.junit.synnefo.runtime.exceptions.SynnefoException
import cucumber.runtime.junit.FeatureRunner
import cucumber.runtime.model.CucumberFeature
import org.junit.runner.Description
import org.junit.runner.notification.RunNotifier
import org.junit.runners.ParentRunner
import org.junit.runners.model.InitializationError
import sun.reflect.generics.reflectiveObjects.NotImplementedException
import java.io.File
import java.net.URISyntaxException
import java.util.*

@Suppress("unused")
class Synnefo
constructor(clazz: Class<*>) : ParentRunner<FeatureRunner>(clazz) {
    private val synnefoLoader: SynnefoLoader
    private val synnefoProperties: SynnefoProperties
    private val cucumberFeatures: List<CucumberFeature>
    private val runnerInfoList: MutableList<SynnefoRunnerInfo>
    private val callbacks: SynnefoCallbacks

    init {
        val opt  =
                (clazz.declaredAnnotations
                        .firstOrNull {
                            it is SynnefoOptions }
                            ?: throw SynnefoException("Runner class is not annotated with @SynnefoOptions")
                ) as SynnefoOptions

        val classPath = File(clazz.protectionDomain.codeSource.location.toURI()).path
        callbacks = SynnefoCallbacks(clazz)

        synnefoLoader = SynnefoLoader(opt, clazz.classLoader)
        cucumberFeatures = synnefoLoader.getCucumberFeatures()

        runnerInfoList = ArrayList()

        synnefoProperties = SynnefoProperties(opt, classPath, cucumberFeatures.map { it.uri.schemeSpecificPart })

        if (opt.runLevel == SynnefoRunLevel.FEATURE) {
            for (feature in cucumberFeatures) {
                runnerInfoList.add(SynnefoRunnerInfo(opt, feature, null))
            }
        } else {
            for ((line, scenario) in synnefoLoader.getCucumberScenarios()) {
                runnerInfoList.add(SynnefoRunnerInfo(opt, scenario, line))
            }
        }
    }

    override fun run(notifier: RunNotifier) {
        val synnefoRunner = SynnefoRunner(runnerInfoList, synnefoProperties, notifier)

        try {
            callbacks.beforeAll()
            synnefoRunner.run()
        } finally {
            callbacks.afterAll()
        }
    }

    public override fun getChildren(): List<FeatureRunner> {
        throw NotImplementedException()
    }

    override fun describeChild(child: FeatureRunner): Description {
        throw NotImplementedException()
    }

    override fun runChild(child: FeatureRunner, notifier: RunNotifier) {
        throw NotImplementedException()
    }
}
