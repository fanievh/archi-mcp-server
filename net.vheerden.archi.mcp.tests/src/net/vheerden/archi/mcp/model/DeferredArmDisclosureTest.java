package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * The rule a deferred response follows, and the one family it cannot reach.
 *
 * <h2>The rule</h2>
 *
 * <p>A tool measures the same things whichever arm it is on: the router computes the routes a
 * queued call will lay down, and the quality loop ranks the attempt a human has not yet approved.
 * So a deferred arm <em>keeps the measurement and rescopes the remedy</em>. It never asserts the
 * write landed, and it names only a recovery that exists on that arm — {@code undo} once applied,
 * {@code end-batch} while queued, and no tool at all while awaiting approval, where the agent
 * cannot approve or reject its own change.</p>
 *
 * <p>Rescoping is not withdrawing: a queued re-route still counted its crossings, so the counts are
 * owed. The exception is a claim about <em>coverage</em> rather than about state — where the
 * comparison never ran, the honest disclosure is an abstention.</p>
 *
 * <h2>The family that has no such arm</h2>
 *
 * <p>The four spacing tools never store a proposal. Measured on the shipped source: none of their
 * control loops consults the approval gate, so a response from them can never be a proposal and an
 * approval-tense sentence written for them would be unreachable code — worse, a sentence claiming
 * to describe a state the tool cannot produce. This is pinned rather than assumed, and pinned with
 * a positive control: a probe that finds no gate anywhere reads exactly like a probe that finds no
 * gate here.</p>
 *
 * <p>If a spacing tool ever gains an approval gate, this test fails and says what the author owes:
 * the abstention that family carries on its queued arm, reworded for who unblocks it.</p>
 */
public class DeferredArmDisclosureTest {

	private static final String IMPL_FILE =
			"net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/ArchiModelAccessorImpl.java";

	private static final String MUTATION_MODEL_DOC = "docs/mutation-model.md";

	/**
	 * The tools whose control loops the census below claims have no approval arm, and how many
	 * declarations each has.
	 *
	 * <p>{@code adjustViewSpacing} is the fourth member and the one that never had a gate to lose,
	 * rather than one that shed it: a tool that never acquired an approval arm belongs in the same
	 * census as three that were checked for one, or the census is not a census. It has a single
	 * declaration where the other three are delegating pairs — counted, not guessed, because the
	 * exactness is what stops a parse reading one half of a pair and answering for both.</p>
	 */
	private static final Map<String, Integer> SPACING_METHODS = Map.of(
			"applyElementSpacingRecommendations", 2,
			"applyGroupSpacingRecommendations", 2,
			"applySpacingRecommendations", 2,
			"adjustViewSpacing", 1);

	/**
	 * A method known to gate on approval, so the probe proves it can see one.
	 *
	 * <p>Without this, a parse that silently matched nothing — a renamed method, a changed
	 * signature shape — would report the spacing family as ungated and be believed.</p>
	 */
	private static final String GATED_METHOD = "layoutWithinGroup";

	private static final String GATE = "isApprovalRequired";
	private static final String STORE = "storeAsProposal";

	@Test
	public void theProbeMustBeAbleToSeeAnApprovalGateBeforeItReportsTheAbsenceOfOne() {
		List<String> bodies = bodiesOf(GATED_METHOD);

		assertEquals("the parse must find " + GATED_METHOD + "'s single declaration, or it "
				+ "certifies nothing about any other method", 1, bodies.size());
		String body = String.join("\n", bodies);
		assertTrue("a parse this shallow proves nothing: " + body.length() + " chars",
				body.length() > 500);
		assertTrue(GATED_METHOD + " stores a proposal behind the approval gate; a probe that "
				+ "cannot see it there cannot be trusted to report its absence elsewhere",
				body.contains(GATE) && body.contains(STORE));
	}

	@Test
	public void theSpacingFamilyHasNoApprovalArmToDiscloseOn() {
		List<String> gated = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : SPACING_METHODS.entrySet()) {
			String method = entry.getKey();
			List<String> bodies = bodiesOf(method);
			assertEquals("the parse must find every overload of " + method + " — reading one of a "
					+ "delegating pair answers the question about half the code", 
					(int) entry.getValue(), bodies.size());
			String body = String.join("\n", bodies);
			assertTrue("the parse found no real body for " + method + " — a census that matches "
					+ "nothing reads exactly like a clean one", body.length() > 500);
			if (body.contains(GATE) || body.contains(STORE)) {
				gated.add(method);
			}
		}

