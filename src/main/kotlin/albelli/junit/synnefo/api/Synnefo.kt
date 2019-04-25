package albelli.junit.synnefo.api

import albelli.junit.synnefo.runtime.*
import albelli.junit.synnefo.runtime.exceptions.SynnefoException
import cucumber.runtime.junit.FeatureRunner
import cucumber.runtime.model.CucumberFeature
import org.junit.runner.Description
import org.junit.runner.notification.RunNotifier
import org.junit.runners.ParentRunner
import sun.reflect.generics.reflectiveObjects.NotImplementedException
import java.io.File
import java.util.*

@Suppress("unused")
class Synnefo
constructor(clazz: Class<*>) : ParentRunner<FeatureRunner>(clazz) {

    private val synnefoLoader: SynnefoLoader
    private val synnefoProperties: SynnefoProperties
    private val cucumberFeatures: List<CucumberFeature>
    private val runnerInfoList: MutableList<SynnefoRunnerInfo>
    private val callbacks: SynnefoCallbacks
    private val classLoader: ClassLoader = clazz.classLoader!!

    init {

        val opt  = loadOptions(clazz)

        val classPath = File(clazz.protectionDomain.codeSource.location.toURI()).path
        callbacks = SynnefoCallbacks(clazz)

        synnefoLoader = SynnefoLoader(opt, classLoader)
        cucumberFeatures = synnefoLoader.getCucumberFeatures()

        runnerInfoList = ArrayList()

        synnefoProperties = SynnefoProperties(opt, classPath, cucumberFeatures.map { it.uri })

        if (synnefoProperties.runLevel == SynnefoRunLevel.FEATURE) {
            for (feature in cucumberFeatures) {
                runnerInfoList.add(SynnefoRunnerInfo(synnefoProperties, feature, null))
            }
        } else {
            for ((line, scenario) in synnefoLoader.getCucumberScenarios()) {
                runnerInfoList.add(SynnefoRunnerInfo(synnefoProperties, scenario, line))
            }
        }
    }

    override fun run(notifier: RunNotifier) {
        val synnefoRunner = SynnefoRunner(runnerInfoList, synnefoProperties, notifier, classLoader)

        try {
            callbacks.beforeAll()
            synnefoRunner.run()
        } finally {
            callbacks.afterAll()
        }
    }

    public override fun getChildren(): List<FeatureRunner> {
        return listOf()
    }

    override fun describeChild(child: FeatureRunner): Description {
        throw NotImplementedException()
    }

    override fun runChild(child: FeatureRunner, notifier: RunNotifier) {
        throw NotImplementedException()
    }

    private fun loadOptions(clazz: Class<*>): SynnefoProperties {
        val opt  =
                (clazz.declaredAnnotations
                        .firstOrNull {
                            it is SynnefoOptions }
                        ?: throw SynnefoException("Runner class is not annotated with @SynnefoOptions")
                        ) as SynnefoOptions

        return SynnefoProperties(opt)
    }
}
