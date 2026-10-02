package es.unex.jdisrest.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Records the configurations of a run in its traces folder: the one it started with and each
 * change made while it runs ({@code POST /api/v1/config}, see {@link AlgorithmReconfiguration}).
 * Every configuration is saved as a properties file, named after the evaluations after which it
 * took effect, and {@code configuration.log} lists them in order with a summary, under a header
 * line for each run that used the folder:
 *
 * <pre>
 * # Run started 2026-09-27T10:00:00 from /home/me/runs/nsgaii.properties with overrides maxEvaluations=40
 * 2026-09-27T10:00:00  evaluations 0         nsgaii.properties               NSGA-II, 40 evaluations, ...
 * 2026-09-27T11:30:12  evaluations 2300      nsgaii_2300.properties          NSGA-II, 90000 evaluations, ...
 * </pre>
 *
 * <h2>The configuration in use</h2>
 * <p>{@link #current()} is what {@code GET /api/v1/config} returns for editing and sending back,
 * so it always has exactly one active line per key (see {@link AlgorithmConfig#writeCopy}): at
 * the start, the file with the lines its overrides replace commented out and the overrides
 * appended; after a change, the text applied, with the earlier lines of a key it sets twice
 * commented out. The saved files hold the same texts.
 *
 * <h2>Order of the records</h2>
 * <p>{@link #recordStart} belongs after the algorithm has been built, so that a run that fails to
 * start leaves no record in a traces folder that later runs reuse, and before the
 * {@link AlgorithmReconfiguration} is registered, so that no change can be recorded before the
 * start. Without a traces folder nothing is written, but the configuration in use is still kept.
 *
 * <p>Thread-safe, because changes arrive on REST threads: the records are synchronized, and
 * {@link #current()} reads a volatile field, so it never waits for a record being written.
 *
 * @author Francisco Luna (Universidad de Málaga)
 */
public final class ConfigHistory {

    static final String LOG_FILE = "configuration.log";
    private static final String EXTENSION = ".properties";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final Path file;
    private final List<String> overrides;
    private final Path folder;
    private final String stem;
    private final String start;
    private volatile String current;

    private ConfigHistory(Path file, List<String> overrides, Path folder, String start) {
        this.file = file;
        this.overrides = List.copyOf(overrides);
        this.folder = folder;
        this.stem = stem(file);
        this.start = start;
        this.current = start;
    }

    /**
     * Creates the history of a run that starts with a configuration file and its overrides.
     *
     * @param file      the configuration file, read as UTF-8
     * @param overrides the {@code key=value} entries the run was given, already validated by
     *                  {@link AlgorithmConfig#load}
     * @param config    the configuration they give, whose traces folder receives the history
     * @return the history, whose {@link #current()} is the effective text of the file
     * @throws IOException if the file cannot be read
     */
    public static ConfigHistory of(Path file, List<String> overrides, AlgorithmConfig config) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        Objects.requireNonNull(overrides, "overrides must not be null");
        Objects.requireNonNull(config, "config must not be null");
        String folder = config.tracesFolder();
        return new ConfigHistory(file, overrides, folder == null ? null : Path.of(folder),
                AlgorithmConfigParser.textWithOverrides(file, overrides));
    }

    /**
     * The configuration in use, as the text of a properties file with one active line per key.
     *
     * @return the text
     */
    public String current() {
        return current;
    }

    /**
     * Copies the starting configuration, with its overrides, into the traces folder (see
     * {@link AlgorithmConfig#writeCopy}), and writes to the log a header line for the run and the
     * line of the copy. Call it once, after the algorithm has been built (see the class
     * description).
     *
     * @param config the configuration the run started with
     * @return the copy, or {@code null} without a traces folder
     * @throws IOException if the copy or the log cannot be written
     */
    public synchronized Path recordStart(AlgorithmConfig config) throws IOException {
        Path copy = null;
        if (folder != null) {
            copy = writeCopy(file, start, folder);
            LocalDateTime now = LocalDateTime.now();
            Path log = folder.resolve(LOG_FILE);
            // A blank line separates the runs that reuse the folder.
            String separator = Files.exists(log) && Files.size(log) > 0 ? System.lineSeparator() : "";
            append(separator + "# Run started " + TIME.format(now) + " from " + file.toAbsolutePath().normalize()
                    + (overrides.isEmpty() ? " without overrides" : " with overrides " + overridesText())
                    + System.lineSeparator() + line(now, 0, copy, config));
        }
        return copy;
    }

    /**
     * Records a configuration applied after {@code evaluations} evaluations: its text becomes the
     * one in use, with the earlier lines of a key it sets twice commented out, and is saved as
     * {@code <name>_<evaluations>.properties} (with a {@code _2}, {@code _3}... suffix if that
     * file exists, for instance after two changes at the same count), with a line in the log.
     *
     * @param text        the text of the new properties file, as applied
     * @param evaluations the evaluations after which it took effect
     * @param config      the configuration it gives
     * @return the saved file, or {@code null} without a traces folder
     * @throws IOException if the file or the log cannot be written; the text is still the one in
     *                     use
     */
    public synchronized Path recordChange(String text, int evaluations, AlgorithmConfig config) throws IOException {
        current = AlgorithmConfigParser.effectiveText(text, List.of());
        Path saved = null;
        if (folder != null) {
            Files.createDirectories(folder);
            saved = unusedFile(folder, stem + "_" + evaluations);
            Files.writeString(saved, current, StandardCharsets.UTF_8);
            append(line(LocalDateTime.now(), evaluations, saved, config));
        }
        return saved;
    }

    // ── Files ─────────────────────────────────────────────────────────────────

    /**
     * Writes the effective text of a configuration file into a folder, as described by
     * {@link AlgorithmConfig#writeCopy}: under the name of the file, or, if the file itself is in
     * the folder, under {@code <name>_0.properties} unless the file already holds that text.
     *
     * @param file   the configuration file
     * @param text   its effective text
     * @param folder the folder for the copy, created if needed
     * @return the copy
     * @throws IOException if the file cannot be read or the copy cannot be written
     */
    static Path writeCopy(Path file, String text, Path folder) throws IOException {
        Files.createDirectories(folder);
        Path copy = folder.resolve(file.getFileName());
        if (Files.exists(copy) && Files.isSameFile(copy, file)) {
            String written = AlgorithmConfigParser.withoutByteOrderMark(Files.readString(file, StandardCharsets.UTF_8));
            if (written.equals(text)) {
                return copy;
            }
            // Never rewrite the file a run was started from: it may be another run's record.
            copy = unusedFile(folder, stem(file) + "_0");
        }
        Files.writeString(copy, text, StandardCharsets.UTF_8);
        return copy;
    }

    /** {@code <base>.properties} in the folder, or {@code <base>_2.properties}, {@code _3}... if it exists. */
    private static Path unusedFile(Path folder, String base) {
        Path target = folder.resolve(base + EXTENSION);
        for (int copy = 2; Files.exists(target); copy++) {
            target = folder.resolve(base + "_" + copy + EXTENSION);
        }
        return target;
    }

    /** The name of a file without its {@code .properties} extension. */
    private static String stem(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(EXTENSION) ? name.substring(0, name.length() - EXTENSION.length()) : name;
    }

    private String overridesText() {
        return overrides.stream()
                .map(override -> String.join("=", AlgorithmConfigParser.splitOverride(override)))
                .collect(Collectors.joining(" "));
    }

    private static String line(LocalDateTime time, int evaluations, Path saved, AlgorithmConfig config) {
        return String.format("%s  evaluations %-8d  %-30s  %s%n",
                TIME.format(time), evaluations, saved.getFileName(), config.describe());
    }

    private void append(String text) throws IOException {
        Files.writeString(folder.resolve(LOG_FILE), text, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
