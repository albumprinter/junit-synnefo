package albelli.junit.synnefo.runtime

import cucumber.api.CucumberOptions
import java.util.*
import java.util.Arrays.asList
import kotlin.collections.ArrayList

class SynnefoRuntimeOptionsCreator(SynnefoProperties: SynnefoProperties) {
    private val cucumberOptions: CucumberOptions = SynnefoProperties.synnefoOptions.cucumberOptions

    private val runtimeOptions = ArrayList<String>()

    init {
        createRuntimeOptions(cucumberOptions).forEach { _, value -> runtimeOptions.addAll(value) }
    }


    fun getRuntimeOptions(): ArrayList<String> {
        return runtimeOptions

    }

    fun mapRuntimeOptions(): Map<String, List<String>> {
        return createRuntimeOptions(cucumberOptions)
    }

    private fun createRuntimeOptions(cucumberOptions: CucumberOptions): Map<String, List<String>> {
        val runtimeOptions = HashMap<String, List<String>>()

        runtimeOptions["--glue"] = optionParser("--glue", envCucumberOptionParser("glue", cucumberOptions.glue.toList()))
        runtimeOptions["--tags"] = optionParser("--tags", envCucumberOptionParser("tags", cucumberOptions.tags.toList()))
        runtimeOptions["--plugin"] = optionParser("--plugin", envCucumberOptionParser("plugin", cucumberOptions.plugin.toList()))
        runtimeOptions["--name"] = optionParser("--name", envCucumberOptionParser("name", cucumberOptions.name.toList()))
        runtimeOptions["--junit"] = optionParser("--junit", envCucumberOptionParser("junit", cucumberOptions.junit.toList()))
        runtimeOptions["--snippets"] = listOf("--snippets", cucumberOptions.snippets.toString())
        runtimeOptions["--dryRun"] = listOf(if (cucumberOptions.dryRun) "--dry-run" else "--no-dry-run")
        runtimeOptions["--strict"] = listOf(if (cucumberOptions.strict) "--strict" else "--no-strict")
        runtimeOptions["--monochrome"] = listOf(if (cucumberOptions.monochrome) "--monochrome" else "--no-monochrome")

        runtimeOptions.values.removeIf { it.isEmpty() }

        return runtimeOptions
    }


    private fun envCucumberOptionParser(systemPropertyName: String, cucumberOptions: List<String>): List<String> {
        val cucumberOption = System.getProperty("cucumber.$systemPropertyName")

        if (cucumberOption != null && cucumberOption.trim { it <= ' ' }.isNotEmpty()) {
            val options = ArrayList<String>()
            cucumberOption.split(",".toRegex())
                    .dropLastWhile { it.isEmpty() }
                    .forEach { t -> options.add(t.trim { it <= ' ' }) }
            return options
        }
        return cucumberOptions
    }

    private fun optionParser(name: String, options: List<String>): List<String> {
        val runOptions = ArrayList<String>()

        if (options.isEmpty())
            return runOptions

        asList(*asList(options).toString().split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray())
                .forEach { value ->
                    runOptions.add(name)
                    runOptions.add(value.trim { it <= ' ' }.replace("[", "").replace("]", ""))
                }

        return runOptions
    }
}
