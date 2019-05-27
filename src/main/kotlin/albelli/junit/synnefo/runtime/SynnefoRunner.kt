package albelli.junit.synnefo.runtime

import kotlinx.coroutines.runBlocking
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.RunNotifier

internal class SynnefoRunner(classLoader: ClassLoader, private val notifier: RunNotifier) {

    private val scheduler: AmazonCodeBuildScheduler = AmazonCodeBuildScheduler(classLoader)

    fun run(runnerInfos: List<SynnefoRunnerInfo>) {
        val result = Result()
        notifier.fireTestRunStarted(Description.createSuiteDescription("Started the tests"))

        val job = AmazonCodeBuildScheduler.Job(
                runnerInfos,
                notifier)

        job.notifier.addFirstListener(result.createListener())

        runBlocking {
            scheduler.scheduleAndWait(job)
        }
        notifier.fireTestRunFinished(result)
    }
}