		if (!gated.isEmpty()) {
			fail("A spacing tool now consults the approval gate: " + gated + ". Its response can "
					+ "therefore be a proposal, and on that arm the before/after snapshots describe "
					+ "the same unmutated view — the comparison is structurally unavailable, "
					+ "exactly as it is on the queued arm. Give it the abstention its queued arm "
					+ "carries, reworded for what unblocks a real measurement there: the human's "
					+ "approval, not end-batch. Do not emit a rating-regression step on that arm; "
					+ "nothing was measured to regress.");
		}
	}

	/**
	 * The same conclusion, pinned on the mechanism rather than on two identifiers.
	 *
	 * <p>The test above greps each spacing body for {@code isApprovalRequired} and
	 * {@code storeAsProposal}. That is a check on how the gate is spelled <em>inside these
	 * methods</em>, and it would stay green if the gate arrived indirectly — through a shared
	 * private helper the spacing methods call, which is exactly the refactor this codebase has
	 * already performed on other guard families. A probe that can be satisfied by moving code one
	 * frame away is not a probe.</p>
	 *
	 * <p>So this pins what actually decides the question. {@code MutationResult.isProposal()} is
	 * true if and only if {@code proposalContext} is non-null, and only the three-argument
	 * constructor can set it. A spacing tool can therefore reach the approval arm in exactly two
	 * ways: construct a three-argument {@code MutationResult} itself, or return one it obtained
	 * from somewhere else. Both are refused here — every {@code return} of a result in these
	 * bodies must be a two-argument construction or a delegation to another overload in the same
	 * checked set — so the conclusion survives the gate being moved, renamed, or wrapped.</p>
	 */
	@Test
	public void noSpacingToolCanReturnAResultThatCarriesAProposal() {
		Pattern returnsResult = Pattern.compile(
				"return\\s+(new\\s+MutationResult<>\\s*\\(|[A-Za-z_][A-Za-z0-9_]*\\s*\\()");
		Pattern threeArgCtor = Pattern.compile(
				"new\\s+MutationResult<>\\s*\\([^;]*,[^;]*,[^;]*\\)", Pattern.DOTALL);

		List<String> offenders = new ArrayList<>();
		for (String method : SPACING_METHODS.keySet()) {
			for (String body : bodiesOf(method)) {
				String code = stripComments(body);

				if (threeArgCtor.matcher(code).find()) {
					offenders.add(method + " constructs a three-argument MutationResult, which is "
							+ "the only thing that can set a ProposalContext");
				}
				Matcher m = returnsResult.matcher(code);
				while (m.find()) {
					String returned = m.group(1).trim();
					if (returned.startsWith("new")) {
						continue;                          // two-argument construction, checked above
					}
					String callee = returned.substring(0, returned.length() - 1).trim();
					if (!SPACING_METHODS.containsKey(callee)) {
						offenders.add(method + " returns a MutationResult from " + callee
								+ "(), which this census does not check");
					}
				}
			}
		}

		if (!offenders.isEmpty()) {
			fail("A spacing tool can now return a result carrying a ProposalContext, so it HAS an "
					+ "awaiting-approval arm and owes that arm a disclosure. On it, the before and "
					+ "after snapshots describe the same unmutated view — the comparison is "
					+ "structurally unavailable, exactly as on the queued arm — so it owes the "
					+ "abstention its queued arm carries, reworded for what unblocks a real "
					+ "measurement there: the human's approval, not end-batch. It must NOT emit a "
					+ "rating-regression step on that arm; nothing was measured to regress.\n  "
					+ String.join("\n  ", offenders));
		}
	}

	/** Comments are prose, not code: a javadoc naming a constructor must not trip the census. */
	private static String stripComments(String java) {
		return java.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
	}

	/**
	 * The contract, written where the next author will meet it.
	 *
	 * <p>A rule that lives only in the shape of six call sites is re-learned by the seventh tool
	 * thirty days later, which is how the batched arm of {@code auto-route-connections} came to
	 * reuse four applied-arm sentences verbatim. The paragraph is guarded rather than merely
	 * written, because prose that nothing reads is prose that quietly goes.</p>
	 */
	@Test
	public void theDeferredArmRuleMustBePublishedInTheMutationModelDoc() {
		String doc = read(MUTATION_MODEL_DOC).toLowerCase(Locale.ROOT);

		assertTrue(MUTATION_MODEL_DOC + " must state that a deferred arm keeps what it measured",
				doc.contains("keeps the measurement"));
		assertTrue(MUTATION_MODEL_DOC + " must state that it rescopes the remedy rather than "
				+ "deleting the fact", doc.contains("rescopes the remedy"));
		assertTrue(MUTATION_MODEL_DOC + " must forbid asserting applied state on a deferred arm",
				doc.contains("never asserts applied state"));
		assertTrue(MUTATION_MODEL_DOC + " must require the remedy to name a recovery that exists "
				+ "on the arm", doc.contains("a recovery that exists"));
	}

	/**
	 * The limit on "keeps the measurement", published where the next author will meet it.
	 *
	 * <p>The rule above is read as license to re-tense every number, and for most tools it is: the
	 * command is frozen at prepare and will do exactly what the prepare-time report describes. Two
	 * shapes break that. A command that re-reads the model in {@code execute()} describes a
	 * different model than the one it will act on, and an identifier the approval rebuild re-mints
	 * is not merely uncertain but guaranteed wrong. Both must be omitted rather than restated, and
	 * the paragraph has to say so — the tool most likely to get it wrong is the next one, written
	 * by someone who read only the first clause.</p>
	 *
	 * <p>Guarded in both directions: the omission rule must be stated, and the reason a frozen
	 * command keeps its count must be stated beside it. A paragraph carrying only the first half
	 * reads as a licence to withdraw every deferred measurement.</p>
	 */
	@Test
	public void theDeferredArmRuleMustBoundWhatMayBeStatedAsMeasured() {
		String doc = read(MUTATION_MODEL_DOC).toLowerCase(Locale.ROOT);

		assertTrue(MUTATION_MODEL_DOC + " must limit a deferred arm to what the queued command "
				+ "will actually do", doc.contains("only what the command it queued will actually do"));
		assertTrue(MUTATION_MODEL_DOC + " must name the re-deriving command as one of the two "
				+ "shapes that lose the right to state a measurement",
				doc.contains("re-derives at execute"));
		assertTrue(MUTATION_MODEL_DOC + " must name the re-minted id as the other",
				doc.contains("re-mint"));
		assertTrue(MUTATION_MODEL_DOC + " must require omission rather than a future-tense "
				+ "restatement", doc.contains("omits it rather than restating it in the future tense"));
		assertTrue(MUTATION_MODEL_DOC + " must also say that a command frozen at prepare KEEPS "
				+ "its count — a rule stating only the omission reads as licence to withdraw "
				+ "every deferred measurement", doc.contains("frozen at prepare"));
	}

	@Test
	public void theDeferredArmRuleMustNameWhatRecoversEachArm() {
		String doc = read(MUTATION_MODEL_DOC);

		assertTrue("the applied arm's recovery must be named", doc.contains("undo"));
		assertTrue("the queued arm's recovery must be named", doc.contains("end-batch"));
		assertFalse("the awaiting-approval arm must not be given a tool that cannot recover it — "
				+ "no MCP call approves or rejects a proposal",
				doc.contains("approval arm with undo") || doc.contains("undo to recover a proposal"));
	}

	// ---- parsing --------------------------------------------------------------------------------

	/**
	 * Every overload of {@code methodName}, brace-matched, concatenated.
	 *
	 * <p>Overloads are unioned rather than picked: a four-argument convenience that delegates to a
	 * five-argument body is one tool as far as the approval gate is concerned, and reading only one
	 * of the pair would answer the question about half the code.</p>
	 */
	private static List<String> bodiesOf(String methodName) {
		List<String> lines = List.of(read(IMPL_FILE).split("\n", -1));
		List<String> bodies = new ArrayList<>();
		for (int i = 0; i < lines.size(); i++) {
			if (!isDeclarationOf(lines, i, methodName)) {
				continue;
			}
			StringBuilder body = new StringBuilder();
			int depth = 0;
			boolean opened = false;
			for (int j = i; j < lines.size(); j++) {
				String line = lines.get(j);
				body.append(line).append('\n');
				for (char c : line.toCharArray()) {
					if (c == '{') {
						depth++;
						opened = true;
					} else if (c == '}') {
						depth--;
					}
				}
				if (opened && depth <= 0) {
					break;
				}
			}
			bodies.add(body.toString());
		}
		return bodies;
	}

	/**
	 * True when line {@code i} opens a declaration of {@code methodName}.
	 *
	 * <p>The impl wraps its long return types, so the name sits on its own line under the type and
	 * a naive one-line regex over {@code MutationResult<…> name(} finds nothing at all. The
	 * discriminator is therefore the name followed by an open parenthesis, with the previous line
	 * carrying a modifier or a return type — never a call, which is preceded by {@code =},
	 * {@code return} or a dot on the same line.</p>
	 */
	private static boolean isDeclarationOf(List<String> lines, int i, String methodName) {
		String line = lines.get(i);
		int at = line.indexOf(methodName + "(");
		if (at < 0) {
			return false;
		}
		String beforeName = line.substring(0, at);
		if (beforeName.endsWith(".")) {
			return false;                       // a call on a receiver, not a declaration
		}
		if (beforeName.isBlank()) {
			// The wrapped shape: the long return type sits on the line above and the name alone
			// on this one. A delegating CALL wrapped the same way always has 'return ' or '= '
			// before it on its own line, so a blank prefix here is a declaration.
			String previous = i > 0 ? lines.get(i - 1) : "";
			return previous.contains("public ") || previous.contains("private ")
					|| previous.contains("protected ");
		}
		// The unwrapped shape: modifier, return type and name on one line.
		return !beforeName.contains("return ") && !beforeName.contains("=")
				&& (beforeName.contains("public ") || beforeName.contains("private ")
						|| beforeName.contains("protected "));
	}

	private static String read(String relativePath) {
		Path path = locate(relativePath);
		try {
			return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to read " + path, e);
		}
	}

	/** Walks up from the working directory until the repo-relative path resolves. */
	private static Path locate(String relativePath) {
		Path here = Path.of("").toAbsolutePath();
		Map<String, Path> tried = new LinkedHashMap<>();
		for (Path dir = here; dir != null; dir = dir.getParent()) {
			Path candidate = dir.resolve(relativePath);
			tried.put(candidate.toString(), candidate);
			if (Files.exists(candidate)) {
				return candidate;
			}
		}
		throw new IllegalStateException("Could not locate " + relativePath
				+ " from " + here + "; tried " + tried.keySet());
	}
}
