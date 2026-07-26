# Tamaki federation

Each EDN file is an organization-scoped `OrganismSpec`. Paths and environment
variable names are public configuration; credentials and private observations
must remain in the referenced state root or secret broker.

The specs intentionally separate objective, responsibility, authority, budget,
actors, loops, Radicle identity, and GitHub organization. The federation
observatory may read projections from every state root but has no mutation
authority.

Concrete identities belong in the local control repository, not in this
public package. Personal and organization organisms may share explicitly
accepted artifacts, but identity never grants implicit cross-organism
authority.
