package albelli.junit.synnefo.runtime

import albelli.junit.synnefo.runtime.exceptions.SynnefoException
import albelli.junit.synnefo.runtime.exceptions.SynnefoTestFailureException
import albelli.junit.synnefo.runtime.exceptions.SynnefoTestStoppedException
import albelli.junit.synnefo.runtime.exceptions.SynnefoTestTimedOutException
import kotlinx.coroutines.*
import org.junit.runner.Description
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunNotifier
import software.amazon.awssdk.core.retry.RetryPolicy
import software.amazon.awssdk.core.retry.RetryUtils
import software.amazon.awssdk.core.retry.backoff.BackoffStrategy
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.services.codebuild.CodeBuildClient
import software.amazon.awssdk.services.codebuild.model.*
import software.amazon.awssdk.services.codebuild.model.StatusType.*
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import java.io.File
import java.lang.Exception
import java.net.URI
import java.nio.file.Paths
import java.util.*
import kotlin.collections.HashMap

internal class AmazonCodeBuildScheduler(private val classLoader: ClassLoader) {

    // TODO
    // Should we have an option to use these clients with keys/secrets?
    // At this point the only way to use them is to use the environment variables
    private val s3: S3Client = S3Client
            .builder()
            .build()
    private val codeBuild: CodeBuildClient = CodeBuildClient
            .builder()
            .overrideConfiguration {
                it.retryPolicy(retryPolicy())
            }
            .build()

    private fun retryPolicy(): RetryPolicy {
        return RetryPolicy
                .builder()
                .numRetries(5)
                .retryCondition { RetryUtils.isServiceException(it.exception()) }
                .backoffStrategy(BackoffStrategy.defaultThrottlingStrategy())
                .build()
    }

    private val installPhaseTemplate =
            "  install:\n" +
            "    runtime-versions:\n" +
            "%s\n"
    // 5 spaces to indent the underlying items

    private val buildSpecTemplate = "version: 0.2\n" +
            "\n" +
            "phases:\n" +
            "  pre_build:\n" +
            "    commands:\n" +
            "      - echo Build started on `date`\n" +
            "%s" +
            "  build:\n" +
            "    commands:\n" +
            "      - mkdir result-artifacts\n" +
            "      - cd result-artifacts\n" +
            "      - %s\n" +
            "      - ls\n" +
            "  post_build:\n" +
            "    commands:\n" +
            "      - echo Build completed on `date`\n" +
            "artifacts:\n" +
            "  files:\n" +
            "    - 'result-artifacts/**/*'\n" +
            "  discard-paths: yes"

    internal data class Job(
            val runnerInfos: List<SynnefoRunnerInfo>,
            val notifier: RunNotifier,
            val randomSeed : Long = System.currentTimeMillis()
    )

    internal data class ScheduledJob(val originalJob: Job, val buildId: String, val info: SynnefoRunnerInfo, val junitDescription: Description)

    internal suspend fun scheduleAndWait(job: Job) {
        val uniqueProps = job.runnerInfos.groupBy { it.synnefoOptions }.map { it.key }
        val featuresCount = uniqueProps.flatMap { it.featurePaths }.count()
        if (featuresCount == 0) {
            println("No feature paths specified, will do nothing")
            return
        }

        val locationMap = uniqueProps.map {
            val sourceLocation = uploadToS3AndGetSourcePath(it)
            ensureProjectExists(it)
            Pair(it, sourceLocation)
        }.associateBy({ it.first }, { it.second })

        // Use the sum of the threads as the total amount of threads available.
        // The downside of this approach is that if an override is used, the same value would be plugged in into both annotations
        val threads = uniqueProps.map { it.threads }.sum()

        val retryConfiguration = uniqueProps.map { it to RetryConfiguration(it.maxRetries, it.retriesPerTest) }.toMap()
        runAndWaitForJobs(job, threads, locationMap, retryConfiguration)
        println("all jobs have finished")

        for (prop in uniqueProps) {
            s3.deleteS3uploads(prop.bucketName, locationMap[prop]
                    ?: error("For whatever reason we don't have the source location for this setting"))
        }
    }

    private suspend fun S3Client.deleteS3uploads(bucketName: String, prefix: String) = withContext(Dispatchers.IO){
        if (prefix.isNullOrWhiteSpace())
            throw SynnefoException("prefix can't be empty")

        val listObjectsRequest = ListObjectsRequest
                .builder()
                .bucket(bucketName)
                .prefix(prefix)
                .build()

        val listResponse =  s3.listObjects(listObjectsRequest)
        val identifiers = listResponse.contents().map { ObjectIdentifier.builder().key(it.key()).build() }
        val deleteObjectsRequest = DeleteObjectsRequest.builder()
                .bucket(bucketName)
                .delete { t -> t.objects(identifiers) }
                .build()

        this@deleteS3uploads.deleteObjects(deleteObjectsRequest)
    }

