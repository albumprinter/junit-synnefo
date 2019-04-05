package albelli.junit.synnefo.runtime

enum class RunResultStatus {
    PASSED,
    FAILED
}

class SynnefoRunResult(val status: RunResultStatus, val originalJob: AmazonCodeBuildScheduler.ScheduledJob)