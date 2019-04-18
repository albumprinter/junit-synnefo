package albelli.junit.synnefo.runtime

import albelli.junit.synnefo.runtime.exceptions.SynnefoTestFailureException
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import org.junit.runner.Description
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunNotifier
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.codebuild.CodeBuildAsyncClient
import software.amazon.awssdk.services.codebuild.model.*
import software.amazon.awssdk.services.codebuild.model.StatusType.*
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import java.io.File
import java.io.FileInputStream
import java.nio.file.Paths
import java.util.*

class AmazonCodeBuildScheduler(private val settings: SynnefoProperties) {

    // TODO
    // Should we have an option to use these clients with keys/secrets?
    // At this point the only way to use them is to use the environment variables
    private val s3: S3AsyncClient = S3AsyncClient.builder().build()
    private val codeBuild: CodeBuildAsyncClient = CodeBuildAsyncClient.builder().build()

    // 1 - jar
    // 2 - feature
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

    data class Job(
            val runnerInfos: List<SynnefoRunnerInfo>,
            val jarPath: String,
            val featurePaths: List<String>,
            val notifier: RunNotifier
    )

    data class ScheduledJob(val originalJob: Job, val buildId: String, val info: SynnefoRunnerInfo, val junitDescription: Description)

    suspend fun scheduleAndWait(job: Job) = runBlocking {
        if (job.featurePaths.isEmpty())
            return@runBlocking

        val sourceLocation = uploadToS3AndGetSourcePath(job, settings)
        ensureProjectExists(settings)

        runAndWaitForJobs(job, sourceLocation)
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
                        }

                        SUCCEEDED -> {
                            job.notifier.fireTestFinished(originalJob.junitDescription)
                            s3Tasks.add(GlobalScope.async { collectArtifact(originalJob) })
                        }

                        IN_PROGRESS -> currentQueue.addLast(originalJob)

