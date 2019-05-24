package albelli.junit.synnefo.runtime

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.RunNotifier

internal class SynnefoRunner(classLoader: ClassLoader, private val notifier: RunNotifier) {

    private val scheduler: AmazonCodeBuildScheduler = AmazonCodeBuildScheduler(classLoader)

    fun run(preJobs: List<Pair<SynnefoProperties ,List<SynnefoRunnerInfo>>>) {
        val result = Result()
        notifier.fireTestRunStarted(Description.createSuiteDescription("Started the tests"))

        val jobs = ArrayList<AmazonCodeBuildScheduler.Job>()

        for ((synnefoProperties, runnerInfoList) in preJobs){
            val job = AmazonCodeBuildScheduler.Job(
                    runnerInfoList,
                    notifier,
                    synnefoProperties)

            job.notifier.addFirstListener(result.createListener())
            jobs.add(job)
        }

        runBlocking {
            jobs
            .map {
                GlobalScope.async { scheduler.scheduleAndWait(it) }
            }
            .map { it.await() }
        }
        notifier.fireTestRunFinished(result)
    }
}
