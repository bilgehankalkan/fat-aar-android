plugins {
    id("groovy")
    `java-gradle-plugin`
    id("maven-publish")
}

group = "com.github.bilgehankalkan"
version = "1.5.0"

buildscript {
    repositories {
        mavenCentral()
    }
}

repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
}

gradlePlugin {
    plugins {
        create("fatAarAndroidPlugin") {
            id = "com.github.bilgehankalkan.fat-aar-android"
            displayName = "Fat Aar Plugin to merge all dependencies into single aar"
            implementationClass = "com.kezong.fataar.FatAarPlugin"
            description = "This plugin is a fork of: https://github.com/aasitnikov/fat-aar-android"
        }
    }
}

dependencies {
    implementation(gradleApi())
    implementation(localGroovy())
    implementation("org.javassist:javassist:3.27.0-GA")
    implementation("com.android.tools.build:gradle:8.12.0")
    implementation("com.android.tools:common:31.12.0")
    implementation("org.ow2.asm:asm-commons:9.7.1")
}