                        UNKNOWN_TO_SDK_VERSION -> job.notifier.fireTestFailure(Failure(originalJob.junitDescription, SynnefoTestFailureException("Received a UNKNOWN_TO_SDK_VERSION enum! This should not have happened really.")))
                    }
                }
            }

            val availableSlots = settings.synnefoOptions.threads - currentQueue.size

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
        val targetDirectory = settings.synnefoOptions.reportTargetDir

        val buildId = result.buildId.substring(result.buildId.indexOf(':') + 1)
        val keyPath = "${settings.synnefoOptions.bucketOutputFolder}$buildId/${settings.synnefoOptions.outputFileName}"

        val getObjectRequest = GetObjectRequest.builder()
                .bucket(settings.synnefoOptions.bucketName)
                .key(keyPath)
                .build()!!

        val client = S3Client
                .builder()
                .region(Region.EU_WEST_1)
                .build()
        val response = client.getObject(getObjectRequest, ResponseTransformer.toInputStream())

        ZipHelper.unzip(response, targetDirectory)
    }

    private suspend fun uploadToS3AndGetSourcePath(job: Job, settings: SynnefoProperties): String {

        val targetDirectory = settings.synnefoOptions.bucketSourceFolder + UUID.randomUUID() + "/"

        val jarPath = Paths.get(job.jarPath)
        val jarFileName = jarPath.fileName.toString()

        for (feature in job.featurePaths) {
            s3.multipartUploadFile(settings.synnefoOptions.bucketName, targetDirectory + feature, feature, 5)
        }

        s3.multipartUploadFile(settings.synnefoOptions.bucketName, targetDirectory + jarFileName, job.jarPath, 5)

        return targetDirectory
    }

    private suspend fun ensureProjectExists(settings: SynnefoProperties) {
        if (projectExists(settings.synnefoOptions.projectName)) {
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
        val sourceLocation = settings.synnefoOptions.bucketName + "/" + settings.synnefoOptions.bucketSourceFolder

        val createRequest = CreateProjectRequest
                .builder()
                .name(settings.synnefoOptions.projectName)
                .description("Autogenerated codebuild project to run tests")
                .artifacts { a ->
                    a
                            .type(ArtifactsType.S3)
                            .path(settings.synnefoOptions.bucketOutputFolder)
                            .name(settings.synnefoOptions.outputFileName)
                            .namespaceType("BUILD_ID")
                            .packaging("ZIP")
                            .location(settings.synnefoOptions.bucketName)
                }
                .cache { b -> b.type(CacheType.NO_CACHE) }
                .environment { b ->
                    b
                            .type(EnvironmentType.LINUX_CONTAINER)
                            .image(settings.synnefoOptions.image)
                            .computeType(settings.synnefoOptions.computeType)
                }
                .serviceRole(settings.synnefoOptions.serviceRole)
                .source { s ->
                    s
                            .type(SourceType.S3)
                            .location(sourceLocation)
                }
                .logsConfig { l ->
                    l
                            .cloudWatchLogs { cwl ->
                                cwl
                                        .groupName("/aws/codebuild/" + settings.synnefoOptions.projectName)
                                        .status(LogsConfigStatusType.ENABLED)
                            }
                }
                .build()

        codeBuild.createProject(createRequest).await()
    }


    private suspend fun startBuild(job: Job, settings: SynnefoProperties, sourceLocation: String, info: SynnefoRunnerInfo): ScheduledJob {
        val buildSpec = generateBuildspecForFeature(Paths.get(job.jarPath).fileName.toString(), info.cucumberFeatureLocation, info.runtimeOptions)

        val buildStartRequest = StartBuildRequest.builder()
                .projectName(settings.synnefoOptions.projectName)
                .buildspecOverride(buildSpec)
                .imageOverride(settings.synnefoOptions.image)
                .computeTypeOverride(settings.synnefoOptions.computeType)
                .artifactsOverride { a ->
                    a
                            .type(ArtifactsType.S3)
                            .path(settings.synnefoOptions.bucketOutputFolder)
                            .name(settings.synnefoOptions.outputFileName)
                            .namespaceType("BUILD_ID")
                            .packaging("ZIP")
                            .location(settings.synnefoOptions.bucketName)
                }
                .sourceLocationOverride(settings.synnefoOptions.bucketName + "/" + sourceLocation)
                .build()


        val startBuildResponse =  codeBuild.startBuild(buildStartRequest).await()
        val buildId = startBuildResponse.build().id()
        val junitDescription = Description.createTestDescription(info.cucumberFeatureLocation, info.cucumberFeatureLocation)
        job.notifier.fireTestStarted(junitDescription)

        return ScheduledJob(job, buildId, info, junitDescription)
    }

    private fun readFileChunks(file: File, partSizeMb: Int) = sequence {
        val contentLength = file.length()
        var partSize = partSizeMb * 1024 * 1024

        val stream = FileInputStream(file)
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

    private suspend fun S3AsyncClient.multipartUploadFile(bucket: String, key: String, filePath: String, partSizeMb: Int) {
        val s3clientExt = this
        val createUploadRequest = CreateMultipartUploadRequest.builder()
                .key(key)
                .bucket(bucket)
                .build()

        val file = File(filePath)

        val response = s3clientExt.createMultipartUpload(createUploadRequest).await()

        val chunks = readFileChunks(file, partSizeMb).toList()

        val partETags = ArrayList<CompletedPart>()

        for(chunk in chunks)
        {
            val uploadRequest = UploadPartRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(response.uploadId())
                    .partNumber(chunk.first)
                    .build()
            val data = AsyncRequestBody.fromBytes(chunk.second)

            val etag = s3clientExt.uploadPart(uploadRequest, data).await().eTag()

            partETags.add(CompletedPart.builder().partNumber(chunk.first).eTag(etag).build())
        }

//        val partETags =
//                chunks.map {
//            val uploadRequest = UploadPartRequest.builder()
//                    .bucket(bucket)
//                    .key(key)
//                    .uploadId(response.uploadId())
//                    .partNumber(it.first)
//                    .build()
//            val data = AsyncRequestBody.fromBytes(it.second)
//
//            val etag = s3clientExt.uploadPart(uploadRequest, data).await().eTag()
//
//            CompletedPart.builder().partNumber(it.first).eTag(etag).build()
//        }

        val completedMultipartUpload = CompletedMultipartUpload.builder().parts(partETags)
                .build()
        val completeMultipartUploadRequest =
                CompleteMultipartUploadRequest.builder()
                        .bucket(bucket)
                        .key(key)
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
        sb.appendWithEscaping("./../$feature")
        runtimeOptions.forEach { sb.appendWithEscaping(it) }

        return String.format(this.buildSpecTemplate, sb.toString())
    }
}
