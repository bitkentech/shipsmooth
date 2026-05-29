# Improving Code Quality with LLMs: A Reference Guide
(based on a chat with an AI)
---

## Part I — The Relevant Mathematics and Behaviours of LLMs

### The Core Model: A Conditional Density

An LLM is not a knowledge store or a rule-follower. It is a probability
distribution over token sequences: P(output | context). Every technique for
improving code quality — instructions, examples, feedback loops — works by
manipulating this distribution. Nothing forces a particular output; everything
merely shifts its probability. This is the foundational fact from which
everything else follows.

### The Latent-Variable View of In-Context Behaviour

The most useful model of how an LLM responds to context is Bayesian. Think of
the model as carrying a vast implicit mixture over latent "modes" θ — styles,
conventions, paradigms — accumulated from training. Generation marginalises
over this mixture: P(output | prompt) = ∫ P(output | θ) P(θ | prompt) dθ. Any
contextual input — a skill file, an example, a system prompt — acts as evidence
that sharpens the posterior P(θ | prompt), concentrating probability mass on one
region of latent space and suppressing others. A skill file doesn't teach the
model; it selects a mode the model already has. The corollary is hard: if the
target style or paradigm is not well-represented in the weights, there is no
basin to concentrate onto, and the skill will underperform no matter how well
written.

### Autoregressive Path Dependence

Tokens are generated left to right, each conditioned on everything preceding
it. This makes early structural commitments sticky. Once a particular
architectural shape, module boundary, or design pattern is in the context, the
model is under coherence pressure to preserve and elaborate it. Restructuring it
later requires fighting that pressure. This is not a preference — it is a
mechanical consequence of how generation works. Good architecture and design
decisions made early in natural language, before a line of code is generated,
are the cheapest possible interventions. The same decisions made after the code
exists are far more expensive to act on.

### Generator-Verifier Asymmetry

Generating correct, high-quality output in one forward pass is hard. Evaluating
whether a piece of existing code violates a rule is, for many rule classes, far
easier — and for deterministic rules, the evaluation is perfect and sound. This
asymmetry is a fundamental property of the problem class, not a quirk of LLMs.
It means that the verify-and-repair loop is not a workaround for model weakness
but the optimal architecture: generate, verify with a sound oracle, repair
against the oracle's signal, re-verify. The LLM is the generator and repairer;
the deterministic tool is the verifier. Conflating these roles — asking the
model to self-certify its own conformance to mechanical rules — discards a
structural advantage.

### Attention Budget and the Cost of Context

Attention is a finite, normalised resource. Every token in the context window
competes for it. Packing a prompt with rules the model already knows,
descriptions of checks a linter will catch for free, or elaborate procedural
scaffolding that isn't directly load-bearing all dilute attention away from what
matters. The effective conditioning at any point in generation depends on the
signal-to-noise ratio of the context, not its raw length. Longer is not
stronger.

### Influence Decay

A skill file or system prompt is read once. As generation proceeds, the model's
own output fills the context and the original conditioning recedes. The
instructions that were strongest at the start of generation are weakest by the
end of a long session. This is not a bug in any implementation — it is a
consequence of context-window mechanics and the model's tendency to condition on
its own recent output. In long, multi-step generations, the default prior (the
dominant mode from training) gradually reasserts itself unless the conditioning
is re-injected at phase boundaries.

### The RLHF Conservative-Edit Bias

Instruction-tuned models are trained with human feedback that rewards making the
smallest plausible edit that addresses a request — preserving the user's
existing code, avoiding unsolicited restructuring, completing without
disruption. This is generally useful behaviour, but it creates a systematic bias
against large refactoring steps. The model will tend toward hill-climbing: a
sequence of small, safe, locally-improving edits that cannot escape a basin of
poor design. This is not timidity on the model's part; it is a learned prior
baked in by training. Overriding it requires explicit instruction, and —
critically — a clean-slate framing that removes the anchor of the existing code.

### Form Without Substance

Pattern completion operates on surface statistics. A model conditioned on a
Clean Architecture skill file will produce `usecases/` folders, interface
definitions, and dependency-injected constructors — because that surface form is
what the training distribution associates with the target. Whether those
structures actually enforce the Dependency Rule is a semantic property the model
cannot self-verify. The form can be correct while the substance is hollow. This
is the dominant failure mode of style-conditioning skills, and it is irreducible
without an external, semantically-grounded verifier.

