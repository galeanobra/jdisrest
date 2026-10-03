package es.unex.jdisrest.config;

import es.unex.jdisrest.config.AlgorithmConfig.PAESConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Configuration provenance: the copy of the starting configuration and the log of a run, the
 * changes made while it runs, a run started from its own copy, and the effective text with one
 * active line per key that {@code GET /api/v1/config} serves.
 */
class ConfigHistoryTest {

    private static final int VARIABLES = 30;
    private static final String TIME = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}";

    @TempDir
    Path folder;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** The traces folder of every file written by {@link #paes}. */
    private Path traces() {
        return folder.resolve("traces");
    }

    /** A PAES configuration file in {@code where}, whose traces go to {@link #traces()}. */
    private Path paes(Path where, String extraLines) throws IOException {
        Files.createDirectories(where);
        Path file = where.resolve("algorithm.properties");
        String tracesFolder = traces().toString().replace("\\", "/");
        Files.writeString(file, "algorithm = paes\nmaxEvaluations = 1000\ntracesFolder = " + tracesFolder + "\n"
                + extraLines, StandardCharsets.UTF_8);
        return file;
    }

    private List<String> log() throws IOException {
        return Files.readAllLines(traces().resolve(ConfigHistory.LOG_FILE), StandardCharsets.UTF_8);
    }

    /** The configuration a text gives, read as the run reads it. */
    static AlgorithmConfig parse(String text) {
        return AlgorithmConfig.parseText(text, VARIABLES);
    }

    /** The lines of a text that are not comments and set {@code key} with any separator. */
    static long activeLines(String text, String key) {
        Pattern setsKey = Pattern.compile("[ \\t\\f]*" + Pattern.quote(key) + "[ \\t\\f]*[=: \\t\\f].*");
        return text.lines().filter(line -> setsKey.matcher(line).matches()).count();
    }

    static Map<String, String> asMap(Properties properties) {
        Map<String, String> map = new HashMap<>();
        properties.stringPropertyNames().forEach(key -> map.put(key, properties.getProperty(key)));
        return map;
    }

    // ── Starting a run ────────────────────────────────────────────────────────

    @Test
    void theStartIsCopiedWithItsOverridesAndLoggedUnderARunHeader() throws IOException {
        Path file = paes(folder, "");
        List<String> overrides = List.of("maxEvaluations=40");
        AlgorithmConfig config = AlgorithmConfig.load(file, overrides, VARIABLES);
        var history = ConfigHistory.of(file, overrides, config);

        Path copy = history.recordStart(config);

        assertAll(
                () -> assertEquals(traces().resolve("algorithm.properties"), copy, "the copy keeps the name"),
                () -> assertEquals(history.current(), Files.readString(copy, StandardCharsets.UTF_8),
                        "the copy is the configuration in use"),
                () -> assertEquals(config, AlgorithmConfig.load(copy, List.of(), VARIABLES),
                        "reading the copy gives the configuration of the run"),
                () -> assertEquals(2, log().size(), "a header and the line of the copy: " + log()),
                () -> assertTrue(log().get(0).matches("# Run started " + TIME + " from .*algorithm\\.properties "
                        + "with overrides maxEvaluations=40"), log().get(0)),
                () -> assertTrue(log().get(1).matches(
                        TIME + "  evaluations 0 +algorithm\\.properties +PAES, 40 evaluations, .*"),
                        log().get(1)));
    }

    @Test
    void withoutATracesFolderNothingIsWrittenButTheConfigurationIsKept() throws IOException {
        Path file = folder.resolve("algorithm.properties");
        Files.writeString(file, "algorithm=paes\nmaxEvaluations=1000\n", StandardCharsets.UTF_8);
        AlgorithmConfig config = AlgorithmConfig.load(file, List.of(), VARIABLES);
        var history = ConfigHistory.of(file, List.of(), config);
        String next = "algorithm=paes\nmaxEvaluations=9000\n";

        Path copy = history.recordStart(config);
        String startText = history.current();
        Path saved = history.recordChange(next, 10, parse(next));

        assertAll(
                () -> assertNull(copy, "no copy without a traces folder"),
                () -> assertNull(saved, "no saved change without a traces folder"),
                () -> assertEquals("algorithm=paes\nmaxEvaluations=1000\n", startText, "the starting text is kept"),
                () -> assertEquals(next, history.current(), "the change is the configuration in use"),
                () -> assertTrue(Files.notExists(traces()), "nothing is written"));
    }

    @Test
    void everyRunThatReusesTheFolderStartsWithItsOwnHeader() throws IOException {
        Path file = paes(folder, "");
        AlgorithmConfig config = AlgorithmConfig.load(file, List.of(), VARIABLES);

        ConfigHistory.of(file, List.of(), config).recordStart(config);
        ConfigHistory.of(file, List.of(), config).recordStart(config);

        List<String> log = log();
        assertAll(
                () -> assertEquals(5, log.size(), "header, copy, blank line, header, copy: " + log),
                () -> assertTrue(log.get(0).matches("# Run started " + TIME + " from .* without overrides"), log.get(0)),
                () -> assertEquals("", log.get(2), "a blank line separates the runs"),
                () -> assertTrue(log.get(3).startsWith("# Run started "), "the second run has its header: " + log.get(3)));
    }

    // ── A run started from its own copy ───────────────────────────────────────

    @Test
    void aRunStartedFromItsCopyWithOverridesRecordsThemInAZeroFile() throws IOException {
        Path file = paes(traces(), "");
        String original = Files.readString(file, StandardCharsets.UTF_8);
        List<String> overrides = List.of("maxEvaluations=40");
        AlgorithmConfig config = AlgorithmConfig.load(file, overrides, VARIABLES);

        Path copy = ConfigHistory.of(file, overrides, config).recordStart(config);

        assertAll(
                () -> assertEquals(traces().resolve("algorithm_0.properties"), copy, "the copy gets the _0 suffix"),
                () -> assertEquals(40, AlgorithmConfig.load(copy, List.of(), VARIABLES).maxEvaluations(),
                        "the copy has the overrides"),
                () -> assertEquals(original, Files.readString(file, StandardCharsets.UTF_8),
                        "the file the run started from is not rewritten"),
                () -> assertTrue(log().get(1).contains("algorithm_0.properties"), "the log names the copy: " + log().get(1)));
    }

    @Test
    void aRunStartedFromItsCopyWithoutOverridesWritesNoOtherFile() throws IOException {
        Path file = paes(traces(), "");
        AlgorithmConfig config = AlgorithmConfig.load(file, List.of(), VARIABLES);

        Path copy = ConfigHistory.of(file, List.of(), config).recordStart(config);

        assertAll(
                () -> assertEquals(file, copy, "the file is its own copy"),
                () -> assertTrue(Files.notExists(traces().resolve("algorithm_0.properties")), "no _0 file"),
                () -> assertTrue(log().get(1).contains(" algorithm.properties "), "the log names the file: " + log().get(1)));
    }

    @Test
    void aSecondRunFromTheSameCopyKeepsTheFirstZeroFile() throws IOException {
        Path file = paes(traces(), "");
        AlgorithmConfig first = AlgorithmConfig.load(file, List.of("maxEvaluations=40"), VARIABLES);
        AlgorithmConfig second = AlgorithmConfig.load(file, List.of("maxEvaluations=50"), VARIABLES);

        ConfigHistory.of(file, List.of("maxEvaluations=40"), first).recordStart(first);
        Path copy = ConfigHistory.of(file, List.of("maxEvaluations=50"), second).recordStart(second);

        assertAll(
                () -> assertEquals(traces().resolve("algorithm_0_2.properties"), copy, "a new suffix for the new run"),
                () -> assertEquals(40, AlgorithmConfig.load(traces().resolve("algorithm_0.properties"), List.of(),
                        VARIABLES).maxEvaluations(), "the record of the first run is kept"),
                () -> assertEquals(50, AlgorithmConfig.load(copy, List.of(), VARIABLES).maxEvaluations(),
                        "the record of the second run"));
    }

    // ── Changes during a run ──────────────────────────────────────────────────

    @Test
    void aChangeIsSavedUnderItsEvaluationCountAndLogged() throws IOException {
        Path file = paes(folder, "");
        AlgorithmConfig config = AlgorithmConfig.load(file, List.of(), VARIABLES);
        var history = ConfigHistory.of(file, List.of(), config);
        history.recordStart(config);
        String text = "algorithm=paes\nmaxEvaluations=9000\n";

        Path saved = history.recordChange(text, 2300, parse(text));

        assertAll(
                () -> assertEquals(traces().resolve("algorithm_2300.properties"), saved, "named after the count"),
                () -> assertEquals(text, Files.readString(saved, StandardCharsets.UTF_8), "the text as applied"),
                () -> assertEquals(text, history.current(), "the change is the configuration in use"),
                () -> assertEquals(3, log().size(), "header, start and change: " + log()),
                () -> assertTrue(log().get(2).matches(TIME + "  evaluations 2300 +algorithm_2300\\.properties +PAES, 9000 "
                        + "evaluations, .*"), log().get(2)));
    }

    @Test
    void twoChangesAtTheSameCountKeepBothFiles() throws IOException {
        Path file = paes(folder, "");
        AlgorithmConfig config = AlgorithmConfig.load(file, List.of(), VARIABLES);
        var history = ConfigHistory.of(file, List.of(), config);
        history.recordStart(config);
        String first = "algorithm=paes\nmaxEvaluations=9000\n";
        String second = "algorithm=paes\nmaxEvaluations=8000\n";

        history.recordChange(first, 500, parse(first));
        Path saved = history.recordChange(second, 500, parse(second));

        assertAll(
                () -> assertEquals(traces().resolve("algorithm_500_2.properties"), saved, "a suffix for the second"),
                () -> assertEquals(first, Files.readString(traces().resolve("algorithm_500.properties"),
                        StandardCharsets.UTF_8), "the first change is kept"),
                () -> assertEquals(second, Files.readString(saved, StandardCharsets.UTF_8), "the second change"),
                () -> assertEquals(4, log().size(), "header, start and two changes: " + log()));
    }

    @Test
    void aChangeThatSetsAKeyTwiceIsRecordedWithOneActiveLine() throws IOException {
        Path file = paes(folder, "");
        AlgorithmConfig config = AlgorithmConfig.load(file, List.of(), VARIABLES);
        var history = ConfigHistory.of(file, List.of(), config);
        String text = "algorithm=paes\nmaxEvaluations=9000\nmaxEvaluations=8000\n";

        Path saved = history.recordChange(text, 100, parse(text));

        assertAll(
                () -> assertEquals("algorithm=paes\n# overridden by a later line: maxEvaluations=9000\nmaxEvaluations=8000\n",
                        history.current(), "the earlier line is commented out"),
                () -> assertEquals(history.current(), Files.readString(saved, StandardCharsets.UTF_8),
                        "the saved file is the text in use"),
                () -> assertEquals(8000, parse(history.current()).maxEvaluations(), "the same configuration"));
    }

    // ── One active line per key ───────────────────────────────────────────────

    @Test
    void anOverriddenLineIsCommentedOutAndTheOverrideAppended() throws IOException {
        Path file = paes(folder, "archiveSize = 50\n");
        List<String> overrides = List.of("maxEvaluations=40");
        var history = ConfigHistory.of(file, overrides, AlgorithmConfig.load(file, overrides, VARIABLES));

        String text = history.current();

        assertAll(
                () -> assertTrue(text.contains("\n# overridden on the command line: maxEvaluations = 1000\n"),
                        "the original line stays visible, commented out: " + text),
                () -> assertTrue(text.endsWith("\nmaxEvaluations = 40\n"), "the override is appended: " + text),
                () -> assertEquals(1, activeLines(text, "maxEvaluations"), "one active line for the key: " + text),
                () -> assertTrue(text.contains("\narchiveSize = 50\n"), "other keys stay as they are: " + text));
    }

    @Test
    void editingTheValueWhereTheKeyAppearsChangesThatKey() throws IOException {
        Path file = paes(folder, "");
        List<String> overrides = List.of("maxEvaluations=40");
        var history = ConfigHistory.of(file, overrides, AlgorithmConfig.load(file, overrides, VARIABLES));

        // GET, edit the only line that sets the key, POST.
        String edited = history.current().replace("maxEvaluations = 40", "maxEvaluations = 200000");

        assertEquals(200000, parse(edited).maxEvaluations(), "the edited value is the one applied");
    }

    @Test
    void keysAreRecognisedWhateverTheirSeparatorAndContinuationLines() {
        String text = "algorithm = paes\nmaxEvaluations:25000\n  archiveSize \\\n    50\n";

        String effective = AlgorithmConfigParser.effectiveText(text, List.of("maxEvaluations=40", "archiveSize=60"));

        var config = assertInstanceOf(PAESConfig.class, parse(effective));
        assertAll(
                () -> assertTrue(effective.startsWith("algorithm = paes\n"
                        + "# overridden on the command line: maxEvaluations:25000\n"
                        + "# overridden on the command line: archiveSize \\\n"
                        + "#    50\n"), "every line of a continued key is commented out: " + effective),
                () -> assertEquals(40, config.maxEvaluations(), "a colon separator"),
                () -> assertEquals(60, config.archiveSize(), "a continued line leaves nothing behind"));
    }

    @Test
    void aContinuedLastLineEndedByACarriageReturnLeavesTheOverridesApart() {
        // Old Mac line ends: the last line continues with a backslash, and \r is its only terminator.
        String text = "algorithm = paes\rmaxEvaluations = 1000\rarchiveSize = 5\\\r";

        String effective = AlgorithmConfigParser.effectiveText(text, List.of("maxEvaluations=40"));

        var config = assertInstanceOf(PAESConfig.class, parse(effective));
        assertAll(
                () -> assertEquals(40, config.maxEvaluations(), "the override is read as its own line: " + effective),
                () -> assertEquals(5, config.archiveSize(), "the continued line ends before the overrides"));
    }

    @Test
    void aKeyWrittenTwiceKeepsOnlyItsLastLineActive() {
        String text = "algorithm=paes\nmaxEvaluations = 100\nmaxEvaluations = 7\n";

        String effective = AlgorithmConfigParser.effectiveText(text, List.of());

        assertAll(
                () -> assertEquals("algorithm=paes\n# overridden by a later line: maxEvaluations = 100\nmaxEvaluations = 7\n",
                        effective, "the earlier line is commented out"),
                () -> assertEquals(7, parse(effective).maxEvaluations(), "the value the file gave"));
    }

    @Test
    void aTextWithoutOverridesOrRepeatedKeysIsUnchanged() {
        String text = "# A comment\r\n\r\nalgorithm = paes\r\n! another comment\r\nmaxEvaluations = 100";

        assertAll(
                () -> assertEquals(text, AlgorithmConfigParser.effectiveText(text, List.of()), "nothing to change"),
                () -> assertEquals(text, AlgorithmConfigParser.effectiveText("\uFEFF" + text, List.of()),
                        "but for the byte order mark"));
    }

    @Test
    void anOverrideGivenTwiceCountsOnce() {
        String effective = AlgorithmConfigParser.effectiveText("algorithm=paes\nmaxEvaluations=100\n",
                List.of("maxEvaluations=40", "maxEvaluations=50"));

        assertAll(
                () -> assertEquals(1, activeLines(effective, "maxEvaluations"), "one active line: " + effective),
                () -> assertEquals(50, parse(effective).maxEvaluations(), "the last override wins"));
    }

    @Test
    void overridesReadBackLiterally() {
        List<String> overrides = List.of("tracesFolder=C:\\runs\\traces", "odd key=#1 = two", "tab=a\tb");

        Properties read = AlgorithmConfigParser.read(AlgorithmConfigParser.effectiveText("", overrides));

        assertEquals(Map.of("tracesFolder", "C:\\runs\\traces", "odd key", "#1 = two", "tab", "a\tb"), asMap(read),
                "escapes make the appended lines read as the overrides were given");
    }
}