    private suspend fun runAndWaitForJobs(job: Job, threads: Int, sourceLocations: Map<SynnefoProperties, String>, retryConfiguration: Map<SynnefoProperties, RetryConfiguration>) = coroutineScope {
        val triesPerTest: MutableMap<String, Int> = HashMap()
        val currentQueue = LinkedList<ScheduledJob>()
        val backlog = job.runnerInfos.toMutableList()
        val codeBuildRequestLimit = 100
        val s3Tasks = ArrayList<kotlinx.coroutines.Job>()
        val notificationTicker = NotificationTicker(15) { println("current running total: ${currentQueue.size}; backlog: ${backlog.size}") }

        println("Going to run ${backlog.count()} jobs")

        while (backlog.size > 0 || currentQueue.size > 0) {

            if (!currentQueue.isEmpty()) {
                val buildIdToJobMap: Map<String, ScheduledJob> = currentQueue.associateBy({ it.buildId }, { it })
                val dequeuedIds = currentQueue.dequeueUpTo(codeBuildRequestLimit).map { it.buildId }

                val request = BatchGetBuildsRequest
                        .builder()
                        .ids(dequeuedIds)
                        .build()
                val response = withContext(Dispatchers.IO){ codeBuild.batchGetBuilds(request)}

                for (build in response.builds()) {
                    val originalJob = buildIdToJobMap.getValue(build.id())

                    when (build.buildStatus()!!) {
                        STOPPED -> {
                            println("build ${originalJob.info.cucumberFeatureLocation} was stopped")
                            job.notifier.fireTestFailure(Failure(originalJob.junitDescription, SynnefoTestStoppedException("Test ${originalJob.info.cucumberFeatureLocation}")))
                        }

                        TIMED_OUT -> {
                            println("build ${originalJob.info.cucumberFeatureLocation} timed out")
                            job.notifier.fireTestFailure(Failure(originalJob.junitDescription, SynnefoTestTimedOutException("Test ${originalJob.info.cucumberFeatureLocation}")))
                        }

                        FAILED, FAULT -> run {
                            println("build ${originalJob.info.cucumberFeatureLocation} failed")
                            val thisTestRetryConfiguration = retryConfiguration[originalJob.info.synnefoOptions]
                                    ?: error("Failed to get the retry configuration for ${originalJob.info.cucumberFeatureLocation}")
                            if (thisTestRetryConfiguration.maxRetries > 0) {
                                println("It's still possible to retry with ${thisTestRetryConfiguration.maxRetries} total retries left")
                                val newRetries = triesPerTest.getOrDefault(originalJob.info.cucumberFeatureLocation, 0) + 1
                                if (newRetries <= thisTestRetryConfiguration.retriesPerTest) {
                                    println("Adding the test back to the backlog.")
                                    triesPerTest[originalJob.info.cucumberFeatureLocation] = newRetries
                                    thisTestRetryConfiguration.maxRetries--
                                    backlog.add(originalJob.info)
                                    return@run
                                }
                                println("But this test had exhausted the maximum retries per test")
                            }

                            job.notifier.fireTestFailure(Failure(originalJob.junitDescription, SynnefoTestFailureException("Test ${originalJob.info.cucumberFeatureLocation}")))
                            s3Tasks.add(launch { collectArtifact(originalJob) }
                            )
                        }

                        SUCCEEDED -> {
                            println("build ${originalJob.info.cucumberFeatureLocation} succeeded")
                            job.notifier.fireTestFinished(originalJob.junitDescription)
                            s3Tasks.add(launch { collectArtifact(originalJob) })
                        }

                        IN_PROGRESS -> currentQueue.addLast(originalJob)

                        UNKNOWN_TO_SDK_VERSION -> job.notifier.fireTestFailure(Failure(originalJob.junitDescription, SynnefoTestFailureException("Received a UNKNOWN_TO_SDK_VERSION enum! This should not have happened really.")))
                    }
                }
            }

            val availableSlots = threads - currentQueue.size
            val jobsToSpawn = backlog.dequeueUpTo(availableSlots)

            val rate = 25
            while (jobsToSpawn.isNotEmpty()) {
                val currentBatch = jobsToSpawn.dequeueUpTo(rate)

                val scheduledJobs = currentBatch
                        .map {

                            val settings = it.synnefoOptions
                            val location = sourceLocations[settings]
                                    ?: error("For whatever reason we don't have the source location for this setting")
                            val shouldTriggerNotifier = !triesPerTest.containsKey(it.cucumberFeatureLocation)
                            async { startBuild(job, settings, location, it, shouldTriggerNotifier) }
                        }
                        .map { it.await() }
                currentQueue.addAll(scheduledJobs)
                println("started ${currentBatch.count()} jobs")
                delay(2500)
            }
            delay(2000)
            notificationTicker.tick()
        }
        s3Tasks.joinAll()
    }

