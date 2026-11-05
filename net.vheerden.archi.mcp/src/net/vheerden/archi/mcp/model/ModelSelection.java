package net.vheerden.archi.mcp.model;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import com.archimatetool.model.IArchimateModel;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.PaginationCursor;
import net.vheerden.archi.mcp.response.dto.OpenModelDto;

/**
 * Decides which open model each tool call acts on.
 *
 * <p>A call is resolved once, when it first needs a model, and keeps that answer for the rest of the
 * call, so a model opened or closed in Archi while the call runs cannot split it across two models.
 * In order:</p>
 * <ol>
 *   <li>The call names a model with {@code modelId}: that model ({@link #ADDRESSED}). An id that is not
 *       open, or that is open more than once (a copied file keeps its id), is refused.</li>
 *   <li>The session has a batch open: the model the batch is bound to ({@link #SESSION_DEFAULT}).</li>
 *   <li>The session has chosen a default model: that model ({@link #SESSION_DEFAULT}), or a refusal
 *       naming it once it is closed. The choice is an id, so a reopened file answers it again.</li>
 *   <li>Exactly one model is open: that one ({@link #SOLE_MODEL}).</li>
 *   <li>Otherwise the model active in Archi, also {@link #SESSION_DEFAULT}, but marked ambiguous:
 *       a read may follow it, and a write is refused by {@link #requireUnambiguous}, because which of
 *       several models it would change is a coin flip.</li>
 * </ol>
 *
 * <p>The scope is per thread and opened around each tool call. With no scope open, a caller's own
 * fallback (the model active in Archi) is returned untouched, which is how every path that is not a
 * tool call behaves.</p>
 *
 * <p>Holds no model object between calls: the session default is an id, and the record of which
 * model each session last acted on is an instance epoch.</p>
 */
public final class ModelSelection {

    /** The call named the model with {@code modelId}. */
    public static final String ADDRESSED = "addressed";
    /** The call named none and took the session's batch model, its default, or the model active in Archi. */
    public static final String SESSION_DEFAULT = "session-default";
    /** The call named none and exactly one model was open. */
    public static final String SOLE_MODEL = "sole-model";

    private final Supplier<IArchimateModel> guiActive;
    private final ToLongFunction<IArchimateModel> epochOf;
    private final Function<String, IArchimateModel> batchInstance;
    private volatile Supplier<List<IArchimateModel>> openModels;
    private volatile Predicate<IArchimateModel> dirty = model -> false;

    /** Each session's chosen default model, by id. No tool sets it in this release. */
    private final Map<String, String> chosen = new ConcurrentHashMap<>();
    /**
     * How long a session keeps holding a model after its last call on it. A session that has gone quiet
     * (or reconnected under a new id) stops blocking another session's undo once this passes.
     */
    static final Duration HOLD_IDLE = Duration.ofMinutes(15);

    /** The instance (epoch) each session's calls most recently acted on, and when. */
    private final Map<String, Hold> holding = new ConcurrentHashMap<>();
    private final LongPredicate isClosed;
    private volatile LongSupplier clock = System::nanoTime;

    private record Hold(long epoch, long at) {
    }
    private final ThreadLocal<Call> current = new ThreadLocal<>();

    /**
     * One tool call's resolution. Written and read only on the thread that runs the call.
     */
    public static final class Call {
        final String sessionId;
        final String requestedId;
        boolean resolved;
        IArchimateModel model;
        String resolution;
        /** Every open model, when the call named none and nothing chose between several. */
        List<IArchimateModel> candidates;
        ModelAccessException refusal;
        boolean used;
        boolean refusedAsAmbiguous;

        Call(String sessionId, String requestedId) {
            this.sessionId = sessionId;
            this.requestedId = requestedId;
        }
    }

    /**
     * @param guiActive     the model active in Archi
     * @param epochOf       the epoch of a model instance, assigned on first observation
     * @param batchInstance the open instance a session's open batch is bound to, or null
     */
    ModelSelection(Supplier<IArchimateModel> guiActive, ToLongFunction<IArchimateModel> epochOf,
            Function<String, IArchimateModel> batchInstance) {
        this(guiActive, epochOf, batchInstance, epoch -> false);
    }

