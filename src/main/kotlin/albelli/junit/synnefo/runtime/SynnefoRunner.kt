package albelli.junit.synnefo.runtime

import cucumber.runtime.model.CucumberFeature
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.RunNotifier
import java.util.*


class SynnefoRunner(
        private val runnerInfoList: List<SynnefoRunnerInfo>,
        private val SynnefoProperties: SynnefoProperties,
        private val cucumberFeatures: List<CucumberFeature>,
        private val notifier: RunNotifier) {

    private val scheduler: AmazonCodeBuildScheduler = AmazonCodeBuildScheduler(SynnefoProperties)
    private val runResults = ArrayList<SynnefoRunResult>()

    fun run() {
        runResults.clear()

        val job = AmazonCodeBuildScheduler.Job(
                runnerInfoList,
                SynnefoProperties.classPath,
                cucumberFeatures.map { it.uri.schemeSpecificPart },
                notifier)

        var result = Result()
        job.notifier.addFirstListener(result.createListener())
        job.notifier.fireTestRunStarted(Description.createSuiteDescription("Started the tests"))
        val jobs = this.scheduler.schedule(job)
        runResults.addAll(this.scheduler.waitForJobs(jobs))
        job.notifier.fireTestRunFinished(result)
    }

    fun collectArtifacts() {

        this.scheduler.collectArtifacts(this.runResults)
    }
}
