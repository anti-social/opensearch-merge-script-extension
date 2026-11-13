import org.opensearch.gradle.testclusters.OpenSearchCluster
import org.opensearch.gradle.testclusters.TestDistribution

plugins {
    java
    idea
    id("opensearch.opensearchplugin")
    id("opensearch.java-agent")
    id("com.netflix.nebula.ospackage")
}

configure<JavaPluginExtension> {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

configureOpensearchPlugin(
    name = "merge-script-extension",
    description = "Adds merge_script search extension to execute scripts on a merge node.",
    classname = "dev.evo.opensearch.plugin.MergeScriptPlugin",
    extendedPlugins = listOf("lang-painless"),
    testDistribution = TestDistribution.ARCHIVE,
    numberOfTestClusterNodes = 1,
    // Compatibility between MINOR update is not working at the moment:
    // https://github.com/opensearch-project/OpenSearch/issues/18787
    opensearchCompatibility = OpensearchCompatibility.REVISION,
)

// Testclusters run CLI tools (bin/opensearch-plugin) with a cleared environment.
// On NixOS bash has no compiled-in default PATH, so the tools fail with
// "dirname: command not found". Opt in to passing the current PATH through
// (set by shell.nix): https://github.com/opensearch-project/OpenSearch/pull/13689
if (providers.gradleProperty("testClustersPassPath").orNull.toBoolean()) {
    configure<NamedDomainObjectContainer<OpenSearchCluster>> {
        configureEach {
            System.getenv("PATH")?.let { environment("PATH", it) }
        }
    }
}

// extraConfiguration()

version = Versions.project

dependencies {
    compileOnly("org.opensearch.plugin:opensearch-scripting-painless-spi:${Versions.opensearch}")
}