    private suspend fun collectArtifact(result: ScheduledJob) = withContext(Dispatchers.IO) {
        val targetDirectory = result.info.synnefoOptions.reportTargetDir

        val buildId = result.buildId.substring(result.buildId.indexOf(':') + 1)
        val keyPath = "${result.info.synnefoOptions.bucketOutputFolder}$buildId/${result.info.synnefoOptions.outputFileName}"
        val getObjectRequest = GetObjectRequest.builder()
                .bucket(result.info.synnefoOptions.bucketName)
                .key(keyPath)
                .build()!!

        val response = s3.getObject(getObjectRequest, ResponseTransformer.toInputStream())

        ZipHelper.unzip(response, targetDirectory)
        s3.deleteS3uploads(result.info.synnefoOptions.bucketName, keyPath)
        println("collected artifacts for ${result.info.cucumberFeatureLocation}")
    }

    private suspend fun uploadToS3AndGetSourcePath(settings: SynnefoProperties): String {
        println("uploadToS3AndGetSourcePath")

        val targetDirectory = settings.bucketSourceFolder + UUID.randomUUID() + "/"

        val jarPath = Paths.get(settings.classPath)
        val jarFileName = jarPath.fileName.toString()

        for (feature in settings.featurePaths) {
            if (!feature.scheme.equals("classpath", true)) {
                s3.multipartUploadFile(settings.bucketName, targetDirectory + feature.schemeSpecificPart, feature, 5)
            }
        }

        s3.multipartUploadFile(settings.bucketName, targetDirectory + jarFileName, File(settings.classPath).toURI(), 5)

        println("uploadToS3AndGetSourcePath done; path: $targetDirectory")
        return targetDirectory
    }

    private suspend fun ensureProjectExists(settings: SynnefoProperties) {
        if (projectExists(settings.projectName)) {
            return
        }

        createCodeBuildProject(settings)
    }

    private suspend fun projectExists(projectName: String?): Boolean = withContext(Dispatchers.IO) {
        val batchGetProjectsRequest = BatchGetProjectsRequest
                .builder()
                .names(projectName)
                .build()

        val response = codeBuild.batchGetProjects(batchGetProjectsRequest)
        return@withContext response.projects().size == 1
    }

    private suspend fun createCodeBuildProject(settings: SynnefoProperties) = withContext(Dispatchers.IO) {
        val sourceLocation = settings.bucketName + "/" + settings.bucketSourceFolder

        val createRequest = CreateProjectRequest
                .builder()
                .name(settings.projectName)
                .description("Autogenerated codebuild project to run tests")
                .artifacts { a ->
                    a
                            .type(ArtifactsType.S3)
                            .path(settings.bucketOutputFolder)
                            .name(settings.outputFileName)
                            .namespaceType("BUILD_ID")
                            .packaging("ZIP")
                            .location(settings.bucketName)
                }
                .cache { b -> b.type(CacheType.NO_CACHE) }
                .environment { b ->
                    b
                            .type(EnvironmentType.LINUX_CONTAINER)
                            .image(settings.image)
                            .computeType(settings.computeType)
                }
                .serviceRole(settings.serviceRole)
                .source { s ->
                    s
                            .type(SourceType.S3)
                            .location(sourceLocation)
                }
                .logsConfig { l ->
                    l
                            .cloudWatchLogs { cwl ->
                                cwl
                                        .groupName("/aws/codebuild/" + settings.projectName)
                                        .status(LogsConfigStatusType.ENABLED)
                            }
                }
                .build()

        codeBuild.createProject(createRequest)
    }

    private suspend fun startBuild(job: Job, settings: SynnefoProperties, sourceLocation: String, info: SynnefoRunnerInfo, triggerTestStarted: Boolean): ScheduledJob = withContext(Dispatchers.IO) {
        val codeBuildRuntimes = settings.codeBuildRunTimeVersions.map { String.format("     %s", it.trim()) }
        val installPhaseSection = if (codeBuildRuntimes.isEmpty()) "" else String.format(installPhaseTemplate, codeBuildRuntimes.joinToString())

        val buildSpec = generateBuildspecForFeature(Paths.get(settings.classPath).fileName.toString()
                , info.cucumberFeatureLocation, info.runtimeOptions, job.randomSeed, installPhaseSection)

        val buildStartRequest = StartBuildRequest.builder()
                .projectName(settings.projectName)
                .buildspecOverride(buildSpec)
                .imageOverride(settings.image)
                .computeTypeOverride(settings.computeType)
                .artifactsOverride { a ->
                    a
                            .type(ArtifactsType.S3)
                            .path(settings.bucketOutputFolder)
                            .name(settings.outputFileName)
                            .namespaceType("BUILD_ID")
                            .packaging("ZIP")
                            .location(settings.bucketName)
                }
                .sourceLocationOverride(settings.bucketName + "/" + sourceLocation)
                .build()


        val startBuildResponse = codeBuild.startBuild(buildStartRequest)
        val buildId = startBuildResponse.build().id()
        val junitDescription = Description.createTestDescription("Synnefo", info.cucumberFeatureLocation)

        if (triggerTestStarted)
            job.notifier.fireTestStarted(junitDescription)

        return@withContext ScheduledJob(job, buildId, info, junitDescription)
    }

