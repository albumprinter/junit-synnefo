@file:Suppress("DEPRECATED_JAVA_ANNOTATION")

package albelli.junit.synnefo.api

import cucumber.api.CucumberOptions
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy

@java.lang.annotation.Repeatable(SynnefoOptionsGroup::class)
@Retention(RetentionPolicy.RUNTIME)
@Target(AnnotationTarget.CLASS)
@Repeatable
annotation class SynnefoOptions(

        /**
         * @return the number of parallel threads
         */
        val threads: Int = 2,
        /**
         * @return the run level (feature or scenario level)
         */
        val runLevel: SynnefoRunLevel = SynnefoRunLevel.FEATURE,
        /**
         * @return target directory of Synnefo-report (this defaults to 'target' directory)
         */
        val reportTargetDir: String = "build/Synnefo",

        /**
         * @return special tags that would be added as "AND" to any other cucumber tags
         */
        val cucumberForcedTags: String = "",

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
        val serviceRole: String = "",

        /**
         * @return the name of the docker image to run the job on (/aws/codebuild or docker hub or ECR)
         */
        val image: String = "aws/codebuild/standard:2.0",

        /**
         * @return the type of the CodeBuild instance to use
         */
        val computeType: String = "BUILD_GENERAL1_SMALL",

        /**
         * @return the name of the bucket to put the jar and artifacts in
         */
        val bucketName: String = "",
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
        val outputFileName: String = "runResults.zip",
        /**
         * @return value indicating whether we should shuffle the backlog of tasks before scheduling them in CodeBuild
         */
        val shuffleBacklogBeforeExecution: Boolean = false,
        /**
         * @return value indicating how many times the whole run can retry
         */
        val maxRetries: Int = 0,
        /**
         * @return value indicating how many times can an individual test be retried
         */
        val retriesPerTest: Int = 3,
        /**
         * @return a set of CodeBuild runtimes to initialize.
         * See the docu for details: https://docs.aws.amazon.com/codebuild/latest/userguide/sample-runtime-versions.html
         */
        val codeBuildRunTimeVersions: Array<String>
        )
@Retention(RetentionPolicy.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class SynnefoOptionsGroup(vararg val value: SynnefoOptions)
