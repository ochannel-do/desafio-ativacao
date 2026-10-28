package com.exemplo.ativacao.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.*;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.*;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
  private final JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.exemplo.ativacao");

  @Test
  void corePuro() {
    classes()
        .that()
        .resideInAPackage("..core..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage("java..", "com.exemplo.ativacao.core..")
        .check(classes);
  }

  @Test
  void dominioIndependente() {
    noClasses()
        .that()
        .resideInAPackage("..core.domain..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..core.application..")
        .check(classes);
  }

  @Test
  void webNaoAcessaPersistencia() {
    noClasses()
        .that()
        .resideInAPackage("..infrastructure.web..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..infrastructure.persistence..", "jakarta.persistence..")
        .check(classes);
  }

  @Test
  void semCiclos() {
    slices()
        .matching("com.exemplo.ativacao.infrastructure.(*)..")
        .should()
        .beFreeOfCycles()
        .check(classes);
  }
}
