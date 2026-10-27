package io.ramaqueue;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "io.ramaqueue", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule dominioNoDependeDeNada = noClasses()
            .that().resideInAPackage("io.ramaqueue.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "io.ramaqueue.application..",
                    "io.ramaqueue.infrastructure..",
                    "org.springframework..",
                    "jakarta..",
                    "javax..",
                    "com.fasterxml..",
                    "org.postgresql..",
                    "io.lettuce..",
                    "java.sql..",
                    "java.net.http..");

    @ArchTest
    static final ArchRule aplicacionNoDependeDeInfraestructura = noClasses()
            .that().resideInAPackage("io.ramaqueue.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "io.ramaqueue.infrastructure..",
                    "org.springframework.amqp..",
                    "org.springframework.jdbc..",
                    "org.springframework.data.redis..",
                    "org.springframework.web..",
                    "com.rabbitmq..",
                    "org.postgresql..",
                    "io.lettuce..",
                    "java.sql..",
                    "java.net.http..");

    @ArchTest
    static final ArchRule sinCiclosEntreCapas = slices()
            .matching("io.ramaqueue.(*)..")
            .should().beFreeOfCycles();
}
