package dev.tanager.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 실제 제품 코드(테스트 클래스 제외)에 아키텍처 규칙을 건다. */
class ArchitectureTest {
    private static final String BASE = "dev.tanager";
    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importPackages(BASE);
    }

    @Test
    void domainIsPure() {
        ArchitectureRules.domainIsPure(BASE).allowEmptyShould(true).check(production);
    }

    @Test
    void layersRespectDirection() {
        ArchitectureRules.layersRespectDirection(BASE).check(production);
    }

    @Test
    void adaptersAreIndependent() {
        ArchitectureRules.adaptersAreIndependent(BASE).allowEmptyShould(true).check(production);
    }

    @Test
    void platformsAreIndependent() {
        ArchitectureRules.platformsAreIndependent(BASE).allowEmptyShould(true).check(production);
    }
}
