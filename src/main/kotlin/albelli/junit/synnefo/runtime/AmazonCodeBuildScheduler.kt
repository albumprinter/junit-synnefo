package albelli.junit.synnefo.runtime

import albelli.junit.synnefo.runtime.exceptions.SynnefoTestFailureException
import org.junit.runner.Description
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunNotifier
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.services.codebuild.CodeBuildClient
import software.amazon.awssdk.services.codebuild.model.*
import software.amazon.awssdk.services.codebuild.model.StatusType.*
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import java.io.*
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.ExecutionException
import java.util.function.Consumer
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class AmazonCodeBuildScheduler(private val settings: SynnefoProperties) {

    // TODO
    // Should we have an option to use these clients with keys/secrets?
    // At this point the only way to use them is to use the environment variables
    private val s3: S3Client = S3Client.builder().build()
    private val codeBuild: CodeBuildClient = CodeBuildClient.builder().build()

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

    @Throws(ExecutionException::class, InterruptedException::class)
    fun schedule(job: Job): List<ScheduledJob> {
        if (job.featurePaths.isEmpty())
            return ArrayList<SynnefoRunResult>();

        val sourceLocation = uploadToS3AndGetSourcePath(job, settings)
        ensureProjectExists(settings)
        return startBuilds(job, settings, sourceLocation)
    }

    fun waitForJobs(jobs: List<ScheduledJob>): ArrayList<SynnefoRunResult> {
        val runResults = ArrayList<SynnefoRunResult>()

        val queue = LinkedList<ScheduledJob>()
        queue.addAll(jobs)

        val limit = 100

        while (!queue.isEmpty()) {
            val lookupDict = queue.associateBy({ it.buildId }, { it })

            val dequeued = queue.dequeueUpTo(limit)
            val dequeuedIds = dequeued.map { it.buildId }
            dequeued.clear()

            val request = BatchGetBuildsRequest
                    .builder()
                    .ids(dequeuedIds)
                    .build()
            val response = codeBuild.batchGetBuilds(request)
            for (build in response.builds()) {

                val originalJob = lookupDict.getValue(build.id())

                when (build.buildStatus()!!) {
                    STOPPED, TIMED_OUT, FAILED, FAULT ->
                    {
                        runResults.add(SynnefoRunResult(RunResultStatus.FAILED, originalJob))
                        originalJob.originalJob.notifier.fireTestFailure(Failure(originalJob.junitDescription, SynnefoTestFailureException("Test ${originalJob.info.cucumberFeatureLocation}")))
                    }

                    SUCCEEDED ->
                    {
                        runResults.add(SynnefoRunResult(RunResultStatus.PASSED, originalJob))
                        originalJob.originalJob.notifier.fireTestFinished(originalJob.junitDescription)

                    }

                    IN_PROGRESS -> queue.addLast(originalJob)

                    UNKNOWN_TO_SDK_VERSION -> throw Exception("nao we die")
                }
            }

            Thread.sleep(2000)
        }
        return runResults
    }


    fun collectArtifacts(runResults: ArrayList<SynnefoRunResult>) {
        val targetDirectory = settings.SynnefoOptions.reportTargetDir

        for (result in runResults) {
            val buildId = result.originalJob.buildId.substring(result.originalJob.buildId.indexOf(':') + 1)
            val keyPath = "${settings.SynnefoOptions.bucketOutputFolder}$buildId/${settings.SynnefoOptions.outputFileName}"

            val getObjectRequest = GetObjectRequest.builder()
                    .bucket(settings.SynnefoOptions.bucketName)
                    .key(keyPath)
                    .build()!!


            val stream = s3.getObject(getObjectRequest, ResponseTransformer.toInputStream())
            unzip(stream, targetDirectory)
        }
    }

    private fun generateBuildspecForFeature(jar: String, feature: String, runtimeOptions: Map<String, List<String>>): String {
        val sb = StringBuilder()
        sb.appendWithEscaping("java")
        sb.appendWithEscaping("-cp")
        sb.appendWithEscaping("./../$jar")
        sb.appendWithEscaping("cucumber.api.cli.Main")
        sb.appendWithEscaping("./../$feature")
        runtimeOptions.forEach { _, values -> values.forEach { sb.appendWithEscaping(it) } }

        return String.format(this.buildSpecTemplate, sb.toString())
    }

    @Throws(ExecutionException::class, InterruptedException::class)
    private fun uploadToS3AndGetSourcePath(job: Job, settings: SynnefoProperties): String {
        // TODO:
        // Make this whole thing properly async

        val targetDirectory = settings.SynnefoOptions.bucketSourceFolder + UUID.randomUUID() + "/"

        val jarPath = Paths.get(job.jarPath)
        val jarFileName = jarPath.fileName.toString()

        for (feature in job.featurePaths) {
            s3.multipartUploadFile(settings.SynnefoOptions.bucketName, targetDirectory + feature, feature, 5)
        }

        s3.multipartUploadFile(settings.SynnefoOptions.bucketName, targetDirectory + jarFileName, job.jarPath, 5)

        return targetDirectory
    }

    @Throws(ExecutionException::class, InterruptedException::class)
    private fun ensureProjectExists(settings: SynnefoProperties) {
        if (projectExists(settings.SynnefoOptions.projectName)) {
            return
        }

        createCodeBuildProject(settings)
    }

    @Throws(ExecutionException::class, InterruptedException::class)
    private fun projectExists(projectName: String?): Boolean {

        val batchGetProjectsRequest = BatchGetProjectsRequest
                .builder()
                .names(projectName)
                .build()

        val response = codeBuild.batchGetProjects(batchGetProjectsRequest)
        return response.projects().size == 1
    }


    @Throws(ExecutionException::class, InterruptedException::class)
    private fun createCodeBuildProject(settings: SynnefoProperties) {
        val sourceLocation = settings.SynnefoOptions.bucketName + "/" + settings.SynnefoOptions.bucketSourceFolder

        // TODO:
        // Make the project creation async

        val createRequest = CreateProjectRequest
                .builder()
                .name(settings.SynnefoOptions.projectName)
                .description("Autogenerated codebuild project to run tests")
                .artifacts { a ->
                    a
                            .type(ArtifactsType.S3)
                            .path(settings.SynnefoOptions.bucketOutputFolder)
                            .name(settings.SynnefoOptions.outputFileName)
                            .namespaceType("BUILD_ID")
                            .packaging("ZIP")
                            .location(settings.SynnefoOptions.bucketName)
                }
                .cache { b -> b.type(CacheType.NO_CACHE) }
                .environment { b ->
                    b
                            .type(EnvironmentType.LINUX_CONTAINER)
                            .image(settings.SynnefoOptions.image)
                            .computeType(ComputeType.BUILD_GENERAL1_SMALL)
                }
                .serviceRole(settings.SynnefoOptions.serviceRole)
                .source { s ->
                    s
                            .type(SourceType.S3)
                            .location(sourceLocation)
                }
                .logsConfig { l ->
                    l
                            .cloudWatchLogs { cwl ->
                                cwl
                                        .groupName("/aws/codebuild/" + settings.SynnefoOptions.projectName)
                                        .status(LogsConfigStatusType.ENABLED)
                            }
                }
                .build()

        codeBuild.createProject(createRequest)
    }

    @Throws(ExecutionException::class, InterruptedException::class)
    private fun startBuilds(job: Job, settings: SynnefoProperties, sourceLocation: String): List<ScheduledJob> {

        val ids = ArrayList<ScheduledJob>()
        for (info in job.runnerInfos) {
            val buildSpec = generateBuildspecForFeature(Paths.get(job.jarPath).fileName.toString(), info.cucumberFeatureLocation, info.runtimeOptions)
            val buildStartRequest = StartBuildRequest.builder()
                    .projectName(settings.SynnefoOptions.projectName)
                    .buildspecOverride(buildSpec)
                    .imageOverride(settings.SynnefoOptions.image)
                    .computeTypeOverride(settings.SynnefoOptions.computeType)
                    .artifactsOverride { a -> a
                                    .type(ArtifactsType.S3)
                                    .path(settings.SynnefoOptions.bucketOutputFolder)
                                    .name(settings.SynnefoOptions.outputFileName)
                                    .namespaceType("BUILD_ID")
                                    .packaging("ZIP")
                                    .location(settings.SynnefoOptions.bucketName)
                        }
                    .sourceLocationOverride(settings.SynnefoOptions.bucketName + "/" + sourceLocation)
                    .build()

            val startBuildResponse = codeBuild.startBuild(buildStartRequest)
            val buildId = startBuildResponse.build().id()
            val junitDescription = Description.createTestDescription(info.cucumberFeatureLocation, info.cucumberFeatureLocation)
            job.notifier.fireTestStarted(junitDescription)

            ids.add(ScheduledJob(job, buildId, info, junitDescription))

        }

        return ids
    }

    private fun S3Client.multipartUploadFile(bucket: String, key: String, filePath: String, partSizeMb: Int) {
        // TODO
        // Make the chunks upload parallel

        val createUploadRequest = CreateMultipartUploadRequest.builder()
                .key(key)
                .bucket(bucket)
                .build()

        val file = File(filePath)
        val contentLength = file.length()
        var partSize = partSizeMb * 1024 * 1024L

        val partETags = ArrayList<CompletedPart>()
        val response = this.createMultipartUpload(createUploadRequest)
        System.out.println(response.uploadId())

        val stream = FileInputStream(file)
        var filePosition: Long = 0
        var i = 1
        while (filePosition < contentLength) {
            partSize = Math.min(partSize, contentLength - filePosition)

            val uploadRequest = UploadPartRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(response.uploadId())
                    .partNumber(i)
                    .build()

            val data = RequestBody.fromInputStream(stream, partSize)
            val etag = s3.uploadPart(uploadRequest, data).eTag()

            partETags.add(CompletedPart.builder().partNumber(i).eTag(etag).build())

            filePosition += partSize
            i++
        }

        val completedMultipartUpload = CompletedMultipartUpload.builder().parts(partETags)
                .build()
        val completeMultipartUploadRequest =
                CompleteMultipartUploadRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .uploadId(response.uploadId())
                        .multipartUpload(completedMultipartUpload)
                        .build()
        s3.completeMultipartUpload(completeMultipartUploadRequest)
    }

    private fun <E> MutableList<E>.dequeueUpTo(limit: Int): MutableList<E> {
        val from = Math.max(0, this.size - limit)
        val to = Math.min(this.size, from + limit)
        return this.subList(from, to)
    }

    private fun StringBuilder.appendWithEscaping(s: String) {
        if (s.contains(' '))
            this.append("\"$s\" ")
        else
            this.append("$s ")
    }

    private fun unzip(fileZip: InputStream, dest: String) {
        val destDir = File(dest)
        destDir.mkdirs()
        val buffer = ByteArray(1024)
        val zis = ZipInputStream(fileZip)
        var zipEntry: ZipEntry? = zis.nextEntry
        while (zipEntry != null) {
            val newFile = newFile(destDir, zipEntry)
            val fos = FileOutputStream(newFile)
            var len: Int
            len = zis.read(buffer)
            while (len > 0) {
                fos.write(buffer, 0, len)
                len = zis.read(buffer)
            }
            fos.close()
            zipEntry = zis.nextEntry
        }
        zis.closeEntry()
        zis.close()
    }

    private fun newFile(destinationDir: File, zipEntry: ZipEntry): File {
        val destFile = File(destinationDir, zipEntry.name)

        val destDirPath = destinationDir.canonicalPath
        val destFilePath = destFile.canonicalPath

        if (!destFilePath.startsWith(destDirPath + File.separator)) {
            throw IOException("Entry is outside of the target dir: " + zipEntry.name)
        }

        return destFile
    }
}
