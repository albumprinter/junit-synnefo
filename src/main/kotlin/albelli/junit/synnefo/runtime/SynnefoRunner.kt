package albelli.junit.synnefo.runtime

import cucumber.runtime.model.CucumberFeature
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.RunNotifier
import java.util.*


class SynnefoRunner(
        private val runnerInfoList: List<SynnefoRunnerInfo>,
        private val synnefoProperties: SynnefoProperties,
        private val cucumberFeatures: List<CucumberFeature>,
        private val notifier: RunNotifier) {

    private val scheduler: AmazonCodeBuildScheduler = AmazonCodeBuildScheduler(synnefoProperties)

    fun run() {
        val job = AmazonCodeBuildScheduler.Job(
                runnerInfoList,
                synnefoProperties.classPath,
                cucumberFeatures.map { it.uri.schemeSpecificPart },
                notifier)

        var result = Result()
        job.notifier.addFirstListener(result.createListener())
        job.notifier.fireTestRunStarted(Description.createSuiteDescription("Started the tests"))
        val jobs = this.scheduler.schedule(job)
        this.scheduler.waitForJobs(jobs)
        this.scheduler.collectArtifacts(jobs)
        job.notifier.fireTestRunFinished(result)
    }
}
