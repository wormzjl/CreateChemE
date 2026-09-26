# Pre-campaign fixture correction, revision 2

Revision 1 stopped before validation: its concurrency fixtures included a
neural-rescued TRAIN label whose cold classical solve did not qualify. Its
frozen source, registration, failed check and exported models remain intact.

Revision 2 changes only the numerical-check fixtures and their registration.
Select the first ten unique accepted original classical TRAIN observations
sorted by tray count and ID, with 2–6 trays and canonical inputs present among
the 805 eligible training cases. Verify the original observations against the
retained Gen2 manifest. The numerical check on these fixtures passed with ten
simultaneous workers and bit-identical serial/parallel accepted profiles.

Reuse revision 1's three exported models and parity evidence byte for byte.
Reuse its exclusion inventory, candidate pool and 252 prospective test inputs
byte for byte. The 405 validation inputs, selection rule, benchmark policy,
worker scheduler, solver implementation and campaign order are unchanged.
No scientific campaign had started when this amendment was registered.

Run `native_checkpoint_selection_v2.py` in this order: `prepare`, `export`,
`parity`, `validation`, `select`, `test`, `report`, `seal`. The retained
revision 1 driver documents the superseded registration; do not execute its
campaign commands. Revision 2 retains its full provenance chain in the archive.
