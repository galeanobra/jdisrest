package es.unex.jdisrest.distributed.rest;

import es.unex.jdisrest.distributed.rest.dto.TaskPayload;
import es.unex.jdisrest.distributed.rest.dto.TaskRejectionPayload;
import es.unex.jdisrest.distributed.rest.dto.TaskResultPayload;
import es.unex.jdisrest.util.SolutionVariables;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.solution.binarysolution.BinarySolution;
import org.uma.jmetal.solution.binarysolution.impl.DefaultBinarySolution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.doublesolution.impl.DefaultDoubleSolution;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.solution.integersolution.impl.DefaultIntegerSolution;
import org.uma.jmetal.util.binarySet.BinarySet;
import org.uma.jmetal.util.bounds.Bounds;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wire-level behaviour of the task endpoints: the JSON the master emits for
 * each solution shape, backwards compatibility of the integer, real and
 * integer-and-real formats, the validation applied to results before they touch
 * the master-held solution, and the answer to a result that fails it.
 *
 * <p>Serialization goes through the same Jackson 3 mapper family Spring
 * WebFlux uses at runtime ({@code tools.jackson}); the untyped-map round trip
 * uses Jackson 2, which is what {@code RestWorker} and
 * {@code PythonProcessEvaluator} use.
 */
class TaskControllerTest {

    private static final JsonMapper JSON3 = JsonMapper.builder().build();
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON2 =
        new com.fasterxml.jackson.databind.ObjectMapper();

    // ── Fixtures ──────────────────────────────────────────────────────────────

    static IntegerSolution intSolution(int objectives, int constraints, int... values) {
        List<Bounds<Integer>> bounds = Collections.nCopies(values.length, Bounds.create(-1000, 1000));
        IntegerSolution s = new DefaultIntegerSolution(bounds, objectives, constraints);
        for (int i = 0; i < values.length; i++) s.variables().set(i, values[i]);
        return s;
    }

    static DoubleSolution doubleSolution(int objectives, int constraints, double... values) {
        List<Bounds<Double>> bounds = Collections.nCopies(values.length, Bounds.create(-1000.0, 1000.0));
        DoubleSolution s = new DefaultDoubleSolution(bounds, objectives, constraints);
        for (int i = 0; i < values.length; i++) s.variables().set(i, values[i]);
        return s;
    }

    /** A binary solution from the bit strings of its variables, bit 0 first. */
    static BinarySolution binarySolution(int objectives, String... values) {
        BinarySolution s = new DefaultBinarySolution(Arrays.stream(values).map(String::length).toList(), objectives, 0);
        for (int i = 0; i < values.length; i++) {
            BinarySet bits = new BinarySet(values[i].length());
            for (int b = 0; b < values[i].length(); b++) {
                if (values[i].charAt(b) == '1') bits.set(b);
            }
            s.variables().set(i, bits);
        }
        return s;
    }

