# ADR-0007: Self-evolving executable world models

## Status

Accepted for the bounded first implementation.

## Context

Tamaki already persists active-inference beliefs and governs repository-bound
artificial organisms, but its belief state does not encode an inspectable,
executable causal dynamics model. Free-form LLM prose is not a stable world
model, while allowing an LLM to rewrite simulator code would combine proposal,
execution, and selection authority.

## Decision

Represent the canonical mutation state as a constrained EDN AST with stocks,
flows, auxiliaries, parameters, units, and a small arithmetic expression
language. Project every selected model into the pinned
`kotoba-lang/org-oasis-open-xmile` data model. That shared capability owns
OASIS document validation, equation semantics, Euler execution, and XMILE 1.0
emission; Tamaki owns only mutation, observation loss, model-complexity
selection, and its authority boundary.

Candidate generation and candidate selection are separate:

1. an LLM, symbolic search process, or human proposes typed mutations at the
   parameter, equation, or structure level;
2. Tamaki rejects unknown operators, dangling references, and algebraic cycles;
3. Tamaki executes one-step Euler forecasts over recorded observations;
4. Tamaki scores prediction loss plus a complexity penalty;
5. the incumbent wins unless a candidate exceeds a minimum improvement;
6. explicit `--execute` writes the canonical AST, XMILE projection, and the
   full selection/falsification receipt.

World-model acceptance is a belief-artifact update, not authority to change a
repository or cause an external effect. Existing evolution, capability,
review, canary, homeostasis, and human gates continue to govern action.

## Consequences

- The model is persistent, executable through the shared XMILE capability,
  inspectable, and safe to replay.
- LLM hypotheses are falsifiable data rather than trusted code.
- Complexity pressure makes single-observation exception variables costly.
- XMILE is a projection in this first version; arbitrary imported XMILE is not
  accepted as a Tamaki mutation. Any future import path must pass the shared
  secure parser and validator, then separately satisfy Tamaki's typed mutation
  and authority boundary.
- Neural residual learning, Bayesian ensembles, multi-step calibration, and
  automatic candidate generation remain follow-up work.
