package albelli.junit.synnefo.runtime

import albelli.junit.synnefo.runtime.exceptions.SynnefoException
import albelli.junit.synnefo.runtime.exceptions.SynnefoTestFailureException
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import org.junit.runner.Description
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunNotifier
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.services.codebuild.CodeBuildAsyncClient
import software.amazon.awssdk.services.codebuild.model.*
import software.amazon.awssdk.services.codebuild.model.StatusType.*
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import java.io.File
import java.net.URI
import java.nio.file.Paths
import java.util.*

internal class AmazonCodeBuildScheduler(private val settings: SynnefoProperties, private val classLoader: ClassLoader) {

    // TODO
    // Should we have an option to use these clients with keys/secrets?
    // At this point the only way to use them is to use the environment variables
    private val s3: S3AsyncClient = S3AsyncClient.builder().build()
    private val codeBuild: CodeBuildAsyncClient = CodeBuildAsyncClient.builder().build()

    private val buildSpecTemplate = "version: 0.2\n" +
            "\n" +
            "phases:\n" +
            "  pre_build:\n" +
            "    commands:\n" +
            "      - echo Build started on `date`\n" +
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
            val jarPath: String,
            val featurePaths: List<URI>,
            val notifier: RunNotifier
    )

    internal data class ScheduledJob(val originalJob: Job, val buildId: String, val info: SynnefoRunnerInfo, val junitDescription: Description)

    internal suspend fun scheduleAndWait(job: Job) {
        if (job.featurePaths.isEmpty())
        {
            println("No feature paths specified, will do nothing")
            return
        }
        println("Going to run ${job.featurePaths.count()} jobs")

        val sourceLocation = uploadToS3AndGetSourcePath(job, settings)
        ensureProjectExists(settings)

        runAndWaitForJobs(job, sourceLocation)
        println("all jobs have finished")
        s3.deleteS3uploads(settings.bucketName, sourceLocation)
    }

    private suspend fun S3AsyncClient.deleteS3uploads(bucketName: String, prefix: String) {
        if(prefix.isNullOrWhiteSpace())
            throw SynnefoException("prefix can't be empty")

        val listObjectsRequest = ListObjectsRequest
                .builder()
                .bucket(bucketName)
                .prefix(prefix)
                .build()

        val listResponse = s3.listObjects(listObjectsRequest).await()


        val identifiers = listResponse.contents().map { ObjectIdentifier.builder().key(it.key()).build() }

        val deleteObjectsRequest = DeleteObjectsRequest.builder()
                .bucket(bucketName)
                .delete { t -> t.objects(identifiers) }
                .build()

        this.deleteObjects(deleteObjectsRequest).await()
    }

    private suspend fun runAndWaitForJobs(job: Job, sourceLocation: String) {
        val currentQueue = LinkedList<ScheduledJob>()

        val backlog = job.runnerInfos.toMutableList()

        val codeBuildRequestLimit = 100

        val s3Tasks = ArrayList<Deferred<Unit>>()

        while (backlog.size > 0 || currentQueue.size > 0) {

            if(!currentQueue.isEmpty()) {

                val lookupDict: Map<String, ScheduledJob> = currentQueue.associateBy({ it.buildId }, { it })
                val dequeuedIds = currentQueue.dequeueUpTo(codeBuildRequestLimit).map { it.buildId }

                val request = BatchGetBuildsRequest
                        .builder()
                        .ids(dequeuedIds)
                        .build()
                val response = codeBuild.batchGetBuilds(request).await()

                for (build in response.builds()) {

                    val originalJob = lookupDict.getValue(build.id())

                    when (build.buildStatus()!!) {
                        STOPPED, TIMED_OUT, FAILED, FAULT -> {
                            job.notifier.fireTestFailure(Failure(originalJob.junitDescription, SynnefoTestFailureException("Test ${originalJob.info.cucumberFeatureLocation}")))
                            s3Tasks.add(GlobalScope.async { collectArtifact(originalJob) })
                            println("build ${originalJob.info.cucumberFeatureLocation} failed")
                        }

                        SUCCEEDED -> {
                            job.notifier.fireTestFinished(originalJob.junitDescription)
                            s3Tasks.add(GlobalScope.async { collectArtifact(originalJob) })
                            println("build ${originalJob.info.cucumberFeatureLocation} succeded")
                        }

                        IN_PROGRESS -> currentQueue.addLast(originalJob)

                        UNKNOWN_TO_SDK_VERSION -> job.notifier.fireTestFailure(Failure(originalJob.junitDescription, SynnefoTestFailureException("Received a UNKNOWN_TO_SDK_VERSION enum! This should not have happened really.")))
                    }
                }
            }

            val availableSlots = settings.threads - currentQueue.size

            val scheduledJobs =
                    backlog
                            .dequeueUpTo(availableSlots)
                            .map {
                                GlobalScope.async { startBuild(job, settings, sourceLocation, it) }
                            }
                            .map { it.await() }

            currentQueue.addAll(scheduledJobs)

            delay(2000)
        }

        s3Tasks.awaitAll()
    }

    private suspend fun collectArtifact(result : ScheduledJob)
    {
        val targetDirectory = settings.reportTargetDir

        val buildId = result.buildId.substring(result.buildId.indexOf(':') + 1)
        val keyPath = "${settings.bucketOutputFolder}$buildId/${settings.outputFileName}"

        val getObjectRequest = GetObjectRequest.builder()
                .bucket(settings.bucketName)
                .key(keyPath)
                .build()!!

        // TODO:
        // Use the async client from above
        // Once the buggy S3 client is fixed by Amazon
        val client = S3Client
                .builder()
                .build()
        val response = client.getObject(getObjectRequest, ResponseTransformer.toInputStream())

        ZipHelper.unzip(response, targetDirectory)
        s3.deleteS3uploads(settings.bucketName, keyPath)
        println("collected artifacts for ${result.info.cucumberFeatureLocation}")
    }

    private suspend fun uploadToS3AndGetSourcePath(job: Job, settings: SynnefoProperties): String {

        println("uploadToS3AndGetSourcePath")

        val targetDirectory = settings.bucketSourceFolder + UUID.randomUUID() + "/"

        val jarPath = Paths.get(job.jarPath)
        val jarFileName = jarPath.fileName.toString()

        for (feature in job.featurePaths) {
            if (!feature.scheme.equals("classpath", true))
            {
                s3.multipartUploadFile(settings.bucketName, targetDirectory + feature.schemeSpecificPart, feature, 5)
            }
        }

        s3.multipartUploadFile(settings.bucketName, targetDirectory + jarFileName, File(job.jarPath).toURI(), 5)

        println("uploadToS3AndGetSourcePath done; path: $targetDirectory")
        return targetDirectory
    }

    private suspend fun ensureProjectExists(settings: SynnefoProperties) {
        if (projectExists(settings.projectName)) {
            return
        }

        createCodeBuildProject(settings)
    }

    private suspend fun projectExists(projectName: String?): Boolean {

        val batchGetProjectsRequest = BatchGetProjectsRequest
                .builder()
                .names(projectName)
                .build()

        val response = codeBuild.batchGetProjects(batchGetProjectsRequest).await()
        return response.projects().size == 1
    }

    private suspend fun createCodeBuildProject(settings: SynnefoProperties) {
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

        codeBuild.createProject(createRequest).await()
    }


    private suspend fun startBuild(job: Job, settings: SynnefoProperties, sourceLocation: String, info: SynnefoRunnerInfo): ScheduledJob {
        val buildSpec = generateBuildspecForFeature(Paths.get(job.jarPath).fileName.toString(), info.cucumberFeatureLocation, info.runtimeOptions)

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


        val startBuildResponse =  codeBuild.startBuild(buildStartRequest).await()
        val buildId = startBuildResponse.build().id()
        val junitDescription = Description.createTestDescription("Synnefo", info.cucumberFeatureLocation)
        job.notifier.fireTestStarted(junitDescription)

        return ScheduledJob(job, buildId, info, junitDescription)
    }

    private fun readFileChunks(file: URI, partSizeMb: Int) = sequence {

        val connection = file.toValidURL(classLoader).openConnection()
        val stream = connection.getInputStream()

        val contentLength = connection.contentLength
        var partSize = partSizeMb * 1024 * 1024

        var filePosition: Long = 0
        var i = 1

        while (filePosition < contentLength) {
            partSize = Math.min(partSize, (contentLength - filePosition).toInt())

            val fileContent = ByteArray(partSize)
            stream.read(fileContent, 0, partSize)

            yield(Pair(i, fileContent))
            filePosition += partSize
            i++
        }
        stream.close()
    }

    private suspend fun S3AsyncClient.multipartUploadFile(bucket: String, key: String, filePath: URI, partSizeMb: Int) {
        val s3clientExt = this
        val filteredKey = key.replace("//", "/")

        val createUploadRequest = CreateMultipartUploadRequest.builder()
                .key(filteredKey)
                .bucket(bucket)
                .build()

        val response = s3clientExt.createMultipartUpload(createUploadRequest).await()

        val chunks = readFileChunks(filePath, partSizeMb).toList()

        val partETags =
                chunks.map {
            val uploadRequest = UploadPartRequest.builder()
                    .bucket(bucket)
                    .key(filteredKey)
                    .uploadId(response.uploadId())
                    .partNumber(it.first)
                    .build()
            val data = AsyncRequestBody.fromBytes(it.second)

            val etag = s3clientExt.uploadPart(uploadRequest, data).await().eTag()

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
        s3clientExt.completeMultipartUpload(completeMultipartUploadRequest).await()
    }

    private fun generateBuildspecForFeature(jar: String, feature: String, runtimeOptions: List<String>): String {
        val sb = StringBuilder()
        sb.appendWithEscaping("java")
        sb.appendWithEscaping("-cp")
        sb.appendWithEscaping("./../$jar")
        sb.appendWithEscaping("cucumber.api.cli.Main")
        if(feature.startsWith("classpath")) {
            sb.appendWithEscaping(feature)
        }
        else {
            sb.appendWithEscaping("./../$feature")
        }
        runtimeOptions.forEach { sb.appendWithEscaping(it) }

        return String.format(this.buildSpecTemplate, sb.toString())
    }


}