    private fun readFileChunks(file: URI, partSizeMb: Int) = sequence {
        val connection = file.toValidURL(classLoader).openConnection()
        connection.getInputStream().use {s ->
            val contentLength = connection.contentLength
            var partSize = partSizeMb * 1024 * 1024

            var filePosition: Long = 0
            var i = 1

            while (filePosition < contentLength) {
                partSize = Math.min(partSize, (contentLength - filePosition).toInt())

                val fileContent = ByteArray(partSize)
                s.read(fileContent, 0, partSize)

                yield(Pair(i, fileContent))
                filePosition += partSize
                i++
            }
        }
    }

    private suspend fun S3Client.multipartUploadFile(bucket: String, key: String, filePath: URI, partSizeMb: Int) = withContext(Dispatchers.IO) {
        val s3clientExt = this@multipartUploadFile
        val filteredKey = key.replace("//", "/")

        val createUploadRequest = CreateMultipartUploadRequest.builder()
                .key(filteredKey)
                .bucket(bucket)
                .build()

        val response = s3clientExt.createMultipartUpload(createUploadRequest)

        val chunks = readFileChunks(filePath, partSizeMb).toList()

        val partETags =
                chunks.map {
                    val uploadRequest = UploadPartRequest.builder()
                            .bucket(bucket)
                            .key(filteredKey)
                            .uploadId(response.uploadId())
                            .partNumber(it.first)
                            .build()
                    val data = RequestBody.fromBytes(it.second)

                    val etag = s3clientExt.uploadPart(uploadRequest, data).eTag()

                    CompletedPart.builder().partNumber(it.first).eTag(etag).build()
                }

        val completedMultipartUpload = CompletedMultipartUpload.builder().parts(partETags)
                .build()
        val completeMultipartUploadRequest =
                CompleteMultipartUploadRequest.builder()
                        .bucket(bucket)
                        .key(filteredKey)
                        .uploadId(response.uploadId())
                        .multipartUpload(completedMultipartUpload)
                        .build()
        s3clientExt.completeMultipartUpload(completeMultipartUploadRequest)
    }

    private fun generateBuildspecForFeature(jar: String, feature: String, runtimeOptions: List<String>, randomSeed : Long, installPhaseSection : String): String {
        val sb = StringBuilder()
        sb.appendWithEscaping("java")
        sb.appendWithEscaping("-cp")
        sb.appendWithEscaping("./../$jar")
        sb.appendWithEscaping(String.format("-D%s=%s", "java.random.seed", randomSeed))
        getSystemProperties().forEach { sb.appendWithEscaping(it) }
        sb.appendWithEscaping("cucumber.api.cli.Main")
        if (feature.startsWith("classpath")) {
            sb.appendWithEscaping(feature)
        } else {
            sb.appendWithEscaping("./../$feature")
        }
        runtimeOptions.forEach { sb.appendWithEscaping(it) }

        return String.format(this.buildSpecTemplate, installPhaseSection, sb.toString())
    }

    private fun getSystemProperties(): List<String> {
        val transferrableProperties = System.getProperty("SynnefoTransferrableProperties")
        if (transferrableProperties.isNullOrWhiteSpace())
            return arrayListOf()

        val propertiesList = transferrableProperties.split(';')

        return System.getProperties()
                .map {
                    Pair(it.key.toString(), it.value.toString().trim())
                }
                .filter { pair ->
                    val isNotIgnored = propertiesList.any { pair.first.startsWith(it, ignoreCase = true) }
                    val isNotEmpty = !pair.second.isNullOrWhiteSpace()
                    isNotIgnored && isNotEmpty
                }
                .map {
                    String.format("-D%s=%s", it.first, it.second)
                }
    }

    internal class RetryConfiguration(var maxRetries: Int, val retriesPerTest: Int)

    internal class NotificationTicker(val times: Int, val tickFun: () -> Unit) {
        private var internalTicker = 0
        fun tick() {
            if (internalTicker++ >= times) {
                internalTicker = 0
                tickFun()
            }
        }
    }
}
