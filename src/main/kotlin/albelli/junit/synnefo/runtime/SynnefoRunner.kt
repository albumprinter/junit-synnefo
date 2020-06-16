package albelli.junit.synnefo.runtime

import kotlinx.coroutines.runBlocking
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.RunNotifier

internal class SynnefoRunner(private val classLoader: ClassLoader, private val notifier: RunNotifier) {
    fun run(runnerInfos: List<SynnefoRunnerInfo>) {
        val result = Result()
        notifier.fireTestRunStarted(Description.createSuiteDescription("Started the tests"))

        val job = AmazonCodeBuildScheduler.Job(
                runnerInfos,
                notifier)

        job.notifier.addFirstListener(result.createListener())

        runBlocking {
            AmazonCodeBuildScheduler(classLoader)
                    .use { s -> s.scheduleAndWait(job) }
        }
        notifier.fireTestRunFinished(result)
    }
}
