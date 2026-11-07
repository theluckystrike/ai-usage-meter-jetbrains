import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.0"
}

group = "zovo.aiusage"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

kotlin { jvmToolchain(21) }

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
tasks.withType<JavaCompile> { options.release.set(21) }

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // IntelliJ 2025.2 ships the Kotlin 2.1+ standard library: do not call newer stdlib APIs.
        apiVersion.set(KotlinVersion.KOTLIN_2_1)
        languageVersion.set(KotlinVersion.KOTLIN_2_1)
        freeCompilerArgs.add("-Xjdk-release=21")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "zovo.ai-usage-meter"
        name = "AI Usage Meter: Claude Code, Codex & Gemini CLI"
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
        vendor {
            name = "Zovo"
            url = "https://github.com/theluckystrike/ai-usage-meter-jetbrains"
        }
    }
    pluginVerification {
        ides {
            // The 2025.2.6.4 SDK is already in the Gradle cache. Add newer IDEs here when there is disk space.
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.6.4")
            providers.gradleProperty("verifyAlso").orNull?.split(',')?.filter { it.isNotBlank() }?.forEach {
                create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, it.trim())
            }
        }
    }
    publishing {
        // export PUBLISH_TOKEN=<JetBrains Marketplace permanent token>, then ./gradlew publishPlugin
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    buildSearchableOptions = false
}

tasks {
    // ./gradlew runIde -PdemoHome=<folder>: reads the synthetic fixture folders in <folder> instead of your own.
    runIde {
        val demo = providers.gradleProperty("demoHome").orNull
        if (demo != null) {
            environment("CLAUDE_CONFIG_DIR", "$demo/.claude,$demo/.claude-work")
            environment("CODEX_HOME", "$demo/.codex")
            jvmArgs("-Dzovo.aiusage.home=$demo")
            if (File("$demo/demo-project").isDirectory) args(listOf("$demo/demo-project"))
        }
        // skip first-run dialogs (data sharing consent, settings import, trust) so the sandbox opens unattended
        jvmArgs(
            "-Didea.trust.all.projects=true", "-Dide.show.tips.on.startup.default.value=false",
            "-Djb.consents.confirmation.enabled=false", "-Djb.privacy.policy.text=<!--999.999-->",
            "-Didea.initially.ask.config=never", "-Didea.suppress.statistics.report=true", "-Xmx2g",
        )
    }
    test {
        systemProperty("aiusage.fixtures", layout.projectDirectory.dir("src/test/resources/fixtures").asFile.absolutePath)
        systemProperty("aiusage.expected", layout.projectDirectory.file("src/test/resources/parity/expected.json").asFile.absolutePath)
        // big and mutated test files go here, not in /tmp; each test deletes its own folder
        systemProperty("aiusage.testRoot", layout.buildDirectory.dir("test-tmp").get().asFile.absolutePath)
        systemProperty("user.timezone", "UTC")
    }
}
