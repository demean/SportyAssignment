package com.sporty.jackpot;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.sporty.jackpot.domain.policy.ContributionPolicy;
import com.sporty.jackpot.domain.policy.RewardPolicy;
import com.sporty.jackpot.persistence.repository.BetRepository;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.GeneralCodingRules;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Layer rules of DESIGN.md section 3, checked against the main classes only.
 */
@DisplayName("Architecture (DESIGN.md section 3)")
class ArchitectureTest {

    private static final String BASE = "com.sporty.jackpot";
    private static final String DOMAIN = BASE + ".domain..";
    private static final String EXCEPTION = BASE + ".exception..";
    private static final String SERVICE = BASE + ".service..";
    private static final String MESSAGING = BASE + ".messaging..";
    private static final String PERSISTENCE = BASE + ".persistence..";
    private static final String ENTITY = BASE + ".persistence.entity..";
    private static final String API = BASE + ".api..";
    private static final String API_DTO = BASE + ".api.dto..";
    private static final String CONFIG = BASE + ".config..";
    private static final String REPOSITORY_PACKAGE = BASE + ".persistence.repository";

    private static final String SPRING_TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";
    private static final String JAKARTA_TRANSACTIONAL = "jakarta.transaction.Transactional";

    private static final JavaClasses MAIN_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(BASE);

    private static void check(ArchRule rule) {
        rule.check(MAIN_CLASSES);
    }

    @Test
    @DisplayName("only main classes are imported (tests and support code are excluded)")
    void importsMainClassesOnly() {
        assertThat(MAIN_CLASSES.contain(JackpotServiceApplication.class)).isTrue();
        assertThat(MAIN_CLASSES.containPackage(BASE + ".support")).isFalse();
        assertThat(MAIN_CLASSES).noneMatch(javaClass -> javaClass.getSimpleName().endsWith("Test"));
    }

    @Nested
    @DisplayName("rule 1: domain and exception are framework-free")
    class FrameworkFreeCore {

