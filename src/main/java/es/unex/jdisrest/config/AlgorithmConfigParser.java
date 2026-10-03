package es.unex.jdisrest.config;

import es.unex.jdisrest.config.AlgorithmConfig.MOEADConfig;
import es.unex.jdisrest.config.AlgorithmConfig.NSGAIIConfig;
import es.unex.jdisrest.config.AlgorithmConfig.PAESConfig;
import es.unex.jdisrest.distributed.algorithms.steadystate.MOEAD;
import es.unex.jdisrest.distributed.algorithms.steadystate.MOEADWeights;
import es.unex.jdisrest.distributed.algorithms.steadystate.PAES;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads an {@link AlgorithmConfig} from properties, and holds the helpers behind the static
 * methods of {@link AlgorithmConfig}.
 *
 * <p>The helpers live here, package-private, rather than in the interface: every static method of
 * an interface is public, and these are implementation details (the raw {@link Properties}
 * parse, the effective text of a file with its overrides, the objectives check) that only this
 * package calls. Every public type of the package ({@link AlgorithmConfig} and its records,
 * {@link OperatorConfig}, {@link OperatorType} and the two catalogues, {@link ConfigHistory},
 * {@link AlgorithmReconfiguration}, {@link ConfiguredMaster} and
 * {@link InvalidConfigurationException}) is public on purpose; the helpers here are not.
 *
 * <p>An instance parses one set of properties once. It remembers every key it looks up, whether
 * the key is present or not, so that any other key can be reported as unknown, together with the
 * list of valid keys in the order they were looked up.
 *
 * @author Francisco Luna (Universidad de Málaga)
 */
final class AlgorithmConfigParser {

    static final int DEFAULT_SIZE = 100;
    static final double DEFAULT_CROSSOVER_PROBABILITY = 0.9;
    static final String PER_VARIABLE = "1/n";
    // MOEA/D defaults of jMetal; the first two are capped by the population and the neighborhood.
    static final int DEFAULT_NEIGHBOR_SIZE = 20;
    static final int DEFAULT_REPLACED_SOLUTIONS = 2;
    static final String DEFAULT_NEIGHBORHOOD_SELECTION_PROBABILITY = "0.9";

    /** Java's UTF-8 decoder keeps it, and {@link Properties} would take it as part of the first key. */
    private static final char BYTE_ORDER_MARK = '\uFEFF';

    /** {@code k/n}, with spaces allowed around the slash: k over the number of variables. */
    private static final Pattern PER_VARIABLES = Pattern.compile("(.*?)\\s*/\\s*n", Pattern.CASE_INSENSITIVE);

    /**
     * A plain decimal number, with an optional exponent. {@link Double#parseDouble} alone would
     * also take Java literals such as {@code 0x1p-2}, {@code 1d} or {@code 1f}.
     */
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");

    /** A Windows drive letter not followed by a separator, as {@code C:\data\runs} reads as {@code C:dataruns}. */
    private static final Pattern DRIVE_RELATIVE = Pattern.compile("[A-Za-z]:(?![/\\\\]).*", Pattern.DOTALL);

    private static final String BACKSLASH_ADVICE = "in a properties file a backslash starts an escape sequence, "
            + "so write Windows paths with / or with doubled backslashes, as C:/runs/traces or C:\\\\runs\\\\traces";

    // Comments of the effective text (see effectiveText).
    private static final String OVERRIDDEN = "# overridden on the command line: ";
    private static final String SUPERSEDED = "# overridden by a later line: ";
    private static final String OVERRIDES_HEADER =
            "# Command-line overrides; the lines of the file they replace are commented out above";

    private final Properties properties;
    private final int numberOfVariables;
    private final Set<String> knownKeys = new LinkedHashSet<>();

    /**
     * @param properties        the properties to parse, not modified
     * @param numberOfVariables number of variables of the problem, which {@code k/n} and the
     *                          operator checks refer to
     * @throws IllegalArgumentException if {@code numberOfVariables} is not positive
     */
    AlgorithmConfigParser(Properties properties, int numberOfVariables) {
        if (numberOfVariables <= 0) {
            throw new IllegalArgumentException("numberOfVariables must be greater than 0: " + numberOfVariables);
        }
        this.properties = properties;
        this.numberOfVariables = numberOfVariables;
    }

