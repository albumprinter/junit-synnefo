package albelli.junit.synnefo.api

import albelli.junit.synnefo.runtime.*
import albelli.junit.synnefo.runtime.exceptions.SynnefoException
import cucumber.runtime.junit.FeatureRunner
import org.junit.runner.Description
import org.junit.runner.notification.RunNotifier
import org.junit.runners.ParentRunner
import sun.reflect.generics.reflectiveObjects.NotImplementedException
import java.io.File

@Suppress("unused")
class Synnefo
constructor(clazz: Class<*>) : ParentRunner<FeatureRunner>(clazz) {

    private val callbacks: SynnefoCallbacks
    private val classLoader: ClassLoader = clazz.classLoader!!

    private val runnerInfoList: ArrayList<SynnefoRunnerInfo> = ArrayList()

    init {

        val opts  = loadOptions(clazz)

        val classPath = File(clazz.protectionDomain.codeSource.location.toURI()).path
        callbacks = SynnefoCallbacks(clazz)
        for(opt in opts)
        {
            val synnefoLoader = SynnefoLoader(opt, classLoader)
            val cucumberFeatures = synnefoLoader.getCucumberFeatures()
            val synnefoProperties = SynnefoProperties(opt , classPath, cucumberFeatures.map { f -> f.uri })

            if (synnefoProperties.runLevel == SynnefoRunLevel.FEATURE) {
                for (feature in cucumberFeatures) {
                    runnerInfoList.add(SynnefoRunnerInfo(synnefoProperties, feature, null))
                }
            } else {
                for ((line, scenario) in synnefoLoader.getCucumberScenarios()) {
                    runnerInfoList.add(SynnefoRunnerInfo(synnefoProperties, scenario, line))
                }
            }

            if (opt.shuffleBacklogBeforeExecution)
                runnerInfoList.shuffle()
        }
    }

    override fun run(notifier: RunNotifier) {
        val synnefoRunner = SynnefoRunner(classLoader, notifier)
        try {
            callbacks.beforeAll()
            synnefoRunner.run(runnerInfoList)
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

    private fun loadOptions(clazz: Class<*>): List<SynnefoProperties> {
        val allAnnotations = ArrayList<SynnefoOptions>()

        val synnefoOptions = clazz.declaredAnnotations
                .filter {
                    it is SynnefoOptions
                }
                .map { it as SynnefoOptions }

        allAnnotations.addAll(synnefoOptions)

        val synnefoOptions2 = clazz.declaredAnnotations
                .filter {
                    it is SynnefoOptionsGroup
                }
                .flatMap { (it as SynnefoOptionsGroup).value.toList() }
        allAnnotations.addAll(synnefoOptions2)

        if(allAnnotations.count() == 0)
            throw SynnefoException("Runner class is not annotated with at least one @SynnefoOptions")

        return allAnnotations.map { SynnefoProperties(it) }
    }
}