    /**
     * @param isClosed whether the instance with this epoch has closed; a closed instance is never
     *                 recorded as held
     */
    ModelSelection(Supplier<IArchimateModel> guiActive, ToLongFunction<IArchimateModel> epochOf,
            Function<String, IArchimateModel> batchInstance, LongPredicate isClosed) {
        this.isClosed = Objects.requireNonNull(isClosed, "isClosed must not be null");
        this.guiActive = Objects.requireNonNull(guiActive, "guiActive must not be null");
        this.epochOf = Objects.requireNonNull(epochOf, "epochOf must not be null");
        this.batchInstance = Objects.requireNonNull(batchInstance, "batchInstance must not be null");
        this.openModels = () -> {
            IArchimateModel active = guiActive.get();
            return active == null ? List.of() : List.of(active);
        };
    }

    /** Sets the monotonic clock holdings are aged by (nanoseconds); for tests. */
    void setClock(LongSupplier nanos) {
        this.clock = Objects.requireNonNull(nanos, "nanos must not be null");
    }

    /** Sets where the open models are listed and how a model's unsaved state is read. */
    void setOpenModels(Supplier<List<IArchimateModel>> openModels, Predicate<IArchimateModel> dirty) {
        this.openModels = Objects.requireNonNull(openModels, "openModels must not be null");
        this.dirty = Objects.requireNonNull(dirty, "dirty must not be null");
    }

    // ---- the call scope ---------------------------------------------------------------------------

    /**
     * Opens the scope of a tool call on this thread, replacing anything a previous call left.
     *
     * @param sessionId   the calling session
     * @param requestedId the {@code modelId} the call carries, or null
     * @return the refusal when the call names a model that is not open, or that is open more than
     *         once; null otherwise. A call that names none is never refused here, since a read may
     *         follow the model active in Archi.
     */
    public ModelAccessException enter(String sessionId, String requestedId) {
        Call call = new Call(sessionId, requestedId == null || requestedId.isBlank() ? null : requestedId);
        current.set(call);
        if (call.requestedId == null) {
            return null;
        }
        resolve(call);
        return call.refusal;
    }

    /** Ends the call on this thread. */
    public void exit() {
        current.remove();
    }

    /** Whether a tool call is in progress on this thread. */
    public boolean inScope() {
        return current.get() != null;
    }

    /**
     * Binds this thread to {@code instance} on behalf of {@code sessionId}, for work done for a
     * stored request rather than by the call itself (an approval rebuilds its proposal against the
     * instance it was proposed on). Pass the returned value to {@link #restore}.
     */
    Call bind(String sessionId, IArchimateModel instance) {
        Call outer = current.get();
        Call bound = new Call(sessionId, null);
        bound.resolved = true;
        bound.model = instance;
        bound.resolution = ADDRESSED;
        current.set(bound);
        return outer;
    }

    /** This thread's call scope, or null outside a call; for {@link #adopt} on another thread. */
    Call scope() {
        return current.get();
    }

    /**
     * Runs this thread in {@code call}'s scope (none when null), for work done on another thread on that
     * call's behalf. Pass the returned value to {@link #restore}.
     */
    Call adopt(Call call) {
        Call outer = current.get();
        restore(call);
        return outer;
    }

    /** Puts back the scope {@link #bind} or {@link #adopt} replaced. */
    void restore(Call outer) {
        if (outer == null) {
            current.remove();
        } else {
            current.set(outer);
        }
    }

    /**
     * The model this call acts on, or {@code fallback} when no call is in progress. Null when no model
     * is open.
     *
     * @throws ModelAccessException when the call's model cannot be resolved
     */
    IArchimateModel modelForCall(IArchimateModel fallback) {
        Call call = current.get();
        if (call == null) {
            return fallback;
        }
        resolve(call);
        if (call.refusal != null) {
            throw call.refusal;
        }
        use(call);
        return call.model;
    }

    /** As {@link #modelForCall}, but null instead of a refusal. */
    IArchimateModel peekModelForCall(IArchimateModel fallback) {
        Call call = current.get();
        if (call == null) {
            return fallback;
        }
        resolve(call);
        if (call.refusal != null) {
            return null;
        }
        use(call);
        return call.model;
    }