    static TaskResultPayload result(List<Double> objectives, List<Double> constraints, List<Number> variables) {
        return new TaskResultPayload("worker-test", objectives, constraints, 1L, variables);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(String json) {
        return JSON3.readValue(json, Map.class);
    }

    // ── GET /next payload ─────────────────────────────────────────────────────

    @Test
    void integerPayloadKeepsTheLegacyFormat() throws Exception {
        TaskPayload payload = TaskController.toPayload(7, intSolution(1, 0, 3, -17, 55));
        String json = JSON3.writeValueAsString(payload);

        assertEquals("{\"taskId\":7,\"variables\":[3,-17,55]}", json,
            "integer problems must produce exactly the 1.0 JSON");
        assertEquals("{\"taskId\":7,\"variables\":[3,-17,55]}", JSON2.writeValueAsString(payload));
    }

    @Test
    void integerCompositePayloadKeepsTheLegacyFormat() {
        CompositeSolution c = new CompositeSolution(List.of(intSolution(1, 0, 1, 2), intSolution(1, 0, 3)));
        TaskPayload payload = TaskController.toPayload(8, c);
        Map<String, Object> json = asMap(JSON3.writeValueAsString(payload));

        assertEquals(Set.of("taskId", "variables", "segmentSizes"), json.keySet());
        assertEquals(List.of(1, 2, 3), json.get("variables"));
        assertEquals(List.of(2, 1), json.get("segmentSizes"));
    }

    @Test
    void doublePayloadCarriesTheEncodingAndRealNumbers() {
        TaskPayload payload = TaskController.toPayload(9, doubleSolution(1, 0, 0.5, -2.0, 3.25));
        Map<String, Object> json = asMap(JSON3.writeValueAsString(payload));

        assertEquals(Set.of("taskId", "variables", "encoding"), json.keySet());
        assertEquals("double", json.get("encoding"));
        assertEquals(List.of(0.5, -2.0, 3.25), json.get("variables"));
        // -2.0 must travel as a JSON float, not as the integer -2.
        assertInstanceOf(Double.class, ((List<?>) json.get("variables")).get(1));
    }

    @Test
    void mixedCompositePayloadDescribesEverySegment() {
        CompositeSolution c = new CompositeSolution(List.of(
            intSolution(1, 0, 1, 2), doubleSolution(1, 0, 0.5, 0.25)));
        TaskPayload payload = TaskController.toPayload(10, c);
        Map<String, Object> json = asMap(JSON3.writeValueAsString(payload));

        assertEquals(Set.of("taskId", "variables", "segmentSizes", "encoding", "segmentEncodings"), json.keySet());
        assertEquals("mixed", json.get("encoding"));
        assertEquals(List.of(2, 2), json.get("segmentSizes"));
        assertEquals(List.of("int", "double"), json.get("segmentEncodings"));
        assertEquals(List.of(1, 2, 0.5, 0.25), json.get("variables"));
    }

    @Test
    void realAndMixedPayloadsAreTheBytesOfEarlierVersions() throws Exception {
        String real = "{\"taskId\":43,\"variables\":[0.25,-1.5,3.0],\"encoding\":\"double\"}";
        String mixed = "{\"taskId\":44,\"variables\":[3,-17,0.25],\"segmentSizes\":[2,1],\"encoding\":\"mixed\","
            + "\"segmentEncodings\":[\"int\",\"double\"]}";
        TaskPayload realPayload = TaskController.toPayload(43, doubleSolution(1, 0, 0.25, -1.5, 3.0));
        TaskPayload mixedPayload = TaskController.toPayload(44,
            new CompositeSolution(List.of(intSolution(1, 0, 3, -17), doubleSolution(1, 0, 0.25))));

        assertEquals(real, JSON3.writeValueAsString(realPayload), "no bitsPerVariable without binary variables");
        assertEquals(real, JSON2.writeValueAsString(realPayload));
        assertEquals(mixed, JSON3.writeValueAsString(mixedPayload));
        assertEquals(mixed, JSON2.writeValueAsString(mixedPayload));
    }

    @Test
    void binaryPayloadCarriesOneIntegerPerBitTheEncodingAndTheLengthsOfItsVariables() throws Exception {
        TaskPayload payload = TaskController.toPayload(45, binarySolution(1, "101", "00110"));

        String json = "{\"taskId\":45,\"variables\":[1,0,1,0,0,1,1,0],\"encoding\":\"binary\",\"bitsPerVariable\":[3,5]}";
        assertEquals(json, JSON3.writeValueAsString(payload),
            "[1,0,1] is the variable a trace writes as 101; the encoding keeps a worker of an integer problem "
                + "of 8 variables from evaluating the bits");
        assertEquals(json, JSON2.writeValueAsString(payload));
    }

    @Test
    void compositePayloadWithABinarySegmentCountsItsBits() {
        CompositeSolution mixed = new CompositeSolution(List.of(intSolution(1, 0, 3, -7), doubleSolution(1, 0, 0.25),
            binarySolution(1, "101", "00110")));
        assertEquals("{\"taskId\":46,\"variables\":[3,-7,0.25,1,0,1,0,0,1,1,0],\"segmentSizes\":[2,1,8],"
                + "\"encoding\":\"mixed\",\"segmentEncodings\":[\"int\",\"double\",\"binary\"],\"bitsPerVariable\":[3,5]}",
            JSON3.writeValueAsString(TaskController.toPayload(46, mixed)));

        CompositeSolution allBinary = new CompositeSolution(List.of(binarySolution(1, "101"), binarySolution(1, "00110")));
        assertEquals("{\"taskId\":47,\"variables\":[1,0,1,0,0,1,1,0],\"segmentSizes\":[3,5],\"encoding\":\"binary\","
                + "\"segmentEncodings\":[\"binary\",\"binary\"],\"bitsPerVariable\":[3,5]}",
            JSON3.writeValueAsString(TaskController.toPayload(47, allBinary)));

        CompositeSolution integerAndBinary = new CompositeSolution(List.of(intSolution(1, 0, 4), binarySolution(1, "1")));
        Map<String, Object> json = asMap(JSON3.writeValueAsString(TaskController.toPayload(48, integerAndBinary)));
        assertEquals("mixed", json.get("encoding"), "never left out with a binary variable, unlike an all-integer composite");
        assertEquals(List.of(1), json.get("bitsPerVariable"));
    }

    @Test
    void payloadReceivedAsUntypedMapIsAppliedByDestinationType() throws Exception {
        // What RestWorker does: Jackson 2 into Map, then SolutionVariables.apply().
        String json = JSON3.writeValueAsString(TaskController.toPayload(11,
            new CompositeSolution(List.of(intSolution(1, 0, 4, 5), doubleSolution(1, 0, 6.0, 7.5)))));
        @SuppressWarnings("unchecked")
        Map<String, Object> task = JSON2.readValue(json, Map.class);
        @SuppressWarnings("unchecked")
        List<Number> variables = (List<Number>) task.get("variables");

        CompositeSolution fresh = new CompositeSolution(List.of(intSolution(1, 0, 0, 0), doubleSolution(1, 0, 0, 0)));
        SolutionVariables.apply(fresh, variables);

        assertEquals(List.of(4, 5), fresh.variables().get(0).variables());
        assertEquals(List.of(6.0, 7.5), fresh.variables().get(1).variables());
    }

    // ── POST /result validation ───────────────────────────────────────────────

    @Test
    void resultVariablesAreConvertedByDestinationNotByJsonType() {
        // A Python worker answering integers for a real-coded problem.
        TaskResultPayload fromWorker = JSON3.readValue(
            "{\"workerId\":\"w\",\"objectives\":[1.5],\"constraints\":[],\"evaluationTimeMs\":3,\"variables\":[1, 2]}",
            TaskResultPayload.class);
        assertInstanceOf(Integer.class, fromWorker.variables().get(0), "Jackson binds 1 to Integer");

        DoubleSolution real = doubleSolution(1, 0, 0, 0);
        assertNull(TaskController.rejectionReason(fromWorker, real));
        SolutionVariables.apply(real, fromWorker.variables());
        assertEquals(List.of(1.0, 2.0), real.variables());
        assertInstanceOf(Double.class, real.variables().get(0));

        // And floats for an integer problem.
        TaskResultPayload floats = JSON3.readValue(
            "{\"workerId\":\"w\",\"objectives\":[1.5],\"constraints\":[],\"evaluationTimeMs\":3,\"variables\":[1.0, 2.0]}",
            TaskResultPayload.class);
        IntegerSolution integer = intSolution(1, 0, 0, 0);
        assertNull(TaskController.rejectionReason(floats, integer));
        SolutionVariables.apply(integer, floats.variables());
        assertEquals(List.of(1, 2), integer.variables());
        assertInstanceOf(Integer.class, integer.variables().get(0));
    }

    @Test
    void legacyResultWithoutVariablesIsAccepted() {
        TaskResultPayload legacy = JSON3.readValue(
            "{\"workerId\":\"w\",\"objectives\":[-1234.5],\"constraints\":[],\"evaluationTimeMs\":3200}",
            TaskResultPayload.class);

        assertNull(legacy.variables());
        assertEquals(3200L, legacy.evaluationTimeMs());
        assertNull(TaskController.rejectionReason(legacy, intSolution(1, 0, 1, 2)));
    }

    @Test
    void resultWithoutEvaluationTimeOrConstraintsIsAccepted() {
        // Both fields are documented as optional; a primitive long would make
        // Jackson 3 reject the body ("Cannot map `null` into type `long`").
        TaskResultPayload minimal = JSON3.readValue(
            "{\"workerId\":\"w\",\"objectives\":[-1234.5]}", TaskResultPayload.class);

        assertNull(minimal.evaluationTimeMs());
        assertNull(minimal.constraints());
        assertNull(TaskController.rejectionReason(minimal, intSolution(1, 0, 1, 2)));
    }

    @Test
    void nonFiniteObjectivesAndConstraintsAreRejected() {
        Solution<?> s = doubleSolution(2, 1, 0, 0);

        String r = TaskController.rejectionReason(result(List.of(1.0, Double.NaN), List.of(0.0), null), s);
        assertNotNull(r);
        assertTrue(r.contains("objectives[1]"), r);

        r = TaskController.rejectionReason(result(List.of(1.0, 2.0), List.of(Double.NEGATIVE_INFINITY), null), s);
        assertNotNull(r);
        assertTrue(r.contains("constraints[0]"), r);
    }

    @Test
    void nullObjectiveFromJsonIsRejected() {
        TaskResultPayload nullObjective = JSON3.readValue(
            "{\"workerId\":\"w\",\"objectives\":[null],\"constraints\":[],\"evaluationTimeMs\":1}",
            TaskResultPayload.class);

        String r = TaskController.rejectionReason(nullObjective, intSolution(1, 0, 1));
        assertNotNull(r);
        assertTrue(r.contains("objectives[0] is null"), r);
    }

    @Test
    void bareNaNTokenIsNotValidJsonForTheMaster() {
        // json.dumps(float('nan')) emits a bare NaN; Jackson rejects it, so the
        // request never reaches submitResult() and the @ExceptionHandler requeues.
        assertThrows(JacksonException.class, () -> JSON3.readValue(
            "{\"workerId\":\"w\",\"objectives\":[NaN],\"constraints\":[],\"evaluationTimeMs\":1}",
            TaskResultPayload.class));
    }

    @Test
    void wrongNumberOfObjectivesOrConstraintsIsRejected() {
        Solution<?> twoObjectivesOneConstraint = doubleSolution(2, 1, 0, 0);

        String r = TaskController.rejectionReason(result(List.of(1.0), List.of(0.0), null), twoObjectivesOneConstraint);
        assertNotNull(r);
        assertTrue(r.startsWith("objectives has 1 values but the problem defines 2"), r);

        r = TaskController.rejectionReason(result(List.of(1.0, 2.0), List.of(), null), twoObjectivesOneConstraint);
        assertNotNull(r);
        assertTrue(r.startsWith("constraints has 0 values but the problem defines 1"), r);

        r = TaskController.rejectionReason(result(List.of(1.0, 2.0), null, null), twoObjectivesOneConstraint);
        assertNotNull(r, "null constraints count as empty");

        Solution<?> noConstraints = doubleSolution(1, 0, 0, 0);
        assertNull(TaskController.rejectionReason(result(List.of(1.0), null, null), noConstraints));
        assertNull(TaskController.rejectionReason(result(List.of(1.0), List.of(), null), noConstraints));
    }

    @Test
    void variablesThatDoNotFitTheSolutionAreRejectedBeforeAnyWrite() {
        IntegerSolution s = intSolution(1, 0, 5, 6);

        String r = TaskController.rejectionReason(result(List.of(1.0), List.of(), List.<Number>of(1, 2, 3)), s);
        assertNotNull(r);
        assertTrue(r.contains("3 values"), r);

        r = TaskController.rejectionReason(result(List.of(1.0), List.of(), List.<Number>of(1, 2.5)), s);
        assertNotNull(r);
        assertTrue(r.contains("variables[1]"), r);

        assertEquals(List.of(5, 6), s.variables(), "validation must not modify the solution");
    }

    @Test
    void bitsOfAResultMustBeZeroOrOneAndAsManyAsTheVariablesHave() {
        BinarySolution s = binarySolution(1, "101", "00110");

        assertNull(TaskController.rejectionReason(result(List.of(1.0), List.of(), List.<Number>of(0, 1, 1, 0, 0, 1, 1, 1)), s));
        assertNull(TaskController.rejectionReason(result(List.of(1.0), List.of(), List.<Number>of(0.0, 1.0, 1, 0, 0, 1, 1, 1)), s),
            "a float within the integrality tolerance of 0 or 1 is a bit");
        assertEquals("variables[7] = 2 is not a bit (0 or 1) but its variable is binary", TaskController.rejectionReason(
            result(List.of(1.0), List.of(), List.<Number>of(0, 1, 1, 0, 0, 1, 1, 2)), s));
        assertEquals("variables[0] = 0.5 is not a bit (0 or 1) but its variable is binary", TaskController.rejectionReason(
            result(List.of(1.0), List.of(), List.<Number>of(0.5, 1, 1, 0, 0, 1, 1, 0)), s));
        assertEquals("variables has 2 values but the solution has 8", TaskController.rejectionReason(
            result(List.of(1.0), List.of(), List.<Number>of(1, 1)), s), "one value per bit, not per variable");
        assertEquals("[101, 00110]", s.variables().toString(), "validation must not modify the solution");
    }

    @Test
    void jsonBooleansAreNotNumbersForTheMaster() {
        // The DTO stays a list of numbers: a worker must send its bits as 0 and 1. The body cannot
        // be decoded, so Spring answers 400 and onRejectedRequest counts a failed evaluation.
        assertThrows(JacksonException.class, () -> JSON3.readValue(
            "{\"workerId\":\"w\",\"objectives\":[1.0],\"constraints\":[],\"variables\":[true,false,1]}",
            TaskResultPayload.class));
    }

    @Test
    void recordWritesRepairedBitsIntoNewSetsOfTheVariablesLengths() {
        BinarySolution s = binarySolution(1, "101", "00110");

        TaskController.record(s, result(List.of(2.0), List.of(), List.<Number>of(0, 1, 1, 1, 0, 0, 0, 1)));

        assertEquals("[011, 10001]", s.variables().toString());
        assertEquals(3, s.variables().get(0).getBinarySetLength());
        assertArrayEquals(new double[] {2.0}, s.objectives());
    }

    @Test
    void emptyVariablesListMeansKeepOriginalVariables() {
        IntegerSolution s = intSolution(1, 0, 5, 6);
        assertNull(TaskController.rejectionReason(result(List.of(1.0), List.of(), new ArrayList<>()), s));
    }

    @Test
    void lamarckianVariablesOutsideTheBoundsAreRejectedBeforeAnyWrite() {
        IntegerSolution integer = intSolution(1, 0, 5, 6);
        String r = TaskController.rejectionReason(result(List.of(1.0), List.of(), List.<Number>of(1, 1001)), integer);
        assertEquals("variables[1] = 1001 is outside the bounds [-1000, 1000] of its variable", r);

        DoubleSolution real = doubleSolution(1, 0, 0, 0);
        r = TaskController.rejectionReason(result(List.of(1.0), List.of(), List.<Number>of(-1000.5, 0)), real);
        assertNotNull(r);
        assertTrue(r.startsWith("variables[0] = -1000.5 is outside the bounds"), r);

        CompositeSolution mixed = new CompositeSolution(List.of(intSolution(1, 0, 1, 2), doubleSolution(1, 0, 0, 0)));
        r = TaskController.rejectionReason(result(List.of(1.0), List.of(), List.<Number>of(1, 2, 0.5, 2000.0)), mixed);
        assertNotNull(r);
        assertTrue(r.startsWith("variables[3] = 2000.0"), "positions count across segments: " + r);

        assertNull(TaskController.rejectionReason(result(List.of(1.0), List.of(), List.<Number>of(-1000, 1000)), integer),
            "the bounds themselves are allowed");
        assertEquals(List.of(5, 6), integer.variables(), "validation must not modify the solution");
    }

    @Test
    void invalidResultIsAnsweredAsALateOneOnceNoMoreResultsAreNeeded() {
        String reason = "objectives[0] is not finite: NaN";

        ResponseEntity<TaskRejectionPayload> late = TaskController.invalidResultAnswer(7, reason, false, true);
        assertEquals(404, late.getStatusCode().value(), "after a stop or the end the master no longer expects it");
        assertNull(late.getBody(), "the bodiless 404 of any late result");

        ResponseEntity<TaskRejectionPayload> counted = TaskController.invalidResultAnswer(7, reason, true, false);
        assertEquals(422, counted.getStatusCode().value());
        assertEquals(new TaskRejectionPayload(7, reason), counted.getBody());

        assertEquals(422, TaskController.invalidResultAnswer(7, reason, false, false).getStatusCode().value(),
            "ignored because another worker holds the task: still a rejection");
        assertEquals(422, TaskController.invalidResultAnswer(7, reason, true, true).getStatusCode().value(),
            "counted just before a stop: the worker learns why");
    }

    @Test
    void recordWritesTheRepairedVariablesThenObjectivesAndConstraints() {
        DoubleSolution s = doubleSolution(2, 1, 0, 0);

        TaskController.record(s, result(List.of(1.5, -2.5), List.of(-0.25), List.<Number>of(3, 4.5)));

        assertEquals(List.of(3.0, 4.5), s.variables());
        assertArrayEquals(new double[] {1.5, -2.5}, s.objectives());
        assertArrayEquals(new double[] {-0.25}, s.constraints());
    }

    @Test
    void recordOfAnInvalidResultWritesNothing() {
        DoubleSolution s = doubleSolution(2, 0, 1, 2);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> TaskController.record(s, result(List.of(1.5, Double.NaN), List.of(), List.<Number>of(3, 4))));

        assertTrue(e.getMessage().contains("objectives[1]"), e.getMessage());
        assertEquals(List.of(1.0, 2.0), s.variables(), "not even the variables, although they were valid");
        assertArrayEquals(new double[2], s.objectives());
    }

