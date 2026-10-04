package es.unex.jdisrest.config;

import es.unex.jdisrest.config.AlgorithmConfig.MOEADConfig;
import es.unex.jdisrest.config.AlgorithmConfig.NSGAIIConfig;
import es.unex.jdisrest.config.AlgorithmConfig.PAESConfig;
import es.unex.jdisrest.config.SolutionLayout.Segment;
import es.unex.jdisrest.config.Variation.SegmentOperators;
import es.unex.jdisrest.distributed.algorithms.steadystate.MOEAD;
import es.unex.jdisrest.distributed.algorithms.steadystate.MOEADWeights;
import es.unex.jdisrest.distributed.algorithms.steadystate.PAES;
import es.unex.jdisrest.util.SolutionVariables.Encoding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.problem.doubleproblem.DoubleProblem;
import org.uma.jmetal.problem.multiobjective.MixedIntegerDoubleProblem;
import org.uma.jmetal.problem.multiobjective.dtlz.DTLZ1;
import org.uma.jmetal.problem.multiobjective.dtlz.DTLZ2;
import org.uma.jmetal.problem.multiobjective.zdt.ZDT1;
import org.uma.jmetal.problem.multiobjective.zdt.ZDT5;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.permutationsolution.impl.IntegerPermutationSolution;
import org.uma.jmetal.util.errorchecking.exception.InvalidConditionException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Algorithm configurations: the defaults and every key of the three algorithms, the messages of
 * missing and wrong values, files with overrides, byte order marks and backslashes, the checks
 * against a problem, the example files, the settings fixed during a run and the one-line
 * descriptions; then the operators of integer, binary and composite problems, with the keys of
 * each segment and {@code k/n} over its size, and the records and accessors of 1.2.
 */
class AlgorithmConfigTest {

    private static final int VARIABLES = 30;

    @TempDir
    Path folder;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    static Properties properties(String... entries) {
        Properties properties = new Properties();
        for (String entry : entries) {
            int separator = entry.indexOf('=');
            properties.setProperty(entry.substring(0, separator), entry.substring(separator + 1));
        }
        return properties;
    }

    static AlgorithmConfig parse(String... entries) {
        return AlgorithmConfigParser.parse(properties(entries), VARIABLES);
    }

    static InvalidConfigurationException rejection(String... entries) {
        return assertThrows(InvalidConfigurationException.class, () -> parse(entries),
                "the configuration must be rejected: " + List.of(entries));
    }

