package es.unex.jdisrest.distributed.rest;

import es.unex.jdisrest.distributed.rest.dto.TaskPayload;
import es.unex.jdisrest.distributed.rest.dto.TaskResultPayload;
import es.unex.jdisrest.util.SolutionVariables;
import org.junit.jupiter.api.Test;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.doublesolution.impl.DefaultDoubleSolution;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.solution.integersolution.impl.DefaultIntegerSolution;
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
 * each solution shape, backwards compatibility of the integer format, and the
 * validation applied to results before they touch the master-held solution.
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
    void emptyVariablesListMeansKeepOriginalVariables() {
        IntegerSolution s = intSolution(1, 0, 5, 6);
        assertNull(TaskController.rejectionReason(result(List.of(1.0), List.of(), new ArrayList<>()), s));
    }

    @Test
    void segmentSizesAreUnchanged() {
        assertNull(TaskController.segmentSizes(intSolution(1, 0, 1, 2)));
        assertEquals(List.of(2, 3), TaskController.segmentSizes(new CompositeSolution(
            List.of(intSolution(1, 0, 1, 2), doubleSolution(1, 0, 1, 2, 3)))));
    }

    @Test
    void compatibilityConstructorsOmitTheNewFields() {
        TaskPayload flat = new TaskPayload(1, Arrays.asList(1, 2, 3));
        assertNull(flat.segmentSizes());
        assertNull(flat.encoding());
        assertNull(flat.segmentEncodings());
        assertEquals(List.of(1, 2, 3), flat.variables());

        TaskPayload composite = new TaskPayload(2, List.of(1, 2, 3), List.of(2, 1));
        assertEquals(List.of(2, 1), composite.segmentSizes());
        assertNull(composite.encoding());
    }
}
