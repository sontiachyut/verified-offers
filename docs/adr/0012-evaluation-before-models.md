# ADR 0012: measured lexical and claim-extraction baselines

Date: 2026-09-16. Status: accepted for implementation.

Keep the default retrieval system lexical (OpenSearch BM25 title match), with
authoritative verification unchanged. Create a checked-in synthetic electronics
catalog and separately authored graded query judgments before running retrieval.
Do not generate relevance labels from search order or verifier output. Record
nDCG@10, Recall@10 and per-query rankings, including failures; this small synthetic
set is a regression benchmark, not human user-study or general search-quality proof.

Add a conservative deterministic price extractor before any optional paid model.
Accept one unambiguous explicit USD amount, return its exact evidence span and
minor units, or abstain. Identity is supplied separately and authorized as usual;
text never controls tenant, merchant, source version, freshness or stock. A proposed
claim always passes the existing authoritative verifier before a VERIFIED result.
No free-form answer, tool execution, URL fetching or write path is available.

Freeze a development and held-out set with manually specified expected outputs
before implementing/evaluating the baseline. The labels are authored for this
project, not an external independent human annotation study. Include ambiguity,
foreign currencies, discounts, unsupported formats and instruction-like text.
Measure extraction precision/recall, abstention coverage, unsafe false proposals,
verification rejection and latency; zero paid model calls means zero inference
API spend, not zero infrastructure cost. Report limitations rather than tune on
holdout until the score looks good. Optional AI adoption requires a new measured
comparison and separate approval for paid credentials/calls.
