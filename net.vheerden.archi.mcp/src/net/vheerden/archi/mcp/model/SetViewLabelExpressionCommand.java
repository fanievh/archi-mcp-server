package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.SetViewLabelExpressionResultDto;

/**
 * Applies one label-expression template to every eligible diagram object on a view
 * in a single, atomic, reversible command.
 *
 * <p>This is the bulk counterpart of the per-object label rail in
 * {@link UpdateViewObjectCommand}: it writes the same {@code IFeatures} entry under
 * {@link UpdateViewObjectCommand#LABEL_EXPRESSION_FEATURE} (Archi's renderer key), with
 * identical set/clear semantics — a non-empty value is stored verbatim (Archi owns the
 * {@code ${name}} / {@code ${property:...}} token grammar), an empty value clears the
 * feature. The whole fan-out is one command, so it is one undo unit and counts as one
 * operation in a bulk-mutate batch.</p>
 *
 * <p><b>Eligibility.</b> By default only ArchiMate element objects
 * ({@link IDiagramModelArchimateObject}) are stamped; an optional {@code objectTypes}
 * filter may widen this to notes and groups. Regardless of type, an object is skipped
 * unless it has a non-blank name — the template the agent supplies references the object
 * name, so a nameless object would render a half-empty glyph. Skipping it keeps the view
 * clean and is the documented no-ghost rule.</p>
 */
public final class SetViewLabelExpressionCommand extends Command {

    /**
     * Known object-kind tokens for the optional {@code objectTypes} filter. The ordered list
     * backs deterministic error messages ({@code Set.of} randomizes iteration order per JVM run);
     * the set is the O(1) membership check.
     */
    private static final List<String> KNOWN_TYPES_ORDERED = List.of("element", "note", "group");
    private static final Set<String> KNOWN_TYPES = Set.copyOf(KNOWN_TYPES_ORDERED);

    private final List<IDiagramModelObject> targets;
    private final List<String> oldValues;
    private final String newValue;

    private SetViewLabelExpressionCommand(List<IDiagramModelObject> targets, String newValue) {
        this.targets = targets;
        this.newValue = newValue;
        this.oldValues = new ArrayList<>(targets.size());
        for (IDiagramModelObject obj : targets) {
            oldValues.add(obj.getFeatures()
                    .getString(UpdateViewObjectCommand.LABEL_EXPRESSION_FEATURE, null));
        }
    }

    @Override
    public boolean canExecute() {
        return true;
    }

    @Override
    public void execute() {
        for (IDiagramModelObject obj : targets) {
            applyLabelExpression(obj, newValue);
        }
    }

    @Override
    public void undo() {
        for (int i = 0; i < targets.size(); i++) {
            applyLabelExpression(targets.get(i), oldValues.get(i));
        }
    }

    /**
     * Writes (or clears, when {@code value} is null) the label-expression feature on one
     * object — byte-for-byte the same mechanism {@link UpdateViewObjectCommand} uses.
     */
    private static void applyLabelExpression(IDiagramModelObject obj, String value) {
        if (value == null) {
            obj.getFeatures().remove(UpdateViewObjectCommand.LABEL_EXPRESSION_FEATURE);
        } else {
            obj.getFeatures().putString(UpdateViewObjectCommand.LABEL_EXPRESSION_FEATURE, value);
        }
    }

    /**
     * Validates parameters, resolves the view, selects eligible objects, and builds the
     * prepared mutation (command + per-view result summary) for the bulk dispatcher.
     *
     * @param model  the active model (resolved by the caller from the session)
     * @param params the operation params: required {@code viewId}, required {@code labelExpression}
     *               (empty string clears), optional {@code objectTypes} (subset of element/note/group)
     */
    static PreparedMutation<SetViewLabelExpressionResultDto> prepare(
            IArchimateModel model, Map<String, Object> params) {

        IArchimateDiagramModel view = resolveView(model, params);
        String template = InputValidation.reject(requireLabelExpression(params), "labelExpression");
        Set<String> types = parseObjectTypes(params);
        String value = UpdateViewObjectCommand.emptyToNull(template);

        List<IDiagramModelObject> matched = new ArrayList<>();
        int[] visited = {0};
        // The no-ghost name guard applies only when SETTING a template (so ${name} resolves).
        // When CLEARING (empty template → null value), removing a stale feature carries no
        // ghost risk, so nameless objects with a pre-existing label are still cleared.
        collect(view, types, value != null, matched, visited);
        int skipped = visited[0] - matched.size();

        SetViewLabelExpressionCommand command = new SetViewLabelExpressionCommand(matched, value);
        SetViewLabelExpressionResultDto result = new SetViewLabelExpressionResultDto(
                view.getId(), view.getName(), template, matched.size(), skipped);
        return new PreparedMutation<>(command, result, view.getId());
    }

