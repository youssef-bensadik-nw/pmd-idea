import com.github.ybroeker.pmdidea.build.*
import org.jetbrains.changelog.closure
import org.jetbrains.changelog.markdownToHTML

plugins {
    id("java")
    jacoco
    id("idea")
    // gradle-intellij-plugin - read more: https://github.com/JetBrains/gradle-intellij-plugin
    id("org.jetbrains.intellij") version "1.17.4"
    // gradle-changelog-plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
    id("org.jetbrains.changelog") version "2.2.1"
}

// Import variables from gradle.properties file
val pluginGroup: String by project
// `pluginName_` variable ends with `_` because of the collision with Kotlin magic getter in the `intellij` closure.
// Read more about the issue: https://github.com/JetBrains/intellij-platform-plugin-template/issues/29
val pluginName_: String by project
val pluginVersion: String by project
val pluginSinceBuild: String by project
val pluginUntilBuild: String by project
val pluginVerifierIdeVersions: String by project

val platformType: String by project
val platformVersion: String by project
val platformPlugins: String by project
val platformDownloadSources: String by project

group = pluginGroup
version = pluginVersion

sourceSets {
    val main by getting
    val pmdwrapper by creating {
        java {
            srcDir("src/pmdwrapper/java")
            compileClasspath += main.output
            runtimeClasspath += main.output
        }
    }
}

configurations["pmdwrapperCompile"].extendsFrom(configurations["compile"])
configurations["pmdwrapperCompileOnly"].extendsFrom(configurations["compileOnly"])
configurations["pmdwrapperCompileClasspath"].extendsFrom(configurations["compileClasspath"])
configurations["pmdwrapperRuntime"].extendsFrom(configurations["runtime"])


// Configure project's dependencies
repositories {
    mavenCentral()
}

// Configure gradle-intellij-plugin plugin.
// Read more: https://github.com/JetBrains/gradle-intellij-plugin
intellij {
    pluginName = pluginName_
    version = platformVersion
    type = platformType
    downloadSources = platformDownloadSources.toBoolean()
    updateSinceUntilBuild = true

    // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file.
    setPlugins(*platformPlugins.split(',').map(String::trim).filter(String::isNotEmpty).toTypedArray())
}


tasks {
    // Set the compatibility versions to 1.8
    withType<JavaCompile> {
        sourceCompatibility = "11"
        targetCompatibility = "11"
        options.setDeprecation(true)
        options.compilerArgs.add("-Xlint:unchecked")
    }

    patchPluginXml {
        version(pluginVersion)
        sinceBuild(pluginSinceBuild)
        untilBuild(pluginUntilBuild)

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        pluginDescription(
            closure {
                File("./README.md").readText().lines().run {
                    val start = "<!-- Plugin description -->"
                    val end = "<!-- Plugin description end -->"

                    if (!containsAll(listOf(start, end))) {
                        throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                    }
                    subList(indexOf(start) + 1, indexOf(end))
                }.joinToString("\n").run { markdownToHTML(this) }
            }
        )

        // Get the latest available change notes from the changelog file
        changeNotes(
            closure {
                changelog.getLatest().toHTML()
            }
        )
    }

    runPluginVerifier {
        ideVersions(pluginVerifierIdeVersions)
    }

    publishPlugin {
        dependsOn("patchChangelog")
        token(System.getenv("PUBLISH_TOKEN"))
        // pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://jetbrains.org/intellij/sdk/docs/tutorials/build_system/deployment.html#specifying-a-release-channel
        channels(pluginVersion.split('-').getOrElse(1) { "default" }.split('.').first())
    }
}


tasks.register<ResolvePmdArtifactsTask>(ResolvePmdArtifactsTask.NAME)
tasks.register<CopyPMDToSandboxTask>(CopyPMDToSandboxTask.NAME)
tasks.register<CopyPMDToSandboxTask>(CopyPMDToSandboxTask.TEST_NAME) { setTest() }
tasks.register<CopyClassesToSandboxTask>(CopyClassesToSandboxTask.NAME)
tasks.register<CopyClassesToSandboxTask>(CopyClassesToSandboxTask.TEST_NAME) { setTest() }

tasks["compileTestJava"].dependsOn("compilePmdwrapperJava")
tasks["testClasses"].dependsOn("pmdwrapperClasses")
tasks["jar"].dependsOn("pmdwrapperClasses")

tasks["buildPlugin"].dependsOn(CopyPMDToSandboxTask.NAME)
tasks["buildPlugin"].dependsOn(CopyClassesToSandboxTask.NAME)
tasks["runIde"].dependsOn(CopyPMDToSandboxTask.NAME)
tasks["runIde"].dependsOn(CopyClassesToSandboxTask.NAME)

tasks[CopyPMDToSandboxTask.NAME].dependsOn("prepareSandbox")
tasks[CopyClassesToSandboxTask.NAME].dependsOn("prepareSandbox")

tasks[CopyPMDToSandboxTask.TEST_NAME].dependsOn("prepareTestingSandbox")
tasks[CopyClassesToSandboxTask.TEST_NAME].dependsOn("prepareTestingSandbox")

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.7.0")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.7.0")
    testImplementation("org.assertj:assertj-core:3.20.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testCompileOnly("junit:junit:4.13")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")

    compileOnly("net.sourceforge.pmd:pmd-core:6.0.1")
    val pmdwrapperCompile by configurations
    pmdwrapperCompile("net.sourceforge.pmd:pmd-core:6.0.1");
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
tasks.test {
    finalizedBy(tasks.jacocoTestReport) // report is always generated after tests run
}
tasks.jacocoTestReport {
    dependsOn(tasks.test) // tests are required to run before generating the report
    reports {
        xml.isEnabled = true
    }
}
