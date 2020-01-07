package albelli.junit.synnefo.runtime

import albelli.junit.synnefo.api.SynnefoOptions
import albelli.junit.synnefo.api.SynnefoRunLevel
import albelli.junit.synnefo.runtime.exceptions.SynnefoException
import cucumber.api.CucumberOptions
import java.net.URI

internal class SynnefoProperties(
        val threads: Int,
        val runLevel: SynnefoRunLevel,
        val reportTargetDir: String,
        val cucumberOptions: CucumberOptions,
        val projectName: String,
        val serviceRole: String,
        val image: String,
        val computeType: String,
        val bucketName: String,
        val bucketSourceFolder: String,
        val bucketOutputFolder: String,
        val outputFileName: String,
        val cucumberForcedTags: String,
        val shuffleBacklogBeforeExecution: Boolean,
        val maxRetries: Int,
        val retriesPerTest: Int,
        val codeBuildRunTimeVersions: Array<String>,
        val classPath: String,
        val featurePaths: List<URI>)
{
    constructor(opt: SynnefoOptions): this(
            getAnyVar("threads", opt.threads),
            opt.runLevel,
            getAnyVar("reportTargetDir", opt.reportTargetDir),
            opt.cucumberOptions,
            getAnyVar("projectName", opt.projectName),
            getAnyVarOrFail("serviceRole", opt.serviceRole),
            getAnyVar("image", opt.image),
            getAnyVar("computeType", opt.computeType),
            getAnyVarOrFail("bucketName", opt.bucketName),
            getAnyVar("bucketSourceFolder", opt.bucketSourceFolder),
            getAnyVar("bucketOutputFolder", opt.bucketOutputFolder),
            getAnyVar("outputFileName", opt.outputFileName),
            opt.cucumberForcedTags,
            getAnyVar("shuffleBacklogBeforeExecution", opt.shuffleBacklogBeforeExecution),
            getAnyVar("maxRetries", opt.maxRetries),
            getAnyVar("retriesPerTest", opt.retriesPerTest),
            opt.codeBuildRunTimeVersions,
            "",
            listOf()
    )

    constructor(opt: SynnefoProperties, classPath: String, featurePaths: List<URI>): this(
            opt.threads,
            opt.runLevel,
            opt.reportTargetDir,
            opt.cucumberOptions,
            opt.projectName,
            opt.serviceRole,
            opt.image,
            opt.computeType,
            opt.bucketName,
            opt.bucketSourceFolder,
            opt.bucketOutputFolder,
            opt.outputFileName,
            opt.cucumberForcedTags,
            opt.shuffleBacklogBeforeExecution,
            opt.maxRetries,
            opt.retriesPerTest,
            opt.codeBuildRunTimeVersions,
            classPath,
            featurePaths
    )

    companion object {

        private fun getAnyVar(varName: String) : String?
        {
            val propertyName = "Synnefo.$varName"
            val environmentName = "Synnefo_$varName"

            val prop = System.getProperty(propertyName)
            if(prop == null || prop.isNullOrWhiteSpace()) {
                val envVar = System.getenv(environmentName)
                return if(envVar == null || envVar.isNullOrWhiteSpace()) {
                    null
                } else {
                    envVar
                }
            } else {
                return prop
            }
        }

        private fun getAnyVar(varName: String, default: Int) : Int
        {
            val anyVar = getAnyVar(varName)
            return anyVar?.toInt() ?: default
        }

        private fun getAnyVar(varName: String, default: String) : String
        {
            val anyVar = getAnyVar(varName)
            return anyVar ?: default
        }

        private fun getAnyVar(varName: String, default: Boolean) : Boolean
        {
            val anyVar = getAnyVar(varName)
            return anyVar?.toBoolean() ?: default
        }

        @Throws(SynnefoException::class)
        private fun getAnyVarOrFail(varName: String, default: String) : String
        {
            val anyVar = getAnyVar(varName, default)

            if (anyVar.isNullOrWhiteSpace())
                throw SynnefoException("Variable $varName is not set, while it should be")
            else
                return anyVar
        }
    }
}
