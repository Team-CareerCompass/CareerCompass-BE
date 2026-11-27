plugins {
    java
    `jvm-test-suite`
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "8.10.2"
}

group = "com.careercompass"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // API 문서 (dev/local 프로파일에서만 노출 — issue #8)
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.17")

    // 운영 프로파일 구조화(JSON) 로그 (#49)
    implementation("net.logstash.logback:logstash-logback-encoder:9.0")

    // JWT (M1 인증)
    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")

    // 과거 지원서 텍스트 추출 — PDF·DOCX (M2 #19)
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation("org.apache.poi:poi-ooxml:5.5.1")

    // 게시판 HTML 파싱 (M3 크롤러 — 가져오기는 JDK HttpClient, 파싱만 Jsoup)
    implementation("org.jsoup:jsoup:1.23.2")

    // FCM 푸시 (M8 — 자격증명 미설정 시 로깅 구현이 대신 동작)
    implementation("com.google.firebase:firebase-admin:9.10.0") {
        // GCS 가 끌고 오는 XML 컨버터가 켜지면 응답이 XML 로 협상될 수 있다 — JSON 단일 계약 보호
        exclude(group = "com.fasterxml.jackson.dataformat", module = "jackson-dataformat-xml")
    }
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation("org.springframework.security:spring-security-test")
            }
        }

        // 실제 PostgreSQL(Testcontainers)을 쓰는 통합 테스트 레인 — issue #9
        val integrationTest by registering(JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation("org.springframework.boot:spring-boot-testcontainers")
                implementation("org.springframework:spring-jdbc")
                implementation("org.testcontainers:postgresql")
                implementation("org.testcontainers:junit-jupiter")
                implementation("org.springframework.security:spring-security-test")
                runtimeOnly("org.postgresql:postgresql")
            }
            targets {
                all {
                    testTask.configure {
                        shouldRunAfter(test)
                    }
                }
            }
        }
    }
}

// 실행 jar 만 남긴다 (Dockerfile 의 COPY *.jar 가 유일해야 함)
tasks.jar {
    enabled = false
}

// jar 를 껐으므로 integrationTest 는 메인 클래스·의존성을 소스셋으로 직접 물려받는다
sourceSets {
    named("integrationTest") {
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
}

configurations {
    named("integrationTestImplementation") { extendsFrom(configurations.implementation.get()) }
    named("integrationTestRuntimeOnly") { extendsFrom(configurations.runtimeOnly.get()) }
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat()
        formatAnnotations()
    }
}
