package net.vheerden.archi.mcp.model;

import java.util.LinkedHashMap;
import java.util.Map;

import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IProperties;
import com.archimatetool.model.IProperty;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * Writes caller-supplied documentation and properties onto a freshly created model object.
 *
 * <p>The write-direction counterpart to {@link DtoMapper#convertProperties}, which reads the same
 * list back out. {@link IProperties} is the common supertype of {@code IFolder} and
 * {@code IArchimateConcept} — and therefore of both {@code IArchimateElement} and
 * {@code IArchimateRelationship} — so the element, folder and relationship create paths share one
 * implementation instead of three copies free to drift apart.</p>
 *
 * <p>Both methods are safe to call inside a prepare, before the command that attaches the object
 * has executed: they touch only the object's own fields, never a cross-reference. That is the same
 * boundary {@code applySemanticAttributesToRelationship} observes, and it is why {@code connect()}
 * stays deferred while these do not.</p>
 */
final class ConceptMetadata {

    /** The prefix every {@code source} key is stored under, as both create tools advertise. */
    static final String SOURCE_PREFIX = "mcp.source.";

    private ConceptMetadata() {
    }

    /**
     * Applies documentation and properties to a concept. Documentation is stored only when it holds
     * a non-blank value, so a caller that sends whitespace does not bury an empty string in the
     * model where a reader would have to distinguish it from an absent one.
     *
     * <p>Folders deliberately do not use this method — they accept any non-null documentation, and
     * routing them through the blank guard here would silently change what they store.</p>
     */
    static void apply(IArchimateConcept concept, String documentation,
            Map<String, String> properties) {
        if (documentation != null && !documentation.isBlank()) {
            concept.setDocumentation(documentation);
        }
        applyProperties(concept, properties);
    }

    /**
     * Appends each entry as an {@code IProperty} on the target, preserving map iteration order.
     * A null map applies nothing, which is how every create path treats an omitted parameter.
     */
    static void applyProperties(IProperties target, Map<String, String> properties) {
        if (properties == null) {
            return;
        }
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            IProperty prop = IArchimateFactory.eINSTANCE.createProperty();
            prop.setKey(entry.getKey());
            prop.setValue(entry.getValue());
            target.getProperties().add(prop);
        }
    }

    /**
     * Merges source traceability properties into the element properties map.
     * Source entries are prefixed with "mcp.source." (e.g., "mcp.source.tool").
     *
     * <p>Two inputs are refused rather than coped with, both for the same reason. A key that
     * already carries the prefix would otherwise be prefixed a second time and stored as
     * {@code mcp.source.mcp.source.tool}; a key whose prefixed form matches an entry the caller
     * also passed in {@code properties} would otherwise overwrite it. Either way the model ends up
     * holding a provenance value the caller never asked for, behind a success response — and a
     * caller that cannot see the model has nothing but that response to go on. Refusing names the
     * offending key while the caller still has it in hand, which is the same disposition
     * {@code ImageOperations.requireUsableExtension} takes for a filename that cannot name a
     * file.</p>
     *
     * <p>A null key or null value is skipped, as it always has been. That is not the same kind of
     * input: skipping writes nothing, so there is no surprising value to attribute later.</p>
     *
     * @param properties existing properties (may be null)
     * @param source     source traceability map (may be null)
     * @return merged properties map, or original if source is null
     * @throws ModelAccessException {@code INVALID_PARAMETER} for either refusal above
     */
    static Map<String, String> mergeSourceProperties(Map<String, String> properties,
            Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return properties;
        }
        Map<String, String> merged = new LinkedHashMap<>();
        if (properties != null) {
            merged.putAll(properties);
        }
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                merged.put(requireMergeableSourceKey(entry.getKey(), merged), entry.getValue());
            }
        }
        return merged;
    }

    /**
     * Returns the property key a source entry will be stored under, refusing the two inputs that
     * would make that key wrong. {@code merged} holds the caller's own properties at this point
     * (source keys are unique within their map, so nothing this loop has already added can be what
     * a later key collides with).
     */
    private static String requireMergeableSourceKey(String sourceKey, Map<String, String> merged) {
        if (sourceKey.startsWith(SOURCE_PREFIX)) {
            // The remainder is what the caller should have sent. When it is blank the key is the
            // bare prefix and names nothing, so there is no bare key to suggest — telling them to
            // resend "" would rebuild the very key being refused.
            String bare = sourceKey.substring(SOURCE_PREFIX.length());
            throw new ModelAccessException(
                    "The source key '" + sourceKey + "' already carries the '" + SOURCE_PREFIX
                            + "' prefix, so prefixing it would store the property '"
                            + SOURCE_PREFIX + sourceKey + "'",
                    ErrorCode.INVALID_PARAMETER,
                    "source key: '" + sourceKey + "'",
                    (bare.isBlank()
                            ? "That key is the bare prefix and names nothing after it. Send the "
                                    + "attribute you are recording — 'tool' or 'file', say — which "
                                    + "is stored as '" + SOURCE_PREFIX + "tool'."
                            : "Every source key is prefixed automatically, so pass the bare key — '"
                                    + bare + "' rather than '" + sourceKey + "'.")
                            + " To set a property whose literal name begins with the prefix, put it "
                            + "in properties instead of source.",
                    null);
        }
        String prefixed = SOURCE_PREFIX + sourceKey;
        if (merged.containsKey(prefixed)) {
            throw new ModelAccessException(
                    "The source key '" + sourceKey + "' and the properties entry '" + prefixed
                            + "' both write the property '" + prefixed + "'",
                    ErrorCode.INVALID_PARAMETER,
                    "conflicting property key: '" + prefixed + "'",
                    "The two maps disagree about one key and there is no precedence rule to fall "
                            + "back on, so neither value is silently discarded. Remove '" + prefixed
                            + "' from properties and keep the source entry '" + sourceKey
                            + "', or drop that source entry and keep the properties value.",
                    null);
        }
        return prefixed;
    }
}