    // ---- preparation helpers ----

    private static IArchimateDiagramModel resolveView(IArchimateModel model, Map<String, Object> params) {
        Object raw = params.get("viewId");
        if (!(raw instanceof String viewId) || viewId.isBlank()) {
            throw new ModelAccessException(
                    "Missing required parameter 'viewId'", ErrorCode.INVALID_PARAMETER);
        }
        EObject obj = ArchimateModelUtils.getObjectByID(model, viewId);
        if (!(obj instanceof IArchimateDiagramModel view)) {
            throw new ModelAccessException(
                    "View not found: " + viewId,
                    ErrorCode.VIEW_NOT_FOUND,
                    null,
                    "Use get-views to find valid view IDs",
                    null);
        }
        return view;
    }

    /**
     * The template must be present as a string. Empty string is valid and means "clear";
     * an absent key is an error (a no-argument operation is meaningless).
     */
    private static String requireLabelExpression(Map<String, Object> params) {
        Object raw = params.get("labelExpression");
        if (!(raw instanceof String template)) {
            throw new ModelAccessException(
                    "Missing required parameter 'labelExpression' "
                            + "(use \"\" to clear label expressions on the view)",
                    ErrorCode.INVALID_PARAMETER);
        }
        return template;
    }

    private static Set<String> parseObjectTypes(Map<String, Object> params) {
        Object raw = params.get("objectTypes");
        if (raw == null) {
            return Set.of("element");
        }
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new ModelAccessException(
                    "Parameter 'objectTypes' must be a non-empty array of "
                            + KNOWN_TYPES_ORDERED + "; omit it to default to element only",
                    ErrorCode.INVALID_PARAMETER);
        }
        Set<String> types = new LinkedHashSet<>();
        for (Object o : list) {
            String t = o instanceof String s ? s.trim().toLowerCase() : null;
            if (t == null || !KNOWN_TYPES.contains(t)) {
                throw new ModelAccessException(
                        "Unknown object type '" + o + "' in 'objectTypes'. Supported: "
                                + KNOWN_TYPES_ORDERED,
                        ErrorCode.INVALID_PARAMETER);
            }
            types.add(t);
        }
        return types;
    }

    /** Depth-first walk of every diagram object on the view, counting visits and matches. */
    private static void collect(IDiagramModelContainer container, Set<String> types,
            boolean requireName, List<IDiagramModelObject> matched, int[] visited) {
        for (IDiagramModelObject child : container.getChildren()) {
            visited[0]++;
            if (matches(child, types, requireName)) {
                matched.add(child);
            }
            if (child instanceof IDiagramModelContainer nested) {
                collect(nested, types, requireName, matched, visited);
            }
        }
    }

    /**
     * Eligible when the object's kind is in the requested set. When {@code requireName} is true
     * (a template is being set), the object must also have a non-blank name so {@code ${name}}
     * resolves — the no-ghost rule. When clearing, the name guard is skipped.
     */
    private static boolean matches(IDiagramModelObject obj, Set<String> types, boolean requireName) {
        boolean typeOk =
                (types.contains("element") && obj instanceof IDiagramModelArchimateObject)
                || (types.contains("note") && obj instanceof IDiagramModelNote)
                || (types.contains("group") && obj instanceof IDiagramModelGroup);
        if (!typeOk) {
            return false;
        }
        if (!requireName) {
            return true;
        }
        String name = obj.getName();
        return name != null && !name.isBlank();
    }
}
