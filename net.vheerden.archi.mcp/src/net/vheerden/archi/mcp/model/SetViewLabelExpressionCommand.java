package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;

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
 *
 * <p><b>Staged work.</b> {@code bulk-mutate} prepares every operation before it applies any, and a
 * nested call is queued behind the open batch's earlier work, so the live view is the view as it was
 * before all of that. The targets are therefore read through {@link StagedViewState}: the objects the
 * earlier operations place on the view are stamped, the ones they remove are not, and a view they
 * create resolves. The value each target held is read when the command runs, not when it was built,
 * so undo restores what an earlier command in the same batch wrote.</p>
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
    }

    @Override
    public boolean canExecute() {
        return true;
    }

    /**
     * Captures each target's value as it stands now, not when the command was built: an earlier command
     * in the same batch may have written it since, and undo must put back what this command replaced.
     */
    @Override
    public void execute() {
        oldValues.clear();
        for (IDiagramModelObject obj : targets) {
            oldValues.add(obj.getFeatures().getString(UpdateViewObjectCommand.LABEL_EXPRESSION_FEATURE, null));
            applyLabelExpression(obj, newValue);
        }
    }

    @Override
    public void undo() {
        // Only the objects execute reached: it records each old value just before writing over it, so a
        // run that stopped part-way, or never started, has fewer old values than targets.
        for (int i = 0; i < oldValues.size(); i++) {
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
     * As {@link #prepare(StagedViewState, IArchimateModel, Map, Map, DispatchArm)} for an operation
     * with nothing staged ahead of it: no open batch and no earlier operation in the call.
     */
    static PreparedMutation<SetViewLabelExpressionResultDto> prepare(
            IArchimateModel model, Map<String, Object> params) {
        return prepare(StagedViewState.of(null, null, null), model, null, params, DispatchArm.QUEUED);
    }

    /**
     * Validates parameters, resolves the view, selects eligible objects, and builds the prepared
     * mutation (command + per-view result summary) for the bulk dispatcher. The operation exists only
     * inside {@code bulk-mutate}, which prepares every operation before it applies any, so it is never
     * on the applied arm when its warnings are written.
     *
     * <p>The view is read as the work staged ahead of this operation will leave it: a view an earlier
     * operation in the call created, or the open batch queued, resolves as a live one does, and the
     * objects stamped are those that will be on it when this operation runs ({@link
     * StagedViewState#objectsOn}), so {@code appliedCount} is the number the commit really stamps.</p>
     *
     * @param staged       the work staged ahead of this operation
     * @param model        the active model (resolved by the caller from the session)
     * @param createdViews the views created earlier in this bulk call, or null outside one
     * @param params       the operation params: required {@code viewId}, required {@code labelExpression}
     *                     (empty string clears), optional {@code objectTypes} (subset of element/note/group)
     * @param arm          the arm the warnings are worded for
     */
    static PreparedMutation<SetViewLabelExpressionResultDto> prepare(StagedViewState staged,
            IArchimateModel model, Map<Integer, IArchimateDiagramModel> createdViews,
            Map<String, Object> params, DispatchArm arm) {

        IArchimateDiagramModel view = resolveView(staged, model, createdViews, params);
        String template = InputValidation.reject(requireLabelExpression(params), "labelExpression");
        Set<String> types = parseObjectTypes(params);
        String value = UpdateViewObjectCommand.emptyToNull(template);

        List<IDiagramModelObject> onView = staged.objectsOn(view);
        List<IDiagramModelObject> matched = new ArrayList<>();
        for (IDiagramModelObject obj : onView) {
            // The no-ghost name guard applies only when SETTING a template (so ${name} resolves).
            // When CLEARING (empty template → null value), removing a stale feature carries no
            // ghost risk, so nameless objects with a pre-existing label are still cleared.
            if (matches(staged, obj, types, value != null)) {
                matched.add(obj);
            }
        }
        int skipped = onView.size() - matched.size();

        SetViewLabelExpressionCommand command = new SetViewLabelExpressionCommand(matched, value);
        SetViewLabelExpressionResultDto result = new SetViewLabelExpressionResultDto(
                view.getId(), view.getName(), template, matched.size(), skipped,
                LabelExpressionPropertyCheck.forObjects(matched, value, arm));
        return new PreparedMutation<>(command, result, view.getId());
    }

    // ---- preparation helpers ----

    private static IArchimateDiagramModel resolveView(StagedViewState staged, IArchimateModel model,
            Map<Integer, IArchimateDiagramModel> createdViews, Map<String, Object> params) {
        ToolArguments.requireTextType(params, "viewId");
        Object raw = params.get("viewId");
        if (!(raw instanceof String viewId) || viewId.isBlank()) {
            throw ParamNameDiagnostics.missingBulkParameter(params, "viewId");
        }
        IArchimateDiagramModel view = staged.viewOrNull(model, viewId, createdViews);
        if (view == null) {
            throw new ModelAccessException(
                    "View not found: " + CallerText.bare(viewId),
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
        ToolArguments.requireTextType(params, "labelExpression");
        Object raw = params.get("labelExpression");
        if (!(raw instanceof String template)) {
            throw new ModelAccessException(
                    "Missing required parameter 'labelExpression' "
                            + "(use \"\" to clear label expressions on the view)",
                    ErrorCode.INVALID_PARAMETER, null,
                    "Provide a 'labelExpression' string — use \"\" to clear label expressions "
                            + "on the view",
                    null);
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
            String t = o instanceof String s ? s.trim().toLowerCase(Locale.ROOT) : null;
            if (t == null || !KNOWN_TYPES.contains(t)) {
                throw new ModelAccessException(
                        "Unknown object type " + CallerText.quoted(String.valueOf(o)) + " in 'objectTypes'. Supported: "
                                + KNOWN_TYPES_ORDERED,
                        ErrorCode.INVALID_PARAMETER);
            }
            types.add(t);
        }
        return types;
    }

    /**
     * Eligible when the object's kind is in the requested set. When {@code requireName} is true
     * (a template is being set), the object must also have a non-blank name so {@code ${name}}
     * resolves — the no-ghost rule — read as the staged work leaves it ({@link StagedViewState#nameOf}).
     * When clearing, the name guard is skipped.
     */
    private static boolean matches(StagedViewState staged, IDiagramModelObject obj, Set<String> types,
            boolean requireName) {
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
        String name = staged.nameOf(obj);
        return name != null && !name.isBlank();
    }
}
