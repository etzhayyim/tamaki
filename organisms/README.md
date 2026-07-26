# Tamaki federation

Each EDN file is an organization-scoped `OrganismSpec`. Paths and environment
variable names are public configuration; credentials and private observations
must remain in the referenced state root or secret broker.

The specs intentionally separate objective, responsibility, authority, budget,
actors, loops, Radicle identity, and GitHub organization. The federation
observatory may read projections from every state root but has no mutation
authority.

`com-junkawasaki` is the personal organism. It may share explicitly accepted
artifacts with organization organisms, but personal identity never grants
implicit authority inside those organizations.
