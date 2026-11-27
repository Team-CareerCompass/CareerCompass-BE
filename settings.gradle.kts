plugins {
    // 로컬에 JDK 21 이 없어도 툴체인이 자동으로 내려받도록 한다
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "careercompass-be"
