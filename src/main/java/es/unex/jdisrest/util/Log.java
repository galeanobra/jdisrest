package es.unex.jdisrest.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Project-wide logger that writes directly to stderr.
 * <p>
 * Unlike JMetalLogger (which uses java.util.logging and gets hijacked by
 * Spring Boot's Logback), this logger always writes to stderr, making it
 * reliable in SLURM environments where stdout and stderr are separate files.
 * <p>
 * Usage:
 *   Log.info("Server started on port " + port);
 *   Log.warn("Worker timeout: " + workerId);
 *   Log.error("Failed to start", exception);
  * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public final class Log {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Log() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Logs a message at INFO level.
     *
     * @param message the message to log
     */
    public static void info(String message) {
        print("INFO", message);
    }

    /**
     * Logs a message at WARN level.
     *
     * @param message the warning message to log
     */
    public static void warn(String message) {
        print("WARN", message);
    }

    /**
     * Logs a message at ERROR level without an associated exception.
     *
     * @param message the error message to log
     */
    public static void error(String message) {
        print("ERROR", message);
    }

    /**
     * Logs a message at ERROR level and prints the full stack trace of the
     * given throwable to stderr.
     *
     * @param message the error message to log
     * @param t       the exception whose stack trace is printed; may be {@code null}
     */
    public static void error(String message, Throwable t) {
        print("ERROR", message);
        if (t != null) {
            t.printStackTrace(System.err);
        }
        System.err.flush();
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Formats and writes a single log line to stderr.
     * The line format is: {@code yyyy-MM-dd HH:mm:ss LEVEL: message [ClassName.method]}.
     *
     * @param level   the log level label (e.g. {@code "INFO"})
     * @param message the message to include in the log line
     */
    private static void print(String level, String message) {
        String timestamp = LocalDateTime.now().format(FMT);
        // Get the caller class name (skip Log, print)
        String caller = caller();
        System.err.println(timestamp + " " + level + ": " + message + " [" + caller + "]");
        System.err.flush();
    }

    /**
     * Walks the current thread's stack to find the first frame outside this
     * class, which represents the actual caller of the public logging method.
     *
     * @return a string of the form {@code "SimpleClassName.methodName"},
     *         or {@code "unknown"} if no external frame is found
     */
    private static String caller() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        // 0=getStackTrace, 1=caller(), 2=print(), 3=info/warn/error, 4=actual caller
        for (int i = 3; i < stack.length; i++) {
            String cls = stack[i].getClassName();
            if (!cls.equals(Log.class.getName())) {
                // Return simple class name + method
                String simple = cls.contains(".") ? cls.substring(cls.lastIndexOf('.') + 1) : cls;
                return simple + "." + stack[i].getMethodName();
            }
        }
        return "unknown";
    }
}