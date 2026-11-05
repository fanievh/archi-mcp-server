package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.emf.ecore.EObject;

import com.archimatetool.model.IDiagramModelArchimateComponent;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IProperties;
import com.archimatetool.model.IProperty;

import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * Says when a label expression reads a property the object it is drawn on does not carry.
 *
 * <p>Archi draws a {@code ${property:KEY}} token whose key the object does not hold as an empty
 * string, so a template naming a key that is misspelled, differently cased or simply not set yet is
 * accepted, stored and read back verbatim, and the label then shows nothing where the value was
 * meant to be. Nothing in the write or the read-back says so. This class is that saying.</p>
 *
 * <p><strong>Whose properties.</strong> Archi's renderer reads them from the object it resolves:
 * an element's box to the element itself, a view reference to the view it references, anything else
 * (a note, a group) to the diagram object. The lookup is an exact, case-sensitive key match.</p>
 *
 * <p><strong>What is checked, and what is not.</strong> Only the unprefixed {@code ${property:KEY}}
 * form. A prefixed form ({@code $model{property:…}}, {@code $parent{…}}, {@code $source{…}},
 * {@code $view{…}} and the connection forms) reads another object's properties, and
 * {@code ${properties:SEP:KEY}} matches keys differently; neither is checked, and every message
 * says so, so the absence of a warning is never read as a check of those forms.</p>
 *
 * <p>A warning, never a refusal: a template may name a property the caller is about to add.</p>
 */
public final class LabelExpressionPropertyCheck {

    /** The most ids a multi-object warning lists. The count in its message is always complete. */
    static final int MAX_LISTED_IDS = 10;

    /**
     * Archi's own property-value pattern with no prefix between {@code $} and <code>{</code>. The key
     * runs to the first closing brace and may not contain the start of another expression.
     */
    private static final Pattern UNPREFIXED_PROPERTY =
            Pattern.compile("\\$\\{property:((?:(?!\\$[^{}]*\\{)[^}])+)\\}");

    private static final String SCOPE_CLAUSE =
            " Only unprefixed ${property:KEY} tokens are checked: prefixed forms such as"
            + " $model{property:KEY}, $parent{…}, $source{…} and $view{…}, and ${properties:SEP:KEY},"
            + " are not.";

    private static final String MIXED_REMEDY_CLAUSE =
            " Correct the key, or add the property where it is read: update-element sets one on an"
            + " element, and a note or a group takes one only in Archi's Properties view.";

    private LabelExpressionPropertyCheck() {
    }

    /** The unprefixed property keys an expression reads, in order of first appearance. */
    static List<String> propertyKeys(String expression) {
        Set<String> keys = new LinkedHashSet<>();
        if (expression != null) {
            Matcher m = UNPREFIXED_PROPERTY.matcher(expression);
            while (m.find()) {
                keys.add(m.group(1));
            }
        }
        return List.copyOf(keys);
    }

    /** The object whose properties Archi reads for a label drawn on {@code obj}. */
    static EObject propertyOwnerOf(IDiagramModelObject obj) {
        if (obj instanceof IDiagramModelArchimateComponent component) {
            return component.getArchimateConcept();
        }
        if (obj instanceof IDiagramModelReference reference) {
            return reference.getReferencedModel();
        }
        return obj;
    }

    /** The keys of {@code keys} the object's label cannot resolve, in the order given. */
    static List<String> missingKeys(IDiagramModelObject obj, List<String> keys) {
        EObject owner = propertyOwnerOf(obj);
        if (!(owner instanceof IProperties holder)) {
            return keys;
        }
        Set<String> held = new LinkedHashSet<>();
        for (IProperty property : holder.getProperties()) {
            held.add(property.getKey());
        }
        List<String> missing = new ArrayList<>();
        for (String key : keys) {
            if (!held.contains(key)) {
                missing.add(key);
            }
        }
        return missing;
    }

    /**
     * The warning for one object about to carry {@code expression}, or an empty list. A null or
     * empty expression (no change, or a clear) warns nothing, and so does a null {@code arm}: it
     * marks a caller inside a larger pass that reports for itself.
     */
    public static List<StructuredWarningDto> forObject(IDiagramModelObject obj, String expression,
            DispatchArm arm) {
        if (obj == null || arm == null || expression == null || expression.isEmpty()) {
            return List.of();
        }
        List<String> missing = missingKeys(obj, propertyKeys(expression));
        if (missing.isEmpty()) {
            return List.of();
        }
        String message = "The label expression reads " + keyList(missing) + ", which "
                + describe(obj) + " does not carry" + consequence(arm, missing.size())
                + remedyClause(remedyToolOf(obj)) + SCOPE_CLAUSE;
        return List.of(new StructuredWarningDto(
                StructuredWarningCodes.LABEL_EXPRESSION_PROPERTY_MISSING, message,
                remedyToolOf(obj), List.of(obj.getId())));
    }