    /**
     * Refuses a write that named no model while several are open and nothing chose between them.
     * Outside a call, and for every other resolution, this does nothing.
     */
    void requireUnambiguous(String operation) {
        Call call = current.get();
        if (call == null) {
            return;
        }
        resolve(call);
        if (call.refusal != null) {
            throw call.refusal;
        }
        if (call.candidates != null) {
            call.refusedAsAmbiguous = true;
            throw new ModelAccessException("'" + operation + "' refused: " + call.candidates.size()
                    + " models are open and the call names none, so which one it would change cannot be "
                    + "decided. Nothing was applied.", ErrorCode.MODEL_AMBIGUOUS,
                    "Open models: " + describeAll(call.candidates),
                    "Retry with modelId set to one of the ids in details (get-models lists them).", null);
        }
    }

    /**
     * The model the call acted on and how it was resolved, as {@code {id, name, resolution}}; null when
     * no call is in progress, the call never touched a model, or it was refused for its address.
     */
    public Map<String, Object> responseModel() {
        Call call = current.get();
        if (call == null || !call.used || call.model == null || call.refusal != null || call.refusedAsAmbiguous) {
            return null;
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("id", call.model.getId());
        model.put("name", call.model.getName());
        model.put("resolution", call.resolution);
        return model;
    }

    /**
     * A key naming the model instance this call reads, for keying per-session caches and version
     * tracking: empty outside a call, null when the call's model cannot be resolved.
     */
    public String cacheKey() {
        Call call = current.get();
        if (call == null) {
            return "";
        }
        resolve(call);
        if (call.refusal != null || call.model == null) {
            return null;
        }
        return String.valueOf(epochOf.applyAsLong(call.model));
    }

    /**
     * The model instance this call reads, for stamping a pagination cursor and checking one: null outside
     * a call, or when the call's model cannot be resolved. Records nothing about who holds the model:
     * naming it is not acting on it.
     */
    public PaginationCursor.CursorModel cursorModel() {
        Call call = current.get();
        if (call == null) {
            return null;
        }
        resolve(call);
        if (call.refusal != null || call.model == null) {
            return null;
        }
        return new PaginationCursor.CursorModel(call.model.getId(), call.model.getName(),
                String.valueOf(epochOf.applyAsLong(call.model)));
    }

    /** Whether a model with {@code modelId} is open in Archi. */
    public boolean isOpen(String modelId) {
        return modelId != null && open().stream().anyMatch(model -> modelId.equals(model.getId()));
    }

    // ---- the session default ----------------------------------------------------------------------

    /** Sets (or, with null, clears) a session's default model. No tool sets it in this release. */
    void choose(String sessionId, String modelId) {
        if (modelId == null) {
            chosen.remove(sessionId);
        } else {
            chosen.put(sessionId, modelId);
        }
    }

    // ---- who holds which model --------------------------------------------------------------------

    /**
     * How many sessions other than {@code sessionId} most recently acted on {@code model}, within the
     * last {@link #HOLD_IDLE}. Read live: a session that has since acted on another model, or gone quiet,
     * no longer counts.
     */
    int otherSessionsHolding(String sessionId, IArchimateModel model) {
        long epoch = epochOf.applyAsLong(model);
        long now = clock.getAsLong();
        holding.values().removeIf(held -> now - held.at() > HOLD_IDLE.toNanos());
        int count = 0;
        for (Map.Entry<String, Hold> entry : holding.entrySet()) {
            if (!entry.getKey().equals(sessionId) && entry.getValue().epoch() == epoch) {
                count++;
            }
        }
        return count;
    }

    /** A closed instance is held by nobody. */
    void onModelClosed(IArchimateModel model) {
        long epoch = epochOf.applyAsLong(model);
        holding.values().removeIf(held -> held.epoch() == epoch);
    }

    /** Forgets everything recorded for a session that has ended or been evicted. */
    public void forgetSession(String sessionId) {
        holding.remove(sessionId);
        chosen.remove(sessionId);
    }

    // ---- get-models -------------------------------------------------------------------------------

    /** Every open model, each marked when a call from {@code sessionId} that names none resolves to it. */
    List<OpenModelDto> listModels(String sessionId) {
        Call probe = new Call(sessionId, null);
        resolve(probe);
        IArchimateModel own = probe.refusal == null ? probe.model : null;
        List<OpenModelDto> listed = new ArrayList<>();
        for (IArchimateModel model : open()) {
            listed.add(new OpenModelDto(model.getId(), model.getName(),
                    model.getFile() != null ? model.getFile().getPath() : null,
                    dirty.test(model), model == own));
        }
        return listed;
    }

    // ---- resolution -------------------------------------------------------------------------------

    private void use(Call call) {
        call.used = true;
        if (call.model != null) {
            long epoch = epochOf.applyAsLong(call.model);
            if (!isClosed.test(epoch)) {
                holding.put(call.sessionId, new Hold(epoch, clock.getAsLong()));
            }
        }
    }

    private void resolve(Call call) {
        if (call.resolved) {
            return;
        }
        call.resolved = true;
        List<IArchimateModel> open = open();
        if (call.requestedId != null) {
            pick(call, call.requestedId, open, false);
            return;
        }
        IArchimateModel batch = batchInstance.apply(call.sessionId);
        if (batch != null && containsInstance(open, batch)) {
            call.model = batch;
            call.resolution = SESSION_DEFAULT;
            return;
        }
        String choice = chosen.get(call.sessionId);
        if (choice != null) {
            pick(call, choice, open, true);
            return;
        }
        if (open.size() == 1) {
            call.model = open.get(0);
            call.resolution = SOLE_MODEL;
            return;
        }
        call.model = guiActive.get();
        call.resolution = SESSION_DEFAULT;
        if (open.size() > 1) {
            call.candidates = open;
        }
    }

    private void pick(Call call, String id, List<IArchimateModel> open, boolean isDefault) {
        List<IArchimateModel> matches = new ArrayList<>();
        for (IArchimateModel model : open) {
            if (id.equals(model.getId())) {
                matches.add(model);
            }
        }
        if (matches.size() == 1) {
            call.model = matches.get(0);
            call.resolution = isDefault ? SESSION_DEFAULT : ADDRESSED;
            return;
        }
        String openList = open.isEmpty() ? "No model is open." : "Open models: " + describeAll(open);
        if (matches.isEmpty()) {
            call.refusal = isDefault
                    ? new ModelAccessException("This session's default model (id " + id + ") is not open in "
                            + "Archi: it has been closed. Nothing was applied.", ErrorCode.MODEL_NOT_OPEN,
                            openList, "Reopen it in Archi, or pass modelId to name an open model (see "
                                    + "details or get-models).", null)
                    : new ModelAccessException("Model " + CallerText.quoted(id) + " is not open in Archi. Nothing was applied.",
                            ErrorCode.MODEL_NOT_OPEN, openList, "Pass the modelId of an open model (see "
                                    + "details or get-models), or open the model in Archi first.", null);
            return;
        }
        StringBuilder copies = new StringBuilder("Copies: ");
        for (int i = 0; i < matches.size(); i++) {
            IArchimateModel copy = matches.get(i);
            copies.append(i == 0 ? "" : "; ").append(i + 1).append(". ")
                    .append(copy.getFile() != null ? "at " + copy.getFile().getPath() : "(unsaved, no file)");
        }
        call.refusal = new ModelAccessException("Model id '" + id + "' is open " + matches.size()
                + " times: a copied file keeps its model id, and id and name are the same for every copy, "
                + "so the call cannot tell which one is meant and acted on none of them.",
                ErrorCode.MODEL_COPIES_AMBIGUOUS, copies.toString(),
                "Close the copies you do not mean in Archi (save them first if they hold changes you want "
                        + "to keep), then retry.", null);
    }

    /** The open models, each observed so that a model open but never active has an epoch too. */
    private List<IArchimateModel> open() {
        List<IArchimateModel> listed = openModels.get();
        List<IArchimateModel> open = new ArrayList<>();
        if (listed != null) {
            for (IArchimateModel model : listed) {
                if (model != null && !containsInstance(open, model)) {
                    epochOf.applyAsLong(model);
                    open.add(model);
                }
            }
        }
        return open;
    }

    private static boolean containsInstance(List<IArchimateModel> models, IArchimateModel model) {
        for (IArchimateModel each : models) {
            if (each == model) {
                return true;
            }
        }
        return false;
    }

    private static String describeAll(List<IArchimateModel> models) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < models.size(); i++) {
            IArchimateModel model = models.get(i);
            sb.append(i == 0 ? "" : "; ").append(i + 1).append(". '")
                    .append(model.getName() != null ? model.getName() : "(unnamed)")
                    .append("' (id ").append(model.getId()).append(')');
        }
        return sb.toString();
    }
}