    static Path write(Path folder, String name, String text) throws IOException {
        Path file = folder.resolve(name);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    /** An operator type whose constructor rejects every value, as a jMetal constructor can. */
    static final OperatorType<String> REJECTING = new OperatorType<>() {
        @Override
        public String key() {
            return "rejecting";
        }

        @Override
        public List<Parameter> parameters() {
            return List.of(new Parameter("width", 1.0));
        }

        @Override
        public String create(double probability, Map<String, Double> parameters) {
            throw new InvalidConditionException("width must be positive");
        }
    };

    // ── NSGA-II ───────────────────────────────────────────────────────────────

    @Test
    void nsgaiiWithOnlyTheRequiredKeysUsesTheDefaults() {
        var config = assertInstanceOf(NSGAIIConfig.class, parse("algorithm=nsgaii", "maxEvaluations=1000"));

        assertAll(
                () -> assertEquals(1000, config.maxEvaluations(), "maxEvaluations as given"),
                () -> assertEquals(100, config.populationSize(), "default population"),
                () -> assertEquals(new OperatorConfig<>(CrossoverType.SBX, 0.9, Map.of("distributionIndex", 20.0)),
                        config.crossover(), "default crossover: SBX 0.9 / 20"),
                () -> assertEquals(new OperatorConfig<>(MutationType.POLYNOMIAL, 1.0 / VARIABLES,
                        Map.of("distributionIndex", 20.0)), config.mutation(), "default mutation: polynomial 1/n / 20"),
                () -> assertNull(config.tracesFolder(), "no traces by default"));
    }

    @Test
    void nsgaiiReadsEveryKey() {
        var config = assertInstanceOf(NSGAIIConfig.class, parse(
                "algorithm=nsgaii", "maxEvaluations=5000", "populationSize=50", "tracesFolder=out",
                "crossover=blxAlpha", "crossover.probability=0.8", "crossover.alpha=0.3",
                "mutation=uniform", "mutation.probability=0.1", "mutation.perturbation=0.2"));

        assertAll(
                () -> assertEquals(5000, config.maxEvaluations(), "maxEvaluations"),
                () -> assertEquals(50, config.populationSize(), "populationSize"),
                () -> assertEquals(new OperatorConfig<>(CrossoverType.BLX_ALPHA, 0.8, Map.of("alpha", 0.3)),
                        config.crossover(), "crossover keys"),
                () -> assertEquals(new OperatorConfig<>(MutationType.UNIFORM, 0.1, Map.of("perturbation", 0.2)),
                        config.mutation(), "mutation keys"),
                () -> assertEquals("out", config.tracesFolder(), "tracesFolder"));
    }

    @Test
    void namesAreCaseInsensitiveAndValuesAreTrimmed() {
        var config = assertInstanceOf(NSGAIIConfig.class, parse(
                "algorithm= NSGAII ", "maxEvaluations=100 ", "crossover=SBX", "mutation=Polynomial",
                "mutation.probability= 1/N"));

        assertAll(
                () -> assertEquals(CrossoverType.SBX, config.crossover().type(), "crossover name in capitals"),
                () -> assertEquals(MutationType.POLYNOMIAL, config.mutation().type(), "mutation name capitalized"),
                () -> assertEquals(1.0 / VARIABLES, config.mutation().probability(), "1/N with spaces"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"3/n", "3/N", " 3 / n ", "3.0/n"})
    void aProbabilityWrittenAsKOverNIsKOverTheNumberOfVariables(String text) {
        var config = assertInstanceOf(NSGAIIConfig.class, parse("algorithm=nsgaii", "maxEvaluations=100",
                "mutation.probability=" + text, "crossover.probability=15/n"));

        assertAll(
                () -> assertEquals(3.0 / VARIABLES, config.mutation().probability(), text + " is 3 over the variables"),
                () -> assertEquals(0.5, config.crossover().probability(), "15/n is 15 over the variables"));
    }

    @Test
    void nPointWithoutParametersCutsTwiceBetweenSingleVariables() {
        var config = assertInstanceOf(NSGAIIConfig.class,
                parse("algorithm=nsgaii", "maxEvaluations=100", "crossover=nPoint", "crossover.probability=1"));

        assertEquals(new OperatorConfig<>(CrossoverType.N_POINT, 1.0, Map.of("points", 2.0, "blockSize", 1.0)),
                config.crossover(), "nPoint defaults to 2 points and blocks of 1 variable");
    }

    @Test
    void nPointAcceptsOnePointFewerThanTheVariables() {
        var config = assertInstanceOf(NSGAIIConfig.class,
                parse("algorithm=nsgaii", "maxEvaluations=100", "crossover=nPoint", "crossover.points=29"));

        assertEquals(Map.of("points", 29.0, "blockSize", 1.0), config.crossover().parameters(),
                "29 cuts fit between 30 variables");
    }

    @Test
    void nPointWithBlocksAcceptsOnePointFewerThanTheBlocks() {
        var config = assertInstanceOf(NSGAIIConfig.class, parse("algorithm=nsgaii", "maxEvaluations=100",
                "crossover=nPoint", "crossover.points=9", "crossover.blockSize=3"));

        assertEquals(Map.of("points", 9.0, "blockSize", 3.0), config.crossover().parameters(),
                "9 cuts fit between 10 blocks of 3 variables");
    }

    @Test
    void anOperatorWithoutParametersHasAnEmptyParameterMap() {
        var config = assertInstanceOf(NSGAIIConfig.class,
                parse("algorithm=nsgaii", "maxEvaluations=100", "crossover=arithmetic", "mutation=random"));

        assertAll(
                () -> assertEquals(Map.of(), config.crossover().parameters(), "arithmetic has no parameters"),
                () -> assertEquals(Map.of(), config.mutation().parameters(), "random has no parameters"));
    }

    // ── PAES ──────────────────────────────────────────────────────────────────

    @Test
    void paesWithOnlyTheRequiredKeysUsesTheDefaults() {
        var config = assertInstanceOf(PAESConfig.class, parse("algorithm=paes", "maxEvaluations=1000"));

        assertAll(
                () -> assertEquals(1000, config.maxEvaluations(), "maxEvaluations as given"),
                () -> assertEquals(100, config.archiveSize(), "default archive"),
                () -> assertEquals(new OperatorConfig<>(MutationType.POLYNOMIAL, 1.0 / VARIABLES,
                        Map.of("distributionIndex", 20.0)), config.mutation(), "default mutation: polynomial 1/n / 20"),
                () -> assertEquals(0.0, config.archiveSelectionProbability(), "classic PAES: the current solution"),
                () -> assertEquals(PAES.ResultSource.PAES_ARCHIVE, config.resultSource(), "the PAES archive is the result"),
                () -> assertNull(config.tracesFolder(), "no traces by default"));
    }

    @Test
    void paesReadsEveryKey() {
        var config = assertInstanceOf(PAESConfig.class, parse(
                "algorithm=paes", "maxEvaluations=2000", "archiveSize=40", "tracesFolder=traces",
                "mutation=linkedPolynomial", "mutation.probability=0.05", "mutation.distributionIndex=10",
                "archiveSelectionProbability=0.2", "result=external"));

        assertAll(
                () -> assertEquals(2000, config.maxEvaluations(), "maxEvaluations"),
                () -> assertEquals(40, config.archiveSize(), "archiveSize"),
                () -> assertEquals(new OperatorConfig<>(MutationType.LINKED_POLYNOMIAL, 0.05,
                        Map.of("distributionIndex", 10.0)), config.mutation(), "mutation keys"),
                () -> assertEquals(0.2, config.archiveSelectionProbability(), "archiveSelectionProbability"),
                () -> assertEquals(PAES.ResultSource.EXTERNAL_ARCHIVE, config.resultSource(), "result"),
                () -> assertEquals("traces", config.tracesFolder(), "tracesFolder"));
    }

    @Test
    void levyFlightWithoutParametersUsesTheDefaultsOfJMetal() {
        var config = assertInstanceOf(PAESConfig.class,
                parse("algorithm=paes", "maxEvaluations=100", "mutation=levyFlight"));

        assertEquals(new OperatorConfig<>(MutationType.LEVY_FLIGHT, 1.0 / VARIABLES,
                Map.of("beta", 1.5, "stepSize", 0.01)), config.mutation(), "jMetal's beta 1.5 and step size 0.01");
    }

    @Test
    void levyFlightReadsBetaAndTheStepSize() {
        var config = assertInstanceOf(PAESConfig.class, parse("algorithm=paes", "maxEvaluations=100",
                "mutation=levyFlight", "mutation.beta=1.9", "mutation.stepSize=0.26"));

        assertEquals(Map.of("beta", 1.9, "stepSize", 0.26), config.mutation().parameters(), "beta and stepSize");
    }

    @Test
    void levyRandomWithoutParametersUsesTheDefaultsOfLevyFlight() {
        var config = assertInstanceOf(PAESConfig.class,
                parse("algorithm=paes", "maxEvaluations=100", "mutation=levyRandom"));

        assertEquals(new OperatorConfig<>(MutationType.LEVY_RANDOM, 1.0 / VARIABLES,
                Map.of("beta", 1.5, "stepSize", 0.01)), config.mutation(), "the defaults of levyFlight");
    }

    @Test
    void levyRandomReadsBetaAndTheMaximumStepSize() {
        var config = assertInstanceOf(PAESConfig.class, parse("algorithm=paes", "maxEvaluations=100",
                "mutation=levyRandom", "mutation.beta=1.25", "mutation.stepSize=0.5"));

        assertEquals(Map.of("beta", 1.25, "stepSize", 0.5), config.mutation().parameters(),
                "beta and the maximum step size");
    }

    @Test
    void paesRejectsACrossover() {
        var exception = rejection("algorithm=paes", "maxEvaluations=100", "crossover=sbx");

        assertTrue(exception.getMessage().startsWith("unknown keys for paes with these operators: crossover."),
                "PAES uses no crossover, so the key is unknown: " + exception.getMessage());
    }

    // ── MOEA/D ────────────────────────────────────────────────────────────────

    @Test
    void moeadWithOnlyTheRequiredKeysUsesTheDefaults() {
        var config = assertInstanceOf(MOEADConfig.class, parse("algorithm=moead", "maxEvaluations=1000"));

        assertAll(
                () -> assertEquals(1000, config.maxEvaluations(), "maxEvaluations as given"),
                () -> assertEquals(100, config.populationSize(), "default population"),
                () -> assertEquals(MOEADWeights.Method.SPREAD, config.weights(), "spread weights"),
                () -> assertEquals(20, config.neighborSize(), "jMetal's neighborhood of 20"),
                () -> assertEquals(0.9, config.neighborhoodSelectionProbability(), "jMetal's selection probability"),
                () -> assertEquals(2, config.maximumNumberOfReplacedSolutions(), "jMetal's 2 replaced solutions"),
                () -> assertEquals(MOEAD.AggregationFunction.TCHEBYCHEFF, config.aggregation(), "Tchebycheff"),
                () -> assertFalse(config.normalizeObjectives(), "no normalization"),
                () -> assertEquals(new OperatorConfig<>(CrossoverType.SBX, 0.9, Map.of("distributionIndex", 20.0)),
                        config.crossover(), "default crossover: SBX 0.9 / 20"),
                () -> assertEquals(new OperatorConfig<>(MutationType.POLYNOMIAL, 1.0 / VARIABLES,
                        Map.of("distributionIndex", 20.0)), config.mutation(), "default mutation: polynomial 1/n / 20"),
                () -> assertNull(config.tracesFolder(), "no traces by default"));
    }

    @Test
    void moeadReadsEveryKey() {
        var config = assertInstanceOf(MOEADConfig.class, parse(
                "algorithm=MOEAD", "maxEvaluations=3000", "populationSize=91", "tracesFolder=traces",
                "weights=Lattice", "neighborSize=10", "neighborhoodSelectionProbability=0.8",
                "maximumNumberOfReplacedSolutions=3", "aggregation=PBI", "normalizeObjectives=TRUE",
                "crossover=nPoint", "crossover.probability=0.6", "crossover.points=2", "crossover.blockSize=3",
                "mutation=random", "mutation.probability=3/n"));

        assertAll(
                () -> assertEquals(3000, config.maxEvaluations(), "maxEvaluations"),
                () -> assertEquals(91, config.populationSize(), "populationSize"),
                () -> assertEquals(MOEADWeights.Method.LATTICE, config.weights(), "weights"),
                () -> assertEquals(10, config.neighborSize(), "neighborSize"),
                () -> assertEquals(0.8, config.neighborhoodSelectionProbability(), "neighborhoodSelectionProbability"),
                () -> assertEquals(3, config.maximumNumberOfReplacedSolutions(), "maximumNumberOfReplacedSolutions"),
                () -> assertEquals(MOEAD.AggregationFunction.PBI, config.aggregation(), "aggregation"),
                () -> assertTrue(config.normalizeObjectives(), "normalizeObjectives"),
                () -> assertEquals(new OperatorConfig<>(CrossoverType.N_POINT, 0.6,
                        Map.of("points", 2.0, "blockSize", 3.0)), config.crossover(), "crossover keys"),
                () -> assertEquals(new OperatorConfig<>(MutationType.RANDOM, 3.0 / VARIABLES, Map.of()),
                        config.mutation(), "mutation keys"),
                () -> assertEquals("traces", config.tracesFolder(), "tracesFolder"));
    }

    @Test
    void aSmallMoeadPopulationShrinksTheNeighborhoodDefaults() {
        var config = assertInstanceOf(MOEADConfig.class,
                parse("algorithm=moead", "maxEvaluations=100", "populationSize=1"));

        assertAll(
                () -> assertEquals(1, config.neighborSize(), "the neighborhood is at most the population"),
                () -> assertEquals(1, config.maximumNumberOfReplacedSolutions(),
                        "the replaced solutions are at most the neighborhood"));
    }

    @Test
    void aMoeadKeyInAnNsgaiiFileIsUnknown() {
        var exception = rejection("algorithm=nsgaii", "maxEvaluations=100", "neighborSize=20");

        assertTrue(exception.getMessage().startsWith("unknown keys for nsgaii with these operators: neighborSize."),
                "NSGA-II has no neighborhood: " + exception.getMessage());
    }

    // ── Missing and wrong values ──────────────────────────────────────────────

    static Stream<Arguments> wrongConfigurations() {
        return Stream.of(
                Arguments.of(List.of("maxEvaluations=100"), "algorithm is required (nsgaii, paes or moead)"),
                Arguments.of(List.of("algorithm=smsemoa", "maxEvaluations=100"),
                        "algorithm must be nsgaii, paes or moead, got 'smsemoa'"),
                Arguments.of(List.of("algorithm=moead", "maxEvaluations=100", "neighborSize=0"),
                        "neighborSize must be a positive integer, got '0'"),
                Arguments.of(List.of("algorithm=moead", "maxEvaluations=100", "neighborSize=101"),
                        "neighborSize must not be greater than populationSize (100), got '101'"),
                Arguments.of(List.of("algorithm=moead", "maxEvaluations=100", "neighborSize=10",
                        "maximumNumberOfReplacedSolutions=11"),
                        "maximumNumberOfReplacedSolutions must not be greater than neighborSize (10), got '11'"),
                Arguments.of(List.of("algorithm=moead", "maxEvaluations=100", "neighborhoodSelectionProbability=1.2"),
                        "neighborhoodSelectionProbability must be in [0, 1] or k/n, got '1.2'"),
                Arguments.of(List.of("algorithm=moead", "maxEvaluations=100", "aggregation=chebyshev"),
                        "aggregation must be tchebycheff, wsum or pbi, got 'chebyshev'"),
                Arguments.of(List.of("algorithm=moead", "maxEvaluations=100", "weights=random"),
                        "weights must be spread or lattice, got 'random'"),
                Arguments.of(List.of("algorithm=moead", "maxEvaluations=100", "normalizeObjectives=yes"),
                        "normalizeObjectives must be true or false, got 'yes'"),
                Arguments.of(List.of("algorithm=nsgaii"), "maxEvaluations is required"),
                Arguments.of(List.of("algorithm=nsgaii", "maxEvaluations=0"),
                        "maxEvaluations must be a positive integer, got '0'"),
                Arguments.of(List.of("algorithm=nsgaii", "maxEvaluations=1e5"),
                        "maxEvaluations must be a positive integer, got '1e5'"),
                Arguments.of(List.of("algorithm=nsgaii", "maxEvaluations=100", "populationSize=-5"),
                        "populationSize must be a positive integer, got '-5'"),
                Arguments.of(List.of("algorithm=paes", "maxEvaluations=100", "archiveSize=many"),
                        "archiveSize must be a positive integer, got 'many'"),
                Arguments.of(List.of("algorithm=nsgaii", "maxEvaluations=100", "crossover=pmx"),
                        "crossover must be one of sbx, blxAlpha, laplace, arithmetic, wholeArithmetic, nPoint, got 'pmx'"),
                Arguments.of(List.of("algorithm=paes", "maxEvaluations=100", "mutation=gaussian"),
                        "mutation must be one of polynomial, linkedPolynomial, uniform, random, levyFlight, levyRandom, got 'gaussian'"),
                Arguments.of(List.of("algorithm=nsgaii", "maxEvaluations=100", "crossover.probability=1.5"),
                        "crossover.probability must be in [0, 1] or k/n, got '1.5'"),
                Arguments.of(List.of("algorithm=nsgaii", "maxEvaluations=100", "mutation.probability=-0.1"),
                        "mutation.probability must be in [0, 1] or k/n, got '-0.1'"),
                Arguments.of(List.of("algorithm=nsgaii", "maxEvaluations=100", "crossover.probability=NaN"),
                        "crossover.probability must be a number or k/n, got 'NaN'"),
                Arguments.of(List.of("algorithm=nsgaii", "maxEvaluations=100", "crossover.probability=0x1p-2"),
                        "crossover.probability must be a number or k/n, got '0x1p-2'"),
                Arguments.of(List.of("algorithm=nsgaii", "maxEvaluations=100", "crossover.distributionIndex=-1"),
                        "crossover.distributionIndex must not be negative, got '-1'"),
                Arguments.of(List.of("algorithm=nsgaii", "maxEvaluations=100", "crossover.distributionIndex=20d"),
                        "crossover.distributionIndex must be a number, got '20d'"),
                Arguments.of(List.of("algorithm=paes", "maxEvaluations=100", "archiveSelectionProbability=2"),
                        "archiveSelectionProbability must be in [0, 1] or k/n, got '2'"),
                Arguments.of(List.of("algorithm=nsgaii", "maxEvaluations=100", "mutation.probability=40/n"),
                        "mutation.probability must be in [0, 1] or k/n, got '40/n' (1.333)"),
                Arguments.of(List.of("algorithm=nsgaii", "maxEvaluations=100", "mutation.probability=-1/n"),
                        "mutation.probability must be in [0, 1] or k/n, got '-1/n' (-0.03333)"),
                Arguments.of(List.of("algorithm=nsgaii", "maxEvaluations=100", "mutation.probability=/n"),
                        "mutation.probability must be a number or k/n, got '/n'"),
                Arguments.of(List.of("algorithm=nsgaii", "maxEvaluations=100", "mutation.probability=three/n"),
                        "mutation.probability must be a number or k/n, got 'three/n'"),
                Arguments.of(List.of("algorithm=nsgaii", "maxEvaluations=100", "crossover=laplace",
                        "crossover.scale=0"), "crossover.scale must be greater than 0, got '0'"),
                Arguments.of(List.of("algorithm=nsgaii", "maxEvaluations=100", "mutation=uniform",
                        "mutation.perturbation=0"), "mutation.perturbation must be greater than 0, got '0'"),
                Arguments.of(List.of("algorithm=paes", "maxEvaluations=100", "mutation=levyFlight",
                        "mutation.beta=1"), "mutation.beta must be in (1, 2), got '1'"),
                Arguments.of(List.of("algorithm=paes", "maxEvaluations=100", "mutation=levyFlight",
                        "mutation.beta=2"), "mutation.beta must be in (1, 2), got '2'"),
                Arguments.of(List.of("algorithm=paes", "maxEvaluations=100", "mutation=levyFlight",
                        "mutation.beta=2.5"), "mutation.beta must be in (1, 2), got '2.5'"),
                Arguments.of(List.of("algorithm=paes", "maxEvaluations=100", "mutation=levyFlight",
                        "mutation.stepSize=0"), "mutation.stepSize must be greater than 0, got '0'"),
                Arguments.of(List.of("algorithm=paes", "maxEvaluations=100", "mutation=levyRandom",
                        "mutation.beta=2"), "mutation.beta must be in (1, 2), got '2'"),
                Arguments.of(List.of("algorithm=paes", "maxEvaluations=100", "mutation=levyRandom",
                        "mutation.stepSize=0"), "mutation.stepSize must be greater than 0, got '0'"),
                Arguments.of(List.of("algorithm=paes", "maxEvaluations=100", "result=both"),
                        "result must be paes or external, got 'both'"),
                Arguments.of(List.of("algorithm=nsgaii", "maxEvaluations=100", "crossover=nPoint",
                        "crossover.points=2.5"), "crossover.points must be an integer, got '2.5'"),
                Arguments.of(List.of("algorithm=nsgaii", "maxEvaluations=100", "crossover=nPoint",
                        "crossover.points=0"), "crossover.points: the number of points must be at least 1, got 0"),
                Arguments.of(List.of("algorithm=nsgaii", "maxEvaluations=100", "crossover=nPoint",
                        "crossover.points=30"),
                        "crossover.points: the number of points (30) must be smaller than the number of variables (30)"),
                Arguments.of(List.of("algorithm=nsgaii", "maxEvaluations=100", "crossover=nPoint",
                        "crossover.blockSize=3", "crossover.points=10"),
                        "crossover.points: the number of points (10) must be smaller than the number of blocks "
                                + "(10 blocks of 3 variables)"),
                Arguments.of(List.of("algorithm=nsgaii", "maxEvaluations=100", "crossover=nPoint",
                        "crossover.blockSize=0"), "crossover.blockSize: the block size must be at least 1, got 0"),
                Arguments.of(List.of("algorithm=nsgaii", "maxEvaluations=100", "crossover=nPoint",
                        "crossover.blockSize=1.5"), "crossover.blockSize must be an integer, got '1.5'"),
                Arguments.of(List.of("algorithm=nsgaii", "maxEvaluations=100", "crossover=nPoint",
                        "crossover.blockSize=3.00001"), "crossover.blockSize must be an integer, got '3.00001'"),
                Arguments.of(List.of("algorithm=nsgaii", "maxEvaluations=100", "crossover=nPoint",
                        "crossover.blockSize=7"),
                        "crossover.blockSize: the number of variables (30) is not a multiple of the block size (7)"),
                Arguments.of(List.of("algorithm=nsgaii", "maxEvaluations=100", "crossover=nPoint",
                        "crossover.blockSize=30"),
                        "crossover.blockSize: an n-point crossover needs at least 2 blocks of 30 variables to cut "
                                + "between, got 30 variables"));
    }

    @ParameterizedTest
    @MethodSource("wrongConfigurations")
    void aMissingOrWrongValueIsRejectedNamingTheKey(List<String> entries, String message) {
        var exception = rejection(entries.toArray(String[]::new));

        assertEquals(message, exception.getMessage(), "the message names the key and the value");
    }

    @Test
    void aMisspeltKeyIsRejectedListingTheValidKeys() {
        var exception = rejection("algorithm=nsgaii", "maxEvaluations=100", "crossover.probabilty=0.8");

        assertEquals("unknown keys for nsgaii with these operators: crossover.probabilty. Valid keys: algorithm, "
                + "maxEvaluations, populationSize, crossover, crossover.probability, crossover.distributionIndex, "
                + "mutation, mutation.probability, mutation.distributionIndex, tracesFolder",
                exception.getMessage(), "the unknown key, then every valid key in reading order");
    }

    @Test
    void aParameterOfAnotherOperatorIsUnknown() {
        var exception = rejection("algorithm=nsgaii", "maxEvaluations=100", "crossover=sbx", "crossover.alpha=0.5");

        assertTrue(exception.getMessage().startsWith("unknown keys for nsgaii with these operators: crossover.alpha."),
                "alpha belongs to blxAlpha, not to sbx: " + exception.getMessage());
    }

    @Test
    void nPointOnAProblemWithOneVariableHasNothingToCut() {
        var exception = assertThrows(InvalidConfigurationException.class, () -> AlgorithmConfigParser.parse(
                properties("algorithm=nsgaii", "maxEvaluations=100", "crossover=nPoint"), 1));

        assertEquals("crossover.blockSize: an n-point crossover needs at least 2 variables to cut between, got 1",
                exception.getMessage(), "one variable has no position to cut");
    }

    @Test
    void anOperatorItsConstructorRejectsIsReportedUnderItsKey() {
        var parser = new AlgorithmConfigParser(properties("crossover=rejecting"), VARIABLES);

        var exception = assertThrows(InvalidConfigurationException.class,
                () -> parser.operator("crossover", List.of(REJECTING), REJECTING, "0.9"));

        assertAll(
                () -> assertEquals("crossover: rejecting cannot be built with these values: width must be positive",
                        exception.getMessage(), "the key, the operator and the constructor's reason"),
                () -> assertInstanceOf(InvalidConditionException.class, exception.getCause(),
                        "the constructor's exception is kept as the cause"));
    }

    @Test
    void aNonPositiveNumberOfVariablesIsAnIllegalArgument() {
        var exception = assertThrows(IllegalArgumentException.class,
                () -> AlgorithmConfigParser.parse(properties("algorithm=nsgaii", "maxEvaluations=100"), 0));

        assertFalse(exception instanceof InvalidConfigurationException,
                "a broken problem is not a configuration error");
    }

    // ── Files and texts ───────────────────────────────────────────────────────

    @Test
    void overridesReplaceTheKeysOfTheFile() throws IOException {
        Path file = write(folder, "nsgaii.properties",
                "# Test run: µ, π and ≥ in a UTF-8 comment\nalgorithm = nsgaii\nmaxEvaluations = 100000\n");

        var config = assertInstanceOf(NSGAIIConfig.class,
                AlgorithmConfig.load(file, List.of("maxEvaluations=40", "populationSize = 10"), VARIABLES));

        assertAll(
                () -> assertEquals(40, config.maxEvaluations(), "an override replaces a key of the file"),
                () -> assertEquals(10, config.populationSize(), "an override adds a key, trimmed"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"maxEvaluations", "=40"})
    void anOverrideThatIsNotKeyValueIsRejected(String override) throws IOException {
        Path file = write(folder, "paes.properties", "algorithm=paes\nmaxEvaluations=100\n");

        var exception = assertThrows(InvalidConfigurationException.class,
                () -> AlgorithmConfig.load(file, List.of(override), VARIABLES));

        assertEquals("expected key=value, got '" + override + "'", exception.getMessage(), "the override as given");
    }

    @Test
    void aMissingFileIsRejectedNamingIt() {
        Path file = folder.resolve("missing.properties");

        var exception = assertThrows(InvalidConfigurationException.class,
                () -> AlgorithmConfig.load(file, List.of(), VARIABLES));

        assertTrue(exception.getMessage().startsWith("cannot read configuration file " + file),
                "the message names the file: " + exception.getMessage());
    }

    @Test
    void aFileThatIsNotUtf8IsRejectedNamingTheEncoding() throws IOException {
        Path file = folder.resolve("latin1.properties");
        Files.writeString(file, "# Café\nalgorithm=nsgaii\nmaxEvaluations=100\n", StandardCharsets.ISO_8859_1);

        var exception = assertThrows(InvalidConfigurationException.class,
                () -> AlgorithmConfig.load(file, List.of(), VARIABLES));

        assertEquals("cannot read configuration file " + file + ": it is not UTF-8 text; save it with the UTF-8 encoding",
                exception.getMessage(), "the message names the expected encoding");
    }

    @ParameterizedTest
    @ValueSource(strings = {"# First line is a comment\nalgorithm=nsgaii\nmaxEvaluations=100\n",
            "algorithm=nsgaii\nmaxEvaluations=100\n"})
    void aFileWithAByteOrderMarkLoads(String text) throws IOException {
        Path file = write(folder, "bom.properties", "\uFEFF" + text);

        AlgorithmConfig config = AlgorithmConfig.load(file, List.of(), VARIABLES);

        assertEquals(100, config.maxEvaluations(), "the byte order mark is not part of the first line");
    }

    @Test
    void aTextWithAByteOrderMarkParses() {
        AlgorithmConfig config = AlgorithmConfig.parseText("\uFEFFalgorithm=paes\nmaxEvaluations=100\n", VARIABLES);

        assertInstanceOf(PAESConfig.class, config, "the byte order mark is not part of the first key");
    }

    @Test
    void aMalformedUnicodeEscapeIsAConfigurationError() throws IOException {
        Path file = write(folder, "escape.properties", "algorithm=nsgaii\nmaxEvaluations=100\ntracesFolder=\\" + "u00zz\n");

        var exception = assertThrows(InvalidConfigurationException.class,
                () -> AlgorithmConfig.load(file, List.of(), VARIABLES));

        assertTrue(exception.getMessage().startsWith("cannot read configuration file " + file + ": "),
                "the message names the file: " + exception.getMessage());
    }

    @Test
    void aKeyWrittenTwiceTakesItsLastValue() throws IOException {
        Path file = write(folder, "twice.properties", "algorithm=nsgaii\nmaxEvaluations=100\nmaxEvaluations=7\n");

        AlgorithmConfig config = AlgorithmConfig.load(file, List.of(), VARIABLES);

        assertEquals(7, config.maxEvaluations(), "as in any properties file, the last occurrence wins");
    }

    // C:\temp\traces reads as C:<TAB>emp<TAB>races, and C:\data\exp as the drive-relative C:dataexp.
    @ParameterizedTest
    @ValueSource(strings = {"C:\\temp\\traces", "C:\\data\\exp"})
    void aTracesFolderWrittenWithSingleBackslashesIsRejected(String path) throws IOException {
        Path file = write(folder, "windows.properties", "algorithm=nsgaii\nmaxEvaluations=100\ntracesFolder=" + path + "\n");

        var exception = assertThrows(InvalidConfigurationException.class,
                () -> AlgorithmConfig.load(file, List.of(), VARIABLES));

        assertTrue(exception.getMessage().startsWith("tracesFolder ")
                        && exception.getMessage().endsWith("so write Windows paths with / or with doubled backslashes, "
                        + "as C:/runs/traces or C:\\\\runs\\\\traces"),
                "the escapes would silently change the folder: " + exception.getMessage());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"C:/runs/traces|C:/runs/traces", "C:\\\\runs\\\\traces|C:\\runs\\traces"})
    void aTracesFolderWrittenWithSlashesOrDoubledBackslashesIsAccepted(String written, String folderName)
            throws IOException {
        Path file = write(folder, "windows.properties", "algorithm=nsgaii\nmaxEvaluations=100\ntracesFolder=" + written + "\n");

        AlgorithmConfig config = AlgorithmConfig.load(file, List.of(), VARIABLES);

        assertEquals(folderName, config.tracesFolder(), "the folder as meant");
    }

    @Test
    void aBackslashInAnOverrideIsTakenLiterally() throws IOException {
        Path file = write(folder, "nsgaii.properties", "algorithm=nsgaii\nmaxEvaluations=100\n");

        AlgorithmConfig config = AlgorithmConfig.load(file, List.of("tracesFolder=C:\\temp\\traces"), VARIABLES);

        assertEquals("C:\\temp\\traces", config.tracesFolder(), "overrides have no escape sequences");
    }

    @Test
    void aCopyWithoutOverridesHasTheSameNameAndContent() throws IOException {
        Path file = write(folder, "paes.properties", "# Test run: µ and π\nalgorithm=paes\nmaxEvaluations=100\n");
        Path traces = folder.resolve("run/traces");

        Path copy = AlgorithmConfig.writeCopy(file, List.of(), traces);

        assertAll(
                () -> assertEquals(traces.resolve("paes.properties"), copy, "same name, in the new folder"),
                () -> assertEquals(Files.readString(file, StandardCharsets.UTF_8),
                        Files.readString(copy, StandardCharsets.UTF_8), "same content"));
    }

    @Test
    void readingACopyWithOverridesGivesTheConfigurationOfTheRun() throws IOException {
        // No final newline in the file, and a backslash in an override.
        Path file = write(folder, "nsgaii.properties", "algorithm = nsgaii\nmaxEvaluations = 100000\ntracesFolder = traces");
        List<String> overrides = List.of("maxEvaluations=40", "populationSize = 10", "tracesFolder=C:\\runs\\traces");

        Path copy = AlgorithmConfig.writeCopy(file, overrides, folder.resolve("traces"));

        var fromCopy = assertInstanceOf(NSGAIIConfig.class, AlgorithmConfig.load(copy, List.of(), VARIABLES));
        String text = Files.readString(copy, StandardCharsets.UTF_8);
        assertAll(
                () -> assertEquals(AlgorithmConfig.load(file, overrides, VARIABLES), fromCopy,
                        "the copy reads as the file with its overrides"),
                () -> assertEquals(40, fromCopy.maxEvaluations(), "the override wins over the key of the file"),
                () -> assertEquals("C:\\runs\\traces", fromCopy.tracesFolder(), "the backslashes survive the copy"),
                () -> assertTrue(text.startsWith("algorithm = nsgaii\n# overridden on the command line: maxEvaluations = 100000\n"
                        + "# overridden on the command line: tracesFolder = traces\n"),
                        "the replaced lines are commented out, so each key has one active line: " + text));
    }

    @Test
    void aCopyOntoTheFileItselfWithoutOverridesWritesNothing() throws IOException {
        Path file = write(folder, "paes.properties", "algorithm=paes\nmaxEvaluations=100\n");

        Path copy = AlgorithmConfig.writeCopy(file, List.of(), folder);

        assertAll(
                () -> assertEquals(file, copy, "the file is its own copy"),
                () -> assertEquals("algorithm=paes\nmaxEvaluations=100\n", Files.readString(file), "nothing written"),
                () -> assertTrue(Files.notExists(folder.resolve("paes_0.properties")), "no other copy"));
    }

    @Test
    void aCopyOntoTheFileItselfWithOverridesGoesToAZeroFile() throws IOException {
        Path file = write(folder, "paes.properties", "algorithm=paes\nmaxEvaluations=100\n");

        Path copy = AlgorithmConfig.writeCopy(file, List.of("maxEvaluations=40"), folder);

        assertAll(
                () -> assertEquals(folder.resolve("paes_0.properties"), copy, "the copy gets the _0 suffix"),
                () -> assertEquals(40, AlgorithmConfig.load(copy, List.of(), VARIABLES).maxEvaluations(),
                        "the copy holds the overrides"),
                () -> assertEquals("algorithm=paes\nmaxEvaluations=100\n", Files.readString(file),
                        "the file the run started from is not rewritten"));
    }

    // ── Problems and example files ────────────────────────────────────────────

    static Stream<Arguments> examplesAndProblems() {
        return Stream.of(new ZDT1(), new DTLZ2(), new DTLZ1())
                .flatMap(problem -> Stream.of(
                        Arguments.of("nsgaii", NSGAIIConfig.class, problem),
                        Arguments.of("paes", PAESConfig.class, problem),
                        Arguments.of("moead", MOEADConfig.class, problem)));
    }

    @ParameterizedTest
    @MethodSource("examplesAndProblems")
    void everyExampleFileLoadsForOrdinaryProblems(String algorithm, Class<? extends AlgorithmConfig> type,
            DoubleProblem problem) {
        AlgorithmConfig config = AlgorithmConfig.load(Path.of("examples", algorithm + ".properties"), List.of(), problem);

        String description = config.describe();
        assertAll(
                () -> assertInstanceOf(type, config, algorithm + ".properties configures " + algorithm),
                () -> assertTrue(description.contains("mutation polynomial (probability "
                                + OperatorConfig.format(1.0 / problem.numberOfVariables()) + ", distributionIndex 20)"),
                        "1/n follows the variables of " + problem.name() + ": " + description),
                () -> assertTrue(description.endsWith(", traces in traces"), "a relative traces folder: " + description));
    }

    @Test
    void loadingForAProblemReadsKOverNWithItsVariables() throws IOException {
        Path file = write(folder, "nsgaii.properties", "algorithm=nsgaii\nmaxEvaluations=100\nmutation.probability=3/n\n");

        var config = assertInstanceOf(NSGAIIConfig.class, AlgorithmConfig.load(file, List.of(), new DTLZ2()));

        assertEquals(0.25, config.mutation().probability(), "3/n is 3 over the 12 variables of DTLZ2");
    }

    @Test
    void latticeWeightsThatDoNotFitTheObjectivesAreRejected() throws IOException {
        Path file = write(folder, "moead.properties", "algorithm=moead\nmaxEvaluations=100\nweights=lattice\n");

        var exception = assertThrows(InvalidConfigurationException.class,
                () -> AlgorithmConfig.load(file, List.of(), new DTLZ2()));

        assertEquals("populationSize: LATTICE weights for 3 objectives need C(H+2, 2) vectors, such as 91 or 105, "
                + "got 100 (spread weights take any size)", exception.getMessage(), "the closest lattice sizes");
    }

    @Test
    void latticeWeightsOfALatticeSizeAreAccepted() throws IOException {
        Path file = write(folder, "moead.properties",
                "algorithm=moead\nmaxEvaluations=100\nweights=lattice\npopulationSize=91\n");

        var config = assertInstanceOf(MOEADConfig.class, AlgorithmConfig.load(file, List.of(), new DTLZ2()));

        assertEquals(91, config.populationSize(), "91 is C(14, 2), the lattice of 12 divisions");
    }

    @ParameterizedTest
    @CsvSource({"lattice, 2", "spread, 3"})
    void otherWeightsFitAnyPopulationSize(String weights, int numberOfObjectives) throws IOException {
        Path file = write(folder, "moead.properties", "algorithm=moead\nmaxEvaluations=100\nweights=" + weights + "\n");
        DoubleProblem problem = numberOfObjectives == 2 ? new ZDT1() : new DTLZ2();

        var config = assertInstanceOf(MOEADConfig.class, AlgorithmConfig.load(file, List.of(), problem));

        assertEquals(100, config.populationSize(), weights + " weights take 100 vectors of " + numberOfObjectives);
    }

    @Test
    void parsingATextForAProblemChecksTheLatticeToo() {
        String text = "algorithm=moead\nmaxEvaluations=100\nweights=lattice\n";

        assertAll(
                () -> assertThrows(InvalidConfigurationException.class, () -> AlgorithmConfig.parseText(text, new DTLZ2()),
                        "100 is no lattice size for 3 objectives"),
                () -> assertInstanceOf(MOEADConfig.class, AlgorithmConfig.parseText(text, 12),
                        "the number of variables alone does not check the lattice"));
    }

    // ── Changes during a run ──────────────────────────────────────────────────

    static AlgorithmConfig runningPaes() {
        return parse("algorithm=paes", "maxEvaluations=1000", "archiveSize=100", "tracesFolder=traces");
    }

    @Test
    void parsingATextReadsItAsAFile() {
        var config = assertInstanceOf(PAESConfig.class,
                AlgorithmConfig.parseText("# Test run\nalgorithm = paes\nmaxEvaluations = 5000\n", VARIABLES));

        assertEquals(5000, config.maxEvaluations(), "comments and spaces as in a file");
    }

    @Test
    void changingTheOperatorsTheBudgetAndThePaesSettingsIsAllowed() {
        AlgorithmConfig next = parse("algorithm=paes", "maxEvaluations=9000", "archiveSize=100", "tracesFolder=traces",
                "mutation=levyFlight", "mutation.probability=3/n", "archiveSelectionProbability=0.5",
                "result=external");

        assertEquals(List.of(), AlgorithmConfig.fixedDuringRun(runningPaes(), next), "nothing fixed changes");
    }

    @Test
    void changingTheAlgorithmIsForbidden() {
        AlgorithmConfig next = parse("algorithm=nsgaii", "maxEvaluations=1000", "tracesFolder=traces");

        assertEquals(List.of("algorithm cannot change during a run (paes to nsgaii)"),
                AlgorithmConfig.fixedDuringRun(runningPaes(), next), "both algorithms are named");
    }

    @Test
    void changingTheArchiveSizeAndTheTracesFolderIsForbidden() {
        AlgorithmConfig next = parse("algorithm=paes", "maxEvaluations=1000", "archiveSize=50");

        assertEquals(List.of("archiveSize cannot change during a run (100 to 50)",
                "tracesFolder cannot change during a run (traces to none)"),
                AlgorithmConfig.fixedDuringRun(runningPaes(), next), "one message per forbidden change");
    }

    @ParameterizedTest
    @ValueSource(strings = {"traces", "./traces", "traces/", "traces/../traces"})
    void anotherSpellingOfTheSameTracesFolderIsNoChange(String spelling) {
        AlgorithmConfig next = parse("algorithm=paes", "maxEvaluations=1000", "tracesFolder=" + spelling);

        assertEquals(List.of(), AlgorithmConfig.fixedDuringRun(runningPaes(), next), spelling + " is the same folder");
    }

    @Test
    void changingTheNsgaiiPopulationSizeIsForbidden() {
        AlgorithmConfig nsgaii = parse("algorithm=nsgaii", "maxEvaluations=1000");
        AlgorithmConfig next = parse("algorithm=nsgaii", "maxEvaluations=1000", "populationSize=20");

        assertEquals(List.of("populationSize cannot change during a run (100 to 20)"),
                AlgorithmConfig.fixedDuringRun(nsgaii, next), "the population shapes the run");
    }

    @Test
    void changingTheMoeadOperatorsBudgetAndSettingsIsAllowed() {
        AlgorithmConfig moead = parse("algorithm=moead", "maxEvaluations=1000");
        AlgorithmConfig next = parse("algorithm=moead", "maxEvaluations=9000", "neighborhoodSelectionProbability=0.5",
                "maximumNumberOfReplacedSolutions=5", "aggregation=wsum", "normalizeObjectives=true",
                "crossover=blxAlpha", "mutation=random", "mutation.probability=3/n");

        assertEquals(List.of(), AlgorithmConfig.fixedDuringRun(moead, next), "nothing fixed changes");
    }

    @Test
    void changingTheMoeadPopulationWeightsAndNeighborhoodIsForbidden() {
        AlgorithmConfig moead = parse("algorithm=moead", "maxEvaluations=1000");
        AlgorithmConfig next = parse("algorithm=moead", "maxEvaluations=1000", "populationSize=91",
                "weights=lattice", "neighborSize=10");

        assertEquals(List.of("populationSize cannot change during a run (100 to 91)",
                "weights cannot change during a run (spread to lattice)",
                "neighborSize cannot change during a run (20 to 10)"),
                AlgorithmConfig.fixedDuringRun(moead, next), "the subproblems and their neighborhoods are fixed");
    }

    @Test
    void changingFromNsgaiiToMoeadNamesBothAlgorithms() {
        AlgorithmConfig nsgaii = parse("algorithm=nsgaii", "maxEvaluations=1000");
        AlgorithmConfig next = parse("algorithm=moead", "maxEvaluations=1000");

        assertEquals(List.of("algorithm cannot change during a run (nsgaii to moead)"),
                AlgorithmConfig.fixedDuringRun(nsgaii, next), "both algorithms are named");
    }

    // ── Descriptions ──────────────────────────────────────────────────────────

    @Test
    void anNsgaiiDescriptionListsTheOperators() {
        String description = parse("algorithm=nsgaii", "maxEvaluations=1000", "tracesFolder=traces").describe();

        assertEquals("NSGA-II, 1000 evaluations, population 100, crossover sbx (probability 0.9, distributionIndex 20), "
                + "mutation polynomial (probability 0.03333, distributionIndex 20), traces in traces", description,
                "every value the run uses");
    }

    @Test
    void aPaesDescriptionListsTheMutationAndThePaesSettings() {
        String description = parse("algorithm=paes", "maxEvaluations=1000", "mutation=random",
                "mutation.probability=0.1", "result=external").describe();

        assertEquals("PAES, 1000 evaluations, archive 100, mutation random (probability 0.1), "
                + "archive selection probability 0, result external, no traces", description, "every value the run uses");
    }

    @Test
    void aMoeadDescriptionListsTheNeighborhoodTheAggregationAndTheOperators() {
        String description = parse("algorithm=moead", "maxEvaluations=1000", "tracesFolder=traces").describe();

        assertEquals("MOEA/D, 1000 evaluations, population 100, weights spread, neighborhood 20 (selection "
                + "probability 0.9, at most 2 replaced), aggregation tchebycheff, crossover sbx (probability 0.9, "
                + "distributionIndex 20), mutation polynomial (probability 0.03333, distributionIndex 20), traces in traces",
                description, "every value the run uses");
    }

    @Test
    void aMoeadDescriptionMentionsTheLatticeAndTheNormalization() {
        String description = parse("algorithm=moead", "maxEvaluations=1000", "populationSize=91",
                "weights=lattice", "aggregation=pbi", "normalizeObjectives=true").describe();

        assertTrue(description.contains("weights lattice") && description.contains("aggregation pbi of normalized objectives"),
                "the weights and the normalization are shown: " + description);
    }

    @Test
    void aDescriptionShowsABetaJustBelowTwoAsItIs() {
        String description = parse("algorithm=paes", "maxEvaluations=1000", "mutation=levyFlight",
                "mutation.beta=1.99999").describe();

        assertTrue(description.contains("beta 1.99999"), "never the forbidden beta 2: " + description);
    }

    @ParameterizedTest
    @CsvSource({"0.9, 0.9", "20, 20", "0, 0", "0.5, 0.5", "1.99999, 1.99999", "3.00001, 3.00001",
            "2.00001, 2.00001", "1.9999999, 1.9999999", "12345.678, 12345.678", "1e-7, 0.0000001"})
    void formatWritesAValueThatWouldRoundToARounderOneInFull(double value, String expected) {
        assertEquals(expected, OperatorConfig.format(value), "a short or a nearly round value is never rounded");
    }

    @Test
    void formatRoundsALongValueToFourSignificantDigits() {
        assertAll(
                () -> assertEquals("0.03333", OperatorConfig.format(1.0 / 30), "1/30"),
                () -> assertEquals("0.1429", OperatorConfig.format(1.0 / 7), "1/7"),
                () -> assertEquals("1.333", OperatorConfig.format(40.0 / 30), "40/30"),
                () -> assertEquals("-0.6667", OperatorConfig.format(-2.0 / 3), "-2/3"));
    }

    // ── Integer, binary and composite problems ────────────────────────────────

    /** Four integer variables. */
    static final SolutionLayout INTEGERS = new SolutionLayout(List.of(new Segment(null, Encoding.INT, 4)));

    /** Binary variables of 80 bits in all, as ZDT5. */
    static final SolutionLayout BITS = new SolutionLayout(List.of(new Segment(null, Encoding.BINARY, 80)));

    /** The segments of {@link TestProblems#mixed()}: ints (3 variables), reals (2) and bits (5 + 3 bits). */
    static final SolutionLayout MIXED = new SolutionLayout(List.of(new Segment("ints", Encoding.INT, 3),
            new Segment("reals", Encoding.DOUBLE, 2), new Segment("bits", Encoding.BINARY, 8)));

    static AlgorithmConfig parse(SolutionLayout layout, String... entries) {
        return AlgorithmConfigParser.parse(properties(entries), layout);
    }

    static List<SegmentOperators> segments(AlgorithmConfig config) {
        return config.variation().segments();
    }

    @Test
    void anIntegerProblemTakesTheIntegerCatalogueUnderTheKeysOfAFlatProblem() {
        var config = assertInstanceOf(NSGAIIConfig.class, parse(INTEGERS, "algorithm=nsgaii", "maxEvaluations=1000"));
        SegmentOperators operators = config.variation().single();

        assertAll(
                () -> assertEquals(new OperatorConfig<>(IntegerCrossoverType.SBX, 0.9, Map.of("distributionIndex", 20.0)),
                        operators.crossover(), "default crossover: the rounding SBX, 0.9 / 20"),
                () -> assertEquals(new OperatorConfig<>(IntegerMutationType.POLYNOMIAL, 0.25,
                        Map.of("distributionIndex", 20.0)), operators.mutation(), "default mutation: polynomial 1/n / 20"),
                () -> assertEquals(INTEGERS, config.layout(), "read for the layout"));
    }

    @Test
    void anIntegerProblemReadsEveryKeyOfItsOperators() {
        var config = assertInstanceOf(MOEADConfig.class, parse(INTEGERS, "algorithm=moead", "maxEvaluations=1000",
                "crossover=blxAlpha", "crossover.probability=0.8", "crossover.alpha=0.3", "mutation=gaussian",
                "mutation.probability=2/n"));
        SegmentOperators operators = config.variation().single();

        assertAll(
                () -> assertEquals(new OperatorConfig<>(IntegerCrossoverType.BLX_ALPHA, 0.8, Map.of("alpha", 0.3)),
                        operators.crossover(), "crossover keys"),
                () -> assertEquals(new OperatorConfig<>(IntegerMutationType.GAUSSIAN, 0.5, Map.of()), operators.mutation(),
                        "2/n over 4 variables"));
    }

    @Test
    void aBinaryProblemDefaultsToSinglePointCrossoverAndOneBitFlipPerMutation() {
        var config = assertInstanceOf(NSGAIIConfig.class, parse(BITS, "algorithm=nsgaii", "maxEvaluations=1000"));
        SegmentOperators operators = config.variation().single();

        assertAll(
                () -> assertEquals(new OperatorConfig<>(BinaryCrossoverType.SINGLE_POINT, 0.9, Map.of()),
                        operators.crossover(), "single-point crossover with probability 0.9"),
                () -> assertEquals(new OperatorConfig<>(BinaryMutationType.BIT_FLIP, 1.0 / 80, Map.of()),
                        operators.mutation(), "1/n over 80 bits"));
    }

    @Test
    void kOverNCountsTheBitsOfABinaryProblem() {
        var config = assertInstanceOf(NSGAIIConfig.class, parse(BITS, "algorithm=nsgaii", "maxEvaluations=1000",
                "crossover=hux", "crossover.probability=40/n", "mutation=BitFlip", "mutation.probability=4/n"));
        SegmentOperators operators = config.variation().single();

        assertAll(
                () -> assertEquals(new OperatorConfig<>(BinaryCrossoverType.HUX, 0.5, Map.of()), operators.crossover(),
                        "40 over 80 bits"),
                () -> assertEquals(new OperatorConfig<>(BinaryMutationType.BIT_FLIP, 0.05, Map.of()), operators.mutation(),
                        "4 over 80 bits, the name in another case"));
    }

    @Test
    void aCompositeGivesEverySegmentTheDefaultsOfItsEncodingWithNOverTheSegment() {
        List<SegmentOperators> segments = segments(parse(MIXED, "algorithm=nsgaii", "maxEvaluations=1000"));

        assertAll(
                () -> assertEquals(MIXED.segments(), segments.stream().map(SegmentOperators::segment).toList(),
                        "one entry per segment, in order"),
                () -> assertEquals(new OperatorConfig<>(IntegerCrossoverType.SBX, 0.9, Map.of("distributionIndex", 20.0)),
                        segments.get(0).crossover(), "ints: the rounding SBX"),
                () -> assertEquals(new OperatorConfig<>(IntegerMutationType.POLYNOMIAL, 1.0 / 3,
                        Map.of("distributionIndex", 20.0)), segments.get(0).mutation(), "ints: 1/n over 3 variables"),
                () -> assertEquals(new OperatorConfig<>(CrossoverType.SBX, 0.9, Map.of("distributionIndex", 20.0)),
                        segments.get(1).crossover(), "reals: SBX"),
                () -> assertEquals(new OperatorConfig<>(MutationType.POLYNOMIAL, 0.5, Map.of("distributionIndex", 20.0)),
                        segments.get(1).mutation(), "reals: 1/n over 2 variables"),
                () -> assertEquals(new OperatorConfig<>(BinaryCrossoverType.SINGLE_POINT, 0.9, Map.of()),
                        segments.get(2).crossover(), "bits: single-point crossover"),
                () -> assertEquals(new OperatorConfig<>(BinaryMutationType.BIT_FLIP, 0.125, Map.of()),
                        segments.get(2).mutation(), "bits: 1/n over 8 bits"));
    }

    @Test
    void aCompositeReadsTheKeysOfEachSegmentUnderItsName() {
        List<SegmentOperators> segments = segments(parse(MIXED, "algorithm=nsgaii", "maxEvaluations=1000",
                "ints.crossover=blxAlpha", "ints.crossover.alpha=0.3", "ints.mutation=random",
                "ints.mutation.probability=0.1", "reals.crossover=laplace", "reals.crossover.scale=0.2",
                "reals.mutation=levyRandom", "reals.mutation.probability=1/n", "bits.crossover=uniform",
                "bits.crossover.probability=0.7", "bits.mutation.probability=2/n"));

        assertAll(
                () -> assertEquals(new OperatorConfig<>(IntegerCrossoverType.BLX_ALPHA, 0.9, Map.of("alpha", 0.3)),
                        segments.get(0).crossover(), "ints crossover"),
                () -> assertEquals(new OperatorConfig<>(IntegerMutationType.RANDOM, 0.1, Map.of()),
                        segments.get(0).mutation(), "ints mutation"),
                () -> assertEquals(new OperatorConfig<>(CrossoverType.LAPLACE, 0.9, Map.of("scale", 0.2)),
                        segments.get(1).crossover(), "reals crossover"),
                () -> assertEquals(new OperatorConfig<>(MutationType.LEVY_RANDOM, 0.5, Map.of("beta", 1.5, "stepSize", 0.01)),
                        segments.get(1).mutation(), "reals mutation"),
                () -> assertEquals(new OperatorConfig<>(BinaryCrossoverType.UNIFORM, 0.7, Map.of()),
                        segments.get(2).crossover(), "bits crossover"),
                () -> assertEquals(new OperatorConfig<>(BinaryMutationType.BIT_FLIP, 0.25, Map.of()),
                        segments.get(2).mutation(), "bits mutation: 2 over 8 bits"));
    }

    @Test
    void aKeyWithoutTheNameOfASegmentIsUnknownInAComposite() {
        var exception = assertThrows(InvalidConfigurationException.class,
                () -> parse(MIXED, "algorithm=nsgaii", "maxEvaluations=1000", "mutation=random"));

        assertEquals("unknown keys for nsgaii with these operators: mutation. Valid keys: algorithm, maxEvaluations, "
                + "populationSize, ints.crossover, ints.crossover.probability, ints.crossover.distributionIndex, "
                + "ints.mutation, ints.mutation.probability, ints.mutation.distributionIndex, reals.crossover, "
                + "reals.crossover.probability, reals.crossover.distributionIndex, reals.mutation, "
                + "reals.mutation.probability, reals.mutation.distributionIndex, bits.crossover, bits.crossover.probability, "
                + "bits.mutation, bits.mutation.probability, tracesFolder", exception.getMessage(),
                "a file serves flat problems or one composite layout: the valid keys follow the segments");
    }

    @Test
    void paesOnACompositeReadsOnlyMutationsAndCountsEveryValueOutsideTheOperators() {
        var config = assertInstanceOf(PAESConfig.class, parse(MIXED, "algorithm=paes", "maxEvaluations=1000",
                "ints.mutation=random", "archiveSelectionProbability=1/n"));
        var exception = assertThrows(InvalidConfigurationException.class,
                () -> parse(MIXED, "algorithm=paes", "maxEvaluations=1000", "bits.crossover=hux"));

        assertAll(
                () -> assertTrue(segments(config).stream().allMatch(operators -> operators.crossover() == null),
                        "PAES does not cross"),
                () -> assertEquals(IntegerMutationType.RANDOM, segments(config).getFirst().mutation().type(), "ints"),
                () -> assertEquals(1.0 / 13, config.archiveSelectionProbability(),
                        "1/n over every value: 3 integers, 2 reals and 8 bits"),
                () -> assertTrue(exception.getMessage().startsWith("unknown keys for paes with these operators: "
                        + "bits.crossover."), "a crossover key is unknown: " + exception.getMessage()));
    }

    @Test
    void moeadOnACompositeCountsEveryValueInItsNeighborhoodProbability() {
        var config = assertInstanceOf(MOEADConfig.class, parse(MIXED, "algorithm=moead", "maxEvaluations=1000",
                "neighborhoodSelectionProbability=13/n"));

        assertEquals(1.0, config.neighborhoodSelectionProbability(), "13 over the 13 values of the layout");
    }

    static Stream<Arguments> wrongOperatorsOfOtherEncodings() {
        return Stream.of(
                Arguments.of(BITS, List.of("crossover=sbx"),
                        "crossover must be one of singlePoint, hux, uniform (binary variables), got 'sbx'"),
                Arguments.of(BITS, List.of("mutation=polynomial"),
                        "mutation must be one of bitFlip (binary variables), got 'polynomial'"),
                Arguments.of(BITS, List.of("mutation.probability=81/n"),
                        "mutation.probability must be in [0, 1] or k/n, got '81/n' (1.0125)"),
                Arguments.of(INTEGERS, List.of("crossover=nPoint"),
                        "crossover must be one of sbx, blxAlpha (integer variables), got 'nPoint'"),
                Arguments.of(INTEGERS, List.of("mutation=levyFlight"),
                        "mutation must be one of polynomial, random, gaussian (integer variables), got 'levyFlight'"),
                Arguments.of(MIXED, List.of("ints.mutation=levyFlight"),
                        "ints.mutation must be one of polynomial, random, gaussian (integer variables), got 'levyFlight'"),
                Arguments.of(MIXED, List.of("reals.crossover=hux"),
                        "reals.crossover must be one of sbx, blxAlpha, laplace, arithmetic, wholeArithmetic, nPoint "
                                + "(real variables), got 'hux'"),
                Arguments.of(MIXED, List.of("bits.mutation=random"),
                        "bits.mutation must be one of bitFlip (binary variables), got 'random'"),
                Arguments.of(MIXED, List.of("bits.mutation.probability=9/n"),
                        "bits.mutation.probability must be in [0, 1] or k/n, got '9/n' (1.125)"),
                Arguments.of(MIXED, List.of("ints.mutation.probability=4/n"),
                        "ints.mutation.probability must be in [0, 1] or k/n, got '4/n' (1.333)"),
                Arguments.of(MIXED, List.of("reals.crossover=nPoint"),
                        "reals.crossover.points: the number of points (2) must be smaller than the number of variables (2)"),
                Arguments.of(MIXED, List.of("reals.crossover=nPoint", "reals.crossover.points=1", "reals.crossover.blockSize=2"),
                        "reals.crossover.blockSize: an n-point crossover needs at least 2 blocks of 2 variables to cut "
                                + "between, got 2 variables"),
                Arguments.of(MIXED, List.of("reals.mutation=uniform", "reals.mutation.perturbation=0"),
                        "reals.mutation.perturbation must be greater than 0, got '0'"));
    }

    @ParameterizedTest
    @MethodSource("wrongOperatorsOfOtherEncodings")
    void aWrongOperatorOrValueForASegmentIsRejectedNamingItsKeyAndEncoding(SolutionLayout layout, List<String> entries,
            String message) {
        List<String> all = Stream.concat(Stream.of("algorithm=nsgaii", "maxEvaluations=1000"), entries.stream()).toList();

        var exception = assertThrows(InvalidConfigurationException.class,
                () -> parse(layout, all.toArray(String[]::new)), "the configuration must be rejected: " + all);

        assertEquals(message, exception.getMessage(), "the key of the segment, and the size or encoding it counts");
    }

    @Test
    void aParameterOfARealOperatorIsUnknownForABinaryProblem() {
        var exception = assertThrows(InvalidConfigurationException.class,
                () -> parse(BITS, "algorithm=nsgaii", "maxEvaluations=1000", "crossover.distributionIndex=20"));

        assertTrue(exception.getMessage().startsWith("unknown keys for nsgaii with these operators: "
                + "crossover.distributionIndex. Valid keys: algorithm, maxEvaluations, populationSize, crossover, "
                + "crossover.probability, mutation, mutation.probability, tracesFolder"),
                "single-point crossover has no parameters: " + exception.getMessage());
    }

    @Test
    void aCompositeDescriptionListsTheOperatorsOfEachSegment() {
        String nsgaii = parse(MIXED, "algorithm=nsgaii", "maxEvaluations=1000", "ints.mutation=random",
                "ints.mutation.probability=0.1").describe();
        String paes = parse(MIXED, "algorithm=paes", "maxEvaluations=1000", "tracesFolder=traces").describe();

        assertAll(
                () -> assertEquals("NSGA-II, 1000 evaluations, population 100, ints [crossover sbx (probability 0.9, "
                        + "distributionIndex 20), mutation random (probability 0.1)], reals [crossover sbx (probability 0.9, "
                        + "distributionIndex 20), mutation polynomial (probability 0.5, distributionIndex 20)], bits "
                        + "[crossover singlePoint (probability 0.9), mutation bitFlip (probability 0.125)], no traces",
                        nsgaii, "every segment with its operators"),
                () -> assertEquals("PAES, 1000 evaluations, archive 100, ints [mutation polynomial (probability 0.3333, "
                        + "distributionIndex 20)], reals [mutation polynomial (probability 0.5, distributionIndex 20)], "
                        + "bits [mutation bitFlip (probability 0.125)], archive selection probability 0, result paes, "
                        + "traces in traces", paes, "PAES has only mutations"));
    }

    static Stream<AlgorithmConfig> configurationsThatAreNotRealCoded() {
        return Stream.of(parse(MIXED, "algorithm=nsgaii", "maxEvaluations=1000"),
                parse(MIXED, "algorithm=paes", "maxEvaluations=1000"),
                parse(MIXED, "algorithm=moead", "maxEvaluations=1000"),
                parse(INTEGERS, "algorithm=nsgaii", "maxEvaluations=1000"),
                parse(BITS, "algorithm=paes", "maxEvaluations=1000"),
                parse(INTEGERS, "algorithm=moead", "maxEvaluations=1000"));
    }

    @ParameterizedTest
    @MethodSource("configurationsThatAreNotRealCoded")
    void theAccessorsOf12AreOnlyForARealCodedProblem(AlgorithmConfig config) {
        Executable mutation = switch (config) {
            case NSGAIIConfig nsgaii -> nsgaii::mutation;
            case PAESConfig paes -> paes::mutation;
            case MOEADConfig moead -> moead::mutation;
        };

        var exception = assertThrows(IllegalStateException.class, mutation, "use variation() instead");

        assertTrue(exception.getMessage().startsWith(config.layout().composite()
                        ? "a composite problem has the operators of each segment: ints ["
                        : "the operators are those of "), exception.getMessage());
    }

    @Test
    void theAccessorsOf12NameTheEncodingOfAFlatProblem() {
        var config = assertInstanceOf(NSGAIIConfig.class, parse(INTEGERS, "algorithm=nsgaii", "maxEvaluations=1000"));

        var exception = assertThrows(IllegalStateException.class, config::crossover);

        assertEquals("the operators are those of integer variables, not of real ones: use variation()",
                exception.getMessage(), "the encoding of the segment");
    }

    @Test
    void theConstructorsOf12BuildTheConfigurationOfARealCodedProblem() {
        var parsed = assertInstanceOf(MOEADConfig.class, parse("algorithm=moead", "maxEvaluations=1000"));
        var paesParsed = assertInstanceOf(PAESConfig.class, parse("algorithm=paes", "maxEvaluations=1000"));

        var nsgaii = new NSGAIIConfig(1000, 100, parsed.crossover(), parsed.mutation(), "traces");
        var paes = new PAESConfig(1000, 100, paesParsed.mutation(), 0.0, PAES.ResultSource.PAES_ARCHIVE, null);
        var moead = new MOEADConfig(1000, 100, MOEADWeights.Method.SPREAD, 20, 0.9, 2,
                MOEAD.AggregationFunction.TCHEBYCHEFF, false, parsed.crossover(), parsed.mutation(), null);

        assertAll(
                () -> assertSame(parsed.crossover(), nsgaii.crossover(), "the crossover given"),
                () -> assertSame(parsed.mutation(), nsgaii.mutation(), "the mutation given"),
                () -> assertEquals("NSGA-II, 1000 evaluations, population 100, crossover sbx (probability 0.9, "
                        + "distributionIndex 20), mutation polynomial (probability 0.03333, distributionIndex 20), "
                        + "traces in traces", nsgaii.describe(), "described as in 1.2"),
                () -> assertEquals(paesParsed.describe(), paes.describe(), "PAES, without a crossover"),
                () -> assertNull(segments(paes).getFirst().crossover(), "PAES has no crossover"),
                () -> assertEquals(parsed.describe(), moead.describe(), "MOEA/D"),
                () -> assertEquals(SolutionLayout.real(1), moead.layout(),
                        "one real segment, whose size the record does not know"));
    }

    @Test
    void theConstructorsOf12NeedAMutation() {
        var parsed = assertInstanceOf(NSGAIIConfig.class, parse("algorithm=nsgaii", "maxEvaluations=1000"));

        var exception = assertThrows(NullPointerException.class,
                () -> new NSGAIIConfig(1000, 100, parsed.crossover(), null, null), "a record of 1.2 accepted it");

        assertEquals("mutation must not be null", exception.getMessage(), "as every segment of a Variation");
    }

    @Test
    void aConfigurationReadForANumberOfVariablesHasTheLayoutOfARealCodedProblem() {
        assertEquals(SolutionLayout.real(VARIABLES), parse("algorithm=nsgaii", "maxEvaluations=1000").layout(),
                "one unnamed real segment of 30 variables");
    }

    @Test
    void loadingForAProblemOfAnyEncodingReadsTheLayoutOfItsSolutions() throws IOException {
        Path file = write(folder, "run.properties", "algorithm=nsgaii\nmaxEvaluations=1000\nreal.mutation.probability=2/n\n");

        AlgorithmConfig mixedIntegerDouble = AlgorithmConfig.load(file, List.of(), new MixedIntegerDoubleProblem());
        AlgorithmConfig binary = AlgorithmConfig.parseText("algorithm=paes\nmaxEvaluations=1000\n", new ZDT5());

        assertAll(
                () -> assertEquals(SolutionLayout.of(new MixedIntegerDoubleProblem()), mixedIntegerDouble.layout(),
                        "segments integer and real"),
                () -> assertEquals(0.2, segments(mixedIntegerDouble).get(1).mutation().probability(),
                        "2 over the 10 variables of the real segment"),
                () -> assertEquals(BITS, binary.layout(), "the 80 bits of ZDT5"));
    }

    @Test
    void aRealCodedProblemOfAnyStaticTypeReadsAsIn12AndCreatesNoSolution() throws IOException {
        Path file = write(folder, "run.properties", "algorithm=nsgaii\nmaxEvaluations=1000\nmutation.probability=3/n\n");
        Problem<DoubleSolution> problem = new ZDT1();

        AlgorithmConfig config = AlgorithmConfig.load(file, List.of(), problem);

        assertAll(
                () -> assertEquals(AlgorithmConfig.load(file, List.of(), new ZDT1()), config,
                        "the configuration of the DoubleProblem overload"),
                () -> assertTrue(TestProblems.drawsNoRandomNumber(() -> AlgorithmConfig.load(file, List.of(), problem)),
                        "no solution is created"),
                () -> assertTrue(TestProblems.drawsNoRandomNumber(
                        () -> AlgorithmConfig.parseText("algorithm=paes\nmaxEvaluations=10\n", new ZDT1())),
                        "nor by the DoubleProblem overloads"));
    }

    @Test
    void latticeWeightsAreCheckedForAProblemOfAnyEncoding() {
        String text = "algorithm=moead\nmaxEvaluations=100\nweights=lattice\n";
        var threeObjectives = new TestProblems.FromFactory<>("ThreeObjectives", 3, () -> TestProblems.integers(4, 0, 10));

        var exception = assertThrows(InvalidConfigurationException.class, () -> AlgorithmConfig.parseText(text, threeObjectives));

        assertAll(
                () -> assertEquals("populationSize: LATTICE weights for 3 objectives need C(H+2, 2) vectors, such as 91 "
                        + "or 105, got 100 (spread weights take any size)", exception.getMessage(), "the closest lattice sizes"),
                () -> assertInstanceOf(MOEADConfig.class, AlgorithmConfig.parseText(text, INTEGERS),
                        "the layout alone does not check the lattice"));
    }

    @Test
    void aProblemWhoseSolutionsCannotBeConfiguredIsNoConfigurationError() {
        var problem = new TestProblems.FromFactory<>("Permutation", () -> new IntegerPermutationSolution(4, 2, 0));

        var exception = assertThrows(IllegalArgumentException.class,
                () -> AlgorithmConfig.parseText("algorithm=nsgaii\nmaxEvaluations=100\n", problem));

        assertAll(
                () -> assertFalse(exception instanceof InvalidConfigurationException, "the problem is at fault, not the text"),
                () -> assertTrue(exception.getMessage().startsWith("cannot configure the operators of Permutation: "),
                        exception.getMessage()));
    }
}