    /**
     * One warning per key that at least one of {@code objects} does not carry, each naming how many
     * lack it and listing up to {@link #MAX_LISTED_IDS} of their view-object ids.
     */
    public static List<StructuredWarningDto> forObjects(List<? extends IDiagramModelObject> objects,
            String expression, DispatchArm arm) {
        if (objects == null || objects.isEmpty() || arm == null || expression == null || expression.isEmpty()) {
            return List.of();
        }
        List<String> keys = propertyKeys(expression);
        if (keys.isEmpty()) {
            return List.of();
        }
        Map<String, List<IDiagramModelObject>> lacking = new LinkedHashMap<>();
        for (String key : keys) {
            lacking.put(key, new ArrayList<>());
        }
        for (IDiagramModelObject obj : objects) {
            for (String key : missingKeys(obj, keys)) {
                lacking.get(key).add(obj);
            }
        }
        List<StructuredWarningDto> warnings = new ArrayList<>();
        for (Map.Entry<String, List<IDiagramModelObject>> entry : lacking.entrySet()) {
            List<IDiagramModelObject> without = entry.getValue();
            if (without.isEmpty()) {
                continue;
            }
            String remedy = sharedRemedyTool(without);
            List<String> ids = new ArrayList<>();
            for (IDiagramModelObject obj : without.subList(0, Math.min(MAX_LISTED_IDS, without.size()))) {
                ids.add(obj.getId());
            }
            String listed = without.size() > MAX_LISTED_IDS
                    ? " The first " + MAX_LISTED_IDS + " are listed in remediationViolatorIds."
                    : " They are listed in remediationViolatorIds.";
            String message = "The label expression reads " + keyList(List.of(entry.getKey()))
                    + ", which " + without.size() + " of the " + objects.size()
                    + " objects it was applied to do not carry" + consequence(arm, 1) + listed
                    + (remedy == null && without.stream().anyMatch(o -> remedyToolOf(o) != null) ? MIXED_REMEDY_CLAUSE
                            : remedyClause(remedy)) + SCOPE_CLAUSE;
            warnings.add(new StructuredWarningDto(
                    StructuredWarningCodes.LABEL_EXPRESSION_PROPERTY_MISSING, message, remedy, ids));
        }
        return List.copyOf(warnings);
    }

    private static String keyList(List<String> keys) {
        StringBuilder sb = new StringBuilder(keys.size() == 1 ? "property " : "properties ");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sb.append(i == keys.size() - 1 ? " and " : ", ");
            }
            sb.append(CallerText.quoted(keys.get(i)));
        }
        return sb.toString();
    }

    private static String consequence(DispatchArm arm, int count) {
        String it = count == 1 ? "it" : "them";
        String token = count == 1 ? "the token" : "each such token";
        return switch (arm) {
            case APPLIED -> ", so Archi draws " + token + " as an empty string. Keys are matched"
                    + " exactly, case included.";
            case QUEUED -> " now. Nothing in this call has been applied yet, and an operation"
                    + " queued beside it may still add " + it + "; if none does, Archi draws "
                    + token + " as an empty string. Keys are matched exactly, case included.";
            case AWAITING_APPROVAL -> " now. The change is waiting on the human's decision; if "
                    + it + " is still missing when it is approved, Archi draws " + token
                    + " as an empty string. Keys are matched exactly, case included.";
        };
    }

    private static String describe(IDiagramModelObject obj) {
        EObject owner = propertyOwnerOf(obj);
        if (obj instanceof IDiagramModelArchimateComponent && owner != null) {
            return "the element shown by view object " + CallerText.quoted(obj.getId());
        }
        if (obj instanceof IDiagramModelReference) {
            return "the view referenced by view object " + CallerText.quoted(obj.getId());
        }
        if (!(owner instanceof IProperties)) {
            return "view object " + CallerText.quoted(obj.getId()) + ", which cannot carry"
                    + " properties,";
        }
        return "view object " + CallerText.quoted(obj.getId());
    }

    /** The tool that sets a property on what the label reads, or null when no tool does. */
    static String remedyToolOf(IDiagramModelObject obj) {
        if (obj instanceof IDiagramModelArchimateComponent) {
            return "update-element";
        }
        if (obj instanceof IDiagramModelReference) {
            return "update-view";
        }
        return null;
    }

    private static String sharedRemedyTool(List<IDiagramModelObject> objects) {
        String tool = remedyToolOf(objects.get(0));
        for (IDiagramModelObject obj : objects) {
            String other = remedyToolOf(obj);
            if (tool == null ? other != null : !tool.equals(other)) {
                return null;
            }
        }
        return tool;
    }

    private static String remedyClause(String tool) {
        if (tool == null) {
            return " Correct the key, or remove the token: no tool on this server sets a property"
                    + " on a note or a group, so one has to be added in Archi's Properties view.";
        }
        return " Correct the key, or add the property with " + tool + ".";
    }
}