### Error Compounding in Long Generations

Each token in a long generation carries some probability of being subtly wrong.
Over a large codebase generated in a single pass, these errors accumulate and
interact. A poor decision made at position N is elaborated and depended upon at
positions N+1 through N+10,000. This makes one-shot generation of large
codebases inherently fragile, and it means that decomposing a large task into
independently generated and verified units is not just organisational tidiness —
it is a direct mitigation of compounding error.

### Limitations That Follow Directly

Several important limitations fall out of the above as corollaries rather than
independent observations:

P(bad output) is never zero. Skill files and prompts reshape the density; they
cannot truncate the support. A deterministic gate that rejects non-conforming
outputs is qualitatively different from a probabilistic nudge toward conforming
ones.

The model cannot reliably self-certify conformance to mechanical rules. "Is this
method's cyclomatic complexity below the threshold?" has a deterministic answer
that a linter computes exactly. The model's answer is a guess shaped by its
training distribution, not a computation.

Decay is the default, not the exception. In any workflow that runs for more than
a few turns, influence from early context should be assumed to be eroding unless
actively compensated.

The conservative-edit prior is permanent. It cannot be fine-tuned away without
losing useful properties. It must be worked around by prompt design.

---

## Part II — The Overarching Architecture

Given these properties, a single architectural pattern falls out almost
necessarily:

**Steer at generation → Generate → Deterministically verify → LLM repair
conditioned on verifier output → Re-verify.**

The skill or instruction steers the generative distribution toward the target
mode before any code is written. The model generates. A deterministic tool
verifies the output against the closed set of rules it encodes with perfect
soundness. The model repairs against the specific, non-hallucinated signal the
tool provides. The tool re-verifies. This loop runs until the deterministic gate
passes.

This architecture assigns each component to what it is actually good at: the
model for generation and judgment over open-ended quality dimensions; the
deterministic tool for sound verification of the mechanically checkable. Neither
is asked to do the other's job.

Upstream of this loop sits a stage that is often omitted: **design-level
reasoning in natural language before any code is generated**. Because
autoregressive path dependence makes early structural commitments sticky and
expensive to undo, the highest-leverage intervention is getting the architecture
right while it is still fluid — in prose, pseudocode, or interface sketches —
before the committed token stream begins. This is where the "quality first"
principle belongs: not as gold-plating early code, but as front-loading the
structural decisions into the cheap, malleable representation.

The full pipeline is therefore: **Design (natural language) → Steer
(skill/prompt) → Generate → Deterministically verify → Repair loop → Human
review gate**.

---

## Part III — Suggestions, Each Grounded in a Principle

**1. Separate what the skill file governs from what the deterministic tool
governs.**

Skills should cover only what no deterministic tool can encode: naming
philosophy, abstraction fit, whether a design communicates intent, whether
decomposition matches the problem structure. Rules that are mechanically
checkable — method length, cyclomatic complexity, import direction, coverage
thresholds — belong in the linter or static analyser, not in the skill file.
Restating them in the skill file wastes attention budget and creates a false
confidence that the model will self-enforce them. The deterministic tool is the
source of truth; the skill should not duplicate it.

*Grounded in: attention budget as finite resource; the model's inability to
self-certify conformance to mechanical rules; density-not-support — the skill
shifts probability, the linter truncates feasibility.*

---

**2. Treat the generate-verify-repair loop as the fundamental unit of the
pipeline, not an afterthought.**

The loop is not a fallback for when the model gets it wrong. It is the optimal
architecture given the generator-verifier asymmetry. Build it explicitly: the
model generates, a sound external oracle verifies, the oracle's output is fed
back to the model as repair context, the oracle verifies again. Never ask the
model to assess its own conformance and proceed on that self-assessment.

*Grounded in: generator-verifier asymmetry; form-without-substance failure mode;
P(bad output) > 0 regardless of prompt quality.*

---

**3. Do the structural, architectural thinking in natural language before
generating code.**

Decisions about module boundaries, dependency direction, layer structure, and
decomposition should be made while they are still fluid — in prose or design
sketches — and locked before code generation begins. Attempting to make these
decisions through code refactoring afterward is dramatically more expensive and
is resisted by the model's coherence pressure.

*Grounded in: autoregressive path dependence — early commitments are sticky and
downstream tokens elaborate them; error compounding — poor structural decisions
made early propagate through the entire generation.*