    // ── GET /next payload of a solution that cannot travel ────────────────────

    @Test
    void solutionWithANonFiniteVariableIsRejectedInsteadOfSentAsAJsonString() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> TaskController.toPayload(1, doubleSolution(1, 0, 0.5, Double.NaN)));
        assertTrue(e.getMessage().startsWith("variables[1] is not finite: NaN"), e.getMessage());

        CompositeSolution mixed = new CompositeSolution(List.of(
            intSolution(1, 0, 1, 2), doubleSolution(1, 0, 0.5, Double.POSITIVE_INFINITY)));
        e = assertThrows(IllegalArgumentException.class, () -> TaskController.toPayload(2, mixed));
        assertTrue(e.getMessage().startsWith("variables[3] is not finite: Infinity"), e.getMessage());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void solutionWithANullOrForeignVariableIsRejectedInsteadOfSentAsJsonNull() {
        IntegerSolution withNull = intSolution(1, 0, 1, 2);
        ((List) withNull.variables()).set(1, null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> TaskController.toPayload(1, withNull));
        assertEquals("variables[1] is null", e.getMessage());

        IntegerSolution withDouble = intSolution(1, 0, 1, 2);
        ((List) withDouble.variables()).set(0, 2.5);
        e = assertThrows(IllegalArgumentException.class, () -> TaskController.toPayload(2, withDouble),
            "an IllegalArgumentException, which the controller turns into a failed evaluation, "
                + "not a ClassCastException later");
        assertTrue(e.getMessage().startsWith("variables[0] = 2.5 is a Double"), e.getMessage());
    }

    @Test
    void segmentSizesCountTheValuesOfEachSegment() {
        assertNull(TaskController.toPayload(1, intSolution(1, 0, 1, 2)).segmentSizes());
        assertEquals(List.of(2, 3), TaskController.toPayload(2, new CompositeSolution(
            List.of(intSolution(1, 0, 1, 2), doubleSolution(1, 0, 1, 2, 3)))).segmentSizes());
        assertEquals(List.of(2, 4), TaskController.toPayload(3, new CompositeSolution(
            List.of(intSolution(1, 0, 1, 2), binarySolution(1, "1", "011")))).segmentSizes(), "bits, not variables");
    }

    @Test
    void binaryVariableOfNoBitsIsRejectedInsteadOfSent() {
        BinarySolution s = binarySolution(1, "101", "1");
        s.variables().set(1, new BinarySet(0));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> TaskController.toPayload(1, s));
        assertEquals("variables[3] is a BinarySet of 0 bits; a binary variable needs at least one", e.getMessage());
    }

    @Test
    void compatibilityConstructorsOmitTheNewFields() {
        TaskPayload flat = new TaskPayload(1, Arrays.asList(1, 2, 3));
        assertNull(flat.segmentSizes());
        assertNull(flat.encoding());
        assertNull(flat.segmentEncodings());
        assertNull(flat.bitsPerVariable());
        assertEquals(List.of(1, 2, 3), flat.variables());

        TaskPayload composite = new TaskPayload(2, List.of(1, 2, 3), List.of(2, 1));
        assertEquals(List.of(2, 1), composite.segmentSizes());
        assertNull(composite.encoding());
        assertNull(composite.bitsPerVariable());

        TaskPayload mixed = new TaskPayload(3, List.of(1, 0.5), List.of(1, 1), "mixed", List.of("int", "double"));
        assertEquals("mixed", mixed.encoding());
        assertNull(mixed.bitsPerVariable(), "the five components of 1.1 and 1.2 cannot describe a binary variable");
    }
}
