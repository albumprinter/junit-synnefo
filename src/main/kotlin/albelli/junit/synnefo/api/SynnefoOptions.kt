@file:Suppress("DEPRECATED_JAVA_ANNOTATION")

package albelli.junit.synnefo.api

import cucumber.api.CucumberOptions
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy

@Retention(RetentionPolicy.RUNTIME)
annotation class SynnefoOptions(

        /**
         * @return the number of parallel threads
         */
        val threads: Int = 5,
        /**
         * @return the run level (feature or scenario level)
         */
        val runLevel: SynnefoRunLevel = SynnefoRunLevel.FEATURE,
        /**
         * @return target directory of Synnefo-report (this defaults to 'target' directory)
         */
        val reportTargetDir: String = "build/Synnefo",
        /**
         * @return the Cucumber options
         */
        val cucumberOptions: CucumberOptions,

        /**
         * @return the name of the CodeBuild project
         */
        val projectName: String = "Synnefo-runners",

        /**
         * @return the arn to the AWS IAM service role to run the codebuild jobs
         */
        val serviceRole: String,

        /**
         * @return the name of the docker image to run the job on (/aws/codebuild or docker hub or ECR)
         */
        val image: String = "albelli/aws-codebuild-docker-images:java-openjdk-8-chromedriver",

        /**
         * @return the type of the CodeBuild instance to use
         */
        val computeType: String = "BUILD_GENERAL1_SMALL",

        /**
         * @return the name of the bucket to put the jar and artifacts in
         */
        val bucketName: String,
        /**
         * @return the folder within the bucket to put the jar in
         */
        val bucketSourceFolder: String = "Synnefo/source-dir/",
        /**
         * @return the folder within the bucket to put artifacts in
         */
        val bucketOutputFolder: String = "Synnefo/artifacts/",
        /**
         * @return the name of the zipped artifacts file
         */
        val outputFileName: String = "runResults.zip"

) {
    companion object {
        internal val OUTPUT_FILE = "Synnefo.outputFile"
        internal val THREADS = "Synnefo.threads"
        internal val RUN_LEVEL = "Synnefo.runLevel"
        internal val VM_OPTIONS = "Synnefo.vmoptions"
        internal val REPORT_TARGET_DIR = "Synnefo.reportTargetDir"
        internal val PROJECT_NAME = "Synnefo.projectName"
        internal val BUCKET_OUTPUT = "Synnefo.bucketOutputFolder"
        internal val BUCKET_SOURCE = "Synnefo.bucketSourceFolder"
        internal val BUCKET_NAME = "Synnefo.bucketName"
        internal val COMPUTE_TYPE = "Synnefo.computeType"
        internal val IMAGE = "Synnefo.image"
        internal val SERVICE_ROLE = "Synnefo.serviceRole"
    }
}