        @Test
        void domainAndExceptionDependOnNoFramework() {
            check(noClasses().that().resideInAnyPackage(DOMAIN, EXCEPTION)
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..", "jakarta.persistence..", "org.hibernate..", "tools.jackson..",
                            "com.fasterxml..")
                    .because("the domain model and business exceptions must stay framework-free"));
        }
    }

    @Nested
    @DisplayName("rule 2: api and messaging never see persistence")
    class NoPersistenceInAdapters {

        @Test
        void apiAndMessagingDoNotDependOnPersistence() {
            check(noClasses().that().resideInAnyPackage(API, MESSAGING)
                    .should().dependOnClassesThat().resideInAPackage(PERSISTENCE)
                    .because("controllers work with DTOs and the listener with the domain; persistence is behind "
                            + "the services"));
        }
    }

    @Nested
    @DisplayName("rule 3: controllers (@RestController, or any other @Controller) take and return DTOs only")
    class ControllerSignatures {

        @Test
        void publicControllerMethodsUseOnlyDtoTypes() {
            check(methods().that().areDeclaredInClassesThat().areMetaAnnotatedWith(Controller.class)
                    .and().arePublic()
                    .should(useOnlyDtoTypesInTheirSignature())
                    .because("controllers must not leak domain objects or entities over HTTP"));
        }

        @Test
        @DisplayName("the signature check itself flags domain types and raw containers")
        void signatureCheckRejectsNonDtoTypes() {
            JavaClasses fixtures = new ClassFileImporter().importClasses(ControllerSignatureFixture.class);

            EvaluationResult result = methods().that().areDeclaredIn(ControllerSignatureFixture.class)
                    .and().arePublic()
                    .should(useOnlyDtoTypesInTheirSignature())
                    .evaluate(fixtures);

            assertThat(result.getFailureReport().getDetails())
                    .anyMatch(detail -> detail.contains(".returnsDomain(")
                            && detail.contains("com.sporty.jackpot.domain.model.Bet"))
                    .anyMatch(detail -> detail.contains(".takesDomain(")
                            && detail.contains("com.sporty.jackpot.domain.model.Jackpot"))
                    .anyMatch(detail -> detail.contains(".rawList("))
                    .anyMatch(detail -> detail.contains(".listOfString("))
                    .anyMatch(detail -> detail.contains(".responseOfListOfDomain("))
                    .noneMatch(detail -> detail.contains(".dtoAndPrimitives("))
                    .noneMatch(detail -> detail.contains(".responseOfListOfDto("));
        }
    }

    @Nested
    @DisplayName("rule 4: entities stay inside persistence and service")
    class EntitiesStayInside {

        @Test
        void entitiesAreOnlyUsedByPersistenceAndService() {
            check(classes().that().resideInAPackage(ENTITY)
                    .should().onlyHaveDependentClassesThat().resideInAnyPackage(PERSISTENCE, SERVICE)
                    .because("entities never leave the service/persistence layers"));
        }
    }

    @Nested
    @DisplayName("rule 5: @Transactional only in service")
    class TransactionalOnlyInServices {

        @Test
        void noTransactionalClassesOutsideServices() {
            check(noClasses().that().resideOutsideOfPackage(SERVICE)
                    .should().beMetaAnnotatedWith(SPRING_TRANSACTIONAL)
                    .orShould().beMetaAnnotatedWith(JAKARTA_TRANSACTIONAL));
        }

        @Test
        void noTransactionalMethodsOutsideServices() {
            check(noMethods().that().areDeclaredInClassesThat().resideOutsideOfPackage(SERVICE)
                    .should().beMetaAnnotatedWith(SPRING_TRANSACTIONAL)
                    .orShould().beMetaAnnotatedWith(JAKARTA_TRANSACTIONAL));
        }

        @Test
        @DisplayName("Kafka listeners and controllers are not transactional")
        void listenersAndControllersAreNotTransactional() {
            check(noMethods().that().areAnnotatedWith(KafkaListener.class)
                    .should().beMetaAnnotatedWith(SPRING_TRANSACTIONAL)
                    .orShould().beMetaAnnotatedWith(JAKARTA_TRANSACTIONAL)
                    .because("each record must be applied in exactly one service transaction"));
            check(noClasses().that().areMetaAnnotatedWith(Controller.class)
                    .should().beMetaAnnotatedWith(SPRING_TRANSACTIONAL)
                    .orShould().beMetaAnnotatedWith(JAKARTA_TRANSACTIONAL));
            check(noMethods().that().areDeclaredInClassesThat().areMetaAnnotatedWith(Controller.class)
                    .should().beMetaAnnotatedWith(SPRING_TRANSACTIONAL)
                    .orShould().beMetaAnnotatedWith(JAKARTA_TRANSACTIONAL));
        }

        @Test
        @DisplayName("every public method of a service owning a repository is transactional, writes READ_COMMITTED")
        void repositoryOwningServicesAreTransactional() {
            check(methods().that().arePublic()
                    .and().areDeclaredInClassesThat().resideInAPackage(SERVICE)
                    .and().areDeclaredInClassesThat(ownARepository())
                    .should(runInATransaction())
                    .because("every DB-touching service method runs in exactly one transaction (DESIGN 1.2); a "
                            + "method without one would commit every repository call on its own"));
        }

        @Test
        @DisplayName("the transaction check itself flags missing transactions and non-READ_COMMITTED writes")
        void transactionCheckRejectsViolations() {
            JavaClasses fixtures = new ClassFileImporter().importClasses(TransactionFixture.class,
                    TransactionalFixture.class, RepositoryFreeFixture.class);

            EvaluationResult result = methods().that().arePublic()
                    .and().areDeclaredInClassesThat(ownARepository())
                    .should(runInATransaction())
                    .evaluate(fixtures);

            assertThat(result.getFailureReport().getDetails())
                    .anyMatch(detail -> detail.contains(".notTransactional(") && detail.contains("no transaction"))
                    .anyMatch(detail -> detail.contains(".defaultIsolationWrite(") && detail.contains("DEFAULT"))
                    .noneMatch(detail -> detail.contains(".readCommittedWrite("))
                    .noneMatch(detail -> detail.contains(".readOnlyQuery("))
                    .noneMatch(detail -> detail.contains(".readOnlyViaClass("))
                    .noneMatch(detail -> detail.contains("RepositoryFreeFixture"));
        }
    }

    @Nested
    @DisplayName("rule 6: constructor injection only")
    class NoFieldInjection {

        @Test
        void noAutowiredFields() {
            check(noFields().should().beAnnotatedWith(Autowired.class)
                    .because("dependencies are injected through constructors"));
        }

        @Test
        void noFieldInjectionAtAll() {
            check(GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION);
        }
    }

    @Nested
    @DisplayName("rule 7: layers use only the layers below them (README section 5)")
    class Layers {

        @Test
        @DisplayName("api and messaging are adapters around service -> persistence -> domain -> exception; config "
                + "wires everything")
        void layersUseOnlyTheLayersBelow() {
            check(layeredArchitecture().consideringOnlyDependenciesInLayers()
                    .layer("Api").definedBy(API)
                    .layer("Messaging").definedBy(MESSAGING)
                    .layer("Service").definedBy(SERVICE)
                    .layer("Persistence").definedBy(PERSISTENCE)
                    .layer("Domain").definedBy(DOMAIN)
                    .layer("Exception").definedBy(EXCEPTION)
                    .layer("Config").definedBy(CONFIG)
                    // the HTTP adapter drives use cases through services only, never another adapter (Kafka)
                    .whereLayer("Api").mayOnlyAccessLayers("Service", "Domain", "Exception")
                    .whereLayer("Messaging").mayOnlyAccessLayers("Service", "Domain", "Exception", "Config")
                    .whereLayer("Service").mayOnlyAccessLayers("Persistence", "Domain", "Exception")
                    .whereLayer("Persistence").mayOnlyAccessLayers("Domain", "Exception")
                    .whereLayer("Domain").mayOnlyAccessLayers("Exception")
                    .whereLayer("Exception").mayNotAccessAnyLayer());
        }

        @Test
        @DisplayName("no package cycles (config, the composition root, may depend on anything)")
        void noPackageCycles() {
            check(slices().matching(BASE + ".(*)..").should().beFreeOfCycles()
                    .ignoreDependency(resideInAPackage(CONFIG), alwaysTrue()));
        }
    }

    @Nested
    @DisplayName("other conventions")
    class OtherConventions {

        @Test
        @DisplayName("no Lombok")
        void noLombok() {
            check(noClasses().should().dependOnClassesThat().resideInAPackage("lombok.."));
        }

        @Test
        @DisplayName("DTOs are records")
        void dtosAreRecords() {
            check(classes().that().resideInAPackage(API_DTO).should().beAssignableTo(Record.class));
        }

        @Test
        @DisplayName("policy hierarchies are sealed and every permitted policy is an immutable record")
        void sealedPolicyHierarchies() {
            for (Class<?> hierarchy : List.of(ContributionPolicy.class, RewardPolicy.class)) {
                assertThat(hierarchy.isSealed()).as(hierarchy.getSimpleName() + " is sealed").isTrue();
                assertThat(hierarchy.getPermittedSubclasses())
                        .isNotEmpty()
                        .allSatisfy(policy -> assertThat(policy.isRecord()).as(policy.getSimpleName()).isTrue());
            }
        }
    }

    /**
     * Allowed in controller signatures: {@code api.dto} types, {@code String}, primitives, and {@code ResponseEntity} /
     * {@code List} whose type arguments are all DTOs.
     */
    private static ArchCondition<JavaMethod> useOnlyDtoTypesInTheirSignature() {
        return new ArchCondition<>("only use api.dto types, String, primitives, ResponseEntity<DTO> or List<DTO>") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                List<JavaType> signatureTypes = new ArrayList<>(method.getParameterTypes());
                signatureTypes.add(method.getReturnType());
                for (JavaType type : signatureTypes) {
                    if (!isAllowed(type)) {
                        events.add(SimpleConditionEvent.violated(method, method.getFullName() + " uses "
                                + type.getName() + " in " + method.getSourceCodeLocation()));
                    }
                }
            }
        };
    }

    private static boolean isAllowed(JavaType type) {
        JavaClass rawType = type.toErasure();
        return rawType.isPrimitive() || rawType.isEquivalentTo(String.class) || isDtoOrContainerOfDtos(type);
    }

    /** A DTO, or a {@code ResponseEntity} / {@code List} whose type arguments are (containers of) DTOs. */
    private static boolean isDtoOrContainerOfDtos(JavaType type) {
        JavaClass rawType = type.toErasure();
        if (rawType.isEquivalentTo(ResponseEntity.class) || rawType.isEquivalentTo(List.class)) {
            return type instanceof JavaParameterizedType parameterized
                    && !parameterized.getActualTypeArguments().isEmpty()
                    && parameterized.getActualTypeArguments().stream()
                    .allMatch(ArchitectureTest::isDtoOrContainerOfDtos);
        }
        return rawType.getPackageName().equals(BASE + ".api.dto");
    }

    /** Classes with a field whose type is a Spring Data repository, i.e. classes that touch the database. */
    private static DescribedPredicate<JavaClass> ownARepository() {
        return DescribedPredicate.describe("own a repository", javaClass -> javaClass.getFields().stream()
                .anyMatch(field -> field.getRawType().getPackageName().equals(REPOSITORY_PACKAGE)));
    }

    /**
     * {@code @Transactional} on the method or its class; read-write transactions must pin {@code READ_COMMITTED}
     * (the isolation the row-lock design relies on).
     */
    private static ArchCondition<JavaMethod> runInATransaction() {
        return new ArchCondition<>("run in a transaction, READ_COMMITTED unless read-only") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                Method reflected = method.reflect();
                Transactional transactional = AnnotatedElementUtils.findMergedAnnotation(reflected, Transactional.class);
                if (transactional == null) {
                    transactional = AnnotatedElementUtils.findMergedAnnotation(reflected.getDeclaringClass(),
                            Transactional.class);
                }
                if (transactional == null) {
                    events.add(SimpleConditionEvent.violated(method, method.getFullName()
                            + " runs in no transaction in " + method.getSourceCodeLocation()));
                } else if (!transactional.readOnly() && transactional.isolation() != Isolation.READ_COMMITTED) {
                    events.add(SimpleConditionEvent.violated(method, method.getFullName() + " writes with isolation "
                            + transactional.isolation() + " instead of READ_COMMITTED in "
                            + method.getSourceCodeLocation()));
                }
            }
        };
    }

    /** Fixture for the negative test of the transaction condition: owns a repository, mixed methods. */
    static class TransactionFixture {

        private final BetRepository repository;

        TransactionFixture(BetRepository repository) {
            this.repository = repository;
        }

        public boolean notTransactional() {
            return repository.existsById("bet-1");
        }

        @Transactional
        public boolean defaultIsolationWrite() {
            return repository.existsById("bet-1");
        }

        @Transactional(isolation = Isolation.READ_COMMITTED)
        public boolean readCommittedWrite() {
            return repository.existsById("bet-1");
        }

        @Transactional(readOnly = true)
        public boolean readOnlyQuery() {
            return repository.existsById("bet-1");
        }
    }

    /** Fixture: read-only transactional at class level. */
    @Transactional(readOnly = true)
    static class TransactionalFixture {

        private final BetRepository repository;

        TransactionalFixture(BetRepository repository) {
            this.repository = repository;
        }

        public boolean readOnlyViaClass() {
            return repository.existsById("bet-1");
        }
    }

    /** Fixture: owns no repository, so it needs no transaction. */
    static class RepositoryFreeFixture {

        public boolean noDatabase() {
            return true;
        }
    }

    /**
     * Fixture for the negative test of the controller signature condition. Deliberately not annotated with
     * {@code @RestController}: it must never be picked up by component scanning of other test contexts.
     */
    static class ControllerSignatureFixture {

        public com.sporty.jackpot.domain.model.Bet returnsDomain() {
            return null;
        }

        public String takesDomain(com.sporty.jackpot.domain.model.Jackpot jackpot) {
            return jackpot.id();
        }

        @SuppressWarnings("rawtypes")
        public List rawList() {
            return List.of();
        }

        public List<String> listOfString() {
            return List.of();
        }

        public ResponseEntity<List<com.sporty.jackpot.domain.model.Jackpot>> responseOfListOfDomain() {
            return ResponseEntity.ok(List.of());
        }

        public ResponseEntity<com.sporty.jackpot.api.dto.BetResponse> dtoAndPrimitives(String betId, long value) {
            return ResponseEntity.ok().build();
        }

        public ResponseEntity<List<com.sporty.jackpot.api.dto.JackpotResponse>> responseOfListOfDto() {
            return ResponseEntity.ok(List.of());
        }
    }
}