    // ── Reading files and texts ───────────────────────────────────────────────

    /**
     * Parses properties already loaded, checking everything that depends on the number of
     * variables.
     *
     * @throws InvalidConfigurationException if a value is missing or wrong, or a key is unknown
     */
    static AlgorithmConfig parse(Properties properties, int numberOfVariables) {
        return new AlgorithmConfigParser(properties, numberOfVariables).parse();
    }

    /**
     * Reads a configuration file as UTF-8, ignoring a leading byte order mark, and applies the
     * overrides to it.
     *
     * @throws InvalidConfigurationException if the file cannot be read, is not UTF-8 or has a
     *                                       malformed escape, or an override is not {@code key=value}
     */
    static Properties read(Path file, List<String> overrides) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            throw new InvalidConfigurationException("cannot read configuration file " + file
                    + ": it is not UTF-8 text; save it with the UTF-8 encoding", e);
        } catch (IOException e) {
            throw new InvalidConfigurationException("cannot read configuration file " + file + ": " + e, e);
        }
        Properties properties = load(text, "configuration file " + file);
        for (String override : overrides) {
            String[] entry = splitOverride(override);
            properties.setProperty(entry[0], entry[1]);
        }
        return properties;
    }

    /**
     * Reads the text of a properties file, ignoring a leading byte order mark.
     *
     * @throws InvalidConfigurationException if the text has a malformed {@code \}{@code uxxxx} escape
     */
    static Properties read(String text) {
        return load(text, "the properties");
    }

    /**
     * The effective text of a configuration file with its overrides (see {@link #effectiveText}).
     *
     * @throws IOException                   if the file cannot be read as UTF-8
     * @throws InvalidConfigurationException if an override is not {@code key=value}
     */
    static String textWithOverrides(Path file, List<String> overrides) throws IOException {
        return effectiveText(Files.readString(file, StandardCharsets.UTF_8), overrides);
    }

    /**
     * Splits {@code key=value} at its first {@code =} into its trimmed key and value.
     *
     * @throws InvalidConfigurationException if there is no {@code =} or nothing before it
     */
    static String[] splitOverride(String override) {
        int separator = override.indexOf('=');
        if (separator <= 0) {
            throw new InvalidConfigurationException("expected key=value, got '" + override + "'");
        }
        return new String[] {override.substring(0, separator).trim(), override.substring(separator + 1).trim()};
    }

    /**
     * Checks what depends on the number of objectives of the problem: the size of the MOEA/D
     * lattice weights.
     *
     * @throws InvalidConfigurationException if the configuration cannot run with that many objectives
     * @throws IllegalArgumentException      if {@code numberOfObjectives} is not positive
     */
    static void checkObjectives(AlgorithmConfig config, int numberOfObjectives) {
        if (numberOfObjectives <= 0) {
            throw new IllegalArgumentException("numberOfObjectives must be greater than 0: " + numberOfObjectives);
        }
        if (config instanceof MOEADConfig moead) {
            String reason = MOEADWeights.check(moead.weights(), numberOfObjectives, moead.populationSize());
            if (reason != null) {
                throw invalid("populationSize: " + reason + " (spread weights take any size)");
            }
        }
    }

    private static Properties load(String text, String source) {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(withoutByteOrderMark(text)));
        } catch (IOException | IllegalArgumentException e) {
            throw new InvalidConfigurationException("cannot read " + source + ": " + e.getMessage(), e);
        }
        return properties;
    }

    /** The text without one leading byte order mark, if it has one. */
    static String withoutByteOrderMark(String text) {
        return !text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK ? text.substring(1) : text;
    }

    // ── Effective text ────────────────────────────────────────────────────────

    /**
     * The text of a properties file with exactly one active line per key: the line whose value
     * the configuration uses. Every other line that sets the key is commented out where it is, so
     * that the text reads as before, and editing the value where the key appears, as a user of
     * {@code GET} and {@code POST /api/v1/config} does, changes that key:
     * <ul>
     *   <li>a key that an override replaces is commented out wherever the text sets it
     *       ({@code # overridden on the command line: maxEvaluations = 25000}), and the overrides
     *       are appended at the end, one line per key, escaped so that reading them back gives
     *       them literally (a backslash is doubled);</li>
     *   <li>a key the text sets more than once keeps its last line, the one {@link Properties}
     *       reads, and the earlier ones are commented out
     *       ({@code # overridden by a later line: maxEvaluations = 100}).</li>
     * </ul>
     *
     * <p>Lines are recognised as {@link Properties} reads them: comment and blank lines are kept
     * as they are, the key of a line is read by {@link Properties} itself (so {@code :}, a space
     * or an escape sequence in the key make no difference), and a line continued with a trailing
     * backslash is commented out on every line it spans, because a comment does not continue. A
     * text without overrides or repeated keys comes back unchanged, but for a leading byte order
     * mark, which is dropped. If an override is given twice, the last one counts.
     *
     * @param text      the text of a properties file
     * @param overrides {@code key=value} entries that replace or add keys, taken literally
     * @return the effective text
     * @throws InvalidConfigurationException if an override is not {@code key=value}
     */
    static String effectiveText(String text, List<String> overrides) {
        Map<String, String> replaced = new LinkedHashMap<>();
        for (String override : overrides) {
            String[] entry = splitOverride(override);
            replaced.remove(entry[0]);
            replaced.put(entry[0], entry[1]);
        }
        List<LogicalLine> lines = logicalLines(withoutByteOrderMark(text));
        Map<String, Integer> lastLine = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).key() != null) {
                lastLine.put(lines.get(i).key(), i);
            }
        }

        StringBuilder effective = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            LogicalLine line = lines.get(i);
            String key = line.key();
            if (key == null || !replaced.containsKey(key) && lastLine.get(key) == i) {
                line.natural().forEach(effective::append);
            } else {
                commentOut(line, replaced.containsKey(key) ? OVERRIDDEN : SUPERSEDED, effective);
            }
        }
        if (!replaced.isEmpty()) {
            // End the last line with \n, turning a final lone \r into \r\n: Properties takes a \n
            // right after a \r as part of the same line end, so a last line continued with a
            // backslash would swallow the blank line below and read the header as its value.
            if (!effective.isEmpty() && effective.charAt(effective.length() - 1) != '\n') {
                effective.append('\n');
            }
            effective.append('\n').append(OVERRIDES_HEADER).append('\n');
            replaced.forEach((key, value) ->
                    effective.append(escape(key, true)).append(" = ").append(escape(value, false)).append('\n'));
        }
        return effective.toString();
    }

    /**
     * A logical line of a properties text.
     *
     * @param natural its natural lines, each with its line terminator (the last line of the text
     *                may have none); more than one when a line ends with a continuation backslash
     * @param key     the key it sets, or {@code null} for a blank or comment line
     */
    private record LogicalLine(List<String> natural, String key) {
    }

    /** Splits a text into logical lines as {@link Properties#load(java.io.Reader)} does. */
    private static List<LogicalLine> logicalLines(String text) {
        List<String> natural = naturalLines(text);
        List<LogicalLine> lines = new ArrayList<>();
        int start = 0;
        while (start < natural.size()) {
            String first = natural.get(start);
            int end = start + 1;
            String key = null;
            int content = firstNonBlank(first);
            // Comment lines never continue; blank lines set nothing.
            if (content >= 0 && first.charAt(content) != '#' && first.charAt(content) != '!') {
                while (end < natural.size() && continues(natural.get(end - 1))) {
                    end++;
                }
                key = keyOf(String.join("", natural.subList(start, end)));
            }
            lines.add(new LogicalLine(List.copyOf(natural.subList(start, end)), key));
            start = end;
        }
        return lines;
    }

    /** The natural lines of a text, each with its terminator: {@code \n}, {@code \r} or {@code \r\n}. */
    private static List<String> naturalLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isLineTerminator(c)) {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                lines.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    /** Index of the first character that is not properties white space, or -1 for a blank line. */
    private static int firstNonBlank(String natural) {
        for (int i = 0; i < natural.length(); i++) {
            char c = natural.charAt(i);
            if (isLineTerminator(c)) {
                return -1;
            }
            if (c != ' ' && c != '\t' && c != '\f') {
                return i;
            }
        }
        return -1;
    }

    /** Whether a natural line ends with an odd number of backslashes, which continues it on the next. */
    private static boolean continues(String natural) {
        int end = natural.length();
        while (end > 0 && isLineTerminator(natural.charAt(end - 1))) {
            end--;
        }
        int backslashes = 0;
        while (end - backslashes > 0 && natural.charAt(end - backslashes - 1) == '\\') {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    /** The key a logical line sets, read by {@link Properties} itself, or {@code null} if it sets none. */
    private static String keyOf(String logicalLine) {
        Properties line = new Properties();
        try {
            line.load(new StringReader(logicalLine));
        } catch (IOException | IllegalArgumentException e) {
            // The whole text has already loaded, so this does not happen; leave the line as it is.
            return null;
        }
        Set<String> keys = line.stringPropertyNames();
        return keys.size() == 1 ? keys.iterator().next() : null;
    }

    private static void commentOut(LogicalLine line, String reason, StringBuilder text) {
        List<String> natural = line.natural();
        text.append(reason).append(natural.getFirst().stripLeading());
        for (String continuation : natural.subList(1, natural.size())) {
            text.append('#').append(continuation);
        }
    }

    private static boolean isLineTerminator(char c) {
        return c == '\n' || c == '\r';
    }

    /**
     * Escapes a key or a value so that {@link Properties} reads it back as it is: backslashes and
     * control characters always, and in a key also the characters that would end it or start a
     * comment. A value only needs its first space escaped, and overrides have none.
     */
    private static String escape(String text, boolean key) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '\t' -> escaped.append("\\t");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\f' -> escaped.append("\\f");
                case ' ' -> escaped.append(key || i == 0 ? "\\ " : " ");
                case '=', ':', '#', '!' -> escaped.append(key ? "\\" : "").append(c);
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    /**
     * Parses the properties.
     *
     * @throws InvalidConfigurationException if a value is missing or wrong, or a key is unknown
     */
    AlgorithmConfig parse() {
        String algorithm = value("algorithm");
        if (algorithm == null) {
            throw invalid("algorithm is required (nsgaii, paes or moead)");
        }
        String name = algorithm.toLowerCase(Locale.ROOT);
        AlgorithmConfig config = switch (name) {
            case "nsgaii" -> new NSGAIIConfig(
                    maxEvaluations(),
                    positiveInteger("populationSize", DEFAULT_SIZE),
                    operator("crossover", List.of(CrossoverType.values()), CrossoverType.SBX,
                            Double.toString(DEFAULT_CROSSOVER_PROBABILITY)),
                    operator("mutation", List.of(MutationType.values()), MutationType.POLYNOMIAL, PER_VARIABLE),
                    tracesFolder());
            case "paes" -> new PAESConfig(
                    maxEvaluations(),
                    positiveInteger("archiveSize", DEFAULT_SIZE),
                    operator("mutation", List.of(MutationType.values()), MutationType.POLYNOMIAL, PER_VARIABLE),
                    probability("archiveSelectionProbability", "0.0"),
                    resultSource(),
                    tracesFolder());
            case "moead" -> moead();
            default -> throw invalid("algorithm must be nsgaii, paes or moead, got '" + algorithm + "'");
        };
        rejectUnknownKeys(name);
        return config;
    }

    private MOEADConfig moead() {
        int maxEvaluations = maxEvaluations();
        int populationSize = positiveInteger("populationSize", DEFAULT_SIZE);
        MOEADWeights.Method weights = weights();
        int neighborSize = atMost("neighborSize", Math.min(DEFAULT_NEIGHBOR_SIZE, populationSize),
                "populationSize", populationSize);
        double neighborhoodSelectionProbability = probability("neighborhoodSelectionProbability",
                DEFAULT_NEIGHBORHOOD_SELECTION_PROBABILITY);
        int maximumNumberOfReplacedSolutions = atMost("maximumNumberOfReplacedSolutions",
                Math.min(DEFAULT_REPLACED_SOLUTIONS, neighborSize), "neighborSize", neighborSize);
        MOEAD.AggregationFunction aggregation = aggregation();
        return new MOEADConfig(maxEvaluations, populationSize, weights, neighborSize, neighborhoodSelectionProbability,
                maximumNumberOfReplacedSolutions, aggregation, bool("normalizeObjectives", false),
                operator("crossover", List.of(CrossoverType.values()), CrossoverType.SBX,
                        Double.toString(DEFAULT_CROSSOVER_PROBABILITY)),
                operator("mutation", List.of(MutationType.values()), MutationType.POLYNOMIAL, PER_VARIABLE),
                tracesFolder());
    }

    private int maxEvaluations() {
        String text = value("maxEvaluations");
        if (text == null) {
            throw invalid("maxEvaluations is required");
        }
        return positiveInteger("maxEvaluations", text);
    }

    private int positiveInteger(String key, int defaultValue) {
        String text = value(key);
        return text == null ? defaultValue : positiveInteger(key, text);
    }

    private int positiveInteger(String key, String text) {
        try {
            int number = Integer.parseInt(text);
            if (number > 0) {
                return number;
            }
        } catch (NumberFormatException e) {
            // Reported below, as a non-positive value.
        }
        throw invalid(key + " must be a positive integer, got '" + text + "'");
    }

    /** A positive integer no greater than the value of {@code limitKey}. */
    private int atMost(String key, int defaultValue, String limitKey, int limit) {
        int number = positiveInteger(key, defaultValue);
        if (number > limit) {
            throw invalid(key + " must not be greater than " + limitKey + " (" + limit + "), got '" + number + "'");
        }
        return number;
    }

    /**
     * The traces folder, or {@code null} when the key is missing or empty. Values that can only come
     * from a Windows path written with single backslashes are rejected: in a properties file
     * {@code C:\temp\traces} reads as {@code C:<TAB>emp<TAB>races} and {@code C:\data\runs} as
     * {@code C:dataruns}, while the same text given as an override is taken literally.
     */
    private String tracesFolder() {
        String folder = value("tracesFolder");
        if (folder == null || folder.isEmpty()) {
            return null;
        }
        for (int i = 0; i < folder.length(); i++) {
            char c = folder.charAt(i);
            if (Character.isISOControl(c)) {
                throw invalid("tracesFolder contains the control character U+%04X at position %d; ".formatted((int) c, i)
                        + BACKSLASH_ADVICE);
            }
        }
        if (DRIVE_RELATIVE.matcher(folder).matches()) {
            throw invalid("tracesFolder '" + folder + "' is relative to the current folder of drive "
                    + folder.substring(0, 2) + ", as a Windows path whose backslashes were read as escape sequences; "
                    + BACKSLASH_ADVICE);
        }
        try {
            Path.of(folder);
        } catch (InvalidPathException e) {
            throw invalid("tracesFolder is not a valid path: " + e.getMessage());
        }
        return folder;
    }

    private PAES.ResultSource resultSource() {
        String text = value("result", "paes");
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "paes" -> PAES.ResultSource.PAES_ARCHIVE;
            case "external" -> PAES.ResultSource.EXTERNAL_ARCHIVE;
            default -> throw invalid("result must be paes or external, got '" + text + "'");
        };
    }

    private MOEAD.AggregationFunction aggregation() {
        String text = value("aggregation", "tchebycheff");
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "tchebycheff" -> MOEAD.AggregationFunction.TCHEBYCHEFF;
            case "wsum" -> MOEAD.AggregationFunction.WSUM;
            case "pbi" -> MOEAD.AggregationFunction.PBI;
            default -> throw invalid("aggregation must be tchebycheff, wsum or pbi, got '" + text + "'");
        };
    }

    /** The weight vectors of MOEA/D. The lattice sizes depend on the objectives: see {@link #checkObjectives}. */
    private MOEADWeights.Method weights() {
        String text = value("weights", "spread");
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "spread" -> MOEADWeights.Method.SPREAD;
            case "lattice" -> MOEADWeights.Method.LATTICE;
            default -> throw invalid("weights must be spread or lattice, got '" + text + "'");
        };
    }

    private boolean bool(String key, boolean defaultValue) {
        String text = value(key, Boolean.toString(defaultValue));
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw invalid(key + " must be true or false, got '" + text + "'");
        };
    }

    /**
     * Reads {@code prefix} (the operator name), {@code prefix.probability} and
     * {@code prefix.<parameter>} for each parameter of that operator, checks the values with
     * {@link OperatorType#check} and builds the operator once, so that a value its constructor
     * rejects is reported here, naming the key, instead of when the run starts or is reconfigured.
     *
     * @param types              the catalogue the name is looked up in, without regard to case
     * @param defaultType        the operator when {@code prefix} is missing
     * @param defaultProbability the text of the probability when {@code prefix.probability} is missing
     * @throws InvalidConfigurationException if a value is wrong or the operator cannot be built
     */
    <O> OperatorConfig<O> operator(String prefix, List<? extends OperatorType<O>> types,
            OperatorType<O> defaultType, String defaultProbability) {
        String name = value(prefix, defaultType.key());
        OperatorType<O> type = null;
        for (OperatorType<O> candidate : types) {
            if (type == null && candidate.key().equalsIgnoreCase(name)) {
                type = candidate;
            }
        }
        if (type == null) {
            throw invalid(prefix + " must be one of "
                    + types.stream().map(OperatorType::key).collect(Collectors.joining(", "))
                    + ", got '" + name + "'");
        }
        double probability = probability(prefix + ".probability", defaultProbability);
        Map<String, Double> parameters = new LinkedHashMap<>();
        for (OperatorType.Parameter parameter : type.parameters()) {
            String key = prefix + "." + parameter.name();
            String text = value(key);
            parameters.put(parameter.name(), text == null ? parameter.defaultValue() : nonNegative(key, text));
        }
        String reason = type.check(parameters, numberOfVariables);
        if (reason != null) {
            throw invalid(prefix + "." + reason);
        }
        Map<String, Double> values = Collections.unmodifiableMap(parameters);
        try {
            type.create(probability, values);
        } catch (RuntimeException e) {
            throw new InvalidConfigurationException(prefix + ": " + type.key() + " cannot be built with these values: "
                    + (e.getMessage() != null ? e.getMessage() : e.toString()), e);
        }
        return new OperatorConfig<>(type, probability, values);
    }

    /** A probability in [0, 1], written as a number or as {@code k/n}, k over the number of variables. */
    private double probability(String key, String defaultText) {
        String text = value(key, defaultText);
        Matcher perVariables = PER_VARIABLES.matcher(text);
        boolean relative = perVariables.matches();
        Double number = finiteNumber(relative ? perVariables.group(1) : text);
        if (number == null) {
            throw invalid(key + " must be a number or k/n, got '" + text + "'");
        }
        double probability = relative ? number / numberOfVariables : number;
        if (probability < 0.0 || probability > 1.0) {
            throw invalid(key + " must be in [0, 1] or k/n, got '" + text + "'"
                    + (relative ? " (" + OperatorConfig.format(probability) + ")" : ""));
        }
        return probability;
    }

    private double nonNegative(String key, String text) {
        Double number = finiteNumber(text);
        if (number == null) {
            throw invalid(key + " must be a number, got '" + text + "'");
        }
        if (number < 0.0) {
            throw invalid(key + " must not be negative, got '" + text + "'");
        }
        return number;
    }

    /** The value of a plain decimal number, or {@code null} if the text is not one or is not finite. */
    private static Double finiteNumber(String text) {
        String trimmed = text.trim();
        Double number = null;
        if (DECIMAL.matcher(trimmed).matches()) {
            double value = Double.parseDouble(trimmed);
            if (Double.isFinite(value)) {
                number = value;
            }
        }
        return number;
    }

    /** The trimmed value of the key, or {@code null} if it is missing; the key becomes known either way. */
    private String value(String key) {
        knownKeys.add(key);
        String text = properties.getProperty(key);
        return text == null ? null : text.trim();
    }

    /** The trimmed value of the key, or {@code defaultText} if it is missing; the key becomes known either way. */
    private String value(String key, String defaultText) {
        String text = value(key);
        return text == null ? defaultText : text;
    }

    private void rejectUnknownKeys(String algorithm) {
        List<String> unknown = new ArrayList<>(properties.stringPropertyNames());
        unknown.removeAll(knownKeys);
        if (!unknown.isEmpty()) {
            Collections.sort(unknown);
            throw invalid("unknown keys for " + algorithm + " with these operators: " + String.join(", ", unknown)
                    + ". Valid keys: " + String.join(", ", knownKeys));
        }
    }

    private static InvalidConfigurationException invalid(String message) {
        return new InvalidConfigurationException(message);
    }
}
