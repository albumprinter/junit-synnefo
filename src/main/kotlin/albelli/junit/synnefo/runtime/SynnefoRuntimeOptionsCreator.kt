package albelli.junit.synnefo.runtime

import cucumber.api.CucumberOptions

internal class SynnefoRuntimeOptionsCreator(synnefoProperties: SynnefoProperties) {
    private val cucumberOptions: CucumberOptions = synnefoProperties.cucumberOptions
    private val cucumberForcedTags: String = synnefoProperties.cucumberForcedTags
    private val runtimeOptions = ArrayList<String>()

    init {
        runtimeOptions.addAll(createRuntimeOptions(cucumberOptions))
    }

    fun getRuntimeOptions(): ArrayList<String> {
        return runtimeOptions
    }

    private fun createRuntimeOptions(cucumberOptions: CucumberOptions): List<String> {
        val runtimeOptions = ArrayList<String>()

        val tags = envCucumberOptionOverride("tags", cucumberOptions.tags.toList()).map {
            if(cucumberForcedTags.isNullOrWhiteSpace()) it
            else "($it) and ($cucumberForcedTags)"
        }
        runtimeOptions.addAll(optionParser("--tags", tags))

        runtimeOptions.addAll(optionParser("--glue", envCucumberOptionOverride("glue", cucumberOptions.glue.toList())))
        runtimeOptions.addAll(optionParser("--plugin", envCucumberOptionOverride("plugin", cucumberOptions.plugin.toList())))
        runtimeOptions.addAll(optionParser("--name", envCucumberOptionOverride("name", cucumberOptions.name.toList())))
        runtimeOptions.addAll(optionParser("--junit", envCucumberOptionOverride("junit", cucumberOptions.junit.toList())))
        runtimeOptions.addAll(listOf("--snippets", cucumberOptions.snippets.toString()))
        runtimeOptions.add(if (cucumberOptions.dryRun) "--dry-run" else "--no-dry-run")
        runtimeOptions.add(if (cucumberOptions.strict) "--strict" else "--no-strict")
        runtimeOptions.add(if (cucumberOptions.monochrome) "--monochrome" else "--no-monochrome")

        return runtimeOptions
    }

    private fun envCucumberOptionOverride(systemPropertyName: String, cucumberOptions: List<String>): List<String> {
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

    private fun optionParser(name: String, options: Iterable<String>): List<String> {
        val runOptions = ArrayList<String>()

        for (opt in options)
        {
            runOptions.add(name)
            runOptions.add(opt)
        }

        return runOptions
    }
}
