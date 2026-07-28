# Etzhayyim Tamaki family

`etzhayyim-family.edn` is the public membership rule for the Etzhayyim Tamaki
family. Every repository owned by the organization projects to exactly one
repository-bound artificial organism (AO):

```text
1 repository = 1 artificial organism = 1 AO
```

The `tamaki` repository is the representative AO. Representation permits
observation, coordination, and presentation of family-wide aggregate state; it
does not grant mutation authority over another repository-bound AO.

The live repository inventory is written to the local ignored state root by
`tamaki family sync --execute`. The resident supervisor repeats this
deterministic reconciliation in its bridge/observation lane (five minutes by
default), so repository creation and archival update family state without an
LLM or token spend. Credentials, private observations, issues, and
organization-specific control data never enter this public package.