---

**4. To take large refactoring steps, use clean-slate re-derivation rather than
anchored editing.**

When a significant structural improvement is needed, frame the task as "given
these requirements, what is the ideal design?" rather than "improve this code."
The former removes the existing code as an anchor; the latter puts it in context
as the dominant conditioning signal, triggering the conservative-edit prior.
Occasionally, the strongest move is to remove the old code from the prompt
entirely and treat it only as a source of requirements.

*Grounded in: RLHF conservative-edit bias — the model is trained to minimise
diffs; the anchoring effect of existing code in context suppressing large
structural moves.*

---

**5. Verifier strength determines the maximum safe step size in refactoring.**

Big refactoring steps are high-variance: any one of them might produce worse
code, and validating a wholesale restructure is harder than validating a
one-line edit. What makes a large step safe is a strong oracle — a comprehensive
test suite combined with deterministic static analysis — that can catch
regressions reliably. Without strong verification, you are forced into small,
hill-climbing iterations regardless of what the model could theoretically
produce. Investing in the verifier is what licenses the large-step strategy.

*Grounded in: generator-verifier asymmetry; the fact that large steps are
high-variance samples requiring sound verification to be safely taken; the
conservative-edit bias and local-minima problem that small steps cannot escape.*

---

**6. Re-inject conditioning at phase boundaries in long sessions; do not assume
the initial prompt holds.**

For any workflow that spans multiple phases or a large generation, the
invariants, quality targets, and architectural constraints should be re-stated at
the beginning of each major phase, not just at the start of the session. A
compact checklist re-injected at each task boundary is more robust than a
comprehensive skill file read once at session start.

*Grounded in: influence decay — the original conditioning recedes as the model's
own output fills the context; the default training prior gradually reasserts
itself without re-grounding.*

---

**7. Use contrastive exemplars (target plus anti-target) rather than rules alone
in skill files.**

An example of correct code conditions the model in output space, which is where
generation happens. A rule describing the property of correct code requires an
extra inference step from instruction to behavior. An anti-target paired with
the target sharpens the decision boundary further than the target alone. The
anti-pattern tables and before/after pairs in a good skill file are doing more
work per token than any equivalent amount of prose guidance.

*Grounded in: few-shot demonstration operating in the same representational
space as generation; contrastive conditioning narrowing the latent posterior more
sharply than positive evidence alone; rules requiring lossier
instruction-to-behavior inference.*

---

**8. For large scope, decompose first; apply quality-first generation per unit,
not to the whole.**

One-shot generation of a large codebase in a single pass is inherently fragile
due to error compounding. The right decomposition is not just organisational: it
is the mechanism that prevents early errors from propagating into everything
downstream. Identify independent units, generate each with full quality
conditioning, verify each deterministically, then integrate. The "quality first"
principle applies at the level of each unit after the decomposition is done, not
across the whole codebase before decomposition happens.

*Grounded in: error compounding in long generations; autoregressive path
dependence making early errors expensive; the overarching architecture's
integration step as a separate, verified phase.*

---

**9. Use higher sampling diversity when exploring design options; use lower
temperature for convergent implementation.**

These are different problems with different optimal regimes. Design exploration
benefits from sampling multiple structurally different alternatives (high
diversity, best-of-N over designs), because the goal is to escape local minima
in design space and find globally better structure. Convergent implementation —
once the design is locked — benefits from low variance, staying close to the
established pattern and reducing the chance of arbitrary drift. Applying the
same regime to both wastes either the exploratory freedom of the design phase or
the precision of the implementation phase.

*Grounded in: the latent-variable model — exploration samples broadly from the
posterior over modes; convergence concentrates on the selected mode; local minima
in design space requiring genuine diversity to escape.*

---

**10. Do not encode volatile details as high-frequency tokens in skill files.**

Any specific version string, path, or tool invocation that appears many times in
a skill file gets heavily reinforced by the repetition and becomes the model's
strongest copy-completion target for that context. When the detail changes — a
version bumps, a path moves — the model will confidently emit the stale value.
Volatile specifics should appear exactly once, in a clearly designated place, and
be referenced abstractly elsewhere.

*Grounded in: pattern completion operating on surface statistics — high-frequency
tokens in context become strong priors in output; the inability of the model to
reason about staleness of its own conditioning.*
